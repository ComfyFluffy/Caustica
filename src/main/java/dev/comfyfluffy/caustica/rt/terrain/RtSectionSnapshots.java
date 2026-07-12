package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.rt.RtFrameStats;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.DebugLevelSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/**
 * Persistent, render-thread-only cache of per-section palette snapshots for RT tessellation, replacing
 * vanilla's per-pass {@link net.minecraft.client.renderer.chunk.RenderRegionCache}.
 *
 * <p>The vanilla cache dedupes {@code SectionCopy}s only within one dispatch pass, so during a streaming
 * fill a section's palette was re-copied ({@code SimpleBitStorage.copy}, the top CPU hotspot while
 * flying) by up to 27 neighbouring dispatches across passes — and every {@code SectionCopy} also cloned
 * the owning chunk's <em>entire</em> block-entity map, which the RT mesher never reads (block entities
 * render through {@code RtEntities}). Entries here are palette-only and live until the section is
 * edited (dirty hook), its column unloads or leaves the window, or LRU eviction — so a section is
 * copied once per load/edit instead of once per neighbouring dispatch.
 *
 * <p>A cached {@link PalettedContainer} copy is immutable after creation; concurrent reads from worker
 * threads (including one copy shared by several in-flight regions) are safe, matching how vanilla's
 * {@code SectionCopy} containers were already shared within a pass. Cache mutation stays on the render
 * thread.
 */
final class RtSectionSnapshots {
    // Bounds worst-case cache memory to ~16 MB (palette copies run ~2-4 KB). Dispatch is column-coherent
    // nearest-first, so the live working set (in-flight neighbourhoods) is far smaller than this.
    private static final int MAX_ENTRIES = 4096;
    /** Cache/region marker for a section with no copyable states (all air, unloaded, or out of range). */
    private static final Object AIR = new Object();

    private final Long2ObjectLinkedOpenHashMap<Object> cache = new Long2ObjectLinkedOpenHashMap<>();

    /** Snapshot the 3×3×3 neighbourhood of a section, reusing cached palette copies (render thread). */
    Region createRegion(ClientLevel level, int scx, int scy, int scz) {
        Object[] sections = new Object[27];
        for (int z = -1; z <= 1; z++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    sections[(x + 1) + (y + 1) * 3 + (z + 1) * 9] = section(level, scx + x, scy + y, scz + z);
                }
            }
        }
        return new Region(level, scx - 1, scy - 1, scz - 1, sections);
    }

    /** Drop a stale entry (edited section, or its column unloaded / left the window). Render thread. */
    void invalidate(long sectionKey) {
        cache.remove(sectionKey);
    }

    void clear() {
        cache.clear();
    }

    private Object section(ClientLevel level, int scx, int scy, int scz) {
        long key = RtTerrain.sectionKey(scx, scy, scz);
        Object cached = cache.getAndMoveToLast(key);
        if (cached != null) {
            return cached;
        }
        Object copy = copySection(level, scx, scy, scz);
        cache.putAndMoveToLast(key, copy);
        if (cache.size() > MAX_ENTRIES) {
            cache.removeFirst();
        }
        return copy;
    }

    private static Object copySection(ClientLevel level, int scx, int scy, int scz) {
        LevelChunk chunk = level.getChunk(scx, scz);
        if (chunk instanceof EmptyLevelChunk) {
            return AIR;
        }
        LevelChunkSection[] sections = chunk.getSections();
        int index = chunk.getSectionIndexFromSectionY(scy);
        if (index < 0 || index >= sections.length) {
            return AIR;
        }
        LevelChunkSection section = sections[index];
        if (section.hasOnlyAir()) {
            return AIR;
        }
        RtFrameStats.FRAME.count("sectionCopies", 1);
        return section.getStates().copy();
    }

    /**
     * The immutable 3×3×3 view a tessellation job reads. Mirrors vanilla's
     * {@code RenderSectionRegion} except that {@link #getBlockEntity} always returns null: the RT
     * mesher never queries block entities (they render through {@code RtEntities}), which is what lets
     * the snapshot skip {@code SectionCopy}'s per-copy clone of the whole chunk's block-entity map.
     */
    static final class Region implements BlockAndTintGetter {
        private final ClientLevel level;
        private final int minSectionX;
        private final int minSectionY;
        private final int minSectionZ;
        private final Object[] sections; // PalettedContainer<BlockState> or AIR, x-then-y-then-z minor
        private final CardinalLighting cardinalLighting;
        private final LevelLightEngine lightEngine;
        private final boolean debug;

        Region(ClientLevel level, int minSectionX, int minSectionY, int minSectionZ, Object[] sections) {
            this.level = level;
            this.minSectionX = minSectionX;
            this.minSectionY = minSectionY;
            this.minSectionZ = minSectionZ;
            this.sections = sections;
            this.cardinalLighting = level.cardinalLighting();
            this.lightEngine = level.getLightEngine();
            this.debug = level.isDebug();
        }

        @Override
        @SuppressWarnings("unchecked")
        public BlockState getBlockState(BlockPos pos) {
            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();
            if (debug) {
                BlockState state = null;
                if (y == 60) {
                    state = Blocks.BARRIER.defaultBlockState();
                }
                if (y == 70) {
                    state = DebugLevelSource.getBlockStateFor(x, z);
                }
                return state == null ? Blocks.AIR.defaultBlockState() : state;
            }
            int index = (SectionPos.blockToSectionCoord(x) - minSectionX)
                    + (SectionPos.blockToSectionCoord(y) - minSectionY) * 3
                    + (SectionPos.blockToSectionCoord(z) - minSectionZ) * 9;
            Object section = sections[index];
            if (section == AIR) {
                return Blocks.AIR.defaultBlockState();
            }
            return ((PalettedContainer<BlockState>) section).get(x & 15, y & 15, z & 15);
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
            return null; // never queried by the RT mesher — see class javadoc
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return level.getBlockTint(pos, resolver);
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
}
