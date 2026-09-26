package dev.comfyfluffy.caustica.renderer.raytracing.shader;

import dev.comfyfluffy.caustica.renderer.raytracing.gen.NeeAtStateData;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.NeeAtBakePushData;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.RetainedInstanceRecordData;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.RetainedLightRecordData;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.SharcFrameData;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.WorldPushData;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.ShadowDiagnosticsData;
import dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtShaderRecordAbiTest {
    @Test
    void bakePushPreservesAddressAndControlLayout() {
        var value = new NeeAtBakePushData(1L, 2L, 3L, 4L, 5L, 6L,
                7, 8, 9, 10, 11L, 12L, 13, 0);
        ByteBuffer bytes = storage(NeeAtBakePushData.BYTE_SIZE);
        value.write(bytes);

        assertEquals(88, bytes.capacity());
        for (int field = 0; field < 6; field++) {
            assertEquals(field + 1L, bytes.getLong(field * Long.BYTES));
        }
        for (int field = 0; field < 4; field++) {
            assertEquals(field + 7, bytes.getInt(48 + field * Integer.BYTES));
        }
        assertEquals(11L, bytes.getLong(64));
        assertEquals(12L, bytes.getLong(72));
        assertEquals(13, bytes.getInt(80));
        assertEquals(0, bytes.getInt(84));
    }

    @Test
    void frameDataPacksRevisionRootsAndOriginConversionAlongsideCameraHistory() {
        var value = new WorldPushData(new Matrix4f().scaling(2), new WorldPushData.Float3(3, 4, 5), 6,
                new Matrix4f().scaling(7), new WorldPushData.Float3(8, 9, 10),
                new WorldPushData.Float2(11, 12), 13, 14, 15,
                new WorldPushData.Float3(16, 17, 18), new Matrix4f().scaling(19), 20, 21,
                0x1111222233334444L, 22, 0x2222333344445555L, 0x3333444455556666L, 31,
                new WorldPushData.Float3(-1024, 2048, -4096), 0.25f);
        ByteBuffer bytes = storage(WorldPushData.BYTE_SIZE);

        value.write(bytes);

        assertEquals(336, bytes.capacity());
        assertEquals(2, bytes.getFloat(0));
        assertEquals(3, bytes.getFloat(64));
        assertEquals(6, bytes.getInt(76));
        assertEquals(7, bytes.getFloat(80));
        assertEquals(8, bytes.getFloat(144));
        assertEquals(11, bytes.getFloat(160));
        assertEquals(13, bytes.getInt(168));
        assertEquals(15, bytes.getFloat(176));
        assertEquals(16, bytes.getFloat(192));
        assertEquals(19, bytes.getFloat(208));
        assertEquals(20, bytes.getFloat(272));
        assertEquals(21, bytes.getFloat(276));
        assertEquals(0x1111222233334444L, bytes.getLong(280));
        assertEquals(22, bytes.getInt(288));
        assertEquals(0x2222333344445555L, bytes.getLong(296));
        assertEquals(0x3333444455556666L, bytes.getLong(304));
        assertEquals(31, bytes.getInt(312));
        assertEquals(-1024, bytes.getFloat(320));
        assertEquals(2048, bytes.getFloat(324));
        assertEquals(-4096, bytes.getFloat(328));
        assertEquals(0.25f, bytes.getFloat(332));
        assertEquals(0, bytes.getInt(292));
        assertEquals(0, bytes.getInt(316));
    }

    @Test
    void instanceDataPreservesFullIdentityOpaqueWordsAndAlignedCurrentAffineRows() {
        var value = new RetainedInstanceRecordData(0x1122334455667788L, 0x8877665544332211L,
                0x2233445566778899L, 0x33445566778899aaL, 0x445566778899aabbL,
                0x5566778899aabbccL, 28, 0,
                new RetainedInstanceRecordData.Float4(1, 2, 3, 4),
                new RetainedInstanceRecordData.Float4(5, 6, 7, 8),
                new RetainedInstanceRecordData.Float4(9, 10, 11, 12));
        ByteBuffer bytes = storage(RetainedInstanceRecordData.BYTE_SIZE);

        value.write(bytes);

        assertEquals(112, bytes.capacity());
        assertEquals(value.identity(), bytes.getLong(0));
        assertEquals(value.placementOrdinal(), bytes.getLong(8));
        assertEquals(value.meshRevision(), bytes.getLong(16));
        assertEquals(value.topologyToken(), bytes.getLong(24));
        assertEquals(value.positionAddress(), bytes.getLong(32));
        assertEquals(value.instanceData(), bytes.getLong(40));
        assertEquals(28, bytes.getInt(48));
        assertEquals(0, bytes.getLong(56));
        for (int component = 0; component < 12; component++) {
            assertEquals(component + 1, bytes.getFloat(64 + component * Float.BYTES));
        }
    }

    @Test
    void retainedLightFieldsUseAlignedVectors() {
        var value = new RetainedLightRecordData(1, 2, 3, 4,
                new RetainedLightRecordData.Float4(5, 6, 7, 8),
                new RetainedLightRecordData.Float4(9, 10, 11, 12),
                new RetainedLightRecordData.Float4(13, 14, 15, 16),
                new RetainedLightRecordData.Float4(17, 18, 19, 20));
        ByteBuffer bytes = storage(RetainedLightRecordData.BYTE_SIZE);

        value.write(bytes);

        assertEquals(80, bytes.capacity());
        assertEquals(1, bytes.getInt(0));
        assertEquals(2, bytes.getInt(4));
        assertEquals(3, bytes.getFloat(8));
        assertEquals(5, bytes.getFloat(16));
        assertEquals(17, bytes.getFloat(64));
        assertEquals(20, bytes.getFloat(76));
    }

    @Test
    void lightingStatePacksFeedbackAddressesCountsAndPhysicalPower() {
        var value = new NeeAtStateData(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                13, 14, 15, 16, 17, 18, 19, 20);
        ByteBuffer bytes = storage(NeeAtStateData.BYTE_SIZE);

        value.write(bytes);

        assertEquals(104, bytes.capacity());
        for (int address = 0; address < 6; address++) assertEquals(address + 1, bytes.getLong(address * 8));
        for (int word = 0; word < 10; word++) assertEquals(word + 7, bytes.getInt(48 + word * 4));
        assertEquals(17, bytes.getFloat(88));
        assertEquals(18, bytes.getInt(92));
        assertEquals(19, bytes.getFloat(96));
        assertEquals(20, bytes.getFloat(100));
    }

    @Test
    void sharcFramePacksTablesCamerasAndAlignedVectors() {
        var value = new SharcFrameData(1L, 2L, 3L, new SharcFrameData.Float3(4, 5, 6), 7,
                new SharcFrameData.Float3(8, 9, 10), 11, 12, 13, 14, 15, 16, 17, 18, 19,
                new SharcFrameData.Int2(20, 21), 22, 23);
        ByteBuffer bytes = storage(SharcFrameData.BYTE_SIZE);

        value.write(bytes);

        assertEquals(112, bytes.capacity());
        for (int address = 0; address < 3; address++) assertEquals(address + 1, bytes.getLong(address * 8));
        assertEquals(4, bytes.getFloat(32));
        assertEquals(7, bytes.getInt(44));
        assertEquals(10, bytes.getFloat(56));
        assertEquals(11, bytes.getInt(60));
        assertEquals(16, bytes.getFloat(80));
        assertEquals(21, bytes.getInt(100));
        assertEquals(22, bytes.getFloat(104));
        assertEquals(23, bytes.getInt(108));
    }

    @Test
    void worldPushRootsRetainTheirDescriptorHeapAbi() {
        assertArrayEquals(new int[]{0, 8, 16, 24, 32, 40, 56, 68, 72, 76, 80, 84, 88, 92, 96,
                        100, 104, 112, 120, 128, 136, 144, 152, 160, 168, 176, 184, 188, 192, 196, 200, 208, 216,
                        224},
                new int[]{RtBindings.WORLD_PUSH_ADDRESS_OFFSET, RtBindings.WORLD_COMPOSITION_DATA_ADDRESS_OFFSET,
                        RtBindings.WORLD_GEOMETRY_TABLE_ADDRESS_OFFSET, RtBindings.WORLD_PATH_QUEUE_ADDRESS_OFFSET,
                        RtBindings.WORLD_TOP_LEVEL_AS_INDEX_OFFSET, RtBindings.WORLD_STABLE_PLANE_METADATA_IMAGE_INDEX_OFFSET,
                        RtBindings.WORLD_PRIMARY_DEPTH_INDEX_OFFSET,
                        RtBindings.WORLD_SPECULAR_MOTION_GUIDE_INDEX_OFFSET, RtBindings.WORLD_DIFFUSE_RADIANCE_HIT_DISTANCE_INDEX_OFFSET,
                        RtBindings.WORLD_SPECULAR_RADIANCE_HIT_DISTANCE_INDEX_OFFSET, RtBindings.WORLD_NRD_VIEW_Z_INDEX_OFFSET,
                        RtBindings.WORLD_DENOISED_DIFFUSE_RADIANCE_HIT_DISTANCE_INDEX_OFFSET,
                        RtBindings.WORLD_DENOISED_SPECULAR_RADIANCE_HIT_DISTANCE_INDEX_OFFSET,
                        RtBindings.WORLD_NRD_STABLE_RADIANCE_INDEX_OFFSET, RtBindings.WORLD_RESPONSIVITY_GUIDE_INDEX_OFFSET,
                        RtBindings.WORLD_INITIAL_VOLUME_IMPLEMENTATION_OFFSET,
                        RtBindings.WORLD_INITIAL_VOLUME_ACTIVE_OFFSET, RtBindings.WORLD_INITIAL_VOLUME_BINDING_OFFSET,
                        RtBindings.WORLD_INITIAL_VOLUME_INSTANCE_OFFSET, RtBindings.WORLD_NEE_AT_STATE_ADDRESS_OFFSET,
                        RtBindings.WORLD_NRD_SIGNAL_ENCODING_OFFSET, RtBindings.WORLD_STABLE_PLANE_BUFFER_ADDRESS_OFFSET,
                        RtBindings.WORLD_VISIBILITY_RAYS_ADDRESS_OFFSET, RtBindings.WORLD_VISIBILITY_RESULTS_ADDRESS_OFFSET,
                        RtBindings.WORLD_SPATIAL_MEDIUM_BINDING_DATA_OFFSET,
                        RtBindings.WORLD_SPATIAL_MEDIUM_INSTANCE_DATA_OFFSET,
                        RtBindings.WORLD_SPATIAL_MEDIUM_IMPLEMENTATION_OFFSET, RtBindings.WORLD_SPATIAL_MEDIUM_ACTIVE_OFFSET,
                        RtBindings.WORLD_SPATIAL_MEDIUM_ORIGIN_X_OFFSET, RtBindings.WORLD_SPATIAL_MEDIUM_ORIGIN_Y_OFFSET,
                        RtBindings.WORLD_SPATIAL_MEDIUM_ORIGIN_Z_OFFSET,
                        RtBindings.WORLD_SHADOW_DIAGNOSTICS_ADDRESS_OFFSET, RtBindings.WORLD_SHARC_FRAME_ADDRESS_OFFSET,
                        RtBindings.WORLD_PUSH_CONSTANT_SIZE});
    }

    @Test
    void shadowDiagnosticsReadbackPreservesCounterWords() {
        var value = new ShadowDiagnosticsData(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        ByteBuffer bytes = storage(ShadowDiagnosticsData.BYTE_SIZE);
        value.write(bytes);
        assertEquals(48, bytes.capacity());
        for (int word = 0; word < 12; word++) assertEquals(word + 1, bytes.getInt(word * 4));
        assertEquals(value, ShadowDiagnosticsData.read(bytes));
    }

    private static ByteBuffer storage(int size) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
        while (bytes.hasRemaining()) bytes.put((byte) 0xff);
        return bytes.clear();
    }
}
