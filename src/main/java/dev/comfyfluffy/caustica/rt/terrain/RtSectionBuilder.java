package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrainMesher.PackedSection;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.lwjgl.system.MemoryUtil;

/** Render-thread upload and BLAS preparation for worker-packed terrain sections. */
final class RtSectionBuilder {
    private RtSectionBuilder() {
    }

    /** Upload a non-empty packed section and prepare, but do not record, its BLAS build. */
    static PreparedSection upload(RtContext ctx, PackedSection packed,
                                  RtAccel.OpacityMicromapInput ommInput,
                                  long key, int sox, int soy, int soz) {
        RtFrameStats.FRAME.count("sectionsUploaded", 1);
        int vertCount = packed.positions().length / 3;
        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        String label = "terrain section " + sox + "," + soy + "," + soz;
        // Terrain sections are long-lived and stream only as the residency window changes. Keep their
        // resources as direct VMA allocations so an eviction returns the allocation to VMA instead of
        // retaining the peak render-distance working set in the per-frame buffer cache.
        RtBuffer positions = ctx.createAsyncBuffer((long) packed.positions().length * Float.BYTES, asInput, true,
                label + " positions");
        RtBuffer indices = ctx.createAsyncBuffer((long) packed.indices().length * Integer.BYTES, asInput | storage, true,
                label + " indices");
        RtBuffer uvs = ctx.createAsyncBuffer((long) packed.uvs().length * Float.BYTES, storage, true,
                label + " uvs");
        RtBuffer material = ctx.createAsyncBuffer((long) packed.material().length * Float.BYTES, storage, true,
                label + " material");

        resolveMaterials(packed.material(), packed.materialSprites());
        MemoryUtil.memFloatBuffer(positions.mapped, packed.positions().length).put(packed.positions());
        MemoryUtil.memIntBuffer(indices.mapped, packed.indices().length).put(packed.indices());
        MemoryUtil.memFloatBuffer(uvs.mapped, packed.uvs().length).put(packed.uvs());
        MemoryUtil.memFloatBuffer(material.mapped, packed.material().length).put(packed.material());
        positions.flush();
        indices.flush();
        uvs.flush();
        material.flush();

        RtAccel.PreparedBlas blas = RtAccel.prepareTerrainBlas(ctx, positions, vertCount, indices,
                packed.bucketTris(), ommInput, label + " BLAS");
        return new PreparedSection(key, positions, indices, uvs, material, blas,
                packed.triBase(), sox, soy, soz);
    }

    /** Patch packed primitive records' hasS/hasN lanes via render-thread material ingestion. */
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

    /** A section uploaded with a prepared, not-yet-built BLAS. */
    record PreparedSection(long key, RtBuffer positions, RtBuffer indices, RtBuffer uvs,
                           RtBuffer material, RtAccel.PreparedBlas blas, int[] triBase,
                           int sx, int sy, int sz) {
    }
}
