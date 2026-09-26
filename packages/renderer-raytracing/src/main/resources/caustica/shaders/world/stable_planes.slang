module stable_planes;
import bindings;
import world_minimal;
import world_common;
import stable_plane_types;
import nrd_signals;

public static const uint STABLE_PLANE_MAX_DELTA_SEGMENTS = 9u;
public StablePlaneStorage stablePlaneRecords(uint2 extent) {
    return stablePlaneStorage(worldBindings.stablePlaneBufferAddress, STABLE_PLANE_COUNT * extent.x * extent.y,
            ((ConstPtr<WorldPush>)worldBindings.worldPushAddress)[0].preExposure);
}
public uint stablePlaneRecordIndex(uint2 pixel, uint2 extent, uint plane) {
    return plane * extent.x * extent.y + pixel.y * extent.x + pixel.x;
}
public void stablePlaneStoreRecord(uint2 pixel, uint2 extent, uint plane, StablePlaneRecord record) {
    stablePlaneRecords(extent).store(stablePlaneRecordIndex(pixel, extent, plane), record);
}

// The dominant endpoint owns screen depth/motion; NRD consumes each plane's own endpoint separately.
// Build selects dominance and exports guides before local lighting and radiance fill consume them.
public void stablePlaneExportGuides(uint2 pixel, uint2 extent, WorldPush frame) {
    uint dominant = 0u, available = 0u;
    float largest = -1.0;
    float3 diffuseEstimate = float3(0.0), specularEstimate = float3(0.0);
    for (uint i = 0u; i < STABLE_PLANE_COUNT; i++) {
        uint address = stablePlaneRecordIndex(pixel, extent, i);
        if (stablePlaneRecords(extent).loadBranchId(address) == STABLE_PLANE_INVALID_BRANCH) continue;
        StablePlaneRecord record = stablePlaneRecords(extent).load(address);
        available++;
        if (record.throughputLuminance > largest) {
            largest = record.throughputLuminance;
            dominant = i;
        }
        diffuseEstimate += record.diffuseBsdfEstimate;
        specularEstimate += record.specularBsdfEstimate;
    }
    for (uint i = 0u; i < STABLE_PLANE_COUNT; i++) {
        uint address = stablePlaneRecordIndex(pixel, extent, i);
        uint flags = stablePlaneRecords(extent).loadFlags(address) & ~STABLE_PLANE_FLAG_DOMINANT;
        if (i == dominant) flags |= STABLE_PLANE_FLAG_DOMINANT;
        stablePlaneRecords(extent).storeFlags(address, flags);
    }
    StablePlaneRecord record = stablePlaneRecords(extent).load(stablePlaneRecordIndex(pixel, extent, dominant));
    bool valid = (record.flags & STABLE_PLANE_FLAG_VALID) != 0u;
    float4 current = mul(frame.curViewProj, float4(record.currentVirtualPosition - frame.camOffset, 1.0));
    float4 previous = mul(frame.prevViewProj,
            float4(record.previousVirtualPosition - frame.camOffset + frame.camDelta, 1.0));
    float2 motion = current.w > 0.0 && previous.w > 0.0
            ? (previous.xy / previous.w - current.xy / current.w) * 0.5 * float2(extent) : float2(0.0);
    worldFloatStorageImage(worldBindings.resources.depthGuide)[pixel] =
            valid && current.w > 0.0 ? saturate(current.z / current.w) : 0.0;
    worldFloatStorageImage(worldBindings.resources.nrdViewZ)[pixel] =
            valid ? max(abs(current.w), 1.0e-4) : NRD_INVALID_VIEW_Z;
    worldFloat2StorageImage(worldBindings.resources.motionGuide)[pixel] = motion;
    worldFloat2StorageImage(worldBindings.resources.specularMotionGuide)[pixel] = motion;
    worldFloat4StorageImage(worldBindings.resources.normalGuide)[pixel] =
            float4(record.normal, record.roughness);
    worldFloat4StorageImage(worldBindings.resources.albedoGuide)[pixel] =
            float4(diffuseEstimate, 0.0);
    worldFloat4StorageImage(worldBindings.resources.specularAlbedoGuide)[pixel] =
            float4(specularEstimate, 1.0);
    // A dominant sky endpoint describes no surface whose history RR could follow. Reached directly or only
    // through delta transmission it is fully responsive. Reflected sky shares its pixel with the reflector's
    // accumulated transport and keeps the floor like every other endpoint.
    bool historyFreeSky = (record.flags & STABLE_PLANE_FLAG_ENVIRONMENT) != 0u
            && !stablePlaneBranchReflects(record.branchId);
    worldFloatStorageImage(worldBindings.resources.responsivityGuide)[pixel] =
            historyFreeSky ? 1.0 : frame.responsivityFloor;
    worldFloat4StorageImage(worldBindings.resources.stablePlaneMetadataImage)[pixel] =
            float4(float(available) / 3.0, float(dominant) * 0.5,
                    float((record.flags >> STABLE_PLANE_DEPTH_SHIFT) & STABLE_PLANE_DEPTH_MASK)
                            / float(STABLE_PLANE_MAX_DELTA_SEGMENTS),
                    (record.flags & STABLE_PLANE_FLAG_TRANSMISSION) != 0u ? 1.0 : 0.0);
}

// Fill preserves build's dominant endpoint while combining radiance from every available plane.
public void stablePlaneExportSignals(uint2 pixel, uint2 extent, WorldPush frame) {
    uint dominant = 0u;
    float3 diffuse = float3(0.0), specular = float3(0.0);
    float3 diffuseEstimate = float3(0.0), specularEstimate = float3(0.0);
    for (uint i = 0u; i < STABLE_PLANE_COUNT; i++) {
        uint address = stablePlaneRecordIndex(pixel, extent, i);
        if (stablePlaneRecords(extent).loadBranchId(address) == STABLE_PLANE_INVALID_BRANCH) continue;
        StablePlaneRecord record = stablePlaneRecords(extent).load(address);
        if ((record.flags & STABLE_PLANE_FLAG_DOMINANT) != 0u) dominant = i;
        diffuse += record.noisyDiffuseRadiance;
        specular += record.noisySpecularRadiance;
        diffuseEstimate += record.diffuseBsdfEstimate;
        specularEstimate += record.specularBsdfEstimate;
    }
    StablePlaneRecord record = stablePlaneRecords(extent).load(stablePlaneRecordIndex(pixel, extent, dominant));
    bool valid = (record.flags & STABLE_PLANE_FLAG_VALID) != 0u;
    float4 current = mul(frame.curViewProj, float4(record.currentVirtualPosition - frame.camOffset, 1.0));
    float3 stable = worldFloat4StorageImage(worldBindings.resources.nrdStableRadiance)[pixel].xyz;
    worldFloat4StorageImage(worldBindings.resources.outputImage)[pixel] =
            float4(clamp((stable + diffuse + specular) * frame.preExposure, 0.0, 65504.0), 1.0);
    nrdWritePrimarySignals(pixel, valid, current.w, record.roughness,
            diffuse, specular, stable, record.diffuseHitDistance, record.specularHitDistance,
            diffuseEstimate, specularEstimate);
}
