package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.gen.SharcFrameData;
import dev.comfyfluffy.caustica.rt.gen.SharcFrameData.Float3;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferMemoryBarrier2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

/** Persistent directional-SH tables plus a timeline-safe mapped SHaRC frame ring. */
public final class RtSharcCache {
    public static final int MIN_EXPONENT = 16;
    public static final int MAX_EXPONENT = 23;
    public static final int RING = 6;
    private static final int QUERY_WARMUP_FRAMES = 16;
    private static final int ACCUMULATION_STRIDE = 32;
    private static final int RESOLVED_STRIDE = 24;
    private static final float SHARC_WORLD_LIMIT = 1.0e6f;
    public static final long MAX_TABLE_BYTES = 768L * 1024L * 1024L;

    private final RtBuffer hashEntries;
    private final RtBuffer accumulation;
    private final RtBuffer resolved;
    private final RtBuffer[] tables;
    private final RtBuffer[] queryTables;
    private final RtBuffer[] frames;
    private final RtGpuExecutor.TrackedGraphicsUse[] frameUses;
    private final int exponent;
    private final int capacity;
    private int slot = -1;
    private boolean pendingClear = true;
    private int framesSinceReset;
    private Float3 previousCamera;
    private boolean destroyed;

    private RtSharcCache(RtBuffer hashEntries, RtBuffer accumulation, RtBuffer resolved,
                         RtBuffer[] frames, int exponent, int capacity) {
        this.hashEntries = hashEntries;
        this.accumulation = accumulation;
        this.resolved = resolved;
        this.tables = new RtBuffer[]{hashEntries, accumulation, resolved};
        this.queryTables = new RtBuffer[]{hashEntries, resolved};
        this.frames = frames;
        this.frameUses = new RtGpuExecutor.TrackedGraphicsUse[RING];
        for (int i = 0; i < RING; i++) frameUses[i] = new RtGpuExecutor.TrackedGraphicsUse();
        this.exponent = exponent;
        this.capacity = capacity;
    }

    public static RtSharcCache create(RtContext ctx, int requestedExponent) {
        int exponent = clampExponent(requestedExponent);
        int capacity = 1 << exponent;
        long tableBytes = tableBytesForExponent(exponent);
        if (tableBytes > MAX_TABLE_BYTES) {
            throw new IllegalArgumentException("SHaRC cache exponent " + exponent + " requires "
                    + tableBytes + " bytes, above the " + MAX_TABLE_BYTES + " byte safety limit");
        }

        int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        RtBuffer hash = null;
        RtBuffer accum = null;
        RtBuffer packed = null;
        RtBuffer[] frameRing = new RtBuffer[RING];
        try {
            hash = ctx.createBuffer((long) capacity * 8L, usage, false, "SHaRC hash entries");
            accum = ctx.createBuffer((long) capacity * ACCUMULATION_STRIDE, usage, false,
                    "SHaRC directional-SH accumulation");
            packed = ctx.createBuffer((long) capacity * RESOLVED_STRIDE, usage, false,
                    "SHaRC directional-SH resolved");
            for (int i = 0; i < RING; i++) {
                frameRing[i] = ctx.createBuffer(SharcFrameData.BYTE_SIZE, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        true, "SHaRC frame " + i);
                MemoryUtil.memSet(frameRing[i].mapped, 0, SharcFrameData.BYTE_SIZE);
                frameRing[i].flush(0L, SharcFrameData.BYTE_SIZE);
            }
            return new RtSharcCache(hash, accum, packed, frameRing, exponent, capacity);
        } catch (Throwable t) {
            if (hash != null) hash.destroy();
            if (accum != null) accum.destroy();
            if (packed != null) packed.destroy();
            for (RtBuffer frame : frameRing) if (frame != null) frame.destroy();
            throw t;
        }
    }

    public static int clampExponent(int requestedExponent) {
        return Math.clamp(requestedExponent, MIN_EXPONENT, MAX_EXPONENT);
    }

    public static long tableBytesForExponent(int requestedExponent) {
        int exponent = clampExponent(requestedExponent);
        int capacity = 1 << exponent;
        try {
            return Math.addExact(Math.multiplyExact((long) capacity, 8L),
                    Math.addExact(Math.multiplyExact((long) capacity, ACCUMULATION_STRIDE),
                            Math.multiplyExact((long) capacity, RESOLVED_STRIDE)));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("SHaRC cache size overflow for exponent " + exponent, e);
        }
    }

    /** Estimated persistent SHaRC buffer footprint, including the mapped frame ring. */
    public static long memoryBytesForExponent(int requestedExponent) {
        int exponent = clampExponent(requestedExponent);
        try {
            return Math.addExact(tableBytesForExponent(exponent),
                    Math.multiplyExact((long) RING, SharcFrameData.BYTE_SIZE));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("SHaRC memory estimate overflow for exponent " + exponent, e);
        }
    }

    public int exponent() {
        return exponent;
    }

    public int capacity() {
        return capacity;
    }

    public static int updateTileSize() {
        return CausticaConfig.Rt.Sharc.UPDATE_TILE_SIZE.value();
    }

    static float sanitizeCameraCoordinate(float value) {
        return Float.isFinite(value) && Math.abs(value) <= SHARC_WORLD_LIMIT ? value : 0.0f;
    }

    public void requestReset() {
        pendingClear = true;
        framesSinceReset = 0;
        previousCamera = null;
    }

    public boolean queryReady() {
        return framesSinceReset >= QUERY_WARMUP_FRAMES;
    }

    /** Advance the frame ring after waiting for its exact prior graphics use. */
    public long beginFrame(long frameIndex, float cameraX, float cameraY, float cameraZ,
                           RtGpuExecutor.GraphicsUseWaiter waiter) {
        slot = (slot + 1) % RING;
        waiter.await(frameUses[slot]);
        Float3 camera = new Float3(sanitizeCameraCoordinate(cameraX),
                sanitizeCameraCoordinate(cameraY), sanitizeCameraCoordinate(cameraZ));
        Float3 prior = previousCamera == null || pendingClear ? camera : previousCamera;
        ByteBuffer mapped = MemoryUtil.memByteBuffer(frames[slot].mapped, SharcFrameData.BYTE_SIZE);
        new SharcFrameData(hashEntries.deviceAddress, accumulation.deviceAddress, resolved.deviceAddress,
                camera, capacity, prior, (int) frameIndex,
                CausticaConfig.Rt.Sharc.SCENE_SCALE.value(),
                CausticaConfig.Rt.Sharc.RADIANCE_SCALE.value(),
                CausticaConfig.Rt.Sharc.ACCUMULATION_FRAMES.value(),
                CausticaConfig.Rt.Sharc.STALE_FRAMES.value(),
                CausticaConfig.Rt.Sharc.GRID_LOGARITHM_BASE.value(),
                CausticaConfig.Rt.Sharc.GRID_LEVEL_BIAS.value(),
                CausticaConfig.Rt.Sharc.ANTI_FIREFLY.value() ? 1 : 0).write(mapped);
        frames[slot].flush(0L, SharcFrameData.BYTE_SIZE);
        previousCamera = camera;
        framesSinceReset = Math.min(framesSinceReset + 1, QUERY_WARMUP_FRAMES + 1);
        return frames[slot].deviceAddress;
    }

    public void commitFrameUse(RtGpuExecutor.GraphicsUse graphicsUse) {
        frameUses[slot].mark(graphicsUse);
        // A clear becomes authoritative only after the command buffer was accepted for submission. If
        // recording or submission fails after recordPendingClear(), leave it pending so the next frame
        // cannot accidentally reuse the old tables.
        pendingClear = false;
    }

    /** Clear all persistent tables before the sparse update when a reset was requested. */
    public void recordPendingClear(VkCommandBuffer cmd, MemoryStack stack) {
        if (!pendingClear) return;
        org.lwjgl.vulkan.VK10.vkCmdFillBuffer(cmd, hashEntries.handle, 0L, hashEntries.size, 0);
        org.lwjgl.vulkan.VK10.vkCmdFillBuffer(cmd, accumulation.handle, 0L, accumulation.size, 0);
        org.lwjgl.vulkan.VK10.vkCmdFillBuffer(cmd, resolved.handle, 0L, resolved.size, 0);
        barrier(cmd, stack, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT);
    }

    public void updateToResolveBarrier(VkCommandBuffer cmd, MemoryStack stack) {
        barrier(cmd, stack, VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT);
    }

    public void resolveToQueryBarrier(VkCommandBuffer cmd, MemoryStack stack) {
        barrier(cmd, stack, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_SHADER_READ_BIT, queryTables);
    }

    private void barrier(VkCommandBuffer cmd, MemoryStack stack, long srcStage, long srcAccess,
                         long dstStage, long dstAccess) {
        barrier(cmd, stack, srcStage, srcAccess, dstStage, dstAccess, tables);
    }

    private void barrier(VkCommandBuffer cmd, MemoryStack stack, long srcStage, long srcAccess,
                         long dstStage, long dstAccess, RtBuffer[] buffers) {
        VkBufferMemoryBarrier2.Buffer barriers = VkBufferMemoryBarrier2.calloc(buffers.length, stack);
        for (int i = 0; i < buffers.length; i++) {
            barriers.get(i).sType$Default().srcStageMask(srcStage).srcAccessMask(srcAccess)
                    .dstStageMask(dstStage).dstAccessMask(dstAccess)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(buffers[i].handle).offset(0L).size(buffers[i].size);
        }
        VkDependencyInfo dependency = VkDependencyInfo.calloc(stack).sType$Default()
                .pBufferMemoryBarriers(barriers);
        vkCmdPipelineBarrier2KHR(cmd, dependency);
    }

    public void destroy() {
        if (destroyed) return;
        hashEntries.destroy();
        accumulation.destroy();
        resolved.destroy();
        for (RtBuffer frame : frames) frame.destroy();
        destroyed = true;
    }
}
