package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A release deferred behind reserved graphics uses must outlive every frame that could still read the
 * resource, while a keep-alive of a frame that never submitted commands has no GPU work to wait for.
 */
final class GraphicsRetirementsTest {
    @Test
    void deferredReleaseWaitsForTheLatestReservedFrame() {
        var retirements = new GraphicsQueue.Retirements();
        List<String> released = new ArrayList<>();
        retirements.reserve();
        retirements.reserve();
        retirements.addAfterReserved(() -> released.add("destroy"));

        run(retirements.takeCompleted(1L));
        assertEquals(List.of(), released, "an older submitted frame completing does not retire the resource");

        run(retirements.takeCompleted(2L));
        assertEquals(List.of("destroy"), released);
        assertTrue(retirements.isEmpty());
    }

    @Test
    void laterReservationsDoNotDelayAnEarlierDeferredRelease() {
        var retirements = new GraphicsQueue.Retirements();
        List<String> released = new ArrayList<>();
        retirements.reserve();
        retirements.addAfterReserved(() -> released.add("destroy"));
        retirements.reserve();

        run(retirements.takeCompleted(1L));
        assertEquals(List.of("destroy"), released);
    }

    @Test
    void abandonedKeepAliveReleasesBeforeAnyFrameCompletes() {
        var retirements = new GraphicsQueue.Retirements();
        List<String> released = new ArrayList<>();
        retirements.reserve();
        retirements.add(0L, () -> released.add("abandoned"));

        run(retirements.takeCompleted(0L));
        assertEquals(List.of("abandoned"), released);
    }

    private static void run(List<GraphicsQueue.DestroyJob> jobs) {
        jobs.forEach(job -> job.release().run());
    }
}
