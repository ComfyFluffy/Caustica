package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SceneView;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserRoute;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceExtent;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.WorldPushData.Float3;
import dev.comfyfluffy.caustica.renderer.runtime.pipeline.RtJitter;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class RtFrameHistoryTest {
    private static final SceneId SCENE = new SceneId() { };
    private static final TraceExtent EXTENT = new TraceExtent(1280, 720, 1920, 1080);
    private static final DenoiserRoute ROUTE = DenoiserRoute.TEMPORAL_DENOISER;

    @Test
    void debugProjectionRetainsSubmittedTransformAndCamera() {
        var projection = new Matrix4f().perspective(1.1f, 16f / 9f, .05f, 500f, true);
        var rotation = new Matrix4f().rotateY(.7f).rotateX(.2f);
        var frame = capture(new RtFrameHistory(), snapshot(SCENE, new SceneOrigin(512, 0, 0),
                523.25, 3, 2, false, projection, rotation), 0);
        var metadata = RtFrameRenderer.DebugProjection.capture(frame);
        var expected = new Matrix4f(projection).mul(rotation).invert().get(new float[16]);
        for (int i = 0; i < 16; i++) assertEquals(expected[i], metadata.inverseProjectionView().get(i), 1e-6f);
        assertEquals(java.util.List.of(523.25, 0.0, 0.0), metadata.cameraWorld());
        assertEquals(1280, metadata.renderWidth());
        assertEquals(720, metadata.renderHeight());
        assertEquals(java.util.List.of(frame.jitterX(), frame.jitterY()), metadata.jitterPixels());
        assertEquals(2, metadata.metersPerWorldUnit());
        assertThrows(UnsupportedOperationException.class, () -> metadata.inverseProjectionView().set(0, 0f));
    }

    @Test
    void captureDoesNotPublishCameraOrAdvanceJitter() {
        var history = new RtFrameHistory();
        capture(history, snapshot(7, 1), 0);
        var retry = capture(history, snapshot(0, 2), 0);
        assertEquals(RtFrameHistory.Break.FIRST_FRAME, retry.historyBreak());
        assertTrue(history.changesScene(retry.snapshot()));
        assertEquals(RtJitter.sample(0, 1280, 1920).x(), retry.jitterX());
        assertEquals(new Float3(0, 0, 0), retry.cameraDelta());
        assertEquals(retry.projectionView(), retry.previousProjectionView());
        assertEquals(retry.jitterY(), retry.previousJitterY());
        assertEquals(0, retry.frameTimeMilliseconds());
    }

    @Test
    void abandonedCaptureLeavesLastSubmissionAsThePredecessor() {
        var history = new RtFrameHistory();
        var first = capture(history, snapshot(1, 1), 10);
        history.submitted(first);
        capture(history, snapshot(7, 1.01), 11);
        var retry = capture(history, snapshot(3, 1.02), 11);
        assertTrue(retry.historyContinuous());
        assertEquals(new Float3(2, 0, 0), retry.cameraDelta());
        assertEquals(first.projectionView(), retry.previousProjectionView());
        assertEquals(first.jitterX(), retry.previousJitterX());
        assertEquals(first.jitterY(), retry.previousJitterY());
        assertEquals(16, retry.frameTimeMilliseconds(), .0001f);
        assertEquals(RtJitter.sample(1, 1280, 1920).x(), retry.jitterX());
        history.submitted(retry);
        var next = capture(history, snapshot(4, 1.03), 12);
        assertEquals(new Float3(1, 0, 0), next.cameraDelta());
        assertEquals(retry.jitterY(), next.previousJitterY());
        assertEquals(RtJitter.sample(2, 1280, 1920).x(), next.jitterX());
    }

    @Test
    void longInterruptionsAndExplicitResetDiscardCameraHistory() {
        var history = seeded();
        assertReset(history.capture(snapshot(1, .02), 2, 300_000_000L, EXTENT, ROUTE, 1, 1, 1),
                RtFrameHistory.Break.INTERRUPTED);
        history.reset();
        var reset = capture(history, snapshot(1, .02), 1);
        assertReset(reset, RtFrameHistory.Break.FIRST_FRAME);
        assertEquals(RtJitter.sample(1, 1280, 1920).x(), reset.jitterX());
        assertTrue(history.changesScene(reset.snapshot()));
    }

    @Test
    void sceneScaleExtentAndRouteChangesResetHistory() {
        assertReset(capture(seeded(), snapshot(new SceneId() { }, SceneOrigin.ZERO, 0, .01,
                1, false, new Matrix4f(), new Matrix4f()), 1), RtFrameHistory.Break.SCENE);
        assertReset(capture(seeded(), snapshot(SCENE, SceneOrigin.ZERO, 0, .01,
                2, false, new Matrix4f(), new Matrix4f()), 1), RtFrameHistory.Break.SCENE);
        assertReset(seeded().capture(snapshot(0, .01), 1, 16_000_000L,
                new TraceExtent(960, 540, 1920, 1080), ROUTE, 1, 1, 1), RtFrameHistory.Break.EXTENT);
        assertReset(seeded().capture(snapshot(0, .01), 1, 16_000_000L,
                EXTENT, DenoiserRoute.RAY_RECONSTRUCTION, 1, 1, 1), RtFrameHistory.Break.ROUTE);
        assertFalse(seeded().changesScene(snapshot(0, .01)));
    }

    @Test
    void completedRevisionOriginLagPreservesWorldCameraMotionAndSubmittedHistory() {
        var history = new RtFrameHistory();
        var previousOrigin = new SceneOrigin(30_000_000.25, -30_000_000.25, 1024.25);
        var requestedOrigin = new SceneOrigin(30_000_032.25, -29_999_968.25, 1056.25);
        var completedOrigin = new SceneOrigin(30_000_016.25, -29_999_984.25, 1040.25);
        var projection = new Matrix4f().m00(1.25f);
        var rotation = new Matrix4f().rotationY(.15f);
        var previousCamera = new Camera(30_000_000.5, -30_000_000.75, 1025.0,
                projection.get(new float[16]), rotation.get(new float[16]));
        var previous = capture(history, new FrameSnapshot(new SceneView(SCENE, previousCamera),
                previousOrigin, true, 12, 1), 0);
        history.submitted(previous);

        var currentCamera = new Camera(30_000_000.75, -30_000_000.25, 1025.75,
                projection.get(new float[16]), rotation.get(new float[16]));
        var requested = new FrameSnapshot(new SceneView(SCENE, currentCamera), requestedOrigin, true, 12.1, 1);
        var selected = requested.withSceneCoordinates(completedOrigin, requested.metersPerWorldUnit());
        var frame = capture(history, selected, 1);

        assertSame(requested.view(), selected.view());
        assertEquals(requestedOrigin, requested.sceneOrigin());
        assertEquals(completedOrigin, frame.snapshot().sceneOrigin());
        assertTrue(frame.historyContinuous());
        assertEquals(new Float3(.25f, .5f, .75f), frame.cameraDelta());
        assertEquals(new Float3(-15.5f, -16f, -14.5f), frame.cameraOffset());
        assertEquals(previous.projectionView(), frame.previousProjectionView());
        assertEquals(previous.viewRotation(), frame.previousViewRotation());
        assertEquals(previous.jitterX(), frame.previousJitterX());
        assertEquals(12, frame.previousProceduralTime());
        assertEquals(16, frame.frameTimeMilliseconds(), .0001f);

        var sameCameraDifferentOrigin = capture(history, requested, 1);
        assertEquals(frame.cameraDelta(), sameCameraDifferentOrigin.cameraDelta());
        assertEquals(frame.previousProceduralTime(), sameCameraDifferentOrigin.previousProceduralTime());
        assertEquals(frame.projectionView(), sameCameraDifferentOrigin.projectionView());
        assertEquals(new Float3(-31.5f, -32f, -30.5f), sameCameraDifferentOrigin.cameraOffset());
        history.submitted(frame);
        var next = capture(history, requested, 2);
        assertTrue(next.historyContinuous());
        assertEquals(new Float3(0, 0, 0), next.cameraDelta());
        assertEquals(frame.jitterX(), next.previousJitterX());
        assertEquals(12.1f, next.previousProceduralTime());
    }

    @Test
    void translationRotationAndProjectionCutsResetHistory() {
        assertReset(capture(seeded(), snapshot(9, .01), 1), RtFrameHistory.Break.CAMERA_CUT);
        assertReset(capture(seeded(), snapshot(SCENE, SceneOrigin.ZERO, 0, .01,
                1, false, new Matrix4f(), new Matrix4f().rotationY((float) Math.PI / 2)), 1),
                RtFrameHistory.Break.CAMERA_CUT);
        assertReset(capture(seeded(), snapshot(SCENE, SceneOrigin.ZERO, 0, .01,
                1, false, new Matrix4f().m00(1.2f), new Matrix4f()), 1), RtFrameHistory.Break.CAMERA_CUT);
    }

    @Test
    void ordinaryCameraMotionAndAnimationPreserveHistoryForReprojection() {
        assertTrue(capture(seeded(), snapshot(0, .01), 1).historyContinuous());
        var moved = capture(seeded(), snapshot(1, .01), 1);
        assertTrue(moved.historyContinuous());
        assertEquals(new Float3(1, 0, 0), moved.cameraDelta());

        Matrix4f rotation = new Matrix4f().rotationY(.1f);
        Matrix4f projection = new Matrix4f().m00(1.05f);
        var animated = capture(seeded(), snapshot(SCENE, SceneOrigin.ZERO, 1, .01,
                1, true, projection, rotation), 1);
        assertTrue(animated.historyContinuous());
        assertEquals(new Float3(1, 0, 0), animated.cameraDelta());
        assertEquals(new Matrix4f(), animated.previousProjection());
        assertEquals(new Matrix4f(), animated.previousViewRotation());
        assertEquals(projection, animated.projection());
        assertEquals(rotation, animated.viewRotation());
        assertEquals(0, animated.previousProceduralTime());
    }

    @Test
    void rawSubmissionDoesNotConsumeTemporalJitterSamples() {
        var history = new RtFrameHistory();
        var raw = history.capture(snapshot(0, 0), 0, 0, EXTENT, DenoiserRoute.RAW, 1, -1, -1);
        assertEquals(0, raw.jitterX(), 0);
        assertEquals(0, raw.jitterY(), 0);
        history.submitted(raw);
        var temporal = history.capture(snapshot(0, .01), 1, 16_000_000L, EXTENT, ROUTE, 2, -1, -1);
        assertReset(temporal, RtFrameHistory.Break.ROUTE);
        assertEquals(-RtJitter.sample(0, 1280, 1920).y(), temporal.jitterY());
        assertEquals(2, temporal.preExposure());
    }

    @Test
    void proceduralTimeUsesPredecessorOnlyWithinTheContinuousTimeWindow() {
        assertEquals(12, proceduralSuccessor(12, 12.1).previousProceduralTime());
        assertEquals(.1f, proceduralSuccessor(3599.9, 3600.1).previousProceduralTime(), .00001f);
        assertEquals(12.5f, proceduralSuccessor(12, 12.5).previousProceduralTime());
        assertEquals(11.9f, proceduralSuccessor(12, 11.9).previousProceduralTime());
    }

    private static RtFrameInput proceduralSuccessor(double previous, double current) {
        var history = new RtFrameHistory();
        history.submitted(capture(history, snapshot(0, previous), 0));
        var next = capture(history, snapshot(0, current), 1);
        assertTrue(next.historyContinuous());
        return next;
    }

    private static void assertReset(RtFrameInput frame, RtFrameHistory.Break cause) {
        assertEquals(cause, frame.historyBreak());
        assertFalse(frame.historyContinuous());
        assertEquals(new Float3(0, 0, 0), frame.cameraDelta());
        assertEquals(frame.projectionView(), frame.previousProjectionView());
        assertEquals(frame.jitterX(), frame.previousJitterX());
        assertEquals(frame.jitterY(), frame.previousJitterY());
        assertEquals(0, frame.frameTimeMilliseconds());
    }

    private static RtFrameHistory seeded() {
        var history = new RtFrameHistory();
        history.submitted(capture(history, snapshot(0, 0), 0));
        return history;
    }

    @Test
    void renderFramesWithoutAnRtCompositeKeepHistory() {
        var history = new RtFrameHistory();
        history.submitted(capture(history, snapshot(1, 1), 10));
        // Frame 11 was a screen transition's redraw with no RT composite.
        var next = capture(history, snapshot(1, 1.03), 12);
        assertTrue(next.historyContinuous());
        history.submitted(next);
        var afterPause = history.capture(snapshot(1, 2), 13, 12 * 16_000_000L + 300_000_000L, EXTENT, ROUTE, 1, 1, 1);
        assertEquals(RtFrameHistory.Break.INTERRUPTED, afterPause.historyBreak());
    }

    private static RtFrameInput capture(RtFrameHistory history, FrameSnapshot snapshot, long number) {
        return history.capture(snapshot, number, number * 16_000_000L, EXTENT, ROUTE, 1, 1, 1);
    }

    private static FrameSnapshot snapshot(double x, double seconds) {
        return snapshot(SCENE, SceneOrigin.ZERO, x, seconds, 1, false, new Matrix4f(), new Matrix4f());
    }

    private static FrameSnapshot snapshot(SceneId scene, SceneOrigin origin, double x, double seconds,
            double metersPerUnit, boolean animation, Matrix4f projection, Matrix4f rotation) {
        var camera = new Camera(x, 0, 0, projection.get(new float[16]), rotation.get(new float[16]));
        return new FrameSnapshot(new SceneView(scene, camera), origin, animation, seconds, metersPerUnit);
    }
}
