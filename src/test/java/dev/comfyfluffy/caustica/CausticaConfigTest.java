package dev.comfyfluffy.caustica;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void volumetricQualityUsesSupportedSteps() {
        CausticaConfig.IntSetting setting = CausticaConfig.Rt.VolumetricLight.QUALITY;
        int previous = setting.value();
        try {
            setting.set(3);
            assertEquals(3, setting.value());

            setting.set(9);
            assertEquals(1, setting.value());
        } finally {
            setting.set(previous);
        }
    }

    @Test
    void volumetricQualityStepsScaleTheFroxelGridTogether() {
        CausticaConfig.IntSetting setting = CausticaConfig.Rt.VolumetricLight.QUALITY;
        int previous = setting.value();
        try {
            CausticaConfig.Rt.VolumetricLight.Grid finest = null;
            for (int quality : CausticaConfig.Rt.VolumetricLight.QUALITY_STEPS) {
                setting.set(quality);
                CausticaConfig.Rt.VolumetricLight.Grid grid = CausticaConfig.Rt.VolumetricLight.grid();
                if (finest != null) {
                    // A higher step must never coarsen the grid: smaller tiles, more slices, and never
                    // fewer sky probes (the top steps spend their budget on resolution instead).
                    assertTrue(grid.tile() < finest.tile());
                    assertTrue(grid.slices() > finest.slices());
                    assertTrue(grid.skyProbes() >= finest.skyProbes());
                }
                finest = grid;
            }
        } finally {
            setting.set(previous);
        }
    }
}
