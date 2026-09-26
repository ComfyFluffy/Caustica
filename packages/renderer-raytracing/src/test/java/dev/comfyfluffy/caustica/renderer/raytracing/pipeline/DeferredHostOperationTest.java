package dev.comfyfluffy.caustica.renderer.raytracing.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DeferredHostOperationTest {
    @Test
    void joinThreadsFollowTheDriverConcurrencyAndLeaveOneProcessor() {
        assertEquals(4, DeferredHostOperation.joinThreads(4, 16));
        assertEquals(15, DeferredHostOperation.joinThreads(15, 16));
        assertEquals(15, DeferredHostOperation.joinThreads(64, 16));
    }

    @Test
    void joinThreadsReadTheConcurrencyAsUnsigned() {
        // 2^32-1 reports an unknown concurrency; 2^31 is a large finite one.
        assertEquals(15, DeferredHostOperation.joinThreads(-1, 16));
        assertEquals(15, DeferredHostOperation.joinThreads(Integer.MIN_VALUE, 16));
    }

    @Test
    void theCallingThreadAlwaysJoins() {
        assertEquals(1, DeferredHostOperation.joinThreads(8, 1));
        assertEquals(1, DeferredHostOperation.joinThreads(8, 2));
        assertEquals(1, DeferredHostOperation.joinThreads(1, 16));
    }
}
