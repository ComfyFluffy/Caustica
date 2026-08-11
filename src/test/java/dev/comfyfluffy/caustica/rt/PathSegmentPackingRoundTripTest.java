package dev.comfyfluffy.caustica.rt;

import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java replica of segment.slang's PackedPathSegment layout for everything the 64-byte record carries
 * beyond raw geometry: the three-layer medium stack (RGB9E5 extinction, fp16 IOR and u20 identity per
 * layer) and the pathFlags word (bounce in bits 0..3, showCelestial at 8, the two-bit secondary domain
 * at 9..10, camera-transmission continuity at 11). The shader and this replica follow one layout
 * definition; a change to either must land in both.
 */
final class PathSegmentPackingRoundTripTest {

    private static final int DOMAIN_WORLD = 0;
    private static final int DOMAIN_LOCAL_VIEW = 1;
    private static final int DOMAIN_REFLECTION = 2;

    private static final int PATH_BOUNCE_MASK = 15;
    private static final int PATH_SHOW_CELESTIAL = 1 << 8;
    private static final int PATH_SECONDARY_DOMAIN_SHIFT = 9;
    private static final int PATH_SECONDARY_DOMAIN_MASK = 3 << PATH_SECONDARY_DOMAIN_SHIFT;
    private static final int PATH_CAMERA_TRANSMISSION_CONTINUITY = 1 << 11;

    /** float3 ro (12 bytes) followed by this many uint lanes — the std430 stride the queue uses. */
    private static final int PACKED_UINT_LANES = 13;

    @Test
    void strideAndFlagBitsMatchTheFrozenLayout() {
        assertEquals(64, 12 + 4 * PACKED_UINT_LANES);
        assertEquals(0, PATH_BOUNCE_MASK & PATH_SHOW_CELESTIAL);
        assertEquals(0, PATH_BOUNCE_MASK & PATH_SECONDARY_DOMAIN_MASK);
        assertEquals(0, PATH_BOUNCE_MASK & PATH_CAMERA_TRANSMISSION_CONTINUITY);
        assertEquals(0, PATH_SHOW_CELESTIAL & PATH_SECONDARY_DOMAIN_MASK);
        assertEquals(0, PATH_SHOW_CELESTIAL & PATH_CAMERA_TRANSMISSION_CONTINUITY);
        assertEquals(0, PATH_SECONDARY_DOMAIN_MASK & PATH_CAMERA_TRANSMISSION_CONTINUITY);
    }

    private record Layer(float ior, float[] extinction, int mediumId) {}

    private record Stack(Layer current, Layer parent1, Layer parent2) {}

    private record Segment(int bounce, boolean showCelestial, int secondaryDomain,
                           boolean cameraTransmissionContinuity, Stack stack) {}

    private record Packed(int currentExtinction, int parent1Extinction, int parent2Extinction,
                          int mediumIors01, int mediumIor2, int mediumIds01, int mediumId2,
                          int pathFlags) {}

    private static int normalizeDomain(int domain) {
        return domain == DOMAIN_LOCAL_VIEW || domain == DOMAIN_REFLECTION ? domain : DOMAIN_WORLD;
    }

    private static Packed pack(Segment s) {
        int domain = s.bounce() == 0 ? DOMAIN_WORLD : normalizeDomain(s.secondaryDomain());
        int pathFlags = (s.bounce() & PATH_BOUNCE_MASK)
                | (s.showCelestial() ? PATH_SHOW_CELESTIAL : 0)
                | (domain << PATH_SECONDARY_DOMAIN_SHIFT)
                | (s.cameraTransmissionContinuity() ? PATH_CAMERA_TRANSMISSION_CONTINUITY : 0);
        Stack stack = s.stack();
        return new Packed(
                packRgb9e5(stack.current().extinction()),
                packRgb9e5(stack.parent1().extinction()),
                packRgb9e5(stack.parent2().extinction()),
                packHalf2(stack.current().ior(), stack.parent1().ior()),
                packHalf2(stack.parent2().ior(), 0.0f),
                (stack.current().mediumId() & 0xFFFFF)
                        | ((stack.parent1().mediumId() & 0xFFF) << 20),
                ((stack.parent1().mediumId() >>> 12) & 0xFF)
                        | ((stack.parent2().mediumId() & 0xFFFFF) << 8),
                pathFlags);
    }

    private static Segment unpack(Packed p) {
        Layer current = new Layer(halfLow(p.mediumIors01()), unpackRgb9e5(p.currentExtinction()),
                p.mediumIds01() & 0xFFFFF);
        Layer parent1 = new Layer(halfHigh(p.mediumIors01()), unpackRgb9e5(p.parent1Extinction()),
                (p.mediumIds01() >>> 20) | ((p.mediumId2() & 0xFF) << 12));
        Layer parent2 = new Layer(halfLow(p.mediumIor2()), unpackRgb9e5(p.parent2Extinction()),
                (p.mediumId2() >>> 8) & 0xFFFFF);
        return new Segment(p.pathFlags() & PATH_BOUNCE_MASK,
                (p.pathFlags() & PATH_SHOW_CELESTIAL) != 0,
                (p.pathFlags() & PATH_SECONDARY_DOMAIN_MASK) >>> PATH_SECONDARY_DOMAIN_SHIFT,
                (p.pathFlags() & PATH_CAMERA_TRANSMISSION_CONTINUITY) != 0,
                new Stack(current, parent1, parent2));
    }

    // ---- the quantizers the record uses, replicated from segment.slang ----

    private static int packHalf2(float x, float y) {
        return (Float.floatToFloat16(x) & 0xFFFF) | (Float.floatToFloat16(y) << 16);
    }

    private static float halfLow(int packed) {
        return Float.float16ToFloat((short) (packed & 0xFFFF));
    }

    private static float halfHigh(int packed) {
        return Float.float16ToFloat((short) (packed >>> 16));
    }

    private static int packRgb9e5(float[] v) {
        float r = clampRgb9e5(v[0]);
        float g = clampRgb9e5(v[1]);
        float b = clampRgb9e5(v[2]);
        float maxChannel = Math.max(r, Math.max(g, b));
        int exponent = maxChannel < Math.scalb(1.0f, -16)
                ? 0 : (int) Math.floor(Math.log(maxChannel) / Math.log(2.0)) + 16;
        exponent = Math.min(exponent, 31);
        float scale = Math.scalb(1.0f, exponent - 24);
        int maxMantissa = (int) Math.floor(maxChannel / scale + 0.5f);
        if (maxMantissa == 512 && exponent < 31) {
            exponent++;
            scale *= 2.0f;
        }
        int mr = Math.min((int) Math.floor(r / scale + 0.5f), 511);
        int mg = Math.min((int) Math.floor(g / scale + 0.5f), 511);
        int mb = Math.min((int) Math.floor(b / scale + 0.5f), 511);
        return mr | (mg << 9) | (mb << 18) | (exponent << 27);
    }

    private static float[] unpackRgb9e5(int p) {
        float scale = Math.scalb(1.0f, (p >>> 27) - 24);
        return new float[]{(p & 0x1FF) * scale, ((p >>> 9) & 0x1FF) * scale, ((p >>> 18) & 0x1FF) * scale};
    }

    private static float clampRgb9e5(float v) {
        return Math.max(0.0f, Math.min(v, 65408.0f));
    }

    @Test
    void roundTripPreservesDomainContinuityBounceAndStack() {
        Random random = new Random(0x5eedcafe);
        int[] ids = {0, 1, 2, 7, 4095, 4096, 65534, 65535, 65536, 1000000, 1048573, 1048574};
        for (int bounce : new int[]{0, 1, 2, 3, 8, 15}) {
            for (int domain : new int[]{DOMAIN_WORLD, DOMAIN_LOCAL_VIEW, DOMAIN_REFLECTION}) {
                for (boolean celestial : new boolean[]{false, true}) {
                    for (boolean continuity : new boolean[]{false, true}) {
                        Segment segment = new Segment(bounce, celestial, domain, continuity,
                                randomStack(random, ids));
                        Segment back = unpack(pack(segment));

                        assertEquals(bounce, back.bounce());
                        assertEquals(celestial, back.showCelestial());
                        assertEquals(continuity, back.cameraTransmissionContinuity(),
                                "continuity survives every bounce, including camera replays");
                        assertEquals(bounce == 0 ? DOMAIN_WORLD : domain, back.secondaryDomain(),
                                "camera replays normalize to WORLD, everything else round-trips");
                        assertStackRoundTrip(segment.stack(), back.stack());
                    }
                }
            }
        }
    }

    private static void assertStackRoundTrip(Stack in, Stack out) {
        assertLayerRoundTrip(in.current(), out.current());
        assertLayerRoundTrip(in.parent1(), out.parent1());
        assertLayerRoundTrip(in.parent2(), out.parent2());
    }

    private static void assertLayerRoundTrip(Layer in, Layer out) {
        assertEquals(in.mediumId(), out.mediumId(), "identities round-trip exactly");
        assertEquals(Float.float16ToFloat(Float.floatToFloat16(in.ior())), out.ior(),
                "IOR round-trips through fp16 exactly");
        float[] quantized = unpackRgb9e5(packRgb9e5(in.extinction()));
        for (int c = 0; c < 3; c++) {
            assertEquals(quantized[c], out.extinction()[c],
                    "extinction round-trips to the reference quantizer's decode");
        }
    }

    private static Stack randomStack(Random random, int[] ids) {
        return new Stack(randomLayer(random, ids), randomLayer(random, ids), randomLayer(random, ids));
    }

    private static Layer randomLayer(Random random, int[] ids) {
        float[] extinction = {
                random.nextFloat() * 2.0f, random.nextFloat() * 2.0f, random.nextFloat() * 2.0f};
        return new Layer(1.0f + random.nextFloat(), extinction, ids[random.nextInt(ids.length)]);
    }

    @Test
    void theFourthDomainEncodingIsNeverPacked() {
        Random random = new Random(0xd00d1e);
        for (int raw = 0; raw < 8; raw++) {
            for (int bounce : new int[]{0, 1, 15}) {
                Segment segment = new Segment(bounce, false, raw, false,
                        randomStack(random, new int[]{0}));
                int packedDomain = (pack(segment).pathFlags() & PATH_SECONDARY_DOMAIN_MASK)
                        >>> PATH_SECONDARY_DOMAIN_SHIFT;
                assertTrue(packedDomain <= DOMAIN_REFLECTION,
                        "raw domain " + raw + " must pack to a legal encoding, got " + packedDomain);
            }
        }
    }

    @Test
    void layerOrderIsPreserved() {
        Stack stack = new Stack(
                new Layer(1.33f, new float[]{0.1f, 0.2f, 0.3f}, 1),
                new Layer(1.31f, new float[]{0.4f, 0.5f, 0.6f}, 2),
                new Layer(1.0f, new float[]{0.0f, 0.0f, 0.0f}, 0));
        Stack back = unpack(pack(new Segment(3, true, DOMAIN_LOCAL_VIEW, true, stack))).stack();
        assertEquals(1, back.current().mediumId());
        assertEquals(2, back.parent1().mediumId());
        assertEquals(0, back.parent2().mediumId());
    }
}
