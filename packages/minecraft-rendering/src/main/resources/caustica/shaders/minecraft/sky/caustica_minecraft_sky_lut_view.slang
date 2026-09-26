// Each frame bakes sun and moon atmosphere slices into one sky-view LUT. Environment evaluation adds
// both slices instead of marching the atmosphere for every miss. Invocation zero publishes the same
// SkyInputs used by the bake into the binding's buffer; queue ordering makes both available to tracing.
module caustica_minecraft_sky_lut_view;

import caustica_minecraft_sky_atmosphere;
import caustica_minecraft_sky_lut_types;

[[vk::push_constant]] SkyLutPush pc;
typealias WritableSkyInputsPtr = Ptr<SkyInputs, Access.ReadWrite, AddressSpace.Device, Std430DataLayout>;

public void runSkyView(uint3 tid) {
    RWTexture2D<float4> skyViewImage =
            ResourceDescriptorHeap[NonUniformResourceIndex(pc.destinationImageIndex)];
    if (tid.x >= uint(SKY_VIEW_LUT_W) || tid.y >= uint(SKY_VIEW_LUT_TOTAL_H)) {
        return;
    }
    if (all(tid == uint3(0u))) {
        WritableSkyInputsPtr(pc.skyInputsAddress)[0] = pc.inputs;
    }
    SkyState sky = skyState(pc.inputs);

    int body = int(tid.y) / SKY_VIEW_LUT_H;
    int row = int(tid.y) - body * SKY_VIEW_LUT_H;
    bool isSun = body == SKY_VIEW_BODY_SUN;
    float3 worldLightDir = isSun ? sky.sunDir : sky.moonDir;
    float3 illuminance = float3(isSun ? sky.sunIlluminance : sky.moonIlluminance);
    float angularRadius = isSun ? sky.sunAngularRadius : sky.moonAngularRadius;

    // Sampling applies the half-texel correction; the bake must invert exactly that, so it works in the
    // same sub-UV space rather than in the parameterisation's raw [0,1].
    float2 uv = (float2(float(tid.x), float(row)) + 0.5)
              / float2(float(SKY_VIEW_LUT_W), float(SKY_VIEW_LUT_H));
    float viewZenithCos;
    float lightViewCos;
    skyViewLutParams(uv, sky.viewerRadiusKm, viewZenithCos, lightViewCos);

    float3 viewDir;
    float3 lightDir;
    skyViewLocalDirections(worldLightDir.y, viewZenithCos, lightViewCos, viewDir, lightDir);

    float3 origin = float3(0.0, sky.viewerRadiusKm, 0.0);
    // Only the beam that passes the rain keeps the phase functions' aureole around the body; the light the
    // rain diffuses scatters isotropically.
    float3 luminance = integrateScatteredLuminance(
            origin, viewDir, lightDir, illuminance, angularRadius,
            SKY_VIEW_STEPS, sky.beamTransmittance, true,
            pc.transmittanceImage, pc.multiScatterImage, pc.sampler,
            sky.groundAlbedo, true).luminance;

    // The atmosphere's ground sphere is not Minecraft terrain. Blend from the unclipped tangent sky
    // into the ground solution over horizonSoften radians of dip to avoid a hard horizon seam.
    float horizonCos = horizonZenithCos(sky.viewerRadiusKm);
    float dip = acos(clamp(viewZenithCos, -1.0, 1.0)) - acos(clamp(horizonCos, -1.0, 1.0));
    if (dip > 0.0 && sky.horizonSoften > 0.0) {
        float3 tangentDir;
        float3 tangentLight;
        skyViewLocalDirections(worldLightDir.y, horizonCos, lightViewCos, tangentDir, tangentLight);
        float3 skyward = integrateScatteredLuminance(
                origin, tangentDir, tangentLight, illuminance, angularRadius,
                SKY_VIEW_STEPS, sky.beamTransmittance, true,
                pc.transmittanceImage, pc.multiScatterImage, pc.sampler,
                sky.groundAlbedo, false).luminance;
        // Log-space interpolation spreads the brightness change evenly in exposure stops.
        float3 low = log2(max(skyward, float3(1.0e-6)));
        float3 high = log2(max(luminance, float3(1.0e-6)));
        luminance = exp2(lerp(low, high, smoothstep(0.0, sky.horizonSoften, dip)));
    }

    // Overcast greys the dome at its own luminance. The weather's darkening is already in the sun's
    // illuminance, and this table is also the scene's sky light, so the grey keeps every texel's cd/m².
    luminance = lerp(float3(dot(luminance, BT709_LUMINANCE)), luminance, sky.skySaturation);
    skyViewImage[tid.xy] = float4(max(luminance, float3(0.0)), 1.0);
}
