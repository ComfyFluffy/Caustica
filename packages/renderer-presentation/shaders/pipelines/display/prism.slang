/*
 * Prism tone mapping, adapted from prismTM.hlsl (stripped RenoDX prism.hlsl).
 * Copyright (c) 2026 Musa Haji
 * Copyright (c) 2026 OopyDoopy / KickFister / Jon
 * SPDX-License-Identifier: MIT
 *
 * The working-space primaries were derived by TheGreatHmmmmm as a perceptual
 * starting point. They are runtime parameters here (the SDR and HDR prism
 * model-red/green/blue x/y options), with the inset/outset matrices rebuilt
 * per evaluate from the reference's rgbToXyz/prismInsetMatrix construction
 * (BT.709 D65 source primaries); the shoulder matches
 * anchoredCInfinityShoulder. The default primaries reproduce the reference's
 * baked matrices.
 */

// BT.709 source primaries and the D65 white point the reference anchors everything to.
static const float2 PRISM_WHITE = float2(0.3127, 0.3290);
static const float2 PRISM_RED = float2(0.64, 0.33);
static const float2 PRISM_GREEN = float2(0.30, 0.60);
static const float2 PRISM_BLUE = float2(0.15, 0.06);

static const float PRISM_MIN_COMPRESSION = 1.0e-4;
static const float PRISM_MIN_ANCHOR = 0.0;

// Reference divideSafe, scalar lanes: fall back when the divisor is exactly zero.
float prismDivideSafe(float numerator, float denominator, float fallback) {
    return abs(denominator) > 0.0 ? numerator / denominator : fallback;
}

// Reference invert3x3, verbatim cofactor expansion.
float3x3 prismInvert3x3(float3x3 m) {
    float a = m[0][0], b = m[0][1], c = m[0][2];
    float d = m[1][0], e = m[1][1], f = m[1][2];
    float g = m[2][0], h = m[2][1], i = m[2][2];
    float A = (e * i - f * h);
    float B = -(d * i - f * g);
    float C = (d * h - e * g);
    float D = -(b * i - c * h);
    float E = (a * i - c * g);
    float F = -(a * h - b * g);
    float G = (b * f - c * e);
    float H = -(a * f - c * d);
    float I = (a * e - b * d);
    float det = a * A + b * B + c * C;
    float invDet = prismDivideSafe(1.0, det, 0.0);
    return float3x3(
        A, D, G,
        B, E, H,
        C, F, I) * invDet;
}

// Reference xyzFromChromaticity / rgbToXyz: RGB -> XYZ for the given primaries,
// with the white point at Y = 1. The matrix columns are the primaries.
float3 prismXyzFromChromaticity(float2 xy) {
    return float3(xy.x, xy.y, 1.0 - xy.x - xy.y) / max(xy.y, 1.0e-6);
}

float3x3 prismRgbToXyz(float2 red, float2 green, float2 blue, float2 white) {
    float3x3 primaries = transpose(float3x3(
        prismXyzFromChromaticity(red),
        prismXyzFromChromaticity(green),
        prismXyzFromChromaticity(blue)));
    float3 scale = mul(prismInvert3x3(primaries), prismXyzFromChromaticity(white));
    return mul(primaries, float3x3(
        scale.x, 0.0, 0.0,
        0.0, scale.y, 0.0,
        0.0, 0.0, scale.z));
}

// Reference prismInsetMatrix: BT.709 -> working basis, the inverse of the model
// -> BT.709 matrix, so BT.709 colors land desaturated. Applied first,
// inverted on the way out.
float3x3 prismInsetMatrix(float2 modelRed, float2 modelGreen, float2 modelBlue) {
    float3x3 bt709 = prismRgbToXyz(PRISM_RED, PRISM_GREEN, PRISM_BLUE, PRISM_WHITE);
    float3x3 model = prismRgbToXyz(modelRed, modelGreen, modelBlue, PRISM_WHITE);
    return mul(prismInvert3x3(bt709), model);
}

// Reference safeDiv, vector lanes.
float3 prismSafeDiv(float3 a, float3 b, float3 fallback) {
    float3 denomOk = float3(abs(b) > float3(1.0e-12));
    return lerp(fallback, a / max(abs(b), float3(1.0e-12)), denomOk);
}

// Per-channel C-infinity shoulder toward peak; values at or below the anchor
// pass through unchanged. Matches the reference exactly, with the zero-distance
// branch made explicit so no infinity reaches exp2.
float3 prismShoulder(float3 color, float3 peak, float anchor, float compression) {
    float3 anchor3 = float3(max(anchor, PRISM_MIN_ANCHOR));
    float compressionSafe = max(compression, PRISM_MIN_COMPRESSION);
    float3 shoulderRange = max(peak - anchor3, float3(0.0));
    float3 distanceFromAnchor = max(color - anchor3, float3(0.0));
    float3 hasDistance = float3(distanceFromAnchor > float3(0.0));
    float3 ratio = shoulderRange / max(compressionSafe * distanceFromAnchor, float3(1.0e-12));
    float3 flatWeight = lerp(float3(0.0), exp2(-ratio), hasDistance);
    float3 responseDenominator = distanceFromAnchor * flatWeight + shoulderRange;
    float3 shoulder = shoulderRange * prismSafeDiv(distanceFromAnchor, responseDenominator, float3(0.0));
    return shoulder + color - distanceFromAnchor;
}

// peakValue is the display peak over effective white; returns linear BT.709.
public float3 prismTonemap(float3 color, float peakValue, float anchor, float compression,
        float2 modelRed, float2 modelGreen, float2 modelBlue) {
    float3x3 inset = prismInsetMatrix(modelRed, modelGreen, modelBlue);
    float3x3 outset = prismInvert3x3(inset);
    float3 working = mul(inset, max(color, float3(0.0)));
    float3 mapped = prismShoulder(working, float3(max(peakValue, 1.0e-6)), anchor, compression);
    return mul(outset, mapped);
}
