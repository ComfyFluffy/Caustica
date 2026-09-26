package dev.comfyfluffy.caustica.renderer.presentation.fog;

import dev.comfyfluffy.caustica.renderer.presentation.gen.FogPushData;
import dev.comfyfluffy.caustica.renderer.presentation.gen.FogPushData.Float4;
import dev.comfyfluffy.caustica.renderer.presentation.gen.FogVolumeData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FogInputsTest {
    @Test
    void registeredVolumeModulesResolveThroughTheirResourceAnchor() throws Exception {
        var source = FogVolume.definition().source();
        try (var medium = source.openModule("caustica_fog_medium");
             var types = source.openModule("caustica_fog_types")) {
            assertNotNull(medium);
            assertNotNull(types);
        }
    }

    @Test
    void spatialRevisionAndLightingRemainImmutable() {
        float[] cells = {1, 0.5f, 1, 64};
        FogField field = new FogField(0, 0, 0, 32, 1, 1, 1, cells);
        cells[0] = 4;
        field.voxels()[0] = 7;
        assertEquals(1, field.voxels()[0]);
        float[] light = {3, 4, 5};
        FogFrame frame = new FogFrame(field, 1, 64, 16, 0, light, light, light);
        light[0] = 6;
        frame.lightRadiance()[0] = 7;
        assertArrayEquals(new float[]{3, 4, 5}, frame.lightRadiance());
    }

    @Test
    void reflectedPushRecordPreservesAddressesCountsAndMatrices() {
        var v = new Float4(1, 2, 3, 4);
        var record = new FogPushData(0x123456789abcdef0L, 0x1230L, 0x4560L, 0x7890L, 1, 2, 3, 4, 0, 64, 32, 2,
                v, v, v, v, v, v, v, 0xabc0L, 0xdef0L, v, v, v, v);
        ByteBuffer buffer = ByteBuffer.allocate(FogPushData.BYTE_SIZE).order(ByteOrder.nativeOrder());
        for (int i = 0; i < buffer.capacity(); i++) buffer.put(i, (byte) 0xff);
        record.write(buffer);
        assertTrue(FogPushData.BYTE_SIZE <= 256);
        assertEquals(0x123456789abcdef0L, buffer.getLong(FogPushData.VOLUME_ADDRESS_OFFSET));
        assertEquals(64, buffer.getInt(FogPushData.STEPS_OFFSET));
        assertEquals(0x1230L, buffer.getLong(FogPushData.VISIBILITY_RAYS_ADDRESS_OFFSET));
        assertEquals(0x4560L, buffer.getLong(FogPushData.VISIBILITY_RESULTS_ADDRESS_OFFSET));
        assertEquals(0x7890L, buffer.getLong(FogPushData.PREFIX_ADDRESS_OFFSET));
        assertEquals(0xabc0L, buffer.getLong(FogPushData.HISTORY_ADDRESS_OFFSET));
        assertEquals(0xdef0L, buffer.getLong(FogPushData.PREVIOUS_HISTORY_ADDRESS_OFFSET));
        assertEquals(4, buffer.getFloat(FogPushData.PREVIOUS_CLIP_W_OFFSET + 12));
        assertEquals(32, buffer.getInt(FogPushData.BATCH_STEPS_OFFSET));
        assertEquals(2, buffer.getInt(FogPushData.DEBUG_OFFSET));
        assertEquals(4, buffer.getFloat(FogPushData.CLIP_W_OFFSET + 12));
        assertEquals(3, buffer.getFloat(FogPushData.TLAS_CAMERA_OFFSET + 8));
    }

    @Test
    void capturedVolumeUsesSceneRelativeCoordinatesAndPhysicalRadiance() {
        FogField field = new FogField(29_999_744, -64, -29_999_744, 32, 1, 1, 1,
                new float[]{1, .5f, 1, 64});
        FogFrame frame = new FogFrame(field, .5f, 70, 18, .75f,
                new float[]{0, 1, 0}, new float[]{100, 200, 300}, new float[]{3, 4, 5});
        ByteBuffer buffer = ByteBuffer.allocate(FogVolumeData.BYTE_SIZE).order(ByteOrder.nativeOrder());
        FogVolume.data(frame, 2, 30_000_000, 128, -30_000_000, .25, 0x1230).write(buffer);
        assertEquals(0x1230, buffer.getLong(FogVolumeData.FIELD_ADDRESS_OFFSET));
        assertEquals(-256, buffer.getFloat(FogVolumeData.FIELD_ORIGIN_OFFSET));
        assertEquals(-192, buffer.getFloat(FogVolumeData.FIELD_ORIGIN_OFFSET + 4));
        assertEquals(256, buffer.getFloat(FogVolumeData.FIELD_ORIGIN_OFFSET + 8));
        assertEquals(.00075f, buffer.getFloat(FogVolumeData.PROFILE_OFFSET), 1e-8f);
        assertEquals(-58, buffer.getFloat(FogVolumeData.PROFILE_OFFSET + 4));
        assertEquals(1024, buffer.getFloat(FogVolumeData.PROFILE_OFFSET + 12));
        assertEquals(200, buffer.getFloat(FogVolumeData.LIGHT_RADIANCE_OFFSET + 4));
        assertEquals(.75f, buffer.getFloat(FogVolumeData.NOISE_ORIGIN_OFFSET + 12));
        float initialNoiseX = buffer.getFloat(FogVolumeData.NOISE_ORIGIN_OFFSET);
        FogVolume.data(frame, 2, 30_000_000 + 4096, 128, -30_000_000, .25, 0x1230).write(buffer);
        assertEquals(initialNoiseX, buffer.getFloat(FogVolumeData.NOISE_ORIGIN_OFFSET));
        assertEquals(-4352, buffer.getFloat(FogVolumeData.FIELD_ORIGIN_OFFSET));
    }
}
