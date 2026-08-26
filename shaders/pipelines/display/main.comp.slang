// Maps the display-res scene-linear ACEScg RT image to sRGB SDR and, when enabled, PQ/BT.2020 HDR.
// The path tracer and DLSS-RR use scene-linear ACEScg; exposure is applied from the compositor-owned
// 1x1 image here. ACES 2.0 uses the baked display-transform LUTs and the package's scene-referred look
// (LMT); local analytical modes own their complete display rendering.

import display_common;
import bindings;
import tone_mapping;

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

float3 sampleBloom(int2 outputPixel, uint outputWidth, uint outputHeight) {
    float2 uv = (float2(outputPixel) + 0.5) / float2(outputWidth, outputHeight);
    return bloomImage.SampleLevel(uv, 0.0).rgb;
}

float skyAtRenderPixel(int2 renderPixel) {
    uint renderWidth, renderHeight;
    skyClassificationImage.GetDimensions(renderWidth, renderHeight);
    int2 maxPixel = int2(renderWidth, renderHeight) - int2(1, 1);
    return clamp(skyClassificationImage[clamp(renderPixel, int2(0, 0), maxPixel)], 0.0, 1.0);
}

// Upscale the primary-sky classification with the same bilinear footprint as the display pixel. The
// separate display classification marks only a visible primary sky miss; transmitted sky remains a surface feature.
float skyCoverage(int2 pixel, uint displayWidth, uint displayHeight) {
    uint renderWidth, renderHeight;
    skyClassificationImage.GetDimensions(renderWidth, renderHeight);
    float2 sampleCoord = (float2(pixel) + 0.5) * float2(renderWidth, renderHeight)
            / float2(displayWidth, displayHeight) - 0.5;
    int2 base = int2(floor(sampleCoord));
    float2 fraction = fract(sampleCoord);
    return skyAtRenderPixel(base) * (1.0 - fraction.x) * (1.0 - fraction.y)
            + skyAtRenderPixel(base + int2(1, 0)) * fraction.x * (1.0 - fraction.y)
            + skyAtRenderPixel(base + int2(0, 1)) * (1.0 - fraction.x) * fraction.y
            + skyAtRenderPixel(base + int2(1, 1)) * fraction.x * fraction.y;
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

static const float3 BT709_TO_ACESCG_R = float3(0.61309743, 0.33952314, 0.04737945);
static const float3 BT709_TO_ACESCG_G = float3(0.07019372, 0.91635388, 0.01345240);
static const float3 BT709_TO_ACESCG_B = float3(0.02061559, 0.10956977, 0.86981463);

float3 bt709ToAcesCg(float3 bt709) {
    return float3(
            dot(bt709, BT709_TO_ACESCG_R),
            dot(bt709, BT709_TO_ACESCG_G),
            dot(bt709, BT709_TO_ACESCG_B));
}

float2 endSkyUv(float3 dir) {
    float3 a = abs(dir);
    float t = 100.0 / max(max(a.x, a.y), a.z);
    float3 p = dir * t;
    if (a.x >= a.y && a.x >= a.z) {
        if (dir.x > 0.0) return (float2(p.y, p.z) + 100.0) * (16.0 / 200.0);
        return (float2(-p.y, p.z) + 100.0) * (16.0 / 200.0);
    }
    if (a.y >= a.z) {
        if (dir.y > 0.0) return (float2(p.x, -p.z) + 100.0) * (16.0 / 200.0);
        return (float2(p.x, p.z) + 100.0) * (16.0 / 200.0);
    }
    if (dir.z > 0.0) return (float2(p.x, p.y) + 100.0) * (16.0 / 200.0);
    return (float2(p.x, -p.y) + 100.0) * (16.0 / 200.0);
}

float3 nativeEndFlash(float3 dir) {
    if ((pc.skyFlags & 1u) == 0u) return float3(0.0);
    static const float PI = 3.14159265359;
    float y = PI - pc.skyParams.w;
    float x = -0.5 * PI - pc.skyParams.z;
    float cy = cos(y), sy = sin(y), cx = cos(x), sx = sin(x);
    float3 right = float3(cy, 0.0, -sy);
    float3 up = float3(sy * sx, cx, cy * sx);
    float3 forward = float3(sy * cx, -sx, cy * cx);
    float3 center = up * 100.0;
    float denom = dot(dir, up);
    if (abs(denom) < 1.0e-5) return float3(0.0);
    float hitDistance = dot(center, up) / denom;
    if (hitDistance <= 0.0) return float3(0.0);
    float3 q = dir * hitDistance - center;
    float2 local = float2(dot(q, right), dot(q, forward)) / 60.0;
    if (max(abs(local.x), abs(local.y)) > 1.0) return float3(0.0);
    float2 uv = lerp(pc.endFlashUv.xy, pc.endFlashUv.zw, local * 0.5 + 0.5);
    float4 texel = celestialsAtlas.SampleLevel(uv, 0.0);
    float intensity = pc.skyParams.y;
    return srgbDecode(texel.rgb) * texel.a * intensity * intensity;
}

float3 nativeEndSky(float2 uv) {
    float2 ndc = uv * 2.0 - 1.0;
    float4 nearH = mul(pc.invViewProj, float4(ndc, 1.0, 1.0));
    float4 farH = mul(pc.invViewProj, float4(ndc, 0.0, 1.0));
    float3 dir = normalize(farH.xyz / max(farH.w, 1.0e-6) - nearH.xyz / max(nearH.w, 1.0e-6));
    float4 texel = endSkyTexture.SampleLevel(endSkyUv(dir), 0.0);
    float3 endSkyColor = srgbDecode(texel.rgb) * (40.0 / 255.0);
    return lerp(pc.skyColor.rgb, endSkyColor, clamp(texel.a, 0.0, 1.0)) + nativeEndFlash(dir);
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

// Local analytical operators were authored against scene-linear BT.709. The transport and the
// ACES 2.0 LUTs use ACEScg/AP1, so this conversion is explicit at the opt-in local seam; the default
// ACES 2.0 path never takes it.
static const float3 ACESCG_TO_BT709_R = float3(1.70505095, -0.62179214, -0.08325887);
static const float3 ACESCG_TO_BT709_G = float3(-0.13025641, 1.14080477, -0.01054832);
static const int PSYCHOV24_SDR_MODE = 8;
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

float3 localSdrToneMap(float3 exposedAcesCg) {
    float3 bt709Signed = acescgToBt709Signed(exposedAcesCg);
    ToneMappingParameters p = sdrToneParameters();
    float3 mapped = pc.sdrMode == PSYCHOV24_SDR_MODE
            ? toneMapPsychoV24Sdr(bt709Signed, p)
            : applyClassicToneMapper(pc.sdrMode, max(bt709Signed, float3(0.0)), p);
    return displayGammaSdr(srgbEncode(clamp(mapped, 0.0, 1.0)));
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

float3 localHdrToneMap(float3 exposedAcesCg) {
    float3 bt709Signed = acescgToBt709Signed(exposedAcesCg);
    ToneMappingParameters p = hdrToneParameters();
    if (pc.hdrMode == 2) {
        return toneMapPsychoV24Hdr(bt709Signed, pc.paperWhiteNits, pc.headroom, p);
    }
    return toneMapBt2390Hdr(max(bt709Signed, float3(0.0)), pc.paperWhiteNits, pc.headroom);
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

// Fixed screen-space rank order for final SDR quantization. The permutation is stable across frames so
// the dither cannot enter DLSS-RR history or shimmer with camera jitter; the half-step thresholds make
// each rank's rounding decision unbiased over the tile.
static const float SKY_DITHER_RANK_8X8[64] = {
    0.281250, 0.968750, 0.375000, 0.859375, 0.250000, 0.937500, 0.406250, 0.812500,
    0.593750, 0.187500, 0.734375, 0.046875, 0.578125, 0.218750, 0.703125, 0.015625,
    0.453125, 0.765625, 0.328125, 0.921875, 0.484375, 0.796875, 0.343750, 0.890625,
    0.640625, 0.093750, 0.546875, 0.156250, 0.671875, 0.078125, 0.515625, 0.125000,
    0.265625, 0.953125, 0.421875, 0.828125, 0.296875, 0.984375, 0.390625, 0.843750,
    0.562500, 0.234375, 0.687500, 0.000000, 0.609375, 0.203125, 0.718750, 0.031250,
    0.468750, 0.781250, 0.359375, 0.875000, 0.437500, 0.750000, 0.312500, 0.906250,
    0.656250, 0.062500, 0.500000, 0.140625, 0.625000, 0.109375, 0.531250, 0.171875
};

float3 ditherSkySdr(float3 displayColor, int2 pixel) {
    uint2 tilePixel = uint2(pixel) & uint2(7u, 7u);
    float rank = SKY_DITHER_RANK_8X8[int(tilePixel.y * 8u + tilePixel.x)];
    float threshold = rank + 0.5 / 64.0;
    float3 code = clamp(displayColor, 0.0, 1.0) * 255.0;
    float3 lower = floor(code);
    float3 fraction = code - lower;
    float3 roundUp = float3(
            fraction.r > threshold ? 1.0 : 0.0,
            fraction.g > threshold ? 1.0 : 0.0,
            fraction.b > threshold ? 1.0 : 0.0);
    return (lower + roundUp) / 255.0;
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

    float4 rt = rtImage[pix];
    float exposure = max(exposureImage[int2(0, 0)], 0.0);
    float3 sceneLinearAcesCg = max(rt.rgb, float3(0.0));
    float coverage = 0.0;
    if (pc.skybox == 2u || (pc.hdrEnabled == 0 && pc.skybox == 1u)) {
        coverage = skyCoverage(pix, w, h);
    }
    if (pc.skybox == 2u) {
        sceneLinearAcesCg = lerp(sceneLinearAcesCg,
                bt709ToAcesCg(max(nativeEndSky((float2(pix) + 0.5) / float2(w, h)), float3(0.0))),
                clamp(coverage, 0.0, 1.0));
    }
    float3 exposedAcesCg = sceneLinearAcesCg * exposure;
    exposedAcesCg += sampleBloom(pix, w, h) * max(pc.bloomStrength, 0.0);
    float3 lookedAcesCg = exposedAcesCg;
    if (pc.sdrMode == 0 || (pc.hdrEnabled != 0 && pc.hdrMode == 0)) {
        lookedAcesCg = applyLook(exposedAcesCg);
    }
    float3 sdrColor = pc.sdrMode == 0
            ? tonemap(lookedAcesCg)
            : localSdrToneMap(exposedAcesCg);
    if (pc.hdrEnabled == 0 && pc.skybox == 1u && coverage >= 0.999) {
        sdrColor = ditherSkySdr(sdrColor, pix);
    }
    outputImage[pix] = float4(sdrColor, 1.0);

    if (pc.hdrEnabled != 0) {
        hdrImage[pix] = pc.hdrMode == 0
                ? float4(tonemapHdr(lookedAcesCg), 1.0)
                : float4(displayGammaHdr(localHdrToneMap(exposedAcesCg)), 1.0);
    }
}
