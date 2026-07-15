package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtLabPbrTest {
    private static final float EPS = 1.0e-5f;

    @Test
    void decodesDielectricAndIgnoredEmission() {
        RtLabPbr.Specular value = RtLabPbr.decodeSpec(
                0.25f, 0.04f, 64.0f / 255.0f, 1.0f,
                0.7f, 0.6f, 0.5f);
        assertEquals(0.5625f, value.roughness(), EPS);
        assertEquals(0.0f, value.metalness(), EPS);
        assertEquals(0.04f, value.f0r(), EPS);
        assertEquals(0.0f, value.emission(), EPS);
        assertEquals(0.0f, value.sss(), EPS);
    }

    @Test
    void decodesGenericMetalEmissionAndSss() {
        RtLabPbr.Specular value = RtLabPbr.decodeSpec(
                0.5f, 1.0f, 1.0f, 127.0f / 255.0f,
                0.7f, 0.6f, 0.5f);
        assertEquals(0.25f, value.roughness(), EPS);
        assertEquals(1.0f, value.metalness(), EPS);
        assertEquals(0.7f, value.f0r(), EPS);
        assertEquals(0.6f, value.f0g(), EPS);
        assertEquals(0.5f, value.f0b(), EPS);
        assertEquals(0.5f, value.emission(), 0.002f);
        assertEquals(1.0f, value.sss(), EPS);
    }

    @Test
    void decodesPredefinedGoldWithoutUsingAlbedo() {
        RtLabPbr.Specular value = RtLabPbr.decodeSpec(
                1.0f, 231.0f / 255.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 0.0f);
        assertEquals(0.0f, value.roughness(), EPS);
        assertEquals(1.0f, value.metalness(), EPS);
        assertEquals(0.944f, value.f0r(), 0.002f);
        assertEquals(0.776f, value.f0g(), 0.002f);
        assertEquals(0.373f, value.f0b(), 0.002f);
    }
}
