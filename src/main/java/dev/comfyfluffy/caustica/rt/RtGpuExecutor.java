package dev.comfyfluffy.caustica.rt;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkSubmitInfo2;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR;

/**
 * Single-owner asynchronous GPU submission lane on vanilla's otherwise-unused distinct compute queue.
 * Devices where that queue aliases graphics are rejected: cross-thread use of one VkQueue is invalid,
 * and render-thread submission would violate this executor's fully asynchronous contract.
 */
public final class RtGpuExecutor {
    private static final int MAX_BUILD_BATCH = 32;
    private static final Job STOP = new Job(null, null, null);
    private static final Job WAKE = new Job(null, null, null);
    private static final long TERRAIN_READ_STAGES =
            VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                    | VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR;

    private final RtContext ctx;
    private final VulkanQueue computeQueue;
    private final long buildTimeline;
    private final long graphicsTimeline;
    private final LinkedBlockingQueue<Job> jobs = new LinkedBlockingQueue<>();
    private final AtomicLong nextBuildValue = new AtomicLong();
    private final AtomicLong completedBuildValue = new AtomicLong();
    private final AtomicLong pendingPublishWaitValue = new AtomicLong();
    private final AtomicLong nextGraphicsValue = new AtomicLong();
    private final ArrayList<DestroyJob> destroyJobs = new ArrayList<>();
    private final Thread thread;
    private long commandPool;
    private volatile boolean closed;
    private volatile Throwable destroyFailure;

    RtGpuExecutor(RtContext ctx) {
        this.ctx = ctx;
        this.computeQueue = ctx.device().computeQueue();
        if (computeQueue.vkQueue().address() == ctx.device().graphicsQueue().vkQueue().address()) {
            throw new IllegalStateException("Caustica RT terrain requires a compute queue distinct from graphics");
        }
        this.buildTimeline = createTimeline("RT terrain build timeline");
        this.graphicsTimeline = createTimeline("RT graphics-use timeline");
        createCommandPool();
        this.thread = new Thread(this::run, "Caustica GPU executor");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public synchronized Build submit(Consumer<VkCommandBuffer> record) {
        return submit(record, () -> {});
    }

    /** Enqueue GPU recording plus native cleanup that runs on this thread after timeline completion. */
    public synchronized Build submit(Consumer<VkCommandBuffer> record, Runnable onComplete) {
        checkDestroyFailure();
        if (closed) {
            throw new IllegalStateException("RT GPU executor is closed");
        }
        long value = nextBuildValue.incrementAndGet();
        Build build = new Build(value);
        jobs.add(new Job(record, onComplete, build));
        return build;
    }

    public boolean isDone(Build build) {
        return build.completion.isDone();
    }

    /** Release submission bookkeeping after {@link #isDone}; propagates executor recording/submission failures. */
    public void free(Build build) {
        build.completion.join();
    }

    /** Mark a completed build visible to terrain publication; the next graphics terrain use waits on it. */
    public void markPublished(Build build) {
        pendingPublishWaitValue.accumulateAndGet(build.value, Math::max);
    }

    /** Attach the required compute-build wait immediately before the RT command buffer is enqueued. */
    public long beginGraphicsTerrainUse(VulkanCommandEncoder encoder) {
        checkDestroyFailure();
        long waitValue = pendingPublishWaitValue.get();
        if (waitValue != 0L) {
            encoder.waitSemaphore(buildTimeline, waitValue, TERRAIN_READ_STAGES);
        }
        return nextGraphicsValue.incrementAndGet();
    }

    /** Signal completion after the RT command buffer; later M3 retirement consumes this timeline. */
    public void endGraphicsTerrainUse(VulkanCommandEncoder encoder, long graphicsValue) {
        encoder.signalSemaphore(graphicsTimeline, graphicsValue, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR);
        jobs.offer(WAKE);
    }

    public long completedBuildValue() {
        queryTimeline(buildTimeline, completedBuildValue);
        return completedBuildValue.get();
    }

    public long completedGraphicsValue() {
        return queryTimeline(graphicsTimeline, null);
    }

    public long pendingPublishWaitValue() {
        return pendingPublishWaitValue.get();
    }

    /** Latest recorded graphics submission that can reference the currently published terrain state. */
    public long latestGraphicsUseValue() {
        return nextGraphicsValue.get();
    }

    public void enqueueDestroyAfterGraphics(long lastUseValue, Runnable destroy) {
        checkDestroyFailure();
        synchronized (destroyJobs) {
            destroyJobs.add(new DestroyJob(lastUseValue, destroy));
        }
        jobs.offer(WAKE);
    }

    /** Queue destruction of a completed build result that was never visible to graphics. */
    public void enqueueDestroyUnpublished(Runnable destroy) {
        enqueueDestroyAfterGraphics(0L, destroy);
    }

    public boolean hasPendingDestroys() {
        synchronized (destroyJobs) {
            return !destroyJobs.isEmpty();
        }
    }

    /** Wait only for terrain's latest graphics use, then execute every now-safe retirement. */
    public void waitForLatestGraphicsAndFlush() {
        checkDestroyFailure();
        long target = latestGraphicsUseValue();
        if (target != 0L) {
            waitTimeline(graphicsTimeline, target);
        }
        processDestroyJobs();
        checkDestroyFailure();
    }

    /** Caller has made the device idle; all queued destruction is now unconditionally safe. */
    public void flushDestroysAfterDeviceIdle() {
        Throwable failure = destroyFailure;
        synchronized (destroyJobs) {
            for (DestroyJob job : destroyJobs) {
                try {
                    job.destroy.run();
                } catch (Throwable t) {
                    if (failure == null) {
                        failure = t;
                    } else {
                        failure.addSuppressed(t);
                    }
                }
            }
            destroyJobs.clear();
        }
        if (failure != null) {
            throw new IllegalStateException("RT GPU executor destruction failed", failure);
        }
    }

    public synchronized void shutdown() {
        if (closed) {
            return;
        }
        closed = true;
        jobs.add(STOP);
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping RT GPU executor", e);
        }
        // Stop and join first: waiting idle before the executor stops leaves a race where it can
        // submit immediately after vkDeviceWaitIdle returns. The idle wait also makes graphics-side
        // timeline semaphore use complete before those semaphores are destroyed below.
        RtContext.check(VK10.vkDeviceWaitIdle(ctx.vk()), "vkDeviceWaitIdle(RT GPU executor shutdown)");
        Throwable failure = null;
        try {
            flushDestroysAfterDeviceIdle();
        } catch (Throwable t) {
            failure = t;
        }
        VK10.vkDestroyCommandPool(ctx.vk(), commandPool, null);
        commandPool = 0L;
        VK10.vkDestroySemaphore(ctx.vk(), graphicsTimeline, null);
        VK10.vkDestroySemaphore(ctx.vk(), buildTimeline, null);
        if (failure != null) {
            throw new IllegalStateException("RT GPU executor shutdown failed", failure);
        }
    }

    private void run() {
        while (true) {
            Job first;
            try {
                first = jobs.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (first == STOP) {
                return;
            }
            if (first == WAKE) {
                if (!processDestroyJobsSafely()) {
                    return;
                }
                continue;
            }
            ArrayList<Job> batch = new ArrayList<>(MAX_BUILD_BATCH);
            batch.add(first);
            boolean stopAfterBatch = false;
            while (batch.size() < MAX_BUILD_BATCH) {
                Job next = jobs.poll();
                if (next == null) {
                    break;
                }
                if (next == STOP) {
                    stopAfterBatch = true;
                    break;
                }
                if (next == WAKE) {
                    continue;
                }
                batch.add(next);
            }
            try {
                execute(batch);
                long completed = batch.get(batch.size() - 1).build.value;
                completedBuildValue.accumulateAndGet(completed, Math::max);
                for (Job job : batch) {
                    try {
                        job.onComplete.run();
                        job.build.completion.complete(null);
                    } catch (Throwable t) {
                        job.build.completion.completeExceptionally(t);
                    }
                }
            } catch (Throwable t) {
                for (Job job : batch) {
                    job.build.completion.completeExceptionally(t);
                }
            }
            if (!processDestroyJobsSafely()) {
                return;
            }
            if (stopAfterBatch) {
                return;
            }
        }
    }

    private boolean processDestroyJobsSafely() {
        try {
            processDestroyJobs();
            return true;
        } catch (Throwable t) {
            destroyFailure = t;
            return false;
        }
    }

    private void checkDestroyFailure() {
        Throwable failure = destroyFailure;
        if (failure != null) {
            throw new IllegalStateException("RT GPU executor destruction failed", failure);
        }
    }

    private void processDestroyJobs() {
        if (!hasPendingDestroys()) {
            return;
        }
        long completed = completedGraphicsValue();
        synchronized (destroyJobs) {
            Iterator<DestroyJob> it = destroyJobs.iterator();
            while (it.hasNext()) {
                DestroyJob job = it.next();
                if (job.lastUseValue <= completed) {
                    it.remove();
                    job.destroy.run();
                }
            }
        }
    }

    private void execute(List<Job> batch) {
        VkCommandBuffer cmd = null;
        boolean submitted = false;
        boolean completed = false;
        long signalValue = batch.get(batch.size() - 1).build.value;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPool).level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            RtContext.check(VK10.vkAllocateCommandBuffers(ctx.vk(), ai, pCmd), "vkAllocateCommandBuffers(RT GPU executor)");
            cmd = new VkCommandBuffer(pCmd.get(0), ctx.vk());
            VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            RtContext.check(VK10.vkBeginCommandBuffer(cmd, bi), "vkBeginCommandBuffer(RT GPU executor)");
            for (Job job : batch) {
                job.record.accept(cmd);
            }
            RtContext.check(VK10.vkEndCommandBuffer(cmd), "vkEndCommandBuffer(RT GPU executor)");
            VkCommandBufferSubmitInfo.Buffer command = VkCommandBufferSubmitInfo.calloc(1, stack)
                    .sType$Default().commandBuffer(cmd);
            VkSemaphoreSubmitInfo.Buffer signal = VkSemaphoreSubmitInfo.calloc(1, stack)
                    .sType$Default().semaphore(buildTimeline).value(signalValue)
                    .stageMask(VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);
            VkSubmitInfo2.Buffer submit = VkSubmitInfo2.calloc(1, stack).sType$Default()
                    .pCommandBufferInfos(command).pSignalSemaphoreInfos(signal);
            RtContext.check(org.lwjgl.vulkan.KHRSynchronization2.vkQueueSubmit2KHR(
                    computeQueue.vkQueue(), submit, 0L), "vkQueueSubmit2KHR(RT GPU executor)");
            submitted = true;
            waitTimeline(buildTimeline, signalValue);
            completed = true;
        } finally {
            // Never retry a failed host wait while unwinding: propagate its original error. A command
            // buffer is safe to release here only if submission never happened or completion was observed.
            // Otherwise the command pool owns it until shutdown waits the device idle and destroys the pool.
            if (cmd != null && (!submitted || completed)) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VK10.vkFreeCommandBuffers(ctx.vk(), commandPool, stack.pointers(cmd));
                }
            }
        }
    }

    private long createTimeline(String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                    .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0L);
            VkSemaphoreCreateInfo ci = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type);
            LongBuffer out = stack.mallocLong(1);
            RtContext.check(VK10.vkCreateSemaphore(ctx.vk(), ci, null, out), "vkCreateSemaphore(" + label + ")");
            long semaphore = out.get(0);
            RtDebugLabels.name(this.ctx, VK10.VK_OBJECT_TYPE_SEMAPHORE, semaphore, label);
            return semaphore;
        }
    }

    private void createCommandPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo ci = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                    .queueFamilyIndex(computeQueue.queueFamilyIndex());
            LongBuffer out = stack.mallocLong(1);
            RtContext.check(VK10.vkCreateCommandPool(ctx.vk(), ci, null, out), "vkCreateCommandPool(RT GPU executor)");
            commandPool = out.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_COMMAND_POOL, commandPool, "RT GPU executor command pool");
        }
    }

    private long queryTimeline(long semaphore, AtomicLong cache) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            RtContext.check(VK12.vkGetSemaphoreCounterValue(ctx.vk(), semaphore, out), "vkGetSemaphoreCounterValue");
            long value = out.get(0);
            if (cache != null) {
                cache.accumulateAndGet(value, Math::max);
                return cache.get();
            }
            return value;
        }
    }

    private void waitTimeline(long semaphore, long value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreWaitInfo wi = VkSemaphoreWaitInfo.calloc(stack).sType$Default()
                    .semaphoreCount(1)
                    .pSemaphores(stack.longs(semaphore))
                    .pValues(stack.longs(value));
            RtContext.check(VK12.vkWaitSemaphores(ctx.vk(), wi, Long.MAX_VALUE), "vkWaitSemaphores(RT GPU executor)");
        }
    }

    public static final class Build {
        private final long value;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        private Build(long value) {
            this.value = value;
        }

        public long value() {
            return value;
        }
    }

    private record Job(Consumer<VkCommandBuffer> record, Runnable onComplete, Build build) {
    }

    private record DestroyJob(long lastUseValue, Runnable destroy) {
    }
}
