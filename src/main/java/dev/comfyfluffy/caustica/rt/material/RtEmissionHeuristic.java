package dev.comfyfluffy.caustica.rt.material;

import java.util.Arrays;

/** Pure CPU compiler for state-gated, albedo-derived emission masks. */
final class RtEmissionHeuristic {
    private static final float DARK_CUTOFF = 0.025f;
    private static final float UNIFORM_RANGE = 0.10f;
    private static final float UNIFORM_FLOOR = 0.12f;

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
        float[] luminance = new float[pixels];
        float[] sorted = new float[pixels];
        for (int pixel = 0; pixel < pixels; pixel++) {
            int i = pixel * 4;
            float value = luminance(linearRgba[i], linearRgba[i + 1], linearRgba[i + 2]);
            luminance[pixel] = value;
            sorted[pixel] = value;
        }
        Arrays.sort(sorted);
        float p20 = percentile(sorted, 0.20f);
        float p50 = percentile(sorted, 0.50f);
        float p85 = percentile(sorted, 0.85f);
        float p95 = percentile(sorted, 0.95f);
        float[] mask = new float[pixels];
        if (p95 < DARK_CUTOFF) return new Result(mask, RtMaterialDesc.EmissionSummary.NONE);

        boolean uniformlyLuminous = p20 >= UNIFORM_FLOOR && p95 - p20 <= UNIFORM_RANGE;
        float low = p50 + 0.30f * Math.max(0.0f, p85 - p50);
        float high = Math.max(low + 0.025f, p95);
        for (int pixel = 0; pixel < pixels; pixel++) {
            int i = pixel * 4;
            float r = linearRgba[i];
            float g = linearRgba[i + 1];
            float b = linearRgba[i + 2];
            float alpha = clamp01(linearRgba[i + 3]);
            float brightest = Math.max(r, Math.max(g, b));
            float darkest = Math.min(r, Math.min(g, b));
            float saturation = brightest > 1.0e-5f ? (brightest - darkest) / brightest : 0.0f;
            float selected = smoothstep(low, high, luminance[pixel]);
            // Colored emissive details tend to carry more chroma than their substrate. The multiplier
            // only refines an already percentile-selected texel; it cannot make a dark texel emissive.
            selected *= 0.82f + 0.18f * saturation;
            if (uniformlyLuminous) {
                float floor = 0.55f + 0.35f * smoothstep(UNIFORM_FLOOR, 0.65f, luminance[pixel]);
                selected = Math.max(selected, floor);
            }
            mask[pixel] = clamp01(selected * alpha);
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

    private static float percentile(float[] sorted, float fraction) {
        if (sorted.length == 1) return sorted[0];
        float position = fraction * (sorted.length - 1);
        int lower = (int) position;
        int upper = Math.min(sorted.length - 1, lower + 1);
        float t = position - lower;
        return sorted[lower] + (sorted[upper] - sorted[lower]) * t;
    }

    private static float smoothstep(float low, float high, float value) {
        float t = clamp01((value - low) / Math.max(1.0e-6f, high - low));
        return t * t * (3.0f - 2.0f * t);
    }

    private static float luminance(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
