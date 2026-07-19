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
                new RtReGIR.SectionLights(7, 0, 0, 0, 3.0));

        RtReGIR.Data data = RtReGIR.build(sections, 0, 0, 0);

        assertEquals(125, data.populatedCells());
        assertEquals(-32, data.originX());
        assertEquals(5, data.dimX());
        assertEquals(125, data.cellCounts().length);
        assertEquals(125, Arrays.stream(data.cellCounts()).sum());
        assertEquals(125, data.spanSectionSlots().length);
        for (int slot : data.spanSectionSlots()) assertEquals(7, slot);
        for (float cdf : data.spanCdfs()) assertEquals(1f, cdf, 0f);
    }

    @Test
    void retainsEveryCandidateBeyondTheOldLimit() {
        RtReGIR.Data data = RtReGIR.build(
                List.of(new RtReGIR.SectionLights(7, 0, 0, 0, 96.0)), 0, 0, 0);

        assertEquals(1, data.cellCounts()[0]);
        assertEquals(125, data.spanSectionSlots().length);
        assertEquals(7, data.spanSectionSlots()[0]);
        assertEquals(125, data.representedSections());
    }

    @Test
    void skipsGridWhenThereAreNoLights() {
        assertNull(RtReGIR.build(List.of(new RtReGIR.SectionLights(0, 0, 0, 0, 0.0)),
                0, 0, 0));
    }
}
