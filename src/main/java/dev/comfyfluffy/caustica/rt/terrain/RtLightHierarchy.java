package dev.comfyfluffy.caustica.rt.terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Worker-side immutable light hierarchy builder. Lights remain compact and ordered by stable section
 * slot; section-local aliases share the same ranges. ReGIR embeds those ranges in per-cell alias columns,
 * avoiding a dependent section-header fetch in the shader while preserving exact proposal PDFs.
 */
final class RtLightHierarchy {
    static final int SOURCE_FLOATS_PER_LIGHT = RtLightCollector.FLOATS_PER_LIGHT;
    static final int GPU_FLOATS_PER_LIGHT = 16;

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
        float[] lightSectionPowers = new float[totalLights];
        double[] powers = new double[totalLights];
        ArrayList<RtReGIR.SectionLights> regirSections = new ArrayList<>(orderedSections.size());

        int lightIndex = 0;
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
                packedLights[destination] = section.lights[source] + ox;
                packedLights[destination + 1] = section.lights[source + 1] + oy;
                packedLights[destination + 2] = section.lights[source + 2] + oz;
                packedLights[destination + 3] = section.lights[source + 3];
                packedLights[destination + 4] = section.lights[source + 4];
                packedLights[destination + 5] = section.lights[source + 5];
                packedLights[destination + 6] = section.lights[source + 6];
                packedLights[destination + 7] = leR;
                packedLights[destination + 8] = section.lights[source + 8];
                packedLights[destination + 9] = section.lights[source + 9];
                packedLights[destination + 10] = section.lights[source + 10];
                packedLights[destination + 11] = leG;
                packedLights[destination + 12] = section.lights[source + 12];
                packedLights[destination + 13] = section.lights[source + 13];
                packedLights[destination + 14] = section.lights[source + 14];
                packedLights[destination + 15] = leB;
                double luminance = 0.2126 * leR + 0.7152 * leG + 0.0722 * leB;
                double power = Math.max(0.0, section.lights[source + 3] * luminance);
                powers[lightIndex] = power;
                sectionPower += power;
            }
            sectionFirstLights[section.sectionSlot] = first;
            sectionLightCounts[section.sectionSlot] = count;
            for (int i = first; i < lightIndex; i++) {
                lightSectionPowers[i] = (float) sectionPower;
            }
            if (sectionPower > 0.0) {
                regirSections.add(new RtReGIR.SectionLights(first, count,
                        section.sectionX, section.sectionY, section.sectionZ, sectionPower));
            }
        }

        AliasData globalAliases = buildAlias(powers, 0, totalLights, cancelled);
        int[] localAliasIndices = new int[totalLights];
        float[] localAliasAccept = new float[totalLights];
        float[] localAliasSelfInvPdf = new float[totalLights];
        float[] localAliasAliasInvPdf = new float[totalLights];
        AliasScratch localScratch = new AliasScratch(maxSectionLights);
        for (int slot = 0; slot < sectionCapacity; slot++) {
            if ((slot & 255) == 0) checkCancelled(cancelled);
            int count = sectionLightCounts[slot];
            if (count == 0) continue;
            int first = sectionFirstLights[slot];
            buildAliasInto(powers, first, count, localAliasIndices, localAliasAccept,
                    localAliasSelfInvPdf, localAliasAliasInvPdf, first, localScratch, cancelled);
        }

        RtReGIR.Data grid = totalLights > 0
                ? RtReGIR.build(regirSections, rebaseX, rebaseY, rebaseZ, cancelled) : null;
        if (grid != null) {
            int gridSectionX = (grid.originX() + rebaseX) / 16;
            int gridSectionY = (grid.originY() + rebaseY) / 16;
            int gridSectionZ = (grid.originZ() + rebaseZ) / 16;
            for (int i = 0; i < totalLights; i++) {
                int destination = i * 3;
                lightSectionCoords[destination] -= gridSectionX;
                lightSectionCoords[destination + 1] -= gridSectionY;
                lightSectionCoords[destination + 2] -= gridSectionZ;
            }
        }
        return new Data(packedLights, lightSectionCoords, lightSectionPowers, globalAliases,
                sectionFirstLights, sectionLightCounts,
                new AliasData(localAliasIndices, localAliasAccept,
                        localAliasSelfInvPdf, localAliasAliasInvPdf),
                grid, totalLights, rebaseX, rebaseY, rebaseZ);
    }

    private static int lightCount(float[] lights) {
        return lights != null ? lights.length / SOURCE_FLOATS_PER_LIGHT : 0;
    }

    private static List<SectionInput> orderedSections(List<SectionInput> sections,
                                                      BooleanSupplier cancelled) {
        int previousSlot = -1;
        for (int i = 0; i < sections.size(); i++) {
            if ((i & 255) == 0) checkCancelled(cancelled);
            int slot = sections.get(i).sectionSlot;
            if (slot < previousSlot) {
                ArrayList<SectionInput> ordered = new ArrayList<>(sections);
                ordered.sort(Comparator.comparingInt(SectionInput::sectionSlot));
                return ordered;
            }
            previousSlot = slot;
        }
        return sections;
    }

    private static AliasData buildAlias(double[] powers, int offset, int count,
                                        BooleanSupplier cancelled) {
        int[] alias = new int[count];
        float[] accept = new float[count];
        float[] selfInvPdf = new float[count];
        float[] aliasInvPdf = new float[count];
        buildAliasInto(powers, offset, count, alias, accept, selfInvPdf, aliasInvPdf,
                0, new AliasScratch(count), cancelled);
        return new AliasData(alias, accept, selfInvPdf, aliasInvPdf);
    }

    private static void buildAliasInto(double[] powers, int powerOffset, int count,
                                       int[] alias, float[] accept, float[] selfInvPdf,
                                       float[] aliasInvPdf, int destinationOffset,
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
            selfInvPdf[destinationOffset + i] = power > 0.0 ? (float) (total / power) : 0.0f;
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
        for (int i = 0; i < count; i++) {
            aliasInvPdf[destinationOffset + i] =
                    selfInvPdf[destinationOffset + alias[destinationOffset + i]];
        }
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

    record AliasData(int[] aliasIndices, float[] accept,
                     float[] selfInvPdf, float[] aliasInvPdf) {
        long bytes() {
            return Math.multiplyExact((long) aliasIndices.length, 16L);
        }
    }

    record Data(float[] packedLights, int[] lightSectionCoords, float[] lightSectionPowers,
                AliasData globalAliases,
                int[] sectionFirstLights, int[] sectionLightCounts,
                AliasData localAliases, RtReGIR.Data grid, int lightCount,
                int rebaseX, int rebaseY, int rebaseZ) {
        long lightBytes() {
            return Math.multiplyExact((long) packedLights.length, Float.BYTES);
        }

        long sectionMetadataBytes() {
            return grid != null ? Math.multiplyExact((long) lightCount, 16L) : 0L;
        }

    }
}
