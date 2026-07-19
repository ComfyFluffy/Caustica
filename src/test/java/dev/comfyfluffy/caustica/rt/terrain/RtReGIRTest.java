package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class RtReGIRTest {
    @Test
    void materializesOnlyResidentCellsNearEmitters() {
        List<RtReGIR.SectionLights> sections = List.of(
                new RtReGIR.SectionLights(0, 0, 0, 0, 3.0),
                new RtReGIR.SectionLights(1, 1, 0, 0, 0.0),
                new RtReGIR.SectionLights(2, 10, 0, 0, 0.0));

        RtReGIR.Data data = RtReGIR.build(sections, 0, 0, 0);

        assertEquals(2, data.populatedCells());
        assertEquals(0, data.originX());
        assertEquals(2, data.dimX());
        assertArrayEquals(new int[]{1, 1}, data.cellCounts());
        assertArrayEquals(new int[]{0, 0}, data.spanSectionSlots());
        assertArrayEquals(new float[]{1f, 1f}, data.spanCdfs(), 0f);
    }

    @Test
    void retainsEveryCandidateBeyondTheOldLimit() {
        RtReGIR.Data data = RtReGIR.build(
                List.of(new RtReGIR.SectionLights(7, 0, 0, 0, 96.0)), 0, 0, 0);

        assertEquals(1, data.cellCounts()[0]);
        assertEquals(1, data.spanSectionSlots().length);
        assertEquals(7, data.spanSectionSlots()[0]);
        assertEquals(1, data.representedSections());
    }

    @Test
    void skipsGridWhenThereAreNoLights() {
        assertNull(RtReGIR.build(List.of(new RtReGIR.SectionLights(0, 0, 0, 0, 0.0)),
                0, 0, 0));
    }
}
