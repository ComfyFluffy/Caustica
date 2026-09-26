package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import dev.comfyfluffy.caustica.engine.vulkan.GpuCrashHistory;
import static dev.comfyfluffy.caustica.engine.vulkan.GpuCrashHistory.Event.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;

/** Graphics command ownership, reusable allocation waits, and off-thread retirement. */
public final class GraphicsQueue {
    private final VulkanDeviceContext ctx;
    private final long graphicsTimeline;
    private final CommandPoolCache<VkCommandBuffer> commandPools;
    private final ScheduledExecutorService retirement = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("Caustica graphics retirement").factory());
    private final Retirements retirements = new Retirements();
    private volatile Throwable executorFailure;

    GraphicsQueue(VulkanDeviceContext ctx) {
        this.ctx = ctx;
        commandPools = new CommandPoolCache<>(new VulkanCommandPoolBackend(ctx,
                ctx.backend().graphicsQueue().familyIndex(), "graphics"), "graphics", 1);
        graphicsTimeline = createTimeline("Graphics completion");
        retirement.scheduleWithFixedDelay(this::pollRetirement, 1, 2, TimeUnit.MILLISECONDS);
    }

    void shutdownAfterDeviceIdle() {
        drainAfterDeviceIdle();
        retirement.shutdown();
        awaitTermination(retirement);
        commandPools.destroyAfterDeviceIdle();
        GpuCrashHistory.record(SEMAPHORE_DESTROY, graphicsTimeline, retirements.reservedValue(), 0, 1);
        VK10.vkDestroySemaphore(ctx.vk(), graphicsTimeline, null);
        checkExecutorFailure();
    }

    public GraphicsUse beginGraphicsUse() {
        assertRenderThread();
        checkExecutorFailure();
        long value = retirements.reserve();
        GpuCrashHistory.record(GRAPHICS_RESERVED, graphicsTimeline, value, 0, 0);
        return new GraphicsUse(this, value);
    }

    CommandPoolCache<VkCommandBuffer>.Lease acquireCommands() {
        assertRenderThread();
        checkExecutorFailure();
        return commandPools.acquire(1);
    }

    public void resolveGraphicsUse(GraphicsSubmission submission, GraphicsUse use) {
        assertRenderThread();
        if (use.owner() != this) throw new IllegalArgumentException("Graphics use belongs to another device");
        use.resolveSubmission(() -> enqueueGraphicsSignal(submission, graphicsTimeline, use.value()));
    }

    static void enqueueGraphicsSignal(GraphicsSubmission submission, long semaphore, long value) {
        submission.signalSemaphore(semaphore, value, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
        GpuCrashHistory.record(GRAPHICS_SIGNAL, semaphore, value, 0, 0);
    }

    public GraphicsUseWaiter graphicsUseWaiter() {
        assertRenderThread();
        checkExecutorFailure();
        return new GraphicsUseWaiter(queryTimeline(graphicsTimeline));
    }

    public void retireAfterGraphics(TrackedGraphicsUse trackedUse, Runnable destroy) {
        assertRenderThread();
        trackedUse.whenMarksApplied(() -> keepAliveAfterGraphicsValue(trackedUse.value, destroy));
    }

    void keepAliveAfterGraphicsValue(long value, Runnable release) {
        retirements.add(value, release);
    }

    /**
     * Releases after every graphics use reserved before this call completes. Callable from any thread;
     * an abandoned reservation is covered by the next signalled value or the device-idle drain.
     */
    void releaseAfterReservedGraphics(Runnable release) {
        retirements.addAfterReserved(release);
    }

    /** Releases on the next retirement poll; only for keep-alives of a frame that submitted no commands. */
    void releaseAbandoned(Runnable release) {
        keepAliveAfterGraphicsValue(0L, release);
    }

    private void pollRetirement() {
        // Stop normal retirement after failure; cleanup drains separately after device idle.
        if (executorFailure != null) return;
        try { processRetirement(queryTimeline(graphicsTimeline)); }
        catch (Throwable failure) { latchFailure(failure); }
    }

    private void processRetirement(long completed) {
        for (DestroyJob job : retirements.takeCompleted(completed)) {
            GpuCrashHistory.record(GRAPHICS_RETIRE, graphicsTimeline, job.value, completed, 0);
            try { job.release.run(); }
            catch (Throwable failure) { latchFailure(failure); }
        }
    }

    void drainAfterDeviceIdle() {
        GpuCrashHistory.record(GRAPHICS_DRAIN, graphicsTimeline, retirements.reservedValue(), 0, 0);
        await(retirement.submit(() -> {
            while (true) {
                processRetirement(Long.MAX_VALUE);
                if (retirements.isEmpty()) break;
            }
        }));
    }

    private static void await(Future<?> future) {
        try { future.get(); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while draining GPU work", e);
        } catch (ExecutionException e) { throw new IllegalStateException("GPU drain failed", e.getCause()); }
    }

    private static void awaitTermination(ExecutorService executor) {
        try { while (!executor.awaitTermination(1, TimeUnit.DAYS)) {} }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping GPU work", e);
        }
    }

    private synchronized void latchFailure(Throwable failure) {
        if (executorFailure == null) executorFailure = failure;
        else if (executorFailure != failure) executorFailure.addSuppressed(failure);
    }

    private void checkExecutorFailure() {
        if (executorFailure != null) throw new IllegalStateException("GPU executor failed", executorFailure);
    }

    private void assertRenderThread() { ctx.backend().assertRenderThread(); }

    private long createTimeline(String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                    .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0L);
            var info = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type);
            var out = stack.mallocLong(1);
            ctx.checkDeviceResult(VK10.vkCreateSemaphore(ctx.vk(), info, null, out), "vkCreateSemaphore(" + label + ")");
            GpuCrashHistory.record(SEMAPHORE_CREATE, out.get(0), 0, 0, 1);
            return out.get(0);
        }
    }

    private long queryTimeline(long semaphore) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var out = stack.mallocLong(1);
            int result = VK12.vkGetSemaphoreCounterValue(ctx.vk(), semaphore, out);
            GpuCrashHistory.record(GRAPHICS_OBSERVED, semaphore, 0,
                    result == VK10.VK_SUCCESS ? out.get(0) : -1, result);
            ctx.checkDeviceResult(result, "vkGetSemaphoreCounterValue");
            return out.get(0);
        }
    }

    private void waitTimeline(long semaphore, long value) {
        GpuWaitEvent event = new GpuWaitEvent();
        boolean measured = event.isEnabled();
        if (measured) event.startedNanos = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var wait = VkSemaphoreWaitInfo.calloc(stack).sType$Default().semaphoreCount(1)
                    .pSemaphores(stack.longs(semaphore)).pValues(stack.longs(value));
            int result = VK12.vkWaitSemaphores(ctx.vk(), wait, Long.MAX_VALUE);
            GpuCrashHistory.record(GRAPHICS_WAIT, semaphore, value,
                    result == VK10.VK_SUCCESS ? value : -1, result);
            ctx.checkDeviceResult(result, "vkWaitSemaphores");
        } finally {
            if (measured) {
                event.elapsedNanos = System.nanoTime() - event.startedNanos;
                event.commit();
            }
        }
    }

    @jdk.jfr.Name("dev.comfyfluffy.caustica.GpuWait")
    @jdk.jfr.Label("Graphics timeline wait") @jdk.jfr.Category({"Caustica", "Frame"})
    @jdk.jfr.StackTrace(false) @jdk.jfr.Enabled(false)
    static final class GpuWaitEvent extends jdk.jfr.Event {
        public long startedNanos;
        @jdk.jfr.Timespan(jdk.jfr.Timespan.NANOSECONDS)
        public long elapsedNanos;
    }

    /** Mutable last-use owner embedded in reusable or asynchronously retired GPU resource slots. */
    public static final class TrackedGraphicsUse {
        private GraphicsQueue owner;
        private long value;
        private GraphicsUse pending;

        /** Adopt a frame's timeline value only if its commands are accepted for execution. */
        public void mark(GraphicsUse graphicsUse) {
            graphicsUse.owner().assertRenderThread();
            adopt(graphicsUse);
        }

        /** Defers adoption until command acceptance and clears the pending mark after resolution. */
        void adopt(GraphicsUse graphicsUse) {
            if (owner != null && owner != graphicsUse.owner()) {
                throw new IllegalArgumentException("Tracked graphics use belongs to a different Vulkan device");
            }
            owner = graphicsUse.owner();
            pending = graphicsUse;
            graphicsUse.whenSubmitted(() -> value = Math.max(value, graphicsUse.value()));
            graphicsUse.whenResolved(() -> pending = null);
        }

        long value() {
            return value;
        }

        /** Retirement observes accepted marks; abandonment preserves the prior completion value. */
        void whenMarksApplied(Runnable action) {
            if (pending == null) action.run();
            else pending.whenResolved(action);
        }

        /** Forget timeline ownership after the owning resources have been destroyed or synchronized. */
        public void clear() {
            if (owner != null) {
                owner.assertRenderThread();
            }
            value = 0L;
            owner = null;
            pending = null;
        }
    }

    /**
     * Reuses one timeline query while awaiting several tracked owners. Timeline values are monotonic, so
     * completing a newer value also proves every older value complete.
     */
    public final class GraphicsUseWaiter {
        private long completedValue;

        private GraphicsUseWaiter(long completedValue) {
            this.completedValue = completedValue;
        }

        /** Return true only when this call had to issue a host wait. */
        public boolean await(TrackedGraphicsUse trackedUse) {
            assertRenderThread();
            long requiredValue = trackedUse.value;
            if (requiredValue <= completedValue) {
                return false;
            }
            checkExecutorFailure();
            waitTimeline(graphicsTimeline, requiredValue);
            completedValue = requiredValue;
            return true;
        }

    }

    /** Release jobs keyed by the graphics timeline value that must complete before each runs. */
    static final class Retirements {
        private final ArrayList<DestroyJob> jobs = new ArrayList<>();
        // Only the render thread reserves; releases deferred from any thread read the latest reservation.
        private volatile long reservedValue;

        long reserve() {
            return ++reservedValue;
        }

        long reservedValue() {
            return reservedValue;
        }

        synchronized void add(long value, Runnable release) {
            jobs.add(new DestroyJob(value, release));
        }

        void addAfterReserved(Runnable release) {
            add(reservedValue, release);
        }

        synchronized List<DestroyJob> takeCompleted(long completed) {
            List<DestroyJob> ready = new ArrayList<>();
            var iterator = jobs.iterator();
            while (iterator.hasNext()) {
                DestroyJob job = iterator.next();
                if (job.value <= completed) {
                    iterator.remove();
                    ready.add(job);
                }
            }
            return ready;
        }

        synchronized boolean isEmpty() {
            return jobs.isEmpty();
        }
    }

    record DestroyJob(long value, Runnable release) {}
}
