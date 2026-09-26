/*
 * Adapted from RenoDX PsychoV Test24 at fc85b7b15585050442ba35412597ecefc9e04cea.
 * Copyright (C) 2026 Carlos Lopez
 * SPDX-License-Identifier: MIT
 *
 * The PsychoVisual operator: the Test24 core with its gamut squeeze routed
 * through the shared BT.2020-primary triangle in MacLeod-Boynton space and an
 * exposed hue-restore blend. It is not the CIE 170-2 PsychoV24 operator in
 * psychov24.slang, which maps chroma through the CIE 170-2 halfspace boundary
 * toward the declared target triangle and always applies the authored
 * hue-direction table.
 *
 * The Caustica profile pins the reference wrapper's exposure to 1 and its
 * adaptation and background anchors to 0.18; peak (from the output path),
 * compression (0 = automatic), gamut compression strength, highlights,
 * shadows, contrast, purity, and hue restore stay runtime parameters.
 */

// Row-major notation: mul(M, v).
static const float3x3 BT709_TO_BT2020 = float3x3(
    0.6274039, 0.3292830, 0.0433131,
    0.0690973, 0.9195406, 0.0113612,
    0.0163916, 0.0880132, 0.8955953);

static const float3x3 BT2020_TO_BT709 = float3x3(
     1.6604910, -0.5876411, -0.0728499,
    -0.1245505,  1.1328999, -0.0083494,
    -0.0181508, -0.1005789,  1.1187297);

float safeDiv(float a, float b, float fallback) {
    return abs(b) <= 1.0e-12 ? fallback : a / b;
}

float3 safeDiv(float3 a, float3 b, float3 fallback) {
    float3 denomOk = float3(abs(b) > float3(1.0e-12));
    return lerp(fallback, a / max(abs(b), float3(1.0e-12)), denomOk);
}

static const float3x3 PSYCHO_BT2020_TO_XYZ = float3x3(
    0.6369580483, 0.1446169036, 0.1688809752,
    0.2627002120, 0.6779980715, 0.0593017165,
    0.0000000000, 0.0280726930, 1.0609850577);

static const float3x3 PSYCHO_XYZ_TO_BT2020 = float3x3(
    1.7166511880, -0.3556707838, -0.2533662814,
    -0.6666843518, 1.6164812366, 0.0157685458,
    0.0176398574, -0.0427706133, 0.9421031212);

static const float3x3 PSYCHO_XYZ_TO_LMS = float3x3(
    0.2670502842655792, 0.8471990148492798, -0.03470416612462053,
    -0.38706882411220156, 1.165429935890458, 0.10302286696614202,
    0.026727793989083093, -0.02729131667566509, 0.5333267257603284);

static const float3x3 PSYCHO_LMS_TO_XYZ = float3x3(
    1.81146296, -1.30814926, 0.37056946,
    0.60691241, 0.41590626, -0.04084826,
    -0.05972506, 0.08684090, 1.85436177);

static const float3 PSYCHO_MB_WEIGHTS = float3(0.68990272, 0.34832189, 0.0371597);
static const float2 PSYCHO_WHITE_D65 = float2(0.31272, 0.32903);

float3 psychoLMSFromBT2020(float3 bt2020) {
    return mul(PSYCHO_XYZ_TO_LMS, mul(PSYCHO_BT2020_TO_XYZ, bt2020));
}

float3 psychoBT2020FromLMS(float3 lmsAbs) {
    return mul(PSYCHO_XYZ_TO_BT2020, mul(PSYCHO_LMS_TO_XYZ, lmsAbs));
}

float psychoMBYFromLMS(float3 lms) {
    return PSYCHO_MB_WEIGHTS.x * lms.x + PSYCHO_MB_WEIGHTS.y * lms.y;
}

float3 psychoMB2FromLMS(float3 lms) {
    const float eps = 1.0e-12;
    float wl = PSYCHO_MB_WEIGHTS.x * lms.x;
    float wm = PSYCHO_MB_WEIGHTS.y * lms.y;
    float yMb = wl + wm;
    if (yMb <= eps) {
        return float3(0.0);
    }
    float inv = safeDiv(1.0, yMb, 0.0);
    return float3(wl * inv, PSYCHO_MB_WEIGHTS.z * lms.z * inv, yMb);
}

float3 psychoLMSFromMB2(float3 mb2) {
    float l = mb2.x;
    float s = mb2.y;
    float y = max(mb2.z, 0.0);
    float L = safeDiv(l * y, PSYCHO_MB_WEIGHTS.x, 0.0);
    float M = safeDiv((1.0 - l) * y, PSYCHO_MB_WEIGHTS.y, 0.0);
    float S = safeDiv(s * y, PSYCHO_MB_WEIGHTS.z, 0.0);
    return float3(L, M, S);
}

float3 psychoXYZFromxyY(float3 xyY) {
    float3 xyz;
    xyz.xz = float2(xyY.x, 1.0 - xyY.x - xyY.y) / xyY.y * xyY.z;
    xyz.y = xyY.z;
    return xyz;
}

float2 psychoWhiteD65Chromaticity() {
    float3 d65Xyz = psychoXYZFromxyY(float3(PSYCHO_WHITE_D65, 1.0));
    float3 d65Lms = mul(PSYCHO_XYZ_TO_LMS, d65Xyz);
    return psychoMB2FromLMS(d65Lms).xy;
}

float2 psychoMBFromLMS(float3 lms) {
    float yMb = psychoMBYFromLMS(lms);
    if (yMb <= 0.0) {
        return float2(0.0);
    }
    return float2(
        safeDiv(PSYCHO_MB_WEIGHTS.x * lms.x, yMb, 0.0),
        safeDiv(PSYCHO_MB_WEIGHTS.z * lms.z, yMb, 0.0));
}

float2 psychoMBFromBT2020Primary(float3 primaryRgb) {
    float3 xyz = mul(PSYCHO_BT2020_TO_XYZ, primaryRgb);
    float3 lms = mul(PSYCHO_XYZ_TO_LMS, xyz);
    return psychoMBFromLMS(lms);
}

float psychoCross2(float2 a, float2 b) {
    return a.x * b.y - a.y * b.x;
}

bool psychoRaySegmentHit2D(float2 origin, float2 dir, float2 a, float2 b, out float tHit) {
    const float eps = 1.0e-20;
    tHit = 0.0;
    float2 e = b - a;
    float denom = psychoCross2(dir, e);
    if (abs(denom) <= eps) {
        return false;
    }
    float2 ao = a - origin;
    float t = psychoCross2(ao, e) / denom;
    float u = psychoCross2(ao, dir) / denom;
    if (t < 0.0 || u < 0.0 || u > 1.0) {
        return false;
    }
    tHit = t;
    return true;
}

float psychoRayMaxTBT2020(float2 origin, float2 direction, out bool hasSolution) {
    const float intervalMax = 1.0e30;
    const float eps = 1.0e-14;
    hasSolution = false;
    if (dot(direction, direction) <= eps) {
        return 0.0;
    }

    float2 r = psychoMBFromBT2020Primary(float3(1.0, 0.0, 0.0));
    float2 g = psychoMBFromBT2020Primary(float3(0.0, 1.0, 0.0));
    float2 b = psychoMBFromBT2020Primary(float3(0.0, 0.0, 1.0));

    float tBest = intervalMax;
    float t;
    bool hitAny = false;

    if (psychoRaySegmentHit2D(origin, direction, r, g, t)) { tBest = min(tBest, t); hitAny = true; }
    if (psychoRaySegmentHit2D(origin, direction, g, b, t)) { tBest = min(tBest, t); hitAny = true; }
    if (psychoRaySegmentHit2D(origin, direction, b, r, t)) { tBest = min(tBest, t); hitAny = true; }

    hasSolution = hitAny;
    return hitAny ? max(tBest, 0.0) : 0.0;
}

float3 psychoGamutCompress(float3 lms) {
    const float eps = 1.0e-14;
    float yMb = psychoMBYFromLMS(abs(lms));
    float2 white = psychoWhiteD65Chromaticity();
    float2 mb0 = psychoMBFromLMS(lms);
    float2 direction = mb0 - white;
    if (dot(direction, direction) < eps) {
        return lms;
    }

    bool hasSolution;
    float tMax = psychoRayMaxTBT2020(white, direction, hasSolution);
    if (!hasSolution) {
        return lms;
    }

    float whiteRatio = max(safeDiv(1.0 - tMax, tMax, 0.0), 0.0);
    float whiteAdd = yMb * whiteRatio;
    float3 whiteUnitLms = psychoLMSFromMB2(float3(white, 1.0));
    return lms + whiteUnitLms * whiteAdd;
}

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

float psycho24_YfFromLMS(float3 lms) {
    float3 weighted = lms * PSYCHO_MB_WEIGHTS;
    return max(weighted.x + weighted.y, PSYCHO24_EPSILON);
}

float psycho24_QuinticUnitRamp(float t) {
    t = clamp(t, 0.0, 1.0);
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

float psycho24_HighlightsScalarV4(float x, float highlights, float adaptedAnchorYf) {
    if (highlights == 1.0) {
        return x;
    }
    float t = 0.0;
    if (x > adaptedAnchorYf) {
        float referenceRangeLog2 = log2(PSYCHO24_HIGHLIGHT_GRADE_REFERENCE_WHITE
                / max(adaptedAnchorYf, PSYCHO24_EPSILON));
        t = clamp(log2(x / max(adaptedAnchorYf, PSYCHO24_EPSILON))
                / max(referenceRangeLog2, PSYCHO24_EPSILON), 0.0, 1.0);
    }
    t = psycho24_QuinticUnitRamp(t);
    float ratio = max(x / max(adaptedAnchorYf, PSYCHO24_EPSILON), PSYCHO24_EPSILON);
    if (highlights > 1.0) {
        return lerp(x, adaptedAnchorYf * pow(ratio, highlights), t);
    }
    float b = adaptedAnchorYf * pow(ratio, 2.0 - highlights);
    return safeDiv(x * x, lerp(x, b, t), x);
}

float psycho24_ShadowsScalarV4(float x, float shadows, float adaptedAnchorYf) {
    if (shadows == 1.0) {
        return x;
    }
    float ratio = max(safeDiv(x, adaptedAnchorYf, 0.0), 0.0);
    float baseTerm = x * adaptedAnchorYf;
    float baseScale = safeDiv(baseTerm, ratio, 0.0);
    float shadowFloor = adaptedAnchorYf * exp2(-PSYCHO24_SHADOW_GRADE_RANGE_STOPS);
    float t = 1.0;
    if (x > shadowFloor) {
        t = clamp(log2(x / max(adaptedAnchorYf, PSYCHO24_EPSILON))
                / log2(shadowFloor / max(adaptedAnchorYf, PSYCHO24_EPSILON)), 0.0, 1.0);
    }
    t = psycho24_QuinticUnitRamp(t);
    if (shadows > 1.0) {
        float raised = x * (1.0 + safeDiv(baseTerm,
                pow(max(ratio, PSYCHO24_EPSILON), shadows), 0.0));
        float reference = x * (1.0 + baseScale);
        return x + (raised - reference) * t;
    }
    float lowered = x * (1.0 - safeDiv(baseTerm,
            pow(max(ratio, PSYCHO24_EPSILON), 2.0 - shadows), 0.0));
    float reference = x * (1.0 - baseScale);
    return x + (lowered - reference) * t;
}

float psycho24_AutoCompressionFromCenteredReferenceRange(float anchorOutYf, float peakYf) {
    float peakOverAnchor = safeDiv(max(peakYf, PSYCHO24_EPSILON),
            max(anchorOutYf, PSYCHO24_EPSILON), PSYCHO24_HEADROOM_RATIO_FALLBACK);
    peakOverAnchor = max(peakOverAnchor, 1.0 + PSYCHO24_EPSILON);
    float referenceOneSideRangeLog10 = PSYCHO24_REFERENCE_SIMULTANEOUS_RANGE_LOG10
            / PSYCHO24_REFERENCE_CENTERED_RANGE_SIDE_COUNT;
    float actualAboveAdaptationRangeLog10 = max(log(peakOverAnchor) / log(10.0), PSYCHO24_EPSILON);
    return max(referenceOneSideRangeLog10 / actualAboveAdaptationRangeLog10,
            PSYCHO24_MIN_AUTO_COMPRESSION);
}

float3 psycho24_ToAdaptiveRelativeWeightedLMS(float3 lmsInput, float3 adaptiveStateLms) {
    return safeDiv(lmsInput * PSYCHO_MB_WEIGHTS, adaptiveStateLms, float3(0.0));
}

float3 psycho24_FromAdaptiveRelativeWeightedLMS(float3 lmsWeightedRelative, float3 adaptiveStateLms) {
    return lmsWeightedRelative * max(adaptiveStateLms, float3(PSYCHO24_EPSILON));
}

float3 psycho24_MBFromWeighted(float3 weightedLms) {
    float y = weightedLms.x + weightedLms.y;
    if (y <= PSYCHO24_EPSILON) {
        return float3(0.0);
    }
    return float3(weightedLms.x / y, weightedLms.z / y, y);
}

float3 psycho24_WeightedFromMB(float3 mb) {
    return float3(mb.x, 1.0 - mb.x, mb.y) * mb.z;
}

float3 psycho24_UnweighLMS(float3 weightedLms) {
    return weightedLms / max(PSYCHO_MB_WEIGHTS, float3(PSYCHO24_EPSILON));
}

float3 psycho24_GamutCompressAdaptive(float3 relativeWeighted, float3 adaptiveStateLms, float strength) {
    if (strength == 0.0) {
        return relativeWeighted;
    }
    float3 weightedAbsolute = psycho24_FromAdaptiveRelativeWeightedLMS(
            relativeWeighted, adaptiveStateLms);
    float3 absoluteLms = psycho24_UnweighLMS(weightedAbsolute);
    float3 compressedLms = psychoGamutCompress(absoluteLms);
    float3 compressedRelative = (compressedLms * PSYCHO_MB_WEIGHTS)
            / max(adaptiveStateLms, float3(PSYCHO24_EPSILON));
    return lerp(relativeWeighted, compressedRelative, clamp(strength, 0.0, 1.0));
}

float3 psycho24_LMSFromBT709(float3 bt709) {
    return psychoLMSFromBT2020(mul(BT709_TO_BT2020, bt709));
}

float3 psycho24_BT709FromLMS(float3 lms) {
    return mul(BT2020_TO_BT709, psychoBT2020FromLMS(lms));
}

float3 psycho24_CopySign(float3 magnitude, float3 source) {
    return float3(source.x < 0.0 ? -abs(magnitude.x) : abs(magnitude.x),
            source.y < 0.0 ? -abs(magnitude.y) : abs(magnitude.y),
            source.z < 0.0 ? -abs(magnitude.z) : abs(magnitude.z));
}

float3 psycho24_ApplyAdaptiveMBPurity(float3 lmsInput, float3 adaptiveNeutralLms, float purityDelta) {
    if (abs(purityDelta - 1.0) <= 1.0e-5) {
        return lmsInput;
    }
    float3 relativeWeighted = psycho24_ToAdaptiveRelativeWeightedLMS(lmsInput, adaptiveNeutralLms);
    float3 mb = psycho24_MBFromWeighted(relativeWeighted);
    float3 mbNeutral = psycho24_MBFromWeighted(PSYCHO_MB_WEIGHTS);
    float2 mbScaledXy = lerp(mbNeutral.xy, mb.xy, purityDelta);
    float3 relativeWeightedOut = psycho24_WeightedFromMB(float3(mbScaledXy, mb.z));
    return psycho24_UnweighLMS(
            psycho24_FromAdaptiveRelativeWeightedLMS(relativeWeightedOut, adaptiveNeutralLms));
}

static const uint PSYCHO_MANUAL_HUE_COUNT = 23u;
static const float PSYCHO_MANUAL_HUE_POSITION[PSYCHO_MANUAL_HUE_COUNT] = {
    0.01071375, 0.10705012, 0.12795984, 0.15335225, 0.18766853, 0.22076293,
    0.24936653, 0.27634237, 0.29474511, 0.31129214, 0.35078118, 0.39136371,
    0.47262991, 0.49426816, 0.54698948, 0.60705013, 0.68311772, 0.81129214,
    0.91306421, 0.93498424, 0.94625976, 0.96664602, 0.97262991
};
static const float PSYCHO_MANUAL_HUE_X[PSYCHO_MANUAL_HUE_COUNT] = {
    0.517681, 0.675575, 0.691365, 0.691365, 0.665049, 0.680839,
    0.661513, 0.654523, 0.648355, 0.640461, 0.556250, 0.519408,
    0.450987, 0.435197, 0.424671, 0.464145, 0.516776, 0.608882,
    0.690461, 0.606250, 0.553618, 0.514145, 0.482566
};

float psycho24_SampleManualHueLinearity(float sourceHuePhase) {
    sourceHuePhase -= floor(sourceHuePhase);
    uint lowerIndex = PSYCHO_MANUAL_HUE_COUNT - 1u;
    for (uint i = 0u; i < PSYCHO_MANUAL_HUE_COUNT; ++i) {
        if (sourceHuePhase >= PSYCHO_MANUAL_HUE_POSITION[i]) {
            lowerIndex = i;
        }
    }
    uint upperIndex = (lowerIndex + 1u) % PSYCHO_MANUAL_HUE_COUNT;
    float lowerPosition = PSYCHO_MANUAL_HUE_POSITION[lowerIndex];
    float upperPosition = upperIndex == 0u
            ? PSYCHO_MANUAL_HUE_POSITION[0] + 1.0
            : PSYCHO_MANUAL_HUE_POSITION[upperIndex];
    if (upperIndex == 0u && sourceHuePhase < lowerPosition) {
        sourceHuePhase += 1.0;
    }
    float t = clamp(safeDiv(sourceHuePhase - lowerPosition,
            upperPosition - lowerPosition, 0.0), 0.0, 1.0);
    return lerp(PSYCHO_MANUAL_HUE_X[lowerIndex], PSYCHO_MANUAL_HUE_X[upperIndex], t);
}

float3 psycho24_ApplyManualHueDirection(float3 compressedLms, float3 directionSourceLms,
        float3 adaptiveStateLms, float toWhiteProgress) {
    float3 compressedRelativeWeighted = psycho24_ToAdaptiveRelativeWeightedLMS(
            compressedLms, adaptiveStateLms);
    float3 sourceRelativeWeighted = psycho24_ToAdaptiveRelativeWeightedLMS(
            directionSourceLms, adaptiveStateLms);
    float3 compressedMb = psycho24_MBFromWeighted(compressedRelativeWeighted);
    float3 sourceMb = psycho24_MBFromWeighted(sourceRelativeWeighted);
    float2 adaptedNeutralMb = psycho24_MBFromWeighted(PSYCHO_MB_WEIGHTS).xy;
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
    float amount = lerp(1.0, psycho24_SampleManualHueLinearity(sourceHuePhase),
            clamp(toWhiteProgress, 0.0, 1.0));
    float compressedRadius = sqrt(compressedRadius2);
    float2 compressedDirection = compressedOffset / compressedRadius;
    float2 sourceDirection = sourceOffset * rsqrt(sourceRadius2);
    float2 outputDirection = lerp(compressedDirection, sourceDirection, amount);
    float outputDirection2 = dot(outputDirection, outputDirection);
    if (outputDirection2 <= PSYCHO24_EPSILON * PSYCHO24_EPSILON) {
        return compressedLms;
    }
    outputDirection *= rsqrt(outputDirection2);
    float3 restoredMb = float3(adaptedNeutralMb + outputDirection * compressedRadius, compressedMb.z);
    float3 restoredRelativeWeighted = psycho24_WeightedFromMB(restoredMb);
    return psycho24_UnweighLMS(
            psycho24_FromAdaptiveRelativeWeightedLMS(restoredRelativeWeighted, adaptiveStateLms));
}

// peakValue is the display peak over effective white; returns linear BT.709.
public float3 psychoVisual(float3 bt709LinearInput, float peakValue,
        float compression, float gamutCompression, float highlights, float shadows,
        float contrast, float purity, float hueRestore) {
    float3 lmsIn = psycho24_LMSFromBT709(bt709LinearInput);
    float3 lmsPeak = psycho24_LMSFromBT709(float3(peakValue));
    float3 adaptiveStateLms = psycho24_LMSFromBT709(float3(0.18));
    float3 desiredBackgroundStateLms = adaptiveStateLms;
    float3 anchorIn = max(adaptiveStateLms, float3(PSYCHO24_EPSILON));
    float3 anchorOut = max(desiredBackgroundStateLms, float3(PSYCHO24_EPSILON));
    float contrastPower = max(contrast, PSYCHO24_EPSILON);

    float3 gradedLms = abs(lmsIn);
    float gradedYf = psycho24_YfFromLMS(gradedLms);
    float adaptedAnchorYf = psycho24_YfFromLMS(anchorIn);
    float gradedYfOut = psycho24_HighlightsScalarV4(gradedYf, highlights, adaptedAnchorYf);
    gradedYfOut = psycho24_ShadowsScalarV4(gradedYfOut, shadows, adaptedAnchorYf);
    gradedLms *= safeDiv(gradedYfOut, gradedYf, 1.0);

    float purityDelta = safeDiv(max(purity, PSYCHO24_EPSILON), contrastPower, 1.0);
    float3 contrastInput = psycho24_ApplyAdaptiveMBPurity(gradedLms, anchorIn, purityDelta);
    float3 contrastRatio = max(contrastInput / anchorIn, float3(PSYCHO24_EPSILON));
    float3 contrastLms = anchorOut * pow(contrastRatio, float3(contrastPower));

    float compressionPower = compression;
    if (compression == PSYCHO24_AUTO_COMPRESSION_SENTINEL) {
        compressionPower = psycho24_AutoCompressionFromCenteredReferenceRange(
                psycho24_YfFromLMS(anchorOut), psycho24_YfFromLMS(lmsPeak));
    }
    compressionPower = max(compressionPower, PSYCHO24_MIN_MANUAL_COMPRESSION);

    float3 anchorOverPeak = anchorOut / max(lmsPeak, float3(PSYCHO24_EPSILON));
    float3 compressionSlopeNorm = 1.0
            - pow(max(anchorOverPeak, float3(PSYCHO24_EPSILON)), float3(compressionPower));
    float3 compressionInput = pow(max(contrastLms / anchorOut, float3(PSYCHO24_EPSILON)),
            float3(compressionPower) / max(compressionSlopeNorm, float3(PSYCHO24_EPSILON)));
    float3 compressionWhiteOffset = pow(max(lmsPeak / anchorOut, float3(PSYCHO24_EPSILON)),
            float3(compressionPower)) - float3(1.0);
    float3 compressionRolloff = pow(
            compressionInput / max(compressionInput + compressionWhiteOffset,
                    float3(PSYCHO24_EPSILON)), float3(1.0 / compressionPower));
    float3 compressedLms = lmsPeak * compressionRolloff;
    float toWhiteProgress = max(compressionRolloff.x, max(compressionRolloff.y, compressionRolloff.z));

    float3 manualHueLms = psycho24_ApplyManualHueDirection(
            compressedLms, contrastInput, adaptiveStateLms, toWhiteProgress);
    float hueRestoreAmount = clamp(hueRestore, 0.0, 1.0);
    float3 hueRestoredLms = hueRestoreAmount >= 1.0
            ? manualHueLms
            : lerp(compressedLms, manualHueLms, hueRestoreAmount);
    float3 displayScaled = psycho24_CopySign(hueRestoredLms, lmsIn);
    float3 displayScaledRelativeWeighted = psycho24_ToAdaptiveRelativeWeightedLMS(
            displayScaled, adaptiveStateLms);
    displayScaledRelativeWeighted = psycho24_GamutCompressAdaptive(
            displayScaledRelativeWeighted, adaptiveStateLms, gamutCompression);

    float3 outputLms = psycho24_UnweighLMS(
            psycho24_FromAdaptiveRelativeWeightedLMS(displayScaledRelativeWeighted, adaptiveStateLms));
    return psycho24_BT709FromLMS(outputLms);
}
