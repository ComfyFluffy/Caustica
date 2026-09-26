package dev.comfyfluffy.caustica.renderer.presentation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins each auto-exposure response to its model through the resolve's knot evaluation: compensation is
 * the first/last knot value outside the knot range and linear between neighbouring knots.
 */
final class RtExposureResponseTest {
    private static final double KEY_LOG2 = Math.log(0.18) / Math.log(2.0);
    /** {@code log2(100 / 12.5)}: EV100 of a luminance in cd/m² is {@code log2(L) + 3}. */
    private static final double EV100_OFFSET = 3.0;

    @Test
    void knotsIncreaseStrictlyBecauseTheResolveDividesByTheirSpacing() {
        for (RtExposure.Response response : RtExposure.Response.values()) {
            for (int knot = 1; knot < 4; knot++) {
                assertTrue(response.sceneEv(knot) > response.sceneEv(knot - 1), response + " knot " + knot);
            }
            assertTrue(response.minEv() < response.maxEv(), response.toString());
        }
    }

    @Test
    void adaptationFollowsTheOneToFiveLawBetweenItsFloorAndTheNoonReference() {
        for (double ev = -20.0; ev <= 30.0; ev += 0.125) {
            double law = Math.clamp(0.2 * (ev - 17.45), -5.0, 0.0);
            assertEquals(law, compensation(RtExposure.Response.ADAPTATION, ev), 1.0e-5, "EV100 " + ev);
        }
    }

    @Test
    void adaptationPlacesTheDawnReferenceSceneBelowKey() {
        // A dawn view metering EV100 13.75 renders its mean at 0.18 * 2^-0.74.
        assertEquals(-0.74, compensation(RtExposure.Response.ADAPTATION, 13.75), 1.0e-5);
    }

    @Test
    void adaptationDaylightIsLawControlledAndNightsAreCeilingControlled() {
        RtExposure.Response response = RtExposure.Response.ADAPTATION;
        double noonLuminanceLog2 = 17.45 - EV100_OFFSET;
        double noonDemand = demandLog2(response, noonLuminanceLog2);
        assertTrue(noonDemand > response.minEv() && noonDemand < response.maxEv(), "noon demand " + noonDemand);

        // The law's demand meets the night ceiling at about 6.8 cd/m².
        assertEquals(response.maxEv(), demandLog2(response, Math.log(6.78) / Math.log(2.0)), 0.01);
        // A moonlit field at 0.024 cd/m² renders at its luminance times the ceiling.
        double moonlitLog2 = Math.log(0.024) / Math.log(2.0);
        double applied = Math.min(demandLog2(response, moonlitLog2), response.maxEv());
        assertEquals(moonlitLog2 + response.maxEv(), moonlitLog2 + applied, 1.0e-9);
    }

    @Test
    void unknownResponseNamesSelectTheAdaptationLaw() {
        assertSame(RtExposure.Response.ADAPTATION, RtExposure.Response.parse("adaptation"));
        assertSame(RtExposure.Response.CURVE, RtExposure.Response.parse("curve"));
        assertSame(RtExposure.Response.ADAPTATION, RtExposure.Response.parse("unknown"));
    }

    /** log2 of the absolute exposure the response asks for when the metered mean is {@code luminanceLog2}. */
    private static double demandLog2(RtExposure.Response response, double luminanceLog2) {
        return KEY_LOG2 + compensation(response, luminanceLog2 + EV100_OFFSET) - luminanceLog2;
    }

    private static double compensation(RtExposure.Response response, double sceneEv) {
        if (sceneEv <= response.sceneEv(0)) return response.compensationEv(0);
        for (int knot = 1; knot < 4; knot++) {
            if (sceneEv < response.sceneEv(knot)) {
                double x0 = response.sceneEv(knot - 1);
                double y0 = response.compensationEv(knot - 1);
                double t = (sceneEv - x0) / (response.sceneEv(knot) - x0);
                return y0 + (response.compensationEv(knot) - y0) * t;
            }
        }
        return response.compensationEv(3);
    }
}
