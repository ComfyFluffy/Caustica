package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Asynchronous lifecycle for one light-hierarchy generation at a time. CPU packing, global and
 * section-local alias construction, and light grid construction all run on a worker. The render thread
 * only atomically publishes GPU-complete buffers; the worker also allocates/fills staging and device
 * buffers and enqueues the copy on {@link RtGpuExecutor}. The previous complete generation remains
 * shader-visible until that point. This class does not coalesce concurrent requests itself — {@link
 * #request} requires the caller to already be idle (see {@link #isIdle()}); {@link RtTerrain} is the
 * single caller and owns the dirty/throttle coalescing in front of it.
 */
final class RtLightGridManager {
    private final Object buildLock = new Object();
    private final Object taskLock = new Object();
    private final ConcurrentLinkedQueue<Completion> completions = new ConcurrentLinkedQueue<>();
    private int activeTasks;
    private volatile long latestRequest;
    private PublishedState published = PublishedState.EMPTY;

    PublishedState published() {
        return published;
    }

    boolean hasCompletions() {
        return !completions.isEmpty();
    }

    /** True only when no worker/upload owns a generation and no completion is waiting to be published. */
    boolean isIdle() {
        synchronized (taskLock) {
            return activeTasks == 0 && completions.isEmpty();
        }
    }

    /**
     * Start building one hierarchy snapshot. The caller owns coalescing: this manager processes one
     * generation at a time, so callers must only invoke this while {@link #isIdle()}.
     */
    void request(RtContext ctx, Collection<RtLightHierarchy.SectionInput> sections,
                 int rebaseX, int rebaseY, int rebaseZ) {
        if (!isIdle()) {
            throw new IllegalStateException(
                    "RtLightGridManager.request() called while a generation is still in flight");
        }
        Input input = new Input(List.copyOf(sections), rebaseX, rebaseY, rebaseZ);
        long requestId;
        synchronized (buildLock) {
            requestId = ++latestRequest;
        }
        Request request = new Request(requestId, ctx, input);
        beginTask();
        try {
            RtWorkerPool.INSTANCE.submit(() -> runWorker(request));
        } catch (Throwable t) {
            finishTask();
            throw t;
        }
    }

    /** Publish only fully uploaded, internally coherent worker generations. */
    void publishReady(RtContext ctx) {
        Completion completion;
        while ((completion = completions.poll()) != null) {
            if (completion instanceof Failed failed) {
                if (isLatest(failed.requestId)) {
                    throw new RuntimeException("RT light hierarchy build failed for request "
                            + failed.requestId, failed.failure);
                }
                continue;
            }
            if (completion instanceof Empty empty) {
                if (isLatest(empty.requestId)) publishEmpty(ctx, empty.requestId);
                continue;
            }

            Uploaded uploaded = (Uploaded) completion;
            if (!isLatest(uploaded.requestId) || uploaded.failure != null) {
                uploaded.destroy();
                if (isLatest(uploaded.requestId) && uploaded.failure != null) {
                    throw new RuntimeException("RT light hierarchy upload failed for request "
                            + uploaded.requestId, uploaded.failure);
                }
                continue;
            }
            publish(ctx, uploaded);
        }
    }

    /** World-reset path only. Normal light changes intentionally retain the published generation. */
    void invalidate(RtContext ctx, long lastGraphicsUse) {
        cancelPending();
        PublishedState old = published;
        published = PublishedState.EMPTY;
        old.retire(ctx, lastGraphicsUse);
    }

    /** Invalidate the current generation (in-flight build/upload self-discards) and drop any completion. */
    void cancelPending() {
        synchronized (buildLock) {
            latestRequest++;
        }
        discardCompletions();
    }

    void awaitIdle() {
        synchronized (taskLock) {
            while (activeTasks != 0) {
                try {
                    taskLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted waiting for RT light hierarchy tasks", e);
                }
            }
        }
    }

    void destroyAfterDeviceIdle() {
        discardCompletions();
        PublishedState old = published;
        published = PublishedState.EMPTY;
        old.destroy();
    }

    private void runWorker(Request request) {
        try {
            RtLightHierarchy.Data data = RtLightHierarchy.build(request.input.sections,
                    request.input.rebaseX, request.input.rebaseY, request.input.rebaseZ,
                    () -> !isLatest(request.requestId));
            if (isLatest(request.requestId)) {
                if (data.lightCount() == 0) {
                    completions.add(new Empty(request.requestId));
                } else {
                    // VMA allocation, staging serialization/flush, and executor enqueue all stay on this
                    // worker. The render thread only atomically publishes Uploaded.
                    submitUpload(request.ctx, request.requestId, data);
                }
            }
        } catch (Throwable t) {
            if (isLatest(request.requestId)) {
                completions.add(new Failed(request.requestId, t));
            }
        } finally {
            finishTask();
        }
    }

    private void submitUpload(RtContext ctx, long requestId, RtLightHierarchy.Data data) {
        long budgetBytes = Math.multiplyExact(
                (long) CausticaConfig.Rt.Lights.HIERARCHY_BUDGET_MIB.value(), 1024L * 1024L);
        Layout layout = Layout.of(data, data.grid() != null);
        if (layout.totalBytes > budgetBytes && layout.hasGrid) {
            CausticaMod.LOGGER.warn("RT light hierarchy {} requires {} MiB (budget {} MiB); "
                            + "publishing its global proposal only", requestId,
                    (layout.totalBytes + 0xfffffL) >> 20, budgetBytes >> 20);
            layout = Layout.of(data, false);
        }
        if (layout.totalBytes > budgetBytes) {
            CausticaMod.LOGGER.error("RT light hierarchy {} global data requires {} MiB "
                            + "(budget {} MiB); retaining the previous published generation",
                    requestId, (layout.totalBytes + 0xfffffL) >> 20, budgetBytes >> 20);
            return;
        }

        RtBuffer arena = null;
        RtBuffer upload = null;
        try {
            int usage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            arena = ctx.createAsyncBuffer(layout.totalBytes, usage, false,
                    "terrain light hierarchy arena " + requestId);
            upload = ctx.createUploadBuffer(layout.totalBytes,
                    "terrain light hierarchy upload " + requestId);

            long cursor = upload.mapped + layout.lightOffset;
            MemoryUtil.memFloatBuffer(cursor, data.packedLights().length).put(data.packedLights());
            cursor = upload.mapped + layout.globalAliasOffset;
            writeAliases(cursor, data.globalAliases());
            RtLightGrid.Data grid = layout.hasGrid ? data.grid() : null;
            if (grid != null) {
                cursor = upload.mapped + layout.localAliasOffset;
                writeAliases(cursor, data.localAliases());
                cursor = upload.mapped + layout.cellOffset;
                for (int i = 0; i < grid.cellOffsets().length; i++) {
                    MemoryUtil.memPutInt(cursor, grid.cellOffsets()[i]);
                    MemoryUtil.memPutInt(cursor + 4, grid.cellCounts()[i]);
                    MemoryUtil.memPutFloat(cursor + 8, grid.cellInvWeightSums()[i]);
                    cursor += 12;
                }
                cursor = upload.mapped + layout.spanOffset;
                for (int i = 0; i < grid.spanFirstLights().length; i++) {
                    MemoryUtil.memPutInt(cursor, grid.spanFirstLights()[i]);
                    MemoryUtil.memPutInt(cursor + 4, grid.spanAliasFirstLights()[i]);
                    MemoryUtil.memPutInt(cursor + 8, grid.spanLightCounts()[i]
                            | (grid.spanAliasLightCounts()[i] << 16));
                    MemoryUtil.memPutFloat(cursor + 12, grid.spanAccept()[i]);
                    cursor += 16;
                }
            }
            upload.flush();

            RtBuffer submittedUpload = upload;
            RtBuffer submittedArena = arena;
            Layout submittedLayout = layout;
            beginTask();
            boolean accepted = false;
            try {
                ctx.gpuExecutor().submit(
                        () -> !isLatest(requestId),
                        cmd -> recordUpload(cmd, submittedUpload, submittedArena,
                                submittedLayout.totalBytes),
                        () -> { },
                        (build, failure) -> finishUpload(requestId, data,
                                submittedUpload, submittedArena, submittedLayout, build, failure));
                accepted = true;
                upload = null;
                arena = null;
            } finally {
                if (!accepted) finishTask();
            }
        } finally {
            if (upload != null) upload.destroy();
            if (arena != null) arena.destroy();
        }
    }

    private static void writeAliases(long cursor, RtLightHierarchy.AliasData aliases) {
        for (int i = 0; i < aliases.aliasIndices().length; i++) {
            MemoryUtil.memPutInt(cursor, aliases.aliasIndices()[i]);
            MemoryUtil.memPutFloat(cursor + 4, aliases.accept()[i]);
            cursor += 8;
        }
    }

    private void finishUpload(long requestId, RtLightHierarchy.Data data, RtBuffer upload,
                              RtBuffer arena, Layout layout,
                              RtGpuExecutor.Build build, Throwable failure) {
        try {
            upload.destroy();
        } finally {
            try {
                if (isLatest(requestId)) {
                    completions.add(new Uploaded(requestId, data, arena, layout, build, failure));
                }
                else arena.destroy();
            } finally {
                finishTask();
            }
        }
    }

    private void publish(RtContext ctx, Uploaded uploaded) {
        RtLightGrid.Data grid = uploaded.layout.hasGrid ? uploaded.data.grid() : null;
        PublishedState next = new PublishedState(uploaded.arena, uploaded.layout,
                uploaded.data.lightCount(), uploaded.data.invGlobalPowerSum(),
                grid != null ? grid.originX() : 0, grid != null ? grid.originY() : 0,
                grid != null ? grid.originZ() : 0, grid != null ? grid.dimX() : 0,
                grid != null ? grid.dimY() : 0, grid != null ? grid.dimZ() : 0,
                uploaded.data.rebaseX(), uploaded.data.rebaseY(), uploaded.data.rebaseZ(),
                uploaded.requestId);
        PublishedState old = published;
        // The executor's host-side timeline wait only proves that the transfer completed. It does not
        // establish device-memory visibility from the async queue to the graphics queue. Publish the
        // exact upload build so beginGraphicsTerrainUse() attaches the required semaphore dependency
        // before any shader can dereference this generation's buffer device addresses.
        ctx.gpuExecutor().markPublished(uploaded.build);
        published = next;
        old.retire(ctx, ctx.gpuExecutor().latestGraphicsUseValue());

        if (CausticaConfig.Rt.Lights.DUMP.value()) dumpNearbyLights(uploaded.data);

        if (CausticaConfig.Rt.Lights.STATS.value()) {
            CausticaMod.LOGGER.info("RT light hierarchy {}: {} lights / {} section slots / {} light grid spans / {} KiB",
                    uploaded.requestId, uploaded.data.lightCount(), uploaded.data.sectionFirstLights().length,
                    grid != null ? grid.spanFirstLights().length : 0,
                    (uploaded.layout.totalBytes + 1023L) >> 10);
        }
    }

    private static void dumpNearbyLights(RtLightHierarchy.Data data) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        double px = player.getX() - data.rebaseX();
        double py = player.getY() - data.rebaseY();
        double pz = player.getZ() - data.rebaseZ();
        double radius = CausticaConfig.Rt.Lights.DUMP_RADIUS.value();
        double radiusSq = radius * radius;
        float[] lights = data.packedLights();
        int dumped = 0;
        for (int light = 0; light < data.lightCount(); light++) {
            int record = light * RtLightHierarchy.GPU_FLOATS_PER_LIGHT;
            float x = lights[record];
            float y = lights[record + 1];
            float z = lights[record + 2];
            double dx = x - px, dy = y - py, dz = z - pz;
            if (dx * dx + dy * dy + dz * dz > radiusSq) continue;
            float area = lights[record + 3];
            int packedLe = Float.floatToRawIntBits(lights[record + 7]);
            float leR = RtLightHierarchy.unpackUnsignedFloat(packedLe & 0x7ff, 6);
            float leG = RtLightHierarchy.unpackUnsignedFloat((packedLe >>> 11) & 0x7ff, 6);
            float leB = RtLightHierarchy.unpackUnsignedFloat((packedLe >>> 22) & 0x3ff, 5);
            CausticaMod.LOGGER.info("RT light[{}] world=({}, {}, {}) area={} Le=({}, {}, {})",
                    light, x + data.rebaseX(), y + data.rebaseY(), z + data.rebaseZ(),
                    area, leR, leG, leB);
            dumped++;
        }
        CausticaMod.LOGGER.info("RT light dump: {} lights within {} blocks", dumped, (int) radius);
    }

    private void publishEmpty(RtContext ctx, long requestId) {
        if (!isLatest(requestId)) return;
        PublishedState old = published;
        published = PublishedState.empty(requestId);
        old.retire(ctx, ctx.gpuExecutor().latestGraphicsUseValue());
    }

    private void discardCompletions() {
        Completion completion;
        while ((completion = completions.poll()) != null) {
            if (completion instanceof Uploaded uploaded) uploaded.destroy();
        }
    }

    private boolean isLatest(long requestId) {
        return requestId == latestRequest;
    }

    private void beginTask() {
        synchronized (taskLock) {
            activeTasks++;
        }
    }

    private void finishTask() {
        synchronized (taskLock) {
            if (--activeTasks < 0) throw new IllegalStateException("RT hierarchy active-task underflow");
            if (activeTasks == 0) taskLock.notifyAll();
        }
    }

    private static void recordUpload(VkCommandBuffer cmd, RtBuffer upload, RtBuffer arena,
                                     long totalBytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            region.get(0).srcOffset(0L).dstOffset(0L).size(totalBytes);
            VK10.vkCmdCopyBuffer(cmd, upload.handle, arena.handle, region);
        }
    }

    private record Input(List<RtLightHierarchy.SectionInput> sections,
                         int rebaseX, int rebaseY, int rebaseZ) { }

    record PublishedState(RtBuffer arena, Layout layout, int lightCount,
                          float invGlobalPowerSum,
                          int originX, int originY, int originZ, int dimX, int dimY, int dimZ,
                          int rebaseX, int rebaseY, int rebaseZ, long generation) {
        private static final PublishedState EMPTY = empty(0L);

        private static PublishedState empty(long generation) {
            return new PublishedState(
                    null, Layout.EMPTY, 0, 0.0f,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, generation);
        }

        long lightAddress() { return address(layout.lightOffset); }
        long globalAliasAddress() { return address(layout.globalAliasOffset); }
        long localAliasAddress() { return layout.hasGrid ? address(layout.localAliasOffset) : 0L; }
        long cellAddress() { return layout.hasGrid ? address(layout.cellOffset) : 0L; }
        long spanAddress() { return layout.hasGrid ? address(layout.spanOffset) : 0L; }

        private long address(long offset) {
            return arena != null ? arena.deviceAddress + offset : 0L;
        }

        private void retire(RtContext ctx, long lastGraphicsUse) {
            if (arena != null) {
                ctx.gpuExecutor().enqueueDestroyAfterGraphics(lastGraphicsUse, arena::destroy);
            }
        }

        private void destroy() {
            if (arena != null) arena.destroy();
        }
    }

    record Layout(long lightOffset, long globalAliasOffset, long localAliasOffset,
                  long cellOffset, long spanOffset, long totalBytes, boolean hasGrid) {
        private static final Layout EMPTY = new Layout(0, 0, 0, 0, 0, 0, false);

        static Layout of(RtLightHierarchy.Data data, boolean includeGrid) {
            long cursor = 0L;
            long lights = cursor;
            cursor = align16(Math.addExact(cursor, data.lightBytes()));
            long globalAliases = cursor;
            cursor = align16(Math.addExact(cursor, data.globalAliases().bytes()));
            long localAliases = 0L, cells = 0L, spans = 0L;
            if (includeGrid) {
                localAliases = cursor;
                cursor = align16(Math.addExact(cursor, data.localAliases().bytes()));
                cells = cursor;
                cursor = align16(Math.addExact(cursor, data.grid().cellBytes()));
                spans = cursor;
                cursor = align16(Math.addExact(cursor, data.grid().spanBytes()));
            }
            return new Layout(lights, globalAliases, localAliases, cells, spans, cursor, includeGrid);
        }

        private static long align16(long value) {
            return Math.addExact(value, 15L) & ~15L;
        }
    }

    private sealed interface Completion permits Failed, Empty, Uploaded { }
    private record Failed(long requestId, Throwable failure) implements Completion { }
    private record Empty(long requestId) implements Completion { }
    private record Uploaded(long requestId, RtLightHierarchy.Data data, RtBuffer arena, Layout layout,
                            RtGpuExecutor.Build build, Throwable failure) implements Completion {
        void destroy() { arena.destroy(); }
    }
    private record Request(long requestId, RtContext ctx, Input input) { }
}
