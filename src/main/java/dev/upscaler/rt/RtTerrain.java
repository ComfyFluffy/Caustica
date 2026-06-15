package dev.upscaler.rt;

import com.mojang.blaze3d.vertex.QuadInstance;
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
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
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
 * <p>Still synchronous: builds run on the client thread (amortized) and frees use {@code waitIdle}.
 * Block-edit dirty tracking (a {@code LevelExtractor} hook), deferred frees, and async BLAS/TLAS are
 * the next steps.
 */
public final class RtTerrain {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("upscaler.rt.terrain", "true"));
    // RT view distance in chunks: explicit override, else min(vanilla render distance, cap). RT is
    // costly, so it's capped below typical raster view distance and tunable.
    private static final int VIEW_CHUNKS = Integer.getInteger("upscaler.rt.viewChunks", 0);
    private static final int VIEW_CHUNKS_CAP = Integer.getInteger("upscaler.rt.viewChunksCap", 8);
    private static final int VIEW_SECTIONS_V = Integer.getInteger("upscaler.rt.viewSectionsV", 6);
    private static final int SECTIONS_PER_TICK = Integer.getInteger("upscaler.rt.sectionsPerTick", 24);
    private static final int SECTION_ENTRY_BYTES = 24; // {u64 primAddr, u64 idxAddr, u64 uvAddr}

    private static final RtTerrain INSTANCE = new RtTerrain();

    private final Map<Long, SectionGeom> resident = new HashMap<>();
    private final Set<Long> empty = new HashSet<>(); // loaded, in-window sections with no geometry
    private RtBuffer sectionTable;
    private RtAccel tlas;
    private boolean ready;
    // Rebase origin (player block at the last TLAS rebuild) for the instance transforms + ray camOffset.
    public int blockX;
    public int blockY;
    public int blockZ;

    private RtTerrain() {
    }

    /** The manager if it currently has a built TLAS to trace, else null. */
    public static RtTerrain currentOrNull() {
        return INSTANCE.ready ? INSTANCE : null;
    }

    public long tlas() {
        return tlas.handle;
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

    private void tick(RtContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            clear(ctx); // left the world — drop all geometry
            return;
        }

        BlockPos pb = mc.player.blockPosition();
        int pcx = pb.getX() >> 4, pcz = pb.getZ() >> 4, psy = pb.getY() >> 4;
        int r = horizontalChunks(mc);
        int minSecY = level.getMinY() >> 4;
        int maxSecY = (level.getMinY() + level.getHeight() - 1) >> 4;
        int loY = Math.max(minSecY, psy - VIEW_SECTIONS_V);
        int hiY = Math.min(maxSecY, psy + VIEW_SECTIONS_V);

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
                    if (!resident.containsKey(key) && !empty.contains(key)) {
                        missing.add(new int[]{scx, scy, scz});
                    }
                }
            }
        }

        // Free sections that left the window (or whose chunk unloaded).
        List<SectionGeom> removed = new ArrayList<>();
        for (Iterator<Map.Entry<Long, SectionGeom>> it = resident.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, SectionGeom> e = it.next();
            if (!desired.contains(e.getKey())) {
                removed.add(e.getValue());
                it.remove();
            }
        }
        empty.removeIf(k -> !desired.contains(k));

        boolean changed = !removed.isEmpty();

        if (!missing.isEmpty()) {
            // Build nearest-first so terrain fills from the player outward.
            missing.sort((a, b) -> Integer.compare(dist2(a, pcx, psy, pcz), dist2(b, pcx, psy, pcz)));
            ModelBlockRenderer renderer = new ModelBlockRenderer(false, true, mc.getBlockColors());
            BlockAndTintGetter view = new LevelView(level);
            BlockStateModelSet modelSet = mc.getModelManager().getBlockStateModelSet();
            QuadCapture capture = new QuadCapture();
            capture.blockColors = mc.getBlockColors();
            capture.view = view;
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            int budget = SECTIONS_PER_TICK;
            for (int[] s : missing) {
                if (budget <= 0) {
                    break;
                }
                budget--;
                long key = sectionKey(s[0], s[1], s[2]);
                SectionGeom g = buildSection(ctx, level, modelSet, renderer, view, capture, m, s[0], s[1], s[2]);
                if (g != null) {
                    resident.put(key, g);
                    changed = true;
                } else {
                    empty.add(key);
                }
            }
        }

        if (changed) {
            rebuild(ctx, removed, pb.getX(), pb.getY(), pb.getZ());
        }
    }

    private int horizontalChunks(Minecraft mc) {
        int r = VIEW_CHUNKS > 0 ? VIEW_CHUNKS : Math.min(mc.options.getEffectiveRenderDistance(), VIEW_CHUNKS_CAP);
        return Math.max(1, r);
    }

    private static int dist2(int[] s, int pcx, int psy, int pcz) {
        int dx = s[0] - pcx, dy = s[1] - psy, dz = s[2] - pcz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Tessellate one section's blocks (section-local) and build its buffers + BLAS; null if empty. */
    private SectionGeom buildSection(RtContext ctx, ClientLevel level, BlockStateModelSet modelSet,
                                     ModelBlockRenderer renderer, BlockAndTintGetter view, QuadCapture capture,
                                     BlockPos.MutableBlockPos m, int scx, int scy, int scz) {
        int sox = scx << 4, soy = scy << 4, soz = scz << 4;
        SectionMesh mesh = new SectionMesh();
        capture.cur = mesh;
        for (int lx = 0; lx < 16; lx++) {
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = sox + lx, wy = soy + ly, wz = soz + lz;
                    m.set(wx, wy, wz);
                    BlockState state = level.getBlockState(m);
                    if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }
                    BlockStateModel model = modelSet.get(state);
                    if (model == null) {
                        continue;
                    }
                    try {
                        capture.state = state;
                        capture.pos = m;
                        renderer.tesselateBlock(capture, lx, ly, lz, view, m, state, model, state.getSeed(m));
                    } catch (Throwable t) {
                        // skip a block whose model rendering throws rather than failing the section
                    }
                }
            }
        }
        if (mesh.idx.isEmpty()) {
            return null;
        }

        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        int vertCount = mesh.verts.size() / 3;
        int idxCount = mesh.idx.size();
        RtBuffer positions = ctx.createBuffer((long) mesh.verts.size() * Float.BYTES, asInput, true);
        RtBuffer indices = ctx.createBuffer((long) mesh.idx.size() * Integer.BYTES, asInput | storage, true);
        RtBuffer uvs = ctx.createBuffer((long) mesh.uvList.size() * Float.BYTES, storage, true);
        RtBuffer material = ctx.createBuffer((long) mesh.prim.size() * Float.BYTES, storage, true);
        MemoryUtil.memFloatBuffer(positions.mapped, mesh.verts.size()).put(mesh.verts.elements(), 0, mesh.verts.size());
        MemoryUtil.memIntBuffer(indices.mapped, mesh.idx.size()).put(mesh.idx.elements(), 0, mesh.idx.size());
        MemoryUtil.memFloatBuffer(uvs.mapped, mesh.uvList.size()).put(mesh.uvList.elements(), 0, mesh.uvList.size());
        MemoryUtil.memFloatBuffer(material.mapped, mesh.prim.size()).put(mesh.prim.elements(), 0, mesh.prim.size());

        // Cutout geometry: non-opaque so the any-hit shader alpha-tests the atlas (foliage/glass).
        RtAccel blas = RtAccel.buildTrianglesBlas(ctx, positions, vertCount, indices, idxCount, false);
        return new SectionGeom(positions, indices, uvs, material, blas, sox, soy, soz);
    }

    /** Rebuild the section table + TLAS from the current resident set; free old + removed sections. */
    private void rebuild(RtContext ctx, List<SectionGeom> removed, int rbx, int rby, int rbz) {
        List<SectionGeom> ordered = new ArrayList<>(resident.values());
        if (ordered.isEmpty()) {
            ctx.waitIdle();
            if (sectionTable != null) {
                sectionTable.destroy();
                sectionTable = null;
            }
            if (tlas != null) {
                tlas.destroy();
                tlas = null;
            }
            for (SectionGeom g : removed) {
                g.destroy();
            }
            ready = false;
            return;
        }

        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        RtBuffer newTable = ctx.createBuffer((long) ordered.size() * SECTION_ENTRY_BYTES, storage, true);
        List<RtAccel.Instance> instances = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            SectionGeom g = ordered.get(i);
            long base = newTable.mapped + (long) i * SECTION_ENTRY_BYTES;
            MemoryUtil.memPutLong(base, g.material.deviceAddress);
            MemoryUtil.memPutLong(base + 8, g.indices.deviceAddress);
            MemoryUtil.memPutLong(base + 16, g.uvs.deviceAddress);
            // instanceCustomIndex == table index i (buildTlas assigns it from the list order).
            float[] xform = {1, 0, 0, g.sx - rbx, 0, 1, 0, g.sy - rby, 0, 0, 1, g.sz - rbz};
            instances.add(new RtAccel.Instance(xform, g.blas.deviceAddress));
        }
        RtAccel newTlas = RtAccel.buildTlas(ctx, instances);

        ctx.waitIdle(); // a previous frame may still read the old TLAS/table/removed buffers
        if (sectionTable != null) {
            sectionTable.destroy();
        }
        if (tlas != null) {
            tlas.destroy();
        }
        for (SectionGeom g : removed) {
            g.destroy();
        }
        sectionTable = newTable;
        tlas = newTlas;
        blockX = rbx;
        blockY = rby;
        blockZ = rbz;
        ready = true;
    }

    private void clear(RtContext ctx) {
        if (resident.isEmpty() && tlas == null && sectionTable == null) {
            empty.clear();
            return;
        }
        ctx.waitIdle();
        if (tlas != null) {
            tlas.destroy();
            tlas = null;
        }
        if (sectionTable != null) {
            sectionTable.destroy();
            sectionTable = null;
        }
        for (SectionGeom g : resident.values()) {
            g.destroy();
        }
        resident.clear();
        empty.clear();
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
        final FloatArrayList prim = new FloatArrayList();   // 8 floats/triangle: normal.xyz0, tint.rgb0
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

            FloatArrayList prim = cur.prim;
            for (int t = 0; t < 2; t++) { // one {normal, tint} record per triangle
                prim.add(nx);
                prim.add(ny);
                prim.add(nz);
                prim.add(0f);
                prim.add(tr);
                prim.add(tg);
                prim.add(tb);
                prim.add(0f);
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

    /** Minimal {@link BlockAndTintGetter} over the client level so the model renderer can cull + tint. */
    private record LevelView(ClientLevel level) implements BlockAndTintGetter {
        @Override
        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver color) {
            return level.getBlockTint(pos, color);
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return level.getLightEngine();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return level.getBlockEntity(pos);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return level.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return level.getFluidState(pos);
        }

        @Override
        public int getHeight() {
            return level.getHeight();
        }

        @Override
        public int getMinY() {
            return level.getMinY();
        }
    }
}
