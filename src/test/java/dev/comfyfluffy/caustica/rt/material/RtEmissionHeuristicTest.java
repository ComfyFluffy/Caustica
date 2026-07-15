package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtEmissionHeuristicTest {
    @Test
    void selectsBrightDetailsRelativeToDarkSubstrate() {
        float[] rgba = repeated(16, 0.02f, 0.015f, 0.01f, 1.0f);
        set(rgba, 3, 1.0f, 0.35f, 0.05f, 1.0f);
        set(rgba, 7, 0.8f, 0.2f, 0.03f, 1.0f);
        RtEmissionHeuristic.Result result = RtEmissionHeuristic.compile(rgba);
        assertTrue(result.mask()[3] > 0.5f);
        assertTrue(result.mask()[7] > 0.2f);
        assertEquals(0.0f, result.mask()[0], 1.0e-6f);
        assertTrue(result.summary().emissive());
        assertTrue(result.summary().coverage() < 0.25f);
    }

    @Test
    void keepsUniformLuminousMaterialsEmissive() {
        RtEmissionHeuristic.Result result = RtEmissionHeuristic.compile(
                repeated(16, 0.35f, 0.30f, 0.22f, 1.0f));
        assertTrue(result.mask()[0] >= 0.55f);
        assertEquals(1.0f, result.summary().coverage(), 1.0e-6f);
        assertTrue(result.summary().integratedLuminance() > 0.15f);
    }

    @Test
    void rejectsUniformlyDarkInputsAndRespectsAlpha() {
        RtEmissionHeuristic.Result dark = RtEmissionHeuristic.compile(
                repeated(4, 0.01f, 0.01f, 0.01f, 1.0f));
        assertEquals(RtMaterialDesc.EmissionSummary.NONE, dark.summary());

        float[] transparent = repeated(4, 0.5f, 0.4f, 0.3f, 0.0f);
        RtEmissionHeuristic.Result alpha = RtEmissionHeuristic.compile(transparent);
        assertEquals(RtMaterialDesc.EmissionSummary.NONE, alpha.summary());
    }

    private static float[] repeated(int count, float r, float g, float b, float a) {
        float[] result = new float[count * 4];
        for (int i = 0; i < count; i++) set(result, i, r, g, b, a);
        return result;
    }

    private static void set(float[] values, int pixel, float r, float g, float b, float a) {
        int i = pixel * 4;
        values[i] = r;
        values[i + 1] = g;
        values[i + 2] = b;
        values[i + 3] = a;
    }
}
