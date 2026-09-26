package dev.comfyfluffy.caustica.renderer.runtime.pipeline;

/** Sub-pixel Halton jitter in render-pixel space for temporal reconstruction. */
public final class RtJitter {
    private RtJitter() { }

    public record Sample(float x, float y) { }

    public static Sample sample(long submittedFrames, int renderWidth, int displayWidth) {
        int index = (int) (submittedFrames % jitterPhaseCount(renderWidth, displayWidth)) + 1;
        return new Sample(halton(index, 2) - .5f, halton(index, 3) - .5f);
    }

    /** Jitter phases in one full sequence; a finite capture accumulates exactly this many frames. */
    public static int phaseCount(int renderWidth, int displayWidth) {
        return jitterPhaseCount(renderWidth, displayWidth);
    }

    static int jitterPhaseCount(int renderWidth, int displayWidth) {
        float ratio = (float) displayWidth / Math.max(1, renderWidth);
        return Math.max(32, (int) Math.ceil(8.0f * ratio * ratio));
    }

    private static float halton(int index, int base) {
        float fraction = 1.0f;
        float result = 0.0f;
        while (index > 0) {
            fraction /= base;
            result += fraction * (index % base);
            index /= base;
        }
        return result;
    }
}
