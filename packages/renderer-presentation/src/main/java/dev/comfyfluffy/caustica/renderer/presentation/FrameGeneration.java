package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanBarriers;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GraphicsUse;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.OwnedCommandBuffer;

import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.nvidia.ngx.DlssFrameGeneration;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCopyImageInfo2;
import org.lwjgl.vulkan.VkImageCopy2;

/** Owns DLSS frame-generation captures, interpolation outputs, and temporal evaluation state. */
final class FrameGeneration {
    private final VulkanDeviceContext context;
    private final DlssFrameGeneration backend;
    private GpuImage hudlessImage;
    private GpuImage hdrHudlessImage;
    private GpuImage interpolationImage;
    private boolean reset = true;
    private final Matrix4f clipToPrevious = new Matrix4f();
    private final Matrix4f previousToClip = new Matrix4f();
    private final Matrix4f matrixScratch = new Matrix4f();

    FrameGeneration(VulkanDeviceContext context, DlssFrameGeneration backend) {
        this.context = context;
        this.backend = backend;
    }

    boolean enabled() {
        return backend.enabled();
    }

    void resetHistory() {
        reset = true;
    }

    void captureHudless(BorrowedImage source, UiPresentationResources ui) {
        if (!backend.enabled() || !ui.enabled() || source.image() == 0L) {
            return;
        }
        int width = source.width();
        int height = source.height();
        if (hudlessImage == null || hudlessImage.width() != width || hudlessImage.height() != height) {
            if (hudlessImage != null) {
                // Submitted captures and interpolations still reference the previous-size image.
                context.waitIdle();
                hudlessImage.destroy();
            }
            hudlessImage = context.createStorageImage(
                    width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM, "FG hudless capture " + width + "x" + height);
        }
        GraphicsSubmission submission = context.backend().createGraphicsSubmission();
        VkCommandBuffer commandBuffer = submission.beginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanBarriers.memoryBarrier(commandBuffer, stack);
            copyImage(commandBuffer, stack, source.image(), hudlessImage.image(), width, height);
            VulkanBarriers.memoryBarrier(commandBuffer, stack);
        }
        if (VK10.vkEndCommandBuffer(commandBuffer) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg hudless capture) failed");
        }
        submission.execute(commandBuffer);
    }

    void captureHdrHudless(VkCommandBuffer commandBuffer, MemoryStack stack,
                           dev.comfyfluffy.caustica.api.vulkan.GpuImage source) {
        if (hdrHudlessImage == null
                || hdrHudlessImage.width() != source.width() || hdrHudlessImage.height() != source.height()) {
            if (hdrHudlessImage != null) {
                // Submitted captures and interpolations still reference the previous-size image.
                context.waitIdle();
                hdrHudlessImage.destroy();
            }
            hdrHudlessImage = context.createStorageImage(source.width(), source.height(),
                    VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "FG HDR hudless capture (PQ) " + source.width() + "x" + source.height());
        }
        VulkanBarriers.memoryBarrier(commandBuffer, stack);
        copyImage(commandBuffer, stack, source.image(), hdrHudlessImage.image(),
                source.width(), source.height());
        VulkanBarriers.memoryBarrier(commandBuffer, stack);
    }

    GpuImage interpolate(GraphicsSubmission submission, RtFramePresenter.RenderedFrame frame, BorrowedImage backbuffer,
            int swapWidth, int swapHeight, boolean hdrBackbuffer,
            UiPresentationResources ui) {
        if (frame == null || frame.depth() == null || frame.motion() == null) {
            return null;
        }
        int format = hdrBackbuffer
                ? VK10.VK_FORMAT_R16G16B16A16_SFLOAT : VK10.VK_FORMAT_R8G8B8A8_UNORM;
        if (!ensureFeature(swapWidth, swapHeight,
                frame.renderWidth(), frame.renderHeight(), format)) {
            throw new IllegalStateException("DLSSG feature not ready (ensureFeature failed)");
        }
        ensureInterpolationImage(swapWidth, swapHeight, format);
        matrixScratch.set(frame.currentViewProjection()).invert();
        clipToPrevious.set(frame.previousViewProjection()).mul(matrixScratch);
        matrixScratch.set(frame.previousViewProjection()).invert();
        previousToClip.set(frame.currentViewProjection()).mul(matrixScratch);
        GpuImage hudless = hdrBackbuffer ? hdrHudlessImage : hudlessImage;
        boolean hudlessReady = hudless != null && hudless.width() == swapWidth && hudless.height() == swapHeight;
        // Presentation may have consumed the populated flag; interpolation still samples that frame's UI texture.
        boolean uiReady = ui.width() == swapWidth && ui.height() == swapHeight
                && ui.colorView() != 0L && ui.colorImage() != 0L;

        GraphicsUse use = context.graphics().beginGraphicsUse();
        try (OwnedCommandBuffer commands = context.beginGraphicsCommands("DLSS frame generation", false);
             MemoryStack stack = MemoryStack.stackPush()) {
            if (uiReady) use.keepAlive(ui.color().retain());
            VkCommandBuffer commandBuffer = commands.commandBuffer();
            VulkanBarriers.memoryBarrier(commandBuffer, stack);
            boolean evaluated = backend.evaluate(commandBuffer,
                    backbuffer.view(), backbuffer.image(), backbuffer.format(),
                    frame.depth().view(), frame.depth().image(), VK10.VK_FORMAT_R32_SFLOAT,
                    frame.motion().view(), frame.motion().image(), VK10.VK_FORMAT_R16G16_SFLOAT,
                    hudlessReady ? hudless.view() : 0L, hudlessReady ? hudless.image() : 0L,
                    hudlessReady ? format : 0,
                    uiReady ? ui.colorView() : 0L, uiReady ? ui.colorImage() : 0L,
                    uiReady ? VK10.VK_FORMAT_R8G8B8A8_UNORM : 0,
                    interpolationImage.view(), interpolationImage.image(), format,
                    swapWidth, swapHeight, frame.renderWidth(), frame.renderHeight(), 1.0f, 1.0f,
                    true, hdrBackbuffer, true, reset, clipToPrevious, previousToClip);
            if (!evaluated) {
                throw new IllegalStateException("ngxshim_evaluate_dlssg_2x failed");
            }
            commands.submit(submission, use);
            reset = false;
        } finally {
            context.graphics().resolveGraphicsUse(submission, use);
        }
        return interpolationImage;
    }

    private boolean ensureFeature(int width, int height,
            int renderWidth, int renderHeight, int format) {
        if (backend.featureReadyFor(width, height, renderWidth, renderHeight, format)) {
            return true;
        }
        context.waitIdle();
        context.submitSync(commandBuffer -> backend.ensureFeature(
                commandBuffer, width, height, renderWidth, renderHeight, format));
        reset = true;
        return backend.featureReadyFor(width, height, renderWidth, renderHeight, format);
    }

    private void ensureInterpolationImage(int width, int height, int format) {
        if (interpolationImage != null && interpolationImage.width() == width
                && interpolationImage.height() == height && interpolationImage.format() == format) {
            return;
        }
        destroyInterpolationImage();
        interpolationImage = context.createStorageImage(
                width, height, format, "FG interpolation " + width + "x" + height);
    }

    void destroy() {
        if (hudlessImage != null) {
            hudlessImage.destroy();
            hudlessImage = null;
        }
        if (hdrHudlessImage != null) {
            hdrHudlessImage.destroy();
            hdrHudlessImage = null;
        }
        destroyInterpolationImage();
        reset = true;
    }

    private void destroyInterpolationImage() {
        if (interpolationImage != null) {
            interpolationImage.destroy();
            interpolationImage = null;
        }
    }

    private static void copyImage(VkCommandBuffer commandBuffer, MemoryStack stack,
                                  long source, long destination, int width, int height) {
        VkImageCopy2.Buffer region = VkImageCopy2.calloc(1, stack);
        region.get(0).sType$Default();
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).extent().set(width, height, 1);
        VK13.vkCmdCopyImage2(commandBuffer, VkCopyImageInfo2.calloc(stack).sType$Default()
                .srcImage(source).srcImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .dstImage(destination).dstImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .pRegions(region));
    }
}
