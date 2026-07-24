package dev.comfyfluffy.caustica.rt;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VKCapabilitiesDevice;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDevicePresentIdFeaturesKHR;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
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
import static org.lwjgl.vulkan.KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayQuery.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_FEATURES_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_PROPERTIES_EXT;
import static org.lwjgl.vulkan.NVLowLatency2.VK_NV_LOW_LATENCY_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPresentId.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR;
import static org.lwjgl.vulkan.EXTRayTracingInvocationReorder.VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTRayTracingInvocationReorder.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_EXT;

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
 * and the device comes up exactly as vanilla. {@code caustica.rt} is read once here, at
 * {@code vkCreateDevice} time, before the device exists — flipping it later at runtime cannot add
 * device features to an already-created device, so a config change only takes effect on restart.
 */
public final class RtDeviceBringup {
    public static boolean enabledByProperty() {
        return CausticaConfig.Rt.ENABLED.value();
    }

    /**
     * The device extensions RT needs (BDA/descriptor-indexing/SPIR-V 1.4 are core on 1.4).
     * {@code ray_tracing_position_fetch} lets the closest-hit read hit triangle vertex positions
     * ({@code gl_HitTriangleVertexPositionsEXT}) for the normal-map TBN, avoiding a positions buffer
     * plumbed through the geometry tables. Supported on all RTX GPUs (the project's target).
     * {@code ray_query} lets fragment shaders (the world-overlay pass, e.g. block outline) issue inline
     * {@code rayQueryEXT} occlusion tests against the same TLAS the ray-tracing pipeline traces, without a
     * dedicated raygen dispatch. Same RTX-GPU support floor as the rest of this list.
     */
    public static final List<String> RT_EXTENSIONS = List.of(
            VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
            VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
            VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
            VK_KHR_RAY_TRACING_POSITION_FETCH_EXTENSION_NAME,
            VK_KHR_RAY_QUERY_EXTENSION_NAME);

    /**
     * Shader Execution Reordering (ratified EXT path) is optional: when the device lacks it, the world
     * raygen falls back to a NO_SER variant that traces with plain {@code TraceRay} instead of a hit
     * object, so its absence must not disable RT.
     */
    public static final List<String> SER_EXTENSIONS = List.of(
            VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME);

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
    private static volatile boolean serEnabled;
    private static volatile boolean ommEnabled; // VK_EXT_opacity_micromap actually enabled on the device
    private static volatile boolean reflexEnabled; // VK_NV_low_latency2 actually enabled on the device
    private static volatile boolean presentIdEnabled; // VK_KHR_present_id actually enabled on the device
    private static volatile boolean wideLinesEnabled; // VkPhysicalDeviceFeatures.wideLines actually enabled
    private static volatile float maxLineWidth = 1.0f; // device's lineWidthRange[1]; 1.0 unless wideLinesEnabled
    private static volatile int overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_1_BIT; // capped to the device's framebufferColorSampleCounts
    private static volatile int maxOpacity4StateSubdivisionLevel;
    private static volatile int computeQueueFamilyIndex = -1;
    private static volatile int computeQueueIndex = -1;
    private static boolean loggedUnavailable;

    private static final VulkanPNextStruct AS_FEATURES_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR,
            VkPhysicalDeviceAccelerationStructureFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct RT_PIPELINE_FEATURES_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR,
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct POSITION_FETCH_FEATURES_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_POSITION_FETCH_FEATURES_KHR,
            VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct RAY_QUERY_FEATURES_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR,
            VkPhysicalDeviceRayQueryFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct SER_EXT_FEATURES_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_EXT,
            VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT.SIZEOF);
    private static final VulkanPNextStruct OMM_FEATURES_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_FEATURES_EXT,
            VkPhysicalDeviceOpacityMicromapFeaturesEXT.SIZEOF);
    private static final VulkanPNextStruct PRESENT_ID_FEATURES_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR,
            VkPhysicalDevicePresentIdFeaturesKHR.SIZEOF);

    private static final VulkanFeature BUFFER_DEVICE_ADDRESS_FEATURE = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT, "bufferDeviceAddress",
            VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS);
    private static final VulkanFeature RUNTIME_DESCRIPTOR_ARRAY_FEATURE = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT, "runtimeDescriptorArray",
            VkPhysicalDeviceVulkan12Features.RUNTIMEDESCRIPTORARRAY);
    private static final VulkanFeature SAMPLED_IMAGE_NON_UNIFORM_FEATURE = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT, "shaderSampledImageArrayNonUniformIndexing",
            VkPhysicalDeviceVulkan12Features.SHADERSAMPLEDIMAGEARRAYNONUNIFORMINDEXING);
    private static final VulkanFeature DESCRIPTOR_PARTIALLY_BOUND_FEATURE = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT, "descriptorBindingPartiallyBound",
            VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGPARTIALLYBOUND);
    private static final VulkanFeature SAMPLED_IMAGE_UPDATE_AFTER_BIND_FEATURE = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT, "descriptorBindingSampledImageUpdateAfterBind",
            VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGSAMPLEDIMAGEUPDATEAFTERBIND);
    private static final VulkanFeature SHADER_INT64_FEATURE = new VulkanFeature(
            VulkanBackend.VK10_FEATURES_STRUCT, "shaderInt64", VkPhysicalDeviceFeatures.SHADERINT64);
    private static final VulkanFeature ACCELERATION_STRUCTURE_FEATURE = new VulkanFeature(
            AS_FEATURES_STRUCT, "accelerationStructure",
            VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE);
    private static final VulkanFeature RAY_TRACING_PIPELINE_FEATURE = new VulkanFeature(
            RT_PIPELINE_FEATURES_STRUCT, "rayTracingPipeline",
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR.RAYTRACINGPIPELINE);
    private static final VulkanFeature POSITION_FETCH_FEATURE = new VulkanFeature(
            POSITION_FETCH_FEATURES_STRUCT, "rayTracingPositionFetch",
            VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR.RAYTRACINGPOSITIONFETCH);
    private static final VulkanFeature RAY_QUERY_FEATURE = new VulkanFeature(
            RAY_QUERY_FEATURES_STRUCT, "rayQuery", VkPhysicalDeviceRayQueryFeaturesKHR.RAYQUERY);
    private static final VulkanFeature SER_EXT_FEATURE = new VulkanFeature(
            SER_EXT_FEATURES_STRUCT, "rayTracingInvocationReorder(EXT)",
            VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT.RAYTRACINGINVOCATIONREORDER);
    private static final VulkanFeature OMM_FEATURE = new VulkanFeature(
            OMM_FEATURES_STRUCT, "micromap", VkPhysicalDeviceOpacityMicromapFeaturesEXT.MICROMAP);
    private static final VulkanFeature PRESENT_ID_FEATURE = new VulkanFeature(
            PRESENT_ID_FEATURES_STRUCT, "presentId", VkPhysicalDevicePresentIdFeaturesKHR.PRESENTID);
    private static final VulkanFeature WIDE_LINES_FEATURE = new VulkanFeature(
            VulkanBackend.VK10_FEATURES_STRUCT, "wideLines", VkPhysicalDeviceFeatures.WIDELINES);

    private static final List<VulkanFeature> REQUIRED_RT_FEATURES = List.of(
            BUFFER_DEVICE_ADDRESS_FEATURE,
            RUNTIME_DESCRIPTOR_ARRAY_FEATURE,
            SAMPLED_IMAGE_NON_UNIFORM_FEATURE,
            DESCRIPTOR_PARTIALLY_BOUND_FEATURE,
            SAMPLED_IMAGE_UPDATE_AFTER_BIND_FEATURE,
            SHADER_INT64_FEATURE,
            ACCELERATION_STRUCTURE_FEATURE,
            RAY_TRACING_PIPELINE_FEATURE,
            POSITION_FETCH_FEATURE,
            RAY_QUERY_FEATURE);

    /** {@code NO_SER} when {@code false}: world.rgen falls back to plain TraceRay (no HitObject / ReorderThread). */
    private static final String WORLD_RAYGEN_SER = "world.rgen.spv";
    private static final String WORLD_RAYGEN_NO_SER = "world_noser.rgen.spv";
    // Hand-written GLSL raygen (shaders/world/world_glsl.rgen) — a feature-parity port of
    // world.rgen.slang's NO_SER path, interface-compatible with the unchanged Slang-compiled
    // world.rchit/rahit/rmiss/shadow.rmiss stages. Currently the active raygen for all devices; the
    // Slang world.rgen.slang variants above are kept compiled but unused.
    private static final String WORLD_RAYGEN_GLSL = "world_glsl.rgen.spv";

    private record FeatureSupport(List<String> missingRequired, boolean ser,
                                  boolean omm, boolean presentId, boolean wideLines) {
        boolean supportsRt() {
            return missingRequired.isEmpty();
        }
    }

    private RtDeviceBringup() {
    }

    /** True once we have augmented a device creation to request RT (extensions + features). */
    public static boolean rtRequested() {
        return rtRequested;
    }

    public static String worldRaygenShader() {
        // return serEnabled ? WORLD_RAYGEN_SER : WORLD_RAYGEN_NO_SER;
        return WORLD_RAYGEN_GLSL;
    }

    public static boolean serExtEnabled() {
        return serEnabled;
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

    /** True if {@code VkPhysicalDeviceFeatures.wideLines} was enabled on the device (world-overlay thick
     *  lines, e.g. the block outline, use this instead of a screen-space quad when available). */
    public static boolean wideLinesEnabled() {
        return wideLinesEnabled;
    }

    /** The device's max native line width (raster {@code lineWidthRange[1]}); 1.0 if wideLines isn't
     *  enabled (Vulkan mandates exactly 1.0 in that case). Callers must clamp their desired width to this. */
    public static float maxLineWidth() {
        return maxLineWidth;
    }

    /** {@code VK_SAMPLE_COUNT_4_BIT} capped down to whatever the device's {@code framebufferColorSampleCounts}
     *  actually advertises (2x, or 1x/no MSAA on the rare device that lacks even that) — no device feature to
     *  enable, just a raster/framebuffer property, unlike {@link #wideLinesEnabled()}. World-overlay passes
     *  that need edge AA (e.g. the block outline's native wide line) use this as their pipeline's
     *  {@code rasterizationSamples}. */
    public static int overlayMsaaSamples() {
        return overlayMsaaSamples;
    }

    /** Whether device creation reserved a queue handle exclusively for Caustica terrain work. */
    public static boolean computeQueueReserved() {
        return computeQueueFamilyIndex >= 0 && computeQueueIndex >= 0;
    }

    public static int computeQueueFamilyIndex() {
        if (!computeQueueReserved()) {
            throw new IllegalStateException("Caustica compute queue was not reserved");
        }
        return computeQueueFamilyIndex;
    }

    public static int computeQueueIndex() {
        if (!computeQueueReserved()) {
            throw new IllegalStateException("Caustica compute queue was not reserved");
        }
        return computeQueueIndex;
    }

    /**
     * Reserve one additional physical queue at device-creation time. Minecraft's queue-family map only
     * requests handles for its graphics/compute/transfer queues; fetching a higher queue index without first
     * increasing the matching {@link VkDeviceQueueCreateInfo#queueCount()} would be invalid. Prefer a
     * compute-only family, but add a previously-unused compute family when that leaves the Minecraft queues
     * untouched and has a free physical slot.
     */
    public static void reserveComputeQueue(VkDeviceCreateInfo deviceCreateInfo,
                                           VulkanPhysicalDevice physicalDevice, MemoryStack stack) {
        computeQueueFamilyIndex = -1;
        computeQueueIndex = -1;
        if (!rtRequested) {
            return;
        }

        VkDeviceQueueCreateInfo.Buffer requestedQueues = deviceCreateInfo.pQueueCreateInfos();
        if (requestedQueues == null) {
            CausticaMod.LOGGER.warn("Caustica RT disabled: Vulkan device creation has no queue requests");
            return;
        }

        var count = stack.callocInt(1);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice.vkPhysicalDevice(), count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice.vkPhysicalDevice(), count, families);

        int[] requestedCounts = new int[families.capacity()];
        for (int i = 0; i < requestedQueues.capacity(); i++) {
            VkDeviceQueueCreateInfo request = requestedQueues.get(i);
            int family = request.queueFamilyIndex();
            if (family >= 0 && family < requestedCounts.length) {
                requestedCounts[family] = request.queueCount();
            }
        }

        int selectedFamily = -1;
        int selectedScore = Integer.MAX_VALUE;
        for (int family = 0; family < families.capacity(); family++) {
            VkQueueFamilyProperties properties = families.get(family);
            int flags = properties.queueFlags();
            if ((flags & VK10.VK_QUEUE_COMPUTE_BIT) == 0
                    || requestedCounts[family] >= properties.queueCount()) {
                continue;
            }
            // Compute-only families win; fewer capabilities then fewer already-requested handles break ties.
            int score = ((flags & VK10.VK_QUEUE_GRAPHICS_BIT) != 0 ? 1_000 : 0)
                    + Integer.bitCount(flags) * 10 + requestedCounts[family];
            if (score < selectedScore) {
                selectedFamily = family;
                selectedScore = score;
            }
        }

        if (selectedFamily < 0) {
            CausticaMod.LOGGER.warn(
                    "Caustica RT disabled: no spare compute-capable Vulkan queue is available");
            return;
        }

        int queueIndex = requestedCounts[selectedFamily];
        VkDeviceQueueCreateInfo matchingRequest = null;
        for (int i = 0; i < requestedQueues.capacity(); i++) {
            VkDeviceQueueCreateInfo request = requestedQueues.get(i);
            if (request.queueFamilyIndex() == selectedFamily) {
                matchingRequest = request;
                break;
            }
        }

        if (matchingRequest != null) {
            var oldPriorities = matchingRequest.pQueuePriorities();
            var priorities = stack.callocFloat(queueIndex + 1);
            if (oldPriorities != null) {
                for (int i = 0; i < queueIndex; i++) {
                    priorities.put(i, oldPriorities.get(i));
                }
            }
            matchingRequest.pQueuePriorities(priorities);
        } else {
            VkDeviceQueueCreateInfo.Buffer expanded = VkDeviceQueueCreateInfo.calloc(requestedQueues.capacity() + 1, stack);
            for (int i = 0; i < requestedQueues.capacity(); i++) {
                VkDeviceQueueCreateInfo source = requestedQueues.get(i);
                expanded.get(i)
                        .sType(source.sType())
                        .pNext(source.pNext())
                        .flags(source.flags())
                        .queueFamilyIndex(source.queueFamilyIndex())
                        .pQueuePriorities(source.pQueuePriorities());
            }
            expanded.get(requestedQueues.capacity())
                    .sType$Default()
                    .queueFamilyIndex(selectedFamily)
                    .pQueuePriorities(stack.callocFloat(1));
            deviceCreateInfo.pQueueCreateInfos(expanded);
        }

        computeQueueFamilyIndex = selectedFamily;
        computeQueueIndex = queueIndex;
        VkQueueFamilyProperties selected = families.get(selectedFamily);
        CausticaMod.LOGGER.info(
                "Reserved Caustica compute queue family={} index={} (family queues={}, flags=0x{})",
                selectedFamily, queueIndex, selected.queueCount(), Integer.toHexString(selected.queueFlags()));
    }

    /** Optional extensions whose extension and feature requirements are both supported. */
    private static List<String> supportedOptionalExtensions(VulkanPhysicalDevice physicalDevice,
                                                             FeatureSupport support) {
        List<String> supported = new ArrayList<>();
        if (support.omm) {
            supported.add(VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);
        }
        if (reflexRequested() && physicalDevice.hasDeviceExtension(VK_NV_LOW_LATENCY_2_EXTENSION_NAME)) {
            supported.add(VK_NV_LOW_LATENCY_2_EXTENSION_NAME);
            if (support.presentId) {
                supported.add(VK_KHR_PRESENT_ID_EXTENSION_NAME);
            }
        }
        return supported;
    }

    private static boolean ommRequested() {
        return CausticaConfig.Rt.Omm.ENABLED.value();
    }

    private static boolean reflexRequested() {
        return CausticaConfig.Rt.Reflex.ENABLED.value();
    }

    /** Query every feature boolean Caustica might enable in one complete Features2 chain. */
    private static FeatureSupport queryFeatureSupport(VulkanPhysicalDevice physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures2 available = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            for (VulkanFeature feature : REQUIRED_RT_FEATURES) {
                feature.struct().findOrCreateStructInPNextChain(available, stack);
            }

            boolean hasSerExt = physicalDevice.hasDeviceExtension(
                    VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME);
            if (hasSerExt) {
                SER_EXT_FEATURE.struct().findOrCreateStructInPNextChain(available, stack);
            }

            boolean queryOmm = ommRequested()
                    && physicalDevice.hasDeviceExtension(VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);
            if (queryOmm) {
                OMM_FEATURE.struct().findOrCreateStructInPNextChain(available, stack);
            }
            boolean queryPresentId = reflexRequested()
                    && physicalDevice.hasDeviceExtension(VK_NV_LOW_LATENCY_2_EXTENSION_NAME)
                    && physicalDevice.hasDeviceExtension(VK_KHR_PRESENT_ID_EXTENSION_NAME);
            if (queryPresentId) {
                PRESENT_ID_FEATURE.struct().findOrCreateStructInPNextChain(available, stack);
            }
            WIDE_LINES_FEATURE.struct().findOrCreateStructInPNextChain(available, stack);

            VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), available);

            List<String> missing = new ArrayList<>();
            for (VulkanFeature feature : REQUIRED_RT_FEATURES) {
                if (!feature.get(available)) {
                    missing.add(feature.name());
                }
            }
            // Optional: its absence just selects the NO_SER world raygen (plain TraceRay) instead of
            // disabling RT.
            boolean supportedSer = hasSerExt && SER_EXT_FEATURE.get(available);
            return new FeatureSupport(missing, supportedSer,
                    queryOmm && OMM_FEATURE.get(available),
                    queryPresentId && PRESENT_ID_FEATURE.get(available),
                    WIDE_LINES_FEATURE.get(available));
        }
    }

    private static String firstUnsupportedExtension(VulkanPhysicalDevice physicalDevice) {
        for (String ext : RT_EXTENSIONS) {
            if (!physicalDevice.hasDeviceExtension(ext)) {
                return ext;
            }
        }
        return null;
    }

    /** Standalone path: add RT extension names to the (mutable) arg0 list. */
    public static void addExtensions(List<String> augmentedExtensions, VulkanPhysicalDevice physicalDevice) {
        if (!enabledByProperty() || firstUnsupportedExtension(physicalDevice) != null) {
            return;
        }
        FeatureSupport support = queryFeatureSupport(physicalDevice);
        if (!support.supportsRt()) {
            return;
        }
        for (String ext : RT_EXTENSIONS) {
            if (!augmentedExtensions.contains(ext)) {
                augmentedExtensions.add(ext);
            }
        }
        if (support.ser && !augmentedExtensions.contains(VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME)) {
            augmentedExtensions.add(VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME);
        }
        for (String ext : supportedOptionalExtensions(physicalDevice, support)) {
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
        rtRequested = false;
        serEnabled = false;
        ommEnabled = false;
        reflexEnabled = false;
        presentIdEnabled = false;
        wideLinesEnabled = false;
        maxLineWidth = 1.0f;
        String missingExtension = firstUnsupportedExtension(physicalDevice);
        if (missingExtension != null) {
            if (!loggedUnavailable) {
                loggedUnavailable = true;
                CausticaMod.LOGGER.warn("Ray tracing unavailable: device [{}] lacks extension {}",
                        physicalDevice.deviceName(), missingExtension);
            }
            return;
        }

        FeatureSupport support = queryFeatureSupport(physicalDevice);
        if (!support.supportsRt()) {
            if (!loggedUnavailable) {
                loggedUnavailable = true;
                CausticaMod.LOGGER.warn("Ray tracing unavailable: device [{}] lacks features {}",
                        physicalDevice.deviceName(), support.missingRequired);
            }
            return;
        }

        Set<VulkanFeature> features = new HashSet<>((Set<VulkanFeature>) args.get(2));
        // Core features merge into vanilla's VK10/VK12 structs; extension features create their matching
        // pNext structs. Every boolean here was verified by queryFeatureSupport above.
        features.addAll(REQUIRED_RT_FEATURES);
        // Optional: SER (rayTracingInvocationReorder, EXT). Its absence must not disable RT — the world
        // raygen falls back to the NO_SER variant (plain TraceRay) when it isn't supported.
        if (support.ser) {
            features.add(SER_EXT_FEATURE);
        }

        // Optional: wideLines (core VK10 feature, no extension). Lets the world-overlay pass (block
        // outline) draw a real thick native line via a raster pipeline's lineWidth / VK_DYNAMIC_STATE_LINE
        // _WIDTH instead of a screen-space quad. Its absence must not disable RT — the overlay falls back
        // to whatever the device's mandated lineWidth (1.0) allows.
        wideLinesEnabled = support.wideLines;
        if (wideLinesEnabled) {
            features.add(WIDE_LINES_FEATURE);
            maxLineWidth = physicalDevice.vkPhysicalDeviceProperties().limits().lineWidthRange(1);
        }

        // World-overlay MSAA (block outline edge AA): a framebuffer property, not a feature — no
        // VulkanFeature entry needed, just cap the desired 4x down to what the device actually supports.
        int colorSampleCounts = physicalDevice.vkPhysicalDeviceProperties().limits().framebufferColorSampleCounts();
        if ((colorSampleCounts & VK10.VK_SAMPLE_COUNT_4_BIT) != 0) {
            overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_4_BIT;
        } else if ((colorSampleCounts & VK10.VK_SAMPLE_COUNT_2_BIT) != 0) {
            overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_2_BIT;
        } else {
            overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_1_BIT;
        }

        // Optional: opacity micromaps (any-hit opt). Only when the gate is on AND the device advertises the
        // extension — its absence must not disable RT, so it is kept out of the mandatory feature set above.
        ommEnabled = support.omm;
        if (ommEnabled) {
            features.add(OMM_FEATURE);
        }

        // Optional: NVIDIA Reflex (VK_NV_low_latency2). Function-only extension, no feature struct to add.
        reflexEnabled = reflexRequested() && physicalDevice.hasDeviceExtension(VK_NV_LOW_LATENCY_2_EXTENSION_NAME);

        // Optional: VK_KHR_present_id (presentID<->present correlation for Reflex markers). Its absence must
        // not disable Reflex sleep/pacing itself — only marker correlation degrades.
        presentIdEnabled = reflexEnabled && support.presentId;
        if (presentIdEnabled) {
            features.add(PRESENT_ID_FEATURE);
        }

        args.set(2, features);

        rtRequested = true;
        serEnabled = support.ser;
        List<String> optionalExtensions = supportedOptionalExtensions(physicalDevice, support);
        CausticaMod.LOGGER.info(
                "Ray tracing: enabling {}{}{} + features [bufferDeviceAddress, accelerationStructure, rayTracingPipeline, rayQuery"
                        + (serEnabled ? ", rayTracingInvocationReorder(EXT)" : "")
                        + (wideLinesEnabled ? ", wideLines(max=" + maxLineWidth + ")" : "")
                        + (ommEnabled ? ", opacityMicromap" : "") + "] + overlayMsaa=" + overlayMsaaSamples + "x on [{}]",
                RT_EXTENSIONS, serEnabled ? " + " + VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME : "",
                optionalExtensions.isEmpty() ? "" : " + " + optionalExtensions,
                physicalDevice.deviceName());
    }

    /**
     * Post-creation verification: confirm the RT entry points actually loaded on the new
     * device and log the RT pipeline / acceleration-structure limits. If this logs "OK",
     * the device truly came up RT-capable.
     */
    public static void probe(VkDevice device) {
        if (!rtRequested) {
            CausticaMod.LOGGER.info("Ray tracing not requested; skipping RT probe");
            maxOpacity4StateSubdivisionLevel = 0;
            return;
        }
        try {
            VKCapabilitiesDevice caps = device.getCapabilities();
            boolean rtPipeline = caps.vkCreateRayTracingPipelinesKHR != 0L;
            boolean asBuild = caps.vkCmdBuildAccelerationStructuresKHR != 0L;
            boolean traceRays = caps.vkCmdTraceRaysKHR != 0L;
            if (!(rtPipeline && asBuild && traceRays)) {
                CausticaMod.LOGGER.error(
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

                CausticaMod.LOGGER.info(
                        "RT bring-up OK — shaderGroupHandleSize={}, shaderGroupBaseAlignment={}, maxRayRecursionDepth={}; "
                                + "maxAS geometry/instance/primitive = {}/{}/{}",
                        rtProps.shaderGroupHandleSize(), rtProps.shaderGroupBaseAlignment(), rtProps.maxRayRecursionDepth(),
                        asProps.maxGeometryCount(), asProps.maxInstanceCount(), asProps.maxPrimitiveCount());
                if (ommProps != null) {
                    maxOpacity4StateSubdivisionLevel = ommProps.maxOpacity4StateSubdivisionLevel();
                    CausticaMod.LOGGER.info(
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
                    CausticaMod.LOGGER.info(
                            "Reflex (VK_NV_low_latency2) entry points loaded — presentId={}", presentIdEnabled);
                } else {
                    CausticaMod.LOGGER.error(
                            "Reflex extension enabled but entry points missing (sleepMode={}, sleep={}, marker={}, timings={})",
                            sleepMode, sleep, marker, timings);
                    reflexEnabled = false;
                }
            }
        } catch (Throwable t) {
            // A probe must never break device creation.
            CausticaMod.LOGGER.error("RT probe threw; continuing without RT", t);
        }
    }
}
