package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorHeap;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.engine.vulkan.VulkanRequiredProfile;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanQueueRef;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanRendererBackend;
import dev.comfyfluffy.caustica.engine.vulkan.VulkanDiagnostics;
import dev.comfyfluffy.caustica.engine.vulkan.GpuCrashHistory;
import dev.comfyfluffy.caustica.engine.vulkan.GpuDiagnosticCheckpoints;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkFormatProperties2;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageFormatProperties2;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceImageFormatInfo2;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR;

/**
 * Owns the renderer's VMA allocator, descriptor heap, queue services, and transient command pool.
 * Allocation and ray-tracing layout use the installed device's capabilities and alignment limits.
 * The host owns the Vulkan device and queues; the runtime composition root owns this context.
 */
public final class VulkanDeviceContext implements GpuDevice {
    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanDeviceContext.class);

    private final VulkanRendererBackend host;
    private final VkDevice vk;
    private final long vma;
    private final VmaMemoryTelemetry memoryTelemetry;
    private final VulkanDescriptorHeap descriptorHeap;
    private final VulkanQueueRef graphicsQueue;
    private final VulkanQueueRef computeQueue;
    /**
     * Externally synchronizes every queue submit, present, and wait on this device, from renderer threads
     * and the host, with {@code vkDeviceWaitIdle}, which requires all of the device's queues.
     */
    private final Object deviceQueueHostLock = new Object();
    private final RtGpuExecutor gpuExecutor;
    private final GraphicsQueue graphics;
    private volatile long completedComputeValue;
    private long importedComputeValue;
    private final int shaderGroupHandleSize;
    private final int shaderGroupBaseAlignment;
    private final int shaderGroupHandleAlignment;
    private final int maxShaderGroupStride;
    private final int accelerationStructureScratchAlignment;
    private final int maxOpacityMicromapSubdivisionLevel;
    private long commandPool;
    private ConventionalDescriptorBindings conventionalBindings;
    private boolean destroyed;

    private VulkanDeviceContext(VulkanRendererBackend host, long vma, VulkanDescriptorHeap descriptorHeap,
                      int handleSize, int baseAlign, int handleAlign,
                      int maxSbtStride, int scratchAlign, int maxOpacitySubdivision,
                      VmaMemoryTelemetry memoryTelemetry) {
        this.host = host;
        this.vk = host.device();
        this.vma = vma;
        this.memoryTelemetry = memoryTelemetry;
        this.descriptorHeap = descriptorHeap;
        this.graphicsQueue = host.graphicsQueue();
        this.computeQueue = host.computeQueue();
        this.shaderGroupHandleSize = handleSize;
        this.shaderGroupBaseAlignment = baseAlign;
        this.shaderGroupHandleAlignment = handleAlign;
        this.maxShaderGroupStride = maxSbtStride;
        this.accelerationStructureScratchAlignment = scratchAlign;
        this.maxOpacityMicromapSubdivisionLevel = maxOpacitySubdivision;
        if (GpuDiagnosticCheckpoints.ENABLED) {
            LOGGER.info("NVIDIA GPU diagnostic checkpoints requested: supported={}",
                    vk.getCapabilities().vkCmdSetCheckpointNV != 0L);
        }
        this.gpuExecutor = new RtGpuExecutor(this);
        this.graphics = new GraphicsQueue(this);
    }

    /** Create the resources owned by one installed renderer Vulkan device. */
    public static VulkanDeviceContext create(VulkanRendererBackend host) {
        VkDevice vk = host.device();
        VmaMemoryTelemetry memoryTelemetry = new VmaMemoryTelemetry();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDevice phys = vk.getPhysicalDevice();

            // Every renderer buffer exposes a device address.
            VmaVulkanFunctions fns = VmaVulkanFunctions.calloc(stack).set(phys.getInstance(), vk);
            // Smaller growth units limit mapping/commit bursts when streaming exhausts a block.
            VmaAllocatorCreateInfo aci = VmaAllocatorCreateInfo.calloc(stack)
                    .flags(Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT)
                    .preferredLargeHeapBlockSize(64L * 1024 * 1024)
                    .instance(phys.getInstance())
                    .vulkanApiVersion(VulkanRequiredProfile.VULKAN_1_4)
                    .device(vk)
                    .physicalDevice(phys)
                    .pVulkanFunctions(fns)
                    .pDeviceMemoryCallbacks(memoryTelemetry.callbacks());
            PointerBuffer pVma = stack.mallocPointer(1);
            check(Vma.vmaCreateAllocator(aci, pVma), "vmaCreateAllocator(RT)");
            VulkanDiagnostics.registerAllocator(pVma.get(0));

            // RT pipeline limits for SBT layout.
            VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps = VkPhysicalDeviceRayTracingPipelinePropertiesKHR
                    .calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR);
            VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps =
                    VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            rtProps.pNext(asProps.address());
            var opacityProps = org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapPropertiesEXT.calloc(stack).sType$Default();
            if (host.capabilities().opacityMicromap()) asProps.pNext(opacityProps.address());
            VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(rtProps.address());
            VK12.vkGetPhysicalDeviceProperties2(phys, props2);

            LOGGER.info(
                    "RT portability limits: SBT handleAlignment={}, baseAlignment={}, maxStride={}; "
                            + "AS scratchAlignment={}",
                    rtProps.shaderGroupHandleAlignment(), rtProps.shaderGroupBaseAlignment(),
                    Integer.toUnsignedLong(rtProps.maxShaderGroupStride()),
                    asProps.minAccelerationStructureScratchOffsetAlignment());

            long allocator = pVma.get(0);
            VulkanDescriptorHeap descriptorHeap = null;
            try {
                descriptorHeap = VulkanDescriptorHeap.create(vk, allocator,
                        host.graphicsQueue().familyIndex(), host.computeQueue().familyIndex());
                return new VulkanDeviceContext(host, allocator, descriptorHeap,
                        rtProps.shaderGroupHandleSize(), rtProps.shaderGroupBaseAlignment(),
                        rtProps.shaderGroupHandleAlignment(), rtProps.maxShaderGroupStride(),
                        asProps.minAccelerationStructureScratchOffsetAlignment(),
                        host.capabilities().opacityMicromap() ? opacityProps.maxOpacity4StateSubdivisionLevel() : -1,
                        memoryTelemetry);
            } catch (Throwable failure) {
                if (descriptorHeap != null) descriptorHeap.close();
                VulkanDiagnostics.registerAllocator(0L);
                Vma.vmaDestroyAllocator(allocator);
                throw failure;
            }
        } catch (Throwable failure) {
            memoryTelemetry.close();
            throw failure;
        }
    }

    public VulkanRendererBackend backend() {
        return host;
    }

    /** Maximum four-state subdivision level, or -1 when opacity micromaps are unavailable. */
    public int maxOpacityMicromapSubdivisionLevel() { return maxOpacityMicromapSubdivisionLevel; }

    @Override
    public VkDevice vk() {
        return vk;
    }

    public void nameObject(int objectType, long handle, String label) {
        RtDebugLabels.name(this, objectType, handle, label);
    }

    @Override
    public long vmaAllocator() {
        return vma;
    }

    @Override
    public GpuDescriptorHeap descriptorHeap() {
        return descriptorHeap;
    }

    void bindConventionalDescriptors(VkCommandBuffer commandBuffer) {
        if (conventionalBindings == null) conventionalBindings = new ConventionalDescriptorBindings(this);
        conventionalBindings.bind(commandBuffer);
    }

    /** Bind the renderer-owned resource and sampler heaps before heap-native commands are recorded. */
    public void bindDescriptorHeaps(VkCommandBuffer commandBuffer) {
        descriptorHeap.bind(commandBuffer);
    }

    /** Begin an independently owned graphics command buffer with its own descriptor binding state. */
    public OwnedCommandBuffer beginGraphicsCommands(String label, boolean descriptorHeaps) {
        host.assertRenderThread();
        return new OwnedCommandBuffer(this, label, descriptorHeaps);
    }

    /** Populate descriptor-heap push-data storage without introducing pipeline-layout state. */
    public void pushData(VkCommandBuffer commandBuffer, int offset, ByteBuffer data) {
        descriptorHeap.pushData(commandBuffer, offset, data);
    }

    @Override
    public int[] asyncBufferSharingQueueFamilies() {
        return distinctQueueFamilies(graphicsQueue.familyIndex(), computeQueue.familyIndex());
    }

    static int[] distinctQueueFamilies(int graphicsFamily, int computeFamily) {
        return graphicsFamily == computeFamily
                ? new int[] { graphicsFamily }
                : new int[] { graphicsFamily, computeFamily };
    }

    public RtGpuExecutor gpuExecutor() {
        return gpuExecutor;
    }

    public GraphicsQueue graphics() { return graphics; }

    /**
     * Destroys on the graphics retirement thread once every graphics use reserved before this call has
     * completed, so graphics frames already submitted or recording may still read the resource. Callable
     * from any thread. Async compute reads are not covered; they end with their job's completion.
     */
    public void deferDestroy(Runnable destroy) { graphics.releaseAfterReservedGraphics(destroy); }

    long completedComputeValue() { return completedComputeValue; }

    void publishCompletedCompute(long value) { completedComputeValue = value; }

    /** Import available compute writes without depending on unfinished background work. */
    void importCompletedComputeWrites(GraphicsSubmission submission) {
        long completed = completedComputeValue;
        if (completed > importedComputeValue) {
            enqueueCompletedComputeWait(submission, gpuExecutor.completionSemaphore(), completed);
            importedComputeValue = completed;
        }
    }

    static void enqueueCompletedComputeWait(GraphicsSubmission submission, long semaphore, long value) {
        if (value != 0L) submission.waitSemaphore(semaphore, value, VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
    }

    public void drainAndWaitIdle() {
        gpuExecutor.drain();
        waitIdle();
        graphics.drainAfterDeviceIdle();
        gpuExecutor.throwIfFailed();
    }

    VulkanQueueRef computeQueue() {
        return computeQueue;
    }

    Object deviceQueueHostLock() {
        return deviceQueueHostLock;
    }

    /** Runs one host-issued queue submit, present, or wait under the device queue lock. */
    public int synchronizedQueueOperation(IntSupplier operation) {
        synchronized (deviceQueueHostLock) {
            return operation.getAsInt();
        }
    }

    public int shaderGroupHandleSize() {
        return shaderGroupHandleSize;
    }

    public int shaderGroupBaseAlignment() {
        return shaderGroupBaseAlignment;
    }

    public int shaderGroupHandleAlignment() {
        return shaderGroupHandleAlignment;
    }

    public int maxShaderGroupStride() {
        return maxShaderGroupStride;
    }

    public int accelerationStructureScratchAlignment() {
        return accelerationStructureScratchAlignment;
    }

    /** Create a VMA buffer; {@code SHADER_DEVICE_ADDRESS} is always added so it has a device address. */
    public GpuBuffer createBuffer(long size, int usage, boolean hostVisible, String label) {
        return createBuffer(size, usage, hostVisible, label, false,
                hostVisible ? Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT : 0, 0L);
    }

    /** Create a buffer whose returned device address is explicitly aligned for its consumer. */
    public GpuBuffer createAlignedBuffer(long size, int usage, boolean hostVisible, String label, long addressAlignment) {
        return createBuffer(size, usage, hostVisible, label, false,
                hostVisible ? Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT : 0, addressAlignment);
    }

    /** Create a buffer shared by the graphics and async-compute families when those families differ. */
    public GpuBuffer createAsyncBuffer(long size, int usage, boolean hostVisible, String label) {
        return createBuffer(size, usage, hostVisible, label, true,
                hostVisible ? Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT : 0, 0L);
    }

    /** Create a transient, persistently mapped upload buffer optimized for sequential host writes. */
    public GpuBuffer createUploadBuffer(long size, String label) {
        return createBuffer(size, VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true, label, false,
                Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT, 0L);
    }

    /** Create a transient, persistently mapped buffer for synchronous GPU-to-CPU transfers. */
    public GpuBuffer createReadbackBuffer(long size, String label) {
        return createBuffer(size, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, true, label, false,
                Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT, 0L);
    }

    /** Create a mapped GPU input buffer that prefers device-local memory and is only written by the host. */
    public GpuBuffer createMappedGpuUploadBuffer(long size, int usage, String label) {
        return createMappedGpuUploadBuffer(size, usage, label, 0L);
    }

    /** Create a mapped GPU input buffer with the device-address alignment required by its consumer. */
    public GpuBuffer createMappedGpuUploadBuffer(long size, int usage, String label, long addressAlignment) {
        return createBuffer(size, usage, true, label, false,
                Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT, addressAlignment,
                Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
    }

    private GpuBuffer createBuffer(long size, int usage, boolean hostVisible, String label, boolean asyncShared,
                                  int hostAccessFlags, long addressAlignment) {
        return createBuffer(size, usage, hostVisible, label, asyncShared, hostAccessFlags, addressAlignment,
                hostVisible ? Vma.VMA_MEMORY_USAGE_AUTO : Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
    }

    private GpuBuffer createBuffer(long size, int usage, boolean hostVisible, String label, boolean asyncShared,
                                  int hostAccessFlags, long addressAlignment, int memoryUsage) {
        if (addressAlignment < 0L
                || (addressAlignment != 0L && (addressAlignment & (addressAlignment - 1L)) != 0L)) {
            throw new IllegalArgumentException("Device-address alignment must be zero or a positive power of two: "
                    + addressAlignment);
        }
        long handle = 0L;
        long allocation = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size).usage(usage | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            if (asyncShared && graphicsQueue.familyIndex() != computeQueue.familyIndex()) {
                bci.sharingMode(VK10.VK_SHARING_MODE_CONCURRENT)
                        .pQueueFamilyIndices(stack.ints(graphicsQueue.familyIndex(), computeQueue.familyIndex()));
            }
            VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(stack).usage(memoryUsage);
            if (hostVisible) {
                aci.flags(hostAccessFlags | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            }
            LongBuffer pBuf = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
            int createResult = addressAlignment == 0L
                    ? Vma.vmaCreateBuffer(vma, bci, aci, pBuf, pAlloc, info)
                    : Vma.vmaCreateBufferWithAlignment(vma, bci, aci, addressAlignment, pBuf, pAlloc, info);
            check(createResult, addressAlignment == 0L ? "vmaCreateBuffer" : "vmaCreateBufferWithAlignment");
            handle = pBuf.get(0);
            allocation = pAlloc.get(0);
            RtDebugLabels.nameBuffer(this, handle, label);
            VkBufferDeviceAddressInfo bdai = VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(handle);
            long rawDeviceAddress = VK12.vkGetBufferDeviceAddress(vk, bdai);
            if (rawDeviceAddress == 0L) {
                throw new IllegalStateException(label + " returned a null device address");
            }
            VulkanDeviceAddress deviceAddress = new VulkanDeviceAddress(rawDeviceAddress);
            if (addressAlignment != 0L && !deviceAddress.isAlignedTo(addressAlignment)) {
                throw new IllegalStateException(label + " device address 0x"
                        + Long.toUnsignedString(deviceAddress.value(), 16) + " is not aligned to " + addressAlignment);
            }
            VulkanDiagnostics.registerBuffer(new VulkanDeviceAddressRange(deviceAddress, size), handle, label);
            long registeredHandle = handle;
            return new VmaGpuBuffer(vma, handle, allocation, deviceAddress, hostVisible ? info.pMappedData() : 0L,
                    size, hostVisible,
                    () -> VulkanDiagnostics.unregisterBuffer(deviceAddress, registeredHandle));
        } catch (Throwable t) {
            if (handle != 0L) {
                Vma.vmaDestroyBuffer(vma, handle, allocation);
            }
            throw t;
        }
    }

    /**
     * Create a sampled storage image with transfer support, transitioned to GENERAL before returning.
     */
    public GpuImage createStorageImage(int width, int height, int format, String label) {
        return createStorageImage(width, height, format, label, 0);
    }

    /**
     * Adds caller-supplied usage bits, including color-attachment support for dynamic rendering.
     */
    public GpuImage createStorageImage(int width, int height, int format, String label, int extraUsage) {
        int usage = VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT
                | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT | extraUsage;
        requireStorageImageSupport(width, height, format, usage, label);
        long image = 0L;
        long allocation = 0L;
        long view = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK10.VK_IMAGE_TYPE_2D).format(format)
                    .mipLevels(1).arrayLayers(1).samples(VK10.VK_SAMPLE_COUNT_1_BIT).tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            ici.extent().set(width, height, 1);
            VmaAllocationCreateInfo iaci = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO);
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            check(Vma.vmaCreateImage(vma, ici, iaci, pImage, pAlloc, null), "vmaCreateImage");
            image = pImage.get(0);
            allocation = pAlloc.get(0);
            RtDebugLabels.nameImage(this, image, label);

            VkImageViewCreateInfo vci = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image).viewType(VK10.VK_IMAGE_VIEW_TYPE_2D).format(format);
            vci.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            check(VK10.vkCreateImageView(vk, vci, null, pView), "vkCreateImageView");
            view = pView.get(0);
            RtDebugLabels.nameImageView(this, view, label + " view");
        } catch (Throwable failure) {
            if (view != 0L) VK10.vkDestroyImageView(vk, view, null);
            if (image != 0L) Vma.vmaDestroyImage(vma, image, allocation);
            throw failure;
        }
        long imageFinal = image;
        submitSync(cmd -> {
            try (MemoryStack stack = MemoryStack.stackPush(); var ignored = RtDebugLabels.scope(this, cmd, "init " + label)) {
                long destinationStages = VK13.VK_PIPELINE_STAGE_2_COPY_BIT
                        | VK13.VK_PIPELINE_STAGE_2_BLIT_BIT
                        | VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT
                        | VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                        | VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
                        | org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR;
                long destinationAccess = VK13.VK_ACCESS_2_TRANSFER_READ_BIT | VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT
                        | VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT
                        | VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                        | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
                if ((extraUsage & VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) != 0) {
                    destinationStages |= VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
                    destinationAccess |= VK13.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT
                            | VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
                }
                VulkanBarriers.transitionUndefinedImage(cmd, stack, imageFinal,
                        destinationStages, destinationAccess);
            }
        });
        try {
            return new VmaGpuImage(vma, vk, descriptorHeap, image, allocation, view,
                    width, height, format, usage);
        } catch (Throwable failure) {
            if (view != 0L) VK10.vkDestroyImageView(vk, view, null);
            if (image != 0L) Vma.vmaDestroyImage(vma, image, allocation);
            throw failure;
        }
    }

    private void requireStorageImageSupport(int width, int height, int format, int usage, String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties2 formatProperties = VkFormatProperties2.calloc(stack).sType$Default();
            VK11.vkGetPhysicalDeviceFormatProperties2(vk.getPhysicalDevice(), format, formatProperties);
            int required = VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
            if ((usage & VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT) != 0) {
                required |= VK11.VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;
            }
            if ((usage & VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT) != 0) {
                required |= VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
            }
            if ((usage & VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) != 0) {
                required |= VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT;
            }
            int supported = formatProperties.formatProperties().optimalTilingFeatures();
            if ((supported & required) != required) {
                throw new UnsupportedOperationException(label + " format " + format
                        + " lacks optimal-tiling features 0x" + Integer.toHexString(required & ~supported));
            }

            VkPhysicalDeviceImageFormatInfo2 imageInfo = VkPhysicalDeviceImageFormatInfo2.calloc(stack)
                    .sType$Default().format(format).type(VK10.VK_IMAGE_TYPE_2D)
                    .tiling(VK10.VK_IMAGE_TILING_OPTIMAL).usage(usage).flags(0);
            VkImageFormatProperties2 imageProperties = VkImageFormatProperties2.calloc(stack).sType$Default();
            int result = VK11.vkGetPhysicalDeviceImageFormatProperties2(
                    vk.getPhysicalDevice(), imageInfo, imageProperties);
            if (result == VK10.VK_ERROR_FORMAT_NOT_SUPPORTED) {
                throw new UnsupportedOperationException(label + " format " + format
                        + " does not support image usage 0x" + Integer.toHexString(usage));
            }
            check(result, "vkGetPhysicalDeviceImageFormatProperties2");
            if (width > imageProperties.imageFormatProperties().maxExtent().width()
                    || height > imageProperties.imageFormatProperties().maxExtent().height()) {
                throw new UnsupportedOperationException(label + " extent " + width + "x" + height
                        + " exceeds format maximum " + imageProperties.imageFormatProperties().maxExtent().width()
                        + "x" + imageProperties.imageFormatProperties().maxExtent().height());
            }
        }
    }

    /**
     * Record and submit one-shot graphics work, waiting for its fence before returning.
     * Use when initialization or readback must finish before CPU access or resource reuse.
     */
    public synchronized void submitSync(Consumer<VkCommandBuffer> record) {
        ensurePool();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPool).level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            check(VK10.vkAllocateCommandBuffers(vk, ai, pCmd), "vkAllocateCommandBuffers");
            boolean pending = false;
            try {
                VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), vk);
                RtDebugLabels.name(this, VK10.VK_OBJECT_TYPE_COMMAND_BUFFER, cmd.address(), "submitSync command buffer");

                VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                        .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                check(VK10.vkBeginCommandBuffer(cmd, bi), "vkBeginCommandBuffer");
                record.accept(cmd);
                check(VK10.vkEndCommandBuffer(cmd), "vkEndCommandBuffer");

                VkFenceCreateInfo fci = VkFenceCreateInfo.calloc(stack).sType$Default();
                LongBuffer pFence = stack.mallocLong(1);
                check(VK10.vkCreateFence(vk, fci, null, pFence), "vkCreateFence");
                long fence = pFence.get(0);
                try {
                    RtDebugLabels.name(this, VK10.VK_OBJECT_TYPE_FENCE, fence, "submitSync fence");

                    VkCommandBufferSubmitInfo.Buffer command = VkCommandBufferSubmitInfo.calloc(1, stack)
                            .sType$Default().commandBuffer(cmd);
                    VkSubmitInfo2.Buffer submit = VkSubmitInfo2.calloc(1, stack)
                            .sType$Default().pCommandBufferInfos(command);
                    synchronized (deviceQueueHostLock) {
                        check(VK13.vkQueueSubmit2(graphicsQueue.queue(), submit, fence), "vkQueueSubmit2");
                    }
                    pending = true;
                    check(VK10.vkWaitForFences(vk, pFence, true, Long.MAX_VALUE), "vkWaitForFences");
                    pending = false;
                } finally {
                    // A failed wait does not prove completion; pending resources must remain alive.
                    if (!pending) VK10.vkDestroyFence(vk, fence, null);
                }
            } finally {
                if (!pending) VK10.vkFreeCommandBuffers(vk, commandPool, pCmd);
            }
        }
    }

    public void waitIdle() {
        // vkDeviceWaitIdle is externally synchronized against every queue owned by the device.
        synchronized (deviceQueueHostLock) {
            GpuCrashHistory.record(GpuCrashHistory.Event.DEVICE_IDLE_BEGIN, vk.address(), 0, 0, 0);
            int result = VK10.vkDeviceWaitIdle(vk);
            GpuCrashHistory.record(GpuCrashHistory.Event.DEVICE_IDLE, vk.address(), 0, 0, result);
            checkDeviceResult(result, "vkDeviceWaitIdle");
        }
    }

    /**
     * Destroys every renderer-owned device object once, before the host destroys the device. Each step
     * runs even after an earlier one fails, including a lost device, because Vulkan requires child
     * objects to be destroyed before their device; the first failure is rethrown with the rest suppressed.
     */
    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        Throwable failure = runDestroyStep(null, gpuExecutor::stop);
        failure = runDestroyStep(failure, this::waitIdle);
        failure = runDestroyStep(failure, graphics::shutdownAfterDeviceIdle);
        failure = runDestroyStep(failure, gpuExecutor::destroyAfterDeviceIdle);
        failure = runDestroyStep(failure, () -> {
            if (commandPool != 0L) {
                VK10.vkDestroyCommandPool(vk, commandPool, null);
                commandPool = 0L;
            }
        });
        failure = runDestroyStep(failure, () -> {
            if (conventionalBindings != null) conventionalBindings.close();
        });
        failure = runDestroyStep(failure, descriptorHeap::close);
        failure = runDestroyStep(failure, () -> {
            if (vma != 0L) {
                VulkanDiagnostics.registerAllocator(0L);
                Vma.vmaDestroyAllocator(vma);
            }
        });
        failure = runDestroyStep(failure, memoryTelemetry::close);
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("Vulkan device context teardown failed", failure);
    }

    private static Throwable runDestroyStep(Throwable primary, Runnable step) {
        try {
            step.run();
        } catch (Throwable failure) {
            if (primary == null) return failure;
            if (primary != failure) primary.addSuppressed(failure);
        }
        return primary;
    }

    private void ensurePool() {
        if (commandPool != 0L) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo ci = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT | VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                    .queueFamilyIndex(graphicsQueue.familyIndex());
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateCommandPool(vk, ci, null, p), "vkCreateCommandPool");
            commandPool = p.get(0);
            RtDebugLabels.name(this, VK10.VK_OBJECT_TYPE_COMMAND_POOL, commandPool, "transient command pool");
        }
    }

    public static void check(int rc, String what) {
        if (rc != VK10.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: " + rc);
        }
    }

    /** Check a device operation and capture fault diagnostics before propagating device loss. */
    public void checkDeviceResult(int rc, String what) {
        if (rc == VK10.VK_ERROR_DEVICE_LOST) {
            VulkanDiagnostics.reportDeviceLost(vk, what, Map.of(
                    "graphics", graphicsQueue.queue(), "compute", computeQueue.queue()));
        }
        check(rc, what);
    }
}
