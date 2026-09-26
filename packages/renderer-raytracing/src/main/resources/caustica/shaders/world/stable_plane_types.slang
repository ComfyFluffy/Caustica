module stable_plane_types;
import world_common;

public static const uint STABLE_PLANE_COUNT = 3u;
public static const uint STABLE_PLANE_INVALID_BRANCH = 0xffffffffu;
public static const uint STABLE_PLANE_FLAG_VALID = 1u;
public static const uint STABLE_PLANE_FLAG_ENVIRONMENT = 2u;
public static const uint STABLE_PLANE_FLAG_DOMINANT = 4u;
public static const uint STABLE_PLANE_FLAG_TRANSMISSION = 8u;
// Flags bits 8 through 15 store the number of delta segments before the endpoint.
public static const uint STABLE_PLANE_DEPTH_SHIFT = 8u;
public static const uint STABLE_PLANE_DEPTH_MASK = 255u;
// A branch id is 1 for the camera path; each delta event shifts in two bits naming its lobe. The leading
// bit and every reflection bit therefore occupy even bit positions.
public static const uint STABLE_PLANE_BRANCH_REFLECTION = 1u;
public static const uint STABLE_PLANE_BRANCH_TRANSMISSION = 2u;

public bool stablePlaneBranchReflects(uint branchId) {
    return countbits(branchId & 0x55555555u) > 1u;
}

// Logical world-space guide values; physical scratch storage is scalar SoA.
public struct StablePlaneRecord {
    public float3 currentVirtualPosition;
    public float roughness;
    public float3 previousVirtualPosition;
    public float throughputLuminance;
    public float3 normal;
    public float diffuseHitDistance;
    public float3 diffuseBsdfEstimate;
    public float specularHitDistance;
    public float3 specularBsdfEstimate;
    public float3 noisyDiffuseRadiance;
    public uint flags;
    public float3 noisySpecularRadiance;
    public uint branchId;
};

// Physical record: field i is word i of StablePlaneStorage. Each half pair keeps its first value in the low
// 16 bits and saturates at +-65504. Every half value reaches an fp16 guide, signal or trace-output image, or is
// an NRD demodulation factor that prepare and merge read identically.
public struct PackedStablePlane {
    public float3 currentVirtualPosition;
    // Plane dominance compares this value, so it stays exact.
    public float throughputLuminance;
    public uint branchId;
    public uint flags;
    // Octahedral snorm16 x, y; axis-aligned normals decode exactly.
    public uint normal;
    // Halves of previousVirtualPosition - currentVirtualPosition, then roughness.
    public uint motionXY;
    public uint motionZRoughness;
    // Halves of the diffuse then the specular BSDF estimate.
    public uint diffuseBsdfEstimateXY;
    public uint diffuseBsdfEstimateZSpecularBsdfEstimateX;
    public uint specularBsdfEstimateYZ;
    // Halves of radiance times the frame pre-exposure, each followed by its hit distance.
    public uint noisyDiffuseRadianceXY;
    public uint noisyDiffuseRadianceZDiffuseHitDistance;
    public uint noisySpecularRadianceXY;
    public uint noisySpecularRadianceZSpecularHitDistance;
};

static const float STABLE_PLANE_HALF_MAX = 65504.0;

uint stablePlanePackHalves(float low, float high) {
    float2 value = clamp(float2(low, high), float2(-STABLE_PLANE_HALF_MAX), float2(STABLE_PLANE_HALF_MAX));
    return f32tof16(value.x) | (f32tof16(value.y) << 16u);
}

float2 stablePlaneUnpackHalves(uint word) {
    return float2(f16tof32(word), f16tof32(word >> 16u));
}

uint stablePlanePackNormal(float3 normal) {
    float2 octahedron = normal.xy / (abs(normal.x) + abs(normal.y) + abs(normal.z));
    if (normal.z < 0.0) {
        octahedron = (1.0 - abs(octahedron.yx)) * select(octahedron >= 0.0, float2(1.0), float2(-1.0));
    }
    int2 value = int2(round(octahedron * 32767.0));
    return (uint(value.x) & 0xffffu) | (uint(value.y) << 16u);
}

float3 stablePlaneUnpackNormal(uint word) {
    float2 octahedron = float2(float(int(word << 16u) >> 16), float(int(word) >> 16)) / 32767.0;
    float3 normal = float3(octahedron, 1.0 - abs(octahedron.x) - abs(octahedron.y));
    float fold = saturate(-normal.z);
    normal.xy += select(normal.xy >= 0.0, float2(-fold), float2(fold));
    return normalize(normal);
}

// Each PackedStablePlane word owns one contiguous stream of all logical plane/pixel records.
public struct StablePlaneStorage {
    public uint64_t address;
    public uint recordCount;
    // Radiance words hold radiance times this frame pre-exposure, like the fp16 trace output they sum into.
    public float preExposure;
    uint loadWord(uint index, uint word) {
        return ((ConstPtr<uint>)address)[uint64_t(word) * uint64_t(recordCount) + uint64_t(index)];
    }
    void storeWord(uint index, uint word, uint value) {
        ((DevicePtr<uint>)address)[uint64_t(word) * uint64_t(recordCount) + uint64_t(index)] = value;
    }
    float3 loadFloat3(uint index, uint word) {
        return float3(asfloat(loadWord(index, word)), asfloat(loadWord(index, word + 1u)),
                asfloat(loadWord(index, word + 2u)));
    }
    void storeFloat3(uint index, uint word, float3 value) {
        storeWord(index, word, asuint(value.x));
        storeWord(index, word + 1u, asuint(value.y));
        storeWord(index, word + 2u, asuint(value.z));
    }
    float2 loadHalves(uint index, uint word) {
        return stablePlaneUnpackHalves(loadWord(index, word));
    }
    void storeHalves(uint index, uint word, float low, float high) {
        storeWord(index, word, stablePlanePackHalves(low, high));
    }
    // Fill stores radiance and hit distance separately, so each keeps the other half of their shared word.
    void storeLowHalf(uint index, uint word, float value) {
        storeWord(index, word, (loadWord(index, word) & 0xffff0000u) | stablePlanePackHalves(value, 0.0));
    }
    void storeHighHalf(uint index, uint word, float value) {
        storeWord(index, word, (loadWord(index, word) & 0xffffu) | stablePlanePackHalves(0.0, value));
    }
    public uint loadBranchId(uint index) { return loadWord(index, 4u); }
    public void storeBranchId(uint index, uint value) {
        storeWord(index, 4u, value);
    }
    public uint loadFlags(uint index) { return loadWord(index, 5u); }
    public void storeFlags(uint index, uint value) {
        storeWord(index, 5u, value);
    }
    public float3 loadNoisyDiffuseRadiance(uint index) {
        return float3(loadHalves(index, 12u), loadHalves(index, 13u).x) / preExposure;
    }
    public void storeNoisyDiffuseRadiance(uint index, float3 value) {
        float3 stored = value * preExposure;
        storeHalves(index, 12u, stored.x, stored.y);
        storeLowHalf(index, 13u, stored.z);
    }
    public void storeDiffuseHitDistance(uint index, float value) {
        storeHighHalf(index, 13u, value);
    }
    public void storeNoisySpecularRadiance(uint index, float3 value) {
        float3 stored = value * preExposure;
        storeHalves(index, 14u, stored.x, stored.y);
        storeLowHalf(index, 15u, stored.z);
    }
    public void storeSpecularHitDistance(uint index, float value) {
        storeHighHalf(index, 15u, value);
    }
    public StablePlaneRecord load(uint index) {
        StablePlaneRecord value = {};
        value.currentVirtualPosition = loadFloat3(index, 0u);
        value.throughputLuminance = asfloat(loadWord(index, 3u));
        value.branchId = loadWord(index, 4u);
        value.flags = loadWord(index, 5u);
        value.normal = stablePlaneUnpackNormal(loadWord(index, 6u));
        float2 motionXY = loadHalves(index, 7u);
        float2 motionZRoughness = loadHalves(index, 8u);
        value.previousVirtualPosition = value.currentVirtualPosition + float3(motionXY, motionZRoughness.x);
        value.roughness = motionZRoughness.y;
        float2 diffuseEstimateXY = loadHalves(index, 9u);
        float2 estimateZX = loadHalves(index, 10u);
        value.diffuseBsdfEstimate = float3(diffuseEstimateXY, estimateZX.x);
        value.specularBsdfEstimate = float3(estimateZX.y, loadHalves(index, 11u));
        float2 diffuseZDistance = loadHalves(index, 13u);
        float2 specularZDistance = loadHalves(index, 15u);
        value.noisyDiffuseRadiance = float3(loadHalves(index, 12u), diffuseZDistance.x) / preExposure;
        value.diffuseHitDistance = diffuseZDistance.y;
        value.noisySpecularRadiance = float3(loadHalves(index, 14u), specularZDistance.x) / preExposure;
        value.specularHitDistance = specularZDistance.y;
        return value;
    }
    public void store(uint index, StablePlaneRecord value) {
        float3 motion = value.previousVirtualPosition - value.currentVirtualPosition;
        float3 diffuse = value.noisyDiffuseRadiance * preExposure;
        float3 specular = value.noisySpecularRadiance * preExposure;
        storeFloat3(index, 0u, value.currentVirtualPosition);
        storeWord(index, 3u, asuint(value.throughputLuminance));
        storeWord(index, 4u, value.branchId);
        storeWord(index, 5u, value.flags);
        storeWord(index, 6u, stablePlanePackNormal(value.normal));
        storeHalves(index, 7u, motion.x, motion.y);
        storeHalves(index, 8u, motion.z, value.roughness);
        storeHalves(index, 9u, value.diffuseBsdfEstimate.x, value.diffuseBsdfEstimate.y);
        storeHalves(index, 10u, value.diffuseBsdfEstimate.z, value.specularBsdfEstimate.x);
        storeHalves(index, 11u, value.specularBsdfEstimate.y, value.specularBsdfEstimate.z);
        storeHalves(index, 12u, diffuse.x, diffuse.y);
        storeHalves(index, 13u, diffuse.z, value.diffuseHitDistance);
        storeHalves(index, 14u, specular.x, specular.y);
        storeHalves(index, 15u, specular.z, value.specularHitDistance);
    }
    // Build parks a queued delta branch in full-precision words 0 through 14 of its future record until it
    // stores that record.
    public void storeQueuedBranch(uint index, float3x3 imageTransform, float3 motion, float unfoldedLength,
            uint identity, bool transmitted) {
        storeFloat3(index, 0u, imageTransform[0]);
        storeFloat3(index, 3u, imageTransform[1]);
        storeFloat3(index, 6u, imageTransform[2]);
        storeFloat3(index, 9u, motion);
        storeWord(index, 12u, asuint(unfoldedLength));
        storeWord(index, 13u, identity);
        storeWord(index, 14u, transmitted ? 1u : 0u);
    }
    public void loadQueuedBranch(uint index, out float3x3 imageTransform, out float3 motion,
            out float unfoldedLength, out uint identity, out bool transmitted) {
        imageTransform = float3x3(loadFloat3(index, 0u), loadFloat3(index, 3u), loadFloat3(index, 6u));
        motion = loadFloat3(index, 9u);
        unfoldedLength = asfloat(loadWord(index, 12u));
        identity = loadWord(index, 13u);
        transmitted = loadWord(index, 14u) != 0u;
    }
};
public StablePlaneStorage stablePlaneStorage(uint64_t address, uint recordCount, float preExposure) {
    StablePlaneStorage storage;
    storage.address = address;
    storage.recordCount = recordCount;
    storage.preExposure = preExposure;
    return storage;
}
