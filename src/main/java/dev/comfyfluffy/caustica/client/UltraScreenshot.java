package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** One-shot DLAA screenshot capture over one complete low-discrepancy jitter phase. */
public final class UltraScreenshot {
    public static final UltraScreenshot INSTANCE = new UltraScreenshot();
    public static final KeyMapping KEY = new KeyMapping(
            "key.caustica.ultra_screenshot", GLFW.GLFW_KEY_F4, CausticaKeyMappings.CATEGORY);

    static final int DLAA_QUALITY = 5;
    static final int SCREENSHOT_SPP = 8;
    private static final long FRESH_FRAME_TIMEOUT_NANOS = 30_000_000_000L;

    private final CaptureProgress progress = new CaptureProgress();
    private long lastFreshFrameNanos;
    private int width;
    private int height;
    private long captureLease;

    private UltraScreenshot() {
    }

    public boolean active() {
        return CaptureSession.ownedBy(CaptureSession.Owner.ULTRA_SCREENSHOT);
    }

    public void toggle(Minecraft minecraft) {
        if (active()) {
            cancel(minecraft, Component.translatable("caustica.status.ultraScreenshot.cancelled"));
            return;
        }
        if (minecraft.level == null || minecraft.player == null) {
            notify(minecraft, Component.translatable("caustica.status.ultraScreenshot.requiresWorld"));
            return;
        }
        if (minecraft.gui.screen() != null) {
            notify(minecraft, Component.translatable("caustica.status.ultraScreenshot.busy"));
            return;
        }
        if (!CausticaConfig.Rt.ENABLED.value() || !CausticaConfig.Rt.DlssRr.ENABLED.value()
                || CausticaConfig.Rt.Composite.DEBUG_VIEW.value() != 0
                || !RtComposite.INSTANCE.readyForUltraScreenshot()) {
            notify(minecraft, Component.translatable("caustica.status.ultraScreenshot.requiresDlssRr"));
            return;
        }
        long lease = CaptureSession.acquireScreenshot(true);
        if (lease == 0L) {
            notify(minecraft, Component.translatable("caustica.status.ultraScreenshot.busy"));
            return;
        }
        try {
            if (!CaptureSession.begin(minecraft, CaptureSession.Owner.ULTRA_SCREENSHOT)) {
                CaptureSession.releaseScreenshot(lease);
                notify(minecraft, Component.translatable("caustica.status.ultraScreenshot.busy"));
                return;
            }
        } catch (Throwable t) {
            CaptureSession.releaseScreenshot(lease);
            CausticaMod.LOGGER.error("Ultra screenshot session start failed", t);
            notify(minecraft, Component.translatable("caustica.status.ultraScreenshot.failed"));
            return;
        }
        captureLease = lease;
        try {
            progress.reset();
            width = minecraft.getWindow().getWidth();
            height = minecraft.getWindow().getHeight();
            lastFreshFrameNanos = System.nanoTime();
            // Reset jitter and reconstruction history while retaining the valid exposure image owned by the session.
            RtComposite.INSTANCE.requestTemporalReset(false);
            notify(minecraft, Component.translatable("caustica.status.ultraScreenshot.started", SCREENSHOT_SPP));
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("Ultra screenshot setup failed", t);
            restore();
            notify(minecraft, Component.translatable("caustica.status.ultraScreenshot.failed"));
        }
    }

    /** Per-render validation, including frames where the level compositor did not produce an image. */
    public void beginFrame(Minecraft minecraft) {
        if (!active()) {
            return;
        }
        if (!CaptureSession.valid(minecraft)) {
            cancel(minecraft, Component.translatable("caustica.status.ultraScreenshot.invalidated"));
            return;
        }
        if (minecraft.getWindow().getWidth() != width
                || minecraft.getWindow().getHeight() != height) {
            cancel(minecraft, Component.translatable("caustica.status.ultraScreenshot.resized"));
            return;
        }
        if (!RtDlssRr.enabled() || RtDlssRr.INSTANCE.hasFailed() || RtComposite.INSTANCE.hasFailed()) {
            cancel(minecraft, Component.translatable("caustica.status.ultraScreenshot.failed"));
            return;
        }
        if (System.nanoTime() - lastFreshFrameNanos > FRESH_FRAME_TIMEOUT_NANOS) {
            cancel(minecraft, Component.translatable("caustica.status.ultraScreenshot.timedOut"));
        }
    }

    /** Called from {@code GameRenderer.render} at TAIL, after the final world, hand, and UI image exists. */
    public void frameRendered(Minecraft minecraft) {
        if (!active() || !RtComposite.INSTANCE.producedFreshDlssRrFrame()) {
            return;
        }
        CaptureProgress.Result result = progress.acceptFreshFrame(
                RtComposite.INSTANCE.currentJitterPhaseCount(),
                minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
        if (result == CaptureProgress.Result.INVALID_PHASE) {
            cancel(minecraft, Component.translatable("caustica.status.ultraScreenshot.failed"));
            return;
        }
        if (result == CaptureProgress.Result.DIMENSIONS_CHANGED) {
            cancel(minecraft, Component.translatable("caustica.status.ultraScreenshot.resized"));
            return;
        }
        lastFreshFrameNanos = System.nanoTime();
        if (result != CaptureProgress.Result.COMPLETE) {
            return;
        }

        long lease = captureLease;
        if (!CaptureSession.screenshotIsUltra(lease)) {
            cancel(minecraft, Component.translatable("caustica.status.ultraScreenshot.failed"));
            return;
        }

        // The wrapped callback owns the lease after grab queues the asynchronous GPU copy.
        boolean screenshotSubmitted = false;
        try {
            CaptureSession.bindScreenshotThreadToken(lease);
            restoreForOutput();
            Screenshot.grab(minecraft, false);
            screenshotSubmitted = true;
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("Ultra screenshot capture failed", t);
            notify(minecraft, Component.translatable("caustica.status.ultraScreenshot.failed"));
        } finally {
            CaptureSession.clearScreenshotThreadToken(lease);
            if (!screenshotSubmitted) {
                CaptureSession.releaseScreenshot(lease);
            }
            captureLease = 0L;
        }
    }

    public void abort(Component reason) {
        if (!active()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        restore();
        notify(minecraft, reason);
    }

    /** Ends an active renderer-owned capture. */
    public void stopRenderer() {
        if (active()) {
            restore();
        }
    }

    public void shutdown() {
        stopRenderer();
        captureLease = 0L;
        CaptureSession.discardScreenshotsForShutdown();
    }

    private void cancel(Minecraft minecraft, Component reason) {
        if (!active()) {
            return;
        }
        restore();
        notify(minecraft, reason);
    }

    private void restore() {
        restoreState(false);
    }

    private void restoreForOutput() {
        Throwable failure = restoreState(true);
        if (failure != null) {
            throw new IllegalStateException("Ultra screenshot cleanup failed", failure);
        }
    }

    private Throwable restoreState(boolean retainLease) {
        boolean recoverRenderer = shouldRecoverRenderer(
                RtDlssRr.INSTANCE.hasFailed(), RtComposite.INSTANCE.hasFailed());
        Throwable failure = restoreRendererState(
                recoverRenderer,
                CaptureSession::end,
                RtComposite.INSTANCE::resetFailureLatch,
                RtComposite.INSTANCE::requestTemporalReset);
        progress.reset();
        lastFreshFrameNanos = 0L;
        width = 0;
        height = 0;
        if (!retainLease && captureLease != 0L) {
            CaptureSession.releaseScreenshot(captureLease);
            captureLease = 0L;
        }
        if (failure != null) {
            CausticaMod.LOGGER.error("Ultra screenshot cleanup failed", failure);
        }
        return failure;
    }

    static Throwable restoreRendererState(boolean recoverRenderer,
                                           Runnable endCapture,
                                           Runnable resetFailure,
                                           Runnable resetTemporal) {
        Throwable failure = null;
        try {
            endCapture.run();
        } catch (Throwable t) {
            failure = t;
        }
        if (recoverRenderer) {
            try {
                // CaptureSession.end restores the configured RR quality before ordinary rendering retries.
                resetFailure.run();
            } catch (Throwable t) {
                failure = appendFailure(failure, t);
            }
        }
        try {
            resetTemporal.run();
        } catch (Throwable t) {
            failure = appendFailure(failure, t);
        }
        return failure;
    }

    static boolean shouldRecoverRenderer(boolean rrFailed, boolean compositeFailed) {
        return rrFailed || compositeFailed;
    }

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static void notify(Minecraft minecraft, Component message) {
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(message);
        }
    }

}
