package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.SharcFrameData.Float3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtSharcCacheTest {
    private static final RtRenderSettings.Sharc SETTINGS = new RtRenderSettings.Sharc(
            true, 16, 3, 384, 128, 32.0f, 3.0f, 0.0f, 1000.0f, 0.0f, true, false);
    private static final SceneOrigin ORIGIN = new SceneOrigin(0.0, 0.0, 0.0);

    @Test
    void queriesWaitForTheWarmupAfterEveryReset() {
        RtSharcCache cache = cache();
        for (int frame = 1; frame <= RtSharcCache.QUERY_WARMUP_FRAMES; frame++) {
            cache.beginFrame(ORIGIN, camera(0.0f), SETTINGS);
            assertFalse(cache.queryReady(), "frame " + frame + " only updates the cache");
        }
        cache.beginFrame(ORIGIN, camera(0.0f), SETTINGS);
        assertTrue(cache.queryReady());

        cache.requestReset();
        cache.beginFrame(ORIGIN, camera(0.0f), SETTINGS);
        assertFalse(cache.queryReady());
    }

    @Test
    void theResolveReceivesTheCameraOfThePreviousUpdatedFrame() {
        RtSharcCache cache = cache();
        assertEquals(camera(1.0f), cache.beginFrame(ORIGIN, camera(1.0f), SETTINGS));
        assertEquals(camera(1.0f), cache.beginFrame(ORIGIN, camera(2.0f), SETTINGS));
        assertEquals(camera(2.0f), cache.beginFrame(ORIGIN, camera(3.0f), SETTINGS));
    }

    @Test
    void aNewOriginNewSettingsOrCameraJumpRestartsTheCache() {
        RtSharcCache cache = warm();
        assertEquals(camera(0.0f), cache.beginFrame(new SceneOrigin(512.0, 0.0, 0.0), camera(0.0f), SETTINGS));
        assertFalse(cache.queryReady());

        cache = warm();
        cache.beginFrame(ORIGIN, camera(0.0f), new RtRenderSettings.Sharc(
                true, 16, 3, 384, 128, 50.0f, 3.0f, 0.0f, 1000.0f, 0.0f, true, false));
        assertFalse(cache.queryReady());

        cache = warm();
        cache.beginFrame(ORIGIN, camera((float) RtSharcCache.CAMERA_JUMP), SETTINGS);
        assertTrue(cache.queryReady(), "a move of exactly the jump distance keeps the cache");
        cache.beginFrame(ORIGIN, camera((float) (2.0 * RtSharcCache.CAMERA_JUMP) + 1.0f), SETTINGS);
        assertFalse(cache.queryReady());
    }

    private static RtSharcCache warm() {
        RtSharcCache cache = cache();
        for (int frame = 0; frame <= RtSharcCache.QUERY_WARMUP_FRAMES; frame++) {
            cache.beginFrame(ORIGIN, camera(0.0f), SETTINGS);
        }
        assertTrue(cache.queryReady());
        return cache;
    }

    // Host-side state only; these tests never touch the tables.
    private static RtSharcCache cache() {
        return new RtSharcCache(null, null, null, 1 << 16);
    }

    private static Float3 camera(float x) {
        return new Float3(x, 64.0f, -32.0f);
    }
}
