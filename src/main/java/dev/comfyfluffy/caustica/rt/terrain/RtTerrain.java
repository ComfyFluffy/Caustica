

package dev.comfyfluffy.caustica.rt.terrain;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
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
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.lwjgl.system.MemoryStack;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

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
 * {@link RtComposite} with count-bounded completion and dispatch passes rather than a per-tick burst.
 * Residency follows vanilla because a section is only "desired" when its
 * chunk is loaded ({@code hasChunk}), so chunk load/unload drives build/free without any mixin.
 *
 * <p>Geometry comes from the Fabric Renderer API model path (correct shapes, neighbour cull, biome
 * tint, alpha cutout, and model quad transforms). Vertices are section-local (f32-exact); each TLAS
 * instance carries a
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

    // Backpressure cap: stop dispatching once this many sections are in flight. Bounds queue depth and
    // snapshot memory (each in-flight region pins 27 cached section snapshots) when flying through the world.
    private static int maxInflight() {
        return CausticaConfig.Rt.Terrain.MAX_INFLIGHT_SECTIONS.value();
    }

    private static final int SECTION_ENTRY_BYTES = 32; // {u64 primAddr, u64 uvAddr, u32 triBase[4]}
    private static final long NO_TESS_TOKEN = Long.MIN_VALUE;
    private static final int NO_MISSING_INDEX = -1;
    private static final long NO_DIRTY_GROUP = 0L;
    // If no render frame has driven a streaming pass for this long, the 20 TPS tick takes over (loading
    // screens / hidden window — states where render-driven streaming has stopped).
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
    private final Long2IntOpenHashMap missingIndex = new Long2IntOpenHashMap();
    private final Long2LongOpenHashMap queuedDirtyGroup = new Long2LongOpenHashMap();
    private final LongArrayList reextract = new LongArrayList();
    private final LongOpenHashSet queuedReextract = new LongOpenHashSet();
    // Publish accumulators: window sync (tick) and completion drain (streaming pass) may run on different
    // frames, so evicted geometry waits here until the next publish pass retires it.
    private final List<SectionGeom> removed = new ArrayList<>();
    private final List<PreparedSection> prepared = new ArrayList<>();
    // Worker/build bookkeeping. `inFlight` maps a dispatched section key to a monotonic token; a completed
    // task whose token no longer matches is discarded. The active-task barrier spans worker + GPU lifetime.
    private final Long2LongOpenHashMap inFlight = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap inFlightDirtyGroup = new Long2LongOpenHashMap();
    private final Long2ObjectOpenHashMap<DirtyGroup> dirtyGroups = new Long2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<SectionResult> completedBuilds = new ConcurrentLinkedQueue<>();
    private final Object activeTaskLock = new Object();
    private int activeTasks;
    /** Invalidates all worker/GPU work from a detached world residency without joining it. */
    private volatile long terrainEpoch = 1L;
    private long buildToken;
    private long dirtyGroupSeq;
    private final RtSectionTable table = new RtSectionTable();
    private boolean ready;
    // Full-residency invalidation requested off the render thread. Wired to Fabric's
    // InvalidateRenderStateCallback = vanilla LevelExtractor.allChanged() (dimension change via setLevel,
    // render-distance change, F3+A). Consumed in tick(), where the RT context is available.
    private volatile boolean fullClearRequested;
    private volatile boolean dirtyPending;
    private boolean noWorldClearApplied;
    // Rebase origin (player block at the last TLAS rebuild) for the instance transforms + ray camOffset.
    public int blockX;
    public int blockY;
    public int blockZ;
    // ReSTIR DI global light buffer: the published sections' collected lights flattened into rebased
    // world space (RESTIR_PLAN.md S0). Rebuilt only when the published set or the rebase origin changes,
    // in the same publish step as the instance list — a light must never reference a section that is not
    // in this frame's TLAS (its shadow ray would find no occluder: a ghost emitter).
    private RtBuffer lightBuffer;
    /** Vose alias table for power-weighted O(1) light selection; published/retired with lightBuffer. */
    private RtBuffer lightAliasBuffer;
    /** Dense section-coordinate lookup and packed per-cell candidate indices for ReGIR. */
    private RtBuffer regirCellBuffer;
    private RtBuffer regirCandidateBuffer;
    private int regirOriginX, regirOriginY, regirOriginZ;
    private int regirDimX, regirDimY, regirDimZ;
    private int lightCount;
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
        missingIndex.defaultReturnValue(NO_MISSING_INDEX);
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

    /** ReSTIR DI global light buffer device address, or 0 while no lights are published. */
    public long lightBufferAddress() {
        return lightBuffer != null ? lightBuffer.deviceAddress : 0L;
    }

    /** Power-weighted light alias table device address, or 0 for the shader's uniform fallback. */
    public long lightAliasBufferAddress() {
        return lightAliasBuffer != null ? lightAliasBuffer.deviceAddress : 0L;
    }

    public long regirCellBufferAddress() {
        return regirCellBuffer != null ? regirCellBuffer.deviceAddress : 0L;
    }

    public long regirCandidateBufferAddress() {
        return regirCandidateBuffer != null ? regirCandidateBuffer.deviceAddress : 0L;
    }

    public int regirOriginX() {
        return regirOriginX;
    }

    public int regirOriginY() {
        return regirOriginY;
    }

    public int regirOriginZ() {
        return regirOriginZ;
    }

    public int regirDimX() {
        return regirDimX;
    }

    public int regirDimY() {
        return regirDimY;
    }

    public int regirDimZ() {
        return regirDimZ;
    }

    public int regirMaxCandidates() {
        return RtReGIR.MAX_CANDIDATES;
    }

    /** Number of compact 64-byte records in the published light buffer. */
    public int lightCount() {
        return lightBuffer != null ? lightCount : 0;
    }

    /** Per-tick residency update: window sync + dirty drain (plus the streaming fallback, see {@link #frame}). */
    public static void update(RtContext ctx) {
        INSTANCE.tick(ctx);
    }

    /**
     * Per-render-frame streaming pass, driven by {@link RtComposite#composite}: publish completed builds
     * and dispatch immutable snapshots to workers, bounded by configured per-pass counts.
     */
    public static void frame(RtContext ctx) {
        if (RtMaterialRegistry.INSTANCE.isReady()) INSTANCE.frameStream(ctx);
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
        RtTerrainOmm.clearCache();
        INSTANCE.fullClearRequested = true;
    }

    private void tick(RtContext ctx) {

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            if (!noWorldClearApplied) {
                clear(ctx, false);
                noWorldClearApplied = true;
            }
            return;
        }
        noWorldClearApplied = false;
        if (!RtMaterialRegistry.INSTANCE.isReady()) {
            return; // resource reload gap: keep old work dormant until the new epoch requests a full clear
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
                    handleDirtySection(key, NO_DIRTY_GROUP);
                }
            }
            if (!dirtyEventDrain.isEmpty()) {
                for (DirtyEvent event : dirtyEventDrain) {
                    handleDirtyEvent(event);
                }
            }
        }
        // Dispatch/drain/build normally runs per render frame (RtComposite → frame()). If no frame has
        // streamed recently — loading screen, no world rendering — drive it from here with the bigger
        // bounded fallback pass so the world still fills.
        if (System.nanoTime() - lastFrameStreamNanos > STREAM_FALLBACK_AFTER_NANOS) {
            stream(ctx);
        }
    }

    /** The per-render-frame entry point: run one count-bounded streaming pass. */
    private void frameStream(RtContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        lastFrameStreamNanos = System.nanoTime();
        stream(ctx);
    }

    /**
     * One streaming pass: drain completed worker/executor builds, publish ready sections, and dispatch
     * new section snapshots to the worker pool. Per-pass result and dispatch caps bound render-thread
     * work. Skips silently when there is nothing to do (no stats row).
     */
    private void stream(RtContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        if (reextract.isEmpty() && missing.isEmpty()
                && completedBuilds.isEmpty()
                && removed.isEmpty() && prepared.isEmpty()) {
            return;
        }
        int pbx = mc.player.getBlockX();
        int pby = mc.player.getBlockY();
        int pbz = mc.player.getBlockZ();
        int pcx = pbx >> 4, pcz = pbz >> 4, psy = pby >> 4;

        ClientChunkCache chunkSource = level.getChunkSource();

        // Drain completed GPU builds first — publication is visible fill progress, so it gets priority.
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.drainCompletion")) {
            drainCompletedBuilds(ctx, prepared, removed, completionResultsPerPass());
        }

        if (!removed.isEmpty() || !prepared.isEmpty()) {
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.publish")) {
                applyBuildChanges(ctx, prepared, removed, shouldRebase(pbx, pby, pbz), pbx, pby, pbz);
                removed.clear();
                prepared.clear();
            }
        }

        // Snapshot and dispatch a bounded number of new worker-owned section builds.
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.snapshotDispatch")) {
            DispatchContext dispatch = null;
            int dispatchSlots = Math.min(asyncDispatchPerPass(), Math.max(0, maxInflight() - inFlight.size()));
            if (dispatchSlots > 0 && !reextract.isEmpty()) {
                if (dispatch == null) {
                    dispatch = dispatchContext(ctx, level);
                }
                dispatchSlots -= dispatchReextract(dispatch, chunkSource, dispatchSlots, pcx, psy, pcz);
            }
            if (dispatchSlots > 0 && !missing.isEmpty()) {
                if (dispatch == null) {
                    dispatch = dispatchContext(ctx, level);
                }
                dispatchMissingBuilds(dispatch, chunkSource, dispatchSlots, pcx, psy, pcz);
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
        missingIndex.clear();

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

    private void handleDirtyEvent(DirtyEvent event) {
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
            if (!handleDirtySection(key, memberGroup) && memberGroup != NO_DIRTY_GROUP) {
                cancelDirtyGroup(memberGroup);
            }
        }
    }

    private boolean canGroupDirtySection(long key) {
        return desired.contains(key) && (resident.containsKey(key) || empty.contains(key));
    }

    private boolean handleDirtySection(long key, long dirtyGroup) {
        snapshots.invalidate(key); // block data changed — the cached palette snapshot is stale
        boolean wasEmpty = empty.remove(key);
        if (wasEmpty && dirtyGroup != NO_DIRTY_GROUP) {
            DirtyGroup group = dirtyGroups.get(dirtyGroup);
            if (group != null) {
                group.restoreEmptyKeys.add(key);
            }
        }
        invalidateInFlight(key); // invalidate any in-flight build of the now-stale section
        if (!desired.contains(key)) {
            clearQueuedWork(key, true);
            return false;
        }
        // Keep the old geometry resident + traced; re-dispatch and swap when the new mesh is ready
        // (no eviction gap -> no flicker). Non-resident dirty keys re-enter the normal missing queue.
        SectionGeom g = resident.get(key);
        if (g != null) {
            if (queuedReextract.add(key)) {
                reextract.add(key);
            }
            setQueuedGroup(key, dirtyGroup);
            return true;
        } else {
            return enqueueMissing(key, dirtyGroup);
        }
    }

    private boolean enqueueMissingIfNeeded(long key) {
        if (resident.containsKey(key) || empty.contains(key) || inFlight.containsKey(key)) {
            return false;
        }
        if (missingIndex.get(key) != NO_MISSING_INDEX) {
            return false;
        }
        setQueuedGroup(key, NO_DIRTY_GROUP);
        missingIndex.put(key, missing.size());
        missing.add(key);
        return true;
    }

    private boolean enqueueMissing(long key, long dirtyGroup) {
        if (resident.containsKey(key) || empty.contains(key) || inFlight.containsKey(key)) {
            return false;
        }
        // `missing` is unsorted; dispatch ranks it directly by distance from the player.
        int index = missingIndex.get(key);
        if (index == NO_MISSING_INDEX) {
            missingIndex.put(key, missing.size());
            missing.add(key);
        }
        setQueuedGroup(key, dirtyGroup);
        return true;
    }

    private void clearQueuedWork(long key, boolean cancelGroup) {
        removeMissing(key);
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
        return missingIndex.get(key) != NO_MISSING_INDEX
                || queuedReextract.contains(key);
    }

    /** Remove an unsorted missing entry in O(1) by moving the last entry into its slot. */
    private void removeMissing(long key) {
        int index = missingIndex.remove(key);
        if (index == NO_MISSING_INDEX) {
            return;
        }
        int lastIndex = missing.size() - 1;
        if (index != lastIndex) {
            long movedKey = missing.getLong(lastIndex);
            missing.set(index, movedKey);
            missingIndex.put(movedKey, index);
        }
        missing.removeLong(lastIndex);
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
     * Snapshot each missing section on the render thread and submit its build to the worker pool. The
     * per-task meshing objects (renderer / captures / MutableBlockPos) are allocated inside the task so
     * nothing mutable is shared across threads; the captured {@code region}, model sets and block
     * colors are read-only. Capped by the configured dispatch count.
     */
    private static DispatchContext dispatchContext(RtContext ctx, ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        return new DispatchContext(ctx, level,
                mc.getModelManager().getBlockStateModelSet(), mc.getModelManager().getFluidStateModelSet(),
                mc.getBlockColors(), mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).spriteFinder());
    }

    /**
     * Dispatch the best missing sections. {@code missing} is an indexed unsorted queue; every call scans
     * its contiguous key array while collecting the top candidates by
     * (column-distance², |Δy|) into a bounded
     * max-heap — nearest-first order continuously tracks the player with no sort anywhere (the old
     * sorted-queue approach re-sorted the whole list on every window rebuild, a multi-ms spike at high
     * render distance). Ranking by <b>column</b> first makes the dispatch order column-coherent: all
     * sections of a column share the same 3×3 chunk neighbourhood, and the pass-scoped
     * {@link RenderRegionCache} dedupes {@code SectionCopy}s, so after a column's first snapshot the rest
     * are nearly free.
     */
    private void dispatchMissingBuilds(DispatchContext dispatch, ClientChunkCache chunkSource, int remaining,
                                       int pcx, int psy, int pcz) {
        if (missing.isEmpty() || remaining <= 0) {
            return;
        }
        // Over-collect 2x the remaining slots so candidates skipped for unready neighbour chunks (they cluster at
        // the window edge) don't leave dispatch slots idle.
        int k = Math.min(missing.size(), Math.max(8, remaining * 2));

        long[] heapRank = new long[k];
        long[] heapKey = new long[k];
        int heapSize = 0;
        for (int read = 0, n = missing.size(); read < n; read++) {
            long key = missing.getLong(read);
            // rank = columnDist²(16+) | |Δy|(0..15): column-major nearest-first.
            long rank = distanceRank(key, pcx, psy, pcz);
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
        // Heapsort the candidates ascending (best first), then dispatch up to the per-pass cap.
        for (int end = heapSize - 1; end > 0; end--) {
            long r = heapRank[0]; heapRank[0] = heapRank[end]; heapRank[end] = r;
            long q = heapKey[0]; heapKey[0] = heapKey[end]; heapKey[end] = q;
            siftDown(heapRank, heapKey, end, 0);
        }
        for (int i = 0; i < heapSize && remaining > 0; i++) {
            long key = heapKey[i];
            if (!desired.contains(key) || resident.containsKey(key) || empty.contains(key) || inFlight.containsKey(key)) {
                removeMissing(key);
                clearQueuedGroup(key, true);
                continue;
            }
            int sx = sectionX(key);
            int sz = sectionZ(key);
            if (!neighborChunksReady(chunkSource, sx, sz)) {
                continue; // stays queued; dispatched once the neighbours load
            }
            removeMissing(key);
            remaining--;
            dispatchSectionBuild(dispatch, key, sx, sectionY(key), sz);
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
     * rebuild while leaving the old geometry resident and traced, so it's swapped — never evicted
     * with a gap — when the new mesh is published and the replaced geometry is retired. This
     * is what prevents the visible flicker on block updates that plain eviction would cause.
     */
    private int dispatchReextract(DispatchContext dispatch, ClientChunkCache chunkSource, int remaining,
                                  int pcx, int psy, int pcz) {
        if (reextract.isEmpty()) {
            return 0;
        }
        int k = Math.min(reextract.size(), Math.max(8, remaining * 2));
        long[] heapRank = new long[k];
        long[] heapKey = new long[k];
        int heapSize = 0;
        for (int i = 0; i < reextract.size(); ) {
            long key = reextract.getLong(i);
            if (!queuedReextract.contains(key)) {
                if (!isQueuedAnywhere(key)) {
                    clearQueuedGroup(key, true);
                }
                removeUnsorted(reextract, i);
                continue;
            }
            // Skip ones the window pass freed this tick (out of view) — they're being retired, not rebuilt.
            SectionGeom g = resident.get(key);
            if (g == null || !desired.contains(key) || inFlight.containsKey(key)) {
                queuedReextract.remove(key);
                clearQueuedGroup(key, true);
                removeUnsorted(reextract, i);
                continue;
            }
            int sx = g.sx >> 4;
            int sz = g.sz >> 4;
            if (!neighborChunksReady(chunkSource, sx, sz)) {
                i++;
                continue;
            }
            long rank = distanceRank(key, pcx, psy, pcz);
            if (heapSize < k) {
                heapRank[heapSize] = rank;
                heapKey[heapSize] = key;
                siftUp(heapRank, heapKey, heapSize++);
            } else if (rank < heapRank[0]) {
                heapRank[0] = rank;
                heapKey[0] = key;
                siftDown(heapRank, heapKey, heapSize, 0);
            }
            i++;
        }
        for (int end = heapSize - 1; end > 0; end--) {
            long r = heapRank[0]; heapRank[0] = heapRank[end]; heapRank[end] = r;
            long q = heapKey[0]; heapKey[0] = heapKey[end]; heapKey[end] = q;
            siftDown(heapRank, heapKey, end, 0);
        }
        int dispatched = 0;
        for (int i = 0; i < heapSize && remaining > 0; i++) {
            long key = heapKey[i];
            SectionGeom g = resident.get(key);
            queuedReextract.remove(key);
            removeUnsorted(reextract, reextract.indexOf(key));
            dispatchSectionBuild(dispatch, key, g.sx >> 4, g.sy >> 4, g.sz >> 4);
            remaining--;
            dispatched++;
        }
        return dispatched;
    }

    private static long distanceRank(long key, int pcx, int psy, int pcz) {
        int dx = sectionX(key) - pcx;
        int dz = sectionZ(key) - pcz;
        long colDist2 = (long) dx * dx + (long) dz * dz;
        long dy = Math.min(0xFFFF, Math.abs(sectionY(key) - psy));
        return (colDist2 << 16) | dy;
    }

    private static void removeUnsorted(LongArrayList queue, int index) {
        int last = queue.size() - 1;
        if (index != last) {
            queue.set(index, queue.getLong(last));
        }
        queue.removeLong(last);
    }

    /** Snapshot one section and dispatch its complete worker → GPU build lifecycle. */
    private void dispatchSectionBuild(DispatchContext dispatch, long key, int sx, int sy, int sz) {
        RtFrameStats.FRAME.count("sectionsSnapshotted", 1);
        RtSectionSnapshots.Region region = snapshots.createRegion(dispatch.level(), sx, sy, sz);
        long token = ++buildToken;
        long dirtyGroup = queuedDirtyGroup.remove(key);
        if (dirtyGroup != NO_DIRTY_GROUP && !dirtyGroups.containsKey(dirtyGroup)) {
            dirtyGroup = NO_DIRTY_GROUP;
        }
        RtMaterialRegistry.Snapshot materialSnapshot = RtMaterialRegistry.INSTANCE.requireSnapshot();
        SectionTask task = new SectionTask(key, token, sx << 4, sy << 4, sz << 4, dirtyGroup,
                terrainEpoch, materialSnapshot.epoch());
        beginActiveTask();
        try {
            RtWorkerPool.INSTANCE.submit(() -> {
                try {
                    if (!isTaskCurrent(task)) {
                        completeTask(task, null, null, null);
                        return;
                    }
                    WorkerTessState ws = WORKER_TESS.get(); // thread-confined; reset per task, arrays amortized
                    ws.reset(dispatch.blockColors(), dispatch.blockSpriteFinder());
                    FluidRenderer fluidRenderer = new FluidRenderer(dispatch.fluidModelSet());
                    CpuSection cpu = buildCpuSection(region, dispatch.modelSet(), ws.blockEmitter, ws.blockRandom,
                            ws.capture,
                            fluidRenderer, ws.fluidCapture, ws.mesh, ws.pos, materialSnapshot, sx, sy, sz);
                    if (!isTaskCurrent(task)) {
                        completeTask(task, null, null, null);
                        return;
                    }
                    PackedSection packed = cpu.packed();
                    if (packed == null) {
                        completeTask(task, null, null, null);
                    } else {
                        PreparedSection prepared = RtSectionBuilder.prepare(dispatch.ctx(), packed,
                                cpu.opacityMicromap(), CausticaConfig.Rt.Terrain.BLAS_COMPACTION.value(),
                                task.key, task.sox, task.soy, task.soz);
                        if (!isTaskCurrent(task)) {
                            destroyPreparedSection(prepared);
                            completeTask(task, null, null, null);
                            return;
                        }
                        try {
                            submitTerrainBuild(dispatch.ctx(), task, prepared);
                        } catch (Throwable t) {
                            RtSectionBuilder.destroy(prepared);
                            throw t;
                        }
                    }
                } catch (Throwable t) {
                    completeTask(task, null, null, t);
                    throw t;
                }
            });
        } catch (Throwable t) {
            finishActiveTask();
            throw t;
        }
        inFlight.put(key, token);
        if (dirtyGroup != NO_DIRTY_GROUP) {
            inFlightDirtyGroup.put(key, dirtyGroup);
        } else {
            inFlightDirtyGroup.remove(key);
        }
    }

    /** Build a terrain BLAS and optionally compact-copy it before publication. */
    private void submitTerrainBuild(RtContext ctx, SectionTask task, PreparedSection prepared) {
        ctx.gpuExecutor().submit(
                () -> !isTaskCurrent(task),
                cmd -> {
                    RtSectionBuilder.recordUpload(cmd, prepared);
                    RtAccel.recordBlasBuilds(ctx, cmd, List.of(prepared.blas()));
                },
                () -> {
                    RtAccel.freeBlasScratch(List.of(prepared.blas()));
                    prepared.releaseUpload();
                },
                (build, failure) -> {
                    if (failure != null) {
                        completeTask(task, prepared, build, failure);
                        return;
                    }
                    if (!isTaskCurrent(task)) {
                        completeTask(task, prepared, build, null);
                        return;
                    }
                    if (prepared.blas().requestsCompaction()) {
                        submitTerrainCompaction(ctx, task, prepared, build);
                    } else {
                        prepared.releaseBuildInputs();
                        completeTask(task, prepared, build, null);
                    }
                });
    }

    private void submitTerrainCompaction(RtContext ctx, SectionTask task, PreparedSection prepared,
                                         RtGpuExecutor.Build build) {
        RtAccel.PreparedTerrainCompaction compaction;
        try {
            compaction = RtAccel.prepareTerrainCompaction(ctx, prepared.blas());
        } catch (Throwable t) {
            completeTask(task, prepared, build, t);
            return;
        }
        try {
            ctx.gpuExecutor().submit(
                    () -> !isTaskCurrent(task),
                    cmd -> RtAccel.recordTerrainCompaction(ctx, cmd, compaction),
                    () -> {
                        RtAccel.finishTerrainCompaction(compaction);
                        prepared.releaseBuildInputs();
                    },
                    (copyBuild, failure) -> {
                        if (failure != null) {
                            Throwable terminal = failure;
                            try {
                                RtAccel.destroyTerrainCompaction(compaction);
                            } catch (Throwable destroyFailure) {
                                terminal.addSuppressed(destroyFailure);
                            }
                            completeTask(task, prepared, copyBuild, terminal);
                            return;
                        }
                        completeTask(task, prepared.withBlas(compaction.compacted()), copyBuild, null);
                    });
        } catch (Throwable t) {
            try {
                RtAccel.destroyTerrainCompaction(compaction);
            } catch (Throwable destroyFailure) {
                t.addSuppressed(destroyFailure);
            }
            completeTask(task, prepared, build, t);
        }
    }

    private void completeTask(SectionTask task, PreparedSection prepared, RtGpuExecutor.Build build, Throwable failure) {
        completeTask(new SectionResult(task, prepared, build, failure));
    }

    private void completeTask(SectionResult result) {
        try {
            if (isTaskCurrent(result.task())) {
                completedBuilds.add(result);
            } else if (result.prepared() != null) {
                destroyPreparedSection(result.prepared());
            }
        } finally {
            finishActiveTask();
        }
    }

    private boolean isTaskCurrent(SectionTask task) {
        return task.terrainEpoch == terrainEpoch;
    }

    private void beginActiveTask() {
        synchronized (activeTaskLock) {
            activeTasks++;
        }
    }

    private void finishActiveTask() {
        synchronized (activeTaskLock) {
            if (--activeTasks < 0) {
                throw new IllegalStateException("RT terrain active-task underflow");
            }
            if (activeTasks == 0) {
                activeTaskLock.notifyAll();
            }
        }
    }

    private void awaitActiveTasks() {
        synchronized (activeTaskLock) {
            while (activeTasks != 0) {
                try {
                    activeTaskLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while joining RT terrain tasks", e);
                }
            }
        }
    }

    /**
     * Publish terminal worker/executor results (up to the configured result count per pass). A task
     * whose token no longer matches {@link #inFlight} is stale and its unpublished native result is
     * destroyed instead of entering the table.
     */
    private void drainCompletedBuilds(RtContext ctx, List<PreparedSection> prepared, List<SectionGeom> removed,
                                      int resultCap) {
        int remaining = resultCap;
        while (remaining > 0) {
            SectionResult result = completedBuilds.poll();
            if (result == null) {
                break;
            }
            SectionTask task = result.task();
            long expected = inFlight.get(task.key);
            boolean tokenValid = expected == task.token;
            boolean valid = tokenValid && task.terrainEpoch == terrainEpoch
                    && task.materialEpoch == RtMaterialRegistry.INSTANCE.epoch();
            if (!valid) {
                if (tokenValid) {
                    inFlight.remove(task.key);
                    long staleGroup = inFlightDirtyGroup.remove(task.key);
                    if (staleGroup != NO_DIRTY_GROUP) cancelDirtyGroup(staleGroup);
                    enqueueMissingIfNeeded(task.key);
                    RtFrameStats.FRAME.count("terrainMaterialEpochRejects", 1);
                }
                destroyCompletedResult(ctx, result);
                continue;
            }
            inFlight.remove(task.key);
            long dirtyGroup = inFlightDirtyGroup.remove(task.key);
            if (result.failure() != null) {
                if (result.prepared() != null) {
                    destroyPreparedSection(result.prepared());
                }
                if (dirtyGroup != NO_DIRTY_GROUP) {
                    cancelDirtyGroup(dirtyGroup);
                }
                throw new RuntimeException("RT terrain section build failed for section "
                        + (task.sox >> 4) + "," + (task.soy >> 4) + "," + (task.soz >> 4),
                        result.failure());
            }
            PreparedSection built = result.prepared();
            if (built != null) {
                try {
                    ctx.gpuExecutor().markPublished(result.build());
                    RtFrameStats.FRAME.count("terrainBuildsCompleted", 1);
                } catch (Throwable t) {
                    RtSectionBuilder.destroy(built);
                    if (dirtyGroup != NO_DIRTY_GROUP) {
                        cancelDirtyGroup(dirtyGroup);
                    }
                    throw new RuntimeException("RT terrain GPU build failed for section "
                            + (task.sox >> 4) + "," + (task.soy >> 4) + "," + (task.soz >> 4), t);
                }
            }
            if (dirtyGroup != NO_DIRTY_GROUP && dirtyGroups.containsKey(dirtyGroup)) {
                DirtyGroup group = dirtyGroups.get(dirtyGroup);
                if (built == null) {
                    SectionGeom prev = resident.get(task.key);
                    if (prev != null) {
                        group.removed.add(prev);
                    } else {
                        group.restoreEmptyKeys.add(task.key);
                    }
                    group.emptyKeys.add(task.key);
                } else {
                    empty.remove(task.key);
                    group.prepared.add(built);
                }
                completeDirtyGroupMember(group, prepared, removed);
                remaining--;
            } else {
                if (built == null) {
                    // Legitimately empty (air or fully-enclosed). If this was an in-place re-extract whose new
                    // state is empty, evict the old geom and retire it in this publish pass.
                    SectionGeom prev = resident.remove(task.key);
                    if (prev != null) {
                        removed.add(prev);
                    }
                    empty.add(task.key);
                    remaining--;
                } else {
                    empty.remove(task.key);
                    prepared.add(built);
                    remaining--;
                }
            }
        }
    }

    private void destroyCompletedResult(RtContext ctx, SectionResult result) {
        if (result.prepared() == null) {
            return;
        }
        destroyPreparedSection(result.prepared());
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
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            RtSectionBuilder.destroy(ps);
        } else {
            ctx.gpuExecutor().enqueueDestroyUnpublished(() -> RtSectionBuilder.destroy(ps));
        }
    }

    /** Per-tick render-thread snapshot dependencies shared by reextract + missing dispatch. */
    private record DispatchContext(RtContext ctx, ClientLevel level, BlockStateModelSet modelSet,
                                   FluidStateModelSet fluidModelSet, BlockColors blockColors,
                                   SpriteFinder blockSpriteFinder) {
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

    /** An outstanding worker/GPU section build; terminal results enter one completion queue. */
    private static final class SectionTask {
        final long key;
        final long token;
        final int sox;
        final int soy;
        final int soz;
        final long dirtyGroup;
        final long terrainEpoch;
        final long materialEpoch;
        SectionTask(long key, long token, int sox, int soy, int soz, long dirtyGroup,
                    long terrainEpoch, long materialEpoch) {
            this.key = key;
            this.token = token;
            this.sox = sox;
            this.soy = soy;
            this.soz = soz;
            this.dirtyGroup = dirtyGroup;
            this.terrainEpoch = terrainEpoch;
            this.materialEpoch = materialEpoch;
        }
    }

    private record SectionResult(SectionTask task, PreparedSection prepared,
                                 RtGpuExecutor.Build build, Throwable failure) {
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
            SectionGeom g = new SectionGeom(ps.key(), ps.uvs(), ps.material(),
                    ps.blas().accel, ps.triBase(), ps.sx(), ps.sy(), ps.sz(), ps.lights());
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
            retireLightBuffer(ctx, lastGraphicsUse);
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
        if (!prepared.isEmpty() || !removed.isEmpty() || rebase) {
            rebuildLightBuffer(ctx, lastGraphicsUse);
        }
        ready = true;
    }

    /** Detach + retire the current light and alias buffers after their last graphics submission. */
    private void retireLightBuffer(RtContext ctx, long lastGraphicsUse) {
        if (lightBuffer != null) {
            RtBuffer old = lightBuffer;
            ctx.gpuExecutor().enqueueDestroyAfterGraphics(lastGraphicsUse, old::destroy);
            lightBuffer = null;
        }
        if (lightAliasBuffer != null) {
            RtBuffer old = lightAliasBuffer;
            ctx.gpuExecutor().enqueueDestroyAfterGraphics(lastGraphicsUse, old::destroy);
            lightAliasBuffer = null;
        }
        if (regirCellBuffer != null) {
            RtBuffer old = regirCellBuffer;
            ctx.gpuExecutor().enqueueDestroyAfterGraphics(lastGraphicsUse, old::destroy);
            regirCellBuffer = null;
        }
        if (regirCandidateBuffer != null) {
            RtBuffer old = regirCandidateBuffer;
            ctx.gpuExecutor().enqueueDestroyAfterGraphics(lastGraphicsUse, old::destroy);
            regirCandidateBuffer = null;
        }
        regirOriginX = regirOriginY = regirOriginZ = 0;
        regirDimX = regirDimY = regirDimZ = 0;
        lightCount = 0;
    }

    /**
     * Flatten the published sections' section-local light records into one device-local global buffer in
     * rebased world space (position lanes get {@code sectionOrigin − rebaseOrigin}; directions, half-axes
     * and Le copy through), compact it to the shader's 64 B hot layout, and build a Vose alias table
     * weighted by emitted power ({@code area*luminance(Le)}). Runs in the same publish step that made
     * {@code table.instances} current.
     */
    private void rebuildLightBuffer(RtContext ctx, long lastGraphicsUse) {
        int sourceStride = RtLightCollector.FLOATS_PER_LIGHT;
        final int gpuStride = 16;
        int totalLights = 0;
        int litSections = 0;
        ArrayList<RtReGIR.SectionLights> regirSections = new ArrayList<>(table.instanceList.size());
        for (int i = 0, n = table.instanceList.size(); i < n; i++) {
            SectionGeom g = table.slots.get(table.instanceList.get(i).customIndex());
            float[] lights = g.lights;
            int sectionLights = lights != null ? lights.length / sourceStride : 0;
            regirSections.add(new RtReGIR.SectionLights(g.sx >> 4, g.sy >> 4, g.sz >> 4,
                    totalLights, sectionLights));
            if (lights != null && lights.length > 0) {
                totalLights += sectionLights;
                litSections++;
            }
        }
        retireLightBuffer(ctx, lastGraphicsUse);
        if (totalLights == 0) {
            return;
        }
        float[] packedLights = new float[Math.multiplyExact(totalLights, gpuStride)];
        float[] lightX = new float[totalLights];
        float[] lightY = new float[totalLights];
        float[] lightZ = new float[totalLights];
        double[] powers = new double[totalLights];
        int lightIndex = 0;
        for (int i = 0, n = table.instanceList.size(); i < n; i++) {
            SectionGeom g = table.slots.get(table.instanceList.get(i).customIndex());
            float[] lights = g.lights;
            if (lights == null || lights.length == 0) continue;
            float ox = g.sx - blockX;
            float oy = g.sy - blockY;
            float oz = g.sz - blockZ;
            for (int base = 0; base < lights.length; base += sourceStride) {
                float leR = lights[base + 16];
                float leG = lights[base + 17];
                float leB = lights[base + 18];
                int dst = lightIndex * gpuStride;
                packedLights[dst] = lightX[lightIndex] = lights[base] + ox;
                packedLights[dst + 1] = lightY[lightIndex] = lights[base + 1] + oy;
                packedLights[dst + 2] = lightZ[lightIndex] = lights[base + 2] + oz;
                packedLights[dst + 3] = lights[base + 3];
                packedLights[dst + 4] = lights[base + 4];
                packedLights[dst + 5] = lights[base + 5];
                packedLights[dst + 6] = lights[base + 6];
                packedLights[dst + 7] = leR;
                packedLights[dst + 8] = lights[base + 8];
                packedLights[dst + 9] = lights[base + 9];
                packedLights[dst + 10] = lights[base + 10];
                packedLights[dst + 11] = leG;
                packedLights[dst + 12] = lights[base + 12];
                packedLights[dst + 13] = lights[base + 13];
                packedLights[dst + 14] = lights[base + 14];
                packedLights[dst + 15] = leB;
                double luminance = 0.2126 * leR + 0.7152 * leG + 0.0722 * leB;
                powers[lightIndex] = Math.max(0.0, lights[base + 3] * luminance);
                lightIndex++;
            }
        }

        LightAliasTable aliasTable = buildLightAliasTable(powers);
        RtReGIR.Data regir = CausticaConfig.Rt.Lights.REGIR_ENABLED.value()
                ? RtReGIR.build(regirSections, lightX, lightY, lightZ, powers, blockX, blockY, blockZ)
                : null;
        long lightBytes = (long) totalLights * gpuStride * Float.BYTES;
        long aliasBytes = aliasTable != null ? (long) totalLights * 16L : 0L;
        long cellBytes = regir != null ? regir.cellBytes() : 0L;
        long candidateBytes = regir != null ? regir.candidateBytes() : 0L;
        long aliasOffset = lightBytes;
        long cellOffset = aliasOffset + aliasBytes;
        long candidateOffset = cellOffset + cellBytes;
        long uploadBytes = candidateOffset + candidateBytes;
        RtBuffer next = null;
        RtBuffer nextAlias = null;
        RtBuffer nextCells = null;
        RtBuffer nextCandidates = null;
        RtBuffer upload = null;
        try {
            int residentUsage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            next = ctx.createAsyncBuffer(lightBytes, residentUsage, false, "terrain lights");
            if (aliasTable != null) {
                nextAlias = ctx.createAsyncBuffer(aliasBytes, residentUsage, false,
                        "terrain light aliases");
            }
            if (regir != null) {
                nextCells = ctx.createAsyncBuffer(cellBytes, residentUsage, false,
                        "terrain ReGIR cells");
                nextCandidates = ctx.createAsyncBuffer(candidateBytes, residentUsage, false,
                        "terrain ReGIR candidates");
            }
            upload = ctx.createUploadBuffer(uploadBytes, "terrain light tables upload");
            MemoryUtil.memFloatBuffer(upload.mapped, packedLights.length).put(packedLights);
            if (aliasTable != null) writeLightAliasTable(upload.mapped + aliasOffset, aliasTable);
            if (regir != null) {
                long cursor = upload.mapped + cellOffset;
                for (int i = 0; i < regir.cellOffsets().length; i++) {
                    MemoryUtil.memPutInt(cursor, regir.cellOffsets()[i]);
                    MemoryUtil.memPutInt(cursor + 4, regir.cellCounts()[i]);
                    cursor += 8;
                }
                MemoryUtil.memIntBuffer(upload.mapped + candidateOffset, regir.candidates().length)
                        .put(regir.candidates());
            }
            upload.flush();
            if (CausticaConfig.Rt.Lights.DUMP.value()) {
                dumpNearbyLights(upload.mapped, totalLights);
            }

            RtBuffer submittedUpload = upload;
            RtBuffer submittedLights = next;
            RtBuffer submittedAliases = nextAlias;
            RtBuffer submittedCells = nextCells;
            RtBuffer submittedCandidates = nextCandidates;
            RtGpuExecutor.Build uploadBuild = ctx.gpuExecutor().submit(
                    cmd -> recordLightUpload(cmd, submittedUpload, submittedLights, submittedAliases,
                            submittedCells, submittedCandidates, lightBytes, aliasOffset, cellOffset,
                            candidateOffset),
                    () -> { },
                    (ignored, failure) -> submittedUpload.destroy());
            ctx.gpuExecutor().markPublished(uploadBuild);
            upload = null; // executor callback owns it after submit
            lightBuffer = next;
            lightAliasBuffer = nextAlias;
            regirCellBuffer = nextCells;
            regirCandidateBuffer = nextCandidates;
            if (regir != null) {
                regirOriginX = regir.originX();
                regirOriginY = regir.originY();
                regirOriginZ = regir.originZ();
                regirDimX = regir.dimX();
                regirDimY = regir.dimY();
                regirDimZ = regir.dimZ();
            }
            lightCount = totalLights;
            next = null;
            nextAlias = null;
            nextCells = null;
            nextCandidates = null;
        } finally {
            if (upload != null) upload.destroy();
            if (nextCandidates != null) nextCandidates.destroy();
            if (nextCells != null) nextCells.destroy();
            if (nextAlias != null) nextAlias.destroy();
            if (next != null) next.destroy();
        }
        if (CausticaConfig.Rt.Lights.STATS.value()) {
            long bytes = lightBytes + aliasBytes + cellBytes + candidateBytes;
            CausticaMod.LOGGER.info("RT lights: {} lights across {} sections ({} published), "
                            + "{} ReGIR cells / {} candidates, {} KiB total",
                    lightCount, litSections, table.instanceList.size(),
                    regir != null ? regir.populatedCells() : 0,
                    regir != null ? regir.candidates().length : 0, bytes / 1024);
        }
    }

    /** Build one Vose alias column per light: alias, acceptance, and both possible inverse PDFs. */
    private static LightAliasTable buildLightAliasTable(double[] powers) {
        int count = powers.length;
        double total = 0.0;
        for (double power : powers) total += power;
        if (!(total > 0.0)) return null;

        double[] scaled = new double[count];
        float[] invPdf = new float[count];
        float[] accept = new float[count];
        int[] alias = new int[count];
        int[] small = new int[count];
        int[] large = new int[count];
        int smallCount = 0, largeCount = 0;
        for (int i = 0; i < count; i++) {
            scaled[i] = powers[i] * count / total;
            invPdf[i] = powers[i] > 0.0 ? (float) (total / powers[i]) : 0.0f;
            if (scaled[i] < 1.0) small[smallCount++] = i;
            else large[largeCount++] = i;
        }
        while (smallCount > 0 && largeCount > 0) {
            int s = small[--smallCount];
            int l = large[--largeCount];
            accept[s] = (float) scaled[s];
            alias[s] = l;
            scaled[l] = scaled[l] + scaled[s] - 1.0;
            if (scaled[l] < 1.0) small[smallCount++] = l;
            else large[largeCount++] = l;
        }
        while (largeCount > 0) {
            int i = large[--largeCount];
            accept[i] = 1.0f;
            alias[i] = i;
        }
        while (smallCount > 0) {
            int i = small[--smallCount];
            accept[i] = 1.0f;
            alias[i] = i;
        }

        return new LightAliasTable(alias, accept, invPdf);
    }

    private static void writeLightAliasTable(long cursor, LightAliasTable table) {
        for (int i = 0; i < table.alias.length; i++) {
            MemoryUtil.memPutInt(cursor, table.alias[i]);
            MemoryUtil.memPutFloat(cursor + 4, table.accept[i]);
            MemoryUtil.memPutFloat(cursor + 8, table.invPdf[i]);
            MemoryUtil.memPutFloat(cursor + 12, table.invPdf[table.alias[i]]);
            cursor += 16;
        }
    }

    /** Copy all proposal tables; the executor's ALL_COMMANDS timeline signal publishes the writes. */
    private static void recordLightUpload(VkCommandBuffer cmd, RtBuffer upload, RtBuffer lights,
                                          RtBuffer aliases, RtBuffer cells, RtBuffer candidates,
                                          long lightBytes, long aliasOffset, long cellOffset,
                                          long candidateOffset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            copyLightTable(cmd, upload, lights, 0L, lightBytes, region);
            if (aliases != null) copyLightTable(cmd, upload, aliases, aliasOffset, aliases.size, region);
            if (cells != null) copyLightTable(cmd, upload, cells, cellOffset, cells.size, region);
            if (candidates != null) {
                copyLightTable(cmd, upload, candidates, candidateOffset, candidates.size, region);
            }
        }
    }

    private static void copyLightTable(VkCommandBuffer cmd, RtBuffer upload, RtBuffer destination,
                                       long sourceOffset, long bytes, VkBufferCopy.Buffer region) {
        region.get(0).srcOffset(sourceOffset).dstOffset(0L).size(bytes);
        VK10.vkCmdCopyBuffer(cmd, upload.handle, destination.handle, region);
    }

    /** Log every published light within {@code lights.dump-radius} blocks of the camera. */
    private void dumpNearbyLights(long packedBase, int packedCount) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        int stride = 16;
        double px = player.getX() - blockX;
        double py = player.getY() - blockY;
        double pz = player.getZ() - blockZ;
        double radius = CausticaConfig.Rt.Lights.DUMP_RADIUS.value();
        double radiusSq = radius * radius;
        int dumped = 0;
        for (int i = 0; i < packedCount; i++) {
            long rec = packedBase + (long) i * stride * Float.BYTES;
            float x = MemoryUtil.memGetFloat(rec);
            float y = MemoryUtil.memGetFloat(rec + 4);
            float z = MemoryUtil.memGetFloat(rec + 8);
            double dx = x - px, dy = y - py, dz = z - pz;
            if (dx * dx + dy * dy + dz * dz > radiusSq) {
                continue;
            }
            float area = MemoryUtil.memGetFloat(rec + 12);
            float leR = MemoryUtil.memGetFloat(rec + 28);
            float leG = MemoryUtil.memGetFloat(rec + 44);
            float leB = MemoryUtil.memGetFloat(rec + 60);
            CausticaMod.LOGGER.info(
                    "RT light[{}] world=({}, {}, {}) size={} area={} Le=({}, {}, {}) lum={}",
                    i, String.format(java.util.Locale.ROOT, "%.2f", x + blockX),
                    String.format(java.util.Locale.ROOT, "%.2f", y + blockY),
                    String.format(java.util.Locale.ROOT, "%.2f", z + blockZ),
                    String.format(java.util.Locale.ROOT, "%.3f", Math.sqrt(area)),
                    String.format(java.util.Locale.ROOT, "%.4f", area),
                    String.format(java.util.Locale.ROOT, "%.3f", leR),
                    String.format(java.util.Locale.ROOT, "%.3f", leG),
                    String.format(java.util.Locale.ROOT, "%.3f", leB),
                    String.format(java.util.Locale.ROOT, "%.3f", 0.2126f * leR + 0.7152f * leG + 0.0722f * leB));
            dumped++;
        }
        CausticaMod.LOGGER.info("RT light dump: {} lights within {} blocks", dumped, (int) radius);
    }

    private record LightAliasTable(int[] alias, float[] accept, float[] invPdf) {
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

    /** Join outstanding worker/GPU tasks and destroy every unpublished terminal result. */
    private void drainTasksForClear(RtContext ctx) {
        // A dead executor cannot make further task progress. Throw on the render thread before waiting;
        // its failure path has already terminally failed every accepted queued build.
        ctx.gpuExecutor().throwIfFailed();
        awaitActiveTasks();
        Throwable failure = null;
        SectionResult result;
        while ((result = completedBuilds.poll()) != null) {
            if (result.prepared() != null) {
                destroyPreparedSection(result.prepared());
            }
            if (result.failure() != null && failure == null) {
                failure = result.failure();
            }
        }
        inFlight.clear();
        inFlightDirtyGroup.clear();
        if (failure != null) {
            throw new RuntimeException("RT terrain worker/build failed during teardown", failure);
        }
    }

    /** Full teardown (world exit / shutdown): drain the GPU, then free everything incl. an in-flight build. */
    private void clear(RtContext ctx, boolean shutdown) {
        if (!shutdown) {
            clearAsync(ctx);
            return;
        }

        // Device teardown is the one path that must prove every worker and GPU callback has relinquished
        // its resources before the executor, allocator, and VkDevice disappear.
        terrainEpoch++;
        drainTasksForClear(ctx);
        cancelAllDirtyGroups();
        ctx.waitIdle();
        ctx.gpuExecutor().flushDestroysAfterDeviceIdle();
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
        missingIndex.clear();
        queuedDirtyGroup.clear();
        reextract.clear();
        queuedReextract.clear();
        windowValid = false;
        if (lightBuffer != null) { // device idle at this point (see waitIdle above): destroy directly
            lightBuffer.destroy();
            lightBuffer = null;
        }
        if (lightAliasBuffer != null) {
            lightAliasBuffer.destroy();
            lightAliasBuffer = null;
        }
        if (regirCellBuffer != null) {
            regirCellBuffer.destroy();
            regirCellBuffer = null;
        }
        if (regirCandidateBuffer != null) {
            regirCandidateBuffer.destroy();
            regirCandidateBuffer = null;
        }
        regirOriginX = regirOriginY = regirOriginZ = 0;
        regirDimX = regirDimY = regirDimZ = 0;
        lightCount = 0;
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
            ready = false;
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
            RtSectionBuilder.destroy(ps);
        }
        prepared.clear();
        ready = false;
    }

    /**
     * Invalidate a world residency without joining worker/GPU work or waiting for graphics. Render-owned
     * state is detached immediately; its native resources are retired on the executor after the last
     * graphics submission that could still reference them. Results arriving from the old epoch self-retire
     * as unpublished resources in {@link #completeTask(SectionResult)}.
     */
    private void clearAsync(RtContext ctx) {
        ctx.gpuExecutor().throwIfFailed();
        terrainEpoch++;

        // Token maps are render-thread ownership, so clearing them makes every old completion unpublishable
        // even in the narrow race where it observed the previous epoch immediately before this increment.
        inFlight.clear();
        inFlightDirtyGroup.clear();
        cancelAllDirtyGroups();

        long lastGraphicsUse = ctx.gpuExecutor().latestGraphicsUseValue();
        Generation oldGeneration = table.detachGeneration();

        Set<SectionGeom> oldGeometry = Collections.newSetFromMap(new IdentityHashMap<>());
        oldGeometry.addAll(resident.values());
        oldGeometry.addAll(removed);
        resident.clear();
        removed.clear();

        ArrayList<PreparedSection> oldPrepared = new ArrayList<>(prepared);
        prepared.clear();
        SectionResult completed;
        while ((completed = completedBuilds.poll()) != null) {
            if (completed.prepared() != null) {
                oldPrepared.add(completed.prepared());
            }
        }

        snapshots.clear();
        synchronized (dirtyLock) {
            dirty.clear();
            dirtyEvents.clear();
            dirtyPending = false;
        }
        dirtyDrain.clear();
        dirtyEventDrain.clear();
        desired.clear();
        desiredColumns.clear();
        loadedColumns.clear();
        missing.clear();
        missingIndex.clear();
        queuedDirtyGroup.clear();
        reextract.clear();
        queuedReextract.clear();
        empty.clear();
        published.clear();
        windowValid = false;

        table.capacity = 0;
        table.nextSlot = 0;
        table.freeSlots.clear();
        table.slots.clear();
        table.instanceList.clear();
        table.instances = null;

        if (oldGeneration != null) {
            retireGeneration(ctx, lastGraphicsUse, oldGeneration);
        }
        retireLightBuffer(ctx, lastGraphicsUse);
        if (!oldGeometry.isEmpty()) {
            ArrayList<SectionGeom> retirement = new ArrayList<>(oldGeometry);
            ctx.gpuExecutor().enqueueDestroyAfterGraphics(lastGraphicsUse,
                    () -> destroyDetachedGeometry(retirement));
        }
        if (!oldPrepared.isEmpty()) {
            ctx.gpuExecutor().enqueueDestroyUnpublished(() -> destroyDetachedPrepared(oldPrepared));
        }

        // Keep the RT seam alive as an empty world while the new desired window begins filling.
        ensureEmptyTableReady(ctx);
    }

    private static void destroyDetachedGeometry(List<SectionGeom> geometry) {
        Throwable failure = null;
        for (SectionGeom geom : geometry) {
            try {
                geom.destroy();
            } catch (Throwable t) {
                if (failure == null) failure = t;
                else failure.addSuppressed(t);
            }
        }
        if (failure != null) {
            throw new RuntimeException("Failed to retire detached RT terrain geometry", failure);
        }
    }

    private static void destroyDetachedPrepared(List<PreparedSection> sections) {
        Throwable failure = null;
        for (PreparedSection section : sections) {
            try {
                RtSectionBuilder.destroy(section);
            } catch (Throwable t) {
                if (failure == null) failure = t;
                else failure.addSuppressed(t);
            }
        }
        if (failure != null) {
            throw new RuntimeException("Failed to retire detached RT terrain builds", failure);
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
