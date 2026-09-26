// Maps the display-res scene-linear ACEScg RT image to sRGB SDR and, when enabled, PQ/BT.2020 HDR.
// The ACES 2.0 modes use the baked display-transform LUTs (see tools/bake_display_lut.py):
// exposure, then the selected scene-referred ACES look (LMT), then the shared ACES 2.0 output
// transform for each display. Every other mode selects an analytic operator from tone_mapping.slang,
// which runs on the exposed scene-linear signal converted to BT.709 and never samples the tone LUTs.
//
// The selected SDR and HDR operators and the HDR switch are specialization constants that
// RtDisplayPipeline fixes per shader variant, so each variant compiles only its own operators and
// carries only their register pressure.

import display_common;
import presentation_common;
import tone_mapping;

[[vk::push_constant]] DisplayPush pc;

[vk::constant_id(0)] const bool HDR_ENABLED = false;
[vk::constant_id(1)] const int SDR_MODE = 0;
[vk::constant_id(2)] const int HDR_MODE = 0;

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
    Texture3D<float4> lookLut = ResourceDescriptorHeap[NonUniformResourceIndex(pc.lookLutIndex)];
    SamplerState lookSampler = SamplerDescriptorHeap[NonUniformResourceIndex(pc.lookSamplerIndex)];
    float3 shaped = lookLut.SampleLevel(lookSampler, lutTexCoord(uvw, pc.lookLutSize), 0.0).rgb;
    return shaperDecode(shaped);
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

float3 displayGammaSdr(float3 displayColor) {
    float3 code = clamp(displayColor, 0.0, 1.0);
    if (abs(pc.gamma - 1.0) < 1.0e-6) {
        return code;
    }
    return clamp(linearToSrgb(luminanceGamma(srgbToLinear(code), BT709_LUMA)), 0.0, 1.0);
}

float3 pqDecode(float3 code) {
    float3 p = pow(clamp(code, 0.0, 1.0), float3(1.0 / PQ_M2));
    float3 y = max((p - PQ_C1) / max(PQ_C2 - PQ_C3 * p, float3(1.0e-6)), float3(0.0));
    return pow(y, float3(1.0 / PQ_M1)) * 10000.0;
}

float3 pqEncode(float3 nits) {
    float3 y = pow(max(nits, float3(0.0)) / 10000.0, float3(PQ_M1));
    return pow((PQ_C1 + PQ_C2 * y) / (1.0 + PQ_C3 * y), float3(PQ_M2));
}

// peakNits is the peak the HDR transform targets: the bound ACES LUT's mastering peak for the
// ACES 2.0 mode, paper white times headroom for the analytic modes.
float3 displayGammaHdr(float3 pqColor, float peakNits) {
    if (abs(pc.gamma - 1.0) < 1.0e-6) {
        return clamp(pqColor, 0.0, 1.0);
    }
    float peak = max(peakNits, 1.0);
    float3 normalizedNits = clamp(pqDecode(pqColor) / peak, 0.0, 1.0);
    float3 adjustedNits = peak * luminanceGamma(normalizedNits, BT2020_LUMA);
    return clamp(pqEncode(adjustedNits), 0.0, 1.0);
}

// Exposure -> log2 shaper -> trilinear LUT fetch. The LUT already bakes in ACES 2.0's gamut mapping to
// BT.709 and the sRGB OETF, so its output goes straight to outputImage. Single mip (RtToneLut), so an
// explicit level-0 SampleLevel is exact, not an approximation.
float3 tonemap(float3 lookedAcesCg) {
    float3 uvw = shaperEncode(lookedAcesCg);
    Texture3D<float4> toneLut = ResourceDescriptorHeap[NonUniformResourceIndex(pc.toneLutIndex)];
    SamplerState toneSampler = SamplerDescriptorHeap[NonUniformResourceIndex(pc.toneSamplerIndex)];
    return displayGammaSdr(toneLut.SampleLevel(toneSampler, lutTexCoord(uvw, pc.lutSize), 0.0).rgb);
}

// Same shaper as tonemap() but sampling the HDR LUT, whose range is already PQ-encoded BT.2020 -- no
// separate encode or UI-brightness mapping needed.
float3 tonemapHdr(float3 lookedAcesCg) {
    float3 uvw = shaperEncode(lookedAcesCg);
    Texture3D<float4> hdrToneLut = ResourceDescriptorHeap[NonUniformResourceIndex(pc.hdrToneLutIndex)];
    SamplerState hdrToneSampler = SamplerDescriptorHeap[NonUniformResourceIndex(pc.hdrToneSamplerIndex)];
    return displayGammaHdr(hdrToneLut.SampleLevel(hdrToneSampler, lutTexCoord(uvw, pc.lutSize), 0.0).rgb,
            pc.hdrPeakNits);
}

// Local analytical operators were authored against scene-linear BT.709. The transport and the
// ACES 2.0 LUTs use ACEScg/AP1, so this conversion is explicit at the analytic seam; the ACES 2.0
// modes never take it.
static const float3 ACESCG_TO_BT709_R = float3(1.70505095, -0.62179214, -0.08325887);
static const float3 ACESCG_TO_BT709_G = float3(-0.13025641, 1.14080477, -0.01054832);
static const float3 ACESCG_TO_BT709_B = float3(-0.02400336, -0.12896897, 1.15297234);

float3 acescgToBt709Signed(float3 acesCg) {
    return float3(
            dot(acesCg, ACESCG_TO_BT709_R),
            dot(acesCg, ACESCG_TO_BT709_G),
            dot(acesCg, ACESCG_TO_BT709_B));
}

ToneMappingParameters sdrToneParameters() {
    ToneMappingParameters p;
    p.param0 = pc.sdrParam0;
    p.param1 = pc.sdrParam1;
    p.param2 = pc.sdrParam2;
    p.param3 = pc.sdrParam3;
    p.param4 = pc.sdrParam4;
    p.param5 = pc.sdrParam5;
    p.param6 = pc.sdrParam6;
    p.param7 = pc.sdrParam7;
    return p;
}

ToneMappingParameters hdrToneParameters() {
    ToneMappingParameters p;
    p.param0 = pc.hdrParam0;
    p.param1 = pc.hdrParam1;
    p.param2 = pc.hdrParam2;
    p.param3 = pc.hdrParam3;
    p.param4 = pc.hdrParam4;
    p.param5 = pc.hdrParam5;
    p.param6 = pc.hdrParam6;
    p.param7 = pc.hdrParam7;
    return p;
}

[shader("compute")]
[numthreads(16, 16, 1)]
void main(uint3 dispatchId : SV_DispatchThreadID) {
    [format("rgba8")] RWTexture2D<float4> outputImage = ResourceDescriptorHeap[NonUniformResourceIndex(pc.outputImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> rtImage = ResourceDescriptorHeap[NonUniformResourceIndex(pc.rtImageIndex)];
    [format("r32f")] RWTexture2D<float> exposureImage = ResourceDescriptorHeap[NonUniformResourceIndex(pc.exposureImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> hdrImage = ResourceDescriptorHeap[NonUniformResourceIndex(pc.hdrImageIndex)];
    int2 pix = int2(dispatchId.xy);
    uint w, h;
    outputImage.GetDimensions(w, h);
    if (pix.x >= int(w) || pix.y >= int(h)) {
        return;
    }

    float4 rt = rtImage[pix];
    float exposure = max(exposureImage[int2(0, 0)], 0.0);
    float3 exposedAcesCg = max(rt.rgb * exposure, float3(0.0));
    // The scene-referred look is part of the ACES 2.0 rendering; analytical modes consume the
    // un-looked exposed signal and own their complete display rendering.
    float3 lookedAcesCg = exposedAcesCg;
    if (SDR_MODE == SDR_ACES_2_0 || (HDR_ENABLED && HDR_MODE == HDR_ACES_2_0)) {
        lookedAcesCg = applyLook(exposedAcesCg);
    }
    float3 bt709 = acescgToBt709Signed(exposedAcesCg);

    float3 sdrColor;
    if (SDR_MODE == SDR_ACES_2_0) {
        sdrColor = tonemap(lookedAcesCg);
    } else {
        // The swapchain target stores sRGB codes, so the display-linear analytic result is encoded.
        float3 mapped = applySdrToneMapper(SDR_MODE, bt709, sdrToneParameters());
        sdrColor = displayGammaSdr(linearToSrgb(clamp(mapped, 0.0, 1.0)));
    }
    outputImage[pix] = float4(sdrColor, 1.0);

    if (HDR_ENABLED) {
        float3 hdrColor;
        if (HDR_MODE == HDR_ACES_2_0) {
            hdrColor = tonemapHdr(lookedAcesCg);
        } else {
            hdrColor = displayGammaHdr(applyHdrToneMapper(HDR_MODE, bt709, pc.paperWhiteNits,
                    pc.headroom, hdrToneParameters()), pc.paperWhiteNits * pc.headroom);
        }
        hdrImage[pix] = float4(hdrColor, 1.0);
    }
}
