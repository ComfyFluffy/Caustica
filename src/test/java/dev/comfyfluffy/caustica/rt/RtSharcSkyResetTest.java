package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtSharcSkyResetTest {
    @Test
    void ordinaryMotionAndAngleWrapDoNotReset() {
        RtComposite.SharcSkyState before = state(0.02f, 0.03f, 0.04f, 0.5f, 2);
        assertFalse(RtComposite.hardSkyDiscontinuity(before, state(0.021f, 0.031f, 0.041f, 0.501f, 2)));

        float fullTurn = (float) (Math.PI * 2.0);
        assertFalse(RtComposite.hardSkyDiscontinuity(
                state(fullTurn - 0.01f, 0.0f, 0.0f, 0.5f, 2),
                state(0.01f, 0.0f, 0.0f, 0.5f, 2)));
    }

    @Test
    void hardCelestialAndLightingChangesReset() {
        RtComposite.SharcSkyState before = state(0.0f, 0.0f, 0.0f, 0.5f, 2);
        assertTrue(RtComposite.hardSkyDiscontinuity(before,
                state(RtComposite.SHARC_SKY_ANGLE_JUMP_RADIANS + 0.01f, 0.0f, 0.0f, 0.5f, 2)));
        assertTrue(RtComposite.hardSkyDiscontinuity(before, state(0.0f, 0.0f, 0.0f, 0.5f, 3)));
        assertTrue(RtComposite.hardSkyDiscontinuity(before,
                new RtComposite.SharcSkyState(0, 1, 0.0f, 0.0f, 0.0f, 0.5f, 2,
                        0.2f, 0.3f, 0.4f)));
    }

    private static RtComposite.SharcSkyState state(
            float sun, float moon, float stars, float brightness, int moonPhase) {
        return new RtComposite.SharcSkyState(0, 0, sun, moon, stars, brightness, moonPhase,
                0.2f, 0.3f, 0.4f);
    }
}
