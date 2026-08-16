// Full-resolution composite of the integrated froxel volume over the reconstructed scene.
//
// One trilinear fetch per pixel: the pixel's own depth picks the grid coordinate, and the volume already
// holds the accumulated in-scattering and transmittance up to that point. Sky pixels integrate the whole
// column. Filtering across the grid's screen tiles and its exponential slices is what upsamples the
// volume smoothly, so the shafts arrive soft without a separate blur.
//
// Output stays in the source image's pre-exposed scene-linear ACEScg domain, ahead of exposure, bloom,
// and the shared SDR/HDR display transform.
import display_common;
import world_common;
import froxel;
import composite_bindings;

[[vk::push_constant]] VolumetricLightPush pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 dispatchId : SV_DispatchThreadID) {
    int2 pixel = int2(dispatchId.xy);
    uint width, height;
    outputImage.GetDimensions(width, height);
    if (pixel.x >= int(width) || pixel.y >= int(height)) {
        return;
    }

    WorldPush world = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    float2 uv = (float2(pixel) + 0.5) / float2(width, height);
    float3 scene = max(sourceImage[pixel].rgb, float3(0.0));

    // Reversed-Z guide: sky is the far endpoint at ~0 and carries the whole fog column. The guide is at
    // render resolution while this pass runs at display resolution, which the normalised fetch handles;
    // the volume is far smoother than the depth edge, so no depth-aware upsample is needed.
    float depth = depthImage.SampleLevel(uv, 0.0).r;
    float sceneDistance = depth <= 1.0e-6
            ? pc.maxDistance
            : length(froxelUnproject(world, uv, depth));

    uint gridWidth, gridHeight, gridDepth;
    volumeImage.GetDimensions(gridWidth, gridHeight, gridDepth);
    // Slice n holds the integral through its FAR boundary, so the value for a distance is half a slice
    // behind that distance's grid coordinate.
    float sliceCoord = froxelSliceCoord(pc.nearDistance, pc.maxDistance, sceneDistance)
            - 0.5 / float(max(gridDepth, 1u));
    float4 fog = volumeImage.SampleLevel(float3(uv, saturate(sliceCoord)), 0.0);

    float3 inScattering = max(fog.rgb, float3(0.0)) * max(pc.intensity, 0.0);
    float transmittance = clamp(fog.a, 0.0, 1.0);
    float3 result = scene * transmittance + inScattering;
    outputImage[pixel] = float4(clamp(result, float3(0.0), float3(HALF_MAX)), 1.0);
}
