package dev.comfyfluffy.caustica.renderer.raytracing.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;

import java.nio.LongBuffer;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongToIntFunction;

import static dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext.check;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.*;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/**
 * Executes one deferrable command through a {@code VK_KHR_deferred_host_operations} operation that the calling
 * thread and short-lived helper threads join.
 *
 * <p>The implementation may read the command's parameters until the operation completes, and writes its outputs
 * before completion. {@link #run} therefore returns only after the operation has completed and every joined thread
 * has returned, so parameters on the caller's stack frame stay valid and outputs are visible to the caller.
 */
final class DeferredHostOperation {
    /** Delay before a thread that found no work rejoins; the operation may expose more parallel work later. */
    private static final long IDLE_REJOIN_NANOS = 1_000_000L;

    private DeferredHostOperation() {
    }

    /**
     * How the command ran.
     *
     * @param result      the command's result; {@code VK_SUCCESS} when it ran immediately without error
     * @param deferred    whether the implementation deferred the command
     * @param concurrency the driver's maximum useful concurrency after deferral, unsigned
     * @param threads     threads that joined the operation, including the caller
     */
    record Execution(int result, boolean deferred, int concurrency, int threads) {
        String summary() {
            return deferred ? "deferred to " + threads + " threads, driver concurrency "
                    + Integer.toUnsignedString(concurrency) : "not deferred";
        }
    }

    /** Runs {@code command} with a new deferred operation handle and completes the operation. */
    static Execution run(VkDevice device, LongToIntFunction command) {
        long operation;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateDeferredOperationKHR(device, null, out), "vkCreateDeferredOperationKHR");
            operation = out.get(0);
        }
        try {
            int requested = command.applyAsInt(operation);
            if (requested != VK_OPERATION_DEFERRED_KHR) {
                return new Execution(requested == VK_OPERATION_NOT_DEFERRED_KHR ? VK_SUCCESS : requested,
                        false, 0, 1);
            }
            int concurrency = vkGetDeferredOperationMaxConcurrencyKHR(device, operation);
            int threads = joinThreads(concurrency, Runtime.getRuntime().availableProcessors());
            join(device, operation, threads);
            return new Execution(vkGetDeferredOperationResultKHR(device, operation), true, concurrency, threads);
        } finally {
            vkDestroyDeferredOperationKHR(device, operation, null);
        }
    }

    /**
     * Threads to join to a deferred operation. The driver's concurrency is unsigned, and 2^32-1 means unknown; it
     * is clamped to leave one processor to the render thread. The calling thread always joins.
     */
    static int joinThreads(int concurrency, int processors) {
        return (int) Math.max(1L, Math.min(Integer.toUnsignedLong(concurrency), processors - 1L));
    }

    /**
     * Joins from the calling thread and {@code threads - 1} helpers until each is told the operation is complete
     * or has no work left for it. At least one concurrently joined thread completes the operation, so it is
     * complete once all have returned.
     */
    private static void join(VkDevice device, long operation, int threads) {
        int[] results = new int[threads];
        Thread[] helpers = new Thread[threads - 1];
        for (int i = 0; i < helpers.length; i++) {
            int slot = i + 1;
            helpers[i] = Thread.ofPlatform().daemon().name("Caustica deferred join-" + slot)
                    .start(() -> results[slot] = joinUntilDone(device, operation));
        }
        results[0] = joinUntilDone(device, operation);
        // The operation may read the caller's parameters until it completes, so this waits even when interrupted.
        boolean interrupted = false;
        for (Thread helper : helpers) {
            while (true) {
                try {
                    helper.join();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        for (int result : results) check(result, "vkDeferredOperationJoinKHR");
    }

    /** Returns {@code VK_SUCCESS} once this thread has no further work, or the join's error. */
    private static int joinUntilDone(VkDevice device, long operation) {
        while (true) {
            int result = vkDeferredOperationJoinKHR(device, operation);
            if (result == VK_THREAD_DONE_KHR) return VK_SUCCESS;
            if (result != VK_THREAD_IDLE_KHR) return result;
            LockSupport.parkNanos(IDLE_REJOIN_NANOS);
        }
    }
}
