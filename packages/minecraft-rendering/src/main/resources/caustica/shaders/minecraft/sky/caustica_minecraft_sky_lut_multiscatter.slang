// Multiple-scattering LUT bake (Hillaire 2020, §5.2). For each (altitude, cos sun zenith) texel, fire 64
// directions over the sphere, raymarch each one for SECOND-order scattered luminance with an isotropic
// phase, and store the geometric series that closes the remaining orders:
//
//     psi_ms = L_2nd / (1 - f_ms)
//
// f_ms is the fraction scattered again before escape. The geometric series includes higher orders
// needed at twilight. Ground albedo is the only changing input; rebuild after transmittance when it
// changes or the environment binding is replaced, and reuse the result between frames.
module caustica_minecraft_sky_lut_multiscatter;

import caustica_minecraft_sky_atmosphere;
import caustica_minecraft_sky_lut_types;

[[vk::push_constant]] SkyLutPush pc;

static const int SQRT_DIRS = 8; // SQRT_DIRS^2 == MULTISCATTER_DIRS

public void runSkyMultiscatter(uint3 tid) {
    RWTexture2D<float4> multiScatterImage =
            ResourceDescriptorHeap[NonUniformResourceIndex(pc.destinationImageIndex)];
    if (tid.x >= uint(MULTISCATTER_LUT_W) || tid.y >= uint(MULTISCATTER_LUT_H)) {
        return;
    }
    float groundAlbedo = pc.inputs.skyLook3.x;

    float2 uv = float2(float(tid.x), float(tid.y))
              / float2(float(MULTISCATTER_LUT_W - 1), float(MULTISCATTER_LUT_H - 1));
    float radiusKm;
    float cosSunZenith;
    multiScatterLutParams(uv, radiusKm, cosSunZenith);

    float3 origin = float3(0.0, radiusKm, 0.0);
    float3 sunDir = float3(sqrt(max(1.0 - cosSunZenith * cosSunZenith, 0.0)), cosSunZenith, 0.0);

    float3 luminanceSum = float3(0.0);
    float3 multiScatSum = float3(0.0);
    for (int d = 0; d < MULTISCATTER_DIRS; d++) {
        // Uniform sphere directions on a regular 8x8 grid of the (azimuth, cos polar) square. A fixed
        // pattern, not a random one: the LUT is baked once and any stochastic noise in it would be frozen
        // into every frame that samples it.
        float randA = (float(d / SQRT_DIRS) + 0.5) / float(SQRT_DIRS);
        float randB = (float(d % SQRT_DIRS) + 0.5) / float(SQRT_DIRS);
        float theta = 2.0 * SKY_PI * randA;
        float phi = acos(clamp(1.0 - 2.0 * randB, -1.0, 1.0));
        float sinPhi = sin(phi);
        float3 dir = float3(cos(theta) * sinPhi, cos(phi), sin(theta) * sinPhi);

        // Unit illuminance, isotropic phase, no multiple-scattering lookup: this IS the term that lookup
        // will later provide, so reading it here would be circular.
        ScatteringResult r = integrateScatteredLuminance(
                origin, dir, sunDir, float3(1.0), 0.0,
                MULTISCATTER_STEPS, 0.0, false,
                pc.transmittanceImage, pc.multiScatterImage, pc.sampler, groundAlbedo, true);
        luminanceSum += r.luminance;
        multiScatSum += r.multiScatAs1;
    }
    float inverse = 1.0 / float(MULTISCATTER_DIRS);
    float3 secondOrder = luminanceSum * inverse;
    float3 scatteredFraction = multiScatSum * inverse;
    // Sum of the infinite series of further scattering events. The fraction is physically below 1 (some
    // light is always absorbed or escapes), but clamp anyway: an fp32 round-trip landing on exactly 1
    // would divide by zero and poison the whole LUT with infinities.
    float3 series = 1.0 / max(1.0 - scatteredFraction, float3(1.0e-4));
    multiScatterImage[tid.xy] = float4(secondOrder * series, 1.0);
}
