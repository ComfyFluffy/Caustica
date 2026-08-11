// PathSegment — a resumable continuation — plus its packed 64-byte buffer form. This is the record
// the primary pass writes and the indirect pass reads. Depends on medium and trace.


// Everything needed to resume tracing from a point in the scene. The path tracer takes one of these and
// may hand back another, which is how a dielectric's two Fresnel continuations are traced without
// instantiating the path tracer twice: the second branch is DATA, not a second inlined copy. `throughput`
// already carries the branch weight, so segments are simply summed.
//
// This is deliberately shaped like a record that could live in a buffer rather than a register: the
// planned split into a primary/guide pass and an indirect pass hands continuations between dispatches,
// and this is the payload it would write.

import world_common;
import world_core;
import medium;
import trace;

public struct PathSegment {
    public float3 ro;
    public float3 rd;
    public float3 throughput;
    public MediumStack medium;
    public float rayConeWidth;
    public float rayConeSpread;
    public uint seed;
    public int bounce;          // interfaces already consumed, so RR start and the bounce cap stay global
    public bool showCelestial;
    // Which secondary domain Pass B must resume this continuation in (SECONDARY_DOMAIN_*). Meaningless
    // at bounce 0, which is always traced as the camera ray; packPathSegment normalizes it to WORLD
    // there so the packed round-trip is an equality.
    public uint secondaryDomain;
    // True while this continuation extends an unbroken chain of camera-visible transmissions: medium
    // transmissions keep it, any reflection-class or diffuse lobe clears it. Pass B keeps such a chain
    // in the local-view domain when the local view is published.
    public bool cameraTransmissionContinuity;
};

public PathSegment makePathSegment(float3 ro, float3 rd, float3 throughput, MediumStack medium,
                            float rayConeWidth, float rayConeSpread, uint seed,
                            int bounce, bool showCelestial, uint secondaryDomain,
                            bool cameraTransmissionContinuity) {
    PathSegment s;
    s.ro = ro;
    s.rd = rd;
    s.throughput = throughput;
    s.medium = medium;
    s.rayConeWidth = rayConeWidth;
    s.rayConeSpread = rayConeSpread;
    s.seed = seed;
    s.bounce = bounce;
    s.showCelestial = showCelestial;
    s.secondaryDomain = secondaryDomain;
    s.cameraTransmissionContinuity = cameraTransmissionContinuity;
    return s;
}
// The uint following the float3 packs into its tail lane and every later member is uint-sized, so
// Std430DataLayout gives this an exact 64-byte stride (12 + 13*4); RtComposite.PATH_RECORD_BYTES
// allocates the queue from the same number.
public struct PackedPathSegment {
    public float3 ro;
    public uint rd;
    public uint throughput;
    public uint currentExtinction;
    public uint parent1Extinction;
    public uint parent2Extinction;
    public uint mediumIors01;   // half2(current.ior, parent1.ior)
    public uint mediumIor2;     // half2(parent2.ior, unused)
    public uint mediumIds01;    // u20 current.mediumId | low u12 parent1.mediumId << 20
    public uint mediumId2;      // high u8 parent1.mediumId | u20 parent2.mediumId << 8
    public uint rayCone;
    public uint seed;
    public uint pathFlags;
    public uint nextRecord;
};

public static const uint PATH_NO_NEXT = 0xffffffffu;
// pathFlags: bits 0..3 bounce, bit 8 showCelestial, bits 9..10 secondary domain (SECONDARY_DOMAIN_*,
// the fourth encoding never packed), bit 11 camera-transmission continuity. Bits 4..7 and 12..31 free.
public static const uint PATH_BOUNCE_MASK = 15u;
public static const uint PATH_SHOW_CELESTIAL = 1u << 8;
public static const uint PATH_SECONDARY_DOMAIN_SHIFT = 9u;
public static const uint PATH_SECONDARY_DOMAIN_MASK = 3u << PATH_SECONDARY_DOMAIN_SHIFT;
public static const uint PATH_CAMERA_TRANSMISSION_CONTINUITY = 1u << 11;

public float2 octEncode(float3 direction) {
    float3 n = direction / max(abs(direction.x) + abs(direction.y) + abs(direction.z), 1.0e-20);
    float2 signNotZero = float2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    float2 oct = n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * signNotZero;
    return oct * 0.5 + 0.5;
}

public float3 octDecode(float2 encoded) {
    float2 oct = encoded * 2.0 - 1.0;
    float3 n = float3(oct, 1.0 - abs(oct.x) - abs(oct.y));
    if (n.z < 0.0) {
        float2 signNotZero = float2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
        n.xy = (1.0 - abs(n.yx)) * signNotZero;
    }
    return normalize(n);
}

public uint packUnorm16x2(float2 v) {
    uint2 q = uint2(round(clamp(v, 0.0, 1.0) * 65535.0));
    return q.x | (q.y << 16u);
}

public float2 unpackUnorm16x2(uint p) {
    return float2(p & 0xffffu, p >> 16u) / 65535.0;
}

// DXGI_FORMAT_R9G9B9E5_SHAREDEXP, implemented locally because Slang exposes no packing intrinsic.
public uint packRgb9e5(float3 value) {
    float3 v = clamp(value, 0.0, 65408.0);
    float maxChannel = max(v.x, max(v.y, v.z));
    uint exponent = maxChannel < exp2(-16.0)
            ? 0u : uint(floor(log2(maxChannel))) + 16u;
    exponent = min(exponent, 31u);
    float scale = exp2(float(int(exponent) - 24));
    uint maxMantissa = uint(floor(maxChannel / scale + 0.5));
    if (maxMantissa == 512u && exponent < 31u) {
        exponent++;
        scale *= 2.0;
    }
    uint3 mantissa = min(uint3(floor(v / scale + 0.5)), uint3(511u, 511u, 511u));
    return mantissa.x | (mantissa.y << 9u) | (mantissa.z << 18u) | (exponent << 27u);
}

public float3 unpackRgb9e5(uint p) {
    float scale = exp2(float(int(p >> 27u) - 24));
    return float3(p & 0x1ffu, (p >> 9u) & 0x1ffu, (p >> 18u) & 0x1ffu) * scale;
}

public PackedPathSegment packPathSegment(PathSegment seg, uint nextRecord) {
    PackedPathSegment p;
    p.ro = seg.ro;
    p.rd = packUnorm16x2(octEncode(seg.rd));
    p.throughput = packRgb9e5(seg.throughput);
    p.currentExtinction = packRgb9e5(seg.medium.current.extinction);
    p.parent1Extinction = packRgb9e5(seg.medium.parent1.extinction);
    p.parent2Extinction = packRgb9e5(seg.medium.parent2.extinction);
    p.mediumIors01 = packHalf2(float2(seg.medium.current.ior, seg.medium.parent1.ior));
    p.mediumIor2 = packHalf2(float2(seg.medium.parent2.ior, 0.0));
    p.mediumIds01 = (seg.medium.current.mediumId & 0xfffffu)
            | ((seg.medium.parent1.mediumId & 0xfffu) << 20u);
    p.mediumId2 = ((seg.medium.parent1.mediumId >> 12u) & 0xffu)
            | ((seg.medium.parent2.mediumId & 0xfffffu) << 8u);
    p.rayCone = packHalf2(float2(seg.rayConeWidth, seg.rayConeSpread));
    p.seed = seg.seed;
    // The one normalization site: a bounce-0 record is always replayed as the camera ray, so its domain
    // stores as WORLD and write/read-back stays a decidable equality.
    uint domain = seg.bounce == 0
            ? SECONDARY_DOMAIN_WORLD : normalizeSecondaryDomain(seg.secondaryDomain);
    p.pathFlags = (uint(seg.bounce) & PATH_BOUNCE_MASK)
            | (seg.showCelestial ? PATH_SHOW_CELESTIAL : 0u)
            | (domain << PATH_SECONDARY_DOMAIN_SHIFT)
            | (seg.cameraTransmissionContinuity ? PATH_CAMERA_TRANSMISSION_CONTINUITY : 0u);
    p.nextRecord = nextRecord;
    return p;
}

public PathSegment unpackPathSegment(PackedPathSegment p) {
    float2 iors01 = unpackHalf2(p.mediumIors01);
    Medium current;
    current.ior = iors01.x;
    current.extinction = unpackRgb9e5(p.currentExtinction);
    current.mediumId = p.mediumIds01 & 0xfffffu;
    Medium parent1;
    parent1.ior = iors01.y;
    parent1.extinction = unpackRgb9e5(p.parent1Extinction);
    parent1.mediumId = (p.mediumIds01 >> 20u) | ((p.mediumId2 & 0xffu) << 12u);
    Medium parent2;
    parent2.ior = unpackHalf2(p.mediumIor2).x;
    parent2.extinction = unpackRgb9e5(p.parent2Extinction);
    parent2.mediumId = (p.mediumId2 >> 8u) & 0xfffffu;
    MediumStack medium;
    medium.current = current;
    medium.parent1 = parent1;
    medium.parent2 = parent2;
    float2 cone = unpackHalf2(p.rayCone);
    return makePathSegment(p.ro, octDecode(unpackUnorm16x2(p.rd)),
            unpackRgb9e5(p.throughput), medium, cone.x, cone.y, p.seed,
            int(p.pathFlags & PATH_BOUNCE_MASK), (p.pathFlags & PATH_SHOW_CELESTIAL) != 0u,
            (p.pathFlags & PATH_SECONDARY_DOMAIN_MASK) >> PATH_SECONDARY_DOMAIN_SHIFT,
            (p.pathFlags & PATH_CAMERA_TRANSMISSION_CONTINUITY) != 0u);
}

// Walk only the visually-primary dielectric chain. The terminal non-dielectric/miss trace is repeated
// by the indirect pass, so the returned record describes the state immediately BEFORE that trace.
// This keeps closest-hit payload data out of the queue and, critically, writes the record before any
