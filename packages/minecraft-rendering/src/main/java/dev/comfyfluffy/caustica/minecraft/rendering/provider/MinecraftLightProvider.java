package dev.comfyfluffy.caustica.minecraft.rendering.provider;

import dev.comfyfluffy.caustica.api.scene.SceneChannel;
import dev.comfyfluffy.caustica.api.scene.SceneEdit;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftCelestialFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftWeather;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Owns Minecraft's retained sun, moon, and helmet lights. */
public final class MinecraftLightProvider implements AutoCloseable {
    private static final double TO_RADIANS = Math.PI / 180.0;
    private static final double SURFACE_TO_TOP_ILLUMINANCE = 100_000.0 / 128_000.0;

    private final SceneChannel lights;
    private final SceneId scene;
    private final Supplier<CelestialSettings> celestialSettings;
    private final Supplier<MinecraftLightFrame> frames;
    private final LightId helmetLight;
    private final LightId sunLight;
    private final LightId moonLight;
    private Optional<LightDescriptor.Distant> publishedSun;
    private Optional<LightDescriptor.Distant> publishedMoon;
    private Optional<LightDescriptor.Spot> publishedHelmet;

    public MinecraftLightProvider(SceneChannel lights, SceneId scene,
                                  Supplier<CelestialSettings> celestialSettings,
                                  Supplier<MinecraftLightFrame> frames) {
        this.lights = lights;
        this.scene = scene;
        this.celestialSettings = celestialSettings;
        this.frames = frames;
        helmetLight = lights.newLight();
        sunLight = lights.newLight();
        moonLight = lights.newLight();
    }

    /** Publishes one already-captured Minecraft frame as an atomic retained-light update. */
    public void update() {
        MinecraftLightFrame frame = frames.get();
        if (frame == null) return;
        CelestialLights celestial = frame.celestial()
                .map(value -> celestialLights(value, celestialSettings.get()))
                .orElse(CelestialLights.NONE);
        publish(celestial, frame.helmet());
    }

    void publish(CelestialLights celestial, Optional<LightDescriptor.Spot> helmet) {
        ArrayList<SceneEdit> operations = new ArrayList<>();
        if (!Objects.equals(publishedSun, celestial.sun())) {
            operations.add(lightEdit(sunLight, celestial.sun()));
        }
        if (!Objects.equals(publishedMoon, celestial.moon())) {
            operations.add(lightEdit(moonLight, celestial.moon()));
        }
        if (!Objects.equals(publishedHelmet, helmet)) {
            operations.add(lightEdit(helmetLight, helmet));
        }
        if (operations.isEmpty()) return;
        lights.edit(operations);
        publishedSun = celestial.sun();
        publishedMoon = celestial.moon();
        publishedHelmet = helmet;
    }

    private SceneEdit lightEdit(LightId light, Optional<? extends LightDescriptor> descriptor) {
        return descriptor.<SceneEdit>map(value ->
                new SceneEdit.SetLight(light, scene, value)).orElseGet(() ->
                new SceneEdit.DropLight(light));
    }

    @Override
    public void close() {
        lights.edit(List.of(new SceneEdit.DropLight(sunLight), new SceneEdit.DropLight(moonLight),
                new SceneEdit.DropLight(helmetLight)));
    }

    public static CelestialLights celestialLights(MinecraftCelestialFrame frame, CelestialSettings settings) {
        return celestialLights(celestialFrame(frame, settings));
    }

    static CelestialLights celestialLights(CelestialFrame frame) {
        // Sunlight follows the weather's daylight darkening; moonlight belongs to the night floor it keeps.
        double beam = frame.weather().beamTransmittance();
        Optional<LightDescriptor.Distant> sun = aboveHorizon(frame.sunAngleRadians(), frame.noonTiltRadians(),
                frame.sunIlluminanceLux() * frame.weather().daylightFactor() * beam,
                frame.sunAngularRadiusRadians());
        double litFraction = Math.abs(frame.moonPhaseIndex() - 4.0) / 4.0;
        double moonIlluminance = frame.moonIlluminanceLux() * beam
                * (frame.moonPhaseFixedFraction()
                + (1.0 - frame.moonPhaseFixedFraction()) * litFraction);
        Optional<LightDescriptor.Distant> moon = aboveHorizon(frame.moonAngleRadians(),
                frame.noonTiltRadians(), moonIlluminance, frame.moonAngularRadiusRadians());
        return new CelestialLights(sun, moon);
    }

    static CelestialFrame celestialFrame(MinecraftCelestialFrame captured, CelestialSettings settings) {
        var lighting = captured.lighting();
        return new CelestialFrame(
                captured.sunAngleRadians(), captured.moonAngleRadians(),
                settings.noonTiltDegrees() * TO_RADIANS,
                lighting.sunIlluminanceLux() * SURFACE_TO_TOP_ILLUMINANCE,
                settings.moonIlluminanceLux() * SURFACE_TO_TOP_ILLUMINANCE,
                captured.moonPhaseIndex(),
                lighting.moonPhaseFixedFraction(),
                settings.sunAngularRadiusDegrees() * TO_RADIANS,
                settings.moonAngularRadiusDegrees() * TO_RADIANS, captured.weather());
    }

    private static Optional<LightDescriptor.Distant> aboveHorizon(double angle, double noonTilt,
                                                                   double illuminance,
                                                                   double angularRadius) {
        double peak = Math.cos(angle);
        double x = -Math.sin(angle);
        double y = Math.cos(noonTilt) * peak;
        double z = Math.sin(noonTilt) * peak;
        if (y <= 0.0 || illuminance <= 0.0) return Optional.empty();
        return Optional.of(new LightDescriptor.Distant(x, y, z,
                illuminance, illuminance, illuminance, angularRadius, true));
    }

    public record CelestialLights(Optional<LightDescriptor.Distant> sun,
                           Optional<LightDescriptor.Distant> moon) {
        private static final CelestialLights NONE = new CelestialLights(Optional.empty(), Optional.empty());
    }

    record CelestialFrame(double sunAngleRadians, double moonAngleRadians,
                          double noonTiltRadians, double sunIlluminanceLux,
                          double moonIlluminanceLux, int moonPhaseIndex,
                          double moonPhaseFixedFraction, double sunAngularRadiusRadians,
                          double moonAngularRadiusRadians, MinecraftWeather weather) {
    }

    /** Sky-owned celestial settings sampled by this contribution without process-global option access. */
    public record CelestialSettings(double noonTiltDegrees, double sunAngularRadiusDegrees,
                                    double moonAngularRadiusDegrees, double moonIlluminanceLux) {
    }

}
