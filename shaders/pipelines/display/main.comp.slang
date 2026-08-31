// Maps the display-res scene-linear ACEScg RT image to sRGB SDR and, when enabled, PQ/BT.2020 HDR,
// via baked ACES 2.0 display-transform LUTs (see tools/bake_display_lut.py). Tonemap seam: the path tracer and DLSS-RR use scene-linear
// ACEScg; exposure is applied from the compositor-owned 1x1 image right here, followed by the selected
// scene-referred ACES look (LMT), then the shared ACES 2.0 output transform for each display.

import display_common;
import bindings;

[[vk::push_constant]] DisplayPush pc;

// MUST exactly match SHAPER_LO_STOPS/SHAPER_HI_STOPS in tools/bake_display_lut.py -- these are the two
// halves of one shaper function, one run at bake time (Python), one at sample time (here); if they
// drift the LUT fetch reads the wrong shelf of the LUT everywhere, silently.
static const float LUT_SHAPER_LO_STOPS = -12.0;
static const float LUT_SHAPER_HI_STOPS = 12.0;

float3 shaperEncode(float3 linearColor) {
    float3 stops = log2(max(linearColor, float3(1.0e-6)));
    return clamp((stops - LUT_SHAPER_LO_STOPS) / (LUT_SHAPER_HI_STOPS - LUT_SHAPER_LO_STOPS), 0.0, 1.0);
}

// Edge-aligned LUT: texel 0's centre is uvw=0.0 and texel (lutSize-1)'s centre is uvw=1.0, but a
// sampler's normalized [0,1] domain spans texel *centres* at 0.5/N .. 1-0.5/N by default. Remap into
// that convention; RtToneLut's CLAMP_TO_EDGE then holds the boundary texel for any exposed value
// outside the shaper's +/-12 EV range instead of wrapping. toneLut/hdrToneLut share pc.lutSize;
// the independently sized lookLut passes pc.lookLutSize.
float3 lutTexCoord(float3 uvw, float size) {
    return uvw * ((size - 1.0) / size) + float3(0.5 / size);
}

float3 shaperDecode(float3 shaped) {
    return exp2(lerp(
            float3(LUT_SHAPER_LO_STOPS),
            float3(LUT_SHAPER_HI_STOPS),
            clamp(shaped, 0.0, 1.0)));
}

float3 applyLook(float3 exposedAcesCg) {
    if (pc.lookEnabled == 0) {
        return exposedAcesCg;
    }
    float3 uvw = shaperEncode(exposedAcesCg);
    float3 shaped = lookLut.SampleLevel(lutTexCoord(uvw, pc.lookLutSize), 0.0).rgb;
    return shaperDecode(shaped);
}

// ---- Unreal-style colour grading -----------------------------------------------------------------
//
// A reimplementation of UE's ColorCorrectAll from PostProcessCombineLUTs.usf, so that a grade authored
// against a UE post-process volume — such as the one in the "Ultra Realism Tonemapper" guide — produces
// the same image here. Two things make that transfer exact rather than approximate: UE's default
// working colour space is AP1, which is this renderer's working space too, and UE grades scene-referred
// before its tonemapper, which is where this sits relative to the output transform.
//
// The 0.18 contrast pivot is what keeps contrast from also being an exposure change: scaling around
// mid grey leaves mid grey fixed, so contrast and exposure stay independent controls.
// ACEScg luma weights, matching world_common.slang's ACESCG_LUMA. Duplicated rather than imported:
// this pipeline does not otherwise depend on the ray tracer's module tree.
static const float3 ACESCG_LUMA = float3(0.27222872, 0.67408177, 0.05368952);
static const float GRADE_PIVOT = 0.18;
// UE's ColorCorrectionShadowsMax / HighlightsMax defaults. Only HighlightsMin is worth exposing —
// it is the one the guide actually moves, and the other two only reshape the blend between regions.
static const float GRADE_SHADOWS_MAX = 0.09;
static const float GRADE_HIGHLIGHTS_MAX = 1.0;

float3 colorCorrect(float3 color, float saturation, float contrast, float gain) {
    float luma = dot(color, ACESCG_LUMA);
    // Saturation as a lerp from luma, clamped at zero: values above 1 extrapolate away from grey, and
    // without the clamp a strongly saturated colour can extrapolate a channel negative and come back
    // through the tone LUT as a hard chroma artifact.
    color = max(lerp(float3(luma), color, saturation), float3(0.0));
    color = pow(max(color / GRADE_PIVOT, float3(0.0)), float3(contrast)) * GRADE_PIVOT;
    return color * gain;
}

float3 applyGrade(float3 acesCg) {
    if (pc.gradeEnabled == 0) {
        return acesCg;
    }
    float luma = dot(acesCg, ACESCG_LUMA);
    // Region weights sum to 1 by construction, so the three graded results blend without changing
    // overall level — the midtone weight is whatever the other two leave behind.
    float shadowWeight = 1.0 - smoothstep(0.0, GRADE_SHADOWS_MAX, luma);
    float highlightWeight = smoothstep(pc.highlightsMin, GRADE_HIGHLIGHTS_MAX, luma);
    float midWeight = max(1.0 - shadowWeight - highlightWeight, 0.0);

    // Region parameters multiply the global ones, exactly as UE composes them — so the guide's
    // highlight saturation of 0.95 means 0.95 x the global 0.75, not 0.95 outright.
    float3 midtones = colorCorrect(acesCg, pc.saturation, pc.contrast, pc.gain);
    float3 highlights = colorCorrect(acesCg, pc.saturation * pc.highlightSaturation,
                                     pc.contrast, pc.gain * pc.highlightGain);
    // Shadows use the global grade unmodified: the guide overrides no shadow-region parameters, and
    // inventing a shadow lift here would silently diverge from the source it is reproducing.
    return midtones * (shadowWeight + midWeight) + highlights * highlightWeight;
}

float3 sampleBloom(int2 outputPixel, uint outputWidth, uint outputHeight) {
    float2 uv = (float2(outputPixel) + 0.5) / float2(outputWidth, outputHeight);
    return bloomImage.SampleLevel(uv, 0.0).rgb;
}

// Artistic gamma after the view/display transform. Apply the curve to display luminance and scale
// RGB uniformly instead of exponentiating each channel: this lifts shadows/midtones without pulling
// chromaticities toward white. The uniform scale is limited at the display boundary so saturated
// colors preserve their channel ratios instead of clipping individual channels.
static const float3 BT709_LUMA = float3(0.2126, 0.7152, 0.0722);
static const float3 BT2020_LUMA = float3(0.2627, 0.6780, 0.0593);

float3 luminanceGamma(float3 linearDisplayColor, float3 lumaCoefficients) {
    float3 color = clamp(linearDisplayColor, 0.0, 1.0);
    float luminance = dot(color, lumaCoefficients);
    if (luminance <= 0.0) {
        return color;
    }

    float adjustedLuminance = pow(luminance, clamp(pc.gamma, 0.1, 5.0));
    float scale = adjustedLuminance / luminance;
    float maxChannel = max(color.r, max(color.g, color.b));
    float gamutLimitedScale = 1.0 / max(maxChannel, 1.0e-8);
    return color * min(scale, gamutLimitedScale);
}

float srgbDecodeChannel(float code) {
    return code <= 0.04045 ? code / 12.92 : pow((code + 0.055) / 1.055, 2.4);
}

float srgbEncodeChannel(float linearValue) {
    return linearValue <= 0.0031308
            ? 12.92 * linearValue
            : 1.055 * pow(linearValue, 1.0 / 2.4) - 0.055;
}

float3 srgbDecode(float3 code) {
    return float3(srgbDecodeChannel(code.r), srgbDecodeChannel(code.g), srgbDecodeChannel(code.b));
}

float3 srgbEncode(float3 linearColor) {
    return float3(
            srgbEncodeChannel(linearColor.r),
            srgbEncodeChannel(linearColor.g),
            srgbEncodeChannel(linearColor.b));
}

float3 displayGammaSdr(float3 displayColor) {
    float3 code = clamp(displayColor, 0.0, 1.0);
    if (abs(pc.gamma - 1.0) < 1.0e-6) {
        return code;
    }
    return clamp(srgbEncode(luminanceGamma(srgbDecode(code), BT709_LUMA)), 0.0, 1.0);
}

static const float PQ_M1 = 0.1593017578125;
static const float PQ_M2 = 78.84375;
static const float PQ_C1 = 0.8359375;
static const float PQ_C2 = 18.8515625;
static const float PQ_C3 = 18.6875;

float3 pqDecode(float3 code) {
    float3 p = pow(clamp(code, 0.0, 1.0), float3(1.0 / PQ_M2));
    float3 y = max((p - PQ_C1) / max(PQ_C2 - PQ_C3 * p, float3(1.0e-6)), float3(0.0));
    return pow(y, float3(1.0 / PQ_M1)) * 10000.0;
}

float3 pqEncode(float3 nits) {
    float3 y = pow(max(nits, float3(0.0)) / 10000.0, float3(PQ_M1));
    return pow((PQ_C1 + PQ_C2 * y) / (1.0 + PQ_C3 * y), float3(PQ_M2));
}

float3 displayGammaHdr(float3 pqColor) {
    if (abs(pc.gamma - 1.0) < 1.0e-6) {
        return clamp(pqColor, 0.0, 1.0);
    }
    float peak = max(pc.hdrPeakNits, 1.0);
    float3 normalizedNits = clamp(pqDecode(pqColor) / peak, 0.0, 1.0);
    float3 adjustedNits = peak * luminanceGamma(normalizedNits, BT2020_LUMA);
    return clamp(pqEncode(adjustedNits), 0.0, 1.0);
}

// Exposure -> log2 shaper -> trilinear LUT fetch. The LUT already bakes in ACES 2.0's gamut mapping to
// BT.709 and the sRGB OETF, so its output goes straight to outputImage. Single mip (RtToneLut), so an
// explicit level-0 SampleLevel is exact, not an approximation.
float3 tonemap(float3 lookedAcesCg) {
    float3 uvw = shaperEncode(lookedAcesCg);
    return displayGammaSdr(toneLut.SampleLevel(lutTexCoord(uvw, pc.lutSize), 0.0).rgb);
}

// Same shaper as tonemap() but sampling the HDR LUT, whose range is already PQ-encoded BT.2020 -- no
// separate encode or UI-brightness mapping needed.
float3 tonemapHdr(float3 lookedAcesCg) {
    float3 uvw = shaperEncode(lookedAcesCg);
    return displayGammaHdr(hdrToneLut.SampleLevel(lutTexCoord(uvw, pc.lutSize), 0.0).rgb);
}

// Everything up to, but not including, the output transform. Factored out because RCAS below needs it
// at four neighbouring pixels as well as the centre.
float3 gradedAcesCg(int2 pix, float exposure, uint w, uint h) {
    float3 exposedAcesCg = max(rtImage[pix].rgb * exposure, float3(0.0));
    exposedAcesCg += sampleBloom(pix, w, h) * max(pc.bloomStrength, 0.0);
    return applyGrade(applyLook(exposedAcesCg));
}

// ---- RCAS ------------------------------------------------------------------------------------------
//
// AMD's Robust Contrast-Adaptive Sharpening, from FSR. "Robust" is the operative word: rather than a
// fixed unsharp kernel, it derives a per-pixel sharpening lobe from how much headroom the local
// neighbourhood actually has, so it cannot ring a highlight into clipping or crush a shadow. That makes
// it the right choice downstream of DLSS Ray Reconstruction, where a fixed sharpen would amplify the
// denoiser's own reconstruction error along edges.
//
// TWO DELIBERATE PLACEMENT DECISIONS:
//
// It runs AFTER the output transform, on display code values, not on scene-linear radiance. RCAS's
// headroom maths assumes a bounded signal with a peak of 1. Scene-linear HDR has no such bound — a
// 10,000 nit emitter is just a large number — and sharpening it would produce overshoot proportional to
// the highlight's absolute intensity rather than to its visible contrast.
//
// The HDR path sharpens PQ code values, which is what "HDR-aware" means here. PQ is perceptually
// uniform and bounded [0,1], so the same lobe produces the same *apparent* edge enhancement at 100 nits
// and at 1000 nits. Sharpening linear nits instead would make bright highlights receive hundreds of
// times more absolute overshoot than midtones, which reads as ringing exactly where the display is most
// able to show it.
//
// Cost is four extra pipeline evaluations per pixel. Gated on sharpness, so a disabled slider costs one
// comparison.
static const float RCAS_LIMIT = -0.1875; // FSR's clamp on the lobe; beyond it the filter visibly rings.

float3 rcas(float3 e, float3 b, float3 d, float3 f, float3 h, float sharpness) {
    float3 mn4 = min(min(b, d), min(f, h));
    float3 mx4 = max(max(b, d), max(f, h));
    // Headroom to black and to peak white, per channel. The tighter of the two bounds the lobe, so a
    // pixel already near either end of the range is sharpened less rather than pushed past it.
    float3 hitMin = mn4 / (4.0 * max(mx4, float3(1.0e-6)));
    float3 hitMax = (1.0 - mx4) / (4.0 * max(mx4, float3(1.0e-6)));
    float3 lobeRgb = max(-hitMin, hitMax);
    float lobe = max(RCAS_LIMIT, min(max(lobeRgb.r, max(lobeRgb.g, lobeRgb.b)), 0.0)) * sharpness;
    return (lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0);
}

int2 clampPixel(int2 pix, uint w, uint h) {
    return int2(clamp(pix.x, 0, int(w) - 1), clamp(pix.y, 0, int(h) - 1));
}

[shader("compute")]
[numthreads(16, 16, 1)]
void main(uint3 dispatchId : SV_DispatchThreadID) {
    int2 pix = int2(dispatchId.xy);
    uint w, h;
    outputImage.GetDimensions(w, h);
    if (pix.x >= int(w) || pix.y >= int(h)) {
        return;
    }

    float exposure = max(exposureImage[int2(0, 0)], 0.0);
    float3 lookedAcesCg = gradedAcesCg(pix, exposure, w, h);

    float sharpness = max(pc.sharpness, 0.0);
    if (sharpness <= 0.0) {
        outputImage[pix] = float4(tonemap(lookedAcesCg), 1.0);
        if (pc.hdrEnabled != 0) {
            hdrImage[pix] = float4(tonemapHdr(lookedAcesCg), 1.0);
        }
        return;
    }

    // Edge pixels clamp to themselves, which makes the lobe collapse toward zero at the border rather
    // than sharpening against a wrapped or undefined neighbour.
    float3 upAcesCg = gradedAcesCg(clampPixel(pix + int2(0, -1), w, h), exposure, w, h);
    float3 leftAcesCg = gradedAcesCg(clampPixel(pix + int2(-1, 0), w, h), exposure, w, h);
    float3 rightAcesCg = gradedAcesCg(clampPixel(pix + int2(1, 0), w, h), exposure, w, h);
    float3 downAcesCg = gradedAcesCg(clampPixel(pix + int2(0, 1), w, h), exposure, w, h);

    outputImage[pix] = float4(rcas(tonemap(lookedAcesCg), tonemap(upAcesCg), tonemap(leftAcesCg),
                                   tonemap(rightAcesCg), tonemap(downAcesCg), sharpness), 1.0);

    if (pc.hdrEnabled != 0) {
        hdrImage[pix] = float4(rcas(tonemapHdr(lookedAcesCg), tonemapHdr(upAcesCg),
                                    tonemapHdr(leftAcesCg), tonemapHdr(rightAcesCg),
                                    tonemapHdr(downAcesCg), sharpness), 1.0);
    }
}
