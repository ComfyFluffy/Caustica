package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.material.RtMaterialAbi;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrainMesher.PackedSection;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

/** Worker-owned terrain buffer allocation/fill and BLAS preparation. */
final class RtSectionBuilder {
    private RtSectionBuilder() {
    }

    /** Upload a non-empty packed section and prepare, but do not record, its BLAS build. */
    static PreparedSection prepare(RtContext ctx, PackedSection packed,
                                   RtAccel.OpacityMicromapInput ommInput,
                                   boolean compactBlas,
                                   long key, int sox, int soy, int soz) {
        RtMaterialAbi.requireTriangleParity(packed.material().length, packed.indices().length);
        int vertCount = packed.positions().length / 3;
        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure
                .VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int storage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        String label = "terrain section " + sox + "," + soy + "," + soz;
        // Resident geometry is device-local. A single mapped staging allocation exists only until
        // the async copy/build completes, avoiding a render-distance-sized host-visible working set.
        RtBuffer positions = null;
        RtBuffer indices = null;
        RtBuffer uvs = null;
        RtBuffer material = null;
        RtBuffer upload = null;
        RtAccel.PreparedBlas blas = null;
        try {
            long positionsBytes = (long) packed.positions().length * Float.BYTES;
            long indicesBytes = (long) packed.indices().length * Integer.BYTES;
            long uvsBytes = (long) packed.uvs().length * Float.BYTES;
            long materialBytes = (long) packed.material().length * Float.BYTES;
            int transferDst = VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

            positions = ctx.createAsyncBuffer(positionsBytes, asInput | transferDst, false,
                    label + " positions");
            indices = ctx.createAsyncBuffer(indicesBytes, asInput | transferDst, false,
                    label + " indices");
            uvs = ctx.createAsyncBuffer(uvsBytes, storage | transferDst, false, label + " uvs");
            material = ctx.createAsyncBuffer(materialBytes, storage | transferDst, false, label + " material");
            upload = ctx.createUploadBuffer(positionsBytes + indicesBytes + uvsBytes + materialBytes,
                    label + " upload");

            long cursor = upload.mapped;
            MemoryUtil.memFloatBuffer(cursor, packed.positions().length).put(packed.positions());
            cursor += positionsBytes;
            MemoryUtil.memIntBuffer(cursor, packed.indices().length).put(packed.indices());
            cursor += indicesBytes;
            MemoryUtil.memFloatBuffer(cursor, packed.uvs().length).put(packed.uvs());
            cursor += uvsBytes;
            MemoryUtil.memFloatBuffer(cursor, packed.material().length).put(packed.material());
            upload.flush();

            blas = RtAccel.prepareTerrainBlas(ctx, positions, vertCount, indices,
                    packed.bucketTris(), ommInput, compactBlas, label + " BLAS");
            return new PreparedSection(key, positions, indices, uvs, material, upload, blas,
                    packed.triBase(), sox, soy, soz, packed.lights());
        } catch (Throwable t) {
            if (blas != null) {
                destroy(new PreparedSection(key, positions, indices, uvs, material, upload, blas,
                        packed.triBase(), sox, soy, soz, packed.lights()));
            } else {
                if (upload != null) upload.destroy();
                if (material != null) material.destroy();
                if (uvs != null) uvs.destroy();
                if (indices != null) indices.destroy();
                if (positions != null) positions.destroy();
            }
            throw t;
        }
    }

    /** Copy the packed staging allocation into device-local section buffers before the BLAS build. */
    static void recordUpload(VkCommandBuffer cmd, PreparedSection prepared) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            long srcOffset = 0L;
            copy(cmd, prepared.upload, prepared.positions, srcOffset, region);
            srcOffset += prepared.positions.size;
            copy(cmd, prepared.upload, prepared.indices, srcOffset, region);
            srcOffset += prepared.indices.size;
            copy(cmd, prepared.upload, prepared.uvs, srcOffset, region);
            srcOffset += prepared.uvs.size;
            copy(cmd, prepared.upload, prepared.material, srcOffset, region);

            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
            barrier.get(0).sType$Default()
                    .srcStageMask(VK_PIPELINE_STAGE_2_TRANSFER_BIT)
                    .srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR)
                    // Vertex/index build inputs are shader reads at the AS-build stage. The
                    // ACCELERATION_STRUCTURE_READ access class is for reading AS objects themselves.
                    .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier);
            vkCmdPipelineBarrier2KHR(cmd, dependency);
        }
    }

    private static void copy(VkCommandBuffer cmd, RtBuffer upload, RtBuffer destination,
                             long srcOffset, VkBufferCopy.Buffer region) {
        region.get(0).srcOffset(srcOffset).dstOffset(0L).size(destination.size);
        VK10.vkCmdCopyBuffer(cmd, upload.handle, destination.handle, region);
    }

    static void destroy(PreparedSection prepared) {
        RtAccel.freeBlasScratch(java.util.List.of(prepared.blas));
        prepared.blas.accel.destroy();
        prepared.upload.destroy();
        prepared.material.destroy();
        prepared.uvs.destroy();
        prepared.indices.destroy();
        prepared.positions.destroy();
    }

    /** Worker-owned native section state paired with its prepared BLAS. {@code lights} = packed
     *  section-local ReSTIR light records (CPU-side, flattened into the global buffer at publish). */
    record PreparedSection(long key, RtBuffer positions, RtBuffer indices, RtBuffer uvs,
                           RtBuffer material, RtBuffer upload, RtAccel.PreparedBlas blas, int[] triBase,
                           int sx, int sy, int sz, float[] lights) {
        void releaseUpload() {
            upload.destroy();
        }

        void releaseBuildInputs() {
            indices.destroy();
            positions.destroy();
        }

        PreparedSection withBlas(RtAccel.PreparedBlas replacement) {
            return new PreparedSection(key, positions, indices, uvs, material, upload, replacement,
                    triBase, sx, sy, sz, lights);
        }
    }
}
