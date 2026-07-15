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

        int profileVariants = RtMaterials.Profile.values().length * MODEL_VARIANTS;
        List<MaterialHeaderData> headers = new ArrayList<>(3 + profileVariants
                + sprites.size() * profileVariants);
        headers.add(header(MODEL_OPAQUE, 0, RtMaterials.Profile.DEFAULT, transparentWhiteAverage(), fallbackEntry));
        int[] fallbackVariants = new int[RtMaterials.Profile.values().length * MODEL_VARIANTS];
        for (RtMaterials.Profile profile : RtMaterials.Profile.values()) {
            int opaqueIndex = index(profile, false);
            if (profile == RtMaterials.Profile.DEFAULT) {
                fallbackVariants[opaqueIndex] = 0;
            } else {
                fallbackVariants[opaqueIndex] = headers.size();
                headers.add(header(MODEL_OPAQUE, 0, profile, transparentWhiteAverage(), fallbackEntry));
            }
            fallbackVariants[index(profile, true)] = headers.size();
            headers.add(header(MODEL_GLASS, 0, profile, transparentWhiteAverage(), fallbackEntry));
        }
        int waterId = headers.size();
        headers.add(header(MODEL_WATER, 0, RtMaterials.Profile.WATER, whiteAverage(), fallbackEntry));
        int lavaId = headers.size();
        headers.add(header(MODEL_OPAQUE, 0, RtMaterials.Profile.LAVA, whiteAverage(), fallbackEntry));

        IdentityHashMap<TextureAtlasSprite, int[]> ids = new IdentityHashMap<>();
        for (TextureAtlasSprite sprite : sprites) {
            RtBlockMaterials.Entry entry = entriesBySprite.get(sprite);
            int features = ((entry.features() & RtBlockMaterials.HAS_S) != 0 ? FEATURE_SPEC : 0)
                    | ((entry.features() & RtBlockMaterials.HAS_N) != 0 ? FEATURE_NORMAL : 0);
            float[] average = averageColor(sprite);
            int[] variants = new int[RtMaterials.Profile.values().length * MODEL_VARIANTS];
            for (RtMaterials.Profile profile : RtMaterials.Profile.values()) {
                variants[index(profile, false)] = headers.size();
                headers.add(header(MODEL_OPAQUE, features, profile, average, entry));
                variants[index(profile, true)] = headers.size();
                headers.add(header(MODEL_GLASS, features, profile, average, entry));
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
                headers.size());
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

    private static int index(RtMaterials.Profile profile, boolean glass) {
        return profile.ordinal() * MODEL_VARIANTS + (glass ? VARIANT_GLASS : VARIANT_OPAQUE);
    }

    private static MaterialHeaderData header(int model, int features, RtMaterials.Profile profile,
                                             float[] average, RtBlockMaterials.Entry entry) {
        float roughness = model == MODEL_GLASS ? 0.05f : profile.roughness();
        float metalness = model == MODEL_GLASS ? 0.0f : profile.metalness();
        float ior = model == MODEL_WATER ? 1.333f : (model == MODEL_GLASS ? 1.52f : 1.0f);
        float transmission = model == MODEL_WATER || model == MODEL_GLASS ? 1.0f : 0.0f;
        int packedFeatures = features | (entry.maxLod() << MAX_LOD_SHIFT);
        return new MaterialHeaderData(model, packedFeatures, entry.textureSlot(), 0,
                new Float4(entry.materialU(), entry.materialV(), entry.materialDu(), entry.materialDv()),
                new Float4(entry.albedoU(), entry.albedoV(), entry.albedoInvDu(), entry.albedoInvDv()),
                new Float4(roughness, metalness, ior, transmission),
                new Float4(average[0], average[1], average[2], average[3]));
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
        private final int materialCount;

        private Snapshot(long epoch, Map<TextureAtlasSprite, int[]> ids, int[] fallbackVariants,
                         int waterId, int lavaId, int materialCount) {
            this.epoch = epoch;
            this.ids = ids;
            this.fallbackVariants = fallbackVariants;
            this.waterId = waterId;
            this.lavaId = lavaId;
            this.materialCount = materialCount;
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
            return materialCount;
        }

        public int resolve(TextureAtlasSprite sprite, RtMaterials.Profile profile, boolean glass) {
            int[] variants = ids.get(sprite);
            int variant = index(profile, glass);
            return variants != null ? variants[variant] : fallbackVariants[variant];
        }
    }
}
