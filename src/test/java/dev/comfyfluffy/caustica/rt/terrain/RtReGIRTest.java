package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class RtReGIRTest {
    @Test
    void materializesTheFullFiveCubedNeighborhoodWithoutUnlitResidencyInput() {
        List<RtReGIR.SectionLights> sections = List.of(
                new RtReGIR.SectionLights(7, 2, 0, 0, 0, 3.0));

        RtReGIR.Data data = RtReGIR.build(sections, 0, 0, 0);

        assertEquals(125, data.populatedCells());
        assertEquals(-32, data.originX());
        assertEquals(5, data.dimX());
        assertEquals(125, data.cellCounts().length);
        assertEquals(125, Arrays.stream(data.cellCounts()).sum());
        assertEquals(125, data.spanFirstLights().length);
        for (int firstLight : data.spanFirstLights()) assertEquals(7, firstLight);
        for (int count : data.spanLightCounts()) assertEquals(2, count);
        for (float accept : data.spanAccept()) assertEquals(1f, accept, 0f);
        assertEquals(125L * 16L, data.spanBytes());
    }

    @Test
    void retainsEveryCandidateBeyondTheOldLimit() {
        RtReGIR.Data data = RtReGIR.build(
                List.of(new RtReGIR.SectionLights(7, 1, 0, 0, 0, 96.0)), 0, 0, 0);

        assertEquals(1, data.cellCounts()[0]);
        assertEquals(125, data.spanFirstLights().length);
        assertEquals(7, data.spanFirstLights()[0]);
        assertEquals(125, data.representedSections());
    }

    @Test
    void aliasColumnsReconstructExactSectionAndGlobalProbabilities() {
        RtReGIR.Data data = RtReGIR.build(List.of(
                new RtReGIR.SectionLights(4, 1, 0, 0, 0, 1.0),
                new RtReGIR.SectionLights(8, 1, 1, 0, 0, 3.0)), 0, 0, 0);
        int x = -data.originX() / 16;
        int y = -data.originY() / 16;
        int z = -data.originZ() / 16;
        int cell = (z * data.dimY() + y) * data.dimX() + x;
        int first = data.cellOffsets()[cell];
        int count = data.cellCounts()[cell];

        assertEquals(2, count);
        assertEquals(0.25f, data.cellInvWeightSums()[cell], 1.0e-6f);
        assertEquals(0.25, aliasProbability(data, first, count, 4), 1.0e-6);
        assertEquals(0.75, aliasProbability(data, first, count, 8), 1.0e-6);
    }

    @Test
    void skipsGridWhenThereAreNoLights() {
        assertNull(RtReGIR.build(List.of(new RtReGIR.SectionLights(0, 1, 0, 0, 0, 0.0)),
                0, 0, 0));
    }

    private static double aliasProbability(RtReGIR.Data data, int first, int count,
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
}
