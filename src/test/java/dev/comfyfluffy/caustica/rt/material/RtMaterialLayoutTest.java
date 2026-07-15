package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.rt.gen.MaterialHeaderData;
import dev.comfyfluffy.caustica.rt.gen.MaterialHeaderData.Float4;
import dev.comfyfluffy.caustica.rt.gen.PushAddrData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtMaterialLayoutTest {
    @Test
    void reflectedMaterialHeaderMatchesHotAbi() {
        assertEquals(48, MaterialHeaderData.BYTE_SIZE);
        ByteBuffer data = ByteBuffer.allocateDirect(MaterialHeaderData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        new MaterialHeaderData(3, 5, 7, 11,
                new Float4(0.1f, 0.2f, 1.52f, 1.0f),
                new Float4(0.3f, 0.4f, 0.5f, 0.6f)).write(data);
        assertEquals(3, data.getInt(0));
        assertEquals(5, data.getInt(4));
        assertEquals(0.1f, data.getFloat(16));
        assertEquals(1.52f, data.getFloat(24));
        assertEquals(0.6f, data.getFloat(44));
    }

    @Test
    void reflectedPushAddressBlockIncludesMaterialTable() {
        assertEquals(40, PushAddrData.BYTE_SIZE);
        ByteBuffer data = ByteBuffer.allocateDirect(PushAddrData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        new PushAddrData(1L, 2L, 3L, 4L, 5).write(data);
        assertEquals(4L, data.getLong(24));
        assertEquals(5, data.getInt(32));
    }
}
