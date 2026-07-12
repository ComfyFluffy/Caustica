

package dev.comfyfluffy.caustica.rt.terrain;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.mixin.SpriteContentsAccessor;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static dev.comfyfluffy.caustica.rt.terrain.RtTerrainMesher.WORKER_TESS;
import static dev.comfyfluffy.caustica.rt.terrain.RtTerrainMesher.buildCpuSection;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrainMesher.CpuSection;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrainMesher.PackedSection;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrainMesher.WorkerTessState;
import dev.comfyfluffy.caustica.rt.terrain.RtSectionBuilder.PreparedSection;
import dev.comfyfluffy.caustica.rt.terrain.RtSectionTable.Generation;
import dev.comfyfluffy.caustica.rt.terrain.RtSectionTable.SectionGeom;
/**
 * Per-section terrain residency synced to vanilla's loaded chunks. A singleton manager
 * keeps a map of resident 16³ sections. The 20 TPS tick maintains the desired window around the player
 * (slid incrementally on section-boundary crossings) and drains dirty events; the actual streaming —
 * snapshot dispatch, completion drain, and publish — runs once per render frame from
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
 * {@link RtWorkerPool}; snapshotting and publication stay on the render thread, while workers own GPU
 * buffer allocation/fill, OMM/BLAS preparation, and enqueue onto the single-owner GPU executor. Frees
 * are retired against graphics timeline completion (no {@code waitIdle} on the hot path).
 */
public final class RtTerrain {
    // The render thread snapshots and publishes; workers mesh, allocate/fill, prepare BLAS/OMM objects,
    // and enqueue builds. The streaming pass is bounded so render-thread bookkeeping stays flat.
    private static int asyncDispatchPerPass() {
        return CausticaConfig.Rt.Terrain.ASYNC_DISPATCH_PER_PASS.value();
    }

    private static int completionResultsPerPass() {
        return CausticaConfig.Rt.Terrain.COMPLETION_RESULTS_PER_PASS.value();
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

    private static long maxInflightNativeBytes() {
        return (long) CausticaConfig.Rt.Terrain.MAX_INFLIGHT_NATIVE_MB.value() << 20;
    }

    // Conservative admission reservation covering mesh buffers, AS/OMM backing, build inputs, and scratch.
    // Together with the executor's 32-build cap this bounds one GPU batch to at most 512 MiB reserved.
    private static final long NATIVE_RESERVATION_BYTES = 16L << 20;

    private static final int SECTION_ENTRY_BYTES = 32; // {u64 primAddr, u64 uvAddr, u32 triBase[4]}
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
    // Publish accumulators: window sync (tick) and completion drain (streaming pass) may run on different
    // frames, so evicted geometry waits here until the next publish pass retires it.
    private final List<SectionGeom> removed = new ArrayList<>();
    private final List<PreparedSection> prepared = new ArrayList<>();
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
    private long inFlightNativeBytes;
    private final RtSectionTable table = new RtSectionTable();
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
        return table.instances;
    }

    /** Section table device address: {@code {u64 primAddr, u64 uvAddr, u32 triBase[4]}} per section, indexed by gl_InstanceCustomIndexEXT. */
    public long tableAddress() {
        return table.address();
    }

    /** Per-tick residency update: window sync + dirty drain (plus the streaming fallback, see {@link #frame}). */
    public static void update(RtContext ctx) {
        INSTANCE.tick(ctx);
    }

    /**
     * Per-render-frame streaming pass, driven by {@link RtComposite#composite}: publish completed builds
     * and dispatch immutable snapshots to workers — all bounded by
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
        stream(ctx, dynamicStreamBudgetNanos());
    }

    /**
     * One streaming pass: drain completed worker/executor builds, publish ready sections, and dispatch
     * new section snapshots to the worker pool — stopping
     * dispatch/drain once {@code budgetNanos} of wall clock is spent. Skips silently when there is
     * nothing to do (no stats row).
     */
    private void stream(RtContext ctx, long budgetNanos) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        if (playerReextract.isEmpty() && reextract.isEmpty() && missing.isEmpty()
                && completedPlayerJobs.isEmpty() && completedDirtyJobs.isEmpty() && completedMissingJobs.isEmpty()
                && removed.isEmpty() && prepared.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + budgetNanos;
        int pbx = mc.player.getBlockX();
        int pby = mc.player.getBlockY();
        int pbz = mc.player.getBlockZ();
        int pcx = pbx >> 4, pcz = pbz >> 4, psy = pby >> 4;

        ClientChunkCache chunkSource = level.getChunkSource();

        // Drain completed GPU builds first — publication is visible fill progress, so it gets priority.
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.drainCompletion")) {
            drainTessellation(ctx, prepared, removed, completionResultsPerPass(), deadline);
        }

        // Snapshot and dispatch new worker-owned section builds with the remaining budget.
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.snapshotDispatch")) {
            DispatchContext dispatch = null;
            int countSlots = Math.max(0, maxInflight() - inFlight.size());
            int nativeSlots = (int) Math.max(0L,
                    (maxInflightNativeBytes() - inFlightNativeBytes) / NATIVE_RESERVATION_BYTES);
            int dispatchSlots = Math.min(asyncDispatchPerPass(), Math.min(countSlots, nativeSlots));
            if (dispatchSlots > 0 && !playerReextract.isEmpty() && System.nanoTime() < deadline) {
                dispatch = dispatchContext(ctx, level);
                dispatchSlots -= dispatchReextract(dispatch, chunkSource, dispatchSlots, deadline,
                        playerReextract, queuedPlayerReextract, PRIORITY_PLAYER);
            }
            if (dispatchSlots > 0 && !reextract.isEmpty() && System.nanoTime() < deadline) {
                if (dispatch == null) {
                    dispatch = dispatchContext(ctx, level);
                }
                dispatchSlots -= dispatchReextract(dispatch, chunkSource, dispatchSlots, deadline,
                        reextract, queuedReextract, PRIORITY_DIRTY);
            }
            if (dispatchSlots > 0 && !missing.isEmpty() && System.nanoTime() < deadline) {
                if (dispatch == null) {
                    dispatch = dispatchContext(ctx, level);
                }
                dispatchTessellation(dispatch, chunkSource, dispatchSlots, deadline, pcx, psy, pcz);
            }
        }

        if (!removed.isEmpty() || !prepared.isEmpty()) {
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.publish")) {
                applyBuildChanges(ctx, prepared, removed, shouldRebase(pbx, pby, pbz), pbx, pby, pbz);
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
     * Snapshot each missing section on the render thread and submit its tessellation to the worker
     * pool. The per-task meshing objects (renderer / captures / MutableBlockPos) are allocated inside the
     * job so nothing mutable is shared across threads; the captured {@code region}, model sets and block
     * colors are read-only. Capped by the configured dispatch budget.
     */
    private static DispatchContext dispatchContext(RtContext ctx, ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        return new DispatchContext(ctx, level,
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
     * with a gap — when the new mesh is published and the replaced geometry is retired. This
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
        inFlightNativeBytes += job.nativeReservationBytes;
        Future<?> future;
        try {
            future = RtWorkerPool.INSTANCE.submit(() -> {
                try {
                    WorkerTessState ws = WORKER_TESS.get(); // thread-confined; reset per job, arrays amortized
                    ws.reset(dispatch.blockColors());
                    ModelBlockRenderer renderer = new ModelBlockRenderer(false, true, dispatch.blockColors());
                    FluidRenderer fluidRenderer = new FluidRenderer(dispatch.fluidModelSet());
                    CpuSection cpu = buildCpuSection(region, dispatch.modelSet(), renderer, ws.capture,
                            fluidRenderer, ws.fluidCapture, ws.mesh, ws.pos, sx, sy, sz);
                    PackedSection packed = cpu.packed();
                    if (packed == null) {
                        enqueueCompleted(job, null, null, null);
                    } else {
                        PreparedSection prepared = RtSectionBuilder.prepare(dispatch.ctx(), packed,
                                cpu.opacityMicromap(), job.key, job.sox, job.soy, job.soz);
                        RtGpuExecutor.Build build;
                        try {
                            build = dispatch.ctx().gpuExecutor().submit(
                                    cmd -> RtAccel.recordBlasBuilds(dispatch.ctx(), cmd, List.of(prepared.blas())),
                                    () -> RtAccel.freeBlasScratch(List.of(prepared.blas())));
                        } catch (Throwable t) {
                            RtSectionBuilder.destroy(prepared);
                            throw t;
                        }
                        enqueueCompleted(job, prepared, build, null);
                    }
                } catch (Throwable t) {
                    enqueueCompleted(job, null, null, t);
                    throw t;
                }
                return null;
            });
        } catch (Throwable t) {
            releaseJobReservation(job);
            throw t;
        }
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

    private void enqueueCompleted(TessJob job, PreparedSection prepared, RtGpuExecutor.Build build, Throwable failure) {
        enqueueCompleted(new TessResult(job, prepared, build, failure));
    }

    private void enqueueCompleted(TessResult result) {
        TessJob job = result.job();
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
     * Publish finished worker/executor results (up to the configured result budget per tick). A job
     * whose token no longer matches {@link #inFlight} is stale and its unpublished native result is
     * destroyed instead of entering the table.
     */
    private void drainTessellation(RtContext ctx, List<PreparedSection> prepared, List<SectionGeom> removed,
                                   int resultCap, long deadline) {
        int budget = resultCap;
        int attempts = resultCap * 4;
        boolean first = true;
        while (budget > 0 && attempts-- > 0) {
            // Always take at least one result so publication progresses even when dispatch ate the whole
            // budget (inFlight backpressure then shifts the budget to draining on the next frames).
            if (!first && System.nanoTime() >= deadline) {
                break;
            }
            first = false;
            TessResult result = pollCompleted();
            if (result == null) {
                break;
            }
            if (result.build() != null && !ctx.gpuExecutor().isDone(result.build())) {
                enqueueCompleted(result);
                continue;
            }
            TessJob job = result.job();
            removeJob(job);
            releaseJobReservation(job);
            long expected = inFlight.get(job.key);
            boolean valid = expected == job.token;
            if (!valid) {
                destroyCompletedResult(ctx, result);
                continue; // stale result; a newer dispatch (or none) supersedes it
            }
            inFlight.remove(job.key);
            long dirtyGroup = inFlightDirtyGroup.remove(job.key);
            if (result.failure() != null) {
                if (dirtyGroup != NO_DIRTY_GROUP) {
                    cancelDirtyGroup(dirtyGroup);
                }
                throw new RuntimeException("RT terrain tessellation failed for section "
                        + (job.sox >> 4) + "," + (job.soy >> 4) + "," + (job.soz >> 4),
                        result.failure());
            }
            PreparedSection built = result.prepared();
            if (built != null) {
                try {
                    ctx.gpuExecutor().free(result.build());
                    RtSectionBuilder.resolveMaterials(built);
                    ctx.gpuExecutor().markPublished(result.build());
                    RtFrameStats.FRAME.count("terrainBuildsCompleted", 1);
                } catch (Throwable t) {
                    RtSectionBuilder.destroy(built);
                    if (dirtyGroup != NO_DIRTY_GROUP) {
                        cancelDirtyGroup(dirtyGroup);
                    }
                    throw new RuntimeException("RT terrain GPU build failed for section "
                            + (job.sox >> 4) + "," + (job.soy >> 4) + "," + (job.soz >> 4), t);
                }
            }
            if (dirtyGroup != NO_DIRTY_GROUP && dirtyGroups.containsKey(dirtyGroup)) {
                DirtyGroup group = dirtyGroups.get(dirtyGroup);
                if (built == null) {
                    SectionGeom prev = resident.get(job.key);
                    if (prev != null) {
                        group.removed.add(prev);
                    } else {
                        group.restoreEmptyKeys.add(job.key);
                    }
                    group.emptyKeys.add(job.key);
                } else {
                    empty.remove(job.key);
                    group.prepared.add(built);
                }
                completeDirtyGroupMember(group, prepared, removed);
                budget--;
            } else {
                if (built == null) {
                    // Legitimately empty (air or fully-enclosed). If this was an in-place re-extract whose new
                    // state is empty, evict the old geom and retire it in this publish pass.
                    SectionGeom prev = resident.remove(job.key);
                    if (prev != null) {
                        removed.add(prev);
                    }
                    empty.add(job.key);
                    budget--;
                } else {
                    empty.remove(job.key);
                    prepared.add(built);
                    budget--;
                }
            }
        }
    }

    private void destroyCompletedResult(RtContext ctx, TessResult result) {
        if (result.prepared() == null) {
            return;
        }
        ctx.gpuExecutor().free(result.build());
        ctx.gpuExecutor().enqueueDestroyUnpublished(() -> RtSectionBuilder.destroy(result.prepared()));
    }

    private void releaseJobReservation(TessJob job) {
        if (job.nativeReservationBytes == 0L) {
            return;
        }
        inFlightNativeBytes -= job.nativeReservationBytes;
        if (inFlightNativeBytes < 0L) {
            throw new IllegalStateException("RT terrain native reservation underflow");
        }
        job.nativeReservationBytes = 0L;
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
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            RtSectionBuilder.destroy(ps);
        } else {
            ctx.gpuExecutor().enqueueDestroyUnpublished(() -> RtSectionBuilder.destroy(ps));
        }
    }

    /** Per-tick render-thread snapshot dependencies shared by reextract + missing dispatch. */
    private record DispatchContext(RtContext ctx, ClientLevel level, BlockStateModelSet modelSet,
                                   FluidStateModelSet fluidModelSet, BlockColors blockColors) {
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
        long nativeReservationBytes = NATIVE_RESERVATION_BYTES;

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

    private record TessResult(TessJob job, PreparedSection prepared, RtGpuExecutor.Build build, Throwable failure) {
    }

    private boolean shouldRebase(int rbx, int rby, int rbz) {
        return !ready || table.buffer == null || table.instances == null
                || Math.abs(rbx - blockX) > rebaseDistanceBlocks()
                || Math.abs(rby - blockY) > rebaseDistanceBlocks()
                || Math.abs(rbz - blockZ) > rebaseDistanceBlocks();
    }

    private void applyBuildChanges(RtContext ctx, List<PreparedSection> prepared, List<SectionGeom> removed,
                                   boolean rebase, int rbx, int rby, int rbz) {
        long lastGraphicsUse = ctx.gpuExecutor().latestGraphicsUseValue();
        int baseX = rebase ? rbx : blockX;
        int baseY = rebase ? rby : blockY;
        int baseZ = rebase ? rbz : blockZ;

        for (SectionGeom g : removed) {
            table.removePublished(resident, published, g);
        }
        retire(ctx, lastGraphicsUse, removed);


        if (!prepared.isEmpty()) {
            Generation oldGeneration = table.beginWriteGeneration(ctx, table.liveSlotCapacity(prepared, resident));
            if (oldGeneration != null) {
                retireGeneration(ctx, lastGraphicsUse, oldGeneration);

            }
        }

        for (PreparedSection ps : prepared) {
            SectionGeom g = new SectionGeom(ps.key(), ps.positions(), ps.indices(), ps.uvs(), ps.material(),
                    ps.blas().accel, ps.triBase(), ps.sx(), ps.sy(), ps.sz());
            if (!desired.contains(ps.key())) {
                // Left the window while its batched BLAS build was in flight (window sync keeps running
                // during builds). Never published — retire the fresh, unreferenced geometry.
                ctx.gpuExecutor().enqueueDestroyUnpublished(g::destroy);
                continue;
            }
            SectionGeom prev = resident.get(ps.key());
            if (prev != null && prev.slot >= 0) {
                g.slot = prev.slot;
                g.instanceIndex = prev.instanceIndex;
                table.slots.set(g.slot, g);
                resident.put(ps.key(), g);
                table.write(g);
                table.instanceList.set(g.instanceIndex, table.instanceFor(g, baseX, baseY, baseZ));
                retire(ctx, lastGraphicsUse, List.of(prev));
            } else {
                g.slot = table.allocateSlot();
                g.instanceIndex = table.instanceList.size();
                table.slots.set(g.slot, g);
                resident.put(ps.key(), g);
                table.write(g);
                table.instanceList.add(table.instanceFor(g, baseX, baseY, baseZ));
            }
            published.add(ps.key());
        }
        table.flushWrites();

        if (resident.isEmpty()) {
            Generation emptyGeneration = table.detachGeneration();
            if (emptyGeneration != null) {
                retireGeneration(ctx, lastGraphicsUse, emptyGeneration);
            }
            table.nextSlot = 0;
            table.freeSlots.clear();
            table.slots.clear();
            table.instanceList.clear();
            table.instances = null;
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
            for (int i = 0, n = table.instanceList.size(); i < n; i++) {
                RtAccel.Instance inst = table.instanceList.get(i);
                SectionGeom g = table.slots.get(inst.customIndex());
                table.instanceList.set(i, table.instanceFor(g, baseX, baseY, baseZ));
            }
            blockX = rbx;
            blockY = rby;
            blockZ = rbz;
        }
        table.instances = table.instanceList;
        ready = true;
    }

    /** Keep a valid zero-instance table through transient empty-residency windows. */
    private void ensureEmptyTableReady(RtContext ctx) {
        table.ensureEmpty(ctx);
        ready = true;
    }

    /** Queue old GPU resources until the last graphics submission that could reference them completes. */
    private void retire(RtContext ctx, long lastGraphicsUse, List<SectionGeom> removed) {
        for (SectionGeom g : removed) {
            ctx.gpuExecutor().enqueueDestroyAfterGraphics(lastGraphicsUse, g::destroy);
        }
    }

    private void retireGeneration(RtContext ctx, long lastGraphicsUse, Generation generation) {
        ctx.gpuExecutor().enqueueDestroyAfterGraphics(lastGraphicsUse,
                () -> table.recycleGeneration(generation));
    }

    /** Join outstanding worker/build jobs and destroy every unpublished native result. */
    private void cancelJobs(RtContext ctx) {
        if (jobs.isEmpty() && inFlight.isEmpty()) {
            inFlightDirtyGroup.clear();
            return;
        }
        Throwable failure = null;
        for (TessJob job : jobs) {
            if (job.future != null) {
                try {
                    job.future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while joining RT terrain workers", e);
                } catch (ExecutionException e) {
                    if (failure == null) {
                        failure = e.getCause();
                    }
                }
            }
            job.jobIndex = -1;
        }
        jobs.clear();
        TessResult result;
        while ((result = pollCompleted()) != null) {
            releaseJobReservation(result.job());
            if (result.prepared() != null) {
                PreparedSection unpublished = result.prepared();
                try {
                    ctx.gpuExecutor().free(result.build());
                } catch (Throwable t) {
                    if (failure == null) {
                        failure = t;
                    }
                }
                ctx.gpuExecutor().enqueueDestroyUnpublished(() -> RtSectionBuilder.destroy(unpublished));
            }
            if (result.failure() != null && failure == null) {
                failure = result.failure();
            }
        }
        inFlight.clear();
        inFlightDirtyGroup.clear();
        if (inFlightNativeBytes != 0L) {
            throw new IllegalStateException("RT terrain native reservations remain after worker join: "
                    + inFlightNativeBytes);
        }
        if (failure != null) {
            throw new RuntimeException("RT terrain worker/build failed during teardown", failure);
        }
    }

    /** Full teardown (world exit / shutdown): drain the GPU, then free everything incl. an in-flight build. */
    private void clear(RtContext ctx, boolean shutdown) {
        cancelJobs(ctx);
        cancelAllDirtyGroups();
        if (shutdown) {
            ctx.waitIdle();
            ctx.gpuExecutor().flushDestroysAfterDeviceIdle();
        } else {
            ctx.gpuExecutor().waitForLatestGraphicsAndFlush();
        }
        table.destroyRecycledGenerations();
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
        if (resident.isEmpty() && table.buffer == null && removed.isEmpty() && prepared.isEmpty()) {
            empty.clear();
            table.instances = null;
            published.clear();
            table.capacity = 0;
            table.nextSlot = 0;
            table.freeSlots.clear();
            table.slots.clear();
            table.instanceList.clear();
            removed.clear();
            prepared.clear();
            if (shutdown) {
                ready = false;
            } else {
                ensureEmptyTableReady(ctx);
            }
            return;
        }
        Generation currentGeneration = table.detachGeneration();
        if (currentGeneration != null) {
            currentGeneration.buffer().destroy();
        }
        table.capacity = 0;
        table.nextSlot = 0;
        table.freeSlots.clear();
        table.slots.clear();
        table.instanceList.clear();
        for (SectionGeom g : resident.values()) {
            g.destroy();
        }
        resident.clear();
        empty.clear();
        table.instances = null;
        published.clear();
        // The accumulators can hold evicted-but-not-yet-retired geometry (window sync fills `removed`
        // between streaming passes) and built-but-not-yet-published sections; the GPU is idle here, free them.
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

}
