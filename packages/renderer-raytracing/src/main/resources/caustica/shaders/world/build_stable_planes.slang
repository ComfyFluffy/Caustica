module build_stable_planes;
import retained_lights;
import path_queue_types;
import surface_bsdf;
import caustica_volume;
import caustica_types;
import bindings;
import world_minimal;
import world_common;
import caustica_api;
import trace_transport;
import closest_hit;
import stable_planes;
import stable_plane_types;

struct StableBranch {
    PackedPathSegment path;
    float3x3 imageTransform;
    float3 motion;
    float length;
    uint identity;
    bool transmitted;
};

float3x3 branchReflection(float3 n) {
    return float3x3(
            1.0 - 2.0 * n.x * n.x, -2.0 * n.x * n.y, -2.0 * n.x * n.z,
            -2.0 * n.y * n.x, 1.0 - 2.0 * n.y * n.y, -2.0 * n.y * n.z,
            -2.0 * n.z * n.x, -2.0 * n.z * n.y, 1.0 - 2.0 * n.z * n.z);
}

// Rotate the refracted endpoint's guide frame back into the incident image frame.
float3x3 branchRefraction(float3 refractedDirection, float3 incidentDirection) {
    float3 from = normalize(refractedDirection);
    float3 v = cross(from, incidentDirection);
    float c = dot(from, incidentDirection);
    float3x3 skew = float3x3(0, -v.z, v.y, v.z, 0, -v.x, -v.y, v.x, 0);
    return float3x3(1, 0, 0, 0, 1, 0, 0, 0, 1) + skew + mul(skew, skew) / (1.0 + c);
}

StableBranch branchAdvance(StableBranch branch, WorldHit hit, TraceLobes lobes, bool transmission) {
    float3 incidentDirection = branch.path.direction;
    float3 direction = transmission ? lobes.transmittedDirection : lobes.reflectedDirection;
    branch.path = traceAdvance(branch.path, hit, direction,
            transmission ? lobes.transmission : lobes.reflection,
            transmission && lobes.volumeTransmission);
    float3x3 transform = transmission ? branchRefraction(direction, incidentDirection)
            : branchReflection(hit.surface.shadingNormal);
    float3 movement = hit.previousPosition - hit.position;
    branch.motion += mul(branch.imageTransform, movement - mul(transform, movement));
    branch.imageTransform = mul(branch.imageTransform, transform);
    branch.identity = (branch.identity << 2u)
            | (transmission ? STABLE_PLANE_BRANCH_TRANSMISSION : STABLE_PLANE_BRANCH_REFLECTION);
    branch.transmitted |= transmission;
    return branch;
}

// Only this pixel's build invocation owns future plane records until guide export.
// The queued path occupies its endpoint; auxiliary fields occupy the future guide.
void branchEnqueue(uint2 pixel, uint2 extent, uint plane, StableBranch branch) {
    uint address = stablePlaneRecordIndex(pixel, extent, plane);
    pathSegmentStorage(worldBindings.pathQueueAddress, STABLE_PLANE_COUNT * extent.x * extent.y).store(address, branch.path);
    stablePlaneRecords(extent).storeQueuedBranch(address, branch.imageTransform, branch.motion, branch.length,
            branch.identity, branch.transmitted);
}

StableBranch branchDequeue(uint2 pixel, uint2 extent, uint plane) {
    uint address = stablePlaneRecordIndex(pixel, extent, plane);
    PathSegmentStorage endpoints = pathSegmentStorage(worldBindings.pathQueueAddress, STABLE_PLANE_COUNT * extent.x * extent.y);
    StableBranch branch;
    branch.path = endpoints.load(address);
    stablePlaneRecords(extent).loadQueuedBranch(address, branch.imageTransform, branch.motion, branch.length,
            branch.identity, branch.transmitted);
    // A terminal environment or over-budget branch must leave no valid fill endpoint.
    endpoints.storeFlags(address, 0u);
    return branch;
}

public void runBuildStablePlanes<TComposition : IComposition>() {
    uint2 pixel = DispatchRaysIndex().xy;
    uint2 extent = DispatchRaysDimensions().xy;
    WorldPush frame = ((ConstPtr<WorldPush>)worldBindings.worldPushAddress)[0];
    PathSegmentStorage endpoints = pathSegmentStorage(worldBindings.pathQueueAddress, STABLE_PLANE_COUNT * extent.x * extent.y);
    for (uint i = 0u; i < STABLE_PLANE_COUNT; i++) {
        endpoints.storeFlags(stablePlaneRecordIndex(pixel, extent, i), 0u);
    }
    StableBranch branch = {};
    branch.path = traceCamera<TComposition>(pixel, extent, frame);
    branch.imageTransform = float3x3(1, 0, 0, 0, 1, 0, 0, 0, 1);
    branch.identity = 1u;
    float3 cameraDirection = branch.path.direction;
    float coneSpread = traceCameraSpread(pixel, extent, frame);
    uint count = 1u;
    float3 stableRadiance = float3(0.0);
    [unroll]
    for (uint plane = 0u; plane < STABLE_PLANE_COUNT; plane++) {
        if (plane >= count) break;
        if (plane > 0u) branch = branchDequeue(pixel, extent, plane);
        float3 volumeRadiance = float3(0.0);
        float3 volumeEstimate = float3(0.0);
        for (uint step = 0u; step <= STABLE_PLANE_MAX_DELTA_SEGMENTS; step++) {
            WorldPayload intersection = {};
            intersection.seed = traceSeed(pixel, frame.frameIndex, plane);
            intersection.pathFlags = WORLD_PATH_GUIDE;
            RayDesc ray = traceRay(branch.path);
            uint mask = branch.path.depth == 0u ? WORLD_RAY_MASK_PRIMARY : WORLD_RAY_MASK_SECONDARY;
            TraceRay(worldAccelerationStructure(), RAY_FLAG_NONE, mask, 0u, 1u, 0u, ray, intersection);
            bool environment = (intersection.pathFlags & WORLD_PATH_MISS) != 0u;
            float distance = environment ? TRACE_MAX_DISTANCE : intersection.hitDistance;
            float3 transmission = exp(-branch.path.absorption * distance);
            // Restart retains endpoint attenuation so fill resumes at the surface.
            PackedPathSegment restart = branch.path;
            TraceSpatialMedium spatial = traceSpatialMediumSegment<TComposition>(branch.path, distance, pixel, true);
            volumeRadiance += branch.path.throughput * spatial.scattering;
            volumeEstimate += branch.path.throughput * (1.0 - spatial.transmittance);
            branch.path.throughput *= transmission * spatial.transmittance;
            restart.throughput = branch.path.throughput;
            branch.length += distance;
            // Material closures need not remain live across medium sampling and its shadow traversal.
            // Build follows only delta events, so the cone width is the camera spread times the unfolded length.
            WorldHit hit = resolveWorldHit<TComposition>(intersection, ray, 0.0, coneSpread * branch.length);
            if (plane == 0u && step == 0u) {
                // Camera-segment media stop at the physical boundary, before delta branches continue.
                float4 clip = mul(frame.curViewProj, float4(hit.position - frame.camOffset, 1.0));
                worldFloatStorageImage(worldBindings.resources.primaryDepth)[pixel] =
                        !environment && clip.w > 0.0 ? saturate(clip.z / clip.w) : 0.0;
            }
            stableRadiance += branch.path.throughput * (environment ? hit.radiance : hit.surface.emissionRadiance);
            TraceLobes lobes = {};
            if (!environment) lobes = traceLobes(branch.path, hit);
            bool reflectDelta = lobes.reflectionProbability > 0.0;
            bool transmitDelta = lobes.transmissionProbability > 0.0;
            bool continuous = lobes.continuousProbability > 0.0;
            uint deltaCount = uint(reflectDelta) + uint(transmitDelta);
            uint extra = continuous ? 1u : deltaCount > 0u ? deltaCount - 1u : 0u;
            bool split = !environment && deltaCount > 0u && count + extra <= STABLE_PLANE_COUNT
                    && branch.path.depth < STABLE_PLANE_MAX_DELTA_SEGMENTS && branch.path.depth <= frame.maxBounces;
            if (split) {
                if (continuous) {
                    // Keep the residual here and reserve capacity to follow the useful delta through another mixed face.
                    bool transmissionDominant = transmitDelta && (!reflectDelta
                            || traceLuminance(branch.path.throughput * lobes.transmission)
                                    >= traceLuminance(branch.path.throughput * lobes.reflection));
                    branchEnqueue(pixel, extent, count++, branchAdvance(branch, hit, lobes, transmissionDominant));
                    restart.flags |= transmissionDominant ? PATH_ENDPOINT_DELEGATED_TRANSMISSION
                            : PATH_ENDPOINT_DELEGATED_REFLECTION;
                } else {
                    // Follow transmission before queued reflections can consume the remaining planes.
                    if (reflectDelta && transmitDelta) branchEnqueue(pixel, extent, count++, branchAdvance(branch, hit, lobes, false));
                    branch = branchAdvance(branch, hit, lobes, transmitDelta);
                    continue;
                }
            }
            float retainedProbability;
            TraceLobes residual = traceResidualLobes(lobes, restart.flags, retainedProbability);
            StablePlaneRecord record = {};
            record.branchId = branch.identity;
            record.flags = (environment ? STABLE_PLANE_FLAG_ENVIRONMENT : STABLE_PLANE_FLAG_VALID)
                    | (branch.transmitted ? STABLE_PLANE_FLAG_TRANSMISSION : 0u)
                    | (min(branch.path.depth, STABLE_PLANE_DEPTH_MASK) << STABLE_PLANE_DEPTH_SHIFT);
            record.currentVirtualPosition = frame.camOffset + cameraDirection * branch.length;
            record.previousVirtualPosition = record.currentVirtualPosition + branch.motion
                    + (environment ? float3(0.0) : mul(branch.imageTransform, hit.previousPosition - hit.position));
            // The retained surface competes with delegated branches using only its residual share.
            // Terminal albedo must not make a dark transmitted object lose its geometry guides.
            record.throughputLuminance = traceLuminance(branch.path.throughput)
                    * retainedProbability;
            record.normal = environment ? -cameraDirection : normalize(mul(branch.imageTransform, hit.surface.shadingNormal));
            record.roughness = environment ? 1.0 : sqrt(hit.surface.alphaRoughness);
            if (!environment) {
                float3 diffuse, specular;
                surfaceDenoiserEstimates(hit.surface, -branch.path.direction, diffuse, specular);
                record.diffuseBsdfEstimate = diffuse * branch.path.throughput;
                record.specularBsdfEstimate = specular * branch.path.throughput;
                // Undelegated delta lobes remain in this endpoint's specular signal.
                record.specularBsdfEstimate += branch.path.throughput
                        * (residual.reflection + residual.transmission);
                restart.hitDistance = hit.hitDistance;
                if (branch.path.depth <= frame.maxBounces) {
                    uint address = stablePlaneRecordIndex(pixel, extent, plane);
                    endpoints.store(address, restart);
                }
            }
            record.noisyDiffuseRadiance = volumeRadiance;
            record.diffuseBsdfEstimate += volumeEstimate;
            if (environment && any(volumeEstimate > 0.0)) {
                record.flags = (record.flags & ~STABLE_PLANE_FLAG_ENVIRONMENT) | STABLE_PLANE_FLAG_VALID;
                float mediumDistance = branch.length - distance + max(spatial.scatteringDistance, traceEpsilon());
                record.currentVirtualPosition = frame.camOffset + cameraDirection * mediumDistance;
                record.previousVirtualPosition = record.currentVirtualPosition + branch.motion;
                record.diffuseHitDistance = spatial.scatteringDistance;
            }
            stablePlaneStoreRecord(pixel, extent, plane, record);
            break;
        }
    }
    for (uint i = count; i < STABLE_PLANE_COUNT; i++) {
        uint address = stablePlaneRecordIndex(pixel, extent, i);
        stablePlaneRecords(extent).storeBranchId(address, STABLE_PLANE_INVALID_BRANCH);
        stablePlaneRecords(extent).storeFlags(address, 0u);
    }
    worldFloat4StorageImage(worldBindings.resources.nrdStableRadiance)[pixel] = float4(stableRadiance, 1.0);
    stablePlaneExportGuides(pixel, extent, frame);
}

[shader("raygeneration")]
void main<TComposition : IComposition>() {
    runBuildStablePlanes<TComposition>();
}
