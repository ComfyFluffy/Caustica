package dev.comfyfluffy.caustica.rt.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.mixin.ParticleEngineAccessor;
import dev.comfyfluffy.caustica.mixin.ParticleGroupAccessor;
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

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryUtil;

import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Dynamic entities as real ray-traced {@code ModelPart} geometry. Each frame, every model entity is
 * re-posed and captured ({@link RtEntityCollector} + {@link RtEntityCapture}) into a mesh in terrain's
 * vertex layout, uploaded, and given a per-entity BLAS built inline in the composite's frame command
 * buffer. One TLAS instance per entity places entity-local geometry at {@code anchor - rebase} and carries
 * the {@link #ENTITY_BIT} custom-index flag so {@code world.rchit} takes the
 * entity path. A per-frame entity geometry table ({@code {primAddr, idxAddr, uvAddr, disp}}) gives the
 * hit shader each entity's per-triangle normals/tint and its per-object motion-vector displacement.
 * Non-model entities (items/arrows — geometry via submitItem/submitBlockModel, which the collector
 * ignores) are skipped.
 *
 * <p>Per-frame cost is real (per-entity capture + buffer uploads + a BLAS build); capped by {@code
 * -Dcaustica.rt.maxEntities}. Changed-entity geometry and refit scratch reuse the existing per-entity
 * frames-in-flight ring; motion uploads suballocate from a per-frame-slot arena. A generic size-bucketed
 * recycling free-list was tried and measured slower per-call than trusting VMA's own allocator.
 */
public final class RtEntities {
    public static final RtEntities INSTANCE = new RtEntities();
    public static boolean enabled() {
        return CausticaConfig.Rt.Entities.ENABLED.value();
    }

    /** Custom-index flag bit (bit 23 of the 24-bit instanceCustomIndex) marking an entity instance. */
    public static final int ENTITY_BIT = 0x800000;
    /** Custom-index flag (bit 22) marking a particle billboard instance (shares the entity geom table). */
    public static final int PARTICLE_BIT = 0x400000;
    // TLAS visibility-mask bits, ANDed against the per-ray cull mask in world.rgen. Bit 0 = secondary rays
    // (shadows / GI / reflections, CULL_SECONDARY); bit 1 = the primary camera ray (CULL_PRIMARY).
    private static final int MASK_SECONDARY = 0x01;
    private static final int MASK_PRIMARY = 0x02;
    /** Default mask: visible to every ray (terrain and ordinary entities use this). */
    private static final int MASK_ALL = 0xFF;
    /** Particles are primary-ray-only: visible/lit by the camera path, invisible to shadows/GI/reflections. */
    private static final int PARTICLE_MASK = MASK_PRIMARY;
    public static boolean particlesEnabled() {
        return CausticaConfig.Rt.Entities.PARTICLES_ENABLED.value();
    }
    public static boolean glowEnabled() {
        return CausticaConfig.Rt.Entities.GLOW_ENABLED.value();
    }
    public static boolean nameTagsEnabled() {
        return CausticaConfig.Rt.Entities.NAME_TAGS_ENABLED.value();
    }

    private static int maxEntities() {
        return CausticaConfig.Rt.Entities.MAX_ENTITIES.value();
    }

    private static int maxOtherEntities() {
        return CausticaConfig.Rt.Entities.MAX_OTHER_ENTITIES.value();
    }

    private static int maxBlockEntities() {
        return CausticaConfig.Rt.Entities.MAX_BLOCK_ENTITIES.value();
    }

    private static int maxParticles() {
        return CausticaConfig.Rt.Entities.MAX_PARTICLES.value();
    }

    private static int entityListCapacity() {
        return CausticaConfig.Rt.Entities.entityListCapacity();
    }

    private static int entityMapCapacity() {
        return CausticaConfig.Rt.Entities.entityMapCapacity();
    }

    // Chunk radius around the player to scan for block entities (chests/signs/…) each frame.
    private static int beViewChunks() {
        return CausticaConfig.Rt.Entities.BE_VIEW_CHUNKS.value();
    }

    // Block entities keep a cached mesh + BLAS keyed by BlockPos. Each frame the BE is re-meshed (cheap)
    // and its mesh hashed; the expensive BLAS is rebuilt ONLY when the mesh actually changed — so static
    // BEs cost no GPU work while animating ones (chest lid, spawner, …) rebuild every frame. New/changed
    // rebuilds are capped per frame so a burst of newly loaded chunks can't stall (over-budget BEs keep
    // their last geometry / pop in over later frames, like terrain's worker dispatch budget).
    private static int beBuildsPerFrame() {
        return CausticaConfig.Rt.Entities.BE_BUILDS_PER_FRAME.value();
    }

    // Entity geometry table entry: {u64 primAddr, u64 idxAddr, u64 uvAddr, u64 dispAddr, vec4 rigidDisp}
    // = 48 bytes (std430 vec4 forces 16-align/48-size). dispAddr points at a per-vertex world-space
    // displacement buffer; when it is 0, rigidDisp.xyz carries whole-object motion (or zero).
    private static final int TABLE_ENTRY_BYTES = 48;
    // Ring of fixed-size geometry tables: each frame fills the next slot so the GPU read of this frame's
    // trace never races a later frame's host write. > frames-in-flight (mirrors RtPipeline RING).
    private static final int TABLE_RING = 6;
    // Frames a superseded cache or per-frame entity resource must outlive before it is freed.
    private static final int KEEP_FRAMES = 4;
    private static final int FRAME_LIST_RING = KEEP_FRAMES;
    // Refit (UPDATE-mode) BLAS: persistent per-entity AS, refit in place each frame (cheap) while
    // topology is stable, instead of a full BUILD. Block entities always use the pooled-BUILD path.
    //
    // Rigid reuse: when this frame's capture is a rigid transform (translation and/or yaw) of the mesh the
    // entity's AS was last built from, reference that AS with the fitted TLAS instance transform and skip
    // the mesh upload + refit entirely — still mobs, item frames/armor stands, and spinning/bobbing
    // dropped items become table-entry + instance writes only.

    // Max per-axis residual (blocks) for a capture to count as a rigid transform of the reference mesh.
    // Well below a texel (1/16 block) and DLSS-RR jitter; float pose math noise is ~1e-5.
    private static final float RIGID_FIT_EPS = 2.0e-3f;

    // Per-entity ring depth: a slot is reused every REFIT_RING frames, so it must be off all queues by
    // then. = KEEP_FRAMES (the established frames-in-flight-safe horizon). Each slot holds one persistent AS.
    private static final int REFIT_RING = KEEP_FRAMES;
    // Force a periodic full rebuild of a slot's AS to bound BVH-quality degradation from repeated refits
    // (an entity that deforms a lot would otherwise refit the same BVH topology forever). Per-slot count.
    private static int refitRebuildInterval() {
        return CausticaConfig.Rt.Entities.REFIT_REBUILD_INTERVAL.value();
    }

    // Treat per-vertex displacements as rigid when every vertex agrees within this tolerance, avoiding a
    // transient disp buffer for plain whole-entity translation.
    private static final float RIGID_DISP_EPS = 1.0e-5f;
    private static final long MOTION_PAGE_BYTES = 1L << 20;
    private static final long MOTION_ALIGNMENT = 16L;
    private static final int MOTION_UNUSED_RETIRE_CYCLES = 16;
    private static final int TRANSIENT_BUFFER_LIST_CAPACITY = 8;
    // Identity 3x4 row-major. Particles are already captured in rebased space; dynamic entities use a
    // translate/yaw instance transform because their geometry is captured around the entity anchor.
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
    private static final Motion NO_MOTION = new Motion(0L, 0f, 0f, 0f);

    // Reusable capture pipeline (single-threaded on the render thread).
    private final RtEntityCollector collector = new RtEntityCollector();
    private final RtEntityCapture capture = new RtEntityCapture();
    private final PoseStack entityPoseStack = new PoseStack();
    private CameraRenderState cameraState;
    // Particle capture: a VertexConsumer adapter that funnels MC's billboard quads into `capture` (the
    // shared entity mesh). We extract each live particle into `particleScratch`, accumulate per-vertex
    // motion-vector displacements in `particleDisp`, and key the previous-frame center off particle
    // identity in `particlePrev` (rebuilt each frame → prunes dead particles).
    private final RtParticleCapture particleCapture = new RtParticleCapture(capture);
    private final QuadParticleRenderState particleScratch = new QuadParticleRenderState();
    private final FloatArrayList particleDisp = new FloatArrayList();
    private IdentityHashMap<Particle, ParticlePrev> particlePrev = new IdentityHashMap<>();
    private IdentityHashMap<Particle, ParticlePrev> particleCur = new IdentityHashMap<>();
    private final float[] particleCenterScratch = new float[3];

    /** Previous frame's particle center (rebase-space) + that frame's rebase origin, for the MV diff. */
    private record ParticlePrev(float cx, float cy, float cz, int rbx, int rby, int rbz) {
    }

    private RtBuffer[] tableRing;
    private int tableCapacity;
    private int tableSlot;

    private final FrameLists[] frameLists = new FrameLists[FRAME_LIST_RING];

    // Previous frame's captured entity-local vertex positions + its interpolated world anchor, keyed by
    // entity id. Maps are swapped/reused each frame: entries not seen this frame fall out, while visible
    // entities keep their float[] backing to avoid steady-state allocation churn.
    private Int2ObjectOpenHashMap<EntityPrev> prevVerts = new Int2ObjectOpenHashMap<>(entityMapCapacity());
    private Int2ObjectOpenHashMap<EntityPrev> curVerts = new Int2ObjectOpenHashMap<>(entityMapCapacity());

    // This frame's glowing entities (see GlowEntity) + the camera-relative offset (camera pos - rebase
    // origin) their positions are captured against, for RtGlowOutlineFeature's raster pass. Rebuilt every frame.
    private final List<GlowEntity> glowBatches = new ArrayList<>();
    private float glowCamOffsetX, glowCamOffsetY, glowCamOffsetZ;

    /** This frame's glowing entities, or an empty list if none (or glow is disabled). */
    public List<GlowEntity> glowBatches() {
        return glowBatches;
    }

    public float glowCamOffsetX() {
        return glowCamOffsetX;
    }

    public float glowCamOffsetY() {
        return glowCamOffsetY;
    }

    public float glowCamOffsetZ() {
        return glowCamOffsetZ;
    }

    // This frame's visible name tags (see NameTagEntity), captured off the SAME EntityRenderState vanilla's
    // own EntityRenderer.extractNameTags already populates (shouldShowName/crosshair-look/distance rules,
    // computed as a side effect of the dispatcher.extractEntity call captureEntities already makes) — no
    // reimplementation of that logic. Positions are rebase-space (same convention as glowBatches); consumed
    // by RtNameTagFeature's raster pass, which reuses glowCamOffset{X,Y,Z} (same camera, same frame).
    private final List<NameTagEntity> nameTagBatches = new ArrayList<>();

    /** This frame's visible name tags, or an empty list if none (or name tags are disabled). */
    public List<NameTagEntity> nameTagBatches() {
        return nameTagBatches;
    }

    /** This frame's camera orientation (view-to-world rotation) — the billboard rotation name tags face. */
    public Quaternionf cameraOrientation() {
        return cameraState.orientation;
    }

    /** Last frame's posed mesh for one entity: local vertex positions + its interpolated world anchor. */
    private static final class EntityPrev {
        float[] verts = new float[0];
        int size;
        float anchorX, anchorY, anchorZ;
    }

    // Per-frame entity GPU resources awaiting a frames-in-flight-safe free.
    private final List<Deferred> deferred = new ArrayList<>();
    private long retainedGeometryBytes;

    // Persistent per-entity acceleration structures, keyed by entity id, for refit.
    private final Int2ObjectOpenHashMap<EntityAccel> entityAccels = new Int2ObjectOpenHashMap<>(entityMapCapacity());

    // Persistent per-block-entity geometry, keyed by BlockPos.asLong(). Built once and reused every frame.
    private final Map<Long, BeEntry> beCache = new HashMap<>();
    private final List<BeCandidate> beCandidates = new ArrayList<>();
    // (Re)builds recorded so far this frame, reset each beginFrame; gates new BE builds to BE_BUILDS_PER_FRAME.
    private int beBuildsThisFrame;

    private RtEntities() {
        for (int i = 0; i < frameLists.length; i++) {
            frameLists[i] = new FrameLists();
        }
    }

    /**
     * Cached block-entity geometry. The mesh is captured in <b>block-local</b> space (identity submit pose),
     * so it is rebase-independent — only the per-frame TLAS instance transform ({@code blockPos − rebase})
     * changes, exactly like a terrain section. The BLAS + mesh buffers are this entry's own VMA allocations
     * and persist until the BE is evicted (out of window / unloaded) or rebuilt (its mesh changed);
     * {@code idx/uv/prim} are read by the hit shader every frame via the geometry table, so they must stay
     * alive while traced.
     */
    private static final class BeEntry {
        RtAccel accel;
        RtBuffer backing;                        // this entry's own AS backing
        RtBuffer positions, indices, uvs, prim;  // this entry's own mesh buffers
        int bx, by, bz;                          // block position (drives the per-frame instance transform)
        long meshHash;                           // hash of the captured mesh — rebuild only when it changes
        long lastSeen;                           // last frame this BE was in the scan window — for eviction
        float[] prevVerts;                       // block-local verts at this build, for the per-vertex MV diff
    }

    /** One persistent updatable AS in an entity's ring: its own backing buffer + the topology it
     *  was built for (refit is valid only while vert/tri counts are unchanged) + refit bookkeeping. */
    private static final class EntitySlot {
        EntityAccel owner;
        RtAccel accel;
        RtBuffer backing;
        RtBuffer geometry;
        RtBuffer refitScratch;
        int vertCount = -1;
        int triCount = -1;
        long updateScratchSize;
        int updatesSinceBuild;
    }

    /** A per-entity ring of {@link EntitySlot}s, cycled one-per-frame so a refit never writes an AS still
     *  in flight, plus the last frame the entity was captured (drives eviction). Also holds the rigid-reuse
     *  reference: the entity-local mesh contents of the most recently written AS and the
     *  cache-owned shading buffers the geometry table points at on reuse frames. */
    private static final class EntityAccel {
        final EntitySlot[] ring = new EntitySlot[REFIT_RING];
        int cursor;
        long lastSeen;
        // Rigid-reuse reference (refAccel == null → no reusable build yet). refVerts are the exact
        // positions the AS was last built/refit from; a frame whose capture is a rigid transform of them
        // reuses the AS via the TLAS instance transform instead of re-uploading + refitting. Reuse frames
        // only READ the AS, so referencing the last-written ring slot while it is in flight is safe.
        RtAccel refAccel;
        float[] refVerts;
        int refVertCount = -1;
        int refIdxCount = -1;
        long refShadeHash;                      // rotation-invariant uv+prim hash (catches tint/sprite swaps)
        long refIndexAddr, refUvAddr, refPrimAddr;
        long retryYawFitAfter;
    }

    /** This frame's entity contribution: the full instance list (terrain + entities), the entity BLAS to
     *  build inline this frame, and the geometry-table device address the hit shader reads. */
    public record FrameEntities(List<RtAccel.Instance> instances, List<RtAccel.PreparedBlas> blas, long geomTableAddr) {
    }

    /** One glowing entity's body mesh (rebased-space positions, copied out of {@link #capture} before the
     *  next entity resets it) plus its vanilla outline colour, for {@code RtGlowOutlineFeature}'s full-res raster
     *  mask pass. Captured as a side effect of the normal RT capture — no extra posing/animation work. */
    public record GlowEntity(float[] verts, int[] idx, int color) {
    }

    /** One visible name tag: display text + the attachment point's world position (rebase-space, same
     *  convention as entity capture — see {@link RtEntities#glowCamOffsetX()} for the camera-relative
     *  offset needed to finish the transform to camera-relative space). */
    public record NameTagEntity(Component text, float x, float y, float z) {
    }

    private record Deferred(long freeFrame, Runnable free) {
    }

    private record Motion(long dispAddr, float rigidX, float rigidY, float rigidZ) {
    }

    private static final class MotionSlice {
        RtBuffer buffer;
        long offset;
        long mapped;
        long deviceAddress;
        long size;

        MotionSlice set(RtBuffer buffer, long offset, long size) {
            this.buffer = buffer;
            this.offset = offset;
            this.mapped = buffer.mapped + offset;
            this.deviceAddress = buffer.deviceAddress + offset;
            this.size = size;
            return this;
        }

        void flush() {
            buffer.flush(offset, size);
        }
    }

    /** Host-visible storage pages owned by one frames-in-flight slot and reused when that slot retires. */
    private static final class MotionArena {
        private final ArrayList<RtBuffer> pages = new ArrayList<>();
        private final IntArrayList lastUsedCycles = new IntArrayList();
        private final MotionSlice slice = new MotionSlice();
        private int pageIndex;
        private long offset;
        private int cycle;

        void reset() {
            cycle++;
            for (int i = pages.size() - 1; i >= 0; i--) {
                if (cycle - lastUsedCycles.getInt(i) >= MOTION_UNUSED_RETIRE_CYCLES) {
                    pages.remove(i).destroy();
                    lastUsedCycles.removeInt(i);
                }
            }
            pageIndex = 0;
            offset = 0L;
        }

        MotionSlice allocate(RtContext ctx, long bytes) {
            long size = Math.max(bytes, MOTION_ALIGNMENT);
            while (true) {
                if (pageIndex == pages.size()) {
                    long capacity = Math.max(MOTION_PAGE_BYTES, size);
                    pages.add(ctx.createBuffer(capacity,
                            org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true,
                            "entity motion arena"));
                    lastUsedCycles.add(cycle);
                    RtFrameStats.FRAME.count("vmaBufferCreates", 1);
                    RtFrameStats.FRAME.count("entityVmaBufferCreates", 1);
                }
                RtBuffer page = pages.get(pageIndex);
                long aligned = alignUp(offset, MOTION_ALIGNMENT);
                if (size <= page.size - aligned) {
                    lastUsedCycles.set(pageIndex, cycle);
                    offset = Math.addExact(aligned, size);
                    return slice.set(page, aligned, size);
                }
                pageIndex++;
                offset = 0L;
            }
        }

        void destroy() {
            for (RtBuffer page : pages) {
                page.destroy();
            }
            pages.clear();
            lastUsedCycles.clear();
        }
    }

    private static long alignUp(long value, long alignment) {
        return Math.addExact(value, alignment - 1L) & -alignment;
    }

    private record EntityGeometryLayout(long positionOffset, long indexOffset,
                                        long uvOffset, long primOffset,
                                        long logicalBytes, long totalBytes) {
        private static final long REGION_ALIGNMENT = 16L;

        static EntityGeometryLayout create(int positionFloats, int indexInts, int uvFloats, int primFloats) {
            long positionBytes = Math.multiplyExact((long) positionFloats, Float.BYTES);
            long indexBytes = Math.multiplyExact((long) indexInts, Integer.BYTES);
            long uvBytes = Math.multiplyExact((long) uvFloats, Float.BYTES);
            long primBytes = Math.multiplyExact((long) primFloats, Float.BYTES);
            long indexOffset = alignUp(positionBytes);
            long uvOffset = alignUp(Math.addExact(indexOffset, indexBytes));
            long primOffset = alignUp(Math.addExact(uvOffset, uvBytes));
            long totalBytes = alignUp(Math.addExact(primOffset, primBytes));
            long logicalBytes = Math.addExact(Math.addExact(positionBytes, indexBytes),
                    Math.addExact(uvBytes, primBytes));
            return new EntityGeometryLayout(0L, indexOffset, uvOffset, primOffset,
                    logicalBytes, totalBytes);
        }

        private static long alignUp(long value) {
            return Math.addExact(value, REGION_ALIGNMENT - 1L) & -REGION_ALIGNMENT;
        }

        EntityGeometryLayout shifted(long baseOffset) {
            if (baseOffset < 0L || baseOffset >= REGION_ALIGNMENT) {
                throw new IllegalArgumentException("Invalid entity geometry base offset: " + baseOffset);
            }
            return new EntityGeometryLayout(
                    Math.addExact(positionOffset, baseOffset),
                    Math.addExact(indexOffset, baseOffset), Math.addExact(uvOffset, baseOffset),
                    Math.addExact(primOffset, baseOffset), logicalBytes, Math.addExact(totalBytes, baseOffset));
        }
    }

    private record BeCandidate(BlockEntity be, double dist2, long posKey) {
    }

    /** Read-only base terrain instances plus this frame's appended dynamic instances. */
    private static final class FrameInstanceList extends AbstractList<RtAccel.Instance> {
        private List<RtAccel.Instance> base = List.of();
        private final ArrayList<RtAccel.Instance> dynamic = new ArrayList<>(entityListCapacity());

        void reset(List<RtAccel.Instance> base) {
            this.base = base;
            dynamic.clear();
        }

        void release() {
            base = List.of();
            dynamic.clear();
        }

        @Override
        public RtAccel.Instance get(int index) {
            int baseSize = base.size();
            return index < baseSize ? base.get(index) : dynamic.get(index - baseSize);
        }

        @Override
        public int size() {
            return base.size() + dynamic.size();
        }

        @Override
        public boolean add(RtAccel.Instance instance) {
            dynamic.add(instance);
            modCount++;
            return true;
        }
    }

    /** Reused per-frame lists; one slot is retired before it can be selected again. */
    private static final class FrameLists {
        final FrameInstanceList instances = new FrameInstanceList();
        final ArrayList<RtAccel.PreparedBlas> blas = new ArrayList<>(entityListCapacity());
        final ArrayList<RtAccel.PreparedBlas> pooledBlas = new ArrayList<>(entityListCapacity());
        final ArrayList<RtBuffer> refitScratch = new ArrayList<>(entityListCapacity());
        final ArrayList<RtBuffer> buffers = new ArrayList<>(TRANSIENT_BUFFER_LIST_CAPACITY);
        final MotionArena motion = new MotionArena();

        void reset(List<RtAccel.Instance> base) {
            instances.reset(base);
            blas.clear();
            pooledBlas.clear();
            refitScratch.clear();
            buffers.clear();
            motion.reset();
        }

        void releaseDeferred() {
            for (RtAccel.PreparedBlas b : pooledBlas) {
                RtAccel.releaseEntityBlas(b);
            }
            for (RtBuffer s : refitScratch) {
                s.destroy();
            }
            for (RtBuffer buf : buffers) {
                buf.destroy();
            }
            instances.release();
            blas.clear();
            pooledBlas.clear();
            refitScratch.clear();
            buffers.clear();
        }

        void destroyPersistent() {
            motion.destroy();
        }
    }

    /** Mutable per-frame build state shared by the entity + block-entity capture passes. */
    private final class FrameBuild {
        final List<RtAccel.Instance> base;
        FrameLists lists;
        List<RtAccel.Instance> instances;
        List<RtAccel.PreparedBlas> blas;        // all BLAS ops to record this frame (BUILD + refit UPDATE)
        List<RtAccel.PreparedBlas> pooledBlas;  // transient one-shot entity BLAS ops → releaseEntityBlas
        List<RtBuffer> refitScratch;            // per-frame scratch from refit ops → destroy() (AS persists)
        List<RtBuffer> buffers;                 // transient motion/particle buffers → destroy()
        MotionArena motion;                     // suballocated entity/BE/particle displacement uploads
        long tableBase;
        long geomTableAddr;
        int count;        // geometry-table entries / TLAS instances
        int logicalCount; // ordinary entities + block entities + individual particles

        FrameBuild(List<RtAccel.Instance> base) {
            this.base = base;
        }

        boolean full() {
            return logicalCount >= maxEntities();
        }
    }

    /**
     * Capture this frame's model entities + block entities into per-object meshes/BLAS and merge them
     * with the terrain static instances. The caller (RtComposite) records the returned BLAS builds
     * before the TLAS build and pushes the geometry-table address. Returns terrain-only (no BLAS, addr 0)
     * when disabled or nothing captured. Dynamic entity coordinates are local and placed by TLAS instances;
     * particles remain captured rebase-relative with an identity instance.
     */
    public FrameEntities beginFrame(RtContext ctx, List<RtAccel.Instance> base, int rbx, int rby, int rbz,
                                    double camX, double camY, double camZ, Matrix4f projection, Matrix4f viewRotation) {
        processDeferred();
        if (!enabled()) {
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
        try {
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("entity.capture")) {
                captureEntities(ctx, build, mc, level, partial, rbx, rby, rbz);
            }
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("entity.blockEntities")) {
                captureBlockEntities(ctx, build, mc, level, partial, rbx, rby, rbz);
            }
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("entity.particles")) {
                captureParticles(ctx, build, mc, partial, rbx, rby, rbz, projection, viewRotation);
            }
        } catch (RuntimeException | Error t) {
            // A partially recorded frame may already have installed unbuilt BLAS into persistent slots.
            // Quiesce old frames and drop the entity cache before propagating the original failure.
            ctx.waitIdle();
            shutdown();
            throw t;
        }
        evictStaleAccels();
        evictStaleBes();
        RtFrameStats.FRAME.count("entityRetainedGeometryBytes", retainedGeometryBytes);

        if (build.instances == null) {
            return new FrameEntities(base, List.of(), 0L);
        }
        // Retire this frame's transient meshes + scratch + pooled-BUILD BLAS once it is no longer in flight
        // (their build + the trace that reads them must complete first). Refit AS persist in entityAccels.
        long freeAt = RtComposite.frameCounter() + KEEP_FRAMES;
        FrameLists listsForFree = build.lists;
        deferred.add(new Deferred(freeAt, () -> {
            // The deferred horizon guarantees these are off all queues, so destroying them now is safe.
            listsForFree.releaseDeferred();
        }));
        return new FrameEntities(build.instances, build.blas, build.geomTableAddr);
    }

    /** Capture animated entities (mobs, items, falling blocks) with per-object motion-vector displacement. */
    private void captureEntities(RtContext ctx, FrameBuild build, Minecraft mc, ClientLevel level, float partial, int rbx, int rby, int rbz) {
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        Entity cameraEntity = mc.getCameraEntity();
        // In first person the camera owner's own body must not block the primary camera ray, but it should
        // still appear in reflections / shadows / GI (so the player sees themselves in water as others would).
        // In F5 third person it renders fully, like any other entity.
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        curVerts.clear();
        glowBatches.clear();
        nameTagBatches.clear();
        boolean glow = glowEnabled();
        boolean nameTags = nameTagsEnabled();
        glowCamOffsetX = (float) (cameraState.pos.x - rbx);
        glowCamOffsetY = (float) (cameraState.pos.y - rby);
        glowCamOffsetZ = (float) (cameraState.pos.z - rbz);
        resetPoseStack(entityPoseStack);
        int capturedThisFrame = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (build.full() || capturedThisFrame >= maxOtherEntities()) {
                break;
            }
            if (entity.isInvisible()) {
                continue;
            }
            boolean firstPersonSelf = entity == cameraEntity && firstPerson;
            int mask = firstPersonSelf ? MASK_SECONDARY : MASK_ALL;
            float ix;
            float iy;
            float iz;
            int id = entity.getId();
            EntityPrev prev = prevVerts.get(id);
            capture.reset(prev != null ? prev.size / 3 : 0);
            try {
                EntityRenderState state;
                long extractStart = RtFrameStats.FRAME.startStage();
                try {
                    state = dispatcher.extractEntity(entity, partial);
                } finally {
                    RtFrameStats.FRAME.endStage("entity.capture.extract", extractStart);
                }
                // Derive placement from the extracted state so the submitted pose and TLAS anchor use the
                // same interpolation result.
                ix = (float) state.x;
                iy = (float) state.y;
                iz = (float) state.z;
                // extractEntity already ran EntityRenderer.extractNameTags (shouldShowName, crosshair-look,
                // distance cutoff, the attachment point) as a normal part of building the render state — no
                // need to reimplement any of that here, just read the result. Name tags billboard to face
                // the camera every frame (see RtNameTagFeature), so — unlike glow, whose mesh is captured
                // straight into the SAME rigid entity mesh used for the BLAS — they are never mixed into
                // `capture`: doing so would make every frame's mesh a non-rigid transform of the last
                // whenever the camera turns, defeating rigid-reuse/motion-vector fitting for every
                // name-tagged entity. RtWorldOverlay renders them in a completely separate raster pass.
                if (nameTags && !firstPersonSelf && state.nameTag != null) {
                    captureNameTag(level, state, ix, iy, iz, rbx, rby, rbz);
                }
                collector.begin(capture, true);
                resetPoseStack(entityPoseStack);
                // Capture around the entity anchor. Per-frame placement moves into the TLAS instance,
                // so ordinary world translation no longer changes the mesh or its float precision.
                long submitStart = RtFrameStats.FRAME.startStage();
                try {
                    dispatcher.submit(state, cameraState, 0.0, 0.0, 0.0, entityPoseStack, collector);
                } finally {
                    RtFrameStats.FRAME.endStage("entity.capture.submit", submitStart);
                }
            } catch (Throwable t) {
                // Fail loud instead of skip-and-limp: a capture throw here is almost always our bug, and
                // swallowing it leaves the entity invisible every frame plus a per-frame MC CrashReport.
                // Propagate to composite(), which logs the full trace, disables RT, and reverts to vanilla.
                throw new RuntimeException("RT entity capture failed", t);
            } finally {
                collector.begin(null, false);
                resetPoseStack(entityPoseStack);
            }
            if (capture.isEmpty()) {
                continue; // non-model entity (arrow/etc.) — no body geometry captured
            }
            if (glow && !firstPersonSelf) {
                // Vanilla never draws the local player's own body in first person (no model to outline —
                // only the held-item hand), so it never shows the Glowing outline on yourself either. Our
                // capture still meshes the first-person self (for reflections/shadows/GI), so the glow mask
                // must explicitly skip it to match — otherwise it'd show an outline vanilla never would.
                int glowColor = collector.outlineColor();
                if (glowColor != 0) {
                    glowBatches.add(new GlowEntity(copyTranslatedVertices(capture.verts,
                            ix - rbx, iy - rby, iz - rbz), capture.idx.toIntArray(), glowColor));
                }
            }
            // Motion vs last frame's posed mesh. New/topology-changed entities get one frame of camera-only
            // MV; rigid translation is packed into the table, deformation gets a disp buffer.
            Motion motion;
            long motionStart = RtFrameStats.FRAME.startStage();
            try {
                motion = uploadVertexMotion(ctx, build, capture.verts, prev, ix, iy, iz);
            } finally {
                RtFrameStats.FRAME.endStage("entity.capture.motion", motionStart);
            }
            curVerts.put(id, storeEntityPrev(prev, capture.verts, ix, iy, iz));
            // Rigid reuse first: a pose that is a rigid transform of the entity's last-built mesh
            // re-references that AS through the instance transform — no upload, no refit.
            boolean reused;
            long reuseStart = RtFrameStats.FRAME.startStage();
            try {
                reused = appendRigidReuse(ctx, build, motion, id, mask, ix - rbx, iy - rby, iz - rbz);
            } finally {
                RtFrameStats.FRAME.endStage("entity.capture.rigidReuse", reuseStart);
            }
            if (!reused) {
                appendCapture(ctx, build, motion, id, ENTITY_BIT, mask,
                        translationTransform(ix - rbx, iy - rby, iz - rbz));
            }
            build.logicalCount++;
            RtFrameStats.FRAME.count("entitiesCaptured", 1);
            capturedThisFrame++;
        }
        Int2ObjectOpenHashMap<EntityPrev> oldPrev = prevVerts;
        prevVerts = curVerts;
        curVerts = oldPrev;
    }

    private static void resetPoseStack(PoseStack poseStack) {
        while (!poseStack.isEmpty()) {
            poseStack.popPose();
        }
        poseStack.setIdentity();
    }

    private static float[] copyTranslatedVertices(FloatArrayList local, float tx, float ty, float tz) {
        float[] placed = new float[local.size()];
        float[] src = local.elements();
        for (int i = 0; i < local.size(); i += 3) {
            placed[i] = src[i] + tx;
            placed[i + 1] = src[i + 1] + ty;
            placed[i + 2] = src[i + 2] + tz;
        }
        return placed;
    }

    /**
     * Gather one entity's name tag (world position + text) into {@link #nameTagBatches}, unless a block is
     * in the way. {@code state.nameTagAttachment} is only non-null when {@code state.nameTag} is (both set
     * together in {@code EntityRenderer.extractNameTags}). Positions are world-space (unrebased) until the
     * very end, matching {@code level.clip}'s coordinate space; the rebase subtraction happens last.
     *
     * <p>Vanilla draws a translucent "ghost" copy of the tag through walls (see {@code
     * SubmitNodeCollection.submitNameTag}'s {@code seeThroughNameTags} phase) instead of hiding it — v1
     * here just hides occluded tags, a simplification to avoid a second draw/blend mode; revisit if that
     * turns out to look wrong in practice.
     */
    private void captureNameTag(ClientLevel level, EntityRenderState state, float ix, float iy, float iz,
                                 int rbx, int rby, int rbz) {
        Vec3 attach = state.nameTagAttachment;
        if (attach == null) {
            return;
        }
        double wx = ix + attach.x;
        double wy = iy + attach.y + 0.5;
        double wz = iz + attach.z;
        // The 5-arg (Vec3,Vec3,Block,Fluid,Entity) overload NPEs on a null entity (it unconditionally builds
        // an EntityCollisionContext via CollisionContext.of, which requireNonNulls it) — this raycast isn't
        // for any particular entity's own collision shape, so pass an empty CollisionContext directly.
        HitResult hit = level.clip(new ClipContext(cameraState.pos, new Vec3(wx, wy, wz),
                ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() != HitResult.Type.MISS) {
            return; // a block is between the camera and the tag
        }
        nameTagBatches.add(new NameTagEntity(state.nameTag,
                (float) wx - rbx, (float) wy - rby, (float) wz - rbz));
    }

    /**
     * Upload this entity's world-space motion-vector displacement. Captures are entity-local, so the delta
     * is {@code (anchorCur + vertexCur) - (anchorPrev + vertexPrev)}. If every vertex agrees,
     * store it as a rigid vector in the geometry-table entry; otherwise write a per-vertex {@code vec4}
     * buffer directly, avoiding the old intermediate {@code float[]}.
     */
    private Motion uploadVertexMotion(RtContext ctx, FrameBuild build, FloatArrayList cur,
                                      EntityPrev prev, float anchorX, float anchorY, float anchorZ) {
        if (prev == null || prev.size != cur.size()) {
            return NO_MOTION;
        }
        float[] curVerts = cur.elements();
        float[] prevVerts = prev.verts;
        float sx = anchorX - prev.anchorX;
        float sy = anchorY - prev.anchorY;
        float sz = anchorZ - prev.anchorZ;
        int vc = cur.size() / 3;
        if (vc == 0) {
            return NO_MOTION;
        }

        float dx0 = (curVerts[0] - prevVerts[0]) + sx;
        float dy0 = (curVerts[1] - prevVerts[1]) + sy;
        float dz0 = (curVerts[2] - prevVerts[2]) + sz;
        boolean rigid = true;
        for (int i = 1; i < vc; i++) {
            float dx = (curVerts[i * 3]     - prevVerts[i * 3])     + sx;
            float dy = (curVerts[i * 3 + 1] - prevVerts[i * 3 + 1]) + sy;
            float dz = (curVerts[i * 3 + 2] - prevVerts[i * 3 + 2]) + sz;
            if (Math.abs(dx - dx0) > RIGID_DISP_EPS
                    || Math.abs(dy - dy0) > RIGID_DISP_EPS
                    || Math.abs(dz - dz0) > RIGID_DISP_EPS) {
                rigid = false;
                break;
            }
        }
        if (rigid) {
            return new Motion(0L, dx0, dy0, dz0);
        }

        beginBuildIfNeeded(ctx, build);
        long bytes = (long) vc * 4L * Float.BYTES;
        MotionSlice disp = build.motion.allocate(ctx, bytes);
        long out = disp.mapped;
        for (int i = 0; i < vc; i++) {
            MemoryUtil.memPutFloat(out, (curVerts[i * 3] - prevVerts[i * 3]) + sx);
            MemoryUtil.memPutFloat(out + 4, (curVerts[i * 3 + 1] - prevVerts[i * 3 + 1]) + sy);
            MemoryUtil.memPutFloat(out + 8, (curVerts[i * 3 + 2] - prevVerts[i * 3 + 2]) + sz);
            MemoryUtil.memPutFloat(out + 12, 0f);
            out += 16;
        }
        disp.flush();
        RtFrameStats.FRAME.count("entityMotionUploadBytes", bytes);
        return new Motion(disp.deviceAddress, 0f, 0f, 0f);
    }

    private static EntityPrev storeEntityPrev(EntityPrev prev, FloatArrayList cur,
                                              float anchorX, float anchorY, float anchorZ) {
        EntityPrev out = prev != null ? prev : new EntityPrev();
        int size = cur.size();
        if (out.verts.length < size) {
            out.verts = new float[size];
        }
        System.arraycopy(cur.elements(), 0, out.verts, 0, size);
        out.size = size;
        out.anchorX = anchorX;
        out.anchorY = anchorY;
        out.anchorZ = anchorZ;
        return out;
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
     * Capture this frame's billboard particles as ONE combined mesh + BLAS (cutout, camera-only receiver),
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
        int particleLimit = maxParticles();
        if (!particlesEnabled() || particleLimit == 0 || build.full()) {
            particlePrev.clear();
            particleCur.clear();
            return;
        }
        Camera cam = mc.gameRenderer.mainCamera();
        if (cam == null) {
            return;
        }
        Map<ParticleRenderType, ParticleGroup<?>> groups =
                ((ParticleEngineAccessor) mc.particleEngine).caustica$getParticleGroups();
        if (groups == null || groups.isEmpty()) {
            particlePrev.clear();
            particleCur.clear();
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
        IdentityHashMap<Particle, ParticlePrev> cur = particleCur;
        cur.clear();
        int particlesCaptured = 0;
        try {
            particleGroups:
            for (ParticleGroup<?> group : groups.values()) {
                Queue<? extends Particle> queue = ((ParticleGroupAccessor) group).caustica$getParticles();
                for (Particle p : queue) {
                    if (build.full() || particlesCaptured >= particleLimit) {
                        break particleGroups;
                    }
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
                    particleCenter(vertBefore, vertAfter, particleCenterScratch);
                    // pointInFrustum wants the world position: rebased center + rebase origin.
                    if (!frustum.pointInFrustum(particleCenterScratch[0] + rbx, particleCenterScratch[1] + rby, particleCenterScratch[2] + rbz)) {
                        capture.verts.size(vb); // off-screen → truncate this particle back out (clean quad boundary)
                        capture.idx.size(ib);
                        capture.uvList.size(ub);
                        capture.prim.size(prb);
                        continue;
                    }
                    appendParticleMv(p, particleCenterScratch, vertBefore, vertAfter, rbx, rby, rbz, cur);
                    build.logicalCount++;
                    particlesCaptured++;
                }
            }
        } catch (Throwable t) {
            capture.reset();
            particleDisp.clear();
            throw new RuntimeException("RT particle capture failed", t); // propagate to composite() (see entity path)
        }
        RtFrameStats.FRAME.count("particlesCaptured", particlesCaptured);
        IdentityHashMap<Particle, ParticlePrev> oldPrev = particlePrev;
        particlePrev = cur;
        particleCur = oldPrev;
        if (capture.isEmpty()) {
            return;
        }
        float[] disp = java.util.Arrays.copyOf(particleDisp.elements(), particleDisp.size());
        appendCapture(ctx, build, disp, -1, PARTICLE_BIT, PARTICLE_MASK); // one combined mesh, per-particle MV
    }

    /** Average (rebase-space) position of a captured particle's verts — approximates the particle center. */
    private void particleCenter(int vertBefore, int vertAfter, float[] out) {
        float[] v = capture.verts.elements();
        float cx = 0f, cy = 0f, cz = 0f;
        for (int i = vertBefore; i < vertAfter; i++) {
            cx += v[i * 3];
            cy += v[i * 3 + 1];
            cz += v[i * 3 + 2];
        }
        int vc = vertAfter - vertBefore;
        out[0] = cx / vc;
        out[1] = cy / vc;
        out[2] = cz / vc;
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
     * Capture block entities (chests, signs, …). Each BE keeps a cached mesh + BLAS keyed by BlockPos.
     * Every frame the BE is re-meshed (cheap) and its mesh hashed; the expensive BLAS is rebuilt only when
     * the mesh actually changed — so static BEs cost no GPU work while animating ones (chest lid, spawner,
     * …) rebuild every frame and stay smooth. New/changed rebuilds are capped at {@link
     * #BE_BUILDS_PER_FRAME} per frame so a burst of newly loaded chunks can't stall; over-budget BEs keep
     * their last geometry / pop in over later frames. Captured block-local → placed by a translate-only
     * instance transform; static, so the MV is 0.
     */
    private void captureBlockEntities(RtContext ctx, FrameBuild build, Minecraft mc, ClientLevel level, float partial, int rbx, int rby, int rbz) {
        beBuildsThisFrame = 0;
        BlockEntityRenderDispatcher beDispatcher = mc.getBlockEntityRenderDispatcher();
        beDispatcher.prepare(cameraState.pos); // sets the camera for shouldRender / extract
        long now = RtComposite.frameCounter();
        int pcx = rbx >> 4, pcz = rbz >> 4;
        Vec3 cam = cameraState.pos;
        List<BeCandidate> candidates = beCandidates;
        candidates.clear();
        int viewChunks = beViewChunks();
        for (int cx = pcx - viewChunks; cx <= pcx + viewChunks; cx++) {
            for (int cz = pcz - viewChunks; cz <= pcz + viewChunks; cz++) {
                if (!level.getChunkSource().hasChunk(cx, cz) || !(level.getChunk(cx, cz) instanceof LevelChunk chunk)) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos p = be.getBlockPos();
                    double dx = p.getX() + 0.5 - cam.x;
                    double dy = p.getY() + 0.5 - cam.y;
                    double dz = p.getZ() + 0.5 - cam.z;
                    candidates.add(new BeCandidate(be, dx * dx + dy * dy + dz * dz, p.asLong()));
                }
            }
        }
        if (candidates.size() > 1) {
            candidates.sort((a, b) -> {
                int byDistance = Double.compare(a.dist2, b.dist2);
                return byDistance != 0 ? byDistance : Long.compare(a.posKey, b.posKey);
            });
        }
        int firstBlockEntity = build.count;
        try {
            for (BeCandidate candidate : candidates) {
                if (build.full() || build.count - firstBlockEntity >= maxBlockEntities()) {
                    return;
                }
                updateBlockEntity(ctx, build, beDispatcher, candidate.be, partial, now, rbx, rby, rbz);
            }
        } finally {
            candidates.clear();
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
            collector.begin(capture, false);
            // Identity pose ⇒ block-local mesh; world placement is the per-frame instance transform in emitBe.
            beDispatcher.submit(state, new PoseStack(), collector, cameraState);
        } catch (Throwable t) {
            throw new RuntimeException("RT block-entity capture failed", t); // propagate to composite() (see entity path)
        } finally {
            collector.begin(null, false);
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
            if (beBuildsThisFrame >= beBuildsPerFrame()) {
                if (entry != null) {
                    emitBe(ctx, build, entry, null, rbx, rby, rbz); // over budget: keep last geometry, no MV
                }
                return;
            }
            // Per-vertex MV from the previous build's block-local mesh (same vertex count ⇒ pairable).
            // The BE itself doesn't move, so the world displacement is the pure local delta.
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

    /** Upload the already-captured BE mesh to fresh buffers and build its BLAS (block-local). */
    private BeEntry buildBe(RtContext ctx, FrameBuild build, BlockEntity be, long hash) {
        beginBuildIfNeeded(ctx, build);
        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        int vertCount = capture.verts.size() / 3;
        int idxCount = capture.idx.size();
        BlockPos p = be.getBlockPos();
        String label = "block entity " + p.getX() + "," + p.getY() + "," + p.getZ();
        RtBuffer positions = allocBuffer(ctx, (long) capture.verts.size() * Float.BYTES, asInput, true,
                label + " positions");
        RtBuffer indices = allocBuffer(ctx, (long) capture.idx.size() * Integer.BYTES, asInput | storage, true,
                label + " indices");
        RtBuffer uvs = allocBuffer(ctx, (long) capture.uvList.size() * Float.BYTES, storage, true,
                label + " uvs");
        RtBuffer prim = allocBuffer(ctx, (long) capture.prim.size() * Float.BYTES, storage, true,
                label + " prim");
        MemoryUtil.memFloatBuffer(positions.mapped, capture.verts.size()).put(capture.verts.elements(), 0, capture.verts.size());
        MemoryUtil.memIntBuffer(indices.mapped, capture.idx.size()).put(capture.idx.elements(), 0, capture.idx.size());
        MemoryUtil.memFloatBuffer(uvs.mapped, capture.uvList.size()).put(capture.uvList.elements(), 0, capture.uvList.size());
        MemoryUtil.memFloatBuffer(prim.mapped, capture.prim.size()).put(capture.prim.elements(), 0, capture.prim.size());

        // Persistent BLAS reused every frame the mesh is unchanged. UpdatableBuild keeps the AS + backing
        // and exposes the build scratch separately (released at the frames-in-flight horizon). We never
        // refit it — a changed BE gets a fresh AS and the old one is defer-freed — so no in-place write can
        // race an in-flight trace.
        RtAccel.UpdatableBuild ub = RtAccel.prepareUpdatableBlasBuild(ctx, positions, vertCount, indices, idxCount, false,
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
        // Retain this build's block-local verts so the next rebuild can diff against them for the MV.
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
        // disp is non-null only for a BE whose mesh changed this frame (chest lid / spawner); a static BE
        // passes null ⇒ dispAddr 0 ⇒ no MV. The disp buffer is a per-frame transient, so a BE that stops
        // animating reverts to MV 0 next frame.
        long dispAddr = uploadDisp(ctx, build, disp);
        writeTableEntry(build, e.prim.deviceAddress, e.indices.deviceAddress, e.uvs.deviceAddress, dispAddr, 0f, 0f, 0f);
        // Block-local mesh placed by a translate-only instance transform (blockPos − rebase), like terrain.
        float[] xform = {1, 0, 0, e.bx - rbx, 0, 1, 0, e.by - rby, 0, 0, 1, e.bz - rbz};
        build.instances.add(new RtAccel.Instance(xform, e.accel.deviceAddress,
                ENTITY_BIT | (build.count & 0x7FFFFF), 0xFF, RtAccel.SBT_ENTITY_OFFSET));
        build.count++;
        build.logicalCount++;
        RtFrameStats.FRAME.count("blockEntitiesCaptured", 1);
    }

    /** Retire a cached block entity's persistent AS + mesh buffers once off all in-flight queues. */
    private void deferDestroyBe(BeEntry e) {
        long freeAt = RtComposite.frameCounter() + KEEP_FRAMES;
        deferred.add(new Deferred(freeAt, () -> {
            RtAccel.destroyEntityAccel(e.accel, e.backing);
            e.positions.destroy();
            e.indices.destroy();
            e.uvs.destroy();
            e.prim.destroy();
        }));
    }

    /** Drop cached block entities not seen (in window) within the last KEEP_FRAMES frames — unloaded/out of view. */
    private void evictStaleBes() {
        if (beCache.isEmpty()) {
            return;
        }
        long now = RtComposite.frameCounter();
        Iterator<Map.Entry<Long, BeEntry>> it = beCache.entrySet().iterator();
        while (it.hasNext()) {
            BeEntry e = it.next().getValue();
            if (now - e.lastSeen < KEEP_FRAMES) {
                continue;
            }
            deferDestroyBe(e);
            it.remove();
        }
    }

    // Vulkan requires buffer size > 0; a few zero-length captures (empty entity mesh, etc.) can otherwise
    // reach allocBuffer() with minSize == 0.
    private static final long MIN_BUFFER_SIZE = 256;

    /** Allocate one of this frame's ~6-per-entity VMA buffers (mesh/BLAS scratch), counted for RtFrameStats. */
    private RtBuffer allocBuffer(RtContext ctx, long minSize, int usage, boolean hostVisible, String label) {
        RtFrameStats.FRAME.count("vmaBufferCreates", 1);
        return ctx.createBuffer(Math.max(minSize, MIN_BUFFER_SIZE), usage, hostVisible, label);
    }

    /** Lazily initialise this frame's build (instance list seeded with terrain, fresh free-lists, table ring slot). */
    private void beginBuildIfNeeded(RtContext ctx, FrameBuild build) {
        if (build.instances != null) {
            return;
        }
        FrameLists lists = frameLists[(int) (RtComposite.frameCounter() % frameLists.length)];
        lists.reset(build.base);
        build.lists = lists;
        build.instances = lists.instances;
        build.blas = lists.blas;
        build.pooledBlas = lists.pooledBlas;
        build.refitScratch = lists.refitScratch;
        build.buffers = lists.buffers;
        build.motion = lists.motion;
        ensureResources(ctx);
        tableSlot = (tableSlot + 1) % TABLE_RING;
        build.tableBase = tableRing[tableSlot].mapped;
        build.geomTableAddr = tableRing[tableSlot].deviceAddress;
    }

    /**
     * Rigid-reuse fast path: if the current local-space {@link #capture} is identical to, or a rigid yaw
     * transform of, the mesh this entity's AS was last built from, emit a geometry-table entry pointing at
     * the cached shading buffers and a TLAS instance carrying the fitted transform over the cached AS —
     * skipping the 4 mesh uploads and the BLAS refit. Covers still mobs, item frames, armor stands, and
     * spinning/bobbing dropped items. The hit shader rotates prim normals / TBN by the instance transform,
     * so rotated instances shade correctly. Motion vectors are untouched: {@code motion} was already
     * computed against last frame's capture (a rotating pose gets its per-vertex disp buffer as usual).
     * Returns false (caller takes the full path) when there is no reusable AS, the topology changed, the
     * pose is non-rigid (animation), or the shading data changed under identical topology.
     */
    private boolean appendRigidReuse(RtContext ctx, FrameBuild build, Motion motion, int entityId, int mask,
                                     float placeX, float placeY, float placeZ) {
        EntityAccel ea = entityAccels.get(entityId);
        if (ea == null || ea.refAccel == null
                || ea.refVertCount != capture.verts.size() / 3 || ea.refIdxCount != capture.idx.size()) {
            return false;
        }
        long equalStart = RtFrameStats.FRAME.startStage();
        boolean equal;
        try {
            equal = positionsBitwiseEqual(ea.refVerts, capture.verts.elements(), ea.refVertCount * 3);
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.rigidReuse.equal", equalStart);
        }
        float[] localTransform = IDENTITY;
        if (!equal) {
            long now = RtComposite.frameCounter();
            if (now < ea.retryYawFitAfter) {
                return false;
            }
            long yawStart = RtFrameStats.FRAME.startStage();
            try {
                localTransform = fitYawTransform(ea.refVerts, capture.verts.elements(), ea.refVertCount);
            } finally {
                RtFrameStats.FRAME.endStage("entity.capture.rigidReuse.yaw", yawStart);
            }
            if (localTransform == null) {
                RtFrameStats.FRAME.count("entityRigidFitFailures", 1);
                ea.retryYawFitAfter = now + 8L;
                return false;
            }
            RtFrameStats.FRAME.count("entityRigidFitSuccesses", 1);
            ea.retryYawFitAfter = 0L;
        }
        // Same topology + rigid pose, but tint/sprite/material lanes may still have changed (dyed sheep,
        // item frame content swap that kept counts). Compare the rotation-invariant shading hash.
        long shadeStart = RtFrameStats.FRAME.startStage();
        try {
            if (shadeHash() != ea.refShadeHash) {
                return false;
            }
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.rigidReuse.shade", shadeStart);
        }
        beginBuildIfNeeded(ctx, build);
        ea.lastSeen = RtComposite.frameCounter();
        writeTableEntry(build, ea.refPrimAddr, ea.refIndexAddr, ea.refUvAddr,
                motion.dispAddr, motion.rigidX, motion.rigidY, motion.rigidZ);
        build.instances.add(new RtAccel.Instance(placeTransform(localTransform, placeX, placeY, placeZ),
                ea.refAccel.deviceAddress,
                ENTITY_BIT | (build.count & 0x3FFFFF), mask, RtAccel.SBT_ENTITY_OFFSET));
        build.count++;
        RtFrameStats.FRAME.count("entityReuse", 1);
        return true;
    }

    /**
     * Fit {@code cur ≈ R_yaw·ref + t} in entity-local space. Exact local equality is handled before this
     * method; this slower centroid/yaw fit covers dropped-item spin and minecarts. Pitch/roll and skeletal
     * deformation take the full path.
     */
    private static float[] fitYawTransform(float[] ref, float[] cur, int vc) {
        // Centroid-align, then the least-squares rotation angle about Y for
        // x' = x·cos + z·sin, z' = −x·sin + z·cos is atan2(Σ(cx·rz − cz·rx), Σ(cx·rx + cz·rz)).
        float crx = 0f, cry = 0f, crz = 0f, ccx = 0f, ccy = 0f, ccz = 0f;
        for (int i = 0; i < vc; i++) {
            crx += ref[i * 3];
            cry += ref[i * 3 + 1];
            crz += ref[i * 3 + 2];
            ccx += cur[i * 3];
            ccy += cur[i * 3 + 1];
            ccz += cur[i * 3 + 2];
        }
        float inv = 1f / vc;
        crx *= inv; cry *= inv; crz *= inv;
        ccx *= inv; ccy *= inv; ccz *= inv;
        double a = 0.0, b = 0.0;
        for (int i = 0; i < vc; i++) {
            float rx = ref[i * 3] - crx, rz = ref[i * 3 + 2] - crz;
            float cx = cur[i * 3] - ccx, cz = cur[i * 3 + 2] - ccz;
            a += (double) cx * rx + (double) cz * rz;
            b += (double) cx * rz - (double) cz * rx;
        }
        float cos = (float) Math.cos(Math.atan2(b, a));
        float sin = (float) Math.sin(Math.atan2(b, a));
        for (int i = 0; i < vc; i++) {
            float rx = ref[i * 3] - crx, ry = ref[i * 3 + 1] - cry, rz = ref[i * 3 + 2] - crz;
            float ex = (rx * cos + rz * sin) - (cur[i * 3] - ccx);
            float ey = ry - (cur[i * 3 + 1] - ccy);
            float ez = (-rx * sin + rz * cos) - (cur[i * 3 + 2] - ccz);
            if (Math.abs(ex) > RIGID_FIT_EPS || Math.abs(ey) > RIGID_FIT_EPS || Math.abs(ez) > RIGID_FIT_EPS) {
                return null;
            }
        }
        // p_out = R·(p − cr) + cc  ⇒  t = cc − R·cr.
        float rcx = crx * cos + crz * sin;
        float rcz = -crx * sin + crz * cos;
        return new float[] {
                cos, 0, sin, ccx - rcx,
                0,   1, 0,   ccy - cry,
                -sin, 0, cos, ccz - rcz};
    }

    private static boolean positionsBitwiseEqual(float[] a, float[] b, int size) {
        for (int i = 0; i < size; i++) {
            if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i])) {
                return false;
            }
        }
        return true;
    }

    private static float[] translationTransform(float x, float y, float z) {
        return new float[] {1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z};
    }

    private static float[] placeTransform(float[] local, float x, float y, float z) {
        if (local == IDENTITY) {
            return translationTransform(x, y, z);
        }
        return new float[] {
                local[0], local[1], local[2], local[3] + x,
                local[4], local[5], local[6], local[7] + y,
                local[8], local[9], local[10], local[11] + z};
    }

    /**
     * FNV-1a over the capture's shading data that must match for AS reuse: UVs plus each prim record's
     * emission/tint/material lanes. Prim NORMALS are deliberately excluded — they rotate with the pose,
     * and the hit shader re-rotates the cached ones via the instance transform.
     */
    private long shadeHash() {
        long h = 1469598103934665603L;
        float[] uv = capture.uvList.elements();
        int un = capture.uvList.size();
        for (int i = 0; i < un; i++) {
            h = (h ^ (Float.floatToRawIntBits(uv[i]) & 0xffffffffL)) * 1099511628211L;
        }
        float[] pr = capture.prim.elements();
        int pn = capture.prim.size();
        for (int base = 0; base < pn; base += 12) {
            for (int k = 3; k < 12; k++) { // skip normal.xyz (0..2); keep emission(3), tint(4..7), mat(8..11)
                h = (h ^ (Float.floatToRawIntBits(pr[base + k]) & 0xffffffffL)) * 1099511628211L;
            }
        }
        return h;
    }

    /**
     * Upload the current {@link #capture} as a per-object mesh + BLAS, add its instance + geom-table entry.
     * {@code entityId} ≥ 0 → refit path (persistent updatable AS keyed by id); {@code < 0} (refit disabled)
     * → transient one-shot full BUILD. Used by the animated-entity pass; block entities use {@link #buildBe}.
     */
    private void appendCapture(RtContext ctx, FrameBuild build, float[] disp, int entityId, int instanceBit, int mask) {
        beginBuildIfNeeded(ctx, build);
        appendCapture(ctx, build, new Motion(uploadDisp(ctx, build, disp), 0f, 0f, 0f),
                entityId, instanceBit, mask, IDENTITY);
    }

    private void appendCapture(RtContext ctx, FrameBuild build, Motion motion, int entityId, int instanceBit, int mask,
                               float[] instanceTransform) {
        beginBuildIfNeeded(ctx, build);
        if (entityId >= 0) {
            appendPackedEntity(ctx, build, motion, entityId, instanceBit, mask, instanceTransform);
            return;
        }
        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        int vertCount = capture.verts.size() / 3;
        int idxCount = capture.idx.size();
        RtBuffer positions = allocBuffer(ctx, (long) capture.verts.size() * Float.BYTES, asInput, true,
                "particle positions");
        RtBuffer indices = allocBuffer(ctx, (long) capture.idx.size() * Integer.BYTES, asInput | storage, true,
                "particle indices");
        RtBuffer uvs = allocBuffer(ctx, (long) capture.uvList.size() * Float.BYTES, storage, true,
                "particle uvs");
        RtBuffer prim = allocBuffer(ctx, (long) capture.prim.size() * Float.BYTES, storage, true,
                "particle prim");
        MemoryUtil.memFloatBuffer(positions.mapped, capture.verts.size())
                .put(capture.verts.elements(), 0, capture.verts.size());
        MemoryUtil.memIntBuffer(indices.mapped, capture.idx.size())
                .put(capture.idx.elements(), 0, capture.idx.size());
        MemoryUtil.memFloatBuffer(uvs.mapped, capture.uvList.size())
                .put(capture.uvList.elements(), 0, capture.uvList.size());
        MemoryUtil.memFloatBuffer(prim.mapped, capture.prim.size())
                .put(capture.prim.elements(), 0, capture.prim.size());

        // Non-opaque so world.rahit alpha-tests the texture (cutout). Opaque texels pass to the chit.
        RtAccel.PreparedBlas blas = RtAccel.prepareEntityBlas(ctx, positions, vertCount, indices, idxCount, false,
                "particle BLAS");
        build.blas.add(blas);
        build.pooledBlas.add(blas);

        writeTableEntry(build, prim.deviceAddress, indices.deviceAddress, uvs.deviceAddress, motion.dispAddr,
                motion.rigidX, motion.rigidY, motion.rigidZ);

        build.instances.add(new RtAccel.Instance(instanceTransform, blas.accel.deviceAddress,
                instanceBit | (build.count & 0x3FFFFF), mask, RtAccel.SBT_ENTITY_OFFSET));
        build.buffers.add(positions);
        build.buffers.add(indices);
        build.buffers.add(uvs);
        build.buffers.add(prim);
        build.count++;
    }

    /** Pack one changed entity's four logical geometry regions into its retired ring slot's backing. */
    private void appendPackedEntity(RtContext ctx, FrameBuild build, Motion motion, int entityId,
                                    int instanceBit, int mask, float[] instanceTransform) {
        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        int vertCount = capture.verts.size() / 3;
        int idxCount = capture.idx.size();
        EntityGeometryLayout layout = EntityGeometryLayout.create(capture.verts.size(), capture.idx.size(),
                capture.uvList.size(), capture.prim.size());

        EntitySlot slot;
        RtBuffer geometry;
        long allocStart = RtFrameStats.FRAME.startStage();
        try {
            slot = selectEntityBuildSlot(entityId);
            long required = Math.addExact(layout.totalBytes, EntityGeometryLayout.REGION_ALIGNMENT - 1L);
            geometry = slot.geometry;
            if (geometry == null || geometry.size < required) {
                RtBuffer old = geometry;
                long capacity = old == null ? required : growCapacity(old.size, required);
                geometry = allocBuffer(ctx, capacity, asInput | storage, true, "entity geometry");
                slot.geometry = geometry;
                retainedGeometryBytes = Math.addExact(retainedGeometryBytes, geometry.size);
                RtFrameStats.FRAME.count("entityVmaBufferCreates", 1);
                if (old != null) {
                    retainedGeometryBytes = Math.subtractExact(retainedGeometryBytes, old.size);
                    old.destroy();
                }
            } else {
                RtFrameStats.FRAME.count("entityGeometryBufferReuses", 1);
            }
            layout = layout.shifted((-geometry.deviceAddress) & (EntityGeometryLayout.REGION_ALIGNMENT - 1L));
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.append.alloc", allocStart);
        }

        long copyStart = RtFrameStats.FRAME.startStage();
        try {
            MemoryUtil.memFloatBuffer(geometry.mapped + layout.positionOffset, capture.verts.size())
                    .put(capture.verts.elements(), 0, capture.verts.size());
            MemoryUtil.memIntBuffer(geometry.mapped + layout.indexOffset, capture.idx.size())
                    .put(capture.idx.elements(), 0, capture.idx.size());
            MemoryUtil.memFloatBuffer(geometry.mapped + layout.uvOffset, capture.uvList.size())
                    .put(capture.uvList.elements(), 0, capture.uvList.size());
            MemoryUtil.memFloatBuffer(geometry.mapped + layout.primOffset, capture.prim.size())
                    .put(capture.prim.elements(), 0, capture.prim.size());
            geometry.flush(layout.positionOffset, layout.totalBytes - layout.positionOffset);
            RtFrameStats.FRAME.count("entityUploadBytes", layout.logicalBytes);
            RtFrameStats.FRAME.count("entityPackedBytes", layout.totalBytes);
            RtFrameStats.FRAME.count("entityPackedPaddingBytes", layout.totalBytes - layout.logicalBytes);
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.append.copy", copyStart);
        }

        long positionAddr = Math.addExact(geometry.deviceAddress, layout.positionOffset);
        long indexAddr = Math.addExact(geometry.deviceAddress, layout.indexOffset);
        long uvAddr = Math.addExact(geometry.deviceAddress, layout.uvOffset);
        long primAddr = Math.addExact(geometry.deviceAddress, layout.primOffset);
        RtAccel accel;
        long blasStart = RtFrameStats.FRAME.startStage();
        try {
            accel = refitOrBuild(ctx, build, slot, positionAddr, indexAddr, vertCount, idxCount);
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.append.blas", blasStart);
        }

        writeTableEntry(build, primAddr, indexAddr, uvAddr, motion.dispAddr,
                motion.rigidX, motion.rigidY, motion.rigidZ);
        build.instances.add(new RtAccel.Instance(instanceTransform, accel.deviceAddress,
                instanceBit | (build.count & 0x3FFFFF), mask, RtAccel.SBT_ENTITY_OFFSET));

        EntityAccel ea = slot.owner;
        clearRefGeometry(ea);
        ea.refAccel = accel;
        ea.refIndexAddr = indexAddr;
        ea.refUvAddr = uvAddr;
        ea.refPrimAddr = primAddr;
        int size = capture.verts.size();
        if (ea.refVerts == null || ea.refVerts.length < size) {
            ea.refVerts = new float[size];
        }
        System.arraycopy(capture.verts.elements(), 0, ea.refVerts, 0, size);
        ea.refVertCount = vertCount;
        ea.refIdxCount = idxCount;
        ea.refShadeHash = shadeHash();
        build.count++;
    }

    /** Clear the latest rigid-reuse view; the backing remains owned by its retired ring slot. */
    private void clearRefGeometry(EntityAccel ea) {
        ea.refAccel = null;
        ea.refIndexAddr = 0L;
        ea.refUvAddr = 0L;
        ea.refPrimAddr = 0L;
    }

    private static long growCapacity(long current, long required) {
        long grown = current <= Long.MAX_VALUE - current / 2L ? current + current / 2L : Long.MAX_VALUE;
        return Math.max(required, grown);
    }

    /** Upload a per-vertex displacement array into this frame slot's motion arena; returns 0 if null. */
    private long uploadDisp(RtContext ctx, FrameBuild build, float[] disp) {
        if (disp == null) {
            return 0L;
        }
        beginBuildIfNeeded(ctx, build);
        MotionSlice slice = build.motion.allocate(ctx, (long) disp.length * Float.BYTES);
        MemoryUtil.memFloatBuffer(slice.mapped, disp.length).put(disp, 0, disp.length);
        slice.flush();
        return slice.deviceAddress;
    }

    /** Write one entity geometry-table entry at the current build slot: {primAddr, idxAddr, uvAddr, dispAddr, rigidDisp}. */
    private void writeTableEntry(FrameBuild build, long primAddr, long idxAddr, long uvAddr, long dispAddr,
                                 float rigidX, float rigidY, float rigidZ) {
        long entry = build.tableBase + (long) build.count * TABLE_ENTRY_BYTES;
        MemoryUtil.memPutLong(entry, primAddr);
        MemoryUtil.memPutLong(entry + 8, idxAddr);
        MemoryUtil.memPutLong(entry + 16, uvAddr);
        MemoryUtil.memPutLong(entry + 24, dispAddr);
        MemoryUtil.memPutFloat(entry + 32, rigidX);
        MemoryUtil.memPutFloat(entry + 36, rigidY);
        MemoryUtil.memPutFloat(entry + 40, rigidZ);
        MemoryUtil.memPutFloat(entry + 44, 0f);
    }

    /**
     * Select the next per-entity slot. One entity contributes at most one changed capture per frame, so a
     * wrapped slot is at least {@link #REFIT_RING} frames old and off all queues.
     */
    private EntitySlot selectEntityBuildSlot(int entityId) {
        EntityAccel ea = entityAccels.get(entityId);
        if (ea == null) {
            ea = new EntityAccel();
            entityAccels.put(entityId, ea);
        }
        ea.lastSeen = RtComposite.frameCounter();
        int s = ea.cursor;
        ea.cursor = (ea.cursor + 1) % REFIT_RING;
        EntitySlot slot = ea.ring[s];
        if (slot == null) {
            slot = new EntitySlot();
            slot.owner = ea;
            ea.ring[s] = slot;
        }
        return slot;
    }

    /**
     * Refit-or-build this entity's persistent acceleration structure in an already-selected retired slot.
     * Records an in-place UPDATE (cheap refit) when the slot already holds an
     * AS of the same topology, else a full ALLOW_UPDATE BUILD (first use of the slot, a topology change, or
     * the periodic BVH-quality rebuild). Refit scratch and packed geometry persist in the slot and are reused.
     */
    private RtAccel refitOrBuild(RtContext ctx, FrameBuild build, EntitySlot slot,
                                 long positionAddr, long indexAddr,
                                 int vertCount, int idxCount) {
        int triCount = idxCount / 3;
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        boolean canUpdate = slot.accel != null
                && slot.vertCount == vertCount && slot.triCount == triCount
                && slot.updatesSinceBuild < refitRebuildInterval();
        if (canUpdate) {
            RtFrameStats.FRAME.count("refits", 1);
            long required = RtAccel.scratchBufferSize(ctx, slot.updateScratchSize);
            if (slot.refitScratch == null || slot.refitScratch.size < required) {
                if (slot.refitScratch != null) {
                    slot.refitScratch.destroy();
                }
                slot.refitScratch = allocBuffer(ctx, required, storage, false, "entity refit scratch");
                RtFrameStats.FRAME.count("entityVmaBufferCreates", 1);
            } else {
                RtFrameStats.FRAME.count("entityScratchBufferReuses", 1);
            }
            build.blas.add(RtAccel.refitUpdate(slot.accel, slot.refitScratch,
                    positionAddr, indexAddr, vertCount, idxCount, false,
                    "entity BLAS refit"));
            slot.updatesSinceBuild++;
            return slot.accel;
        }
        // (Re)build: the selected ring slot is already past the in-flight horizon, so replace its old AS
        // immediately and create a fresh updatable one sized for the current topology.
        if (slot.accel != null) {
            RtAccel.destroyEntityAccel(slot.accel, slot.backing);
            slot.accel = null;
            slot.backing = null;
        }
        RtAccel.UpdatableBuild ub = RtAccel.prepareUpdatableBlasBuild(ctx, positionAddr, vertCount, indexAddr, idxCount, false,
                "entity BLAS");
        RtFrameStats.FRAME.count("entityVmaBufferCreates", 2); // persistent AS backing + transient build scratch
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

    /** Drop persistent AS for entities not captured within the last KEEP_FRAMES frames (off all queues). */
    private void evictStaleAccels() {
        if (entityAccels.isEmpty()) {
            return;
        }
        long now = RtComposite.frameCounter();
        var it = entityAccels.values().iterator();
        while (it.hasNext()) {
            EntityAccel ea = it.next();
            if (now - ea.lastSeen < KEEP_FRAMES) {
                continue;
            }
            for (EntitySlot slot : ea.ring) {
                if (slot != null) {
                    destroyEntitySlot(slot);
                }
            }
            clearRefGeometry(ea);
            it.remove();
        }
    }

    private void destroyEntitySlot(EntitySlot slot) {
        if (slot.accel != null) {
            RtAccel.destroyEntityAccel(slot.accel, slot.backing);
            slot.accel = null;
            slot.backing = null;
        }
        if (slot.geometry != null) {
            retainedGeometryBytes = Math.subtractExact(retainedGeometryBytes, slot.geometry.size);
            slot.geometry.destroy();
            slot.geometry = null;
        }
        if (slot.refitScratch != null) {
            slot.refitScratch.destroy();
            slot.refitScratch = null;
        }
    }

    private void setCamera(double camX, double camY, double camZ, Matrix4f projection, Matrix4f viewRotation) {
        if (cameraState == null) {
            cameraState = new CameraRenderState();
        }
        cameraState.pos = new Vec3(camX, camY, camZ);
        cameraState.projectionMatrix.set(projection);
        cameraState.viewRotationMatrix.set(viewRotation);
        // viewRotation is the world->view rotation (mvCurProjView = frameProjection * frameViewRotation);
        // vanilla's Camera.rotation() (what CameraRenderState.orientation actually holds, per Camera.java
        // "cameraState.orientation.set(this.rotation())") is the INVERSE of that — view->world, i.e. the
        // camera's own facing direction, used to billboard world-space quads (name tags) to face the
        // camera. A pure rotation's inverse is its conjugate. Nothing consumed this field before
        // RtNameTagFeature; a plain setFromUnnormalized(viewRotation) here would billboard backwards.
        cameraState.orientation.setFromUnnormalized(viewRotation).conjugate();
        cameraState.initialized = true;
    }

    private void ensureResources(RtContext ctx) {
        int requiredCapacity = maxEntities();
        if (tableRing != null && tableCapacity >= requiredCapacity) {
            return;
        }
        if (tableRing != null) {
            RtBuffer[] oldRing = tableRing;
            deferred.add(new Deferred(RtComposite.frameCounter() + KEEP_FRAMES, () -> {
                for (RtBuffer b : oldRing) {
                    b.destroy();
                }
            }));
            tableRing = null;
            tableCapacity = 0;
        }
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        tableRing = new RtBuffer[TABLE_RING];
        for (int i = 0; i < TABLE_RING; i++) {
            tableRing[i] = ctx.createBuffer((long) requiredCapacity * TABLE_ENTRY_BYTES, storage, true,
                    "entity geometry table ring " + i);
        }
        tableCapacity = requiredCapacity;
    }

    /** Drop CPU templates that retain resource-pack-owned model trees. */
    public void onResourceReload() {
        collector.clearCaches();
    }

    private void processDeferred() {
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

    /** Free the geometry-table ring + any outstanding per-frame entity resources (teardown; GPU idle). */
    public void shutdown() {
        // Drain outstanding deferred releases first (they destroy buffers/AS), then destroy the persistent
        // per-entity AS. Runs after waitIdle, so immediate destruction is safe.
        for (Deferred d : deferred) {
            d.free().run();
        }
        deferred.clear();
        for (FrameLists lists : frameLists) {
            lists.releaseDeferred();
            lists.destroyPersistent();
        }
        for (EntityAccel ea : entityAccels.values()) {
            for (EntitySlot slot : ea.ring) {
                if (slot != null) {
                    destroyEntitySlot(slot);
                }
            }
            clearRefGeometry(ea);
        }
        entityAccels.clear();
        for (BeEntry e : beCache.values()) {
            RtAccel.destroyEntityAccel(e.accel, e.backing);
            e.positions.destroy();
            e.indices.destroy();
            e.uvs.destroy();
            e.prim.destroy();
        }
        beCache.clear();
        if (tableRing != null) {
            for (RtBuffer b : tableRing) {
                b.destroy();
            }
            tableRing = null;
            tableCapacity = 0;
        }
        prevVerts.clear();
        curVerts.clear();
        particlePrev.clear();
        particleCur.clear();
        particleDisp.clear();
        glowBatches.clear();
        nameTagBatches.clear();
        beCandidates.clear();
        retainedGeometryBytes = 0L;
        collector.clearCaches();
    }
}
