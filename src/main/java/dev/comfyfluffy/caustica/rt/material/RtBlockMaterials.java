package dev.comfyfluffy.caustica.rt.material;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.util.Map;

/**
 * LabPBR {@code _s}/{@code _n} parallel atlases mirroring the block atlas. A thin wrapper over
 * {@link RtParallelAtlas}; {@link #prepareAll} pre-enumerates all block-atlas sprites so terrain
 * extraction's {@link #ensure} is a pure cache lookup.
 *
 * <p>Block-like entities sampling the block atlas reuse these same atlases (prim {@code mat} code 2).
 */
public final class RtBlockMaterials {
    public static final RtBlockMaterials INSTANCE = new RtBlockMaterials();

    public static final int HAS_S = RtParallelAtlas.HAS_S;
    public static final int HAS_N = RtParallelAtlas.HAS_N;

    private final RtParallelAtlas atlas = new RtParallelAtlas(TextureAtlas.LOCATION_BLOCKS, "rt_blocks");

    private RtBlockMaterials() {}

    /** (Re)create both parallel atlases sized to the current block atlas. */
    public void reset() {
        atlas.reset();
    }

    /** Build the {@code _s}/{@code _n} atlases for every block-atlas sprite (parallel decode). */
    public void prepareAll() {
        atlas.prepareAll();
    }

    /** The {@link #HAS_S}|{@link #HAS_N} presence bitmask for a block-atlas sprite. */
    public int ensure(TextureAtlasSprite sprite) {
        return atlas.ensure(sprite);
    }

    /** Immutable material-map flags captured after {@link #prepareAll}. */
    public Map<TextureAtlasSprite, Integer> preparedFlags() {
        return atlas.preparedFlags();
    }

    /** Re-upload atlases that gained sprites since last flush. Call before the trace records. */
    public void flush() {
        atlas.flush();
    }

    /** Free the block parallel material atlases on renderer shutdown. */
    public void destroy() {
        atlas.close();
    }

    /** Vulkan image-view handle of the {@code _s} atlas, or 0 if not created. Stable across uploads. */
    public long viewS() {
        return atlas.viewS();
    }

    /** Vulkan image-view handle of the {@code _n} atlas, or 0 if not created. Stable across uploads. */
    public long viewN() {
        return atlas.viewN();
    }
}
