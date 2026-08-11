package dev.comfyfluffy.caustica.rt.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The publication verdict behind the localViewPresent frame signal. Presence demands the full
 * conjunction — compatibility toggle, two-slot budget admission, provider capture readiness (which
 * itself folds provider absence, missing state, camera-unsafe state, ownership mismatch and the circuit
 * breaker into one fact) and the completed geometry-table write. Any single failure leaves the signal
 * clear the same frame, so the shader's transmission-continuity chain falls back to baseline behaviour
 * without hysteresis.
 */
final class LocalViewPublicationStateTest {

    @Test
    void presenceHoldsOnlyWhenEveryGateHeld() {
        for (int bits = 0; bits < 16; bits++) {
            boolean eligible = (bits & 1) != 0;
            boolean admitted = (bits & 2) != 0;
            boolean captured = (bits & 4) != 0;
            boolean written = (bits & 8) != 0;
            assertEquals(bits == 15,
                    RtEntities.localViewPresence(eligible, admitted, captured, written),
                    "gates " + Integer.toBinaryString(bits));
        }
    }

    @Test
    void everySpecFailureClassMapsToAClearedGate() {
        assertFalse(RtEntities.localViewPresence(false, false, false, false),
                "experimental toggle off, or the entity is not the first-person camera entity");
        assertFalse(RtEntities.localViewPresence(true, false, false, false),
                "budget degradation left fewer than two free geometry-table slots");
        assertFalse(RtEntities.localViewPresence(true, true, false, false),
                "provider absent, state missing, camera-unsafe, ownership mismatch or circuit breaker");
        assertFalse(RtEntities.localViewPresence(true, true, true, false),
                "publication did not complete the geometry-table write");
    }
}
