package dev.comfyfluffy.caustica.minecraft.client.program;

import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFogFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.provider.MinecraftLightProvider;
import dev.comfyfluffy.caustica.renderer.presentation.fog.FogField;
import dev.comfyfluffy.caustica.renderer.presentation.fog.FogFrame;
import dev.comfyfluffy.caustica.settings.OptionValues;

import java.nio.FloatBuffer;

/** Converts captured Minecraft weather and biome facts into renderer medium parameters. */
final class MinecraftFogInputs {
    private MinecraftFogFrame.Grid source;
    private FogField field;

    FogFrame capture(MinecraftFogFrame frame, OptionValues options) {
        if (frame == null) return null;
        var grid = frame.grid();
        if (source != grid) {
            float[] voxels = new float[grid.voxelCount() * 4];
            grid.writeVoxels(FloatBuffer.wrap(voxels));
            field = new FogField(grid.originX(), grid.originY(), grid.originZ(), grid.spacing(),
                    grid.sizeX(), grid.sizeY(), grid.sizeZ(), voxels);
            source = grid;
        }
        var celestial = frame.celestial();
        var lights = MinecraftLightProvider.celestialLights(celestial,
                MinecraftProgramSession.celestialSettings(options));
        var light = lights.sun().or(() -> lights.moon()).orElse(null);
        float[] direction = light == null ? new float[]{0, 1, 0}
                : new float[]{(float) light.directionX(), (float) light.directionY(), (float) light.directionZ()};
        float[] illuminance = light == null ? new float[3] : new float[]{
                (float) light.illuminanceRedLux(), (float) light.illuminanceGreenLux(),
                (float) light.illuminanceBlueLux()};
        float daylight = (float) Math.max(0, Math.cos(celestial.sunAngleRadians()));
        float sky = celestial.lighting().sunIlluminanceLux() * celestial.weather().daylightFactor() * daylight
                * 0.015f + celestial.lighting().nightAirglowLuminanceCdM2();
        return new FogFrame(field, frame.dailyDensity(), celestial.seaLevel() + 6, 18,
                windPhase(frame.animationSeconds()), direction, illuminance,
                overcast(new float[]{sky * 0.7f, sky * 0.85f, sky}, celestial.weather().skySaturation()));
    }

    /**
     * The ambient stands in for the sky's light, so the weather greys it as it greys the sky-view LUT: toward its
     * own ACEScg luminance, which stays unchanged, keeping {@code saturation} of its colour.
     */
    private static float[] overcast(float[] ambient, float saturation) {
        float luminance = 0.27222872f * ambient[0] + 0.67408177f * ambient[1] + 0.05368952f * ambient[2];
        return new float[]{luminance + saturation * (ambient[0] - luminance),
                luminance + saturation * (ambient[1] - luminance), luminance + saturation * (ambient[2] - luminance)};
    }

    /** Radians in a 65,536-second cycle; integer shader harmonics stay continuous at the wrap. */
    static float windPhase(double animationSeconds) {
        double cycles = animationSeconds / 65536.0;
        return (float) ((cycles - Math.floor(cycles)) * (2.0 * Math.PI));
    }
}
