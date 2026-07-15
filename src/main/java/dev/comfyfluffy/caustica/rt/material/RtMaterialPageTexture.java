package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

/** One RGBA8 canonical material page with an explicitly uploaded semantic mip chain. */
final class RtMaterialPageTexture {
    private final long vma;
    private final VkDevice vk;
    private final long image;
    private final long allocation;
    private final long view;
    private boolean destroyed;

    RtMaterialPageTexture(RtContext ctx, int width, int height, List<byte[]> levels, String label) {
        if (levels.isEmpty()) throw new IllegalArgumentException("Material page has no mip levels");
        this.vma = ctx.vma();
        this.vk = ctx.vk();
        long createdImage = 0L;
        long createdAllocation = 0L;
        long createdView = 0L;
        RtBuffer staging = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK10.VK_IMAGE_TYPE_2D).format(VK10.VK_FORMAT_R8G8B8A8_UNORM)
                    .mipLevels(levels.size()).arrayLayers(1).samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(width, height, 1);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO);
            LongBuffer imageOut = stack.mallocLong(1);
            PointerBuffer allocationOut = stack.mallocPointer(1);
            check(Vma.vmaCreateImage(vma, imageInfo, allocationInfo, imageOut, allocationOut, null),
                    "vmaCreateImage(material page)");
            createdImage = imageOut.get(0);
            createdAllocation = allocationOut.get(0);
            RtDebugLabels.nameImage(ctx, createdImage, label);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(createdImage).viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK10.VK_FORMAT_R8G8B8A8_UNORM);
            viewInfo.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(levels.size()).baseArrayLayer(0).layerCount(1);
            LongBuffer viewOut = stack.mallocLong(1);
            check(VK10.vkCreateImageView(vk, viewInfo, null, viewOut), "vkCreateImageView(material page)");
            createdView = viewOut.get(0);
            RtDebugLabels.nameImageView(ctx, createdView, label + " view");

            long totalBytes = 0L;
            for (byte[] level : levels) totalBytes = Math.addExact(totalBytes, level.length);
            staging = ctx.createBuffer(totalBytes, VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true,
                    label + " upload");
            ByteBuffer mapped = MemoryUtil.memByteBuffer(staging.mapped, Math.toIntExact(totalBytes));
            long[] offsets = new long[levels.size()];
            int offset = 0;
            for (int i = 0; i < levels.size(); i++) {
                offsets[i] = offset;
                byte[] level = levels.get(i);
                mapped.position(offset).put(level);
                offset += level.length;
            }
            staging.flush();

            long uploadImage = createdImage;
            long uploadBuffer = staging.handle;
            ctx.submitSync(cmd -> {
                try (MemoryStack uploadStack = MemoryStack.stackPush()) {
                    VkImageMemoryBarrier.Buffer toTransfer = VkImageMemoryBarrier.calloc(1, uploadStack);
                    toTransfer.get(0).sType$Default()
                            .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                            .newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                            .srcAccessMask(0).dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                            .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                            .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(uploadImage);
                    toTransfer.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(levels.size()).baseArrayLayer(0).layerCount(1);
                    VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                            VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toTransfer);

                    VkBufferImageCopy.Buffer copies = VkBufferImageCopy.calloc(levels.size(), uploadStack);
                    int mipWidth = width;
                    int mipHeight = height;
                    for (int i = 0; i < levels.size(); i++) {
                        copies.get(i).bufferOffset(offsets[i]).bufferRowLength(0).bufferImageHeight(0);
                        copies.get(i).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                                .mipLevel(i).baseArrayLayer(0).layerCount(1);
                        copies.get(i).imageOffset().set(0, 0, 0);
                        copies.get(i).imageExtent().set(mipWidth, mipHeight, 1);
                        mipWidth = Math.max(1, mipWidth / 2);
                        mipHeight = Math.max(1, mipHeight / 2);
                    }
                    VK10.vkCmdCopyBufferToImage(cmd, uploadBuffer, uploadImage,
                            VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copies);

                    VkImageMemoryBarrier.Buffer toGeneral = VkImageMemoryBarrier.calloc(1, uploadStack);
                    toGeneral.get(0).sType$Default()
                            .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                            .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                            .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                            .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                            .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                            .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(uploadImage);
                    toGeneral.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(levels.size()).baseArrayLayer(0).layerCount(1);
                    VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                            VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, toGeneral);
                }
            });
        } catch (Throwable t) {
            if (createdView != 0L) VK10.vkDestroyImageView(vk, createdView, null);
            if (createdImage != 0L) Vma.vmaDestroyImage(vma, createdImage, createdAllocation);
            throw t;
        } finally {
            if (staging != null) staging.destroy();
        }
        this.image = createdImage;
        this.allocation = createdAllocation;
        this.view = createdView;
    }

    long view() {
        return view;
    }

    void destroy() {
        if (destroyed) return;
        VK10.vkDestroyImageView(vk, view, null);
        Vma.vmaDestroyImage(vma, image, allocation);
        destroyed = true;
    }

    private static void check(int result, String operation) {
        if (result != VK10.VK_SUCCESS) throw new IllegalStateException(operation + " failed: " + result);
    }
}
