package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtDlssRrTest {
    @Test
    void recommendedMipMapBiasUsesNvidiaResolutionFormula() {
        assertEquals(-1.0f, RtDlssRr.recommendedMipMapBias(1920, 1920), 1.0e-6f);
        assertEquals(-2.0f, RtDlssRr.recommendedMipMapBias(960, 1920), 1.0e-6f);
        assertEquals(-1.5849625f, RtDlssRr.recommendedMipMapBias(1280, 1920), 1.0e-5f);
        assertEquals(0.0f, RtDlssRr.recommendedMipMapBias(0, 1920), 0.0f);
        assertEquals(0.0f, RtDlssRr.recommendedMipMapBias(1920, 0), 0.0f);
    }
}
