package dev.comfyfluffy.caustica.rt.material;

/**
 * CPU reference decoder for the LabPBR 1.3 specular texture. Runtime shading currently performs the
 * same decode in {@code world.rchit.slang}; keeping the source-format rules here gives the material
 * compiler and its tests one deterministic implementation to use when LabPBR is canonicalized before
 * upload.
 */
public final class RtLabPbr {
    private RtLabPbr() {
    }

    private static final float[][] METAL_N = {
            {2.9114f, 2.9497f, 2.5845f},
            {0.18299f, 0.42108f, 1.3734f},
            {1.3456f, 0.96521f, 0.61722f},
            {3.1071f, 3.1812f, 2.3230f},
            {0.27105f, 0.67693f, 1.3164f},
            {1.9100f, 1.8300f, 1.4400f},
            {2.3757f, 2.0847f, 1.8453f},
            {0.15943f, 0.14512f, 0.13547f}
    };
    private static final float[][] METAL_K = {
            {3.0893f, 2.9318f, 2.7670f},
            {3.4242f, 2.3459f, 1.7704f},
            {7.4746f, 6.3995f, 5.3031f},
            {3.3314f, 3.3291f, 3.1350f},
            {3.6092f, 2.6248f, 2.2921f},
            {3.5100f, 3.4000f, 3.1800f},
            {4.2655f, 3.7153f, 3.1365f},
            {3.9291f, 3.1900f, 2.3808f}
    };

    /** Decode normalized LabPBR channels and linear albedo into physical surface parameters. */
    public static Specular decodeSpec(float red, float green, float blue, float alpha,
                                      float albedoR, float albedoG, float albedoB) {
        float smoothness = clamp01(red);
        float roughness = (1.0f - smoothness) * (1.0f - smoothness);
        float g = clamp01(green) * 255.0f;
        float metalness;
        float f0r;
        float f0g;
        float f0b;
        if (g < 229.5f) {
            metalness = 0.0f;
            f0r = f0g = f0b = clamp01(green);
        } else if (g < 237.5f) {
            metalness = 1.0f;
            int metal = Math.round(g) - 230;
            f0r = metalF0(metal, 0);
            f0g = metalF0(metal, 1);
            f0b = metalF0(metal, 2);
        } else {
            metalness = 1.0f;
            f0r = clamp01(albedoR);
            f0g = clamp01(albedoG);
            f0b = clamp01(albedoB);
        }

        float a = clamp01(alpha) * 255.0f;
        float emission = a < 254.5f ? a / 254.0f : 0.0f;
        float b = clamp01(blue) * 255.0f;
        float sss = b > 64.5f ? (b - 65.0f) / 190.0f : 0.0f;
        return new Specular(roughness, metalness, f0r, f0g, f0b, emission, sss);
    }

    private static float metalF0(int metal, int channel) {
        float n = METAL_N[metal][channel];
        float k = METAL_K[metal][channel];
        float nm1 = n - 1.0f;
        float np1 = n + 1.0f;
        return (nm1 * nm1 + k * k) / (np1 * np1 + k * k);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    public record Specular(float roughness, float metalness,
                           float f0r, float f0g, float f0b,
                           float emission, float sss) {
    }
}
