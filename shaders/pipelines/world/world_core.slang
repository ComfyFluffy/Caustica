// Bindings, per-frame push state, the ray payload, and the constants every pass shares.
// Import first: everything below depends on pc, worldPush and payload.

import world_common;
import sky;
import bindings;

// Per-frame push data loaded once from the BDA buffer at the top of main() (same pattern as the GLSL
// `worldPush` global). Layout constants generated from this module's SPIR-V — see world_common.WorldPush.
public static WorldPush worldPush = {};

// The radiance payload remains module-level so tracePath and its guide helpers can share it across
// HitObject trace/invoke calls, mirroring the GLSL rayPayloadEXT global.
public static Payload payload = {};

public struct VisibilityResult {
    public float3 transmittance;
    public float waterHitT;
};



// Views onto the module-level payload's hand-packed surface words (see Payload.surface0..2). Hit shaders
// take their payload as a parameter and call the world_common forms directly; these exist so raygen-side
// code reads the same as the payloadRoughness()/payloadEmission() accessors below it.
public float3 payloadAlbedo() { return unpackAlbedo(payload); }
public float3 payloadNormal() { return unpackNormal(payload); }
/** Sky radiance on a miss — absolute scene units, full fp32. Only meaningful when hitT < 0. */
public float3 payloadSky() { return unpackSky(payload); }

public uint payloadMaterial() { return payload.flags & PAYLOAD_MATERIAL_MASK; }
// Set by world.rchit on any dielectric hit (see PAYLOAD_DIELECTRIC_ENTERING) — whether the ray was
// travelling into the volume (vs. out of it) at this hit's face.
public bool payloadDielectricEntering() { return (payload.flags & PAYLOAD_DIELECTRIC_ENTERING) != 0u; }
public bool payloadEmitterInList() { return (payload.flags & PAYLOAD_EMITTER_IN_LIST) != 0u; }
// Set by world.rchit on an entity hit whose EntityGeom carries ENTITY_GEOM_LOCAL_VIEW — the hit surface
// belongs to the local-view representation, so rays leaving it take the local-view secondary domain.
public bool payloadSurfaceLocalView() { return (payload.flags & PAYLOAD_SURFACE_LOCAL_VIEW) != 0u; }
public bool payloadSurfaceIce() { return (payload.flags & PAYLOAD_SURFACE_ICE) != 0u; }
// The 20-bit canonical identity of the medium behind the current hit's dielectric face (see
// PAYLOAD_MEDIUM_ID_SHIFT); MEDIUM_ID_AIR on non-dielectric hits.
public uint payloadMediumId() { return (payload.flags & PAYLOAD_MEDIUM_ID_MASK) >> PAYLOAD_MEDIUM_ID_SHIFT; }
// LINEAR roughness, i.e. GGX alpha directly — NOT perceptual roughness. This is the one convention used
// end to end: LabPBR defines roughness = (1 - perceptualSmoothness)^2 and RtLabPbr.decode stores exactly
// that, RtMaterials.Profile carries the same units, and DLSS-RR wants linear roughness in its guide. So
// nothing squares it: ggxD/ggxG1/sampleGGXVNDF/rrSpecularAlbedo all take this value as alpha as-is, and
// any threshold compared against it is an alpha threshold.
public float payloadRoughness() { return unpackHalf2(payload.roughMetal).x; }
public float payloadMetalness() { return unpackHalf2(payload.roughMetal).y; }
public float payloadEmission() { return unpackHalf2(payload.emissionSss).x; }
public float payloadSss() { return unpackHalf2(payload.emissionSss).y; }
public float payloadIor() { return unpackHalf2(payload.iorTransmission).x; }
public float payloadTransmission() { return unpackHalf2(payload.iorTransmission).y; }

public static const float PI = 3.14159265359;
public static const float INV_PI = 0.31830988618;

// The sky (atmosphere LUT + sun/moon discs + stars) is evaluated in world.rmiss. On a miss, raygen reads
// payloadSky() and accumulates that radiance.
//
// The single directional NEE light — whichever of the sun and moon actually delivers more light through
// the atmosphere — is DERIVED, not pushed: world.rgen fills this in from sky.dominantCelestialLight() once
// per dispatch, using the same transmittance LUT world.rmiss tints the visible discs with. That shared
// source is what guarantees the light on terrain and the sky's own sunset can never disagree; the previous
// arrangement kept a hand-maintained Java port of the atmosphere march for exactly this value.
// Illuminance here is ACEScg (the transport basis), converted once where it is assigned.
public static CelestialLight celestialLight = {};
public static const float SURF_BIAS = 0.005;  // offset secondary-ray origins along the normal (anti-acne)
public static const float RAY_TMIN = 0.001;   // tiny tmin: the normal offset already clears the surface

// Water is a smooth dielectric. IOR 1.333; absorption is per-block Beer-Lambert extinction.
public static const float WATER_IOR = 1.333;
public static const float3 SKY_SPEC_ALBEDO = float3(0.5, 0.5, 0.5); // RR guide default for sky pixels.
public static const float3 SKY_DIFF_ALBEDO = float3(0.5, 0.5, 0.5);

// Transmitted-ray origin offset when crossing a face from the TRANSLUCENT terrain layer (glass, ice).
// Must be SMALLER than the mesher-side TRANSLUCENT_INSET (RtTerrainMesher, 2e-4 blocks): those quads are
// recessed into their block by that inset, so a face touching a slab/stair leaves a tiny gap before the
// neighbour's surface. A large bias (SURF_BIAS) would restart the ray past that neighbour and see
// through it; this lands the ray inside the gap so the neighbour is still hit, while still clearing the
// surface it left to avoid self-intersection. Water is meshed by RtFluidMesher with no inset, so it uses
// the ordinary bias.
public static const float INSET_TRANSMIT_BIAS = 1.0e-4;

// Ray-cone texture LOD: raygen tracks a one-pixel footprint along the path, and closest-hit converts it
// to texture mip levels from the local UV/world-space scale. Diffuse/glossy bounces widen the cone so
// indirect texture fetches don't keep sampling mip 0 through broad lobes.
public static const float RAY_CONE_MIN_SPREAD = 1.0e-5;
public static const float RAY_CONE_MIN_WIDTH = 1.0e-5;
public static const float RAY_CONE_DIFFUSE_SPREAD = 0.25;
public static const float RAY_CONE_GLOSSY_SPREAD_SCALE = 0.35;

// Stored roughness IS alpha (see payloadRoughness), so these compare against it directly.
public static const float MIRROR_ALPHA_MAX = 4.0e-4; // LabPBR perceptual smoothness >= 0.98
// Above this the specular lobe is too broad for a single mirrored reprojection to describe, so the
// reflection MV is left at zero and RR falls back to the ordinary MV.
public static const float SPEC_MOTION_ALPHA_MAX = 0.25; // perceptual smoothness <= 0.5

public bool isDeltaAlpha(float roughness) {
    return clamp(roughness, 0.0, 1.0) <= MIRROR_ALPHA_MAX;
}

// Continuations one sample may carry: the camera segment, plus the reflection branch a primary
// dielectric splits off. Raising this needs a real queue, which is what the continuation buffer is for.
public static const uint MAX_PATH_SEGMENTS = 2u;
