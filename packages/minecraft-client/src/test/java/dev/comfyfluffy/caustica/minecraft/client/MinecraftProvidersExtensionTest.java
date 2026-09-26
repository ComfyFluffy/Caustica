package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.renderer.presentation.bloom.BloomExtension;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionFactory;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.SkyLutPass;
import dev.comfyfluffy.caustica.renderer.presentation.fog.FogPass;
import dev.comfyfluffy.caustica.minecraft.rendering.material.MinecraftMaterialEpochCompiler;
import dev.comfyfluffy.caustica.minecraft.client.terrain.RtTerrain;
import dev.comfyfluffy.caustica.minecraft.client.terrain.RtWorkerPool;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import dev.comfyfluffy.caustica.settings.SettingsAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaConfig;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaOptions;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftProvidersExtensionTest {
    @TempDir Path temporaryDirectory;
    private CausticaOptions previousStore;

    @BeforeEach
    void installSettings() {
        previousStore = CausticaConfig.store();
        SettingsRegistry registry = new SettingsRegistry();
        MinecraftOptions.register(registry);
        CausticaConfig.install(CausticaOptions.load(temporaryDirectory.resolve("caustica.toml"), registry));
    }

    @AfterEach
    void restoreSettings() { CausticaConfig.install(previousStore); }

    @Test
    void bloomAndMinecraftSettingsUseTheIndependentSettingsRegistry() {
        SettingsRegistry settings = new SettingsRegistry();
        new BloomExtension().registerSettings(settings);
        extension().registerSettings(settings);

        assertTrue(settings.declared(BloomExtension.ID));
        assertTrue(settings.declared(MinecraftProvidersExtension.ID));
        var expected = new ArrayList<>(SkyLutPass.OPTIONS);
        expected.addAll(FogPass.OPTIONS);
        assertEquals(expected, settings.settings(MinecraftProvidersExtension.ID).options());
    }

    @Test
    void installsExactlyOneCoreMinecraftWorldSessionFactory() {
        List<MinecraftWorldSessionFactory> factories = new ArrayList<>();
        SettingsAccess options = new dev.comfyfluffy.caustica.settings.testing.InMemorySettings();
        MinecraftApi api = new MinecraftApi(factory -> {
            factories.add(factory);
            return () -> { };
        });

        var extension = extension();
        extension.settingsReady(options);
        extension.registerMinecraft(api);

        assertEquals(1, factories.size());
    }

    private static MinecraftProvidersExtension extension() {
        MinecraftMaterialEpochCompiler materials = (epoch, rules) -> null;
        var textures = new dev.comfyfluffy.caustica.minecraft.client.entity.RtEntityTextures();
        var entities = new dev.comfyfluffy.caustica.minecraft.client.entity.RtEntities(
                textures, MinecraftTelemetry.disabled());
        return new MinecraftProvidersExtension(selector -> () -> { }, (sink, calibration) -> () -> { },
                materials, new MinecraftLightingCalibration(1, 1, 0, 0, 0),
                textures, entities,
                new RtTerrain(new RtWorkerPool(), MinecraftTelemetry.disabled()),
                MinecraftTelemetry.disabled());
    }
}
