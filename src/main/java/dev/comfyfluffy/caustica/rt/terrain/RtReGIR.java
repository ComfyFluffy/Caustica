package dev.comfyfluffy.caustica.rt.terrain;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
        if (sections.isEmpty()) {
            return null;
        }

        Long2ObjectOpenHashMap<SourceSection> bySection = new Long2ObjectOpenHashMap<>(sections.size());
        for (SectionLights section : sections) {
            bySection.put(RtTerrain.sectionKey(section.x, section.y, section.z),
                    new SourceSection(section));
        }
        // A cell is useful only near an emitter. Materializing the entire render-distance volume on
        // every streaming publication would make the CPU build grow with all geometry, even though a
        // far, unlit cell is already well served by the global proposal.
        LongOpenHashSet activeKeys = new LongOpenHashSet();
        for (SectionLights source : sections) {
            if (!(source.power > 0.0)) continue;
            for (int dz = -NEIGHBOR_RADIUS; dz <= NEIGHBOR_RADIUS; dz++) {
                for (int dy = -NEIGHBOR_RADIUS; dy <= NEIGHBOR_RADIUS; dy++) {
                    for (int dx = -NEIGHBOR_RADIUS; dx <= NEIGHBOR_RADIUS; dx++) {
                        long key = RtTerrain.sectionKey(source.x + dx, source.y + dy, source.z + dz);
                        if (bySection.containsKey(key)) activeKeys.add(key);
                    }
                }
            }
        }
        if (activeKeys.isEmpty()) return null;
        SectionLights[] active = new SectionLights[activeKeys.size()];
        int activeCount = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (SectionLights section : sections) {
            if (!activeKeys.contains(RtTerrain.sectionKey(section.x, section.y, section.z))) continue;
            active[activeCount++] = section;
            minX = Math.min(minX, section.x);
            minY = Math.min(minY, section.y);
            minZ = Math.min(minZ, section.z);
            maxX = Math.max(maxX, section.x);
            maxY = Math.max(maxY, section.y);
            maxZ = Math.max(maxZ, section.z);
        }
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
        IntArrayList spanSectionSlots = new IntArrayList();
        FloatArrayList spanCdfs = new FloatArrayList();
        ArrayList<WeightedSpan> selected = new ArrayList<>(125);
        int populatedCells = 0;
        long representedSections = 0L;

        for (int cellIndex = 0; cellIndex < activeCount; cellIndex++) {
            SectionLights cell = active[cellIndex];
            selected.clear();
            for (int dz = -NEIGHBOR_RADIUS; dz <= NEIGHBOR_RADIUS; dz++) {
                for (int dy = -NEIGHBOR_RADIUS; dy <= NEIGHBOR_RADIUS; dy++) {
                    for (int dx = -NEIGHBOR_RADIUS; dx <= NEIGHBOR_RADIUS; dx++) {
                        SourceSection source = bySection.get(RtTerrain.sectionKey(
                                cell.x + dx, cell.y + dy, cell.z + dz));
                        if (source == null || !(source.lights.power > 0.0)) continue;
                        double distanceSq = 16.0 * 16.0 * (dx * dx + dy * dy + dz * dz);
                        double weight = source.lights.power / Math.max(SECTION_DISTANCE_FLOOR_SQ, distanceSq);
                        selected.add(new WeightedSpan(source.lights.sectionSlot, weight));
                    }
                }
            }

            if (selected.isEmpty()) continue;
            selected.sort(Comparator.comparingInt(WeightedSpan::sectionSlot));
            double totalWeight = 0.0;
            for (WeightedSpan span : selected) totalWeight += span.weight;

            int linear = ((cell.z - minZ) * dimY + (cell.y - minY)) * dimX + (cell.x - minX);
            cellOffsets[linear] = spanSectionSlots.size();
            cellCounts[linear] = selected.size();
            double cumulative = 0.0;
            for (int spanIndex = 0; spanIndex < selected.size(); spanIndex++) {
                WeightedSpan span = selected.get(spanIndex);
                cumulative += span.weight / totalWeight;
                spanSectionSlots.add(span.sectionSlot);
                spanCdfs.add(spanIndex + 1 == selected.size() ? 1.0f : (float) cumulative);
                representedSections++;
            }
            populatedCells++;
        }

        if (spanSectionSlots.isEmpty()) return null;
        return new Data(minX * 16 - rebaseX, minY * 16 - rebaseY, minZ * 16 - rebaseZ,
                dimX, dimY, dimZ, cellOffsets, cellCounts,
                spanSectionSlots.toIntArray(), spanCdfs.toFloatArray(),
                populatedCells, representedSections);
    }

    record SectionLights(int sectionSlot, int x, int y, int z, double power) {
    }

    private record SourceSection(SectionLights lights) {
    }

    private record WeightedSpan(int sectionSlot, double weight) {
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
