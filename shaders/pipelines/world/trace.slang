// Ray dispatch: SBT/cull constants, payload construction, ordinary/reordered radiance, guide probes,
// and shadow visibility. Depends on core.

// receiver billboards only. The first-person camera owner uses 0x01 (secondary only).

import world_common;
import world_core;
import bindings;

// Ray inclusion masks, not cull classes: each is ANDed against an instance's TLAS visibility mask and
// the instance is visible when the result is non-zero. The CULL_ prefix is historical.
public static const uint CULL_SECONDARY = 0x01u;
public static const uint CULL_PRIMARY = 0x02u;
public static const uint CULL_LOCAL_VIEW_SECONDARY = 0x04u;
// Reflection domain: continuation rays and specular probes leaving a local-view surface through a
// reflection-class lobe (interface Fresnel reflection, delta specular, glossy VNDF). Scene geometry's
// 0xFF instance mask contains this bit while neither player representation's mask does, so a local-view
// reflection can never contain the player.
public static const uint CULL_REFLECTION = 0x08u;

// The three legal values of a path record's packed secondary-domain field; the fourth two-bit encoding
// is never packed. WORLD doubles as the normalization target for camera-replayed records.
public static const uint SECONDARY_DOMAIN_WORLD = 0u;
public static const uint SECONDARY_DOMAIN_LOCAL_VIEW = 1u;
public static const uint SECONDARY_DOMAIN_REFLECTION = 2u;

// Scatter classes a continuation lobe falls into; the pass-through of an optically inert medium face
// counts as transmission.
public static const uint SCATTER_REFLECTION = 0u;
public static const uint SCATTER_TRANSMISSION = 1u;
public static const uint SCATTER_DIFFUSE = 2u;

public static const uint TERRAIN_BUCKETS = 4u;
public static const uint SBT_RADIANCE = 0u;
public static const uint SBT_SHADOW = TERRAIN_BUCKETS;
public static const uint SBT_STRIDE_BUCKET = 1u;
public static const uint MISS_RADIANCE = 0u;
public static const uint MISS_GUIDE = 1u;

public uint normalizeSecondaryDomain(uint domain) {
    return domain == SECONDARY_DOMAIN_LOCAL_VIEW || domain == SECONDARY_DOMAIN_REFLECTION
            ? domain : SECONDARY_DOMAIN_WORLD;
}

public uint secondaryMaskForDomain(uint domain) {
    uint normalized = normalizeSecondaryDomain(domain);
    if (normalized == SECONDARY_DOMAIN_LOCAL_VIEW) return CULL_LOCAL_VIEW_SECONDARY;
    if (normalized == SECONDARY_DOMAIN_REFLECTION) return CULL_REFLECTION;
    return CULL_SECONDARY;
}

// A continuation ray's domain, decided once per hit after the scatter lobe is chosen. An unbroken
// camera-visible transmission chain keeps the local-view domain across world interfaces (both player
// representations then resolve consistently through water and glass); otherwise world surfaces keep the
// world domain for every lobe, and a local-view surface sends reflection-class lobes into the
// reflection domain and every other lobe into its own local-view domain.
public uint continuationDomainForLobe(uint baseSurfaceDomain, uint scatterClass,
                                      bool cameraTransmissionContinuity) {
    if (scatterClass == SCATTER_TRANSMISSION && cameraTransmissionContinuity) {
        return SECONDARY_DOMAIN_LOCAL_VIEW;
    }
    if (baseSurfaceDomain == SECONDARY_DOMAIN_LOCAL_VIEW) {
        return scatterClass == SCATTER_REFLECTION
                ? SECONDARY_DOMAIN_REFLECTION : SECONDARY_DOMAIN_LOCAL_VIEW;
    }
    return SECONDARY_DOMAIN_WORLD;
}

// The base domain a secondary ray inherits from the surface it leaves. The flags word is an explicit
// parameter rather than a read of the global payload: the global is overwritten by the next trace, so a
// caller must name WHICH hit it derives from.
public uint secondaryDomainForSurface(uint payloadFlags) {
    return (payloadFlags & PAYLOAD_SURFACE_LOCAL_VIEW) != 0u
            ? SECONDARY_DOMAIN_LOCAL_VIEW : SECONDARY_DOMAIN_WORLD;
}

public uint secondaryMaskForSurface(uint payloadFlags) {
    return secondaryMaskForDomain(secondaryDomainForSurface(payloadFlags));
}

public RayDesc makeRay(float3 origin, float tmin, float3 dir, float tmax) {
    RayDesc r;
    r.Origin = origin;
    r.TMin = tmin;
    r.Direction = dir;
    r.TMax = tmax;
    return r;
}

public float3 offsetSurfaceOrigin(float3 position, float3 surfaceNormal,
                           float3 outgoingDirection, float bias) {
    float3 sideNormal = dot(surfaceNormal, outgoingDirection) >= 0.0
            ? surfaceNormal : -surfaceNormal;
    return position + sideNormal * bias;
}

// A radiance payload carrying only the two words the invoked hit/miss shader READS: `flags`
// (show-celestial gate, consumed by world.rmiss) and the packed ray cone (consumed by world.rchit for
// texture LOD). Every other member is an OUTPUT that world.rchit/world.rmiss write, so it carries no
// information into a trace and is initialized to a neutral value here.
public Payload makeRadiancePayload(uint flags, uint rayCone) {
    Payload p;
    // Zeroes the packed albedo/normal words directly: both the hit and the miss view read zero out of
    // them (half 0 and float 0 are the same bit pattern), so this is neutral for either case.
    p.surface0 = 0u;
    p.surface1 = 0u;
    p.surface2 = 0u;
    p.hitT = -1.0; // miss sentinel: world.rmiss writes only the surface words, so a miss leaves this negative
    p.motionPrev = half3(0.0h, 0.0h, 0.0h);
    p.f0 = half3(0.0h, 0.0h, 0.0h);
    p.flags = flags;
    p.roughMetal = 0u;
    p.emissionSss = 0u;
    p.iorTransmission = 0u;
    p.rayCone = rayCone;
    return p;
}

// Ordinary radiance trace for latency-sensitive, coherent work such as Pass A. It invokes hit/miss
// shaders directly and therefore requires no invocation-reorder capability.
public void traceRadiance(uint cullMask, float3 ro, float tmin, float3 rd, float tmax,
                    bool showCelestial, float rayConeWidth, float rayConeSpread) {
    payload = makeRadiancePayload(showCelestial ? PAYLOAD_SHOW_CELESTIAL : 0u,
            packHalf2(float2(rayConeWidth, rayConeSpread)));
    TraceRay(topLevelAS, RAY_FLAG_NONE, cullMask,
            SBT_RADIANCE, SBT_STRIDE_BUCKET, MISS_RADIANCE,
            makeRay(ro, tmin, rd, tmax), payload);
}

// Auxiliary RR probes deliberately use ordinary TraceRay. They are one-off, incoherent rays whose
// result is consumed immediately, so paying a reorder barrier and carrying guide state across it only
// adds latency. The dedicated miss record also avoids the atmosphere march: guides need hit-vs-miss,
// not sky radiance.
public void traceGuide(uint cullMask, float3 ro, float tmin, float3 rd, float tmax,
                float rayConeWidth, float rayConeSpread) {
    payload = makeRadiancePayload(0u, packHalf2(float2(rayConeWidth, rayConeSpread)));
    TraceRay(topLevelAS, RAY_FLAG_NONE, cullMask, SBT_RADIANCE, SBT_STRIDE_BUCKET,
            MISS_GUIDE, makeRay(ro, tmin, rd, tmax), payload);
}

public Payload makeShadowPayload() {
    Payload shadowPayload;
    shadowPayload.surface0 = 0u;
    shadowPayload.surface1 = 0u;
    shadowPayload.surface2 = 0u;
    // The albedo view is this ray's accumulated transmittance; the any-hits multiply into it.
    packAlbedo(shadowPayload, float3(1.0, 1.0, 1.0));
    shadowPayload.hitT = -1.0; // water-crossing sentinel, filled by the water any-hit
    shadowPayload.motionPrev = half3(0.0h, 0.0h, 0.0h);
    shadowPayload.f0 = half3(0.0h, 0.0h, 0.0h);
    // world_guide.rmiss clears this sentinel. An accepted opaque hit skips closest-hit and therefore
    // leaves it set, while ignored translucent/water hits continue until either a later opaque hit or miss.
    shadowPayload.flags = 1u;
    shadowPayload.roughMetal = 0u;
    shadowPayload.emissionSss = 0u;
    shadowPayload.iorTransmission = 0u;
    shadowPayload.rayCone = 0u;
    return shadowPayload;
}

public VisibilityResult visibility(uint rayMask, float3 origin, float3 dir, float tmax) {
    // Vulkan requires an identical payload structure for every stage reachable by this trace. The shadow
    // path uses only the albedo view as accumulated transmittance and hitT as the nearest-water crossing.
    Payload shadowPayload = makeShadowPayload();
    // Shadow SBT records run any-hit only for cutout/translucent/water. Cutout alpha-tests; translucent
    // and water tint that transmittance and pass through. Solid blocks terminate traversal. There is no
    // closest shader worth executing. The lightweight guide miss clears the flags sentinel so this path
    // needs only ordinary TraceRay and no invocation-reorder capability.
    TraceRay(topLevelAS,
            RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH | RAY_FLAG_SKIP_CLOSEST_HIT_SHADER,
            rayMask, SBT_SHADOW, SBT_STRIDE_BUCKET, MISS_GUIDE,
            makeRay(origin, RAY_TMIN, dir, tmax), shadowPayload);
    VisibilityResult result;
    result.transmittance = shadowPayload.flags == 0u
            ? unpackAlbedo(shadowPayload) : float3(0.0, 0.0, 0.0);
    result.waterHitT = shadowPayload.hitT;
    return result;
}

// `valid` is false when the point projects behind the previous camera, where the perspective divide
// would otherwise manufacture an arbitrarily large NDC. Mirror images reach that case easily: a virtual
// image sits as far behind the reflector as the source is in front, so a reflection seen at a grazing
