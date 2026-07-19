package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Coalesced asynchronous lifecycle for one coherent light-hierarchy generation. CPU packing, global
 * and section-local alias construction, and ReGIR construction all run on a worker. The render thread
 * only submits a prepared upload and atomically publishes GPU-complete buffers; the previous complete
 * generation remains shader-visible until that point.
 */
final class RtReGIRManager {
    private final Object buildLock = new Object();
    private final Object taskLock = new Object();
    private final ConcurrentLinkedQueue<Completion> completions = new ConcurrentLinkedQueue<>();
    private Request pendingRequest;
    private boolean workerRunning;
    private int activeTasks;
    private volatile long latestRequest;
    private PublishedState published = PublishedState.EMPTY;

    PublishedState published() {
        return published;
    }

    boolean hasCompletions() {
        return !completions.isEmpty();
    }

    /** Replace any not-yet-started hierarchy snapshot; an in-progress older result self-discards. */
    void request(Input input) {
        synchronized (buildLock) {
            long requestId = ++latestRequest;
            pendingRequest = new Request(requestId, input);
            if (workerRunning) return;
            workerRunning = true;
            beginTask();
            try {
                RtWorkerPool.INSTANCE.submit(this::runWorker);
            } catch (Throwable t) {
                workerRunning = false;
                pendingRequest = null;
                finishTask();
                throw t;
            }
        }
    }

    /** Submit worker results and publish only fully uploaded, internally coherent generations. */
    void publishReady(RtContext ctx) {
        Completion completion;
        while ((completion = completions.poll()) != null) {
            if (completion instanceof Prepared prepared) {
                if (!isLatest(prepared.requestId)) continue;
                if (prepared.failure != null) {
                    throw new RuntimeException("RT light hierarchy build failed for request "
                            + prepared.requestId, prepared.failure);
                }
                if (prepared.data.lightCount() == 0) {
                    publishEmpty(ctx, prepared.requestId);
                } else {
                    submitUpload(ctx, prepared.requestId, prepared.data);
                }
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

    void cancelPending() {
        synchronized (buildLock) {
            latestRequest++;
            pendingRequest = null;
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

    private void runWorker() {
        try {
            while (true) {
                Request request;
                synchronized (buildLock) {
                    request = pendingRequest;
                    pendingRequest = null;
                    if (request == null) {
                        workerRunning = false;
                        return;
                    }
                }
                try {
                    RtLightHierarchy.Data data = RtLightHierarchy.build(request.input.sections,
                            request.input.rebaseX, request.input.rebaseY, request.input.rebaseZ);
                    if (isLatest(request.requestId)) {
                        completions.add(new Prepared(request.requestId, data, null));
                    }
                } catch (Throwable t) {
                    if (isLatest(request.requestId)) {
                        completions.add(new Prepared(request.requestId, null, t));
                    }
                }
            }
        } finally {
            finishTask();
        }
    }

    private void submitUpload(RtContext ctx, long requestId, RtLightHierarchy.Data data) {
        RtReGIR.Data grid = data.grid();
        long lightBytes = data.lightBytes();
        long globalAliasBytes = data.globalAliases().bytes();
        long sectionBytes = data.sectionBytes();
        long localAliasBytes = data.localAliases().bytes();
        long cellBytes = grid != null ? grid.cellBytes() : 0L;
        long spanBytes = grid != null ? grid.spanBytes() : 0L;
        long uploadBytes = lightBytes + globalAliasBytes + sectionBytes
                + localAliasBytes + cellBytes + spanBytes;

        Buffers buffers = new Buffers();
        RtBuffer upload = null;
        try {
            int usage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            buffers.lights = ctx.createAsyncBuffer(lightBytes, usage, false,
                    "terrain hierarchy lights " + requestId);
            buffers.globalAliases = ctx.createAsyncBuffer(globalAliasBytes, usage, false,
                    "terrain hierarchy global aliases " + requestId);
            buffers.sections = ctx.createAsyncBuffer(sectionBytes, usage, false,
                    "terrain hierarchy sections " + requestId);
            buffers.localAliases = ctx.createAsyncBuffer(localAliasBytes, usage, false,
                    "terrain hierarchy local aliases " + requestId);
            if (cellBytes > 0L) {
                buffers.cells = ctx.createAsyncBuffer(cellBytes, usage, false,
                        "terrain hierarchy ReGIR cells " + requestId);
                buffers.spans = ctx.createAsyncBuffer(spanBytes, usage, false,
                        "terrain hierarchy ReGIR spans " + requestId);
            }
            upload = ctx.createUploadBuffer(uploadBytes, "terrain light hierarchy upload " + requestId);

            long cursor = upload.mapped;
            MemoryUtil.memFloatBuffer(cursor, data.packedLights().length).put(data.packedLights());
            cursor += lightBytes;
            writeAliases(cursor, data.globalAliases());
            cursor += globalAliasBytes;
            for (int i = 0; i < data.sectionFirstLights().length; i++) {
                MemoryUtil.memPutInt(cursor, data.sectionFirstLights()[i]);
                MemoryUtil.memPutInt(cursor + 4, data.sectionLightCounts()[i]);
                MemoryUtil.memPutFloat(cursor + 8, data.sectionPowers()[i]);
                MemoryUtil.memPutInt(cursor + 12, 0);
                cursor += 16;
            }
            writeAliases(cursor, data.localAliases());
            cursor += localAliasBytes;
            if (grid != null) {
                for (int i = 0; i < grid.cellOffsets().length; i++) {
                    MemoryUtil.memPutInt(cursor, grid.cellOffsets()[i]);
                    MemoryUtil.memPutInt(cursor + 4, grid.cellCounts()[i]);
                    cursor += 8;
                }
                for (int i = 0; i < grid.spanSectionSlots().length; i++) {
                    MemoryUtil.memPutInt(cursor, grid.spanSectionSlots()[i]);
                    MemoryUtil.memPutFloat(cursor + 4, grid.spanCdfs()[i]);
                    cursor += 8;
                }
            }
            upload.flush();

            RtBuffer submittedUpload = upload;
            Buffers submittedBuffers = buffers;
            beginTask();
            boolean accepted = false;
            try {
                ctx.gpuExecutor().submit(
                        cmd -> recordUpload(cmd, submittedUpload, submittedBuffers,
                                lightBytes, globalAliasBytes, sectionBytes,
                                localAliasBytes, cellBytes, spanBytes),
                        () -> { },
                        (ignored, failure) -> finishUpload(requestId, data,
                                submittedUpload, submittedBuffers, failure));
                accepted = true;
                upload = null;
                buffers = null;
            } finally {
                if (!accepted) finishTask();
            }
        } finally {
            if (upload != null) upload.destroy();
            if (buffers != null) buffers.destroy();
        }
    }

    private static void writeAliases(long cursor, RtLightHierarchy.AliasData aliases) {
        for (int i = 0; i < aliases.aliasIndices().length; i++) {
            MemoryUtil.memPutInt(cursor, aliases.aliasIndices()[i]);
            MemoryUtil.memPutFloat(cursor + 4, aliases.accept()[i]);
            MemoryUtil.memPutFloat(cursor + 8, aliases.selfInvPdf()[i]);
            MemoryUtil.memPutFloat(cursor + 12, aliases.aliasInvPdf()[i]);
            cursor += 16;
        }
    }

    private void finishUpload(long requestId, RtLightHierarchy.Data data, RtBuffer upload,
                              Buffers buffers, Throwable failure) {
        try {
            upload.destroy();
        } finally {
            try {
                if (isLatest(requestId)) completions.add(new Uploaded(requestId, data, buffers, failure));
                else buffers.destroy();
            } finally {
                finishTask();
            }
        }
    }

    private void publish(RtContext ctx, Uploaded uploaded) {
        RtReGIR.Data grid = uploaded.data.grid();
        Buffers b = uploaded.buffers;
        PublishedState next = new PublishedState(b.lights, b.globalAliases, b.sections,
                b.localAliases, b.cells, b.spans, uploaded.data.lightCount(),
                grid != null ? grid.originX() : 0, grid != null ? grid.originY() : 0,
                grid != null ? grid.originZ() : 0, grid != null ? grid.dimX() : 0,
                grid != null ? grid.dimY() : 0, grid != null ? grid.dimZ() : 0,
                uploaded.data.rebaseX(), uploaded.data.rebaseY(), uploaded.data.rebaseZ());
        PublishedState old = published;
        published = next;
        old.retire(ctx, ctx.gpuExecutor().latestGraphicsUseValue());

        if (CausticaConfig.Rt.Lights.DUMP.value()) dumpNearbyLights(uploaded.data);

        if (CausticaConfig.Rt.Lights.STATS.value()) {
            CausticaMod.LOGGER.info("RT light hierarchy {}: {} lights / {} section slots / {} ReGIR spans",
                    uploaded.requestId, uploaded.data.lightCount(), uploaded.data.sectionFirstLights().length,
                    grid != null ? grid.spanSectionSlots().length : 0);
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
            float leR = lights[record + 7];
            float leG = lights[record + 11];
            float leB = lights[record + 15];
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
        published = PublishedState.EMPTY;
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

    private static void recordUpload(VkCommandBuffer cmd, RtBuffer upload, Buffers b,
                                     long lightBytes, long globalAliasBytes, long sectionBytes,
                                     long localAliasBytes, long cellBytes, long spanBytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            long offset = 0L;
            copy(cmd, upload, b.lights, offset, lightBytes, region);
            offset += lightBytes;
            copy(cmd, upload, b.globalAliases, offset, globalAliasBytes, region);
            offset += globalAliasBytes;
            copy(cmd, upload, b.sections, offset, sectionBytes, region);
            offset += sectionBytes;
            copy(cmd, upload, b.localAliases, offset, localAliasBytes, region);
            offset += localAliasBytes;
            if (cellBytes > 0L) {
                copy(cmd, upload, b.cells, offset, cellBytes, region);
                offset += cellBytes;
                copy(cmd, upload, b.spans, offset, spanBytes, region);
            }
        }
    }

    private static void copy(VkCommandBuffer cmd, RtBuffer upload, RtBuffer destination,
                             long sourceOffset, long bytes, VkBufferCopy.Buffer region) {
        region.get(0).srcOffset(sourceOffset).dstOffset(0L).size(bytes);
        VK10.vkCmdCopyBuffer(cmd, upload.handle, destination.handle, region);
    }

    record Input(List<RtLightHierarchy.SectionInput> sections,
                 int rebaseX, int rebaseY, int rebaseZ) {
        Input {
            sections = List.copyOf(sections);
        }
    }

    record PublishedState(RtBuffer lights, RtBuffer globalAliases, RtBuffer sections,
                          RtBuffer localAliases, RtBuffer cells, RtBuffer spans, int lightCount,
                          int originX, int originY, int originZ, int dimX, int dimY, int dimZ,
                          int rebaseX, int rebaseY, int rebaseZ) {
        private static final PublishedState EMPTY = new PublishedState(
                null, null, null, null, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        long lightAddress() { return lights != null ? lights.deviceAddress : 0L; }
        long globalAliasAddress() { return globalAliases != null ? globalAliases.deviceAddress : 0L; }
        long sectionAddress() { return sections != null ? sections.deviceAddress : 0L; }
        long localAliasAddress() { return localAliases != null ? localAliases.deviceAddress : 0L; }
        long cellAddress() { return cells != null ? cells.deviceAddress : 0L; }
        long spanAddress() { return spans != null ? spans.deviceAddress : 0L; }

        private void retire(RtContext ctx, long lastGraphicsUse) {
            if (lights == null) return;
            for (RtBuffer buffer : new RtBuffer[]{lights, globalAliases, sections,
                    localAliases, cells, spans}) {
                if (buffer != null) ctx.gpuExecutor().enqueueDestroyAfterGraphics(lastGraphicsUse, buffer::destroy);
            }
        }

        private void destroy() {
            for (RtBuffer buffer : new RtBuffer[]{lights, globalAliases, sections,
                    localAliases, cells, spans}) {
                if (buffer != null) buffer.destroy();
            }
        }
    }

    private static final class Buffers {
        RtBuffer lights;
        RtBuffer globalAliases;
        RtBuffer sections;
        RtBuffer localAliases;
        RtBuffer cells;
        RtBuffer spans;

        void destroy() {
            for (RtBuffer buffer : new RtBuffer[]{lights, globalAliases, sections,
                    localAliases, cells, spans}) {
                if (buffer != null) buffer.destroy();
            }
        }
    }

    private sealed interface Completion permits Prepared, Uploaded { }
    private record Prepared(long requestId, RtLightHierarchy.Data data, Throwable failure)
            implements Completion { }
    private record Uploaded(long requestId, RtLightHierarchy.Data data, Buffers buffers,
                            Throwable failure) implements Completion {
        void destroy() { buffers.destroy(); }
    }
    private record Request(long requestId, Input input) { }
}
