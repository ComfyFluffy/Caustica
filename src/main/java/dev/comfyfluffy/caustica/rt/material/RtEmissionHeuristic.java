package dev.comfyfluffy.caustica.rt.material;

/** Pure CPU compiler for state-gated, albedo-derived emission masks. */
final class RtEmissionHeuristic {
    private static final float DARK_FLOOR = 0.1f;
    private static final float LUMINANCE_POWER = 3.0f;

    private RtEmissionHeuristic() {
    }

    record Result(float[] mask, RtMaterialDesc.EmissionSummary summary) {
    }

    /**
     * The RGB input is linear and alpha is coverage. Eligibility is deliberately external: callers must
     * only invoke this for a sprite associated with an emitting state or an explicit material override.
     */
    static Result compile(float[] linearRgba) {
        if (linearRgba.length == 0 || (linearRgba.length & 3) != 0) {
            throw new IllegalArgumentException("Emission input must contain interleaved RGBA texels");
        }
        int pixels = linearRgba.length / 4;
        float[] mask = new float[pixels];
        for (int pixel = 0; pixel < pixels; pixel++) {
            int i = pixel * 4;
            float value = clamp01(luminance(linearRgba[i], linearRgba[i + 1], linearRgba[i + 2]));
            if (value >= DARK_FLOOR) {
                float strength = (float) Math.pow(value, LUMINANCE_POWER);
                mask[pixel] = clamp01(strength * linearRgba[i + 3]);
            }
        }
        return new Result(mask, summarize(linearRgba, mask));
    }

    static RtMaterialDesc.EmissionSummary summarize(float[] linearRgba, float[] mask) {
        int pixels = mask.length;
        if (linearRgba.length != pixels * 4) throw new IllegalArgumentException("Emission mask size mismatch");
        double r = 0.0, g = 0.0, b = 0.0, energy = 0.0;
        int covered = 0;
        for (int pixel = 0; pixel < pixels; pixel++) {
            int i = pixel * 4;
            float weight = clamp01(mask[pixel]);
            float er = linearRgba[i] * weight;
            float eg = linearRgba[i + 1] * weight;
            float eb = linearRgba[i + 2] * weight;
            r += er;
            g += eg;
            b += eb;
            energy += luminance(er, eg, eb);
            if (weight > 1.0f / 255.0f) covered++;
        }
        if (energy <= 0.0) return RtMaterialDesc.EmissionSummary.NONE;
        float inv = 1.0f / pixels;
        // integratedLuminance is the integral over the unit texture domain, not a resolution-dependent sum.
        return new RtMaterialDesc.EmissionSummary((float) r * inv, (float) g * inv, (float) b * inv,
                (float) energy * inv, covered * inv);
    }

    private static float luminance(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
