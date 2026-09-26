package dev.comfyfluffy.caustica.minecraft.client.material;

import com.mojang.blaze3d.platform.NativeImage;
import dev.comfyfluffy.caustica.minecraft.client.CausticaMod;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftResourceIds;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialEmissionIndex;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialImage;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialImageSource;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialTextureAnalysisSource;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialTextureKind;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialTextureResource;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialUv;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialImage;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialRule;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialTextureSource;
import dev.comfyfluffy.caustica.minecraft.content.material.OpenPbrColorBinding;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.minecraft.client.mixin.SpriteContentsAccessor;
import dev.comfyfluffy.caustica.minecraft.client.mixin.TextureAtlasAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Collects Minecraft material texture resources from atlases and resource packs. */
public final class MinecraftMaterialCatalogBuilder {
    private MinecraftMaterialCatalogBuilder() {
    }

    public static List<MaterialTextureResource> build(List<MinecraftMaterialRule> rules,
                                                       MinecraftLightingCalibration calibration) {
        MaterialEmissionIndex emissions = MinecraftEmissionSemantics.analyze();
        float uniformEmissionLuminance = calibration.blockEmissionLuminanceCdM2();
        List<TextureAtlasSprite> sprites = blockSprites();
        List<MaterialTextureResource> blocks = new ArrayList<>();
        for (TextureAtlasSprite sprite : sprites) {
            if (sprite == null) continue;
            blocks.add(atlasResource(sprite, emissions, uniformEmissionLuminance));
        }

        Set<ResourceId> blockNames = new HashSet<>();
        blocks.forEach(resource -> blockNames.add(resource.material()));
        blocks.addAll(standaloneResources(blockNames, rules, uniformEmissionLuminance));
        return List.copyOf(blocks);
    }

    private static MaterialTextureResource atlasResource(TextureAtlasSprite sprite,
                                                           MaterialEmissionIndex emissions,
                                                           float uniformEmissionLuminance) {
        Identifier name = sprite.contents().name();
        ResourceId material = MinecraftResourceIds.resourceId(name);
        Optional<Resource> spec = resource(sibling(name, "_s.png"));
        Optional<Resource> normal = resource(sibling(name, "_n.png"));
        NativeImage original = ((SpriteContentsAccessor) sprite.contents()).caustica$originalImage();
        int[] alphaFrames = exhaustiveAlphaFrames(sprite.contents().getUniqueFrames().toIntArray(),
                original.getWidth(), original.getHeight(),
                sprite.contents().width(), sprite.contents().height());
        MaterialImageSource albedo = borrowed(original, sprite.contents().width(), sprite.contents().height());
        MaterialImageSource specular = spec.map(value -> resourceImage(value, true)).orElse(null);
        MaterialImageSource normalMap = normal.map(value -> resourceImage(value, true)).orElse(null);
        boolean inferEmission = spec.isEmpty() && emissions.permits(material);
        return new MaterialTextureResource(material, MaterialTextureKind.SHARED_ATLAS,
                new MaterialTextureAnalysisSource(sprite.contents().width(), sprite.contents().height(),
                        alphaFrames.length,
                        new MinecraftMaterialTextureSource(albedo, specular, normalMap, inferEmission,
                                alphaFrames,
                                Math.max(1, original.getWidth() / sprite.contents().width()))),
                new MaterialUv(sprite.getU0(), sprite.getV0(),
                        1.0f / (sprite.getU1() - sprite.getU0()),
                        1.0f / (sprite.getV1() - sprite.getV0())),
                spec.isPresent(), normal.isPresent(), spec.isPresent() || inferEmission,
                OpenPbrColorBinding.BASE_COLOR, OpenPbrColorBinding.BASE_COLOR,
                MinecraftMaterialClassifier.dielectricIor(material),
                uniformEmissionLuminance * MinecraftMaterialClassifier.emissionScale(material));
    }

    private static List<MaterialTextureResource> standaloneResources(Set<ResourceId> blockNames,
                                                                      List<MinecraftMaterialRule> rules,
                                                                      float uniformEmissionLuminance) {
        Set<Identifier> discovered = discoverStandalone(blockNames, rules);
        List<MaterialTextureResource> result = new ArrayList<>();
        for (Identifier albedoLocation : discovered) {
            Optional<Resource> albedoResource = resource(albedoLocation);
            if (albedoResource.isEmpty()) continue;
            MaterialImageSource albedoSource = resourceImage(albedoResource.get(), false);
            try (MaterialImage albedo = albedoSource.open()) {
                if (albedo.width() <= 0 || albedo.height() <= 0) continue;
                ResourceId material = MinecraftResourceIds.logicalTexture(albedoLocation);
                Optional<Resource> spec = resource(siblingTexture(albedoLocation, "_s"));
                Optional<Resource> normal = resource(siblingTexture(albedoLocation, "_n"));
                MaterialImageSource specular = spec.map(value -> resourceImage(value, false)).orElse(null);
                MaterialImageSource normalMap = normal.map(value -> resourceImage(value, false)).orElse(null);
                result.add(new MaterialTextureResource(material, MaterialTextureKind.STANDALONE,
                        new MaterialTextureAnalysisSource(albedo.width(), albedo.height(), 1,
                                new MinecraftMaterialTextureSource(albedoSource, specular, normalMap, false)),
                        MaterialUv.IDENTITY, spec.isPresent(), normal.isPresent(), spec.isPresent(),
                        OpenPbrColorBinding.BASE_COLOR, OpenPbrColorBinding.BASE_COLOR,
                        MinecraftMaterialClassifier.dielectricIor(material), uniformEmissionLuminance));
            } catch (Throwable throwable) {
                CausticaMod.LOGGER.warn("RT entity material albedo load failed for {}", albedoLocation, throwable);
            }
        }
        return result;
    }

    private static Set<Identifier> discoverStandalone(Set<ResourceId> blockNames,
                                                       List<MinecraftMaterialRule> rules) {
        Set<Identifier> result = new LinkedHashSet<>();
        Map<Identifier, Resource> authored = Minecraft.getInstance().getResourceManager().listResources(
                "textures", id -> id.getPath().endsWith("_s.png") || id.getPath().endsWith("_n.png"));
        List<Identifier> ordered = new ArrayList<>(authored.keySet());
        ordered.sort(Comparator.comparing(Identifier::toString));
        for (Identifier companion : ordered) {
            String path = companion.getPath();
            Identifier albedo = Identifier.fromNamespaceAndPath(companion.getNamespace(),
                    path.substring(0, path.length() - 6) + ".png");
            ResourceId material = MinecraftResourceIds.logicalTexture(albedo);
            if (!blockNames.contains(material)) result.add(albedo);
        }
        for (MinecraftMaterialRule rule : rules) {
            if (rule.geometry() != null || blockNames.contains(rule.material())) continue;
            Identifier albedo = textureLocation(rule.material());
            result.add(albedo);
        }
        return result;
    }

    private static List<TextureAtlasSprite> blockSprites() {
        TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS);
        List<TextureAtlasSprite> sprites = ((TextureAtlasAccessor) atlas).caustica$sprites();
        return sprites != null ? sprites : List.of();
    }

    private static MaterialImageSource borrowed(NativeImage image, int width, int height) {
        return () -> new MinecraftMaterialImage(image::getPixel, width, height, () -> { });
    }

    private static MaterialImageSource resourceImage(Resource resource, boolean firstFrameOnly) {
        return () -> {
            NativeImage image;
            try (InputStream input = resource.open()) {
                image = NativeImage.read(input);
            }
            int height = firstFrameOnly ? Math.min(image.getHeight(), image.getWidth()) : image.getHeight();
            return new MinecraftMaterialImage(image::getPixel, image.getWidth(), height, image::close);
        };
    }

    private static Optional<Resource> resource(Identifier location) {
        return Minecraft.getInstance().getResourceManager().getResource(location);
    }

    private static Identifier sibling(Identifier name, String suffix) {
        return Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + suffix);
    }

    private static Identifier siblingTexture(Identifier albedo, String suffix) {
        String path = albedo.getPath();
        if (path.endsWith(".png")) path = path.substring(0, path.length() - 4);
        return Identifier.fromNamespaceAndPath(albedo.getNamespace(), path + suffix + ".png");
    }

    private static Identifier textureLocation(ResourceId material) {
        return Identifier.fromNamespaceAndPath(material.namespace(), "textures/" + material.path() + ".png");
    }

    static int[] exhaustiveAlphaFrames(int[] uniqueFrames, int rawWidth, int rawHeight,
                                       int frameWidth, int frameHeight) {
        if (frameWidth <= 0 || frameHeight <= 0 || rawWidth < frameWidth || rawHeight < frameHeight) {
            throw new IllegalArgumentException("Invalid animation sheet dimensions");
        }
        int columns = rawWidth / frameWidth;
        int rows = rawHeight / frameHeight;
        int frameSlots = Math.multiplyExact(columns, rows);
        boolean invalidMetadata = java.util.Arrays.stream(uniqueFrames)
                .anyMatch(frame -> frame < 0 || frame >= frameSlots);
        if (invalidMetadata) {
            return java.util.stream.IntStream.range(0, frameSlots).toArray();
        }
        int[] valid = java.util.Arrays.stream(uniqueFrames)
                .distinct()
                .toArray();
        return valid.length == 0 ? new int[]{0} : valid;
    }

}
