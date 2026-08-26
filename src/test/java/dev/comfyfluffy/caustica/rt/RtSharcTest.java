package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.rt.gen.SharcFrameData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtSharcTest {
    @Test
    void clampsTheTableExponentAndAccountsForTheFrameRing() {
        assertEquals(64L * 65536L, RtSharcCache.tableBytesForExponent(16));
        assertEquals(RtSharcCache.tableBytesForExponent(16)
                + (long) RtSharcCache.RING * SharcFrameData.BYTE_SIZE,
                RtSharcCache.memoryBytesForExponent(16));
        assertEquals(RtSharcCache.MIN_EXPONENT, RtSharcCache.clampExponent(1));
        assertEquals(RtSharcCache.MAX_EXPONENT, RtSharcCache.clampExponent(99));
        assertEquals(20, RtSharcCache.clampExponent(20));
    }
}
