package dev.comfyfluffy.caustica.minecraft.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MinecraftWeatherTest {
    @Test void clearWeatherKeepsAllLight() {
        var clear = new MinecraftWeather(0, 0);

        assertEquals(1f, clear.daylightFactor());
        assertEquals(1f, clear.beamTransmittance());
    }

    @Test void fullRainKeepsElevenSixteenthsOfDaylightAndBlocksBothBeams() {
        var rain = new MinecraftWeather(1, 0);

        assertEquals(11f / 16f, rain.daylightFactor());
        assertEquals(0f, rain.beamTransmittance());
    }

    @Test void fullThunderKeepsTheSquareOfTheRainDaylight() {
        var thunder = new MinecraftWeather(1, 1);

        assertEquals(121f / 256f, thunder.daylightFactor());
        assertEquals(0f, thunder.beamTransmittance());
    }

    @Test void rampsInterpolateLinearlyInEachLevel() {
        var halfRain = new MinecraftWeather(.5f, 0);
        assertEquals(1f - 5f / 32f, halfRain.daylightFactor());
        assertEquals(.5f, halfRain.beamTransmittance());

        // Thunder rising under full rain moves from the rain factor toward the thunder factor.
        var halfThunder = new MinecraftWeather(1, .5f);
        assertEquals((1f - 5f / 32f) * (1f - 135f / 512f), halfThunder.daylightFactor(), 1.0e-7f);
        assertEquals(0f, halfThunder.beamTransmittance());
    }

    @Test void scalesVanillaSkyLightAboveItsNightValueAtEveryTimeOfDay() {
        // Sky light factor (day 1, night 0.24) and sky light level (day 15, night 4) share the weather layer.
        float[][] dayAndNight = {{1f, .24f}, {15f, 4f}};
        for (float[] attribute : dayAndNight) {
            float day = attribute[0];
            float night = attribute[1];
            for (float clear : new float[]{day, (day + night) / 2, night}) {
                for (int rainStep = 0; rainStep <= 10; rainStep++) {
                    for (int thunderStep = 0; thunderStep <= rainStep; thunderStep++) {
                        var weather = new MinecraftWeather(rainStep / 10f, thunderStep / 10f);
                        float vanilla = vanillaWeatherLayer(clear, night, weather);
                        assertEquals((clear - night) * weather.daylightFactor(), vanilla - night, 1.0e-4f);
                    }
                }
            }
        }
    }

    @Test void skyKeepsVanillasSaturationAtClearRainAndThunder() {
        assertEquals(1f, new MinecraftWeather(0, 0).skySaturation());
        // Rain keeps a quarter of the colour's departure from grey over 0.25 + 0.75 * 0.6 of its grey.
        assertEquals(.25f / .7f, new MinecraftWeather(1, 0).skySaturation(), 1.0e-6f);
        // Thunder keeps 0.06 of the departure over 0.06 + 0.94 * 0.24 of the grey.
        assertEquals(.06f / .2856f, new MinecraftWeather(1, 1).skySaturation(), 1.0e-6f);
    }

    @Test void skySaturationIsVanillasSkyColourLayerRelativeToItsGrey() {
        // Plains, desert and snowy-plains sky colours.
        for (int sky : new int[]{0x78A7FF, 0x6EB1FF, 0x7FA1FF}) {
            float[] clear = {(sky >> 16 & 255) / 255f, (sky >> 8 & 255) / 255f, (sky & 255) / 255f};
            float clearGrey = greyscale(clear)[0];
            for (int rainStep = 0; rainStep <= 10; rainStep++) {
                for (int thunderStep = 0; thunderStep <= rainStep; thunderStep++) {
                    var weather = new MinecraftWeather(rainStep / 10f, thunderStep / 10f);
                    float[] vanilla = vanillaSkyColorLayer(clear, weather);
                    float grey = greyscale(vanilla)[0];
                    for (int channel = 0; channel < 3; channel++) {
                        assertEquals(weather.skySaturation() * (clear[channel] / clearGrey - 1),
                                vanilla[channel] / grey - 1, 1.0e-5f);
                    }
                }
            }
        }
    }

    /**
     * 26.2 {@code WeatherAttributes.addLayer} for {@code SKY_COLOR}, on unquantized channels: rain without thunder,
     * then thunder, each blend the colour with {@code ARGB.srgbLerp} toward {@code ColorModifier.BLEND_TO_GRAY}'s
     * result, {@code srgbLerp(factor, c, scaleRGB(greyscale(c), brightness))}, with (0.6, 0.75) and (0.24, 0.94).
     */
    private static float[] vanillaSkyColorLayer(float[] color, MinecraftWeather weather) {
        float thunder = weather.thunderLevel();
        float rain = weather.rainLevel() - thunder;
        if (rain > 0) color = srgbLerp(rain, color, blendToGray(color, .6f, .75f));
        if (thunder > 0) color = srgbLerp(thunder, color, blendToGray(color, .24f, .94f));
        return color;
    }

    private static float[] blendToGray(float[] color, float brightness, float factor) {
        return srgbLerp(factor, color, scaleRgb(greyscale(color), brightness));
    }

    private static float[] greyscale(float[] color) {
        float grey = color[0] * .3f + color[1] * .59f + color[2] * .11f;
        return new float[]{grey, grey, grey};
    }

    private static float[] scaleRgb(float[] color, float scale) {
        return new float[]{color[0] * scale, color[1] * scale, color[2] * scale};
    }

    private static float[] srgbLerp(float delta, float[] from, float[] to) {
        return new float[]{lerp(delta, from[0], to[0]), lerp(delta, from[1], to[1]), lerp(delta, from[2], to[2])};
    }

    /**
     * 26.2 {@code WeatherAttributes.addLayer}: rain without thunder, then thunder, each linearly blend the value
     * toward {@code FloatModifier.ALPHA_BLEND}'s result, a blend toward the night value by 5/16 and 135/256.
     */
    private static float vanillaWeatherLayer(float value, float night, MinecraftWeather weather) {
        float rainOnly = weather.rainLevel() - weather.thunderLevel();
        if (rainOnly > 0) value = lerp(rainOnly, value, lerp(.3125f, value, night));
        if (weather.thunderLevel() > 0) value = lerp(weather.thunderLevel(), value, lerp(.52734375f, value, night));
        return value;
    }

    private static float lerp(float delta, float start, float end) {
        return start + delta * (end - start);
    }
}
