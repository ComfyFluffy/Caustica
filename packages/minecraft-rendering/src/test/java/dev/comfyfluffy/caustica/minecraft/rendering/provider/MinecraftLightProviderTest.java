package dev.comfyfluffy.caustica.minecraft.rendering.provider;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.scene.SceneChannel;
import dev.comfyfluffy.caustica.api.scene.SceneEdit;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftCelestialFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftWeather;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftLightProviderTest {
    private static final MinecraftWeather CLEAR = new MinecraftWeather(0, 0);

    @Test
    void noonSubmitsOnlyTheAboveHorizonSun() {
        MinecraftLightProvider.CelestialLights lights = MinecraftLightProvider.celestialLights(frame(
                0.0, Math.PI, 128_000, 5, 0));

        assertTrue(lights.sun().isPresent());
        assertTrue(lights.moon().isEmpty());
        assertEquals(128_000, lights.sun().orElseThrow().illuminanceRedLux());
        assertEquals(Math.cos(Math.PI / 6.0), lights.sun().orElseThrow().directionY(), 1.0e-12);
    }

    @Test
    void rainDarkensSunlightAndPassesOnlyItsShareOfEachBeam() {
        var halfRain = new MinecraftWeather(.5f, 0);
        var day = MinecraftLightProvider.celestialLights(frame(0.0, Math.PI, 128_000, 5, 0, halfRain));
        var night = MinecraftLightProvider.celestialLights(frame(Math.PI, 0.0, 128_000, 5, 0, halfRain));

        // Half rain keeps 1 - 5/32 of daylight and half of each beam; the moon is not daylight.
        assertEquals(128_000 * 0.84375 * 0.5, day.sun().orElseThrow().illuminanceRedLux(), 1.0e-9);
        assertEquals(5.0 * 0.5, night.moon().orElseThrow().illuminanceRedLux(), 1.0e-9);

        var thunder = new MinecraftWeather(1, 1);
        assertTrue(MinecraftLightProvider.celestialLights(frame(0.0, Math.PI, 128_000, 5, 0, thunder))
                .sun().isEmpty());
        assertTrue(MinecraftLightProvider.celestialLights(frame(Math.PI, 0.0, 128_000, 5, 0, thunder))
                .moon().isEmpty());
    }

    @Test
    void nightSubmitsPhaseScaledMoonAndOmitsZeroMoon() {
        MinecraftLightProvider.CelestialLights fullMoon = MinecraftLightProvider.celestialLights(frame(
                Math.PI, 0.0, 128_000, 5, 0));
        MinecraftLightProvider.CelestialLights zeroMoon = MinecraftLightProvider.celestialLights(frame(
                Math.PI, 0.0, 128_000, 0, 4));

        assertTrue(fullMoon.sun().isEmpty());
        assertEquals(5.0, fullMoon.moon().orElseThrow().illuminanceRedLux());
        assertTrue(zeroMoon.sun().isEmpty());
        assertTrue(zeroMoon.moon().isEmpty());
    }

    @Test
    void newMoonRetainsTheConfiguredFixedFraction() {
        MinecraftLightProvider.CelestialLights lights = MinecraftLightProvider.celestialLights(frame(
                Math.PI, 0.0, 128_000, 5, 4));

        assertEquals(0.5, lights.moon().orElseThrow().illuminanceRedLux(), 1.0e-12);
    }

    @Test
    void moonlightFollowsTheConfiguredFullMoonIlluminance() {
        var captured = new MinecraftCelestialFrame((float) Math.PI, 0, 0, 0,
                0, 63, 63, 1, new MinecraftLightingCalibration(128_000, 1, 0, 0, .1f), CLEAR);

        var lights = MinecraftLightProvider.celestialLights(captured,
                new MinecraftLightProvider.CelestialSettings(0, .6, 1.5, 0.3));

        // A full moon overhead delivers the configured lux at the same surface ratio as the sun.
        assertEquals(0.3 * 100_000.0 / 128_000.0, lights.moon().orElseThrow().illuminanceRedLux(), 1.0e-9);
        assertTrue(MinecraftLightProvider.celestialLights(captured,
                new MinecraftLightProvider.CelestialSettings(0, .6, 1.5, 0)).moon().isEmpty());
    }

    @Test
    void unchangedCelestialAndHelmetSkipSubmission() {
        RecordingLights channel = new RecordingLights();
        MinecraftLightProvider provider = provider(channel);
        var celestial = MinecraftLightProvider.celestialLights(frame(0.0, Math.PI, 128_000, 5, 0));
        var helmet = Optional.of(helmet());
        provider.publish(celestial, helmet);
        provider.publish(celestial, helmet);

        assertEquals(1, channel.submissions.size());
        assertEquals(3, channel.submissions.getFirst().size());
    }

    @Test
    void helmetReplacementAndClearEmitOnlyTheirChangedOperation() {
        RecordingLights channel = new RecordingLights();
        MinecraftLightProvider provider = provider(channel);
        LightDescriptor.Spot first = helmet();
        LightDescriptor.Spot replacement = new LightDescriptor.Spot(
                1, 2, 3, 0, -1, 0, 12, 0.5, 4, 5, 7);
        provider.publish(emptyCelestial(), Optional.of(first));

        provider.publish(emptyCelestial(), Optional.of(replacement));
        assertEquals(1, channel.submissions.getLast().size());
        assertTrue(channel.submissions.getLast().getFirst() instanceof SceneEdit.SetLight);

        provider.publish(emptyCelestial(), Optional.empty());
        assertEquals(1, channel.submissions.getLast().size());
        assertTrue(channel.submissions.getLast().getFirst() instanceof SceneEdit.DropLight);
    }

    @Test
    void celestialTransitionsEmitOnlyTheChangedSunOrMoonOperation() {
        RecordingLights channel = new RecordingLights();
        MinecraftLightProvider provider = provider(channel);
        provider.publish(MinecraftLightProvider.celestialLights(frame(
                0.0, Math.PI, 128_000, 5, 0)), Optional.empty());

        provider.publish(emptyCelestial(), Optional.empty());
        assertEquals(1, channel.submissions.getLast().size());
        assertTrue(channel.submissions.getLast().getFirst() instanceof SceneEdit.DropLight);

        provider.publish(MinecraftLightProvider.celestialLights(frame(
                Math.PI, 0.0, 128_000, 5, 0)), Optional.empty());
        assertEquals(1, channel.submissions.getLast().size());
        assertTrue(channel.submissions.getLast().getFirst() instanceof SceneEdit.SetLight);
    }

    @Test
    void closeClearsSingletonLightsInOneBatch() {
        RecordingLights channel = new RecordingLights();
        MinecraftLightProvider provider = provider(channel);
        provider.close();

        var clear = channel.submissions.getLast();
        assertEquals(3, clear.size());
        assertTrue(clear.stream().allMatch(SceneEdit.DropLight.class::isInstance));
    }

    @Test
    void updateConsumesOneCoherentCapturedFrame() {
        RecordingLights channel = new RecordingLights();
        AtomicInteger reads = new AtomicInteger();
        var celestial = new MinecraftCelestialFrame(0, (float) Math.PI, 0, 0,
                0, 63, 63, 1, new MinecraftLightingCalibration(128_000, 1, 0, 0, .1f), CLEAR);
        var frame = new MinecraftLightFrame(Optional.of(celestial), Optional.empty());
        MinecraftLightProvider provider = new MinecraftLightProvider(channel, new SceneId() { },
                () -> new MinecraftLightProvider.CelestialSettings(30, .6, 1.5, 5),
                () -> { reads.incrementAndGet(); return frame; });

        provider.update();

        assertEquals(1, reads.get());
        assertEquals(3, channel.submissions.getLast().size());
        assertTrue(channel.submissions.getLast().getFirst() instanceof SceneEdit.SetLight);
    }

    private static MinecraftLightProvider.CelestialLights emptyCelestial() {
        return new MinecraftLightProvider.CelestialLights(Optional.empty(), Optional.empty());
    }

    private static MinecraftLightProvider provider(RecordingLights channel) {
        return new MinecraftLightProvider(channel, new SceneId() { },
                () -> new MinecraftLightProvider.CelestialSettings(30.0, 0.6, 1.5, 5.0), () -> null);
    }

    private static LightDescriptor.Spot helmet() {
        return new LightDescriptor.Spot(1, 2, 3, 0, -1, 0, 10, 0.5, 4, 5, 6);
    }

    private static MinecraftLightProvider.CelestialFrame frame(
            double sunAngle, double moonAngle, double sunLux, double moonLux, int moonPhase) {
        return frame(sunAngle, moonAngle, sunLux, moonLux, moonPhase, CLEAR);
    }

    private static MinecraftLightProvider.CelestialFrame frame(double sunAngle, double moonAngle, double sunLux,
                                                               double moonLux, int moonPhase,
                                                               MinecraftWeather weather) {
        return new MinecraftLightProvider.CelestialFrame(sunAngle, moonAngle, Math.PI / 6.0,
                sunLux, moonLux, moonPhase, 0.1, Math.toRadians(0.6), Math.toRadians(1.5), weather);
    }

    private static final class RecordingLights implements SceneChannel {
        private int issued;
        @Override public InstanceId newInstance() { return new InstanceId() { }; }
        private final ArrayList<List<? extends SceneEdit>> submissions = new ArrayList<>();

        @Override
        public LightId newLight() {
            issued++;
            return new LightId() { };
        }

        @Override
        public void edit(List<? extends SceneEdit> batch) {
            submissions.add(batch);
        }
    }
}
