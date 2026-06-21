package dev.upscaler.rt;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.upscaler.UpscalerMod;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
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
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * P2 step 3: per-section terrain residency synced to vanilla's loaded chunks. A singleton manager
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
 * section table ({@code {primAddr, idxAddr, uvAddr}} per section) the hit shaders read.
 *
 * <p>Tessellation reads only an immutable snapshot ({@link RenderSectionRegion}, captured on the render
 * thread via {@link RenderRegionCache} exactly as vanilla's chunk compiler does), so under
 * {@code -Dupscaler.rt.asyncTerrain} the meshing runs on {@link RtWorkerPool}; the snapshot, GPU buffer
 * upload, BLAS prepare and queue submission all stay on the render thread. The BLAS build itself is an
 * async GPU submit, and frees are deferred frames-in-flight-safe (no {@code waitIdle} on the hot path).
 */
public final class RtTerrain {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("upscaler.rt.terrain", "true"));
    // RT view distance in chunks: explicit override, else min(vanilla render distance, cap). RT is
    // costly, so it's capped below typical raster view distance and tunable.
    private static final int VIEW_CHUNKS = Integer.getInteger("upscaler.rt.viewChunks", 0);
    private static final int VIEW_CHUNKS_CAP = Integer.getInteger("upscaler.rt.viewChunksCap", 8);
    private static final int VIEW_SECTIONS_V = Integer.getInteger("upscaler.rt.viewSectionsV", 6);
    private static final int SECTIONS_PER_TICK = Integer.getInteger("upscaler.rt.sectionsPerTick", 24);
    // Async terrain: tessellate sections on RtWorkerPool instead of inline on the render thread. The
    // snapshot (RenderSectionRegion) and all Vulkan still happen on the render thread; only the meshing
    // moves off. SECTIONS_PER_TICK then caps dispatches/tick; SECTION_RESULTS_PER_TICK caps uploads/tick.
    private static final boolean ASYNC = Boolean.parseBoolean(System.getProperty("upscaler.rt.asyncTerrain", "false"));
    // Async caps run higher than the sync SECTIONS_PER_TICK because the work left on the render thread is
    // only the GPU upload (buffer create + memcpy) — tessellation, the expensive part, is on the pool —
    // so per-tick async batches are cheaper than one sync batch even when several times larger.
    private static final int ASYNC_DISPATCH_PER_TICK = Integer.getInteger("upscaler.rt.asyncDispatchPerTick", 64);
    private static final int SECTION_RESULTS_PER_TICK = Integer.getInteger("upscaler.rt.sectionResultsPerTick", 64);
    // Backpressure cap: stop dispatching once this many sections are in flight. Bounds queue depth and
    // snapshot memory (each RenderSectionRegion holds 27 SectionCopies) when flying through the world.
    private static final int MAX_INFLIGHT = Integer.getInteger("upscaler.rt.maxInflightSections", 192);
    private static final int SECTION_ENTRY_BYTES = 24; // {u64 primAddr, u64 idxAddr, u64 uvAddr}
    // Frames a retired resource must outlive before it's freed (> frames-in-flight). The frame counter
    // advances per composite; old TLAS/table/sections are freed this many frames after the swap.
    private static final int KEEP_FRAMES = 4;

    private static final RtTerrain INSTANCE = new RtTerrain();

    private final Map<Long, SectionGeom> resident = new HashMap<>();
    private Set<Long> published = new HashSet<>();
    private final Set<Long> empty = new HashSet<>(); // loaded, in-window sections with no geometry
    private final Set<Long> dirty = java.util.concurrent.ConcurrentHashMap.newKeySet(); // edited sections to re-extract
    private final List<Deferred> deferred = new ArrayList<>(); // frames-in-flight-safe frees
    // ASYNC tessellation bookkeeping (render-thread only). `inFlight` maps a dispatched section key to a
    // monotonic token; a completed job whose token no longer matches (section re-dirtied / unloaded /
    // left the window since dispatch) is dropped. `jobs` holds the outstanding worker futures.
    private final Map<Long, Long> inFlight = new HashMap<>();
    private final List<TessJob> jobs = new ArrayList<>();
    private long tessToken;
    private boolean loggedTessFailure; // log the first worker tessellation failure (should never happen)
    private static volatile boolean loggedMeshFailure; // first per-block/fluid meshing throw (swallowed below,
                                                       // so it never reaches the worker-task catch — log once)
    private Pending pending; // in-flight async geometry build, or null
    private RtBuffer sectionTable;
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
    // Rebase origin (player block at the last TLAS rebuild) for the instance transforms + ray camOffset.
    public int blockX;
    public int blockY;
    public int blockZ;

    private RtTerrain() {
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

    /** Section table device address: {@code {u64 primAddr, u64 idxAddr, u64 uvAddr}} per section, indexed by gl_InstanceCustomIndexEXT. */
    public long tableAddress() {
        return sectionTable.deviceAddress;
    }

    /** Per-tick residency update (windowing + incremental build/free + TLAS rebuild on change). */
    public static void update(RtContext ctx) {
        if (ENABLED) {
            INSTANCE.tick(ctx);
        }
    }

    public static void shutdown(RtContext ctx) {
        INSTANCE.clear(ctx);
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
        if (!ENABLED) {
            return;
        }
        for (int scx = (minX - 1) >> 4; scx <= (maxX + 1) >> 4; scx++) {
            for (int scy = (minY - 1) >> 4; scy <= (maxY + 1) >> 4; scy++) {
                for (int scz = (minZ - 1) >> 4; scz <= (maxZ + 1) >> 4; scz++) {
                    INSTANCE.dirty.add(sectionKey(scx, scy, scz));
                }
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
        INSTANCE.reresolveAllRequested = true;
    }

    private void tick(RtContext ctx) {
        processDeferredFrees();

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            clear(ctx); // left the world — drop all geometry (drains + frees, incl. any in-flight build)
            return;
        }

        // Full clear on an explicit invalidation — vanilla's LevelExtractor.allChanged() via the Fabric
        // InvalidateRenderStateCallback. That fires on a dimension switch (setLevel → allChanged),
        // render-distance change, and F3+A. Without it, End→Overworld keeps the old dimension's geometry:
        // residency is keyed by raw section coords (no world identity), so the same coords stay resident
        // and are never rebuilt for the new world.
        if (fullClearRequested) {
            fullClearRequested = false;
            clear(ctx);
        }

        // Re-extract all live sections after a resource reload so material flags pick up the new atlases.
        if (reresolveAllRequested) {
            reresolveAllRequested = false;
            dirty.addAll(resident.keySet());
            dirty.addAll(empty);
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

        BlockPos pb = mc.player.blockPosition();
        int pcx = pb.getX() >> 4, pcz = pb.getZ() >> 4, psy = pb.getY() >> 4;
        int r = horizontalChunks(mc);
        int minSecY = level.getMinY() >> 4;
        int maxSecY = (level.getMinY() + level.getHeight() - 1) >> 4;
        int loY = Math.max(minSecY, psy - VIEW_SECTIONS_V);
        int hiY = Math.min(maxSecY, psy + VIEW_SECTIONS_V);

        List<SectionGeom> removed = new ArrayList<>();
        List<int[]> reextract = new ArrayList<>(); // ASYNC: dirty sections to rebuild in place (kept resident)

        // Re-extract edited sections. Snapshot+removeAll drains without losing concurrent adds.
        if (!dirty.isEmpty()) {
            List<Long> keys = new ArrayList<>(dirty);
            dirty.removeAll(keys);
            for (long key : keys) {
                empty.remove(key);
                inFlight.remove(key); // invalidate any in-flight tessellation of the now-stale section
                if (ASYNC) {
                    // Keep the old geometry resident + traced; re-dispatch and swap when the new mesh is
                    // ready (no eviction gap → no flicker). Non-resident dirty keys fall through to the
                    // normal window/missing pass.
                    SectionGeom g = resident.get(key);
                    if (g != null) {
                        reextract.add(new int[]{g.sx >> 4, g.sy >> 4, g.sz >> 4});
                    }
                } else {
                    // Sync rebuilds in the same tick, so evicting now leaves no visible gap.
                    SectionGeom g = resident.remove(key);
                    if (g != null) {
                        removed.add(g);
                    }
                }
            }
        }

        // Desired window = loaded sections within the view. hasChunk gating makes residency follow
        // vanilla: unloaded chunks aren't desired (so their sections get freed), loaded ones are.
        Set<Long> desired = new HashSet<>();
        List<int[]> missing = new ArrayList<>();
        for (int scx = pcx - r; scx <= pcx + r; scx++) {
            for (int scz = pcz - r; scz <= pcz + r; scz++) {
                if (!level.getChunkSource().hasChunk(scx, scz)) {
                    continue;
                }
                for (int scy = loY; scy <= hiY; scy++) {
                    long key = sectionKey(scx, scy, scz);
                    desired.add(key);
                    if (!resident.containsKey(key) && !empty.contains(key)
                            && !inFlight.containsKey(key)
                            && neighborChunksReady(level, scx, scz)) {
                        missing.add(new int[]{scx, scy, scz});
                    }
                }
            }
        }

        // Free sections that left the window (or whose chunk unloaded).
        for (Iterator<Map.Entry<Long, SectionGeom>> it = resident.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, SectionGeom> e = it.next();
            if (!desired.contains(e.getKey())) {
                removed.add(e.getValue());
                it.remove();
            }
        }
        empty.removeIf(k -> !desired.contains(k));
        // Drop in-flight tessellations whose section left the window / unloaded since dispatch.
        inFlight.keySet().removeIf(k -> !desired.contains(k));

        // Tessellate + upload new sections (BLAS build deferred to rebuild's single batched submission).
        List<PreparedSection> prepared = new ArrayList<>();
        // Build nearest-first so terrain fills from the player outward.
        if (!missing.isEmpty()) {
            missing.sort((a, b) -> Integer.compare(dist2(a, pcx, psy, pcz), dist2(b, pcx, psy, pcz)));
        }
        if (ASYNC) {
            dispatchReextract(level, reextract);
            dispatchTessellation(level, missing);
            drainTessellation(ctx, prepared, removed);
        } else if (!missing.isEmpty()) {
            ModelBlockRenderer renderer = new ModelBlockRenderer(false, true, mc.getBlockColors());
            // Snapshot source. Tessellation reads only the captured RenderSectionRegion (block states,
            // light, biome tint, block entities for the 18³ neighbourhood) — never the live ClientLevel —
            // which is what makes moving the meshing to a worker pool safe. The cache reuses neighbour
            // SectionCopies across the sections built this tick (mirrors vanilla's chunk compiler).
            RenderRegionCache regionCache = new RenderRegionCache();
            BlockStateModelSet modelSet = mc.getModelManager().getBlockStateModelSet();
            QuadCapture capture = new QuadCapture();
            capture.blockColors = mc.getBlockColors();
            // Fluids (water/lava) have no baked model — they're meshed by FluidRenderer into a
            // VertexConsumer. FluidCapture is both the Output and the capturing builder.
            FluidRenderer fluidRenderer = new FluidRenderer(mc.getModelManager().getFluidStateModelSet());
            FluidCapture fluidCapture = new FluidCapture();
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            int budget = SECTIONS_PER_TICK;
            for (int[] s : missing) {
                if (budget <= 0) {
                    break;
                }
                budget--;
                long key = sectionKey(s[0], s[1], s[2]);
                // Snapshot on the render thread (reads the live level); the resulting region is then
                // a thread-safe BlockAndTintGetter the tessellation can run against off-thread.
                RenderSectionRegion region = regionCache.createRegion(level, SectionPos.asLong(s[0], s[1], s[2]));
                SectionMesh mesh = tessellate(region, modelSet, renderer, capture, fluidRenderer, fluidCapture, m, s[0], s[1], s[2]);
                if (mesh.idx.isEmpty()) {
                    empty.add(key);
                } else {
                    prepared.add(uploadSection(ctx, mesh, key, s[0] << 4, s[1] << 4, s[2] << 4));
                }
            }
        }

        if (!removed.isEmpty() || !prepared.isEmpty()) {
            startBuild(ctx, prepared, removed, pb.getX(), pb.getY(), pb.getZ());
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
     * section at the window edge has an outward neighbour that is out of window (so not ray-traced) but
     * whose chunk is still loaded — the RT view is capped well below the vanilla render distance — so we
     * can read its blocks and bake a correct border. Without this, the edge section would mesh against
     * air, then show a seam once the player moves and that neighbour becomes interior and is rendered.
     * The only cost is at the very view edge when the render distance is at or below the RT cap: the
     * outermost ring waits for a chunk that is never loaded, leaving it unbuilt — an acceptable nit far
     * from the player.
     */
    private boolean neighborChunksReady(ClientLevel level, int scx, int scz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (!level.getChunkSource().hasChunk(scx + dx, scz + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private int horizontalChunks(Minecraft mc) {
        int r = VIEW_CHUNKS > 0 ? VIEW_CHUNKS : Math.min(mc.options.getEffectiveRenderDistance(), VIEW_CHUNKS_CAP);
        return Math.max(1, r);
    }

    private static int dist2(int[] s, int pcx, int psy, int pcz) {
        int dx = s[0] - pcx, dy = s[1] - psy, dz = s[2] - pcz;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Tessellate one section to a section-local CPU mesh. <b>Pure CPU + snapshot reads only</b> — no
     * Vulkan, no shared mutable state — so this is the unit a worker thread runs. LabPBR material
     * ingestion ({@link RtBlockMaterials#ensure}) creates/uploads GPU textures, so it is deferred: each
     * triangle's sprite is recorded into {@link SectionMesh#triSprites} and resolved later on the render
     * thread in {@link #uploadSection}. Returns the mesh (possibly empty — caller checks {@code idx}).
     */
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
                    // Lava's block light (15) rides the same emission channel as P3.2a (water emits 0).
                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        fluidCapture.emission = state.getLightEmission() / 15f;
                        // Water is the dielectric fluid (P5.2 translucency/refraction); lava stays an
                        // opaque emitter. Tagged per-prim so the path tracer can branch (see emitQuad).
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
                    } catch (Throwable t) {
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
     * create/upload GPU textures). {@code mesh} must be non-empty.
     */
    private static PreparedSection uploadSection(RtContext ctx, SectionMesh mesh, long key, int sox, int soy, int soz) {
        resolveMaterials(mesh);
        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        int vertCount = mesh.verts.size() / 3;
        int idxCount = mesh.idx.size();
        String label = "terrain section " + sox + "," + soy + "," + soz;
        RtBuffer positions = ctx.createBuffer((long) mesh.verts.size() * Float.BYTES, asInput, true,
                label + " positions");
        RtBuffer indices = ctx.createBuffer((long) mesh.idx.size() * Integer.BYTES, asInput | storage, true,
                label + " indices");
        RtBuffer uvs = ctx.createBuffer((long) mesh.uvList.size() * Float.BYTES, storage, true,
                label + " uvs");
        RtBuffer material = ctx.createBuffer((long) mesh.prim.size() * Float.BYTES, storage, true,
                label + " material");
        MemoryUtil.memFloatBuffer(positions.mapped, mesh.verts.size()).put(mesh.verts.elements(), 0, mesh.verts.size());
        MemoryUtil.memIntBuffer(indices.mapped, mesh.idx.size()).put(mesh.idx.elements(), 0, mesh.idx.size());
        MemoryUtil.memFloatBuffer(uvs.mapped, mesh.uvList.size()).put(mesh.uvList.elements(), 0, mesh.uvList.size());
        MemoryUtil.memFloatBuffer(material.mapped, mesh.prim.size()).put(mesh.prim.elements(), 0, mesh.prim.size());

        // Cutout geometry: non-opaque so the any-hit shader alpha-tests the atlas (foliage/glass).
        // The BLAS build is deferred — the caller batches all sections' builds into one submission.
        RtAccel.PreparedBlas blas = RtAccel.prepareTrianglesBlas(ctx, positions, vertCount, indices, idxCount, false,
                label + " BLAS");
        return new PreparedSection(key, positions, indices, uvs, material, blas, sox, soy, soz);
    }

    /**
     * Patch each prim record's {@code mat.z/mat.w} (hasS/hasN) by ingesting its triangle's sprite into
     * the LabPBR atlases. Deferred out of {@link #tessellate} because {@link RtBlockMaterials#ensure}
     * creates/uploads GPU textures and mutates a non-concurrent cache — render thread only. The
     * {@code triSprites} list is one entry per prim record (per triangle), aligned with {@code prim}.
     */
    private static void resolveMaterials(SectionMesh mesh) {
        List<TextureAtlasSprite> tri = mesh.triSprites;
        FloatArrayList prim = mesh.prim;
        for (int t = 0; t < tri.size(); t++) {
            TextureAtlasSprite sprite = tri.get(t);
            if (sprite == null) {
                continue; // fluids / untextured / RtMaterials disabled → flags stay at the 0 placeholder
            }
            int flags = RtBlockMaterials.INSTANCE.ensure(sprite);
            int off = t * 12;
            prim.set(off + 10, (flags & RtBlockMaterials.HAS_S) != 0 ? 1f : 0f);
            prim.set(off + 11, (flags & RtBlockMaterials.HAS_N) != 0 ? 1f : 0f);
        }
    }

    /**
     * ASYNC: snapshot each missing section on the render thread and submit its tessellation to the worker
     * pool. The per-task meshing objects (renderer / captures / MutableBlockPos) are allocated inside the
     * job so nothing mutable is shared across threads; the captured {@code region}, model sets and block
     * colors are read-only. Capped at {@link #SECTIONS_PER_TICK} dispatches per tick.
     */
    private void dispatchTessellation(ClientLevel level, List<int[]> missing) {
        if (missing.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderRegionCache regionCache = new RenderRegionCache();
        BlockStateModelSet modelSet = mc.getModelManager().getBlockStateModelSet();
        FluidStateModelSet fluidModelSet = mc.getModelManager().getFluidStateModelSet();
        BlockColors blockColors = mc.getBlockColors();
        int budget = ASYNC_DISPATCH_PER_TICK;
        for (int[] s : missing) {
            if (budget <= 0 || inFlight.size() >= MAX_INFLIGHT) {
                break;
            }
            budget--;
            dispatchSection(level, regionCache, modelSet, fluidModelSet, blockColors, s[0], s[1], s[2]);
        }
    }

    /**
     * ASYNC re-extraction of edited (dirty) sections that are still resident: dispatch a fresh
     * tessellation while leaving the old geometry resident and traced, so it's swapped — never evicted
     * with a gap — when the new mesh is built (see {@link #startBuild} retiring the replaced geom). This
     * is what prevents the visible flicker on block updates that plain eviction would cause.
     */
    private void dispatchReextract(ClientLevel level, List<int[]> reextract) {
        if (reextract.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderRegionCache regionCache = new RenderRegionCache();
        BlockStateModelSet modelSet = mc.getModelManager().getBlockStateModelSet();
        FluidStateModelSet fluidModelSet = mc.getModelManager().getFluidStateModelSet();
        BlockColors blockColors = mc.getBlockColors();
        for (int[] s : reextract) {
            // Skip ones the window pass freed this tick (out of view) — they're being retired, not rebuilt.
            if (!resident.containsKey(sectionKey(s[0], s[1], s[2]))) {
                continue;
            }
            dispatchSection(level, regionCache, modelSet, fluidModelSet, blockColors, s[0], s[1], s[2]);
        }
    }

    /** Snapshot one section on the render thread and submit its tessellation to the worker pool. */
    private void dispatchSection(ClientLevel level, RenderRegionCache regionCache, BlockStateModelSet modelSet,
                                 FluidStateModelSet fluidModelSet, BlockColors blockColors, int sx, int sy, int sz) {
        long key = sectionKey(sx, sy, sz);
        RenderSectionRegion region = regionCache.createRegion(level, SectionPos.asLong(sx, sy, sz));
        long token = ++tessToken;
        Future<SectionMesh> future = RtWorkerPool.INSTANCE.submit(() -> {
            ModelBlockRenderer renderer = new ModelBlockRenderer(false, true, blockColors);
            QuadCapture capture = new QuadCapture();
            capture.blockColors = blockColors;
            FluidRenderer fluidRenderer = new FluidRenderer(fluidModelSet);
            FluidCapture fluidCapture = new FluidCapture();
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            return tessellate(region, modelSet, renderer, capture, fluidRenderer, fluidCapture, m, sx, sy, sz);
        });
        inFlight.put(key, token);
        jobs.add(new TessJob(key, token, sx << 4, sy << 4, sz << 4, future));
    }

    /**
     * ASYNC: upload finished worker meshes (up to {@link #SECTION_RESULTS_PER_TICK} per tick) on the
     * render thread. A job whose token no longer matches {@link #inFlight} is stale — its section was
     * re-dirtied / unloaded / left the window since dispatch — and is dropped without uploading.
     */
    private void drainTessellation(RtContext ctx, List<PreparedSection> prepared, List<SectionGeom> removed) {
        int budget = SECTION_RESULTS_PER_TICK;
        for (Iterator<TessJob> it = jobs.iterator(); it.hasNext() && budget > 0; ) {
            TessJob job = it.next();
            if (!job.future().isDone()) {
                continue;
            }
            it.remove();
            SectionMesh mesh;
            try {
                mesh = job.future().get();
            } catch (Exception e) {
                inFlight.remove(job.key());
                if (!loggedTessFailure) {
                    loggedTessFailure = true;
                    UpscalerMod.LOGGER.warn("async terrain: tessellation task failed for section {},{},{}",
                            job.sox() >> 4, job.soy() >> 4, job.soz() >> 4, e);
                }
                continue;
            }
            Long expected = inFlight.get(job.key());
            boolean valid = expected != null && expected == job.token();
            if (!valid) {
                continue; // stale result; a newer dispatch (or none) supersedes it
            }
            inFlight.remove(job.key());
            if (mesh.idx.isEmpty()) {
                // Legitimately empty (air or fully-enclosed). If this was an in-place re-extract whose new
                // state is empty, evict the old geom and retire it via the build swap (a startBuild runs
                // because `removed` is now non-empty).
                SectionGeom prev = resident.remove(job.key());
                if (prev != null) {
                    removed.add(prev);
                }
                empty.add(job.key());
            } else {
                prepared.add(uploadSection(ctx, mesh, job.key(), job.sox(), job.soy(), job.soz()));
                budget--;
            }
        }
    }

    /** A section tessellated + uploaded with a prepared (not-yet-built) BLAS, pending the batch build. */
    private record PreparedSection(long key, RtBuffer positions, RtBuffer indices, RtBuffer uvs, RtBuffer material,
                                   RtAccel.PreparedBlas blas, int sx, int sy, int sz) {
    }

    /** A deferred free: run {@code free} once the frame counter reaches {@code freeFrame}. */
    private record Deferred(long freeFrame, Runnable free) {
    }

    /** An outstanding async tessellation: {@code future} yields the section's CPU mesh on a worker. */
    private record TessJob(long key, long token, int sox, int soy, int soz, Future<SectionMesh> future) {
    }

    /** An in-flight async BLAS build: the new section geometry/instances land when {@code op} completes. */
    private record Pending(RtContext.AsyncSubmit op, List<RtAccel.PreparedBlas> blas, RtBuffer newTable,
                           List<RtAccel.Instance> newInstances, Set<Long> newPublished, List<SectionGeom> removed, int rbx, int rby, int rbz) {
    }

    /**
     * Start an async geometry build. The new sections are added to residency and their BLAS are built
     * off the render thread; the previously-published instance list + table stay live and traceable
     * (against the old, already-built BLAS) until {@link #finalizePending} swaps the result in. The
     * TLAS is no longer built here — {@link RtComposite} rebuilds it per frame from {@link
     * #staticInstances()} plus dynamic instances. {@code rbx/rby/rbz} is the new rebase origin.
     */
    private void startBuild(RtContext ctx, List<PreparedSection> prepared, List<SectionGeom> removed, int rbx, int rby, int rbz) {
        for (PreparedSection ps : prepared) {
            SectionGeom prev = resident.put(ps.key(), new SectionGeom(ps.positions(), ps.indices(), ps.uvs(), ps.material(), ps.blas().accel, ps.sx(), ps.sy(), ps.sz()));
            if (prev != null) {
                // Re-extracted section (ASYNC in-place rebuild): the old geometry stayed traced until now;
                // retire it with the swap so there's no eviction gap and no leak.
                removed.add(prev);
            }
        }

        List<SectionGeom> ordered = new ArrayList<>(resident.values());
        if (ordered.isEmpty()) {
            // Everything left the window: retire the current table + removed sections, go not-ready.
            long freeAt = RtComposite.frameCounter() + KEEP_FRAMES;
            retire(freeAt, sectionTable, removed);
            sectionTable = null;
            staticInstances = null;
            published = new HashSet<>();
            ready = false;
            return;
        }

        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        RtBuffer newTable = ctx.createBuffer((long) ordered.size() * SECTION_ENTRY_BYTES, storage, true,
                "terrain section table " + ordered.size() + " sections");
        List<RtAccel.Instance> instances = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            SectionGeom g = ordered.get(i);
            long base = newTable.mapped + (long) i * SECTION_ENTRY_BYTES;
            MemoryUtil.memPutLong(base, g.material.deviceAddress);
            MemoryUtil.memPutLong(base + 8, g.indices.deviceAddress);
            MemoryUtil.memPutLong(base + 16, g.uvs.deviceAddress);
            // instanceCustomIndex == section-table index i (< ENTITY_BIT, so the hit shader takes the
            // terrain path). The BLAS device address is valid now even though its contents finish
            // building async — the instance list is only published (and traced) once the build completes.
            float[] xform = {1, 0, 0, g.sx - rbx, 0, 1, 0, g.sy - rby, 0, 0, 1, g.sz - rbz};
            instances.add(new RtAccel.Instance(xform, g.blas.deviceAddress, i));
        }

        List<RtAccel.PreparedBlas> blasBuilds = new ArrayList<>(prepared.size());
        for (PreparedSection ps : prepared) {
            blasBuilds.add(ps.blas());
        }
        // BLAS-only async build (empty when this tick only freed sections — completes immediately).
        RtContext.AsyncSubmit op = ctx.submitAsync(cmd -> RtAccel.recordBlasBuilds(ctx, cmd, blasBuilds));
        pending = new Pending(op, blasBuilds, newTable, instances, new HashSet<>(resident.keySet()), removed, rbx, rby, rbz);
    }

    /** Swap a completed async build in: retire old table + removed sections, publish the new instances/table. */
    private void finalizePending(RtContext ctx) {
        Pending p = pending;
        pending = null;
        ctx.freeAsync(p.op());
        RtAccel.freeBlasScratch(p.blas()); // build done -> BLAS scratch safe to free
        long freeAt = RtComposite.frameCounter() + KEEP_FRAMES;
        retire(freeAt, sectionTable, p.removed());
        sectionTable = p.newTable();
        staticInstances = p.newInstances();
        published = p.newPublished();
        blockX = p.rbx();
        blockY = p.rby();
        blockZ = p.rbz();
        ready = true;
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
            return;
        }
        for (TessJob job : jobs) {
            job.future().cancel(true);
        }
        jobs.clear();
        inFlight.clear();
    }

    /** Full teardown (world exit / shutdown): drain the GPU, then free everything incl. an in-flight build. */
    private void clear(RtContext ctx) {
        cancelJobs();
        dirty.clear(); // any pending re-extract keys refer to the old world/coords — drop them
        if (pending == null && resident.isEmpty() && sectionTable == null && deferred.isEmpty()) {
            empty.clear();
            staticInstances = null;
            published = new HashSet<>();
            return;
        }
        ctx.waitIdle();
        if (pending != null) {
            ctx.freeAsync(pending.op());
            RtAccel.freeBlasScratch(pending.blas());
            pending.newTable().destroy();
            // The new sections' BLAS were added to `resident` in startBuild, so resident's destroy
            // below frees them; only the removed (already out of resident) need freeing here.
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
        for (SectionGeom g : resident.values()) {
            g.destroy();
        }
        resident.clear();
        empty.clear();
        staticInstances = null;
        published = new HashSet<>();
        ready = false;
    }

    /** Pack section coords into a stable map key; ranges fit comfortably in the masks. */
    private static long sectionKey(int scx, int scy, int scz) {
        return (scx & 0x3FFFFFFL) | ((scz & 0x3FFFFFFL) << 26) | ((scy & 0xFFFL) << 52);
    }

    /** GPU residency for one section: geometry buffers + BLAS + world section origin. */
    private static final class SectionGeom {
        final RtBuffer positions;
        final RtBuffer indices;
        final RtBuffer uvs;
        final RtBuffer material;
        final RtAccel blas;
        final int sx;
        final int sy;
        final int sz;

        SectionGeom(RtBuffer positions, RtBuffer indices, RtBuffer uvs, RtBuffer material, RtAccel blas, int sx, int sy, int sz) {
            this.positions = positions;
            this.indices = indices;
            this.uvs = uvs;
            this.material = material;
            this.blas = blas;
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

    /** Transient CPU accumulator for one section's quads while tessellating. */
    private static final class SectionMesh {
        final FloatArrayList verts = new FloatArrayList();
        final IntArrayList idx = new IntArrayList();
        final FloatArrayList uvList = new FloatArrayList(); // 2 floats/vertex: atlas UV
        final FloatArrayList prim = new FloatArrayList();   // 12 floats/triangle: normal.xyz+emission, tint.rgb+material, mat.{rough,metal,hasS,hasN}
        // One sprite per prim record (per triangle), aligned with `prim`. Resolved to hasS/hasN on the
        // render thread (RtBlockMaterials.ensure touches the GPU) — null when no LabPBR ingestion applies.
        final List<TextureAtlasSprite> triSprites = new ArrayList<>();
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

        @Override
        public void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
            FloatArrayList verts = cur.verts;
            IntArrayList idx = cur.idx;
            int base = verts.size() / 3;
            Vector3fc p0 = quad.position(0);
            Vector3fc p1 = quad.position(1);
            Vector3fc p2 = quad.position(2);
            Vector3fc p3 = quad.position(3);
            addVertex(p0, x, y, z);
            addVertex(p1, x, y, z);
            addVertex(p2, x, y, z);
            addVertex(p3, x, y, z);
            addUv(quad.packedUV(0));
            addUv(quad.packedUV(1));
            addUv(quad.packedUV(2));
            addUv(quad.packedUV(3));
            idx.add(base);
            idx.add(base + 1);
            idx.add(base + 2);
            idx.add(base);
            idx.add(base + 2);
            idx.add(base + 3);

            float ex1 = p1.x() - p0.x(), ey1 = p1.y() - p0.y(), ez1 = p1.z() - p0.z();
            float ex2 = p2.x() - p0.x(), ey2 = p2.y() - p0.y(), ez2 = p2.z() - p0.z();
            float nx = ey1 * ez2 - ez1 * ey2;
            float ny = ez1 * ex2 - ex1 * ez2;
            float nz = ex1 * ey2 - ey1 * ex2;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1.0e-6f) {
                nx /= len;
                ny /= len;
                nz /= len;
            }
            // Biome tint: tintIndex >= 0 means the quad is biome-colored (grass/foliage/etc.). In 26.2
            // the color comes from a BlockTintSource; colorInWorld blends the biome color at this pos
            // (0x00RRGGBB). Untinted quads (tintIndex < 0) stay white.
            int tintIndex = quad.materialInfo().tintIndex();
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

            // Emissive: vanilla block light level (0..15) -> 0..1, stashed in the free normal.w slot
            // (no per-prim layout change). The path tracer multiplies it by albedo for colored glow.
            float emission = state != null ? state.getLightEmission() / 15f : 0f;

            // P6.1: heuristic PBR material (roughness, metalness) for the GGX BRDF / DLSS-RR guides.
            float rough = RtMaterials.roughness(state);
            float metal = RtMaterials.metalness(state);
            // P6.2a/b: this quad's sprite's LabPBR maps get ingested into the parallel atlases, flagging
            // the prim — mat.z = 1 if an _s map exists (per-texel roughness/metalness/F0/emission), mat.w
            // = 1 if an _n map exists (per-texel normal). The ingest (RtBlockMaterials.ensure) creates GPU
            // textures, so it's deferred to the render thread: record the sprite per triangle and write a
            // 0 placeholder now; resolveMaterials() patches hasS/hasN before upload.
            TextureAtlasSprite sprite = RtMaterials.ENABLED ? quad.materialInfo().sprite() : null;

            FloatArrayList prim = cur.prim;
            for (int t = 0; t < 2; t++) { // one {normal+emission, tint, mat} record per triangle
                prim.add(nx);
                prim.add(ny);
                prim.add(nz);
                prim.add(emission);
                prim.add(tr);
                prim.add(tg);
                prim.add(tb);
                prim.add(0f);
                prim.add(rough);
                prim.add(metal);
                prim.add(0f); // hasS placeholder — patched in resolveMaterials()
                prim.add(0f); // hasN placeholder
                cur.triSprites.add(sprite);
            }
        }

        private void addVertex(Vector3fc p, float x, float y, float z) {
            cur.verts.add(p.x() + x);
            cur.verts.add(p.y() + y);
            cur.verts.add(p.z() + z);
        }

        private void addUv(long packedUV) {
            // UVPair packs u in the high 32 bits, v in the low 32 (atlas-space, no sprite remap needed).
            cur.uvList.add(Float.intBitsToFloat((int) (packedUV >>> 32)));
            cur.uvList.add(Float.intBitsToFloat((int) packedUV));
        }
    }

    /**
     * Captures the quads {@link FluidRenderer} emits (water/lava) into the current section's mesh. It
     * is both the {@link FluidRenderer.Output} and the {@link VertexConsumer} it hands back. Vertices
     * arrive in groups of 4 (one quad) via the bulk {@code addVertex}; we keep position + atlas UV,
     * compute a geometric normal (sign is irrelevant — the closest-hit flips it toward the viewer), and
     * emit two triangles like {@link QuadCapture}. Coords are already section-local (FluidRenderer uses
     * {@code pos & 15}). The cardinal-lit vertex colour is dropped — albedo comes from the atlas in the
     * hit shader, same as blocks. Tint is left white (biome water tint is deferred to P5 water work).
     *
     * <p>P5.2: water faces are tagged in the per-prim {@code tint.w} slot ({@code 1.0} = water) so the
     * path tracer treats them as a smooth dielectric (Fresnel reflection + refraction + Beer–Lambert
     * absorption). Lava keeps {@code tint.w == 0.0} and stays an opaque emitter.
     */
    private static final class FluidCapture implements VertexConsumer, FluidRenderer.Output {
        SectionMesh cur;     // set before each section
        float emission;      // set per fluid block (lava = 1, water = 0)
        boolean water;       // set per fluid block: true for water (dielectric), false for lava
        private int n;
        private final float[] qx = new float[4], qy = new float[4], qz = new float[4], qu = new float[4], qv = new float[4];

        @Override
        public VertexConsumer getBuilder(ChunkSectionLayer layer) {
            return this; // one capturing builder regardless of the fluid's render layer
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v,
                              int overlay, int light, float nx, float ny, float nz) {
            qx[n] = x; qy[n] = y; qz[n] = z; qu[n] = u; qv[n] = v;
            if (++n == 4) {
                emitQuad();
                n = 0;
            }
        }

        private void emitQuad() {
            FloatArrayList verts = cur.verts;
            IntArrayList idx = cur.idx;
            int base = verts.size() / 3;
            for (int i = 0; i < 4; i++) {
                verts.add(qx[i]);
                verts.add(qy[i]);
                verts.add(qz[i]);
                cur.uvList.add(qu[i]);
                cur.uvList.add(qv[i]);
            }
            idx.add(base);
            idx.add(base + 1);
            idx.add(base + 2);
            idx.add(base);
            idx.add(base + 2);
            idx.add(base + 3);

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
            // P6.1: water is a near-smooth dielectric; lava is a moderately rough opaque emitter.
            float rough = water ? RtMaterials.WATER_ROUGH : RtMaterials.LAVA_ROUGH;
            FloatArrayList prim = cur.prim;
            for (int t = 0; t < 2; t++) { // one {normal+emission, tint, mat} record per triangle
                prim.add(nx);
                prim.add(ny);
                prim.add(nz);
                prim.add(emission);
                prim.add(1f);
                prim.add(1f);
                prim.add(1f);
                prim.add(material);
                prim.add(rough);
                prim.add(0f); // metalness (fluids are dielectric)
                prim.add(0f); // hasS (fluids carry no LabPBR atlas material)
                prim.add(0f); // hasN
                cur.triSprites.add(null); // keep triSprites aligned 1:1 with prim records
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
