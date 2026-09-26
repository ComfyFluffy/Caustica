package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class RtStableTraceRangesTest {
    @Test void holesCoalesceWithoutMovingSurvivorsAndReuseHasANewGeneration() {
        var ranges = new RtStableTraceRanges();
        var first = ranges.reserve(3, 12);
        var middle = ranges.reserve(5, 20);
        var last = ranges.reserve(2, 8);
        ranges.release(first);
        ranges.release(middle);
        var replacement = ranges.reserve(8, 32);
        assertEquals(0, replacement.geometryBase());
        assertEquals(0, replacement.emitterBase());
        assertEquals(8, last.geometryBase());
        assertEquals(32, last.emitterBase());
        assertNotSame(first, replacement);
        ranges.release(replacement);
        ranges.release(last);
        assertEquals(0, ranges.geometryHighWater());
        assertEquals(0, ranges.emitterHighWater());
        var reused = ranges.reserve(3, 12);
        assertEquals(first, reused);
        assertNotSame(first, reused);
    }

    @Test void independentRangeSizesSurviveStreamingChurnWithoutOverlap() {
        var ranges = new RtStableTraceRanges();
        var active = new ArrayList<RtStableTraceRanges.PageRange>();
        var random = new Random(71);
        for (int step = 0; step < 3000; step++) {
            if (!active.isEmpty() && (active.size() > 40 || random.nextBoolean())) {
                ranges.release(active.remove(random.nextInt(active.size())));
            } else {
                active.add(ranges.reserve(1 + random.nextInt(20), random.nextInt(30) * 4));
            }
            var geometry = new BitSet();
            var emitters = new BitSet();
            for (var range : active) {
                assertTrue(geometry.get(range.geometryBase(), range.geometryBase() + range.geometryCount()).isEmpty());
                geometry.set(range.geometryBase(), range.geometryBase() + range.geometryCount());
                assertTrue(emitters.get(range.emitterBase(), range.emitterBase() + range.emitterBytes()).isEmpty());
                emitters.set(range.emitterBase(), range.emitterBase() + range.emitterBytes());
                assertEquals(0, range.emitterBase() % 4);
            }
            assertEquals(geometry.length(), ranges.geometryHighWater());
            assertEquals(emitters.length(), ranges.emitterHighWater());
        }
        active.forEach(ranges::release);
        assertEquals(0, ranges.geometryHighWater());
        assertEquals(0, ranges.emitterHighWater());
    }

    @Test void sbtOffsetLimitAndFailedEmitterReservationPreserveExistingRanges() {
        var geometry = new RtStableTraceRanges();
        var full = geometry.reserve(1 << 24, 0);
        assertEquals(0xffffff, full.geometryBase() + full.geometryCount() - 1);
        assertThrows(IllegalStateException.class, () -> geometry.reserve(1, 0));
        geometry.release(full);
        assertEquals(0, geometry.geometryHighWater());

        var emitters = new RtStableTraceRanges();
        var almostFull = emitters.reserve(1, Integer.MAX_VALUE - 3);
        assertThrows(IllegalStateException.class, () -> emitters.reserve(7, 8));
        assertEquals(1, emitters.geometryHighWater());
        assertEquals(Integer.MAX_VALUE - 3, emitters.emitterHighWater());
        emitters.release(almostFull);
        assertEquals(0, emitters.geometryHighWater());
        assertEquals(0, emitters.emitterHighWater());
    }
}
