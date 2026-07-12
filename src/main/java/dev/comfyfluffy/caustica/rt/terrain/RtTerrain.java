package dev.comfyfluffy.caustica.rt.terrain;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.SpriteContentsAccessor;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
import dev.comfyfluffy.caustica.rt.material.RtMaterials;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;

/**
 * Per-section terrain residency synced to vanilla's loaded chunks. A singleton manager
 * keeps a map of resident 16³ sections. The 20 TPS tick maintains the desired window around the player
 * (slid incrementally on section-boundary crossings) and drains dirty events; the actual streaming —
 * snapshot dispatch, upload drain, batched BLAS build kick — runs once per render frame from
 * {@link RtComposite} under a small wall-clock budget, so fill cost is a flat slice of every frame
 * rather than a per-tick burst. Residency follows vanilla because a section is only "desired" when its
 * chunk is loaded ({@code hasChunk}), so chunk load/unload drives build/free without any mixin.
 *
 * <p>Geometry comes from vanilla's {@link ModelBlockRenderer} (correct shapes, neighbour cull, biome
 * tint, alpha cutout). Vertices are section-local (f32-exact); each TLAS instance carries a
 * translation {@code sectionOrigin − rebaseOrigin} (rebase = player block at the last rebuild, so
 * transforms stay small at any world coordinate) and an {@code instanceCustomIndex} into a BDA
 * section table ({@code {primAddr, uvAddr, triBase[4]}} per section) the hit shaders read. The index
 * buffer itself is retained only for the BLAS build (per-triangle corner UVs mean shading never needs
 * an index-buffer read — lever B), so its address isn't duplicated into this table.
 *
 * <p>Tessellation reads only an immutable snapshot ({@link RtSectionSnapshots.Region}, palette-only
 * copies captured on the render thread and cached persistently across passes — see
 * {@link RtSectionSnapshots}). CPU meshing runs on
 * {@link RtWorkerPool}; snapshotting, GPU upload, BLAS prepare and queue submission stay on the render
 * thread. The BLAS build itself is an async GPU submit, and frees are deferred frames-in-flight-safe
 * (no {@code waitIdle} on the hot path).
 */
public final class RtTerrain {
    // CPU tessellation runs on RtWorkerPool. The render thread snapshots section neighbourhoods, uploads
    // completed meshes, prepares BLASes, and submits the GPU build. Those render-thread pieces run as one
    // "streaming pass" per render frame (driven by RtComposite), bounded by a wall-clock budget so the
    // per-frame cost is flat instead of the old 20 TPS burst; the counts below are per-pass ceilings.
    private static int asyncDispatchPerTick() {
        return CausticaConfig.Rt.Terrain.ASYNC_DISPATCH_PER_TICK.value();
    }

    private static int sectionResultsPerTick() {
        return CausticaConfig.Rt.Terrain.SECTION_RESULTS_PER_TICK.value();
    }

    // Backlog (missing + queued re-extracts + in-flight jobs) at which the dynamic budget reaches max.
    private static final int STREAM_PRESSURE_FULL_BACKLOG = 256;

    /**
     * Per-frame budget scaled by queue pressure: near-empty queues get the base slice, a big backlog
     * (initial fill, F3+A, teleport, fast flight) ramps linearly to the max so fill throughput recovers —
     * a few extra budgeted ms per frame only while the queue lasts.
     */
    private long dynamicStreamBudgetNanos() {
        float base = CausticaConfig.Rt.Terrain.STREAM_BUDGET_MS.value();
        float max = Math.max(base, CausticaConfig.Rt.Terrain.STREAM_BUDGET_MAX_MS.value());
        int backlog = missing.size() + playerReextract.size() + reextract.size() + inFlight.size();
        float t = Math.min(1f, backlog / (float) STREAM_PRESSURE_FULL_BACKLOG);
        return (long) ((base + (max - base) * t) * 1_000_000f);
    }

    private static long streamFallbackBudgetNanos() {
        return (long) (CausticaConfig.Rt.Terrain.STREAM_FALLBACK_BUDGET_MS.value() * 1_000_000f);
    }

    // Backpressure cap: stop dispatching once this many sections are in flight. Bounds queue depth and
    // snapshot memory (each in-flight region pins 27 cached section snapshots) when flying through the world.
    private static int maxInflight() {
        return CausticaConfig.Rt.Terrain.MAX_INFLIGHT_SECTIONS.value();
    }

    private static final int SECTION_ENTRY_BYTES = 32; // {u64 primAddr, u64 uvAddr, u32 triBase[4]}
    // Frames a retired resource must outlive before it's freed (> frames-in-flight). The frame counter
    // advances per composite; old TLAS/table/sections are freed this many frames after the swap.
    private static final int KEEP_FRAMES = 4;
    private static final long NO_TESS_TOKEN = Long.MIN_VALUE;
    private static final int PRIORITY_PLAYER = 0;
    private static final int PRIORITY_DIRTY = 1;
    private static final int PRIORITY_MISSING = 2;
    private static final long NO_DIRTY_GROUP = 0L;
    // If no render frame has driven a streaming pass for this long, the 20 TPS tick takes over (loading
    // screens / hidden window — states where a bigger budget can't hitch a visible frame).
    private static final long STREAM_FALLBACK_AFTER_NANOS = 200_000_000L;

    private static int sectionTableInitialCapacity() {
        return CausticaConfig.Rt.Terrain.SECTION_TABLE_INITIAL_CAPACITY.value();
    }

    private static int rebaseDistanceBlocks() {
        return CausticaConfig.Rt.Terrain.REBASE_DISTANCE_BLOCKS.value();
    }

    private static final RtTerrain INSTANCE = new RtTerrain();

    private final Long2ObjectOpenHashMap<SectionGeom> resident = new Long2ObjectOpenHashMap<>();
    // Persistent palette snapshots for tessellation regions (render-thread only); invalidated on dirty
    // sections, column unload/window-leave, and full clears.
    private final RtSectionSnapshots snapshots = new RtSectionSnapshots();
    private LongOpenHashSet published = new LongOpenHashSet();
    private final LongOpenHashSet empty = new LongOpenHashSet(); // loaded, in-window sections with no geometry
    private final Object dirtyLock = new Object();
    private final LongOpenHashSet dirty = new LongOpenHashSet(); // edited sections to re-extract
    private final LongArrayList dirtyDrain = new LongArrayList();
    private final ArrayList<DirtyEvent> dirtyEvents = new ArrayList<>();
    private final ArrayList<DirtyEvent> dirtyEventDrain = new ArrayList<>();
    // Persistent desired window and queued work. The expensive section window is rebuilt only when the
    // player crosses a section/radius/Y-band boundary; steady ticks poll chunk columns for load changes.
    private final LongOpenHashSet desired = new LongOpenHashSet();
    private final LongOpenHashSet desiredColumns = new LongOpenHashSet();
    private final LongOpenHashSet loadedColumns = new LongOpenHashSet();
    private final LongArrayList missing = new LongArrayList();
    private final LongOpenHashSet queuedMissing = new LongOpenHashSet();
    private final Long2IntOpenHashMap missingPriority = new Long2IntOpenHashMap();
    private final Long2LongOpenHashMap queuedDirtyGroup = new Long2LongOpenHashMap();
    private final LongArrayList playerReextract = new LongArrayList();
    private final LongOpenHashSet queuedPlayerReextract = new LongOpenHashSet();
    private final LongArrayList reextract = new LongArrayList();
    private final LongOpenHashSet queuedReextract = new LongOpenHashSet();
    // Accumulators consumed by the next startBuild: window sync (tick) and upload drain (streaming pass)
    // may run on different frames, so evicted geometry waits here until a build kick retires it.
    private final List<SectionGeom> removed = new ArrayList<>();
    private final List<PreparedSection> prepared = new ArrayList<>();
    private final List<Deferred> deferred = new ArrayList<>(); // frames-in-flight-safe frees
    // Worker tessellation bookkeeping (render-thread only). `inFlight` maps a dispatched section key to a
    // monotonic token; a completed job whose token no longer matches (section re-dirtied / unloaded /
    // left the window since dispatch) is dropped. `jobs` holds the outstanding worker futures.
    private final Long2LongOpenHashMap inFlight = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap inFlightDirtyGroup = new Long2LongOpenHashMap();
    private final Long2ObjectOpenHashMap<DirtyGroup> dirtyGroups = new Long2ObjectOpenHashMap<>();
    private final List<TessJob> jobs = new ArrayList<>();
    private final ConcurrentLinkedQueue<TessResult> completedPlayerJobs = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<TessResult> completedDirtyJobs = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<TessResult> completedMissingJobs = new ConcurrentLinkedQueue<>();
    private long tessToken;
    private long dirtyGroupSeq;
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
    private int windowPcz;
    private int windowRadius;
    private int windowLoY;
    private int windowHiY;
    // When the last streaming pass ran on a render frame — the tick fallback watches this (see
    // STREAM_FALLBACK_AFTER_NANOS).
    private long lastFrameStreamNanos;

    private RtTerrain() {
        missingPriority.defaultReturnValue(PRIORITY_MISSING);
        queuedDirtyGroup.defaultReturnValue(NO_DIRTY_GROUP);
        inFlight.defaultReturnValue(NO_TESS_TOKEN);
        inFlightDirtyGroup.defaultReturnValue(NO_DIRTY_GROUP);
    }

    /**
     * The manager if it has a valid (possibly zero-instance) section table to trace against, else null.
     * Null only while genuinely uninitialized (no world, or mid-teardown) — a transient empty-residency
     * window (world join, dimension change, a full evict) still returns non-null so the RT frame keeps
     * tracing (sky/entities only) instead of a caller falling back to vanilla.
     */
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

    /** Section table device address: {@code {u64 primAddr, u64 uvAddr, u32 triBase[4]}} per section, indexed by gl_InstanceCustomIndexEXT. */
    public long tableAddress() {
        return sectionTable.deviceAddress;
    }

    /** Per-tick residency update: window sync + dirty drain (plus the streaming fallback, see {@link #frame}). */
    public static void update(RtContext ctx) {
        INSTANCE.tick(ctx);
    }

    /**
     * Per-render-frame streaming pass, driven by {@link RtComposite#composite}: finalize a completed BLAS
     * build, dispatch snapshots to the worker pool and drain finished uploads — all bounded by
     * {@code caustica.rt.streamBudgetMs} of wall clock so the cost is a flat slice of every frame instead
     * of a 20 TPS burst.
     */
    public static void frame(RtContext ctx) {
        INSTANCE.frameStream(ctx);
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
            LongArrayList keys = new LongArrayList();
            for (int scx = (minX - 1) >> 4; scx <= (maxX + 1) >> 4; scx++) {
                for (int scy = (minY - 1) >> 4; scy <= (maxY + 1) >> 4; scy++) {
                    for (int scz = (minZ - 1) >> 4; scz <= (maxZ + 1) >> 4; scz++) {
                        keys.add(sectionKey(scx, scy, scz));
                    }
                }
            }
            if (!keys.isEmpty()) {
                long groupId = ++terrain.dirtyGroupSeq;
                if (groupId == NO_DIRTY_GROUP) {
                    groupId = ++terrain.dirtyGroupSeq;
                }
                terrain.dirtyEvents.add(new DirtyEvent(groupId, keys));
                terrain.dirtyPending = true;
            }
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

        int pbx = mc.player.getBlockX();
        int pby = mc.player.getBlockY();
        int pbz = mc.player.getBlockZ();
        int pcx = pbx >> 4, pcz = pbz >> 4, psy = pby >> 4;
        int r = horizontalChunks(mc);
        ClientChunkCache chunkSource = level.getChunkSource();
        int minSecY = level.getMinY() >> 4;
        int maxSecY = (level.getMinY() + level.getHeight() - 1) >> 4;
        int loY = minSecY;
        int hiY = maxSecY;

        // Evicted geometry lands in `removed` and is consumed by the next streaming pass's build kick.
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.windowSync")) {
            syncDesiredWindow(chunkSource, pcx, psy, pcz, r, loY, hiY, removed);
        }

        // Re-extract edited sections. Drain under a short lock so concurrent block updates are not lost.
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.dirtyDrain")) {
            drainDirty();
            if (!dirtyDrain.isEmpty()) {
                for (LongIterator it = dirtyDrain.iterator(); it.hasNext(); ) {
                    long key = it.nextLong();
                    handleDirtySection(key, pcx, psy, pcz, NO_DIRTY_GROUP);
                }
            }
            if (!dirtyEventDrain.isEmpty()) {
                for (DirtyEvent event : dirtyEventDrain) {
                    handleDirtyEvent(event, pcx, psy, pcz);
                }
            }
        }
        // Dispatch/drain/build normally runs per render frame (RtComposite → frame()). If no frame has
        // streamed recently — loading screen, no world rendering — drive it from here with the bigger
        // fallback budget so the world still fills.
        if (System.nanoTime() - lastFrameStreamNanos > STREAM_FALLBACK_AFTER_NANOS) {
            stream(ctx, streamFallbackBudgetNanos());
        }
    }

    /** The per-render-frame entry point: run one budgeted streaming pass. */
    private void frameStream(RtContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        lastFrameStreamNanos = System.nanoTime();
        processDeferredFrees();
        stream(ctx, dynamicStreamBudgetNanos());
    }

    /**
     * One streaming pass: finalize a completed async BLAS build, dispatch section snapshots to the worker
     * pool, drain finished tessellations into GPU uploads, and kick the next batched build — stopping
     * dispatch/drain once {@code budgetNanos} of wall clock is spent. Skips silently when there is
     * nothing to do (no stats row).
     */
    private void stream(RtContext ctx, long budgetNanos) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        boolean buildDone = pending != null && ctx.isAsyncDone(pending.op);
        if (pending != null && !buildDone) {
            return; // one GPU build in flight at a time; workers keep meshing their queue meanwhile
        }
        if (!buildDone && playerReextract.isEmpty() && reextract.isEmpty() && missing.isEmpty()
                && completedPlayerJobs.isEmpty() && completedDirtyJobs.isEmpty() && completedMissingJobs.isEmpty()
                && removed.isEmpty() && prepared.isEmpty()) {
            return;
        }
        if (buildDone) {
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.finalize")) {
                finalizePending(ctx);
            }
        }
        long deadline = System.nanoTime() + budgetNanos;
        int pbx = mc.player.getBlockX();
        int pby = mc.player.getBlockY();
        int pbz = mc.player.getBlockZ();
        int pcx = pbx >> 4, pcz = pbz >> 4, psy = pby >> 4;
        ClientChunkCache chunkSource = level.getChunkSource();

        // Drain finished tessellations FIRST — uploads are the visible fill progress, so they get budget
        // priority over new snapshots (dispatch was starving them when snapshots ate the whole slice).
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.drainUpload")) {
            drainTessellation(ctx, prepared, removed, sectionResultsPerTick(), deadline);
        }

        // Tessellate new sections with the remaining budget (BLAS build deferred to the batched
        // submission below).
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.snapshotDispatch")) {
            DispatchContext dispatch = null;
            int dispatchSlots = Math.min(asyncDispatchPerTick(), Math.max(0, maxInflight() - inFlight.size()));
            if (dispatchSlots > 0 && !playerReextract.isEmpty() && System.nanoTime() < deadline) {
                dispatch = dispatchContext(level);
                dispatchSlots -= dispatchReextract(dispatch, chunkSource, dispatchSlots, deadline,
                        playerReextract, queuedPlayerReextract, PRIORITY_PLAYER);
            }
            if (dispatchSlots > 0 && !reextract.isEmpty() && System.nanoTime() < deadline) {
                if (dispatch == null) {
                    dispatch = dispatchContext(level);
                }
                dispatchSlots -= dispatchReextract(dispatch, chunkSource, dispatchSlots, deadline,
                        reextract, queuedReextract, PRIORITY_DIRTY);
            }
            if (dispatchSlots > 0 && !missing.isEmpty() && System.nanoTime() < deadline) {
                if (dispatch == null) {
                    dispatch = dispatchContext(level);
                }
                dispatchTessellation(dispatch, chunkSource, dispatchSlots, deadline, pcx, psy, pcz);
            }
        }

        if (!removed.isEmpty() || !prepared.isEmpty()) {
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.startBuild")) {
                startBuild(ctx, prepared, removed, pbx, pby, pbz);
                removed.clear();
                prepared.clear();
            }
        }
    }

    private void syncDesiredWindow(ClientChunkCache chunkSource, int pcx, int psy, int pcz,
                                   int radius, int loY, int hiY, List<SectionGeom> removed) {
        if (!windowValid || windowRadius != radius || windowLoY != loY || windowHiY != hiY
                || Math.abs(pcx - windowPcx) > radius || Math.abs(pcz - windowPcz) > radius) {
            // First window, a shape change, or a jump past any overlap (teleport) — build from scratch.
            rebuildDesiredWindow(chunkSource, pcx, pcz, radius, loY, hiY, removed);
        } else if (windowPcx != pcx || windowPcz != pcz) {
            slideDesiredWindow(chunkSource, pcx, pcz, radius, loY, hiY, removed);
        } else {
            pollLoadedColumns(chunkSource, loY, hiY, removed);
        }
    }

    private void rebuildDesiredWindow(ClientChunkCache chunkSource, int pcx, int pcz,
                                      int radius, int loY, int hiY, List<SectionGeom> removed) {
        snapshots.clear(); // teleport / shape change — no per-column eviction diff, drop everything
        desired.clear();
        desiredColumns.clear();
        loadedColumns.clear();
        missing.clear();
        queuedMissing.clear();
        missingPriority.clear();

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
        removeKeysNotIn(queuedPlayerReextract, desired);
        windowValid = true;
        windowPcx = pcx;
        windowPcz = pcz;
        windowRadius = radius;
        windowLoY = loY;
        windowHiY = hiY;
    }

    /**
     * Slide the desired window after a section-boundary crossing: touch only the columns entering and
     * leaving the (2r+1)² rect instead of rebuilding it (the rebuild — ~100k hash inserts + a full
     * resident prune + a queue sort at r=32 — was a 10–30 ms hitch every 16 blocks of flight).
     */
    private void slideDesiredWindow(ClientChunkCache chunkSource, int pcx, int pcz,
                                    int radius, int loY, int hiY, List<SectionGeom> removed) {
        int newMinX = pcx - radius, newMaxX = pcx + radius;
        int newMinZ = pcz - radius, newMaxZ = pcz + radius;
        for (int scx = windowPcx - radius; scx <= windowPcx + radius; scx++) {
            boolean xOutside = scx < newMinX || scx > newMaxX;
            for (int scz = windowPcz - radius; scz <= windowPcz + radius; scz++) {
                if (!xOutside && scz >= newMinZ && scz <= newMaxZ) {
                    continue; // still in the window
                }
                long column = columnKey(scx, scz);
                desiredColumns.remove(column);
                // Only loaded columns ever had desired sections / queued work (see addDesiredColumnSections
                // call sites) — nothing to remove for a never-loaded column.
                if (loadedColumns.remove(column)) {
                    removeDesiredColumnSections(scx, scz, loY, hiY, removed);
                }
            }
        }
        int oldMinX = windowPcx - radius, oldMaxX = windowPcx + radius;
        int oldMinZ = windowPcz - radius, oldMaxZ = windowPcz + radius;
        for (int scx = newMinX; scx <= newMaxX; scx++) {
            boolean xOutside = scx < oldMinX || scx > oldMaxX;
            for (int scz = newMinZ; scz <= newMaxZ; scz++) {
                if (!xOutside && scz >= oldMinZ && scz <= oldMaxZ) {
                    continue;
                }
                long column = columnKey(scx, scz);
                desiredColumns.add(column);
                if (chunkSource.hasChunk(scx, scz)) {
                    loadedColumns.add(column);
                    addDesiredColumnSections(scx, scz, loY, hiY);
                }
            }
        }
        windowPcx = pcx;
        windowPcz = pcz;
    }

    private void pollLoadedColumns(ClientChunkCache chunkSource, int loY, int hiY, List<SectionGeom> removed) {
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
                addDesiredColumnSections(scx, scz, loY, hiY);
            } else {
                loadedColumns.remove(column);
                removeDesiredColumnSections(scx, scz, loY, hiY, removed);
            }
        }
    }

    private void addDesiredColumnSections(int scx, int scz, int loY, int hiY) {
        for (int scy = loY; scy <= hiY; scy++) {
            long key = sectionKey(scx, scy, scz);
            desired.add(key);
            enqueueMissingIfNeeded(key);
        }
    }

    private void removeDesiredColumnSections(int scx, int scz, int loY, int hiY, List<SectionGeom> removed) {
        for (int scy = loY; scy <= hiY; scy++) {
            long key = sectionKey(scx, scy, scz);
            // Unloaded or out of the window — the chunk may reload with different data, so the cached
            // snapshot can't be trusted past this point.
            snapshots.invalidate(key);
            desired.remove(key);
            clearQueuedWork(key, true);
            invalidateInFlight(key);
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
        removeInFlightNotIn(desired);
        removeQueuedGroupsNotIn(desired);
    }

    private void handleDirtyEvent(DirtyEvent event, int pcx, int psy, int pcz) {
        int groupMembers = 0;
        for (LongIterator it = event.keys().iterator(); it.hasNext(); ) {
            if (canGroupDirtySection(it.nextLong())) {
                groupMembers++;
            }
        }

        long groupId = NO_DIRTY_GROUP;
        if (groupMembers > 1) {
            groupId = event.groupId();
            dirtyGroups.put(groupId, new DirtyGroup(groupId, groupMembers, event.keys()));
        }

        for (LongIterator it = event.keys().iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            long memberGroup = groupId != NO_DIRTY_GROUP
                    && dirtyGroups.containsKey(groupId)
                    && canGroupDirtySection(key) ? groupId : NO_DIRTY_GROUP;
            if (!handleDirtySection(key, pcx, psy, pcz, memberGroup) && memberGroup != NO_DIRTY_GROUP) {
                cancelDirtyGroup(memberGroup);
            }
        }
    }

    private boolean canGroupDirtySection(long key) {
        return desired.contains(key) && (resident.containsKey(key) || empty.contains(key));
    }

    private boolean handleDirtySection(long key, int pcx, int psy, int pcz, long dirtyGroup) {
        snapshots.invalidate(key); // block data changed — the cached palette snapshot is stale
        boolean wasEmpty = empty.remove(key);
        if (wasEmpty && dirtyGroup != NO_DIRTY_GROUP) {
            DirtyGroup group = dirtyGroups.get(dirtyGroup);
            if (group != null) {
                group.restoreEmptyKeys.add(key);
            }
        }
        invalidateInFlight(key); // invalidate any in-flight tessellation of the now-stale section
        if (!desired.contains(key)) {
            clearQueuedWork(key, true);
            return false;
        }
        // Keep the old geometry resident + traced; re-dispatch and swap when the new mesh is ready
        // (no eviction gap -> no flicker). Non-resident dirty keys re-enter the normal missing queue.
        SectionGeom g = resident.get(key);
        int priority = isPlayerUpdatePriority(key, pcx, psy, pcz) ? PRIORITY_PLAYER : PRIORITY_DIRTY;
        if (g != null) {
            if (priority == PRIORITY_PLAYER) {
                queuedReextract.remove(key); // promote; stale normal entry will be skipped
                if (queuedPlayerReextract.add(key)) {
                    playerReextract.add(key);
                }
            } else if (!queuedPlayerReextract.contains(key) && queuedReextract.add(key)) {
                reextract.add(key);
            }
            setQueuedGroup(key, dirtyGroup);
            return true;
        } else {
            return enqueueMissingUrgent(key, priority, dirtyGroup);
        }
    }

    private static boolean isPlayerUpdatePriority(long key, int pcx, int psy, int pcz) {
        return Math.abs(sectionX(key) - pcx) <= 1
                && Math.abs(sectionY(key) - psy) <= 1
                && Math.abs(sectionZ(key) - pcz) <= 1;
    }

    private boolean enqueueMissingIfNeeded(long key) {
        if (resident.containsKey(key) || empty.contains(key) || inFlight.containsKey(key)) {
            return false;
        }
        if (!queuedMissing.add(key)) {
            return false;
        }
        setQueuedGroup(key, NO_DIRTY_GROUP);
        missingPriority.put(key, PRIORITY_MISSING);
        missing.add(key);
        return true;
    }

    private boolean enqueueMissingUrgent(long key, int priority) {
        return enqueueMissingUrgent(key, priority, NO_DIRTY_GROUP);
    }

    private boolean enqueueMissingUrgent(long key, int priority, long dirtyGroup) {
        if (resident.containsKey(key) || empty.contains(key) || inFlight.containsKey(key)) {
            return false;
        }
        // `missing` is unsorted — urgency is just the priority lane, read when dispatch selects the best
        // candidates. Already-queued keys only get their priority/group upgraded.
        if (queuedMissing.add(key)) {
            missing.add(key);
        }
        setQueuedGroup(key, dirtyGroup);
        missingPriority.put(key, priority);
        return true;
    }

    private void clearQueuedWork(long key, boolean cancelGroup) {
        queuedMissing.remove(key);
        missingPriority.remove(key);
        queuedPlayerReextract.remove(key);
        queuedReextract.remove(key);
        clearQueuedGroup(key, cancelGroup);
    }

    private void setQueuedGroup(long key, long groupId) {
        long oldGroup = groupId == NO_DIRTY_GROUP ? queuedDirtyGroup.remove(key) : queuedDirtyGroup.put(key, groupId);
        if (oldGroup != NO_DIRTY_GROUP && oldGroup != groupId) {
            cancelDirtyGroup(oldGroup);
        }
    }

    private void clearQueuedGroup(long key, boolean cancelGroup) {
        long groupId = queuedDirtyGroup.remove(key);
        if (cancelGroup && groupId != NO_DIRTY_GROUP) {
            cancelDirtyGroup(groupId);
        }
    }

    private boolean isQueuedAnywhere(long key) {
        return queuedMissing.contains(key) || queuedPlayerReextract.contains(key) || queuedReextract.contains(key);
    }

    private void invalidateInFlight(long key) {
        long token = inFlight.remove(key);
        long groupId = inFlightDirtyGroup.remove(key);
        if (token != NO_TESS_TOKEN && groupId != NO_DIRTY_GROUP) {
            cancelDirtyGroup(groupId);
        }
    }

    private void removeInFlightNotIn(LongOpenHashSet keep) {
        for (LongIterator it = inFlight.keySet().iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (!keep.contains(key)) {
                it.remove();
                long groupId = inFlightDirtyGroup.remove(key);
                if (groupId != NO_DIRTY_GROUP) {
                    cancelDirtyGroup(groupId);
                }
            }
        }
    }

    private void removeQueuedGroupsNotIn(LongOpenHashSet keep) {
        LongArrayList cancelGroups = new LongArrayList();
        for (LongIterator it = queuedDirtyGroup.keySet().iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (!keep.contains(key)) {
                long groupId = queuedDirtyGroup.get(key);
                it.remove();
                if (groupId != NO_DIRTY_GROUP) {
                    cancelGroups.add(groupId);
                }
            }
        }
        for (LongIterator it = cancelGroups.iterator(); it.hasNext(); ) {
            cancelDirtyGroup(it.nextLong());
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
        dirtyEventDrain.clear();
        if (!dirtyPending) {
            return;
        }
        synchronized (dirtyLock) {
            for (LongIterator it = dirty.iterator(); it.hasNext(); ) {
                dirtyDrain.add(it.nextLong());
            }
            dirtyEventDrain.addAll(dirtyEvents);
            dirty.clear();
            dirtyEvents.clear();
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

    /**
     * Reusable per-worker-thread meshing state. The mesh + captures are reset between jobs so their
     * backing arrays amortize across sections instead of re-growing per job (the per-job allocate-and-grow
     * ladder was a profiler hotspot during fill). Everything the job result carries out —
     * {@link PackedSection}, OMM data — is copied out of this state before the job returns, so reuse on
     * the next job cannot corrupt a queued result. The renderers stay per-job: they're cheap and capture
     * the dispatch context's model sets/colors.
     */
    private static final class WorkerTessState {
        final QuadCapture capture = new QuadCapture();
        final FluidCapture fluidCapture = new FluidCapture();
        final SectionMesh mesh = new SectionMesh();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        void reset(BlockColors blockColors) {
            capture.blockColors = blockColors;
            capture.discardBlock(); // defensive: a prior job's throw could leave buffered quads
            fluidCapture.reset();
            mesh.reset();
        }
    }

    private static final ThreadLocal<WorkerTessState> WORKER_TESS = ThreadLocal.withInitial(WorkerTessState::new);

    /**
     * Tessellate one section to a section-local CPU mesh and precompute pure-CPU sidecar data such as
     * the terrain opacity micromap. <b>Pure CPU + snapshot reads only</b> — no Vulkan, no shared mutable
     * state — so this is the unit a worker thread runs. Terrain LabPBR sprite references are carried to
     * upload, where the render thread resolves them through the material atlas.
     * Returns the mesh (possibly empty — caller checks {@code idx}).
     */
    private static CpuSection buildCpuSection(BlockAndTintGetter region, BlockStateModelSet modelSet,
                                              ModelBlockRenderer renderer, QuadCapture capture,
                                              FluidRenderer fluidRenderer, FluidCapture fluidCapture,
                                              SectionMesh mesh, BlockPos.MutableBlockPos m, int scx, int scy, int scz) {
        tessellate(region, modelSet, renderer, capture, fluidRenderer, fluidCapture, mesh, m, scx, scy, scz);
        if (mesh.isEmpty()) {
            return new CpuSection(null, null);
        }
        Geom cutout = mesh.cutoutOrEmpty();
        RtAccel.OpacityMicromapInput ommInput =
                RtTerrainOmm.buildInput(cutout.triCount(), cutout.cornerUv.elements(),
                        cutout.ommSprites.elements(), cutout.ommSprites.size());
        return new CpuSection(packSection(mesh), ommInput);
    }

    private static PackedSection packSection(SectionMesh mesh) {
        Geom[] buckets = mesh.buckets(); // { solid, cutout, translucent, water }, indexed by RtAccel.BUCKET_*
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
        int posOff = 0, idxOff = 0, uvOff = 0, matOff = 0, spriteOff = 0, vertBase = 0, triAcc = 0;
        for (int b = 0; b < buckets.length; b++) {
            Geom geom = buckets[b];
            triBase[b] = triAcc;
            int vertSize = geom.verts.size();
            System.arraycopy(geom.verts.elements(), 0, positions, posOff, vertSize);
            int idxSize = geom.idx.size();
            int[] gi = geom.idx.elements();
            if (vertBase == 0) {
                System.arraycopy(gi, 0, indices, idxOff, idxSize);
            } else {
                for (int i = 0; i < idxSize; i++) {
                    indices[idxOff + i] = gi[i] + vertBase;
                }
            }
            int uvSize = geom.cornerUv.size();
            System.arraycopy(geom.cornerUv.elements(), 0, uvs, uvOff, uvSize);
            int matSize = geom.prim.size();
            System.arraycopy(geom.prim.elements(), 0, material, matOff, matSize);
            geom.materialSprites.copyInto(materialSprites, spriteOff);
            posOff += vertSize;
            idxOff += idxSize;
            uvOff += uvSize;
            matOff += matSize;
            spriteOff += bucketTris[b];
            vertBase += vertSize / 3;
            triAcc += bucketTris[b];
        }
        return new PackedSection(positions, indices, uvs, material, materialSprites, bucketTris, triBase);
    }

    private static void tessellate(BlockAndTintGetter region, BlockStateModelSet modelSet,
                                   ModelBlockRenderer renderer, QuadCapture capture,
                                   FluidRenderer fluidRenderer, FluidCapture fluidCapture,
                                   SectionMesh mesh, BlockPos.MutableBlockPos m, int scx, int scy, int scz) {
        int sox = scx << 4, soy = scy << 4, soz = scz << 4;
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
                    // shape, so handled independently of the block model below. Emits section-local
                    // coords + atlas sprite UVs straight into the capturing consumer. Lava's block light
                    // (15) rides the emission channel (water emits 0).
                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        fluidCapture.emission = state.getLightEmission() / 15f;
                        // Water is the dielectric fluid; lava stays an opaque emitter. Tagged per-prim
                        // so the path tracer can branch (see emitQuad).
                        fluidCapture.water = fluid.is(FluidTags.WATER);
                        try {
                            RtFluidMesher.tesselate(region, m, fluidCapture, fluidRenderer.fluidModels, state, fluid);
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
    }

    /** Surface the first per-block/fluid meshing throw (swallowed above to keep one bad block from voiding
     *  the whole section), then stay quiet. May run on a worker thread; the flag is volatile + one-shot. */
    private static void warnMeshOnce(String what, Throwable t) {
        if (!loggedMeshFailure) {
            loggedMeshFailure = true;
            CausticaMod.LOGGER.warn("RT terrain: {} meshing threw (skipped); first occurrence:", what, t);
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
        RtFrameStats.FRAME.count("sectionsUploaded", 1);
        int vertCount = packed.positions().length / 3;
        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        String label = "terrain section " + sox + "," + soy + "," + soz;
        // Terrain sections are long-lived and stream only as the residency window changes. Keep their
        // resources as direct VMA allocations so an eviction returns the allocation to VMA instead of
        // retaining the peak render-distance working set in the per-frame buffer cache.
        RtBuffer positions = ctx.createBuffer((long) packed.positions().length * Float.BYTES, asInput, true,
                label + " positions");
        RtBuffer indices = ctx.createBuffer((long) packed.indices().length * Integer.BYTES, asInput | storage, true,
                label + " indices");
        RtBuffer uvs = ctx.createBuffer((long) packed.uvs().length * Float.BYTES, storage, true,
                label + " uvs");
        RtBuffer material = ctx.createBuffer((long) packed.material().length * Float.BYTES, storage, true,
                label + " material");

        resolveMaterials(packed.material(), packed.materialSprites());
        MemoryUtil.memFloatBuffer(positions.mapped, packed.positions().length).put(packed.positions());
        MemoryUtil.memIntBuffer(indices.mapped, packed.indices().length).put(packed.indices());
        MemoryUtil.memFloatBuffer(uvs.mapped, packed.uvs().length).put(packed.uvs());
        MemoryUtil.memFloatBuffer(material.mapped, packed.material().length).put(packed.material());

        // Split BLAS: geom for each non-empty bucket — solid (OPAQUE, any-hit skipped), cutout (alpha test),
        // water (shadow passthrough). Build is deferred — the caller batches all sections into one submission.
        RtAccel.PreparedBlas blas = RtAccel.prepareTerrainBlas(ctx, positions, vertCount, indices, packed.bucketTris(), ommInput,
                label + " BLAS");
        return new PreparedSection(key, positions, indices, uvs, material, blas, packed.triBase(), sox, soy, soz);
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

    // Whole-sprite average {r, g, b, a} for TRANSLUCENT-bucket sprites (stained glass, ice, …), keyed by
    // sprite identity. world.rahit's shadow-ray path reads this (packed into the prim's otherwise-unused
    // mat lane for translucent triangles — see emit()) instead of sampling blockAtlas per-hit: a shadow
    // ray only needs the pane's overall tint/opacity, not per-texel pattern detail, and this is by far
    // the hottest per-hit texture read in the any-hit shader. Computed lazily from worker threads during
    // tessellation, so it must be concurrency-safe; sprite objects (and this cache) are invalidated for
    // free by the next atlas stitch replacing them with new instances.
    private static final Map<TextureAtlasSprite, float[]> TRANSLUCENT_AVG_CACHE = new ConcurrentHashMap<>();

    private static float[] translucentAvgColor(TextureAtlasSprite sprite) {
        float[] cached = TRANSLUCENT_AVG_CACHE.get(sprite);
        if (cached != null) {
            return cached;
        }
        float[] avg = computeAvgColor(sprite);
        TRANSLUCENT_AVG_CACHE.put(sprite, avg);
        return avg;
    }

    /** Average {r, g, b, a} (0..1) over every texel of a sprite's first frame. */
    private static float[] computeAvgColor(TextureAtlasSprite sprite) {
        var contents = sprite.contents();
        int width = contents.width();
        int height = contents.height();
        NativeImage image = ((SpriteContentsAccessor) contents).caustica$originalImage();
        if (image == null || width <= 0 || height <= 0) {
            return new float[]{1f, 1f, 1f, 0f};
        }
        long sr = 0, sg = 0, sb = 0, sa = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int px = image.getPixel(x, y); // frame 0 always occupies the image's top-left tile
                sr += ARGB.red(px);
                sg += ARGB.green(px);
                sb += ARGB.blue(px);
                sa += ARGB.alpha(px);
            }
        }
        float count = (float) width * height * 255f;
        return new float[]{sr / count, sg / count, sb / count, sa / count};
    }

    /**
     * Snapshot each missing section on the render thread and submit its tessellation to the worker
     * pool. The per-task meshing objects (renderer / captures / MutableBlockPos) are allocated inside the
     * job so nothing mutable is shared across threads; the captured {@code region}, model sets and block
     * colors are read-only. Capped by the configured dispatch budget.
     */
    private static DispatchContext dispatchContext(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        return new DispatchContext(level,
                mc.getModelManager().getBlockStateModelSet(), mc.getModelManager().getFluidStateModelSet(),
                mc.getBlockColors());
    }

    /**
     * Dispatch the best missing sections. {@code missing} is <b>unsorted</b>; every call makes one
     * compacting pass over it (dropping entries whose {@code queuedMissing} membership was cleared
     * elsewhere) while collecting the top candidates by (priority, column-distance², |Δy|) into a bounded
     * max-heap — nearest-first order continuously tracks the player with no sort anywhere (the old
     * sorted-queue approach re-sorted the whole list on every window rebuild, a multi-ms spike at high
     * render distance). Ranking by <b>column</b> first makes the dispatch order column-coherent: all
     * sections of a column share the same 3×3 chunk neighbourhood, and the pass-scoped
     * {@link RenderRegionCache} dedupes {@code SectionCopy}s, so after a column's first snapshot the rest
     * are nearly free.
     */
    private void dispatchTessellation(DispatchContext dispatch, ClientChunkCache chunkSource, int budget, long deadline,
                                      int pcx, int psy, int pcz) {
        if (missing.isEmpty() || budget <= 0) {
            return;
        }
        // Over-collect 2x the budget so candidates skipped for unready neighbour chunks (they cluster at
        // the window edge) don't leave dispatch slots idle.
        int k = Math.min(missing.size(), Math.max(8, budget * 2));
        long[] heapRank = new long[k];
        long[] heapKey = new long[k];
        int heapSize = 0;
        int write = 0;
        for (int read = 0, n = missing.size(); read < n; read++) {
            long key = missing.getLong(read);
            if (!queuedMissing.contains(key)) {
                // Dequeued (dispatched / built / left the window) since it was added — compact out.
                missingPriority.remove(key);
                if (!isQueuedAnywhere(key)) {
                    clearQueuedGroup(key, true);
                }
                continue;
            }
            missing.set(write++, key);
            // rank = priority(48+) | columnDist²(16..47) | |Δy|(0..15): column-major nearest-first.
            int dx = sectionX(key) - pcx;
            int dz = sectionZ(key) - pcz;
            long colDist2 = (long) dx * dx + (long) dz * dz;
            long dy = Math.min(0xFFFF, Math.abs(sectionY(key) - psy));
            long rank = ((long) missingPriority.get(key) << 48) | (colDist2 << 16) | dy;
            if (heapSize < k) {
                heapRank[heapSize] = rank;
                heapKey[heapSize] = key;
                siftUp(heapRank, heapKey, heapSize++);
            } else if (rank < heapRank[0]) {
                heapRank[0] = rank;
                heapKey[0] = key;
                siftDown(heapRank, heapKey, heapSize, 0);
            }
        }
        missing.size(write);
        // Heapsort the candidates ascending (best first), then dispatch until the budget/deadline runs out.
        for (int end = heapSize - 1; end > 0; end--) {
            long r = heapRank[0]; heapRank[0] = heapRank[end]; heapRank[end] = r;
            long q = heapKey[0]; heapKey[0] = heapKey[end]; heapKey[end] = q;
            siftDown(heapRank, heapKey, end, 0);
        }
        for (int i = 0; i < heapSize && budget > 0 && System.nanoTime() < deadline; i++) {
            long key = heapKey[i];
            if (!desired.contains(key) || resident.containsKey(key) || empty.contains(key) || inFlight.containsKey(key)) {
                queuedMissing.remove(key);
                missingPriority.remove(key);
                clearQueuedGroup(key, true);
                continue; // stale list entry — compacted out on a later pass
            }
            int sx = sectionX(key);
            int sz = sectionZ(key);
            if (!neighborChunksReady(chunkSource, sx, sz)) {
                continue; // stays queued; dispatched once the neighbours load
            }
            int priority = missingPriority.remove(key);
            queuedMissing.remove(key);
            budget--;
            dispatchSection(dispatch, key, sx, sectionY(key), sz, priority);
        }
    }

    /** Max-heap sift-up on parallel (rank, key) arrays — worst candidate at the root. */
    private static void siftUp(long[] rank, long[] key, int i) {
        while (i > 0) {
            int parent = (i - 1) >> 1;
            if (rank[parent] >= rank[i]) {
                return;
            }
            long r = rank[parent]; rank[parent] = rank[i]; rank[i] = r;
            long q = key[parent]; key[parent] = key[i]; key[i] = q;
            i = parent;
        }
    }

    private static void siftDown(long[] rank, long[] key, int size, int i) {
        while (true) {
            int left = 2 * i + 1;
            int right = left + 1;
            int big = i;
            if (left < size && rank[left] > rank[big]) {
                big = left;
            }
            if (right < size && rank[right] > rank[big]) {
                big = right;
            }
            if (big == i) {
                return;
            }
            long r = rank[big]; rank[big] = rank[i]; rank[i] = r;
            long q = key[big]; key[big] = key[i]; key[i] = q;
            i = big;
        }
    }

    /**
     * Re-extraction of edited (dirty) sections that are still resident: dispatch a fresh
     * tessellation while leaving the old geometry resident and traced, so it's swapped — never evicted
     * with a gap — when the new mesh is built (see {@link #startBuild} retiring the replaced geom). This
     * is what prevents the visible flicker on block updates that plain eviction would cause.
     */
    private int dispatchReextract(DispatchContext dispatch, ClientChunkCache chunkSource, int budget, long deadline,
                                  LongArrayList queue, LongOpenHashSet queued, int priority) {
        if (queue.isEmpty()) {
            return 0;
        }
        int dispatched = 0;
        int attempts = Math.min(queue.size(), Math.max(64, budget * 4));
        for (int i = 0; i < queue.size() && budget > 0 && attempts-- > 0 && System.nanoTime() < deadline; ) {
            long key = queue.getLong(i);
            if (!queued.contains(key)) {
                if (!isQueuedAnywhere(key)) {
                    clearQueuedGroup(key, true);
                }
                queue.removeLong(i);
                continue;
            }
            // Skip ones the window pass freed this tick (out of view) — they're being retired, not rebuilt.
            SectionGeom g = resident.get(key);
            if (g == null || !desired.contains(key) || inFlight.containsKey(key)) {
                queued.remove(key);
                clearQueuedGroup(key, true);
                queue.removeLong(i);
                continue;
            }
            int sx = g.sx >> 4;
            int sz = g.sz >> 4;
            if (!neighborChunksReady(chunkSource, sx, sz)) {
                i++;
                continue;
            }
            queued.remove(key);
            queue.removeLong(i);
            dispatchSection(dispatch, key, g.sx >> 4, g.sy >> 4, g.sz >> 4, priority);
            budget--;
            dispatched++;
        }
        return dispatched;
    }

    /** Snapshot one section on the render thread and submit its tessellation to the worker pool. */
    private void dispatchSection(DispatchContext dispatch, long key, int sx, int sy, int sz, int priority) {
        RtFrameStats.FRAME.count("sectionsSnapshotted", 1);
        RtSectionSnapshots.Region region = snapshots.createRegion(dispatch.level(), sx, sy, sz);
        long token = ++tessToken;
        long dirtyGroup = queuedDirtyGroup.remove(key);
        if (dirtyGroup != NO_DIRTY_GROUP && !dirtyGroups.containsKey(dirtyGroup)) {
            dirtyGroup = NO_DIRTY_GROUP;
        }
        TessJob job = new TessJob(key, token, sx << 4, sy << 4, sz << 4, priority, dirtyGroup);
        Future<?> future = RtWorkerPool.INSTANCE.submit(() -> {
            try {
                WorkerTessState ws = WORKER_TESS.get(); // thread-confined; reset per job, arrays amortized
                ws.reset(dispatch.blockColors());
                ModelBlockRenderer renderer = new ModelBlockRenderer(false, true, dispatch.blockColors());
                FluidRenderer fluidRenderer = new FluidRenderer(dispatch.fluidModelSet());
                CpuSection cpu = buildCpuSection(region, dispatch.modelSet(), renderer, ws.capture,
                        fluidRenderer, ws.fluidCapture, ws.mesh, ws.pos, sx, sy, sz);
                enqueueCompleted(job, cpu, null);
            } catch (Throwable t) {
                enqueueCompleted(job, null, t);
                throw t;
            }
            return null;
        });
        job.future = future;
        inFlight.put(key, token);
        if (dirtyGroup != NO_DIRTY_GROUP) {
            inFlightDirtyGroup.put(key, dirtyGroup);
        } else {
            inFlightDirtyGroup.remove(key);
        }
        addJob(job);
    }

    private void addJob(TessJob job) {
        job.jobIndex = jobs.size();
        jobs.add(job);
    }

    private void enqueueCompleted(TessJob job, CpuSection cpu, Throwable failure) {
        TessResult result = new TessResult(job, cpu, failure);
        switch (job.priority) {
            case PRIORITY_PLAYER -> completedPlayerJobs.add(result);
            case PRIORITY_DIRTY -> completedDirtyJobs.add(result);
            default -> completedMissingJobs.add(result);
        }
    }

    private TessResult pollCompleted() {
        TessResult result = completedPlayerJobs.poll();
        if (result != null) {
            return result;
        }
        result = completedDirtyJobs.poll();
        return result != null ? result : completedMissingJobs.poll();
    }

    private void removeJob(TessJob job) {
        int index = job.jobIndex;
        if (index < 0 || index >= jobs.size() || jobs.get(index) != job) {
            return; // already canceled/removed; stale completed result
        }
        int lastIndex = jobs.size() - 1;
        TessJob last = jobs.remove(lastIndex);
        if (index != lastIndex) {
            jobs.set(index, last);
            last.jobIndex = index;
        }
        job.jobIndex = -1;
    }

    /**
     * Upload finished worker meshes (up to the configured result budget per tick) on the
     * render thread. A job whose token no longer matches {@link #inFlight} is stale — its section was
     * re-dirtied / unloaded / left the window since dispatch — and is dropped without uploading.
     */
    private void drainTessellation(RtContext ctx, List<PreparedSection> prepared, List<SectionGeom> removed,
                                   int resultCap, long deadline) {
        int budget = resultCap;
        int attempts = resultCap * 4;
        boolean first = true;
        while (budget > 0 && attempts-- > 0) {
            // Always take at least one result so uploads make progress even when dispatch ate the whole
            // budget (inFlight backpressure then shifts the budget to draining on the next frames).
            if (!first && System.nanoTime() >= deadline) {
                break;
            }
            first = false;
            TessResult result = pollCompleted();
            if (result == null) {
                break;
            }
            TessJob job = result.job();
            removeJob(job);
            long expected = inFlight.get(job.key);
            boolean valid = expected == job.token;
            if (!valid) {
                continue; // stale result; a newer dispatch (or none) supersedes it
            }
            inFlight.remove(job.key);
            long dirtyGroup = inFlightDirtyGroup.remove(job.key);
            if (result.failure() != null) {
                if (dirtyGroup != NO_DIRTY_GROUP) {
                    cancelDirtyGroup(dirtyGroup);
                }
                if (!loggedTessFailure) {
                    loggedTessFailure = true;
                    CausticaMod.LOGGER.warn("async terrain: tessellation task failed for section {},{},{}",
                            job.sox >> 4, job.soy >> 4, job.soz >> 4, result.failure());
                }
                continue;
            }
            PackedSection packed = result.cpu().packed();
            if (dirtyGroup != NO_DIRTY_GROUP && dirtyGroups.containsKey(dirtyGroup)) {
                DirtyGroup group = dirtyGroups.get(dirtyGroup);
                if (packed == null) {
                    SectionGeom prev = resident.get(job.key);
                    if (prev != null) {
                        group.removed.add(prev);
                    } else {
                        group.restoreEmptyKeys.add(job.key);
                    }
                    group.emptyKeys.add(job.key);
                } else {
                    empty.remove(job.key);
                    group.prepared.add(uploadSection(ctx, packed, result.cpu().opacityMicromap(), job.key, job.sox, job.soy, job.soz));
                }
                completeDirtyGroupMember(group, prepared, removed);
                budget--;
            } else {
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
                    empty.remove(job.key);
                    prepared.add(uploadSection(ctx, packed, result.cpu().opacityMicromap(), job.key, job.sox, job.soy, job.soz));
                    budget--;
                }
            }
        }
    }

    private void completeDirtyGroupMember(DirtyGroup group, List<PreparedSection> prepared, List<SectionGeom> removed) {
        if (--group.remaining > 0) {
            return;
        }
        dirtyGroups.remove(group.id);
        prepared.addAll(group.prepared);
        removed.addAll(group.removed);
        for (LongIterator it = group.emptyKeys.iterator(); it.hasNext(); ) {
            empty.add(it.nextLong());
        }
    }

    private void cancelDirtyGroup(long groupId) {
        DirtyGroup group = dirtyGroups.remove(groupId);
        if (group == null) {
            return;
        }
        for (PreparedSection ps : group.prepared) {
            destroyPreparedSection(ps);
        }
        for (LongIterator it = group.restoreEmptyKeys.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (desired.contains(key) && !resident.containsKey(key) && !inFlight.containsKey(key) && !isQueuedAnywhere(key)) {
                empty.add(key);
            }
        }
        for (LongIterator it = group.keys.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (queuedDirtyGroup.get(key) == groupId) {
                queuedDirtyGroup.remove(key);
            }
            if (inFlightDirtyGroup.get(key) == groupId) {
                inFlightDirtyGroup.remove(key);
            }
        }
    }

    private void cancelAllDirtyGroups() {
        if (dirtyGroups.isEmpty()) {
            return;
        }
        for (DirtyGroup group : dirtyGroups.values()) {
            for (PreparedSection ps : group.prepared) {
                destroyPreparedSection(ps);
            }
        }
        dirtyGroups.clear();
        queuedDirtyGroup.clear();
        inFlightDirtyGroup.clear();
    }

    private void destroyPreparedSection(PreparedSection ps) {
        RtAccel.freeBlasScratch(List.of(ps.blas()));
        SectionGeom g = new SectionGeom(ps.key(), ps.positions(), ps.indices(), ps.uvs(), ps.material(),
                ps.blas().accel, ps.triBase(), ps.sx(), ps.sy(), ps.sz());
        g.destroy();
    }

    /** Pure-CPU worker result: tessellated mesh plus optional opacity micromap input for its cutout bucket. */
    private record CpuSection(PackedSection packed, RtAccel.OpacityMicromapInput opacityMicromap) {
    }

    /** Per-tick render-thread snapshot dependencies shared by reextract + missing dispatch. */
    private record DispatchContext(ClientLevel level, BlockStateModelSet modelSet,
                                   FluidStateModelSet fluidModelSet, BlockColors blockColors) {
    }

    /** Worker-packed terrain buffer payload; upload only allocates buffers and bulk-copies these arrays. */
    private record PackedSection(float[] positions, int[] indices, float[] uvs, float[] material,
                                 TextureAtlasSprite[] materialSprites,
                                 int[] bucketTris, int[] triBase) {
    }

    /** A section tessellated + uploaded with a prepared (not-yet-built) BLAS, pending the batch build. */
    private record PreparedSection(long key, RtBuffer positions, RtBuffer indices, RtBuffer uvs, RtBuffer material,
                                   RtAccel.PreparedBlas blas, int[] triBase, int sx, int sy, int sz) {
    }

    private record DirtyEvent(long groupId, LongArrayList keys) {
    }

    private static final class DirtyGroup {
        final long id;
        final LongArrayList keys;
        final ArrayList<PreparedSection> prepared = new ArrayList<>();
        final ArrayList<SectionGeom> removed = new ArrayList<>();
        final LongArrayList emptyKeys = new LongArrayList();
        final LongArrayList restoreEmptyKeys = new LongArrayList();
        int remaining;

        DirtyGroup(long id, int remaining, LongArrayList keys) {
            this.id = id;
            this.remaining = remaining;
            this.keys = new LongArrayList(keys);
        }
    }

    /** A deferred free: run {@code free} once the frame counter reaches {@code freeFrame}. */
    private record Deferred(long freeFrame, Runnable free) {
    }

    /** An outstanding async tessellation; completed results are delivered through the priority queues. */
    private static final class TessJob {
        final long key;
        final long token;
        final int sox;
        final int soy;
        final int soz;
        final int priority;
        final long dirtyGroup;
        Future<?> future;
        int jobIndex = -1;

        TessJob(long key, long token, int sox, int soy, int soz, int priority, long dirtyGroup) {
            this.key = key;
            this.token = token;
            this.sox = sox;
            this.soy = soy;
            this.soz = soz;
            this.priority = priority;
            this.dirtyGroup = dirtyGroup;
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
        RtAccel.freeBlasScratch(p.blas()); // build done -> BLAS scratch safe to destroy
        applyBuildChanges(ctx, p.prepared(), p.removed(), p.rebase(), p.rbx(), p.rby(), p.rbz());
    }

    private boolean shouldRebase(int rbx, int rby, int rbz) {
        return !ready || sectionTable == null || staticInstances == null
                || Math.abs(rbx - blockX) > rebaseDistanceBlocks()
                || Math.abs(rby - blockY) > rebaseDistanceBlocks()
                || Math.abs(rbz - blockZ) > rebaseDistanceBlocks();
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
            SectionGeom g = new SectionGeom(ps.key(), ps.positions(), ps.indices(), ps.uvs(), ps.material(),
                    ps.blas().accel, ps.triBase(), ps.sx(), ps.sy(), ps.sz());
            if (!desired.contains(ps.key())) {
                // Left the window while its batched BLAS build was in flight (window sync keeps running
                // during builds). Never published — retire the fresh, unreferenced geometry.
                retire(freeAt, null, List.of(g));
                continue;
            }
            SectionGeom prev = resident.get(ps.key());
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
            // The instance list + slot registry were just reset, but evicted geometry can still be waiting
            // in the `removed` accumulator (window sync runs while a build is in flight — e.g. a respawn
            // evicts everything at once) or in a dirty group's removed list. Their instanceIndex/slot point
            // into the cleared lists; neutralize them so the eventual removePublishedSection is a no-op
            // (their buffers are still retired normally when the accumulator is consumed).
            for (SectionGeom g : this.removed) {
                g.instanceIndex = -1;
                g.slot = -1;
            }
            for (DirtyGroup group : dirtyGroups.values()) {
                for (SectionGeom g : group.removed) {
                    g.instanceIndex = -1;
                    g.slot = -1;
                }
            }
            // Zero resident sections (e.g. every section just evicted on a respawn) is a transient
            // streaming state, not "no world" — keep tracing (sky/entities only) instead of handing the
            // frame back to vanilla; see ensureEmptyTableReady.
            ensureEmptyTableReady(ctx);
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
        int capacity = sectionTableInitialCapacity();
        capacity = Math.max(capacity, sectionTableCapacity);
        while (capacity < minCapacity) {
            capacity <<= 1;
        }
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        RtBuffer newTable = ctx.createBuffer((long) capacity * SECTION_ENTRY_BYTES, storage, true,
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

    /**
     * Establish a minimal, valid, zero-instance table so the terrain stays traceable (sky/entities only,
     * {@link #ready} true) through transient no-resident-sections windows (world join, dimension change,
     * a full residency evict) instead of forcing a null/not-ready gap. Only called once sectionTable is
     * already null (old one already freed by the caller) — a plain allocation, not a growth/retire swap.
     */
    private void ensureEmptyTableReady(RtContext ctx) {
        if (sectionTable == null) {
            int capacity = sectionTableInitialCapacity();
            sectionTable = ctx.createBuffer((long) capacity * SECTION_ENTRY_BYTES,
                    org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true,
                    "terrain section table " + capacity + " slots (empty)");
            sectionTableCapacity = capacity;
        }
        if (staticInstances == null) {
            staticInstances = staticInstanceList;
        }
        ready = true;
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
        // idxAddr is deliberately not part of this table: lever B (per-triangle corner UVs) means no
        // shader ever reads a terrain section's index buffer for shading; it's only needed for the BLAS
        // build, which reads g.indices directly. Dropping it keeps this record a clean 32-byte / 2-sector
        // fetch on the hottest per-hit load in the frame instead of the old 40-byte stride.
        long base = sectionTable.mapped + (long) g.slot * SECTION_ENTRY_BYTES;
        MemoryUtil.memPutLong(base, g.material.deviceAddress);
        MemoryUtil.memPutLong(base + 8, g.uvs.deviceAddress);
        MemoryUtil.memPutInt(base + 16, g.triBase[0]);
        MemoryUtil.memPutInt(base + 20, g.triBase[1]);
        MemoryUtil.memPutInt(base + 24, g.triBase[2]);
        MemoryUtil.memPutInt(base + 28, g.triBase[3]);
    }

    private static RtAccel.Instance instanceFor(SectionGeom g, int rbx, int rby, int rbz) {
        float[] xform = {1, 0, 0, g.sx - rbx, 0, 1, 0, g.sy - rby, 0, 0, 1, g.sz - rbz};
        return new RtAccel.Instance(xform, g.blas.deviceAddress, g.slot);
    }

    /** Queue old GPU resources for a frames-in-flight-safe free at {@code freeFrame}. */
    private void retire(long freeFrame, RtBuffer oldTable, List<SectionGeom> removed) {
        if (oldTable != null) {
            deferred.add(new Deferred(freeFrame, oldTable::destroy));
        }
        for (SectionGeom g : removed) {
            deferred.add(new Deferred(freeFrame, g::destroy));
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
            inFlightDirtyGroup.clear();
            return;
        }
        for (TessJob job : jobs) {
            if (job.future != null) {
                job.future.cancel(true);
            }
            job.jobIndex = -1;
        }
        jobs.clear();
        completedPlayerJobs.clear();
        completedDirtyJobs.clear();
        completedMissingJobs.clear();
        inFlight.clear();
        inFlightDirtyGroup.clear();
    }

    /** Full teardown (world exit / shutdown): drain the GPU, then free everything incl. an in-flight build. */
    private void clear(RtContext ctx, boolean shutdown) {
        cancelJobs();
        cancelAllDirtyGroups();
        snapshots.clear();
        synchronized (dirtyLock) {
            dirty.clear(); // any pending re-extract keys refer to the old world/coords — drop them
            dirtyEvents.clear();
            dirtyPending = false;
        }
        dirtyDrain.clear();
        dirtyEventDrain.clear();
        desired.clear();
        desiredColumns.clear();
        loadedColumns.clear();
        missing.clear();
        queuedMissing.clear();
        missingPriority.clear();
        queuedDirtyGroup.clear();
        playerReextract.clear();
        queuedPlayerReextract.clear();
        reextract.clear();
        queuedReextract.clear();
        windowValid = false;
        if (pending == null && resident.isEmpty() && sectionTable == null && deferred.isEmpty()
                && removed.isEmpty() && prepared.isEmpty()) {
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
            if (shutdown) {
                ready = false;
            } else {
                ensureEmptyTableReady(ctx);
            }
            return;
        }
        ctx.waitIdle();
        if (pending != null) {
            ctx.freeAsync(pending.op());
            RtAccel.freeBlasScratch(pending.blas());
            for (PreparedSection ps : pending.prepared()) {
                SectionGeom g = new SectionGeom(ps.key(), ps.positions(), ps.indices(), ps.uvs(), ps.material(),
                        ps.blas().accel, ps.triBase(), ps.sx(), ps.sy(), ps.sz());
                g.destroy();
            }
            for (SectionGeom g : pending.removed()) {
                g.destroy();
            }
            pending = null;
        }
        for (Deferred d : deferred) {
            d.free().run();
        }
        deferred.clear();
        if (sectionTable != null) {
            sectionTable.destroy();
            sectionTable = null;
        }
        sectionTableCapacity = 0;
        nextSectionSlot = 0;
        freeSectionSlots.clear();
        sectionSlots.clear();
        staticInstanceList.clear();
        for (SectionGeom g : resident.values()) {
            g.destroy();
        }
        resident.clear();
        empty.clear();
        staticInstances = null;
        published.clear();
        // The accumulators can hold evicted-but-not-yet-retired geometry (window sync fills `removed`
        // between streaming passes) and uploaded-but-unbuilt sections; the GPU is idle here, free them.
        for (SectionGeom g : removed) {
            g.destroy();
        }
        removed.clear();
        for (PreparedSection ps : prepared) {
            destroyPreparedSection(ps);
        }
        prepared.clear();
        if (shutdown) {
            ready = false;
        } else {
            ensureEmptyTableReady(ctx);
        }
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
    static long sectionKey(int scx, int scy, int scz) {
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
        final int[] triBase;  // per-fixed-bucket triangle offset; hit shaders add triBase[gl_GeometryIndexEXT] to pid
        final int sx;
        final int sy;
        final int sz;
        int slot = -1;
        int instanceIndex = -1;

        SectionGeom(long key, RtBuffer positions, RtBuffer indices, RtBuffer uvs, RtBuffer material,
                    RtAccel blas, int[] triBase, int sx, int sy, int sz) {
            this.key = key;
            this.positions = positions;
            this.indices = indices;
            this.uvs = uvs;
            this.material = material;
            this.blas = blas;
            this.triBase = triBase;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
        }

        void destroy() {
            blas.destroy();
            material.destroy();
            uvs.destroy();
            indices.destroy();
            positions.destroy();
        }
    }

    /**
     * Transient CPU accumulator for one section's quads while tessellating. Split into per-material geometry
     * buckets so the BLAS can flag solid blocks {@code VK_GEOMETRY_OPAQUE_BIT}, keep true alpha cutout in
     * an any-hit bucket, and route translucent/water through closest-hit-only records for radiance but
     * any-hit records for shadow tint/pass-through. The buckets are concatenated in {@code BUCKET_*} order
     * into the packed section buffers at upload, so each geometry's triangles occupy a contiguous range.
     */
    private static final class SectionMesh {
        // Conservative worker-side starting capacities. These trade a little transient RAM for avoiding the
        // repeated grow/copy ladder on normal terrain sections.
        private static final int OPAQUE_TRI_CAP = 768;
        private static final int CUTOUT_TRI_CAP = 256;
        private static final int TRANSLUCENT_TRI_CAP = 64;
        private static final int WATER_TRI_CAP = 128;
        // One bucket per fixed RtAccel terrain geometry: solid, cutout, translucent, water.
        private static final Geom EMPTY_GEOM = new Geom(0);
        Geom opaque;
        Geom cutout;
        Geom translucent;
        Geom water;
        private final Geom[] buckets = new Geom[RtAccel.TERRAIN_BUCKETS];

        Geom[] buckets() {
            buckets[RtAccel.BUCKET_SOLID] = geomOrEmpty(opaque);
            buckets[RtAccel.BUCKET_CUTOUT] = geomOrEmpty(cutout);
            buckets[RtAccel.BUCKET_TRANSLUCENT] = geomOrEmpty(translucent);
            buckets[RtAccel.BUCKET_WATER] = geomOrEmpty(water);
            return buckets;
        }

        Geom cutoutOrEmpty() {
            return geomOrEmpty(cutout);
        }

        Geom opaque() {
            return opaque != null ? opaque : (opaque = new Geom(OPAQUE_TRI_CAP));
        }

        Geom cutout() {
            return cutout != null ? cutout : (cutout = new Geom(CUTOUT_TRI_CAP));
        }

        Geom translucent() {
            return translucent != null ? translucent : (translucent = new Geom(TRANSLUCENT_TRI_CAP));
        }

        Geom water() {
            return water != null ? water : (water = new Geom(WATER_TRI_CAP));
        }

        private static Geom geomOrEmpty(Geom geom) {
            return geom != null ? geom : EMPTY_GEOM;
        }

        boolean isEmpty() {
            return (opaque == null || opaque.idx.isEmpty())
                    && (cutout == null || cutout.idx.isEmpty())
                    && (translucent == null || translucent.idx.isEmpty())
                    && (water == null || water.idx.isEmpty());
        }

        /** Empty the buckets keeping their backing arrays — the mesh is reused across jobs per worker thread. */
        void reset() {
            resetGeom(opaque);
            resetGeom(cutout);
            resetGeom(translucent);
            resetGeom(water);
        }

        private static void resetGeom(Geom geom) {
            if (geom != null) {
                geom.reset();
            }
        }
    }

    /** One geometry bucket's packed, section-local mesh data. */
    private static final class Geom {
        final FloatArrayList verts;
        final IntArrayList idx;
        // Lever B: per-triangle corner UVs in primitive order — 6 floats/triangle (3 corners x u,v),
        // aligned with `idx`'s triangle order so the hit shader reads cornerUv[3*pid + k] directly with no
        // index->vertex-UV gather. The index buffer is still emitted (above) for the BLAS build.
        final FloatArrayList cornerUv;
        // 12 floats/triangle: normal.xyz+emission, tint.rgb+material, mat.{rough,metal,hasS,hasN} —
        // except TRANSLUCENT triangles, whose mat lane instead holds a precomputed sprite avg {r,g,b,a}
        // (see emit()/translucentAvgColor).
        final FloatArrayList prim;
        // One sprite per prim record (per triangle), aligned with `prim`. Resolved on the render thread
        // because RtBlockMaterials.ensure can lazy-load material maps.
        final SpriteList materialSprites;
        // One sprite per triangle for opacity micromap classification.
        final SpriteList ommSprites;

        Geom(int triCapacity) {
            int cap = Math.max(2, triCapacity);
            int quadCapacity = (cap + 1) >>> 1;
            verts = new FloatArrayList(quadCapacity * 12); // 4 xyz vertices per quad
            idx = new IntArrayList(cap * 3);
            cornerUv = new FloatArrayList(cap * 6);
            prim = new FloatArrayList(cap * 12);
            materialSprites = new SpriteList(cap);
            ommSprites = new SpriteList(cap);
        }

        int triCount() {
            return idx.size() / 3;
        }

        void reset() {
            verts.clear();       // fastutil clear() keeps the backing array
            idx.clear();
            cornerUv.clear();
            prim.clear();
            materialSprites.clear();
            ommSprites.clear();
        }
    }

    /** Minimal growable sprite array for the worker path; avoids ArrayList object churn and per-copy gets. */
    private static final class SpriteList {
        private TextureAtlasSprite[] elements;
        private int size;

        SpriteList(int capacity) {
            elements = new TextureAtlasSprite[Math.max(2, capacity)];
        }

        void add(TextureAtlasSprite sprite) {
            if (size == elements.length) {
                TextureAtlasSprite[] grown = new TextureAtlasSprite[elements.length + (elements.length >>> 1)];
                System.arraycopy(elements, 0, grown, 0, elements.length);
                elements = grown;
            }
            elements[size++] = sprite;
        }

        TextureAtlasSprite[] elements() {
            return elements;
        }

        int size() {
            return size;
        }

        void copyInto(TextureAtlasSprite[] dst, int offset) {
            System.arraycopy(elements, 0, dst, offset, size);
        }

        void clear() {
            java.util.Arrays.fill(elements, 0, size, null); // don't retain sprites across atlas reloads
            size = 0;
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
        private static final float TRANSLUCENT_INSET = 2.0e-4f; // inward recess (blocks) for glass/ice vs coplanar neighbours
        private static final float COINCIDENT_EPS = 1.0e-4f; // verts this close are "the same" point
        private static final int RESOLVE_CAP = 128;          // skip the O(n^2) resolve for pathological blocks
        private final List<PendingQuad> pending = new ArrayList<>(8);
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
            // TRANSLUCENT (stained glass, ice, honey, slime, nether portal): a colored-transmission
            // dielectric resolved in the path tracer (tint.w == 2), excluded from the binary alpha cutout.
            q.translucent = quad.materialInfo().layer() == ChunkSectionLayer.TRANSLUCENT;

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
            // TRANSLUCENT prims repurpose the mat lane for a precomputed avg color/alpha (emit(), for
            // world.rahit's shadow path) instead of LabPBR hasS/hasN flags, so keep materialSprite null —
            // resolveMaterials() already skips null entries, which avoids it clobbering that lane.
            q.materialSprite = q.translucent ? null : sprite;
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
            // Recess translucent (glass / ice) faces slightly into their own block. Vanilla culls a glass
            // face that touches a full solid block, but KEEPS the one touching a non-occluding neighbour
            // (slabs / stairs) — which lands exactly coplanar with that neighbour's face and z-fights. A tiny
            // inward inset makes the glass resolve consistently behind the neighbour's surface.
            if (q.translucent) {
                offset(q, -TRANSLUCENT_INSET);
            }
            Geom g = q.translucent ? cur.translucent() : (q.cutout ? cur.cutout() : cur.opaque());
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
            // TRANSLUCENT prims never read mat.{rough,metal,hasS,hasN} in either hit shader (world.rchit's
            // stained-glass/ice early return hardcodes roughness/metalness and never checks hasS/hasN), so
            // the mat lane is repurposed to carry this sprite's precomputed whole-texture average {r,g,b,a}
            // instead — world.rahit's shadow path reads it rather than sampling blockAtlas per-hit. Biome
            // tint is pre-multiplied into the stored rgb here (a per-quad CPU-side multiply) so that shadow
            // path never needs pr.tint.rgb at all — just the one 16-byte mat lane, not a second scattered
            // load elsewhere in the 48-byte Prim record. rchit's radiance path still reads the real,
            // un-multiplied tint.rgb from the prim's own tint lane (written below as usual) for its
            // per-texel shading, so this doesn't affect how glass looks head-on.
            float[] avgColor = q.translucent ? translucentAvgColor(q.sprite) : null;
            for (int t = 0; t < 2; t++) { // one {normal+emission, tint, mat} record per triangle
                prim.add(q.nx);
                prim.add(q.ny);
                prim.add(q.nz);
                // normal.w = block-light emission (0..1) + a +2 flag for non-SOLID layers, so the closest
                // hit can opt SOLID terrain out of SSS (leaves/foliage keep it). See world.rchit.
                prim.add(q.cutout ? q.emission + 2f : q.emission);
                prim.add(q.tr);
                prim.add(q.tg);
                prim.add(q.tb);
                prim.add(q.translucent ? 2f : 0f); // tint.w material flag: 2 = stained glass / ice (0 opaque)
                if (avgColor != null) {
                    prim.add(avgColor[0] * q.tr);
                    prim.add(avgColor[1] * q.tg);
                    prim.add(avgColor[2] * q.tb);
                    prim.add(avgColor[3]);
                } else {
                    prim.add(q.rough);
                    prim.add(q.metal);
                    prim.add(0f); // hasS placeholder; patched by resolveMaterials()
                    prim.add(0f); // hasN placeholder
                }
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
        boolean translucent; // TRANSLUCENT layer (stained glass / ice): colored-transmission dielectric
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

        /** Reset per-job assembly state (a mid-quad meshing throw could leave a partial quad buffered). */
        void reset() {
            n = 0;
        }

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
            Geom g = water ? cur.water() : cur.opaque();
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
