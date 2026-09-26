package dev.comfyfluffy.caustica.minecraft.client.program;

import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftCelestialFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFogFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftWeather;
import dev.comfyfluffy.caustica.minecraft.rendering.provider.MinecraftLightProvider;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionValues;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MinecraftFogInputsTest {
    private static final OptionValues DEFAULTS = new OptionValues() {
        @Override public <T> T get(Option<T> option) { return option.defaultValue(); }
    };

    @Test void windPhaseRepeatsAcrossPositiveAndNegativeCycleBoundaries() {
        for (double seconds : new double[]{-10, -.125, 0, 42.75, 65535.875}) {
            float phase = MinecraftFogInputs.windPhase(seconds);
            assertEquals(phase, MinecraftFogInputs.windPhase(seconds + 65536), 0.000001);
            assertEquals(phase, MinecraftFogInputs.windPhase(seconds + 65536 * 1000.0), 0.000001);
        }
    }

    @Test void windHarmonicsAdvanceSmoothlyAcrossTimeWrap() {
        double halfFrame = 1.0 / 120;
        for (int harmonic : new int[]{96, 192}) {
            double before = MinecraftFogInputs.windPhase(65536 - halfFrame) * (double) harmonic;
            double after = MinecraftFogInputs.windPhase(65536 + halfFrame) * (double) harmonic;
            double expectedAdvance = 2 * Math.PI * harmonic * (2 * halfFrame) / 65536;
            assertEquals(Math.sin(before + expectedAdvance), Math.sin(after), 0.00005);
            assertEquals(Math.cos(before + expectedAdvance), Math.cos(after), 0.00005);
        }
    }

    @Test void fogLightingTakesTheSunlightWeatherFactors() {
        var clear = noonFog(new MinecraftWeather(0, 0));
        var storm = noonFog(new MinecraftWeather(.75f, .25f));

        var clearFog = new MinecraftFogInputs().capture(clear, DEFAULTS);
        var stormFog = new MinecraftFogInputs().capture(storm, DEFAULTS);
        var stormSun = MinecraftLightProvider.celestialLights(storm.celestial(),
                MinecraftProgramSession.celestialSettings(DEFAULTS)).sun().orElseThrow();

        // The fog is lit by the path tracer's sun light, and its daylight ambient darkens and greys with the sky.
        var weather = storm.celestial().weather();
        assertEquals((float) stormSun.illuminanceGreenLux(), stormFog.lightRadiance()[1]);
        assertEquals(clearFog.lightRadiance()[1] * weather.daylightFactor() * .25f, stormFog.lightRadiance()[1],
                1.0e-2f);
        float clearLuminance = luminance(clearFog.ambientRadiance());
        float stormLuminance = luminance(stormFog.ambientRadiance());
        assertEquals(clearLuminance * weather.daylightFactor(), stormLuminance, 1.0e-2f);
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(weather.skySaturation() * (clearFog.ambientRadiance()[channel] / clearLuminance - 1),
                    stormFog.ambientRadiance()[channel] / stormLuminance - 1, 1.0e-5f);
        }
    }

    private static float luminance(float[] acesCg) {
        return 0.27222872f * acesCg[0] + 0.67408177f * acesCg[1] + 0.05368952f * acesCg[2];
    }

    private static MinecraftFogFrame noonFog(MinecraftWeather weather) {
        var celestial = new MinecraftCelestialFrame(0, (float) Math.PI, 0, 0, 0, 63, 63, 1,
                new MinecraftLightingCalibration(128_000, 2_000, 0, 10, .1f), weather);
        return new MinecraftFogFrame(celestial, 0, new MinecraftFogFrame.Grid(0, 0, 0, 32, 1, 1, 1,
                new float[MinecraftFogFrame.Grid.COMPONENTS], new float[1]));
    }
}
