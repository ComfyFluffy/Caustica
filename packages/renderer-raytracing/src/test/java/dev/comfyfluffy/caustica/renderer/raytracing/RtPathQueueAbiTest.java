package dev.comfyfluffy.caustica.renderer.raytracing;

import dev.comfyfluffy.caustica.renderer.raytracing.gen.PackedPathSegmentData;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.PackedPathSegmentData.Float3;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.PackedStablePlaneData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtPathQueueAbiTest {
    @Test
    void scratchCapacityUsesThreeReflectedRestartRecordsPerPixel() {
        assertEquals(64, PackedPathSegmentData.BYTE_SIZE);
        assertEquals(1920L * 1080L * 3L * PackedPathSegmentData.BYTE_SIZE,
                TraceResources.pathScratchBytes(1920, 1080));
    }

    @Test
    void restartRecordsWriteExactFieldsAndArrayStride() {
        ByteBuffer storage = ByteBuffer.allocate(2 * PackedPathSegmentData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        for (int record = 0; record < 2; record++) {
            float value = 100f * record;
            new PackedPathSegmentData(new Float3(value + 1, value + 2, value + 3), value + 4,
                    new Float3(value + 5, value + 6, value + 7), value + 8,
                    new Float3(value + 9, value + 10, value + 11), 0x12345678 + record,
                    new Float3(value + 13, value + 14, value + 15), 0x80000001 + record)
                    .write(storage.slice(record * PackedPathSegmentData.BYTE_SIZE,
                            PackedPathSegmentData.BYTE_SIZE).order(ByteOrder.nativeOrder()));
        }
        for (int record = 0; record < 2; record++) {
            int base = record * 64;
            for (int word = 0; word < 16; word++) {
                if (word == 11) {
                    assertEquals(0x12345678 + record, storage.getInt(base + word * 4));
                } else if (word == 15) {
                    assertEquals(0x80000001 + record, storage.getInt(base + word * 4));
                } else {
                    assertEquals(100f * record + word + 1, storage.getFloat(base + word * 4));
                }
            }
        }
    }

    @Test
    void stablePlaneCapacityUsesThreeReflectedRecordsPerPixel() {
        assertEquals(64, PackedStablePlaneData.BYTE_SIZE);
        assertEquals(1920L * 1080L * 3L * PackedStablePlaneData.BYTE_SIZE,
                TraceResources.stablePlaneBytes(1920, 1080));
    }

    @Test
    void packedStablePlaneFieldsFollowTheirWordStreams() {
        assertEquals(0, PackedStablePlaneData.CURRENT_VIRTUAL_POSITION_OFFSET);
        int[] wordOffsets = {
                PackedStablePlaneData.THROUGHPUT_LUMINANCE_OFFSET,
                PackedStablePlaneData.BRANCH_ID_OFFSET,
                PackedStablePlaneData.FLAGS_OFFSET,
                PackedStablePlaneData.NORMAL_OFFSET,
                PackedStablePlaneData.MOTION_XY_OFFSET,
                PackedStablePlaneData.MOTION_ZROUGHNESS_OFFSET,
                PackedStablePlaneData.DIFFUSE_BSDF_ESTIMATE_XY_OFFSET,
                PackedStablePlaneData.DIFFUSE_BSDF_ESTIMATE_ZSPECULAR_BSDF_ESTIMATE_X_OFFSET,
                PackedStablePlaneData.SPECULAR_BSDF_ESTIMATE_YZ_OFFSET,
                PackedStablePlaneData.NOISY_DIFFUSE_RADIANCE_XY_OFFSET,
                PackedStablePlaneData.NOISY_DIFFUSE_RADIANCE_ZDIFFUSE_HIT_DISTANCE_OFFSET,
                PackedStablePlaneData.NOISY_SPECULAR_RADIANCE_XY_OFFSET,
                PackedStablePlaneData.NOISY_SPECULAR_RADIANCE_ZSPECULAR_HIT_DISTANCE_OFFSET
        };
        for (int field = 0; field < wordOffsets.length; field++) {
            assertEquals((3 + field) * Integer.BYTES, wordOffsets[field]);
        }
    }
}
