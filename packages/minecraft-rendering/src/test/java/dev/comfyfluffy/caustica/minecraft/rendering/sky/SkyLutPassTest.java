package dev.comfyfluffy.caustica.minecraft.rendering.sky;

import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.minecraft.rendering.TestResource;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.gen.MinecraftEnvironmentBindingData;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.gen.SkyInputsData;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.gen.SkyLutPushData;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftCelestialFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftWeather;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionValues;
import dev.comfyfluffy.caustica.support.SharedResource;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK13;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SkyLutPassTest {
    private static final SkyLutPass.SkyState SKY = new SkyLutPass.SkyState(
            .1f, .2f, .3f, .4f, 100_000, .2f, .003f, 1.5f, .5f, .01f, .02f,
            .1f, .03f, .04f, 1.25f, 2, .3f, .05f, .6875f, .25f, .75f);
    private static final SkyLutPass.AtlasSnapshot ATLAS = new SkyLutPass.AtlasSnapshot(null, 0, 1,
            new SkyInputsData.Float4(.1f, .2f, .3f, .4f),
            new SkyInputsData.Float4(.5f, .6f, .7f, .8f));

    @Test void generatedSkyInputsMapStateAndAtlasRects() {
        ByteBuffer bytes = ByteBuffer.allocate(SkyInputsData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        SkyLutPass.skyInputs(SKY, ATLAS).write(bytes);
        assertEquals(128, SkyInputsData.BYTE_SIZE);
        assertEquals(SKY.sunAngleRadians(), bytes.getFloat(0));
        assertEquals(SKY.groundAlbedo(), bytes.getFloat(64));
        assertEquals(SKY.daylightFactor(), bytes.getFloat(72));
        assertEquals(SKY.beamTransmittance(), bytes.getFloat(76));
        assertEquals(SKY.skySaturation(), bytes.getFloat(80));
        assertEquals(.1f, bytes.getFloat(96));
        assertEquals(.8f, bytes.getFloat(124));
    }

    @Test void generatedRootsMatchDescriptorHeapAbi() {
        assertEquals(160, SkyLutPushData.BYTE_SIZE);
        assertEquals(32, MinecraftEnvironmentBindingData.BYTE_SIZE);
        assertEquals(1, SkyLutPass.groups(1));
        assertEquals(2, SkyLutPass.groups(9));
    }

    @Test void environmentBindingChangesOnlyWithAtlasIdentityOrEpoch() {
        assertTrue(SkyLutPass.sameBindingEpoch(7, 11, 7, 11));
        assertFalse(SkyLutPass.sameBindingEpoch(7, 11, 8, 11));
        assertFalse(SkyLutPass.sameBindingEpoch(7, 11, 7, 12));
    }

    @Test void persistentSkyResourcesWaitForPriorRayReadsBeforeComputeWrites() {
        assertEquals(KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                SkyLutPass.PRIOR_SKY_READ_STAGE);
        assertEquals(VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                SkyLutPass.PRIOR_SKY_READ_ACCESS);
        assertEquals(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, SkyLutPass.SKY_WRITE_STAGE);
        assertEquals(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, SkyLutPass.SKY_WRITE_ACCESS);
    }

    @Test void selectedBindingLeaseDefersLutResourceClosure() {
        AtomicInteger closes = new AtomicInteger();
        var lifetime = SharedResource.owned(new Object(), ignored -> closes.incrementAndGet());
        var bindingLease = lifetime.retain();

        lifetime.close();
        assertEquals(0, closes.get());
        bindingLease.close();
        assertEquals(1, closes.get());
    }

    @Test void publishedBindingsCarryDistinctRetainableResourceIdentities() {
        var environment = new EnvironmentId<MinecraftProgramTypes.EnvironmentBindingData>() { };
        List<EnvironmentBinding<?>> selected = new ArrayList<>();
        List<ResourceOwner> readers = new ArrayList<>();
        AtomicInteger releases = new AtomicInteger();
        try (var first = TestResource.create(releases::incrementAndGet);
             var second = TestResource.create(releases::incrementAndGet)) {
            SkyLutPass.publishBinding(first, environment, binding -> {
                readers.add(binding.bindingData().resource().retain());
                selected.add(binding);
            }, 0x1000L);
            SkyLutPass.publishBinding(second, environment, binding -> {
                readers.add(binding.bindingData().resource().retain());
                selected.add(binding);
            }, 0x1000L);

            assertNotSame(ResourceOwner.none(), first);
            assertNotSame(first, second);
            assertNotSame(first, selected.get(0).bindingData().resource());
            assertNotSame(second, selected.get(1).bindingData().resource());
        }
        assertEquals(0, releases.get());
        readers.forEach(ResourceOwner::close);
        assertEquals(2, releases.get());
    }

    @Test void producerAndFrameOwnersKeepSharedLutsAliveIndependently() {
        AtomicInteger closes = new AtomicInteger();
        var lifetime = SharedResource.owned(new Object(), ignored -> closes.incrementAndGet());
        var first = TestResource.create(lifetime.retain()::close);
        var second = TestResource.create(lifetime.retain()::close);
        var firstFrame = first.retain();
        var secondFrame = second.retain();
        first.close();
        second.close();
        lifetime.close();
        assertEquals(0, closes.get());
        firstFrame.close();
        assertEquals(0, closes.get());
        secondFrame.close();
        assertEquals(1, closes.get());
    }

    @Test void acceptedBindingRetainsResourcesBeforeProducerRelease() {
        AtomicInteger releases = new AtomicInteger();
        var producer = TestResource.create(releases::incrementAndGet);
        var environment = new EnvironmentId<MinecraftProgramTypes.EnvironmentBindingData>() { };
        List<ResourceOwner> scene = new ArrayList<>();

        SkyLutPass.publishBinding(producer, environment, binding -> {
            scene.add(binding.bindingData().resource().retain());
        }, 0x1000L);
        producer.close();
        assertEquals(0, releases.get());
        scene.getFirst().close();
        assertEquals(1, releases.get());
    }

    @Test void lutDimensionsMatchAtmosphereConstants() {
        assertEquals(256, SkyLutPass.TRANSMITTANCE_WIDTH);
        assertEquals(64, SkyLutPass.TRANSMITTANCE_HEIGHT);
        assertEquals(192, SkyLutPass.SKY_VIEW_WIDTH);
        assertEquals(216, SkyLutPass.SKY_VIEW_HEIGHT);
    }

    @Test void convertsSceneAltitudeToKilometresUsingFrameScale() {
        assertEquals(1.0f, SkyLutPass.viewerAltitudeKm(1063.0, 63.0, 1.0));
        assertEquals(1.0f, SkyLutPass.viewerAltitudeKm(2063.0, 63.0, 0.5));
        assertEquals(0.0f, SkyLutPass.viewerAltitudeKm(20.0, 63.0, 1.0));
    }

    @Test void skyStateUsesOneCapturedHostFrame() {
        var lighting = new MinecraftLightingCalibration(100, 3, 4, 5, .25f);
        var captured = new MinecraftCelestialFrame(.1f, .2f, .3f, .4f,
                6, 63, 1063, 1, lighting, new MinecraftWeather(1, .5f));
        OptionValues defaults = new OptionValues() {
            @Override public <T> T get(Option<T> option) { return option.defaultValue(); }
        };

        SkyLutPass.SkyState state = SkyLutPass.gather(defaults, captured);

        // The shader applies the weather to the calibrated sun, as the sun light and fog do on the CPU.
        assertEquals(100f, state.sunIlluminanceLux());
        assertEquals(captured.weather().daylightFactor(), state.daylightFactor());
        assertEquals(captured.weather().beamTransmittance(), state.beamTransmittance());
        assertEquals(captured.weather().skySaturation(), state.skySaturation());
        assertEquals(.1f, state.sunAngleRadians());
        assertEquals(.3f, state.starAngleRadians());
        assertEquals(1f, state.viewerAltitudeKm());
        assertEquals(6f, state.moonPhaseIndex());
        assertEquals(SkyLutPass.MOON_ILLUMINANCE_LUX.defaultValue(), state.moonIlluminanceLux());
        assertEquals(.3f, state.moonIlluminanceLux());
    }

}
