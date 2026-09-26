module closest_hit;
import retained_lights;
import path_queue_types;
import caustica_types;
import bindings;
import world_common;
import world_minimal;
import caustica_api;
import caustica_surface;
import caustica_volume;
import surface_bsdf;

public VolumeInput volumeInput(SurfaceInput surface, uint64_t bindingData, uint evaluationFlags) {
    VolumeInput query = {};
    query.shaderRoot = surface.shaderRoot;
    query.bindingData = bindingData;
    query.instanceData = surface.instanceData;
    query.primitiveIndex = surface.primitiveIndex;
    query.boundaryPosition = surface.position;
    query.proceduralPosition = surface.proceduralPosition;
    query.metersPerSceneUnit = surface.metersPerSceneUnit;
    query.time = surface.time;
    query.previousTime = surface.previousTime;
    query.evaluationFlags = evaluationFlags;
    return query;
}

public void runClosestHit<TComposition : IComposition>(inout WorldPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    payload.geometryRecordIndex = worldInstanceCustomIndex() + GeometryIndex();
    payload.primitiveIndex = PrimitiveIndex();
    payload.barycentrics = attributes.barycentrics;
    payload.hitDistance = RayTCurrent();
    payload.pathFlags &= ~WORLD_PATH_MISS;
}

public WorldHit resolveWorldHit<TComposition : IComposition>(WorldPayload intersection,
        RayDesc ray, float previousBsdfPdf, float textureFootprint) {
    WorldHit payload = {};
    payload.pathFlags = intersection.pathFlags;
    payload.hitDistance = intersection.hitDistance;
    if ((intersection.pathFlags & WORLD_PATH_MISS) != 0u) {
        TComposition.Environments environments = {};
        payload.radiance = max(environments.evaluateEnvironment(worldEnvironmentImplementation(),
                worldEnvironmentQuery(ray.Direction, previousBsdfPdf <= 0.0)), float3(0.0));
        return payload;
    }
    RetainedGeometryRecord record = ((ConstPtr<RetainedGeometryRecord>)worldBindings.geometryTableAddress)
            [intersection.geometryRecordIndex];
    bool volume = (record.flags & RETAINED_GEOMETRY_HAS_VOLUME) != 0u;
    bool reconstructionAnchor = (payload.pathFlags & WORLD_PATH_GUIDE) != 0u;
    WorldInstanceHistory history = worldInstanceHistory(record, reconstructionAnchor);
    uint3 indices = worldTriangleIndices(record, intersection.primitiveIndex);
    float3 p0 = worldVertex(history.current, indices.x);
    float3 edgeU = worldVertex(history.current, indices.y) - p0;
    float3 edgeV = worldVertex(history.current, indices.z) - p0;
    SurfaceInput input = worldSurfaceInput(intersection.barycentrics, intersection.primitiveIndex,
            ray.Origin + ray.Direction * intersection.hitDistance, ray.Direction, edgeU, edgeV,
            record, history, reconstructionAnchor);
    input.textureFootprint = textureFootprint;
    TComposition.Surfaces surfaces = {};
    OpenPbrSurface material = surfaces.evaluateSurface(record.surfaceImplementation, input);
    material.geometry_thin_walled = !volume;
    // Authored normals follow mesh winding; both sides must use the hit's oriented hemisphere.
    if (dot(material.geometry_normal, input.geometricNormal) < 0.0) {
        material.geometry_normal = -material.geometry_normal;
        material.previous_geometry_normal = -material.previous_geometry_normal;
    }
    payload.surface = surfaceClosure(material, input.geometricNormal);
    payload.position = input.position;
    payload.geometricNormal = input.geometricNormal;
    if (reconstructionAnchor) {
        BuiltInTriangleIntersectionAttributes attributes;
        attributes.barycentrics = intersection.barycentrics;
        payload.previousPosition = worldPreviousHitPosition(attributes, record, history, intersection.primitiveIndex);
    }
    payload.lightIndex = worldEmitterLightIndex(record, intersection.primitiveIndex);
    payload.pathFlags &= ~(WORLD_PATH_MISS | WORLD_PATH_VOLUME_ACTIVE);
    payload.volumeAbsorption = float3(0.0);
    payload.volumeIor = 1.0;
    if (volume && dot(worldTriangleNormal(input.objectToScene, edgeU, edgeV), ray.Direction) < 0.0) {
        TComposition.Volumes volumes = {};
        VolumeInput query = volumeInput(input, record.volumeBinding, input.evaluationFlags);
        VolumeProperties properties = volumes.evaluateVolume(record.volumeImplementation, query);
        payload.volumeAbsorption = max(properties.absorptionCoefficient, float3(0.0));
        payload.volumeIor = max(properties.indexOfRefraction, 1.0);
        payload.pathFlags |= WORLD_PATH_VOLUME_ACTIVE;
    }
    return payload;
}


[shader("closesthit")]
void main<TComposition : IComposition>(inout WorldPayload payload,
        in BuiltInTriangleIntersectionAttributes attributes) {
    runClosestHit<TComposition>(payload, attributes);
}
