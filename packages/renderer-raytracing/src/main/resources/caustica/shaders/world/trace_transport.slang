module trace_transport;
import caustica_types;
import bindings;
import world_common;
import caustica_api;
import caustica_volume;
import world_minimal;
import retained_lights;
import surface_bsdf;
import path_queue_types;
import closest_hit;
import caustica_coverage;
import trace_diagnostics;

public static const float TRACE_MAX_DISTANCE = 10000.0;

public float traceLuminance(float3 value) {
    return dot(max(value, float3(0.0)), ACESCG_LUMA);
}

public float traceEpsilon() {
    return 0.001 / neeAtState(worldBindings.neeAtStateAddress)[0].metersPerSceneUnit;
}

public uint traceSeed(uint2 pixel, uint frame, uint plane) {
    return neeAtHash(pixel.x ^ (pixel.y * 0x9e3779b9u)
            ^ (frame * 0x85ebca6bu) ^ (plane * 0xc2b2ae35u)) | 1u;
}

public RayDesc traceRay(PackedPathSegment path) {
    RayDesc ray;
    ray.Origin = path.origin;
    ray.Direction = path.direction;
    ray.TMin = traceEpsilon();
    ray.TMax = TRACE_MAX_DISTANCE;
    return ray;
}

public PackedPathSegment traceAdvance(PackedPathSegment path, WorldHit hit,
        float3 direction, float3 weight, bool crossesBoundary) {
    path.origin = hit.position + hit.geometricNormal
            * (dot(hit.geometricNormal, direction) >= 0.0 ? 2.0 : -2.0) * traceEpsilon();
    path.direction = normalize(direction);
    path.throughput *= weight;
    path.depth++;
    path.hitDistance = 0.0;
    path.flags = PATH_ENDPOINT_VALID | (path.flags & PATH_ENDPOINT_VOLUME);
    if (crossesBoundary) {
        path.absorption = hit.volumeAbsorption;
        path.ior = hit.volumeIor;
        path.flags = PATH_ENDPOINT_VALID
                | ((hit.pathFlags & WORLD_PATH_VOLUME_ACTIVE) != 0u ? PATH_ENDPOINT_VOLUME : 0u);
    }
    return path;
}

public struct TraceLobes {
    public float3 reflection;
    public float3 transmission;
    public float3 reflectedDirection;
    public float3 transmittedDirection;
    public float continuousProbability;
    public float reflectionProbability;
    public float transmissionProbability;
    public bool volumeTransmission;
};

float traceFresnel(float cosine, float etaI, float etaT) {
    float sinT2 = (etaI * etaI) / (etaT * etaT) * max(0.0, 1.0 - cosine * cosine);
    if (sinT2 >= 1.0) return 1.0;
    float cosT = sqrt(1.0 - sinT2);
    float rs = (etaI * cosine - etaT * cosT) / (etaI * cosine + etaT * cosT);
    float rp = (etaT * cosine - etaI * cosT) / (etaT * cosine + etaI * cosT);
    return 0.5 * (rs * rs + rp * rp);
}

public TraceLobes traceLobes(PackedPathSegment path, WorldHit hit) {
    TraceLobes lobes = {};
    float3 outgoing = -path.direction;
    float3 normal = hit.surface.shadingNormal;
    lobes.reflectedDirection = reflect(path.direction, normal);
    lobes.transmittedDirection = path.direction;
    surfaceDeltaLobes(hit.surface, outgoing, lobes.reflection, lobes.transmission);
    if (hit.surface.boundaryWeight > 0.0) {
        float cosine = max(dot(outgoing, normal), 0.0);
        float fresnel = traceFresnel(cosine, path.ior, hit.volumeIor);
        lobes.reflection += hit.surface.boundaryWeight * fresnel;
        lobes.transmittedDirection = refract(path.direction, normal, path.ior / hit.volumeIor);
        lobes.transmission += hit.surface.boundaryWeight * (1.0 - fresnel)
                * hit.surface.thinTransmissionColor
                * ((path.ior * path.ior) / (hit.volumeIor * hit.volumeIor));
        lobes.volumeTransmission = true;
    }
    float3 diffuse, specular;
    surfaceDenoiserEstimates(hit.surface, outgoing, diffuse, specular);
    float c = max(max(diffuse.x, diffuse.y), diffuse.z)
            + max(max(specular.x, specular.y), specular.z);
    float r = max(max(lobes.reflection.x, lobes.reflection.y), lobes.reflection.z);
    float t = max(max(lobes.transmission.x, lobes.transmission.y), lobes.transmission.z);
    float total = c + r + t;
    if (total > 0.0) {
        lobes.continuousProbability = c / total;
        lobes.reflectionProbability = r / total;
        lobes.transmissionProbability = t / total;
    }
    return lobes;
}

// Keep RGB lobe weights intact while conditioning sampling on this endpoint's owned transport.
// The pre-normalization share is only a guide-dominance estimate, never a radiance multiplier.
public TraceLobes traceResidualLobes(TraceLobes lobes, uint endpointFlags, out float retainedProbability) {
    retainedProbability = 1.0;
    if ((endpointFlags & (PATH_ENDPOINT_DELEGATED_REFLECTION | PATH_ENDPOINT_DELEGATED_TRANSMISSION)) == 0u)
        return lobes;
    if ((endpointFlags & PATH_ENDPOINT_DELEGATED_REFLECTION) != 0u) {
        lobes.reflection = float3(0.0);
        lobes.reflectionProbability = 0.0;
    }
    if ((endpointFlags & PATH_ENDPOINT_DELEGATED_TRANSMISSION) != 0u) {
        lobes.transmission = float3(0.0);
        lobes.transmissionProbability = 0.0;
    }
    retainedProbability = lobes.continuousProbability + lobes.reflectionProbability + lobes.transmissionProbability;
    if (retainedProbability > 0.0) {
        lobes.continuousProbability /= retainedProbability;
        lobes.reflectionProbability /= retainedProbability;
        lobes.transmissionProbability /= retainedProbability;
    }
    return lobes;
}

public bool traceScatter(inout PackedPathSegment path, WorldHit hit, TraceLobes lobes,
        inout uint seed, out float pdf, out uint event) {
    pdf = 0.0;
    event = EVENT_NONE;
    float select = retainedLightRandom(seed);
    if (select < lobes.reflectionProbability) {
        path = traceAdvance(path, hit, lobes.reflectedDirection,
                lobes.reflection / lobes.reflectionProbability, false);
        event = EVENT_GLOSSY | EVENT_REFLECTION;
        return true;
    }
    if (select < lobes.reflectionProbability + lobes.transmissionProbability) {
        path = traceAdvance(path, hit, lobes.transmittedDirection,
                lobes.transmission / lobes.transmissionProbability, lobes.volumeTransmission);
        event = EVENT_GLOSSY | EVENT_TRANSMISSION;
        return true;
    }
    if (lobes.continuousProbability == 0.0) return false;
    BsdfSampleQuery query;
    query.surface = hit.surface;
    query.outgoingDirection = -path.direction;
    query.random = float3(retainedLightRandom(seed), retainedLightRandom(seed), retainedLightRandom(seed));
    BsdfSample sample = sampleBsdf(query);
    if (sample.pdf <= 0.0 || !any(sample.throughputWeight > 0.0)) return false;
    pdf = sample.pdf * lobes.continuousProbability;
    event = sample.eventFlags;
    path = traceAdvance(path, hit, sample.incomingDirection,
            sample.throughputWeight / lobes.continuousProbability, false);
    return true;
}

public ShadowPayload traceShadowQuery<TComposition : IComposition>(RayDesc ray,
        uint seed, float3 absorption, float ior, inout ShadowDiagnosticTrace diagnostics, out float3 geometricNormal) {
    geometricNormal = float3(0.0);
    ShadowPayload shadow = {};
    shadow.volumeAbsorption = absorption;
    shadow.volumeIor = ior;
    // Traversal resolves opaque geometry and known micromap states itself, so only coverage-tested
    // surfaces without a volume reach this loop.
    RayQuery<RAY_FLAG_NONE> query;
    query.TraceRayInline(worldAccelerationStructure(), RAY_FLAG_NONE,
            WORLD_RAY_MASK_SECONDARY, ray);
    uint proceedCount = 0u;
    while (query.Proceed()) {
        if (shadowDiagnosticsActive()) proceedCount++;
        uint instanceBase = query.CandidateInstanceID();
        uint geometryIndex = query.CandidateGeometryIndex();
        uint primitiveIndex = query.CandidatePrimitiveIndex();
        RetainedGeometryRecord record = ((ConstPtr<RetainedGeometryRecord>)worldBindings.geometryTableAddress)
                [instanceBase + geometryIndex];
        CoverageInput input = {};
        input.shaderRoot = worldShaderRoot();
        input.bindingData = record.surfaceBinding;
        input.instanceData = worldInstanceRecord(record).instanceData;
        input.primitiveIndex = primitiveIndex;
        input.barycentrics = query.CandidateTriangleBarycentrics();
        TComposition.Coverages coverages = {};
        float coverage = clamp(coverages.evaluateCoverage(record.coverageImplementation, input), 0.0, 1.0);
        bool stochastic = (record.flags & RETAINED_GEOMETRY_STOCHASTIC) != 0u;
        if (stochastic ? worldCoverageRandom(seed, instanceBase, geometryIndex, primitiveIndex) >= coverage
                : coverage < record.alphaCutoff) continue;
        // Committing narrows the search distance; traversal still finds the nearest accepted surface.
        query.CommitNonOpaqueTriangleHit();
    }
    if (query.CommittedStatus() == COMMITTED_NOTHING) {
        shadow.hitDistance = -1.0;
        shadowDiagnosticQuery(diagnostics, proceedCount, false, uint2(0u));
        return shadow;
    }
    RetainedGeometryRecord record = ((ConstPtr<RetainedGeometryRecord>)worldBindings.geometryTableAddress)
            [query.CommittedInstanceID() + query.CommittedGeometryIndex()];
    uint primitiveIndex = query.CommittedPrimitiveIndex();
    shadowDiagnosticQuery(diagnostics, proceedCount, true,
            uint2(query.CommittedInstanceID() + query.CommittedGeometryIndex(), primitiveIndex));
    shadow.hitDistance = query.CommittedRayT();
    // A certified blocker has full coverage and zero transmission across its entire revision.
    if ((record.flags & RETAINED_GEOMETRY_GUARANTEED_SHADOW_BLOCKER) != 0u) return shadow;
    WorldInstanceHistory history = worldInstanceHistory(record, false);
    uint3 indices = worldTriangleIndices(record, primitiveIndex);
    float3 p0 = worldVertex(history.current, indices.x);
    float3 edgeU = worldVertex(history.current, indices.y) - p0;
    float3 edgeV = worldVertex(history.current, indices.z) - p0;
    SurfaceInput input = worldSurfaceInput(query.CommittedTriangleBarycentrics(), primitiveIndex,
            ray.Origin + ray.Direction * shadow.hitDistance, ray.Direction, edgeU, edgeV,
            record, history, false);
    geometricNormal = input.geometricNormal;
    bool volume = (record.flags & RETAINED_GEOMETRY_HAS_VOLUME) != 0u;
    TComposition.Surfaces surfaces = {};
    OpenPbrSurface material = surfaces.evaluateSurface(record.surfaceImplementation, input);
    material.geometry_thin_walled = !volume;
    if (dot(material.geometry_normal, input.geometricNormal) < 0.0)
        material.geometry_normal = -material.geometry_normal;
    SurfaceClosure surface = surfaceClosure(material, input.geometricNormal);
    if (!volume) {
        shadow.transmission = surfaceShadowTransmission(surface, input.outgoingDirection);
        return shadow;
    }
    float incomingIor = shadow.volumeIor;
    shadow.volumeAbsorption = float3(0.0);
    shadow.volumeIor = 1.0;
    if (surface.boundaryWeight <= 0.0) return shadow;
    shadow.transmission = surface.thinTransmissionColor * surface.boundaryWeight;
    TComposition.Volumes volumes = {};
    VolumeInput volumeQuery = volumeInput(input, record.volumeBinding,
            input.evaluationFlags & EVALUATION_ANIMATION_ENABLED);
    bool insideVolume = dot(worldTriangleNormal(input.objectToScene, edgeU, edgeV), ray.Direction) < 0.0;
    if (insideVolume) {
        VolumeProperties properties = volumes.evaluateVolume(record.volumeImplementation, volumeQuery);
        shadow.volumeAbsorption = max(properties.absorptionCoefficient, float3(0.0));
        shadow.volumeIor = max(properties.indexOfRefraction, 1.0);
    } else {
        VolumeBoundaryLightingInput boundary = {};
        boundary.volume = volumeQuery;
        boundary.lightDirection = normalize(ray.Direction);
        boundary.boundaryDistance = shadow.hitDistance;
        boundary.interiorIor = incomingIor;
        shadow.transmission *= max(volumes.evaluateBoundaryLighting(
                record.volumeImplementation, boundary), float3(0.0));
    }
    return shadow;
}

public float3 traceVisibilityRay<TComposition : IComposition>(RayDesc ray, float3 absorption,
        float ior, inout uint seed) {
    float3 visibility = float3(1.0);
    ShadowDiagnosticTrace diagnostics = {};
    while (ray.TMax > ray.TMin) {
        retainedLightRandom(seed);
        float3 geometricNormal;
        ShadowPayload shadow = traceShadowQuery<TComposition>(ray, seed, absorption, ior, diagnostics, geometricNormal);
        if (shadow.hitDistance < 0.0) break;
        visibility *= exp(-absorption * shadow.hitDistance) * shadow.transmission;
        if (!any(visibility > 0.0)) {
            shadowDiagnosticReport(diagnostics);
            return float3(0.0);
        }
        absorption = shadow.volumeAbsorption;
        ior = shadow.volumeIor;
        float remainingDistance = ray.TMax - shadow.hitDistance;
        float3 hitPosition = ray.Origin + ray.Direction * shadow.hitDistance;
        float3 previousOrigin = ray.Origin;
        // Normal clearance survives grazing directions whose along-ray epsilon rounds back onto the plane.
        ray.Origin = hitPosition + geometricNormal
                * (dot(geometricNormal, ray.Direction) >= 0.0 ? 1.0 : -1.0)
                * min(2.0 * traceEpsilon(), remainingDistance);
        // The offset is lateral as well as longitudinal; only its forward projection consumes ray range.
        float advance = clamp(dot(ray.Origin - hitPosition, ray.Direction), 0.0, remainingDistance);
        visibility *= exp(-absorption * advance);
        shadowDiagnosticAdvance(diagnostics, previousOrigin, ray.Origin);
        ray.TMax = remainingDistance - advance;
    }
    shadowDiagnosticReport(diagnostics);
    return visibility * exp(-absorption * ray.TMax);
}

public float3 traceVisibility<TComposition : IComposition>(PackedPathSegment path, WorldHit hit,
        DirectLightSample selected, inout uint seed) {
    RayDesc ray;
    ray.Origin = hit.position + hit.geometricNormal
            * (dot(hit.geometricNormal, selected.direction) >= 0.0 ? 2.0 : -2.0) * traceEpsilon();
    ray.Direction = selected.direction;
    ray.TMin = traceEpsilon();
    ray.TMax = selected.maximumDistance;
    float3 visibility = traceVisibilityRay<TComposition>(ray, path.absorption, path.ior, seed);
    // Outdoor light connections attenuate through the spatial medium; explicit interiors carry
    // their absorption in the shadow state and stay on the interior contract.
    if (path.ior == 1.0 && !any(path.absorption > 0.0)) {
        visibility *= traceSpatialLightTransmittance<TComposition>(
                ray.Origin, ray.Direction, selected.maximumDistance);
    }
    return visibility;
}

public struct TraceSpatialMedium {
    public float3 transmittance;
    public float3 scattering;
    public float scatteringDistance;
};

// Sorted normalized node/weight pairs; the order-N rule starts at index N-1.
static const float2 SPATIAL_QUADRATURE[15] = {
    float2(0.5, 1.0),
    float2(0.2113248654051871, 0.5),
    float2(0.7886751345948129, 0.5),
    float2(0.0694318442029737, 0.1739274225687269),
    float2(0.3300094782075719, 0.3260725774312731),
    float2(0.6699905217924281, 0.3260725774312731),
    float2(0.9305681557970263, 0.1739274225687269),
    float2(0.0198550717512319, 0.0506142681451882),
    float2(0.1016667612931867, 0.1111905172266873),
    float2(0.2372337950418355, 0.1568533229389437),
    float2(0.4082826787521751, 0.1813418916891810),
    float2(0.5917173212478249, 0.1813418916891810),
    float2(0.7627662049581645, 0.1568533229389437),
    float2(0.8983332387068133, 0.1111905172266873),
    float2(0.9801449282487681, 0.0506142681451882)
};

public float3 traceVolumeLighting<TComposition : IComposition>(float3 position, float3 direction,
        float anisotropy, float3 ambientRadiance, uint2 pixel, uint candidates, bool coherent,
        float minimumDistance, float maximumDistance, inout uint seed) {
    NeeAtState state = neeAtState(worldBindings.neeAtStateAddress)[0];
    RetainedLightSamplingContext context = retainedLightSamplingContext(state, pixel, candidates, coherent);
    DirectLightSample selected = invalidDirectLight();
    float3 selectedRadiance = 0.0;
    float selectedWeight = 0.0;
    float totalWeight = 0.0;
    for (uint candidate = 0; candidate < candidates; candidate++) {
        DirectLightSample light = sampleRetainedLight(context, position, candidate, seed);
        if (!light.valid) continue;
        float phase = tracePhase(dot(direction, light.direction), anisotropy);
        float3 radiance = light.radianceOverShapePdf * (phase / light.proposalPdf);
        float weight = traceLuminance(radiance);
        totalWeight += weight;
        if (weight > 0.0 && retainedLightRandom(seed) * totalWeight < weight) {
            selected = light;
            selectedRadiance = radiance;
            selectedWeight = weight;
        }
    }
    if (any(ambientRadiance > 0.0)) {
        // Phase sampling cancels the phase PDF for constant distant radiance. The ambient
        // candidate represents one integral, while the retained-light candidates are averaged.
        float u = retainedLightRandom(seed);
        float g = anisotropy;
        float ratio = (1.0 - g * g) / (1.0 - g + 2.0 * g * u);
        float cosine = abs(g) < 1e-4 ? 2.0 * u - 1.0 : (1.0 + g * g - ratio * ratio) / (2.0 * g);
        cosine = clamp(cosine, -1.0, 1.0);
        float phi = 6.28318530718 * retainedLightRandom(seed);
        float3 tangent, bitangent;
        retainedLightBasis(direction, tangent, bitangent);
        float3 ambientDirection = direction * cosine + sqrt(max(0.0, 1.0 - cosine * cosine))
                * (tangent * cos(phi) + bitangent * sin(phi));
        float3 radiance = ambientRadiance * float(candidates);
        float weight = traceLuminance(radiance);
        totalWeight += weight;
        if (weight > 0.0 && retainedLightRandom(seed) * totalWeight < weight) {
            selected.valid = true;
            selected.direction = ambientDirection;
            selected.maximumDistance = maximumDistance;
            selectedRadiance = radiance;
            selectedWeight = weight;
        }
    }
    if (!selected.valid) return 0.0;
    RayDesc ray;
    ray.Origin = position;
    ray.Direction = selected.direction;
    ray.TMin = minimumDistance;
    ray.TMax = min(selected.maximumDistance, maximumDistance);
    // The scattering point is in the spatial medium by construction; the helper holds identity when
    // the medium is inactive.
    float3 visibility = traceVisibilityRay<TComposition>(ray, float3(0.0), 1.0, seed)
            * traceSpatialLightTransmittance<TComposition>(ray.Origin, ray.Direction, ray.TMax);
    return selectedRadiance * visibility * (totalWeight / (float(candidates) * selectedWeight));
}

public TraceSpatialMedium traceSpatialMediumSegment<TComposition : IComposition>(
        PackedPathSegment path, float distance, uint2 pixel, bool includeScattering) {
    TraceSpatialMedium result = { float3(1.0), float3(0.0), 0.0 };
    // Explicit interiors replace the outdoor medium; the post pass owns the camera segment.
    if (worldBindings.spatialMediumActive == 0u
            || path.depth == 0u
            || (path.flags & PATH_ENDPOINT_VOLUME) != 0u) return result;
    NeeAtState state = neeAtState(worldBindings.neeAtStateAddress)[0];
    float maximumDistance = 256.0 / state.metersPerSceneUnit;
    float length = min(distance, maximumDistance);
    float lengthMeters = length * state.metersPerSceneUnit;
    uint sampleCount = lengthMeters <= 1.0 ? 1u : lengthMeters <= 4.0 ? 2u : lengthMeters <= 8.0 ? 4u : 8u;
    WorldPush frame = ((ConstPtr<WorldPush>)worldBindings.worldPushAddress)[0];
    TComposition.Volumes volumes = {};
    SpatialMediumInput input = {};
    input.shaderRoot = worldShaderRoot();
    input.bindingData = worldBindings.spatialMediumBindingData;
    input.instanceData = worldBindings.spatialMediumInstanceData;
    input.metersPerSceneUnit = state.metersPerSceneUnit;
    input.time = frame.time;
    uint seed = traceSeed(pixel, frame.frameIndex, path.depth + 149u);
    float totalWeight = 0.0;
    float selectedDistance = 0.0;
    float3 selectedCoefficient = float3(0.0);
    float selectedAnisotropy = 0.0;
    float3 selectedAmbientRadiance = 0.0;
    float selectedWeight = 0.0;
    float scatteringWeight = 0.0;
    for (uint i = 0u; i < sampleCount; i++) {
        float2 quadrature = SPATIAL_QUADRATURE[sampleCount - 1u + i];
        float segmentLength = length * quadrature.y;
        float sampleDistance = length * quadrature.x;
        float3 p = path.origin + path.direction * sampleDistance;
        input.position = p + float3(worldBindings.spatialMediumOriginX,
                worldBindings.spatialMediumOriginY, worldBindings.spatialMediumOriginZ);
        input.proceduralPosition = p + frame.proceduralDomainOffset;
        SpatialMediumProperties medium = volumes.evaluateSpatialMedium(worldBindings.spatialMediumImplementation, input);
        float3 segmentT = exp(-medium.extinction * segmentLength);
        if (includeScattering) {
            float3 integral = float3(
                    medium.extinction.x > 0.0 ? (1.0 - segmentT.x) / medium.extinction.x : segmentLength,
                    medium.extinction.y > 0.0 ? (1.0 - segmentT.y) / medium.extinction.y : segmentLength,
                    medium.extinction.z > 0.0 ? (1.0 - segmentT.z) / medium.extinction.z : segmentLength);
            float3 coefficient = result.transmittance * medium.extinction * medium.scatteringAlbedo * integral;
            float weight = traceLuminance(coefficient);
            totalWeight += weight;
            if (weight > 0.0 && retainedLightRandom(seed) * totalWeight < weight) {
                selectedDistance = sampleDistance;
                selectedCoefficient = coefficient;
                selectedAnisotropy = medium.anisotropy;
                selectedAmbientRadiance = medium.ambientRadiance;
                selectedWeight = weight;
            }
            float3 scattering = result.transmittance * medium.ambientSource * integral;
            result.scattering += scattering;
            scatteringWeight += weight;
            result.scatteringDistance += sampleDistance * weight;
        }
        result.transmittance *= segmentT;
    }
    // Thin segments rarely need a shadow ray. Survival compensation preserves their mean lighting.
    float shadowProbability = min(totalWeight * 16.0, 1.0);
    if (includeScattering && shadowProbability > 0.0 && retainedLightRandom(seed) < shadowProbability) {
        float3 position = path.origin + path.direction * selectedDistance;
        float3 lighting = traceVolumeLighting<TComposition>(position, path.direction, selectedAnisotropy,
                selectedAmbientRadiance, pixel, 1u, false, traceEpsilon(), maximumDistance, seed);
        result.scattering += selectedCoefficient * lighting * (totalWeight / (selectedWeight * shadowProbability));
    }
    result.scatteringDistance = scatteringWeight > 0.0 ? result.scatteringDistance / scatteringWeight : 0.0;
    return result;
}

// Light connections attenuate through the spatial medium with the same quadrature ladder and range
// cap as continuation segments. Scattering stays owned by the path integrator, so only extinction is
// evaluated; the deterministic nodes need no seed. Returns identity when the medium is inactive.
public float3 traceSpatialLightTransmittance<TComposition : IComposition>(
        float3 origin, float3 direction, float distance) {
    float3 transmittance = float3(1.0);
    if (worldBindings.spatialMediumActive == 0u) return transmittance;
    NeeAtState state = neeAtState(worldBindings.neeAtStateAddress)[0];
    float length = min(distance, 256.0 / state.metersPerSceneUnit);
    float lengthMeters = length * state.metersPerSceneUnit;
    uint sampleCount = lengthMeters <= 1.0 ? 1u : lengthMeters <= 4.0 ? 2u : lengthMeters <= 8.0 ? 4u : 8u;
    WorldPush frame = ((ConstPtr<WorldPush>)worldBindings.worldPushAddress)[0];
    TComposition.Volumes volumes = {};
    SpatialMediumInput input = {};
    input.shaderRoot = worldShaderRoot();
    input.bindingData = worldBindings.spatialMediumBindingData;
    input.instanceData = worldBindings.spatialMediumInstanceData;
    input.metersPerSceneUnit = state.metersPerSceneUnit;
    input.time = frame.time;
    for (uint i = 0u; i < sampleCount; i++) {
        float2 quadrature = SPATIAL_QUADRATURE[sampleCount - 1u + i];
        float3 p = origin + direction * (length * quadrature.x);
        input.position = p + float3(worldBindings.spatialMediumOriginX,
                worldBindings.spatialMediumOriginY, worldBindings.spatialMediumOriginZ);
        input.proceduralPosition = p + frame.proceduralDomainOffset;
        SpatialMediumProperties medium = volumes.evaluateSpatialMedium(
                worldBindings.spatialMediumImplementation, input);
        transmittance *= exp(-medium.extinction * (length * quadrature.y));
    }
    return transmittance;
}

public struct TraceDirect {
    public float3 diffuse;
    public float3 specular;
    public float distance;
};

struct TraceLightSelection {
    DirectLightSample light;
    float weightSum;
    float target;
    uint candidateCount;
};

// The reservoir keeps one candidate; its sum and target normalize that candidate's contribution.
TraceLightSelection traceSelectLight(WorldHit hit, float3 outgoingDirection,
        uint2 pixel, bool coherent, inout uint seed) {
    uint count;
    DirectLightSample selected = invalidDirectLight();
    float sum = 0.0, selectedTarget = 0.0;
    BsdfQuery query;
    query.surface = hit.surface;
    query.outgoingDirection = outgoingDirection;
    {
        NeeAtState state = neeAtState(worldBindings.neeAtStateAddress)[0];
        count = clamp(state.candidateCount, 1u, 8u);
        RetainedLightSamplingContext context = retainedLightSamplingContext(state, pixel, count, coherent);
        for (uint i = 0u; i < count; i++) {
            DirectLightSample candidate = sampleRetainedLight(context, hit.position, i, seed);
            if (!candidate.valid) continue;
            query.incomingDirection = candidate.direction;
            float target = max(max(candidate.radianceOverShapePdf.x,
                    candidate.radianceOverShapePdf.y), candidate.radianceOverShapePdf.z)
                    * evaluateBsdfPdf(query);
            float weight = target / candidate.proposalPdf;
            sum += weight;
            if (weight > 0.0 && retainedLightRandom(seed) * sum < weight) {
                selected = candidate;
                selectedTarget = target;
            }
        }
    }
    return { selected, sum, selectedTarget, count };
}

public TraceDirect traceDirect<TComposition : IComposition>(PackedPathSegment path, WorldHit hit, TraceLobes lobes,
        uint2 pixel, bool coherent, inout uint seed, inout uint2 feedback) {
    TraceDirect result = {};
    if (lobes.continuousProbability == 0.0) return result;
    TraceLightSelection selection = traceSelectLight(hit, -path.direction, pixel, coherent, seed);
    DirectLightSample selected = selection.light;
    if (!selected.valid || selection.target <= 0.0) return result;
    float3 visibility = traceVisibility<TComposition>(path, hit, selected, seed);
    if (!any(visibility > 0.0)) return result;
    BsdfQuery query;
    query.surface = hit.surface;
    query.outgoingDirection = -path.direction;
    query.incomingDirection = selected.direction;
    BsdfEvaluation evaluation = evaluateBsdf(query);
    float3 factor = selected.radianceOverShapePdf * abs(dot(hit.surface.shadingNormal,
            selected.direction)) * visibility * (selection.weightSum / (float(selection.candidateCount) * selection.target));
    result.diffuse = evaluation.diffuseValue * factor;
    result.specular = evaluation.specularValue * factor;
    if (selected.linkedEmitter && selected.shapePdf > 0.0) {
        float pdf = neeAtLightMisPdf(selected.proposalPdf, selected.shapePdf, selection.candidateCount);
        result.diffuse *= pdf / (pdf + evaluation.diffusePdf * lobes.continuousProbability);
        result.specular *= pdf / (pdf + evaluation.specularPdf * lobes.continuousProbability);
    }
    result.distance = selected.signalDistance;
    neeAtAccumulateFeedback(feedback, worldBindings, pixel, selected.lightIndex,
            traceLuminance(path.throughput * (result.diffuse + result.specular)), coherent);
    return result;
}

// Deterministic optical quadrature supplies stable attenuation and reconstruction guides.
// The finite integration domain matches the prepared outdoor field's useful range.
public float tracePhase(float cosine, float g) {
    return (1.0 - g * g) / (12.5663706144 * pow(1.0 + g * g - 2.0 * g * cosine, 1.5));
}

float2 traceCameraClip(uint2 pixel, uint2 extent, WorldPush frame) {
    return (float2(pixel) + 0.5 + frame.jitter) * (2.0 / float2(extent)) - 1.0;
}

float3 traceCameraDirection(float2 clip, WorldPush frame) {
    float4 point = mul(frame.invViewProj, float4(clip, 0.0, 1.0));
    return normalize(point.xyz / point.w);
}

/**
 * Angle between this pixel's camera ray and the ray one trace-resolution pixel above it. A camera ray
 * cone starts at the eye with zero width and this spread. Delta events at flat triangles keep the
 * spread, so the cone's width is spread times unfolded path length until a continuous scatter widens it.
 */
public float traceCameraSpread(uint2 pixel, uint2 extent, WorldPush frame) {
    float2 clip = traceCameraClip(pixel, extent, frame);
    return length(traceCameraDirection(clip + float2(0.0, 2.0 / float(extent.y)), frame)
            - traceCameraDirection(clip, frame));
}

/**
 * Spread a continuous scatter adds to a ray cone: 0.3 times the apex angle of the cone whose solid
 * angle is 1 / pdf. RTXPT's factor deliberately under-estimates the lobe because stochastic paths
 * already antialias, while over-blur would be visible.
 */
public float traceConeSpreadGrowth(float pdf) {
    return 0.6 * acos(max(-1.0, 1.0 - 1.0 / (6.28318530718 * pdf)));
}

public PackedPathSegment traceCamera<TComposition : IComposition>(uint2 pixel,
        uint2 extent, WorldPush frame) {
    PackedPathSegment path = {};
    path.origin = frame.camOffset;
    path.direction = traceCameraDirection(traceCameraClip(pixel, extent, frame), frame);
    path.throughput = float3(1.0);
    path.ior = 1.0;
    path.flags = PATH_ENDPOINT_VALID;
    if (worldBindings.initialVolumeActive != 0u) {
        TComposition.Volumes volumes = {};
        VolumeInput input = {};
        input.shaderRoot = worldShaderRoot();
        input.bindingData = worldBindings.initialVolumeBinding;
        input.instanceData = worldBindings.initialVolumeInstance;
        input.boundaryPosition = path.origin;
        input.proceduralPosition = path.origin + frame.proceduralDomainOffset;
        input.metersPerSceneUnit = neeAtState(worldBindings.neeAtStateAddress)[0].metersPerSceneUnit;
        input.time = frame.time;
        input.previousTime = frame.previousTime;
        input.evaluationFlags = (frame.flags & 16u) != 0u ? EVALUATION_ANIMATION_ENABLED : 0u;
        VolumeProperties properties = volumes.evaluateVolume(worldBindings.initialVolumeImplementation, input);
        path.absorption = max(properties.absorptionCoefficient, float3(0.0));
        path.ior = max(properties.indexOfRefraction, 1.0);
        path.flags |= PATH_ENDPOINT_VOLUME;
    }
    return path;
}
