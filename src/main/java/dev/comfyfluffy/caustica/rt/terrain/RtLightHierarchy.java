package dev.comfyfluffy.caustica.rt.terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Worker-side immutable light hierarchy builder. The first hierarchy generation keeps light records
 * compact in one buffer, but all proposal metadata is section-owned: a section header names its
 * contiguous light range and local alias range, and ReGIR cells name section slots. This boundary lets
 * the compact ranges become independently allocated pages later without changing the shader sampler.
 */
final class RtLightHierarchy {
    static final int SOURCE_FLOATS_PER_LIGHT = RtLightCollector.FLOATS_PER_LIGHT;
    static final int GPU_FLOATS_PER_LIGHT = 16;

    private RtLightHierarchy() {
    }

    static Data build(List<SectionInput> sections, int rebaseX, int rebaseY, int rebaseZ) {
        ArrayList<SectionInput> orderedSections = new ArrayList<>(sections);
        orderedSections.sort(Comparator.comparingInt(SectionInput::sectionSlot));
        int sectionCapacity = 0;
        int totalLights = 0;
        for (SectionInput section : orderedSections) {
            sectionCapacity = Math.max(sectionCapacity, section.sectionSlot + 1);
            totalLights = Math.addExact(totalLights, lightCount(section.lights));
        }

        int[] sectionFirstLights = new int[sectionCapacity];
        int[] sectionLightCounts = new int[sectionCapacity];
        float[] sectionPowers = new float[sectionCapacity];
        float[] packedLights = new float[Math.multiplyExact(totalLights, GPU_FLOATS_PER_LIGHT)];
        double[] powers = new double[totalLights];
        ArrayList<RtReGIR.SectionLights> regirSections = new ArrayList<>(orderedSections.size());

        int lightIndex = 0;
        for (SectionInput section : orderedSections) {
            int count = lightCount(section.lights);
            int first = lightIndex;
            double sectionPower = 0.0;
            float ox = section.sectionX * 16f - rebaseX;
            float oy = section.sectionY * 16f - rebaseY;
            float oz = section.sectionZ * 16f - rebaseZ;
            for (int source = 0; source < section.lights.length;
                 source += SOURCE_FLOATS_PER_LIGHT, lightIndex++) {
                int destination = lightIndex * GPU_FLOATS_PER_LIGHT;
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
            sectionPowers[section.sectionSlot] = (float) sectionPower;
            regirSections.add(new RtReGIR.SectionLights(section.sectionSlot,
                    section.sectionX, section.sectionY, section.sectionZ, sectionPower));
        }

        AliasData globalAliases = buildAlias(powers, 0, totalLights);
        int[] localAliasIndices = new int[totalLights];
        float[] localAliasAccept = new float[totalLights];
        float[] localAliasSelfInvPdf = new float[totalLights];
        float[] localAliasAliasInvPdf = new float[totalLights];
        for (int slot = 0; slot < sectionCapacity; slot++) {
            int count = sectionLightCounts[slot];
            if (count == 0) continue;
            int first = sectionFirstLights[slot];
            AliasData local = buildAlias(powers, first, count);
            for (int i = 0; i < count; i++) {
                int destination = first + i;
                localAliasIndices[destination] = local.aliasIndices[i];
                localAliasAccept[destination] = local.accept[i];
                localAliasSelfInvPdf[destination] = local.selfInvPdf[i];
                localAliasAliasInvPdf[destination] = local.aliasInvPdf[i];
            }
        }

        RtReGIR.Data grid = totalLights > 0
                ? RtReGIR.build(regirSections, rebaseX, rebaseY, rebaseZ) : null;
        return new Data(packedLights, globalAliases,
                sectionFirstLights, sectionLightCounts, sectionPowers,
                new AliasData(localAliasIndices, localAliasAccept,
                        localAliasSelfInvPdf, localAliasAliasInvPdf),
                grid, totalLights, rebaseX, rebaseY, rebaseZ);
    }

    private static int lightCount(float[] lights) {
        return lights != null ? lights.length / SOURCE_FLOATS_PER_LIGHT : 0;
    }

    private static AliasData buildAlias(double[] powers, int offset, int count) {
        int[] alias = new int[count];
        float[] accept = new float[count];
        float[] selfInvPdf = new float[count];
        float[] aliasInvPdf = new float[count];
        if (count == 0) return new AliasData(alias, accept, selfInvPdf, aliasInvPdf);

        double total = 0.0;
        for (int i = 0; i < count; i++) total += powers[offset + i];
        if (!(total > 0.0)) return new AliasData(alias, accept, selfInvPdf, aliasInvPdf);

        double[] scaled = new double[count];
        int[] small = new int[count];
        int[] large = new int[count];
        int smallCount = 0;
        int largeCount = 0;
        for (int i = 0; i < count; i++) {
            double power = powers[offset + i];
            scaled[i] = power * count / total;
            selfInvPdf[i] = power > 0.0 ? (float) (total / power) : 0.0f;
            if (scaled[i] < 1.0) small[smallCount++] = i;
            else large[largeCount++] = i;
        }
        while (smallCount > 0 && largeCount > 0) {
            int s = small[--smallCount];
            int l = large[--largeCount];
            accept[s] = (float) scaled[s];
            alias[s] = l;
            scaled[l] = scaled[l] + scaled[s] - 1.0;
            if (scaled[l] < 1.0) small[smallCount++] = l;
            else large[largeCount++] = l;
        }
        while (largeCount > 0) {
            int i = large[--largeCount];
            accept[i] = 1.0f;
            alias[i] = i;
        }
        while (smallCount > 0) {
            int i = small[--smallCount];
            accept[i] = 1.0f;
            alias[i] = i;
        }
        for (int i = 0; i < count; i++) aliasInvPdf[i] = selfInvPdf[alias[i]];
        return new AliasData(alias, accept, selfInvPdf, aliasInvPdf);
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

    record Data(float[] packedLights, AliasData globalAliases,
                int[] sectionFirstLights, int[] sectionLightCounts, float[] sectionPowers,
                AliasData localAliases, RtReGIR.Data grid, int lightCount,
                int rebaseX, int rebaseY, int rebaseZ) {
        long lightBytes() {
            return Math.multiplyExact((long) packedLights.length, Float.BYTES);
        }

        long sectionBytes() {
            return Math.multiplyExact((long) sectionFirstLights.length, 16L);
        }
    }
}
