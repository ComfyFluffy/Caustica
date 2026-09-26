import world_common;
import stable_plane_types;
import nrd_compose_types;

[[vk::push_constant]] NrdComposePush pc;

static const uint NRD_MODE_PREPARE = 0u;
static const uint NRD_MODE_MERGE = 1u;
static const float NRD_INVALID_VIEW_Z = 65504.0;
static const float4 REBLUR_HIT_DISTANCE_PARAMETERS = float4(3.0, 0.1, 20.0, -25.0);

// Inactive records only initialize branchId and flags; do not read their remaining fields.
StablePlaneRecord loadPlane(StablePlaneStorage records, uint index) {
    StablePlaneRecord record = {};
    record.branchId = records.loadBranchId(index);
    if (record.branchId == STABLE_PLANE_INVALID_BRANCH) return record;
    return records.load(index);
}

uint recordIndex(uint2 pixel, uint2 extent, uint plane) {
    return plane * extent.x * extent.y + pixel.y * extent.x + pixel.x;
}

bool validSurface(StablePlaneRecord r) {
    return r.branchId != STABLE_PLANE_INVALID_BRANCH
            && (r.flags & STABLE_PLANE_FLAG_VALID) != 0u
            && (r.flags & STABLE_PLANE_FLAG_ENVIRONMENT) == 0u;
}

float neighborRelaxation(StablePlaneStorage records, int2 pixel, int2 extent,
        uint plane, float3 centerNormal, int2 offset) {
    uint2 neighbor = uint2(clamp(pixel + offset, int2(0), extent - 1));
    StablePlaneRecord r = loadPlane(records, recordIndex(neighbor, uint2(extent), plane));
    return r.branchId == STABLE_PLANE_INVALID_BRANCH
            ? 0.02 : 1.0 - dot(centerNormal, normalize(r.normal));
}

float disocclusionRelaxation(StablePlaneStorage records, uint2 pixel, uint2 extent,
        uint plane, StablePlaneRecord r) {
    if (((r.flags >> 8u) & 0xffu) == 0u) return 0.0;
    float3 n = normalize(r.normal);
    int2 p = int2(pixel); int2 size = int2(extent);
    float value = neighborRelaxation(records, p, size, plane, n, int2(-1, 0));
    value += neighborRelaxation(records, p, size, plane, n, int2(1, 0));
    value += neighborRelaxation(records, p, size, plane, n, int2(0, -1));
    value += neighborRelaxation(records, p, size, plane, n, int2(0, 1));
    return saturate((value - 0.00002) * 25.0);
}

float3 encodeRadiance(float3 radiance, NrdPlaneFrame frame) {
    // NRD history uses a fixed scene-linear scale. RELAX stores second moments in FP16,
    // so each input component must stay below sqrt(65504), independently of display exposure.
    float3 signal = max(radiance, float3(0.0)) * frame.radianceScale;
    float peak = max(signal.x, max(signal.y, signal.z));
    return signal * (250.0 / max(250.0, peak));
}

float3 linearToYCoCg(float3 c) {
    return float3(dot(c, float3(0.25, 0.5, 0.25)), dot(c, float3(0.5, 0.0, -0.5)),
            dot(c, float3(-0.25, 0.5, -0.25)));
}

float3 ycocgToLinear(float3 c) {
    float t = c.x - c.z;
    return float3(t + c.y, c.x + c.z, t - c.y);
}

float normalizedHitDistance(float hitDistance, float viewZ, float roughness) {
    float scale = (REBLUR_HIT_DISTANCE_PARAMETERS.x
            + abs(viewZ) * REBLUR_HIT_DISTANCE_PARAMETERS.y)
            * lerp(1.0, REBLUR_HIT_DISTANCE_PARAMETERS.z,
                    saturate(exp2(REBLUR_HIT_DISTANCE_PARAMETERS.w * roughness * roughness)));
    return saturate(hitDistance / max(scale, 1.0e-6));
}

float4 packSignal(float3 radiance, float hitDistance, float viewZ, float roughness) {
    radiance = clamp(max(radiance, float3(0.0)), float3(0.0), float3(65504.0));
    if (pc.signalEncoding == 0u) return float4(radiance, clamp(hitDistance, 0.0, 65504.0));
    return float4(linearToYCoCg(radiance), normalizedHitDistance(hitDistance, viewZ, roughness));
}

float3 unpackRadiance(float3 encoded) {
    return max(pc.signalEncoding == 0u ? encoded : ycocgToLinear(encoded), float3(0.0));
}

void prepare(uint2 pixel, uint2 extent, StablePlaneStorage records, NrdPlaneFrame frame,
        RWTexture2D<float4> diffuseSignal, RWTexture2D<float4> specularSignal,
        RWTexture2D<float4> normalRoughness, RWTexture2D<float> viewZImage,
        RWTexture2D<float4> motionImage, RWTexture2D<float> disocclusionMix) {
    StablePlaneRecord r = loadPlane(records, recordIndex(pixel, extent, pc.planeIndex));
    if (!validSurface(r)) {
        diffuseSignal[pixel] = float4(0.0); specularSignal[pixel] = float4(0.0);
        normalRoughness[pixel] = float4(0.0); viewZImage[pixel] = NRD_INVALID_VIEW_Z;
        motionImage[pixel] = float4(0.0); disocclusionMix[pixel] = 0.0;
        return;
    }

    float4 currentClip = mul(frame.currentViewProjection,
            float4(r.currentVirtualPosition - frame.cameraOffset, 1.0));
    float4 previousClip = mul(frame.previousViewProjection,
            float4(r.previousVirtualPosition - frame.cameraOffset + frame.cameraDelta, 1.0));
    float currentViewZ = max(abs(currentClip.w), 1.0e-4);
    float3 motion = float3(0.0);
    if (currentClip.w > 0.0 && previousClip.w > 0.0) {
        motion.xy = (previousClip.xy / previousClip.w - currentClip.xy / currentClip.w)
                * 0.5 * float2(extent);
        motion.z = previousClip.w - currentClip.w;
    }

    float relaxation = disocclusionRelaxation(records, pixel, extent, pc.planeIndex, r);
    float roughness = saturate(max(0.2, r.roughness) + relaxation);
    float3 diffuse = r.noisyDiffuseRadiance / max(r.diffuseBsdfEstimate, float3(1.0e-4));
    float3 specular = r.noisySpecularRadiance / max(r.specularBsdfEstimate, float3(1.0e-4));
    diffuse = encodeRadiance(diffuse, frame);
    specular = encodeRadiance(specular, frame);
    float specHit = (r.flags & STABLE_PLANE_FLAG_DOMINANT) != 0u
            ? max(r.specularHitDistance, 0.0) : 0.0;
    diffuseSignal[pixel] = packSignal(diffuse, 0.0, currentViewZ, 1.0);
    specularSignal[pixel] = packSignal(specular, specHit, currentViewZ, r.roughness);
    normalRoughness[pixel] = float4(normalize(r.normal), roughness);
    viewZImage[pixel] = currentViewZ;
    motionImage[pixel] = float4(motion, 0.0);
    disocclusionMix[pixel] = relaxation;
}

void merge(uint2 pixel, uint2 extent, StablePlaneStorage records, NrdPlaneFrame frame,
        RWTexture2D<float4> outputImage, RWTexture2D<float4> denoisedDiffuse,
        RWTexture2D<float4> denoisedSpecular, RWTexture2D<float4> stableRadiance) {
    float3 accumulated = pc.planeIndex == STABLE_PLANE_COUNT - 1u
            ? max(stableRadiance[pixel].rgb, float3(0.0)) * frame.preExposure
            : outputImage[pixel].rgb;
    StablePlaneRecord r = loadPlane(records, recordIndex(pixel, extent, pc.planeIndex));
    if (validSurface(r)) {
        // Remodulation uses the same floor as prepare, including radiance from spatial scattering.
        float3 diffuse = unpackRadiance(denoisedDiffuse[pixel].rgb) * max(r.diffuseBsdfEstimate, float3(1.0e-4));
        float3 specular = unpackRadiance(denoisedSpecular[pixel].rgb) * max(r.specularBsdfEstimate, float3(1.0e-4));
        accumulated += max(diffuse + specular, float3(0.0))
                * (frame.preExposure / frame.radianceScale);
    } else if (r.branchId != STABLE_PLANE_INVALID_BRANCH
            && (r.flags & STABLE_PLANE_FLAG_ENVIRONMENT) != 0u) {
        // Reflected and transmitted environment contributions have no surface to denoise.
        // Their plane records already contain scene-linear radiance with the BSDF applied.
        accumulated += max(r.noisyDiffuseRadiance + r.noisySpecularRadiance, float3(0.0))
                * frame.preExposure;
    }
    outputImage[pixel] = float4(clamp(accumulated, float3(0.0), float3(65504.0)), 1.0);
}

[shader("compute")]
[numthreads(16, 16, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    [format("rgba16f")] RWTexture2D<float4> outputImage = ResourceDescriptorHeap[NonUniformResourceIndex(pc.outputImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> diffuseSignal = ResourceDescriptorHeap[NonUniformResourceIndex(pc.diffuseSignalImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> specularSignal = ResourceDescriptorHeap[NonUniformResourceIndex(pc.specularSignalImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> normalRoughness = ResourceDescriptorHeap[NonUniformResourceIndex(pc.normalRoughnessImageIndex)];
    [format("r32f")] RWTexture2D<float> viewZImage = ResourceDescriptorHeap[NonUniformResourceIndex(pc.viewZImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> motionImage = ResourceDescriptorHeap[NonUniformResourceIndex(pc.motionImageIndex)];
    [format("r16f")] RWTexture2D<float> disocclusionMix = ResourceDescriptorHeap[NonUniformResourceIndex(pc.disocclusionMixImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> denoisedDiffuse = ResourceDescriptorHeap[NonUniformResourceIndex(pc.denoisedDiffuseImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> denoisedSpecular = ResourceDescriptorHeap[NonUniformResourceIndex(pc.denoisedSpecularImageIndex)];
    [format("rgba16f")] RWTexture2D<float4> stableRadiance = ResourceDescriptorHeap[NonUniformResourceIndex(pc.stableRadianceImageIndex)];

    NrdPlaneFrame frame = ((DevicePtr<NrdPlaneFrame>)pc.frameAddress)[0];
    StablePlaneStorage records = stablePlaneStorage(pc.stablePlaneAddress, STABLE_PLANE_COUNT * frame.width * frame.height,
            frame.preExposure);
    uint2 extent = uint2(frame.width, frame.height); uint2 pixel = tid.xy;
    if (any(pixel >= extent)) return;
    if (pc.mode == NRD_MODE_PREPARE) prepare(pixel, extent, records, frame, diffuseSignal,
            specularSignal, normalRoughness, viewZImage, motionImage, disocclusionMix);
    else if (pc.mode == NRD_MODE_MERGE) merge(pixel, extent, records, frame, outputImage,
            denoisedDiffuse, denoisedSpecular, stableRadiance);
}
