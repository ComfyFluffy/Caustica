// Participating media: the tint-to-extinction mappings and the depth-3 medium stack the dielectric
// interface pushes and pops. Depends on core only.

// Per-channel Beer–Lambert extinction from a water body's biome tint (carried in the payload's albedo view
// for water hits, or worldPush.waterParams.xyz for the camera's own biome when starting submerged). A blue
// ocean tint (low red) absorbs red fastest → bluer with depth; swamp green-brown shifts the hue. The floor
// keeps even a white-tinted body very slightly absorbing so deep water never reads as clear vacuum.

import world_common;
import world_core;

public static const float WATER_DENSITY = 0.1;
public static const float3 WATER_ABSORB_FLOOR = float3(0.015, 0.010, 0.008);
public float3 waterExtinction(float3 tint) {
    return WATER_ABSORB_FLOOR + WATER_DENSITY * (float3(1.0, 1.0, 1.0) - clamp(tint, 0.0, 1.0));
}

// A non-water dielectric's tint is a filter over one block of travel rather than a biome water colour, so
// it maps to extinction by inverting Beer-Lambert over that reference distance: transmittance t after one
// block means sigma = -ln(t). `transmission` scales that transmittance, so it keeps the sense the
// material's authored factor has everywhere else — 1 passes the tint through unchanged, 0 is opaque.
// Clamped away from zero so a fully saturated texel stays finite instead of producing an infinite sigma.
public static const float VOLUME_TINT_REFERENCE_DISTANCE = 1.0; // blocks
public float3 volumeExtinction(float3 tint, float transmission) {
    float3 blockTransmittance = clamp(clamp(tint, 0.0, 1.0) * clamp(transmission, 0.0, 1.0),
            1.0e-3, 1.0);
    return -log(blockTransmittance) / VOLUME_TINT_REFERENCE_DISTANCE;
}

// ---- Participating medium the path is currently inside.
//
// Refraction needs the RELATIVE index across an interface, so the renderer has to know what it is
// travelling through, not just what it is hitting. `ior` drives Snell/Fresnel; `extinction` drives the
// per-segment Beer-Lambert attenuation.
//
// The stack is depth 3 (current + the two it will return to) held in named fields, NOT an array. A
// dynamically indexed local array lands in scratch memory, and this raygen is already register-bound —
// paying an occupancy hit for nesting the game rarely produces would be a bad trade. Because `entering`
// is re-derived per face from geometry rather than toggled, the path re-synchronises at the next
// crossing instead of staying corrupted.
//
// Exits pair by identity (MEDIUM_ID_*, world_common) rather than by position, so a non-nested overlap
// — enter ice, enter glass, exit ice, exit glass — recovers the true surrounding medium instead of
// popping the wrong layer. MediumStackReferenceModelExhaustiveTest holds the executable Java reference
// model of these semantics; a change to either side must land in both. Air is the bottom sentinel and
// is never pushed, so every stack keeps a non-air prefix.

public struct Medium {
    public float ior;
    public float3 extinction;
    public uint mediumId; // canonical 20-bit identity (MEDIUM_ID_*)
};

// Water-specific behaviour — the wave-refraction caustic on submerged receivers, the water miss guard —
// keys off the identity; nothing else about a medium is water-specific.
public bool mediumIsWater(Medium m) {
    return m.mediumId == MEDIUM_ID_WATER;
}

public struct MediumStack {
    public Medium current;
    public Medium parent1;
    public Medium parent2;
};

public Medium airMedium() {
    Medium m;
    m.ior = 1.0;
    m.extinction = float3(0.0, 0.0, 0.0);
    m.mediumId = MEDIUM_ID_AIR;
    return m;
}

public MediumStack makeMediumStack(Medium start) {
    MediumStack s;
    s.current = start;
    s.parent1 = airMedium();
    s.parent2 = airMedium();
    return s;
}

// False when the entered medium cannot be tracked: the stack already holds three real layers, or the
// id is the air sentinel. The caller must fail that continuation closed rather than corrupt a layer.
public bool mediumPush(inout MediumStack stack, Medium entered) {
    if (entered.mediumId == MEDIUM_ID_AIR || stack.parent2.mediumId != MEDIUM_ID_AIR) {
        return false;
    }
    stack.parent2 = stack.parent1;
    stack.parent1 = stack.current;
    stack.current = entered;
    return true;
}

public static const uint MEDIUM_EXIT_CURRENT = 0u;
public static const uint MEDIUM_EXIT_PARENT1 = 1u;
public static const uint MEDIUM_EXIT_PARENT2 = 2u;
public static const uint MEDIUM_EXIT_NO_MATCH = 3u;

// Nearest identity match from the top down, without mutating: a current-layer exit still needs
// parent1's IOR for Fresnel and refraction before the caller commits the removal.
public uint mediumResolveExit(MediumStack stack, uint exitingMediumId) {
    if (exitingMediumId == MEDIUM_ID_AIR) return MEDIUM_EXIT_NO_MATCH;
    if (stack.current.mediumId == exitingMediumId) return MEDIUM_EXIT_CURRENT;
    if (stack.parent1.mediumId == exitingMediumId) return MEDIUM_EXIT_PARENT1;
    if (stack.parent2.mediumId == exitingMediumId) return MEDIUM_EXIT_PARENT2;
    return MEDIUM_EXIT_NO_MATCH;
}

public bool mediumExitIsDeep(uint match) {
    return match == MEDIUM_EXIT_PARENT1 || match == MEDIUM_EXIT_PARENT2;
}

// Removes exactly the matched layer, keeping every nearer one; no-match leaves the stack untouched.
public void mediumCommitExit(inout MediumStack stack, uint match) {
    if (match == MEDIUM_EXIT_CURRENT) {
        stack.current = stack.parent1;
        stack.parent1 = stack.parent2;
        stack.parent2 = airMedium();
    } else if (match == MEDIUM_EXIT_PARENT1) {
        stack.parent1 = stack.parent2;
        stack.parent2 = airMedium();
    } else if (match == MEDIUM_EXIT_PARENT2) {
        stack.parent2 = airMedium();
    }
}

// Water's tint is a biome colour whose absorption is calibrated per block of depth; any other volume
// dielectric's tint is a filter over a reference block of travel. Both end up as per-channel extinction.
public Medium makeDielectricMedium(uint mediumId, float ior, float3 tint, float transmission) {
    Medium m;
    m.ior = ior;
    m.extinction = mediumId == MEDIUM_ID_WATER
            ? waterExtinction(tint) : volumeExtinction(tint, transmission);
    m.mediumId = mediumId;
    return m;
}
