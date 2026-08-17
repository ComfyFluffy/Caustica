package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.rt.gen.MaterialHeaderData;
import dev.comfyfluffy.caustica.rt.gen.MaterialHeaderData.Float4;
import dev.comfyfluffy.caustica.rt.gen.WorldPushConstantsData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtMaterialLayoutTest {
    @Test
    void reflectedMaterialHeaderMatchesHotAbi() {
        assertEquals(80, MaterialHeaderData.BYTE_SIZE);
        ByteBuffer data = ByteBuffer.allocateDirect(MaterialHeaderData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        new MaterialHeaderData(3, 5, 7, 11,
                new Float4(0.01f, 0.02f, 0.03f, 0.04f),
                new Float4(0.05f, 0.06f, 7.0f, 8.0f),
                new Float4(0.1f, 0.2f, 1.52f, 1.0f),
                new Float4(0.3f, 0.4f, 0.5f, 0.6f)).write(data);
        assertEquals(3, data.getInt(0));
        assertEquals(5, data.getInt(4));
        assertEquals(7, data.getInt(8));
        assertEquals(11, data.getInt(12));
        assertEquals(0.01f, data.getFloat(16));
        assertEquals(7.0f, data.getFloat(40));
        assertEquals(0.1f, data.getFloat(48));
        assertEquals(1.52f, data.getFloat(56));
        assertEquals(0.6f, data.getFloat(76));
    }

    @Test
    void reflectedWorldPushConstantsIncludeLightBuffersFrameIndexAndParallax() {
        // 10 uint64_t addresses (world/table/material, 5 light buffers, path queue) + frameIndex, then
        // the parallax state the closest hit reads inline rather than through worldPushAddr.
        assertEquals(96, WorldPushConstantsData.BYTE_SIZE);
        ByteBuffer data = ByteBuffer.allocateDirect(WorldPushConstantsData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        new WorldPushConstantsData(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11,
                0.0625f, 24.0f, 32).write(data);
        assertEquals(4L, data.getLong(24));      // materialTableAddr
        assertEquals(5L, data.getLong(32));      // lightBufAddr
        assertEquals(9L, data.getLong(64));      // lightGridSpanAddr (last of the light-buffer addresses)
        assertEquals(10L, data.getLong(72));     // pathQueueAddr
        assertEquals(11, data.getInt(80));       // frameIndex
        assertEquals(0.0625f, data.getFloat(84)); // parallaxDepth, in blocks
        assertEquals(24.0f, data.getFloat(88));  // parallaxDistance, in blocks
        assertEquals(32, data.getInt(92));       // parallaxSteps
    }
}
