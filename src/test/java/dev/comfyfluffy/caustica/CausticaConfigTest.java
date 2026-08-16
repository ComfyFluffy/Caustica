package dev.comfyfluffy.caustica;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CausticaConfigTest {
    @Test
    void invalidPeakNitsFallsBackToDefault() {
        CausticaConfig.IntSetting setting = CausticaConfig.Rt.Hdr.PEAK_NITS;
        int previous = setting.value();
        try {
            setting.set(2000);
            assertEquals(2000, setting.value());

            setting.set(900);
            assertEquals(1000, setting.value());
        } finally {
            setting.set(previous);
        }
    }

    @Test
    void volumetricSampleCountUsesSupportedQualitySteps() {
        CausticaConfig.IntSetting setting = CausticaConfig.Rt.VolumetricLight.SAMPLES;
        int previous = setting.value();
        try {
            setting.set(48);
            assertEquals(48, setting.value());

            setting.set(47);
            assertEquals(32, setting.value());
        } finally {
            setting.set(previous);
        }
    }
}
