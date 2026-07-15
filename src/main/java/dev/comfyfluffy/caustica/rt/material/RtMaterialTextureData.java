package dev.comfyfluffy.caustica.rt.material;

import java.util.ArrayList;
import java.util.List;

/** CPU-side canonical material texels and semantic mip reduction. All channels are physical values. */
final class RtMaterialTextureData {
    static final int CHANNELS = 4;

    private RtMaterialTextureData() {
    }

    record Level(int width, int height, float[] surface0, float[] normalAo, float[] surface1) {
        Level {
            int values = Math.multiplyExact(Math.multiplyExact(width, height), CHANNELS);
            if (width <= 0 || height <= 0 || surface0.length != values
                    || normalAo.length != values || surface1.length != values) {
                throw new IllegalArgumentException("Invalid canonical material level");
            }
        }
    }

    static List<Level> mipChain(Level base, int maxLevel) {
        List<Level> levels = new ArrayList<>(maxLevel + 1);
        levels.add(base);
        while (levels.size() <= maxLevel) {
            levels.add(reduce(levels.get(levels.size() - 1)));
        }
        return levels;
    }

    /**
     * Reduce already-decoded physical channels. Emission is energy-averaged, normals are averaged then
     * renormalized, and lost normal length raises roughness so distant normal detail does not become a
     * falsely smooth surface.
     */
    static Level reduce(Level src) {
        int width = Math.max(1, (src.width + 1) / 2);
        int height = Math.max(1, (src.height + 1) / 2);
        float[] surface0 = new float[width * height * CHANNELS];
        float[] normalAo = new float[surface0.length];
        float[] surface1 = new float[surface0.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float roughSq = 0.0f;
                float metal = 0.0f, emission = 0.0f, sss = 0.0f;
                float nx = 0.0f, ny = 0.0f, nz = 0.0f, ao = 0.0f, heightValue = 0.0f;
                float f0r = 0.0f, f0g = 0.0f, f0b = 0.0f, transmission = 0.0f;
                int samples = 0;
                for (int oy = 0; oy < 2; oy++) {
                    int sy = y * 2 + oy;
                    if (sy >= src.height) continue;
                    for (int ox = 0; ox < 2; ox++) {
                        int sx = x * 2 + ox;
                        if (sx >= src.width) continue;
                        int si = (sy * src.width + sx) * CHANNELS;
                        float rough = src.surface0[si];
                        roughSq += rough * rough;
                        metal += src.surface0[si + 1];
                        emission += src.surface0[si + 2];
                        sss += src.surface0[si + 3];

                        float tx = src.normalAo[si] * 2.0f - 1.0f;
                        float ty = src.normalAo[si + 1] * 2.0f - 1.0f;
                        float tz = (float) Math.sqrt(Math.max(0.0f, 1.0f - tx * tx - ty * ty));
                        nx += tx;
                        ny += ty;
                        nz += tz;
                        ao += src.normalAo[si + 2];
                        heightValue += src.normalAo[si + 3];

                        f0r += src.surface1[si];
                        f0g += src.surface1[si + 1];
                        f0b += src.surface1[si + 2];
                        transmission += src.surface1[si + 3];
                        samples++;
                    }
                }
                float inv = 1.0f / samples;
                nx *= inv;
                ny *= inv;
                nz *= inv;
                float normalLength = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (normalLength > 1.0e-6f) {
                    nx /= normalLength;
                    ny /= normalLength;
                } else {
                    nx = ny = 0.0f;
                }
                int di = (y * width + x) * CHANNELS;
                // Toksvig-style variance term. This is intentionally conservative and monotonic.
                surface0[di] = clamp01((float) Math.sqrt(Math.min(1.0f,
                        roughSq * inv + Math.max(0.0f, 1.0f - normalLength))));
                surface0[di + 1] = clamp01(metal * inv);
                surface0[di + 2] = clamp01(emission * inv);
                surface0[di + 3] = clamp01(sss * inv);
                normalAo[di] = clamp01(nx * 0.5f + 0.5f);
                normalAo[di + 1] = clamp01(ny * 0.5f + 0.5f);
                normalAo[di + 2] = clamp01(ao * inv);
                normalAo[di + 3] = clamp01(heightValue * inv);
                surface1[di] = clamp01(f0r * inv);
                surface1[di + 1] = clamp01(f0g * inv);
                surface1[di + 2] = clamp01(f0b * inv);
                surface1[di + 3] = clamp01(transmission * inv);
            }
        }
        return new Level(width, height, surface0, normalAo, surface1);
    }

    static int unorm8(float value) {
        return Math.round(clamp01(value) * 255.0f);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
