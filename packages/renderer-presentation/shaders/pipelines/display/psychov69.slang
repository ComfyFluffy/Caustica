/*
 * Adapted from the PsychoV69 0.1.0 experimental source release (dist/PsychoV69.hlsl).
 * Derived in part from PsychoV30/V31, Copyright (C) 2026 Carlos Lopez
 * SPDX-License-Identifier: MIT
 *
 * Faithful float32 port of the reference core: the expression shapes, clamps,
 * branch structure, and constants of the reference are preserved exactly (the
 * core was validated upstream as FP32 against a float64 oracle). The
 * integration profile pins the reference wrapper's compatibility arguments:
 * scene reference white 1.0, a declared BT.709 source boundary, and dual
 * neutral anchors (white_anchor = 1). Runtime-adjustable are the reference
 * wrapper's own parameters: peak (from the output path), compression
 * (0 = automatic), gamut compression strength, highlights, shadows,
 * contrast, purity, exposure, and source awareness. The target gamut mode
 * (0 = BT.709 cube, 1 = BT.2020 cube) follows the output path: SDR
 * constrains to the BT.709 cube, HDR to the BT.2020 cube.
 *
 * Dead branches of the reference are omitted: the NONE/BT.2020 source-boundary
 * transports (the source boundary is pinned to BT.709), the white_anchor = 0
 * slope variant, and the diagnostic status bits (explicitly not surfaced by
 * the reference).
 */

// D65-normalized Stockman/CVRL cone-load basis rows and inverses, taken as the
// reference's explicit float32 literals (matrices computed in float64 upstream,
// then rounded to float32). `psycho69Xxx(v)` is the reference's per-row dot form.
static const float3 PSYCHO69_CONE_FROM_709_L = float3(0.2723846136, 0.6557854613, 0.07182992503);
static const float3 PSYCHO69_CONE_FROM_709_M = float3(0.09913179712, 0.7775322515, 0.1233359514);
static const float3 PSYCHO69_CONE_FROM_709_S = float3(0.02682290166, 0.09259973229, 0.880577366);
static const float3 PSYCHO69_RGB709_FROM_CONE_R = float3(5.281690941, -4.478065517, 0.1963745758);
static const float3 PSYCHO69_RGB709_FROM_CONE_G = float3(-0.6588615228, 1.866551286, -0.2076897636);
static const float3 PSYCHO69_RGB709_FROM_CONE_B = float3(-0.09159885232, -0.05987825767, 1.15147711);
static const float3 PSYCHO69_RGB2020_FROM_709_R = float3(0.627403896, 0.3292830383, 0.04331306563);
static const float3 PSYCHO69_RGB2020_FROM_709_G = float3(0.06909728939, 0.9195403952, 0.01136231561);
static const float3 PSYCHO69_RGB2020_FROM_709_B = float3(0.01639143886, 0.08801330792, 0.8955952532);
static const float3 PSYCHO69_RGB709_FROM_2020_R = float3(1.660491002, -0.5876411387, -0.07284986319);
static const float3 PSYCHO69_RGB709_FROM_2020_G = float3(-0.1245504746, 1.132899897, -0.008349422663);
static const float3 PSYCHO69_RGB709_FROM_2020_B = float3(-0.01815076332, -0.1005788981, 1.118729661);

static const float PSYCHO69_ALPHA_L = 0.6983240888;
static const float PSYCHO69_ANCHOR = 0.18;
// Explicit engineering domain, not an undocumented numerical epsilon fix.
static const float PSYCHO69_MIN_NORMAL = 1.1754943508222875e-38;
static const float PSYCHO69_MAX_SCENE = 65504.0;
static const float PSYCHO69_AUTO_COMPRESSION_SENTINEL = 0.0;
static const float PSYCHO69_GAMUT_SOFT_KNEE = 0.85;

float3 psycho69ConeFrom709(float3 v) {
    return float3(dot(v, PSYCHO69_CONE_FROM_709_L),
            dot(v, PSYCHO69_CONE_FROM_709_M),
            dot(v, PSYCHO69_CONE_FROM_709_S));
}

float3 psycho69RGB709FromCone(float3 v) {
    return float3(dot(v, PSYCHO69_RGB709_FROM_CONE_R),
            dot(v, PSYCHO69_RGB709_FROM_CONE_G),
            dot(v, PSYCHO69_RGB709_FROM_CONE_B));
}

float3 psycho69RGB2020From709(float3 v) {
    return float3(dot(v, PSYCHO69_RGB2020_FROM_709_R),
            dot(v, PSYCHO69_RGB2020_FROM_709_G),
            dot(v, PSYCHO69_RGB2020_FROM_709_B));
}

float3 psycho69RGB709From2020(float3 v) {
    return float3(dot(v, PSYCHO69_RGB709_FROM_2020_R),
            dot(v, PSYCHO69_RGB709_FROM_2020_G),
            dot(v, PSYCHO69_RGB709_FROM_2020_B));
}

float psycho69Yf(float3 cone) {
    return PSYCHO69_ALPHA_L * cone.x + (1.0 - PSYCHO69_ALPHA_L) * cone.y;
}

float3 psycho69ToTarget(float3 rgb709, int target) {
    return target == 0 ? rgb709 : psycho69RGB2020From709(rgb709);
}

float3 psycho69FromTarget(float3 targetRgb, int target) {
    return target == 0 ? targetRgb : psycho69RGB709From2020(targetRgb);
}

// Accurate 1-2^x for x<0, without an expm1 intrinsic or cancellation near 0.
// The small branch uses a sixth-order Taylor polynomial at |x ln2|<.08665.
float psycho69OneMinusExp2(float x) {
    if (x < -0.125) {
        return 1.0 - exp2(x);
    }
    float t = x * 0.6931471805599453;
    return -t * (1.0 + t * (0.5 + t * (0.1666666666666667 + t * (0.0416666666666667
         + t * (0.0083333333333333 + t * 0.0013888888888889)))));
}

float psycho69Softplus2(float x) {
    // The potentially underflowing exp2 is <=1 and only a negligible additive
    // correction. It is NEVER used as the numerator/denominator of a ratio.
    return max(x, 0.0) + log2(1.0 + exp2(-abs(x)));
}

float psycho69Quintic(float x) {
    x = clamp(x, 0.0, 1.0);
    return x * x * x * (x * (6.0 * x - 15.0) + 10.0);
}

// Prepared-state bundle for the reference's Prepare() over the pinned profile.
struct Psycho69Prepared {
    float peak;
    float h;
    float logK;
    float logB;
    float beta;
    float highlights;
    float shadows;
    float contrast;
    float purity;
    float exposure;
    float gamutStrength;
    float sourceAwareness;
    int targetGamut;
};

Psycho69Prepared psycho69Prepare(float peakRatio, int targetGamutMode,
        float compression, float gamutCompression, float highlights, float shadows,
        float contrast, float purity, float exposure, float sourceAwareness) {
    Psycho69Prepared p;
    // White nits are pinned to 1.0 in white-relative units; the reference's
    // documented repair then holds effective white at 99% of peak.
    float peakNits = clamp(peakRatio, 1.0, 10000.0);
    float whiteNits = clamp(1.0, 0.01, 0.99 * peakNits);
    whiteNits = min(whiteNits, 0.99 * peakNits);
    p.peak = peakNits / whiteNits;
    float compressionH = clamp(compression, 0.0, 128.0);
    if (compressionH > 0.0) {
        compressionH = max(compressionH, 1.0 / 64.0);
    }
    p.h = compressionH == PSYCHO69_AUTO_COMPRESSION_SENTINEL
            ? clamp(p.peak, 1.0, 128.0)
            : compressionH;
    p.logK = p.h * log2(PSYCHO69_ANCHOR / p.peak);
    float b = psycho69OneMinusExp2(p.logK);
    p.logB = log2(b);
    p.beta = contrast * p.h / b;
    // white_anchor pinned to 1: solve F(1)=1 AND F(.18)=.18, with the same
    // asymptotic peak. log(P^h-1) = h log(P) + log(1-exp(-h log(P))).
    float hp = p.h * log2(p.peak);
    float whiteLogOdds = hp + log2(psycho69OneMinusExp2(-hp));
    p.beta = contrast * (p.logB - p.logK - whiteLogOdds) / log2(1.0 / PSYCHO69_ANCHOR);
    p.highlights = clamp(highlights, 0.5, 1.5);
    p.shadows = clamp(shadows, 0.5, 1.5);
    p.contrast = clamp(contrast, 0.25, 4.0);
    p.purity = clamp(purity, 0.0, 2.0);
    p.exposure = clamp(exposure, 1.0 / 64.0, 64.0);
    p.gamutStrength = clamp(gamutCompression, 0.0, 1.0);
    p.sourceAwareness = clamp(sourceAwareness, 0.0, 1.0);
    p.targetGamut = targetGamutMode == 0 ? 0 : 1;
    return p;
}

float psycho69PositiveU(float q, Psycho69Prepared p) {
    if (q <= 0.0) {
        return 0.0;
    }
    if (q == 1.0) {
        return PSYCHO69_ANCHOR / p.peak;
    }
    float logDrive = p.logB - p.logK - p.beta * log2(max(q, PSYCHO69_MIN_NORMAL));
    return exp2(-psycho69Softplus2(logDrive) / p.h);
}

float psycho69SignedU(float q, Psycho69Prepared p) {
    return q < 0.0 ? -psycho69PositiveU(-q, p) : psycho69PositiveU(q, p);
}

float3 psycho69GradeCone(float3 cone, Psycho69Prepared p) {
    float y = psycho69Yf(cone);
    if (y > PSYCHO69_MIN_NORMAL) {
        float stops = log2(max(y / PSYCHO69_ANCHOR, PSYCHO69_MIN_NORMAL));
        // Explicit monotonic log-space grading. Default is exactly identity.
        // .18 is fixed. Highlights and shadows are independent log-slope controls.
        float tHi = psycho69Quintic(stops / log2(1.0 / PSYCHO69_ANCHOR));
        float tLo = psycho69Quintic(-stops / 4.0);
        float shaped = stops * (1.0 + (p.highlights - 1.0) * tHi + (p.shadows - 1.0) * tLo);
        // Keep all subsequent arithmetic in the declared engineering domain.
        float yg = PSYCHO69_ANCHOR * exp2(clamp(shaped, -100.0, 24.0));
        cone = cone * (yg / y);
    }
    float neutral = psycho69Yf(cone);
    return (float3(neutral) + (cone - float3(neutral)) * p.purity) / PSYCHO69_ANCHOR;
}

// Scaled A2 response coordinate (D, sum, T) plus the response Yf before
// direction authoring.
struct Psycho69Response {
    float3 coord;
    float yf;
};

Psycho69Response psycho69Respond(float3 sourceRgb709, Psycho69Prepared p) {
    float3 q = psycho69GradeCone(psycho69ConeFrom709(sourceRgb709), p);
    float3 u = float3(psycho69SignedU(q.x, p), psycho69SignedU(q.y, p),
            psycho69SignedU(q.z, p));
    float sd = q.x - q.y;
    float st = 2.0 * q.z - q.x - q.y;
    float rd = u.x - u.y;
    float rt = 2.0 * u.z - u.x - u.y;
    // Normalize before squaring to avoid overflow on bright/signed trajectories.
    float ss = max(abs(sd), abs(st));
    float rs = max(abs(rd), abs(rt));
    float d = rd;
    float t = rt;
    if (ss > 0.0 && rs > 0.0) {
        float sn = sqrt(0.5 * (sd / ss) * (sd / ss) + (st / ss) * (st / ss) / 6.0);
        float rn = sqrt(0.5 * (rd / rs) * (rd / rs) + (rt / rs) * (rt / rs) / 6.0);
        float md = (sd / ss) / sn + (rd / rs) / rn;
        float mt = (st / ss) / sn + (rt / rs) / rn;
        float mn = sqrt(0.5 * md * md + mt * mt / 6.0);
        // Antipodal bisectors have no unique direction. Keep the response direction.
        if (mn > 1.0e-6) {
            d = md * (rs * rn / mn);
            t = mt * (rs * rn / mn);
        }
    }
    Psycho69Response r;
    r.coord = float3(d, u.x + u.y + u.z, t);
    r.yf = psycho69Yf(u);
    return r;
}

float psycho69CoordYf(float3 c) {
    return (2.0 * c.y + 3.0 * (PSYCHO69_ALPHA_L - (1.0 - PSYCHO69_ALPHA_L)) * c.x - c.z) / 6.0;
}

float3 psycho69ConeFromCoord(float3 c) {
    return float3((2.0 * c.y - c.z + 3.0 * c.x) / 6.0,
            (2.0 * c.y - c.z - 3.0 * c.x) / 6.0,
            (c.y + c.z) / 3.0);
}

float3 psycho69CoordFromDYfT(float d, float a, float t) {
    return float3(d,
            3.0 * a - 1.5 * (PSYCHO69_ALPHA_L - (1.0 - PSYCHO69_ALPHA_L)) * d + 0.5 * t,
            t);
}

float3 psycho69TargetFromCoord(float3 coord, int target) {
    return psycho69ToTarget(psycho69RGB709FromCone(psycho69ConeFromCoord(coord)), target);
}

// Exact ray projection into the selected RGB cube. Kept independent of every
// artistic slider; convex blends and final post-effects must pass this stage.
float3 psycho69ContainTarget(float3 rgb, float peak, int target) {
    if (min(rgb.x, min(rgb.y, rgb.z)) >= 0.0 && max(rgb.x, max(rgb.y, rgb.z)) <= peak) {
        return rgb;
    }
    float a = clamp(psycho69Yf(psycho69ConeFrom709(psycho69FromTarget(rgb, target))), 0.0, peak);
    float3 d = rgb - float3(a);
    float s = 1.0;
    if (d.x > 0.0) {
        s = min(s, (peak - a) / d.x);
    } else if (d.x < 0.0) {
        s = min(s, -a / d.x);
    }
    if (d.y > 0.0) {
        s = min(s, (peak - a) / d.y);
    } else if (d.y < 0.0) {
        s = min(s, -a / d.y);
    }
    if (d.z > 0.0) {
        s = min(s, (peak - a) / d.z);
    } else if (d.z < 0.0) {
        s = min(s, -a / d.z);
    }
    return clamp(float3(a) + d * clamp(s, 0.0, 1.0), 0.0, peak);
}

// Closed-form endpoint: fixed Yf with an optional rounded L4 soft knee.
// Unlike V31's joint optimizer this does NOT minimize joint cone distance.
float3 psycho69MapCoord(Psycho69Response r, Psycho69Prepared p) {
    float a = clamp(psycho69CoordYf(r.coord), 0.0, clamp(r.yf, 0.0, 1.0));
    if (a <= 0.0) {
        return float3(0.0);
    }
    if (a >= 1.0) {
        return float3(0.0, 3.0, 0.0);
    }
    float3 base = psycho69CoordFromDYfT(r.coord.x, a, r.coord.z);
    float3 radial = psycho69TargetFromCoord(base, p.targetGamut) - float3(a);
    float3 pressure = float3(
            radial.x >= 0.0 ? radial.x / (1.0 - a) : -radial.x / a,
            radial.y >= 0.0 ? radial.y / (1.0 - a) : -radial.y / a,
            radial.z >= 0.0 ? radial.z / (1.0 - a) : -radial.z / a);
    float maxPressure = max(pressure.x, max(pressure.y, pressure.z));
    float s = 1.0;
    if (maxPressure > 0.0) {
        float3 v = pressure / maxPressure;
        float3 v2 = v * v;
        float rounded = maxPressure * sqrt(sqrt(dot(v2, v2)));
        float hard = min(1.0, 1.0 / maxPressure);
        float soft = 1.0;
        if (rounded > PSYCHO69_GAMUT_SOFT_KNEE) {
            float excess = rounded - PSYCHO69_GAMUT_SOFT_KNEE;
            float fitted = PSYCHO69_GAMUT_SOFT_KNEE
                    + (1.0 - PSYCHO69_GAMUT_SOFT_KNEE)
                            * (excess / (excess + 1.0 - PSYCHO69_GAMUT_SOFT_KNEE));
            soft = fitted / rounded;
        }
        s = lerp(hard, soft, p.gamutStrength);
    }
    return psycho69CoordFromDYfT(r.coord.x * s, a, r.coord.z * s);
}

// Source-boundary response cage over the pinned BT.709 source gamut
// (reference SourceCage with FromSource identity). Returns false when the direct
// map must be kept.
bool psycho69SourceCage(float3 anchoredSource, Psycho69Response desired,
        Psycho69Prepared p, out float3 cageCoord) {
    cageCoord = float3(0.0);
    float neutral = psycho69Yf(psycho69ConeFrom709(anchoredSource));
    if (neutral <= PSYCHO69_MIN_NORMAL) {
        return false;
    }
    float3 residual = anchoredSource - float3(neutral);
    float pressure = max(max(-residual.x / neutral, 0.0),
            max(-residual.y / neutral, -residual.z / neutral));
    // At exact/nearly neutral chroma use the direct map; source direction has
    // no meaningful boundary there. This is an explicit conditioning threshold.
    if (pressure <= 1.0e-6) {
        return false;
    }
    float3 boundary = max(float3(neutral) + residual / pressure, float3(0.0));
    Psycho69Response nr = psycho69Respond(float3(neutral), p);
    Psycho69Response br = psycho69Respond(boundary, p);
    float3 targetEndpoint = psycho69MapCoord(br, p);
    float da = psycho69CoordYf(br.coord) - nr.yf;
    // Normalize the chord before squaring; protects small dark chords.
    float scale = max(max(abs(br.coord.x), abs(br.coord.z)), abs(da));
    if (scale <= PSYCHO69_MIN_NORMAL) {
        return false;
    }
    float d = br.coord.x / scale;
    float t = br.coord.z / scale;
    float a = da / scale;
    float len2 = 0.5 * d * d + t * t / 6.0 + a * a;
    float progress = clamp(
            (0.5 * (desired.coord.x / scale) * d
             + (desired.coord.z / scale) * t / 6.0
             + ((psycho69CoordYf(desired.coord) - nr.yf) / scale) * a) / len2,
            0.0, 1.0);
    cageCoord = psycho69CoordFromDYfT(
            progress * targetEndpoint.x,
            lerp(nr.yf, psycho69CoordYf(targetEndpoint), progress),
            progress * targetEndpoint.z);
    return !any(isnan(cageCoord)) && !any(isinf(cageCoord));
}

// PsychoV69 core over the pinned integration profile. peakRatio is the
// display peak over effective white (the reference's peak_nits/white_nits with
// white pinned to 1.0). Returns the contained target-basis result in
// white-relative units (the reference's result.target_rgb; for the SDR BT.709
// target this is exactly the reference's result.rgb).
public float3 psychoV69(float3 bt709SceneLinear, float peakRatio, int targetGamutMode,
        float compression, float gamutCompression, float highlights, float shadows,
        float contrast, float purity, float exposure, float sourceAwareness) {
    Psycho69Prepared p = psycho69Prepare(peakRatio, targetGamutMode, compression,
            gamutCompression, highlights, shadows, contrast, purity, exposure,
            sourceAwareness);
    if (any(isnan(bt709SceneLinear)) || any(isinf(bt709SceneLinear))) {
        return float3(0.0);
    }
    // Bound before scaling so a finite FP32 input cannot overflow exposure.
    float3 safe = clamp(bt709SceneLinear, float3(-PSYCHO69_MAX_SCENE), float3(PSYCHO69_MAX_SCENE));
    float3 exposed = clamp(safe * (p.exposure / 1.0),
            float3(-PSYCHO69_MAX_SCENE), float3(PSYCHO69_MAX_SCENE));
    if (max(abs(exposed.x), max(abs(exposed.y), abs(exposed.z))) == 0.0) {
        return float3(0.0);
    }
    // Source boundary pinned to BT.709: ToSource/FromSource are identity, and
    // out-of-gamut source color is anchored to the boundary at fixed Yf.
    float3 source = exposed;
    if (min(source.x, min(source.y, source.z)) < 0.0) {
        float y = psycho69Yf(psycho69ConeFrom709(source));
        float3 bounded = max(source, float3(0.0));
        float yb = psycho69Yf(psycho69ConeFrom709(bounded));
        source = (y > 0.0 && yb > 0.0) ? bounded * (y / yb) : float3(0.0);
        exposed = source;
    }
    Psycho69Response desired = psycho69Respond(exposed, p);
    float3 mapped = psycho69MapCoord(desired, p);
    if (p.sourceAwareness > 0.0) {
        float3 cageCoord;
        if (psycho69SourceCage(source, desired, p, cageCoord)) {
            mapped = lerp(mapped, cageCoord, p.sourceAwareness);
        }
    }
    float3 target = psycho69TargetFromCoord(mapped, p.targetGamut) * p.peak;
    if (any(isnan(target)) || any(isinf(target))) {
        return float3(0.0);
    }
    return psycho69ContainTarget(target, p.peak, p.targetGamut);
}
