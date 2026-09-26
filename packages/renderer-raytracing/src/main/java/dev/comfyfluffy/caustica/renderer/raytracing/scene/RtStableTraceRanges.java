package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import java.util.TreeMap;

/** Scene-local offsets; each captured frame keeps its own buffers and immutable range generations. */
final class RtStableTraceRanges {
    // Geometry bases are Vulkan's 24-bit instance custom index and, at one hit record each, SBT offset.
    private final Ranges geometry = new Ranges(1 << 24);
    private final Ranges emitters = new Ranges(Integer.MAX_VALUE);

    /** Object identity distinguishes a new occupant of a reused numeric range. */
    record PageRange(int geometryBase, int geometryCount, int emitterBase, int emitterBytes) { }

    PageRange reserve(int geometryCount, int emitterBytes) {
        int geometryBase = geometry.reserve(geometryCount);
        try {
            return new PageRange(geometryBase, geometryCount, emitters.reserve(emitterBytes), emitterBytes);
        } catch (RuntimeException failure) {
            geometry.release(geometryBase, geometryCount);
            throw failure;
        }
    }

    void release(PageRange range) {
        geometry.release(range.geometryBase, range.geometryCount);
        emitters.release(range.emitterBase, range.emitterBytes);
    }

    /** A changed instance keeps compatible offsets but invalidates bytes from its prior revision. */
    PageRange replace(PageRange previous, int geometryCount, int emitterBytes) {
        if (previous.geometryCount == geometryCount && previous.emitterBytes == emitterBytes) {
            return new PageRange(previous.geometryBase, geometryCount, previous.emitterBase, emitterBytes);
        }
        release(previous);
        return reserve(geometryCount, emitterBytes);
    }

    int geometryHighWater() { return geometry.highWater; }
    int emitterHighWater() { return emitters.highWater; }

    /** First-fit allocation with adjacent-hole coalescing and immediate trailing-space reclamation. */
    private static final class Ranges {
        private final int limit;
        private final TreeMap<Integer, Integer> free = new TreeMap<>();
        private int highWater;

        Ranges(int limit) { this.limit = limit; }

        int reserve(int count) {
            if (count == 0) return 0;
            for (var hole : free.entrySet()) {
                if (hole.getValue() < count) continue;
                int base = hole.getKey(), size = hole.getValue();
                free.remove(base);
                if (size > count) free.put(base + count, size - count);
                return base;
            }
            if (count > limit - highWater) throw new IllegalStateException("trace range capacity exceeded: " + limit);
            int base = highWater;
            highWater += count;
            return base;
        }

        void release(int base, int count) {
            if (count == 0) return;
            var before = free.lowerEntry(base);
            if (before != null && before.getKey() + before.getValue() == base) {
                base = before.getKey();
                count += before.getValue();
                free.remove(base);
            }
            var after = free.ceilingEntry(base);
            if (after != null && base + count == after.getKey()) {
                count += after.getValue();
                free.remove(after.getKey());
            }
            if (base + count == highWater) highWater = base;
            else free.put(base, count);
        }
    }
}
