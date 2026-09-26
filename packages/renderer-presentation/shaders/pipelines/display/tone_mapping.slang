// Analytic display tone mappers. Each operator receives exposed scene-linear BT.709: the psycho
// family takes the signed signal (their cores gamut-map extended-range BT.709), every other
// operator the non-negative one. SDR operators return display-linear BT.709; HDR operators return
// PQ-encoded BT.2020 with paper white at paperWhiteNits and the peak at paperWhiteNits * headroom.
// Mode IDs mirror RtToneMapping.SdrMode/HdrMode; the ACES 2.0 modes are the LUT path in
// main.comp.slang and never reach these dispatchers.

import prism;
import psychov24;
import psychov30;
import psychov31;
import psychov69;
import psychovisual;

public static const int SDR_ACES_2_0 = 0;
public static const int SDR_AGX = 1;
public static const int SDR_PBR_NEUTRAL = 2;
public static const int SDR_REINHARD = 3;
public static const int SDR_ACES = 4;
public static const int SDR_LOTTES = 5;
public static const int SDR_UNCHARTED_2 = 6;
public static const int SDR_GT = 7;
public static const int SDR_PSYCHOV24 = 8;
public static const int SDR_PSYCHOV31 = 9;
public static const int SDR_PSYCHOVISUAL = 10;
public static const int SDR_PRISM = 11;
public static const int SDR_REINHARD_JODIE = 12;
public static const int SDR_PSYCHOV30 = 13;
public static const int SDR_PSYCHOV69 = 14;

public static const int HDR_ACES_2_0 = 0;
public static const int HDR_CAUSTICA = 1;
public static const int HDR_PSYCHOV24 = 2;
public static const int HDR_BT2390 = 3;
public static const int HDR_PSYCHOV31 = 4;
public static const int HDR_PSYCHOVISUAL = 5;
public static const int HDR_PRISM = 6;
public static const int HDR_PSYCHOV30 = 7;
public static const int HDR_PSYCHOV69 = 8;

static const float3 LUMA_BT709 = float3(0.2126, 0.7152, 0.0722);
static const float3 LUMA_BT2020 = float3(0.2627, 0.6780, 0.0593);

// Row-major mathematical notation; the build targets column-major storage, while mul(M, v)
// preserves the intended color-space transforms.
static const float3x3 BT709_TO_BT2020 = float3x3(
    0.6274039, 0.3292830, 0.0433131,
    0.0690973, 0.9195406, 0.0113612,
    0.0163916, 0.0880132, 0.8955953
);

static const float PQ_M1 = 0.1593017578125;
static const float PQ_M2 = 78.84375;
static const float PQ_C1 = 0.8359375;
static const float PQ_C2 = 18.8515625;
static const float PQ_C3 = 18.6875;

float pqEncode(float nits) {
    float y = pow(max(nits, 0.0) / 10000.0, PQ_M1);
    return pow((PQ_C1 + PQ_C2 * y) / (1.0 + PQ_C3 * y), PQ_M2);
}
float3 pqEncodeNits(float3 nits) {
    return float3(pqEncode(nits.r), pqEncode(nits.g), pqEncode(nits.b));
}

// Paper-white-relative BT.2020 bounded to the display peak, then PQ-encoded.
float3 pqEncodePaperRelativeBt2020(float3 paperRelativeBt2020, float paperWhiteNits,
        float headroom) {
    float3 bounded = clamp(paperRelativeBt2020, float3(0.0), float3(headroom));
    return pqEncodeNits(bounded * paperWhiteNits);
}

float safeDiv(float a, float b, float fallback) {
    return abs(b) <= 1.0e-12 ? fallback : a / b;
}

float luminanceBt709(float3 color) {
    return dot(color, LUMA_BT709);
}


static const float3x3 AGX_INSET = float3x3(
    0.842479062253094, 0.078433599999999, 0.079223745147764,
    0.042328242261012, 0.878468636469772, 0.079166127460543,
    0.042375654905705, 0.078433600000000, 0.879142973793104
);

static const float3x3 AGX_OUTSET = float3x3(
    1.196879005120170, -0.098020881140137, -0.099029744079720,
    -0.052896851757456, 1.151903129904170, -0.098961176844843,
    -0.052971635514443, -0.098043450117124, 1.151073672641160
);

static const float AGX_MIN_EV = -12.47393;
static const float AGX_MAX_EV = 4.026069;

// AgX's default contrast curve is the polynomial used by the selectable AgX operator.
float3 agxDefaultContrast(float3 x) {
    float3 x2 = x * x;
    float3 x4 = x2 * x2;
    return 15.5 * x4 * x2
        - 40.14 * x4 * x
        + 31.96 * x4
        - 6.868 * x2 * x
        + 0.4298 * x2
        + 0.1191 * x
        - 0.00232;
}

float3 applyLookAdjustments(float3 color, float contrast, float saturation) {
    if (abs(contrast - 1.0) <= 1.0e-6 && abs(saturation - 1.0) <= 1.0e-6) {
        return color;
    }
    color = clamp((color - 0.5) * max(contrast, 0.0) + 0.5, 0.0, 1.0);
    float luma = luminanceBt709(color);
    return clamp(lerp(float3(luma), color, max(saturation, 0.0)), 0.0, 1.0);
}

float3 agx(float3 color, float contrast, float saturation) {
    color = mul(AGX_INSET, max(color, float3(0.0)));
    color = clamp(log2(max(color, float3(1.0e-10))), AGX_MIN_EV, AGX_MAX_EV);
    color = (color - AGX_MIN_EV) / (AGX_MAX_EV - AGX_MIN_EV);
    color = agxDefaultContrast(color);
    color = mul(AGX_OUTSET, color);
    return applyLookAdjustments(clamp(color, 0.0, 1.0), contrast, saturation);
}

// Khronos PBR Neutral analytical operator.
float3 pbrNeutralTonemap(float3 color, float startCompression, float desaturation) {
    startCompression = clamp(startCompression, 0.0, 0.99);
    desaturation = max(desaturation, 0.0);
    color = max(color, float3(0.0));
    float x = min(color.r, min(color.g, color.b));
    float offset = x < 0.08 ? x - 6.25 * x * x : 0.04;
    color -= offset;

    float peak = max(color.r, max(color.g, color.b));
    if (peak < startCompression) {
        return color;
    }

    float d = 1.0 - startCompression;
    float newPeak = 1.0 - d * d / max(peak + d - startCompression, 1.0e-6);
    color *= safeDiv(newPeak, peak, 1.0);

    float g = 1.0 - 1.0 / (desaturation * (peak - newPeak) + 1.0);
    return lerp(color, float3(newPeak), g);
}

float3 luminanceTonemap(float3 color, float lMapped, float lSource) {
    return color * safeDiv(lMapped, lSource, 1.0);
}

// Extended Reinhard photographic operator.
float3 reinhardExtendedTonemap(float3 color, float whitePoint) {
    float l = luminanceBt709(color);
    if (l < 1.0e-6) {
        return color;
    }
    float lWhite = max(whitePoint, 1.0e-3);
    float lWhite2 = lWhite * lWhite;
    float lm = l * (1.0 + l / lWhite2) / (1.0 + l);
    return luminanceTonemap(color, clamp(lm, 0.0, 1.0), l);
}

// Reinhard applied per channel, blended toward luminance-only Reinhard as the pixel
// brightens so highlights roll off toward white instead of clipping to a hue.
float3 reinhardJodieTonemap(float3 color, float whitePoint) {
    float l = luminanceBt709(color);
    if (l < 1.0e-6) {
        return color;
    }
    float lWhite = max(whitePoint, 1.0e-3);
    float lWhite2 = lWhite * lWhite;
    float lm = clamp(l * (1.0 + l / lWhite2) / (1.0 + l), 0.0, 1.0);
    float3 perChannel = clamp(color * (1.0 + color / lWhite2) / (1.0 + color), 0.0, 1.0);
    return lerp(perChannel, float3(lm), clamp(l, 0.0, 1.0));
}

// Krzysztof Narkowicz's compact ACES fitted curve, applied to BT.709 luminance.
float3 acesNarkowiczTonemap(float3 color, float inputScale) {
    color *= max(inputScale, 0.0);
    float l = luminanceBt709(color);
    if (l < 1.0e-6) {
        return color;
    }
    float lm = (l * (2.51 * l + 0.03)) / max(l * (2.43 * l + 0.59) + 0.14, 1.0e-6);
    return luminanceTonemap(color, clamp(lm, 0.0, 1.0), l);
}

// Timothy Lottes's parameterized filmic curve.
float3 lottesTonemap(
        float3 color, float contrast, float shoulder, float maximum,
        float middleIn, float middleOut) {
    float l = luminanceBt709(color);
    if (l < 1.0e-6) {
        return color;
    }

    float a = max(contrast, 1.0e-3);
    float d = max(shoulder, 1.0e-3);
    float hdrMax = max(maximum, 1.0e-3);
    float midIn = max(middleIn, 1.0e-3);
    float midOut = max(middleOut, 1.0e-3);
    float hdrPow = pow(hdrMax, a * d);
    float midPow = pow(midIn, a * d);
    float denom = max((hdrPow - midPow) * midOut, 1.0e-6);
    float b = (-pow(midIn, a) + pow(hdrMax, a) * midOut) / denom;
    float c = (hdrPow * pow(midIn, a) - pow(hdrMax, a) * midPow * midOut) / denom;

    float lm = pow(l, a) / max(pow(l, a * d) * b + c, 1.0e-6);
    return luminanceTonemap(color, clamp(lm, 0.0, 1.0), l);
}

// John Hable's Uncharted 2 filmic curve.
float hablePartial(float x, float a, float b, float c, float d, float e, float f) {
    return ((x * (a * x + c * b) + d * e) / max(x * (a * x + b) + d * f, 1.0e-6)) - e / f;
}

float3 uncharted2Tonemap(
        float3 color, float a, float b, float c, float d, float e, float f,
        float whitePoint) {
    float l = luminanceBt709(color);
    if (l < 1.0e-6) {
        return color;
    }

    a = max(a, 1.0e-4);
    b = max(b, 1.0e-4);
    c = max(c, 0.0);
    d = max(d, 1.0e-4);
    e = max(e, 0.0);
    f = max(f, 1.0e-4);
    float w = max(whitePoint, 1.0e-3);
    float whiteScale = max(hablePartial(w, a, b, c, d, e, f), 1.0e-6);
    float lm = hablePartial(l, a, b, c, d, e, f) / whiteScale;
    return luminanceTonemap(color, clamp(lm, 0.0, 1.0), l);
}

// Hajime Uchimura's GT tone-mapping curve.
float3 gtTonemap(
        float3 color, float contrast, float linearStart, float linearLength,
        float blackCurve, float blackLift) {
    float l = luminanceBt709(color);
    if (l < 1.0e-6) {
        return color;
    }

    const float p = 1.0;
    float a = max(contrast, 1.0e-3);
    float m = clamp(linearStart, 1.0e-4, 0.99);
    linearLength = max(linearLength, 1.0e-4);
    float c = max(blackCurve, 1.0e-4);
    float b = blackLift;

    float l0 = ((p - m) * linearLength) / a;
    float s0 = m + l0;
    float s1 = m + a * l0;
    float c2 = (a * p) / max(p - s1, 1.0e-6);
    float cp = -c2 / p;

    float w0 = 1.0 - smoothstep(0.0, m, l);
    float w2 = step(m + l0, l);
    float w1 = 1.0 - w0 - w2;

    float toe = m * pow(max(l / m, 0.0), c) + b;
    float shoulder = p - (p - s1) * exp(cp * (l - s0));
    float lm = toe * w0 + l * w1 + shoulder * w2;
    return luminanceTonemap(color, clamp(lm, 0.0, 1.0), l);
}

// Soft highlight roll-off above paper white toward the display headroom.
float3 tonemapHdrCaustica(float3 paperReferred709, float paperWhiteNits, float headroom) {
    float3 c = paperReferred709;
    float3 lo = min(c, float3(1.0));
    float3 hi = max(c - float3(1.0), float3(0.0));
    float k = max(headroom - 1.0, 0.0);
    float3 rolled = (k > 0.0) ? (k * hi) / (k + hi) : float3(0.0);
    float3 paperReferred = lo + rolled;
    float3 nits709 = paperReferred * paperWhiteNits;
    float3 nits2020 = mul(BT709_TO_BT2020, nits709);
    return float3(pqEncode(nits2020.r), pqEncode(nits2020.g), pqEncode(nits2020.b));
}

// BT.2390 PQ EETF: the standard's luma variant. The 1:1 central region is
// preserved, a Hermite knee rolls PQ luma toward the display peak, and the
// target black is zero because this renderer has no ambient-black control.
// Applied to luma only: BT.2390 warns that per-channel application shifts
// color and saturation. The knee default 1.5*maxLum - 0.5 is the spec's
// knee_offset 0.5 form, not libplacebo's tuning default.
float bt2390EetfPq(float encodedLuminance, float targetPeakNits) {
    float maxLum = pqEncode(clamp(targetPeakNits, 1.0, 10000.0));
    float kneeStart = clamp(1.5 * maxLum - 0.5, 0.0, 1.0);
    float inputPq = clamp(encodedLuminance, 0.0, 1.0);
    if (inputPq < kneeStart || kneeStart >= 0.999999) {
        return inputPq;
    }
    float t = clamp((inputPq - kneeStart) / max(1.0 - kneeStart, 1.0e-6), 0.0, 1.0);
    float t2 = t * t;
    float t3 = t2 * t;
    float e2 = (2.0 * t3 - 3.0 * t2 + 1.0) * kneeStart
            + (t3 - 2.0 * t2 + t) * (1.0 - kneeStart)
            + (-2.0 * t3 + 3.0 * t2) * maxLum;
    // E3 = E2 + b * (1 - E2)^4; b is zero for the renderer's zero-black target.
    return clamp(e2, 0.0, 1.0);
}

float3 tonemapHdrBt2390(float3 paperReferred709, float paperWhiteNits, float headroom) {
    float3 nits2020 = max(mul(BT709_TO_BT2020, paperReferred709 * paperWhiteNits), float3(0.0));
    float3 encoded = clamp(pqEncodeNits(nits2020), float3(0.0), float3(1.0));
    float inputLuma = dot(encoded, LUMA_BT2020);
    float mappedLuma = bt2390EetfPq(inputLuma, paperWhiteNits * headroom);
    return clamp(encoded * (mappedLuma / max(inputLuma, 1.0e-6)), float3(0.0), float3(1.0));
}

// The operators below return paper-white-relative BT.709 for a peak of `headroom`.
float3 pqEncodeMappedBt709(float3 mapped709, float paperWhiteNits, float headroom) {
    // Signed BT.709 transport represents BT.2020-contained colors with negative BT.709 coordinates;
    // the target-domain clamp happens after conversion, in the target basis.
    float3 mapped2020 = max(mul(BT709_TO_BT2020, mapped709), float3(0.0));
    return pqEncodePaperRelativeBt2020(mapped2020, paperWhiteNits, headroom);
}

public struct ToneMappingParameters {
    public float param0;
    public float param1;
    public float param2;
    public float param3;
    public float param4;
    public float param5;
    public float param6;
    public float param7;
}

public float3 applySdrToneMapper(int mode, float3 bt709, ToneMappingParameters p) {
    float3 color = max(bt709, float3(0.0));
    if (mode == SDR_AGX) {
        return agx(color, p.param0, p.param1);
    } else if (mode == SDR_PBR_NEUTRAL) {
        return pbrNeutralTonemap(color, p.param0, p.param1);
    } else if (mode == SDR_REINHARD) {
        return reinhardExtendedTonemap(color, p.param0);
    } else if (mode == SDR_ACES) {
        return acesNarkowiczTonemap(color, p.param0);
    } else if (mode == SDR_LOTTES) {
        return lottesTonemap(color, p.param0, p.param1, p.param2, p.param3, p.param4);
    } else if (mode == SDR_UNCHARTED_2) {
        return uncharted2Tonemap(color, p.param0, p.param1, p.param2, p.param3,
                p.param4, p.param5, p.param6);
    } else if (mode == SDR_GT) {
        return gtTonemap(color, p.param0, p.param1, p.param2, p.param3, p.param4);
    } else if (mode == SDR_PSYCHOVISUAL) {
        return clamp(psychoVisual(bt709, 1.0, p.param0, p.param1, p.param2, p.param3,
                p.param4, p.param5, p.param6), 0.0, 1.0);
    } else if (mode == SDR_PRISM) {
        return clamp(prismTonemap(color, 1.0, p.param1, p.param0, float2(p.param2, p.param3),
                float2(p.param4, p.param5), float2(p.param6, p.param7)), 0.0, 1.0);
    } else if (mode == SDR_REINHARD_JODIE) {
        return reinhardJodieTonemap(color, p.param0);
    } else if (mode == SDR_PSYCHOV31) {
        return clamp(psychoV31(bt709, 1.0, 0, p.param0, p.param1, p.param2, p.param3,
                p.param4, p.param5, p.param6), 0.0, 1.0);
    } else if (mode == SDR_PSYCHOV30) {
        return clamp(psychoV30(bt709, 1.0, 0, p.param0, p.param1, p.param2, p.param3,
                p.param4, p.param5), 0.0, 1.0);
    } else if (mode == SDR_PSYCHOV69) {
        return clamp(psychoV69(bt709, 1.0, 0, p.param0, p.param1, p.param2, p.param3,
                p.param4, p.param5, p.param6, p.param7), 0.0, 1.0);
    }
    // SDR_PSYCHOV24
    return clamp(psychoV24(bt709, 1.0, p.param0, p.param1, 0, p.param2, p.param3,
            p.param4, p.param5), 0.0, 1.0);
}

public float3 applyHdrToneMapper(int mode, float3 paperReferred709, float paperWhiteNits,
        float headroom, ToneMappingParameters p) {
    float3 color = max(paperReferred709, float3(0.0));
    if (mode == HDR_CAUSTICA) {
        return tonemapHdrCaustica(color, paperWhiteNits, headroom);
    } else if (mode == HDR_BT2390) {
        return tonemapHdrBt2390(color, paperWhiteNits, headroom);
    } else if (mode == HDR_PSYCHOVISUAL) {
        return pqEncodeMappedBt709(psychoVisual(paperReferred709, headroom, p.param0, p.param1,
                p.param2, p.param3, p.param4, p.param5, p.param6), paperWhiteNits, headroom);
    } else if (mode == HDR_PRISM) {
        return pqEncodeMappedBt709(prismTonemap(color, headroom, p.param1, p.param0,
                float2(p.param2, p.param3), float2(p.param4, p.param5),
                float2(p.param6, p.param7)), paperWhiteNits, headroom);
    } else if (mode == HDR_PSYCHOV31) {
        return pqEncodeMappedBt709(psychoV31(paperReferred709, headroom, 1, p.param0, p.param1,
                p.param2, p.param3, p.param4, p.param5, p.param6), paperWhiteNits, headroom);
    } else if (mode == HDR_PSYCHOV30) {
        return pqEncodeMappedBt709(psychoV30(paperReferred709, headroom, 1, p.param0, p.param1,
                p.param2, p.param3, p.param4, p.param5), paperWhiteNits, headroom);
    } else if (mode == HDR_PSYCHOV69) {
        // PsychoV69 returns its contained BT.2020 target basis directly.
        float3 mapped2020 = max(psychoV69(paperReferred709, headroom, 1, p.param0, p.param1,
                p.param2, p.param3, p.param4, p.param5, p.param6, p.param7), float3(0.0));
        return clamp(pqEncodeNits(mapped2020 * paperWhiteNits), float3(0.0), float3(1.0));
    }
    // HDR_PSYCHOV24
    return pqEncodeMappedBt709(psychoV24(paperReferred709, headroom, p.param0, p.param1, 1,
            p.param2, p.param3, p.param4, p.param5), paperWhiteNits, headroom);
}
