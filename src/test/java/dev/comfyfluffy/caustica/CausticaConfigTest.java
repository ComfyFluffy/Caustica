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
    void firstPersonShadowTransmittanceClampsAndRejectsNonFinites() {
        CausticaConfig.FloatSetting setting = CausticaConfig.Rt.Entities.FIRST_PERSON_SHADOW_TRANSMITTANCE;
        float previous = setting.value();
        try {
            assertEquals(0.35f, setting.defaultValue().floatValue());

            setting.set(1.5f);
            assertEquals(1.0f, setting.value());
            setting.set(-2.0f);
            assertEquals(0.0f, setting.value());

            setting.set(Float.POSITIVE_INFINITY);
            assertEquals(1.0f, setting.value());
            setting.set(Float.NEGATIVE_INFINITY);
            assertEquals(0.0f, setting.value());

            setting.set(Float.NaN);
            assertEquals(0.35f, setting.value(),
                    "NaN must fall back to the default, never reach the GPU shadow policy");
        } finally {
            setting.set(previous);
        }
    }
}
