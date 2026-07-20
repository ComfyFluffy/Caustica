package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtLightHierarchyTest {
    @Test
    void buildsStableSectionRangesAndPowerWeightedLocalAliases() {
        float[] strongAndWeak = concat(light(1f, 1f), light(1f, 3f));
        float[] other = light(2f, 1f);

        RtLightHierarchy.Data data = RtLightHierarchy.build(List.of(
                new RtLightHierarchy.SectionInput(5, 4, 2, 1, other),
                new RtLightHierarchy.SectionInput(2, 1, 0, 0, strongAndWeak)
        ), 16, 0, 0);

        assertEquals(3, data.lightCount());
        assertEquals(0, data.sectionFirstLights()[2]);
        assertEquals(2, data.sectionLightCounts()[2]);
        assertEquals(2, data.sectionFirstLights()[5]);
        assertEquals(1, data.sectionLightCounts()[5]);

        // Section slot 2 has light powers 1 and 3.
        assertEquals(0.25, aliasProbability(data.localAliases(), 0, 2, 0), 1.0e-6);
        assertEquals(0.75, aliasProbability(data.localAliases(), 0, 2, 1), 1.0e-6);
        // Position is compacted into rebased world coordinates by the worker.
        assertEquals(0f, data.packedLights()[0], 0f);
        assertEquals(48f, data.packedLights()[2 * 12], 0f);
        // Grid-relative section coordinates live in the final Light48 lane.
        assertPackedCoord(data.packedLights()[11], 2, 2, 2);
        assertPackedCoord(data.packedLights()[2 * 12 + 11], 5, 4, 3);
        assertEquals(1f / 6f, data.invGlobalPowerSum(), 1.0e-6f);
        assertEquals(3L * 48L, data.lightBytes());
        assertEquals(3L * 8L, data.globalAliases().bytes());
    }

    @Test
    void lightGridAliasSpansEmbedSortedFlatLightRanges() {
        RtLightHierarchy.Data data = RtLightHierarchy.build(List.of(
                new RtLightHierarchy.SectionInput(9, 0, 0, 0, light(1f, 1f)),
                new RtLightHierarchy.SectionInput(3, 1, 0, 0, light(1f, 1f))
        ), 0, 0, 0);

        RtLightGrid.Data grid = data.grid();
        int cellX = -grid.originX() / 16;
        int cellY = -grid.originY() / 16;
        int cellZ = -grid.originZ() / 16;
        int centerCell = (cellZ * grid.dimY() + cellY) * grid.dimX() + cellX;
        int firstSpan = grid.cellOffsets()[centerCell];
        assertEquals(2, grid.cellCounts()[centerCell]);
        assertEquals(0, grid.spanFirstLights()[firstSpan]);
        assertEquals(1, grid.spanFirstLights()[firstSpan + 1]);
        assertEquals(1, grid.spanLightCounts()[firstSpan]);
        assertEquals(1, grid.spanLightCounts()[firstSpan + 1]);
        assertEquals(0.5, spanAliasProbability(grid, firstSpan, 2, 0), 1.0e-6);
        assertEquals(0.5, spanAliasProbability(grid, firstSpan, 2, 1), 1.0e-6);
        assertEquals(grid.spanFirstLights().length * 16L, grid.spanBytes());
    }

    @Test
    void mortonOrderOverridesUnrelatedStableSlotOrder() {
        RtLightHierarchy.Data data = RtLightHierarchy.build(List.of(
                new RtLightHierarchy.SectionInput(0, 8, 0, 0, light(1f, 1f)),
                new RtLightHierarchy.SectionInput(9, 0, 0, 0, light(1f, 1f))), 0, 0, 0);

        assertEquals(0, data.sectionFirstLights()[9]);
        assertEquals(1, data.sectionFirstLights()[0]);
    }

    @Test
    void packedRadianceRoundTripsRepresentativeHdrValues() {
        int packed = RtLightHierarchy.packR11G11B10(0.125f, 5.0f, 31.5f);
        assertEquals(0.125f, RtLightHierarchy.unpackUnsignedFloat(packed & 0x7ff, 6), 0.002f);
        assertEquals(5.0f, RtLightHierarchy.unpackUnsignedFloat((packed >>> 11) & 0x7ff, 6), 0.04f);
        assertEquals(31.5f, RtLightHierarchy.unpackUnsignedFloat((packed >>> 22) & 0x3ff, 5), 0.5f);
    }

    @Test
    void retainedGenerationCanBeTranslatedAcrossARebase() {
        List<RtLightHierarchy.SectionInput> sections = List.of(
                new RtLightHierarchy.SectionInput(0, 3, 0, 0, light(1f, 1f)));
        RtLightHierarchy.Data oldGeneration = RtLightHierarchy.build(sections, 16, 0, 0);
        RtLightHierarchy.Data rebuiltGeneration = RtLightHierarchy.build(sections, 32, 0, 0);

        float oldToCurrent = oldGeneration.rebaseX() - rebuiltGeneration.rebaseX();
        assertEquals(rebuiltGeneration.packedLights()[0],
                oldGeneration.packedLights()[0] + oldToCurrent, 0f);
        assertEquals(rebuiltGeneration.grid().originX(),
                oldGeneration.grid().originX() + oldToCurrent, 0f);
    }

    @Test
    void supersededBuildStopsCooperatively() {
        List<RtLightHierarchy.SectionInput> sections = List.of(
                new RtLightHierarchy.SectionInput(0, 0, 0, 0, light(1f, 1f)));

        assertThrows(CancellationException.class,
                () -> RtLightHierarchy.build(sections, 0, 0, 0, () -> true));
    }

    private static float[] light(float area, float radiance) {
        float[] record = new float[RtLightCollector.FLOATS_PER_LIGHT];
        record[3] = area;
        record[16] = radiance;
        record[17] = radiance;
        record[18] = radiance;
        return record;
    }

    private static float[] concat(float[] a, float[] b) {
        float[] result = new float[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static double aliasProbability(RtLightHierarchy.AliasData data, int first, int count,
                                           int targetLocalIndex) {
        double probability = 0.0;
        for (int column = 0; column < count; column++) {
            int index = first + column;
            if (column == targetLocalIndex) probability += data.accept()[index] / count;
            if (data.aliasIndices()[index] == targetLocalIndex) {
                probability += (1.0 - data.accept()[index]) / count;
            }
        }
        return probability;
    }

    private static double spanAliasProbability(RtLightGrid.Data data, int first, int count,
                                               int targetFirstLight) {
        double probability = 0.0;
        for (int column = 0; column < count; column++) {
            int span = first + column;
            if (data.spanFirstLights()[span] == targetFirstLight) {
                probability += data.spanAccept()[span] / count;
            }
            if (data.spanAliasFirstLights()[span] == targetFirstLight) {
                probability += (1.0 - data.spanAccept()[span]) / count;
            }
        }
        return probability;
    }

    private static void assertPackedCoord(float packedFloat, int x, int y, int z) {
        int packed = Float.floatToRawIntBits(packedFloat);
        assertEquals(x, packed & 0x3ff);
        assertEquals(y, (packed >>> 10) & 0x3ff);
        assertEquals(z, (packed >>> 20) & 0x3ff);
    }
}
