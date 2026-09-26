package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.minecraft.client.entity.RtEntities;
import dev.comfyfluffy.caustica.minecraft.client.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFrameCaptureInstaller;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFrameSelectionInstaller;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftApi;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtension;
import dev.comfyfluffy.caustica.minecraft.rendering.material.MinecraftMaterialEpochCompiler;
import dev.comfyfluffy.caustica.minecraft.client.program.MinecraftProgramSession;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.SkyLutPass;
import dev.comfyfluffy.caustica.renderer.presentation.fog.FogPass;
import dev.comfyfluffy.caustica.minecraft.client.terrain.RtTerrain;
import dev.comfyfluffy.caustica.settings.CausticaSettingsExtension;
import dev.comfyfluffy.caustica.settings.DisplayText;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import dev.comfyfluffy.caustica.settings.SettingsAccess;

/** Installs Caustica's core Minecraft world-session contribution and settings feature. */
public final class MinecraftProvidersExtension implements MinecraftExtension, CausticaSettingsExtension {
    public static final ResourceId ID = ResourceId.of("caustica", "minecraft");
    private final MinecraftFrameSelectionInstaller frameSelections;
    private final MinecraftFrameCaptureInstaller frameCaptures;
    private final MinecraftMaterialEpochCompiler materialEpochs;
    private final MinecraftLightingCalibration calibration;
    private final RtEntityTextures entityTextures;
    private final RtEntities entities;
    private final RtTerrain terrain;
    private final MinecraftTelemetry.Instrumentation instrumentation;

    public MinecraftProvidersExtension(MinecraftFrameSelectionInstaller frameSelections,
                                       MinecraftFrameCaptureInstaller frameCaptures,
                                       MinecraftMaterialEpochCompiler materialEpochs,
                                       MinecraftLightingCalibration calibration,
                                       RtEntityTextures entityTextures, RtEntities entities,
                                       RtTerrain terrain, MinecraftTelemetry.Instrumentation instrumentation) {
        this.frameSelections = java.util.Objects.requireNonNull(frameSelections, "frameSelections");
        this.frameCaptures = java.util.Objects.requireNonNull(frameCaptures, "frameCaptures");
        this.materialEpochs = java.util.Objects.requireNonNull(materialEpochs, "materialEpochs");
        this.calibration = java.util.Objects.requireNonNull(calibration, "calibration");
        this.entityTextures = entityTextures;
        this.entities = entities;
        this.terrain = java.util.Objects.requireNonNull(terrain, "terrain");
        this.instrumentation = java.util.Objects.requireNonNull(instrumentation, "instrumentation");
    }

    private SettingsAccess options;

    @Override
    public void settingsReady(SettingsAccess settings) {
        options = settings;
    }

    @Override public void registerMinecraft(MinecraftApi api) {
        api.sessions().add(context -> MinecraftProgramSession.open(
                context, frameSelections, frameCaptures, materialEpochs, calibration,
                entityTextures, entities, terrain, options, instrumentation));
    }

    @Override public void registerSettings(SettingsRegistry registry) {
        registry.feature(ID).title(DisplayText.translatable("feature.caustica.minecraft"))
                .description(DisplayText.translatable("feature.caustica.minecraft.description"))
                .group(SkyLutPass.GROUP).options(SkyLutPass.OPTIONS)
                .group(FogPass.GROUP).options(FogPass.OPTIONS).register();
    }
}
