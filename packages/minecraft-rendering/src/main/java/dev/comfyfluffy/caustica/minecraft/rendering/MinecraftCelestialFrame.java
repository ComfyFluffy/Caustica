package dev.comfyfluffy.caustica.minecraft.rendering;

import java.util.Objects;

/** Minecraft celestial values sampled once at frame ingress. */
public record MinecraftCelestialFrame(float sunAngleRadians, float moonAngleRadians,
                                      float starAngleRadians, float starBrightness,
                                      int moonPhaseIndex, int seaLevel, double cameraY,
                                      double metersPerSceneUnit,
                                      MinecraftLightingCalibration lighting, MinecraftWeather weather) {
    public MinecraftCelestialFrame {
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(weather, "weather");
    }
}
