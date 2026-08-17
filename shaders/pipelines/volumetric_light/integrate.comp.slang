// Froxel integration: one thread per screen tile walks its whole depth column front to back and turns
// per-cell (scattering, extinction) into (in-scattered radiance reaching the eye, transmittance to the eye).
//
// Each slice is integrated analytically rather than by point-sampling its centre. For constant
// scattering S and extinction sigma over a slice of thickness d, the light that leaves the slice toward
// the eye is (S - S*exp(-sigma*d)) / sigma, which stays correct when a slice is optically thick — the
// far slices of an exponential distribution always are, and a point sample there is what makes fog
// brighten or darken as the camera moves.
//
// The result is prefix-accumulated, so the composite can read the fog for ANY distance with one
// trilinear fetch instead of re-marching per pixel.
import display_common;
import froxel;
import integrate_bindings;

[[vk::push_constant]] VolumetricLightPush pc;

static const float HALF_MAX = 65504.0;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 dispatchId : SV_DispatchThreadID) {
    uint gridWidth, gridHeight, gridDepth;
    integratedImage.GetDimensions(gridWidth, gridHeight, gridDepth);
    if (dispatchId.x >= gridWidth || dispatchId.y >= gridHeight) {
        return;
    }

    float3 accumulated = float3(0.0);
    float transmittance = 1.0;
    float sliceStart = pc.nearDistance;
    for (uint slice = 0u; slice < gridDepth; ++slice) {
        uint3 cell = uint3(dispatchId.xy, slice);
        float4 sample = max(scatterImage[cell], float4(0.0));
        float sliceEnd = froxelSliceDistance(pc.nearDistance, pc.maxDistance,
                float(slice + 1u) / float(gridDepth));
        float thickness = max(sliceEnd - sliceStart, 1.0e-4);
        sliceStart = sliceEnd;

        float extinction = max(sample.a, 1.0e-6);
        float sliceTransmittance = exp(-extinction * thickness);
        float3 sliceScattering = (sample.rgb - sample.rgb * sliceTransmittance) / extinction;
        accumulated += sliceScattering * transmittance;
        transmittance *= sliceTransmittance;

        integratedImage[cell] = float4(min(accumulated, float3(HALF_MAX)), transmittance);
    }
}
