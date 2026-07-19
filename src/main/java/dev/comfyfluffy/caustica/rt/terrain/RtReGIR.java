package dev.comfyfluffy.caustica.rt.terrain;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * CPU builder for the section-sized ReGIR proposal grid. Every nearby light remains eligible, but a
 * cell stores one weighted contiguous range per lit section instead of duplicating every light index.
 * The span count is naturally bounded by the spatial neighborhood, not by an arbitrary light cap.
 */
final class RtReGIR {
    private static final int NEIGHBOR_RADIUS = 2;
    private static final int MAX_DENSE_GRID_CELLS = 4_000_000;

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
        double globalPower = 0.0;
        for (int i = 0; i < sections.size(); i++) {
            if ((i & 255) == 0) checkCancelled(cancelled);
            SectionLights section = sections.get(i);
            if (!(section.power > 0.0)) continue;
            poweredCount++;
            globalPower += section.power;
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
        float[] cellInvWeightSums = new float[volume];
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
        int maxCellSpans = 0;
        int prefix = 0;
        for (int cell = 0; cell < volume; cell++) {
            cellOffsets[cell] = prefix;
            int count = cellCounts[cell];
            prefix += count;
            if (count != 0) {
                populatedCells++;
                maxCellSpans = Math.max(maxCellSpans, count);
            }
        }
        int[] spanFirstLights = new int[prefix];
        int[] spanLightCounts = new int[prefix];
        int[] spanAliasFirstLights = new int[prefix];
        int[] spanAliasLightCounts = new int[prefix];
        float[] spanAccept = new float[prefix];
        float[] spanSelfPdfs = new float[prefix];
        float[] spanAliasPdfs = new float[prefix];
        float[] spanSelfGlobalMasses = new float[prefix];
        float[] spanAliasGlobalMasses = new float[prefix];
        double[] spanWeights = new double[prefix];
        int[] writeOffsets = cellOffsets.clone();

        // Sections arrive sorted by first light. Appending sources in that order means every cell's
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
                        int distanceSq = dx * dx + dy * dy + dz * dz;
                        spanFirstLights[span] = source.firstLight;
                        spanLightCounts[span] = source.lightCount;
                        spanSelfGlobalMasses[span] = (float) (source.power / globalPower);
                        // The common 16^2 block-to-section scale cancels during normalization. Keeping
                        // this in section units lets the shader reconstruct the same weight in O(1).
                        spanWeights[span] = source.power / Math.max(1, distanceSq);
                    }
                }
            }
        }

        double[] scaled = new double[maxCellSpans];
        int[] small = new int[maxCellSpans];
        int[] large = new int[maxCellSpans];
        int[] aliases = new int[maxCellSpans];
        for (int cell = 0; cell < volume; cell++) {
            if ((cell & 4095) == 0) checkCancelled(cancelled);
            int first = cellOffsets[cell];
            int count = cellCounts[cell];
            if (count == 0) continue;
            double totalWeight = 0.0;
            for (int i = 0; i < count; i++) totalWeight += spanWeights[first + i];
            cellInvWeightSums[cell] = (float) (1.0 / totalWeight);

            int smallCount = 0;
            int largeCount = 0;
            for (int i = 0; i < count; i++) {
                double probability = spanWeights[first + i] / totalWeight;
                spanSelfPdfs[first + i] = (float) probability;
                scaled[i] = probability * count;
                if (scaled[i] < 1.0) small[smallCount++] = i;
                else large[largeCount++] = i;
            }
            while (smallCount > 0 && largeCount > 0) {
                int s = small[--smallCount];
                int l = large[--largeCount];
                spanAccept[first + s] = (float) scaled[s];
                aliases[s] = l;
                scaled[l] = scaled[l] + scaled[s] - 1.0;
                if (scaled[l] < 1.0) small[smallCount++] = l;
                else large[largeCount++] = l;
            }
            while (largeCount > 0) {
                int i = large[--largeCount];
                spanAccept[first + i] = 1.0f;
                aliases[i] = i;
            }
            while (smallCount > 0) {
                int i = small[--smallCount];
                spanAccept[first + i] = 1.0f;
                aliases[i] = i;
            }
            for (int i = 0; i < count; i++) {
                int aliasSpan = first + aliases[i];
                spanAliasFirstLights[first + i] = spanFirstLights[aliasSpan];
                spanAliasLightCounts[first + i] = spanLightCounts[aliasSpan];
                spanAliasPdfs[first + i] = spanSelfPdfs[aliasSpan];
                spanAliasGlobalMasses[first + i] = spanSelfGlobalMasses[aliasSpan];
            }
        }

        return new Data(minX * 16 - rebaseX, minY * 16 - rebaseY, minZ * 16 - rebaseZ,
                dimX, dimY, dimZ, cellOffsets, cellCounts, cellInvWeightSums,
                spanFirstLights, spanLightCounts, spanAliasFirstLights, spanAliasLightCounts,
                spanAccept, spanSelfPdfs, spanAliasPdfs,
                spanSelfGlobalMasses, spanAliasGlobalMasses, populatedCells, spanCountLong);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("Superseded ReGIR build");
    }

    record SectionLights(int firstLight, int lightCount, int x, int y, int z, double power) {
    }

    record Data(int originX, int originY, int originZ, int dimX, int dimY, int dimZ,
                int[] cellOffsets, int[] cellCounts, float[] cellInvWeightSums,
                int[] spanFirstLights, int[] spanLightCounts,
                int[] spanAliasFirstLights, int[] spanAliasLightCounts,
                float[] spanAccept, float[] spanSelfPdfs, float[] spanAliasPdfs,
                float[] spanSelfGlobalMasses, float[] spanAliasGlobalMasses,
                int populatedCells, long representedSections) {
        long cellBytes() {
            return Math.multiplyExact((long) cellOffsets.length, 12L);
        }

        long spanBytes() {
            return Math.multiplyExact((long) spanFirstLights.length, 36L);
        }
    }
}
