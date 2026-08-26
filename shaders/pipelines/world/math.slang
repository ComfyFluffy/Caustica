// Stateless math: luminance, GGX (D/G/VNDF), Fresnel, Henyey-Greenstein, the RR specular-albedo
// fit, the PCG stream, and direction sampling. No bindings, no globals, no rays.

import world_common;
import world_core;
import sky;

public float luminance(float3 c) { return dot(c, ACESCG_LUMA); }

// GGX normal distribution.
// NOTE: the 1e-7 denominator guard is not merely a divide-by-zero epsilon, and it is load-bearing. At
// the lobe peak d == a2, so the guard dominates PI*d*d until a2 >> 1.8e-4, i.e. rough >> 0.116; every
// smoother material has its NEE highlight peak truncated. That truncation is what keeps the sun from
// being counted twice: a specular continuation sets showCelestial, so the BSDF-sampled ray may hit the
// sun disc in world.rmiss while NEE is also adding a sun term for the same lobe, with no MIS weighting
// between them. Removing the guard restores the physical peak AND the double count, and makes the
// single-sample sun estimator explode wherever the lobe is tighter than the disc (~0.0047 rad). The
// real fix is MIS (or a disc-aware specular light sample), not an epsilon edit — left as-is.
public float ggxD(float ndh, float alpha) {
    float a2 = alpha * alpha;
    float d = ndh * ndh * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d + 1.0e-7);
}

// Smith GGX masking term for one direction (separable form; G2 = G1(v) * G1(l)).
public float ggxG1(float ndx, float alpha) {
    float a2 = alpha * alpha;
    return 2.0 * ndx / (ndx + sqrt(a2 + (1.0 - a2) * ndx * ndx) + 1.0e-7);
}

public float3 fresnelSchlick(float cosT, float3 f0) {
    float m = clamp(1.0 - cosT, 0.0, 1.0);
    float m2 = m * m;
    return f0 + (1.0 - f0) * (m2 * m2 * m);
}

// Henyey–Greenstein phase function (with 4π normalisation so it integrates to 1 over the sphere).
// g=0 → isotropic; g>0 → forward-scatter peak at cosT=1 (light and view aligned through a thin slab).
public static const float SSS_G        = 0.6; // forward-scatter anisotropy; leaves/grass are quite directional
public static const float SSS_STRENGTH = 1.0; // 1.0 ≈ 2.5× the Lambertian at peak (sss=1, cosT=1, backNdl=1)
// Keep SSS on the first two Pass B hits. Pass A's primary/interface prefix is outside this local depth.
public static const int   MAX_SSS_INDIRECT_DEPTH = 1;
public float hg(float cosT, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * PI * pow(max(0.0, 1.0 + g2 - 2.0 * g * cosT), 1.5));
}

// NVIDIA RR guide-buffer helper: integrated/view-dependent specular reflectivity, not raw F0.
// alpha is GGX alpha (== the linear roughness materials store), NoV is the first-hit view cosine.
public float3 rrSpecularAlbedo(float3 specularColor, float alpha, float NoV) {
    NoV = abs(NoV);
    float NoV2 = NoV * NoV;
    float alpha2 = alpha * alpha;
    float4 X = float4(1.0, NoV, NoV2, NoV * NoV2);
    float4 Y = float4(1.0, alpha, alpha2, alpha * alpha2);

    float2 m1 = float2(
            dot(float2(0.99044, -1.28514), X.xy),
            dot(float2(1.29678, -0.755907), X.xy));
    float3 Xxyw = float3(X.x, X.y, X.w);
    float3 m2 = float3(
            dot(float3(1.0, 2.92338, 59.4188), Xxyw),
            dot(float3(20.3225, -27.0302, 222.592), Xxyw),
            dot(float3(121.563, 626.13, 316.627), Xxyw));
    float bias = dot(m1, Y.xy) / max(dot(m2, float3(Y.x, Y.y, Y.w)), 1.0e-7);

    float2 m3 = float2(
            dot(float2(0.0365463, 3.32707), X.xy),
            dot(float2(9.0632, -9.04756), X.xy));
    float3 Xxzw = float3(X.x, X.z, X.w);
    float3 m4 = float3(
            dot(float3(1.0, 3.59685, -1.36772), Xxzw),
            dot(float3(9.04401, -16.3174, 9.22949), Xxzw),
            dot(float3(5.56589, 19.7886, -20.2123), Xxzw));
    float scale = dot(m3, Y.xy) / max(dot(m4, float3(Y.x, Y.y, Y.w)), 1.0e-7);

    bias *= clamp(specularColor.g * 50.0, 0.0, 1.0);
    return specularColor * max(0.0, scale) + float3(max(0.0, bias), max(0.0, bias), max(0.0, bias));
}

// Exact (unpolarized) Fresnel reflectance for a dielectric interface. cosI is the incidence cosine on
// the incoming side; etaI/etaT are the IORs the ray travels from / into. Returns 1.0 on total internal
// reflection (sin of the transmitted angle >= 1), which matches refract() returning the zero vector —
// so reflect/refract selection and TIR stay consistent.
public float fresnelDielectric(float cosI, float etaI, float etaT) {
    float sinT2 = (etaI * etaI) / (etaT * etaT) * max(0.0, 1.0 - cosI * cosI);
    if (sinT2 >= 1.0) {
        return 1.0;
    }
    float cosT = sqrt(1.0 - sinT2);
    float rs = (etaI * cosI - etaT * cosT) / (etaI * cosI + etaT * cosT);
    float rp = (etaI * cosT - etaT * cosI) / (etaI * cosT + etaT * cosI);
    return 0.5 * (rs * rs + rp * rp);
}

// PCG remains available for non-path-sampling scheduling. Stochastic estimator decisions use PathSampler.
public uint pcg(inout uint s) {
    s = s * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (w >> 22u) ^ w;
}
public float rndf(inout uint seed) {
    return float(pcg(seed) >> 8u) * (1.0 / 16777216.0);
}

public static const uint PATH_DIM_INTERFACE = 0u;
public static const uint PATH_DIM_LOBE = 1u;
public static const uint PATH_DIM_RR = 2u;
public static const uint PATH_DIM_GGX_U = 0u;
public static const uint PATH_DIM_GGX_V = 1u;
public static const uint PATH_DIM_DIFFUSE_U = 0u;
public static const uint PATH_DIM_DIFFUSE_V = 1u;
public static const uint PATH_DIM_RIS_GRID_X = 0u;
public static const uint PATH_DIM_RIS_GRID_Y = 1u;
public static const uint PATH_DIM_RIS_GRID_Z = 2u;
public static const uint PATH_DIM_RIS_SELECT = 0u;
public static const uint PATH_DIM_RIS_REPLACE = 2u;
public static const uint PATH_DIM_RIS_LIGHT_U = 0u;
public static const uint PATH_DIM_RIS_LIGHT_V = 1u;
public static const uint PATH_DIM_SHARC_UPDATE = 0u;
public static const uint PATH_DIM_CELESTIAL_U = 0u;

public static const uint PATH_GROUP_TRANSPORT_CORE = 0u;
public static const uint PATH_GROUP_TRANSPORT_SPECULAR = 1u;
public static const uint PATH_GROUP_TRANSPORT_DIFFUSE = 2u;
public static const uint PATH_GROUP_RIS_BASE = 3u;
public static const uint PATH_GROUP_RIS_CANDIDATE_FIRST = 4u;
public static const uint PATH_GROUP_SHARC = 68u;
public static const uint PATH_GROUP_CELESTIAL = 69u;
public static const uint PATH_GROUP_COUNT = 70u;
public static const uint PATH_BRANCH_COUNT = 2u;
public static const uint PATH_BOUNCE_COUNT = 9u;
public static const uint PATH_SOBOL_DIMENSIONS = 4u;
public static const uint PATH_SOBOL_FIRST_TABLE_DIMENSION = 1u;
public static const uint PATH_SOBOL_TABLE_DIMENSION_COUNT =
        PATH_SOBOL_DIMENSIONS - PATH_SOBOL_FIRST_TABLE_DIMENSION;
public static const uint PATH_SOBOL_WORDS_PER_DIMENSION = 128u;
public static const uint PATH_DIRECTION_TABLE_OFFSET = 0u;
public static const uint PATH_ROOT_TABLE_OFFSET = PATH_DIRECTION_TABLE_OFFSET
        + PATH_SOBOL_TABLE_DIMENSION_COUNT * PATH_SOBOL_WORDS_PER_DIMENSION;
public static const uint PATH_ROOTS_PER_GROUP = 9u;
public static const uint PATH_ROOT_INDEX_SHUFFLE = 0u;
public static const uint PATH_ROOT_COORDINATE_SCRAMBLE_BASE = 1u;
public static const uint PATH_ROOT_DIGITAL_SHIFT_BASE = 5u;
public static const uint PATH_EPOCH_KEY = 0x9e3779b9u;
public static const uint PATH_STATE_BOUNCE_MASK = 0x1eu;
public static const uint PATH_STATE_GROUP_MASK = 0xfe0u;

public struct PathSampler {
    public uint globalSample;
    public uint pixelEpochKey;
    public uint shuffledIndex;
    // branch, bounce, and group occupy bits 0, 1..4, and 5..11 of the resource-bounded state.
    public uint pathState;
}

public uint pathHash(uint value) {
    uint x = value;
    x ^= x >> 16u;
    x *= 0x21f0aaadu;
    x ^= x >> 15u;
    x *= 0xf35a2d97u;
    x ^= x >> 15u;
    return x;
}

public uint pathReverseBits(uint value) {
    // The pinned Slang toolchain lowers this intrinsic to SPIR-V OpBitReverse.
    return reversebits(value);
}

public uint pathNestedUniformPermutation(uint value, uint seed) {
    uint x = pathReverseBits(value);
    x ^= x * 0x3d20adeau;
    x += seed;
    x *= (seed >> 16u) | 1u;
    x ^= x * 0x05526c56u;
    x ^= x * 0x53a22864u;
    return pathReverseBits(x);
}

public uint pathRootBase(in PathSampler sampler) {
    uint state = sampler.pathState;
    return PATH_ROOT_TABLE_OFFSET
            + (((state & 1u) * PATH_BOUNCE_COUNT + ((state >> 1u) & 15u)) * PATH_GROUP_COUNT
                    + (state >> 5u)) * PATH_ROOTS_PER_GROUP;
}

public void rekeyPathSampler(inout PathSampler sampler) {
    ConstPtr<uint> data = ConstPtr<uint>(worldPush.pathSampleAddr);
    uint rootBase = pathRootBase(sampler);
    uint indexSeed = pathHash(data[rootBase + PATH_ROOT_INDEX_SHUFFLE]
            ^ sampler.pixelEpochKey);
    sampler.shuffledIndex = pathNestedUniformPermutation(sampler.globalSample, indexSeed);
}

public PathSampler makePathSampler(uint2 pixel, uint localSample, uint pathBranch) {
    uint pixelCode = pixel.x | (pixel.y << 16u);
    PathSampler sampler;
    sampler.globalSample = worldPush.pathSampleBase + localSample;
    sampler.pixelEpochKey = pixelCode ^ worldPush.pathSampleEpoch * PATH_EPOCH_KEY;
    sampler.shuffledIndex = sampler.globalSample;
    sampler.pathState = pathBranch | (PATH_GROUP_TRANSPORT_CORE << 5u);
    return sampler;
}

public void beginPathBounce(inout PathSampler sampler, uint bounce) {
    sampler.pathState = (sampler.pathState & ~PATH_STATE_BOUNCE_MASK) | (bounce << 1u);
    rekeyPathSampler(sampler);
}

public PathSampler pathSamplerWithGroup(in PathSampler parent, uint group) {
    PathSampler sampler = parent;
    sampler.pathState = (sampler.pathState & ~PATH_STATE_GROUP_MASK) | (group << 5u);
    rekeyPathSampler(sampler);
    return sampler;
}

public uint pathSobolBitsFromGray(ConstPtr<uint> data, uint gray, uint dimension) {
    if (dimension == 0u) return pathReverseBits(gray);
    uint tableDimension = dimension - PATH_SOBOL_FIRST_TABLE_DIMENSION;
    uint base = PATH_DIRECTION_TABLE_OFFSET + tableDimension * PATH_SOBOL_WORDS_PER_DIMENSION;
    return data[base + ((gray >> 0u) & 15u)]
            ^ data[base + 16u + ((gray >> 4u) & 15u)]
            ^ data[base + 32u + ((gray >> 8u) & 15u)]
            ^ data[base + 48u + ((gray >> 12u) & 15u)]
            ^ data[base + 64u + ((gray >> 16u) & 15u)]
            ^ data[base + 80u + ((gray >> 20u) & 15u)]
            ^ data[base + 96u + ((gray >> 24u) & 15u)]
            ^ data[base + 112u + ((gray >> 28u) & 15u)];
}

public float pathSampleValue(ConstPtr<uint> data, in PathSampler sampler, uint rootBase, uint gray,
                             uint dimension) {
    uint bits = pathSobolBitsFromGray(data, gray, dimension);
    uint scrambleSeed = pathHash(
            data[rootBase + PATH_ROOT_COORDINATE_SCRAMBLE_BASE + dimension]
                    ^ sampler.pixelEpochKey);
    bits = pathNestedUniformPermutation(bits, scrambleSeed);
    uint digitalShift = pathHash(data[rootBase + PATH_ROOT_DIGITAL_SHIFT_BASE + dimension]
            ^ sampler.pixelEpochKey);
    bits ^= digitalShift;
    return float(bits >> 8u) * (1.0 / 16777216.0);
}

public float pathSample(in PathSampler sampler, uint dimension) {
    ConstPtr<uint> data = ConstPtr<uint>(worldPush.pathSampleAddr);
    uint sampleIndex = sampler.shuffledIndex;
    return pathSampleValue(data, sampler, pathRootBase(sampler),
            sampleIndex ^ (sampleIndex >> 1u), dimension);
}

public float2 pathSample2(in PathSampler sampler, uint dimensionBase) {
    ConstPtr<uint> data = ConstPtr<uint>(worldPush.pathSampleAddr);
    uint sampleIndex = sampler.shuffledIndex;
    uint gray = sampleIndex ^ (sampleIndex >> 1u);
    uint rootBase = pathRootBase(sampler);
    return float2(pathSampleValue(data, sampler, rootBase, gray, dimensionBase),
            pathSampleValue(data, sampler, rootBase, gray, dimensionBase + 1u));
}

public float3 pathSample3(in PathSampler sampler, uint dimensionBase) {
    ConstPtr<uint> data = ConstPtr<uint>(worldPush.pathSampleAddr);
    uint sampleIndex = sampler.shuffledIndex;
    uint gray = sampleIndex ^ (sampleIndex >> 1u);
    uint rootBase = pathRootBase(sampler);
    return float3(pathSampleValue(data, sampler, rootBase, gray, dimensionBase),
            pathSampleValue(data, sampler, rootBase, gray, dimensionBase + 1u),
            pathSampleValue(data, sampler, rootBase, gray, dimensionBase + 2u));
}

public float3 primaryRayDir(float2 ndc) {
    float4 nearH = mul(worldPush.invViewProj, float4(ndc.x, ndc.y, 1.0, 1.0));
    float4 farH = mul(worldPush.invViewProj, float4(ndc.x, ndc.y, 0.0, 1.0));
    float3 nearP = nearH.xyz / nearH.w;
    float3 farP = farH.xyz / farH.w;
    return normalize(farP - nearP);
}

public float primaryRayConeSpread(float2 ndc, float2 size, float3 dir) {
    float2 onePixelNdc = 2.0 / size;
    float3 dx = primaryRayDir(ndc + float2(onePixelNdc.x, 0.0));
    float3 dy = primaryRayDir(ndc + float2(0.0, onePixelNdc.y));
    return max(max(length(cross(dir, dx)), length(cross(dir, dy))), RAY_CONE_MIN_SPREAD);
}

// Cosine-weighted hemisphere sample about n (Malley's method). For a Lambertian BRDF this is the
// importance-sampling match: BRDF*cos/pdf = (albedo/PI)*cos / (cos/PI) = albedo, so the continuation
// throughput is just *= albedo with no PI/cos bookkeeping left over.
public float3 cosineDir(float3 n, in PathSampler sampler, uint dimensionBase) {
    float2 samples = pathSample2(sampler, dimensionBase);
    float u1 = samples.x;
    float u2 = samples.y;
    float r = sqrt(u1);
    float phi = 6.2831853 * u2;
    float3 t = normalize(abs(n.x) > 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0));
    float3 b = normalize(cross(n, t));
    t = cross(b, n);
    float3 local = float3(r * cos(phi), r * sin(phi), sqrt(max(0.0, 1.0 - u1)));
    return normalize(local.x * t + local.y * b + local.z * n);
}

public float3 sampleSquare(float3 axis, float halfAngle, in PathSampler sampler, uint dimensionBase) {
    float3 right;
    float3 up;
    celestialSquareFrame(axis, right, up);
    float t = tan(halfAngle);
    float2 samples = pathSample2(sampler, dimensionBase);
    float u = (samples.x * 2.0 - 1.0) * t;
    float v = (samples.y * 2.0 - 1.0) * t;
    return normalize(axis + u * right + v * up);
}

// Sample a GGX visible-normal (VNDF, Heitz 2018) about geometric normal n for view ve, returning the
// sampled microfacet normal (half-vector) in world space. Paired with the separable Smith term, the
// importance-sampling weight reduces to F * G1(NdotL) (applied by the caller).
public float3 sampleGGXVNDF(float3 n, float3 ve, float alpha, in PathSampler sampler, uint dimensionBase) {
    float3 t = normalize(abs(n.x) > 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0));
    float3 b = normalize(cross(n, t));
    t = cross(b, n);
    float3 Ve = float3(dot(ve, t), dot(ve, b), dot(ve, n)); // view in tangent space (z = n)
    float a = alpha;
    float3 Vh = normalize(float3(a * Ve.x, a * Ve.y, Ve.z));
    float lensq = Vh.x * Vh.x + Vh.y * Vh.y;
    float3 T1 = lensq > 0.0 ? float3(-Vh.y, Vh.x, 0.0) * rsqrt(lensq) : float3(1.0, 0.0, 0.0);
    float3 T2 = cross(Vh, T1);
    float2 samples = pathSample2(sampler, dimensionBase);
    float u1 = samples.x;
    float u2 = samples.y;
    float r = sqrt(u1);
    float phi = 6.2831853 * u2;
    float p1 = r * cos(phi);
    float p2 = r * sin(phi);
    float ss = 0.5 * (1.0 + Vh.z);
    p2 = (1.0 - ss) * sqrt(1.0 - p1 * p1) + ss * p2;
    float3 Nh = p1 * T1 + p2 * T2 + sqrt(max(0.0, 1.0 - p1 * p1 - p2 * p2)) * Vh;
    float3 Ne = normalize(float3(a * Nh.x, a * Nh.y, max(0.0, Nh.z))); // microfacet normal, tangent space
    return normalize(Ne.x * t + Ne.y * b + Ne.z * n);                 // -> world space
}
