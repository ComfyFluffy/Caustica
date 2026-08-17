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

    uint gridWidth, gridHeight, gridDepth;
    volumeImage.GetDimensions(gridWidth, gridHeight, gridDepth);
    // Slice n holds the integral through its FAR boundary, so the value for a distance is half a slice
    // behind that distance's grid coordinate.
    float sliceBias = 0.5 / float(max(gridDepth, 1u));

    // The depth guide is at render resolution while this pass runs at display resolution, so a silhouette
    // falls between guide texels. Resolve it by evaluating the volume at each of the four neighbouring
    // depths and blending THOSE, never by blending the depths themselves: reversed-Z is hyperbolic, so an
    // interpolated depth is a distance that belongs to neither surface — a block three blocks away and a
    // background sixty blocks away average to about six — which ringed every silhouette with a rim of
    // far-too-short fog that flickered as the camera moved a fraction of a pixel.
    //
    // Where the four agree, which is everywhere except an edge, this is exactly a single tap; where they
    // do not, it is a monotone ramp across one guide texel, and it is deterministic, so it cannot shimmer.
    uint guideWidth, guideHeight;
    depthImage.GetDimensions(guideWidth, guideHeight);
    float2 guideSize = float2(guideWidth, guideHeight);
    float2 guideTexel = uv * guideSize - 0.5;
    float2 guideBase = floor(guideTexel);
    float2 guideFrac = guideTexel - guideBase;

    float4 fog = float4(0.0);
    for (int corner = 0; corner < 4; ++corner) {
        float2 offset = float2(float(corner & 1), float((corner >> 1) & 1));
        float weight = lerp(1.0 - guideFrac.x, guideFrac.x, offset.x)
                * lerp(1.0 - guideFrac.y, guideFrac.y, offset.y);
        if (weight <= 0.0) {
            continue;
        }
        float2 guideUv = (guideBase + offset + 0.5) / guideSize;
        // Reversed-Z guide: sky is the far endpoint at ~0 and carries the whole fog column.
        float depth = depthImage.SampleLevel(guideUv, 0.0).r;
        float sceneDistance = depth <= 1.0e-6
                ? pc.maxDistance
                : length(froxelUnproject(world, guideUv, depth));
        // Only the slice coordinate varies between corners; the column itself is this pixel's own.
        float sliceCoord = froxelSliceCoord(pc.nearDistance, pc.maxDistance, sceneDistance) - sliceBias;
        fog += weight * volumeImage.SampleLevel(float3(uv, saturate(sliceCoord)), 0.0);
    }

    float3 inScattering = max(fog.rgb, float3(0.0)) * max(pc.intensity, 0.0);
    float transmittance = clamp(fog.a, 0.0, 1.0);
    float3 result = scene * transmittance + inScattering;
    outputImage[pixel] = float4(clamp(result, float3(0.0), float3(HALF_MAX)), 1.0);
}
