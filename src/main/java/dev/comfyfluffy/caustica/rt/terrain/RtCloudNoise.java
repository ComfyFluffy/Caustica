package dev.comfyfluffy.caustica.rt.terrain;

import java.nio.ByteBuffer;

import org.lwjgl.system.MemoryUtil;

/**
 * Bakes the cloud shape field into a tiling 3D texture.
 *
 * <h2>Why</h2>
 * {@code clouds.slang} evaluated its density function roughly 64 view samples x 6 light samples per
 * pixel, and every evaluation ran three or four octaves of hashed value noise — eight lattice hashes,
 * a quintic fade and seven lerps per octave. That is hundreds of ALU operations per sample for a field
 * that never changes. Precomputing it turns the whole thing into one hardware-filtered fetch.
 *
 * <h2>The noise must tile, and that constrains the generator</h2>
 * The shader samples this with a repeating address mode over a domain scaled by cloud feature size, so
 * the field has to be seamless across every face. That rules out hashing world coordinates directly:
 * the lattice has to wrap modulo the texture size at every octave. {@link #hash} therefore takes
 * already-wrapped integer coordinates, and each octave wraps at its own frequency.
 *
 * <p>The consequence is that the sky repeats every {@code feature-size} blocks. At the default 140
 * that is a 140-block period, which is invisible from the ground because the deck is only ~90 blocks
 * thick and the horizon cuts it off long before a repeat becomes legible. It would be visible from
 * far above the deck looking down, which is not a view Minecraft offers.
 *
 * <h2>Channel layout</h2>
 * R holds the 4-octave base shape, G the 3-octave erosion detail — the same two fields the analytic
 * version computed, at the same relative frequencies, so the shader's coverage remap and erosion maths
 * are unchanged. Two 8-bit channels: the field feeds a smoothstep remap that quantisation cannot
 * survive being visible through, and 8 bits keeps the whole thing at 4 MB.
 */
public final class RtCloudNoise {
    /** 128^3 x RG8 = 4 MB. Doubling to 256 costs 32 MB for detail the coverage remap discards. */
    public static final int SIZE = 128;
    public static final int BYTES_PER_TEXEL = 2;

    private RtCloudNoise() {
    }

    /**
     * @return a direct buffer the caller must free with {@link MemoryUtil#memFree}, holding
     *         SIZE^3 RG8 texels in depth-major order
     */
    public static ByteBuffer generate() {
        ByteBuffer out = MemoryUtil.memAlloc(SIZE * SIZE * SIZE * BYTES_PER_TEXEL);
        for (int z = 0; z < SIZE; z++) {
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    float u = (float) x / SIZE;
                    float v = (float) y / SIZE;
                    float w = (float) z / SIZE;
                    // Base starts at 4 periods across the texture rather than 1: a single period per
                    // axis gives one blob per tile, which reads as a repeating pattern the moment two
                    // tiles are visible at once.
                    float base = fbm(u, v, w, 4, 4);
                    float detail = fbm(u, v, w, 16, 3);
                    int index = ((z * SIZE + y) * SIZE + x) * BYTES_PER_TEXEL;
                    out.put(index, (byte) Math.round(clamp01(base) * 255.0f));
                    out.put(index + 1, (byte) Math.round(clamp01(detail) * 255.0f));
                }
            }
        }
        return out;
    }

    /** Octave sum. Lacunarity 2 is required here — a non-integer ratio would break tiling. */
    private static float fbm(float u, float v, float w, int baseFrequency, int octaves) {
        float sum = 0f;
        float amplitude = 0.5f;
        float normalization = 0f;
        int frequency = baseFrequency;
        for (int i = 0; i < octaves; i++) {
            sum += amplitude * periodicValueNoise(u, v, w, frequency);
            normalization += amplitude;
            frequency *= 2;
            amplitude *= 0.5f;
        }
        return sum / Math.max(normalization, 1e-6f);
    }

    /** Value noise on a lattice that wraps at {@code frequency}, so the result is seamless. */
    private static float periodicValueNoise(float u, float v, float w, int frequency) {
        float x = u * frequency;
        float y = v * frequency;
        float z = w * frequency;
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        int zi = (int) Math.floor(z);
        float xf = x - xi;
        float yf = y - yi;
        float zf = z - zi;
        // Quintic fade: C2 continuous, so summed octaves show no lattice creases where they align.
        float fx = fade(xf);
        float fy = fade(yf);
        float fz = fade(zf);
        int x0 = Math.floorMod(xi, frequency);
        int y0 = Math.floorMod(yi, frequency);
        int z0 = Math.floorMod(zi, frequency);
        int x1 = Math.floorMod(xi + 1, frequency);
        int y1 = Math.floorMod(yi + 1, frequency);
        int z1 = Math.floorMod(zi + 1, frequency);
        float n000 = hash(x0, y0, z0);
        float n100 = hash(x1, y0, z0);
        float n010 = hash(x0, y1, z0);
        float n110 = hash(x1, y1, z0);
        float n001 = hash(x0, y0, z1);
        float n101 = hash(x1, y0, z1);
        float n011 = hash(x0, y1, z1);
        float n111 = hash(x1, y1, z1);
        return lerp(lerp(lerp(n000, n100, fx), lerp(n010, n110, fx), fy),
                lerp(lerp(n001, n101, fx), lerp(n011, n111, fx), fy), fz);
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    /** Integer hash to [0,1). Wang-style mix; only needs to decorrelate a lattice, not pass SmallCrush. */
    private static float hash(int x, int y, int z) {
        int h = x * 374761393 + y * 668265263 + z * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h >>> 8) * (1.0f / 16777216.0f);
    }
}
