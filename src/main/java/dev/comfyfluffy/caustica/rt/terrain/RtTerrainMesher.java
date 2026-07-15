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
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
import dev.comfyfluffy.caustica.rt.material.RtMaterialAbi;
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

final class RtTerrainMesher {
    /**
     * Reusable per-worker-thread meshing state. The mesh + captures are reset between tasks so their
     * backing arrays amortize across sections instead of re-growing per task. Everything the result carries out —
     * {@link PackedSection}, OMM data — is copied out of this state before the job returns, so reuse on
     * the next task cannot corrupt a queued result. The renderers stay per-task: they're cheap and capture
     * the dispatch context's model sets/colors.
     */
    static final class WorkerTessState {
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

    static final ThreadLocal<WorkerTessState> WORKER_TESS = ThreadLocal.withInitial(WorkerTessState::new);

    /**
     * Tessellate one section to a section-local CPU mesh and precompute pure-CPU sidecar data such as
     * the terrain opacity micromap. <b>Pure CPU + snapshot reads only</b> — no Vulkan, no shared mutable
     * state — so this is the unit a worker thread runs. Terrain LabPBR sprite references are carried to
     * buffer preparation; the render thread resolves them through the material atlas at publication.
     * Returns the mesh (possibly empty — caller checks {@code idx}).
     */
    static CpuSection buildCpuSection(BlockAndTintGetter region, BlockStateModelSet modelSet,
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
        RtMaterialAbi.requireTriangleParity(primFloats, idxCount);

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
                        RtFluidMesher.tesselate(region, m, fluidCapture, fluidRenderer.fluidModels, state, fluid);
                    }
                    if (state.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }
                    BlockStateModel model = modelSet.get(state);
                    if (model == null) {
                        continue;
                    }
                    capture.state = state;
                    capture.pos = m;
                    renderer.tesselateBlock(capture, lx, ly, lz, region, m, state, model, state.getSeed(m));
                    capture.flushBlock(); // resolve coplanar ties (grass overlay / cross faces), then emit
                }
            }
        }
    }


    /** Pure-CPU worker result: tessellated mesh plus optional opacity micromap input for its cutout bucket. */
    record CpuSection(PackedSection packed, RtAccel.OpacityMicromapInput opacityMicromap) {
    }

    /** Worker-packed terrain payload; native preparation allocates buffers and bulk-copies these arrays. */
    record PackedSection(float[] positions, int[] indices, float[] uvs, float[] material,
                                 TextureAtlasSprite[] materialSprites,
                                 int[] bucketTris, int[] triBase) {
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
     * Transient CPU accumulator for one section's quads while tessellating. Split into per-material geometry
     * buckets so the BLAS can flag solid blocks {@code VK_GEOMETRY_OPAQUE_BIT}, keep true alpha cutout in
     * an any-hit bucket, and route translucent/water through closest-hit-only records for radiance but
     * any-hit records for shadow tint/pass-through. The buckets are concatenated in {@code BUCKET_*} order
     * into the packed section buffers during preparation, so each geometry's triangles occupy a contiguous range.
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
