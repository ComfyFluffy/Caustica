package dev.comfyfluffy.caustica.client;

/** Counts only fresh renderer frames in one fixed phase and resolution. */
final class CaptureProgress {
    enum Result {
        WAITING,
        COMPLETE,
        INVALID_PHASE,
        DIMENSIONS_CHANGED
    }

    private int freshFrames;
    private int targetFrames;
    private int width;
    private int height;

    Result acceptFreshFrame(int phaseCount, int frameWidth, int frameHeight) {
        if (phaseCount <= 0) {
            return Result.INVALID_PHASE;
        }
        if (targetFrames == 0) {
            targetFrames = phaseCount;
            width = frameWidth;
            height = frameHeight;
        } else if (phaseCount != targetFrames || frameWidth != width || frameHeight != height) {
            return Result.DIMENSIONS_CHANGED;
        }
        if (freshFrames >= targetFrames) {
            return Result.WAITING;
        }
        return ++freshFrames == targetFrames ? Result.COMPLETE : Result.WAITING;
    }

    void reset() {
        freshFrames = 0;
        targetFrames = 0;
        width = 0;
        height = 0;
    }

    int freshFrames() {
        return freshFrames;
    }

    int targetFrames() {
        return targetFrames;
    }
}
