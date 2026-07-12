package dev.comfyfluffy.caustica.rt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.lwjgl.vulkan.VkSubmitInfo;

import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtImage;

import java.nio.LongBuffer;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR;

/**
 * Shared per-device RT resources: a buffer-device-address-enabled VMA allocator (vanilla's
 * lacks the flag), the graphics queue + a transient command pool for synchronous one-shot
 * submits, and the RT pipeline limits (SBT handle size / alignment). Single owner for the
 * plumbing every RT module needs; obtained lazily via {@link #get}.
 */
public final class RtContext {
    private static RtContext instance;
    private static boolean unavailable;

    private final VulkanDevice device;
    private final VkDevice vk;
    private final long vma;
    private final VulkanQueue graphicsQueue;
    private final VulkanQueue computeQueue;
    private final RtGpuExecutor gpuExecutor;
    private final int shaderGroupHandleSize;
    private final int shaderGroupBaseAlignment;
    private final int accelerationStructureScratchAlignment;
    private long commandPool;

    private RtContext(VulkanDevice device, long vma, int handleSize, int baseAlign, int scratchAlign) {
        this.device = device;
        this.vk = device.vkDevice();
        this.vma = vma;
        this.graphicsQueue = device.graphicsQueue();
        this.computeQueue = device.computeQueue();
        this.shaderGroupHandleSize = handleSize;
        this.shaderGroupBaseAlignment = baseAlign;
        this.accelerationStructureScratchAlignment = scratchAlign;
        this.gpuExecutor = new RtGpuExecutor(this);
    }

    /** The RT context for the current Vulkan device, or null if RT/Vulkan isn't available. */
    public static RtContext get() {
        if (instance != null) {
            return instance;
        }
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return null;
        }
        return get(device);
    }

    public static synchronized RtContext get(VulkanDevice device) {
        if (instance != null || unavailable) {
            return instance;
        }
        if (device.computeQueue().vkQueue().address() == device.graphicsQueue().vkQueue().address()) {
            unavailable = true;
            CausticaMod.LOGGER.warn("Caustica RT disabled: Vulkan compute queue aliases graphics queue");
            return null;
        }
        instance = create(device);
        return instance;
    }

    public static RtContext currentOrNull() {
        return instance;
    }

    private static RtContext create(VulkanDevice device) {
        VkDevice vk = device.vkDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDevice phys = vk.getPhysicalDevice();

            // BDA-enabled allocator (vanilla's createVma omits the flag).
            VmaVulkanFunctions fns = VmaVulkanFunctions.calloc(stack).set(phys.getInstance(), vk);
            VmaAllocatorCreateInfo aci = VmaAllocatorCreateInfo.calloc(stack)
                    .flags(Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT)
                    .instance(phys.getInstance())
                    .vulkanApiVersion(VK12.VK_API_VERSION_1_2)
                    .device(vk)
                    .physicalDevice(phys)
                    .pVulkanFunctions(fns);
            PointerBuffer pVma = stack.mallocPointer(1);
            check(Vma.vmaCreateAllocator(aci, pVma), "vmaCreateAllocator(RT)");

            // RT pipeline limits for SBT layout.
            VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps = VkPhysicalDeviceRayTracingPipelinePropertiesKHR
                    .calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR);
            VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps =
                    VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            rtProps.pNext(asProps.address());
            VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(rtProps.address());
            VK12.vkGetPhysicalDeviceProperties2(phys, props2);

            return new RtContext(device, pVma.get(0), rtProps.shaderGroupHandleSize(), rtProps.shaderGroupBaseAlignment(),
                    asProps.minAccelerationStructureScratchOffsetAlignment());
        }
    }

    public VulkanDevice device() {
        return device;
    }

    public VkDevice vk() {
        return vk;
    }

    public long vma() {
        return vma;
    }

    public RtGpuExecutor gpuExecutor() {
        return gpuExecutor;
    }

    public int shaderGroupHandleSize() {
        return shaderGroupHandleSize;
    }

    public int shaderGroupBaseAlignment() {
        return shaderGroupBaseAlignment;
    }

    public int accelerationStructureScratchAlignment() {
        return accelerationStructureScratchAlignment;
    }

    /** Create a VMA buffer; {@code SHADER_DEVICE_ADDRESS} is always added so it has a device address. */
    public RtBuffer createBuffer(long size, int usage, boolean hostVisible) {
        return createBuffer(size, usage, hostVisible, "buffer " + size + "B");
    }

    /** Create a VMA buffer; {@code SHADER_DEVICE_ADDRESS} is always added so it has a device address. */
    public RtBuffer createBuffer(long size, int usage, boolean hostVisible, String label) {
        return createBuffer(size, usage, hostVisible, label, false);
    }

    /** Create a buffer shared by the graphics and async-compute families when those families differ. */
    public RtBuffer createAsyncBuffer(long size, int usage, boolean hostVisible, String label) {
        return createBuffer(size, usage, hostVisible, label, true);
    }

    private RtBuffer createBuffer(long size, int usage, boolean hostVisible, String label, boolean asyncShared) {
        long handle = 0L;
        long allocation = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size).usage(usage | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            if (asyncShared && graphicsQueue.queueFamilyIndex() != computeQueue.queueFamilyIndex()) {
                bci.sharingMode(VK10.VK_SHARING_MODE_CONCURRENT)
                        .pQueueFamilyIndices(stack.ints(graphicsQueue.queueFamilyIndex(), computeQueue.queueFamilyIndex()));
            }
            VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO);
            if (hostVisible) {
                aci.flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            }
            LongBuffer pBuf = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
            check(Vma.vmaCreateBuffer(vma, bci, aci, pBuf, pAlloc, info), "vmaCreateBuffer");
            handle = pBuf.get(0);
            allocation = pAlloc.get(0);
            RtDebugLabels.nameBuffer(this, handle, label);
            VkBufferDeviceAddressInfo bdai = VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(handle);
            long address = VK12.vkGetBufferDeviceAddress(vk, bdai);
            return new RtBuffer(vma, handle, allocation, address, hostVisible ? info.pMappedData() : 0L, size, usage, hostVisible);
        } catch (Throwable t) {
            if (handle != 0L) {
                Vma.vmaDestroyBuffer(vma, handle, allocation);
            }
            throw t;
        }
    }

    /** Create an R8G8B8A8_UNORM storage image (STORAGE + TRANSFER_SRC/DST) already transitioned to GENERAL. */
    public RtImage createStorageImage(int width, int height) {
        return createStorageImage(width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM);
    }

    /**
     * Create a storage image of the given format (STORAGE + TRANSFER_SRC/DST), transitioned to GENERAL.
     * The RT trace target uses an HDR float format (R16G16B16A16_SFLOAT) so radiance values above 1 are
     * preserved for the tonemap seam; the world-target copy stays R8G8B8A8 to match vanilla's LDR target
     * for the vkCmdCopyImage round-trip (copy requires texel-size-compatible formats).
     */
    public RtImage createStorageImage(int width, int height, int format) {
        return createStorageImage(width, height, format, "storage image " + width + "x" + height);
    }

    public RtImage createStorageImage(int width, int height, int format, String label) {
        return createStorageImage(width, height, format, label, 0);
    }

    /**
     * Same as {@link #createStorageImage(int, int, int, String)} plus caller-supplied usage bits — e.g.
     * {@code VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT} for an image a graphics pipeline renders into via
     * dynamic rendering (a plain storage image is invalid as a {@code VkRenderingInfo} colour attachment;
     * see {@code VUID-VkRenderingInfo-colorAttachmentCount-06087}).
     */
    public RtImage createStorageImage(int width, int height, int format, String label, int extraUsage) {
        long image;
        long allocation;
        long view;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK10.VK_IMAGE_TYPE_2D).format(format)
                    .mipLevels(1).arrayLayers(1).samples(VK10.VK_SAMPLE_COUNT_1_BIT).tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                    // SAMPLED so DLSS-RR can read these as input textures (color + guide buffers);
                    // STORAGE for raygen/compute writes; TRANSFER for the world-target copies.
                    .usage(VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT
                            | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT | extraUsage)
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
        }
        long imageFinal = image;
        submitSync(cmd -> {
            try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope ignored = RtDebugLabels.scope(this, cmd, "init " + label)) {
                VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
                b.get(0).sType$Default().oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .srcAccessMask(0).dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT
                                | VK10.VK_ACCESS_TRANSFER_READ_BIT | VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .image(imageFinal);
                b.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                        0, null, null, b);
            }
        });
        return new RtImage(vma, vk, image, allocation, view, width, height);
    }

    /**
     * A multisampled colour attachment for a raster mask pass that gets dynamic-rendering-resolved into a
     * single-sample target immediately afterwards (see {@code RtWorldOverlay.beginMsaaColorRendering}) —
     * e.g. the block outline's 4x MSAA edge-AA pass. {@code COLOR_ATTACHMENT_BIT | TRANSIENT_ATTACHMENT_BIT}
     * only: unlike {@link #createStorageImage}, this is never sampled/stored/copied, and multisample images
     * generally can't carry {@code STORAGE_BIT} anyway ({@code storageImageSampleCounts} is a separate,
     * often-unsupported device limit). Kept in {@code GENERAL} layout like every other image here.
     */
    public RtImage createTransientMsaaColorImage(int width, int height, int format, int samples, String label) {
        long image;
        long allocation;
        long view;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK10.VK_IMAGE_TYPE_2D).format(format)
                    .mipLevels(1).arrayLayers(1).samples(samples).tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK10.VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT)
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
        }
        long imageFinal = image;
        submitSync(cmd -> {
            try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope ignored = RtDebugLabels.scope(this, cmd, "init " + label)) {
                VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
                b.get(0).sType$Default().oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .srcAccessMask(0).dstAccessMask(VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .image(imageFinal);
                b.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                        0, null, null, b);
            }
        });
        return new RtImage(vma, vk, image, allocation, view, width, height);
    }

    /**
     * Record + submit a one-shot command buffer synchronously (own pool + queue submit + fence).
     * Use for init work that must complete before a CPU read or before the buffers are reused —
     * Blaze3D's {@code VulkanCommandEncoder.execute()} only defers into the frame's submission.
     */
    public synchronized void submitSync(Consumer<VkCommandBuffer> record) {
        ensurePool();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPool).level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            check(VK10.vkAllocateCommandBuffers(vk, ai, pCmd), "vkAllocateCommandBuffers");
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
            RtDebugLabels.name(this, VK10.VK_OBJECT_TYPE_FENCE, fence, "submitSync fence");

            VkSubmitInfo si = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cmd));
            check(VK10.vkQueueSubmit(graphicsQueue.vkQueue(), si, fence), "vkQueueSubmit");
            check(VK10.vkWaitForFences(vk, pFence, true, Long.MAX_VALUE), "vkWaitForFences");

            VK10.vkDestroyFence(vk, fence, null);
            VK10.vkFreeCommandBuffers(vk, commandPool, pCmd);
        }
    }

    public void waitIdle() {
        check(VK10.vkDeviceWaitIdle(vk), "vkDeviceWaitIdle");
    }

    public void destroy() {
        gpuExecutor.shutdown();
        if (commandPool != 0L) {
            VK10.vkDestroyCommandPool(vk, commandPool, null);
            commandPool = 0L;
        }
        if (vma != 0L) {
            Vma.vmaDestroyAllocator(vma);
        }
        instance = null;
        unavailable = false;
    }

    private void ensurePool() {
        if (commandPool != 0L) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo ci = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT | VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                    .queueFamilyIndex(graphicsQueue.queueFamilyIndex());
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
}
