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
    List<RtAccel.Instance> instances;

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

    /** Allocate a copy-on-write table and return the previous generation for deferred retirement. */
    RtBuffer ensureCapacity(RtContext ctx, int minCapacity) {
        int newCapacity = CausticaConfig.Rt.Terrain.SECTION_TABLE_INITIAL_CAPACITY.value();
        newCapacity = Math.max(newCapacity, capacity);
        while (newCapacity < minCapacity) {
            newCapacity <<= 1;
        }
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        RtBuffer newTable = ctx.createBuffer((long) newCapacity * SECTION_ENTRY_BYTES, storage, true,
                "terrain section table " + newCapacity + " slots");
        RtBuffer oldTable = buffer;
        int oldCapacity = capacity;
        buffer = newTable;
        capacity = newCapacity;
        if (oldTable != null && oldCapacity > 0) {
            MemoryUtil.memCopy(oldTable.mapped, newTable.mapped,
                    (long) oldCapacity * SECTION_ENTRY_BYTES);
        }
        return oldTable;
    }

    void ensureEmpty(RtContext ctx) {
        if (buffer == null) {
            int initialCapacity = CausticaConfig.Rt.Terrain.SECTION_TABLE_INITIAL_CAPACITY.value();
            buffer = ctx.createBuffer((long) initialCapacity * SECTION_ENTRY_BYTES,
                    org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true,
                    "terrain section table " + initialCapacity + " slots (empty)");
            capacity = initialCapacity;
        }
        if (instances == null) {
            instances = instanceList;
        }
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
        long base = buffer.mapped + (long) geom.slot * SECTION_ENTRY_BYTES;
        MemoryUtil.memPutLong(base, geom.material.deviceAddress);
        MemoryUtil.memPutLong(base + 8, geom.uvs.deviceAddress);
        MemoryUtil.memPutInt(base + 16, geom.triBase[0]);
        MemoryUtil.memPutInt(base + 20, geom.triBase[1]);
        MemoryUtil.memPutInt(base + 24, geom.triBase[2]);
        MemoryUtil.memPutInt(base + 28, geom.triBase[3]);
    }

    RtAccel.Instance instanceFor(SectionGeom geom, int rbx, int rby, int rbz) {
        float[] xform = {1, 0, 0, geom.sx - rbx, 0, 1, 0, geom.sy - rby,
                0, 0, 1, geom.sz - rbz};
        return new RtAccel.Instance(xform, geom.blas.deviceAddress, geom.slot);
    }

    /** GPU residency for one section: geometry buffers + BLAS + world section origin. */
    static final class SectionGeom {
        final long key;
        final RtBuffer positions;
        final RtBuffer indices;
        final RtBuffer uvs;
        final RtBuffer material;
        final RtAccel blas;
        final int[] triBase;
        final int sx;
        final int sy;
        final int sz;
        int slot = -1;
        int instanceIndex = -1;

        SectionGeom(long key, RtBuffer positions, RtBuffer indices, RtBuffer uvs, RtBuffer material,
                    RtAccel blas, int[] triBase, int sx, int sy, int sz) {
            this.key = key;
            this.positions = positions;
            this.indices = indices;
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
            indices.destroy();
            positions.destroy();
        }
    }
}
