package dev.comfyfluffy.caustica.minecraft.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class UltraCaptureSessionTest {
    @Test
    void pauseOnlyAppliesToUnsharedIntegratedServers() {
        assertFalse(UltraCaptureSession.shouldPauseIntegratedServer(false, false));
        assertFalse(UltraCaptureSession.shouldPauseIntegratedServer(false, true));
        assertTrue(UltraCaptureSession.shouldPauseIntegratedServer(true, false));
        assertFalse(UltraCaptureSession.shouldPauseIntegratedServer(true, true));
    }

    @Test
    void screenshotLeaseBlocksOverlappingWrites() {
        long lease = UltraCaptureSession.acquireScreenshot(true);
        assertTrue(lease != 0L);
        assertEquals(0L, UltraCaptureSession.acquireScreenshot(true));
        assertTrue(UltraCaptureSession.screenshotIsUltra(lease));
        UltraCaptureSession.releaseScreenshot(lease);
        assertFalse(UltraCaptureSession.screenshotIsUltra(lease));

        long next = UltraCaptureSession.acquireScreenshot(false);
        assertTrue(next != 0L);
        assertFalse(UltraCaptureSession.screenshotIsUltra(next));
        // A stale token cannot release a replacement lease.
        UltraCaptureSession.releaseScreenshot(lease);
        assertTrue(next != 0L);
        UltraCaptureSession.releaseScreenshot(next);
        assertFalse(UltraCaptureSession.screenshotIsUltra(next));
    }

    @Test
    void threadTokenBindsAndClearsOnlyItsOwnValue() {
        assertTrue(UltraCaptureSession.screenshotThreadToken() == 0L);
        UltraCaptureSession.bindScreenshotThreadToken(7L);
        assertEquals(7L, UltraCaptureSession.screenshotThreadToken());
        UltraCaptureSession.clearScreenshotThreadToken(9L);
        assertEquals(7L, UltraCaptureSession.screenshotThreadToken());
        UltraCaptureSession.clearScreenshotThreadToken(7L);
        assertTrue(UltraCaptureSession.screenshotThreadToken() == 0L);
        UltraCaptureSession.discardScreenshotsForShutdown();
    }

    @Test
    void dlaaOverrideAppliesOnlyWhileAULTRACaptureOwnsTheSession() {
        assertEquals(1, UltraCaptureSession.effectiveDlssQuality(1));
        assertEquals(5, UltraCaptureSession.effectiveDlssQuality(5));
    }
}
