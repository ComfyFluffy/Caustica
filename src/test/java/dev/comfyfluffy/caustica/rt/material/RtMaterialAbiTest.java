package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtMaterialAbiTest {
    @Test
    void acceptsMatchingTriangleRecords() {
        assertEquals(2, RtMaterialAbi.checkedPrimitiveCount(24));
        assertDoesNotThrow(() -> RtMaterialAbi.requireTriangleParity(24, 6));
    }

    @Test
    void rejectsPartialAndMismatchedRecords() {
        assertThrows(IllegalArgumentException.class, () -> RtMaterialAbi.checkedPrimitiveCount(13));
        assertThrows(IllegalArgumentException.class, () -> RtMaterialAbi.requireTriangleParity(24, 3));
        assertThrows(IllegalArgumentException.class, () -> RtMaterialAbi.requireTriangleParity(12, 4));
    }
}
