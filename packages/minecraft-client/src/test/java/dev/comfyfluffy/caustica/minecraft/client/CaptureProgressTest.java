package dev.comfyfluffy.caustica.minecraft.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CaptureProgressTest {
    @Test
    void completesExactlyAtTheTargetFreshFrameAndStaysWaiting() {
        var progress = new CaptureProgress();
        for (int i = 1; i < 32; i++) {
            assertEquals(CaptureProgress.Result.WAITING,
                    progress.acceptFreshFrame(32, 3840, 2160));
            assertEquals(i, progress.freshFrames());
        }
        assertEquals(CaptureProgress.Result.COMPLETE, progress.acceptFreshFrame(32, 3840, 2160));
        assertEquals(32, progress.freshFrames());
        assertEquals(32, progress.targetFrames());
        assertEquals(CaptureProgress.Result.WAITING, progress.acceptFreshFrame(32, 3840, 2160));
    }

    @Test
    void phaseCountChangeIsADimensionChange() {
        var progress = new CaptureProgress();
        assertEquals(CaptureProgress.Result.WAITING, progress.acceptFreshFrame(32, 3840, 2160));
        assertEquals(CaptureProgress.Result.DIMENSIONS_CHANGED, progress.acceptFreshFrame(72, 3840, 2160));
    }

    @Test
    void resolutionChangeIsADimensionChange() {
        var progress = new CaptureProgress();
        assertEquals(CaptureProgress.Result.WAITING, progress.acceptFreshFrame(32, 3840, 2160));
        assertEquals(CaptureProgress.Result.DIMENSIONS_CHANGED, progress.acceptFreshFrame(32, 1920, 1080));
    }

    @Test
    void invalidPhaseIsRejected() {
        var progress = new CaptureProgress();
        assertEquals(CaptureProgress.Result.INVALID_PHASE, progress.acceptFreshFrame(0, 3840, 2160));
        assertEquals(0, progress.freshFrames());
    }

    @Test
    void resetRestartsCounting() {
        var progress = new CaptureProgress();
        assertEquals(CaptureProgress.Result.WAITING, progress.acceptFreshFrame(32, 3840, 2160));
        progress.reset();
        assertEquals(0, progress.freshFrames());
        assertEquals(0, progress.targetFrames());
        assertEquals(CaptureProgress.Result.WAITING, progress.acceptFreshFrame(32, 3840, 2160));
    }
}
