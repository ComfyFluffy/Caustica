package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.minecraft.client.config.CausticaConfig;
import dev.comfyfluffy.caustica.renderer.runtime.RendererOptions;
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
    private static final long FRESH_FRAME_TIMEOUT_NANOS = 30_000_000_000L;

    private final CaptureProgress progress = new CaptureProgress();
    private long lastFreshFrameNanos;
    private int width;
    private int height;
    private long captureLease;

    private UltraScreenshot() {
    }

    public boolean active() {
        return UltraCaptureSession.ownedBy(UltraCaptureSession.Owner.ULTRA_SCREENSHOT);
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
        if (!readyForUltraScreenshot()) {
            notify(minecraft, Component.translatable("caustica.status.ultraScreenshot.requiresDlssRr"));
            return;
        }
        long lease = UltraCaptureSession.acquireScreenshot(true);
        if (lease == 0L) {
            notify(minecraft, Component.translatable("caustica.status.ultraScreenshot.busy"));
            return;
        }
        try {
            if (!UltraCaptureSession.begin(minecraft, UltraCaptureSession.Owner.ULTRA_SCREENSHOT)) {
                UltraCaptureSession.releaseScreenshot(lease);
                notify(minecraft, Component.translatable("caustica.status.ultraScreenshot.busy"));
                return;
            }
        } catch (Throwable t) {
            UltraCaptureSession.releaseScreenshot(lease);
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
            // Restart the jitter sequence and temporal history so the capture accumulates only DLAA frames.
            CausticaClientComposition.current().runtime().resetSceneHistory();
            notify(minecraft, Component.translatable("caustica.status.ultraScreenshot.started"));
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("Ultra screenshot setup failed", t);
            restore();
            notify(minecraft, Component.translatable("caustica.status.ultraScreenshot.failed"));
        }
    }

    private boolean readyForUltraScreenshot() {
        if (!CausticaClientComposition.current().runtime().frameActive()) {
            return false;
        }
        if (CausticaConfig.get(RendererOptions.Rt.Composite.DEBUG_VIEW) != 0) {
            return false;
        }
        return "ray_reconstruction".equals(CausticaConfig.get(RendererOptions.Rt.Denoising.ROUTE));
    }

    /** Per-render validation, including frames where the level compositor did not produce an image. */
    public void beginFrame(Minecraft minecraft) {
        if (!active()) {
            return;
        }
        if (!UltraCaptureSession.valid(minecraft)) {
            cancel(minecraft, Component.translatable("caustica.status.ultraScreenshot.invalidated"));
            return;
        }
        if (minecraft.getWindow().getWidth() != width
                || minecraft.getWindow().getHeight() != height) {
            cancel(minecraft, Component.translatable("caustica.status.ultraScreenshot.resized"));
            return;
        }
        if (!readyForUltraScreenshot()) {
            cancel(minecraft, Component.translatable("caustica.status.ultraScreenshot.requiresDlssRr"));
            return;
        }
        if (System.nanoTime() - lastFreshFrameNanos > FRESH_FRAME_TIMEOUT_NANOS) {
            cancel(minecraft, Component.translatable("caustica.status.ultraScreenshot.timedOut"));
        }
    }

    /** Called from {@code GameRenderer.render} at TAIL, after the final world, hand, and UI image exists. */
    public void frameRendered(Minecraft minecraft, boolean freshRtFrame) {
        if (!active() || !freshRtFrame) {
            return;
        }
        CaptureProgress.Result result = progress.acceptFreshFrame(
                CausticaClientComposition.current().runtime().jitterPhaseCount(
                        minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight()),
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
        if (!UltraCaptureSession.screenshotIsUltra(lease)) {
            cancel(minecraft, Component.translatable("caustica.status.ultraScreenshot.failed"));
            return;
        }

        // Once grab queues the asynchronous GPU copy, the completion callback owns the lease: vanilla
        // invokes it after the PNG write finishes or fails.
        boolean screenshotSubmitted = false;
        try {
            UltraCaptureSession.bindScreenshotThreadToken(lease);
            restoreForOutput();
            Screenshot.grab(minecraft.gameDirectory, minecraft.gameRenderer.mainRenderTarget(), message -> {
                UltraCaptureSession.releaseScreenshot(lease);
                minecraft.showDebugChat(message);
            });
            screenshotSubmitted = true;
        } catch (Throwable t) {
            CausticaMod.LOGGER.error("Ultra screenshot capture failed", t);
            notify(minecraft, Component.translatable("caustica.status.ultraScreenshot.failed"));
        } finally {
            UltraCaptureSession.clearScreenshotThreadToken(lease);
            if (!screenshotSubmitted) {
                UltraCaptureSession.releaseScreenshot(lease);
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
        UltraCaptureSession.discardScreenshotsForShutdown();
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
        restoreState(true);
    }

    private void restoreState(boolean retainLease) {
        UltraCaptureSession.end();
        progress.reset();
        lastFreshFrameNanos = 0L;
        width = 0;
        height = 0;
        if (!retainLease && captureLease != 0L) {
            UltraCaptureSession.releaseScreenshot(captureLease);
            captureLease = 0L;
        }
    }

    private static void notify(Minecraft minecraft, Component message) {
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(message);
        }
    }
}
