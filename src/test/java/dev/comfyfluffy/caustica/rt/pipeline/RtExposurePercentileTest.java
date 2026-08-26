package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.comfyfluffy.caustica.CausticaConfig;
import org.junit.jupiter.api.Test;

final class RtExposurePercentileTest {
    @Test
    void defaultsAndConfigValuesStayInTheUnitInterval() {
        var window = RtExposure.PercentileWindow.sanitize(0.50f, 0.95f);
        assertEquals(0.50f, window.low(), 1.0e-6f);
        assertEquals(0.95f, window.high(), 1.0e-6f);

        var low = CausticaConfig.Rt.Exposure.LOW_PERCENTILE;
        var high = CausticaConfig.Rt.Exposure.HIGH_PERCENTILE;
        float previousLow = low.value();
        float previousHigh = high.value();
        try {
            low.set(-0.5f);
            high.set(2.0f);
            assertEquals(0.0f, low.value(), 1.0e-6f);
            assertEquals(1.0f, high.value(), 1.0e-6f);
            low.set(Float.NaN);
            high.set(Float.POSITIVE_INFINITY);
            assertEquals(0.50f, low.value(), 1.0e-6f);
            assertEquals(0.95f, high.value(), 1.0e-6f);
        } finally {
            low.set(previousLow);
            high.set(previousHigh);
        }
    }

    @Test
    void reversedAndEqualWindowsAreNormalizedToAUsableRange() {
        var reversed = RtExposure.PercentileWindow.sanitize(0.90f, 0.10f);
        assertEquals(0.10f, reversed.low(), 1.0e-6f);
        assertEquals(0.90f, reversed.high(), 1.0e-6f);

        var equalLow = RtExposure.PercentileWindow.sanitize(0.0f, 0.0f);
        assertEquals(0.0f, equalLow.low());
        assertTrue(equalLow.high() > equalLow.low());

        var equalHigh = RtExposure.PercentileWindow.sanitize(1.0f, 1.0f);
        assertTrue(equalHigh.low() < equalHigh.high());
        assertEquals(1.0f, equalHigh.high());
    }

    @Test
    void nonfiniteWindowValuesUseTheDocumentedDefaults() {
        var window = RtExposure.PercentileWindow.sanitize(Float.NaN, Float.NEGATIVE_INFINITY);
        assertEquals(0.50f, window.low(), 1.0e-6f);
        assertEquals(0.95f, window.high(), 1.0e-6f);
    }

    @Test
    void histogramDispatchGuardsStrideAndSmallOrOverflowingExtents() {
        assertEquals(1, RtExposurePipeline.safeStride(0));
        assertEquals(1, RtExposurePipeline.safeStride(-4));
        assertEquals(1, RtExposurePipeline.sampledExtent(0, 0));
        assertEquals(2, RtExposurePipeline.sampledExtent(16, 8));
        assertEquals(3, RtExposurePipeline.sampledExtent(17, 8));
        assertEquals(1, RtExposurePipeline.dispatchGroups(0, 0));
        assertEquals(2, RtExposurePipeline.dispatchGroups(256, 8));
        assertEquals(1, RtExposurePipeline.dispatchGroups(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(1, RtExposurePipeline.effectiveStride(3840, 2160, 1));
        assertEquals(2, RtExposurePipeline.effectiveStride(7680, 4320, 1));
        assertEquals(2, RtExposurePipeline.effectiveStride(7680, 4320, 2));
    }
}
