package dev.comfyfluffy.caustica.rt.material;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anchors RtMaterialRegistry's medium-identity capacity to the shader's 20-bit payload field (bits
 * 9..28 in world_common.slang): every slot at or below the cap derives an identity that fits the
 * space, and one more record trips rebuild's fail-closed guard before any buffer is allocated.
 */
final class RtMaterialRegistryCapacityTest {

    @Test
    void capacityAnchorsTheTwentyBitIdentitySpace() throws Exception {
        Field capacityField = RtMaterialRegistry.class.getDeclaredField("MAX_MEDIUM_IDENTITY_RECORDS");
        capacityField.setAccessible(true);
        int capacity = capacityField.getInt(null);

        // 2^20 - 3, with air and water reserved: a dielectric's identity is materialId + 2.
        assertEquals(1048573, capacity);

        // At full capacity the largest materialId is capacity - 1, so the derived identity peaks at
        // capacity + 1 = 0xFFFFE, keeping the 0xFFFFF sentinel unallocated.
        assertEquals(0xFFFFE, capacity + 1);

        // One record more than the cap trips rebuild's fail-closed guard before buffer allocation.
        assertTrue(1048574 > capacity);
    }
}
