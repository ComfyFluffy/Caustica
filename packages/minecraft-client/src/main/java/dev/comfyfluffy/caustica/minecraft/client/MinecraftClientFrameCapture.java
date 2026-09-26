package dev.comfyfluffy.caustica.minecraft.client;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.minecraft.client.CausticaMod;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftCapturedFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftCelestialFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftSkyFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFogFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftWeather;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.EnumSet;
import java.util.Optional;

/** Samples Minecraft client, texture, and terrain state exactly once for a render frame. */
final class MinecraftClientFrameCapture {
    private static final float TO_RADIANS = (float) (Math.PI / 180.0);
    private static final Identifier SUN_SPRITE_ID = Identifier.withDefaultNamespace("sun");
    private static final Identifier[] MOON_SPRITE_IDS = moonSpriteIds();
    private static final AtlasWarningTracker ATLAS_WARNINGS = new AtlasWarningTracker();

    private MinecraftClientFrameCapture() { }

    static MinecraftCapturedFrame capture(Minecraft minecraft, double cameraY, double metersPerSceneUnit,
                                          MinecraftLightingCalibration calibration,
                                          MinecraftFogFrame.Grid fogGrid) {
        Optional<MinecraftCelestialFrame> celestial = celestial(
                minecraft, cameraY, metersPerSceneUnit, calibration);
        MinecraftLightFrame light = new MinecraftLightFrame(celestial, helmet(minecraft));
        Optional<MinecraftSkyFrame> sky = celestial.flatMap(value -> atlas(minecraft, value)
                .map(atlas -> new MinecraftSkyFrame(value, atlas)));
        Optional<MinecraftFogFrame> fog = fogGrid == null ? Optional.empty() : celestial.map(value ->
                new MinecraftFogFrame(value,
                        ((double) minecraft.level.getGameTime()
                                + minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false)) / 20.0,
                        fogGrid));
        return new MinecraftCapturedFrame(light, sky, fog);
    }

    private static Optional<MinecraftCelestialFrame> celestial(
            Minecraft minecraft, double cameraY, double metersPerSceneUnit,
            MinecraftLightingCalibration calibration) {
        if (minecraft.player == null || minecraft.level == null
                || !Level.OVERWORLD.equals(minecraft.level.dimension())) return Optional.empty();
        float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var probe = minecraft.gameRenderer.mainCamera().attributeProbe();
        return Optional.of(new MinecraftCelestialFrame(
                probe.getValue(EnvironmentAttributes.SUN_ANGLE, partial) * TO_RADIANS,
                probe.getValue(EnvironmentAttributes.MOON_ANGLE, partial) * TO_RADIANS,
                probe.getValue(EnvironmentAttributes.STAR_ANGLE, partial) * TO_RADIANS,
                probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, partial),
                probe.getValue(EnvironmentAttributes.MOON_PHASE, partial).index(),
                minecraft.level.getSeaLevel(), cameraY, metersPerSceneUnit,
                calibration, new MinecraftWeather(minecraft.level.getRainLevel(partial),
                        minecraft.level.getThunderLevel(partial))));
    }

    private static Optional<MinecraftSkyFrame.CelestialAtlas> atlas(
            Minecraft minecraft, MinecraftCelestialFrame celestial) {
        TextureAtlas atlas;
        try {
            atlas = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.CELESTIALS);
        } catch (RuntimeException failure) {
            if (ATLAS_WARNINGS.first(AtlasFailure.LOOKUP)) {
                CausticaMod.LOGGER.warn("RT sky unavailable: Minecraft celestial atlas lookup failed", failure);
            }
            return Optional.empty();
        }
        Object textureView = atlas.getTextureView();
        if (!(textureView instanceof VulkanGpuTextureView view)) {
            if (ATLAS_WARNINGS.first(AtlasFailure.NON_VULKAN_VIEW)) {
                CausticaMod.LOGGER.warn("RT sky unavailable: Minecraft celestial atlas view is not Vulkan ({})",
                        textureView == null ? "null" : textureView.getClass().getName());
            }
            return Optional.empty();
        }
        GpuFormat format = view.texture().getFormat();
        if (format != GpuFormat.RGBA8_UNORM) {
            if (ATLAS_WARNINGS.first(AtlasFailure.FORMAT)) {
                CausticaMod.LOGGER.warn("RT sky unavailable: Minecraft celestial atlas format is {}, expected {}",
                        format, GpuFormat.RGBA8_UNORM);
            }
            return Optional.empty();
        }
        try {
            TextureAtlasSprite sun = atlas.getSprite(SUN_SPRITE_ID);
            int phase = Math.clamp(celestial.moonPhaseIndex(), 0, MOON_SPRITE_IDS.length - 1);
            TextureAtlasSprite moon = atlas.getSprite(MOON_SPRITE_IDS[phase]);
            return Optional.of(new MinecraftSkyFrame.CelestialAtlas(
                    new MinecraftCelestialAtlasImage(view.texture()), view.baseMipLevel(),
                    view.mipLevels(), uv(sun), uv(moon)));
        } catch (RuntimeException failure) {
            if (ATLAS_WARNINGS.first(AtlasFailure.LOOKUP)) {
                CausticaMod.LOGGER.warn("RT sky unavailable: Minecraft celestial sprite lookup failed", failure);
            }
            return Optional.empty();
        }
    }

    enum AtlasFailure { LOOKUP, NON_VULKAN_VIEW, FORMAT }

    static final class AtlasWarningTracker {
        private final EnumSet<AtlasFailure> warned = EnumSet.noneOf(AtlasFailure.class);

        synchronized boolean first(AtlasFailure failure) { return warned.add(failure); }
    }

    private static Optional<LightDescriptor.Spot> helmet(Minecraft minecraft) {
        if (minecraft.player == null
                || !minecraft.player.getItemBySlot(EquipmentSlot.HEAD).is(CausticaItems.SPOTLIGHT_HELMET)) {
            return Optional.empty();
        }
        Vec3 eye = minecraft.player.getEyePosition();
        Vector3fc forward = minecraft.gameRenderer.mainCamera().forwardVector();
        return Optional.of(new LightDescriptor.Spot(
                eye.x + forward.x() * 0.18, eye.y - 0.08 + forward.y() * 0.18,
                eye.z + forward.z() * 0.18, forward.x(), forward.y(), forward.z(),
                48.0, Math.toRadians(22.0), 720.0, 690.0, 610.0));
    }

    private static MinecraftSkyFrame.Uv uv(TextureAtlasSprite sprite) {
        return new MinecraftSkyFrame.Uv(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
    }

    private static Identifier[] moonSpriteIds() {
        MoonPhase[] phases = MoonPhase.values();
        Identifier[] ids = new Identifier[phases.length];
        for (int i = 0; i < phases.length; i++) {
            ids[i] = Identifier.withDefaultNamespace("moon/" + phases[i].getSerializedName());
        }
        return ids;
    }
}
