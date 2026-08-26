// Strided log2-luminance histogram over the post-RR trace color. Metering after RR avoids the
// Jensen bias that Monte-Carlo noise introduces into the logarithmic average.

import display_common;
import bindings;

[[vk::push_constant]] ExposureHistPush pc;

static const float LOG_MIN = -12.0;
static const float LOG_MAX = 12.0;
static const float INV_LOG_RANGE = 1.0 / (LOG_MAX - LOG_MIN);
static const float3 ACESCG_LUMA = float3(0.27222872, 0.67408177, 0.05368952);

// One 16x16 workgroup == 256 threads == one thread per luminance bin. Each thread initializes and
// flushes all three population bins. Per-pixel atomics stay in shared memory; only non-empty bins
// pay global atomics.
groupshared uint localBins[768];

[shader("compute")]
[numthreads(16, 16, 1)]
void main(uint3 dispatchId : SV_DispatchThreadID, uint groupIndex : SV_GroupIndex) {
    localBins[groupIndex] = 0u;
    localBins[256u + groupIndex] = 0u;
    localBins[512u + groupIndex] = 0u;
    GroupMemoryBarrierWithGroupSync();

    uint stride = max(pc.stride, 1u);
    int2 pix = int2(dispatchId.xy * stride);
    uint w, h;
    colorImage.GetDimensions(w, h);
    if (pix.x < int(w) && pix.y < int(h)) {
        float3 rgb = colorImage[pix].rgb;
        // Invalid radiance is not a meaningful metering sample. Skipping it is safe because resolve
        // derives its population from the bins instead of assuming image area.
        if (all(isfinite(rgb))) {
            rgb = max(rgb, float3(0.0));
            // The reconstructed scene image is linear ACEScg; meter it in the transport basis.
            float lum = dot(rgb, ACESCG_LUMA);
            float logLum = clamp(log2(max(lum, 1.0e-5)), LOG_MIN, LOG_MAX);
            uint bin = min(uint(floor((logLum - LOG_MIN) * INV_LOG_RANGE * 256.0)), 255u);

            float2 uv = (float2(pix) + 0.5) / float2(w, h);
            float2 centered = uv * 2.0 - 1.0;
            float sigma = max(pc.centerWeightSigma, 0.01);
            float gaussian = exp(-0.5 * dot(centered, centered) / (sigma * sigma));
            float weight = lerp(clamp(pc.centerWeightFloor, 0.0, 1.0), 1.0, gaussian);
            uint fixedWeight = uint(round(weight * 256.0));

            uint depthW, depthH;
            depthImage.GetDimensions(depthW, depthH);
            int2 guidePix = min(int2(uv * float2(depthW, depthH)), int2(depthW, depthH) - 1);
            bool sky = depthImage[guidePix] <= 1.0e-6;
            bool emissive = !sky && albedoImage[guidePix].a > 0.5;
            uint populationOffset = sky ? 256u : (emissive ? 512u : 0u);
            uint weightedBin = bin + populationOffset;
            if (fixedWeight > 0u) {
                uint unused;
                InterlockedAdd(localBins[weightedBin], fixedWeight, unused);
            }
        }
    }

    GroupMemoryBarrierWithGroupSync();
    uint surfaceCount = localBins[groupIndex];
    if (surfaceCount > 0u) {
        uint unusedSurface;
        InterlockedAdd(histBins[groupIndex], surfaceCount, unusedSurface);
    }
    uint skyCount = localBins[256u + groupIndex];
    if (skyCount > 0u) {
        uint unusedSky;
        InterlockedAdd(histBins[256u + groupIndex], skyCount, unusedSky);
    }
    uint emissiveCount = localBins[512u + groupIndex];
    if (emissiveCount > 0u) {
        uint unusedEmissive;
        InterlockedAdd(histBins[512u + groupIndex], emissiveCount, unusedEmissive);
    }
}
