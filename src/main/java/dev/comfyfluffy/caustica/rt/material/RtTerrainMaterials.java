package dev.comfyfluffy.caustica.rt.material;

import com.mojang.blaze3d.platform.NativeImage;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.SpriteContentsAccessor;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.gen.MaterialHeaderData;
import dev.comfyfluffy.caustica.rt.gen.MaterialHeaderData.Float4;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, resource-epoch terrain material registry. The render thread compiles and uploads it after
 * the block material atlases are prepared; terrain workers only read the published {@link Snapshot}.
 */
public final class RtTerrainMaterials {
    public static final RtTerrainMaterials INSTANCE = new RtTerrainMaterials();

    public static final int MODEL_OPAQUE = 0;
    public static final int MODEL_WATER = 1;
    public static final int MODEL_GLASS = 3;
    public static final int FEATURE_SPEC = 1;
    public static final int FEATURE_NORMAL = 2;
    public static final int FEATURE_HEURISTIC_EMISSION = 4;
    public static final int FEATURE_OVERRIDE_EMISSION = 8;
    private static final int EMISSION_STRENGTH_SHIFT = 8;
    private static final int EMISSION_STRENGTH_MASK = 255;
    private static final float MAX_OVERRIDE_EMISSION_STRENGTH = 4.0f;
    private static final int MAX_LOD_SHIFT = 24;

    private static final int MODEL_VARIANTS = 2; // ordinary opaque/cutout and thin glass
    private static final int EMISSION_VARIANTS = 2; // state-gated emission disabled/enabled
    private static final int VARIANT_OPAQUE = 0;
    private static final int VARIANT_GLASS = 1;

    private volatile Snapshot snapshot;
    private RtBuffer table;
    private long nextEpoch;

    private RtTerrainMaterials() {
    }

    /** Build and atomically publish a complete registry for the currently prepared block atlas. */
    public void rebuild(RtContext ctx, RtBlockMaterials blockMaterials, RtMaterialOverrides overrides) {
        Map<TextureAtlasSprite, RtBlockMaterials.Entry> entriesBySprite = blockMaterials.preparedEntries();
        List<TextureAtlasSprite> sprites = new ArrayList<>(entriesBySprite.keySet());
        sprites.sort(Comparator.comparing(sprite -> sprite.contents().name().toString()));
        RtBlockMaterials.Entry fallbackEntry = blockMaterials.entry(null);

        int profileVariants = RtMaterials.Profile.values().length * MODEL_VARIANTS * EMISSION_VARIANTS;
        List<MaterialHeaderData> headers = new ArrayList<>(3 + profileVariants
                + sprites.size() * profileVariants);
        List<RtMaterialDesc> descriptions = new ArrayList<>(headers.size());
        add(headers, descriptions, compileDesc(MODEL_OPAQUE, 0, RtMaterials.Profile.DEFAULT,
                false, true, RtMaterialDesc.EmissionSummary.NONE), transparentWhiteAverage(), fallbackEntry);
        int[] fallbackVariants = new int[profileVariants];
        for (RtMaterials.Profile profile : RtMaterials.Profile.values()) {
            for (boolean glass : new boolean[]{false, true}) {
                for (boolean emitting : new boolean[]{false, true}) {
                    int variant = index(profile, glass, emitting);
                    if (profile == RtMaterials.Profile.DEFAULT && !glass && !emitting) {
                        fallbackVariants[variant] = 0;
                        continue;
                    }
                    fallbackVariants[variant] = headers.size();
                    add(headers, descriptions, compileDesc(glass ? MODEL_GLASS : MODEL_OPAQUE, 0,
                                    profile, emitting, true, RtMaterialDesc.EmissionSummary.NONE),
                            transparentWhiteAverage(), fallbackEntry);
                }
            }
        }
        int waterId = headers.size();
        add(headers, descriptions, compileDesc(MODEL_WATER, 0, RtMaterials.Profile.WATER,
                false, true, RtMaterialDesc.EmissionSummary.NONE), whiteAverage(), fallbackEntry);
        int lavaId = headers.size();
        add(headers, descriptions, compileDesc(MODEL_OPAQUE, 0, RtMaterials.Profile.LAVA,
                true, true, uniformWhiteSummary()), whiteAverage(), fallbackEntry);

        IdentityHashMap<TextureAtlasSprite, int[]> ids = new IdentityHashMap<>();
        List<MutableCompiledOverride> compiledOverrides = new ArrayList<>();
        for (RtMaterialOverrides.Rule rule : overrides.rules()) {
            compiledOverrides.add(new MutableCompiledOverride(rule));
        }
        for (TextureAtlasSprite sprite : sprites) {
            RtBlockMaterials.Entry entry = entriesBySprite.get(sprite);
            int baseFeatures = ((entry.features() & RtBlockMaterials.HAS_S) != 0 ? FEATURE_SPEC : 0)
                    | ((entry.features() & RtBlockMaterials.HAS_N) != 0 ? FEATURE_NORMAL : 0)
                    | ((entry.features() & RtBlockMaterials.HAS_HEURISTIC_EMISSION) != 0
                    ? FEATURE_HEURISTIC_EMISSION : 0);
            float[] average = averageColor(sprite);
            RtMaterialDesc.EmissionSummary uniformSummary = uniformEmissionSummary(sprite);
            int[] variants = new int[profileVariants];
            for (RtMaterials.Profile profile : RtMaterials.Profile.values()) {
                for (boolean glass : new boolean[]{false, true}) {
                    for (boolean emitting : new boolean[]{false, true}) {
                        int features = emitting ? baseFeatures : baseFeatures & ~FEATURE_HEURISTIC_EMISSION;
                        RtMaterialDesc.EmissionSummary emissionSummary = (features & FEATURE_SPEC) != 0
                                || (features & FEATURE_HEURISTIC_EMISSION) != 0
                                ? entry.emissionSummary()
                                : (emitting ? uniformSummary : RtMaterialDesc.EmissionSummary.NONE);
                        variants[index(profile, glass, emitting)] = headers.size();
                        add(headers, descriptions, compileDesc(glass ? MODEL_GLASS : MODEL_OPAQUE,
                                features, profile, emitting, false, emissionSummary), average, entry);
                    }
                }
            }
            ids.put(sprite, variants);

            for (MutableCompiledOverride compiled : compiledOverrides) {
                if (!compiled.rule.matchesSprite(sprite)) continue;
                int[] overrideVariants = new int[profileVariants];
                for (RtMaterials.Profile profile : RtMaterials.Profile.values()) {
                    for (boolean glass : new boolean[]{false, true}) {
                        for (boolean emitting : new boolean[]{false, true}) {
                            int features = emitting ? baseFeatures : baseFeatures & ~FEATURE_HEURISTIC_EMISSION;
                            RtMaterialDesc.EmissionSummary summary = (features & (FEATURE_SPEC
                                    | FEATURE_HEURISTIC_EMISSION)) != 0 ? entry.emissionSummary()
                                    : (emitting ? uniformSummary : RtMaterialDesc.EmissionSummary.NONE);
                            RtMaterialDesc base = compileDesc(glass ? MODEL_GLASS : MODEL_OPAQUE,
                                    features, profile, emitting, false, summary);
                            RtMaterialDesc desc = compiled.rule.apply(base, entry.overrideEmissionSummary());
                            overrideVariants[index(profile, glass, emitting)] = headers.size();
                            add(headers, descriptions, desc, average, entry);
                        }
                    }
                }
                compiled.ids.put(sprite, overrideVariants);
            }
        }

        long byteSize = Math.multiplyExact((long) headers.size(), MaterialHeaderData.BYTE_SIZE);
        if (byteSize > Integer.MAX_VALUE) {
            throw new IllegalStateException("RT terrain material table exceeds mapped-buffer limit: " + byteSize);
        }
        RtBuffer nextTable = ctx.createBuffer(byteSize, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                true, "terrain material table");
        try {
            ByteBuffer mapped = MemoryUtil.memByteBuffer(nextTable.mapped, (int) byteSize)
                    .order(ByteOrder.nativeOrder());
            for (int i = 0; i < headers.size(); i++) {
                ByteBuffer entry = mapped.slice(i * MaterialHeaderData.BYTE_SIZE,
                        MaterialHeaderData.BYTE_SIZE).order(ByteOrder.nativeOrder());
                headers.get(i).write(entry);
            }
            nextTable.flush();
        } catch (Throwable t) {
            nextTable.destroy();
            throw t;
        }

        RtBuffer oldTable = table;
        long epoch = ++nextEpoch;
        List<CompiledOverride> frozenOverrides = compiledOverrides.stream()
                .filter(value -> !value.ids.isEmpty())
                .map(MutableCompiledOverride::freeze).toList();
        for (MutableCompiledOverride compiled : compiledOverrides) {
            if (compiled.ids.isEmpty()) {
                CausticaMod.LOGGER.warn("RT material override {} matched no block-atlas sprite ({})",
                        compiled.rule.source(), compiled.rule.sprite());
            }
        }
        Snapshot next = new Snapshot(epoch, Collections.unmodifiableMap(ids), fallbackVariants, waterId, lavaId,
                List.copyOf(descriptions), frozenOverrides);
        table = nextTable;
        snapshot = next; // volatile publication: map and arrays are never mutated afterward
        if (oldTable != null) oldTable.destroy();
        long emissive = descriptions.stream().filter(desc -> desc.emissionSource() != RtMaterialDesc.EmissionSource.NONE)
                .count();
        long inferred = descriptions.stream().filter(desc -> desc.emissionSource()
                == RtMaterialDesc.EmissionSource.HEURISTIC_MASK).count();
        long authoredEmission = descriptions.stream().filter(desc -> desc.emissionSource()
                == RtMaterialDesc.EmissionSource.LAB_PBR).count();
        long uniformEmission = descriptions.stream().filter(desc -> desc.emissionSource()
                == RtMaterialDesc.EmissionSource.STATE_UNIFORM).count();
        long overrideEmission = descriptions.stream().filter(desc -> desc.emissionSource()
                == RtMaterialDesc.EmissionSource.OVERRIDE).count();
        double averageCoverage = descriptions.stream().filter(desc -> desc.emissionSummary().emissive())
                .mapToDouble(desc -> desc.emissionSummary().coverage()).average().orElse(0.0);
        CausticaMod.LOGGER.info("RT terrain materials: epoch={}, records={}, sprites={}, overrideRules={}, matchedOverrides={}, emissive={}, labPbrEmission={}, heuristicMasks={}, uniformEmission={}, overrideEmission={}, avgEmissionCoverage={}, tableKiB={}",
                epoch, headers.size(), sprites.size(), overrides.rules().size(), frozenOverrides.size(), emissive,
                authoredEmission, inferred, uniformEmission, overrideEmission,
                String.format(java.util.Locale.ROOT, "%.3f", averageCoverage), byteSize / 1024);
    }

    public Snapshot requireSnapshot() {
        Snapshot current = snapshot;
        if (current == null) throw new IllegalStateException("RT terrain materials are not prepared");
        return current;
    }

    public long epoch() {
        Snapshot current = snapshot;
        return current != null ? current.epoch() : 0L;
    }

    public boolean isReady() {
        return snapshot != null && table != null;
    }

    public long tableAddress() {
        RtBuffer current = table;
        if (current == null) throw new IllegalStateException("RT terrain material table is not uploaded");
        return current.deviceAddress;
    }

    /** Caller must ensure no in-flight trace references the current table. */
    public void destroy() {
        snapshot = null;
        if (table != null) {
            table.destroy();
            table = null;
        }
    }

    private static int index(RtMaterials.Profile profile, boolean glass, boolean emitting) {
        return (profile.ordinal() * MODEL_VARIANTS + (glass ? VARIANT_GLASS : VARIANT_OPAQUE))
                * EMISSION_VARIANTS + (emitting ? 1 : 0);
    }

    private static RtMaterialDesc compileDesc(int model, int features, RtMaterials.Profile profile,
                                              boolean emitting, boolean neutral,
                                              RtMaterialDesc.EmissionSummary emissionSummary) {
        float roughness = model == MODEL_GLASS ? 0.05f : profile.roughness();
        float metalness = model == MODEL_GLASS ? 0.0f : profile.metalness();
        float ior = model == MODEL_WATER ? 1.333f : (model == MODEL_GLASS ? 1.52f : 1.0f);
        float transmission = model == MODEL_WATER || model == MODEL_GLASS ? 1.0f : 0.0f;
        boolean labPbr = (features & (FEATURE_SPEC | FEATURE_NORMAL)) != 0;
        RtMaterialDesc.Source source = neutral ? RtMaterialDesc.Source.NEUTRAL
                : (labPbr ? RtMaterialDesc.Source.LAB_PBR : RtMaterialDesc.Source.HEURISTIC);
        RtMaterialDesc.EmissionSource emissionSource;
        if ((features & FEATURE_SPEC) != 0) {
            emissionSource = RtMaterialDesc.EmissionSource.LAB_PBR;
        } else if ((features & FEATURE_HEURISTIC_EMISSION) != 0) {
            emissionSource = RtMaterialDesc.EmissionSource.HEURISTIC_MASK;
        } else if (emitting) {
            emissionSource = RtMaterialDesc.EmissionSource.STATE_UNIFORM;
        } else {
            emissionSource = RtMaterialDesc.EmissionSource.NONE;
        }
        float emissionStrength = emissionSource == RtMaterialDesc.EmissionSource.NONE ? 0.0f : 1.0f;
        return new RtMaterialDesc(model, source, features, roughness, metalness, ior, transmission,
                emissionSource, emissionStrength, emissionSummary);
    }

    private static void add(List<MaterialHeaderData> headers, List<RtMaterialDesc> descriptions,
                            RtMaterialDesc desc, float[] average, RtBlockMaterials.Entry entry) {
        int packedFeatures = desc.features() | (entry.maxLod() << MAX_LOD_SHIFT);
        if ((desc.features() & FEATURE_OVERRIDE_EMISSION) != 0) {
            int strength = Math.round(Math.min(MAX_OVERRIDE_EMISSION_STRENGTH, desc.emissionStrength())
                    * (EMISSION_STRENGTH_MASK / MAX_OVERRIDE_EMISSION_STRENGTH));
            packedFeatures |= strength << EMISSION_STRENGTH_SHIFT;
        }
        headers.add(new MaterialHeaderData(desc.model(), packedFeatures, entry.textureSlot(), 0,
                new Float4(entry.materialU(), entry.materialV(), entry.materialDu(), entry.materialDv()),
                new Float4(entry.albedoU(), entry.albedoV(), entry.albedoInvDu(), entry.albedoInvDv()),
                new Float4(desc.roughness(), desc.metalness(), desc.ior(), desc.transmission()),
                new Float4(average[0], average[1], average[2], average[3])));
        descriptions.add(desc);
    }

    private static float[] whiteAverage() {
        return new float[]{1.0f, 1.0f, 1.0f, 1.0f};
    }

    private static float[] transparentWhiteAverage() {
        return new float[]{1.0f, 1.0f, 1.0f, 0.0f};
    }

    private static RtMaterialDesc.EmissionSummary uniformWhiteSummary() {
        return new RtMaterialDesc.EmissionSummary(1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static RtMaterialDesc.EmissionSummary uniformEmissionSummary(TextureAtlasSprite sprite) {
        var contents = sprite.contents();
        int width = contents.width();
        int height = contents.height();
        NativeImage image = ((SpriteContentsAccessor) contents).caustica$originalImage();
        if (image == null || width <= 0 || height <= 0) return RtMaterialDesc.EmissionSummary.NONE;
        double r = 0.0, g = 0.0, b = 0.0, luminance = 0.0;
        int covered = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getPixel(x, y);
                float alpha = ARGB.alpha(pixel) / 255.0f;
                float lr = srgbToLinear(ARGB.red(pixel) / 255.0f) * alpha;
                float lg = srgbToLinear(ARGB.green(pixel) / 255.0f) * alpha;
                float lb = srgbToLinear(ARGB.blue(pixel) / 255.0f) * alpha;
                r += lr;
                g += lg;
                b += lb;
                luminance += 0.2126f * lr + 0.7152f * lg + 0.0722f * lb;
                if (alpha > 1.0f / 255.0f) covered++;
            }
        }
        float inv = 1.0f / (width * (float) height);
        if (luminance <= 0.0) return RtMaterialDesc.EmissionSummary.NONE;
        return new RtMaterialDesc.EmissionSummary((float) r * inv, (float) g * inv, (float) b * inv,
                (float) luminance * inv, covered * inv);
    }

    private static float srgbToLinear(float value) {
        return value <= 0.04045f ? value / 12.92f
                : (float) Math.pow((value + 0.055f) / 1.055f, 2.4f);
    }

    private static final class MutableCompiledOverride {
        final RtMaterialOverrides.Rule rule;
        final IdentityHashMap<TextureAtlasSprite, int[]> ids = new IdentityHashMap<>();

        MutableCompiledOverride(RtMaterialOverrides.Rule rule) {
            this.rule = rule;
        }

        CompiledOverride freeze() {
            return new CompiledOverride(rule, Collections.unmodifiableMap(new IdentityHashMap<>(ids)));
        }
    }

    private record CompiledOverride(RtMaterialOverrides.Rule rule, Map<TextureAtlasSprite, int[]> ids) {
    }

    /** Average raw PNG RGB/A over the first sprite frame, matching the previous shadow-filter input. */
    private static float[] averageColor(TextureAtlasSprite sprite) {
        var contents = sprite.contents();
        int width = contents.width();
        int height = contents.height();
        NativeImage image = ((SpriteContentsAccessor) contents).caustica$originalImage();
        if (image == null || width <= 0 || height <= 0) return transparentWhiteAverage();
        long sr = 0L, sg = 0L, sb = 0L, sa = 0L;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getPixel(x, y);
                sr += ARGB.red(pixel);
                sg += ARGB.green(pixel);
                sb += ARGB.blue(pixel);
                sa += ARGB.alpha(pixel);
            }
        }
        float scale = 1.0f / (width * (float) height * 255.0f);
        return new float[]{sr * scale, sg * scale, sb * scale, sa * scale};
    }

    /** Read-only lookup captured once by a terrain task. */
    public static final class Snapshot {
        private final long epoch;
        private final Map<TextureAtlasSprite, int[]> ids;
        private final int[] fallbackVariants;
        private final int waterId;
        private final int lavaId;
        private final List<RtMaterialDesc> descriptions;
        private final List<CompiledOverride> overrides;

        private Snapshot(long epoch, Map<TextureAtlasSprite, int[]> ids, int[] fallbackVariants,
                         int waterId, int lavaId, List<RtMaterialDesc> descriptions,
                         List<CompiledOverride> overrides) {
            this.epoch = epoch;
            this.ids = ids;
            this.fallbackVariants = fallbackVariants;
            this.waterId = waterId;
            this.lavaId = lavaId;
            this.descriptions = descriptions;
            this.overrides = overrides;
        }

        public long epoch() {
            return epoch;
        }

        public int waterId() {
            return waterId;
        }

        public int lavaId() {
            return lavaId;
        }

        public int materialCount() {
            return descriptions.size();
        }

        public RtMaterialDesc material(int materialId) {
            return descriptions.get(materialId);
        }

        public int resolve(TextureAtlasSprite sprite, BlockState state, boolean glass) {
            RtMaterials.Profile profile = RtMaterials.profile(state);
            boolean emitting = state != null && state.getLightEmission() > 0;
            int variant = index(profile, glass, emitting);
            for (CompiledOverride override : overrides) {
                if (!override.rule.matches(sprite, state)) continue;
                int[] variants = override.ids.get(sprite);
                if (variants != null) return variants[variant];
            }
            int[] variants = ids.get(sprite);
            return variants != null ? variants[variant] : fallbackVariants[variant];
        }

        public int resolve(TextureAtlasSprite sprite, RtMaterials.Profile profile, boolean glass, boolean emitting) {
            int[] variants = ids.get(sprite);
            int variant = index(profile, glass, emitting);
            return variants != null ? variants[variant] : fallbackVariants[variant];
        }
    }
}
