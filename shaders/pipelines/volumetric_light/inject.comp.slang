// Froxel injection: per-cell in-scattered radiance and extinction, temporally accumulated.
//
// Each froxel evaluates the medium once at a jittered depth inside its own slice, asks the primary depth
// guide whether the dominant celestial light reaches that point, and stores
// (scattered radiance per unit length, extinction). Nothing here samples the rendered frame, so a shaft
// never inherits the shape of the drawn sun, moon, or star field, and the light needs no on-screen
// position at all — an off-screen or behind-the-camera sun casts the same shafts as one in view.
//
// The pass runs after DLSS Ray Reconstruction, so it also owns its own temporal filter: this frame's
// jittered sample is blended into the previous grid reprojected through prevViewProj. Successive frames
// therefore integrate different depths and different shadow offsets within each cell, which is what turns
// an affordable slice count into smooth shafts instead of visible layers.
import display_common;
import world_common;
import sky;
import froxel;
import inject_bindings;

[[vk::push_constant]] VolumetricLightPush pc;

static const float3 BT709_LUMA = float3(0.2126, 0.7152, 0.0722);
static const uint VOLUMETRIC_FLAG_TRACE_OCCLUSION = 1u;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 dispatchId : SV_DispatchThreadID) {
    uint gridWidth, gridHeight, gridDepth;
    scatterImage.GetDimensions(gridWidth, gridHeight, gridDepth);
    if (any(dispatchId >= uint3(gridWidth, gridHeight, gridDepth))) {
        return;
    }

    WorldPush world = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    bool underwater = (world.flags & 1u) != 0u;

    float2 uv = (float2(dispatchId.xy) + 0.5) / float2(gridWidth, gridHeight);
    float sliceJitter = froxelSliceJitter(pc.frameIndex);
    float cellNoise = froxelHash(dispatchId, pc.frameIndex);
    float sliceCoord = (float(dispatchId.z) + sliceJitter) / float(gridDepth);
    float sampleDistance = froxelSliceDistance(pc.nearDistance, pc.maxDistance, sliceCoord);
    float3 viewDir = froxelViewDirection(world, uv);
    float3 posCamRel = viewDir * sampleDistance;

    float density = froxelDensity(pc, world, posCamRel, underwater);
    // Extinction is scalar so the integration pass can carry one transmittance per froxel; water's
    // per-channel colour rides on the scattered radiance instead (see froxelUnderwaterLight).
    //
    // Submerged, extinction is the added scattering ONLY. The path tracer already walked the camera's
    // water medium with the biome tint's Beer-Lambert absorption, so counting that absorption again here
    // would darken every submerged pixel twice.
    float extinction = underwater ? density : density * (1.0 + FROXEL_AIR_ABSORPTION);

    float3 scattered = float3(0.0);
    if (density > 0.0) {
        SkyState frameSky = skyState(world);
        CelestialLight light = dominantCelestialLight(frameSky, transmittanceLut);
        float3 lightDir = light.dir;
        float3 illuminance = light.illuminance;
        float anisotropy = pc.anisotropy;
        if (underwater) {
            froxelUnderwaterLight(world, posCamRel, lightDir, illuminance);
            anisotropy = FROXEL_WATER_ANISOTROPY;
        }
        if (dot(illuminance, BT709_LUMA) > 1.0e-5) {
            float shadow;
            if ((pc.flags & VOLUMETRIC_FLAG_TRACE_OCCLUSION) != 0u) {
                // One ray per froxel per frame, jittered across the light's disc. The grid's own temporal
                // filter integrates those samples, so the penumbra widens with occluder distance the way
                // a real one does instead of costing a ray per sample.
                float2 discSample = float2(cellNoise, froxelHash(dispatchId, pc.frameIndex + 0x9E3779B9u));
                float3 traceDir = froxelSampleLightCone(lightDir, light.halfAngle, discSample);
                shadow = froxelTracedVisibility(worldTlas, posCamRel + world.camOffset,
                        traceDir, FROXEL_RAY_REACH);
            } else {
                shadow = froxelShadow(world, pc, depthImage, posCamRel, lightDir, cellNoise);
            }
            float phase = froxelPhase(dot(lightDir, viewDir), anisotropy);
            // Ambient stands in for sky and multiple scattering, which have no directional shadow: it is
            // the small unshadowed term that keeps deep shadow from reading as vacuum.
            scattered = density * illuminance * (phase * shadow + pc.ambient * ISOTROPIC_PHASE);
        }
    }

    // Stored pre-exposed, matching the scene image's domain and keeping fp16 values near unity.
    float4 current = float4(scattered * world.preExposure, extinction);

    // Reprojection: camDelta takes a current camera-relative point back into the previous frame's camera
    // space, exactly as the guide motion vectors do. Both the previous screen position and the previous
    // grid depth have to be inside the old frustum for the history to mean anything.
    float blend = clamp(pc.temporalBlend, 0.0, 1.0);
    float3 prevPos = posCamRel + world.camDelta;
    float4 prevClip = mul(world.prevViewProj, float4(prevPos, 1.0));
    if (blend < 1.0 && prevClip.w > 1.0e-5) {
        float2 prevUv = prevClip.xy / prevClip.w * 0.5 + 0.5;
        float prevDistance = length(prevPos);
        if (all(prevUv >= float2(0.0)) && all(prevUv <= float2(1.0))
                && prevDistance >= pc.nearDistance && prevDistance <= pc.maxDistance) {
            float prevSlice = froxelSliceCoord(pc.nearDistance, pc.maxDistance, prevDistance);
            float4 history = historyImage.SampleLevel(float3(prevUv, prevSlice), 0.0);
            history.rgb *= pc.historyScale;
            if (all(isfinite(history))) {
                current = lerp(max(history, float4(0.0)), current, blend);
            }
        }
    }

    scatterImage[dispatchId] = min(current, float4(HALF_MAX));
}
