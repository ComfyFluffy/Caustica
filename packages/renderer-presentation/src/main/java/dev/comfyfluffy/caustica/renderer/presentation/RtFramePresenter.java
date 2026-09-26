package dev.comfyfluffy.caustica.renderer.presentation;


import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.OwnedGpuImage;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.nvidia.ngx.DlssFrameGeneration;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueue;

import java.util.Objects;
import java.util.function.Supplier;

/** Coordinates the presentation components owned by one renderer activation. */
public final class RtFramePresenter {
    private final DlssFrameGeneration dlssFrameGeneration;
    private final Supplier<Settings> settings;
    private final GeneratedFrameQueue generatedFrames;
    private final FrameGeneration frameGeneration;
    private final HdrPresentation hdrPresentation;
    private final SdrPqPresentation sdrPqPresentation;
    private RenderedFrame renderedFrame;

    public record Settings(boolean hdrEnabled, boolean pqSwapchainActive, float uiNits) {}

    public record RenderedFrame(GpuImage hdrDisplayImage, GpuImage motion, GpuImage depth,
            int renderWidth, int renderHeight, Matrix4f currentViewProjection,
            Matrix4f previousViewProjection, boolean hdrReady) {
        public RenderedFrame {
            currentViewProjection = new Matrix4f(currentViewProjection);
            previousViewProjection = new Matrix4f(previousViewProjection);
        }
    }

    public RtFramePresenter(VulkanDeviceContext context, DlssFrameGeneration dlssFrameGeneration,
            Supplier<Settings> settings) {
        this.dlssFrameGeneration = Objects.requireNonNull(dlssFrameGeneration, "dlssFrameGeneration");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.generatedFrames = new GeneratedFrameQueue(context);
        this.frameGeneration = new FrameGeneration(context, dlssFrameGeneration);
        this.hdrPresentation = new HdrPresentation(context, frameGeneration);
        this.sdrPqPresentation = new SdrPqPresentation(context);
    }

    public void beginFrame() {
        invalidateRenderedFrame();
    }

    public void invalidateRenderedFrame() {
        renderedFrame = null;
    }

    public void resetSceneHistory() {
        renderedFrame = null;
        frameGeneration.resetHistory();
    }

    public void publish(RenderedFrame frame) {
        renderedFrame = frame;
    }

    public boolean isActive(boolean sceneAvailable) {
        return !generatedFrames.failed() && dlssFrameGeneration.enabled()
                && dlssFrameGeneration.isAvailable() && sceneAvailable;
    }

    public void prepareGeneratedFrame(GraphicsSubmission submission, PresentationSwapchain swapchain,
            BorrowedImage source, boolean hdrBackbuffer, UiPresentationResources ui) {
        generatedFrames.prepare(submission, swapchain, source, hdrBackbuffer, ui, renderedFrame, frameGeneration);
    }

    public void prepareGeneratedFrame(GraphicsSubmission submission, PresentationSwapchain swapchain,
            GpuImage source, boolean hdrBackbuffer, UiPresentationResources ui) {
        prepareGeneratedFrame(submission, swapchain, BorrowedImage.of(source), hdrBackbuffer, ui);
    }

    public void flushPendingPresent(PresentationSwapchain swapchain, VkQueue presentQueue) {
        generatedFrames.flush(swapchain, presentQueue);
    }

    public void destroy(VkDevice device) {
        destroyGpuResources();
        generatedFrames.destroy(device);
        dlssFrameGeneration.destroyAfterDeviceIdle();
    }

    private void destroyGpuResources() {
        renderedFrame = null;
        frameGeneration.destroy();
        hdrPresentation.destroy();
        sdrPqPresentation.destroy();
    }

    public boolean isHdrPresentActive() {
        return isHdrPresentActive(settings.get());
    }

    private boolean isHdrPresentActive(Settings current) {
        return current.hdrEnabled() && renderedFrame != null
                && renderedFrame.hdrReady() && renderedFrame.hdrDisplayImage() != null;
    }

    public GpuImage hdrBackbuffer() {
        RenderedFrame frame = renderedFrame;
        return frame == null ? null : frame.hdrDisplayImage();
    }

    public void presentHdr(GraphicsSubmission submission, AcquiredSwapchainTarget target,
            UiPresentationResources ui) {
        RenderedFrame frame = renderedFrame;
        if (frame == null || !frame.hdrReady()) {
            throw new IllegalStateException("No rendered HDR frame is available for presentation");
        }
        hdrPresentation.present(submission, frame, target, ui, settings.get().uiNits());
    }

    public boolean isPqSdrPresentActive() {
        Settings current = settings.get();
        return current.pqSwapchainActive() && !isHdrPresentActive(current);
    }

    /** Records conversion and presentation, retaining the source through GPU completion. */
    public void presentSdrToPq(GraphicsSubmission submission, AcquiredSwapchainTarget target,
            OwnedGpuImage source) {
        sdrPqPresentation.present(submission, target, source, settings.get().uiNits());
    }

    public void captureHudless(BorrowedImage source, UiPresentationResources ui) {
        frameGeneration.captureHudless(source, ui);
    }

}
