package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Coalesced asynchronous lifecycle for the section-sized ReGIR proposal grid. CPU preparation and GPU
 * upload remain off the render thread; only GPU-complete immutable states are published to shaders.
 */
final class RtReGIRManager {
    private final Object buildLock = new Object();
    private final Object taskLock = new Object();
    private final ConcurrentLinkedQueue<Completion> completions = new ConcurrentLinkedQueue<>();
    private Request pendingRequest;
    private boolean workerRunning;
    private int activeTasks;
    private volatile long generation = 1L;
    private PublishedState published = PublishedState.EMPTY;

    long generation() {
        return generation;
    }

    PublishedState published() {
        return published;
    }

    boolean hasCompletions() {
        return !completions.isEmpty();
    }

    /** Replace any not-yet-started request; the single worker always drains the newest snapshot. */
    void request(Input input) {
        Request request = new Request(generation, input);
        synchronized (buildLock) {
            if (request.generation != generation) return;
            pendingRequest = request;
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

    /** Render-thread drain: submit prepared data for upload and atomically publish completed buffers. */
    void publishReady(RtContext ctx) {
        Completion completion;
        while ((completion = completions.poll()) != null) {
            if (completion instanceof Prepared prepared) {
                if (!isCurrent(prepared.generation)) continue;
                if (prepared.failure != null) {
                    throw new RuntimeException("RT ReGIR build failed for generation "
                            + prepared.generation, prepared.failure);
                }
                if (prepared.data != null) submitUpload(ctx, prepared.generation, prepared.data);
                continue;
            }

            Uploaded uploaded = (Uploaded) completion;
            boolean current = isCurrent(uploaded.generation);
            if (!current || uploaded.failure != null) {
                uploaded.destroy();
                if (current && uploaded.failure != null) {
                    throw new RuntimeException("RT ReGIR upload failed for generation "
                            + uploaded.generation, uploaded.failure);
                }
                continue;
            }
            publish(ctx, uploaded);
        }
    }

    /** Invalidate all asynchronous work and retire the currently published state. */
    void invalidate(RtContext ctx, long lastGraphicsUse) {
        cancelPending();
        PublishedState old = published;
        published = PublishedState.EMPTY;
        old.retire(ctx, lastGraphicsUse);
    }

    /** Invalidate worker/upload results without touching a state still potentially used by graphics. */
    void cancelPending() {
        generation++;
        synchronized (buildLock) {
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
                    throw new IllegalStateException("Interrupted waiting for RT ReGIR tasks", e);
                }
            }
        }
    }

    /** Device-idle teardown of the one published immutable state and all completed unpublished states. */
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
                if (!isCurrent(request.generation)) continue;
                Input input = request.input;
                try {
                    RtReGIR.Data data = RtReGIR.build(input.sections, input.lightX, input.lightY,
                            input.lightZ, input.powers, input.rebaseX, input.rebaseY, input.rebaseZ);
                    if (isCurrent(request.generation)) {
                        completions.add(new Prepared(request.generation, data, null));
                    }
                } catch (Throwable t) {
                    if (isCurrent(request.generation)) {
                        completions.add(new Prepared(request.generation, null, t));
                    }
                }
            }
        } finally {
            finishTask();
        }
    }

    private void submitUpload(RtContext ctx, long uploadGeneration, RtReGIR.Data data) {
        long cellBytes = data.cellBytes();
        long candidateBytes = data.candidateBytes();
        RtBuffer cells = null;
        RtBuffer candidates = null;
        RtBuffer upload = null;
        try {
            int usage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            cells = ctx.createAsyncBuffer(cellBytes, usage, false,
                    "terrain ReGIR cells generation " + uploadGeneration);
            candidates = ctx.createAsyncBuffer(candidateBytes, usage, false,
                    "terrain ReGIR candidates generation " + uploadGeneration);
            upload = ctx.createUploadBuffer(cellBytes + candidateBytes,
                    "terrain ReGIR upload generation " + uploadGeneration);
            long cursor = upload.mapped;
            for (int i = 0; i < data.cellOffsets().length; i++) {
                MemoryUtil.memPutInt(cursor, data.cellOffsets()[i]);
                MemoryUtil.memPutInt(cursor + 4, data.cellCounts()[i]);
                cursor += 8;
            }
            MemoryUtil.memIntBuffer(upload.mapped + cellBytes, data.candidates().length)
                    .put(data.candidates());
            upload.flush();

            RtBuffer submittedUpload = upload;
            RtBuffer submittedCells = cells;
            RtBuffer submittedCandidates = candidates;
            beginTask();
            boolean accepted = false;
            try {
                ctx.gpuExecutor().submit(
                        cmd -> recordUpload(cmd, submittedUpload, submittedCells,
                                submittedCandidates, cellBytes),
                        () -> { },
                        (ignored, failure) -> finishUpload(uploadGeneration, data, submittedUpload,
                                submittedCells, submittedCandidates, failure));
                accepted = true;
                upload = null;
                cells = null;
                candidates = null;
            } finally {
                if (!accepted) finishTask();
            }
        } finally {
            if (upload != null) upload.destroy();
            if (candidates != null) candidates.destroy();
            if (cells != null) cells.destroy();
        }
    }

    private void finishUpload(long uploadGeneration, RtReGIR.Data data, RtBuffer upload,
                              RtBuffer cells, RtBuffer candidates, Throwable failure) {
        try {
            upload.destroy();
        } finally {
            try {
                if (isCurrent(uploadGeneration)) {
                    completions.add(new Uploaded(uploadGeneration, data, cells, candidates, failure));
                } else {
                    candidates.destroy();
                    cells.destroy();
                }
            } finally {
                finishTask();
            }
        }
    }

    private void publish(RtContext ctx, Uploaded uploaded) {
        PublishedState old = published;
        published = new PublishedState(uploaded.cells, uploaded.candidates,
                uploaded.data.originX(), uploaded.data.originY(), uploaded.data.originZ(),
                uploaded.data.dimX(), uploaded.data.dimY(), uploaded.data.dimZ());
        old.retire(ctx, ctx.gpuExecutor().latestGraphicsUseValue());

        if (CausticaConfig.Rt.Lights.STATS.value()) {
            RtReGIR.Data data = uploaded.data;
            CausticaMod.LOGGER.info("RT ReGIR generation {}: {} cells / {} candidates, {} KiB",
                    uploaded.generation, data.populatedCells(), data.candidates().length,
                    (data.cellBytes() + data.candidateBytes()) / 1024);
        }
    }

    private void discardCompletions() {
        Completion completion;
        while ((completion = completions.poll()) != null) {
            if (completion instanceof Uploaded uploaded) uploaded.destroy();
        }
    }

    private boolean isCurrent(long candidateGeneration) {
        return candidateGeneration == generation;
    }

    private void beginTask() {
        synchronized (taskLock) {
            activeTasks++;
        }
    }

    private void finishTask() {
        synchronized (taskLock) {
            if (--activeTasks < 0) {
                throw new IllegalStateException("RT ReGIR active-task underflow");
            }
            if (activeTasks == 0) taskLock.notifyAll();
        }
    }

    private static void recordUpload(VkCommandBuffer cmd, RtBuffer upload, RtBuffer cells,
                                     RtBuffer candidates, long cellBytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            copy(cmd, upload, cells, 0L, cellBytes, region);
            copy(cmd, upload, candidates, cellBytes, candidates.size, region);
        }
    }

    private static void copy(VkCommandBuffer cmd, RtBuffer upload, RtBuffer destination,
                             long sourceOffset, long bytes, VkBufferCopy.Buffer region) {
        region.get(0).srcOffset(sourceOffset).dstOffset(0L).size(bytes);
        VK10.vkCmdCopyBuffer(cmd, upload.handle, destination.handle, region);
    }

    record Input(List<RtReGIR.SectionLights> sections,
                 float[] lightX, float[] lightY, float[] lightZ, double[] powers,
                 int rebaseX, int rebaseY, int rebaseZ) {
    }

    record PublishedState(RtBuffer cells, RtBuffer candidates,
                          int originX, int originY, int originZ,
                          int dimX, int dimY, int dimZ) {
        private static final PublishedState EMPTY = new PublishedState(null, null, 0, 0, 0, 0, 0, 0);

        long cellAddress() {
            return cells != null ? cells.deviceAddress : 0L;
        }

        long candidateAddress() {
            return candidates != null ? candidates.deviceAddress : 0L;
        }

        private void retire(RtContext ctx, long lastGraphicsUse) {
            if (cells == null) return;
            ctx.gpuExecutor().enqueueDestroyAfterGraphics(lastGraphicsUse, cells::destroy);
            ctx.gpuExecutor().enqueueDestroyAfterGraphics(lastGraphicsUse, candidates::destroy);
        }

        private void destroy() {
            if (cells != null) cells.destroy();
            if (candidates != null) candidates.destroy();
        }
    }

    private sealed interface Completion permits Prepared, Uploaded {
    }

    private record Prepared(long generation, RtReGIR.Data data, Throwable failure) implements Completion {
    }

    private record Uploaded(long generation, RtReGIR.Data data, RtBuffer cells,
                            RtBuffer candidates, Throwable failure) implements Completion {
        private void destroy() {
            candidates.destroy();
            cells.destroy();
        }
    }

    private record Request(long generation, Input input) {
    }
}
