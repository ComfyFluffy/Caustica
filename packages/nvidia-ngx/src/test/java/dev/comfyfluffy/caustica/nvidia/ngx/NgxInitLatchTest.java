package dev.comfyfluffy.caustica.nvidia.ngx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NgxInitLatchTest {
    @Test
    void unattemptedLatchIsNeitherReadyNorFailed() {
        NgxInitLatch latch = new NgxInitLatch();
        assertNull(latch.ready());
        assertFalse(latch.failed());
    }

    @Test
    void failedAttemptLatchesUntilTheNextActivation() {
        NgxInitLatch latch = new NgxInitLatch();
        latch.markFailed();
        assertTrue(latch.failed());
        assertNull(latch.ready());

        latch.beginActivation();
        assertFalse(latch.failed());
        assertNull(latch.ready());
    }

    @Test
    void readyInitializationSurvivesActivationBoundaries() {
        NgxRuntime.Initialized initialization = new NgxRuntime.Initialized(null, null);
        NgxInitLatch latch = new NgxInitLatch();
        latch.markReady(initialization);

        latch.beginActivation();
        assertSame(initialization, latch.ready());
        assertFalse(latch.failed());
    }

    @Test
    void laterFailureCannotUnreadyASuccessfulInit() {
        NgxInitLatch latch = new NgxInitLatch();
        latch.markReady(new NgxRuntime.Initialized(null, null));

        latch.markFailed();
        assertNotNull(latch.ready());
        assertFalse(latch.failed());
    }

    @Test
    void clearReturnsTheLatchToUnattempted() {
        NgxInitLatch latch = new NgxInitLatch();
        latch.markReady(new NgxRuntime.Initialized(null, null));
        latch.clear();
        assertNull(latch.ready());
        assertFalse(latch.failed());

        latch.markFailed();
        latch.clear();
        assertFalse(latch.failed());
    }
}
