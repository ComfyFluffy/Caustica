package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtPathSamplingTest {
    private static final int[][] DIRECTIONS = RtSobolDirectionNumbers.createDirections();

    @Test
    void grayCodeSobolValuesMatchReference() {
        int[][] expected = {
                {0x00000000, 0x80000000, 0xc0000000, 0x40000000,
                        0x60000000, 0xe0000000, 0xa0000000, 0x20000000},
                {0x00000000, 0x80000000, 0x40000000, 0xc0000000,
                        0x60000000, 0xe0000000, 0x20000000, 0xa0000000},
                {0x00000000, 0x80000000, 0x40000000, 0xc0000000,
                        0xa0000000, 0x20000000, 0xe0000000, 0x60000000},
                {0x00000000, 0x80000000, 0x40000000, 0xc0000000,
                        0xe0000000, 0x60000000, 0xa0000000, 0x20000000}
        };
        for (int dimension = 0; dimension < expected.length; dimension++) {
            for (int sample = 0; sample < expected[dimension].length; sample++) {
                assertEquals(expected[dimension][sample], directSobolBits(sample, dimension));
            }
        }
    }

    @Test
    void resourceLayoutIsDeterministicAndCompact() {
        int[] roots = roots(0x51a7cafeL);
        int[] first = RtPathSamplerData.buildResourceWords(roots);
        int[] second = RtPathSamplerData.buildResourceWords(roots);
        assertArrayEquals(first, second);
        assertEquals(0, RtPathSamplerData.DIRECTION_TABLE_OFFSET);
        assertEquals(RtPathSamplerData.ROOT_TABLE_OFFSET + RtPathSamplerData.ROOT_WORD_COUNT,
                RtPathSamplerData.WORD_COUNT);
        assertArrayEquals(roots, Arrays.copyOfRange(first,
                RtPathSamplerData.ROOT_TABLE_OFFSET, RtPathSamplerData.WORD_COUNT));
    }

    @Test
    void resourceLookupMatchesDirectSobolEvaluation() {
        int[] words = RtPathSamplerData.buildResourceWords(roots(0x51a7cafeL));
        Random random = new Random(0x5e0e1ceL);
        for (int iteration = 0; iteration < 2048; iteration++) {
            int sample = random.nextInt();
            int dimension = random.nextInt(DIRECTIONS.length);
            assertEquals(directSobolBits(sample, dimension), resourceSobolBits(words, sample, dimension));
        }
    }

    @Test
    void invalidResourcesAndAddressesFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> RtPathSamplerData.buildResourceWords(new int[1]));
        assertThrows(IllegalArgumentException.class,
                () -> RtPathSamplerData.buildResourceWords(null));
        assertThrows(IllegalStateException.class,
                () -> RtPathSamplerData.requireUsableBuffer(0L, 1L));
        assertThrows(IllegalStateException.class,
                () -> RtPathSamplerData.requireUsableBuffer(1L, 0L));
    }

    private static int directSobolBits(int sampleIndex, int dimension) {
        int gray = sampleIndex ^ (sampleIndex >>> 1);
        int value = 0;
        for (int bit = 0; bit < Integer.SIZE; bit++) {
            if ((gray & (1 << bit)) != 0) {
                value ^= DIRECTIONS[dimension][bit];
            }
        }
        return value;
    }

    private static int resourceSobolBits(int[] words, int sampleIndex, int dimension) {
        int gray = sampleIndex ^ (sampleIndex >>> 1);
        if (dimension == 0) {
            return Integer.reverse(gray);
        }
        int base = RtPathSamplerData.DIRECTION_TABLE_OFFSET
                + (dimension - RtPathSamplerData.FIRST_TABLE_DIMENSION)
                * RtPathSamplerData.WORDS_PER_DIMENSION;
        int value = 0;
        for (int block = 0; block < RtPathSamplerData.NIBBLE_BLOCKS; block++) {
            value ^= words[base + block * RtPathSamplerData.NIBBLE_VALUES
                    + ((gray >>> (block * 4)) & 15)];
        }
        return value;
    }

    private static int[] roots(long seed) {
        Random random = new Random(seed);
        int[] roots = new int[RtPathSamplerData.ROOT_WORD_COUNT];
        for (int index = 0; index < roots.length; index++) {
            roots[index] = random.nextInt();
        }
        return roots;
    }
}
