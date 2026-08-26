package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable GPU data for Caustica's canonical shuffled-scrambled Sobol path sampler.
 *
 * <p>The resource stores compact Joe-Kuo Sobol nibble lookups for dimensions 1..3; dimension 0 is
 * evaluated analytically as the bit-reversed Gray code. It also stores independently generated
 * randomization roots for every continuation branch, bounce, and semantic low-dimensional group. The
 * independent per-coordinate digital-shift roots are the estimator's unbiased randomization; the
 * nested-uniform index shuffle and coordinate scramble improve projection quality without being the
 * proof foundation. This is sample-indexed direction data, never a spatial tile.
 */
public final class RtPathSamplerData {
    public static final int ALGORITHM_VERSION = 3;

    static final int DIMENSIONS = RtSobolDirectionNumbers.DIMENSIONS;
    static final int NIBBLE_BLOCKS = 8;
    static final int NIBBLE_VALUES = 16;
    static final int WORDS_PER_DIMENSION = NIBBLE_BLOCKS * NIBBLE_VALUES;
    static final int FIRST_TABLE_DIMENSION = 1;
    static final int TABLE_DIMENSION_COUNT = DIMENSIONS - FIRST_TABLE_DIMENSION;

    public static final int PATH_BRANCH_COUNT = 2;
    public static final int MAX_SUPPORTED_BOUNCE = 8;
    static final int BOUNCE_COUNT = MAX_SUPPORTED_BOUNCE + 1;
    public static final int MAX_RIS_CANDIDATES = 32;

    static final int GROUP_COUNT = 3 + 1 + MAX_RIS_CANDIDATES * 2 + 2;
    static final int ROOTS_PER_GROUP = 1 + DIMENSIONS * 2;

    static final int DIRECTION_TABLE_OFFSET = 0;
    static final int ROOT_TABLE_OFFSET = DIRECTION_TABLE_OFFSET
            + TABLE_DIMENSION_COUNT * WORDS_PER_DIMENSION;
    static final int ROOT_WORD_COUNT = PATH_BRANCH_COUNT * BOUNCE_COUNT * GROUP_COUNT * ROOTS_PER_GROUP;
    static final int WORD_COUNT = ROOT_TABLE_OFFSET + ROOT_WORD_COUNT;
    static final long BYTE_SIZE = (long) WORD_COUNT * Integer.BYTES;
    static final int ADDRESS_ALIGNMENT = 16;

    private static final int[][] DIRECTIONS = RtSobolDirectionNumbers.createDirections();

    private final RtBuffer buffer;

    private RtPathSamplerData(RtBuffer buffer) {
        this.buffer = buffer;
    }

    /** Create the one required sampler resource. Failure propagates and disables RT through RtComposite. */
    public static RtPathSamplerData create(RtContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        SecureRandom random = new SecureRandom();
        int[] roots = new int[ROOT_WORD_COUNT];
        for (int index = 0; index < roots.length; index++) {
            roots[index] = random.nextInt();
        }
        return create(ctx, roots);
    }

    private static RtPathSamplerData create(RtContext ctx, int[] randomizationRoots) {
        Objects.requireNonNull(ctx, "ctx");
        int[] roots = checkedRootCopy(randomizationRoots);
        RtBuffer buffer = ctx.createAlignedBuffer(BYTE_SIZE, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true,
                "canonical shuffled-scrambled Sobol path sampler", ADDRESS_ALIGNMENT);
        try {
            requireUsableBuffer(buffer.mapped, buffer.deviceAddress);

            int[] words = buildResourceWords(roots);
            long address = buffer.mapped;
            for (int word : words) {
                MemoryUtil.memPutInt(address, word);
                address += Integer.BYTES;
            }
            buffer.flush();
            return new RtPathSamplerData(buffer);
        } catch (RuntimeException | Error failure) {
            try {
                buffer.destroy();
            } catch (RuntimeException | Error destroyFailure) {
                failure.addSuppressed(destroyFailure);
            }
            throw failure;
        }
    }

    public long deviceAddress() {
        return buffer.deviceAddress;
    }

    public void destroy() {
        buffer.destroy();
    }

    static void requireUsableBuffer(long mappedAddress, long deviceAddress) {
        if (mappedAddress == 0L) {
            throw new IllegalStateException("Path sampler data buffer is not host mapped");
        }
        if (deviceAddress == 0L) {
            throw new IllegalStateException("Path sampler data buffer has no device address");
        }
    }

    static int[] buildResourceWords(int[] randomizationRoots) {
        int[] roots = checkedRootCopy(randomizationRoots);
        int[] words = new int[WORD_COUNT];
        for (int dimension = FIRST_TABLE_DIMENSION; dimension < DIMENSIONS; dimension++) {
            int tableDimension = dimension - FIRST_TABLE_DIMENSION;
            int dimensionOffset = DIRECTION_TABLE_OFFSET + tableDimension * WORDS_PER_DIMENSION;
            for (int block = 0; block < NIBBLE_BLOCKS; block++) {
                int blockOffset = dimensionOffset + block * NIBBLE_VALUES;
                for (int nibble = 0; nibble < NIBBLE_VALUES; nibble++) {
                    int value = 0;
                    for (int bit = 0; bit < 4; bit++) {
                        if ((nibble & (1 << bit)) != 0) {
                            value ^= DIRECTIONS[dimension][block * 4 + bit];
                        }
                    }
                    words[blockOffset + nibble] = value;
                }
            }
        }
        System.arraycopy(roots, 0, words, ROOT_TABLE_OFFSET, roots.length);
        return words;
    }

    private static int[] checkedRootCopy(int[] roots) {
        if (roots == null || roots.length != ROOT_WORD_COUNT) {
            throw new IllegalArgumentException("Path sampler requires exactly " + ROOT_WORD_COUNT
                    + " randomization roots");
        }
        return Arrays.copyOf(roots, ROOT_WORD_COUNT);
    }
}
