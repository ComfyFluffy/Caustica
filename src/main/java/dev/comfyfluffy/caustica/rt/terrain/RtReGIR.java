package dev.comfyfluffy.caustica.rt.terrain;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Arrays;
import java.util.List;

/**
 * CPU builder for the section-sized ReGIR proposal grid. A cell stores a small, sorted set of
 * influential light indices; the shader mixes its uniform cell proposal with the full global power
 * proposal, so truncating the cell set changes variance and locality but never removes a light's support.
 */
final class RtReGIR {
    static final int MAX_CANDIDATES = 32;

    private static final int GLOBAL_CANDIDATES = 8;
    private static final int NEIGHBOR_RADIUS = 2;
    private static final int MAX_DENSE_GRID_CELLS = 4_000_000;

    private RtReGIR() {
    }

    static Data build(List<SectionLights> sections, float[] lightX, float[] lightY, float[] lightZ,
                      double[] powers, int rebaseX, int rebaseY, int rebaseZ) {
        if (sections.isEmpty() || powers.length == 0) {
            return null;
        }

        Long2ObjectOpenHashMap<SectionLights> bySection = new Long2ObjectOpenHashMap<>(sections.size());
        for (SectionLights section : sections) {
            bySection.put(RtTerrain.sectionKey(section.x, section.y, section.z), section);
        }
        // A cell is useful only near an emitter. Materializing the entire render-distance volume on
        // every streaming publication would make the CPU build grow with all geometry, even though a
        // far, unlit cell is already well served by the global proposal.
        LongOpenHashSet activeKeys = new LongOpenHashSet();
        for (SectionLights source : sections) {
            if (source.lightCount == 0) continue;
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
        IntArrayList candidates = new IntArrayList(Math.multiplyExact(activeCount, MAX_CANDIDATES));

        int[] global = strongestLights(powers, Math.min(GLOBAL_CANDIDATES, powers.length));
        int[] seen = new int[powers.length];
        int stamp = 0;
        int[] selected = new int[MAX_CANDIDATES];
        double[] selectedScore = new double[MAX_CANDIDATES];

        for (int cellIndex = 0; cellIndex < activeCount; cellIndex++) {
            SectionLights cell = active[cellIndex];
            if (++stamp == 0) {
                Arrays.fill(seen, 0);
                stamp = 1;
            }
            int count = 0;
            for (int light : global) {
                if (seen[light] != stamp) {
                    seen[light] = stamp;
                    selected[count] = light;
                    selectedScore[count] = Double.POSITIVE_INFINITY; // reserved global-support slots
                    count++;
                }
            }
            int reserved = count;
            double centerX = cell.x * 16.0 - rebaseX + 8.0;
            double centerY = cell.y * 16.0 - rebaseY + 8.0;
            double centerZ = cell.z * 16.0 - rebaseZ + 8.0;
            for (int dz = -NEIGHBOR_RADIUS; dz <= NEIGHBOR_RADIUS; dz++) {
                for (int dy = -NEIGHBOR_RADIUS; dy <= NEIGHBOR_RADIUS; dy++) {
                    for (int dx = -NEIGHBOR_RADIUS; dx <= NEIGHBOR_RADIUS; dx++) {
                        SectionLights source = bySection.get(RtTerrain.sectionKey(
                                cell.x + dx, cell.y + dy, cell.z + dz));
                        if (source == null) continue;
                        int end = source.firstLight + source.lightCount;
                        for (int light = source.firstLight; light < end; light++) {
                            if (seen[light] == stamp) continue;
                            seen[light] = stamp;
                            double lx = lightX[light] - centerX;
                            double ly = lightY[light] - centerY;
                            double lz = lightZ[light] - centerZ;
                            // One block squared prevents singular rankings for lights inside the cell.
                            double score = powers[light] / Math.max(1.0, lx * lx + ly * ly + lz * lz);
                            if (count < MAX_CANDIDATES) {
                                selected[count] = light;
                                selectedScore[count] = score;
                                count++;
                            } else {
                                int weakest = reserved;
                                for (int i = reserved + 1; i < count; i++) {
                                    if (selectedScore[i] < selectedScore[weakest]) weakest = i;
                                }
                                if (score > selectedScore[weakest]) {
                                    selected[weakest] = light;
                                    selectedScore[weakest] = score;
                                }
                            }
                        }
                    }
                }
            }

            Arrays.sort(selected, 0, count); // shader membership uses binary search
            int linear = ((cell.z - minZ) * dimY + (cell.y - minY)) * dimX + (cell.x - minX);
            cellOffsets[linear] = candidates.size();
            cellCounts[linear] = count;
            candidates.addElements(candidates.size(), selected, 0, count);
        }

        return new Data(minX * 16 - rebaseX, minY * 16 - rebaseY, minZ * 16 - rebaseZ,
                dimX, dimY, dimZ, cellOffsets, cellCounts, candidates.toIntArray(), activeCount);
    }

    private static int[] strongestLights(double[] powers, int count) {
        int[] result = new int[count];
        Arrays.fill(result, -1);
        for (int light = 0; light < powers.length; light++) {
            for (int slot = 0; slot < count; slot++) {
                if (result[slot] < 0 || powers[light] > powers[result[slot]]) {
                    for (int move = count - 1; move > slot; move--) result[move] = result[move - 1];
                    result[slot] = light;
                    break;
                }
            }
        }
        return result;
    }

    record SectionLights(int x, int y, int z, int firstLight, int lightCount) {
    }

    record Data(int originX, int originY, int originZ, int dimX, int dimY, int dimZ,
                int[] cellOffsets, int[] cellCounts, int[] candidates, int populatedCells) {
        long cellBytes() {
            return Math.multiplyExact((long) cellOffsets.length, 8L);
        }

        long candidateBytes() {
            return Math.multiplyExact((long) candidates.length, Integer.BYTES);
        }
    }
}
