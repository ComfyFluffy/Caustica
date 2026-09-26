package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.SharcFrameData;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.SharcFrameData.Float3;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteOrder;

/**
 * SHaRC hash grid tables and the reset, warmup and camera state that decides how each frame uses them.
 * Entries follow SHaRC 1.8 with directional SH encoding: 8-byte keys, 32-byte accumulation and 24-byte
 * resolved entries, all zero-initialized as the SDK requires. Positions are scene coordinates, so a new
 * scene origin invalidates every key.
 */
final class RtSharcCache {
    /** Frames after a reset that update and resolve the cache before a query may use it. */
    static final int QUERY_WARMUP_FRAMES = 16;
    /** A camera move beyond this many scene units within one frame restarts the cache. */
    static final double CAMERA_JUMP = 64.0;
    // The resolve's linear probe (SHARC_LINEAR_PROBE_WINDOW_SIZE) reads up to eight keys past an entry.
    private static final int KEY_PADDING = 8;
    // SharcFrame.flags bits of sharc_types.slang.
    private static final int FLAG_ANTI_FIREFLY = 1;
    private static final int FLAG_PRIMARY_QUERY = 2;

    private final GpuBuffer hashEntries;
    private final GpuBuffer accumulation;
    private final GpuBuffer resolved;
    private final int capacity;
    private boolean clearPending = true;
    private int framesSinceReset;
    private RtRenderSettings.Sharc settings;
    private SceneOrigin origin;
    private Float3 camera;

    RtSharcCache(GpuBuffer hashEntries, GpuBuffer accumulation, GpuBuffer resolved, int capacity) {
        this.hashEntries = hashEntries;
        this.accumulation = accumulation;
        this.resolved = resolved;
        this.capacity = capacity;
    }

    static RtSharcCache create(VulkanDeviceContext context, int exponent) {
        int capacity = 1 << exponent;
        int usage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        return new RtSharcCache(
                context.createAlignedBuffer((capacity + KEY_PADDING) * 8L, usage, false, "SHaRC hash entries", 16),
                context.createAlignedBuffer(capacity * 32L, usage, false, "SHaRC accumulation", 16),
                context.createAlignedBuffer(capacity * 24L, usage, false, "SHaRC resolved", 16),
                capacity);
    }

    int capacity() {
        return capacity;
    }

    /** Clears the tables before the next update and restarts warmup. */
    void requestReset() {
        clearPending = true;
        framesSinceReset = 0;
        camera = null;
    }

    /**
     * Starts one frame that updates the cache: a new scene origin, changed settings or a camera jump
     * reset it first. Returns the previous frame's camera for the resolve, which is this camera after
     * a reset.
     */
    Float3 beginFrame(SceneOrigin origin, Float3 camera, RtRenderSettings.Sharc settings) {
        if (!origin.equals(this.origin) || !settings.equals(this.settings)
                || this.camera != null && jumped(this.camera, camera)) {
            requestReset();
        }
        Float3 previous = this.camera == null ? camera : this.camera;
        this.origin = origin;
        this.settings = settings;
        this.camera = camera;
        framesSinceReset = Math.min(framesSinceReset + 1, QUERY_WARMUP_FRAMES + 1);
        return previous;
    }

    /** Whether this frame's fill may query the cache; the warmup frames only update and resolve it. */
    boolean queryReady() {
        return framesSinceReset > QUERY_WARMUP_FRAMES;
    }

    private static boolean jumped(Float3 previous, Float3 camera) {
        double x = camera.x() - previous.x();
        double y = camera.y() - previous.y();
        double z = camera.z() - previous.z();
        return x * x + y * y + z * z > CAMERA_JUMP * CAMERA_JUMP;
    }

    /** One frame's SharcFrame in a mapped buffer the caller releases once the frame completes. */
    GpuBuffer writeFrame(VulkanDeviceContext context, int frameIndex, Float3 camera, Float3 previousCamera,
                         float preExposure, int renderWidth, int renderHeight) {
        GpuBuffer frame = context.createMappedGpuUploadBuffer(SharcFrameData.BYTE_SIZE,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "SHaRC frame");
        new SharcFrameData(hashEntries.deviceAddress().value(), accumulation.deviceAddress().value(),
                resolved.deviceAddress().value(), camera, capacity, previousCamera, frameIndex,
                settings.sceneScale(), settings.gridLogarithmBase(), settings.gridLevelBias(),
                settings.radianceScale(), preExposure, settings.accumulationFrames(), settings.staleFrames(),
                settings.updateTileSize(), new SharcFrameData.Int2(renderWidth, renderHeight),
                settings.roughnessThreshold(), (settings.antiFirefly() ? FLAG_ANTI_FIREFLY : 0)
                        | (settings.primarySurfaceDebug() ? FLAG_PRIMARY_QUERY : 0))
                .write(MemoryUtil.memByteBuffer(frame.mapped(), SharcFrameData.BYTE_SIZE)
                        .order(ByteOrder.nativeOrder()));
        frame.flush(0L, SharcFrameData.BYTE_SIZE);
        return frame;
    }

    /** Records the table clear a reset requested; the caller orders it before the update. */
    void recordPendingClear(VkCommandBuffer commandBuffer) {
        if (!clearPending) return;
        clearPending = false;
        VK10.vkCmdFillBuffer(commandBuffer, hashEntries.handle(), 0L, VK10.VK_WHOLE_SIZE, 0);
        VK10.vkCmdFillBuffer(commandBuffer, accumulation.handle(), 0L, VK10.VK_WHOLE_SIZE, 0);
        VK10.vkCmdFillBuffer(commandBuffer, resolved.handle(), 0L, VK10.VK_WHOLE_SIZE, 0);
    }

    void destroy() {
        hashEntries.destroy();
        accumulation.destroy();
        resolved.destroy();
    }
}
