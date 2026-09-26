package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.api.vulkan.OwnedGpuImage;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GraphicsUse;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.OwnedCommandBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanBarriers;

import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Converts an SDR host frame to PQ and submits its swapchain blit. */
final class SdrPqPresentation {
    private final VulkanDeviceContext context;
    private RtSdrPresentPipeline pipeline;
    private GpuImage image;

    SdrPqPresentation(VulkanDeviceContext context) {
        this.context = context;
    }

    void present(GraphicsSubmission submission, AcquiredSwapchainTarget target,
            OwnedGpuImage source, float uiNits) {
        if (pipeline == null) {
            pipeline = RtSdrPresentPipeline.create(context);
        }
        if (image == null || image.width() != target.width() || image.height() != target.height()) {
            if (image != null) {
                // Submitted conversions and swapchain blits still use the previous-size image.
                context.waitIdle();
                image.destroy();
            }
            image = context.createStorageImage(target.width(), target.height(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "RT SDR->PQ present image " + target.width() + "x" + target.height());
        }
        GraphicsUse use = context.graphics().beginGraphicsUse();
        try (OwnedCommandBuffer commands = context.beginGraphicsCommands("SDR to PQ presentation", true);
             MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer commandBuffer = commands.commandBuffer();
            use.keepAlive(source.retain());
            VulkanBarriers.memoryBarrier(commandBuffer, stack);

            pipeline.dispatch(commandBuffer, image,
                    source.descriptor(dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind.SAMPLED).index(),
                    uiNits);
            HdrPresentation.recordSwapchainBlit(
                    commandBuffer, stack, image.image(), target.image(), target.width(), target.height());
            submission.waitSemaphore(target.acquireSemaphore(), 0L, VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            commands.submit(submission, use);
            submission.signalSemaphore(target.presentSemaphore(), 0L, VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT);
        } finally {
            context.graphics().resolveGraphicsUse(submission, use);
        }
    }

    void destroy() {
        if (pipeline != null) {
            pipeline.destroy();
            pipeline = null;
        }
        if (image != null) {
            image.destroy();
            image = null;
        }
    }
}
