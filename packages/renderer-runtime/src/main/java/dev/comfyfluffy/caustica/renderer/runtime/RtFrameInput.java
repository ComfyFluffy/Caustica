package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserRoute;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceExtent;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.WorldPushData.Float3;
import org.joml.Matrix4fc;

/**
 * Captured inputs shared by trace, reconstruction, presentation, and submitted-frame history. A null
 * {@code historyBreak} means the frame continues its predecessor's temporal history.
 */
record RtFrameInput(FrameSnapshot snapshot, long number, long nanos, TraceExtent extent,
                    DenoiserRoute route, float jitterX, float jitterY, float preExposure,
                    RtFrameHistory.Break historyBreak,
                    Matrix4fc projection, Matrix4fc viewRotation, Matrix4fc projectionView,
                    Matrix4fc previousProjectionView, Matrix4fc previousViewRotation,
                    Matrix4fc previousProjection, Float3 cameraOffset, Float3 cameraDelta,
                    float previousJitterX, float previousJitterY, float frameTimeMilliseconds,
                    float previousProceduralTime) {
    boolean historyContinuous() { return historyBreak == null; }
}
