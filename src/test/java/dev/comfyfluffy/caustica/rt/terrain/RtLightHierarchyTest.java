package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        // Section slot 2 has light powers 1 and 3, so their reciprocal local PDFs are 4 and 4/3.
        assertEquals(4f, data.localAliases().selfInvPdf()[0], 1.0e-6f);
        assertEquals(4f / 3f, data.localAliases().selfInvPdf()[1], 1.0e-6f);
        // Position is compacted into rebased world coordinates by the worker.
        assertEquals(0f, data.packedLights()[0], 0f);
        assertEquals(48f, data.packedLights()[2 * 16], 0f);
    }

    @Test
    void regirSpansReferenceSectionSlotsInsteadOfFlatLightRanges() {
        RtLightHierarchy.Data data = RtLightHierarchy.build(List.of(
                new RtLightHierarchy.SectionInput(9, 0, 0, 0, light(1f, 1f)),
                new RtLightHierarchy.SectionInput(3, 1, 0, 0, light(1f, 1f))
        ), 0, 0, 0);

        RtReGIR.Data grid = data.grid();
        assertEquals(3, grid.spanSectionSlots()[0]);
        assertEquals(9, grid.spanSectionSlots()[1]);
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
}
