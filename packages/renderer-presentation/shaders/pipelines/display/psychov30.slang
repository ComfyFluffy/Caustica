/*
 * Adapted from the RenoDX PsychoV family's Test29/Test30 cores
 * (test30.hlsl over test29.hlsl) at the PsychoV30 integration snapshot.
 * Copyright (C) 2026 Carlos Lopez
 * SPDX-License-Identifier: MIT
 *
 * The Caustica integration profile pins the reference wrapper's compatibility
 * arguments: exposure 1, cone response exponent 1, adaptation and background
 * anchors 0.18/0.18, source boundary BT.709, and the reference's trailing Lq
 * defaults. Only the wrapper's own parameters stay runtime-adjustable:
 * peak, compression (0 = automatic), gamut compression strength, highlights,
 * shadows, contrast, and purity. The target gamut mode (0 = BT.709 cube,
 * 1 = BT.2020 cube) follows the output path: SDR constrains to the BT.709
 * cube, HDR to the BT.2020 cube.
 *
 * Dead branches of the reference are omitted: the NONE/BT.2020/AP1
 * source-boundary transports, psycho29's Neutwo support scales (generalized
 * mix pinned to 1), psycho29's per-hue dynamic q search (dynamic mix pinned
 * to 0), and psycho29's orthonormal Yf-ceiling polygon solve (not called by
 * the Test30/31 fixed profile).
 *
 * The public symbols below are the Test30 layer that the PsychoV31 core in
 * psychov31.slang builds on.
 */

public static const float PSYCHO30_EPSILON = 1.0e-6;
public static const float PSYCHO30_EPSILON2 = PSYCHO30_EPSILON * PSYCHO30_EPSILON;
static const float PSYCHO30_AUTO_COMPRESSION_SENTINEL = 0.0;
static const float PSYCHO30_LARGE_SUPPORT = 1.0e20;
static const float PSYCHO30_REFERENCE_SIMULTANEOUS_RANGE_LOG10 = 3.7;
static const float PSYCHO30_HIGHLIGHT_GRADE_REFERENCE_WHITE = 1.0;
static const float PSYCHO30_SHADOW_GRADE_RANGE_STOPS = 4.0;
public static const float3 PSYCHO30_YF_ROW = float3(0.68990272, 0.34832189, 0.0);
static const float3 PSYCHO30_MB_WEIGHTS = float3(0.68990272, 0.34832189, 0.0371597069161);

// Float32 literals of the reference construction in row-major notation: mul(M, v)
// is the reference's mul(M, v), and a primary's LMS is column j of M.
public static const float3x3 PSYCHO30_BT709_TO_LMS_MAT = float3x3(
    0.2896057665348053, 0.6972466707229614, 0.07637128233909607,
    0.09018375724554062, 0.7073490023612976, 0.11220314353704453,
    0.015528740361332893, 0.05360930785536766, 0.5097978115081787);

public static const float3x3 PSYCHO30_LMS_TO_BT709_MAT = float3x3(
    4.9676194190979, -4.922379493713379, 0.3391991853713989,
    -0.6196829080581665, 2.051750659942627, -0.3587439954280853,
    -0.08615200221538544, -0.0658193826675415, 1.9889544248580933);

static const float3x3 PSYCHO30_LMS_TO_BT2020_MAT = float3x3(
    2.908921241760254, -2.4155642986297607, 0.18083429336547852,
    -0.22755327820777893, 1.5457966327667236, -0.28384271264076233,
    -0.050271231681108475, 0.04094895347952843, 1.7552839517593384);

public static const float3 PSYCHO30_D65_WHITE_LMS = float3(1.0632237195968628, 0.909735918045044, 0.5789358615875244);

public static const float PSYCHO30_D65_WHITE_YF = 1.0504018068313599;

public static const float PSYCHO30_D65_ALPHA_L = 0.6983240842819214;

public static const float PSYCHO30_D65_ALPHA_M = 0.3016759157180786;

public static const float PSYCHO30_D65_ALPHA_DELTA = 0.3966481685638428;

public static const float3 PSYCHO30_BT709_D_RGB = float3(4.681554317474365, -1.4610304832458496, -0.21418438851833344);

public static const float3 PSYCHO30_BT709_T_RGB = float3(0.09818729013204575, -0.10384488105773926, 0.5757385492324829);

public static const float3 PSYCHO30_BT2020_D_RGB = float3(2.4468557834625244, -1.0224274396896362, -0.24367523193359375);

public static const float3 PSYCHO30_BT2020_T_RGB = float3(0.05234573036432266, -0.08216336369514465, 0.5080984234809875);

public static const float3 PSYCHO30_BT709_SOURCE_YF_WEIGHTS = float3(0.2201184183359146, 0.6925135254859924, 0.08736805617809296);

public float psycho30YfFromLMS(float3 lms) {
    return dot(PSYCHO30_YF_ROW, lms);
}

public float psycho30Max3(float3 v) {
    return max(v.x, max(v.y, v.z));
}

public float psycho30Min3(float3 v) {
    return min(v.x, min(v.y, v.z));
}

// Bit-exact renodx::math::CopySign: the sign bit of signSource, including -0.
float psycho30CopySignScalar(float magnitude, float signSource) {
    return asfloat(
            (asuint(magnitude) & 0x7fffffffu)
            | (asuint(signSource) & 0x80000000u));
}

public float3 psycho30CopySign(float3 magnitude, float3 signSource) {
    return asfloat(
            (asuint(magnitude) & uint3(0x7fffffffu))
            | (asuint(signSource) & uint3(0x80000000u)));
}

float3 psycho30SignPow(float3 x, float exponent) {
    return psycho30CopySign(pow(abs(x), float3(exponent)), x);
}

float psycho30Log10(float x) {
    return log(x) / log(10.0);
}

float psycho30QuinticUnitRamp(float t) {
    t = clamp(t, 0.0, 1.0);
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

float psycho30HighlightsScalar(float x, float highlights, float adaptedAnchorYf) {
    if (highlights == 1.0) {
        return x;
    }
    float ratio = x / adaptedAnchorYf;
    float t;
    if (x > adaptedAnchorYf) {
        t = clamp(log2(ratio)
                / log2(PSYCHO30_HIGHLIGHT_GRADE_REFERENCE_WHITE / adaptedAnchorYf), 0.0, 1.0);
    } else {
        t = 0.0;
    }
    t = psycho30QuinticUnitRamp(t);
    if (highlights > 1.0) {
        float shaped = pow(ratio, highlights - 1.0);
        return x * lerp(1.0, shaped, t);
    } else {
        float shaped = pow(ratio, 1.0 - highlights);
        return x / lerp(1.0, shaped, t);
    }
}

float psycho30ShadowsScalar(float x, float shadows, float adaptedAnchorYf) {
    if (shadows == 1.0) {
        return x;
    }
    float ratio = x / adaptedAnchorYf;
    float anchorSquared = adaptedAnchorYf * adaptedAnchorYf;
    float anchorCubed = anchorSquared * adaptedAnchorYf;
    float shadowFloor = adaptedAnchorYf * exp2(-PSYCHO30_SHADOW_GRADE_RANGE_STOPS);
    float t;
    if (x > shadowFloor) {
        t = clamp(log2(ratio) / log2(shadowFloor / adaptedAnchorYf), 0.0, 1.0);
    } else {
        t = 1.0;
    }
    t = psycho30QuinticUnitRamp(t);
    if (shadows > 1.0) {
        float shaped = pow(ratio, 2.0 - shadows);
        float target = x * (1.0 - anchorSquared) + anchorCubed * shaped;
        return lerp(x, target, t);
    } else {
        float shaped = pow(ratio, shadows);
        float target = x * (1.0 + anchorSquared) - anchorCubed * shaped;
        return lerp(x, target, t);
    }
}

// Direct adaptation-relative LMS interpolation toward neutral at fixed
// physiological Yf (reference psycho30_ApplyAdaptiveRelativePurity).
public float3 psycho30ApplyAdaptiveRelativePurity(float3 sourceLms, float3 anchorLms, float purityDelta) {
    float3 sourceQ = sourceLms / anchorLms;
    if (purityDelta == 1.0) {
        return sourceQ;
    }
    float relativeYf = psycho30YfFromLMS(sourceLms) / psycho30YfFromLMS(anchorLms);
    return lerp(float3(relativeYf), sourceQ, purityDelta);
}

float3 psycho30TargetRGBFromLMS(float3 lms, int targetGamutMode) {
    return targetGamutMode == 0
            ? mul(PSYCHO30_LMS_TO_BT709_MAT, lms)
            : mul(PSYCHO30_LMS_TO_BT2020_MAT, lms);
}

float psycho30TargetNeutralYfLimit(float targetRgbPeak, float3 anchorLms, int targetGamutMode) {
    float anchorYf = psycho30YfFromLMS(anchorLms);
    float3 anchorTargetRgb = psycho30TargetRGBFromLMS(anchorLms, targetGamutMode);
    return targetRgbPeak * anchorYf / psycho30Max3(anchorTargetRgb);
}

// Automatic h centers the chosen simultaneous log10 range around adaptation
// (reference psycho30_AutoCompressionPower).
float psycho30AutoCompressionPower(float anchorYf, float peakYf) {
    float aboveAdaptationRange = psycho30Log10(peakYf / anchorYf);
    return max(
            (PSYCHO30_REFERENCE_SIMULTANEOUS_RANGE_LOG10 * 0.5) / aboveAdaptationRange,
            1.0);
}

// Anchor-preserving, slope-normalized finite endpoint
// (reference psycho30_FiniteEndpoint).
float psycho30FiniteEndpoint(float x, float anchor, float peak, float h) {
    bool uniformResponse = h == 1.0;
    float anchorPower = anchor / peak;
    if (!uniformResponse) {
        anchorPower = pow(anchor / peak, h);
    }
    float slopeNormalization = 1.0 - anchorPower;
    float normalizedInput = x / anchor;
    float responseDrive = pow(normalizedInput, -h / slopeNormalization);
    float encoded = anchorPower
            / (anchorPower + slopeNormalization * responseDrive);
    float result = peak * encoded;
    if (!uniformResponse) {
        result = peak * pow(encoded, 1.0 / h);
    }
    return result;
}

// Normalized finite per-cone response (reference
// psycho30_FiniteResponseUFromPositiveQ). Requires all(sourceQ > 0).
float3 psycho30FiniteResponseUFromPositiveQ(float3 sourceQ, float3 adaptationPeakRatio,
        float responsePower, float responseH) {
    bool uniformResponse = responseH == 1.0;
    float3 anchorPower;
    if (uniformResponse) {
        anchorPower = adaptationPeakRatio;
    } else {
        anchorPower = pow(adaptationPeakRatio, float3(responseH));
    }
    float3 slopeNormalization = 1.0 - anchorPower;
    float3 inputExponent = responsePower * responseH / slopeNormalization;
    float3 responseDrive = pow(sourceQ, -inputExponent);
    float3 finiteEncoded = anchorPower
            / (anchorPower + slopeNormalization * responseDrive);
    if (uniformResponse) {
        return finiteEncoded;
    }
    return pow(finiteEncoded, float3(1.0 / responseH));
}

public float psycho30ScaledA2Radius6(float2 dt) {
    return 3.0 * dt.x * dt.x + dt.y * dt.y;
}

// Mean-A2 response coordinate from a positive cone state (reference
// psycho30_MeanA2ResponseFromPositiveQ). Requires all(sourceQ > 0).
float3 psycho30MeanA2ResponseFromPositiveQ(float3 sourceQ, float3 adaptationPeakRatio,
        float responsePower, float responseH, out float normalizedResponseYf) {
    float3 responseU = psycho30FiniteResponseUFromPositiveQ(
            sourceQ, adaptationPeakRatio, responsePower, responseH);
    float2 sourceDt = float2(sourceQ.x - sourceQ.y,
            2.0 * sourceQ.z - sourceQ.x - sourceQ.y);
    float responseLmsDifference = responseU.x - responseU.y;
    float responseLmsSum = responseU.x + responseU.y;
    float2 responseDt = float2(responseLmsDifference, 2.0 * responseU.z - responseLmsSum);
    float2 authoredDt = responseDt;
    float sourceRadius6 = psycho30ScaledA2Radius6(sourceDt);
    float responseRadius6 = psycho30ScaledA2Radius6(responseDt);

    if (sourceRadius6 > 6.0 * PSYCHO30_EPSILON2
            && responseRadius6 > 6.0 * PSYCHO30_EPSILON2) {
        float inverseResponseRadius = rsqrt(responseRadius6);
        float responseRadius = responseRadius6 * inverseResponseRadius;
        float2 meanDirection = sourceDt * rsqrt(sourceRadius6)
                + responseDt * inverseResponseRadius;
        float meanRadius6 = psycho30ScaledA2Radius6(meanDirection);
        if (meanRadius6 > PSYCHO30_EPSILON2) {
            authoredDt = meanDirection * (responseRadius * rsqrt(meanRadius6));
        }
    }

    normalizedResponseYf = PSYCHO30_D65_ALPHA_L * responseU.x
            + PSYCHO30_D65_ALPHA_M * responseU.y;
    return float3(authoredDt.x, responseLmsSum + responseU.z, authoredDt.y);
}

float2 psycho30ClosestPointOnScaleSegment(float desiredC, float radialMetric,
        float2 segmentStart, float2 segmentEnd) {
    float2 segment = segmentEnd - segmentStart;
    float denominator = 2.0 * segment.x * segment.x
            + radialMetric * segment.y * segment.y;
    if (denominator <= PSYCHO30_EPSILON2) {
        return segmentStart;
    }
    float numerator = 2.0 * (desiredC - segmentStart.x) * segment.x
            + radialMetric * (1.0 - segmentStart.y) * segment.y;
    float t = clamp(numerator / denominator, 0.0, 1.0);
    return segmentStart + segment * t;
}

// Exact nearest point for the fixed authored A2 direction
// (reference psycho30_YfCeilingSolve).
float3 psycho30YfCeilingSolve(float3 desiredCoord, float responseYf, int targetGamutMode) {
    float maxA = responseYf;
    float radialANumerator =
            3.0 * PSYCHO30_D65_ALPHA_DELTA * desiredCoord.x - desiredCoord.z;
    float desiredA = (2.0 * desiredCoord.y + radialANumerator) / 6.0;
    float3 radialRgb = targetGamutMode == 0
            ? desiredCoord.x * PSYCHO30_BT709_D_RGB
                    + desiredCoord.z * PSYCHO30_BT709_T_RGB
            : desiredCoord.x * PSYCHO30_BT2020_D_RGB
                    + desiredCoord.z * PSYCHO30_BT2020_T_RGB;
    float3 desiredTargetRgb = desiredA + radialRgb;
    if (desiredA >= 0.0
            && desiredA <= maxA
            && all(desiredTargetRgb >= float3(-PSYCHO30_EPSILON))
            && all(desiredTargetRgb <= float3(1.0 + PSYCHO30_EPSILON))) {
        return desiredCoord;
    }

    float radialMetric = psycho30ScaledA2Radius6(desiredCoord.xz);
    if (radialMetric <= 6.0 * PSYCHO30_EPSILON2) {
        return float3(0.0, clamp(desiredCoord.y, 0.0, 3.0 * maxA), 0.0);
    }

    float positivePressure = psycho30Max3(radialRgb);
    float negativePressure = -psycho30Min3(radialRgb);

    // Build the same polygon from its triangle or quadrilateral pressure case.
    float totalPressure = positivePressure + negativePressure;
    bool isTriangle = maxA * totalPressure <= negativePressure;
    float maxARadialScale;
    float upperA;
    float upperRadialScale;
    if (isTriangle) {
        maxARadialScale = maxA / negativePressure;
        upperA = maxA;
        upperRadialScale = maxARadialScale;
    } else {
        maxARadialScale = (1.0 - maxA) / positivePressure;
        upperRadialScale = 1.0 / totalPressure;
        upperA = negativePressure * upperRadialScale;
    }
    float2 vertex0 = float2(0.0);
    float2 vertex1 = float2(3.0 * maxA, 0.0);
    float2 vertex2 = float2(
            3.0 * maxA - 0.5 * radialANumerator * maxARadialScale,
            maxARadialScale);
    float2 vertex3 = float2(
            3.0 * upperA - 0.5 * radialANumerator * upperRadialScale,
            upperRadialScale);
    // The first edge has constant zero radial scale, so no projection divide.
    float2 bestCScale = float2(clamp(desiredCoord.y, vertex0.x, vertex1.x), 0.0);
    float2 bestDelta = bestCScale - float2(desiredCoord.y, 1.0);
    float bestCost = 2.0 * bestDelta.x * bestDelta.x
            + radialMetric * bestDelta.y * bestDelta.y;

    float2 edgeCandidate = psycho30ClosestPointOnScaleSegment(
            desiredCoord.y, radialMetric, vertex1, vertex2);
    float2 edgeDelta = edgeCandidate - float2(desiredCoord.y, 1.0);
    float edgeCost = 2.0 * edgeDelta.x * edgeDelta.x
            + radialMetric * edgeDelta.y * edgeDelta.y;
    if (edgeCost < bestCost) {
        bestCScale = edgeCandidate;
        bestCost = edgeCost;
    }

    if (!isTriangle) {
        edgeCandidate = psycho30ClosestPointOnScaleSegment(
                desiredCoord.y, radialMetric, vertex2, vertex3);
        edgeDelta = edgeCandidate - float2(desiredCoord.y, 1.0);
        edgeCost = 2.0 * edgeDelta.x * edgeDelta.x
                + radialMetric * edgeDelta.y * edgeDelta.y;
        if (edgeCost < bestCost) {
            bestCScale = edgeCandidate;
            bestCost = edgeCost;
        }
    }

    edgeCandidate = psycho30ClosestPointOnScaleSegment(
            desiredCoord.y, radialMetric, vertex0, vertex3);
    edgeDelta = edgeCandidate - float2(desiredCoord.y, 1.0);
    edgeCost = 2.0 * edgeDelta.x * edgeDelta.x
            + radialMetric * edgeDelta.y * edgeDelta.y;
    // The reference leaves bestCost stale on this final edge; preserved exactly.
    if (edgeCost < bestCost) {
        bestCScale = edgeCandidate;
    }

    return float3(desiredCoord.x * bestCScale.y, bestCScale.x, desiredCoord.z * bestCScale.y);
}

// Source-boundary anchoring (reference psycho30_AnchorSourceBoundaryToYf). The
// positive branch covers every in-gamut input; the residual branch keeps the
// out-of-gamut continuation defined.
float3 psycho30AnchorSourceBoundaryToYf(float3 sourceRgb, float3x3 sourceToLms,
        float3 sourceYfWeights, out float3 anchoredSourceRgb) {
    if (all(sourceRgb >= float3(0.0))) {
        anchoredSourceRgb = sourceRgb;
        return mul(sourceToLms, sourceRgb);
    } else {
        float sourceTotal = dot(max(sourceRgb, float3(0.0)), sourceYfWeights);
        float3 residual = sourceRgb - sourceTotal;
        float3 lowerFraction = float3(
                residual.x < -PSYCHO30_EPSILON
                        ? sourceTotal / -residual.x : PSYCHO30_LARGE_SUPPORT,
                residual.y < -PSYCHO30_EPSILON
                        ? sourceTotal / -residual.y : PSYCHO30_LARGE_SUPPORT,
                residual.z < -PSYCHO30_EPSILON
                        ? sourceTotal / -residual.z : PSYCHO30_LARGE_SUPPORT);
        float boundaryFraction = min(1.0, psycho30Min3(lowerFraction));
        anchoredSourceRgb = sourceTotal + residual * boundaryFraction;
        float3 boundedLms = mul(sourceToLms, anchoredSourceRgb);
        float boundedYf = psycho30YfFromLMS(boundedLms);
        float scale = sourceTotal * PSYCHO30_D65_WHITE_YF / boundedYf;
        anchoredSourceRgb *= scale;
        return boundedLms * scale;
    }
}

// Signed-fallback MacLeod-Boynton helpers (reference psycho30_AdaptiveNeutralMB,
// psycho30_MBFromRelativeLMS).
float2 psycho30AdaptiveNeutralMB() {
    float lmWeightSum = PSYCHO30_MB_WEIGHTS.x + PSYCHO30_MB_WEIGHTS.y;
    return float2(PSYCHO30_MB_WEIGHTS.x, PSYCHO30_MB_WEIGHTS.z) / lmWeightSum;
}

float2 psycho30MBFromRelativeLMS(float3 relativeLms) {
    float yf = psycho30YfFromLMS(relativeLms);
    return float2(
            relativeLms.x * PSYCHO30_MB_WEIGHTS.x / yf,
            relativeLms.z * PSYCHO30_MB_WEIGHTS.z / yf);
}

float3 psycho30ApplySignedConeResponseFallback(float3 sourceRelativeLms, float responsePower) {
    if (responsePower == 1.0) {
        return sourceRelativeLms;
    }
    return psycho30SignPow(sourceRelativeLms, responsePower);
}

public float2 psycho30ScaledA2FromQ(float3 q) {
    return float2(q.x - q.y, 2.0 * q.z - q.x - q.y);
}

float3 psycho30LMSFromScaledA2(float2 scaledA2, float physicalYf, float3 anchorLms) {
    float aL = PSYCHO30_YF_ROW.x * anchorLms.x;
    float aM = PSYCHO30_YF_ROW.y * anchorLms.y;
    float qM = (physicalYf - aL * scaledA2.x) / (aL + aM);
    float qL = qM + scaledA2.x;
    float qS = 0.5 * (scaledA2.y + qL + qM);
    return float3(qL, qM, qS) * anchorLms;
}

float psycho30ScaledA2TargetSupport(float2 direction, float clipMagnitude, float physicalYf,
        float3 anchorLms, int targetGamutMode, float targetRgbPeak) {
    if (clipMagnitude <= PSYCHO30_EPSILON) {
        return 0.0;
    }
    float3 neutralTarget = psycho30TargetRGBFromLMS(
            psycho30LMSFromScaledA2(float2(0.0), physicalYf, anchorLms), targetGamutMode);
    float3 unitTarget = psycho30TargetRGBFromLMS(
            psycho30LMSFromScaledA2(direction, physicalYf, anchorLms), targetGamutMode);
    float3 deltaTarget = unitTarget - neutralTarget;
    float support = clipMagnitude;
    for (int channel = 0; channel < 3; ++channel) {
        if (deltaTarget[channel] > PSYCHO30_EPSILON) {
            support = min(support,
                    (targetRgbPeak - neutralTarget[channel]) / deltaTarget[channel]);
        } else if (deltaTarget[channel] < -PSYCHO30_EPSILON) {
            support = min(support,
                    neutralTarget[channel] / -deltaTarget[channel]);
        }
    }
    return max(support, 0.0);
}

float psycho30Cross2(float2 a, float2 b) {
    return a.x * b.y - a.y * b.x;
}

float psycho30RaySegmentRadius(float2 origin, float2 direction, float2 a, float2 b) {
    float2 edge = b - a;
    float denominator = psycho30Cross2(direction, edge);
    if (abs(denominator) <= PSYCHO30_EPSILON) {
        return PSYCHO30_LARGE_SUPPORT;
    }
    float2 ao = a - origin;
    float t = psycho30Cross2(ao, edge) / denominator;
    float u = psycho30Cross2(ao, direction) / denominator;
    return t >= 0.0 && u >= 0.0 && u <= 1.0
            ? t
            : PSYCHO30_LARGE_SUPPORT;
}

float psycho30TransformedSourceClipScaledA2Magnitude(float2 sourceMb, float responsePower,
        float3 responseAnchorRatio, float physicalYf, float3 anchorLms) {
    float2 neutralMb = psycho30AdaptiveNeutralMB();
    float2 sourceOffset = sourceMb - neutralMb;
    float sourceRadius2 = dot(sourceOffset, sourceOffset);
    if (sourceRadius2 <= PSYCHO30_EPSILON2) {
        return 0.0;
    }

    float2 vertices[3];
    for (int channel = 0; channel < 3; ++channel) {
        float3 primaryLms = float3(PSYCHO30_BT709_TO_LMS_MAT[0][channel],
                PSYCHO30_BT709_TO_LMS_MAT[1][channel], PSYCHO30_BT709_TO_LMS_MAT[2][channel]);
        vertices[channel] = psycho30MBFromRelativeLMS(primaryLms / anchorLms);
    }
    float2 sourceDirection = sourceOffset * rsqrt(sourceRadius2);
    float sourceBoundaryRadius = min(
            psycho30RaySegmentRadius(neutralMb, sourceDirection, vertices[0], vertices[1]),
            min(
                    psycho30RaySegmentRadius(neutralMb, sourceDirection, vertices[1], vertices[2]),
                    psycho30RaySegmentRadius(neutralMb, sourceDirection, vertices[2], vertices[0])));
    if (sourceBoundaryRadius >= PSYCHO30_LARGE_SUPPORT) {
        return 0.0;
    }

    float2 boundaryMb = neutralMb + sourceDirection * sourceBoundaryRadius;
    float mFraction = 1.0 - boundaryMb.x;
    if (boundaryMb.x <= PSYCHO30_EPSILON
            || mFraction <= PSYCHO30_EPSILON
            || boundaryMb.y <= PSYCHO30_EPSILON) {
        return 0.0;
    }
    float2 boundaryResponseRatio = float2(
            boundaryMb.x * PSYCHO30_MB_WEIGHTS.y / (mFraction * PSYCHO30_MB_WEIGHTS.x),
            boundaryMb.y * PSYCHO30_MB_WEIGHTS.y / (mFraction * PSYCHO30_MB_WEIGHTS.z));
    float2 responseRatio;
    if (responsePower == 1.0) {
        responseRatio = boundaryResponseRatio;
    } else {
        responseRatio = pow(boundaryResponseRatio, float2(responsePower));
    }
    responseRatio *= float2(
            responseAnchorRatio.x / responseAnchorRatio.y,
            responseAnchorRatio.z / responseAnchorRatio.y);
    float3 boundaryQDirection = float3(responseRatio.x, 1.0, responseRatio.y);
    float boundaryQM = physicalYf
            / psycho30YfFromLMS(anchorLms * boundaryQDirection);
    float3 boundaryQAtYf = boundaryQDirection * boundaryQM;
    return sqrt(psycho30ScaledA2Radius6(psycho30ScaledA2FromQ(boundaryQAtYf)));
}

float psycho30NeutwoWithClip(float x, float peak, float clip, float h) {
    if (peak <= PSYCHO30_EPSILON) {
        return 0.0;
    }
    if (clip <= peak * (1.0 + PSYCHO30_EPSILON)) {
        return min(x, peak);
    }
    float q = clamp(x / clip, 0.0, 1.0);
    float k = clamp(peak / clip, 0.0, 1.0);
    if (h == 1.0) {
        float denominator = q * (1.0 - k) + k;
        return peak * q / denominator;
    } else {
        float qh = pow(q, h);
        float kh = pow(k, h);
        float denominator = pow(qh * (1.0 - kh) + kh, 1.0 / h);
        return peak * q / denominator;
    }
}

// Signed fallback for finite adaptation-relative LMS containing a zero or
// negative cone value (reference psycho30_LinearA2Fallback).
float3 psycho30LinearA2Fallback(float3 sourceQ, float3 anchorInLms, float3 anchorOutLms,
        int targetGamutMode, float targetRgbPeak, float responsePower, float responseH,
        float targetCompressionStrength) {
    float3 responseLms = anchorOutLms
            * psycho30ApplySignedConeResponseFallback(sourceQ, responsePower);
    float responseYf = psycho30YfFromLMS(responseLms);

    float2 sourceScaledA2 = psycho30ScaledA2FromQ(sourceQ);
    float2 responseScaledA2 = psycho30ScaledA2FromQ(responseLms / anchorInLms);
    float sourceRadius6 = psycho30ScaledA2Radius6(sourceScaledA2);
    float responseRadius6 = psycho30ScaledA2Radius6(responseScaledA2);
    float3 authoredLms = responseLms;
    if (sourceRadius6 > 6.0 * PSYCHO30_EPSILON2
            && responseRadius6 > 6.0 * PSYCHO30_EPSILON2) {
        float inverseSourceRadius = rsqrt(sourceRadius6);
        float inverseResponseRadius = rsqrt(responseRadius6);
        float responseRadius = responseRadius6 * inverseResponseRadius;
        float2 midpoint = sourceScaledA2 * inverseSourceRadius
                + responseScaledA2 * inverseResponseRadius;
        float midpointRadius6 = psycho30ScaledA2Radius6(midpoint);
        if (midpointRadius6 > PSYCHO30_EPSILON2) {
            authoredLms = psycho30LMSFromScaledA2(
                    midpoint * rsqrt(midpointRadius6) * responseRadius,
                    responseYf,
                    anchorInLms);
        }
    }

    float neutralYfLimit = psycho30TargetNeutralYfLimit(
            targetRgbPeak, anchorInLms, targetGamutMode);
    float anchorOutYf = psycho30YfFromLMS(anchorOutLms);
    float targetYf = psycho30CopySignScalar(
            psycho30FiniteEndpoint(abs(responseYf), anchorOutYf, neutralYfLimit, responseH),
            responseYf);

    float3 desiredLms;
    if (responseYf == 0.0 && targetYf == 0.0) {
        desiredLms = float3(0.0);
    } else {
        // Preserving adaptation-relative MB chromaticity while replacing
        // physical Yf is exactly uniform LMS scaling over this finite
        // nonzero-Yf domain.
        desiredLms = authoredLms * (targetYf / responseYf);
    }
    if (targetCompressionStrength == 0.0) {
        return desiredLms;
    } else if (targetYf <= 0.0) {
        return desiredLms * (1.0 - targetCompressionStrength);
    } else {
        float2 desiredScaledA2 = psycho30ScaledA2FromQ(desiredLms / anchorInLms);
        float desiredRadius6 = psycho30ScaledA2Radius6(desiredScaledA2);
        if (desiredRadius6 <= 6.0 * PSYCHO30_EPSILON2) {
            return anchorInLms
                    * (targetYf / psycho30YfFromLMS(anchorInLms));
        }

        float inverseDesiredMagnitude = rsqrt(desiredRadius6);
        float desiredMagnitude = desiredRadius6 * inverseDesiredMagnitude;
        float2 direction = desiredScaledA2 * inverseDesiredMagnitude;
        float sourceClipMagnitude = max(
                psycho30TransformedSourceClipScaledA2Magnitude(
                        psycho30MBFromRelativeLMS(sourceQ),
                        responsePower,
                        anchorOutLms / anchorInLms,
                        targetYf,
                        anchorInLms),
                desiredMagnitude);
        float targetSupport = psycho30ScaledA2TargetSupport(
                direction,
                sourceClipMagnitude,
                targetYf,
                anchorInLms,
                targetGamutMode,
                targetRgbPeak);
        float compressedMagnitude = min(
                psycho30NeutwoWithClip(
                        desiredMagnitude,
                        targetSupport,
                        max(sourceClipMagnitude, targetSupport),
                        responseH),
                targetSupport);
        return psycho30LMSFromScaledA2(
                direction
                        * lerp(desiredMagnitude, compressedMagnitude, targetCompressionStrength),
                targetYf,
                anchorInLms);
    }
}

// PsychoV30 core over the pinned integration profile (reference psychotm_test30).
// peakValue is the display peak over effective white; returns linear BT.709.
public float3 psychoV30(float3 bt709LinearInput, float peakValue, int gamutCompressionMode,
        float compression, float gamutCompression, float highlights, float shadows,
        float contrast, float purity) {
    // Extended-range transport to observer basis. Exposure is pinned to 1 and
    // the source boundary to BT.709 (the NONE/BT.2020/AP1 transports are
    // unreachable under the integration profile).
    float3 exposedInput = bt709LinearInput;
    float3 inputLms;
    if (all(exposedInput <= float3(0.0)) && !any(isinf(exposedInput))) {
        return float3(0.0);
    }
    float3 anchoredSourceRgb;
    inputLms = psycho30AnchorSourceBoundaryToYf(
            exposedInput,
            PSYCHO30_BT709_TO_LMS_MAT,
            PSYCHO30_BT709_SOURCE_YF_WEIGHTS,
            anchoredSourceRgb);

    // Adaptation and background anchors are both pinned to 0.18, so the
    // output anchor equals the input anchor.
    float3 anchorInLms = mul(PSYCHO30_BT709_TO_LMS_MAT, float3(0.18));
    float3 anchorOutLms = anchorInLms;

    // Observer-basis controls: scalar physiological-Yf grading followed by
    // adaptation-relative LMS purity.
    float3 gradedLms;
    if (highlights != 1.0 || shadows != 1.0) {
        gradedLms = abs(inputLms);
        float gradedYf = psycho30YfFromLMS(gradedLms);
        float adaptedAnchorYf = psycho30YfFromLMS(anchorInLms);
        float gradedYfOut = psycho30HighlightsScalar(gradedYf, highlights, adaptedAnchorYf);
        gradedYfOut = psycho30ShadowsScalar(gradedYfOut, shadows, adaptedAnchorYf);
        gradedLms *= gradedYfOut / gradedYf;
        gradedLms = psycho30CopySign(gradedLms, inputLms);
    } else {
        gradedLms = inputLms;
    }

    float responsePower = contrast;              // contrast * coneResponseExponent (pinned 1)
    float purityDelta = purity / contrast;
    float3 responseInputQ = psycho30ApplyAdaptiveRelativePurity(
            gradedLms, anchorInLms, purityDelta);

    // Positive finite-G response and Mean-A2 direction authoring.
    float targetRgbPeak = peakValue;
    float responseH;
    if (compression == PSYCHO30_AUTO_COMPRESSION_SENTINEL) {
        responseH = psycho30AutoCompressionPower(
                psycho30YfFromLMS(anchorOutLms),
                psycho30TargetNeutralYfLimit(targetRgbPeak, anchorInLms, gamutCompressionMode));
    } else {
        responseH = compression;
    }

    if (any(responseInputQ <= float3(0.0))) {
        // An actual zero or negative cone component selects the signed path.
        float3 fallbackLms = psycho30LinearA2Fallback(
                responseInputQ,
                anchorInLms,
                anchorOutLms,
                gamutCompressionMode,
                targetRgbPeak,
                responsePower,
                responseH,
                gamutCompression);
        return mul(PSYCHO30_LMS_TO_BT709_MAT, fallbackLms);
    }

    float responseYf;
    float3 desiredCoord = psycho30MeanA2ResponseFromPositiveQ(
            responseInputQ,
            anchorOutLms / (PSYCHO30_D65_WHITE_LMS * targetRgbPeak),
            responsePower,
            responseH,
            responseYf);

    // Device mapping: exact fixed-direction projection into the selected
    // normalized RGB cube with the post-response physiological-Yf ceiling.
    float targetCompressionWeight = gamutCompression;
    float3 selectedCoord = desiredCoord;
    if (targetCompressionWeight != 0.0) {
        float3 solvedCoord = psycho30YfCeilingSolve(desiredCoord, responseYf, gamutCompressionMode);
        selectedCoord = targetCompressionWeight == 1.0
                ? solvedCoord
                : lerp(desiredCoord, solvedCoord, targetCompressionWeight);
    }

    // Direct inverse scaled-A2/Yf basis to linear BT.709.
    float outputA = (2.0 * selectedCoord.y
            + 3.0 * PSYCHO30_D65_ALPHA_DELTA * selectedCoord.x
            - selectedCoord.z) / 6.0;
    float3 outputBt709 = targetRgbPeak
            * (outputA
               + selectedCoord.x * PSYCHO30_BT709_D_RGB
               + selectedCoord.z * PSYCHO30_BT709_T_RGB);
    return outputBt709;
}
