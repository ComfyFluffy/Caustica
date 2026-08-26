package dev.comfyfluffy.caustica.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CaptureSessionPolicyTest {
    @AfterEach
    void clearScreenshotLease() {
        CaptureSession.discardScreenshotsForShutdown();
    }

    @Test
    void pausesOnlyAnUnsharedIntegratedServer() {
        assertTrue(CaptureSession.shouldPauseIntegratedServer(true, false));
        assertFalse(CaptureSession.shouldPauseIntegratedServer(true, true));
        assertFalse(CaptureSession.shouldPauseIntegratedServer(false, false));
    }

    @Test
    void screenshotLeaseBlocksOverlappingWritesUntilCompletion() {
        long f4 = CaptureSession.acquireScreenshot(true);
        assertNotEquals(0L, f4);
        assertTrue(CaptureSession.screenshotIsUltra(f4));
        assertTrue(CaptureSession.acquireScreenshot(false) == 0L);

        CaptureSession.releaseScreenshot(f4);
        assertFalse(CaptureSession.screenshotIsUltra(f4));
    }

    @Test
    void lateScreenshotCallbackCannotReleaseAReplacement() {
        long old = CaptureSession.acquireScreenshot(true);
        CaptureSession.discardScreenshotsForShutdown();
        long current = CaptureSession.acquireScreenshot(true);

        CaptureSession.releaseScreenshot(old);
        assertNotEquals(0L, current);
        assertTrue(CaptureSession.screenshotIsUltra(current));
    }

    @Test
    void captureFailureRetriesTheConfiguredRendererAfterRestore() {
        assertFalse(UltraScreenshot.shouldRecoverRenderer(false, false));
        assertTrue(UltraScreenshot.shouldRecoverRenderer(true, false));
        assertTrue(UltraScreenshot.shouldRecoverRenderer(false, true));
        assertTrue(UltraScreenshot.shouldRecoverRenderer(true, true));

        List<String> steps = new ArrayList<>();
        int configuredQuality = 2;
        AtomicInteger effectiveQuality = new AtomicInteger(UltraScreenshot.DLAA_QUALITY);
        Throwable failure = UltraScreenshot.restoreRendererState(
                true,
                () -> {
                    steps.add("end-capture");
                    effectiveQuality.set(configuredQuality);
                },
                () -> {
                    steps.add("retry-renderer");
                    assertEquals(configuredQuality, effectiveQuality.get());
                },
                () -> steps.add("reset-temporal"));

        assertNull(failure);
        assertEquals(List.of("end-capture", "retry-renderer", "reset-temporal"), steps);
    }

    @Test
    void captureRecoveryFailureRemainsReportedAndStillResetsTemporalState() {
        List<String> steps = new ArrayList<>();
        IllegalStateException releaseFailure = new IllegalStateException("native feature retained");

        Throwable failure = UltraScreenshot.restoreRendererState(
                true,
                () -> steps.add("end-capture"),
                () -> {
                    steps.add("retry-renderer");
                    throw releaseFailure;
                },
                () -> steps.add("reset-temporal"));

        assertSame(releaseFailure, failure);
        assertEquals(List.of("end-capture", "retry-renderer", "reset-temporal"), steps);
    }
}
