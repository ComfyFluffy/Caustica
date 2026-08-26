package dev.comfyfluffy.caustica.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CausticaJitterTest {
    @Test
    void dlaaUsesTheActualNativeResolutionAndThirtyTwoPhases() {
        assertEquals(32, CausticaJitter.jitterPhaseCount(3840, 2160, 3840, 2160));
    }

    @Test
    void phaseCountUsesTheLargestActualAxisRatio() {
        assertEquals(72, CausticaJitter.jitterPhaseCount(1280, 720, 3840, 1080));
        assertEquals(72, CausticaJitter.jitterPhaseCount(1920, 360, 3840, 1080));
    }

    @Test
    void resetRestartsTheSequence() {
        CausticaJitter jitter = CausticaJitter.INSTANCE;
        jitter.reset();
        jitter.prepare(1920, 1080, 1920, 1080);
        float firstX = jitter.jitterPixelsX();
        float firstY = jitter.jitterPixelsY();
        jitter.prepare(1920, 1080, 1920, 1080);
        jitter.reset();
        jitter.prepare(1920, 1080, 1920, 1080);
        assertEquals(firstX, jitter.jitterPixelsX());
        assertEquals(firstY, jitter.jitterPixelsY());
    }
}
