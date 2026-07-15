package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.material.RtMaterialAbi;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrainMesher.PackedSection;
import org.lwjgl.system.MemoryUtil;

/** Worker-owned terrain buffer allocation/fill and BLAS preparation. */
final class RtSectionBuilder {
    private RtSectionBuilder() {
    }

    /** Upload a non-empty packed section and prepare, but do not record, its BLAS build. */
    static PreparedSection prepare(RtContext ctx, PackedSection packed,
                                   RtAccel.OpacityMicromapInput ommInput,
                                   long key, int sox, int soy, int soz) {
        RtMaterialAbi.requireTriangleParity(packed.material().length, packed.indices().length);
        int vertCount = packed.positions().length / 3;
        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        String label = "terrain section " + sox + "," + soy + "," + soz;
        // Terrain sections are long-lived and stream only as the residency window changes. Keep their
        // resources as direct VMA allocations so an eviction returns the allocation to VMA instead of
        // retaining the peak render-distance working set in the per-frame buffer cache.
        RtBuffer positions = null;
        RtBuffer indices = null;
        RtBuffer uvs = null;
        RtBuffer material = null;
        RtAccel.PreparedBlas blas = null;
        try {
            positions = ctx.createAsyncBuffer((long) packed.positions().length * Float.BYTES, asInput, true,
                    label + " positions");
            indices = ctx.createAsyncBuffer((long) packed.indices().length * Integer.BYTES, asInput | storage, true,
                    label + " indices");
            uvs = ctx.createAsyncBuffer((long) packed.uvs().length * Float.BYTES, storage, true,
                    label + " uvs");
            material = ctx.createAsyncBuffer((long) packed.material().length * Float.BYTES, storage, true,
                    label + " material");

            MemoryUtil.memFloatBuffer(positions.mapped, packed.positions().length).put(packed.positions());
            MemoryUtil.memIntBuffer(indices.mapped, packed.indices().length).put(packed.indices());
            MemoryUtil.memFloatBuffer(uvs.mapped, packed.uvs().length).put(packed.uvs());
            MemoryUtil.memFloatBuffer(material.mapped, packed.material().length).put(packed.material());
            positions.flush();
            indices.flush();
            uvs.flush();
            material.flush();

            blas = RtAccel.prepareTerrainBlas(ctx, positions, vertCount, indices,
                    packed.bucketTris(), ommInput, label + " BLAS");
            return new PreparedSection(key, positions, indices, uvs, material, blas,
                    packed.triBase(), sox, soy, soz);
        } catch (Throwable t) {
            if (blas != null) {
                destroy(new PreparedSection(key, positions, indices, uvs, material, blas,
                        packed.triBase(), sox, soy, soz));
            } else {
                if (material != null) material.destroy();
                if (uvs != null) uvs.destroy();
                if (indices != null) indices.destroy();
                if (positions != null) positions.destroy();
            }
            throw t;
        }
    }

    static void destroy(PreparedSection prepared) {
        RtAccel.freeBlasScratch(java.util.List.of(prepared.blas));
        prepared.blas.accel.destroy();
        prepared.material.destroy();
        prepared.uvs.destroy();
        prepared.indices.destroy();
        prepared.positions.destroy();
    }

    /** Worker-owned native section state paired with its prepared BLAS. */
    record PreparedSection(long key, RtBuffer positions, RtBuffer indices, RtBuffer uvs,
                           RtBuffer material, RtAccel.PreparedBlas blas, int[] triBase,
                           int sx, int sy, int sz) {
    }
}
