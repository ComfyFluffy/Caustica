package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class RtReGIRTest {
    @Test
    void materializesOnlyResidentCellsNearEmitters() {
        List<RtReGIR.SectionLights> sections = List.of(
                new RtReGIR.SectionLights(0, 0, 0, 0, 2),
                new RtReGIR.SectionLights(1, 0, 0, 2, 0),
                new RtReGIR.SectionLights(10, 0, 0, 2, 0));

        RtReGIR.Data data = RtReGIR.build(
                sections, new double[]{1.0, 2.0}, 0, 0, 0);

        assertEquals(2, data.populatedCells());
        assertEquals(0, data.originX());
        assertEquals(2, data.dimX());
        assertArrayEquals(new int[]{1, 1}, data.cellCounts());
        assertArrayEquals(new int[]{0, 0}, data.spanFirstLights());
        assertArrayEquals(new int[]{2, 2}, data.spanLightCounts());
        assertArrayEquals(new float[]{1f, 1f}, data.spanCdfs(), 0f);
    }

    @Test
    void retainsEveryCandidateBeyondTheOldLimit() {
        int lightCount = 96;
        double[] powers = new double[lightCount];
        Arrays.fill(powers, 1.0);

        RtReGIR.Data data = RtReGIR.build(
                List.of(new RtReGIR.SectionLights(0, 0, 0, 0, lightCount)),
                powers, 0, 0, 0);

        assertEquals(1, data.cellCounts()[0]);
        assertEquals(1, data.spanFirstLights().length);
        assertEquals(0, data.spanFirstLights()[0]);
        assertEquals(lightCount, data.spanLightCounts()[0]);
        assertEquals(lightCount, data.representedLights());
    }

    @Test
    void skipsGridWhenThereAreNoLights() {
        assertNull(RtReGIR.build(List.of(new RtReGIR.SectionLights(0, 0, 0, 0, 0)),
                new double[0], 0, 0, 0));
    }
}
