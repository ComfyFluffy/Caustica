// NVIDIA SHaRC 1.8 adapter: the update and query policies of the stable-plane fill and the cache
// resolve. The SDK headers are an external input found through SharcSdk; this module compiles only
// when they are on the Slang search path.
//
// A cache entry holds the light its surface reflects toward the previous path vertex, demodulated by
// the surface's albedo: direct lighting plus every continuation. The surface's own emission is not
// cached; the fill adds it live with its MIS weight before it queries.
module sharc_bridge;

import caustica_color;
import world_common;
import surface_bsdf;
import sharc_types;
import sharc_policy;

// SHARC_UPDATE only adds the update state and bodies, and no SDK code depends on SHARC_QUERY, so
// one translation unit serves the update policy, the query policy and the resolve.
#define SHARC_UPDATE 1
#define SHARC_ENABLE_GLSL 0
#define HASH_GRID_COMPACT 0
#define HASH_GRID_ENABLE_64_BIT_ATOMICS 1
#define HASH_GRID_USE_NORMALS 1
#define SHARC_ENABLE_RESPONSIVE_LIGHTING 0
#define SHARC_ENABLE_SH_ENCODING 1
#define SHARC_MATERIAL_DEMODULATION 1
#define SHARC_SEPARATE_EMISSIVE 1
// With resampling the SDK keeps SHARC_PROPAGATION_DEPTH at its default of two recorded vertices.
#define SHARC_ENABLE_CACHE_RESAMPLING 1
// An entry answers queries and resampling only after more than three samples.
#define SHARC_SAMPLE_NUM_THRESHOLD 3
typealias SharcPtr<T> = Ptr<T, Access.ReadWrite, AddressSpace.Device, Std430DataLayout>;
#define RW_STRUCTURED_BUFFER(name, type) SharcPtr<type> name
#define BUFFER_AT_OFFSET(name, offset) name[offset]
#pragma warning(push)
// SharcGetCachedRadiance returns false without writing its out parameter.
#pragma warning(disable : 41018)
#include "SharcCommon.h"
#pragma warning(pop)

// An int32 accumulator holds one frame of quantized samples. Capping each sample's largest quantized
// component at 2^31 / 4096 lets 4096 capped samples reach one entry in a frame without overflow.
static const float SHARC_SAMPLE_LIMIT = 524288.0;

SharcParameters sharcParameters(SharcFrame frame) {
    SharcParameters parameters;
    parameters.hashGridParameters.cameraPosition = frame.cameraPosition;
    parameters.hashGridParameters.logarithmBase = frame.gridLogarithmBase;
    parameters.hashGridParameters.sceneScale = frame.sceneScale;
    parameters.hashGridParameters.levelBias = frame.gridLevelBias;
    parameters.hashGridData.capacity = frame.capacity;
    parameters.hashGridData.hashEntriesBuffer = SharcPtr<HashGridKey>(frame.hashEntriesAddress);
    // The resolve drains each frame's accumulation with that frame's scale, so the resolved table
    // keeps absolute radiance while the quantization follows the scene exposure.
    parameters.radianceScale = frame.radianceScale * frame.preExposure;
    parameters.accumulationBuffer = SharcPtr<SharcAccumulationData>(frame.accumulationAddress);
    parameters.resolvedBuffer = SharcPtr<SharcPackedData>(frame.resolvedAddress);
    return parameters;
}

// Diffuse, dielectric, non-mirror vertices own cache entries; everything else stays live.
bool sharcOwns(SharcVertex vertex, float roughnessThreshold) {
    SurfaceClosure surface = vertex.surface;
    return vertex.complete && !surface.deltaSurface
            && sqrt(surface.alphaRoughness) > roughnessThreshold
            && max(surface.specularF0.x, max(surface.specularF0.y, surface.specularF0.z)) <= 0.1
            && dot(surface.diffuseAlbedo, ACESCG_LUMA) > 1.0e-4
            && dot(surface.shadingNormal, vertex.outgoing) > 1.0e-4;
}

// The RTXGI SHaRC sample's demodulation: diffuse albedo plus F0 weighted by the hemispherical
// average of Schlick's Fresnel, F0 + (1 - F0) / 21.
float3 sharcDemodulation(SurfaceClosure surface) {
    float3 fresnelAverage = surface.specularF0 + (1.0 - surface.specularF0) / 21.0;
    return max(surface.diffuseAlbedo, float3(0.05))
            + max(surface.specularF0, float3(0.02)) * dot(fresnelAverage, ACESCG_LUMA);
}

SharcHitData sharcHit(SharcVertex vertex, float3 emission) {
    SharcHitData hit;
    hit.positionWorld = vertex.position;
    hit.normalWorld = vertex.faceNormal;
    hit.radianceDirectionWorld = vertex.outgoing;
    hit.radianceDirectionWeight = 0.0;
    hit.materialDemodulation = sharcDemodulation(vertex.surface);
    hit.emissive = emission;
    return hit;
}

// Directional-SH weight of light reaching the vertex along its sampled lobe: zero for diffuse
// lobes, rising toward one as a glossy lobe sharpens.
float sharcDirectionality(uint scatterEvent, SurfaceClosure surface) {
    if ((scatterEvent & EVENT_GLOSSY) == 0u) return 0.0;
    float smoothness = 1.0 - sqrt(surface.alphaRoughness);
    return smoothness * smoothness;
}

// SharcAddVoxelData with the accumulator cap and, when antiFireflyFloor is positive, a confidence
// test that scales high-weight samples far brighter than a converged entry back toward it. The
// floor is one pre-exposed unit of absolute radiance.
void sharcAdd(SharcParameters parameters, HashGridIndex index, float3 value, float3 weight,
        float3 direction, float directionWeight, uint sampleCount, float antiFireflyFloor) {
    if (index == HASH_GRID_INVALID_CACHE_INDEX) return;
    float pathWeight = max(SharcLuma(weight), 1.0);
    if (antiFireflyFloor > 0.0 && pathWeight > 2.0) {
        SharcVoxelData entry = SharcGetVoxelData(parameters.resolvedBuffer, index);
        if (entry.accumulatedSampleNum > 2.0) {
            float entryLuma = max(SharcRadianceLuma(entry.accumulatedRadiance), antiFireflyFloor);
            float sampleLuma = max(SharcLuma(value * weight), antiFireflyFloor);
            float confidence = saturate((entry.accumulatedSampleNum - 2.0) / 10.0);
            weight *= saturate(lerp(5.0, 10.0, confidence) * entryLuma / sampleLuma);
        } else {
            weight /= sqrt(pathWeight);
        }
    }
    float3 quantized = value * weight * parameters.radianceScale;
    float peak = max(quantized.x, max(quantized.y, quantized.z));
    if (peak > SHARC_SAMPLE_LIMIT) weight *= SHARC_SAMPLE_LIMIT / peak;
    SharcAddVoxelData(parameters, index, value, weight, direction, directionWeight, sampleCount);
}

void sharcPropagate(SharcParameters parameters, SharcState state, float3 radiance,
        float antiFireflyFloor) {
    for (uint i = 0u; i < state.pathLength; i++) {
        sharcAdd(parameters, state.cacheIndices[i], radiance, state.sampleWeights[i],
                state.radianceDirections[i], state.radianceDirectionWeights[i], 0u, antiFireflyFloor);
    }
}

// SharcUpdateHit with sharcAdd, and the continuation's directionality stored with the new vertex.
bool sharcUpdateHit(SharcParameters parameters, inout SharcState state, SharcHitData hit,
        float3 directLighting, float directionality, float random, float antiFireflyFloor) {
    HashGridKey key;
    HashGridIndex index = HashGridInsertEntry(parameters.hashGridData, hit.positionWorld,
            hit.normalWorld, parameters.hashGridParameters, key);
    float3 direction = SharcGetRadianceDirection(hit);
    uint resamplingDepth = uint(round(lerp(float(SHARC_RESAMPLING_DEPTH_MIN),
            float(SHARC_PROPAGATION_DEPTH), random)));
    if (resamplingDepth <= state.pathLength) {
        SharcVoxelData entry = SharcGetVoxelData(parameters.resolvedBuffer, index);
        if (entry.accumulatedSampleNum > SHARC_SAMPLE_NUM_THRESHOLD) {
            // The converged entry stands in for this vertex's reflected light and the path beyond it.
            sharcPropagate(parameters, state, SharcDecodeRadiance(entry.accumulatedRadiance, direction)
                    * hit.materialDemodulation + hit.emissive, antiFireflyFloor);
            return false;
        }
    }
    sharcAdd(parameters, index, directLighting / hit.materialDemodulation, float3(1.0), direction,
            SharcGetRadianceDirectionWeight(hit), 1u, antiFireflyFloor);
    sharcPropagate(parameters, state, directLighting + hit.emissive, antiFireflyFloor);
    for (uint i = min(state.pathLength, uint(SHARC_PROPAGATION_DEPTH - 1)); i > 0u; i--) {
        state.cacheIndices[i] = state.cacheIndices[i - 1u];
        state.sampleWeights[i] = state.sampleWeights[i - 1u];
        state.radianceDirections[i] = state.radianceDirections[i - 1u];
        state.radianceDirectionWeights[i] = state.radianceDirectionWeights[i - 1u];
    }
    state.cacheIndices[0] = index;
    state.sampleWeights[0] = 1.0 / hit.materialDemodulation;
    state.radianceDirections[0] = direction;
    state.radianceDirectionWeights[0] = directionality;
    state.pathLength = min(state.pathLength + 1u, uint(SHARC_PROPAGATION_DEPTH));
    return true;
}

uint sharcHash(uint value) {
    uint state = value * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

/** Query pass: full-resolution fill that ends paths at eligible cached vertices. */
public struct SharcQueryPolicy : ISharcPolicy {
    SharcFrame frame;
    SharcParameters parameters;

    [mutating] public bool begin(uint64_t frameAddress, uint2 dispatchIndex, uint2 dispatchExtent,
            out uint2 pixel, out uint2 extent) {
        frame = ConstPtr<SharcFrame>(frameAddress)[0];
        parameters = sharcParameters(frame);
        pixel = dispatchIndex;
        extent = dispatchExtent;
        return true;
    }

    public bool updatesOnly() { return false; }

    [mutating] public bool query(SharcVertex vertex, bool primary, float segmentLength,
            float lobeRoughness, out float3 radiance) {
        radiance = float3(0.0);
        if (primary && (frame.flags & SHARC_FLAG_PRIMARY_QUERY) == 0u) return false;
        if (!sharcOwns(vertex, frame.roughnessThreshold)) return false;
        if (!primary) {
            // SHaRC integration guide: a segment shorter than its voxel, or a lobe footprint
            // narrower than it, would resolve detail the entry does not hold.
            float voxelSize = HashGridGetVoxelSize(
                    HashGridGetLevel(vertex.position, parameters.hashGridParameters),
                    parameters.hashGridParameters);
            float alpha = min(lobeRoughness, 0.99);
            alpha *= alpha;
            float footprint = segmentLength * sqrt(0.5 * alpha * alpha / (1.0 - alpha * alpha));
            if (segmentLength <= voxelSize * sqrt(3.0) || footprint <= voxelSize) return false;
        }
        return SharcGetCachedRadiance(parameters, sharcHit(vertex, float3(0.0)), radiance, false);
    }

    [mutating] public bool record(SharcVertex vertex, float3 directLighting, float3 emission,
            uint scatterEvent, float random) {
        return true;
    }
    [mutating] public void propagate(float3 radiance) { }
    [mutating] public void carry(float3 segmentWeight) { }
};

/** Update pass: one rotating pixel per tile traces a path whose vertices feed the cache. */
public struct SharcUpdatePolicy : ISharcPolicy {
    SharcFrame frame;
    SharcParameters parameters;
    SharcState state;
    float antiFireflyFloor;

    [mutating] public bool begin(uint64_t frameAddress, uint2 dispatchIndex, uint2 dispatchExtent,
            out uint2 pixel, out uint2 extent) {
        frame = ConstPtr<SharcFrame>(frameAddress)[0];
        parameters = sharcParameters(frame);
        SharcInit(state);
        antiFireflyFloor = (frame.flags & SHARC_FLAG_ANTI_FIREFLY) != 0u ? 1.0 / frame.preExposure : 0.0;
        // The dispatch covers the tile grid; each tile's sample pixel starts at a hashed offset and
        // advances by one pixel per frame, so every pixel is traced once per tileSize^2 frames.
        uint tileSize = frame.updateTileSize;
        uint tileArea = tileSize * tileSize;
        uint offset = (sharcHash(dispatchIndex.x * 1973u + dispatchIndex.y * 9277u + 26699u) % tileArea
                + frame.frameIndex % tileArea) % tileArea;
        pixel = dispatchIndex * tileSize + uint2(offset % tileSize, offset / tileSize);
        extent = frame.renderExtent;
        return all(pixel < extent);
    }

    public bool updatesOnly() { return true; }

    [mutating] public bool query(SharcVertex vertex, bool primary, float segmentLength,
            float lobeRoughness, out float3 radiance) {
        radiance = float3(0.0);
        return false;
    }

    [mutating] public bool record(SharcVertex vertex, float3 directLighting, float3 emission,
            uint scatterEvent, float random) {
        if (!sharcOwns(vertex, frame.roughnessThreshold)) {
            propagate(directLighting + emission);
            return true;
        }
        return sharcUpdateHit(parameters, state, sharcHit(vertex, emission), directLighting,
                sharcDirectionality(scatterEvent, vertex.surface), random, antiFireflyFloor);
    }

    [mutating] public void propagate(float3 radiance) {
        sharcPropagate(parameters, state, radiance, antiFireflyFloor);
    }

    [mutating] public void carry(float3 segmentWeight) {
        SharcSetThroughput(state, segmentWeight);
    }
};

/** Resolve pass: merges one entry's frame accumulation into its resolved radiance. */
public void resolveSharc(uint entryIndex, uint64_t frameAddress) {
    SharcFrame frame = ConstPtr<SharcFrame>(frameAddress)[0];
    SharcResolveParameters resolve;
    resolve.cameraPositionPrev = frame.previousCameraPosition;
    resolve.accumulationFrameNum = frame.accumulationFrames;
    resolve.responsiveFrameNum = 0u;
    resolve.staleFrameNumMax = frame.staleFrames;
    resolve.frameIndex = frame.frameIndex;
    SharcResolveEntry(entryIndex, sharcParameters(frame), resolve);
}
