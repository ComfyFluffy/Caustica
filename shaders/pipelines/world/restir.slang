// ReSTIR temporal reuse for the block-emitter reservoirs. Depends on lighting and bindings.
//
// lighting.slang's risInitial already does the RIS half of ReSTIR: it draws candidate emitters from the
// light grid and keeps one survivor weighted by its target function. What it does not do is remember
// anything. Every pixel, every frame, starts from an empty reservoir, so the effective sample count is
// whatever the candidate budget allows in one frame — which is why caves and night scenes stay noisy no
// matter how good the denoiser is.
//
// This adds the missing half: last frame's survivor, reprojected through the motion vector, is combined
// into this frame's reservoir. Over a stationary second a pixel accumulates hundreds of effective
// candidates for the cost of one, and the emitter that actually matters wins consistently instead of
// flickering between whichever few were sampled that frame.
//
// FOUR CORRECTNESS REQUIREMENTS, each of which produces a specific artifact when skipped:
//
//   * M-capping. Unbounded history makes a pixel infinitely confident in a stale sample and the image
//     stops responding to change — a torch you place lights nothing for several seconds. The cap trades
//     a little variance for a bounded reaction time.
//   * p-hat re-evaluation. The stored sample's target function must be recomputed against THIS frame's
//     surface, not reused from last frame's. A reservoir carried onto a rotated or moved surface has a
//     different BRDF and geometry term, and reusing the old weight is the classic ReSTIR bias.
//   * Geometric rejection. The reprojected pixel is only the same surface if depth and normal agree.
//     Without the test, history bleeds across silhouettes and leaves a smear trailing every moving edge.
//   * Reset on discontinuity. First frame, resize, dimension change, world reload — the buffer holds
//     positions in a rebased space that no longer exists, so it must be treated as empty.
//
// PING-PONG WITHOUT DESCRIPTOR REWRITES. Both images stay bound; a frame-parity push constant decides
// which is history. Rewriting descriptors per frame would touch ring-buffered sets that frames still in
// flight are reading — a use-after-write that validation layers catch only sometimes and that shows up
// as intermittent corruption otherwise.
//
// STORAGE. Two ping-ponged rgba32f images at RENDER resolution (binding 12 read, 13 write). Four
// channels is not enough for a Reservoir's fourteen floats, so what is stored is the minimum needed to
// replay the sample: the emitter point, its normal, its radiance, area, M and W. Everything else —
// wSum, phat — is recomputed on load, which is required anyway by the p-hat rule above.
//
//   xyz : emitter sample point, rebased world space (needs full fp32; a half here quantises the light
//         position into visible banding across a large room)
//   w   : packed, see below
//
// Only the primary hit is reused. A secondary bounce has no stable screen-space identity to reproject
// through, so temporal reuse there would be combining unrelated surfaces.

import world_common;
import lighting;
import math;
import bindings;

// Effective-sample ceiling. 20x the per-frame candidate budget is the usual starting point: enough
// history to kill the noise, short enough that a light change resolves in a few frames.
public static const float RESTIR_M_CAP = 20.0;
// Depth agreement, as a fraction of the reprojected depth. Proportional rather than absolute because a
// fixed epsilon that works at 5 blocks rejects everything at 200.
public static const float RESTIR_DEPTH_TOLERANCE = 0.05;
// Normal agreement in cosine. 0.9 is about 25 degrees — loose enough to survive normal-mapped detail,
// tight enough to reject a perpendicular wall behind a silhouette.
public static const float RESTIR_NORMAL_TOLERANCE = 0.9;

public struct PackedReservoir {
    public float3 pos;
    public float3 lnrm;
    public float3 le;
    public float area;
    public float M;
    public float W;
};

public PackedReservoir packedEmpty() {
    PackedReservoir p;
    p.pos = float3(0.0);
    p.lnrm = float3(0.0);
    p.le = float3(0.0);
    p.area = 0.0;
    p.M = 0.0;
    p.W = 0.0;
    return p;
}

public bool packedValid(PackedReservoir p) {
    return p.M > 0.0 && p.W > 0.0;
}

// ---- Octahedral normal encoding, 16 bits per component into one float --------------------------------
// The emitter normal only feeds a cosine term, so a couple of degrees of quantisation is invisible.
// Octahedral beats spherical coordinates here for having no pole singularity.

float2 octEncode(float3 n) {
    n /= max(abs(n.x) + abs(n.y) + abs(n.z), 1.0e-8);
    float2 oct = n.xy;
    if (n.z < 0.0) {
        oct = (1.0 - abs(float2(n.y, n.x))) * float2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return oct * 0.5 + 0.5;
}

float3 octDecode(float2 oct) {
    float2 f = oct * 2.0 - 1.0;
    float3 n = float3(f.x, f.y, 1.0 - abs(f.x) - abs(f.y));
    float t = max(-n.z, 0.0);
    n.x += n.x >= 0.0 ? -t : t;
    n.y += n.y >= 0.0 ? -t : t;
    return normalize(n);
}

// ---- Reservoir <-> image texels ----------------------------------------------------------------------
//
// Two texels per pixel, side by side in a double-width image: texel 2x holds position + packed normal,
// texel 2x+1 holds radiance + area + M + W. A double-width image rather than two images keeps this to
// one binding pair and one allocation, and the two texels are adjacent so a load touches one cache line.

// Slang has no built-in half packers here, and the three-value pack needs a custom split anyway:
// area and M are small and bounded, W needs range but not precision, so W keeps a full half and the
// other two share the remaining 16 bits as 8-bit fixed point. area is normalised against a generous
// emitter-face bound; a block face is at most 1 square block.
static const float RESTIR_AREA_SCALE = 4.0;

uint packHalf2(float2 v) {
    return (f32tof16(v.x) & 0xFFFFu) | ((f32tof16(v.y) & 0xFFFFu) << 16);
}

float2 unpackHalf2(uint v) {
    return float2(f16tof32(v & 0xFFFFu), f16tof32((v >> 16) & 0xFFFFu));
}

uint packAreaMW(float area, float M, float W) {
    uint areaBits = uint(clamp(area / RESTIR_AREA_SCALE, 0.0, 1.0) * 255.0 + 0.5);
    uint mBits = uint(clamp(M / RESTIR_M_CAP, 0.0, 1.0) * 255.0 + 0.5);
    return areaBits | (mBits << 8) | ((f32tof16(W) & 0xFFFFu) << 16);
}

void unpackAreaMW(uint bits, out float area, out float M, out float W) {
    area = float(bits & 0xFFu) / 255.0 * RESTIR_AREA_SCALE;
    M = float((bits >> 8) & 0xFFu) / 255.0 * RESTIR_M_CAP;
    W = f16tof32((bits >> 16) & 0xFFFFu);
}

// parity 0 => A is history and B is written; parity 1 => the reverse.
public PackedReservoir loadReservoir(int2 pix, uint parity) {
    int2 lo = int2(pix.x * 2, pix.y);
    int2 hi = int2(pix.x * 2 + 1, pix.y);
    float4 a = parity == 0u ? reservoirA[lo] : reservoirB[lo];
    float4 b = parity == 0u ? reservoirA[hi] : reservoirB[hi];
    PackedReservoir p;
    p.pos = a.xyz;
    p.lnrm = octDecode(unpackHalf2(asuint(a.w)));
    p.le = b.xyz;
    // area, M and W share the last channel via a half3-style pack: area and M are small and bounded,
    // W needs range but not precision. Splitting them out would have cost a third texel.
    unpackAreaMW(asuint(b.w), p.area, p.M, p.W);
    return p;
}

public void storeReservoir(int2 pix, uint parity, PackedReservoir p) {
    float4 lo = float4(p.pos, asfloat(packHalf2(octEncode(p.lnrm))));
    float4 hi = float4(p.le, asfloat(packAreaMW(p.area, p.M, p.W)));
    if (parity == 0u) {
        reservoirB[int2(pix.x * 2, pix.y)] = lo;
        reservoirB[int2(pix.x * 2 + 1, pix.y)] = hi;
    } else {
        reservoirA[int2(pix.x * 2, pix.y)] = lo;
        reservoirA[int2(pix.x * 2 + 1, pix.y)] = hi;
    }
}

public PackedReservoir toPacked(Reservoir r) {
    PackedReservoir p;
    p.pos = r.pos;
    p.lnrm = r.lnrm;
    p.le = r.le;
    p.area = r.area;
    p.M = r.M;
    p.W = r.W;
    return p;
}

// ---- Spatial reuse -----------------------------------------------------------------------------------
//
// Temporal reuse alone cannot help a pixel that has no usable history: the frame after a camera cut, a
// disoccluded edge, a surface that just came round a corner. Those are exactly the pixels a player is
// looking at, and they stay at single-frame noise while everything around them has converged. Spatial
// reuse borrows from screen-space neighbours, which have no such gap.
//
// Neighbours are read from the HISTORY image, not from this frame's output. Reading what the current
// dispatch is concurrently writing would be a race — reservoir writes are unordered across a dispatch,
// so a neighbour might be last frame's value, this frame's, or a torn mix, varying run to run. Sampling
// last frame's completed buffer makes this spatiotemporal reuse with a one-frame lag, which is both
// well-defined and what the lag already is for the temporal path.
//
// HONESTY ABOUT BIAS. This is the practical variant, not the unbiased one. Correct spatial ReSTIR needs
// per-neighbour MIS weights that account for each neighbour's own sampling domain; that costs a
// visibility ray per tap and roughly doubles the maths. What this does instead — re-evaluating p-hat at
// the receiving surface, rejecting geometric mismatches, and capping M — bounds the error to a slight
// over-smoothing near contact shadows rather than a visible energy shift. It is the trade almost every
// shipping ReSTIR implementation makes, but it IS a trade.

public static const int RESTIR_MAX_SPATIAL_TAPS = 8;
static const float RESTIR_TWO_PI = 6.28318530718;

// A disc sample around the pixel. Uniform in area rather than in radius, so taps do not bunch at the
// centre where they duplicate information the pixel already has.
public int2 spatialTapOffset(float radius, inout uint seed) {
    float angle = rndf(seed) * RESTIR_TWO_PI;
    float r = radius * sqrt(rndf(seed));
    return int2(int(round(cos(angle) * r)), int(round(sin(angle) * r)));
}

// ---- Temporal combine --------------------------------------------------------------------------------

// Was the reprojected pixel the same surface? Both tests are needed: depth alone accepts a wall seen
// edge-on behind a silhouette, normals alone accept a parallel surface further away.
public bool temporalSurfaceMatches(float currentDepth, float historyDepth,
                                   float3 currentNormal, float3 historyNormal) {
    if (historyDepth <= 0.0 || currentDepth <= 0.0) {
        return false;
    }
    if (abs(currentDepth - historyDepth) > currentDepth * RESTIR_DEPTH_TOLERANCE) {
        return false;
    }
    return dot(currentNormal, historyNormal) >= RESTIR_NORMAL_TOLERANCE;
}

// Merge a history reservoir into this frame's. `history.W` is the unbiased contribution weight from the
// frame it was built in; multiplying by the freshly evaluated p-hat and M converts it back into the
// resampling weight this reservoir's stream expects.
public Reservoir temporalCombine(Reservoir current, PackedReservoir history,
                                 float3 hitPos, float3 n, float3 v, float3 rd,
                                 float3 diffAlb, float3 F0, float rough,
                                 bool twoSided, float sss, inout uint seed) {
    if (!packedValid(history)) {
        return current;
    }
    // Re-evaluate the stored sample against the CURRENT surface. This is the step that keeps reuse
    // unbiased; the stored p-hat belongs to a surface that may no longer exist.
    float phat;
    evalSampleContrib(history.pos, history.lnrm, history.le, history.area,
                      hitPos, n, v, rd, diffAlb, F0, rough, twoSided, sss, phat);
    if (phat <= 0.0) {
        // The remembered emitter contributes nothing here — behind the surface now, or facing away.
        // Its M is still evidence about how much was sampled, but the sample itself cannot survive.
        current.M += min(history.M, RESTIR_M_CAP);
        return current;
    }
    float historyM = min(history.M, RESTIR_M_CAP);
    float weight = phat * history.W * historyM;
    current.wSum += weight;
    current.M += historyM;
    if (rndf(seed) * current.wSum <= weight) {
        current.pos = history.pos;
        current.lnrm = history.lnrm;
        current.le = history.le;
        current.area = history.area;
        current.phat = phat;
    }
    // Rebuild the contribution weight from the merged stream. Guarding on phat keeps a survivor whose
    // target function collapsed from producing an infinite W and a white fireflies pixel.
    current.W = current.phat > 0.0 ? current.wSum / (current.M * current.phat) : 0.0;
    return current;
}
