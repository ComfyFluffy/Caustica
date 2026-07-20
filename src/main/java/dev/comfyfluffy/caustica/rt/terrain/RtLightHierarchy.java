package dev.comfyfluffy.caustica.rt.terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Worker-side immutable light hierarchy builder. Lights are Morton ordered by section; section-local
 * aliases share those ranges. Proposal PDFs are reconstructed from the selected light's power, so the
 * GPU records contain only selection data.
 */
final class RtLightHierarchy {
    static final int SOURCE_FLOATS_PER_LIGHT = RtLightCollector.FLOATS_PER_LIGHT;
    static final int GPU_FLOATS_PER_LIGHT = 12;
    private static final int MAX_PACKED_GRID_DIM = 1024;
    private static final int NORMAL_FLIP_BIT = 1 << 30;

    private RtLightHierarchy() {
    }

    static Data build(List<SectionInput> sections, int rebaseX, int rebaseY, int rebaseZ) {
        return build(sections, rebaseX, rebaseY, rebaseZ, () -> false);
    }

    static Data build(List<SectionInput> sections, int rebaseX, int rebaseY, int rebaseZ,
                      BooleanSupplier cancelled) {
        List<SectionInput> orderedSections = orderedSections(sections, cancelled);
        int sectionCapacity = 0;
        int totalLights = 0;
        int maxSectionLights = 0;
        for (int i = 0; i < orderedSections.size(); i++) {
            if ((i & 255) == 0) checkCancelled(cancelled);
            SectionInput section = orderedSections.get(i);
            int count = lightCount(section.lights);
            sectionCapacity = Math.max(sectionCapacity, section.sectionSlot + 1);
            totalLights = Math.addExact(totalLights, count);
            maxSectionLights = Math.max(maxSectionLights, count);
        }

        int[] sectionFirstLights = new int[sectionCapacity];
        int[] sectionLightCounts = new int[sectionCapacity];
        float[] packedLights = new float[Math.multiplyExact(totalLights, GPU_FLOATS_PER_LIGHT)];
        int[] lightSectionCoords = new int[Math.multiplyExact(totalLights, 3)];
        double[] powers = new double[totalLights];
        ArrayList<RtReGIR.SectionLights> regirSections = new ArrayList<>(orderedSections.size());

        int lightIndex = 0;
        double globalPower = 0.0;
        for (int sectionIndex = 0; sectionIndex < orderedSections.size(); sectionIndex++) {
            if ((sectionIndex & 63) == 0) checkCancelled(cancelled);
            SectionInput section = orderedSections.get(sectionIndex);
            int count = lightCount(section.lights);
            int first = lightIndex;
            double sectionPower = 0.0;
            float ox = section.sectionX * 16f - rebaseX;
            float oy = section.sectionY * 16f - rebaseY;
            float oz = section.sectionZ * 16f - rebaseZ;
            for (int source = 0; source < section.lights.length;
                 source += SOURCE_FLOATS_PER_LIGHT, lightIndex++) {
                int destination = lightIndex * GPU_FLOATS_PER_LIGHT;
                int sectionDestination = lightIndex * 3;
                lightSectionCoords[sectionDestination] = section.sectionX;
                lightSectionCoords[sectionDestination + 1] = section.sectionY;
                lightSectionCoords[sectionDestination + 2] = section.sectionZ;
                float leR = section.lights[source + 16];
                float leG = section.lights[source + 17];
                float leB = section.lights[source + 18];
                int packedLe = packR11G11B10(leR, leG, leB);
                packedLights[destination] = section.lights[source] + ox;
                packedLights[destination + 1] = section.lights[source + 1] + oy;
                packedLights[destination + 2] = section.lights[source + 2] + oz;
                packedLights[destination + 3] = section.lights[source + 3];
                packedLights[destination + 4] = section.lights[source + 8];
                packedLights[destination + 5] = section.lights[source + 9];
                packedLights[destination + 6] = section.lights[source + 10];
                packedLights[destination + 7] = Float.intBitsToFloat(packedLe);
                packedLights[destination + 8] = section.lights[source + 12];
                packedLights[destination + 9] = section.lights[source + 13];
                packedLights[destination + 10] = section.lights[source + 14];
                float crossX = section.lights[source + 9] * section.lights[source + 14]
                        - section.lights[source + 10] * section.lights[source + 13];
                float crossY = section.lights[source + 10] * section.lights[source + 12]
                        - section.lights[source + 8] * section.lights[source + 14];
                float crossZ = section.lights[source + 8] * section.lights[source + 13]
                        - section.lights[source + 9] * section.lights[source + 12];
                if (crossX * section.lights[source + 4] + crossY * section.lights[source + 5]
                        + crossZ * section.lights[source + 6] < 0.0f) {
                    packedLights[destination + 11] = Float.intBitsToFloat(NORMAL_FLIP_BIT);
                }
                double luminance = 0.2126 * unpackUnsignedFloat(packedLe & 0x7ff, 6)
                        + 0.7152 * unpackUnsignedFloat((packedLe >>> 11) & 0x7ff, 6)
                        + 0.0722 * unpackUnsignedFloat((packedLe >>> 22) & 0x3ff, 5);
                double power = Math.max(0.0, section.lights[source + 3] * luminance);
                powers[lightIndex] = power;
                sectionPower += power;
                globalPower += power;
            }
            sectionFirstLights[section.sectionSlot] = first;
            sectionLightCounts[section.sectionSlot] = count;
            if (sectionPower > 0.0) {
                regirSections.add(new RtReGIR.SectionLights(first, count,
                        section.sectionX, section.sectionY, section.sectionZ, sectionPower));
            }
        }

        AliasData globalAliases = buildAlias(powers, 0, totalLights, cancelled);
        int[] localAliasIndices = new int[totalLights];
        float[] localAliasAccept = new float[totalLights];
        AliasScratch localScratch = new AliasScratch(maxSectionLights);
        for (int slot = 0; slot < sectionCapacity; slot++) {
            if ((slot & 255) == 0) checkCancelled(cancelled);
            int count = sectionLightCounts[slot];
            if (count == 0) continue;
            int first = sectionFirstLights[slot];
            if (count > 0xffff) {
                throw new IllegalStateException("Section light count exceeds Span16 capacity: " + count);
            }
            buildAliasInto(powers, first, count, localAliasIndices, localAliasAccept,
                    first, localScratch, cancelled);
        }

        RtReGIR.Data grid = totalLights > 0
                ? RtReGIR.build(regirSections, rebaseX, rebaseY, rebaseZ, cancelled) : null;
        if (grid != null && (grid.dimX() > MAX_PACKED_GRID_DIM || grid.dimY() > MAX_PACKED_GRID_DIM
                || grid.dimZ() > MAX_PACKED_GRID_DIM)) {
            grid = null;
        }
        if (grid != null) {
            int gridSectionX = (grid.originX() + rebaseX) / 16;
            int gridSectionY = (grid.originY() + rebaseY) / 16;
            int gridSectionZ = (grid.originZ() + rebaseZ) / 16;
            for (int i = 0; i < totalLights; i++) {
                int source = i * 3;
                int x = lightSectionCoords[source] - gridSectionX;
                int y = lightSectionCoords[source + 1] - gridSectionY;
                int z = lightSectionCoords[source + 2] - gridSectionZ;
                if ((x | y | z) < 0 || x >= MAX_PACKED_GRID_DIM || y >= MAX_PACKED_GRID_DIM
                        || z >= MAX_PACKED_GRID_DIM) {
                    throw new IllegalStateException("Light section is outside packed ReGIR grid");
                }
                int destination = i * GPU_FLOATS_PER_LIGHT + 11;
                int flags = Float.floatToRawIntBits(packedLights[destination]) & NORMAL_FLIP_BIT;
                packedLights[destination] = Float.intBitsToFloat(flags | x | (y << 10) | (z << 20));
            }
        }
        return new Data(packedLights, globalAliases,
                sectionFirstLights, sectionLightCounts,
                new AliasData(localAliasIndices, localAliasAccept), grid, totalLights,
                globalPower > 0.0 ? (float) (1.0 / globalPower) : 0.0f,
                rebaseX, rebaseY, rebaseZ);
    }

    private static int lightCount(float[] lights) {
        return lights != null ? lights.length / SOURCE_FLOATS_PER_LIGHT : 0;
    }

    private static List<SectionInput> orderedSections(List<SectionInput> sections,
                                                      BooleanSupplier cancelled) {
        if (sections.size() < 2) return sections;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (int i = 0; i < sections.size(); i++) {
            if ((i & 255) == 0) checkCancelled(cancelled);
            SectionInput section = sections.get(i);
            minX = Math.min(minX, section.sectionX);
            minY = Math.min(minY, section.sectionY);
            minZ = Math.min(minZ, section.sectionZ);
        }
        final int originX = minX, originY = minY, originZ = minZ;
        ArrayList<SectionInput> ordered = new ArrayList<>(sections);
        ordered.sort(Comparator.comparingLong((SectionInput section) -> mortonKey(
                        section.sectionX - originX, section.sectionY - originY,
                        section.sectionZ - originZ))
                .thenComparingInt(SectionInput::sectionSlot));
        return ordered;
    }

    private static long mortonKey(int x, int y, int z) {
        return spread3(x) | (spread3(y) << 1) | (spread3(z) << 2);
    }

    private static long spread3(int value) {
        long x = Integer.toUnsignedLong(value) & 0x1fffffL;
        x = (x | x << 32) & 0x1f00000000ffffL;
        x = (x | x << 16) & 0x1f0000ff0000ffL;
        x = (x | x << 8) & 0x100f00f00f00f00fL;
        x = (x | x << 4) & 0x10c30c30c30c30c3L;
        return (x | x << 2) & 0x1249249249249249L;
    }

    private static AliasData buildAlias(double[] powers, int offset, int count,
                                        BooleanSupplier cancelled) {
        int[] alias = new int[count];
        float[] accept = new float[count];
        buildAliasInto(powers, offset, count, alias, accept,
                0, new AliasScratch(count), cancelled);
        return new AliasData(alias, accept);
    }

    private static void buildAliasInto(double[] powers, int powerOffset, int count,
                                       int[] alias, float[] accept, int destinationOffset,
                                       AliasScratch scratch, BooleanSupplier cancelled) {
        if (count == 0) return;
        double total = 0.0;
        for (int i = 0; i < count; i++) {
            if ((i & 1023) == 0) checkCancelled(cancelled);
            total += powers[powerOffset + i];
        }
        if (!(total > 0.0)) return;

        double[] scaled = scratch.scaled;
        int[] small = scratch.small;
        int[] large = scratch.large;
        int smallCount = 0;
        int largeCount = 0;
        for (int i = 0; i < count; i++) {
            double power = powers[powerOffset + i];
            scaled[i] = power * count / total;
            if (scaled[i] < 1.0) small[smallCount++] = i;
            else large[largeCount++] = i;
        }
        while (smallCount > 0 && largeCount > 0) {
            int s = small[--smallCount];
            int l = large[--largeCount];
            accept[destinationOffset + s] = (float) scaled[s];
            alias[destinationOffset + s] = l;
            scaled[l] = scaled[l] + scaled[s] - 1.0;
            if (scaled[l] < 1.0) small[smallCount++] = l;
            else large[largeCount++] = l;
        }
        while (largeCount > 0) {
            int i = large[--largeCount];
            accept[destinationOffset + i] = 1.0f;
            alias[destinationOffset + i] = i;
        }
        while (smallCount > 0) {
            int i = small[--smallCount];
            accept[destinationOffset + i] = 1.0f;
            alias[destinationOffset + i] = i;
        }
    }

    static int packR11G11B10(float r, float g, float b) {
        return packUnsignedFloat(r, 6) | (packUnsignedFloat(g, 6) << 11)
                | (packUnsignedFloat(b, 5) << 22);
    }

    private static int packUnsignedFloat(float value, int mantissaBits) {
        if (!(value > 0.0f)) return 0;
        if (!Float.isFinite(value)) return (30 << mantissaBits) | ((1 << mantissaBits) - 1);
        int exponent = Math.getExponent(value);
        int encodedExponent = exponent + 15;
        int mantissaScale = 1 << mantissaBits;
        if (encodedExponent <= 0) {
            int mantissa = Math.round(Math.scalb(value, 14 + mantissaBits));
            return Math.min(mantissa, mantissaScale - 1);
        }
        if (encodedExponent >= 31) return (30 << mantissaBits) | (mantissaScale - 1);
        int mantissa = Math.round((Math.scalb(value, -exponent) - 1.0f) * mantissaScale);
        if (mantissa == mantissaScale) {
            mantissa = 0;
            if (++encodedExponent >= 31) return (30 << mantissaBits) | (mantissaScale - 1);
        }
        return (encodedExponent << mantissaBits) | mantissa;
    }

    static float unpackUnsignedFloat(int bits, int mantissaBits) {
        int mantissaMask = (1 << mantissaBits) - 1;
        int mantissa = bits & mantissaMask;
        int exponent = (bits >>> mantissaBits) & 31;
        if (exponent == 0) return Math.scalb((float) mantissa, 1 - 15 - mantissaBits);
        return Math.scalb(1.0f + (float) mantissa / (1 << mantissaBits), exponent - 15);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Superseded light hierarchy build");
        }
    }

    private static final class AliasScratch {
        final double[] scaled;
        final int[] small;
        final int[] large;

        AliasScratch(int capacity) {
            scaled = new double[capacity];
            small = new int[capacity];
            large = new int[capacity];
        }
    }

    record SectionInput(int sectionSlot, int sectionX, int sectionY, int sectionZ, float[] lights) {
        SectionInput {
            lights = lights != null ? lights : new float[0];
        }
    }

    record AliasData(int[] aliasIndices, float[] accept) {
        long bytes() {
            return Math.multiplyExact((long) aliasIndices.length, 8L);
        }
    }

    record Data(float[] packedLights, AliasData globalAliases,
                int[] sectionFirstLights, int[] sectionLightCounts,
                AliasData localAliases, RtReGIR.Data grid, int lightCount,
                float invGlobalPowerSum,
                int rebaseX, int rebaseY, int rebaseZ) {
        long lightBytes() {
            return Math.multiplyExact((long) packedLights.length, Float.BYTES);
        }

    }
}
