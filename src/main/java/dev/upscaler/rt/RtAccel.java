package dev.upscaler.rt;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.lwjgl.vulkan.VkDevice;

import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCreateAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkDestroyAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR;

/**
 * A built acceleration structure (BLAS or TLAS) plus its backing buffer. Build with the static
 * factories; free with {@link #destroy()}. This is the unit P1's chunk lifecycle manages
 * (one BLAS per section, one TLAS rebuilt per frame).
 */
public final class RtAccel {
    public final long handle;
    public final long deviceAddress;

    private final RtBuffer backing;
    private final VkDevice vk;
    private boolean destroyed;

    private RtAccel(VkDevice vk, long handle, long deviceAddress, RtBuffer backing) {
        this.vk = vk;
        this.handle = handle;
        this.deviceAddress = deviceAddress;
        this.backing = backing;
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        if (handle != 0L) {
            vkDestroyAccelerationStructureKHR(vk, handle, null);
        }
        backing.destroy();
        destroyed = true;
    }

    /**
     * Build a bottom-level AS over an indexed triangle mesh (position-only vertex stream).
     * {@code opaque} marks the geometry {@code OPAQUE} (no any-hit, fastest) for solid meshes;
     * pass {@code false} for alpha-tested cutout geometry so the pipeline's any-hit shader runs
     * (flagged {@code NO_DUPLICATE_ANY_HIT_INVOCATION} so each primitive's any-hit fires at most once).
     */
    public static RtAccel buildTrianglesBlas(RtContext ctx, RtBuffer positions, int vertexCount,
                                             RtBuffer indices, int indexCount, boolean opaque) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geom.sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                    .flags(opaque ? VK_GEOMETRY_OPAQUE_BIT_KHR : VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR);
            var tri = geom.geometry().triangles();
            tri.sType$Default()
                    .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT).vertexStride(3L * Float.BYTES)
                    .maxVertex(vertexCount - 1).indexType(VK10.VK_INDEX_TYPE_UINT32);
            tri.vertexData().deviceAddress(positions.deviceAddress);
            tri.indexData().deviceAddress(indices.deviceAddress);

            VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(1).pGeometries(geom);

            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    build.get(0), stack.ints(indexCount / 3), sizes);

            RtAccel accel = createAndBuild(ctx, build, sizes, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
                    indexCount / 3, stack);
            return accel;
        }
    }

    /** A TLAS instance: a 3x4 row-major transform and the device address of its BLAS. */
    public record Instance(float[] transform3x4, long blasDeviceAddress) {
    }

    /** Build a top-level AS from instances. */
    public static RtAccel buildTlas(RtContext ctx, List<Instance> instances) {
        VkDevice vk = ctx.vk();
        int count = instances.size();
        RtBuffer instanceBuffer = ctx.createBuffer((long) VkAccelerationStructureInstanceKHR.SIZEOF * count,
                org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, true);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Reuse a single record + transform buffer across all instances: allocating per-instance
            // on the MemoryStack (64 KB/thread) overflows it once there are hundreds of sections.
            VkAccelerationStructureInstanceKHR rec = VkAccelerationStructureInstanceKHR.calloc(stack);
            java.nio.FloatBuffer xform = stack.mallocFloat(12);
            for (int i = 0; i < count; i++) {
                Instance inst = instances.get(i);
                xform.clear();
                xform.put(inst.transform3x4()).flip();
                rec.transform().matrix(xform);
                rec.instanceCustomIndex(i).mask(0xFF).instanceShaderBindingTableRecordOffset(0)
                        .flags(0x00000001) // VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR
                        .accelerationStructureReference(inst.blasDeviceAddress());
                MemoryUtil.memCopy(rec.address(), instanceBuffer.mapped + (long) i * VkAccelerationStructureInstanceKHR.SIZEOF,
                        VkAccelerationStructureInstanceKHR.SIZEOF);
            }

            VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geom.sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR).flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            geom.geometry().instances().sType$Default().arrayOfPointers(false);
            geom.geometry().instances().data().deviceAddress(instanceBuffer.deviceAddress);

            VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(1).pGeometries(geom);

            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    build.get(0), stack.ints(count), sizes);

            RtAccel accel = createAndBuild(ctx, build, sizes, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, count, stack);
            instanceBuffer.destroy(); // consumed by the build (synchronous), safe to free
            return accel;
        }
    }

    private static RtAccel createAndBuild(RtContext ctx, VkAccelerationStructureBuildGeometryInfoKHR.Buffer build,
                                          VkAccelerationStructureBuildSizesInfoKHR sizes, int type, int primitiveCount,
                                          MemoryStack stack) {
        VkDevice vk = ctx.vk();
        RtBuffer backing = ctx.createBuffer(sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
        VkAccelerationStructureCreateInfoKHR ci = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                .buffer(backing.handle).offset(0).size(sizes.accelerationStructureSize()).type(type);
        java.nio.LongBuffer pAs = stack.mallocLong(1);
        RtContext.check(vkCreateAccelerationStructureKHR(vk, ci, null, pAs), "vkCreateAccelerationStructureKHR");
        long handle = pAs.get(0);

        RtBuffer scratch = ctx.createBuffer(sizes.buildScratchSize(), VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
        build.get(0).dstAccelerationStructure(handle);
        build.get(0).scratchData().deviceAddress(scratch.deviceAddress);

        VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
        range.get(0).primitiveCount(primitiveCount).primitiveOffset(0).firstVertex(0).transformOffset(0);
        PointerBuffer ppRange = stack.mallocPointer(1).put(0, range.address());

        ctx.submitSync(cmd -> vkCmdBuildAccelerationStructuresKHR(cmd, build, ppRange));
        scratch.destroy();

        VkAccelerationStructureDeviceAddressInfoKHR addrInfo = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                .sType$Default().accelerationStructure(handle);
        long deviceAddress = vkGetAccelerationStructureDeviceAddressKHR(vk, addrInfo);
        return new RtAccel(vk, handle, deviceAddress, backing);
    }
}
