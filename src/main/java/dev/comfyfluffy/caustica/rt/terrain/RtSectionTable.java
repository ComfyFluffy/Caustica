package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.terrain.RtSectionBuilder.PreparedSection;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Mutable GPU section-table state owned by the render thread. M0 centralizes the table buffer, slot
 * registry, and published static-instance list here while {@link RtTerrain} retains publication order.
 */
final class RtSectionTable {
    private static final int SECTION_ENTRY_BYTES = 32;
    RtBuffer buffer;
    int capacity;
    int nextSlot;
    final LongArrayList freeSlots = new LongArrayList();
    final ArrayList<SectionGeom> slots = new ArrayList<>();
    final ArrayList<RtAccel.Instance> instanceList = new ArrayList<>();
    private final ConcurrentLinkedQueue<Generation> recycledGenerations = new ConcurrentLinkedQueue<>();
    List<RtAccel.Instance> instances;
    private long dirtyStart = Long.MAX_VALUE;
    private long dirtyEnd;

    long address() {
        return buffer.deviceAddress;
    }

    int liveSlotCapacity(List<PreparedSection> prepared,
                         Long2ObjectOpenHashMap<SectionGeom> resident) {
        int needed = nextSlot;
        int free = freeSlots.size();
        for (PreparedSection ps : prepared) {
            SectionGeom prev = resident.get(ps.key());
            if (prev != null && prev.slot >= 0) {
                needed = Math.max(needed, prev.slot + 1);
            } else if (free > 0) {
                free--;
            } else {
                needed++;
            }
        }
        return needed;
    }

    /** Acquire a writable copy-on-write generation and return the previous graphics-owned generation. */
    Generation beginWriteGeneration(RtContext ctx, int minCapacity) {
        int newCapacity = CausticaConfig.Rt.Terrain.SECTION_TABLE_INITIAL_CAPACITY.value();
        newCapacity = Math.max(newCapacity, capacity);
        while (newCapacity < minCapacity) {
            newCapacity <<= 1;
        }
        Generation writable = acquireGeneration(ctx, newCapacity);
        RtBuffer newTable = writable.buffer;
        newCapacity = writable.capacity;
        Generation oldGeneration = buffer == null ? null : new Generation(buffer, capacity);
        int oldCapacity = capacity;
        buffer = newTable;
        capacity = newCapacity;
        if (oldGeneration != null && oldCapacity > 0) {
            MemoryUtil.memCopy(oldGeneration.buffer.mapped, newTable.mapped,
                    (long) oldCapacity * SECTION_ENTRY_BYTES);
            markDirty(0L, (long) oldCapacity * SECTION_ENTRY_BYTES);
        }
        return oldGeneration;
    }

    void ensureEmpty(RtContext ctx) {
        if (buffer == null) {
            int initialCapacity = CausticaConfig.Rt.Terrain.SECTION_TABLE_INITIAL_CAPACITY.value();
            Generation generation = acquireGeneration(ctx, initialCapacity);
            buffer = generation.buffer;
            capacity = generation.capacity;
        }
        if (instances == null) {
            instances = instanceList;
        }
    }

    Generation detachGeneration() {
        if (buffer == null) {
            return null;
        }
        Generation generation = new Generation(buffer, capacity);
        buffer = null;
        capacity = 0;
        return generation;
    }

    /** Called after this generation's graphics timeline value completes. */
    void recycleGeneration(Generation generation) {
        recycledGenerations.add(generation);
    }

    void destroyRecycledGenerations() {
        Generation generation;
        while ((generation = recycledGenerations.poll()) != null) {
            generation.buffer.destroy();
        }
    }

    private Generation acquireGeneration(RtContext ctx, int minCapacity) {
        Generation generation;
        while ((generation = recycledGenerations.poll()) != null) {
            if (generation.capacity >= minCapacity) {
                return generation;
            }
            ctx.gpuExecutor().enqueueDestroyUnpublished(generation.buffer::destroy);
        }
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        RtBuffer buffer = ctx.createBuffer((long) minCapacity * SECTION_ENTRY_BYTES, storage, true,
                "terrain section table " + minCapacity + " slots");
        return new Generation(buffer, minCapacity);
    }

    record Generation(RtBuffer buffer, int capacity) {
    }

    int allocateSlot() {
        int slot = !freeSlots.isEmpty()
                ? (int) freeSlots.removeLong(freeSlots.size() - 1)
                : nextSlot++;
        while (slots.size() <= slot) {
            slots.add(null);
        }
        return slot;
    }

    void removePublished(Long2ObjectOpenHashMap<SectionGeom> resident,
                         LongOpenHashSet published, SectionGeom geom) {
        SectionGeom current = resident.get(geom.key);
        if (current == geom) {
            resident.remove(geom.key);
        }
        published.remove(geom.key);
        if (geom.instanceIndex >= 0) {
            int removeIndex = geom.instanceIndex;
            int lastIndex = instanceList.size() - 1;
            if (removeIndex != lastIndex) {
                RtAccel.Instance moved = instanceList.get(lastIndex);
                instanceList.set(removeIndex, moved);
                slots.get(moved.customIndex()).instanceIndex = removeIndex;
            }
            instanceList.remove(lastIndex);
            geom.instanceIndex = -1;
        }
        if (geom.slot >= 0) {
            slots.set(geom.slot, null);
            freeSlots.add(geom.slot);
            geom.slot = -1;
        }
    }

    void write(SectionGeom geom) {
        long offset = (long) geom.slot * SECTION_ENTRY_BYTES;
        long base = buffer.mapped + offset;
        MemoryUtil.memPutLong(base, geom.material.deviceAddress);
        MemoryUtil.memPutLong(base + 8, geom.uvs.deviceAddress);
        MemoryUtil.memPutInt(base + 16, geom.triBase[0]);
        MemoryUtil.memPutInt(base + 20, geom.triBase[1]);
        MemoryUtil.memPutInt(base + 24, geom.triBase[2]);
        MemoryUtil.memPutInt(base + 28, geom.triBase[3]);
        markDirty(offset, SECTION_ENTRY_BYTES);
    }

    /** Publish every copy/patch made to the writable generation with one VMA flush. */
    void flushWrites() {
        if (dirtyStart == Long.MAX_VALUE) {
            return;
        }
        buffer.flush(dirtyStart, dirtyEnd - dirtyStart);
        dirtyStart = Long.MAX_VALUE;
        dirtyEnd = 0L;
    }

    private void markDirty(long offset, long length) {
        dirtyStart = Math.min(dirtyStart, offset);
        dirtyEnd = Math.max(dirtyEnd, offset + length);
    }

    RtAccel.Instance instanceFor(SectionGeom geom, int rbx, int rby, int rbz) {
        float[] xform = {1, 0, 0, geom.sx - rbx, 0, 1, 0, geom.sy - rby,
                0, 0, 1, geom.sz - rbz};
        return new RtAccel.Instance(xform, geom.blas.deviceAddress, geom.slot);
    }

    /** GPU residency for one section: shader attributes + compacted BLAS + world section origin. */
    static final class SectionGeom {
        final long key;
        final RtBuffer uvs;
        final RtBuffer material;
        final RtAccel blas;
        final int[] triBase;
        final int sx;
        final int sy;
        final int sz;
        int slot = -1;
        int instanceIndex = -1;

        SectionGeom(long key, RtBuffer uvs, RtBuffer material,
                    RtAccel blas, int[] triBase, int sx, int sy, int sz) {
            this.key = key;
            this.uvs = uvs;
            this.material = material;
            this.blas = blas;
            this.triBase = triBase;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
        }

        void destroy() {
            blas.destroy();
            material.destroy();
            uvs.destroy();
        }
    }
}
