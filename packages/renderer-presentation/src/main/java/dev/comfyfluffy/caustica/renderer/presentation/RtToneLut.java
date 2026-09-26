package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.vulkan.VmaImageAllocation;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VK14;
import org.lwjgl.vulkan.VkBufferImageCopy2;
import org.lwjgl.vulkan.VkCopyBufferToImageInfo2;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageDescriptorInfoEXT;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * A baked ACES look or display transform: one RGBA16F 3D mip uploaded from a classpath resource.
 * The resource header carries its size and shaper range; see {@code tools/bake_display_lut.py}.
 */
public final class RtToneLut {
    private static final int MAGIC = 0x54554C43; // "CLUT" little-endian
    private static final int HEADER_BYTES = 4 + 4 + 4 + 4 + 4; // magic, version, size, loStops, hiStops
    // Display shader contract. Reject incompatible resources at load time instead of silently sampling
    // them with shaders/pipelines/display/main.comp.slang's fixed shaper.
    private static final float SHADER_SHAPER_LO_STOPS = -12.0f;
    private static final float SHADER_SHAPER_HI_STOPS = 12.0f;

    /** Mastering peaks of the packaged ACES 2.0 HDR LUTs, {@code hdr_aces2_rec2020_<nits>nit.bin}. */
    public static final List<Integer> HDR_LUT_NITS = List.of(500, 1000, 2000, 4000);

    /** The packaged HDR LUT mastering peak nearest the display peak; ties take the lower. */
    public static int nearestHdrLutNits(float peakNits) {
        int nearest = HDR_LUT_NITS.getFirst();
        for (int candidate : HDR_LUT_NITS) {
            if (Math.abs(peakNits - candidate) < Math.abs(peakNits - nearest)) {
                nearest = candidate;
            }
        }
        return nearest;
    }

    private final VmaImageAllocation imageAllocation;
    private final GpuDescriptorRange<GpuDescriptorIndex.Resource> sampledDescriptor;
    private final GpuDescriptorRange<GpuDescriptorIndex.Sampler> samplerDescriptor;
    public final int size;
    private boolean destroyed;

    private RtToneLut(VmaImageAllocation imageAllocation,
                       GpuDescriptorRange<GpuDescriptorIndex.Resource> sampledDescriptor,
                       GpuDescriptorRange<GpuDescriptorIndex.Sampler> samplerDescriptor,
                       int size) {
        this.imageAllocation = imageAllocation;
        this.sampledDescriptor = sampledDescriptor;
        this.samplerDescriptor = samplerDescriptor;
        this.size = size;
    }

    public GpuDescriptorIndex.Resource sampledIndex() {
        return sampledDescriptor.firstIndex();
    }

    public GpuDescriptorIndex.Sampler samplerIndex() {
        return samplerDescriptor.firstIndex();
    }

    /** Loads a display-transform resource from {@code /caustica/color/luts/}. */
    public static RtToneLut load(VulkanDeviceContext ctx, String resourceName) {
        String path = "/caustica/color/luts/" + resourceName;
        ByteBuffer data = readResource(path).order(ByteOrder.LITTLE_ENDIAN);
        int magic = data.getInt(0);
        if (magic != MAGIC) {
            throw new IllegalStateException(path + ": bad magic 0x" + Integer.toHexString(magic));
        }
        int version = data.getInt(4);
        if (version != 1) {
            throw new IllegalStateException(path + ": unsupported version " + version);
        }
        int size = data.getInt(8);
        float loStops = data.getFloat(12);
        float hiStops = data.getFloat(16);
        if (size < 2) {
            throw new IllegalStateException(path + ": invalid LUT size " + size);
        }
        if (loStops != SHADER_SHAPER_LO_STOPS || hiStops != SHADER_SHAPER_HI_STOPS) {
            throw new IllegalStateException(path + ": LUT shaper " + loStops + ".." + hiStops
                    + " does not match display shader " + SHADER_SHAPER_LO_STOPS + ".."
                    + SHADER_SHAPER_HI_STOPS);
        }
        long texelCount = (long) size * size * size;
        long expectedBytes = HEADER_BYTES + texelCount * 4L * 2L; // RGBA16F
        if (data.remaining() != expectedBytes) {
            throw new IllegalStateException(path + ": expected " + expectedBytes + " bytes, got "
                    + data.remaining() + " (size=" + size + ")");
        }
        ByteBuffer texels = data.slice(HEADER_BYTES, (int) (expectedBytes - HEADER_BYTES));
        return upload(ctx, size, texels, path);
    }

    private static RtToneLut upload(VulkanDeviceContext ctx, int size, ByteBuffer texels, String label) {
        VmaImageAllocation createdImage = null;
        GpuDescriptorRange<GpuDescriptorIndex.Resource> sampledDescriptor = null;
        GpuDescriptorRange<GpuDescriptorIndex.Sampler> samplerDescriptor = null;
        GpuBuffer staging = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK10.VK_IMAGE_TYPE_3D).format(VK10.VK_FORMAT_R16G16B16A16_SFLOAT)
                    .mipLevels(1).arrayLayers(1).samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(size, size, size);
            createdImage = VmaImageAllocation.create(ctx, imageInfo, "tone lut " + label);
            RtDebugLabels.nameImage(ctx, createdImage.image(), "tone LUT " + label);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(createdImage.image()).viewType(VK10.VK_IMAGE_VIEW_TYPE_3D)
                    .format(VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
            viewInfo.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            // Edge-aligned LUT: the shaper's [0,1] domain maps texel 0's centre to input 0 and texel
            // (size-1)'s centre to input 1 (see tools/bake_display_lut.py). CLAMP_TO_EDGE holds the
            // boundary texel for any exposed value outside the shaper's ±stops range instead of
            // wrapping or reading black.
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0f).maxLod(0f);
            sampledDescriptor = ctx.descriptorHeap().allocateResources(1);
            samplerDescriptor = ctx.descriptorHeap().allocateSamplers(1);
            VkImageDescriptorInfoEXT imageDescriptor = VkImageDescriptorInfoEXT.calloc(stack).sType$Default()
                    .pView(viewInfo).layout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkResourceDescriptorInfoEXT resource = VkResourceDescriptorInfoEXT.calloc(stack).sType$Default()
                    .type(VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).data(value -> value.pImage(imageDescriptor));
            ctx.descriptorHeap().writer().writeResource(sampledDescriptor, 0, resource);
            ctx.descriptorHeap().writer().writeSampler(samplerDescriptor, 0, samplerInfo);

            int totalBytes = texels.remaining();
            staging = ctx.createUploadBuffer(totalBytes, "tone lut " + label + " upload");
            ByteBuffer mapped = MemoryUtil.memByteBuffer(staging.mapped(), totalBytes);
            mapped.put(texels.duplicate());
            staging.flush();

            long uploadImage = createdImage.image();
            long uploadBuffer = staging.handle();
            ctx.submitSync(cmd -> {
                try (MemoryStack uploadStack = MemoryStack.stackPush()) {
                    VkImageMemoryBarrier2.Buffer toTransfer = VkImageMemoryBarrier2.calloc(1, uploadStack);
                    toTransfer.get(0).sType$Default()
                            .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                            .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                            .srcStageMask(VK13.VK_PIPELINE_STAGE_2_NONE).srcAccessMask(VK13.VK_ACCESS_2_NONE)
                            .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                            .dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                            .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                            .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(uploadImage);
                    toTransfer.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                    VK14.vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(uploadStack)
                            .sType$Default().pImageMemoryBarriers(toTransfer));

                    VkBufferImageCopy2.Buffer copy = VkBufferImageCopy2.calloc(1, uploadStack);
                    copy.get(0).sType$Default();
                    copy.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
                    copy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .mipLevel(0).baseArrayLayer(0).layerCount(1);
                    copy.get(0).imageOffset().set(0, 0, 0);
                    copy.get(0).imageExtent().set(size, size, size);
                    VK13.vkCmdCopyBufferToImage2(cmd, VkCopyBufferToImageInfo2.calloc(uploadStack)
                            .sType$Default().srcBuffer(uploadBuffer).dstImage(uploadImage)
                            .dstImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).pRegions(copy));

                    // Keep GENERAL to match the sampled-image descriptor while making the upload visible.
                    VkImageMemoryBarrier2.Buffer toRead = VkImageMemoryBarrier2.calloc(1, uploadStack);
                    toRead.get(0).sType$Default()
                            .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                            .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                            .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                            .srcAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                            .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                            .dstAccessMask(VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                            .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                            .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(uploadImage);
                    toRead.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                    VK14.vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(uploadStack)
                            .sType$Default().pImageMemoryBarriers(toRead));
                }
            });
        } catch (Throwable t) {
            if (samplerDescriptor != null) samplerDescriptor.destroy();
            if (sampledDescriptor != null) sampledDescriptor.destroy();
            if (createdImage != null) createdImage.close();
            throw t;
        } finally {
            if (staging != null) staging.destroy();
        }
        return new RtToneLut(createdImage, sampledDescriptor, samplerDescriptor, size);
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        samplerDescriptor.destroy();
        sampledDescriptor.destroy();
        imageAllocation.close();
        destroyed = true;
    }

    private static ByteBuffer readResource(String path) {
        try (InputStream in = RtToneLut.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing LUT resource: " + path);
            }
            return ByteBuffer.wrap(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("failed to read LUT resource: " + path, e);
        }
    }
}
