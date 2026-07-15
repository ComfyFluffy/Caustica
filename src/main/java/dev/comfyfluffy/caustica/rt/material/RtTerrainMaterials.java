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
    public void rebuild(RtContext ctx, RtBlockMaterials blockMaterials) {
        Map<TextureAtlasSprite, RtBlockMaterials.Entry> entriesBySprite = blockMaterials.preparedEntries();
        List<TextureAtlasSprite> sprites = new ArrayList<>(entriesBySprite.keySet());
        sprites.sort(Comparator.comparing(sprite -> sprite.contents().name().toString()));
        RtBlockMaterials.Entry fallbackEntry = blockMaterials.entry(null);

        int profileVariants = RtMaterials.Profile.values().length * MODEL_VARIANTS * EMISSION_VARIANTS;
        List<MaterialHeaderData> headers = new ArrayList<>(3 + profileVariants
                + sprites.size() * profileVariants);
        List<RtMaterialDesc> descriptions = new ArrayList<>(headers.size());
        add(headers, descriptions, compileDesc(MODEL_OPAQUE, 0, RtMaterials.Profile.DEFAULT,
                false, true), transparentWhiteAverage(), fallbackEntry);
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
                                    profile, emitting, true), transparentWhiteAverage(), fallbackEntry);
                }
            }
        }
        int waterId = headers.size();
        add(headers, descriptions, compileDesc(MODEL_WATER, 0, RtMaterials.Profile.WATER,
                false, true), whiteAverage(), fallbackEntry);
        int lavaId = headers.size();
        add(headers, descriptions, compileDesc(MODEL_OPAQUE, 0, RtMaterials.Profile.LAVA,
                true, true), whiteAverage(), fallbackEntry);

        IdentityHashMap<TextureAtlasSprite, int[]> ids = new IdentityHashMap<>();
        for (TextureAtlasSprite sprite : sprites) {
            RtBlockMaterials.Entry entry = entriesBySprite.get(sprite);
            int features = ((entry.features() & RtBlockMaterials.HAS_S) != 0 ? FEATURE_SPEC : 0)
                    | ((entry.features() & RtBlockMaterials.HAS_N) != 0 ? FEATURE_NORMAL : 0);
            float[] average = averageColor(sprite);
            int[] variants = new int[profileVariants];
            for (RtMaterials.Profile profile : RtMaterials.Profile.values()) {
                for (boolean glass : new boolean[]{false, true}) {
                    for (boolean emitting : new boolean[]{false, true}) {
                        variants[index(profile, glass, emitting)] = headers.size();
                        add(headers, descriptions, compileDesc(glass ? MODEL_GLASS : MODEL_OPAQUE,
                                features, profile, emitting, false), average, entry);
                    }
                }
            }
            ids.put(sprite, variants);
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
        Snapshot next = new Snapshot(epoch, Collections.unmodifiableMap(ids), fallbackVariants, waterId, lavaId,
                List.copyOf(descriptions));
        table = nextTable;
        snapshot = next; // volatile publication: map and arrays are never mutated afterward
        if (oldTable != null) oldTable.destroy();
        CausticaMod.LOGGER.info("RT terrain materials: epoch={}, records={}, sprites={}, tableKiB={}",
                epoch, headers.size(), sprites.size(), byteSize / 1024);
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
                                              boolean emitting, boolean neutral) {
        float roughness = model == MODEL_GLASS ? 0.05f : profile.roughness();
        float metalness = model == MODEL_GLASS ? 0.0f : profile.metalness();
        float ior = model == MODEL_WATER ? 1.333f : (model == MODEL_GLASS ? 1.52f : 1.0f);
        float transmission = model == MODEL_WATER || model == MODEL_GLASS ? 1.0f : 0.0f;
        boolean labPbr = (features & (FEATURE_SPEC | FEATURE_NORMAL)) != 0;
        RtMaterialDesc.Source source = neutral ? RtMaterialDesc.Source.NEUTRAL
                : (labPbr ? RtMaterialDesc.Source.LAB_PBR : RtMaterialDesc.Source.HEURISTIC);
        RtMaterialDesc.EmissionSource emissionSource = (features & FEATURE_SPEC) != 0
                ? RtMaterialDesc.EmissionSource.LAB_PBR
                : (emitting ? RtMaterialDesc.EmissionSource.STATE_UNIFORM : RtMaterialDesc.EmissionSource.NONE);
        return new RtMaterialDesc(model, source, features, roughness, metalness, ior, transmission,
                emissionSource, emitting ? 1.0f : 0.0f, RtMaterialDesc.EmissionSummary.NONE);
    }

    private static void add(List<MaterialHeaderData> headers, List<RtMaterialDesc> descriptions,
                            RtMaterialDesc desc, float[] average, RtBlockMaterials.Entry entry) {
        int packedFeatures = desc.features() | (entry.maxLod() << MAX_LOD_SHIFT);
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

        private Snapshot(long epoch, Map<TextureAtlasSprite, int[]> ids, int[] fallbackVariants,
                         int waterId, int lavaId, List<RtMaterialDesc> descriptions) {
            this.epoch = epoch;
            this.ids = ids;
            this.fallbackVariants = fallbackVariants;
            this.waterId = waterId;
            this.lavaId = lavaId;
            this.descriptions = descriptions;
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
            return resolve(sprite, RtMaterials.profile(state), glass, state != null && state.getLightEmission() > 0);
        }

        public int resolve(TextureAtlasSprite sprite, RtMaterials.Profile profile, boolean glass, boolean emitting) {
            int[] variants = ids.get(sprite);
            int variant = index(profile, glass, emitting);
            return variants != null ? variants[variant] : fallbackVariants[variant];
        }
    }
}
