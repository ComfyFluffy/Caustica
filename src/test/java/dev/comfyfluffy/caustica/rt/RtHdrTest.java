package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtHdrTest {
    private static final float EPSILON = 0.000001f;

    @Test
    void buildsRec2020D65MetadataAtTheSelectedMasteringPeak() {
        RtHdr.MasteringMetadata metadata = RtHdr.masteringMetadata(1000);

        assertChromaticity(metadata.red(), 0.708f, 0.292f);
        assertChromaticity(metadata.green(), 0.170f, 0.797f);
        assertChromaticity(metadata.blue(), 0.131f, 0.046f);
        assertChromaticity(metadata.white(), 0.3127f, 0.3290f);
        assertEquals(1000.0f, metadata.maxLuminance(), EPSILON);
        assertEquals(0.0001f, metadata.minLuminance(), EPSILON);
        assertEquals(1000.0f, metadata.maxContentLightLevel(), EPSILON);
        assertEquals(0.0f, metadata.maxFrameAverageLightLevel(), EPSILON);
    }

    @Test
    void rejectsAnInvalidMasteringPeak() {
        assertThrows(IllegalArgumentException.class, () -> RtHdr.masteringMetadata(0));
    }

    private static void assertChromaticity(RtHdr.Chromaticity actual, float x, float y) {
        assertEquals(x, actual.x(), EPSILON);
        assertEquals(y, actual.y(), EPSILON);
    }
}
