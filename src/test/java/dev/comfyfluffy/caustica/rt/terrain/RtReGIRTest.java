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
                new RtReGIR.SectionLights(0, 0, 0, 0, 2),
                new RtReGIR.SectionLights(1, 0, 0, 2, 0),
                new RtReGIR.SectionLights(10, 0, 0, 2, 0));

        RtReGIR.Data data = RtReGIR.build(sections,
                new float[]{8f, 9f}, new float[]{8f, 8f}, new float[]{8f, 8f},
                new double[]{1.0, 2.0}, 0, 0, 0);

        assertEquals(2, data.populatedCells());
        assertEquals(0, data.originX());
        assertEquals(2, data.dimX());
        assertArrayEquals(new int[]{2, 2}, data.cellCounts());
        assertArrayEquals(new int[]{0, 1, 0, 1}, data.candidates());
    }

    @Test
    void skipsGridWhenThereAreNoLights() {
        assertNull(RtReGIR.build(List.of(new RtReGIR.SectionLights(0, 0, 0, 0, 0)),
                new float[0], new float[0], new float[0], new double[0], 0, 0, 0));
    }
}
