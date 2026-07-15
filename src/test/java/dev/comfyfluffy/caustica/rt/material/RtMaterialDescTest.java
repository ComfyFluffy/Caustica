package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtMaterialDescTest {
    @Test
    void retainsCompilerSourceAndNormalizedEmissionMetadata() {
        RtMaterialDesc.EmissionSummary summary = new RtMaterialDesc.EmissionSummary(
                0.4f, 0.2f, 0.1f, 0.25f, 0.5f);
        RtMaterialDesc desc = new RtMaterialDesc(0, RtMaterialDesc.Source.HEURISTIC, 0,
                0.7f, 0.0f, 1.0f, 0.0f, RtMaterialDesc.EmissionSource.HEURISTIC_MASK,
                1.0f, summary);
        assertEquals(RtMaterialDesc.Source.HEURISTIC, desc.source());
        assertEquals(RtMaterialDesc.EmissionSource.HEURISTIC_MASK, desc.emissionSource());
        assertEquals(summary, desc.emissionSummary());
    }

    @Test
    void rejectsInvalidPhysicalParameters() {
        assertThrows(IllegalArgumentException.class, () -> new RtMaterialDesc(0,
                RtMaterialDesc.Source.HEURISTIC, 0, 1.1f, 0.0f, 1.0f, 0.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE));
        assertThrows(IllegalArgumentException.class, () -> new RtMaterialDesc(0,
                RtMaterialDesc.Source.HEURISTIC, 0, 0.5f, 0.0f, 0.0f, 0.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE));
    }
}
