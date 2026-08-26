// Caustica-owned adapter for NVIDIA SHaRC 1.8.
//
// The NVIDIA headers are an external, separately licensed build input. They are deliberately not copied
// into this source tree. This adapter keeps the cache in BDA buffers and makes local/direct-light
// ownership explicit: current-vertex lighting stays live, while only propagated radiance is cached.
#ifndef CAUSTICA_SHARC_BRIDGE
#define CAUSTICA_SHARC_BRIDGE 1

#ifndef SHARC_UPDATE
#define SHARC_UPDATE 0
#endif
#ifndef SHARC_QUERY
#define SHARC_QUERY 0
#endif
#ifdef CAUSTICA_SHARC_UPDATE
#undef SHARC_UPDATE
#define SHARC_UPDATE 1
#endif
#ifdef CAUSTICA_SHARC_QUERY
#undef SHARC_QUERY
#define SHARC_QUERY 1
#endif

#define SHARC_ENABLE_GLSL 0
#ifndef SHARC_ENABLE_SH_ENCODING
#define SHARC_ENABLE_SH_ENCODING 1
#endif
#define HASH_GRID_ENABLE_64_BIT_ATOMICS 1
#define HASH_GRID_USE_NORMALS 1
#define HASH_GRID_COMPACT 0
#define SHARC_PROPAGATION_DEPTH 4
#define SHARC_MATERIAL_DEMODULATION 1
#define SHARC_SEPARATE_EMISSIVE 1
#define SHARC_ENABLE_CACHE_RESAMPLING 1
#define SHARC_ENABLE_RESPONSIVE_LIGHTING 0
#ifndef SHARC_SAMPLE_NUM_THRESHOLD
#define SHARC_SAMPLE_NUM_THRESHOLD 3
#endif
#define CAUSTICA_SHARC_MIN_SEGMENT_RATIO 1.0
#define SHARC_LINEAR_PROBE_WINDOW_SIZE \
    ((entryIndex + 1u < sharcParameters.hashGridData.capacity) \
        ? min(8u, sharcParameters.hashGridData.capacity - (entryIndex + 1u)) : 0u)
#define CAUSTICA_SHARC_WORLD_LIMIT 1.0e6

typealias SharcRWPtr<T> = Ptr<T, Access.ReadWrite, AddressSpace.Device, Std430DataLayout>;
#define RW_STRUCTURED_BUFFER(name, type) SharcRWPtr<type> name
#define BUFFER_AT_OFFSET(name, offset) name[offset]

#include "SharcCommon.h"

bool causticaFinite3(float3 value, float limit) {
    return all(value == value) && all(abs(value) <= float3(limit, limit, limit));
}

float causticaSharcDirectionalityWeight(bool isDelta, bool isDiffuse, float perceptualRoughness) {
    if (isDiffuse) return 0.0;
    if (isDelta) return 1.0;
    if (perceptualRoughness != perceptualRoughness || abs(perceptualRoughness) > 1.0) return 0.0;
    float smoothness = saturate(1.0 - perceptualRoughness);
    return smoothness * smoothness;
}

float3 causticaSafeDirection(float3 direction) {
    float lengthSquared = dot(direction, direction);
    return causticaFinite3(direction, 1.0e30) && lengthSquared > 1.0e-12 && lengthSquared < 1.0e30
            ? direction * rsqrt(lengthSquared) : float3(0.0, 0.0, 1.0);
}

bool causticaCacheSampleFinite(float3 radiance, float3 sampleWeight) {
    return causticaFinite3(radiance, HALF_MAX) && all(radiance >= float3(0.0))
            && causticaFinite3(sampleWeight, HALF_MAX) && all(sampleWeight >= float3(0.0));
}

bool causticaSharcGridGeometry(SharcParameters parameters, float3 positionWorld,
                               out float voxelSize) {
    voxelSize = 0.0;
    if (!causticaFinite3(positionWorld, CAUSTICA_SHARC_WORLD_LIMIT)) return false;
    float3 biasedPosition = positionWorld
            + float3(HASH_GRID_POSITION_BIAS, HASH_GRID_POSITION_BIAS, HASH_GRID_POSITION_BIAS);
    uint level = HashGridGetLevel(biasedPosition, parameters.hashGridParameters);
    voxelSize = HashGridGetVoxelSize(level, parameters.hashGridParameters);
    if (!causticaFinite3(float3(voxelSize, voxelSize, voxelSize), CAUSTICA_SHARC_WORLD_LIMIT)
            || voxelSize <= 0.0) return false;
    float3 gridPosition = floor(biasedPosition / voxelSize);
    return causticaFinite3(gridPosition, 65535.0);
}

float3 causticaSharcResolvePreviousCamera(SharcParameters parameters, float3 cameraPositionPrev) {
    float base = parameters.hashGridParameters.logarithmBase;
    float minVoxelSize = base / (parameters.hashGridParameters.sceneScale
            * pow(base, parameters.hashGridParameters.levelBias));
    bool currentSafe = causticaFinite3(float3(minVoxelSize, minVoxelSize, minVoxelSize),
            CAUSTICA_SHARC_WORLD_LIMIT)
            && minVoxelSize > 0.0
            && causticaFinite3(floor(parameters.hashGridParameters.cameraPosition / minVoxelSize), 65535.0);
    bool previousSafe = causticaFinite3(cameraPositionPrev, CAUSTICA_SHARC_WORLD_LIMIT)
            && currentSafe
            && causticaFinite3(floor(cameraPositionPrev / minVoxelSize), 65535.0);
    return previousSafe ? cameraPositionPrev : parameters.hashGridParameters.cameraPosition;
}

bool causticaValidCacheIndex(SharcParameters parameters, uint index) {
    return index != HASH_GRID_INVALID_CACHE_INDEX && index < parameters.hashGridData.capacity;
}

bool causticaCacheSampleUsable(SharcParameters parameters, float3 radiance, float3 sampleWeight) {
    if (!causticaCacheSampleFinite(radiance, sampleWeight)) return false;
    float3 weighted = radiance * sampleWeight;
    return causticaFinite3(weighted * parameters.radianceScale, 1.0e8);
}

float3 causticaSharcAntiFireflyWeight(SharcParameters parameters, HashGridIndex cacheIndex,
                                      float3 sampleValue, float3 sampleWeight) {
    float scalarWeight = max(SharcLuma(sampleWeight), 1.0);
    if (!causticaFinite3(sampleWeight, HALF_MAX) || any(sampleWeight < float3(0.0))
            || !causticaFinite3(sampleValue, HALF_MAX) || any(sampleValue < float3(0.0))
            || !causticaFinite3(float3(scalarWeight, scalarWeight, scalarWeight), HALF_MAX)
            || scalarWeight <= 2.0) return sampleWeight;

    SharcVoxelData previous = SharcGetVoxelData(parameters.resolvedBuffer, cacheIndex);
    float previousSamples = previous.accumulatedSampleNum;
    if (previousSamples > 2.0 && previousSamples == previousSamples
            && abs(previousSamples) <= HALF_MAX) {
        float previousLuma = max(SharcRadianceLuma(previous.accumulatedRadiance), 1.0);
        float currentLuma = max(SharcLuma(sampleValue * sampleWeight), 1.0);
        if (causticaFinite3(float3(previousLuma, currentLuma, 0.0), HALF_MAX) && currentLuma > 0.0) {
            float confidenceT = saturate((previousSamples - 2.0) / 10.0);
            sampleWeight *= saturate(lerp(5.0, 10.0, confidenceT) * previousLuma / currentLuma);
        }
    } else {
        sampleWeight /= sqrt(scalarWeight);
    }
    return sampleWeight;
}

void causticaSharcAddVoxelData(SharcParameters parameters, HashGridIndex cacheIndex,
                               float3 sampleValue, float3 sampleWeight,
                               float3 sampleDirection, float sampleDirectionWeight,
                               uint sampleData, bool antiFirefly) {
    if (!causticaValidCacheIndex(parameters, cacheIndex)
            || !causticaCacheSampleFinite(sampleValue, sampleWeight)) return;
    if (antiFirefly) {
        sampleWeight = causticaSharcAntiFireflyWeight(parameters, cacheIndex,
                sampleValue, sampleWeight);
    }
    if (causticaCacheSampleUsable(parameters, sampleValue, sampleWeight)) {
        SharcAddVoxelData(parameters, cacheIndex, sampleValue, SharcSampleWeight(sampleWeight),
                sampleDirection, sampleDirectionWeight, sampleData);
    }
}

SharcHitData causticaMakeSharcHit(float3 positionWorld, float3 normalWorld, float3 albedo,
                                  float3 emissive, float3 outgoingDirectionToPreviousVertex,
                                  float directionalityWeight) {
    SharcHitData hit;
    hit.positionWorld = positionWorld;
    hit.normalWorld = causticaSafeDirection(normalWorld);
    hit.materialDemodulation = max(albedo, float3(1.0e-3));
    hit.emissive = emissive;
#if SHARC_ENABLE_SH_ENCODING
    hit.radianceDirectionWorld = causticaSafeDirection(outgoingDirectionToPreviousVertex);
    hit.radianceDirectionWeight = clamp(directionalityWeight, 0.0, 1.0);
#endif
    return hit;
}

bool causticaSharcTryQuery(SharcParameters parameters, float3 positionWorld, float3 normalWorld,
                           float3 diffuseAlbedo, float3 outgoingDirection, float segmentLength,
                           out float3 radiance) {
    radiance = float3(0.0, 0.0, 0.0);
    if (!causticaFinite3(positionWorld, CAUSTICA_SHARC_WORLD_LIMIT)
            || !causticaFinite3(normalWorld, 1.0e6)
            || !causticaFinite3(diffuseAlbedo, 1.0e6)
            || !causticaFinite3(outgoingDirection, 1.0e6)
            || !causticaFinite3(float3(segmentLength, segmentLength, segmentLength), CAUSTICA_SHARC_WORLD_LIMIT)
            || all(diffuseAlbedo <= float3(1.0e-4))
            || dot(causticaSafeDirection(normalWorld), causticaSafeDirection(outgoingDirection)) <= 1.0e-4) {
        return false;
    }
    float voxelSize;
    if (!causticaSharcGridGeometry(parameters, positionWorld, voxelSize)) return false;
    float minSegment = voxelSize * CAUSTICA_SHARC_MIN_SEGMENT_RATIO;
    if (!causticaFinite3(float3(voxelSize, minSegment, segmentLength), CAUSTICA_SHARC_WORLD_LIMIT)
            || segmentLength < minSegment) return false;
    SharcHitData hit = causticaMakeSharcHit(positionWorld, normalWorld, diffuseAlbedo,
            float3(0.0, 0.0, 0.0), outgoingDirection, 0.0);
    if (!SharcGetCachedRadiance(parameters, hit, radiance, false)) return false;
    if (!causticaFinite3(radiance, HALF_MAX) || any(radiance < float3(0.0))
            || !any(radiance > float3(1.0e-6))) {
        radiance = float3(0.0);
        return false;
    }
    return true;
}

void causticaSharcPropagate(SharcParameters parameters, inout SharcState state,
                            float3 propagatedDirectLighting, bool antiFirefly) {
#if SHARC_UPDATE
    for (uint i = 0u; i < state.pathLength; ++i) {
        float3 sampleWeight = float3(state.sampleWeights[i]);
        if (!causticaValidCacheIndex(parameters, state.cacheIndices[i])
                || !causticaCacheSampleFinite(propagatedDirectLighting, sampleWeight)) continue;
        float3 previousDirection = float3(0.0, 0.0, 1.0);
        float previousWeight = 0.0;
#if SHARC_ENABLE_SH_ENCODING
        previousDirection = state.radianceDirections[i];
        previousWeight = state.radianceDirectionWeights[i];
#endif
        causticaSharcAddVoxelData(parameters, state.cacheIndices[i], propagatedDirectLighting,
                sampleWeight, previousDirection, previousWeight, 0u, antiFirefly);
    }
#endif
}

void causticaSharcUpdateMiss(SharcParameters parameters, inout SharcState state,
                             float3 radiance, bool antiFirefly) {
#if SHARC_UPDATE
    causticaSharcPropagate(parameters, state, radiance, antiFirefly);
#endif
}

bool causticaSharcUpdateHit(SharcParameters parameters, inout SharcState state,
                            SharcHitData hit, float3 cacheableDirectLighting,
                            float3 liveDirectLighting, float3 propagatedDirectLighting,
                            bool antiFirefly, float random) {
#if SHARC_UPDATE
    if (!causticaFinite3(hit.positionWorld, CAUSTICA_SHARC_WORLD_LIMIT)
            || !causticaFinite3(hit.normalWorld, 1.0e6)
            || !causticaFinite3(hit.materialDemodulation, 1.0e6)
            || !causticaFinite3(cacheableDirectLighting, HALF_MAX)
            || !causticaFinite3(liveDirectLighting, HALF_MAX)
            || !causticaFinite3(propagatedDirectLighting, HALF_MAX)
            || any(cacheableDirectLighting < float3(0.0))
            || any(liveDirectLighting < float3(0.0))
            || any(propagatedDirectLighting < float3(0.0))) return false;
    float voxelSize;
    if (!causticaSharcGridGeometry(parameters, hit.positionWorld, voxelSize)) {
        causticaSharcPropagate(parameters, state, propagatedDirectLighting + hit.emissive, antiFirefly);
        return false;
    }
    HashGridKey key;
    HashGridIndex index = HashGridInsertEntry(parameters.hashGridData, hit.positionWorld,
            hit.normalWorld, parameters.hashGridParameters, key);
    if (!causticaValidCacheIndex(parameters, index)) {
        causticaSharcPropagate(parameters, state, propagatedDirectLighting + hit.emissive, antiFirefly);
        return false;
    }
    float3 demodulation = max(hit.materialDemodulation, float3(1.0e-3));
    float3 direction = SharcGetRadianceDirection(hit);
    float directionWeight = SharcGetRadianceDirectionWeight(hit);
    bool continueTracing = true;
    float3 propagatedRadiance = propagatedDirectLighting + hit.emissive;
#if SHARC_ENABLE_CACHE_RESAMPLING
    uint resamplingDepth = uint(round(lerp(float(SHARC_RESAMPLING_DEPTH_MIN),
            float(SHARC_PROPAGATION_DEPTH), random)));
    if (resamplingDepth <= state.pathLength) {
        SharcVoxelData voxel = SharcGetVoxelData(parameters.resolvedBuffer, index);
        if (voxel.accumulatedSampleNum > SHARC_SAMPLE_NUM_THRESHOLD) {
            float3 cachedRadiance = SharcDecodeRadiance(voxel.accumulatedRadiance, direction);
#if SHARC_MATERIAL_DEMODULATION
            cachedRadiance *= hit.materialDemodulation;
#endif
#if SHARC_SEPARATE_EMISSIVE
            cachedRadiance += hit.emissive;
#endif
            // The cache owns diffuse transport; current-vertex specular/RIS/SSS remains live.
            float3 resampledRadiance = cachedRadiance + liveDirectLighting;
#if !SHARC_SEPARATE_EMISSIVE
            resampledRadiance += hit.emissive;
#endif
            if (causticaFinite3(resampledRadiance, HALF_MAX) && all(resampledRadiance >= float3(0.0))) {
                propagatedRadiance = resampledRadiance;
                continueTracing = false;
            }
        }
    }
#endif
    causticaSharcPropagate(parameters, state, propagatedRadiance, antiFirefly);
    if (!continueTracing) return false;
    float3 demodulatedDirect = cacheableDirectLighting / demodulation;
    if (!causticaCacheSampleUsable(parameters, demodulatedDirect, float3(1.0))) return false;
    causticaSharcAddVoxelData(parameters, index, demodulatedDirect, float3(1.0),
            direction, directionWeight, 1u, antiFirefly);
    uint shiftCount = min(state.pathLength, uint(SHARC_PROPAGATION_DEPTH - 1));
    for (uint i = shiftCount; i > 0u; --i) {
        state.cacheIndices[i] = state.cacheIndices[i - 1u];
        state.sampleWeights[i] = state.sampleWeights[i - 1u];
#if SHARC_ENABLE_SH_ENCODING
        state.radianceDirections[i] = state.radianceDirections[i - 1u];
        state.radianceDirectionWeights[i] = state.radianceDirectionWeights[i - 1u];
#endif
    }
    state.cacheIndices[0] = index;
    state.sampleWeights[0] = SharcSampleWeight(1.0 / demodulation);
#if SHARC_ENABLE_SH_ENCODING
    state.radianceDirections[0] = direction;
    state.radianceDirectionWeights[0] = directionWeight;
#endif
    state.pathLength = min(state.pathLength + 1u, uint(SHARC_PROPAGATION_DEPTH));
#endif
    return true;
}

bool causticaSharcSetThroughput(SharcParameters parameters, inout SharcState state,
                                float3 segmentThroughput) {
#if SHARC_UPDATE
    if (!causticaCacheSampleUsable(parameters, segmentThroughput, float3(1.0))) {
        state.pathLength = 0u;
        return false;
    }
    SharcSetThroughput(state, segmentThroughput);
#endif
    return true;
}

SharcParameters causticaSharcParameters(uint64_t hashEntriesAddress, uint64_t accumulationAddress,
                                        uint64_t resolvedAddress, uint capacity, float3 cameraPosition,
                                        float sceneScale, float radianceScale,
                                        float gridLogarithmBase, float gridLevelBias) {
    SharcParameters parameters;
    float3 safeCameraPosition = causticaFinite3(cameraPosition, CAUSTICA_SHARC_WORLD_LIMIT)
            ? cameraPosition : float3(0.0, 0.0, 0.0);
    parameters.hashGridParameters.cameraPosition = safeCameraPosition;
    float safeLogarithmBase = gridLogarithmBase == gridLogarithmBase
            && abs(gridLogarithmBase) <= 16.0 && gridLogarithmBase >= 1.01 ? gridLogarithmBase : 2.0;
    float safeSceneScale = sceneScale == sceneScale
            && abs(sceneScale) <= 100.0 && sceneScale >= 1.0 ? sceneScale : 1.0;
    float safeLevelBias = gridLevelBias == gridLevelBias
            && abs(gridLevelBias) <= 16.0 ? gridLevelBias : 0.0;
    float safeRadianceScale = radianceScale == radianceScale
            && abs(radianceScale) <= 1000.0 && radianceScale >= 50.0 ? radianceScale : 1000.0;
    parameters.hashGridParameters.logarithmBase = safeLogarithmBase;
    parameters.hashGridParameters.sceneScale = safeSceneScale;
    parameters.hashGridParameters.levelBias = safeLevelBias;
    parameters.hashGridData.capacity = capacity;
    parameters.hashGridData.hashEntriesBuffer = SharcRWPtr<uint64_t>(hashEntriesAddress);
    parameters.radianceScale = safeRadianceScale;
    parameters.accumulationBuffer = SharcRWPtr<SharcAccumulationData>(accumulationAddress);
    parameters.resolvedBuffer = SharcRWPtr<SharcPackedData>(resolvedAddress);
    return parameters;
}

#endif
