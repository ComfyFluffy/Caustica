package dev.upscaler.rt;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import dev.upscaler.UpscalerConfig;
import dev.upscaler.UpscalerMod;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VKCapabilitiesDevice;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDevicePresentIdFeaturesKHR;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPositionFetch.VK_KHR_RAY_TRACING_POSITION_FETCH_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPositionFetch.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_POSITION_FETCH_FEATURES_KHR;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_FEATURES_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_PROPERTIES_EXT;
import static org.lwjgl.vulkan.NVLowLatency2.VK_NV_LOW_LATENCY_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPresentId.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR;

/**
 * RT device bring-up. Enables the hardware ray-tracing device extensions and their
 * feature structs on vanilla's Blaze3D device at {@code vkCreateDevice} time.
 *
 * <p>Vanilla assembles a {@code VkPhysicalDeviceFeatures2} pNext chain from the
 * {@code Set<VulkanFeature>} (arg2) via {@code VulkanFeature.set} →
 * {@code findOrCreateStructInPNextChain} (dedup by sType), so {@code bufferDeviceAddress}
 * merges into the existing {@code VkPhysicalDeviceVulkan12Features} struct and the two
 * KHR structs are created fresh. BDA / descriptor-indexing / SPIR-V 1.4 are core on the
 * 1.4 device, so only three extension <i>names</i> are needed; the rest are feature enables.
 *
 * <p>Extension names are added to the device extension list separately; feature structs are added here.
 * Both are gated on the selected device actually supporting RT; if not, nothing is added
 * and the device comes up exactly as vanilla. This startup capability switch is intentionally
 * independent of {@code upscaler.rt.output}: output mode is a runtime work/display toggle,
 * while the device features must be present before that toggle can be flipped to RT later.
 */
public final class RtDeviceBringup {
    public static boolean enabledByProperty() {
        return UpscalerConfig.Rt.ENABLED.value();
    }

    /**
     * The device extensions RT needs (BDA/descriptor-indexing/SPIR-V 1.4 are core on 1.4).
     * {@code ray_tracing_position_fetch} lets the closest-hit read hit triangle vertex positions
     * ({@code gl_HitTriangleVertexPositionsEXT}) for the normal-map TBN, avoiding a positions buffer
     * plumbed through the geometry tables. Supported on all RTX GPUs (the project's target).
     */
    public static final List<String> RT_EXTENSIONS = List.of(
            VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
            VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
            VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
            VK_KHR_RAY_TRACING_POSITION_FETCH_EXTENSION_NAME);

    /**
     * OPTIONAL RT extensions: enabled only when the selected device supports them AND the gate is on, but
     * never required — a device lacking them still comes up RT-capable (unlike {@link #RT_EXTENSIONS}, whose
     * absence disables RT entirely). {@code VK_EXT_opacity_micromap} (any-hit opt, lever C): per-triangle
     * opacity micromaps let the hardware skip {@code world.rahit} on fully-opaque/transparent cutout micro-
     * triangles, so the alpha-test any-hit runs only on the foliage silhouette. Hardware-accelerated on RTX
     * 40-series and Blackwell; absent / software elsewhere, hence optional.
     */
    public static final List<String> OPTIONAL_RT_EXTENSIONS = List.of(
            VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);

    /**
     * NVIDIA Reflex. {@code VK_NV_low_latency2} adds no feature bits (function-only extension). Bundled with
     * {@code VK_KHR_present_id}: Reflex's latency markers carry a {@code presentID} that only correlates with
     * a specific present call when that present's {@code vkQueuePresentKHR} chains a matching
     * {@code VkPresentIdKHR} — which needs its own device feature bit (unlike low_latency2, which is
     * function-only).
     */
    public static final List<String> REFLEX_EXTENSIONS = List.of(
            VK_NV_LOW_LATENCY_2_EXTENSION_NAME, VK_KHR_PRESENT_ID_EXTENSION_NAME);

    private static volatile boolean rtRequested;
    private static volatile boolean ommEnabled; // VK_EXT_opacity_micromap actually enabled on the device
    private static volatile boolean reflexEnabled; // VK_NV_low_latency2 actually enabled on the device
    private static volatile boolean presentIdEnabled; // VK_KHR_present_id actually enabled on the device
    private static volatile int maxOpacity4StateSubdivisionLevel;
    private static boolean loggedUnavailable;

    private RtDeviceBringup() {
    }

    /** True once we have augmented a device creation to request RT (extensions + features). */
    public static boolean rtRequested() {
        return rtRequested;
    }

    /** True if {@code VK_EXT_opacity_micromap} was enabled on the device (gate on + device support). */
    public static boolean ommEnabled() {
        return ommEnabled;
    }

    /** True if {@code VK_NV_low_latency2} (Reflex) was enabled on the device (gate on + device support). */
    public static boolean reflexEnabled() {
        return reflexEnabled;
    }

    /** True if {@code VK_KHR_present_id} was enabled on the device (needed for Reflex marker/present correlation). */
    public static boolean presentIdEnabled() {
        return presentIdEnabled;
    }

    /** Hardware limit for 4-state opacity micromaps, populated by {@link #probe(VkDevice)}. */
    public static int maxOpacity4StateSubdivisionLevel() {
        return maxOpacity4StateSubdivisionLevel;
    }

    /** Optional extensions the gate wants AND the device supports — added but never required. */
    private static List<String> supportedOptionalExtensions(VulkanPhysicalDevice physicalDevice) {
        List<String> supported = new ArrayList<>();
        if (ommRequested()) {
            OPTIONAL_RT_EXTENSIONS.stream().filter(physicalDevice::hasDeviceExtension).forEach(supported::add);
        }
        if (reflexRequested()) {
            REFLEX_EXTENSIONS.stream().filter(physicalDevice::hasDeviceExtension).forEach(supported::add);
        }
        return supported;
    }

    private static boolean ommRequested() {
        return UpscalerConfig.Rt.Omm.ENABLED.value();
    }

    private static boolean reflexRequested() {
        return UpscalerConfig.Rt.Reflex.ENABLED.value();
    }

    private static String firstUnsupported(VulkanPhysicalDevice physicalDevice) {
        for (String ext : RT_EXTENSIONS) {
            if (!physicalDevice.hasDeviceExtension(ext)) {
                return ext;
            }
        }
        return null;
    }

    /** Standalone path: add RT extension names to the (mutable) arg0 list. */
    public static void addExtensions(List<String> augmentedExtensions, VulkanPhysicalDevice physicalDevice) {
        if (!enabledByProperty() || firstUnsupported(physicalDevice) != null) {
            return;
        }
        for (String ext : RT_EXTENSIONS) {
            if (!augmentedExtensions.contains(ext)) {
                augmentedExtensions.add(ext);
            }
        }
        for (String ext : supportedOptionalExtensions(physicalDevice)) {
            if (!augmentedExtensions.contains(ext)) {
                augmentedExtensions.add(ext);
            }
        }
    }

    /** Add the RT VulkanFeatures to arg2 after the matching extension names have been requested. */
    @SuppressWarnings("unchecked")
    public static void addFeatures(Args args, VulkanPhysicalDevice physicalDevice) {
        if (!enabledByProperty()) {
            return;
        }
        String missing = firstUnsupported(physicalDevice);
        if (missing != null) {
            if (!loggedUnavailable) {
                loggedUnavailable = true;
                UpscalerMod.LOGGER.warn("Ray tracing unavailable: device [{}] lacks {}", physicalDevice.deviceName(), missing);
            }
            return;
        }

        Set<VulkanFeature> features = new HashSet<>((Set<VulkanFeature>) args.get(2));
        VulkanPNextStruct asStruct = new VulkanPNextStruct(
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR,
                VkPhysicalDeviceAccelerationStructureFeaturesKHR.SIZEOF);
        VulkanPNextStruct rtStruct = new VulkanPNextStruct(
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR,
                VkPhysicalDeviceRayTracingPipelineFeaturesKHR.SIZEOF);
        // Ray-tracing position fetch (gl_HitTriangleVertexPositionsEXT in the closest-hit).
        VulkanPNextStruct posFetchStruct = new VulkanPNextStruct(
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_POSITION_FETCH_FEATURES_KHR,
                VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR.SIZEOF);
        // bufferDeviceAddress merges into vanilla's existing Vulkan12Features struct.
        features.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "bufferDeviceAddress",
                VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS));
        // Bindless entity textures: a runtime-sized sampler2D[] indexed non-uniformly in the hit shader,
        // with partially-bound + update-after-bind slots (a growing per-RenderType registry). Core on the
        // VK 1.4 device; just needs enabling alongside bufferDeviceAddress on the same struct.
        features.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "runtimeDescriptorArray",
                VkPhysicalDeviceVulkan12Features.RUNTIMEDESCRIPTORARRAY));
        features.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "shaderSampledImageArrayNonUniformIndexing",
                VkPhysicalDeviceVulkan12Features.SHADERSAMPLEDIMAGEARRAYNONUNIFORMINDEXING));
        features.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "descriptorBindingPartiallyBound",
                VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGPARTIALLYBOUND));
        features.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "descriptorBindingSampledImageUpdateAfterBind",
                VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGSAMPLEDIMAGEUPDATEAFTERBIND));
        // shaderInt64: the world hit shader uses uint64_t buffer-reference addresses (Int64 capability).
        features.add(new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "shaderInt64",
                VkPhysicalDeviceFeatures.SHADERINT64));
        features.add(new VulkanFeature(asStruct, "accelerationStructure",
                VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE));
        features.add(new VulkanFeature(rtStruct, "rayTracingPipeline",
                VkPhysicalDeviceRayTracingPipelineFeaturesKHR.RAYTRACINGPIPELINE));
        features.add(new VulkanFeature(posFetchStruct, "rayTracingPositionFetch",
                VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR.RAYTRACINGPOSITIONFETCH));

        // Optional: opacity micromaps (any-hit opt). Only when the gate is on AND the device advertises the
        // extension — its absence must not disable RT, so it is kept out of the mandatory feature set above.
        ommEnabled = ommRequested() && physicalDevice.hasDeviceExtension(VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);
        if (ommEnabled) {
            VulkanPNextStruct ommStruct = new VulkanPNextStruct(
                    VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_FEATURES_EXT,
                    VkPhysicalDeviceOpacityMicromapFeaturesEXT.SIZEOF);
            features.add(new VulkanFeature(ommStruct, "micromap",
                    VkPhysicalDeviceOpacityMicromapFeaturesEXT.MICROMAP));
        }

        // Optional: NVIDIA Reflex (VK_NV_low_latency2). Function-only extension, no feature struct to add.
        reflexEnabled = reflexRequested() && physicalDevice.hasDeviceExtension(VK_NV_LOW_LATENCY_2_EXTENSION_NAME);

        // Optional: VK_KHR_present_id (presentID<->present correlation for Reflex markers). Its absence must
        // not disable Reflex sleep/pacing itself — only marker correlation degrades.
        presentIdEnabled = reflexEnabled && physicalDevice.hasDeviceExtension(VK_KHR_PRESENT_ID_EXTENSION_NAME);
        if (presentIdEnabled) {
            VulkanPNextStruct presentIdStruct = new VulkanPNextStruct(
                    VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR,
                    VkPhysicalDevicePresentIdFeaturesKHR.SIZEOF);
            features.add(new VulkanFeature(presentIdStruct, "presentId",
                    VkPhysicalDevicePresentIdFeaturesKHR.PRESENTID));
        }

        args.set(2, features);

        rtRequested = true;
        UpscalerMod.LOGGER.info(
                "Ray tracing: enabling {}{}{} + features [bufferDeviceAddress, accelerationStructure, rayTracingPipeline"
                        + (ommEnabled ? ", opacityMicromap" : "") + "] on [{}]",
                RT_EXTENSIONS, ommEnabled ? " + " + OPTIONAL_RT_EXTENSIONS : "",
                reflexEnabled ? " + " + REFLEX_EXTENSIONS : "", physicalDevice.deviceName());
    }

    /**
     * Post-creation verification: confirm the RT entry points actually loaded on the new
     * device and log the RT pipeline / acceleration-structure limits. If this logs "OK",
     * the device truly came up RT-capable.
     */
    public static void probe(VkDevice device) {
        if (!rtRequested) {
            UpscalerMod.LOGGER.info("Ray tracing not requested; skipping RT probe");
            maxOpacity4StateSubdivisionLevel = 0;
            return;
        }
        try {
            VKCapabilitiesDevice caps = device.getCapabilities();
            boolean rtPipeline = caps.vkCreateRayTracingPipelinesKHR != 0L;
            boolean asBuild = caps.vkCmdBuildAccelerationStructuresKHR != 0L;
            boolean traceRays = caps.vkCmdTraceRaysKHR != 0L;
            if (!(rtPipeline && asBuild && traceRays)) {
                UpscalerMod.LOGGER.error(
                        "RT extensions enabled but entry points missing (rtPipeline={}, asBuild={}, traceRays={}) — RT bring-up FAILED",
                        rtPipeline, asBuild, traceRays);
                return;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps =
                        VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
                VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps =
                        VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default();
                rtProps.pNext(asProps.address());
                // Chain the OMM properties only when the feature is enabled (else the driver would ignore an
                // unrecognized struct, but keeping the chain clean matches the enabled feature set).
                VkPhysicalDeviceOpacityMicromapPropertiesEXT ommProps = null;
                if (ommEnabled) {
                    ommProps = VkPhysicalDeviceOpacityMicromapPropertiesEXT.calloc(stack).sType$Default();
                    asProps.pNext(ommProps.address());
                }
                VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
                props2.pNext(rtProps.address());
                VK12.vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), props2);

                UpscalerMod.LOGGER.info(
                        "RT bring-up OK — shaderGroupHandleSize={}, shaderGroupBaseAlignment={}, maxRayRecursionDepth={}; "
                                + "maxAS geometry/instance/primitive = {}/{}/{}",
                        rtProps.shaderGroupHandleSize(), rtProps.shaderGroupBaseAlignment(), rtProps.maxRayRecursionDepth(),
                        asProps.maxGeometryCount(), asProps.maxInstanceCount(), asProps.maxPrimitiveCount());
                if (ommProps != null) {
                    maxOpacity4StateSubdivisionLevel = ommProps.maxOpacity4StateSubdivisionLevel();
                    UpscalerMod.LOGGER.info(
                            "Opacity micromaps enabled — maxSubdivisionLevel 4-state={}, 2-state={}",
                            ommProps.maxOpacity4StateSubdivisionLevel(), ommProps.maxOpacity2StateSubdivisionLevel());
                } else {
                    maxOpacity4StateSubdivisionLevel = 0;
                }
            }
            if (reflexEnabled) {
                boolean sleepMode = caps.vkSetLatencySleepModeNV != 0L;
                boolean sleep = caps.vkLatencySleepNV != 0L;
                boolean marker = caps.vkSetLatencyMarkerNV != 0L;
                boolean timings = caps.vkGetLatencyTimingsNV != 0L;
                if (sleepMode && sleep && marker && timings) {
                    UpscalerMod.LOGGER.info(
                            "Reflex (VK_NV_low_latency2) entry points loaded — presentId={}", presentIdEnabled);
                } else {
                    UpscalerMod.LOGGER.error(
                            "Reflex extension enabled but entry points missing (sleepMode={}, sleep={}, marker={}, timings={})",
                            sleepMode, sleep, marker, timings);
                    reflexEnabled = false;
                }
            }
        } catch (Throwable t) {
            // A probe must never break device creation.
            UpscalerMod.LOGGER.error("RT probe threw; continuing without RT", t);
        }
    }
}
