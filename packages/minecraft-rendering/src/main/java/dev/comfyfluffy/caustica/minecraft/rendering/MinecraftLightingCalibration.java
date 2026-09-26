package dev.comfyfluffy.caustica.minecraft.rendering;

/**
 * Immutable Minecraft photometric calibration supplied by runtime composition. Moonlight is a sky option
 * ({@code SkyLutPass.MOON_ILLUMINANCE_LUX}) rather than calibration.
 */
public record MinecraftLightingCalibration(float sunIlluminanceLux,
                                            float blockEmissionLuminanceCdM2,
                                            float nightAirglowLuminanceCdM2,
                                            float starLuminanceCdM2,
                                            float moonPhaseFixedFraction) {
    public MinecraftLightingCalibration {
        positive(sunIlluminanceLux, "sunIlluminanceLux");
        positive(blockEmissionLuminanceCdM2, "blockEmissionLuminanceCdM2");
        nonNegative(nightAirglowLuminanceCdM2, "nightAirglowLuminanceCdM2");
        nonNegative(starLuminanceCdM2, "starLuminanceCdM2");
        nonNegative(moonPhaseFixedFraction, "moonPhaseFixedFraction");
        if (moonPhaseFixedFraction > 1.0f) throw new IllegalArgumentException(
                "moonPhaseFixedFraction must be in [0,1]");
    }

    private static void positive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0.0f) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void nonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f) throw new IllegalArgumentException(name + " must be non-negative");
    }
}
