// Full-resolution additive composite for the half-resolution crepuscular-ray signal. The shafts are
// intentionally soft, so bilinear upsampling is sufficient and avoids paying radial-sampling cost at
// display resolution. Output stays in the source image's pre-exposed scene-linear ACEScg domain.
import composite_bindings;

static const float HALF_MAX = 65504.0;

[shader("compute")]
[numthreads(16, 16, 1)]
void main(uint3 dispatchId : SV_DispatchThreadID) {
    int2 pixel = int2(dispatchId.xy);
    uint width, height;
    outputImage.GetDimensions(width, height);
    if (pixel.x >= int(width) || pixel.y >= int(height)) {
        return;
    }

    float2 uv = (float2(pixel) + 0.5) / float2(width, height);
    float3 scene = max(sourceImage[pixel].rgb, float3(0.0));
    float3 scattering = max(scatterImage.SampleLevel(uv, 0.0).rgb, float3(0.0));
    outputImage[pixel] = float4(clamp(scene + scattering, float3(0.0), float3(HALF_MAX)), 1.0);
}
