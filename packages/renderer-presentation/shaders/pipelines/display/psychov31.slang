/*
 * Adapted from the RenoDX PsychoV family's customtest31.hlsli
 * (custom_psychotm_test31 over the unmodified Test30/Test29 glue).
 * Copyright (C) 2026 Carlos Lopez
 * Modifications Copyright (C) 2026 Musa Haji (GitHub: mqhaji)
 * SPDX-License-Identifier: MIT
 *
 * The custom Test31 core replaces Test30's positive finite-G response with a
 * signed anchored pipeline: LMS grading (contrast, highlights, shadows,
 * purity), a C-infinity anchor shoulder (compression strength), and Mean-A2
 * authoring between the source and response hues with physical tonal-region
 * weights. Gamut mapping scores radial demand against the target cube's L8
 * support and soft-limits it (sharpness 16), with the source-awareness blend
 * ramping over the L8 demand knee 0.8..2 and the source response cage
 * projecting onto the bounded neutral/boundary target chord.
 *
 * The integration profile pins the reference defaults for every argument without
 * a config knob: exposure 1, flare 0, highlight/shadow contrast 1, highlight
 * saturation 1, dechroma 0, sDR EOTF emulation 0, adaptation and background
 * anchors 0.18/0.18, Mean-A2 source weights 1/0.5/0 (shadow/midgray/highlight),
 * source boundary BT.709, gamut knee 0.8..2, smooth-limit sharpness 16,
 * source-max sharpness 4, source positive epsilon 0.001, progress clamp width
 * 0.05. The `compression` knob is the C-infinity shoulder strength (reference
 * default 1.5) and has no automatic mode. The target gamut mode
 * (0 = BT.709 cube, 1 = BT.2020 cube) follows the output path: SDR constrains
 * to the BT.709 cube, HDR to the BT.2020 cube.
 *
 * Dead branches of the reference are omitted: the NONE/BT.2020/AP1
 * source-boundary transports, the Display P3 and Samsung G80SD target gamut
 * modes, the grade-only custom_psychograde_test31 entry, the unsigned grading
 * path, and the highlight-saturation/dechroma/sDR EOTF emulation blocks.
 */

import psychov30;

// --- custom Test31 constants (reference CUSTOM_PSYCHO31_*) ----------------------

static const float CUSTOM_PSYCHO31_GAMUT_KNEE_START = 0.8;
static const float CUSTOM_PSYCHO31_GAMUT_KNEE_END = 2.0;
// Sharpness of soft min(demand, 1) on the L8 boundary (reference
// CUSTOM_PSYCHO31_SMOOTH_LIMIT_SHARPNESS; the Samsung G80SD variant is a dead
// target mode under the pinned profile).
static const float CUSTOM_PSYCHO31_SMOOTH_LIMIT_SHARPNESS = 16.0;
static const float CUSTOM_PSYCHO31_SOURCE_MAX_SHARPNESS = 4.0;
static const float CUSTOM_PSYCHO31_SOURCE_POSITIVE_EPSILON = 0.001;
static const float CUSTOM_PSYCHO31_PROGRESS_CLAMP_WIDTH = 0.05;

// Pinned Mean-A2 tonal source weights (shadow, midgray, highlight).
static const float3 CUSTOM_PSYCHO31_MEAN_A2_SOURCE_WEIGHTS = float3(1.0, 0.5, 0.0);

// --- C-infinity primitives ---------------------------------------------------

// Reference custom_psycho31_CInfinityTransition (vector form saturates instead of
// gating; both forms agree on (0, 1) and at the clamp bounds).
float3 customPsycho31CInfinityTransition3(float3 position) {
    position = clamp(position, 0.0, 1.0);
    return 1.0 / (1.0 + exp2((1.0 - 2.0 * position) / (position * (1.0 - position))));
}

float customPsycho31CInfinityTransition(float position) {
    if (position <= 0.0) {
        return 0.0;
    }
    if (position >= 1.0) {
        return 1.0;
    }
    return 1.0 / (1.0 + exp2((1.0 - 2.0 * position) / (position * (1.0 - position))));
}

float customPsycho31SmoothUnitLimit(float value) {
    const float sharpness = CUSTOM_PSYCHO31_SMOOTH_LIMIT_SHARPNESS;
    const float zeroOffset = 1.0 - log2(1.0 + exp2(sharpness)) / sharpness;
    float nearZero = 0.0;
    if (!(value >= 1.0e-3)) {
        // Avoid subtracting nearly equal softplus values at the neutral axis;
        // a local Taylor evaluation joins the ordinary formula with flat gates.
        float originSlope = 1.0 / (1.0 + exp2(-sharpness));
        nearZero = value * originSlope
                * (1.0 - 0.5 * sharpness * log(2.0) * (1.0 - originSlope) * value)
                / (1.0 - zeroOffset);
        if (value <= 1.0e-4) {
            return nearZero;
        }
    }
    float limited = 1.0 - log2(1.0 + exp2(sharpness * (1.0 - value))) / sharpness;
    if (value >= 1.0e-3) {
        return (limited - zeroOffset) / (1.0 - zeroOffset);
    }
    return lerp(nearZero, (limited - zeroOffset) / (1.0 - zeroOffset),
            customPsycho31CInfinityTransition((value - 1.0e-4) / 9.0e-4));
}

float customPsycho31CInfinityClamp01(float value) {
    const float width = CUSTOM_PSYCHO31_PROGRESS_CLAMP_WIDTH;
    float lowerWeight = customPsycho31CInfinityTransition(value / width);
    float lowerClamped = value * lowerWeight;
    float upperWeight = customPsycho31CInfinityTransition((1.0 - lowerClamped) / width);
    return 1.0 - (1.0 - lowerClamped) * upperWeight;
}

float customPsycho31SmoothMax3(float3 values) {
    const float sharpness = CUSTOM_PSYCHO31_SOURCE_MAX_SHARPNESS;
    return log2(
            exp2(sharpness * values.x)
            + exp2(sharpness * values.y)
            + exp2(sharpness * values.z))
            / sharpness;
}

// --- anchored grading (reference custom_psycho31_ApplyAnchoredTonalGrading with
// flare 0 and highlight/shadow contrast pinned to 1) ---------------------------

float3 customPsycho31ApplyAnchoredTonalGrading(float3 color, float3 anchorIn, float3 anchorOut,
        float contrast, float highlights, float shadows) {
    if (contrast == 1.0 && highlights == 1.0 && shadows == 1.0) {
        // The pinned flare/highlight-contrast/shadow-contrast branches are
        // neutral, so only the anchor equality term remains.
        return color;
    }

    float3 normalized = color / anchorIn;
    float3 gradedNormalized = normalized;
    if (contrast != 1.0) {
        float contrastScalar = contrast;
        float3 inputStops = log2(normalized);
        float3 highlightStops = max(inputStops, float3(0.0));
        float3 displacement = (contrastScalar - 1.0) * highlightStops;
        float3 displacementMagnitude = abs(displacement);
        float3 outputHighlightStops = highlightStops + displacement
                / (displacementMagnitude * exp2(-1.0 / displacementMagnitude) + 1.0);
        gradedNormalized = exp2(contrastScalar * min(inputStops, float3(0.0))
                + outputHighlightStops);
    }

    if (highlights != 1.0 || shadows != 1.0) {
        const float TONAL_OFFSET_START_STOPS = 1.0;
        const float TONAL_OFFSET_INVERSE_RANGE_STOPS = 1.0 / (8.0 - 1.0);

        float3 tonalStops = log2(gradedNormalized);
        float3 tonalDisplacement = float3(0.0);
        if (highlights != 1.0) {
            float adjustment = highlights - 1.0;
            float displacement = adjustment * (1.5 * abs(adjustment) + 0.5);
            float3 weight = customPsycho31CInfinityTransition3(
                    (tonalStops - TONAL_OFFSET_START_STOPS)
                            * TONAL_OFFSET_INVERSE_RANGE_STOPS);
            tonalDisplacement += displacement * weight;
        }

        if (shadows != 1.0) {
            float adjustment = shadows - 1.0;
            float displacement = adjustment * (1.5 * abs(adjustment) + 0.5);
            float3 weight = customPsycho31CInfinityTransition3(
                    (-TONAL_OFFSET_START_STOPS - tonalStops)
                            * TONAL_OFFSET_INVERSE_RANGE_STOPS);
            tonalDisplacement += displacement * weight;
        }

        gradedNormalized *= exp2(tonalDisplacement);
    }
    return gradedNormalized * anchorOut;
}

// Reference custom_psycho31_GradeLMS over the pinned profile: purity is the only
// live purity knob, the signed-response policy is always on, and the
// highlight-saturation/dechroma/sDR-EOTF blocks are omitted.
float3 customPsycho31GradeLMS(float3 gradingSourceLms, float3 anchorInLms, float3 anchorOutLms,
        float highlights, float shadows, float contrast, float purityScale,
        out float3 tonalInputLms) {
    tonalInputLms = gradingSourceLms;
    if (purityScale != 1.0) {
        tonalInputLms = psycho30ApplyAdaptiveRelativePurity(
                gradingSourceLms,
                anchorInLms,
                purityScale)
                * anchorInLms;
    }
    // Signed response: magnitudes are graded and the caller restores signs.
    return customPsycho31ApplyAnchoredTonalGrading(
            abs(tonalInputLms),
            anchorInLms,
            anchorOutLms,
            contrast,
            highlights,
            shadows);
}

// Reference custom_psycho31_ApplyAnchoredCInfinityShoulder.
float3 customPsycho31ApplyAnchoredCInfinityShoulder(float3 color, float3 peak, float3 anchor,
        float compressionStrength) {
    float3 shoulderRange = peak - anchor;
    float3 distanceFromAnchor = max(color - anchor, float3(0.0));
    float3 flatWeight = exp2(
            -shoulderRange / (compressionStrength * distanceFromAnchor));
    float3 responseDenominator = distanceFromAnchor * flatWeight + shoulderRange;
    return shoulderRange * (distanceFromAnchor / responseDenominator)
            + (color - distanceFromAnchor);
}

// Reference custom_psycho31_ShoulderMeanA2Weight: physical WHITE-weighted source
// level picks the shadow/midgray weight below the anchor, and shoulder loss
// gates the midgray/highlight blend above it.
float customPsycho31ShoulderMeanA2Weight(float3 sourceQ, float3 gradedLms, float3 responseLms,
        float meanA2ShadowSourceWeight, float meanA2MidgraySourceWeight,
        float meanA2HighlightSourceWeight) {
    // Developer-uniform weights have a 0..1 contract; retain lower clamps.
    meanA2ShadowSourceWeight = max(meanA2ShadowSourceWeight, 0.0);
    meanA2MidgraySourceWeight = max(meanA2MidgraySourceWeight, 0.0);
    meanA2HighlightSourceWeight = max(meanA2HighlightSourceWeight, 0.0);
    if (meanA2ShadowSourceWeight == meanA2MidgraySourceWeight
            && meanA2MidgraySourceWeight == meanA2HighlightSourceWeight) {
        return meanA2MidgraySourceWeight;
    }
    float sourceLevel = psycho30YfFromLMS(PSYCHO30_D65_WHITE_LMS * sourceQ)
            / PSYCHO30_D65_WHITE_YF;
    if (sourceLevel <= 1.0) {
        if (meanA2ShadowSourceWeight == meanA2MidgraySourceWeight) {
            return meanA2ShadowSourceWeight;
        }
        if (sourceLevel <= 0.5) {
            return meanA2ShadowSourceWeight;  // Includes black; never log2(0).
        }
        if (sourceLevel == 1.0) {
            return meanA2MidgraySourceWeight;
        }
        return lerp(meanA2ShadowSourceWeight, meanA2MidgraySourceWeight,
                customPsycho31CInfinityTransition(1.0 + log2(sourceLevel)));
    }
    if (meanA2MidgraySourceWeight == meanA2HighlightSourceWeight) {
        return meanA2MidgraySourceWeight;
    }
    float gradedYf = psycho30YfFromLMS(gradedLms);
    float responseYf = psycho30YfFromLMS(responseLms);
    if (!(gradedYf > 0.0) || isnan(gradedYf) || isinf(gradedYf)
            || isnan(responseYf) || isinf(responseYf)) {
        return meanA2MidgraySourceWeight;
    }
    float shoulderLoss = clamp(1.0 - responseYf / gradedYf, 0.0, 1.0);
    if (shoulderLoss <= 0.0) {
        return meanA2MidgraySourceWeight;
    }
    // One-stop gates have flat joins at level 1; skip logs only on plateaus.
    float blend = (sourceLevel >= 2.0
                    ? 1.0
                    : customPsycho31CInfinityTransition(log2(sourceLevel)))
            * customPsycho31CInfinityTransition(shoulderLoss / 0.5);
    return lerp(meanA2MidgraySourceWeight, meanA2HighlightSourceWeight, blend);
}

// Reference custom_psycho31_MeanA2Response: response hue steered toward the
// source hue by the effective weight, with confidence collars at the neutral
// axis and before the antipodal sum vanishes.
float3 customPsycho31MeanA2Response(float3 sourceQ, float3 responseU,
        float effectiveMeanA2Weight, out float responseYf, out uint valid) {
    valid = !any(isnan(sourceQ))
                    && !any(isinf(sourceQ))
                    && !any(isnan(responseU))
                    && !any(isinf(responseU))
            ? 1u
            : 0u;
    if (valid == 0u) {
        responseYf = 0.0;
        return float3(0.0);
    }

    float2 sourceDt = psycho30ScaledA2FromQ(sourceQ);
    float2 responseDt = psycho30ScaledA2FromQ(responseU);
    float2 authoredDt = responseDt;
    float sourceRadius6 = psycho30ScaledA2Radius6(sourceDt);
    float responseRadius6 = psycho30ScaledA2Radius6(responseDt);
    // Source hue is undefined at neutral: suppress steering on a small
    // relative radius collar (1..8 ppm of max(1, |Q|)).
    float sourceScale = max(1.0, psycho30Max3(abs(sourceQ)));
    float sourceConfidence = customPsycho31CInfinityTransition(
            (sqrt(sourceRadius6 / 6.0) / sourceScale - 1.0e-6) / 7.0e-6);
    if (sourceConfidence > 0.0 && responseRadius6 > 0.0) {
        float inverseResponseRadius = rsqrt(responseRadius6);
        float responseRadius = responseRadius6 * inverseResponseRadius;
        effectiveMeanA2Weight *= sourceConfidence;
        float2 meanDirection = sourceDt * (rsqrt(sourceRadius6) * effectiveMeanA2Weight)
                + responseDt * inverseResponseRadius;
        float meanRadius6 = psycho30ScaledA2Radius6(meanDirection);
        // Explicit policy: relax toward RESPONSE hue before the antipodal sum
        // vanishes (squared unit-sum lengths 2^-16..2^-12).
        float angularConfidence = customPsycho31CInfinityTransition(
                (meanRadius6 - 0.0000152587890625) / 0.0002288818359375);
        if (angularConfidence < 1.0) {
            meanDirection = sourceDt
                    * (rsqrt(sourceRadius6)
                            * (effectiveMeanA2Weight * angularConfidence))
                    + responseDt * inverseResponseRadius;
            meanRadius6 = psycho30ScaledA2Radius6(meanDirection);
        }
        authoredDt = meanDirection * (responseRadius * rsqrt(meanRadius6));
    }

    responseYf = PSYCHO30_D65_ALPHA_L * responseU.x
            + PSYCHO30_D65_ALPHA_M * responseU.y;
    return float3(
            authoredDt.x,
            responseU.x + responseU.y + responseU.z,
            authoredDt.y);
}

// --- Test30 coordinate glue (unchanged reference layer) --------------------------

float3 psycho31LMSFromTest30Coord(float3 coord, float peakValue) {
    float normalizedA = (2.0 * coord.y
            + 3.0 * PSYCHO30_D65_ALPHA_DELTA * coord.x
            - coord.z) / 6.0;
    float3 bt709 = peakValue
            * (normalizedA
               + coord.x * PSYCHO30_BT709_D_RGB
               + coord.z * PSYCHO30_BT709_T_RGB);
    return mul(PSYCHO30_BT709_TO_LMS_MAT, bt709);
}

float psycho31YfFromTest30Coord(float3 coord) {
    return (2.0 * coord.y
            + 3.0 * PSYCHO30_D65_ALPHA_DELTA * coord.x
            - coord.z) / 6.0;
}

float3 psycho31Test30CoordFromDTA(float d, float t, float a) {
    return float3(
            d,
            3.0 * a - 1.5 * PSYCHO30_D65_ALPHA_DELTA * d + 0.5 * t,
            t);
}

// --- L8 target demand (reference custom_psycho31_NormalizedL8TargetDemand over
// the BT.709/BT.2020 modes) ----------------------------------------------------

float customPsycho31NormalizedL8TargetDemand(float3 coord, float responseYfCeiling,
        int targetGamutMode) {
    float mappedA = clamp(
            psycho31YfFromTest30Coord(coord),
            0.0,
            clamp(responseYfCeiling, 0.0, 1.0));
    float radialLength = sqrt(
            0.5 * coord.x * coord.x
            + coord.z * coord.z / 6.0);
    if (mappedA <= 0.0
            || mappedA >= 1.0
            || radialLength == 0.0) {
        return 0.0;
    }

    float inverseRadialLength = 1.0 / radialLength;
    float normalizedD = coord.x * inverseRadialLength;
    float normalizedT = coord.z * inverseRadialLength;
    float3 radialRgb = targetGamutMode == 0
            ? normalizedD * PSYCHO30_BT709_D_RGB + normalizedT * PSYCHO30_BT709_T_RGB
            : normalizedD * PSYCHO30_BT2020_D_RGB + normalizedT * PSYCHO30_BT2020_T_RGB;
    float3 upperDemand = max(radialRgb, float3(0.0)) / (1.0 - mappedA);
    float3 lowerDemand = max(-radialRgb, float3(0.0)) / mappedA;
    float demandScale = max(
            psycho30Max3(upperDemand),
            psycho30Max3(lowerDemand));
    if (demandScale == 0.0) {
        return 0.0;
    }
    // Upper and lower demands are disjoint per channel; combine before powering.
    float3 normalizedDemandRgb = (upperDemand + lowerDemand) / demandScale;
    // L8 demand with the soft limiter: components to the 4th power, then the
    // dot gives the 8th-power sum.
    normalizedDemandRgb *= normalizedDemandRgb;
    normalizedDemandRgb *= normalizedDemandRgb;
    float eighthPowerSum = dot(normalizedDemandRgb, normalizedDemandRgb);
    float support = 1.0 / (demandScale * sqrt(sqrt(sqrt(eighthPowerSum))));
    return radialLength / support;
}

// Reference custom_psycho31_MapL8TargetDemand: shared reconstruction for fresh or
// already-computed L8 demand.
float3 customPsycho31MapL8TargetDemand(float3 coord, float responseYfCeiling,
        int targetGamutMode, float normalizedDemand) {
    float mappedA = clamp(
            psycho31YfFromTest30Coord(coord),
            0.0,
            clamp(responseYfCeiling, 0.0, 1.0));
    if (!(normalizedDemand > 0.0)) {
        return psycho31Test30CoordFromDTA(0.0, 0.0, mappedA);
    }

    float mappedDemand = customPsycho31SmoothUnitLimit(normalizedDemand);
    float radialScale = mappedDemand / normalizedDemand;
    return psycho31Test30CoordFromDTA(
            coord.x * radialScale,
            coord.z * radialScale,
            mappedA);
}

// Reference custom_psycho31_TargetEndpoint.
float3 customPsycho31TargetEndpoint(float3 coord, float responseYfCeiling,
        int targetGamutMode, out uint valid) {
    float3 solvedCoord = customPsycho31MapL8TargetDemand(
            coord,
            responseYfCeiling,
            targetGamutMode,
            customPsycho31NormalizedL8TargetDemand(coord, responseYfCeiling, targetGamutMode));
    valid = 0u;
    if (any(isnan(solvedCoord))) {
        return float3(0.0);
    }
    valid = !any(isinf(solvedCoord)) ? 1u : 0u;
    return valid != 0u ? solvedCoord : float3(0.0);
}

// Reference custom_psycho31_AnchorSourceBoundaryToYf: identity on the positive
// octant, continuous on its faces and at black, scaled to the matrix neutral
// Yf (not a D65 constant from another source gamut).
float3 customPsycho31AnchorSourceBoundaryToYf(float3 sourceRgb, float3x3 sourceToLms,
        float3 sourceYfWeights, out float3 anchoredSourceRgb) {
    anchoredSourceRgb = sourceRgb;
    if (all(sourceRgb >= float3(0.0))) {
        return mul(sourceToLms, sourceRgb);
    }
    float sourceTotal = dot(max(sourceRgb, float3(0.0)), sourceYfWeights);
    if (sourceTotal <= 0.0) {
        anchoredSourceRgb = float3(0.0);
        return float3(0.0);
    }
    float boundaryFraction = sourceTotal / (sourceTotal - psycho30Min3(sourceRgb));
    anchoredSourceRgb = sourceTotal + (sourceRgb - sourceTotal) * boundaryFraction;
    float3 boundedLms = mul(sourceToLms, anchoredSourceRgb);
    float scale = sourceTotal
            * psycho30YfFromLMS(mul(sourceToLms, float3(1.0)))
            / psycho30YfFromLMS(boundedLms);
    anchoredSourceRgb *= scale;
    return boundedLms * scale;
}

// --- signed response state (reference custom_psycho31_ResponseState/Coord) -------

void customPsycho31ResponseState(float3 sourceLms, float3 anchorInLms, float3 anchorOutLms,
        float3 targetPeakLms, float highlights, float shadows, float contrast,
        float purityScale, float compression,
        out float3 sourceQ, out float3 responseU, out float effectiveMeanA2Weight) {
    float3 tonalInputLms;
    float3 gradedLms = customPsycho31GradeLMS(
            sourceLms,
            anchorInLms,
            anchorOutLms,
            highlights,
            shadows,
            contrast,
            purityScale,
            tonalInputLms);
    float3 responseLms = customPsycho31ApplyAnchoredCInfinityShoulder(
            gradedLms,
            targetPeakLms,
            anchorOutLms,
            compression);

    sourceQ = tonalInputLms / anchorInLms;
    // The signed-response policy grades magnitudes; restore signs only.
    responseU = psycho30CopySign(responseLms, tonalInputLms) / targetPeakLms;
    effectiveMeanA2Weight = customPsycho31ShoulderMeanA2Weight(
            abs(sourceQ), gradedLms, responseLms,
            CUSTOM_PSYCHO31_MEAN_A2_SOURCE_WEIGHTS.x,
            CUSTOM_PSYCHO31_MEAN_A2_SOURCE_WEIGHTS.y,
            CUSTOM_PSYCHO31_MEAN_A2_SOURCE_WEIGHTS.z);
}

float3 customPsycho31ResponseCoord(float3 sourceLms, float3 anchorInLms, float3 anchorOutLms,
        float3 targetPeakLms, float highlights, float shadows, float contrast,
        float purityScale, float compression,
        out float3 sourceQ, out float responseYf, out uint valid) {
    float3 responseU;
    float effectiveMeanA2Weight;
    customPsycho31ResponseState(
            sourceLms,
            anchorInLms,
            anchorOutLms,
            targetPeakLms,
            highlights,
            shadows,
            contrast,
            purityScale,
            compression,
            sourceQ,
            responseU,
            effectiveMeanA2Weight);
    return customPsycho31MeanA2Response(
            sourceQ,
            responseU,
            effectiveMeanA2Weight,
            responseYf,
            valid);
}

// --- source coordinate cage (reference custom_psycho31_SourceCoordinateCage) -----

float3 customPsycho31SourceCoordinateCage(float3 desiredCoord, float3 anchoredSourceRgb,
        float3x3 sourceToLms, float3 anchorInLms, float3 anchorOutLms, float3 targetPeakLms,
        float highlights, float shadows, float contrast, float purityScale,
        float compression, float responseYfCeiling, int targetGamutMode,
        out uint valid) {
    valid = 0u;
    // The reference's row-vector product
    // mul(STOCKMAN_SHARP_LMS_TO_XFYFZF_MAT[1], source_to_lms).
    float3 sourceYfCoefficients = mul(PSYCHO30_YF_ROW, sourceToLms);
    float neutralSourceRgb = dot(
            sourceYfCoefficients,
            anchoredSourceRgb)
            / dot(sourceYfCoefficients, float3(1.0));
    // Anchored source RGB is nonnegative. Only black lacks a source ray.
    if (neutralSourceRgb <= 0.0) {
        return float3(0.0);
    }

    float3 sourceResidual = anchoredSourceRgb - neutralSourceRgb;
    float3 rawLowerDemand = -sourceResidual / neutralSourceRgb;
    float3 smoothLowerDemand = 0.5
            * (rawLowerDemand
               + sqrt(rawLowerDemand * rawLowerDemand
                      + float3(CUSTOM_PSYCHO31_SOURCE_POSITIVE_EPSILON
                              * CUSTOM_PSYCHO31_SOURCE_POSITIVE_EPSILON)));
    float demandScale = customPsycho31SmoothMax3(smoothLowerDemand);

    float boundaryFraction = 1.0 / demandScale;

    float3 neutralSourceQ;
    float neutralResponseYf;
    uint neutralValid;
    float3 neutralCoord = customPsycho31ResponseCoord(
            mul(sourceToLms, float3(neutralSourceRgb)),
            anchorInLms,
            anchorOutLms,
            targetPeakLms,
            highlights,
            shadows,
            contrast,
            purityScale,
            compression,
            neutralSourceQ,
            neutralResponseYf,
            neutralValid);
    float3 boundarySourceQ;
    float boundaryResponseYf;
    uint boundaryValid;
    float3 sourceBoundaryCoord = customPsycho31ResponseCoord(
            mul(sourceToLms, neutralSourceRgb + sourceResidual * boundaryFraction),
            anchorInLms,
            anchorOutLms,
            targetPeakLms,
            highlights,
            shadows,
            contrast,
            purityScale,
            compression,
            boundarySourceQ,
            boundaryResponseYf,
            boundaryValid);
    if (neutralValid == 0u || boundaryValid == 0u) {
        return float3(0.0);
    }

    uint targetEndpointValid;
    float3 targetEndpointCoord = customPsycho31TargetEndpoint(
            sourceBoundaryCoord,
            boundaryResponseYf,
            targetGamutMode,
            targetEndpointValid);
    if (targetEndpointValid == 0u) {
        return float3(0.0);
    }

    // Both ends must be bounded, including a graded, colored source-neutral.
    uint neutralEndpointValid;
    float3 neutralEndpointCoord = customPsycho31TargetEndpoint(
            neutralCoord,
            neutralResponseYf,
            targetGamutMode,
            neutralEndpointValid);
    if (neutralEndpointValid == 0u) {
        return float3(0.0);
    }

    // Colored adaptation/grade need not leave source-neutral on the D65 axis.
    // Project the full response chord, then interpolate its bounded endpoints.
    float desiredA = psycho31YfFromTest30Coord(desiredCoord);
    float neutralA = psycho31YfFromTest30Coord(neutralCoord);
    float3 sourceChord = sourceBoundaryCoord - neutralCoord;
    float sourceChordA = psycho31YfFromTest30Coord(sourceChord);
    float sourceChordLength2 =
            0.5 * sourceChord.x * sourceChord.x
            + sourceChord.z * sourceChord.z / 6.0
            + sourceChordA * sourceChordA;
    float projectedProgress =
            (0.5 * (desiredCoord.x - neutralCoord.x) * sourceChord.x
             + (desiredCoord.z - neutralCoord.z) * sourceChord.z / 6.0
             + (desiredA - neutralA) * sourceChordA)
            / max(sourceChordLength2, PSYCHO30_EPSILON2);
    float responseProgress = customPsycho31CInfinityClamp01(projectedProgress);
    // Convex interpolation stays in the target cube even for signed responses.
    float3 mappedCoord = lerp(neutralEndpointCoord, targetEndpointCoord, responseProgress);
    // Share the pixel's Yf ceiling so this chord and the direct endpoint meet
    // at black. Scaling toward black preserves containment and chord hue.
    float cageA = psycho31YfFromTest30Coord(mappedCoord);
    float ceilingA = clamp(desiredA, 0.0, clamp(responseYfCeiling, 0.0, 1.0));
    if (cageA > ceilingA) {
        mappedCoord *= ceilingA / cageA;
    }
    valid = !any(isnan(mappedCoord)) && !any(isinf(mappedCoord)) ? 1u : 0u;
    return valid != 0u ? mappedCoord : float3(0.0);
}

// --- PsychoV31 core (reference custom_psychotm_test31 over the pinned profile) ---

// peakValue is the display peak over effective white; returns linear BT.709.
public float3 psychoV31(float3 bt709LinearInput, float peakValue, int gamutCompressionMode,
        float compression, float gamutCompression, float highlights, float shadows,
        float contrast, float purity, float sourceAwareness) {
    // Test30 frontend: retain the source RGB coordinate while evaluating the
    // same transport (BT.709 pinned; exposure pinned to 1).
    float3 exposedInput = bt709LinearInput;
    float3 inputLms;
    float3 anchoredSourceRgb = exposedInput;
    float3x3 sourceToLms = PSYCHO30_BT709_TO_LMS_MAT;
    if (all(exposedInput <= float3(0.0)) && !any(isinf(exposedInput))) {
        return float3(0.0);
    }
    inputLms = customPsycho31AnchorSourceBoundaryToYf(
            exposedInput,
            sourceToLms,
            PSYCHO30_BT709_SOURCE_YF_WEIGHTS,
            anchoredSourceRgb);

    float3 anchorInLms = mul(PSYCHO30_BT709_TO_LMS_MAT, float3(0.18));
    // The background state is pinned equal to the adaptation state.
    float3 anchorOutLms = anchorInLms;
    float targetRgbPeak = peakValue;
    float3 targetPeakLms = PSYCHO30_D65_WHITE_LMS * targetRgbPeak;

    float3 sourceQ;
    float responseYf;
    uint responseValid;
    float3 desiredTest30Coord = customPsycho31ResponseCoord(
            inputLms,
            anchorInLms,
            anchorOutLms,
            targetPeakLms,
            highlights,
            shadows,
            contrast,
            purity,
            compression,
            sourceQ,
            responseYf,
            responseValid);
    if (responseValid == 0u) {
        return float3(0.0);
    }

    float targetCompressionWeight = gamutCompression;
    if (targetCompressionWeight == 0.0) {
        return mul(PSYCHO30_LMS_TO_BT709_MAT, psycho31LMSFromTest30Coord(
                desiredTest30Coord, targetRgbPeak));
    }

    float3 desiredLms = psycho31LMSFromTest30Coord(desiredTest30Coord, targetRgbPeak);
    // Only demand is needed to decide whether the direct endpoint can be skipped.
    float normalizedDemand = customPsycho31NormalizedL8TargetDemand(
            desiredTest30Coord,
            responseYf,
            gamutCompressionMode);
    float sourceWeight = clamp(sourceAwareness, 0.0, 1.0);
    // The source-awareness blend ramps over the L8 demand knee 0.8..2.
    float kneePosition =
            (normalizedDemand - CUSTOM_PSYCHO31_GAMUT_KNEE_START)
            / (CUSTOM_PSYCHO31_GAMUT_KNEE_END
               - CUSTOM_PSYCHO31_GAMUT_KNEE_START);
    sourceWeight *= customPsycho31CInfinityTransition(kneePosition);
    float3 sourceAwareTest30Coord = float3(0.0);
    uint cageValid = 0u;
    if (sourceWeight != 0.0) {
        float3 cageTest30Coord = customPsycho31SourceCoordinateCage(
                desiredTest30Coord,
                anchoredSourceRgb,
                sourceToLms,
                anchorInLms,
                anchorOutLms,
                targetPeakLms,
                highlights,
                shadows,
                contrast,
                purity,
                compression,
                responseYf,
                gamutCompressionMode,
                cageValid);
        if (cageValid != 0u) {
            sourceAwareTest30Coord = cageTest30Coord;
        }
    }

    float3 kneeMappedTest30Coord = float3(0.0);
    uint solveValid = 0u;
    if (sourceWeight != 1.0 || cageValid == 0u) {
        kneeMappedTest30Coord = customPsycho31MapL8TargetDemand(
                desiredTest30Coord, responseYf, gamutCompressionMode, normalizedDemand);
        solveValid = !any(isnan(kneeMappedTest30Coord))
                              && !any(isinf(kneeMappedTest30Coord))
                ? 1u
                : 0u;
        if (cageValid == 0u) {
            // Preserve TargetEndpoint's finite-to-zero fallback, including
            // weight 1.
            sourceAwareTest30Coord = solveValid != 0u ? kneeMappedTest30Coord : float3(0.0);
        }
    }

    float3 mappedLms;
    if (sourceWeight == 1.0) {
        mappedLms = psycho31LMSFromTest30Coord(sourceAwareTest30Coord, targetRgbPeak);
    } else {
        if (solveValid == 0u) {
            return float3(0.0);
        }
        if (sourceWeight != 0.0) {
            // The coordinate-to-LMS transform is linear; reconstruct the blend
            // once.
            kneeMappedTest30Coord = lerp(
                    kneeMappedTest30Coord,
                    sourceAwareTest30Coord,
                    sourceWeight);
        }
        mappedLms = psycho31LMSFromTest30Coord(kneeMappedTest30Coord, targetRgbPeak);
    }

    float3 outputLms = lerp(desiredLms, mappedLms, targetCompressionWeight);
    float3 outputBt709 = mul(PSYCHO30_LMS_TO_BT709_MAT, outputLms);
    return !any(isnan(outputBt709)) && !any(isinf(outputBt709))
            ? outputBt709
            : float3(0.0);
}
