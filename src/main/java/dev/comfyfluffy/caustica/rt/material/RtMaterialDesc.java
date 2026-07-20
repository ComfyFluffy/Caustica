package dev.comfyfluffy.caustica.rt.material;

/** Immutable physical material description produced at the resource-epoch boundary. */
public record RtMaterialDesc(
        int model,
        Source source,
        int features,
        float roughness,
        float metalness,
        float ior,
        float transmission,
        EmissionSource emissionSource,
        float emissionStrength,
        EmissionSummary emissionSummary
) {
    public enum Source {
        OVERRIDE,
        LAB_PBR,
        HEURISTIC,
        NEUTRAL
    }

    public enum EmissionSource {
        NONE,
        OVERRIDE,
        LAB_PBR,
        HEURISTIC_MASK,
        STATE_UNIFORM
    }

    /** Normalized compiler output; per-primitive state light multiplies it when the source is state-gated. */
    public record EmissionSummary(float averageR, float averageG, float averageB,
                                  float integratedLuminance, float coverage) {
        public static final EmissionSummary NONE = new EmissionSummary(0, 0, 0, 0, 0);

        public boolean emissive() {
            return integratedLuminance > 0.0f && coverage > 0.0f;
        }
    }

    public RtMaterialDesc {
        if (source == null || emissionSource == null || emissionSummary == null) {
            throw new IllegalArgumentException("Material description enums/summary must be present");
        }
        if (!finite01(roughness) || !finite01(metalness) || !Float.isFinite(ior) || ior <= 0.0f
                || !finite01(transmission) || !Float.isFinite(emissionStrength) || emissionStrength < 0.0f) {
            throw new IllegalArgumentException("Invalid physical material parameters");
        }
    }

    private static boolean finite01(float value) {
        return Float.isFinite(value) && value >= 0.0f && value <= 1.0f;
    }
}
