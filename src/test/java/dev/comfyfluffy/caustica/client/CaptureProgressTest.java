package dev.comfyfluffy.caustica.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CaptureProgressTest {
    @Test
    void completesOnlyAfterTheDerivedFreshFrameCount() {
        CaptureProgress progress = new CaptureProgress();
        for (int i = 1; i < 32; i++) {
            assertEquals(CaptureProgress.Result.WAITING, progress.acceptFreshFrame(32, 3840, 2160));
        }
        assertEquals(CaptureProgress.Result.COMPLETE, progress.acceptFreshFrame(32, 3840, 2160));
        assertEquals(32, progress.freshFrames());
        assertEquals(32, progress.targetFrames());
        assertEquals(CaptureProgress.Result.WAITING, progress.acceptFreshFrame(32, 3840, 2160));
        assertEquals(32, progress.freshFrames());
    }

    @Test
    void rejectsPhaseOrResolutionChangesInsteadOfMixingFrames() {
        CaptureProgress progress = new CaptureProgress();
        progress.acceptFreshFrame(32, 3840, 2160);
        assertEquals(CaptureProgress.Result.DIMENSIONS_CHANGED,
                progress.acceptFreshFrame(33, 3840, 2160));
        progress.reset();
        progress.acceptFreshFrame(32, 3840, 2160);
        assertEquals(CaptureProgress.Result.DIMENSIONS_CHANGED,
                progress.acceptFreshFrame(32, 2560, 1440));
        assertEquals(CaptureProgress.Result.INVALID_PHASE,
                new CaptureProgress().acceptFreshFrame(0, 3840, 2160));
    }
}
