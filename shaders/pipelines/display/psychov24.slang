/*
 * Adapted from RenoDX PsychoV Test24.
 * Pinned source commit: fc85b7b15585050442ba35412597ecefc9e04cea
 * Copyright (C) 2026 Carlos Lopez
 * SPDX-License-Identifier: MIT
 *
 * Caustica profile: fixed 0.18 adaptation/background anchors, SDR peak 1.0,
 * HDR peak from the configured headroom, and gamut hue restoration disabled.
 */

static const float PSYCHO24_EPSILON = 1.0e-6;
static const float PSYCHO24_TWO_PI = 6.2831853071795864769;
static const float PSYCHO24_REFERENCE_SIMULTANEOUS_RANGE_LOG10 = 3.7;
static const float PSYCHO24_REFERENCE_CENTERED_RANGE_SIDE_COUNT = 2.0;
static const float PSYCHO24_HEADROOM_RATIO_FALLBACK = 1.0;
static const float PSYCHO24_MIN_AUTO_COMPRESSION = 1.0;
static const float PSYCHO24_MIN_MANUAL_COMPRESSION = 1.0e-6;
static const float PSYCHO24_AUTO_COMPRESSION_SENTINEL = 0.0;
static const float PSYCHO24_HIGHLIGHT_GRADE_REFERENCE_WHITE = 1.0;
static const float PSYCHO24_SHADOW_GRADE_RANGE_STOPS = 4.0;

static const float3 PSYCHO24_LMS_WEIGHTS = float3(0.68990272, 0.34832189, 0.0371597069161);

static const float3x3 PSYCHO24_BT709_TO_LMS_WEIGHTED = float3x3(
    0.199799812973072, 0.481032356328408, 0.0526887528474167,
    0.0314129769005886, 0.246385149505685, 0.0390828120286937,
    0.000577043431732311, 0.00199210614746666, 0.0189439379660955);

static const float3x3 PSYCHO24_BT2020_TO_LMS_WEIGHTED = float3x3(
    0.270896642297020, 0.422251540658867, 0.0403727391345614,
    0.0207641953210756, 0.256739246826207, 0.0393774962470899,
    0.000366210725727965, 0.0000124019842897904, 0.0211344748353781);

static const float3x3 PSYCHO24_LMS_WEIGHTED_TO_BT709 = float3x3(
    7.20046392938217, -14.1316973475976, 9.12814468829434,
    -0.898217764419057, 5.89038676696474, -9.65411232470627,
    -0.124875582354962, -0.188961374460433, 53.5244932911426);

static const float3x3 PSYCHO24_XYZ_TO_LMS_WEIGHTED = float3x3(
    0.1842387318611145, 0.58448493480682373, -0.023942500352859497,
    -0.13482454419136047, 0.40594476461410522, 0.035885117948055267,
    0.00099319696892052889, -0.0010141372913494706, 0.019818264991044998);
static const float2 PSYCHO24_D65_XY = float2(0.31272, 0.32903);

float psycho24DivideSafe(float numerator, float denominator, float fallback) {
    return denominator == 0.0 ? fallback : numerator / denominator;
}

float3 psycho24DivideSafe(float3 numerator, float3 denominator, float3 fallback) {
    return float3(
        denominator.x == 0.0 ? fallback.x : numerator.x / denominator.x,
        denominator.y == 0.0 ? fallback.y : numerator.y / denominator.y,
        denominator.z == 0.0 ? fallback.z : numerator.z / denominator.z);
}

float3 psycho24LmsFromBt709(float3 bt709) {
    return mul(PSYCHO24_BT709_TO_LMS_WEIGHTED, bt709) / PSYCHO24_LMS_WEIGHTS;
}

float3 psycho24Bt709FromLms(float3 lms) {
    return mul(PSYCHO24_LMS_WEIGHTED_TO_BT709, lms * PSYCHO24_LMS_WEIGHTS);
}

float3 psycho24MbFromWeighted(float3 weightedLms) {
    float y = max(weightedLms.x + weightedLms.y, 0.0);
    float inverseY = psycho24DivideSafe(1.0, y, 0.0);
    return float3(weightedLms.x * inverseY, weightedLms.z * inverseY, y);
}

float3 psycho24WeightedFromMb(float3 mb) {
    return float3(mb.x, 1.0 - mb.x, mb.y) * mb.z;
}

float3 psycho24ToAdaptiveRelativeWeightedLms(float3 lmsInput, float3 adaptiveStateLms) {
    return psycho24DivideSafe(lmsInput * PSYCHO24_LMS_WEIGHTS,
            adaptiveStateLms, float3(0.0));
}

float3 psycho24FromAdaptiveRelativeWeightedLms(float3 relativeWeightedLms,
        float3 adaptiveStateLms) {
    return relativeWeightedLms * max(adaptiveStateLms, float3(PSYCHO24_EPSILON));
}

float3 psycho24UnweighLms(float3 weightedLms) {
    return weightedLms / PSYCHO24_LMS_WEIGHTS;
}

float2 psycho24Cie1702WhiteChromaticity() {
    float2 xy = PSYCHO24_D65_XY;
    float3 xyz = float3(xy.x / xy.y, 1.0, (1.0 - xy.x - xy.y) / xy.y);
    return psycho24MbFromWeighted(mul(PSYCHO24_XYZ_TO_LMS_WEIGHTED, xyz)).xy;
}

static const float PSYCHO24_CIE1702_RAY_T_MAX = 1.0e20;
static const float PSYCHO24_MB_NEAR_WHITE_EPSILON = 1.0e-14;
static const float PSYCHO24_INTERVAL_MAX = 3.402823466e+38;
static const int PSYCHO24_CIE1702_EDGE_COUNT = 7;
static const float2 PSYCHO24_CIE1702_HALFSPACE_NORMALS[PSYCHO24_CIE1702_EDGE_COUNT] = {
    float2(-0.043889, -0.006807),
    float2(-0.007821, -0.008564),
    float2(-0.000604, -0.007942),
    float2(0.0, -0.080835),
    float2(0.953597, 0.307020),
    float2(-0.060969, 0.019752),
    float2(-0.106895, 0.004035)
};
static const float PSYCHO24_CIE1702_HALFSPACE_NUMERATORS[PSYCHO24_CIE1702_EDGE_COUNT] = {
    0.0065035249,
    0.00104900495,
    0.000207697044,
    0.00165556648,
    0.252472349,
    0.0241967351,
    0.0199621232
};

float psycho24RayExitCie1702(float2 origin, float2 direction) {
    if (dot(direction, direction) <= PSYCHO24_MB_NEAR_WHITE_EPSILON) {
        return PSYCHO24_CIE1702_RAY_T_MAX;
    }

    float2 whiteToOrigin = psycho24Cie1702WhiteChromaticity() - origin;
    float tBest = PSYCHO24_CIE1702_RAY_T_MAX;
    bool hitAny = false;
    for (int i = 0; i < PSYCHO24_CIE1702_EDGE_COUNT; ++i) {
        float2 normal = PSYCHO24_CIE1702_HALFSPACE_NORMALS[i];
        float denominator = dot(normal, direction);
        float numerator = PSYCHO24_CIE1702_HALFSPACE_NUMERATORS[i]
                + dot(normal, whiteToOrigin);
        float t = denominator > 1.0e-8
                ? numerator * rcp(denominator)
                : PSYCHO24_CIE1702_RAY_T_MAX;
        tBest = min(tBest, t);
        hitAny = hitAny || denominator > 1.0e-8;
    }
    return hitAny ? max(tBest, 0.0) : PSYCHO24_CIE1702_RAY_T_MAX;
}

float psycho24RayExitCie1702FromWhite(float2 direction) {
    return psycho24RayExitCie1702(psycho24Cie1702WhiteChromaticity(), direction);
}

float psycho24Cross2(float2 a, float2 b) {
    return a.x * b.y - a.y * b.x;
}

bool psycho24RaySegmentHit2D(float2 origin, float2 direction, float2 a, float2 b,
        out float tHit) {
    tHit = 0.0;
    float2 edge = b - a;
    float denominator = psycho24Cross2(direction, edge);
    if (abs(denominator) <= 1.0e-20) {
        return false;
    }
    float2 aToOrigin = a - origin;
    float t = psycho24Cross2(aToOrigin, edge) / denominator;
    float u = psycho24Cross2(aToOrigin, direction) / denominator;
    if (t < 0.0 || u < 0.0 || u > 1.0) {
        return false;
    }
    tHit = t;
    return true;
}

float3 psycho24RgbToWeightedPrimary(float3x3 rgbToLmsWeighted, uint primaryIndex) {
    return float3(rgbToLmsWeighted[0][primaryIndex],
            rgbToLmsWeighted[1][primaryIndex], rgbToLmsWeighted[2][primaryIndex]);
}

float2 psycho24MbFromWeightedPrimary(float3 weightedPrimary) {
    return psycho24MbFromWeighted(weightedPrimary).xy;
}

void psycho24MakeRgbTriangleInMbAdaptiveWeighted(float3x3 rgbToLmsWeighted,
        float3 adaptiveStateLms, out float2 r, out float2 g, out float2 b) {
    r = psycho24MbFromWeightedPrimary(psycho24RgbToWeightedPrimary(rgbToLmsWeighted, 0)
            / adaptiveStateLms);
    g = psycho24MbFromWeightedPrimary(psycho24RgbToWeightedPrimary(rgbToLmsWeighted, 1)
            / adaptiveStateLms);
    b = psycho24MbFromWeightedPrimary(psycho24RgbToWeightedPrimary(rgbToLmsWeighted, 2)
            / adaptiveStateLms);
}

float psycho24RayMaxTRgbTriangleInMb(float2 origin, float2 direction,
        float2 r, float2 g, float2 b, out bool hasSolution) {
    hasSolution = false;
    if (dot(direction, direction) <= PSYCHO24_MB_NEAR_WHITE_EPSILON) {
        return 0.0;
    }

    float tBest = PSYCHO24_INTERVAL_MAX;
    float tHit;
    bool hitAny = false;
    if (psycho24RaySegmentHit2D(origin, direction, r, g, tHit)) {
        tBest = min(tBest, tHit);
        hitAny = true;
    }
    if (psycho24RaySegmentHit2D(origin, direction, g, b, tHit)) {
        tBest = min(tBest, tHit);
        hitAny = true;
    }
    if (psycho24RaySegmentHit2D(origin, direction, b, r, tHit)) {
        tBest = min(tBest, tHit);
        hitAny = true;
    }
    hasSolution = hitAny;
    return hitAny ? max(tBest, 0.0) : 0.0;
}

float psycho24NeutwoPeakClip(float x, float peak, float clip) {
    float peakSafe = max(peak, 0.0);
    float clipSafe = max(clip, peakSafe);
    float x2 = x * x;
    float clip2 = clipSafe * clipSafe;
    float peak2 = peakSafe * peakSafe;
    float denominatorSquared = mad(x2, clip2 - peak2, clip2 * peak2);
    return clipSafe * peakSafe * x * rsqrt(max(denominatorSquared, 1.0e-20));
}

float psycho24NeutwoScaleFromRayT(float tPeak, float tClip) {
    float tPeakSafe = max(tPeak, 0.0);
    float tClipSafe = max(tClip, tPeakSafe);
    return saturate(psycho24NeutwoPeakClip(1.0, tPeakSafe, tClipSafe));
}

float psycho24SoftCompressionActivationFromRayT(float tPeak) {
    float outside = 1.0 - saturate(tPeak);
    return psycho24DivideSafe(outside, outside + 0.08, 0.0);
}

float3 psycho24ClampWeightedLmsToCie1702(float3 weightedInput) {
    float3 weightedClamped = max(weightedInput, float3(0.0));
    float3 mb = psycho24MbFromWeighted(weightedClamped);
    float y = mb.z;
    if (!(y > 1.0e-20)) {
        return float3(weightedClamped.x, weightedClamped.y, 0.0);
    }

    float2 white = psycho24Cie1702WhiteChromaticity();
    float2 direction = mb.xy - white;
    if (dot(direction, direction) <= PSYCHO24_MB_NEAR_WHITE_EPSILON) {
        return weightedClamped;
    }
    float tClip = psycho24RayExitCie1702FromWhite(direction);
    float2 lsOut = white + direction * min(1.0, tClip);
    return psycho24WeightedFromMb(float3(lsOut, y));
}

float3 psycho24GamutCompressWeightedLmsCore(float3 weightedInput,
        float3x3 boundRgbToLmsWeighted, float3 adaptiveStateLms, float strength) {
    float3 weightedClamped = psycho24ClampWeightedLmsToCie1702(max(weightedInput, float3(0.0)));
    float3 mb = psycho24MbFromWeighted(weightedClamped);
    float y = mb.z;
    if (!(y > 1.0e-20)) {
        return float3(weightedClamped.x, weightedClamped.y, 0.0);
    }

    float2 white = psycho24Cie1702WhiteChromaticity();
    float2 direction = mb.xy - white;
    if (dot(direction, direction) <= PSYCHO24_MB_NEAR_WHITE_EPSILON) {
        return weightedClamped;
    }

    float2 boundR;
    float2 boundG;
    float2 boundB;
    psycho24MakeRgbTriangleInMbAdaptiveWeighted(
            boundRgbToLmsWeighted, adaptiveStateLms, boundR, boundG, boundB);
    bool hasPeak;
    float tPeak = psycho24RayMaxTRgbTriangleInMb(
            white, direction, boundR, boundG, boundB, hasPeak);
    float tClip = psycho24RayExitCie1702FromWhite(direction);
    if (!hasPeak) {
        tPeak = tClip;
    }

    float tHard = saturate(tPeak);
    float tSoft = psycho24NeutwoScaleFromRayT(min(tPeak, tClip), tClip);
    float softMix = saturate(strength) * psycho24SoftCompressionActivationFromRayT(tPeak);
    float tFinal = lerp(tHard, tSoft, softMix);
    return psycho24WeightedFromMb(float3(white + tFinal * direction, y));
}

float psycho24YfFromLms(float3 lms) {
    float3 weighted = lms * PSYCHO24_LMS_WEIGHTS;
    return max(weighted.x + weighted.y, PSYCHO24_EPSILON);
}

float psycho24QuinticUnitRamp(float t) {
    t = saturate(t);
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

float psycho24HighlightsScalarV4(float x, float highlights, float adaptedAnchorYf) {
    if (highlights == 1.0) {
        return x;
    }
    float t = 0.0;
    if (x > adaptedAnchorYf) {
        float referenceRangeLog2 = log2(PSYCHO24_HIGHLIGHT_GRADE_REFERENCE_WHITE
                / max(adaptedAnchorYf, PSYCHO24_EPSILON));
        t = saturate(log2(x / max(adaptedAnchorYf, PSYCHO24_EPSILON))
                / max(referenceRangeLog2, PSYCHO24_EPSILON));
    }
    t = psycho24QuinticUnitRamp(t);
    float ratio = max(x / max(adaptedAnchorYf, PSYCHO24_EPSILON), PSYCHO24_EPSILON);
    if (highlights > 1.0) {
        return lerp(x, adaptedAnchorYf * pow(ratio, highlights), t);
    }
    float b = adaptedAnchorYf * pow(ratio, 2.0 - highlights);
    return psycho24DivideSafe(x * x, lerp(x, b, t), x);
}

float psycho24ShadowsScalarV4(float x, float shadows, float adaptedAnchorYf) {
    if (shadows == 1.0) {
        return x;
    }
    float ratio = max(psycho24DivideSafe(x, adaptedAnchorYf, 0.0), 0.0);
    float baseTerm = x * adaptedAnchorYf;
    float baseScale = psycho24DivideSafe(baseTerm, ratio, 0.0);
    float shadowFloor = adaptedAnchorYf * exp2(-PSYCHO24_SHADOW_GRADE_RANGE_STOPS);
    float t = 1.0;
    if (x > shadowFloor) {
        t = saturate(log2(x / max(adaptedAnchorYf, PSYCHO24_EPSILON))
                / log2(shadowFloor / max(adaptedAnchorYf, PSYCHO24_EPSILON)));
    }
    t = psycho24QuinticUnitRamp(t);
    if (shadows > 1.0) {
        float raised = x * (1.0 + psycho24DivideSafe(baseTerm,
                pow(max(ratio, PSYCHO24_EPSILON), shadows), 0.0));
        float reference = x * (1.0 + baseScale);
        return x + (raised - reference) * t;
    }
    float lowered = x * (1.0 - psycho24DivideSafe(baseTerm,
            pow(max(ratio, PSYCHO24_EPSILON), 2.0 - shadows), 0.0));
    float reference = x * (1.0 - baseScale);
    return x + (lowered - reference) * t;
}

float psycho24AutoCompressionFromCenteredReferenceRange(float anchorOutYf, float peakYf) {
    float peakOverAnchor = psycho24DivideSafe(max(peakYf, PSYCHO24_EPSILON),
            max(anchorOutYf, PSYCHO24_EPSILON), PSYCHO24_HEADROOM_RATIO_FALLBACK);
    peakOverAnchor = max(peakOverAnchor, 1.0 + PSYCHO24_EPSILON);
    float referenceOneSideRangeLog10 = PSYCHO24_REFERENCE_SIMULTANEOUS_RANGE_LOG10
            / PSYCHO24_REFERENCE_CENTERED_RANGE_SIDE_COUNT;
    float actualAboveAdaptationRangeLog10 = max(log10(peakOverAnchor), PSYCHO24_EPSILON);
    return max(referenceOneSideRangeLog10 / actualAboveAdaptationRangeLog10,
            PSYCHO24_MIN_AUTO_COMPRESSION);
}

float3 psycho24ApplyAdaptiveMbPurity(float3 lmsInput, float3 adaptiveNeutralLms,
        float purityDelta) {
    if (abs(purityDelta - 1.0) <= 1.0e-5) {
        return lmsInput;
    }
    float3 relativeWeighted = psycho24ToAdaptiveRelativeWeightedLms(lmsInput, adaptiveNeutralLms);
    float3 mb = psycho24MbFromWeighted(relativeWeighted);
    float3 mbNeutral = psycho24MbFromWeighted(PSYCHO24_LMS_WEIGHTS);
    float2 mbScaled = lerp(mbNeutral.xy, mb.xy, purityDelta);
    return psycho24UnweighLms(psycho24FromAdaptiveRelativeWeightedLms(
            psycho24WeightedFromMb(float3(mbScaled, mb.z)), adaptiveNeutralLms));
}

static const uint PSYCHO24_MANUAL_HUE_COUNT = 23u;
static const float PSYCHO24_MANUAL_HUE_POSITION[PSYCHO24_MANUAL_HUE_COUNT] = {
    0.01071375, 0.10705012, 0.12795984, 0.15335225, 0.18766853, 0.22076293,
    0.24936653, 0.27634237, 0.29474511, 0.31129214, 0.35078118, 0.39136371,
    0.47262991, 0.49426816, 0.54698948, 0.60705013, 0.68311772, 0.81129214,
    0.91306421, 0.93498424, 0.94625976, 0.96664602, 0.97262991
};
static const float PSYCHO24_MANUAL_HUE_X[PSYCHO24_MANUAL_HUE_COUNT] = {
    0.517681, 0.675575, 0.691365, 0.691365, 0.665049, 0.680839,
    0.661513, 0.654523, 0.648355, 0.640461, 0.556250, 0.519408,
    0.450987, 0.435197, 0.424671, 0.464145, 0.516776, 0.608882,
    0.690461, 0.606250, 0.553618, 0.514145, 0.482566
};

float psycho24SampleManualHueLinearity(float sourceHuePhase) {
    sourceHuePhase -= floor(sourceHuePhase);
    uint lowerIndex = PSYCHO24_MANUAL_HUE_COUNT - 1u;
    for (uint i = 0u; i < PSYCHO24_MANUAL_HUE_COUNT; ++i) {
        if (sourceHuePhase >= PSYCHO24_MANUAL_HUE_POSITION[i]) {
            lowerIndex = i;
        }
    }
    uint upperIndex = (lowerIndex + 1u) % PSYCHO24_MANUAL_HUE_COUNT;
    float lowerPosition = PSYCHO24_MANUAL_HUE_POSITION[lowerIndex];
    float upperPosition = upperIndex == 0u
            ? PSYCHO24_MANUAL_HUE_POSITION[0] + 1.0
            : PSYCHO24_MANUAL_HUE_POSITION[upperIndex];
    if (upperIndex == 0u && sourceHuePhase < lowerPosition) {
        sourceHuePhase += 1.0;
    }
    float t = saturate(psycho24DivideSafe(sourceHuePhase - lowerPosition,
            upperPosition - lowerPosition, 0.0));
    return lerp(PSYCHO24_MANUAL_HUE_X[lowerIndex], PSYCHO24_MANUAL_HUE_X[upperIndex], t);
}

float3 psycho24ApplyManualHueDirection(float3 compressedLms, float3 directionSourceLms,
        float3 adaptiveStateLms, float toWhiteProgress) {
    float3 compressedRelativeWeighted = psycho24ToAdaptiveRelativeWeightedLms(
            compressedLms, adaptiveStateLms);
    float3 sourceRelativeWeighted = psycho24ToAdaptiveRelativeWeightedLms(
            directionSourceLms, adaptiveStateLms);
    float3 compressedMb = psycho24MbFromWeighted(compressedRelativeWeighted);
    float3 sourceMb = psycho24MbFromWeighted(sourceRelativeWeighted);
    float2 adaptedNeutralMb = psycho24MbFromWeighted(PSYCHO24_LMS_WEIGHTS).xy;
    float2 compressedOffset = compressedMb.xy - adaptedNeutralMb;
    float2 sourceOffset = sourceMb.xy - adaptedNeutralMb;
    float compressedRadius2 = dot(compressedOffset, compressedOffset);
    float sourceRadius2 = dot(sourceOffset, sourceOffset);
    if (compressedRadius2 <= PSYCHO24_EPSILON * PSYCHO24_EPSILON
            || sourceRadius2 <= PSYCHO24_EPSILON * PSYCHO24_EPSILON) {
        return compressedLms;
    }

    float sourceHuePhase = atan2(sourceOffset.y, sourceOffset.x) / PSYCHO24_TWO_PI;
    sourceHuePhase -= floor(sourceHuePhase);
    float amount = lerp(1.0, psycho24SampleManualHueLinearity(sourceHuePhase),
            saturate(toWhiteProgress));
    float compressedRadius = sqrt(compressedRadius2);
    float2 compressedDirection = compressedOffset / compressedRadius;
    float2 sourceDirection = sourceOffset * rsqrt(sourceRadius2);
    float2 outputDirection = lerp(compressedDirection, sourceDirection, amount);
    float outputDirection2 = dot(outputDirection, outputDirection);
    if (outputDirection2 <= PSYCHO24_EPSILON * PSYCHO24_EPSILON) {
        return compressedLms;
    }
    outputDirection *= rsqrt(outputDirection2);
    float3 restoredMb = float3(adaptedNeutralMb + outputDirection * compressedRadius,
            compressedMb.z);
    return psycho24UnweighLms(psycho24FromAdaptiveRelativeWeightedLms(
            psycho24WeightedFromMb(restoredMb), adaptiveStateLms));
}

float3 psycho24CopySign(float3 magnitude, float3 source) {
    static const uint PSYCHO24_FLOAT_SIGN = 0x80000000u;
    static const uint PSYCHO24_FLOAT_MAGNITUDE = 0x7fffffffu;
    uint3 signBits = asuint(source) & PSYCHO24_FLOAT_SIGN;
    uint3 magnitudeBits = asuint(magnitude) & PSYCHO24_FLOAT_MAGNITUDE;
    return asfloat(signBits | magnitudeBits);
}

float3 psycho24Core(float3 bt709LinearInput, float peakValue, float compression,
        float gamutCompression, int gamutCompressionMode, float highlights, float shadows,
        float contrast, float purity) {
    float3 lmsIn = psycho24LmsFromBt709(bt709LinearInput);
    float3 lmsPeak = psycho24LmsFromBt709(float3(peakValue));
    float3 adaptiveStateLms = psycho24LmsFromBt709(float3(0.18));
    float3 anchorIn = max(adaptiveStateLms, float3(PSYCHO24_EPSILON));
    float3 anchorOut = max(adaptiveStateLms, float3(PSYCHO24_EPSILON));
    float contrastPower = max(contrast, PSYCHO24_EPSILON);

    float3 gradedLms = abs(lmsIn);
    float gradedYf = psycho24YfFromLms(gradedLms);
    float adaptedAnchorYf = psycho24YfFromLms(anchorIn);
    float gradedYfOut = psycho24HighlightsScalarV4(gradedYf, highlights, adaptedAnchorYf);
    gradedYfOut = psycho24ShadowsScalarV4(gradedYfOut, shadows, adaptedAnchorYf);
    gradedLms *= psycho24DivideSafe(gradedYfOut, gradedYf, 1.0);

    float purityDelta = psycho24DivideSafe(max(purity, PSYCHO24_EPSILON), contrastPower, 1.0);
    float3 contrastInput = psycho24ApplyAdaptiveMbPurity(gradedLms, anchorIn, purityDelta);
    float3 contrastRatio = max(contrastInput / anchorIn, float3(PSYCHO24_EPSILON));
    float3 contrastLms = anchorOut * pow(contrastRatio, float3(contrastPower));

    float compressionPower = compression;
    if (compression == PSYCHO24_AUTO_COMPRESSION_SENTINEL) {
        compressionPower = psycho24AutoCompressionFromCenteredReferenceRange(
                psycho24YfFromLms(anchorOut), psycho24YfFromLms(lmsPeak));
    }
    compressionPower = max(compressionPower, PSYCHO24_MIN_MANUAL_COMPRESSION);

    float3 anchorOverPeak = anchorOut / max(lmsPeak, float3(PSYCHO24_EPSILON));
    float3 compressionSlopeNorm = 1.0
            - pow(max(anchorOverPeak, float3(PSYCHO24_EPSILON)), float3(compressionPower));
    float3 compressionInput = pow(max(contrastLms / anchorOut, float3(PSYCHO24_EPSILON)),
            float3(compressionPower) / max(compressionSlopeNorm, float3(PSYCHO24_EPSILON)));
    float3 compressionWhiteOffset = pow(max(lmsPeak / anchorOut, float3(PSYCHO24_EPSILON)),
            float3(compressionPower)) - float3(1.0);
    float3 compressionRolloff = pow(compressionInput
            / max(compressionInput + compressionWhiteOffset, float3(PSYCHO24_EPSILON)),
            float3(1.0 / compressionPower));
    float3 compressedLms = lmsPeak * compressionRolloff;
    float toWhiteProgress = max(compressionRolloff.x,
            max(compressionRolloff.y, compressionRolloff.z));

    float3 hueRestoredLms = psycho24ApplyManualHueDirection(
            compressedLms, contrastInput, adaptiveStateLms, toWhiteProgress);
    float3 displayScaled = psycho24CopySign(hueRestoredLms, lmsIn);
    float3 displayScaledRelativeWeighted = psycho24ToAdaptiveRelativeWeightedLms(
            displayScaled, adaptiveStateLms);
    if (gamutCompression != 0.0) {
        if (gamutCompressionMode == 0) {
            displayScaledRelativeWeighted = psycho24GamutCompressWeightedLmsCore(
                    displayScaledRelativeWeighted, PSYCHO24_BT709_TO_LMS_WEIGHTED,
                    adaptiveStateLms, gamutCompression);
        } else if (gamutCompressionMode == 1) {
            displayScaledRelativeWeighted = psycho24GamutCompressWeightedLmsCore(
                    displayScaledRelativeWeighted, PSYCHO24_BT2020_TO_LMS_WEIGHTED,
                    adaptiveStateLms, gamutCompression);
        }
    }

    float3 outputLms = psycho24UnweighLms(psycho24FromAdaptiveRelativeWeightedLms(
            displayScaledRelativeWeighted, adaptiveStateLms));
    return psycho24Bt709FromLms(outputLms);
}

public float3 psychoV24(float3 bt709LinearInput, float peakValue, float compression,
        float gamutCompression, int gamutCompressionMode, float highlights, float shadows,
        float contrast, float purity) {
    return psycho24Core(bt709LinearInput, peakValue, compression, gamutCompression,
            gamutCompressionMode, highlights, shadows, contrast, purity);
}
