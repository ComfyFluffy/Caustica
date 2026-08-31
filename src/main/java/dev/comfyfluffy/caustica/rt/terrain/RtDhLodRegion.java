package dev.comfyfluffy.caustica.rt.terrain;

import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/**
 * Presents Distant Horizons LOD data to Caustica's existing terrain mesher as if it were an ordinary
 * 16x16x16 section.
 *
 * <h2>The idea</h2>
 * Rather than writing a second mesher for LOD geometry, this makes LOD data look like the input the
 * tested one already takes. {@link RtTerrainMesher#buildCpuSection} walks a 16-cubed grid calling
 * {@code getBlockState}; back that with DH data and you get full model tessellation, correct sprites,
 * correct material IDs, biome tints, fluid handling and the coplanar-quad resolution — all from the
 * path that already works for near terrain. No new sprite lookup, no new material resolution, no second
 * ABI to keep in sync with {@code PackedSection}.
 *
 * <h2>How it stays cheap: the section is a SCALE, not a downsample</h2>
 * A virtual block here represents {@code 1 &lt;&lt; detailLevel} world blocks on a side. The mesher emits
 * section-local coordinates in 0..16 exactly as it always does, and the TLAS instance carries a
 * {@code 2^detailLevel} scale alongside its translation. So one LOD section covers a
 * {@code (16 * 2^detail)} block cube while costing the triangle budget of one ordinary section — at
 * detail 3 that is a 128-block cube for the price of a 16-block one.
 *
 * <p>This is the whole reason the approach is viable. Meshing DH data at 1:1 and relying on distance to
 * hide it would produce the triangle count of full-detail terrain out to the horizon, which is the
 * problem LOD exists to avoid.
 *
 * <h2>Known approximations, stated rather than hidden</h2>
 * <ul>
 *   <li><b>Light engine and biome lookups fall through to the level.</b> Out past the chunk cache those
 *       return defaults, so distant terrain gets default sky light and default biome tint. For a path
 *       tracer this matters less than it would for a rasterizer — the actual lighting is traced, and
 *       baked light mostly feeds ambient occlusion and emission. But grass and foliage colour at
 *       distance will be the fallback tint, not the biome's. DH's own {@code biomeWrapper} could fix
 *       this and is the obvious follow-up.</li>
 *   <li><b>One block state per virtual block.</b> DH's run-length column is sampled at the virtual
 *       block's centre. A single-block ore vein inside an 8-block cube disappears. That is what LOD
 *       means.</li>
 *   <li><b>Block entities return null</b>, as in the near-terrain region, because the mesher never asks.</li>
 * </ul>
 */
final class RtDhLodRegion implements BlockAndTintGetter {
    /** Section edge in virtual blocks — the same 16 the mesher walks. */
    static final int SECTION_BLOCKS = 16;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final ClientLevel level;
    private final CardinalLighting cardinalLighting;
    private final LevelLightEngine lightEngine;
    /** Dense virtual-block grid, x-major then y then z, so the mesher's inner z loop walks contiguously. */
    private final BlockState[] states = new BlockState[SECTION_BLOCKS * SECTION_BLOCKS * SECTION_BLOCKS];
    private final int detailLevel;
    private final int scale;
    /** World-space origin of the section, in real blocks. */
    private final int originBlockX;
    private final int originBlockY;
    private final int originBlockZ;
    private boolean empty = true;

    RtDhLodRegion(ClientLevel level, int detailLevel, int originBlockX, int originBlockY, int originBlockZ) {
        this.level = level;
        this.cardinalLighting = level.cardinalLighting();
        this.lightEngine = level.getLightEngine();
        this.detailLevel = detailLevel;
        this.scale = 1 << detailLevel;
        this.originBlockX = originBlockX;
        this.originBlockY = originBlockY;
        this.originBlockZ = originBlockZ;
        java.util.Arrays.fill(states, AIR);
    }

    int detailLevel() {
        return detailLevel;
    }

    int scale() {
        return scale;
    }

    int originBlockX() {
        return originBlockX;
    }

    int originBlockY() {
        return originBlockY;
    }

    int originBlockZ() {
        return originBlockZ;
    }

    /** True when nothing was written — the caller should skip meshing entirely rather than build an empty BLAS. */
    boolean isEmpty() {
        return empty;
    }

    /**
     * Rasterises DH boxes into the virtual grid.
     *
     * <p>Boxes are vertical runs of one block state, so this fills a span of virtual Y per box rather
     * than looping blocks. A box whose horizontal footprint is larger than one virtual block (which
     * happens when DH's detail level is coarser than ours) fills the cells it covers; a box smaller
     * than one virtual block writes a single cell, and the last writer wins. Last-writer-wins is
     * deliberate and not a coin flip: {@link RtDhLodSource} returns boxes in column order, so the
     * winner is the topmost run touching that cell, which is the surface you can actually see.
     */
    void fill(List<RtDhLodSource.LodBox> boxes) {
        for (RtDhLodSource.LodBox box : boxes) {
            int localX0 = Math.floorDiv(box.blockX() - originBlockX, scale);
            int localZ0 = Math.floorDiv(box.blockZ() - originBlockZ, scale);
            int spanCells = Math.max(1, box.sizeXZ() / scale);
            int localY0 = Math.floorDiv(box.bottomY() - originBlockY, scale);
            int localY1 = Math.floorDiv(box.topY() - 1 - originBlockY, scale);
            if (localY1 < 0 || localY0 >= SECTION_BLOCKS) {
                continue;
            }
            int yStart = Math.max(localY0, 0);
            int yEnd = Math.min(localY1, SECTION_BLOCKS - 1);
            for (int dx = 0; dx < spanCells; dx++) {
                int lx = localX0 + dx;
                if (lx < 0 || lx >= SECTION_BLOCKS) {
                    continue;
                }
                for (int dz = 0; dz < spanCells; dz++) {
                    int lz = localZ0 + dz;
                    if (lz < 0 || lz >= SECTION_BLOCKS) {
                        continue;
                    }
                    for (int ly = yStart; ly <= yEnd; ly++) {
                        states[index(lx, ly, lz)] = box.blockState();
                        empty = false;
                    }
                }
            }
        }
    }

    private static int index(int x, int y, int z) {
        return (x * SECTION_BLOCKS + y) * SECTION_BLOCKS + z;
    }

    // ---- BlockAndTintGetter ----------------------------------------------------------------------
    //
    // The mesher addresses this in VIRTUAL block coordinates: it is told the section is at
    // (0,0,0)..(16,16,16) and emits section-local vertices in that space. The instance transform scales
    // those back up. So positions arriving here are already local and need no world conversion — which
    // also means this never touches the real world's chunk cache for geometry, only for the tint and
    // light fallbacks below.

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        if (x < 0 || y < 0 || z < 0 || x >= SECTION_BLOCKS || y >= SECTION_BLOCKS || z >= SECTION_BLOCKS) {
            // Out-of-section neighbour queries drive face culling. Returning AIR means boundary faces
            // are always emitted, which costs some triangles but guarantees no hole between adjacent LOD
            // sections. A hole in a path tracer is not a seam — primary rays fall through to the sky and
            // shadow rays fall through, putting a bar of light on the ground.
            return AIR;
        }
        return states[index(x, y, z)];
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return cardinalLighting;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return lightEngine;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null; // never queried by the RT mesher, as in RtSectionSnapshots.Region
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        // Sampled at the section's world origin rather than the virtual position: the virtual position
        // is meaningless in world space, and one tint for the whole LOD section is both cheaper and
        // more stable than a per-cell lookup that would flicker as sections stream.
        return level.getBlockTint(new BlockPos(originBlockX, originBlockY, originBlockZ), resolver);
    }

    @Override
    public boolean hasBiomes() {
        return level.hasBiomes();
    }

    @Override
    public Holder<Biome> getBiomeFabric(BlockPos pos) {
        return level.getBiomeFabric(new BlockPos(originBlockX, originBlockY, originBlockZ));
    }

    @Override
    public int getMinY() {
        return level.getMinY();
    }

    @Override
    public int getHeight() {
        return level.getHeight();
    }
}
