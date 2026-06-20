package dev.upscaler.rt;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.upscaler.mixin.ParticleEngineAccessor;
import dev.upscaler.mixin.ParticleGroupAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;

import java.util.IdentityHashMap;
import java.util.Queue;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P5.1b-2: dynamic entities as real ray-traced {@code ModelPart} geometry. Each frame, every model
 * entity is re-posed and captured ({@link RtEntityCollector} + {@link RtEntityCapture}) into a mesh in
 * terrain's vertex layout, uploaded, and given a per-entity BLAS built inline in the composite's frame
 * command buffer; one TLAS instance per entity (identity transform — geometry is captured directly in
 * terrain's rebased space) carries the {@link #ENTITY_BIT} custom-index flag so {@code world.rchit}
 * takes the entity path. A per-frame entity geometry table ({@code {primAddr, idxAddr, uvAddr, disp}})
 * gives the hit shader each entity's per-triangle normals/tint and its per-object motion-vector
 * displacement (P5.1c). Non-model entities (items/arrows — geometry via submitItem/submitBlockModel,
 * which the collector ignores) are skipped.
 *
 * <p>Shading is flat vertex-colour (white → grey-lit) until entity textures land (P5.1b-2b): entities
 * use per-type texture files, not the block atlas, so the captured UVs are stored but not yet sampled.
 *
 * <p>Per-frame cost is real (per-entity capture + buffer uploads + a BLAS build); capped by {@code
 * -Dupscaler.rt.maxEntities}. A reusable mesh/BLAS pool is a deferred perf item.
 */
public final class RtEntities {
    public static final RtEntities INSTANCE = new RtEntities();
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("upscaler.rt.entities", "true"));
    /** Custom-index flag bit (bit 23 of the 24-bit instanceCustomIndex) marking an entity instance. */
    public static final int ENTITY_BIT = 0x800000;
    /** Custom-index flag (bit 22) marking a particle billboard instance (shares the entity geom table). */
    public static final int PARTICLE_BIT = 0x400000;
    /** TLAS instance mask for particles: bit 1 only, so the 0x01 secondary cull mask skips them — particles
     *  are seen by the primary (camera) ray only (no shadows / GI / reflections; the v1 scope). */
    private static final int PARTICLE_MASK = 0x02;
    public static final boolean PARTICLES_ENABLED = Boolean.parseBoolean(System.getProperty("upscaler.rt.particles", "true"));
    private static final int MAX_ENTITIES = Integer.getInteger("upscaler.rt.maxEntities", 1024);
    // Chunk radius around the player to scan for block entities (chests/signs/…) each frame.
    private static final int BE_VIEW_CHUNKS = Integer.getInteger("upscaler.rt.beViewChunks", 8);
    // P5-perf #2: block entities keep a cached mesh + BLAS keyed by BlockPos. Each frame the BE is re-meshed
    // (cheap) and its mesh hashed; the expensive BLAS is rebuilt ONLY when the mesh actually changed — so
    // static BEs cost no GPU work (the new-chunk stutter was rebuilding all of them every frame) while
    // animating ones (chest lid, spawner, …) rebuild every frame and stay smooth. New/changed rebuilds are
    // capped per frame so a burst of newly loaded chunks can't stall (over-budget BEs keep their last
    // geometry / pop in over later frames, like terrain's SECTIONS_PER_TICK).
    private static final int BE_BUILDS_PER_FRAME = Integer.getInteger("upscaler.rt.beBuildsPerFrame", 8);
    // Entity geometry table entry: {u64 primAddr, u64 idxAddr, u64 uvAddr, u64 dispAddr, vec4 pad} = 48
    // bytes (std430 vec4 forces 16-align/48-size). P5.1c-2: dispAddr points at a per-vertex world-space
    // displacement buffer (cur − prev frame) for the motion vector; 0 ⇒ static (no MV).
    private static final int TABLE_ENTRY_BYTES = 48;
    // Ring of fixed-size geometry tables: each frame fills the next slot so the GPU read of this frame's
    // trace never races a later frame's host write. > frames-in-flight (mirrors RtPipeline RING).
    private static final int TABLE_RING = 6;
    // Frames a per-frame entity resource (mesh buffers + BLAS + scratch) must outlive before it's freed.
    private static final int KEEP_FRAMES = 4;
    // P5-perf #1 (step 2): refit (UPDATE-mode) BLAS. Persistent per-entity AS, refit in place each frame
    // (cheap) while topology is stable, instead of a full BUILD. Toggle for A/B + fallback to step-1
    // pooled BUILD. Block entities always use the pooled-BUILD path (they get P5-perf #2 instead).
    private static final boolean REFIT = Boolean.parseBoolean(System.getProperty("upscaler.rt.entityRefit", "true"));
    // Per-entity ring depth: a slot is reused every REFIT_RING frames, so it must be off all queues by
    // then. = KEEP_FRAMES (the established frames-in-flight-safe horizon). Each slot holds one persistent AS.
    private static final int REFIT_RING = KEEP_FRAMES;
    // Force a periodic full rebuild of a slot's AS to bound BVH-quality degradation from repeated refits
    // (an entity that deforms a lot would otherwise refit the same BVH topology forever). Per-slot count.
    private static final int REFIT_REBUILD_INTERVAL = Integer.getInteger("upscaler.rt.refitRebuildInterval", 120);
    // Identity 3x4 row-major: entity geometry is captured directly in rebased space, so no per-instance
    // transform is needed (unlike terrain sections, which carry sectionOrigin − rebase).
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};

    // Reusable capture pipeline (single-threaded on the render thread).
    private final RtEntityCollector collector = new RtEntityCollector();
    private final RtEntityCapture capture = new RtEntityCapture();
    private CameraRenderState cameraState;

    // Particle capture: a VertexConsumer adapter that funnels MC's billboard quads into `capture` (the
    // shared entity mesh). We extract each live particle into `particleScratch`, accumulate per-vertex
    // motion-vector displacements in `particleDisp`, and key the previous-frame center off particle
    // identity in `particlePrev` (rebuilt each frame → prunes dead particles).
    private final RtParticleCapture particleCapture = new RtParticleCapture(capture);
    private final QuadParticleRenderState particleScratch = new QuadParticleRenderState();
    private final it.unimi.dsi.fastutil.floats.FloatArrayList particleDisp = new it.unimi.dsi.fastutil.floats.FloatArrayList();
    private IdentityHashMap<Particle, ParticlePrev> particlePrev = new IdentityHashMap<>();

    /** Previous frame's particle center (rebase-space) + that frame's rebase origin, for the MV diff. */
    private record ParticlePrev(float cx, float cy, float cz, int rbx, int rby, int rbz) {
    }

    private RtBuffer[] tableRing;
    private int tableSlot;

    // P5-perf #1 (step 1): recycle per-frame entity mesh buffers + BLAS backing/scratch instead of
    // alloc/free churning ~6 VMA buffers per entity per frame. See RtBufferPool.
    private final RtBufferPool pool = new RtBufferPool();

    // P5.1c-2: previous frame's captured (rebase-space) vertex positions + that frame's rebase origin,
    // keyed by entity id (rebuilt each frame → prunes entities that left view). Diffed against the current
    // capture to produce a PER-VERTEX world-space displacement (so rotation/animation reproject, not just
    // rigid translation). Topology must match (same vertex count) to pair vertices index-by-index.
    private Map<Integer, EntityPrev> prevVerts = new HashMap<>();

    /** Last frame's posed mesh for one entity: rebase-space vertex positions + the rebase origin they were
     *  captured against (needed to convert the inter-frame delta to world space when the rebase moved). */
    private record EntityPrev(float[] verts, int rbx, int rby, int rbz) {
    }

    // Per-frame entity GPU resources awaiting a frames-in-flight-safe free.
    private final List<Deferred> deferred = new ArrayList<>();

    // P5-perf #1 (step 2): persistent per-entity acceleration structures, keyed by entity id, for refit.
    private final Map<Integer, EntityAccel> entityAccels = new HashMap<>();

    // P5-perf #2: persistent per-block-entity geometry, keyed by BlockPos.asLong(). Built once and reused
    // every frame (the chunk-load stutter was rebuilding all of these every frame).
    private final Map<Long, BeEntry> beCache = new HashMap<>();
    // (Re)builds recorded so far this frame, reset each beginFrame; gates new BE builds to BE_BUILDS_PER_FRAME.
    private int beBuildsThisFrame;

    private RtEntities() {
    }

    /**
     * Cached block-entity geometry. The mesh is captured in <b>block-local</b> space (identity submit pose),
     * so it is rebase-independent — only the per-frame TLAS instance transform ({@code blockPos − rebase})
     * changes, exactly like a terrain section. The BLAS + mesh buffers are pool-owned and persist until the
     * BE is evicted (out of window / unloaded) or rebuilt (its mesh changed); {@code idx/uv/prim} are read
     * by the hit shader every frame via the geometry table, so they must stay alive while traced.
     */
    private static final class BeEntry {
        RtAccel accel;
        RtBuffer backing;                        // pool-owned AS backing
        RtBuffer positions, indices, uvs, prim;  // pool-owned mesh buffers
        int bx, by, bz;                          // block position (drives the per-frame instance transform)
        long meshHash;                           // hash of the captured mesh — rebuild only when it changes
        long lastSeen;                           // last frame this BE was in the scan window — for eviction
        float[] prevVerts;                       // P5.1c-2: block-local verts at this build, for the per-vertex MV diff
    }

    /** One persistent updatable AS in an entity's ring: its backing buffer (pool-owned) + the topology it
     *  was built for (refit is valid only while vert/tri counts are unchanged) + refit bookkeeping. */
    private static final class EntitySlot {
        RtAccel accel;
        RtBuffer backing;
        int vertCount = -1;
        int triCount = -1;
        long updateScratchSize;
        int updatesSinceBuild;
    }

    /** A per-entity ring of {@link EntitySlot}s, cycled one-per-frame so a refit never writes an AS still
     *  in flight, plus the last frame the entity was captured (drives eviction). */
    private static final class EntityAccel {
        final EntitySlot[] ring = new EntitySlot[REFIT_RING];
        int cursor;
        long lastSeen;
    }

    /** This frame's entity contribution: the full instance list (terrain + entities), the entity BLAS to
     *  build inline this frame, and the geometry-table device address the hit shader reads. */
    public record FrameEntities(List<RtAccel.Instance> instances, List<RtAccel.PreparedBlas> blas, long geomTableAddr) {
    }

    private record Deferred(long freeFrame, Runnable free) {
    }

    /** Mutable per-frame build state shared by the entity + block-entity capture passes. */
    private final class FrameBuild {
        final List<RtAccel.Instance> base;
        List<RtAccel.Instance> instances;
        List<RtAccel.PreparedBlas> blas;        // all BLAS ops to record this frame (BUILD + refit UPDATE)
        List<RtAccel.PreparedBlas> pooledBlas;  // pooled-BUILD ops (block entities) → releaseBlasToPool
        List<RtBuffer> refitScratch;            // per-frame scratch from refit ops → pool.release (AS persists)
        List<RtBuffer> buffers;                 // per-frame mesh buffers (both paths) → pool.release
        long tableBase;
        long geomTableAddr;
        int count;

        FrameBuild(List<RtAccel.Instance> base) {
            this.base = base;
        }

        boolean full() {
            return count >= MAX_ENTITIES;
        }
    }

    /**
     * Capture this frame's model entities + block entities into per-object meshes/BLAS and merge them
     * with the terrain static instances. The caller (RtComposite) records the returned BLAS builds
     * before the TLAS build and pushes the geometry-table address. Returns terrain-only (no BLAS, addr 0)
     * when disabled or nothing captured. Coordinates are captured rebase-relative → identity instance.
     */
    public FrameEntities beginFrame(RtContext ctx, List<RtAccel.Instance> base, int rbx, int rby, int rbz,
                                    double camX, double camY, double camZ, Matrix4f projection, Matrix4f viewRotation) {
        processDeferred();
        pool.maybeLogStats();
        if (!ENABLED) {
            return new FrameEntities(base, List.of(), 0L);
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return new FrameEntities(base, List.of(), 0L);
        }
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        setCamera(camX, camY, camZ, projection, viewRotation);

        FrameBuild build = new FrameBuild(base);
        captureEntities(ctx, build, mc, level, partial, rbx, rby, rbz);
        captureBlockEntities(ctx, build, mc, level, partial, rbx, rby, rbz);
        captureParticles(ctx, build, mc, partial, rbx, rby, rbz, projection, viewRotation);
        evictStaleAccels();
        evictStaleBes();

        if (build.instances == null) {
            return new FrameEntities(base, List.of(), 0L);
        }
        // Retire this frame's transient meshes + scratch + pooled-BUILD BLAS once it is no longer in flight
        // (their build + the trace that reads them must complete first). Refit AS persist in entityAccels.
        long freeAt = RtComposite.frameCounter() + KEEP_FRAMES;
        List<RtAccel.PreparedBlas> pooledForFree = build.pooledBlas;
        List<RtBuffer> refitScratchForFree = build.refitScratch;
        List<RtBuffer> buffersForFree = build.buffers;
        deferred.add(new Deferred(freeAt, () -> {
            // Recycle (don't destroy): the deferred horizon guarantees these are off all queues, so the
            // pool can hand them straight back to the next frame's appendCapture.
            if (pooledForFree != null) {
                for (RtAccel.PreparedBlas b : pooledForFree) {
                    RtAccel.releaseBlasToPool(pool, b);
                }
            }
            if (refitScratchForFree != null) {
                for (RtBuffer s : refitScratchForFree) {
                    pool.release(s);
                }
            }
            for (RtBuffer buf : buffersForFree) {
                pool.release(buf);
            }
        }));
        return new FrameEntities(build.instances, build.blas, build.geomTableAddr);
    }

    /** Capture animated entities (mobs, items, falling blocks) with per-object motion-vector displacement. */
    private void captureEntities(RtContext ctx, FrameBuild build, Minecraft mc, ClientLevel level, float partial, int rbx, int rby, int rbz) {
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        Entity cameraEntity = mc.getCameraEntity();
        Map<Integer, EntityPrev> curVerts = new HashMap<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (build.full()) {
                break;
            }
            if (entity == cameraEntity || entity.isInvisible()) {
                continue;
            }
            float ix = (float) Mth.lerp(partial, entity.xo, entity.getX());
            float iy = (float) Mth.lerp(partial, entity.yo, entity.getY());
            float iz = (float) Mth.lerp(partial, entity.zo, entity.getZ());
            capture.reset();
            try {
                EntityRenderState state = dispatcher.extractEntity(entity, partial);
                collector.begin(capture);
                // Capture directly in rebased space so the TLAS instance transform is identity.
                dispatcher.submit(state, cameraState, ix - rbx, iy - rby, iz - rbz, new PoseStack(), collector);
            } catch (Throwable t) {
                continue; // non-fatal: skip an entity whose extract/submit throws
            } finally {
                collector.begin(null);
            }
            if (capture.isEmpty()) {
                continue; // non-model entity (arrow/etc.) — no body geometry captured
            }
            int id = entity.getId();
            // P5.1c-2: per-vertex world-space displacement vs last frame's posed mesh (null when new or the
            // topology changed → one frame of camera-only MV, like the old per-object path on first sight).
            float[] disp = vertexDisp(capture.verts, prevVerts.get(id), rbx, rby, rbz);
            curVerts.put(id, new EntityPrev(
                    java.util.Arrays.copyOf(capture.verts.elements(), capture.verts.size()), rbx, rby, rbz));
            appendCapture(ctx, build, disp, id, ENTITY_BIT, 0xFF);
        }
        prevVerts = curVerts;
    }

    /**
     * Per-vertex world-space displacement (cur − prev frame), in the {@code Disps} buffer layout (vec4 per
     * vertex, xyz used). Captures are rebase-relative, so the world delta adds the rebase shift
     * {@code rebaseCur − rebasePrev}. Returns {@code null} when the entity is new or its vertex count
     * changed (can't pair vertices index-by-index → no MV that frame). Mirrors the refit topology gate.
     */
    private static float[] vertexDisp(it.unimi.dsi.fastutil.floats.FloatArrayList cur, EntityPrev prev,
                                      int rbx, int rby, int rbz) {
        if (prev == null || prev.verts().length != cur.size()) {
            return null;
        }
        return buildDisp(cur.elements(), cur.size(), prev.verts(),
                rbx - prev.rbx(), rby - prev.rby(), rbz - prev.rbz());
    }

    /** Core per-vertex disp builder: {@code (cur − prev) + rebaseShift}, packed vec4/vertex (w = 0). */
    private static float[] buildDisp(float[] cur, int curSize, float[] prev, float sx, float sy, float sz) {
        int vc = curSize / 3;
        float[] d = new float[vc * 4];
        for (int i = 0; i < vc; i++) {
            d[i * 4]     = (cur[i * 3]     - prev[i * 3])     + sx;
            d[i * 4 + 1] = (cur[i * 3 + 1] - prev[i * 3 + 1]) + sy;
            d[i * 4 + 2] = (cur[i * 3 + 2] - prev[i * 3 + 2]) + sz;
            d[i * 4 + 3] = 0f;
        }
        return d;
    }

    /**
     * Capture this frame's billboard particles as ONE combined mesh + BLAS (cutout, unlit, camera-only),
     * with per-particle motion vectors. We iterate the LIVE {@code Particle} objects (via accessor mixins)
     * rather than the public packed render state, because only the live objects carry stable identity —
     * needed to diff each particle's center against last frame for the MV. Each particle is extracted into
     * {@link #particleScratch} (its billboard quad), funneled through {@link #particleCapture} into the
     * shared {@code capture}, and its quad center cached by identity in {@link #particlePrev}. Per-layer
     * texture slot comes from the layer's atlas (block/item/particle) via the bindless registry. One
     * {@code PARTICLE_BIT} instance with mask {@link #PARTICLE_MASK} (primary-ray only).
     */
    private void captureParticles(RtContext ctx, FrameBuild build, Minecraft mc, float partial,
                                  int rbx, int rby, int rbz, Matrix4f projection, Matrix4f viewRotation) {
        if (!PARTICLES_ENABLED || build.full()) {
            return;
        }
        Camera cam = mc.gameRenderer.mainCamera();
        if (cam == null) {
            return;
        }
        Map<ParticleRenderType, ParticleGroup<?>> groups =
                ((ParticleEngineAccessor) mc.particleEngine).upscaler$getParticleGroups();
        if (groups == null || groups.isEmpty()) {
            return;
        }
        capture.reset();
        particleDisp.clear();
        // extract() emits camera-relative positions; shift them into rebased space (identity instance).
        Vec3 camPos = cam.position();
        particleCapture.setOffset((float) (camPos.x - rbx), (float) (camPos.y - rby), (float) (camPos.z - rbz));
        // Frustum-cull on the particle center (matches vanilla's extractRenderState): we iterate ALL live
        // particles for identity, so cull the off-screen ones out of the BVH after capturing each.
        Frustum frustum = new Frustum(viewRotation, projection);
        frustum.prepare(camPos.x, camPos.y, camPos.z);
        IdentityHashMap<Particle, ParticlePrev> cur = new IdentityHashMap<>();
        try {
            for (ParticleGroup<?> group : groups.values()) {
                Queue<? extends Particle> queue = ((ParticleGroupAccessor) group).upscaler$getParticles();
                for (Particle p : queue) {
                    if (!(p instanceof SingleQuadParticle sq)) {
                        continue; // item-pickup / elder-guardian particles aren't billboard quads (skip)
                    }
                    int vb = capture.verts.size(), ib = capture.idx.size();
                    int ub = capture.uvList.size(), prb = capture.prim.size();
                    int vertBefore = vb / 3;
                    particleScratch.clear();
                    sq.extract(particleScratch, cam, partial);
                    for (SingleQuadParticle.Layer layer : particleScratch.layers()) {
                        capture.currentTexSlot = RtEntityTextures.INSTANCE.slotForAtlas(layer.textureAtlasLocation());
                        particleScratch.buildLayer(layer, particleCapture);
                        particleCapture.flush();
                    }
                    int vertAfter = capture.verts.size() / 3;
                    if (vertAfter == vertBefore) {
                        continue; // nothing captured for this particle
                    }
                    float[] center = particleCenter(vertBefore, vertAfter);
                    // pointInFrustum wants the world position: rebased center + rebase origin.
                    if (!frustum.pointInFrustum(center[0] + rbx, center[1] + rby, center[2] + rbz)) {
                        capture.verts.size(vb); // off-screen → truncate this particle back out (clean quad boundary)
                        capture.idx.size(ib);
                        capture.uvList.size(ub);
                        capture.prim.size(prb);
                        continue;
                    }
                    appendParticleMv(p, center, vertBefore, vertAfter, rbx, rby, rbz, cur);
                }
            }
        } catch (Throwable t) {
            capture.reset(); // a mid-capture throw could leave a partial quad — drop particles this frame
            particleDisp.clear();
            return;
        }
        particlePrev = cur;
        if (capture.isEmpty()) {
            return;
        }
        float[] disp = java.util.Arrays.copyOf(particleDisp.elements(), particleDisp.size());
        appendCapture(ctx, build, disp, -1, PARTICLE_BIT, PARTICLE_MASK); // one combined mesh, per-particle MV
    }

    /** Average (rebase-space) position of a captured particle's verts — approximates the particle center. */
    private float[] particleCenter(int vertBefore, int vertAfter) {
        float[] v = capture.verts.elements();
        float cx = 0f, cy = 0f, cz = 0f;
        for (int i = vertBefore; i < vertAfter; i++) {
            cx += v[i * 3];
            cy += v[i * 3 + 1];
            cz += v[i * 3 + 2];
        }
        int vc = vertAfter - vertBefore;
        return new float[]{cx / vc, cy / vc, cz / vc};
    }

    /**
     * Compute one particle's motion-vector displacement (its quad center vs. last frame's, keyed by
     * identity) and write it for each of the particle's vertices into {@link #particleDisp}. All four
     * billboard verts share the center displacement (per-particle-rigid MV).
     */
    private void appendParticleMv(Particle p, float[] center, int vertBefore, int vertAfter,
                                  int rbx, int rby, int rbz, IdentityHashMap<Particle, ParticlePrev> cur) {
        ParticlePrev prev = particlePrev.get(p);
        // World displacement = (curCenter − prevCenter) + (rebaseCur − rebasePrev). New particle ⇒ 0 (no MV).
        float dx = prev == null ? 0f : (center[0] - prev.cx()) + (rbx - prev.rbx());
        float dy = prev == null ? 0f : (center[1] - prev.cy()) + (rby - prev.rby());
        float dz = prev == null ? 0f : (center[2] - prev.cz()) + (rbz - prev.rbz());
        for (int i = vertBefore; i < vertAfter; i++) {
            particleDisp.add(dx);
            particleDisp.add(dy);
            particleDisp.add(dz);
            particleDisp.add(0f);
        }
        cur.put(p, new ParticlePrev(center[0], center[1], center[2], rbx, rby, rbz));
    }

    /**
     * Capture block entities (chests, signs, …). P5-perf #2: each BE keeps a cached mesh + BLAS keyed by
     * BlockPos. Every frame the BE is re-meshed (cheap) and its mesh hashed; the expensive BLAS is rebuilt
     * only when the mesh actually changed — so static BEs cost no GPU work (the new-chunk stutter was
     * rebuilding all of them every frame) while animating ones (chest lid, spawner, …) rebuild every frame
     * and stay smooth. New/changed rebuilds are capped at {@link #BE_BUILDS_PER_FRAME} per frame so a burst
     * of newly loaded chunks can't stall; over-budget BEs keep their last geometry / pop in over later
     * frames. Captured block-local → placed by a translate-only instance transform; static, so the MV is 0.
     */
    private void captureBlockEntities(RtContext ctx, FrameBuild build, Minecraft mc, ClientLevel level, float partial, int rbx, int rby, int rbz) {
        beBuildsThisFrame = 0;
        BlockEntityRenderDispatcher beDispatcher = mc.getBlockEntityRenderDispatcher();
        beDispatcher.prepare(cameraState.pos); // sets the camera for shouldRender / extract
        long now = RtComposite.frameCounter();
        int pcx = rbx >> 4, pcz = rbz >> 4;
        for (int cx = pcx - BE_VIEW_CHUNKS; cx <= pcx + BE_VIEW_CHUNKS; cx++) {
            for (int cz = pcz - BE_VIEW_CHUNKS; cz <= pcz + BE_VIEW_CHUNKS; cz++) {
                if (build.full()) {
                    return;
                }
                if (!level.getChunkSource().hasChunk(cx, cz) || !(level.getChunk(cx, cz) instanceof LevelChunk chunk)) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (build.full()) {
                        return;
                    }
                    updateBlockEntity(ctx, build, beDispatcher, be, partial, now, rbx, rby, rbz);
                }
            }
        }
    }

    /** Re-mesh one block entity; rebuild its cached BLAS only if the mesh changed (budgeted); then emit it. */
    private void updateBlockEntity(RtContext ctx, FrameBuild build, BlockEntityRenderDispatcher beDispatcher,
                                   BlockEntity be, float partial, long now, int rbx, int rby, int rbz) {
        capture.reset();
        try {
            BlockEntityRenderState state = beDispatcher.tryExtractRenderState(be, partial, null, false);
            if (state == null) {
                return; // off-screen-only (beacon/end-gateway), distance-culled, or no renderer
            }
            collector.begin(capture);
            // Identity pose ⇒ block-local mesh; world placement is the per-frame instance transform in emitBe.
            beDispatcher.submit(state, new PoseStack(), collector, cameraState);
        } catch (Throwable t) {
            return;
        } finally {
            collector.begin(null);
        }
        if (capture.isEmpty()) {
            return;
        }
        long key = be.getBlockPos().asLong();
        BeEntry entry = beCache.get(key);
        if (entry != null) {
            entry.lastSeen = now;
        }
        long hash = meshHash();
        float[] disp = null; // static unless the mesh changed this frame (chest lid / spawner animation)
        if (entry == null || entry.meshHash != hash) {
            // Geometry changed (or new BE) → rebuild, but only within this frame's budget. Over budget: keep
            // showing the previous geometry; a brand-new BE simply pops in over the next frames.
            if (beBuildsThisFrame >= BE_BUILDS_PER_FRAME) {
                if (entry != null) {
                    emitBe(ctx, build, entry, null, rbx, rby, rbz); // over budget: keep last geometry, no MV
                }
                return;
            }
            // P5.1c-2: per-vertex MV from the previous build's block-local mesh (same vertex count ⇒
            // pairable). The BE itself doesn't move, so the world displacement is the pure local delta.
            if (entry != null && entry.prevVerts != null && entry.prevVerts.length == capture.verts.size()) {
                disp = buildDisp(capture.verts.elements(), capture.verts.size(), entry.prevVerts, 0f, 0f, 0f);
            }
            BeEntry rebuilt = buildBe(ctx, build, be, hash);
            rebuilt.lastSeen = now;
            if (entry != null) {
                deferDestroyBe(entry); // retire the superseded geometry off-queue
            }
            beCache.put(key, rebuilt);
            entry = rebuilt;
        }
        emitBe(ctx, build, entry, disp, rbx, rby, rbz);
    }

    /** Upload the already-captured BE mesh to persistent pooled buffers and build its BLAS (block-local). */
    private BeEntry buildBe(RtContext ctx, FrameBuild build, BlockEntity be, long hash) {
        beginBuildIfNeeded(ctx, build);
        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        int vertCount = capture.verts.size() / 3;
        int idxCount = capture.idx.size();
        BlockPos p = be.getBlockPos();
        String label = "block entity " + p.getX() + "," + p.getY() + "," + p.getZ();
        RtBuffer positions = pool.acquire(ctx, (long) capture.verts.size() * Float.BYTES, asInput, true,
                label + " positions");
        RtBuffer indices = pool.acquire(ctx, (long) capture.idx.size() * Integer.BYTES, asInput | storage, true,
                label + " indices");
        RtBuffer uvs = pool.acquire(ctx, (long) capture.uvList.size() * Float.BYTES, storage, true,
                label + " uvs");
        RtBuffer prim = pool.acquire(ctx, (long) capture.prim.size() * Float.BYTES, storage, true,
                label + " prim");
        MemoryUtil.memFloatBuffer(positions.mapped, capture.verts.size()).put(capture.verts.elements(), 0, capture.verts.size());
        MemoryUtil.memIntBuffer(indices.mapped, capture.idx.size()).put(capture.idx.elements(), 0, capture.idx.size());
        MemoryUtil.memFloatBuffer(uvs.mapped, capture.uvList.size()).put(capture.uvList.elements(), 0, capture.uvList.size());
        MemoryUtil.memFloatBuffer(prim.mapped, capture.prim.size()).put(capture.prim.elements(), 0, capture.prim.size());

        // Persistent pooled BLAS reused every frame the mesh is unchanged. UpdatableBuild keeps the AS +
        // backing and exposes the build scratch separately (released at the frames-in-flight horizon). We
        // never refit it — a changed BE gets a fresh AS and the old one is defer-freed — so no in-place
        // write can race an in-flight trace.
        RtAccel.UpdatableBuild ub = RtAccel.prepareUpdatableBlasBuild(ctx, pool, positions, vertCount, indices, idxCount, false,
                label + " BLAS");
        build.blas.add(ub.op());
        build.refitScratch.add(ub.scratch());
        beBuildsThisFrame++;

        BeEntry e = new BeEntry();
        e.accel = ub.accel();
        e.backing = ub.backing();
        e.positions = positions;
        e.indices = indices;
        e.uvs = uvs;
        e.prim = prim;
        e.bx = p.getX();
        e.by = p.getY();
        e.bz = p.getZ();
        e.meshHash = hash;
        // P5.1c-2: retain this build's block-local verts so the next rebuild can diff against them for the MV.
        e.prevVerts = java.util.Arrays.copyOf(capture.verts.elements(), capture.verts.size());
        return e;
    }

    /** FNV-1a hash of the currently captured mesh (positions + indices + per-prim data) for rebuild detection. */
    private long meshHash() {
        long h = 1469598103934665603L;
        float[] v = capture.verts.elements();
        int vn = capture.verts.size();
        for (int i = 0; i < vn; i++) {
            h = (h ^ (Float.floatToRawIntBits(v[i]) & 0xffffffffL)) * 1099511628211L;
        }
        int[] x = capture.idx.elements();
        int xn = capture.idx.size();
        for (int i = 0; i < xn; i++) {
            h = (h ^ (x[i] & 0xffffffffL)) * 1099511628211L;
        }
        float[] pr = capture.prim.elements();
        int pn = capture.prim.size();
        for (int i = 0; i < pn; i++) {
            h = (h ^ (Float.floatToRawIntBits(pr[i]) & 0xffffffffL)) * 1099511628211L;
        }
        return h;
    }

    /** Emit a cached block entity into this frame: its geometry-table entry + a TLAS instance (no GPU build). */
    private void emitBe(RtContext ctx, FrameBuild build, BeEntry e, float[] disp, int rbx, int rby, int rbz) {
        if (build.full()) {
            return;
        }
        beginBuildIfNeeded(ctx, build);
        // P5.1c-2: disp is non-null only for a BE whose mesh changed this frame (chest lid / spawner); a
        // static BE passes null ⇒ dispAddr 0 ⇒ no MV. The disp buffer is a per-frame transient (the geom
        // table is rewritten every frame), so a BE that stops animating reverts to MV 0 next frame.
        long dispAddr = uploadDisp(ctx, build, disp, "block entity");
        writeTableEntry(build, e.prim.deviceAddress, e.indices.deviceAddress, e.uvs.deviceAddress, dispAddr);
        // Block-local mesh placed by a translate-only instance transform (blockPos − rebase), like terrain.
        float[] xform = {1, 0, 0, e.bx - rbx, 0, 1, 0, e.by - rby, 0, 0, 1, e.bz - rbz};
        build.instances.add(new RtAccel.Instance(xform, e.accel.deviceAddress, ENTITY_BIT | (build.count & 0x7FFFFF)));
        build.count++;
    }

    /** Retire a cached block entity's persistent AS + mesh buffers once off all in-flight queues. */
    private void deferDestroyBe(BeEntry e) {
        long freeAt = RtComposite.frameCounter() + KEEP_FRAMES;
        deferred.add(new Deferred(freeAt, () -> {
            RtAccel.destroyPooledAccel(pool, e.accel, e.backing);
            pool.release(e.positions);
            pool.release(e.indices);
            pool.release(e.uvs);
            pool.release(e.prim);
        }));
    }

    /** Drop cached block entities not seen (in window) within the last KEEP_FRAMES frames — unloaded/out of view. */
    private void evictStaleBes() {
        if (beCache.isEmpty()) {
            return;
        }
        long now = RtComposite.frameCounter();
        java.util.Iterator<Map.Entry<Long, BeEntry>> it = beCache.entrySet().iterator();
        while (it.hasNext()) {
            BeEntry e = it.next().getValue();
            if (now - e.lastSeen < KEEP_FRAMES) {
                continue;
            }
            deferDestroyBe(e);
            it.remove();
        }
    }

    /** Lazily initialise this frame's build (instance list seeded with terrain, fresh free-lists, table ring slot). */
    private void beginBuildIfNeeded(RtContext ctx, FrameBuild build) {
        if (build.instances != null) {
            return;
        }
        build.instances = new ArrayList<>(build.base);
        build.blas = new ArrayList<>();
        build.pooledBlas = new ArrayList<>();
        build.refitScratch = new ArrayList<>();
        build.buffers = new ArrayList<>();
        ensureResources(ctx);
        tableSlot = (tableSlot + 1) % TABLE_RING;
        build.tableBase = tableRing[tableSlot].mapped;
        build.geomTableAddr = tableRing[tableSlot].deviceAddress;
    }

    /**
     * Upload the current {@link #capture} as a per-object mesh + BLAS, add its instance + geom-table entry.
     * {@code entityId} ≥ 0 → refit path (persistent updatable AS keyed by id); {@code < 0} (refit disabled)
     * → pooled full BUILD (step 1). Used by the animated-entity pass; block entities use {@link #buildBe}.
     */
    private void appendCapture(RtContext ctx, FrameBuild build, float[] disp, int entityId, int instanceBit, int mask) {
        beginBuildIfNeeded(ctx, build);
        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        int vertCount = capture.verts.size() / 3;
        int idxCount = capture.idx.size();
        // Pooled: acquire returns capacity ≥ requested (power-of-two bucket); we write only the exact
        // prefix and pass exact counts to the BLAS/geom-table, so the unused tail is harmless.
        String label = entityId >= 0 ? "entity " + entityId : "entity mesh " + build.count;
        RtBuffer positions = pool.acquire(ctx, (long) capture.verts.size() * Float.BYTES, asInput, true,
                label + " positions");
        RtBuffer indices = pool.acquire(ctx, (long) capture.idx.size() * Integer.BYTES, asInput | storage, true,
                label + " indices");
        RtBuffer uvs = pool.acquire(ctx, (long) capture.uvList.size() * Float.BYTES, storage, true,
                label + " uvs");
        RtBuffer prim = pool.acquire(ctx, (long) capture.prim.size() * Float.BYTES, storage, true,
                label + " prim");
        MemoryUtil.memFloatBuffer(positions.mapped, capture.verts.size()).put(capture.verts.elements(), 0, capture.verts.size());
        MemoryUtil.memIntBuffer(indices.mapped, capture.idx.size()).put(capture.idx.elements(), 0, capture.idx.size());
        MemoryUtil.memFloatBuffer(uvs.mapped, capture.uvList.size()).put(capture.uvList.elements(), 0, capture.uvList.size());
        MemoryUtil.memFloatBuffer(prim.mapped, capture.prim.size()).put(capture.prim.elements(), 0, capture.prim.size());

        // Non-opaque so world.rahit alpha-tests the texture (cutout). Opaque texels pass to the chit.
        RtAccel accel;
        if (REFIT && entityId >= 0) {
            accel = refitOrBuild(ctx, build, entityId, positions, indices, vertCount, idxCount, label);
        } else {
            RtAccel.PreparedBlas blas = RtAccel.prepareTrianglesBlasPooled(ctx, pool, positions, vertCount, indices, idxCount, false,
                    label + " BLAS");
            build.blas.add(blas);
            build.pooledBlas.add(blas);
            accel = blas.accel;
        }

        long dispAddr = uploadDisp(ctx, build, disp, "entity " + entityId);
        writeTableEntry(build, prim.deviceAddress, indices.deviceAddress, uvs.deviceAddress, dispAddr);

        build.instances.add(new RtAccel.Instance(IDENTITY, accel.deviceAddress, instanceBit | (build.count & 0x3FFFFF), mask));
        build.buffers.add(positions);
        build.buffers.add(indices);
        build.buffers.add(uvs);
        build.buffers.add(prim);
        build.count++;
    }

    /** Upload a per-vertex displacement array to a pooled per-frame buffer; returns its address (0 if null). */
    private long uploadDisp(RtContext ctx, FrameBuild build, float[] disp, String label) {
        if (disp == null) {
            return 0L;
        }
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        RtBuffer dispBuf = pool.acquire(ctx, (long) disp.length * Float.BYTES, storage, true, label + " disp");
        MemoryUtil.memFloatBuffer(dispBuf.mapped, disp.length).put(disp, 0, disp.length);
        build.buffers.add(dispBuf);
        return dispBuf.deviceAddress;
    }

    /** Write one entity geometry-table entry at the current build slot: {primAddr, idxAddr, uvAddr, dispAddr}. */
    private void writeTableEntry(FrameBuild build, long primAddr, long idxAddr, long uvAddr, long dispAddr) {
        long entry = build.tableBase + (long) build.count * TABLE_ENTRY_BYTES;
        MemoryUtil.memPutLong(entry, primAddr);
        MemoryUtil.memPutLong(entry + 8, idxAddr);
        MemoryUtil.memPutLong(entry + 16, uvAddr);
        MemoryUtil.memPutLong(entry + 24, dispAddr);
    }

    /**
     * Refit-or-build this entity's persistent acceleration structure. Cycles the entity's ring to a slot
     * that is off all queues, then records an in-place UPDATE (cheap refit) when the slot already holds an
     * AS of the same topology, else a full ALLOW_UPDATE BUILD (first use of the slot, a topology change, or
     * the periodic BVH-quality rebuild). The mesh buffers + scratch are per-frame transients; the AS persists.
     */
    private RtAccel refitOrBuild(RtContext ctx, FrameBuild build, int entityId, RtBuffer positions, RtBuffer indices, int vertCount, int idxCount, String label) {
        int triCount = idxCount / 3;
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        EntityAccel ea = entityAccels.computeIfAbsent(entityId, k -> new EntityAccel());
        ea.lastSeen = RtComposite.frameCounter();
        int s = ea.cursor;
        ea.cursor = (ea.cursor + 1) % REFIT_RING;
        EntitySlot slot = ea.ring[s];
        boolean canUpdate = slot != null && slot.accel != null
                && slot.vertCount == vertCount && slot.triCount == triCount
                && slot.updatesSinceBuild < REFIT_REBUILD_INTERVAL;
        if (canUpdate) {
            RtBuffer scratch = pool.acquire(ctx, slot.updateScratchSize, storage, false, label + " refit scratch");
            build.blas.add(RtAccel.refitUpdate(slot.accel, scratch, positions.deviceAddress, indices.deviceAddress, vertCount, idxCount, false,
                    label + " BLAS refit"));
            build.refitScratch.add(scratch);
            slot.updatesSinceBuild++;
            return slot.accel;
        }
        // (Re)build: first use of this slot, a topology change, or the periodic rebuild. Retire the old AS
        // (off-queue by the horizon) and create a fresh updatable one sized for the current topology.
        if (slot == null) {
            slot = new EntitySlot();
            ea.ring[s] = slot;
        } else if (slot.accel != null) {
            deferDestroyAccel(slot.accel, slot.backing);
        }
        RtAccel.UpdatableBuild ub = RtAccel.prepareUpdatableBlasBuild(ctx, pool, positions, vertCount, indices, idxCount, false,
                label + " BLAS");
        slot.accel = ub.accel();
        slot.backing = ub.backing();
        slot.vertCount = vertCount;
        slot.triCount = triCount;
        slot.updateScratchSize = ub.updateScratchSize();
        slot.updatesSinceBuild = 0;
        build.blas.add(ub.op());
        build.refitScratch.add(ub.scratch()); // per-frame build scratch (the AS + backing persist in the ring)
        return slot.accel;
    }

    /** Retire a per-entity AS once it is off all queues; its backing buffer returns to the pool. */
    private void deferDestroyAccel(RtAccel accel, RtBuffer backing) {
        long freeAt = RtComposite.frameCounter() + KEEP_FRAMES;
        deferred.add(new Deferred(freeAt, () -> RtAccel.destroyPooledAccel(pool, accel, backing)));
    }

    /** Drop persistent AS for entities not captured within the last KEEP_FRAMES frames (off all queues). */
    private void evictStaleAccels() {
        if (entityAccels.isEmpty()) {
            return;
        }
        long now = RtComposite.frameCounter();
        java.util.Iterator<Map.Entry<Integer, EntityAccel>> it = entityAccels.entrySet().iterator();
        while (it.hasNext()) {
            EntityAccel ea = it.next().getValue();
            if (now - ea.lastSeen < KEEP_FRAMES) {
                continue;
            }
            for (EntitySlot slot : ea.ring) {
                if (slot != null && slot.accel != null) {
                    RtAccel.destroyPooledAccel(pool, slot.accel, slot.backing);
                    slot.accel = null;
                    slot.backing = null;
                }
            }
            it.remove();
        }
    }

    private void setCamera(double camX, double camY, double camZ, Matrix4f projection, Matrix4f viewRotation) {
        if (cameraState == null) {
            cameraState = new CameraRenderState();
        }
        cameraState.pos = new Vec3(camX, camY, camZ);
        cameraState.projectionMatrix.set(projection);
        cameraState.viewRotationMatrix.set(viewRotation);
        cameraState.orientation.setFromUnnormalized(viewRotation);
        cameraState.initialized = true;
    }

    private void ensureResources(RtContext ctx) {
        if (tableRing != null) {
            return;
        }
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        tableRing = new RtBuffer[TABLE_RING];
        for (int i = 0; i < TABLE_RING; i++) {
            tableRing[i] = ctx.createBuffer((long) MAX_ENTITIES * TABLE_ENTRY_BYTES, storage, true,
                    "entity geometry table ring " + i);
        }
    }

    private void processDeferred() {
        if (deferred.isEmpty()) {
            return;
        }
        long now = RtComposite.frameCounter();
        java.util.Iterator<Deferred> it = deferred.iterator();
        while (it.hasNext()) {
            Deferred d = it.next();
            if (d.freeFrame() <= now) {
                d.free().run();
                it.remove();
            }
        }
    }

    /** Free the geometry-table ring + any outstanding per-frame entity resources (teardown; GPU idle). */
    public void shutdown() {
        // Drain outstanding deferred releases first (they return buffers/AS to the pool), then destroy the
        // persistent per-entity AS, then the pool itself. Runs after waitIdle, so immediate destruction is safe.
        for (Deferred d : deferred) {
            d.free().run();
        }
        deferred.clear();
        for (EntityAccel ea : entityAccels.values()) {
            for (EntitySlot slot : ea.ring) {
                if (slot != null && slot.accel != null) {
                    RtAccel.destroyPooledAccel(pool, slot.accel, slot.backing);
                    slot.accel = null;
                    slot.backing = null;
                }
            }
        }
        entityAccels.clear();
        for (BeEntry e : beCache.values()) {
            RtAccel.destroyPooledAccel(pool, e.accel, e.backing);
            pool.release(e.positions);
            pool.release(e.indices);
            pool.release(e.uvs);
            pool.release(e.prim);
        }
        beCache.clear();
        pool.destroyAll();
        if (tableRing != null) {
            for (RtBuffer b : tableRing) {
                b.destroy();
            }
            tableRing = null;
        }
        prevVerts.clear();
    }
}
