package dev.comfyfluffy.caustica.renderer.presentation;


import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK14;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;

import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT;

/** Owns extra swapchain-image acquisition and the generated-before-real deferred present queue. */
final class GeneratedFrameQueue {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeneratedFrameQueue.class);
    private static final long ACQUIRE_TIMEOUT_NS = 5_000_000_000L;

    private final VulkanDeviceContext context;
    private long[] acquireSemaphores = new long[0];
    private int acquireCursor;
    private PendingPresent pending;
    private boolean failed;

    private record PendingPresent(int imageIndex, long semaphore) { }

    GeneratedFrameQueue(VulkanDeviceContext context) {
        this.context = context;
    }

    boolean failed() {
        return failed;
    }

    void prepare(GraphicsSubmission submission, PresentationSwapchain swapchain,
            BorrowedImage source, boolean hdrBackbuffer,
            UiPresentationResources ui, RtFramePresenter.RenderedFrame frame, FrameGeneration generation) {
        pending = null;
        if (failed || swapchain.swapchain() == 0L || source.image() == 0L) {
            return;
        }
        try {
            ensureCapacity(swapchain.device(), swapchain.images().size() + 1);
            GpuImage interpolation = generation.interpolate(submission, frame, source,
                    swapchain.width(), swapchain.height(), hdrBackbuffer, ui);
            if (interpolation == null) {
                return;
            }
            long blitSource = interpolation.image();
            int copyWidth = Math.min(swapchain.width(), interpolation.width());
            int copyHeight = Math.min(swapchain.height(), interpolation.height());
            long acquireSemaphore = acquireSemaphores[acquireCursor];
            acquireCursor = (acquireCursor + 1) % acquireSemaphores.length;

            int acquiredIndex;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer imageIndex = stack.callocInt(1);
                int result = KHRSwapchain.vkAcquireNextImageKHR(
                        swapchain.device(), swapchain.swapchain(), ACQUIRE_TIMEOUT_NS,
                        acquireSemaphore, 0L, imageIndex);
                if (result == VK_ERROR_OUT_OF_DATE_KHR) {
                    return;
                }
                if (result != VK10.VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
                    throw new IllegalStateException("vkAcquireNextImageKHR(FG) failed: " + result);
                }
                acquiredIndex = imageIndex.get(0);
            }
            PresentationSwapchain.Image target = swapchain.images().get(acquiredIndex);
            recordBlit(submission, blitSource, target.image(),
                    copyWidth, copyHeight, acquireSemaphore, target.presentSemaphore());
            pending = new PendingPresent(acquiredIndex, target.presentSemaphore());
        } catch (Throwable error) {
            failed = true;
            LOGGER.error("DLSS-FG present-record failed; frame generation disabled", error);
        }
    }

    void flush(PresentationSwapchain swapchain, VkQueue presentQueue) {
        if (!failed && pending != null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack).sType$Default();
                present.pWaitSemaphores(stack.longs(pending.semaphore()));
                present.swapchainCount(1);
                present.pSwapchains(stack.longs(swapchain.swapchain()));
                present.pImageIndices(stack.ints(pending.imageIndex()));
                int result = context.synchronizedQueueOperation(
                        () -> KHRSwapchain.vkQueuePresentKHR(presentQueue, present));
                if (result != VK10.VK_SUCCESS && result != VK_SUBOPTIMAL_KHR
                        && result != VK_ERROR_OUT_OF_DATE_KHR) {
                    throw new IllegalStateException("vkQueuePresentKHR(FG) failed: " + result);
                }
            } catch (Throwable error) {
                failed = true;
                LOGGER.error("DLSS-FG present failed; frame generation disabled", error);
            } finally {
                pending = null;
            }
        }
    }

    private void recordBlit(GraphicsSubmission submission, long srcImage, long dstImage, int copyW, int copyH,
            long acquireSem, long presentSem) {
        VkCommandBuffer cmd = submission.beginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            HdrPresentation.recordSwapchainBlit(cmd, stack, srcImage, dstImage, copyW, copyH);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg blit) failed");
        }
        enqueuePresent(submission, cmd, acquireSem, presentSem);
    }

    static void enqueuePresent(GraphicsSubmission submission, VkCommandBuffer commandBuffer,
            long acquireSemaphore, long presentSemaphore) {
        submission.waitSemaphore(acquireSemaphore, 0L, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
        submission.execute(commandBuffer);
        submission.signalSemaphore(presentSemaphore, 0L, VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT);
    }

    /**
     * Grows the acquire ring for a swapchain with more images. Submitted blits may still wait on the
     * existing semaphores, so they stay in the ring until {@link #destroy} runs after device idle.
     */
    private void ensureCapacity(VkDevice device, int semaphoreCount) {
        if (acquireSemaphores.length >= semaphoreCount) {
            return;
        }
        int existing = acquireSemaphores.length;
        acquireSemaphores = Arrays.copyOf(acquireSemaphores, semaphoreCount);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            LongBuffer semaphore = stack.mallocLong(1);
            for (int i = existing; i < semaphoreCount; i++) {
                if (VK10.vkCreateSemaphore(device, info, null, semaphore) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSemaphore(fg acquire) failed");
                }
                acquireSemaphores[i] = semaphore.get(0);
            }
        }
    }

    void destroy(VkDevice device) {
        destroyAcquireSemaphores(device);
        pending = null;
        failed = false;
    }

    private void destroyAcquireSemaphores(VkDevice device) {
        for (long semaphore : acquireSemaphores) {
            if (semaphore != 0L) {
                VK10.vkDestroySemaphore(device, semaphore, null);
            }
        }
        acquireSemaphores = new long[0];
        acquireCursor = 0;
    }

}
