// Half-resolution screen-space volumetric light scattering. This is the classic post-process
// "additive sampling" construction: walk from each output pixel toward the projected celestial source,
// accumulate visible source radiance, and attenuate successive samples. The current primary depth guide
// turns the post-RR scene into an occlusion mask without a second geometry pass.
//
// Running after DLSS Ray Reconstruction is intentional. Crepuscular shafts are synthetic screen-space
// features and therefore have no valid world motion; putting them into RR's history would smear them.
// Their full-resolution additive composite rejoins scene-linear ACEScg before exposure, bloom, and the
// common SDR/HDR display transform.
import display_common;
import world_common;
import sky;
import scatter_bindings;

[[vk::push_constant]] VolumetricLightPush pc;

static const float3 BT709_LUMA = float3(0.2126, 0.7152, 0.0722);
static const float SOURCE_INNER_RADIUS = 0.015;
static const float SOURCE_OUTER_RADIUS = 0.38;
static const float OFFSCREEN_FADE_DISTANCE = 0.30;
static const uint MAX_SAMPLES = 64u;

float aspectDistance(float2 a, float2 b, float aspect) {
    return length((a - b) * float2(aspect, 1.0));
}

float skyVisibility(float2 uv) {
    // The guide stores reversed-Z hardware depth: sky is the far endpoint at approximately zero. The
    // point-filtered descriptor keeps an opaque silhouette opaque instead of interpolating it with sky.
    float depth = depthImage.SampleLevel(uv, 0.0).r;
    return depth <= 1.0e-6 ? 1.0 : 0.0;
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 dispatchId : SV_DispatchThreadID) {
    int2 pixel = int2(dispatchId.xy);
    uint width, height;
    scatterImage.GetDimensions(width, height);
    if (pixel.x >= int(width) || pixel.y >= int(height)) {
        return;
    }

    float4 result = float4(0.0, 0.0, 0.0, 1.0);
    WorldPush world = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    // The underwater camera path has no physically meaningful sky escape. Avoid turning an open streamed
    // water volume or a guide miss into shafts from a source that the radiance path intentionally hides.
    if ((world.flags & 1u) != 0u || pc.intensity <= 0.0) {
        scatterImage[pixel] = result;
        return;
    }

    SkyState sky = skyState(world);
    CelestialLight light = dominantCelestialLight(sky, transmittanceLut);
    float lightLevel = dot(light.illuminance, BT709_LUMA);

    // Project the same world direction used by NEE and the visible celestial disc. w=0 makes this a
    // direction at infinity; curViewProj already contains Minecraft's camera rotation and view bobbing.
    float4 lightClip = mul(world.curViewProj, float4(light.dir, 0.0));
    if (lightLevel <= 1.0e-5 || lightClip.w <= 1.0e-5) {
        scatterImage[pixel] = result;
        return;
    }
    float2 lightUv = lightClip.xy / lightClip.w * 0.5 + 0.5;
    float aspect = float(width) / max(float(height), 1.0);
    float2 nearestOnScreen = clamp(lightUv, float2(0.0), float2(1.0));
    float offscreenDistance = aspectDistance(lightUv, nearestOnScreen, aspect);
    float sourceVisibility = 1.0 - smoothstep(0.0, OFFSCREEN_FADE_DISTANCE, offscreenDistance);
    if (sourceVisibility <= 0.0) {
        scatterImage[pixel] = result;
        return;
    }

    float2 uv = (float2(pixel) + 0.5) / float2(width, height);
    uint samples = min(max(pc.sampleCount, 1u), MAX_SAMPLES);
    float2 stepUv = (uv - lightUv) * (max(pc.density, 0.0) / float(samples));
    float2 sampleUv = uv;
    float illuminationDecay = 1.0;
    float3 accumulated = float3(0.0);
    float normalization = 0.0;

    for (uint i = 0u; i < samples; ++i) {
        sampleUv -= stepUv;
        bool inFrame = all(sampleUv >= float2(0.0)) && all(sampleUv <= float2(1.0));
        if (inFrame) {
            float distanceToSource = aspectDistance(sampleUv, lightUv, aspect);
            float sourceEnvelope = 1.0 - smoothstep(
                    SOURCE_INNER_RADIUS, SOURCE_OUTER_RADIUS, distanceToSource);
            float sampleWeight = sourceEnvelope * illuminationDecay;
            if (sampleWeight > 0.0) {
                float visible = skyVisibility(sampleUv);
                float3 source = max(sourceImage.SampleLevel(sampleUv, 0.0).rgb, float3(0.0));
                source = min(source, float3(HALF_MAX));
                accumulated += source * (visible * sampleWeight);
                // Occluded taps deliberately remain in the denominator: their missing energy is what
                // turns silhouettes and gaps into dark/light shafts instead of renormalizing them away.
                normalization += sampleWeight;
            }
        }
        illuminationDecay *= clamp(pc.decay, 0.0, 1.0);
    }

    float3 scattering = normalization > 1.0e-6
            ? accumulated * (pc.intensity * sourceVisibility / normalization)
            : float3(0.0);
    scatterImage[pixel] = float4(clamp(scattering, float3(0.0), float3(HALF_MAX)), 1.0);
}
