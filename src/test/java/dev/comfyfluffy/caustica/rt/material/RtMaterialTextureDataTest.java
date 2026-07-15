package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtMaterialTextureDataTest {
    private static final float EPS = 1.0e-5f;

    @Test
    void averagesEmissionAsEnergyAndContinuousChannels() {
        RtMaterialTextureData.Level reduced = RtMaterialTextureData.reduce(level(
                new float[]{0.2f, 0.0f, 0.0f, 0.0f, 0.4f, 1.0f, 1.0f, 0.5f,
                        0.6f, 0.0f, 0.5f, 1.0f, 0.8f, 1.0f, 0.5f, 0.5f},
                repeatedNormal(4, 0.5f, 0.5f, 1.0f, 0.0f),
                repeatedNormal(4, 0.04f, 0.04f, 0.04f, 0.0f)));
        assertEquals(0.5f, reduced.surface0()[2], EPS);
        assertEquals(0.5f, reduced.surface0()[1], EPS);
        assertEquals(0.5f, reduced.surface0()[3], EPS);
    }

    @Test
    void renormalizesNormalsAndRaisesRoughnessForLostDetail() {
        float[] normalAo = {
                1.0f, 0.5f, 1.0f, 0.0f,
                0.0f, 0.5f, 1.0f, 0.0f,
                1.0f, 0.5f, 1.0f, 0.0f,
                0.0f, 0.5f, 1.0f, 0.0f
        };
        RtMaterialTextureData.Level reduced = RtMaterialTextureData.reduce(level(
                repeatedNormal(4, 0.1f, 0.0f, 0.0f, 0.0f), normalAo,
                repeatedNormal(4, 0.04f, 0.04f, 0.04f, 0.0f)));
        assertEquals(0.5f, reduced.normalAo()[0], EPS);
        assertEquals(0.5f, reduced.normalAo()[1], EPS);
        assertTrue(reduced.surface0()[0] > 0.9f, "opposing normal detail must widen the lobe");
    }

    @Test
    void oddSizedMipReductionKeepsEdgeSamples() {
        int pixels = 3;
        RtMaterialTextureData.Level src = new RtMaterialTextureData.Level(3, 1,
                repeatedNormal(pixels, 0.5f, 0.0f, 0.25f, 0.0f),
                repeatedNormal(pixels, 0.5f, 0.5f, 1.0f, 0.0f),
                repeatedNormal(pixels, 0.04f, 0.04f, 0.04f, 0.0f));
        RtMaterialTextureData.Level reduced = RtMaterialTextureData.reduce(src);
        assertEquals(2, reduced.width());
        assertEquals(1, reduced.height());
        assertEquals(0.25f, reduced.surface0()[6], EPS);
    }

    private static RtMaterialTextureData.Level level(float[] surface0, float[] normalAo, float[] surface1) {
        return new RtMaterialTextureData.Level(2, 2, surface0, normalAo, surface1);
    }

    private static float[] repeatedNormal(int count, float x, float y, float z, float w) {
        float[] result = new float[count * 4];
        for (int i = 0; i < count; i++) {
            result[i * 4] = x;
            result[i * 4 + 1] = y;
            result[i * 4 + 2] = z;
            result[i * 4 + 3] = w;
        }
        return result;
    }
}
