package dev.comfyfluffy.caustica.rt.accel;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAccelerationStructureTrianglesOpacityMicromapEXT;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCopyAccelerationStructureInfoKHR;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkMicromapBuildInfoEXT;
import org.lwjgl.vulkan.VkMicromapBuildSizesInfoEXT;
import org.lwjgl.vulkan.VkMicromapCreateInfoEXT;
import org.lwjgl.vulkan.VkMicromapTriangleEXT;
import org.lwjgl.vulkan.VkMicromapUsageEXT;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;

import java.util.List;

import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_ACCESS_2_MICROMAP_READ_BIT_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_ACCESS_2_MICROMAP_WRITE_BIT_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_BUFFER_USAGE_MICROMAP_STORAGE_BIT_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_BUILD_MICROMAP_MODE_BUILD_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_BUILD_MICROMAP_PREFER_FAST_TRACE_BIT_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_PIPELINE_STAGE_2_MICROMAP_BUILD_BIT_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.vkCmdBuildMicromapsEXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.vkCreateMicromapEXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.vkDestroyMicromapEXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.vkGetMicromapBuildSizesEXT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPositionFetch.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_DATA_ACCESS_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_INDEX_TYPE_NONE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdCopyAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdWriteAccelerationStructuresPropertiesKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCreateAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkDestroyAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR;

/**
 * A built acceleration structure (BLAS or TLAS) plus its backing buffer. Build with the static
 * factories; free with {@link #destroy()}. One BLAS per section; one TLAS rebuilt per frame.
 */
public final class RtAccel {
    // vkCmdBuildMicromapsEXT requires both data.deviceAddress and triangleArray.deviceAddress to be
    // multiples of 256 (VUID-vkCmdBuildMicromapsEXT-pInfos-07515).
    private static final long MICROMAP_INPUT_ADDRESS_ALIGNMENT = 256L;

    /**
     * Size an allocation that will expose {@code requiredSize} bytes after its device address is aligned
     * for an acceleration-structure or micromap scratch address.
     */
    public static long scratchBufferSize(RtContext ctx, long requiredSize) {
        return Math.addExact(requiredSize, ctx.accelerationStructureScratchAlignment() - 1L);
    }

    private static RtBuffer createScratchBuffer(RtContext ctx, long requiredSize, String label) {
        return ctx.createBuffer(scratchBufferSize(ctx, requiredSize), VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false, label);
    }

    private static long scratchAddress(RtContext ctx, RtBuffer scratch) {
        return alignUp(scratch.deviceAddress, ctx.accelerationStructureScratchAlignment());
    }

    public final long handle;
    public final long deviceAddress;

    private final RtBuffer backing;
    private final boolean ownsBacking;
    private OpacityMicromap opacityMicromap;
    private long compactionQueryPool;
    private final VkDevice vk;
    private boolean destroyed;

    private RtAccel(VkDevice vk, long handle, long deviceAddress, RtBuffer backing) {
        this(vk, handle, deviceAddress, backing, true);
    }

    private RtAccel(VkDevice vk, long handle, long deviceAddress, RtBuffer backing, boolean ownsBacking) {
        this(vk, handle, deviceAddress, backing, ownsBacking, null);
    }

    private RtAccel(VkDevice vk, long handle, long deviceAddress, RtBuffer backing, boolean ownsBacking,
                    OpacityMicromap opacityMicromap) {
        this.vk = vk;
        this.handle = handle;
        this.deviceAddress = deviceAddress;
        this.backing = backing;
        this.ownsBacking = ownsBacking;
        this.opacityMicromap = opacityMicromap;
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        if (handle != 0L) {
            vkDestroyAccelerationStructureKHR(vk, handle, null);
        }
        if (opacityMicromap != null) {
            opacityMicromap.destroy();
            opacityMicromap = null;
        }
        if (compactionQueryPool != 0L) {
            VK10.vkDestroyQueryPool(vk, compactionQueryPool, null);
            compactionQueryPool = 0L;
        }
        // An entity BLAS's backing is caller-owned (released via releaseEntityBlas, not destroyed here).
        if (ownsBacking) {
            backing.destroy();
        }
        destroyed = true;
    }

    private OpacityMicromap detachOpacityMicromap() {
        OpacityMicromap result = opacityMicromap;
        opacityMicromap = null;
        return result;
    }

    /** CPU-generated opacity micromap input for one terrain geometry's triangle order. */
    public record OpacityMicromapInput(byte[] data, byte[] triangles, int triangleCount, int subdivisionLevel,
                                       int bytesPerTriangle) {
    }

    /** Pack {@code VkMicromapTriangleEXT[]} records into plain bytes so workers can prepare them off-thread. */
    public static byte[] opacityMicromapTriangles(int triangleCount, int subdivisionLevel, int bytesPerTriangle) {
        byte[] triangles = new byte[triangleCount * VkMicromapTriangleEXT.SIZEOF];
        for (int t = 0; t < triangleCount; t++) {
            int base = t * VkMicromapTriangleEXT.SIZEOF;
            putLe32(triangles, base, t * bytesPerTriangle);
            putLe16(triangles, base + 4, subdivisionLevel);
            putLe16(triangles, base + 6, VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
        }
        return triangles;
    }

    private static void putLe32(byte[] dst, int offset, int value) {
        dst[offset] = (byte) value;
        dst[offset + 1] = (byte) (value >>> 8);
        dst[offset + 2] = (byte) (value >>> 16);
        dst[offset + 3] = (byte) (value >>> 24);
    }

    private static void putLe16(byte[] dst, int offset, int value) {
        dst[offset] = (byte) value;
        dst[offset + 1] = (byte) (value >>> 8);
    }

    private static final class OpacityMicromap {
        final VkDevice vk;
        final long handle;
        final RtBuffer backing;
        RtBuffer data;
        RtBuffer triangles;
        RtBuffer scratch;
        final long scratchAddress; // aligned into the over-allocated scratch using the queried device limit
        final long dataAddress;
        final long triangleArrayAddress;
        final int triangleCount;
        final int subdivisionLevel;
        final int bytesPerTriangle;
        boolean destroyed;

        OpacityMicromap(VkDevice vk, long handle, RtBuffer backing, RtBuffer data, RtBuffer triangles,
                        RtBuffer scratch, long scratchAddress, long dataAddress, long triangleArrayAddress, int triangleCount,
                        int subdivisionLevel, int bytesPerTriangle) {
            this.vk = vk;
            this.handle = handle;
            this.backing = backing;
            this.data = data;
            this.triangles = triangles;
            this.scratch = scratch;
            this.scratchAddress = scratchAddress;
            this.dataAddress = dataAddress;
            this.triangleArrayAddress = triangleArrayAddress;
            this.triangleCount = triangleCount;
            this.subdivisionLevel = subdivisionLevel;
            this.bytesPerTriangle = bytesPerTriangle;
        }

        void freeBuildInputs() {
            if (scratch != null) {
                scratch.destroy();
                scratch = null;
            }
            if (triangles != null) {
                triangles.destroy();
                triangles = null;
            }
            if (data != null) {
                data.destroy();
                data = null;
            }
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            if (handle != 0L) {
                vkDestroyMicromapEXT(vk, handle, null);
            }
            freeBuildInputs();
            backing.destroy();
            destroyed = true;
        }
    }

    /**
     * A BLAS whose AS + backing buffer are allocated but whose build command is recorded later, so
     * many sections' builds can be batched into one submission — one {@code vkQueueSubmit} + fence
     * wait per tick instead of one per section (each submit drains the graphics queue, so per-section
     * submits were the dominant terrain-streaming stall).
     * {@code opaque} marks geometry {@code OPAQUE} (solid, no any-hit) vs
     * {@code NO_DUPLICATE_ANY_HIT_INVOCATION} for alpha-tested cutout.
     */
    public static final class PreparedBlas {
        public final RtAccel accel;
        private final RtBuffer scratch;
        // Non-null only for an entity BLAS (see prepareEntityBlas): the AS backing buffer, caller-owned,
        // so releaseEntityBlas destroys it explicitly rather than accel.destroy() doing so.
        private final RtBuffer externalBacking;
        private final long vertexAddr;
        private final long indexAddr;
        private final int maxVertex;
        private final int triangleCount;
        private final boolean opaque;
        private final String label;
        // Refit support. {@code updatable} = built with ALLOW_UPDATE (so it can be refit later);
        // {@code update} = this recorded op is an in-place UPDATE (refit) rather than a full BUILD.
        // Set for the entity refit path; false for terrain + transient block entities.
        private final boolean updatable;
        private final boolean update;
        // Terrain multi-geometry split (any-hit opt): one geometry per material bucket, in the fixed packed
        // order { solid, cutout, translucent, water } (see TERRAIN_BUCKETS). Bucket 0 (solid) is flagged
        // VK_GEOMETRY_OPAQUE_BIT. The fixed geometry indices are also SBT material indices: radiance rays
        // use closest-hit-only records for solid/translucent/water and an any-hit record for true cutout;
        // shadow rays use any-hit records for cutout/translucent/water.
        // Both split flags false ⇒ the legacy single-geometry path keyed on triangleCount.
        private final boolean terrainSplit;
        private final int[] terrainTris; // per-bucket triangle counts in TERRAIN_BUCKETS order (null if !terrainSplit)
        private final boolean entitySplit;
        private final int[] entityTris; // opaque/any-hit counts (null if !entitySplit)
        private final OpacityMicromap opacityMicromap; // optional, terrain cutout bucket only

        private PreparedBlas(RtAccel accel, RtBuffer scratch, RtBuffer externalBacking, long vertexAddr, long indexAddr,
                             int maxVertex, int triangleCount, boolean opaque, String label, boolean updatable, boolean update) {
            this(accel, scratch, externalBacking, vertexAddr, indexAddr, maxVertex, triangleCount, opaque, label,
                    updatable, update, false, null, false, null, null);
        }

        private PreparedBlas(RtAccel accel, RtBuffer scratch, RtBuffer externalBacking, long vertexAddr, long indexAddr,
                             int maxVertex, int triangleCount, boolean opaque, String label, boolean updatable, boolean update,
                             boolean terrainSplit, int[] terrainTris, boolean entitySplit, int[] entityTris,
                             OpacityMicromap opacityMicromap) {
            this.accel = accel;
            this.scratch = scratch;
            this.externalBacking = externalBacking;
            this.vertexAddr = vertexAddr;
            this.indexAddr = indexAddr;
            this.maxVertex = maxVertex;
            this.triangleCount = triangleCount;
            this.opaque = opaque;
            this.label = label;
            this.updatable = updatable;
            this.update = update;
            this.terrainSplit = terrainSplit;
            this.terrainTris = terrainTris;
            this.entitySplit = entitySplit;
            this.entityTris = entityTris;
            this.opacityMicromap = opacityMicromap;
        }

        /** A terrain section BLAS split into fixed per-bucket geometries in {@link RtAccel#TERRAIN_BUCKETS} order. */
        static PreparedBlas terrain(RtAccel accel, RtBuffer scratch, RtBuffer externalBacking, long vertexAddr, long indexAddr, int maxVertex,
                                    int[] terrainTris, OpacityMicromap opacityMicromap, String label) {
            int total = 0;
            for (int t : terrainTris) {
                total += t;
            }
            return new PreparedBlas(accel, scratch, externalBacking, vertexAddr, indexAddr, maxVertex,
                    total, false, label, false, false, true, terrainTris, false, null, opacityMicromap);
        }

        static PreparedBlas entity(RtAccel accel, RtBuffer scratch, RtBuffer externalBacking, long vertexAddr,
                                   long indexAddr, int maxVertex, int[] entityTris, String label,
                                   boolean updatable, boolean update) {
            int total = 0;
            for (int t : entityTris) total += t;
            return new PreparedBlas(accel, scratch, externalBacking, vertexAddr, indexAddr, maxVertex,
                    total, false, label, updatable, update, false, null, true, entityTris, null);
        }

        private void freeTransientBuildResources() {
            scratch.destroy();
            if (opacityMicromap != null) {
                opacityMicromap.freeBuildInputs();
            }
        }
    }

    /** Terrain material buckets. Geometry indices are fixed and double as SBT material record indices. */
    public static final int BUCKET_SOLID = 0;
    public static final int BUCKET_CUTOUT = 1;
    public static final int BUCKET_TRANSLUCENT = 2;
    public static final int BUCKET_WATER = 3;
    public static final int TERRAIN_BUCKETS = 4;
    /** Entity BLAS geometries: opaque bypasses any-hit; everything else shares one any-hit geometry. */
    public static final int ENTITY_BUCKET_OPAQUE = 0;
    public static final int ENTITY_BUCKET_ANY_HIT = 1;
    public static final int ENTITY_BUCKETS = 2;
    public static final int SBT_RAY_RADIANCE = 0;
    public static final int SBT_RAY_SHADOW = 1;
    public static final int SBT_TERRAIN_RADIANCE_OFFSET = SBT_RAY_RADIANCE * TERRAIN_BUCKETS;
    public static final int SBT_TERRAIN_SHADOW_OFFSET = SBT_RAY_SHADOW * TERRAIN_BUCKETS;
    public static final int SBT_ENTITY_OFFSET = TERRAIN_BUCKETS * 2;
    public static final int SBT_HIT_GROUP_COUNT = SBT_ENTITY_OFFSET + TERRAIN_BUCKETS * 2;

    /**
     * Result of {@link #prepareUpdatableBlasBuild}: the per-frame BUILD op to record, plus the persistent
     * resources the caller's per-entity ring must keep ({@code backing}) and cache ({@code updateScratchSize}
     * for sizing later refit scratch). The {@code scratch} is this frame's transient build scratch (release
     * at the frames-in-flight horizon, like the mesh buffers); the {@code op.accel} + {@code backing} persist.
     */
    public record UpdatableBuild(PreparedBlas op, RtAccel accel, RtBuffer backing, RtBuffer scratch, long updateScratchSize) {
    }

    /**
     * Initial BUILD for a caller-owned persistent BLAS that will never be updated in place. The caller
     * retains {@code accel} + {@code backing}, retires {@code scratch} after the build completes, and later
     * destroys the pair with {@link #destroyEntityAccel}. This avoids ALLOW_UPDATE overhead for immutable
     * cached geometry such as block entities.
     */
    public record PersistentBuild(PreparedBlas op, RtAccel accel, RtBuffer backing, RtBuffer scratch) {
    }

    /** Source and destination of the second, compact-copy phase of a terrain BLAS build. */
    public record PreparedTerrainCompaction(PreparedBlas source, PreparedBlas compacted) {
    }

    /** Allocate a BLAS (AS + backing + scratch) and query sizes, deferring the build to {@link #recordBlasBuilds}. */
    public static PreparedBlas prepareTrianglesBlas(RtContext ctx, RtBuffer positions, int vertexCount,
                                                    RtBuffer indices, int indexCount, boolean opaque, String label) {
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "BLAS");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryBlasSizes(vk, stack, positions, indices, vertexCount, indexCount, opaque, false);
            RtBuffer backing = ctx.createBuffer(sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false,
                    debugLabel + " backing");
            RtBuffer scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), debugLabel + " build scratch");
            RtAccel accel = createBlasOn(ctx, stack, backing, sizes.accelerationStructureSize(), true, debugLabel);
            return new PreparedBlas(accel, scratch, null, positions.deviceAddress, indices.deviceAddress, vertexCount - 1,
                    indexCount / 3, opaque, debugLabel, false, false);
        }
    }

    /**
     * Allocate a terrain section BLAS split into fixed material buckets (any-hit opt). {@code bucketTris}
     * holds triangle counts in {@link #TERRAIN_BUCKETS} order: solid, cutout, translucent, water. All
     * geometries reference the same packed vertex/index buffers; zero-triangle buckets are kept so
     * {@code gl_GeometryIndexEXT} remains a stable material/SBT index in the shaders.
     */
    public static PreparedBlas prepareTerrainBlas(RtContext ctx, RtBuffer positions, int vertexCount,
                                                  RtBuffer indices, int[] bucketTris, OpacityMicromapInput opacityMicromapInput,
                                                  String label) {
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "terrain BLAS");
        OpacityMicromap opacityMicromap = null;
        RtBuffer backing = null;
        RtBuffer scratch = null;
        RtAccel accel = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            opacityMicromap = prepareOpacityMicromap(ctx, opacityMicromapInput, debugLabel);
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryTerrainBlasSizes(vk, stack, positions, indices,
                    vertexCount, bucketTris, opacityMicromap);
            backing = ctx.createAsyncBuffer(sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false,
                    debugLabel + " backing");
            scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), debugLabel + " build scratch");
            accel = createBlasOn(ctx, stack, backing, sizes.accelerationStructureSize(), true, debugLabel, opacityMicromap);
            VkQueryPoolCreateInfo queryCi = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR).queryCount(1);
            java.nio.LongBuffer pQueryPool = stack.mallocLong(1);
            RtContext.check(VK10.vkCreateQueryPool(vk, queryCi, null, pQueryPool),
                    "vkCreateQueryPool(terrain BLAS compacted size)");
            accel.compactionQueryPool = pQueryPool.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_QUERY_POOL, accel.compactionQueryPool,
                    debugLabel + " compacted-size query");
            return PreparedBlas.terrain(accel, scratch, null, positions.deviceAddress, indices.deviceAddress, vertexCount - 1,
                    bucketTris, opacityMicromap, debugLabel);
        } catch (Throwable t) {
            if (accel != null) {
                accel.destroy();
                if (scratch != null) scratch.destroy();
            } else {
                if (scratch != null) scratch.destroy();
                if (backing != null) backing.destroy();
                if (opacityMicromap != null) opacityMicromap.destroy();
            }
            throw t;
        }
    }

    /**
     * Read a completed terrain build's compacted-size query and allocate its compact-copy destination.
     * Called only after the compute timeline confirms the build/query submission completed.
     */
    public static PreparedTerrainCompaction prepareTerrainCompaction(RtContext ctx, PreparedBlas source) {
        if (!source.terrainSplit || source.accel.compactionQueryPool == 0L) {
            throw new IllegalArgumentException("terrain BLAS has no pending compaction query");
        }
        long compactedSize;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.LongBuffer result = stack.mallocLong(1);
            RtContext.check(VK10.vkGetQueryPoolResults(ctx.vk(), source.accel.compactionQueryPool,
                    0, 1, result, Long.BYTES, VK10.VK_QUERY_RESULT_64_BIT),
                    "vkGetQueryPoolResults(terrain BLAS compacted size)");
            compactedSize = result.get(0);
        }
        VK10.vkDestroyQueryPool(ctx.vk(), source.accel.compactionQueryPool, null);
        source.accel.compactionQueryPool = 0L;
        if (compactedSize <= 0L) {
            throw new IllegalStateException("terrain BLAS compacted size is " + compactedSize);
        }

        RtBuffer backing = null;
        RtAccel compactedAccel = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            backing = ctx.createAsyncBuffer(compactedSize,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false,
                    source.label + " compacted backing");
            compactedAccel = createBlasOn(ctx, stack, backing, compactedSize, true,
                    source.label + " compacted");
            OpacityMicromap opacityMicromap = source.accel.detachOpacityMicromap();
            compactedAccel.opacityMicromap = opacityMicromap;
            PreparedBlas compacted = PreparedBlas.terrain(compactedAccel, source.scratch, null,
                    source.vertexAddr, source.indexAddr, source.maxVertex, source.terrainTris,
                    opacityMicromap, source.label);
            return new PreparedTerrainCompaction(source, compacted);
        } catch (Throwable t) {
            if (compactedAccel != null) {
                compactedAccel.destroy();
            } else if (backing != null) {
                backing.destroy();
            }
            throw t;
        }
    }

    private static OpacityMicromap prepareOpacityMicromap(RtContext ctx, OpacityMicromapInput input,
                                                          String blasLabel) {
        if (input == null || input.triangleCount() <= 0) {
            return null;
        }
        VkDevice vk = ctx.vk();
        String label = blasLabel + " opacity micromap";
        int inputUsage = VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT;
        RtBuffer data = null;
        RtBuffer triangles = null;
        RtBuffer backing = null;
        RtBuffer scratch = null;
        long handle = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            data = ctx.createAsyncBuffer(input.data().length + MICROMAP_INPUT_ADDRESS_ALIGNMENT - 1,
                    inputUsage, true, label + " data");
            long dataOffset = alignUp(data.deviceAddress, MICROMAP_INPUT_ADDRESS_ALIGNMENT) - data.deviceAddress;
            long dataAddress = data.deviceAddress + dataOffset;
            MemoryUtil.memByteBuffer(data.mapped + dataOffset, input.data().length).put(input.data());
            long triangleBytes = input.triangles().length;
            triangles = ctx.createAsyncBuffer(triangleBytes + MICROMAP_INPUT_ADDRESS_ALIGNMENT - 1, inputUsage, true,
                    label + " triangles");
            long triangleOffset = alignUp(triangles.deviceAddress, MICROMAP_INPUT_ADDRESS_ALIGNMENT) - triangles.deviceAddress;
            long triangleArrayAddress = triangles.deviceAddress + triangleOffset;
            MemoryUtil.memByteBuffer(triangles.mapped + triangleOffset, input.triangles().length).put(input.triangles());
            data.flush();
            triangles.flush();

            VkMicromapUsageEXT.Buffer usage = micromapUsage(stack, input.triangleCount(), input.subdivisionLevel());
            VkMicromapBuildInfoEXT build = micromapBuildInfo(stack, dataAddress, 0L, triangleArrayAddress, 0L, usage);
            VkMicromapBuildSizesInfoEXT sizes = VkMicromapBuildSizesInfoEXT.calloc(stack).sType$Default();
            vkGetMicromapBuildSizesEXT(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, build, sizes);

            backing = ctx.createAsyncBuffer(sizes.micromapSize(), VK_BUFFER_USAGE_MICROMAP_STORAGE_BIT_EXT, false,
                    label + " backing");
            VkMicromapCreateInfoEXT ci = VkMicromapCreateInfoEXT.calloc(stack).sType$Default()
                    .buffer(backing.handle).offset(0).size(sizes.micromapSize()).type(VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT);
            java.nio.LongBuffer pMicromap = stack.mallocLong(1);
            RtContext.check(vkCreateMicromapEXT(vk, ci, null, pMicromap), "vkCreateMicromapEXT");
            handle = pMicromap.get(0);
            RtDebugLabels.nameMicromap(ctx, handle, label);

            scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), label + " build scratch");
            long scratchAddress = scratchAddress(ctx, scratch);
            return new OpacityMicromap(vk, handle, backing, data, triangles, scratch, scratchAddress,
                    dataAddress, triangleArrayAddress, input.triangleCount(), input.subdivisionLevel(), input.bytesPerTriangle());
        } catch (Throwable t) {
            if (handle != 0L) vkDestroyMicromapEXT(vk, handle, null);
            if (scratch != null) scratch.destroy();
            if (backing != null) backing.destroy();
            if (triangles != null) triangles.destroy();
            if (data != null) data.destroy();
            throw t;
        }
    }

    /**
     * Entity-path variant of {@link #prepareTrianglesBlas}: fully transient, rebuilt fresh every frame (no
     * persistent per-entity ring), so the AS backing is caller-owned rather than accel-owned. Reclaimed with
     * {@link #releaseEntityBlas} (NOT {@code freeBlasScratch} + {@code accel.destroy()}). Used only by
     * {@link RtEntities}; the terrain path keeps {@link #prepareTrianglesBlas}.
     */
    public static PreparedBlas prepareEntityBlas(RtContext ctx, RtBuffer positions, int vertexCount,
                                                 RtBuffer indices, int indexCount, boolean opaque, String label) {
        return prepareEntityBlas(ctx, positions.deviceAddress, vertexCount,
                indices.deviceAddress, indexCount, opaque, label);
    }

    /** Address-based variant for transient entity geometry packed into sub-regions of one owner buffer. */
    public static PreparedBlas prepareEntityBlas(RtContext ctx, long vertexAddr, int vertexCount,
                                                 long indexAddr, int indexCount, boolean opaque, String label) {
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "entity BLAS");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryBlasSizes(vk, stack, vertexAddr, indexAddr,
                    vertexCount, indexCount, opaque, false);
            RtBuffer backing = ctx.createBuffer(sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false,
                    debugLabel + " backing");
            RtBuffer scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), debugLabel + " build scratch");
            RtAccel accel = createBlasOn(ctx, stack, backing, sizes.accelerationStructureSize(), false, debugLabel);
            return new PreparedBlas(accel, scratch, backing, vertexAddr, indexAddr, vertexCount - 1,
                    indexCount / 3, opaque, debugLabel, false, false);
        }
    }

    /** Multi-geometry entity BLAS: packed indices are ordered opaque, then any-hit. */
    public static PreparedBlas prepareEntityBlas(RtContext ctx, long vertexAddr, int vertexCount,
                                                 long indexAddr, int[] bucketTris, String label) {
        requireEntityBuckets(bucketTris);
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "entity BLAS");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryEntityBlasSizes(vk, stack, vertexAddr,
                    indexAddr, vertexCount, bucketTris, false);
            RtBuffer backing = ctx.createBuffer(sizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false, debugLabel + " backing");
            RtBuffer scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), debugLabel + " build scratch");
            RtAccel accel = createBlasOn(ctx, stack, backing, sizes.accelerationStructureSize(), false, debugLabel);
            return PreparedBlas.entity(accel, scratch, backing, vertexAddr, indexAddr, vertexCount - 1,
                    bucketTris.clone(), debugLabel, false, false);
        }
    }

    /** Prepare a non-updatable persistent BLAS over packed caller-owned geometry. */
    public static PersistentBuild preparePersistentBlasBuild(RtContext ctx, long vertexAddr, int vertexCount,
                                                             long indexAddr, int indexCount, boolean opaque,
                                                             String label) {
        PreparedBlas op = prepareEntityBlas(ctx, vertexAddr, vertexCount, indexAddr, indexCount, opaque, label);
        return new PersistentBuild(op, op.accel, op.externalBacking, op.scratch);
    }

    public static PersistentBuild preparePersistentEntityBlasBuild(RtContext ctx, long vertexAddr, int vertexCount,
                                                                   long indexAddr, int[] bucketTris, String label) {
        PreparedBlas op = prepareEntityBlas(ctx, vertexAddr, vertexCount, indexAddr, bucketTris, label);
        return new PersistentBuild(op, op.accel, op.externalBacking, op.scratch);
    }

    /**
     * Create a new <em>updatable</em> (ALLOW_UPDATE) BLAS sized for this mesh, and prepare its initial full
     * BUILD. The {@code accel} + {@code backing} persist in the caller's per-entity ring (NOT released per
     * frame); later frames refit it with {@link #refitUpdate} (cheap in-place UPDATE) while the topology is
     * stable, and free it with {@link #destroyEntityAccel} on eviction / topology change.
     */
    public static UpdatableBuild prepareUpdatableBlasBuild(RtContext ctx, RtBuffer positions, int vertexCount,
                                                           RtBuffer indices, int indexCount, boolean opaque, String label) {
        return prepareUpdatableBlasBuild(ctx, positions.deviceAddress, vertexCount,
                indices.deviceAddress, indexCount, opaque, label);
    }

    /** Address-based variant for entity geometry packed into sub-regions of one owner buffer. */
    public static UpdatableBuild prepareUpdatableBlasBuild(RtContext ctx, long vertexAddr, int vertexCount,
                                                           long indexAddr, int indexCount, boolean opaque, String label) {
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "updatable BLAS");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryBlasSizes(vk, stack, vertexAddr, indexAddr,
                    vertexCount, indexCount, opaque, true);
            long accelSize = sizes.accelerationStructureSize();
            long updateScratch = sizes.updateScratchSize();
            RtBuffer backing = ctx.createBuffer(accelSize, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false,
                    debugLabel + " backing");
            RtBuffer scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), debugLabel + " build scratch");
            RtAccel accel = createBlasOn(ctx, stack, backing, accelSize, false, debugLabel);
            PreparedBlas op = new PreparedBlas(accel, scratch, backing, vertexAddr, indexAddr,
                    vertexCount - 1, indexCount / 3, opaque, debugLabel, true, false);
            return new UpdatableBuild(op, accel, backing, scratch, updateScratch);
        }
    }

    public static UpdatableBuild prepareUpdatableEntityBlasBuild(RtContext ctx, long vertexAddr, int vertexCount,
                                                                 long indexAddr, int[] bucketTris, String label) {
        requireEntityBuckets(bucketTris);
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "updatable entity BLAS");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryEntityBlasSizes(vk, stack, vertexAddr,
                    indexAddr, vertexCount, bucketTris, true);
            long accelSize = sizes.accelerationStructureSize();
            RtBuffer backing = ctx.createBuffer(accelSize,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false, debugLabel + " backing");
            RtBuffer scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), debugLabel + " build scratch");
            RtAccel accel = createBlasOn(ctx, stack, backing, accelSize, false, debugLabel);
            PreparedBlas op = PreparedBlas.entity(accel, scratch, backing, vertexAddr, indexAddr,
                    vertexCount - 1, bucketTris.clone(), debugLabel, true, false);
            return new UpdatableBuild(op, accel, backing, scratch, sizes.updateScratchSize());
        }
    }

    /**
     * Prepare an in-place refit (UPDATE) of an existing updatable BLAS with new vertex data of the SAME
     * topology. {@code scratch} (sized {@code updateScratchSize}) and the mesh buffers are caller-owned
     * per-frame transients; the {@code accel} persists. Records nothing on its own — returned to {@link
     * #recordBlasBuilds} like a BUILD.
     */
    public static PreparedBlas refitUpdate(RtAccel accel, RtBuffer scratch, long vertexAddr, long indexAddr,
                                           int vertexCount, int indexCount, boolean opaque, String label) {
        String debugLabel = labelOr(label, "BLAS refit");
        return new PreparedBlas(accel, scratch, null, vertexAddr, indexAddr, vertexCount - 1, indexCount / 3,
                opaque, debugLabel, true, true);
    }

    public static PreparedBlas refitEntityUpdate(RtAccel accel, RtBuffer scratch, long vertexAddr, long indexAddr,
                                                 int vertexCount, int[] bucketTris, String label) {
        requireEntityBuckets(bucketTris);
        return PreparedBlas.entity(accel, scratch, null, vertexAddr, indexAddr, vertexCount - 1,
                bucketTris.clone(), labelOr(label, "entity BLAS refit"), true, true);
    }

    /** Reclaim a transient entity BLAS: destroy its AS handle, then its backing + scratch buffers. */
    public static void releaseEntityBlas(PreparedBlas blas) {
        blas.accel.destroy(); // ownsBacking == false → destroys only the AS handle, not the backing buffer
        blas.externalBacking.destroy();
        blas.scratch.destroy();
    }

    /** Destroy a caller-owned-backing persistent AS: destroy the handle, then its backing buffer. */
    public static void destroyEntityAccel(RtAccel accel, RtBuffer backing) {
        accel.destroy(); // ownsBacking == false → handle only
        backing.destroy();
    }

    private static VkAccelerationStructureBuildSizesInfoKHR queryBlasSizes(VkDevice vk, MemoryStack stack, RtBuffer positions,
                                                                           RtBuffer indices, int vertexCount, int indexCount, boolean opaque, boolean allowUpdate) {
        return queryBlasSizes(vk, stack, positions.deviceAddress, indices.deviceAddress,
                vertexCount, indexCount, opaque, allowUpdate);
    }

    private static VkAccelerationStructureBuildSizesInfoKHR queryBlasSizes(VkDevice vk, MemoryStack stack,
                                                                           long vertexAddr, long indexAddr,
                                                                           int vertexCount, int indexCount,
                                                                           boolean opaque, boolean allowUpdate) {
        VkAccelerationStructureGeometryKHR.Buffer geom = triangleGeometry(stack, vertexAddr, indexAddr,
                vertexCount, opaque);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(allowUpdate))
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(1).pGeometries(geom);
        VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
        vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                build.get(0), stack.ints(indexCount / 3), sizes);
        return sizes;
    }

    private static void requireEntityBuckets(int[] bucketTris) {
        if (bucketTris == null || bucketTris.length != ENTITY_BUCKETS) {
            throw new IllegalArgumentException("Expected " + ENTITY_BUCKETS + " entity bucket counts");
        }
        for (int count : bucketTris) {
            if (count < 0) throw new IllegalArgumentException("Negative entity triangle count");
        }
    }

    private static int buildFlags(boolean allowUpdate) {
        // ALLOW_DATA_ACCESS lets the closest-hit read vertex positions from the BLAS via
        // gl_HitTriangleVertexPositionsEXT (VK_KHR_ray_tracing_position_fetch) for the normal-map TBN.
        // Applied to every BLAS (terrain/entity) AND the refit path, so the build/UPDATE flags stay
        // identical (a refit invariant) — this is the single shared flag source.
        return VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_DATA_ACCESS_BIT_KHR
                | (allowUpdate ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR : 0);
    }

    private static RtAccel createBlasOn(RtContext ctx, MemoryStack stack, RtBuffer backing, long accelSize,
                                        boolean ownsBacking, String label) {
        return createBlasOn(ctx, stack, backing, accelSize, ownsBacking, label, null);
    }

    private static RtAccel createBlasOn(RtContext ctx, MemoryStack stack, RtBuffer backing, long accelSize,
                                        boolean ownsBacking, String label, OpacityMicromap opacityMicromap) {
        VkDevice vk = ctx.vk();
        VkAccelerationStructureCreateInfoKHR ci = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                .buffer(backing.handle).offset(0).size(accelSize).type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
        java.nio.LongBuffer pAs = stack.mallocLong(1);
        RtContext.check(vkCreateAccelerationStructureKHR(vk, ci, null, pAs), "vkCreateAccelerationStructureKHR");
        long handle = pAs.get(0);
        try {
            RtDebugLabels.nameAccelerationStructure(ctx, handle, label);
            VkAccelerationStructureDeviceAddressInfoKHR addrInfo = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                    .sType$Default().accelerationStructure(handle);
            long deviceAddress = vkGetAccelerationStructureDeviceAddressKHR(vk, addrInfo);
            return new RtAccel(vk, handle, deviceAddress, backing, ownsBacking, opacityMicromap);
        } catch (Throwable t) {
            vkDestroyAccelerationStructureKHR(vk, handle, null);
            throw t;
        }
    }

    private static VkAccelerationStructureGeometryKHR.Buffer triangleGeometry(MemoryStack stack, long vertexAddr, long indexAddr, int vertexCount, boolean opaque) {
        VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        fillTriangleGeometry(geom.get(0), vertexAddr, indexAddr, vertexCount, opaque);
        return geom;
    }

    private static void fillTriangleGeometry(VkAccelerationStructureGeometryKHR geom, long vertexAddr, long indexAddr, int vertexCount, boolean opaque) {
        geom.sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(opaque ? VK_GEOMETRY_OPAQUE_BIT_KHR : VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR);
        var tri = geom.geometry().triangles();
        tri.sType$Default()
                .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT).vertexStride(3L * Float.BYTES)
                .maxVertex(vertexCount - 1).indexType(VK10.VK_INDEX_TYPE_UINT32);
        tri.vertexData().deviceAddress(vertexAddr);
        tri.indexData().deviceAddress(indexAddr);
    }

    /** Fixed entity geometry order; empty buckets remain present so GeometryIndex/SBT routing is stable. */
    private static VkAccelerationStructureGeometryKHR.Buffer entityGeometries(MemoryStack stack, long vertexAddr,
                                                                               long indexAddr, int vertexCount) {
        VkAccelerationStructureGeometryKHR.Buffer geometries =
                VkAccelerationStructureGeometryKHR.calloc(ENTITY_BUCKETS, stack);
        for (int bucket = 0; bucket < ENTITY_BUCKETS; bucket++) {
            fillTriangleGeometry(geometries.get(bucket), vertexAddr, indexAddr, vertexCount,
                    bucket == ENTITY_BUCKET_OPAQUE);
        }
        return geometries;
    }

    private static VkAccelerationStructureBuildRangeInfoKHR.Buffer entityBuildRanges(MemoryStack stack,
                                                                                      int[] bucketTris) {
        VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(ENTITY_BUCKETS, stack);
        int triangleBase = 0;
        for (int bucket = 0; bucket < ENTITY_BUCKETS; bucket++) {
            int count = bucketTris[bucket];
            ranges.get(bucket).primitiveCount(count)
                    .primitiveOffset(triangleBase * 3 * Integer.BYTES)
                    .firstVertex(0).transformOffset(0);
            triangleBase += count;
        }
        return ranges;
    }

    private static VkAccelerationStructureBuildSizesInfoKHR queryEntityBlasSizes(VkDevice vk, MemoryStack stack,
                                                                                  long vertexAddr, long indexAddr,
                                                                                  int vertexCount, int[] bucketTris,
                                                                                  boolean allowUpdate) {
        VkAccelerationStructureGeometryKHR.Buffer geometries = entityGeometries(stack, vertexAddr, indexAddr, vertexCount);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.get(0).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(allowUpdate)).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(geometries.capacity()).pGeometries(geometries);
        java.nio.IntBuffer maxPrims = stack.mallocInt(ENTITY_BUCKETS);
        maxPrims.put(bucketTris).flip();
        VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
        vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                build.get(0), maxPrims, sizes);
        return sizes;
    }

    private static VkMicromapUsageEXT.Buffer micromapUsage(MemoryStack stack, int triangleCount, int subdivisionLevel) {
        VkMicromapUsageEXT.Buffer usage = VkMicromapUsageEXT.calloc(1, stack);
        usage.get(0).count(triangleCount)
                .subdivisionLevel(subdivisionLevel)
                .format(VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
        return usage;
    }

    private static VkMicromapBuildInfoEXT micromapBuildInfo(MemoryStack stack, long dataAddr, long scratchAddr,
                                                            long triangleArrayAddr, long dstMicromap,
                                                            VkMicromapUsageEXT.Buffer usage) {
        VkMicromapBuildInfoEXT build = VkMicromapBuildInfoEXT.calloc(stack).sType$Default()
                .type(VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT)
                .flags(VK_BUILD_MICROMAP_PREFER_FAST_TRACE_BIT_EXT)
                .mode(VK_BUILD_MICROMAP_MODE_BUILD_EXT)
                .dstMicromap(dstMicromap)
                .usageCountsCount(usage.capacity())
                .pUsageCounts(usage)
                .triangleArrayStride(VkMicromapTriangleEXT.SIZEOF);
        build.data().deviceAddress(dataAddr);
        build.scratchData().deviceAddress(scratchAddr);
        build.triangleArray().deviceAddress(triangleArrayAddr);
        return build;
    }

    /** One triangle geometry per bucket, in {@link #TERRAIN_BUCKETS} order; only solid is flagged opaque. */
    private static VkAccelerationStructureGeometryKHR.Buffer terrainGeometries(MemoryStack stack, long vertexAddr,
                                                                               long indexAddr, int vertexCount, int[] bucketTris,
                                                                               OpacityMicromap opacityMicromap) {
        VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(bucketTris.length, stack);
        VkAccelerationStructureTrianglesOpacityMicromapEXT ommAttachment = null;
        if (opacityMicromap != null && bucketTris[BUCKET_CUTOUT] > 0) {
            VkMicromapUsageEXT.Buffer usage = micromapUsage(stack, opacityMicromap.triangleCount, opacityMicromap.subdivisionLevel);
            ommAttachment = VkAccelerationStructureTrianglesOpacityMicromapEXT.calloc(stack).sType$Default()
                    .indexType(VK_INDEX_TYPE_NONE_KHR)
                    .indexStride(0L)
                    .baseTriangle(0)
                    .usageCountsCount(usage.capacity())
                    .pUsageCounts(usage)
                    .micromap(opacityMicromap.handle);
            ommAttachment.indexBuffer().deviceAddress(0L);
        }
        for (int b = 0; b < bucketTris.length; b++) {
            VkAccelerationStructureGeometryKHR out = geom.get(b);
            fillTriangleGeometry(out, vertexAddr, indexAddr, vertexCount, b == BUCKET_SOLID);
            if (b == BUCKET_CUTOUT && ommAttachment != null) {
                out.geometry().triangles().pNext(ommAttachment.address());
            }
        }
        return geom;
    }

    /** Build ranges parallel to {@link #terrainGeometries}; empty buckets get a zero primitive count. */
    private static VkAccelerationStructureBuildRangeInfoKHR.Buffer terrainBuildRanges(MemoryStack stack, int[] bucketTris) {
        VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(bucketTris.length, stack);
        int acc = 0;
        for (int b = 0; b < bucketTris.length; b++) {
            int tris = bucketTris[b];
            range.get(b).primitiveCount(tris).primitiveOffset(acc * 3 * Integer.BYTES).firstVertex(0).transformOffset(0);
            acc += tris;
        }
        return range;
    }

    private static int terrainGeomCount(int[] bucketTris) {
        return bucketTris.length;
    }

    private static VkAccelerationStructureBuildSizesInfoKHR queryTerrainBlasSizes(VkDevice vk, MemoryStack stack, RtBuffer positions,
                                                                                  RtBuffer indices, int vertexCount, int[] bucketTris,
                                                                                  OpacityMicromap opacityMicromap) {
        VkAccelerationStructureGeometryKHR.Buffer geom = terrainGeometries(stack, positions.deviceAddress, indices.deviceAddress,
                vertexCount, bucketTris, opacityMicromap);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(false) | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR)
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(geom.capacity()).pGeometries(geom);
        java.nio.IntBuffer maxPrims = stack.mallocInt(geom.capacity());
        for (int tris : bucketTris) {
            maxPrims.put(tris);
        }
        maxPrims.flip();
        VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
        vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                build.get(0), maxPrims, sizes);
        return sizes;
    }

    /**
     * A TLAS instance: a 3x4 row-major transform, the device address of its BLAS, the 24-bit
     * {@code instanceCustomIndex} the hit shaders read, the 8-bit visibility {@code mask} (ANDed with the
     * trace cull mask), and the base SBT hit-record offset. Terrain uses offset 0 so geometry index selects
     * the material bucket. Entities use {@link #SBT_ENTITY_OFFSET}; their fixed geometry index then selects
     * opaque or any-hit; the remaining two records in each four-record entity SBT block stay unused.
     */
    public record Instance(float[] transform3x4, long blasDeviceAddress, int customIndex, int mask, int sbtRecordOffset) {
        public Instance(float[] transform3x4, long blasDeviceAddress, int customIndex) {
            this(transform3x4, blasDeviceAddress, customIndex, 0xFF, 0);
        }

        public Instance(float[] transform3x4, long blasDeviceAddress, int customIndex, int mask) {
            this(transform3x4, blasDeviceAddress, customIndex, mask, 0);
        }
    }

    /** A build-ready TLAS view over a {@link TlasRing} slot's resources (the ring owns and frees them). */
    public static final class PreparedTlas {
        public final RtAccel accel;
        private final RtBuffer instanceBuffer;
        private final RtBuffer scratch;
        private final int instanceCount;
        private final String label;

        private PreparedTlas(RtAccel accel, RtBuffer instanceBuffer, RtBuffer scratch, int instanceCount, String label) {
            this.accel = accel;
            this.instanceBuffer = instanceBuffer;
            this.scratch = scratch;
            this.instanceCount = instanceCount;
            this.label = label;
        }
    }

    /**
     * Reusable per-frame TLAS resources. Allocating the instance buffer + AS backing + scratch fresh every
     * frame (and defer-destroying them 4 frames later) occasionally hit VMA's slow path — a fresh
     * VkDeviceMemory block allocation + map — observed as rare 20–50ms prepareTlas spikes. The ring keeps
     * {@value #RING} slots, each sized for a capacity instance count, and rebuilds the same AS in place: a
     * slot is reused every {@value #RING} frames (the established frames-in-flight horizon), so its
     * previous build/trace is off all queues before the instance buffer is rewritten. A slot is recreated
     * only when the instance count outgrows its capacity.
     */
    public static final class TlasRing {
        private static final int RING = 4;           // = the frames-in-flight KEEP_FRAMES horizon
        private static final float GROWTH = 1.25f;   // capacity headroom on (re)size
        private static final int MIN_CAPACITY = 1024;
        private final Slot[] slots = new Slot[RING];
        private int cursor;

        private static final class Slot {
            RtAccel accel;
            RtBuffer instanceBuffer;
            RtBuffer scratch;
            int capacity;

            void destroy() {
                accel.destroy();
                instanceBuffer.destroy();
                scratch.destroy();
            }
        }

        /** Free all slots. Teardown-only — the caller guarantees the device is idle. */
        public void destroy() {
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] != null) {
                    slots[i].destroy();
                    slots[i] = null;
                }
            }
        }
    }

    /**
     * Fill the next ring slot's instance buffer and return it as a build-ready TLAS (the slot's AS is
     * rebuilt in place — BUILD mode overwrites). Do NOT call {@link PreparedTlas#destroyAll} on the
     * result: the ring owns the resources.
     */
    public static PreparedTlas prepareTlas(RtContext ctx, List<Instance> instances, TlasRing ring) {
        return prepareTlas(ctx, instances, List.of(), ring);
    }

    /** Pack terrain and dynamic instances as two contiguous ranges without a composite-list get per item. */
    public static PreparedTlas prepareTlas(RtContext ctx, List<Instance> baseInstances,
                                           List<Instance> dynamicInstances, TlasRing ring) {
        int baseCount = baseInstances.size();
        int count = Math.addExact(baseCount, dynamicInstances.size());
        TlasRing.Slot slot = ring.slots[ring.cursor];
        if (slot == null || count > slot.capacity) {
            // Outgrown (or first use). The slot's previous use is RING frames behind — off all queues by
            // the same convention the old per-frame deferred free relied on — so immediate destroy is safe.
            if (slot != null) {
                slot.destroy();
            }
            slot = createTlasSlot(ctx, Math.max(TlasRing.MIN_CAPACITY, (int) (count * TlasRing.GROWTH)));
            ring.slots[ring.cursor] = slot;
        }
        ring.cursor = (ring.cursor + 1) % TlasRing.RING;

        writeTlasInstances(baseInstances, slot.instanceBuffer.mapped, 0);
        writeTlasInstances(dynamicInstances, slot.instanceBuffer.mapped, baseCount);
        if (count > 0) {
            slot.instanceBuffer.flush(0L, (long) count * VkAccelerationStructureInstanceKHR.SIZEOF);
        }
        return new PreparedTlas(slot.accel, slot.instanceBuffer, slot.scratch, count,
                "frame TLAS " + count + " instances");
    }

    // Pack directly into the mapped Vulkan array to keep this per-instance TLAS hot path allocation-free.
    private static void writeTlasInstances(List<Instance> instances, long mapped, int firstInstance) {
        final int facingCullDisable = 0x00000001;
        for (int i = 0, count = instances.size(); i < count; i++) {
            Instance instance = instances.get(i);
            long dst = mapped + (long) (firstInstance + i) * VkAccelerationStructureInstanceKHR.SIZEOF;
            float[] transform = instance.transform3x4();
            for (int component = 0; component < 12; component++) {
                MemoryUtil.memPutFloat(dst + (long) component * Float.BYTES, transform[component]);
            }
            MemoryUtil.memPutInt(dst + 48L,
                    (instance.customIndex() & 0x00FF_FFFF) | ((instance.mask() & 0xFF) << 24));
            MemoryUtil.memPutInt(dst + 52L,
                    (instance.sbtRecordOffset() & 0x00FF_FFFF) | (facingCullDisable << 24));
            MemoryUtil.memPutLong(dst + 56L, instance.blasDeviceAddress());
        }
    }

    /** Create one ring slot sized for {@code capacity} instances (instance buffer + AS + backing + scratch). */
    private static TlasRing.Slot createTlasSlot(RtContext ctx, int capacity) {
        VkDevice vk = ctx.vk();
        String label = "TLAS ring slot (" + capacity + " instance capacity)";
        TlasRing.Slot slot = new TlasRing.Slot();
        slot.capacity = capacity;
        slot.instanceBuffer = ctx.createBuffer((long) VkAccelerationStructureInstanceKHR.SIZEOF * capacity,
                org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, true,
                label + " instance buffer");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Size the AS + scratch for the slot CAPACITY: build sizes are monotonic in instance count, so
            // every per-frame build with count ≤ capacity fits the same backing/scratch.
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = tlasBuildInfo(stack, slot.instanceBuffer.deviceAddress);
            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    build.get(0), stack.ints(capacity), sizes);

            RtBuffer backing = ctx.createBuffer(sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false,
                    label + " backing");
            VkAccelerationStructureCreateInfoKHR ci = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                    .buffer(backing.handle).offset(0).size(sizes.accelerationStructureSize()).type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
            java.nio.LongBuffer pAs = stack.mallocLong(1);
            RtContext.check(vkCreateAccelerationStructureKHR(vk, ci, null, pAs), "vkCreateAccelerationStructureKHR");
            long handle = pAs.get(0);
            RtDebugLabels.nameAccelerationStructure(ctx, handle, label);
            slot.scratch = createScratchBuffer(ctx, sizes.buildScratchSize(), label + " build scratch");
            VkAccelerationStructureDeviceAddressInfoKHR addrInfo = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                    .sType$Default().accelerationStructure(handle);
            long deviceAddress = vkGetAccelerationStructureDeviceAddressKHR(vk, addrInfo);
            slot.accel = new RtAccel(vk, handle, deviceAddress, backing);
        }
        return slot;
    }

    private static VkAccelerationStructureBuildGeometryInfoKHR.Buffer tlasBuildInfo(MemoryStack stack, long instanceBufferAddr) {
        VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geom.sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR).flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
        geom.geometry().instances().sType$Default().arrayOfPointers(false);
        geom.geometry().instances().data().deviceAddress(instanceBufferAddr);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(1).pGeometries(geom);
        return build;
    }

    private static void recordBlasBuildsRaw(RtContext ctx, VkCommandBuffer cmd, List<PreparedBlas> blas) {
        for (PreparedBlas b : blas) {
            try (MemoryStack stack = MemoryStack.stackPush()) { // per-iteration: avoid 64 KB stack overflow
                recordBlasBuild(ctx, cmd, stack, b);
            }
        }
    }

    /** Record labelled BLAS builds into the command buffer. */
    public static void recordBlasBuilds(RtContext ctx, VkCommandBuffer cmd, List<PreparedBlas> blas) {
        String label = blas.size() == 1 ? blas.get(0).label + (blas.get(0).update ? " refit" : " build")
                : "BLAS builds " + blas.size();
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, label)) {
            recordBlasBuildsRaw(ctx, cmd, blas);
        }
    }

    /** Free the transient scratch buffers of a set of prepared BLAS (only after their build completed). */
    public static void freeBlasScratch(List<PreparedBlas> blas) {
        for (PreparedBlas b : blas) {
            b.freeTransientBuildResources();
        }
    }

    /** Record the compact copy after {@link #prepareTerrainCompaction} has sized its destination. */
    public static void recordTerrainCompaction(RtContext ctx, VkCommandBuffer cmd,
                                               PreparedTerrainCompaction compaction) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd,
                     compaction.source.label + " compact")) {
            accelerationStructureBuildBarrier(cmd, stack);
            VkCopyAccelerationStructureInfoKHR copy = VkCopyAccelerationStructureInfoKHR.calloc(stack)
                    .sType$Default()
                    .src(compaction.source.accel.handle)
                    .dst(compaction.compacted.accel.handle)
                    .mode(VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR);
            vkCmdCopyAccelerationStructureKHR(cmd, copy);
        }
    }

    /** Release the uncompacted source after the compact copy reaches timeline completion. */
    public static void finishTerrainCompaction(PreparedTerrainCompaction compaction) {
        compaction.source.accel.destroy();
    }

    /** Release both AS allocations after a failed compact-copy phase. Geometry buffers remain caller-owned. */
    public static void destroyTerrainCompaction(PreparedTerrainCompaction compaction) {
        compaction.compacted.accel.destroy();
        compaction.source.accel.destroy();
    }

    private static void recordTlasBuildRaw(RtContext ctx, VkCommandBuffer cmd, PreparedTlas tlas) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = tlasBuildInfo(stack, tlas.instanceBuffer.deviceAddress);
            build.get(0).dstAccelerationStructure(tlas.accel.handle);
            build.get(0).scratchData().deviceAddress(scratchAddress(ctx, tlas.scratch));
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            range.get(0).primitiveCount(tlas.instanceCount).primitiveOffset(0).firstVertex(0).transformOffset(0);
            PointerBuffer ppRange = stack.mallocPointer(1).put(0, range.address());
            vkCmdBuildAccelerationStructuresKHR(cmd, build, ppRange);
        }
    }

    /** Record a labelled TLAS build into the command buffer. */
    public static void recordTlasBuild(RtContext ctx, VkCommandBuffer cmd, PreparedTlas tlas) {
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, tlas.label + " build")) {
            recordTlasBuildRaw(ctx, cmd, tlas);
        }
    }

    private static void recordBlasBuild(RtContext ctx, VkCommandBuffer cmd, MemoryStack stack, PreparedBlas b) {
        if (b.terrainSplit) {
            recordTerrainBlasBuild(ctx, cmd, stack, b);
            return;
        }
        if (b.entitySplit) {
            recordEntityBlasBuild(ctx, cmd, stack, b);
            return;
        }
        VkAccelerationStructureGeometryKHR.Buffer geom = triangleGeometry(stack, b.vertexAddr, b.indexAddr, b.maxVertex + 1, b.opaque);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(b.updatable))
                .mode(b.update ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR : VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(1).pGeometries(geom)
                .dstAccelerationStructure(b.accel.handle);
        if (b.update) {
            // In-place refit: the existing (off-queue) AS is both source and destination. The flags +
            // topology (primitiveCount/maxVertex) must match its original ALLOW_UPDATE build.
            build.get(0).srcAccelerationStructure(b.accel.handle);
        }
        build.get(0).scratchData().deviceAddress(scratchAddress(ctx, b.scratch));
        VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
        range.get(0).primitiveCount(b.triangleCount).primitiveOffset(0).firstVertex(0).transformOffset(0);
        PointerBuffer ppRange = stack.mallocPointer(1).put(0, range.address());
        vkCmdBuildAccelerationStructuresKHR(cmd, build, ppRange);
    }

    /** Record the fixed opaque/any-hit entity geometries as one BUILD or in-place UPDATE. */
    private static void recordEntityBlasBuild(RtContext ctx, VkCommandBuffer cmd, MemoryStack stack,
                                              PreparedBlas b) {
        VkAccelerationStructureGeometryKHR.Buffer geometries = entityGeometries(stack, b.vertexAddr,
                b.indexAddr, b.maxVertex + 1);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.get(0).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(b.updatable))
                .mode(b.update ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR
                        : VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(geometries.capacity()).pGeometries(geometries)
                .dstAccelerationStructure(b.accel.handle);
        if (b.update) {
            build.get(0).srcAccelerationStructure(b.accel.handle);
        }
        build.get(0).scratchData().deviceAddress(scratchAddress(ctx, b.scratch));
        VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges = entityBuildRanges(stack, b.entityTris);
        PointerBuffer ppRanges = stack.mallocPointer(1).put(0, ranges.address());
        vkCmdBuildAccelerationStructuresKHR(cmd, build, ppRanges);
    }

    /** Record a terrain section's two-geometry (opaque + alpha) BUILD. Always a fresh BUILD — terrain
     *  sections are never refit in place (re-extraction allocates a new BLAS), so no UPDATE branch. */
    private static void recordTerrainBlasBuild(RtContext ctx, VkCommandBuffer cmd, MemoryStack stack, PreparedBlas b) {
        if (b.accel.compactionQueryPool == 0L) {
            throw new IllegalStateException("terrain BLAS has no compaction query pool");
        }
        VK10.vkCmdResetQueryPool(cmd, b.accel.compactionQueryPool, 0, 1);
        if (b.opacityMicromap != null) {
            recordMicromapBuild(cmd, stack, b.opacityMicromap);
            micromapBuildBarrier(cmd, stack);
        }
        VkAccelerationStructureGeometryKHR.Buffer geom = terrainGeometries(stack, b.vertexAddr, b.indexAddr,
                b.maxVertex + 1, b.terrainTris, b.opacityMicromap);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(false) | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR)
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(geom.capacity()).pGeometries(geom)
                .dstAccelerationStructure(b.accel.handle);
        build.get(0).scratchData().deviceAddress(scratchAddress(ctx, b.scratch));
        VkAccelerationStructureBuildRangeInfoKHR.Buffer range = terrainBuildRanges(stack, b.terrainTris);
        PointerBuffer ppRange = stack.mallocPointer(1).put(0, range.address());
        vkCmdBuildAccelerationStructuresKHR(cmd, build, ppRange);
        accelerationStructureBuildBarrier(cmd, stack);
        vkCmdWriteAccelerationStructuresPropertiesKHR(cmd, stack.longs(b.accel.handle),
                VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR,
                b.accel.compactionQueryPool, 0);
    }

    private static void recordMicromapBuild(VkCommandBuffer cmd, MemoryStack stack, OpacityMicromap opacityMicromap) {
        VkMicromapUsageEXT.Buffer usage = micromapUsage(stack, opacityMicromap.triangleCount, opacityMicromap.subdivisionLevel);
        VkMicromapBuildInfoEXT.Buffer build = VkMicromapBuildInfoEXT.calloc(1, stack);
        build.get(0).set(micromapBuildInfo(stack, opacityMicromap.dataAddress, opacityMicromap.scratchAddress,
                opacityMicromap.triangleArrayAddress, opacityMicromap.handle, usage));
        vkCmdBuildMicromapsEXT(cmd, build);
    }

    private static void micromapBuildBarrier(VkCommandBuffer cmd, MemoryStack stack) {
        VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
        barrier.get(0).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_MICROMAP_BUILD_BIT_EXT)
                .srcAccessMask(VK_ACCESS_2_MICROMAP_WRITE_BIT_EXT)
                .dstStageMask(VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR)
                .dstAccessMask(VK_ACCESS_2_MICROMAP_READ_BIT_EXT);
        VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier);
        vkCmdPipelineBarrier2KHR(cmd, dep);
    }

    private static void accelerationStructureBuildBarrier(VkCommandBuffer cmd, MemoryStack stack) {
        VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
        barrier.get(0).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR)
                .srcAccessMask(VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                .dstStageMask(VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR)
                .dstAccessMask(VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier);
        vkCmdPipelineBarrier2KHR(cmd, dep);
    }

    private static long alignUp(long value, long alignment) {
        return (value + alignment - 1) & -alignment;
    }

    private static String labelOr(String label, String fallback) {
        return label == null || label.isBlank() ? fallback : label;
    }
}
