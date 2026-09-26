package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserRoute;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceExtent;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.WorldPushData.Float3;
import dev.comfyfluffy.caustica.renderer.runtime.pipeline.RtJitter;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Only submitted frames become temporal predecessors. Capturing or abandoning a frame changes nothing. */
final class RtFrameHistory {
    private static final float MIN_FORWARD_DOT = 0.70710677f; // cos(45 degrees)
    private static final float MAX_PROJECTION_RELATIVE_CHANGE = .1f;
    private static final double MAX_CAMERA_DISTANCE_SQUARED_METERS = 64;
    private static final double ANIMATION_PERIOD_SECONDS = 3600;
    private static final float MAX_ANIMATION_GAP_SECONDS = .25f;
    // Render frames without an RT composite (a screen transition's synchronous redraw) leave the last
    // submitted frame a valid predecessor; an interruption longer than this does not.
    private static final long MAX_PREDECESSOR_AGE_NANOS = 250_000_000L;

    /** Why a frame starts without temporal history. Each cause is a separate frame counter. */
    enum Break {
        /** No submitted predecessor: the first frame of a view, or the first after an explicit reset. */
        FIRST_FRAME("frame.historyBreak.firstFrame"),
        /** The predecessor is not an earlier frame submitted within the predecessor age. */
        INTERRUPTED("frame.historyBreak.interrupted"),
        /** The entry scene or its world scale changed. */
        SCENE("frame.historyBreak.scene"),
        EXTENT("frame.historyBreak.extent"),
        ROUTE("frame.historyBreak.route"),
        /** The camera turned, zoomed, or moved farther than reprojection can follow. */
        CAMERA_CUT("frame.historyBreak.cameraCut");

        final String metric;

        Break(String metric) { this.metric = metric; }
    }

    private RtFrameInput previous;
    private long samples;

    RtFrameInput capture(FrameSnapshot snapshot, long number, long nanos, TraceExtent extent,
                         DenoiserRoute route, float preExposure, float jitterSignX, float jitterSignY) {
        Matrix4f projection = snapshot.copyProjection();
        Matrix4f rotation = snapshot.copyViewRotation();
        Matrix4f projectionView = new Matrix4f(projection).mul(rotation);
        Break historyBreak = historyBreak(snapshot, number, nanos, extent, route, projection, rotation);
        boolean continuous = historyBreak == null;
        // Camera-relative projections use world motion; GPU instance history separately rebases scene origins.
        Float3 delta = continuous ? new Float3(
                (float) (snapshot.cameraX() - previous.snapshot().cameraX()),
                (float) (snapshot.cameraY() - previous.snapshot().cameraY()),
                (float) (snapshot.cameraZ() - previous.snapshot().cameraZ())) : new Float3(0, 0, 0);
        var jitter = route == DenoiserRoute.RAW ? new RtJitter.Sample(0, 0)
                : RtJitter.sample(samples, extent.renderWidth(), extent.displayWidth());
        float jitterX = jitter.x() * jitterSignX;
        float jitterY = jitter.y() * jitterSignY;
        float time = (float) (snapshot.timeSeconds() % ANIMATION_PERIOD_SECONDS);
        float priorTime = continuous ? (float) (previous.snapshot().timeSeconds() % ANIMATION_PERIOD_SECONDS) : time;
        if (time - priorTime < 0 || time - priorTime > MAX_ANIMATION_GAP_SECONDS) priorTime = time;
        return new RtFrameInput(snapshot, number, nanos, extent, route, jitterX, jitterY, preExposure,
                historyBreak, projection, rotation, projectionView,
                continuous ? previous.projectionView() : projectionView,
                continuous ? previous.viewRotation() : rotation,
                continuous ? previous.projection() : projection,
                new Float3(snapshot.sceneOrigin().relativeX(snapshot.cameraX()),
                        snapshot.sceneOrigin().relativeY(snapshot.cameraY()),
                        snapshot.sceneOrigin().relativeZ(snapshot.cameraZ())), delta,
                continuous ? previous.jitterX() : jitterX, continuous ? previous.jitterY() : jitterY,
                continuous ? (nanos - previous.nanos()) * 1.0e-6f : 0, priorTime);
    }

    void submitted(RtFrameInput frame) {
        previous = frame;
        if (frame.route() != DenoiserRoute.RAW) samples++;
    }

    boolean changesScene(FrameSnapshot snapshot) {
        return previous == null || previous.snapshot().view().entryScene() != snapshot.view().entryScene();
    }

    void reset() { previous = null; }

    private Break historyBreak(FrameSnapshot snapshot, long number, long nanos, TraceExtent extent,
                               DenoiserRoute route, Matrix4fc projection, Matrix4fc rotation) {
        if (previous == null) return Break.FIRST_FRAME;
        if (number <= previous.number() || nanos - previous.nanos() > MAX_PREDECESSOR_AGE_NANOS) return Break.INTERRUPTED;
        if (previous.snapshot().view().entryScene() != snapshot.view().entryScene()
                || previous.snapshot().metersPerWorldUnit() != snapshot.metersPerWorldUnit()) return Break.SCENE;
        if (!previous.extent().equals(extent)) return Break.EXTENT;
        if (previous.route() != route) return Break.ROUTE;
        if (cameraCut(previous, snapshot, projection, rotation)) return Break.CAMERA_CUT;
        return null;
    }

    private static boolean cameraCut(RtFrameInput previous, FrameSnapshot snapshot,
                                     Matrix4fc projection, Matrix4fc rotation) {
        Matrix4fc oldRotation = previous.viewRotation();
        float forwardDot = rotation.m02() * oldRotation.m02() + rotation.m12() * oldRotation.m12()
                + rotation.m22() * oldRotation.m22();
        return forwardDot < MIN_FORWARD_DOT
                || relativeDifference(projection.m00(), previous.projection().m00()) > MAX_PROJECTION_RELATIVE_CHANGE
                || relativeDifference(projection.m11(), previous.projection().m11()) > MAX_PROJECTION_RELATIVE_CHANGE
                || distanceSquared(snapshot, previous.snapshot()) * snapshot.metersPerWorldUnit()
                        * snapshot.metersPerWorldUnit() > MAX_CAMERA_DISTANCE_SQUARED_METERS;
    }

    private static double distanceSquared(FrameSnapshot a, FrameSnapshot b) {
        double x = a.cameraX() - b.cameraX(), y = a.cameraY() - b.cameraY(), z = a.cameraZ() - b.cameraZ();
        return x * x + y * y + z * z;
    }

    private static float relativeDifference(float a, float b) {
        return Math.abs(a - b) / Math.max(Math.max(Math.abs(a), Math.abs(b)), 1.0e-6f);
    }
}
