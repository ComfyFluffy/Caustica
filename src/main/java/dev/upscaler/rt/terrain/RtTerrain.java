package dev.upscaler.rt.terrain;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.upscaler.UpscalerMod;
import dev.upscaler.mixin.SpriteContentsAccessor;
import dev.upscaler.rt.RtComposite;
import dev.upscaler.rt.RtContext;
import dev.upscaler.rt.RtDebugLabels;
import dev.upscaler.rt.RtDeviceBringup;
import dev.upscaler.rt.accel.RtAccel;
import dev.upscaler.rt.accel.RtBuffer;
import dev.upscaler.rt.accel.RtBufferPool;
import dev.upscaler.rt.material.RtBlockMaterials;
import dev.upscaler.rt.material.RtMaterials;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.util.ARGB;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;

/**
 * Per-section terrain residency synced to vanilla's loaded chunks. A singleton manager
 * keeps a map of resident 16³ sections; each tick it polls the render-distance window around the
 * player, builds newly-in-range sections (capped per tick) and frees out-of-range ones, then rebuilds
 * the section table + TLAS when the set changed. Residency follows vanilla because a section is only
 * "desired" when its chunk is loaded ({@code hasChunk}), so chunk load/unload drives build/free
 * without any mixin.
 *
 * <p>Geometry comes from vanilla's {@link ModelBlockRenderer} (correct shapes, neighbour cull, biome
 * tint, alpha cutout). Vertices are section-local (f32-exact); each TLAS instance carries a
 * translation {@code sectionOrigin − rebaseOrigin} (rebase = player block at the last rebuild, so
 * transforms stay small at any world coordinate) and an {@code instanceCustomIndex} into a BDA
 * section table ({@code {primAddr, idxAddr, uvAddr, triBase[3], waterGeom}} per section) the hit shaders read.
 *
 * <p>Tessellation reads only an immutable snapshot ({@link RenderSectionRegion}, captured on the render
 * thread via {@link RenderRegionCache} exactly as vanilla's chunk compiler does). CPU meshing runs on
 * {@link RtWorkerPool}; snapshotting, GPU upload, BLAS prepare and queue submission stay on the render
 * thread. The BLAS build itself is an async GPU submit, and frees are deferred frames-in-flight-safe
 * (no {@code waitIdle} on the hot path).
 */
public final class RtTerrain {
    private static final int VIEW_SECTIONS_V = Integer.getInteger("upscaler.rt.viewSectionsV", 6);
    // CPU tessellation runs on RtWorkerPool. The render thread snapshots RenderSectionRegions, uploads
    // completed meshes, prepares BLASes, and submits the GPU build.
    private static final int ASYNC_DISPATCH_PER_TICK = Integer.getInteger("upscaler.rt.asyncDispatchPerTick", 64);
    private static final int SECTION_RESULTS_PER_TICK = Integer.getInteger("upscaler.rt.sectionResultsPerTick", 64);
    private static final int ASYNC_DISPATCH_MOVING_PER_TICK = Integer.getInteger("upscaler.rt.asyncDispatchMovingPerTick",
            Math.min(ASYNC_DISPATCH_PER_TICK, 24));
    private static final int SECTION_RESULTS_MOVING_PER_TICK = Integer.getInteger("upscaler.rt.sectionResultsMovingPerTick",
            Math.min(SECTION_RESULTS_PER_TICK, 24));
    // Backpressure cap: stop dispatching once this many sections are in flight. Bounds queue depth and
    // snapshot memory (each RenderSectionRegion holds 27 SectionCopies) when flying through the world.
    private static final int MAX_INFLIGHT = Integer.getInteger("upscaler.rt.maxInflightSections", 192);
    private static final int SECTION_ENTRY_BYTES = 40; // {u64 primAddr, u64 idxAddr, u64 uvAddr, u32 triBase[3], u32 waterGeom}
    // Sentinel for "this section has no water geometry": no gl_GeometryIndexEXT (0..2) can equal it.
    private static final int NO_WATER_GEOM = 0xFFFFFFFF;
    // Frames a retired resource must outlive before it's freed (> frames-in-flight). The frame counter
    // advances per composite; old TLAS/table/sections are freed this many frames after the swap.
    private static final int KEEP_FRAMES = 4;
    private static final long NO_TESS_TOKEN = Long.MIN_VALUE;
    private static final int SECTION_TABLE_INITIAL_CAPACITY = Integer.getInteger("upscaler.rt.sectionTableInitialCapacity", 512);
    private static final int REBASE_DISTANCE_BLOCKS = Integer.getInteger("upscaler.rt.rebaseDistanceBlocks", 128);

    private static final RtTerrain INSTANCE = new RtTerrain();

    private final Long2ObjectOpenHashMap<SectionGeom> resident = new Long2ObjectOpenHashMap<>();
    private LongOpenHashSet published = new LongOpenHashSet();
    private final LongOpenHashSet empty = new LongOpenHashSet(); // loaded, in-window sections with no geometry
    private final Object dirtyLock = new Object();
    private final LongOpenHashSet dirty = new LongOpenHashSet(); // edited sections to re-extract
    private final LongArrayList dirtyDrain = new LongArrayList();
    // Persistent desired window and queued work. The expensive section window is rebuilt only when the
    // player crosses a section/radius/Y-band boundary; steady ticks poll chunk columns for load changes.
    private final LongOpenHashSet desired = new LongOpenHashSet();
    private final LongOpenHashSet desiredColumns = new LongOpenHashSet();
    private final LongOpenHashSet loadedColumns = new LongOpenHashSet();
    private final LongArrayList missing = new LongArrayList();
    private final LongOpenHashSet queuedMissing = new LongOpenHashSet();
    private final LongArrayList reextract = new LongArrayList();
    private final LongOpenHashSet queuedReextract = new LongOpenHashSet();
    private final List<SectionGeom> removed = new ArrayList<>();
    private final List<PreparedSection> prepared = new ArrayList<>();
    private final RtBufferPool pool = new RtBufferPool();
    private final List<Deferred> deferred = new ArrayList<>(); // frames-in-flight-safe frees
    // Worker tessellation bookkeeping (render-thread only). `inFlight` maps a dispatched section key to a
    // monotonic token; a completed job whose token no longer matches (section re-dirtied / unloaded /
    // left the window since dispatch) is dropped. `jobs` holds the outstanding worker futures.
    private final Long2LongOpenHashMap inFlight = new Long2LongOpenHashMap();
    private final List<TessJob> jobs = new ArrayList<>();
    private final ConcurrentLinkedQueue<TessResult> completedJobs = new ConcurrentLinkedQueue<>();
    private long tessToken;
    private boolean loggedTessFailure; // log the first worker tessellation failure (should never happen)
    private static volatile boolean loggedMeshFailure; // first per-block/fluid meshing throw (swallowed below,
                                                       // so it never reaches the worker-task catch — log once)
    private Pending pending; // in-flight async geometry build, or null
    private RtBuffer sectionTable;
    private int sectionTableCapacity;
    private int nextSectionSlot;
    private final LongArrayList freeSectionSlots = new LongArrayList();
    private final ArrayList<SectionGeom> sectionSlots = new ArrayList<>();
    private final ArrayList<RtAccel.Instance> staticInstanceList = new ArrayList<>();
    // Static section instances (BLAS address + sectionOrigin-rebase transform, customIndex = list
    // order = section-table index). Rebuilt on residency change; the per-frame TLAS in RtComposite
    // merges these with dynamic (entity) instances. RtTerrain no longer owns the traced TLAS — it
    // only builds the static section BLAS asynchronously and publishes the instance list + table.
    private List<RtAccel.Instance> staticInstances;
    private boolean ready;
    // Full-residency invalidation requested off the render thread. Wired to Fabric's
    // InvalidateRenderStateCallback = vanilla LevelExtractor.allChanged() (dimension change via setLevel,
    // render-distance change, F3+A). Consumed in tick(), where the RT context is available.
    private volatile boolean fullClearRequested;
    // Re-extract every live section to recompute LabPBR material flags against (re)loaded atlases — used
    // after a resource reload, which does NOT route through allChanged(). Consumed in tick().
    private volatile boolean reresolveAllRequested;
    private volatile boolean dirtyPending;
    // Rebase origin (player block at the last TLAS rebuild) for the instance transforms + ray camOffset.
    public int blockX;
    public int blockY;
    public int blockZ;
    private boolean windowValid;
    private int windowPcx;
    private int windowPsy;
    private int windowPcz;
    private int windowRadius;
    private int windowLoY;
    private int windowHiY;

    private RtTerrain() {
        inFlight.defaultReturnValue(NO_TESS_TOKEN);
    }

    /** The manager if it currently has resident geometry (built BLAS + instances) to trace, else null. */
    public static RtTerrain currentOrNull() {
        return INSTANCE.ready ? INSTANCE : null;
    }

    public static boolean isSectionReady(BlockPos blockPos) {
        int scx = SectionPos.blockToSectionCoord(blockPos.getX());
        int scy = SectionPos.blockToSectionCoord(blockPos.getY());
        int scz = SectionPos.blockToSectionCoord(blockPos.getZ());
        long key = sectionKey(scx, scy, scz);
        return INSTANCE.ready && ((INSTANCE.resident.containsKey(key) && INSTANCE.published.contains(key)) || INSTANCE.empty.contains(key));
    }

    /**
     * The static section instances to put in this frame's TLAS (BLAS address + sectionOrigin−rebase
     * transform). {@code instanceCustomIndex} is the list position, which {@link RtAccel#prepareTlas}
     * assigns and which the hit shaders use to index the section table. The list is stable between
     * residency rebuilds, so the per-frame TLAS rebuild just re-references the same BLAS each frame.
     */
    public List<RtAccel.Instance> staticInstances() {
        return staticInstances;
    }

    /** Section table device address: {@code {u64 primAddr, u64 idxAddr, u64 uvAddr, u32 triBase[3], u32 waterGeom}} per section, indexed by gl_InstanceCustomIndexEXT. */
    public long tableAddress() {
        return sectionTable.deviceAddress;
    }

    /** Per-tick residency update (windowing + incremental build/free + TLAS rebuild on change). */
    public static void update(RtContext ctx) {
        INSTANCE.tick(ctx);
    }

    public static void shutdown(RtContext ctx) {
        INSTANCE.clear(ctx, true);
    }

    /**
     * Mark every section overlapping a dirty block area — <em>plus the bordering neighbour sections</em>
     * — for re-extraction. Fed by the LevelExtractor hook (vanilla's block-change signal). Thread-safe;
     * drained on the next {@link #tick}.
     *
     * <p>The block area is expanded by one block on every side before mapping to sections, matching
     * vanilla's own dirty expansion. A change touching a section edge therefore also re-extracts the
     * adjacent section: that neighbour's cull faces toward the change (a broken block uncovers a face)
     * and, for fluids, its shared-edge surface heights (the top-face corner heights are averaged from
     * the blocks straddling the section boundary) both depend on the edited block. Without re-extracting
     * it the neighbour keeps stale geometry — opaque holes and a disconnected water surface at the seam.
     * Interior edits stay within one section (±1 doesn't cross a 16-block boundary).
     */
    public static void markBlocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        RtTerrain terrain = INSTANCE;
        synchronized (terrain.dirtyLock) {
            for (int scx = (minX - 1) >> 4; scx <= (maxX + 1) >> 4; scx++) {
                for (int scy = (minY - 1) >> 4; scy <= (maxY + 1) >> 4; scy++) {
                    for (int scz = (minZ - 1) >> 4; scz <= (maxZ + 1) >> 4; scz++) {
                        terrain.dirty.add(sectionKey(scx, scy, scz));
                    }
                }
            }
            terrain.dirtyPending = true;
        }
    }

    /**
     * Request a full residency clear, applied on the next {@link #tick} (render thread, where the RT
     * context is available). Wired to Fabric's {@code InvalidateRenderStateCallback} — vanilla's
     * {@link net.minecraft.client.renderer.extract.LevelExtractor#allChanged()}, which fires on a
     * dimension change (via {@code setLevel}), a render-distance change, and F3+A. Thread-safe.
     */
    public static void requestFullClear() {
        INSTANCE.fullClearRequested = true;
    }

    /**
     * Mark every resident (and known-empty) section for re-extraction so its per-prim LabPBR material
     * flags ({@code hasS}/{@code hasN}) are recomputed against freshly (re)loaded atlases. Used after a
     * resource reload, which does <em>not</em> route through {@code allChanged()}. Geometry stays live
     * until each section's rebuild swaps in. Applied on the next {@link #tick} (render thread).
     */
    public static void markAllDirty() {
        // The atlas (and thus sprite identities + UVs) changed — drop cached per-triangle classifications.
        RtTerrainOmm.clearCache();
        INSTANCE.reresolveAllRequested = true;
    }

    private void tick(RtContext ctx) {
        processDeferredFrees();

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            clear(ctx, false); // left the world — drop all geometry (drains + frees, incl. any in-flight build)
            return;
        }

        // Full clear on an explicit invalidation — vanilla's LevelExtractor.allChanged() via the Fabric
        // InvalidateRenderStateCallback. That fires on a dimension switch (setLevel → allChanged),
        // render-distance change, and F3+A. Without it, End→Overworld keeps the old dimension's geometry:
        // residency is keyed by raw section coords (no world identity), so the same coords stay resident
        // and are never rebuilt for the new world.
        if (fullClearRequested) {
            fullClearRequested = false;
            clear(ctx, false);
        }

        // Re-extract all live sections after a resource reload so material flags pick up the new atlases.
        if (reresolveAllRequested) {
            reresolveAllRequested = false;
            synchronized (dirtyLock) {
                dirty.addAll(resident.keySet());
                dirty.addAll(empty);
                dirtyPending = true;
            }
        }

        // One GPU build in flight at a time. When it finishes, finalize and FALL THROUGH so this same
        // tick prepares the next batch — tick() runs at 20 TPS, so returning here would waste a whole
        // tick per build and halve fill throughput. While the build is still running, there's nothing to
        // do on the render thread (workers keep meshing their queue regardless), so return.
        if (pending != null) {
            if (ctx.isAsyncDone(pending.op)) {
                finalizePending(ctx);
            } else {
                return;
            }
        }

        int pbx = mc.player.getBlockX();
        int pby = mc.player.getBlockY();
        int pbz = mc.player.getBlockZ();
        int pcx = pbx >> 4, pcz = pbz >> 4, psy = pby >> 4;
        int r = horizontalChunks(mc);
        ClientChunkCache chunkSource = level.getChunkSource();
        int minSecY = level.getMinY() >> 4;
        int maxSecY = (level.getMinY() + level.getHeight() - 1) >> 4;
        int loY = Math.max(minSecY, psy - VIEW_SECTIONS_V);
        int hiY = Math.min(maxSecY, psy + VIEW_SECTIONS_V);

        List<SectionGeom> removed = this.removed;
        removed.clear();
        List<PreparedSection> prepared = this.prepared;
        prepared.clear();

        boolean movedWindow = windowValid && (windowPcx != pcx || windowPsy != psy || windowPcz != pcz);
        syncDesiredWindow(chunkSource, pcx, psy, pcz, r, loY, hiY, removed);

        // Re-extract edited sections. Drain under a short lock so concurrent block updates are not lost.
        drainDirty();
        if (!dirtyDrain.isEmpty()) {
            for (LongIterator it = dirtyDrain.iterator(); it.hasNext(); ) {
                long key = it.nextLong();
                handleDirtySection(key);
            }
        }

        // Tessellate + upload new sections (BLAS build deferred to rebuild's single batched submission).
        DispatchContext dispatch = null;
        int dispatchCap = movedWindow ? ASYNC_DISPATCH_MOVING_PER_TICK : ASYNC_DISPATCH_PER_TICK;
        int resultCap = movedWindow ? SECTION_RESULTS_MOVING_PER_TICK : SECTION_RESULTS_PER_TICK;
        int dispatchSlots = Math.min(dispatchCap, Math.max(0, MAX_INFLIGHT - inFlight.size()));
        if (dispatchSlots > 0 && !reextract.isEmpty()) {
            dispatch = dispatchContext(level);
            dispatchSlots -= dispatchReextract(dispatch, chunkSource, dispatchSlots);
        }
        if (dispatchSlots > 0 && !missing.isEmpty()) {
            if (dispatch == null) {
                dispatch = dispatchContext(level);
            }
            dispatchTessellation(dispatch, chunkSource, dispatchSlots);
        }
        drainTessellation(ctx, prepared, removed, resultCap);

        if (!removed.isEmpty() || !prepared.isEmpty()) {
            startBuild(ctx, prepared, removed, pbx, pby, pbz);
        }
    }

    private void syncDesiredWindow(ClientChunkCache chunkSource, int pcx, int psy, int pcz,
                                   int radius, int loY, int hiY, List<SectionGeom> removed) {
        if (!windowValid || windowPcx != pcx || windowPsy != psy || windowPcz != pcz
                || windowRadius != radius || windowLoY != loY || windowHiY != hiY) {
            rebuildDesiredWindow(chunkSource, pcx, psy, pcz, radius, loY, hiY, removed);
        } else {
            pollLoadedColumns(chunkSource, pcx, psy, pcz, loY, hiY, removed);
        }
    }

    private void rebuildDesiredWindow(ClientChunkCache chunkSource, int pcx, int psy, int pcz,
                                      int radius, int loY, int hiY, List<SectionGeom> removed) {
        desired.clear();
        desiredColumns.clear();
        loadedColumns.clear();
        missing.clear();
        queuedMissing.clear();

        for (int scx = pcx - radius; scx <= pcx + radius; scx++) {
            for (int scz = pcz - radius; scz <= pcz + radius; scz++) {
                long column = columnKey(scx, scz);
                desiredColumns.add(column);
                if (!chunkSource.hasChunk(scx, scz)) {
                    continue;
                }
                loadedColumns.add(column);
                addDesiredColumnSections(scx, scz, loY, hiY);
            }
        }

        pruneUndesired(removed);
        removeKeysNotIn(queuedReextract, desired);
        sortMissing(pcx, psy, pcz);
        windowValid = true;
        windowPcx = pcx;
        windowPsy = psy;
        windowPcz = pcz;
        windowRadius = radius;
        windowLoY = loY;
        windowHiY = hiY;
    }

    private void pollLoadedColumns(ClientChunkCache chunkSource, int pcx, int psy, int pcz,
                                   int loY, int hiY, List<SectionGeom> removed) {
        boolean addedMissing = false;
        for (LongIterator it = desiredColumns.iterator(); it.hasNext(); ) {
            long column = it.nextLong();
            int scx = columnX(column);
            int scz = columnZ(column);
            boolean loaded = chunkSource.hasChunk(scx, scz);
            boolean wasLoaded = loadedColumns.contains(column);
            if (loaded == wasLoaded) {
                continue;
            }
            if (loaded) {
                loadedColumns.add(column);
                addedMissing |= addDesiredColumnSections(scx, scz, loY, hiY);
            } else {
                loadedColumns.remove(column);
                removeDesiredColumnSections(scx, scz, loY, hiY, removed);
            }
        }
        if (addedMissing) {
            sortMissing(pcx, psy, pcz);
        }
    }

    private boolean addDesiredColumnSections(int scx, int scz, int loY, int hiY) {
        boolean addedMissing = false;
        for (int scy = loY; scy <= hiY; scy++) {
            long key = sectionKey(scx, scy, scz);
            desired.add(key);
            addedMissing |= enqueueMissingIfNeeded(key);
        }
        return addedMissing;
    }

    private void removeDesiredColumnSections(int scx, int scz, int loY, int hiY, List<SectionGeom> removed) {
        for (int scy = loY; scy <= hiY; scy++) {
            long key = sectionKey(scx, scy, scz);
            desired.remove(key);
            queuedMissing.remove(key);
            queuedReextract.remove(key);
            inFlight.remove(key);
            empty.remove(key);
            SectionGeom g = resident.remove(key);
            if (g != null) {
                removed.add(g);
            }
        }
    }

    private void pruneUndesired(List<SectionGeom> removed) {
        for (ObjectIterator<Long2ObjectMap.Entry<SectionGeom>> it = resident.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            Long2ObjectMap.Entry<SectionGeom> e = it.next();
            if (!desired.contains(e.getLongKey())) {
                removed.add(e.getValue());
                it.remove();
            }
        }
        removeKeysNotIn(empty, desired);
        removeKeysNotIn(inFlight.keySet(), desired);
    }

    private void handleDirtySection(long key) {
        empty.remove(key);
        inFlight.remove(key); // invalidate any in-flight tessellation of the now-stale section
        if (!desired.contains(key)) {
            queuedMissing.remove(key);
            queuedReextract.remove(key);
            return;
        }
        // Keep the old geometry resident + traced; re-dispatch and swap when the new mesh is ready
        // (no eviction gap -> no flicker). Non-resident dirty keys re-enter the normal missing queue.
        SectionGeom g = resident.get(key);
        if (g != null) {
            if (queuedReextract.add(key)) {
                reextract.add(key);
            }
        } else {
            enqueueMissingIfNeeded(key);
        }
    }

    private boolean enqueueMissingIfNeeded(long key) {
        if (resident.containsKey(key) || empty.contains(key) || inFlight.containsKey(key)) {
            return false;
        }
        if (!queuedMissing.add(key)) {
            return false;
        }
        missing.add(key);
        return true;
    }

    private void sortMissing(int pcx, int psy, int pcz) {
        if (missing.size() > 1) {
            missing.sort((a, b) -> Integer.compare(dist2(a, pcx, psy, pcz), dist2(b, pcx, psy, pcz)));
        }
    }

    /**
     * Whether a section may be built now: all eight of its horizontal neighbour chunks are loaded. We
     * extract using vanilla's model/fluid renderers, which read across chunk borders for cull faces and
     * (for fluids) the surrounding blocks that set a water surface's edge/corner heights. If a border
     * section is built while a neighbour chunk is still missing, those reads return air — the
     * neighbour-facing faces and the shared water surface come out wrong, and nothing re-dirties the
     * section once the chunk arrives (a bulk chunk load fires no per-block update). Deferring the build
     * until every neighbour is present makes the first build correct.
     *
     * <p>We deliberately gate on <em>all</em> neighbours, not just those inside the RT view window. A
     * section at the window edge can have an outward neighbour that is outside the current vanilla-loaded
     * area. Without this, the edge section would mesh against air, then show a seam once the player moves
     * and that neighbour becomes interior and is rendered.
     */
    private boolean neighborChunksReady(ClientChunkCache chunkSource, int scx, int scz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (!chunkSource.hasChunk(scx + dx, scz + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private int horizontalChunks(Minecraft mc) {
        return Math.max(1, mc.options.getEffectiveRenderDistance());
    }

    private void drainDirty() {
        dirtyDrain.clear();
        if (!dirtyPending) {
            return;
        }
        synchronized (dirtyLock) {
            for (LongIterator it = dirty.iterator(); it.hasNext(); ) {
                dirtyDrain.add(it.nextLong());
            }
            dirty.clear();
            dirtyPending = false;
        }
    }

    private static void removeKeysNotIn(LongSet keys, LongOpenHashSet keep) {
        for (LongIterator it = keys.iterator(); it.hasNext(); ) {
            if (!keep.contains(it.nextLong())) {
                it.remove();
            }
        }
    }

    private static int dist2(long key, int pcx, int psy, int pcz) {
        int dx = sectionX(key) - pcx, dy = sectionY(key) - psy, dz = sectionZ(key) - pcz;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Tessellate one section to a section-local CPU mesh and precompute pure-CPU sidecar data such as
     * the terrain opacity micromap. <b>Pure CPU + snapshot reads only</b> — no Vulkan, no shared mutable
     * state — so this is the unit a worker thread runs. Terrain LabPBR sprite references are carried to
     * upload, where the render thread resolves them through the material atlas.
     * Returns the mesh (possibly empty — caller checks {@code idx}).
     */
    private static CpuSection buildCpuSection(RenderSectionRegion region, BlockStateModelSet modelSet,
                                              ModelBlockRenderer renderer, QuadCapture capture,
                                              FluidRenderer fluidRenderer, FluidCapture fluidCapture,
                                              BlockPos.MutableBlockPos m, int scx, int scy, int scz) {
        SectionMesh mesh = tessellate(region, modelSet, renderer, capture, fluidRenderer, fluidCapture, m, scx, scy, scz);
        if (mesh.isEmpty()) {
            return new CpuSection(null, null);
        }
        RtAccel.OpacityMicromapInput ommInput =
                RtTerrainOmm.buildInput(mesh.cutout.triCount(), mesh.cutout.cornerUv.elements(), mesh.cutout.ommSprites);
        return new CpuSection(packSection(mesh), ommInput);
    }

    private static PackedSection packSection(SectionMesh mesh) {
        Geom[] buckets = mesh.buckets(); // { opaque, cutout, water }, indexed by RtAccel.BUCKET_*
        int vertFloats = 0, idxCount = 0, uvFloats = 0, primFloats = 0, triCount = 0;
        int[] bucketTris = new int[buckets.length];
        for (int b = 0; b < buckets.length; b++) {
            vertFloats += buckets[b].verts.size();
            idxCount += buckets[b].idx.size();
            uvFloats += buckets[b].cornerUv.size();
            primFloats += buckets[b].prim.size();
            bucketTris[b] = buckets[b].triCount();
            triCount += bucketTris[b];
        }

        float[] positions = new float[vertFloats];
        int[] indices = new int[idxCount];
        float[] uvs = new float[uvFloats];
        float[] material = new float[primFloats];
        TextureAtlasSprite[] materialSprites = new TextureAtlasSprite[triCount];
        int[] triBase = new int[buckets.length];
        int waterGeom = NO_WATER_GEOM;
        int posOff = 0, idxOff = 0, uvOff = 0, matOff = 0, spriteOff = 0, vertBase = 0, triAcc = 0, g = 0;
        for (int b = 0; b < buckets.length; b++) {
            Geom geom = buckets[b];
            if (geom.idx.isEmpty()) {
                continue; // empty bucket -> no geometry emitted; its gl_GeometryIndexEXT slot doesn't exist
            }
            int vertSize = geom.verts.size();
            System.arraycopy(geom.verts.elements(), 0, positions, posOff, vertSize);
            int[] gi = geom.idx.elements();
            for (int i = 0, n = geom.idx.size(); i < n; i++) {
                indices[idxOff + i] = gi[i] + vertBase;
            }
            int uvSize = geom.cornerUv.size();
            System.arraycopy(geom.cornerUv.elements(), 0, uvs, uvOff, uvSize);
            int matSize = geom.prim.size();
            System.arraycopy(geom.prim.elements(), 0, material, matOff, matSize);
            for (int i = 0, n = geom.materialSprites.size(); i < n; i++) {
                materialSprites[spriteOff + i] = geom.materialSprites.get(i);
            }
            triBase[g] = triAcc;
            if (b == RtAccel.BUCKET_WATER) {
                waterGeom = g;
            }
            posOff += vertSize;
            idxOff += geom.idx.size();
            uvOff += uvSize;
            matOff += matSize;
            spriteOff += bucketTris[b];
            vertBase += vertSize / 3;
            triAcc += bucketTris[b];
            g++;
        }
        return new PackedSection(positions, indices, uvs, material, materialSprites, bucketTris, triBase, waterGeom);
    }

    private static SectionMesh tessellate(RenderSectionRegion region, BlockStateModelSet modelSet,
                                          ModelBlockRenderer renderer, QuadCapture capture,
                                          FluidRenderer fluidRenderer, FluidCapture fluidCapture,
                                          BlockPos.MutableBlockPos m, int scx, int scy, int scz) {
        int sox = scx << 4, soy = scy << 4, soz = scz << 4;
        SectionMesh mesh = new SectionMesh();
        capture.cur = mesh;
        capture.view = region;
        fluidCapture.cur = mesh;
        for (int lx = 0; lx < 16; lx++) {
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = sox + lx, wy = soy + ly, wz = soz + lz;
                    m.set(wx, wy, wz);
                    BlockState state = region.getBlockState(m);
                    if (state.isAir()) {
                        continue;
                    }
                    // Fluids (water/lava, incl. waterlogged blocks): separate mesher, INVISIBLE render
                    // shape, so handled independently of the block model below. FluidRenderer emits
                    // section-local coords + atlas sprite UVs straight into the capturing consumer.
                    // Lava's block light (15) rides the emission channel (water emits 0).
                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        fluidCapture.emission = state.getLightEmission() / 15f;
                        // Water is the dielectric fluid; lava stays an opaque emitter. Tagged per-prim
                        // so the path tracer can branch (see emitQuad).
                        fluidCapture.water = fluid.is(FluidTags.WATER);
                        try {
                            fluidRenderer.tesselate(region, m, fluidCapture, state, fluid);
                        } catch (Throwable t) {
                            warnMeshOnce("fluid", t); // skip a fluid whose meshing throws, don't fail the section
                        }
                    }
                    if (state.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }
                    BlockStateModel model = modelSet.get(state);
                    if (model == null) {
                        continue;
                    }
                    try {
                        capture.state = state;
                        capture.pos = m;
                        renderer.tesselateBlock(capture, lx, ly, lz, region, m, state, model, state.getSeed(m));
                        capture.flushBlock(); // resolve coplanar ties (grass overlay / cross faces), then emit
                    } catch (Throwable t) {
                        capture.discardBlock(); // drop any partially-buffered quads from the throw
                        warnMeshOnce("block model", t); // skip a block whose meshing throws, don't fail the section
                    }
                }
            }
        }
        return mesh;
    }

    /** Surface the first per-block/fluid meshing throw (swallowed above to keep one bad block from voiding
     *  the whole section), then stay quiet. May run on a worker thread; the flag is volatile + one-shot. */
    private static void warnMeshOnce(String what, Throwable t) {
        if (!loggedMeshFailure) {
            loggedMeshFailure = true;
            UpscalerMod.LOGGER.warn("RT terrain: {} meshing threw (skipped); first occurrence:", what, t);
        }
    }

    /**
     * Upload a tessellated {@link SectionMesh} to GPU buffers and prepare (not yet build) its BLAS.
     * <b>Render thread only</b> — creates Vulkan buffers and resolves LabPBR materials (which
     * create/upload GPU textures). The optional OMM input is precomputed by {@link #buildCpuSection}.
     * {@code mesh} must be non-empty.
     */
    private PreparedSection uploadSection(RtContext ctx, PackedSection packed, RtAccel.OpacityMicromapInput ommInput,
                                          long key, int sox, int soy, int soz) {
        int vertCount = packed.positions().length / 3;
        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        String label = "terrain section " + sox + "," + soy + "," + soz;
        RtBuffer positions = pool.acquire(ctx, (long) packed.positions().length * Float.BYTES, asInput, true,
                label + " positions");
        RtBuffer indices = pool.acquire(ctx, (long) packed.indices().length * Integer.BYTES, asInput | storage, true,
                label + " indices");
        RtBuffer uvs = pool.acquire(ctx, (long) packed.uvs().length * Float.BYTES, storage, true,
                label + " uvs");
        RtBuffer material = pool.acquire(ctx, (long) packed.material().length * Float.BYTES, storage, true,
                label + " material");

        resolveMaterials(packed.material(), packed.materialSprites());
        MemoryUtil.memFloatBuffer(positions.mapped, packed.positions().length).put(packed.positions());
        MemoryUtil.memIntBuffer(indices.mapped, packed.indices().length).put(packed.indices());
        MemoryUtil.memFloatBuffer(uvs.mapped, packed.uvs().length).put(packed.uvs());
        MemoryUtil.memFloatBuffer(material.mapped, packed.material().length).put(packed.material());

        // Split BLAS: geom for each non-empty bucket — solid (OPAQUE, any-hit skipped), cutout (alpha test),
        // water (shadow passthrough). Build is deferred — the caller batches all sections into one submission.
        RtAccel.PreparedBlas blas = RtAccel.prepareTerrainBlasPooled(ctx, pool, positions, vertCount, indices, packed.bucketTris(), ommInput,
                label + " BLAS");
        return new PreparedSection(key, positions, indices, uvs, material, blas, packed.triBase(), packed.waterGeom(), sox, soy, soz);
    }

    /** Patch packed prim records' hasS/hasN lanes via render-thread material ingestion. */
    private static void resolveMaterials(float[] material, TextureAtlasSprite[] materialSprites) {
        for (int t = 0; t < materialSprites.length; t++) {
            TextureAtlasSprite sprite = materialSprites[t];
            if (sprite == null) {
                continue;
            }
            int flags = RtBlockMaterials.INSTANCE.ensure(sprite);
            int off = t * 12;
            material[off + 10] = (flags & RtBlockMaterials.HAS_S) != 0 ? 1f : 0f;
            material[off + 11] = (flags & RtBlockMaterials.HAS_N) != 0 ? 1f : 0f;
        }
    }

    /**
     * Snapshot each missing section on the render thread and submit its tessellation to the worker
     * pool. The per-task meshing objects (renderer / captures / MutableBlockPos) are allocated inside the
     * job so nothing mutable is shared across threads; the captured {@code region}, model sets and block
     * colors are read-only. Capped at {@link #ASYNC_DISPATCH_PER_TICK} dispatches per tick.
     */
    private static DispatchContext dispatchContext(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        return new DispatchContext(level, new RenderRegionCache(),
                mc.getModelManager().getBlockStateModelSet(), mc.getModelManager().getFluidStateModelSet(),
                mc.getBlockColors());
    }

    private void dispatchTessellation(DispatchContext dispatch, ClientChunkCache chunkSource, int budget) {
        if (missing.isEmpty() || budget <= 0) {
            return;
        }
        int attempts = Math.min(missing.size(), Math.max(64, budget * 4));
        for (int i = 0; i < missing.size() && budget > 0 && attempts-- > 0; ) {
            long key = missing.getLong(i);
            if (!queuedMissing.contains(key)) {
                missing.removeLong(i);
                continue;
            }
            if (!desired.contains(key) || resident.containsKey(key) || empty.contains(key) || inFlight.containsKey(key)) {
                queuedMissing.remove(key);
                missing.removeLong(i);
                continue;
            }
            int sx = sectionX(key);
            int sz = sectionZ(key);
            if (!neighborChunksReady(chunkSource, sx, sz)) {
                i++;
                continue;
            }
            queuedMissing.remove(key);
            missing.removeLong(i);
            budget--;
            dispatchSection(dispatch, key, sx, sectionY(key), sz);
        }
    }

    /**
     * Re-extraction of edited (dirty) sections that are still resident: dispatch a fresh
     * tessellation while leaving the old geometry resident and traced, so it's swapped — never evicted
     * with a gap — when the new mesh is built (see {@link #startBuild} retiring the replaced geom). This
     * is what prevents the visible flicker on block updates that plain eviction would cause.
     */
    private int dispatchReextract(DispatchContext dispatch, ClientChunkCache chunkSource, int budget) {
        if (reextract.isEmpty()) {
            return 0;
        }
        int dispatched = 0;
        int attempts = Math.min(reextract.size(), Math.max(64, budget * 4));
        for (int i = 0; i < reextract.size() && budget > 0 && attempts-- > 0; ) {
            long key = reextract.getLong(i);
            if (!queuedReextract.contains(key)) {
                reextract.removeLong(i);
                continue;
            }
            // Skip ones the window pass freed this tick (out of view) — they're being retired, not rebuilt.
            SectionGeom g = resident.get(key);
            if (g == null || !desired.contains(key) || inFlight.containsKey(key)) {
                queuedReextract.remove(key);
                reextract.removeLong(i);
                continue;
            }
            int sx = g.sx >> 4;
            int sz = g.sz >> 4;
            if (!neighborChunksReady(chunkSource, sx, sz)) {
                i++;
                continue;
            }
            queuedReextract.remove(key);
            reextract.removeLong(i);
            dispatchSection(dispatch, key, g.sx >> 4, g.sy >> 4, g.sz >> 4);
            budget--;
            dispatched++;
        }
        return dispatched;
    }

    /** Snapshot one section on the render thread and submit its tessellation to the worker pool. */
    private void dispatchSection(DispatchContext dispatch, long key, int sx, int sy, int sz) {
        RenderSectionRegion region = dispatch.regionCache().createRegion(dispatch.level(), SectionPos.asLong(sx, sy, sz));
        long token = ++tessToken;
        TessJob job = new TessJob(key, token, sx << 4, sy << 4, sz << 4);
        Future<?> future = RtWorkerPool.INSTANCE.submit(() -> {
            try {
                ModelBlockRenderer renderer = new ModelBlockRenderer(false, true, dispatch.blockColors());
                QuadCapture capture = new QuadCapture();
                capture.blockColors = dispatch.blockColors();
                FluidRenderer fluidRenderer = new FluidRenderer(dispatch.fluidModelSet());
                FluidCapture fluidCapture = new FluidCapture();
                BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                CpuSection cpu = buildCpuSection(region, dispatch.modelSet(), renderer, capture, fluidRenderer, fluidCapture, m, sx, sy, sz);
                completedJobs.add(new TessResult(job, cpu, null));
            } catch (Throwable t) {
                completedJobs.add(new TessResult(job, null, t));
                throw t;
            }
            return null;
        });
        job.future = future;
        inFlight.put(key, token);
        jobs.add(job);
    }

    /**
     * Upload finished worker meshes (up to {@link #SECTION_RESULTS_PER_TICK} per tick) on the
     * render thread. A job whose token no longer matches {@link #inFlight} is stale — its section was
     * re-dirtied / unloaded / left the window since dispatch — and is dropped without uploading.
     */
    private void drainTessellation(RtContext ctx, List<PreparedSection> prepared, List<SectionGeom> removed, int resultCap) {
        int budget = resultCap;
        int attempts = resultCap * 4;
        while (budget > 0 && attempts-- > 0) {
            TessResult result = completedJobs.poll();
            if (result == null) {
                break;
            }
            TessJob job = result.job();
            jobs.remove(job);
            if (result.failure() != null) {
                inFlight.remove(job.key);
                if (!loggedTessFailure) {
                    loggedTessFailure = true;
                    UpscalerMod.LOGGER.warn("async terrain: tessellation task failed for section {},{},{}",
                            job.sox >> 4, job.soy >> 4, job.soz >> 4, result.failure());
                }
                continue;
            }
            long expected = inFlight.get(job.key);
            boolean valid = expected == job.token;
            if (!valid) {
                continue; // stale result; a newer dispatch (or none) supersedes it
            }
            inFlight.remove(job.key);
            PackedSection packed = result.cpu().packed();
            if (packed == null) {
                // Legitimately empty (air or fully-enclosed). If this was an in-place re-extract whose new
                // state is empty, evict the old geom and retire it via the build swap (a startBuild runs
                // because `removed` is now non-empty).
                SectionGeom prev = resident.remove(job.key);
                if (prev != null) {
                    removed.add(prev);
                }
                empty.add(job.key);
                budget--;
            } else {
                prepared.add(uploadSection(ctx, packed, result.cpu().opacityMicromap(), job.key, job.sox, job.soy, job.soz));
                budget--;
            }
        }
    }

    /** Pure-CPU worker result: tessellated mesh plus optional opacity micromap input for its cutout bucket. */
    private record CpuSection(PackedSection packed, RtAccel.OpacityMicromapInput opacityMicromap) {
    }

    /** Per-tick render-thread snapshot dependencies shared by reextract + missing dispatch. */
    private record DispatchContext(ClientLevel level, RenderRegionCache regionCache, BlockStateModelSet modelSet,
                                   FluidStateModelSet fluidModelSet, BlockColors blockColors) {
    }

    /** Worker-packed terrain buffer payload; upload only allocates buffers and bulk-copies these arrays. */
    private record PackedSection(float[] positions, int[] indices, float[] uvs, float[] material,
                                 TextureAtlasSprite[] materialSprites,
                                 int[] bucketTris, int[] triBase, int waterGeom) {
    }

    /** A section tessellated + uploaded with a prepared (not-yet-built) BLAS, pending the batch build. */
    private record PreparedSection(long key, RtBuffer positions, RtBuffer indices, RtBuffer uvs, RtBuffer material,
                                   RtAccel.PreparedBlas blas, int[] triBase, int waterGeom, int sx, int sy, int sz) {
    }

    /** A deferred free: run {@code free} once the frame counter reaches {@code freeFrame}. */
    private record Deferred(long freeFrame, Runnable free) {
    }

    /** An outstanding async tessellation; completed results are delivered through {@link #completedJobs}. */
    private static final class TessJob {
        final long key;
        final long token;
        final int sox;
        final int soy;
        final int soz;
        Future<?> future;

        TessJob(long key, long token, int sox, int soy, int soz) {
            this.key = key;
            this.token = token;
            this.sox = sox;
            this.soy = soy;
            this.soz = soz;
        }
    }

    private record TessResult(TessJob job, CpuSection cpu, Throwable failure) {
    }

    /** An in-flight async BLAS build: the new section geometry/instances land when {@code op} completes. */
    private record Pending(RtContext.AsyncSubmit op, List<RtAccel.PreparedBlas> blas,
                           List<PreparedSection> prepared, List<SectionGeom> removed,
                           boolean rebase, int rbx, int rby, int rbz) {
    }

    /**
     * Start an async geometry build. Prepared sections are published only after their BLAS build completes;
     * the previous section table stays immutable for in-flight frames, and the next publish copies it and
     * patches only changed slots. The TLAS is rebuilt per frame by {@link RtComposite}.
     */
    private void startBuild(RtContext ctx, List<PreparedSection> prepared, List<SectionGeom> removed, int rbx, int rby, int rbz) {
        boolean rebase = shouldRebase(rbx, rby, rbz);
        if (prepared.isEmpty()) {
            applyBuildChanges(ctx, List.of(), removed, rebase, rbx, rby, rbz);
            return;
        }
        List<RtAccel.PreparedBlas> blasBuilds = new ArrayList<>(prepared.size());
        for (PreparedSection ps : prepared) {
            blasBuilds.add(ps.blas());
        }
        RtContext.AsyncSubmit op = ctx.submitAsync(cmd -> RtAccel.recordBlasBuilds(ctx, cmd, blasBuilds));
        pending = new Pending(op, blasBuilds, new ArrayList<>(prepared), new ArrayList<>(removed), rebase, rbx, rby, rbz);
    }

    /** Swap a completed async build in: retire old table + removed sections, publish the new instances/table. */
    private void finalizePending(RtContext ctx) {
        Pending p = pending;
        pending = null;
        ctx.freeAsync(p.op());
        RtAccel.freeBlasScratch(pool, p.blas()); // build done -> BLAS scratch safe to reuse
        applyBuildChanges(ctx, p.prepared(), p.removed(), p.rebase(), p.rbx(), p.rby(), p.rbz());
    }

    private boolean shouldRebase(int rbx, int rby, int rbz) {
        return !ready || sectionTable == null || staticInstances == null
                || Math.abs(rbx - blockX) > REBASE_DISTANCE_BLOCKS
                || Math.abs(rby - blockY) > REBASE_DISTANCE_BLOCKS
                || Math.abs(rbz - blockZ) > REBASE_DISTANCE_BLOCKS;
    }

    private void applyBuildChanges(RtContext ctx, List<PreparedSection> prepared, List<SectionGeom> removed,
                                   boolean rebase, int rbx, int rby, int rbz) {
        long freeAt = RtComposite.frameCounter() + KEEP_FRAMES;
        int baseX = rebase ? rbx : blockX;
        int baseY = rebase ? rby : blockY;
        int baseZ = rebase ? rbz : blockZ;

        for (SectionGeom g : removed) {
            removePublishedSection(g);
        }
        retire(freeAt, null, removed);

        if (!prepared.isEmpty()) {
            ensureSectionTableCapacity(ctx, liveSlotCapacity(prepared), freeAt);
        }

        for (PreparedSection ps : prepared) {
            SectionGeom prev = resident.get(ps.key());
            SectionGeom g = new SectionGeom(ps.key(), ps.positions(), ps.indices(), ps.uvs(), ps.material(),
                    ps.blas().accel, ps.blas().pooledBacking(), ps.triBase(), ps.waterGeom(), ps.sx(), ps.sy(), ps.sz());
            if (prev != null && prev.slot >= 0) {
                g.slot = prev.slot;
                g.instanceIndex = prev.instanceIndex;
                sectionSlots.set(g.slot, g);
                resident.put(ps.key(), g);
                writeSectionEntry(g);
                staticInstanceList.set(g.instanceIndex, instanceFor(g, baseX, baseY, baseZ));
                retire(freeAt, null, List.of(prev));
            } else {
                g.slot = allocateSectionSlot();
                g.instanceIndex = staticInstanceList.size();
                sectionSlots.set(g.slot, g);
                resident.put(ps.key(), g);
                writeSectionEntry(g);
                staticInstanceList.add(instanceFor(g, baseX, baseY, baseZ));
            }
            published.add(ps.key());
        }

        if (resident.isEmpty()) {
            retire(freeAt, sectionTable, List.of());
            sectionTable = null;
            sectionTableCapacity = 0;
            nextSectionSlot = 0;
            freeSectionSlots.clear();
            sectionSlots.clear();
            staticInstanceList.clear();
            staticInstances = null;
            published.clear();
            ready = false;
            return;
        }

        if (rebase) {
            for (int i = 0, n = staticInstanceList.size(); i < n; i++) {
                RtAccel.Instance inst = staticInstanceList.get(i);
                SectionGeom g = sectionSlots.get(inst.customIndex());
                staticInstanceList.set(i, instanceFor(g, baseX, baseY, baseZ));
            }
            blockX = rbx;
            blockY = rby;
            blockZ = rbz;
        }
        staticInstances = staticInstanceList;
        ready = true;
    }

    private int liveSlotCapacity(List<PreparedSection> prepared) {
        int needed = nextSectionSlot;
        int free = freeSectionSlots.size();
        for (PreparedSection ps : prepared) {
            SectionGeom prev = resident.get(ps.key());
            if (prev != null && prev.slot >= 0) {
                needed = Math.max(needed, prev.slot + 1);
            } else if (free > 0) {
                free--;
            } else {
                needed++;
            }
        }
        return needed;
    }

    private void ensureSectionTableCapacity(RtContext ctx, int minCapacity, long freeAt) {
        int capacity = Math.max(SECTION_TABLE_INITIAL_CAPACITY, 1);
        capacity = Math.max(capacity, sectionTableCapacity);
        while (capacity < minCapacity) {
            capacity <<= 1;
        }
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        RtBuffer newTable = pool.acquire(ctx, (long) capacity * SECTION_ENTRY_BYTES, storage, true,
                "terrain section table " + capacity + " slots");
        RtBuffer oldTable = sectionTable;
        int oldCapacity = sectionTableCapacity;
        sectionTable = newTable;
        sectionTableCapacity = capacity;
        if (oldTable != null && oldCapacity > 0) {
            MemoryUtil.memCopy(oldTable.mapped, newTable.mapped, (long) oldCapacity * SECTION_ENTRY_BYTES);
        }
        if (oldTable != null) {
            retire(freeAt, oldTable, List.of());
        }
    }

    private int allocateSectionSlot() {
        int slot;
        if (!freeSectionSlots.isEmpty()) {
            slot = (int) freeSectionSlots.removeLong(freeSectionSlots.size() - 1);
        } else {
            slot = nextSectionSlot++;
        }
        while (sectionSlots.size() <= slot) {
            sectionSlots.add(null);
        }
        return slot;
    }

    private void removePublishedSection(SectionGeom g) {
        SectionGeom current = resident.get(g.key);
        if (current == g) {
            resident.remove(g.key);
        }
        published.remove(g.key);
        if (g.instanceIndex >= 0) {
            int removeIndex = g.instanceIndex;
            int lastIndex = staticInstanceList.size() - 1;
            if (removeIndex != lastIndex) {
                RtAccel.Instance moved = staticInstanceList.get(lastIndex);
                staticInstanceList.set(removeIndex, moved);
                SectionGeom movedGeom = sectionSlots.get(moved.customIndex());
                movedGeom.instanceIndex = removeIndex;
            }
            staticInstanceList.remove(lastIndex);
            g.instanceIndex = -1;
        }
        if (g.slot >= 0) {
            sectionSlots.set(g.slot, null);
            freeSectionSlots.add(g.slot);
            g.slot = -1;
        }
    }

    private void writeSectionEntry(SectionGeom g) {
        long base = sectionTable.mapped + (long) g.slot * SECTION_ENTRY_BYTES;
        MemoryUtil.memPutLong(base, g.material.deviceAddress);
        MemoryUtil.memPutLong(base + 8, g.indices.deviceAddress);
        MemoryUtil.memPutLong(base + 16, g.uvs.deviceAddress);
        MemoryUtil.memPutInt(base + 24, g.triBase[0]);
        MemoryUtil.memPutInt(base + 28, g.triBase[1]);
        MemoryUtil.memPutInt(base + 32, g.triBase[2]);
        MemoryUtil.memPutInt(base + 36, g.waterGeom);
    }

    private static RtAccel.Instance instanceFor(SectionGeom g, int rbx, int rby, int rbz) {
        float[] xform = {1, 0, 0, g.sx - rbx, 0, 1, 0, g.sy - rby, 0, 0, 1, g.sz - rbz};
        return new RtAccel.Instance(xform, g.blas.deviceAddress, g.slot);
    }

    /** Queue old GPU resources for a frames-in-flight-safe free at {@code freeFrame}. */
    private void retire(long freeFrame, RtBuffer oldTable, List<SectionGeom> removed) {
        if (oldTable != null) {
            deferred.add(new Deferred(freeFrame, () -> pool.release(oldTable)));
        }
        for (SectionGeom g : removed) {
            deferred.add(new Deferred(freeFrame, () -> g.destroy(pool)));
        }
    }

    private void processDeferredFrees() {
        if (deferred.isEmpty()) {
            return;
        }
        long now = RtComposite.frameCounter();
        Iterator<Deferred> it = deferred.iterator();
        while (it.hasNext()) {
            Deferred d = it.next();
            if (d.freeFrame() <= now) {
                d.free().run();
                it.remove();
            }
        }
    }

    /** Cancel outstanding async tessellations and drop their bookkeeping (CPU-only — nothing to free). */
    private void cancelJobs() {
        if (jobs.isEmpty() && inFlight.isEmpty()) {
            return;
        }
        for (TessJob job : jobs) {
            if (job.future != null) {
                job.future.cancel(true);
            }
        }
        jobs.clear();
        completedJobs.clear();
        inFlight.clear();
    }

    /** Full teardown (world exit / shutdown): drain the GPU, then free everything incl. an in-flight build. */
    private void clear(RtContext ctx, boolean destroyPool) {
        cancelJobs();
        synchronized (dirtyLock) {
            dirty.clear(); // any pending re-extract keys refer to the old world/coords — drop them
            dirtyPending = false;
        }
        dirtyDrain.clear();
        desired.clear();
        desiredColumns.clear();
        loadedColumns.clear();
        missing.clear();
        queuedMissing.clear();
        reextract.clear();
        queuedReextract.clear();
        windowValid = false;
        if (pending == null && resident.isEmpty() && sectionTable == null && deferred.isEmpty()) {
            empty.clear();
            staticInstances = null;
            published.clear();
            sectionTableCapacity = 0;
            nextSectionSlot = 0;
            freeSectionSlots.clear();
            sectionSlots.clear();
            staticInstanceList.clear();
            removed.clear();
            prepared.clear();
            if (destroyPool) {
                pool.destroyAll();
            }
            return;
        }
        ctx.waitIdle();
        if (pending != null) {
            ctx.freeAsync(pending.op());
            RtAccel.freeBlasScratch(pool, pending.blas());
            for (PreparedSection ps : pending.prepared()) {
                SectionGeom g = new SectionGeom(ps.key(), ps.positions(), ps.indices(), ps.uvs(), ps.material(),
                        ps.blas().accel, ps.blas().pooledBacking(), ps.triBase(), ps.waterGeom(), ps.sx(), ps.sy(), ps.sz());
                g.destroy(pool);
            }
            for (SectionGeom g : pending.removed()) {
                g.destroy(pool);
            }
            pending = null;
        }
        for (Deferred d : deferred) {
            d.free().run();
        }
        deferred.clear();
        if (sectionTable != null) {
            pool.release(sectionTable);
            sectionTable = null;
        }
        sectionTableCapacity = 0;
        nextSectionSlot = 0;
        freeSectionSlots.clear();
        sectionSlots.clear();
        staticInstanceList.clear();
        for (SectionGeom g : resident.values()) {
            g.destroy(pool);
        }
        resident.clear();
        empty.clear();
        staticInstances = null;
        published.clear();
        removed.clear();
        prepared.clear();
        if (destroyPool) {
            pool.destroyAll();
        }
        ready = false;
    }

    private static long columnKey(int scx, int scz) {
        return ((long) scx << 32) ^ (scz & 0xFFFFFFFFL);
    }

    private static int columnX(long key) {
        return (int) (key >> 32);
    }

    private static int columnZ(long key) {
        return (int) key;
    }

    /** Pack section coords into a stable map key; ranges fit comfortably in the masks. */
    private static long sectionKey(int scx, int scy, int scz) {
        return (scx & 0x3FFFFFFL) | ((scz & 0x3FFFFFFL) << 26) | ((scy & 0xFFFL) << 52);
    }

    private static int sectionX(long key) {
        return (int) (key << 38 >> 38);
    }

    private static int sectionZ(long key) {
        return (int) (key << 12 >> 38);
    }

    private static int sectionY(long key) {
        return (int) (key >> 52);
    }

    /** GPU residency for one section: geometry buffers + BLAS + world section origin. */
    private static final class SectionGeom {
        final long key;
        final RtBuffer positions;
        final RtBuffer indices;
        final RtBuffer uvs;
        final RtBuffer material;
        final RtAccel blas;
        final RtBuffer blasBacking;
        final int[] triBase;  // per-emitted-geometry triangle offset; hit shaders add triBase[gl_GeometryIndexEXT] to pid
        final int waterGeom;  // gl_GeometryIndexEXT of the water geometry (NO_WATER_GEOM if none)
        final int sx;
        final int sy;
        final int sz;
        int slot = -1;
        int instanceIndex = -1;

        SectionGeom(long key, RtBuffer positions, RtBuffer indices, RtBuffer uvs, RtBuffer material,
                    RtAccel blas, RtBuffer blasBacking, int[] triBase, int waterGeom, int sx, int sy, int sz) {
            this.key = key;
            this.positions = positions;
            this.indices = indices;
            this.uvs = uvs;
            this.material = material;
            this.blas = blas;
            this.blasBacking = blasBacking;
            this.triBase = triBase;
            this.waterGeom = waterGeom;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
        }

        void destroy(RtBufferPool pool) {
            if (blasBacking != null) {
                RtAccel.destroyPooledAccel(pool, blas, blasBacking);
            } else {
                blas.destroy();
            }
            pool.release(material);
            pool.release(uvs);
            pool.release(indices);
            pool.release(positions);
        }
    }

    /**
     * Transient CPU accumulator for one section's quads while tessellating. Split into per-material geometry
     * buckets so the BLAS can flag the {@link #opaque} bucket (solid blocks) {@code VK_GEOMETRY_OPAQUE_BIT}
     * — the driver then never invokes {@code world.rahit} for it, the bulk of every scene — while the
     * {@link #cutout} bucket (alpha-tested foliage/glass) keeps the alpha-test any-hit and the {@link #water}
     * bucket keeps a memory-free any-hit (shadow passthrough only, classified by geometry index). The buckets
     * are concatenated in {@code BUCKET_*} order into the packed section buffers at upload, so each geometry's
     * triangles occupy a contiguous range (see {@link RtAccel#prepareTerrainBlas} + the section table).
     */
    private static final class SectionMesh {
        // One bucket per RtAccel terrain geometry, in BUCKET_SOLID / BUCKET_CUTOUT / BUCKET_WATER order.
        // solid = opaque (any-hit skipped); cutout = alpha-tested foliage/glass; water = shadow passthrough
        // only (no alpha test — the any-hit classifies it by geometry index, no memory load).
        final Geom opaque = new Geom();
        final Geom cutout = new Geom();
        final Geom water = new Geom();

        Geom[] buckets() {
            return new Geom[]{opaque, cutout, water}; // index == RtAccel.BUCKET_*
        }

        boolean isEmpty() {
            return opaque.idx.isEmpty() && cutout.idx.isEmpty() && water.idx.isEmpty();
        }
    }

    /** One geometry bucket's packed, section-local mesh data. */
    private static final class Geom {
        final FloatArrayList verts = new FloatArrayList();
        final IntArrayList idx = new IntArrayList();
        // Lever B: per-triangle corner UVs in primitive order — 6 floats/triangle (3 corners x u,v),
        // aligned with `idx`'s triangle order so the hit shader reads cornerUv[3*pid + k] directly with no
        // index->vertex-UV gather. The index buffer is still emitted (above) for the BLAS build.
        final FloatArrayList cornerUv = new FloatArrayList();
        final FloatArrayList prim = new FloatArrayList();   // 12 floats/triangle: normal.xyz+emission, tint.rgb+material, mat.{rough,metal,hasS,hasN}
        // One sprite per prim record (per triangle), aligned with `prim`. Resolved on the render thread
        // because RtBlockMaterials.ensure can lazy-load material maps.
        final List<TextureAtlasSprite> materialSprites = new ArrayList<>();
        // One sprite per triangle for opacity micromap classification.
        final List<TextureAtlasSprite> ommSprites = new ArrayList<>();

        int triCount() {
            return idx.size() / 3;
        }
    }

    /** Captures the quads vanilla's model renderer emits into the current section's mesh. */
    private static final class QuadCapture implements BlockQuadOutput {
        SectionMesh cur; // set before each tesselateBlock call

        // Per-block context for biome tint, set before each tesselateBlock call. We resolve the tint
        // straight from BlockColors (pure biome color) rather than QuadInstance.getColor, which bakes
        // in vanilla AO + directional shading we don't want — our tint must be unlit albedo.
        BlockColors blockColors;
        BlockAndTintGetter view;
        BlockState state;
        BlockPos pos;

        // Coplanar-resolution: vanilla emits coincident quads that tie on depth in the BVH and flicker —
        // a block face's opaque base + its tinted cutout overlay (grass/snowy sides), and a cross model's
        // two-sided faces. put() buffers a block's quads here; flushBlock() (called per block) nudges all
        // but the first member of each coincident group outward along its own normal so each lands on its
        // own plane (base stays, overlay moves in front so its cutout reveals the base; cross back-face
        // separates from the front). Pooled — reset each block, never reallocated steady-state.
        private static final float OFFSET = 2.0e-4f;         // outward nudge (blocks) to break coplanar depth ties
        private static final float COINCIDENT_EPS = 1.0e-4f; // verts this close are "the same" point
        private static final int RESOLVE_CAP = 128;          // skip the O(n^2) resolve for pathological blocks
        private final List<PendingQuad> pending = new ArrayList<>();
        private int pendingCount;
        private int[] gidScratch = new int[0];

        @Override
        public void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
            PendingQuad q = acquire();
            Vector3fc p0 = quad.position(0), p1 = quad.position(1), p2 = quad.position(2), p3 = quad.position(3);
            q.x[0] = p0.x() + x; q.y[0] = p0.y() + y; q.z[0] = p0.z() + z;
            q.x[1] = p1.x() + x; q.y[1] = p1.y() + y; q.z[1] = p1.z() + z;
            q.x[2] = p2.x() + x; q.y[2] = p2.y() + y; q.z[2] = p2.z() + z;
            q.x[3] = p3.x() + x; q.y[3] = p3.y() + y; q.z[3] = p3.z() + z;
            q.uv[0] = quad.packedUV(0); q.uv[1] = quad.packedUV(1);
            q.uv[2] = quad.packedUV(2); q.uv[3] = quad.packedUV(3);

            float ex1 = q.x[1] - q.x[0], ey1 = q.y[1] - q.y[0], ez1 = q.z[1] - q.z[0];
            float ex2 = q.x[2] - q.x[0], ey2 = q.y[2] - q.y[0], ez2 = q.z[2] - q.z[0];
            float nx = ey1 * ez2 - ez1 * ey2, ny = ez1 * ex2 - ex1 * ez2, nz = ex1 * ey2 - ey1 * ex2;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1.0e-6f) { nx /= len; ny /= len; nz /= len; }
            q.nx = nx; q.ny = ny; q.nz = nz;

            // Route by chunk render layer: only SOLID is fully opaque (no alpha test) → OPAQUE-flagged
            // geometry whose any-hit the driver skips. CUTOUT/TRANSLUCENT keep the alpha-test any-hit. Blocks
            // are never the water bucket (fluids only). The non-SOLID flag also marks overlay candidates.
            q.cutout = quad.materialInfo().layer() != ChunkSectionLayer.SOLID;

            // Biome tint: tintIndex >= 0 means biome-colored (grass/foliage). In 26.2 the color comes from a
            // BlockTintSource; colorInWorld blends the biome color at this pos. Untinted quads stay white.
            // tintIndex >= 0 also marks the overlay member of a base+overlay pair (the tinted one is on top).
            int tintIndex = quad.materialInfo().tintIndex();
            q.tinted = tintIndex >= 0;
            float tr = 1f, tg = 1f, tb = 1f;
            if (tintIndex >= 0 && blockColors != null && state != null) {
                BlockTintSource src = blockColors.getTintSource(state, tintIndex);
                if (src != null) {
                    int rgb = src.colorInWorld(state, view, pos);
                    tr = ((rgb >> 16) & 0xFF) * (1f / 255f);
                    tg = ((rgb >> 8) & 0xFF) * (1f / 255f);
                    tb = (rgb & 0xFF) * (1f / 255f);
                }
            }
            q.tr = tr; q.tg = tg; q.tb = tb;

            // Emissive: vanilla block light level (0..15) -> 0..1, stashed in the free normal.w slot.
            q.emission = state != null ? state.getLightEmission() / 15f : 0f;
            // Heuristic PBR material (roughness, metalness) for the GGX BRDF / DLSS-RR guides.
            q.rough = RtMaterials.roughness(state);
            q.metal = RtMaterials.metalness(state);
            TextureAtlasSprite sprite = quad.materialInfo().sprite();
            q.sprite = sprite;
            q.materialSprite = RtMaterials.ENABLED ? sprite : null;
        }

        /** Acquire a pooled PendingQuad for the current block (grown on demand, count reset by flushBlock). */
        private PendingQuad acquire() {
            if (pendingCount == pending.size()) {
                pending.add(new PendingQuad());
            }
            return pending.get(pendingCount++);
        }

        /** Drop the current block's buffered quads without emitting (a meshing throw left them partial). */
        void discardBlock() {
            pendingCount = 0;
        }

        /** Resolve coplanar ties among the current block's quads, then emit them into the section buckets. */
        void flushBlock() {
            int n = pendingCount;
            if (n == 0) {
                return;
            }
            if (n >= 2 && n <= RESOLVE_CAP) {
                resolveCoplanar(n);
            }
            for (int i = 0; i < n; i++) {
                emit(pending.get(i));
            }
            pendingCount = 0;
        }

        /**
         * Union coincident quads (same 4 corners, any winding) into groups, then within each group keep the
         * first member (the opaque/untinted base if present) in place and push the rest outward along their
         * own normals by {@link #OFFSET} × rank. Same-normal layers (grass base/overlay) fan out along one
         * direction; opposite-normal pairs (cross faces) separate because each moves along its own normal.
         */
        private void resolveCoplanar(int n) {
            int[] gid = gidScratch.length >= n ? gidScratch : (gidScratch = new int[n]);
            for (int i = 0; i < n; i++) {
                gid[i] = -1;
            }
            for (int i = 0; i < n; i++) {
                if (gid[i] != -1) {
                    continue;
                }
                gid[i] = i;
                for (int j = i + 1; j < n; j++) {
                    if (gid[j] == -1 && coincident(pending.get(i), pending.get(j))) {
                        gid[j] = i;
                    }
                }
            }
            for (int r = 0; r < n; r++) {
                if (gid[r] != r) {
                    continue; // not a group representative
                }
                int rank = 0;
                // Pass 1: bases (opaque + untinted) — the first stays put (rank 0), so the overlay lands in
                // front of it. Pass 2: overlays (cutout or tinted) — always pushed outward.
                for (int k = 0; k < n; k++) {
                    PendingQuad q = pending.get(k);
                    if (gid[k] == r && !(q.cutout || q.tinted)) {
                        if (rank > 0) {
                            offset(q, OFFSET * rank);
                        }
                        rank++;
                    }
                }
                for (int k = 0; k < n; k++) {
                    PendingQuad q = pending.get(k);
                    if (gid[k] == r && (q.cutout || q.tinted)) {
                        if (rank > 0) {
                            offset(q, OFFSET * rank);
                        }
                        rank++;
                    }
                }
            }
        }

        /** True if every corner of {@code a} coincides with a corner of {@code b} (same quad, any winding). */
        private static boolean coincident(PendingQuad a, PendingQuad b) {
            for (int k = 0; k < 4; k++) {
                boolean found = false;
                for (int m = 0; m < 4; m++) {
                    if (Math.abs(a.x[k] - b.x[m]) < COINCIDENT_EPS
                            && Math.abs(a.y[k] - b.y[m]) < COINCIDENT_EPS
                            && Math.abs(a.z[k] - b.z[m]) < COINCIDENT_EPS) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
            return true;
        }

        /** Shift all four of a quad's corners by {@code d} along its (outward) normal. */
        private static void offset(PendingQuad q, float d) {
            for (int v = 0; v < 4; v++) {
                q.x[v] += q.nx * d;
                q.y[v] += q.ny * d;
                q.z[v] += q.nz * d;
            }
        }

        /** Emit one resolved quad into its section bucket (2 triangles, corner UVs, per-prim records). */
        private void emit(PendingQuad q) {
            Geom g = q.cutout ? cur.cutout : cur.opaque;
            int base = g.verts.size() / 3;
            for (int k = 0; k < 4; k++) {
                g.verts.add(q.x[k]);
                g.verts.add(q.y[k]);
                g.verts.add(q.z[k]);
            }
            IntArrayList idx = g.idx;
            idx.add(base);
            idx.add(base + 1);
            idx.add(base + 2);
            idx.add(base);
            idx.add(base + 2);
            idx.add(base + 3);
            // Per-triangle corner UVs (primitive order matching the two triangles: 0,1,2 then 0,2,3).
            addTriUv(g, q.uv[0], q.uv[1], q.uv[2]);
            addTriUv(g, q.uv[0], q.uv[2], q.uv[3]);
            FloatArrayList prim = g.prim;
            for (int t = 0; t < 2; t++) { // one {normal+emission, tint, mat} record per triangle
                prim.add(q.nx);
                prim.add(q.ny);
                prim.add(q.nz);
                prim.add(q.emission);
                prim.add(q.tr);
                prim.add(q.tg);
                prim.add(q.tb);
                prim.add(0f);
                prim.add(q.rough);
                prim.add(q.metal);
                prim.add(0f); // hasS placeholder; patched by resolveMaterials()
                prim.add(0f); // hasN placeholder
                g.materialSprites.add(q.materialSprite);
                g.ommSprites.add(q.sprite);
            }
        }
    }

    /** One block's buffered quad, awaiting coplanar resolution before it is emitted into a section bucket. */
    private static final class PendingQuad {
        final float[] x = new float[4], y = new float[4], z = new float[4];
        final long[] uv = new long[4];
        float nx, ny, nz;
        boolean cutout; // non-SOLID render layer (alpha-tested) — also an overlay candidate
        boolean tinted; // tintIndex >= 0 — the tinted member of a base+overlay pair
        float tr, tg, tb, emission, rough, metal;
        TextureAtlasSprite sprite, materialSprite;
    }

    /** Append one triangle's 3 corner UVs (6 floats) from packed UVPairs. UVPair packs u in the high 32
     *  bits, v in the low 32 (atlas-space, no sprite remap needed). */
    private static void addTriUv(Geom g, long pa, long pb, long pc) {
        FloatArrayList c = g.cornerUv;
        c.add(Float.intBitsToFloat((int) (pa >>> 32)));
        c.add(Float.intBitsToFloat((int) pa));
        c.add(Float.intBitsToFloat((int) (pb >>> 32)));
        c.add(Float.intBitsToFloat((int) pb));
        c.add(Float.intBitsToFloat((int) (pc >>> 32)));
        c.add(Float.intBitsToFloat((int) pc));
    }

    /** Append one triangle's 3 corner UVs (6 floats) from float u,v pairs (fluid path). */
    private static void addTriUv(Geom g, float ua, float va, float ub, float vb, float uc, float vc) {
        FloatArrayList c = g.cornerUv;
        c.add(ua);
        c.add(va);
        c.add(ub);
        c.add(vb);
        c.add(uc);
        c.add(vc);
    }

    /**
     * Captures the quads {@link FluidRenderer} emits (water/lava) into the current section's mesh. It
     * is both the {@link FluidRenderer.Output} and the {@link VertexConsumer} it hands back. Vertices
     * arrive in groups of 4 (one quad) via the bulk {@code addVertex}; we keep position + atlas UV,
     * compute a geometric normal (sign is irrelevant — the closest-hit flips it toward the viewer), and
     * emit two triangles like {@link QuadCapture}. Coords are already section-local (FluidRenderer uses
     * {@code pos & 15}). The cardinal-lit vertex colour is dropped — albedo comes from the atlas in the
     * hit shader, same as blocks. Tint is left white (biome water tint is a deferred item).
     *
     * <p>Water faces are tagged in the per-prim {@code tint.w} slot ({@code 1.0} = water) so the path
     * tracer treats them as a smooth dielectric (Fresnel reflection + refraction + Beer–Lambert
     * absorption). Lava keeps {@code tint.w == 0.0} and stays an opaque emitter.
     */
    private static final class FluidCapture implements VertexConsumer, FluidRenderer.Output {
        SectionMesh cur;     // set before each section
        float emission;      // set per fluid block (lava = 1, water = 0)
        boolean water;       // set per fluid block: true for water (dielectric), false for lava
        private int n;
        private final float[] qx = new float[4], qy = new float[4], qz = new float[4], qu = new float[4], qv = new float[4];
        private final int[] qc = new int[4]; // per-vertex packed ARGB (vanilla bakes the biome water tint here)

        @Override
        public VertexConsumer getBuilder(ChunkSectionLayer layer) {
            return this; // one capturing builder regardless of the fluid's render layer
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v,
                              int overlay, int light, float nx, float ny, float nz) {
            qx[n] = x; qy[n] = y; qz[n] = z; qu[n] = u; qv[n] = v; qc[n] = color;
            if (++n == 4) {
                emitQuad();
                n = 0;
            }
        }

        private void emitQuad() {
            // Water gets its own geometry → water bucket: its any-hit only passes shadow rays through (the
            // closest-hit does the dielectric), classified by geometry index with no memory load. Lava is an
            // opaque emitter → opaque bucket (no any-hit at all).
            Geom g = water ? cur.water : cur.opaque;
            FloatArrayList verts = g.verts;
            IntArrayList idx = g.idx;
            int base = verts.size() / 3;
            for (int i = 0; i < 4; i++) {
                verts.add(qx[i]);
                verts.add(qy[i]);
                verts.add(qz[i]);
            }
            idx.add(base);
            idx.add(base + 1);
            idx.add(base + 2);
            idx.add(base);
            idx.add(base + 2);
            idx.add(base + 3);
            // Per-triangle corner UVs (primitive order: 0,1,2 then 0,2,3), matching the two triangles above.
            addTriUv(g, qu[0], qv[0], qu[1], qv[1], qu[2], qv[2]);
            addTriUv(g, qu[0], qv[0], qu[2], qv[2], qu[3], qv[3]);

            float ex1 = qx[1] - qx[0], ey1 = qy[1] - qy[0], ez1 = qz[1] - qz[0];
            float ex2 = qx[2] - qx[0], ey2 = qy[2] - qy[0], ez2 = qz[2] - qz[0];
            float nx = ey1 * ez2 - ez1 * ey2;
            float ny = ez1 * ex2 - ex1 * ez2;
            float nz = ex1 * ey2 - ey1 * ex2;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1.0e-6f) {
                nx /= len;
                ny /= len;
                nz /= len;
            }
            float material = water ? 1f : 0f; // tint.w: 1 = water dielectric, 0 = opaque (lava)
            // Water is a near-smooth dielectric; lava is a moderately rough opaque emitter.
            float rough = water ? RtMaterials.WATER_ROUGH : RtMaterials.LAVA_ROUGH;
            // Biome water tint: vanilla's FluidRenderer bakes BiomeColors.getAverageWaterColor into the
            // per-vertex colour, so the average of the quad's four colours is this water body's tint. The
            // path tracer turns it into a per-channel Beer–Lambert extinction (ocean blue vs swamp green).
            // Lava keeps a white tint (its colour rides the emission channel, not absorption).
            float tr = 1f, tg = 1f, tb = 1f;
            if (water) {
                int sr = 0, sg = 0, sb = 0;
                for (int i = 0; i < 4; i++) {
                    sr += (qc[i] >> 16) & 0xFF;
                    sg += (qc[i] >> 8) & 0xFF;
                    sb += qc[i] & 0xFF;
                }
                tr = sr / 1020f; // 4 vertices * 255
                tg = sg / 1020f;
                tb = sb / 1020f;
            }
            FloatArrayList prim = g.prim;
            for (int t = 0; t < 2; t++) { // one {normal+emission, tint, mat} record per triangle
                prim.add(nx);
                prim.add(ny);
                prim.add(nz);
                prim.add(emission);
                prim.add(tr);
                prim.add(tg);
                prim.add(tb);
                prim.add(material);
                prim.add(rough);
                prim.add(0f); // metalness (fluids are dielectric)
                prim.add(0f); // hasS (fluids carry no LabPBR atlas material)
                prim.add(0f); // hasN
                g.materialSprites.add(null);
                g.ommSprites.add(null);
            }
        }

        // Unused VertexConsumer surface — FluidRenderer only calls the bulk addVertex above.
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }

}
