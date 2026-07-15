package dev.comfyfluffy.caustica.rt.material;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.TextureAtlasAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A pair of <b>parallel atlases</b> mirroring a source texture atlas's sprite layout: one {@link
 * NativeImage} sized to the source atlas, into which each sprite's LabPBR {@code _s} (specular) and
 * {@code _n} (normal) map is blitted at the <em>same</em> rect the albedo occupies. A consumer then
 * samples them at the same UV as the albedo — one plain sampler each.
 *
 * <p>Generalized from the block-atlas case ({@link RtBlockMaterials}) so the same machinery serves the
 * block-entity atlases (chest / sign / bed / shulker / banner …) via {@link RtEntityMaterials}: the
 * source atlas is a constructor parameter. A sprite whose {@code _s}/{@code _n} map is missing keeps the
 * caller's fallback (signalled per-prim via the free {@code mat.z}/{@code mat.w} lanes).
 *
 * <p>Upload reuses MC's own texture path ({@link DynamicTexture}); each {@code GpuTextureView} handle is
 * stable across re-uploads, so descriptors are bound once.
 */
public final class RtParallelAtlas {
    /** Per-sprite flag bits returned by {@link #ensure} (stored in {@code mat.z}/{@code mat.w}). */
    public static final int HAS_S = 1;
    public static final int HAS_N = 2;

    private final Identifier sourceAtlas;
    private final String debugLabel;
    private final Atlas specAtlas;
    private final Atlas normalAtlas;
    private int atlasW, atlasH;
    // Per-sprite result cache: bitmask of HAS_S | HAS_N (which maps were found + blitted). Concurrent
    // because prepareAll() populates it from parallel worker threads (one entry per sprite, disjoint).
    private final Map<TextureAtlasSprite, Integer> seen = new ConcurrentHashMap<>();
    private boolean loggedFailure;
    private boolean loggedLazyFallback;

    /** @param label a short, unique-per-source-atlas debug label (DynamicTexture names derive from it). */
    public RtParallelAtlas(Identifier sourceAtlas, String label) {
        this.sourceAtlas = sourceAtlas;
        this.debugLabel = label;
        this.specAtlas = new Atlas("_s.png", label + "_s");
        this.normalAtlas = new Atlas("_n.png", label + "_n");
    }

    /** One parallel atlas (the {@code _s} or {@code _n} map) backed by a CPU image + an MC DynamicTexture. */
    private final class Atlas {
        final String suffix;     // resource suffix, e.g. "_s.png"
        final String label;
        NativeImage image;
        DynamicTexture tex;
        boolean dirty;

        Atlas(String suffix, String label) {
            this.suffix = suffix;
            this.label = label;
        }

        void create() {
            close();
            image = new NativeImage(atlasW, atlasH, true); // zeroed: unfilled texels are gated by mat.z/.w
            tex = new DynamicTexture(() -> label, image);  // creates + uploads the GpuTexture
            dirty = false;
        }

        void close() {
            if (tex != null) {
                tex.close();
                tex = null;
                image = null;
            }
            dirty = false;
        }

        /** Load+blit this sprite's map; returns true if the resource existed. */
        boolean load(TextureAtlasSprite sprite, Identifier name) throws Exception {
            if (image == null) {
                return false;
            }
            Identifier loc = Identifier.fromNamespaceAndPath(name.getNamespace(),
                    "textures/" + name.getPath() + suffix);
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(loc);
            if (res.isEmpty()) {
                return false;
            }
            try (InputStream in = res.get().open(); NativeImage src = NativeImage.read(in)) {
                blit(src, sprite, image);
                dirty = true;
            }
            return true;
        }

        void flush() {
            if (dirty && tex != null) {
                tex.upload();
                dirty = false;
            }
        }

        long view() {
            return tex != null ? vkImageView(tex.getTextureView()) : 0L;
        }
    }

    /**
     * (Re)create both parallel atlases sized to the current source atlas. Called when the world pipeline
     * is (re)created — the source atlas is already resolved by then, so the textures/views exist
     * immediately and can be bound once. No-op (views stay 0) if the atlas isn't ready.
     */
    public void reset() {
        seen.clear();
        specAtlas.close();
        normalAtlas.close();
        try {
            GpuTextureView atlas = Minecraft.getInstance().getTextureManager()
                    .getTexture(sourceAtlas).getTextureView();
            atlasW = atlas.getWidth(0);
            atlasH = atlas.getHeight(0);
            if (atlasW <= 0 || atlasH <= 0) {
                return;
            }
            specAtlas.create();
            normalAtlas.create();
        } catch (Throwable t) {
            warnOnce("RT material atlas creation failed for " + sourceAtlas, t);
            specAtlas.close();
            normalAtlas.close();
        }
    }

    /** Whether the parallel atlases were created (the source atlas was ready at {@link #reset}). */
    public boolean isReady() {
        return specAtlas.image != null;
    }

    /**
     * Decode + blit a sprite's {@code _s}/{@code _n} maps into the parallel atlases and return the
     * {@link #HAS_S}|{@link #HAS_N} bitmask. Pure CPU (resource read + blit to the sprite's disjoint atlas
     * rect); safe to call from parallel threads. Does not touch {@link #seen} or upload — callers do.
     */
    private int loadSpriteFlags(TextureAtlasSprite sprite) {
        int flags = 0;
        try {
            Identifier name = sprite.contents().name(); // e.g. minecraft:block/stone, minecraft:entity/chest/normal
            if (specAtlas.load(sprite, name)) {
                flags |= HAS_S;
            }
            if (normalAtlas.load(sprite, name)) {
                flags |= HAS_N;
            }
        } catch (Throwable t) {
            warnOnce("RT material map load failed for a sprite", t);
        }
        return flags;
    }

    /**
     * Build the {@code _s}/{@code _n} atlases for <em>every</em> source-atlas sprite up front, so an
     * extraction-time {@link #ensure} is a pure cache lookup with no per-sprite PNG decode on the hot
     * path. The decode + blit run in parallel (each sprite owns a disjoint atlas rect, {@link #seen} is
     * concurrent); the GPU upload is a single {@link #flush} afterward. No-op if the atlases didn't init.
     */
    public void prepareAll() {
        if (specAtlas.image == null) {
            return;
        }
        List<TextureAtlasSprite> sprites;
        try {
            TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance().getTextureManager().getTexture(sourceAtlas);
            sprites = ((TextureAtlasAccessor) atlas).caustica$sprites();
        } catch (Throwable t) {
            warnOnce("RT material prepareAll: could not enumerate sprites of " + sourceAtlas, t);
            return;
        }
        if (sprites == null) {
            return;
        }
        sprites.parallelStream().forEach(sprite -> {
            if (sprite == null || seen.containsKey(sprite)) {
                return;
            }
            seen.put(sprite, loadSpriteFlags(sprite));
        });
        flush();
        int specCount = 0;
        int normalCount = 0;
        for (int flags : seen.values()) {
            if ((flags & HAS_S) != 0) specCount++;
            if ((flags & HAS_N) != 0) normalCount++;
        }
        RtMaterialDiagnostics.reportAtlas(debugLabel, seen.size(), specCount, normalCount,
                atlasW, atlasH, Integer.BYTES, 2);
    }

    /**
     * The {@code _s}/{@code _n} presence bitmask ({@link #HAS_S}|{@link #HAS_N}) for a sprite. A cache hit
     * after {@link #prepareAll}; otherwise a lazy decode + blit (its upload is picked up by the next {@link
     * #flush}, called pre-trace each frame). The lazy path is expected for the block-entity atlases, which
     * are not pre-enumerated — they are sized + sampled rarely, so per-sprite lazy load is fine.
     */
    public int ensure(TextureAtlasSprite sprite) {
        if (sprite == null || specAtlas.image == null) {
            return 0;
        }
        Integer cached = seen.get(sprite);
        if (cached != null) {
            return cached;
        }
        if (!loggedLazyFallback) {
            loggedLazyFallback = true;
            CausticaMod.LOGGER.info("RT material ensure() lazy-loaded sprite {} for atlas {}",
                    sprite.contents().name(), sourceAtlas);
        }
        int flags = loadSpriteFlags(sprite);
        seen.put(sprite, flags);
        return flags;
    }

    /** Immutable identity-keyed view of the fully prepared sprite flags. */
    public Map<TextureAtlasSprite, Integer> preparedFlags() {
        return Collections.unmodifiableMap(new IdentityHashMap<>(seen));
    }

    /** Re-upload the atlases that gained sprites since the last flush. Call before the trace records. */
    public void flush() {
        specAtlas.flush();
        normalAtlas.flush();
    }

    /** Vulkan image-view handle of the {@code _s} atlas, or 0 if not created. Stable across uploads. */
    public long viewS() {
        return specAtlas.view();
    }

    /** Vulkan image-view handle of the {@code _n} atlas, or 0 if not created. Stable across uploads. */
    public long viewN() {
        return normalAtlas.view();
    }

    /** Free both parallel atlases (GPU textures + CPU images). */
    public void close() {
        seen.clear();
        specAtlas.close();
        normalAtlas.close();
    }

    /**
     * Blit a map into the atlas at the sprite's content rect, nearest-sampled to the sprite's resolution
     * (the pack's map may be a different size; animated sprites use frame 0 = the top {@code width×height}
     * region). Content origin derives from the sprite UVs (avoids the private {@code padding} field).
     */
    private void blit(NativeImage src, TextureAtlasSprite sprite, NativeImage dst) {
        int w = sprite.contents().width();
        int h = sprite.contents().height();
        int cx = Math.round(sprite.getU0() * atlasW);
        int cy = Math.round(sprite.getV0() * atlasH);
        int sw = src.getWidth();
        int sh = Math.min(src.getHeight(), src.getWidth()); // animated strip: clamp to a square frame 0
        for (int dy = 0; dy < h; dy++) {
            int sy = Math.min(sh - 1, dy * sh / h);
            int ty = cy + dy;
            if (ty < 0 || ty >= atlasH) {
                continue;
            }
            for (int dx = 0; dx < w; dx++) {
                int sx = Math.min(sw - 1, dx * sw / w);
                int tx = cx + dx;
                if (tx < 0 || tx >= atlasW) {
                    continue;
                }
                dst.setPixel(tx, ty, src.getPixel(sx, sy));
            }
        }
    }

    private void warnOnce(String msg, Throwable t) {
        if (!loggedFailure) {
            loggedFailure = true;
            CausticaMod.LOGGER.warn(msg, t);
        }
    }

    private static long vkImageView(GpuTextureView view) {
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        return 0L;
    }
}
