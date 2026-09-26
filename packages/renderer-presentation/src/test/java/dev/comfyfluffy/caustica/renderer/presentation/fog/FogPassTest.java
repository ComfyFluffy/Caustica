package dev.comfyfluffy.caustica.renderer.presentation.fog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FogPassTest {
    @Test
    void referenceColumnGridTracesEveryDefaultStepInOneLightingDispatch() {
        // 3840 x 2160 output with RR Performance traces 1920 x 1080; divisor 8 gives 240 x 135 columns.
        assertEquals(64, FogPass.batchSteps(240 * 135, 64));
        assertEquals(64, FogPass.batchSteps(240 * 135, 512));
    }

    @Test
    void largerGridsSplitStepsWithinTheRecordBudgetButKeepEightPerDispatch() {
        // Divisor 4 at the same trace resolution.
        assertEquals(16, FogPass.batchSteps(480 * 270, 64));
        // Divisor 4 at a 3840 x 2160 trace would fit only four steps in the budget.
        assertEquals(8, FogPass.batchSteps(960 * 540, 64));
        assertEquals(32, FogPass.batchSteps(1, 32));
    }
}
