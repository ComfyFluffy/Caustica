package dev.upscaler.rt;

import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * P5.1b-2 capture infrastructure: a {@link VertexConsumer} that records the posed entity geometry
 * vanilla emits — exactly the same bulk {@code addVertex(x,y,z,color,u,v,overlay,light,nx,ny,nz)} that
 * {@code ModelPart.Cube.compile} calls (4 verts/quad → 2 triangles), so this mirrors the fluid-capture
 * pattern. {@link RtEntityCollector} drives it by calling {@code model.renderToBuffer(pose, this, …)}.
 *
 * <p>The accumulators use the SAME layout as terrain's {@code SectionMesh} (positions, indices, atlas
 * UV, per-prim {@code {normal.xyz, emission}, {tint.rgb, 0}}) so the GPU-side rework (P5.1b-2 step 2)
 * can upload + BLAS them with the existing terrain machinery. Until entity textures land (P5.1b-2b) the
 * tint carries the model's vertex colour (white → grey-lit) and UVs are kept for later.
 */
public final class RtEntityCapture implements VertexConsumer {
    final FloatArrayList verts = new FloatArrayList();   // 3 floats/vertex (capture-space position)
    final IntArrayList idx = new IntArrayList();         // 3 indices/triangle
    final FloatArrayList uvList = new FloatArrayList();  // 2 floats/vertex (entity-texture UV, for P5.1b-2b)
    final FloatArrayList prim = new FloatArrayList();    // 12 floats/triangle: normal.xyz+0, tint.rgb+texSlot, mat.{rough,metal,0,0}

    // P5.1b-2b: the bindless texture slot for the geometry currently being submitted (set by the
    // collector per submitModel, so body + feature layers get their own texture). Stored per-prim in
    // tint.w; the hit shader samples entityTex[texSlot].
    int currentTexSlot;
    // P6.2c: whether the current submission's bindless slot has a LabPBR _s / _n map (→ prim mat.z/mat.w).
    // Set by the collector per submission; mobs (per-type textures) may have them, atlas-sourced quads don't.
    boolean currentHasS;
    boolean currentHasN;
    // Block-atlas geometry (dropped/held block items, falling blocks, contained block displays) samples the
    // BLOCK atlas, so its LabPBR _s/_n live in the terrain parallel atlases (blockSpecAtlas/blockNormalAtlas)
    // — NOT the per-type bindless arrays. When set, currentHasS/currentHasN refer to those: emitQuad encodes
    // mat.z/mat.w as 2 (block atlas) instead of 1 (bindless entity), so world.rchit samples the right atlas.
    boolean currentBlockAtlas;
    // Whether the current submission is an alpha-blended (translucent) render type — slime / sulfur-cube
    // shells, ghosts, … Stored per-prim in the otherwise-unused entity emission lane (normal.w); world.rahit
    // reads it and does stochastic transparency for those surfaces instead of a binary cutout, so the inner
    // content (slime core, the sulfur cube's contained block) shows through the shell. Set by the collector
    // per submission (model bodies only — block/item/particle paths force it false).
    boolean currentTranslucent;
    // P5.1b-2f: when a model textures from an atlas SPRITE (block entities: chests/signs/beds via a
    // Material), its ModelPart UVs are 0..1 in a virtual texture and must be remapped into the sprite's
    // atlas region — the work vanilla's sprite-coordinate-expander VertexConsumer does, which we bypass.
    // Off for full-texture models (mobs, sprite == null) and for baked quads (already atlas-space).
    private boolean uvRemap;
    private float uvU0, uvV0, uvDU, uvDV;

    private int n; // quad vertex accumulator (0..3)
    private final float[] qx = new float[4], qy = new float[4], qz = new float[4];
    private final float[] qu = new float[4], qv = new float[4];
    private final float[] qnx = new float[4], qny = new float[4], qnz = new float[4];
    private final int[] qcol = new int[4];
    private final Vector3f scratch = new Vector3f(); // P5.1b-2d/e baked-quad position transform

    /** Clear all accumulators for a fresh entity capture. */
    public void reset() {
        verts.clear();
        idx.clear();
        uvList.clear();
        prim.clear();
        n = 0;
        currentTexSlot = 0;
        currentHasS = false;
        currentHasN = false;
        currentBlockAtlas = false;
        currentTranslucent = false;
        uvRemap = false;
    }

    /** Remap subsequent {@link #addVertex} (ModelPart) UVs from 0..1 into a sprite's atlas region. */
    public void setUvRemap(float u0, float v0, float u1, float v1) {
        uvRemap = true;
        uvU0 = u0;
        uvV0 = v0;
        uvDU = u1 - u0;
        uvDV = v1 - v0;
    }

    /** Use ModelPart UVs as-is (full-texture models). */
    public void clearUvRemap() {
        uvRemap = false;
    }

    public boolean isEmpty() {
        return idx.isEmpty();
    }

    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v,
                          int overlay, int light, float nx, float ny, float nz) {
        if (uvRemap) { // ModelPart 0..1 UV → sprite's atlas region (block-entity Material sprites)
            u = uvU0 + u * uvDU;
            v = uvV0 + v * uvDV;
        }
        qx[n] = x; qy[n] = y; qz[n] = z;
        qu[n] = u; qv[n] = v;
        qnx[n] = nx; qny[n] = ny; qnz[n] = nz;
        qcol[n] = color;
        if (++n == 4) {
            emitQuad();
            n = 0;
        }
    }

    /**
     * Capture a {@link BakedQuad} (held/dropped items via {@code submitItem}, falling blocks via {@code
     * submitBlockModel}) — its 4 positions transformed by {@code pose}, atlas UV from {@code packedUV},
     * a flat {@code color} tint. These quads carry no authored normal, so emitQuad computes a geometric
     * one. They sample the block atlas (the capture's {@code currentTexSlot} = 0, the bindless fallback).
     */
    public void addBakedQuad(Matrix4f pose, BakedQuad quad, int color) {
        for (int i = 0; i < 4; i++) {
            Vector3fc p = quad.position(i);
            pose.transformPosition(p.x(), p.y(), p.z(), scratch);
            long uv = quad.packedUV(i);
            qx[n] = scratch.x; qy[n] = scratch.y; qz[n] = scratch.z;
            qu[n] = Float.intBitsToFloat((int) (uv >>> 32));
            qv[n] = Float.intBitsToFloat((int) uv);
            qnx[n] = 0f; qny[n] = 0f; qnz[n] = 0f; // no authored normal → emitQuad falls back to geometric
            qcol[n] = color;
            if (++n == 4) {
                emitQuad();
                n = 0;
            }
        }
    }

    private void emitQuad() {
        int base = verts.size() / 3;
        for (int i = 0; i < 4; i++) {
            verts.add(qx[i]);
            verts.add(qy[i]);
            verts.add(qz[i]);
            uvList.add(qu[i]);
            uvList.add(qv[i]);
        }
        idx.add(base);
        idx.add(base + 1);
        idx.add(base + 2);
        idx.add(base);
        idx.add(base + 2);
        idx.add(base + 3);

        // Authored model normal (pose-transformed by compile); planar quad, so vertex 0's normal is the
        // face normal. Baked quads (items/blocks) pass no normal → fall back to a geometric one from the
        // quad edges. The closest-hit flips it toward the viewer, as for terrain.
        float nx = qnx[0], ny = qny[0], nz = qnz[0];
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len <= 1.0e-6f) {
            float ex1 = qx[1] - qx[0], ey1 = qy[1] - qy[0], ez1 = qz[1] - qz[0];
            float ex2 = qx[2] - qx[0], ey2 = qy[2] - qy[0], ez2 = qz[2] - qz[0];
            nx = ey1 * ez2 - ez1 * ey2;
            ny = ez1 * ex2 - ex1 * ez2;
            nz = ex1 * ey2 - ey1 * ex2;
            len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        }
        if (len > 1.0e-6f) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        // Vertex colour as a flat per-prim tint (ARGB → rgb). White (-1) for most models → grey when lit;
        // real per-texture colour arrives with entity textures (P5.1b-2b).
        int c = qcol[0];
        float tr = ((c >> 16) & 0xFF) * (1f / 255f);
        float tg = ((c >> 8) & 0xFF) * (1f / 255f);
        float tb = (c & 0xFF) * (1f / 255f);
        for (int t = 0; t < 2; t++) { // one {normal+emission, tint, mat} record per triangle
            prim.add(nx);
            prim.add(ny);
            prim.add(nz);
            // normal.w: entities don't carry block-light emission, so this lane flags an alpha-blended
            // (translucent) surface → world.rahit does stochastic transparency instead of a binary cutout.
            prim.add(currentTranslucent ? 1f : 0f);
            prim.add(tr);
            prim.add(tg);
            prim.add(tb);
            prim.add((float) currentTexSlot); // tint.w = bindless texture slot (P5.1b-2b)
            prim.add(RtMaterials.ENTITY_ROUGH); // P6.1: entities default to a matte dielectric
            prim.add(0f);                       // metalness
            // mat.z / mat.w: LabPBR _s / _n presence + source. 0 = none, 1 = per-type bindless entity atlas
            // (P6.2c mobs), 2 = block atlas (block-like entities; sampled from the terrain _s/_n atlases).
            float matSource = currentBlockAtlas ? 2f : 1f;
            prim.add(currentHasS ? matSource : 0f); // mat.z
            prim.add(currentHasN ? matSource : 0f); // mat.w
        }
    }

    // Unused VertexConsumer surface — ModelPart.Cube.compile only calls the bulk addVertex above.
    @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
    @Override public VertexConsumer setColor(int color) { return this; }
    @Override public VertexConsumer setUv(float u, float v) { return this; }
    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    @Override public VertexConsumer setLineWidth(float width) { return this; }
}
