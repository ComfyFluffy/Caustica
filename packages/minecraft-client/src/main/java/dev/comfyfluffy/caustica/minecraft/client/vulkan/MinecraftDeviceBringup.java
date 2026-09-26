package dev.comfyfluffy.caustica.minecraft.client.vulkan;

import dev.comfyfluffy.caustica.renderer.raytracing.shader.SharcSdk;
import dev.comfyfluffy.caustica.renderer.runtime.RendererOptions;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaConfig;
import dev.comfyfluffy.caustica.minecraft.client.CausticaMod;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuRasterCapabilities;
import dev.comfyfluffy.caustica.engine.vulkan.VulkanProfileSupport;
import dev.comfyfluffy.caustica.engine.vulkan.VulkanProfileValidation;
import dev.comfyfluffy.caustica.engine.vulkan.VulkanRequiredProfile;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanDeviceCapabilities;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanQueueReservation;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTHdrMetadata;
import org.lwjgl.vulkan.EXTOpacityMicromap;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapFeaturesEXT;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceDescriptorHeapFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceDynamicRenderingFeatures;
import org.lwjgl.vulkan.VkPhysicalDevicePresentIdFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceShaderDemoteToHelperInvocationFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceShaderObjectFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceShaderUntypedPointersFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceSynchronization2Features;
import org.lwjgl.vulkan.VkPhysicalDeviceUnifiedImageLayoutsFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkQueueFamilyProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.lwjgl.vulkan.VKCapabilitiesDevice;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.vulkan.EXTRayTracingInvocationReorder.VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTRayTracingInvocationReorder.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_EXT;
import static org.lwjgl.vulkan.EXTDescriptorHeap.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_HEAP_FEATURES_EXT;
import static org.lwjgl.vulkan.EXTShaderObject.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_OBJECT_FEATURES_EXT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPresentId.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRRayQuery.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPositionFetch.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_POSITION_FETCH_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRShaderUntypedPointers.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_UNTYPED_POINTERS_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRUnifiedImageLayouts.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_UNIFIED_IMAGE_LAYOUTS_FEATURES_KHR;
import static org.lwjgl.vulkan.NVLowLatency2.VK_NV_LOW_LATENCY_2_EXTENSION_NAME;

/** Negotiates renderer Vulkan features and transfers the immutable result to the matching device backend. */
public final class MinecraftDeviceBringup {
    private static final VulkanRequiredProfile REQUIRED_PROFILE = VulkanRequiredProfile.CAUSTICA_1_4;

    private static final VulkanPNextStruct AS_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR,
            VkPhysicalDeviceAccelerationStructureFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct PIPELINE_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR,
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct POSITION_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_POSITION_FETCH_FEATURES_KHR,
            VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct QUERY_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR,
            VkPhysicalDeviceRayQueryFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct SER_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_EXT,
            VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT.SIZEOF);
    private static final VulkanPNextStruct OMM_STRUCT = new VulkanPNextStruct(
            EXTOpacityMicromap.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_FEATURES_EXT,
            VkPhysicalDeviceOpacityMicromapFeaturesEXT.SIZEOF);
    private static final VulkanPNextStruct PRESENT_ID_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR,
            VkPhysicalDevicePresentIdFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct UNIFIED_LAYOUTS_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_UNIFIED_IMAGE_LAYOUTS_FEATURES_KHR,
            VkPhysicalDeviceUnifiedImageLayoutsFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct DESCRIPTOR_HEAP_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_HEAP_FEATURES_EXT,
            VkPhysicalDeviceDescriptorHeapFeaturesEXT.SIZEOF);
    private static final VulkanPNextStruct SHADER_OBJECT_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_OBJECT_FEATURES_EXT,
            VkPhysicalDeviceShaderObjectFeaturesEXT.SIZEOF);
    private static final VulkanPNextStruct SHADER_UNTYPED_POINTERS_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_UNTYPED_POINTERS_FEATURES_KHR,
            VkPhysicalDeviceShaderUntypedPointersFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct SHADER_DEMOTE_STRUCT = new VulkanPNextStruct(
            org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_DEMOTE_TO_HELPER_INVOCATION_FEATURES,
            VkPhysicalDeviceShaderDemoteToHelperInvocationFeatures.SIZEOF);

    private static final VulkanFeature BUFFER_ADDRESS = new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT,
            "bufferDeviceAddress", VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS);
    private static final VulkanFeature TIMELINE_SEMAPHORE = new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT,
            "timelineSemaphore", VkPhysicalDeviceVulkan12Features.TIMELINESEMAPHORE);
    private static final VulkanFeature SHADER_FLOAT16 = new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT,
            "shaderFloat16", VkPhysicalDeviceVulkan12Features.SHADERFLOAT16);
    private static final VulkanFeature DESCRIPTOR_BINDING_PARTIALLY_BOUND = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT, "descriptorBindingPartiallyBound",
            VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGPARTIALLYBOUND);
    private static final VulkanFeature STORAGE_IMAGE_EXTENDED_FORMATS = new VulkanFeature(
            VulkanBackend.VK10_FEATURES_STRUCT, "shaderStorageImageExtendedFormats",
            VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEEXTENDEDFORMATS);
    private static final VulkanFeature STORAGE_IMAGE_READ_WITHOUT_FORMAT = new VulkanFeature(
            VulkanBackend.VK10_FEATURES_STRUCT, "shaderStorageImageReadWithoutFormat",
            VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEREADWITHOUTFORMAT);
    private static final VulkanFeature STORAGE_IMAGE_WRITE_WITHOUT_FORMAT = new VulkanFeature(
            VulkanBackend.VK10_FEATURES_STRUCT, "shaderStorageImageWriteWithoutFormat",
            VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEWRITEWITHOUTFORMAT);
    private static final VulkanFeature SHADER_DRAW_PARAMETERS = new VulkanFeature(VulkanBackend.VK11_FEATURES_STRUCT,
            "shaderDrawParameters", VkPhysicalDeviceVulkan11Features.SHADERDRAWPARAMETERS);
    private static final VulkanFeature SHADER_DEMOTE = new VulkanFeature(SHADER_DEMOTE_STRUCT,
            "shaderDemoteToHelperInvocation",
            VkPhysicalDeviceShaderDemoteToHelperInvocationFeatures.SHADERDEMOTETOHELPERINVOCATION);
    private static final VulkanFeature SYNCHRONIZATION_2 = new VulkanFeature(VulkanBackend.SYNC2_FEATURES_STRUCT,
            "synchronization2", VkPhysicalDeviceSynchronization2Features.SYNCHRONIZATION2);
    private static final VulkanFeature DYNAMIC_RENDERING = new VulkanFeature(VulkanBackend.DYNAMIC_RENDERING_FEATURES_STRUCT,
            "dynamicRendering", VkPhysicalDeviceDynamicRenderingFeatures.DYNAMICRENDERING);
    private static final VulkanFeature SHADER_INT64 = new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT,
            "shaderInt64", VkPhysicalDeviceFeatures.SHADERINT64);
    private static final VulkanFeature SHADER_INT16 = new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT,
            "shaderInt16", VkPhysicalDeviceFeatures.SHADERINT16);
    private static final VulkanFeature UNIFIED_IMAGE_LAYOUTS = new VulkanFeature(UNIFIED_LAYOUTS_STRUCT,
            "unifiedImageLayouts", VkPhysicalDeviceUnifiedImageLayoutsFeaturesKHR.UNIFIEDIMAGELAYOUTS);
    private static final VulkanFeature DESCRIPTOR_HEAP = new VulkanFeature(DESCRIPTOR_HEAP_STRUCT,
            "descriptorHeap", VkPhysicalDeviceDescriptorHeapFeaturesEXT.DESCRIPTORHEAP);
    private static final VulkanFeature SHADER_OBJECT = new VulkanFeature(SHADER_OBJECT_STRUCT,
            "shaderObject", VkPhysicalDeviceShaderObjectFeaturesEXT.SHADEROBJECT);
    private static final VulkanFeature SHADER_UNTYPED_POINTERS = new VulkanFeature(SHADER_UNTYPED_POINTERS_STRUCT,
            "shaderUntypedPointers", VkPhysicalDeviceShaderUntypedPointersFeaturesKHR.SHADERUNTYPEDPOINTERS);
    private static final VulkanFeature ACCELERATION_STRUCTURE = new VulkanFeature(AS_STRUCT,
            "accelerationStructure", VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE);
    private static final VulkanFeature RAY_PIPELINE = new VulkanFeature(PIPELINE_STRUCT,
            "rayTracingPipeline", VkPhysicalDeviceRayTracingPipelineFeaturesKHR.RAYTRACINGPIPELINE);
    private static final VulkanFeature POSITION_FETCH = new VulkanFeature(POSITION_STRUCT,
            "rayTracingPositionFetch", VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR.RAYTRACINGPOSITIONFETCH);
    private static final VulkanFeature RAY_QUERY = new VulkanFeature(QUERY_STRUCT,
            "rayQuery", VkPhysicalDeviceRayQueryFeaturesKHR.RAYQUERY);
    private static final VulkanFeature SER = new VulkanFeature(SER_STRUCT,
            "rayTracingInvocationReorder(EXT)",
            VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT.RAYTRACINGINVOCATIONREORDER);
    private static final VulkanFeature PRESENT_ID = new VulkanFeature(PRESENT_ID_STRUCT,
            "presentId", VkPhysicalDevicePresentIdFeaturesKHR.PRESENTID);
    private static final VulkanFeature OMM = new VulkanFeature(OMM_STRUCT, "micromap",
            VkPhysicalDeviceOpacityMicromapFeaturesEXT.MICROMAP);
    private static final VulkanFeature WIDE_LINES = new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT,
            "wideLines", VkPhysicalDeviceFeatures.WIDELINES);
    // The SHaRC stages insert hash grid keys with 64-bit atomics and store the resolved cache as float16.
    private static final VulkanFeature BUFFER_INT64_ATOMICS = new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT,
            "shaderBufferInt64Atomics", VkPhysicalDeviceVulkan12Features.SHADERBUFFERINT64ATOMICS);
    private static final VulkanFeature STORAGE_BUFFER_16_BIT = new VulkanFeature(VulkanBackend.VK11_FEATURES_STRUCT,
            "storageBuffer16BitAccess", VkPhysicalDeviceVulkan11Features.STORAGEBUFFER16BITACCESS);
    private record ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature profile,
                                  VulkanFeature device) {
    }

    private static final List<ProfileFeature> PROFILE_FEATURES = List.of(
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.SHADER_INT64, SHADER_INT64),
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.SHADER_INT16, SHADER_INT16),
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.SHADER_FLOAT16, SHADER_FLOAT16),
            new ProfileFeature(
                    dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.DESCRIPTOR_BINDING_PARTIALLY_BOUND,
                    DESCRIPTOR_BINDING_PARTIALLY_BOUND),
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.SHADER_STORAGE_IMAGE_EXTENDED_FORMATS,
                    STORAGE_IMAGE_EXTENDED_FORMATS),
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.SHADER_STORAGE_IMAGE_READ_WITHOUT_FORMAT,
                    STORAGE_IMAGE_READ_WITHOUT_FORMAT),
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.SHADER_STORAGE_IMAGE_WRITE_WITHOUT_FORMAT,
                    STORAGE_IMAGE_WRITE_WITHOUT_FORMAT),
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.SHADER_DRAW_PARAMETERS,
                    SHADER_DRAW_PARAMETERS),
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.SHADER_DEMOTE_TO_HELPER_INVOCATION,
                    SHADER_DEMOTE),
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.BUFFER_DEVICE_ADDRESS, BUFFER_ADDRESS),
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.TIMELINE_SEMAPHORE, TIMELINE_SEMAPHORE),
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.SYNCHRONIZATION_2, SYNCHRONIZATION_2),
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.DYNAMIC_RENDERING, DYNAMIC_RENDERING),
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.UNIFIED_IMAGE_LAYOUTS, UNIFIED_IMAGE_LAYOUTS),
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.DESCRIPTOR_HEAP, DESCRIPTOR_HEAP),
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.SHADER_OBJECT, SHADER_OBJECT),
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.SHADER_UNTYPED_POINTERS, SHADER_UNTYPED_POINTERS),
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.ACCELERATION_STRUCTURE, ACCELERATION_STRUCTURE),
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.RAY_TRACING_PIPELINE, RAY_PIPELINE),
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.RAY_QUERY, RAY_QUERY),
            new ProfileFeature(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.RAY_TRACING_POSITION_FETCH, POSITION_FETCH));
    private volatile int loaderApiVersion;
    private volatile int requestedInstanceApiVersion;
    private final Map<VkPhysicalDevice, Negotiation> negotiations = new HashMap<>();

    public record NegotiatedDevice(VulkanDeviceCapabilities capabilities, VulkanQueueReservation computeQueue) {
    }

    private static final class Negotiation {
        VulkanDeviceCapabilities capabilities = VulkanDeviceCapabilities.unavailable();
        VulkanQueueReservation computeQueue;
        boolean hdrMetadata;
    }

    private record Support(VulkanProfileSupport profile, boolean ser, boolean omm,
                           boolean presentId, boolean wideLines, boolean sharc) {
    }

    public MinecraftDeviceBringup() {
    }

    /** Captures the loader version and returns the hard instance version requested from Vulkan. */
    public int requestInstanceApiVersion() {
        loaderApiVersion = VK.getInstanceVersionSupported();
        requestedInstanceApiVersion = requestInstanceApiVersion(loaderApiVersion);
        return requestedInstanceApiVersion;
    }

    static int requestInstanceApiVersion(int loaderVersion) {
        int requestedInstanceApiVersion = REQUIRED_PROFILE.apiVersion();
        VulkanProfileSupport versionSupport = new VulkanProfileSupport(
                loaderVersion, requestedInstanceApiVersion, requestedInstanceApiVersion,
                REQUIRED_PROFILE.deviceExtensions(), REQUIRED_PROFILE.features());
        VulkanProfileValidation validation = VulkanProfileValidation.validate(REQUIRED_PROFILE, versionSupport);
        if (!validation.supported()) throw new IllegalStateException(validation.diagnostic());
        return requestedInstanceApiVersion;
    }

    private synchronized Negotiation negotiation(VkPhysicalDevice physicalDevice) {
        return negotiations.computeIfAbsent(physicalDevice, ignored -> new Negotiation());
    }

    public void addExtensions(Collection<String> extensions, VulkanPhysicalDevice device) {
        addHdrExtension(extensions, device);
        Support support = querySupport(device);
        requireProfile(support.profile());
        REQUIRED_PROFILE.deviceExtensions().forEach(extension -> addOnce(extensions, extension));
        if (support.ser()) addOnce(extensions, VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME);
        if (support.omm()) addOnce(extensions, EXTOpacityMicromap.VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);
        if (CausticaConfig.get(RendererOptions.Rt.Reflex.ENABLED)
                && device.hasDeviceExtension(VK_NV_LOW_LATENCY_2_EXTENSION_NAME)) {
            addOnce(extensions, VK_NV_LOW_LATENCY_2_EXTENSION_NAME);
            if (support.presentId()) addOnce(extensions, VK_KHR_PRESENT_ID_EXTENSION_NAME);
        }
    }

    private void addHdrExtension(Collection<String> extensions, VulkanPhysicalDevice device) {
        boolean supported = device.hasDeviceExtension(EXTHdrMetadata.VK_EXT_HDR_METADATA_EXTENSION_NAME);
        negotiation(device.vkPhysicalDevice()).hdrMetadata = supported;
        if (supported) {
            addOnce(extensions, EXTHdrMetadata.VK_EXT_HDR_METADATA_EXTENSION_NAME);
            CausticaMod.LOGGER.info("HDR: enabling {} for PQ swapchain mastering metadata",
                    EXTHdrMetadata.VK_EXT_HDR_METADATA_EXTENSION_NAME);
        } else {
            CausticaMod.LOGGER.warn("HDR: device [{}] lacks {}; mastering metadata disabled",
                    device.deviceName(), EXTHdrMetadata.VK_EXT_HDR_METADATA_EXTENSION_NAME);
        }
    }

    @SuppressWarnings("unchecked")
    public void addFeatures(Args args, VulkanPhysicalDevice device) {
        Negotiation negotiation = negotiation(device.vkPhysicalDevice());
        negotiation.capabilities = VulkanDeviceCapabilities.unavailable();
        Support support = querySupport(device);
        requireProfile(support.profile());
        Set<VulkanFeature> features = new HashSet<>((Set<VulkanFeature>) args.get(2));
        PROFILE_FEATURES.stream().map(ProfileFeature::device).forEach(features::add);
        if (support.ser()) features.add(SER);
        if (support.omm()) features.add(OMM);
        if (support.wideLines()) features.add(WIDE_LINES);
        if (support.sharc()) {
            features.add(BUFFER_INT64_ATOMICS);
            features.add(STORAGE_BUFFER_16_BIT);
        }

        boolean lowLatency = CausticaConfig.get(RendererOptions.Rt.Reflex.ENABLED)
                && device.hasDeviceExtension(VK_NV_LOW_LATENCY_2_EXTENSION_NAME);
        boolean presentIds = lowLatency && support.presentId();
        if (presentIds) features.add(PRESENT_ID);
        args.set(2, features);

        float maxLineWidth = support.wideLines()
                ? device.vkPhysicalDeviceProperties().limits().lineWidthRange(1) : 1.0f;
        int samples = preferredOverlaySampleCount(
                device.vkPhysicalDeviceProperties().limits().framebufferColorSampleCounts());
        negotiation.capabilities = new VulkanDeviceCapabilities(true, support.ser(), support.omm(),
                lowLatency, presentIds, negotiation.hdrMetadata, support.sharc(), support.sharc(),
                new GpuRasterCapabilities(support.wideLines(), maxLineWidth, samples));
        CausticaMod.LOGGER.info("Ray tracing enabled on [{}]: SER={}, Reflex={}, presentId={}, overlaySamples={}, "
                + "SHaRC features={}", device.deviceName(), support.ser(), lowLatency, presentIds, samples,
                support.sharc());
        CausticaMod.LOGGER.info("Opacity micromap acceleration: {}", support.omm());
    }

    public void reserveComputeQueue(VkDeviceCreateInfo createInfo, VulkanPhysicalDevice device,
                                           MemoryStack stack) {
        Negotiation negotiation = negotiation(device.vkPhysicalDevice());
        negotiation.computeQueue = reserveComputeQueue(createInfo, device.vkPhysicalDevice(), stack,
                negotiation.capabilities.rayTracing());
    }

    /** Validates negotiated entry points and captures device limits before the backend is published. */
    public synchronized void probe(VkDevice device) {
        Negotiation negotiation = negotiation(device.getPhysicalDevice());
        VulkanDeviceCapabilities old = negotiation.capabilities;
        if (!old.rayTracing()) return;
        VKCapabilitiesDevice entryPoints = device.getCapabilities();
        boolean rayTracing = entryPoints.vkCreateRayTracingPipelinesKHR != 0L
                && entryPoints.vkCmdBuildAccelerationStructuresKHR != 0L
                && entryPoints.vkCmdTraceRaysKHR != 0L;
        boolean lowLatency = old.lowLatency() && entryPoints.vkSetLatencySleepModeNV != 0L
                && entryPoints.vkLatencySleepNV != 0L && entryPoints.vkSetLatencyMarkerNV != 0L
                && entryPoints.vkGetLatencyTimingsNV != 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps =
                    VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps =
                    VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default().pNext(asProps.address());
            VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default()
                    .pNext(rtProps);
            VK12.vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), props);
            CausticaMod.LOGGER.info("RT device limits: groupHandle={}, groupAlignment={}, recursionDepth={}, maxGeometry={}",
                    rtProps.shaderGroupHandleSize(), rtProps.shaderGroupBaseAlignment(),
                    rtProps.maxRayRecursionDepth(), asProps.maxGeometryCount());
        }
        negotiation.capabilities = new VulkanDeviceCapabilities(rayTracing,
                rayTracing && old.shaderExecutionReordering(),
                rayTracing && old.opacityMicromap() && entryPoints.VK_EXT_opacity_micromap,
                lowLatency, lowLatency && old.presentIds(), old.hdrMetadata(), old.shaderBufferInt64Atomics(),
                old.storageBuffer16BitAccess(), old.raster());
        if (!rayTracing) CausticaMod.LOGGER.error("RT entry points are incomplete after device creation");
        if (old.lowLatency() && !lowLatency) CausticaMod.LOGGER.error("Low-latency entry points are incomplete");
    }

    /** Removes the negotiation result once the matching live backend has captured it. */
    public synchronized NegotiatedDevice consume(VkDevice device) {
        Negotiation negotiation = negotiations.remove(device.getPhysicalDevice());
        if (negotiation == null || negotiation.computeQueue == null) return null;
        return new NegotiatedDevice(negotiation.capabilities, negotiation.computeQueue);
    }

    static int preferredOverlaySampleCount(int supportedCounts) {
        if ((supportedCounts & VK10.VK_SAMPLE_COUNT_4_BIT) != 0) return VK10.VK_SAMPLE_COUNT_4_BIT;
        if ((supportedCounts & VK10.VK_SAMPLE_COUNT_2_BIT) != 0) return VK10.VK_SAMPLE_COUNT_2_BIT;
        return VK10.VK_SAMPLE_COUNT_1_BIT;
    }

    private static VulkanQueueReservation reserveComputeQueue(VkDeviceCreateInfo createInfo,
            org.lwjgl.vulkan.VkPhysicalDevice physicalDevice, MemoryStack stack, boolean rayTracing) {
        if (!rayTracing) return null;
        VkDeviceQueueCreateInfo.Buffer requestedQueues = createInfo.pQueueCreateInfos();
        if (requestedQueues == null) return null;
        var count = stack.callocInt(1);
        VK12.vkGetPhysicalDeviceQueueFamilyProperties2(physicalDevice, count, null);
        VkQueueFamilyProperties2.Buffer families = VkQueueFamilyProperties2.calloc(count.get(0), stack);
        for (int family = 0; family < families.capacity(); family++) {
            families.get(family).sType$Default();
        }
        VK12.vkGetPhysicalDeviceQueueFamilyProperties2(physicalDevice, count, families);
        int[] requestedCounts = new int[families.capacity()];
        for (int i = 0; i < requestedQueues.capacity(); i++) {
            VkDeviceQueueCreateInfo request = requestedQueues.get(i);
            requestedCounts[request.queueFamilyIndex()] = request.queueCount();
        }
        int family = selectComputeQueueFamily(families, requestedCounts);
        if (family < 0) {
            CausticaMod.LOGGER.warn("Renderer RT disabled: no spare compute-capable Vulkan queue is available");
            return null;
        }
        int queueIndex = requestedCounts[family];
        VkDeviceQueueCreateInfo matching = null;
        for (int i = 0; i < requestedQueues.capacity(); i++) {
            if (requestedQueues.get(i).queueFamilyIndex() == family) matching = requestedQueues.get(i);
        }
        if (matching != null) {
            var oldPriorities = matching.pQueuePriorities();
            var priorities = stack.callocFloat(queueIndex + 1);
            if (oldPriorities != null) for (int i = 0; i < queueIndex; i++) priorities.put(i, oldPriorities.get(i));
            matching.pQueuePriorities(priorities);
        } else {
            VkDeviceQueueCreateInfo.Buffer expanded = VkDeviceQueueCreateInfo.calloc(requestedQueues.capacity() + 1, stack);
            for (int i = 0; i < requestedQueues.capacity(); i++) {
                expanded.get(i).set(requestedQueues.get(i));
            }
            expanded.get(requestedQueues.capacity()).sType$Default().queueFamilyIndex(family)
                    .pQueuePriorities(stack.callocFloat(1));
            createInfo.pQueueCreateInfos(expanded);
        }
        CausticaMod.LOGGER.info("Reserved renderer compute queue family={} index={}", family, queueIndex);
        return new VulkanQueueReservation(family, queueIndex);
    }

    static int selectComputeQueueFamily(VkQueueFamilyProperties2.Buffer families, int[] requestedCounts) {
        int selected = -1;
        int score = Integer.MAX_VALUE;
        for (int family = 0; family < families.capacity(); family++) {
            VkQueueFamilyProperties properties = families.get(family).queueFamilyProperties();
            int flags = properties.queueFlags();
            if ((flags & VK10.VK_QUEUE_COMPUTE_BIT) == 0 || requestedCounts[family] >= properties.queueCount()) continue;
            int candidate = ((flags & VK10.VK_QUEUE_GRAPHICS_BIT) != 0 ? 1_000 : 0)
                    + Integer.bitCount(flags) * 10 + requestedCounts[family];
            if (candidate < score) { selected = family; score = candidate; }
        }
        return selected;
    }

    private Support querySupport(VulkanPhysicalDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures2 available = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            PROFILE_FEATURES.stream().map(ProfileFeature::device).forEach(
                    feature -> feature.struct().findOrCreateStructInPNextChain(available, stack));
            boolean querySer = Boolean.parseBoolean(System.getProperty("caustica.rt.ser", "true"))
                    && device.hasDeviceExtension(VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME);
            boolean queryOmm = Boolean.parseBoolean(System.getProperty("caustica.rt.omm", "true"))
                    && device.hasDeviceExtension(EXTOpacityMicromap.VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);
            boolean queryPresent = CausticaConfig.get(RendererOptions.Rt.Reflex.ENABLED)
                    && device.hasDeviceExtension(VK_NV_LOW_LATENCY_2_EXTENSION_NAME)
                    && device.hasDeviceExtension(VK_KHR_PRESENT_ID_EXTENSION_NAME);
            if (querySer) SER.struct().findOrCreateStructInPNextChain(available, stack);
            if (queryOmm) OMM.struct().findOrCreateStructInPNextChain(available, stack);
            if (queryPresent) PRESENT_ID.struct().findOrCreateStructInPNextChain(available, stack);
            // Only a build with verified SHaRC headers negotiates the features of its stages.
            boolean querySharc = SharcSdk.includeDirectory() != null;
            if (querySharc) {
                BUFFER_INT64_ATOMICS.struct().findOrCreateStructInPNextChain(available, stack);
                STORAGE_BUFFER_16_BIT.struct().findOrCreateStructInPNextChain(available, stack);
            }
            WIDE_LINES.struct().findOrCreateStructInPNextChain(available, stack);
            VK12.vkGetPhysicalDeviceFeatures2(device.vkPhysicalDevice(), available);
            Set<String> extensions = new HashSet<>();
            REQUIRED_PROFILE.deviceExtensions().stream().filter(device::hasDeviceExtension).forEach(extensions::add);
            EnumSet<dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature> supportedFeatures =
                    EnumSet.noneOf(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.class);
            PROFILE_FEATURES.stream().filter(feature -> feature.device().get(available))
                    .map(ProfileFeature::profile).forEach(supportedFeatures::add);
            int loaderVersion = loaderApiVersion != 0 ? loaderApiVersion : VK.getInstanceVersionSupported();
            int requestedVersion = requestedInstanceApiVersion != 0
                    ? requestedInstanceApiVersion : REQUIRED_PROFILE.apiVersion();
            VulkanProfileSupport profile = new VulkanProfileSupport(loaderVersion, requestedVersion,
                    device.vkPhysicalDeviceProperties().apiVersion(), extensions, supportedFeatures);
            return new Support(profile, querySer && SER.get(available), queryOmm && OMM.get(available),
                    queryPresent && PRESENT_ID.get(available), WIDE_LINES.get(available),
                    querySharc && BUFFER_INT64_ATOMICS.get(available) && STORAGE_BUFFER_16_BIT.get(available));
        }
    }

    static VulkanProfileValidation validateProfile(VulkanProfileSupport support) {
        return VulkanProfileValidation.validate(REQUIRED_PROFILE, support);
    }

    static Set<dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature> mappedProfileFeatures() {
        EnumSet<dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature> mapped =
                EnumSet.noneOf(dev.comfyfluffy.caustica.engine.vulkan.VulkanFeature.class);
        PROFILE_FEATURES.stream().map(ProfileFeature::profile).forEach(mapped::add);
        return Set.copyOf(mapped);
    }

    private static void requireProfile(VulkanProfileSupport support) {
        VulkanProfileValidation validation = validateProfile(support);
        if (!validation.supported()) throw new IllegalStateException(validation.diagnostic());
    }

    private static void addOnce(Collection<String> extensions, String extension) {
        if (!extensions.contains(extension)) extensions.add(extension);
    }

}
