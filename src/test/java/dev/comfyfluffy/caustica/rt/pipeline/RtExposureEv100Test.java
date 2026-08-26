package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.comfyfluffy.caustica.rt.RtSceneUnits;
import org.junit.jupiter.api.Test;

final class RtExposureEv100Test {
    @Test
    void ev100OffsetRemovesTheLatchedPreExposureScale() {
        assertEquals(RtSceneUnits.EV100_OFFSET, RtExposure.ev100Offset(1.0f), 1.0e-6f);
        assertEquals(RtSceneUnits.EV100_OFFSET - 2.0f, RtExposure.ev100Offset(4.0f), 1.0e-6f);
    }

    @Test
    void invalidPreExposureFallsBackToTheNeutralScale() {
        assertEquals(RtSceneUnits.EV100_OFFSET, RtExposure.ev100Offset(0.0f), 1.0e-6f);
        assertEquals(RtSceneUnits.EV100_OFFSET, RtExposure.ev100Offset(-1.0f), 1.0e-6f);
        assertEquals(RtSceneUnits.EV100_OFFSET, RtExposure.ev100Offset(Float.NaN), 1.0e-6f);
    }

    @Test
    void onlyEnteringAutoInvalidatesHistory() {
        assertTrue(RtExposure.modeTransitionRequiresReset(RtExposure.Mode.MANUAL, RtExposure.Mode.AUTO));
        assertFalse(RtExposure.modeTransitionRequiresReset(RtExposure.Mode.AUTO, RtExposure.Mode.MANUAL));
        assertFalse(RtExposure.modeTransitionRequiresReset(RtExposure.Mode.AUTO, RtExposure.Mode.AUTO));
        assertFalse(RtExposure.modeTransitionRequiresReset(null, RtExposure.Mode.AUTO));
    }
}
