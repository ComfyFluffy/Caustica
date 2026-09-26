package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftOptions;

import dev.comfyfluffy.caustica.minecraft.client.CausticaMod;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaConfig;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaOptions;
import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.renderer.presentation.bloom.BloomExtension;
import dev.comfyfluffy.caustica.engine.session.RenderSessionHost;
import dev.comfyfluffy.caustica.minecraft.client.session.MinecraftWorldSessionHost;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtension;
import dev.comfyfluffy.caustica.minecraft.client.material.MinecraftClientMaterialEpochCompiler;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.client.terrain.RtTerrain;
import dev.comfyfluffy.caustica.minecraft.client.platform.CausticaPlatform;
import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry;
import dev.comfyfluffy.caustica.nvidia.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.settings.CausticaSettingsExtension;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import dev.comfyfluffy.caustica.settings.SettingsAccess;
import dev.comfyfluffy.caustica.slang.SlangRuntime;
import dev.comfyfluffy.caustica.slang.SlangRuntimeConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;

/** Loader-neutral process extension discovery and settings installation. */
public final class MinecraftApiBootstrap {
    private MinecraftApiBootstrap() { }

    public static ApiServices initialize(CausticaPlatform platform, RtTelemetry telemetry,
                                         MinecraftFrameAdapter frameAdapter, RtTerrain terrain,
                                         SettingsRegistry settingsRegistry, CausticaOptions options) {
        List<CausticaExtension> extensions = new ArrayList<>();
        extensions.add(new BloomExtension());
        extensions.addAll(platform.extensions());
        float sunIlluminanceLux = 128_000.0f;
        // A level-15 emitter texel of white albedo radiates 5/21 of the sun's top-of-atmosphere lux value
        // as cd/m² (30,476 cd/m²); the auto-exposure night ceiling is calibrated against this balance.
        var calibration = new MinecraftLightingCalibration(sunIlluminanceLux,
                sunIlluminanceLux * 5.0f / 21.0f, 0.002f, 10.0f, 0.1f);
        MinecraftProvidersExtension minecraftProviders = new MinecraftProvidersExtension(
                frameAdapter::installFrameSelector, frameAdapter::installFrameCapture,
                new MinecraftClientMaterialEpochCompiler(calibration), calibration,
                frameAdapter.entityTextures(), frameAdapter.entities(), terrain,
                MinecraftTelemetry.renderer(telemetry));
        List<MinecraftExtension> minecraftExtensions = new ArrayList<>(platform.minecraftExtensions());
        minecraftExtensions.add(minecraftProviders);

        registerSettings(settingsRegistry, extensions, minecraftExtensions);

        Path gameDirectory = platform.gameDir();
        Optional<Path> slangOverride = CausticaConfig.get(MinecraftOptions.Slang.PATH).map(Path::of);
        SlangRuntime slangRuntime = new SlangRuntime(new SlangRuntimeConfig(
                gameDirectory.resolve("caustica-slang"), slangOverride));
        var shaderCache = gameDirectory.resolve("caustica-shaders");
        Path shaderCacheRoot = shaderCache.resolve("sources").toAbsolutePath().normalize();
        telemetry.configure(MinecraftFrameMetrics.schema());
        options.register(settingsRegistry);
        options.importLegacy(platform.configDir().resolve("caustica-options.toml"));
        options.save();
        supplySettings(options, extensions, minecraftExtensions);
        RenderSessionHost host = new RenderSessionHost();
        MinecraftWorldSessionHost minecraftHost = new MinecraftWorldSessionHost();
        registerExtensions(host, minecraftHost, extensions);
        registerMinecraftExtensions(minecraftHost, minecraftExtensions, extensions);

        CausticaMod.LOGGER.info("Caustica extension API {} initialized with {} settings feature(s)",
                dev.comfyfluffy.caustica.api.CausticaApi.VERSION, settingsRegistry.all().size());
        Optional<Path> ngxOverride = CausticaConfig.get(MinecraftOptions.Ngx.PATH).map(Path::of);
        return new ApiServices(host, minecraftHost, settingsRegistry, options, slangRuntime, shaderCacheRoot,
                new NgxRuntime.Settings(gameDirectory.resolve("caustica-ngx"), ngxOverride));
    }

    static void registerMinecraftExtensions(MinecraftWorldSessionHost host,
                                            List<MinecraftExtension> extensions,
                                            List<CausticaExtension> genericExtensions) {
        for (MinecraftExtension extension : extensions) {
            if (genericExtensions.stream().anyMatch(generic -> generic == extension)) continue;
            registerMinecraftSessionExtension(host, extension);
        }
    }

    static void registerExtensions(RenderSessionHost host, MinecraftWorldSessionHost minecraftHost,
                                   List<CausticaExtension> extensions) {
        for (CausticaExtension extension : extensions) {
            try {
                extension.register(host.api());
            } catch (RuntimeException failure) {
                CausticaMod.LOGGER.error("Caustica session registration failed: {}",
                        extension.getClass().getName(), failure);
            }
            if (extension instanceof MinecraftExtension minecraftExtension) {
                registerMinecraftSessionExtension(minecraftHost, minecraftExtension);
            }
        }
    }

    static void registerSettings(SettingsRegistry settingsRegistry,
                                 List<? extends CausticaExtension> genericExtensions,
                                 List<? extends MinecraftExtension> minecraftExtensions) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (Object extension : genericExtensions) {
            registerSettings(settingsRegistry, extension, seen);
        }
        for (Object extension : minecraftExtensions) {
            registerSettings(settingsRegistry, extension, seen);
        }
    }

    static void supplySettings(SettingsAccess settings,
                               List<? extends CausticaExtension> genericExtensions,
                               List<? extends MinecraftExtension> minecraftExtensions) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        var extensions = new ArrayList<Object>(genericExtensions);
        extensions.addAll(minecraftExtensions);
        for (Object extension : extensions) {
            if (!seen.add(extension) || !(extension instanceof CausticaSettingsExtension settingsExtension)) continue;
            try {
                settingsExtension.settingsReady(settings);
            } catch (RuntimeException failure) {
                CausticaMod.LOGGER.error("Caustica settings initialization failed: {}",
                        extension.getClass().getName(), failure);
            }
        }
    }

    private static void registerSettings(SettingsRegistry settingsRegistry, Object extension, java.util.Set<Object> seen) {
        if (!seen.add(extension) || !(extension instanceof CausticaSettingsExtension settingsExtension)) return;
        try {
            settingsExtension.registerSettings(settingsRegistry);
        } catch (RuntimeException failure) {
            CausticaMod.LOGGER.error("Caustica settings registration failed: {}",
                    extension.getClass().getName(), failure);
        }
    }

    private static void registerMinecraftSessionExtension(MinecraftWorldSessionHost host,
                                                          MinecraftExtension extension) {
        try {
            extension.registerMinecraft(host.api());
        } catch (RuntimeException failure) {
            CausticaMod.LOGGER.error("Minecraft world-session registration failed: {}",
                    extension.getClass().getName(), failure);
        }
    }

    public record ApiServices(RenderSessionHost renderSessionHost,
                              MinecraftWorldSessionHost minecraftWorldSessionHost,
                              SettingsRegistry settings,
                              CausticaOptions options,
                              SlangRuntime slangRuntime,
                              Path shaderCacheRoot,
                              NgxRuntime.Settings ngxSettings) { }
}
