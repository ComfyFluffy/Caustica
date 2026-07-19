package dev.comfyfluffy.caustica.rt.terrain;

import java.util.concurrent.CancellationException;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * CPU builder for the section-sized ReGIR proposal grid. Every nearby light remains eligible, but a
 * cell stores one weighted contiguous range per lit section instead of duplicating every light index.
 * The span count is naturally bounded by the spatial neighborhood, not by an arbitrary light cap.
 */
final class RtReGIR {
    private static final int NEIGHBOR_RADIUS = 2;
    private static final int MAX_DENSE_GRID_CELLS = 4_000_000;
    private static final double SECTION_DISTANCE_FLOOR_SQ = 16.0 * 16.0;

    private RtReGIR() {
    }

    static Data build(List<SectionLights> sections, int rebaseX, int rebaseY, int rebaseZ) {
        return build(sections, rebaseX, rebaseY, rebaseZ, () -> false);
    }

    static Data build(List<SectionLights> sections, int rebaseX, int rebaseY, int rebaseZ,
                      BooleanSupplier cancelled) {
        if (sections.isEmpty()) return null;

        // Only powered sections enter this builder. Their +/-2-section extents define the cells that
        // can use a local proposal; unlit terrain residency is irrelevant and no longer needs to be
        // copied or hashed on every light update.
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int poweredCount = 0;
        for (int i = 0; i < sections.size(); i++) {
            if ((i & 255) == 0) checkCancelled(cancelled);
            SectionLights section = sections.get(i);
            if (!(section.power > 0.0)) continue;
            poweredCount++;
            minX = Math.min(minX, section.x - NEIGHBOR_RADIUS);
            minY = Math.min(minY, section.y - NEIGHBOR_RADIUS);
            minZ = Math.min(minZ, section.z - NEIGHBOR_RADIUS);
            maxX = Math.max(maxX, section.x + NEIGHBOR_RADIUS);
            maxY = Math.max(maxY, section.y + NEIGHBOR_RADIUS);
            maxZ = Math.max(maxZ, section.z + NEIGHBOR_RADIUS);
        }
        if (poweredCount == 0) return null;
        int dimX = Math.addExact(Math.subtractExact(maxX, minX), 1);
        int dimY = Math.addExact(Math.subtractExact(maxY, minY), 1);
        int dimZ = Math.addExact(Math.subtractExact(maxZ, minZ), 1);
        long volumeLong = (long) dimX * dimY * dimZ;
        if (volumeLong > MAX_DENSE_GRID_CELLS) {
            // A few widely separated emitter clusters can make their dense bounding box mostly empty.
            // Fall back to the global proposal rather than allocating an unexpectedly huge sparse map.
            return null;
        }
        int volume = (int) volumeLong;
        int[] cellOffsets = new int[volume];
        int[] cellCounts = new int[volume];
        long spanCountLong = 0L;
        for (int sourceIndex = 0; sourceIndex < sections.size(); sourceIndex++) {
            if ((sourceIndex & 63) == 0) checkCancelled(cancelled);
            SectionLights source = sections.get(sourceIndex);
            if (!(source.power > 0.0)) continue;
            for (int dz = -NEIGHBOR_RADIUS; dz <= NEIGHBOR_RADIUS; dz++) {
                for (int dy = -NEIGHBOR_RADIUS; dy <= NEIGHBOR_RADIUS; dy++) {
                    for (int dx = -NEIGHBOR_RADIUS; dx <= NEIGHBOR_RADIUS; dx++) {
                        int linear = ((source.z + dz - minZ) * dimY
                                + (source.y + dy - minY)) * dimX + (source.x + dx - minX);
                        cellCounts[linear]++;
                        spanCountLong++;
                    }
                }
            }
        }
        if (spanCountLong > Integer.MAX_VALUE) {
            throw new IllegalStateException("ReGIR span count exceeds Java array capacity: " + spanCountLong);
        }

        int populatedCells = 0;
        int prefix = 0;
        for (int cell = 0; cell < volume; cell++) {
            cellOffsets[cell] = prefix;
            int count = cellCounts[cell];
            prefix += count;
            if (count != 0) populatedCells++;
        }
        int[] spanSectionSlots = new int[prefix];
        float[] spanCdfs = new float[prefix];
        double[] spanWeights = new double[prefix];
        int[] writeOffsets = cellOffsets.clone();

        // Sections arrive sorted by section slot. Appending sources in that order means every cell's
        // span range is already sorted, avoiding 125-element per-cell lists and sorts.
        for (int sourceIndex = 0; sourceIndex < sections.size(); sourceIndex++) {
            if ((sourceIndex & 63) == 0) checkCancelled(cancelled);
            SectionLights source = sections.get(sourceIndex);
            if (!(source.power > 0.0)) continue;
            for (int dz = -NEIGHBOR_RADIUS; dz <= NEIGHBOR_RADIUS; dz++) {
                for (int dy = -NEIGHBOR_RADIUS; dy <= NEIGHBOR_RADIUS; dy++) {
                    for (int dx = -NEIGHBOR_RADIUS; dx <= NEIGHBOR_RADIUS; dx++) {
                        int linear = ((source.z + dz - minZ) * dimY
                                + (source.y + dy - minY)) * dimX + (source.x + dx - minX);
                        int span = writeOffsets[linear]++;
                        double distanceSq = 16.0 * 16.0 * (dx * dx + dy * dy + dz * dz);
                        spanSectionSlots[span] = source.sectionSlot;
                        spanWeights[span] = source.power / Math.max(SECTION_DISTANCE_FLOOR_SQ, distanceSq);
                    }
                }
            }
        }

        for (int cell = 0; cell < volume; cell++) {
            if ((cell & 4095) == 0) checkCancelled(cancelled);
            int first = cellOffsets[cell];
            int count = cellCounts[cell];
            if (count == 0) continue;
            double totalWeight = 0.0;
            for (int i = 0; i < count; i++) totalWeight += spanWeights[first + i];
            double cumulative = 0.0;
            for (int i = 0; i < count; i++) {
                cumulative += spanWeights[first + i] / totalWeight;
                spanCdfs[first + i] = i + 1 == count ? 1.0f : (float) cumulative;
            }
        }

        return new Data(minX * 16 - rebaseX, minY * 16 - rebaseY, minZ * 16 - rebaseZ,
                dimX, dimY, dimZ, cellOffsets, cellCounts,
                spanSectionSlots, spanCdfs, populatedCells, spanCountLong);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("Superseded ReGIR build");
    }

    record SectionLights(int sectionSlot, int x, int y, int z, double power) {
    }

    record Data(int originX, int originY, int originZ, int dimX, int dimY, int dimZ,
                int[] cellOffsets, int[] cellCounts,
                int[] spanSectionSlots, float[] spanCdfs,
                int populatedCells, long representedSections) {
        long cellBytes() {
            return Math.multiplyExact((long) cellOffsets.length, 8L);
        }

        long spanBytes() {
            return Math.multiplyExact((long) spanSectionSlots.length, 8L);
        }
    }
}
