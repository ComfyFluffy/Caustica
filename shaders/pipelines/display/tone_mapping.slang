import psychov24;


static const float3 LUMA_BT709 = float3(0.2126, 0.7152, 0.0722);
static const float3 LUMA_BT2020 = float3(0.2627, 0.6780, 0.0593);

// Row-major mathematical notation; the build targets column-major storage, while mul(M, v)
// preserves the intended color-space transforms.
static const float3x3 BT709_TO_BT2020 = float3x3(
    0.6274039, 0.3292830, 0.0433131,
    0.0690973, 0.9195406, 0.0113612,
    0.0163916, 0.0880132, 0.8955953
);
static const float3x3 PSYCHO24_BT709_TO_BT2020 = float3x3(
    0.6274039149, 0.3292830288, 0.04331305996,
    0.0690972954, 0.9195403457, 0.01136231795,
    0.01639143936, 0.08801331371, 0.8955952525
);

static const float3x3 BT2020_TO_BT709 = float3x3(
     1.6604910, -0.5876411, -0.0728499,
    -0.1245505,  1.1328999, -0.0083494,
    -0.0181508, -0.1005789,  1.1187297
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

float safeDiv(float a, float b, float fallback) {
    return abs(b) <= 1.0e-12 ? fallback : a / b;
}

float3 safeDiv(float3 a, float3 b, float3 fallback) {
    const float eps = 1.0e-12;
    return float3(abs(b.x) <= eps ? fallback.x : a.x / b.x,
            abs(b.y) <= eps ? fallback.y : a.y / b.y,
            abs(b.z) <= eps ? fallback.z : a.z / b.z);
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

public float3 applyClassicToneMapper(int mode, float3 color, ToneMappingParameters p) {
    if (mode == 1) {
        return agx(color, p.param0, p.param1);
    } else if (mode == 2) {
        return pbrNeutralTonemap(color, p.param0, p.param1);
    } else if (mode == 3) {
        return reinhardExtendedTonemap(color, p.param0);
    } else if (mode == 4) {
        return acesNarkowiczTonemap(color, p.param0);
    } else if (mode == 5) {
        return lottesTonemap(color, p.param0, p.param1, p.param2, p.param3, p.param4);
    } else if (mode == 6) {
        return uncharted2Tonemap(color, p.param0, p.param1, p.param2, p.param3,
                p.param4, p.param5, p.param6);
    } else if (mode == 7) {
        return gtTonemap(color, p.param0, p.param1, p.param2, p.param3, p.param4);
    }
    return color;
}

public float3 toneMapPsychoV24Sdr(float3 color, ToneMappingParameters p) {
    return psychoV24(color, 1.0,
            p.param0, p.param1, 0, p.param2, p.param3, p.param4, p.param5);
}

public float3 toneMapPsychoV24Hdr(float3 paperReferred709, float paperWhiteNits,
        float headroom, ToneMappingParameters p) {
    float3 mapped709 = psychoV24(paperReferred709, headroom,
            p.param0, p.param1, 1, p.param2, p.param3, p.param4, p.param5);
    float3 mapped2020 = max(mul(PSYCHO24_BT709_TO_BT2020, mapped709), float3(0.0));
    return pqEncodeNits(mapped2020 * paperWhiteNits);
}

// BT.2390 PQ EETF. This is the standard's luma-Y' variant: the 1:1 central region is preserved,
// the Hermite knee rolls the PQ luma toward the target display peak, and a zero-black target is used
// because Caustica has no ambient-black control. The EETF is deliberately not applied independently
// to RGB channels; BT.2390 warns that doing so can introduce color and saturation shifts.
float bt2390EetfPq(float encodedLuminance, float targetPeakNits) {
    float maxLum = pqEncode(clamp(targetPeakNits, 1.0, 10000.0));
    float kneeStart = clamp(1.5 * maxLum - 0.5, 0.0, 1.0);
    float inputPq = clamp(encodedLuminance, 0.0, 1.0);
    float e2 = inputPq;
    if (inputPq < kneeStart || kneeStart >= 0.999999) {
        return e2;
    }

    float t = clamp((inputPq - kneeStart) / max(1.0 - kneeStart, 1.0e-6), 0.0, 1.0);
    float t2 = t * t;
    float t3 = t2 * t;
    e2 = (2.0 * t3 - 3.0 * t2 + 1.0) * kneeStart
            + (t3 - 2.0 * t2 + t) * (1.0 - kneeStart)
            + (-2.0 * t3 + 3.0 * t2) * maxLum;
    // E3 = E2 + b * (1 - E2)^4; b is zero for the renderer's zero-black target.
    return clamp(e2, 0.0, 1.0);
}

public float3 toneMapBt2390Hdr(float3 paperReferred709, float paperWhiteNits, float headroom) {
    float3 paperReferred = max(paperReferred709, float3(0.0));
    float3 nits2020 = max(mul(BT709_TO_BT2020, paperReferred * paperWhiteNits), float3(0.0));
    float3 encoded = clamp(pqEncodeNits(nits2020), float3(0.0), float3(1.0));
    float inputLuma = dot(encoded, LUMA_BT2020);
    float mappedLuma = bt2390EetfPq(inputLuma, paperWhiteNits * headroom);
    return clamp(luminanceTonemap(encoded, mappedLuma, inputLuma), float3(0.0), float3(1.0));
}
