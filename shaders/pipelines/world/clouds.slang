// Raymarched volumetric cloud layer. Depends on sky and bindings.
//
// A single cumulus deck between cloudBottom and cloudBottom+thickness, marched front to back and lit by
// whichever celestial body dominates, through the same transmittance LUT the terrain and the sun disc
// use. Output is linear BT.709 (sky.slang's working space), returned as a scattered-radiance plus
// transmittance pair so the caller composites the deck over the sky it already computed.
//
// Density is procedural rather than textured. A 3D noise texture would be the faster answer per sample,
// but it costs a descriptor binding, a CPU-side bake, and a resource lifetime to manage in RtComposite;
// the arithmetic version keeps the entire feature inside two shader files plus eight push floats. If this
// ever becomes the frame's bottleneck, the density function is the single thing to move to a texture and
// nothing else here changes.
//
// Shape is the standard cumulus recipe: a low-frequency fBm establishes the base shape, coverage remaps
// it so raising coverage grows existing clouds outward instead of fading a uniform haze up from zero, a
// height gradient rounds the bottom and flattens-then-billows the top, and a high-frequency fBm erodes
// the edges so silhouettes are wispy rather than blobby.
//
// Lighting is dual-lobe Henyey-Greenstein — a strong forward lobe for the silver lining when you look
// toward the sun, a weak backward lobe so the deck does not go flat when you look away — with a Beer's
// law transmittance along a short march toward the light, and the "powder" term that reproduces the dark
// edge on the lit side of a cloud that pure Beer's law gets wrong.
//
// COST WARNING, and the reason for the gate in sky.rmiss: this is a nested march, ~64 view samples each
// spawning ~6 light samples, each of those evaluating several octaves of noise. That is affordable once
// per pixel on a primary ray. It is not affordable on every diffuse bounce's miss, which is why the caller
// runs it only for rays that may see the celestial discs — the primary ray and specular/dielectric
// continuations. Diffuse bounces see the unclouded sky, which loses the deck's contribution to bounce
// lighting; that is a deliberate trade, not an oversight.

import world_common;
import sky;
import bindings;

// Base step counts, scaled at runtime by the quality setting. 64/6 is the reference quality; halving
// the view steps is close to free visually on a moving camera because the deck is smooth and the
// denoiser sees it as low-frequency, while halving the light steps costs more than it saves — the
// light march is what produces the shading gradient across a cloud.
public static const int CLOUD_VIEW_STEPS  = 64;
public static const int CLOUD_LIGHT_STEPS = 6;
// Marching past this is wasted work: at the deck's grazing angles the transmittance cutoff below always
// trips first, and the noise domain loses fp32 precision long before this.
public static const float CLOUD_MAX_MARCH = 60000.0;
// Front-to-back early out. 0.01 is ~6.6 stops down, far under the ACES 2.0 output transform's ability to
// show a difference.
public static const float CLOUD_MIN_TRANSMITTANCE = 0.01;

public static const float CLOUD_HG_FORWARD  = 0.80;
public static const float CLOUD_HG_BACKWARD = -0.25;
public static const float CLOUD_HG_MIX      = 0.60;

public struct CloudParams {
    public float coverage;     // 0 clear .. 1 overcast. 0 disables the whole module.
    public float density;      // extinction per block inside fully dense cloud
    public float bottomY;      // deck base, rebased world Y
    public float thickness;    // deck depth in blocks
    public float2 wind;        // domain offset in blocks, advanced on the CPU so it is frame-rate independent
    public float detail;       // 0..1 erosion strength
    public float featureSize;  // blocks per noise period; smaller means more, tighter cloud masses
    public float ambient;      // fraction of zenith sky scattered in as multiple-scattering stand-in
    public float quality;      // scales the march step counts; 1 is reference, 0.5 is half the samples
};

public CloudParams makeCloudParams(WorldPush push) {
    CloudParams c;
    c.coverage = clamp(push.cloud0.x, 0.0, 1.0);
    c.density = max(push.cloud0.y, 0.0);
    c.bottomY = push.cloud0.z;
    c.thickness = max(push.cloud0.w, 1.0);
    c.wind = push.cloud1.xy;
    c.detail = clamp(push.cloud1.z, 0.0, 1.0);
    c.ambient = max(push.cloud1.w, 0.0);
    c.featureSize = max(push.cloud2.w, 1.0);
    c.quality = clamp(push.cloud3.x, 0.1, 1.0);
    return c;
}

public bool cloudsActive(CloudParams c) {
    return c.coverage > 0.0 && c.density > 0.0;
}

// ---- Noise -------------------------------------------------------------------------------------------
// Value noise on a hashed integer lattice. Chosen over gradient noise because clouds want the blobby,
// isotropic character value noise already has, and it is roughly half the ALU.

// Value-noise helpers removed: the base and detail fields are now baked into cloudNoiseTex
// (RtCloudNoise), so nothing evaluates them at runtime any more.

// ---- Density -----------------------------------------------------------------------------------------

// Vertical profile: a soft rounded base and a wider billowing top, both zero at the deck boundaries so the
// march never terminates against a hard slab face.
float cloudHeightProfile(float relativeHeight) {
    float h = clamp(relativeHeight, 0.0, 1.0);
    float base = smoothstep(0.0, 0.22, h);
    float top = 1.0 - smoothstep(0.55, 1.0, h);
    return base * top;
}

public float cloudDensity(CloudParams c, float3 position) {
    float relativeHeight = (position.y - c.bottomY) / c.thickness;
    if (relativeHeight <= 0.0 || relativeHeight >= 1.0) {
        return 0.0;
    }
    float profile = cloudHeightProfile(relativeHeight);
    if (profile <= 0.0) {
        return 0.0;
    }

    // Base shape. Horizontal scale is coarser than vertical: cumulus fields are wide and shallow, and
    // isotropic noise reads as fog banks rather than clouds.
    //
    // FEATURE SIZE IS CONFIGURABLE, and the original hardcoded 0.0012 was badly wrong: one noise period
    // spanned ~830 blocks, so an entire render distance held two or three cloud masses, and wind at
    // 1.5 blocks/second moved the field 0.2% of a feature width per second — visually frozen. "Almost
    // no clouds" and "clouds do not move" were one mistake, not two.
    float baseFrequency = 1.0 / max(c.featureSize, 1.0);
    float3 domain = float3(position.x + c.wind.x, position.y, position.z + c.wind.y);
    float3 baseCoord = float3(domain.x * baseFrequency,
                              domain.y * baseFrequency * 2.9,
                              domain.z * baseFrequency);
    // One filtered fetch instead of four octaves of hashed value noise. The texture tiles, so the
    // repeat address mode does the domain wrapping for free and the coordinate needs no fract().
    float4 noise = cloudNoiseTex.SampleLevel(baseCoord, 0.0);
    float shape = noise.x;

    // Coverage as a threshold remap, not a multiply. Multiplying would raise a flat haze everywhere as
    // coverage rises; subtracting a threshold and renormalizing grows the clouds that exist outward from
    // their cores, which is what an advancing front actually looks like.
    float threshold = 1.0 - c.coverage;
    float shaped = saturate((shape - threshold) / max(1.0 - threshold, 1.0e-3));
    shaped *= profile;
    if (shaped <= 0.0) {
        return 0.0;
    }

    // Erosion, applied as an inward carve whose strength falls off with density. Eroding the core as hard
    // as the edge would punch holes through the middle of every cloud.
    if (c.detail > 0.0) {
        // Detail rides at 10x the base frequency so it scales with feature size rather than staying
        // fixed while the base changes underneath it. Second fetch rather than reusing the first
        // texel's G: the two fields are sampled at different frequencies, which is the point.
        float detailFrequency = baseFrequency * 10.0;
        float3 detailCoord = float3(domain.x * detailFrequency,
                                    domain.y * detailFrequency * 1.7,
                                    domain.z * detailFrequency);
        float erosion = 1.0 - cloudNoiseTex.SampleLevel(detailCoord, 0.0).y;
        float strength = c.detail * (1.0 - shaped);
        shaped = saturate(shaped - erosion * erosion * strength);
    }

    return shaped * c.density;
}

// ---- Ground shadowing ---------------------------------------------------------------------------------
//
// The deck is marched only on celestial-visible rays, so without this the world underneath is lit as if
// the sky were clear: an overcast noon stays as bright as a clear one, which is the single most obviously
// wrong thing about a cloud layer that only exists in the sky.
//
// This is a separate, deliberately coarse march along the shadow ray. Two departures from the view march,
// both intentional:
//
//   * two octaves, no erosion. A cloud shadow reaching the ground has been diffused through hundreds of
//     blocks of forward scattering; the deck's fine edge structure is simply not present in it. Marching
//     the detail octaves here would cost more and produce a crisper shadow than physics allows.
//   * a transmittance floor. Beer's law through a dense deck goes to near zero, but real overcast ground
//     is not black — it is lit by light that scattered through the deck and arrives diffusely. That
//     multiple-scattering path is not modelled, so the floor stands in for it. Without it, storms
//     produce a world lit only by block light, which looks broken rather than dark.

public static const int CLOUD_SHADOW_STEPS = 8;

float cloudDensityCoarse(CloudParams c, float3 position) {
    float relativeHeight = (position.y - c.bottomY) / c.thickness;
    if (relativeHeight <= 0.0 || relativeHeight >= 1.0) {
        return 0.0;
    }
    float3 domain = float3(position.x + c.wind.x, position.y, position.z + c.wind.y);
    float baseFrequency = 1.0 / max(c.featureSize, 1.0);
    float shape = cloudNoiseTex.SampleLevel(float3(domain.x * baseFrequency,
                                                  domain.y * baseFrequency * 2.9,
                                                  domain.z * baseFrequency), 0.0).x;
    float threshold = 1.0 - c.coverage;
    return saturate((shape - threshold) / max(1.0 - threshold, 1.0e-3))
            * cloudHeightProfile(relativeHeight) * c.density;
}

// Fraction of celestial light reaching `position` through the deck. `strength` scales the optical depth;
// `floorValue` is the multiple-scattering floor described above.
public float cloudShadowTransmittance(CloudParams c, float3 position, float3 lightDir,
                                      float strength, float floorValue) {
    if (!cloudsActive(c) || strength <= 0.0) {
        return 1.0;
    }
    // A receiver above the deck is not shadowed by it, and a light below the horizon contributes nothing
    // worth marching for.
    if (position.y >= c.bottomY + c.thickness || lightDir.y <= 0.01) {
        return 1.0;
    }
    float tNear;
    float tFar;
    if (!cloudSlabRange(c, position, lightDir, tNear, tFar)) {
        return 1.0;
    }
    // Cap the slant path: at low sun the geometric distance through the deck grows without bound, and an
    // unclamped march makes the world snap to the shadow floor for the last few minutes before sunset.
    float span = min(tFar - tNear, c.thickness * 4.0);
    float step = span / float(CLOUD_SHADOW_STEPS);
    float opticalDepth = 0.0;
    float t = tNear + step * 0.5;
    for (int i = 0; i < CLOUD_SHADOW_STEPS; ++i) {
        opticalDepth += cloudDensityCoarse(c, position + lightDir * t) * step;
        t += step;
    }
    return lerp(clamp(floorValue, 0.0, 1.0), 1.0, exp(-opticalDepth * strength));
}

// ---- Rain wetness -------------------------------------------------------------------------------------
//
// A wet surface is not just a darker surface. Two things happen physically, and doing only the first is
// the usual mistake:
//
//   1. A water film fills the surface's microscopic pores. Light entering the film is trapped by total
//      internal reflection at the film's top and bounces inside until it is absorbed, so less of it comes
//      back out — the surface darkens. Porous materials (dirt, wool, sand) darken far more than sealed
//      ones (glass, metal), which is why the effect scales with roughness here: in the absence of a
//      porosity channel, authored roughness is the best available proxy for it.
//
//   2. The film's own top surface is smooth, so the material gains a sharp specular lobe it did not have
//      dry. This is what actually reads as "wet" — the darkening alone just looks like a texture swap.
//
// Applied only to upward-facing surfaces: rain lands on horizontal faces, runs off vertical ones, and
// never reaches undersides.

public static const float WETNESS_DARKENING = 0.45;   // strongest albedo loss, for a fully porous surface
public static const float WETNESS_SMOOTHNESS = 0.15;  // roughness a fully wet surface tends toward

// `skyExposure` is the caller's upward visibility test — rain does not reach a surface under a roof.
public void applyWetness(float wetness, float skyExposure, float3 normal,
                         inout float3 albedo, inout float rough) {
    float wet = clamp(wetness, 0.0, 1.0) * clamp(skyExposure, 0.0, 1.0);
    // Fade over the upper hemisphere rather than a hard n.y > 0 test, so a sloped face does not show a
    // seam against the flat one next to it.
    wet *= smoothstep(0.0, 0.35, normal.y);
    if (wet <= 0.0) {
        return;
    }
    // Roughness stands in for porosity: a rough surface has pores to fill, a polished one does not.
    float porosity = rough;
    albedo *= 1.0 - WETNESS_DARKENING * porosity * wet;
    // Toward, not to: even a wet gravel path keeps some of its own scatter, and driving roughness to the
    // film value outright turns every wet surface into a mirror.
    rough = lerp(rough, max(WETNESS_SMOOTHNESS, rough * 0.25), wet);
}

// ---- Phase and light march ---------------------------------------------------------------------------

float cloudHenyeyGreenstein(float cosTheta, float g) {
    float gg = g * g;
    float denom = 1.0 + gg - 2.0 * g * cosTheta;
    return (1.0 - gg) / (4.0 * SKY_PI * max(denom * sqrt(max(denom, 1.0e-6)), 1.0e-6));
}

float cloudPhase(float cosTheta) {
    return lerp(cloudHenyeyGreenstein(cosTheta, CLOUD_HG_BACKWARD),
                cloudHenyeyGreenstein(cosTheta, CLOUD_HG_FORWARD), CLOUD_HG_MIX);
}

// Optical depth from a point toward the light. Steps grow geometrically: near samples decide the visible
// shading gradient, far ones only need to establish that the light is or is not deeply buried.
float cloudLightOpticalDepth(CloudParams c, float3 position, float3 lightDir) {
    float opticalDepth = 0.0;
    float step = max(c.thickness * 0.08, 1.0);
    float travelled = 0.0;
    int lightSteps = int(max(float(CLOUD_LIGHT_STEPS) * c.quality, 3.0));
    for (int i = 0; i < lightSteps; ++i) {
        travelled += step;
        opticalDepth += cloudDensity(c, position + lightDir * travelled) * step;
        step *= 1.5;
    }
    return opticalDepth;
}

// Beer's law with the powder correction. Pure Beer's law makes a cloud brightest exactly where it is
// densest on the lit side, which is backwards: light entering a dense boundary scatters back out of the
// surface it entered through, leaving a characteristic dark rim. The (1 - e^-2d) factor restores it.
float cloudBeerPowder(float opticalDepth) {
    float beer = exp(-opticalDepth);
    float powder = 1.0 - exp(-opticalDepth * 2.0);
    return beer * lerp(1.0, powder * 2.0, 0.5);
}

// ---- Slab intersection -------------------------------------------------------------------------------

// Returns false when the ray never meets the deck: below it looking down, above it looking up, or level
// enough to never cross either plane.
bool cloudSlabRange(CloudParams c, float3 origin, float3 direction, out float tNear, out float tFar) {
    tNear = 0.0;
    tFar = 0.0;
    float bottom = c.bottomY;
    float top = c.bottomY + c.thickness;
    if (abs(direction.y) < 1.0e-5) {
        if (origin.y <= bottom || origin.y >= top) {
            return false;
        }
        tNear = 0.0;
        tFar = CLOUD_MAX_MARCH;
        return true;
    }
    float t0 = (bottom - origin.y) / direction.y;
    float t1 = (top - origin.y) / direction.y;
    tNear = max(min(t0, t1), 0.0);
    tFar = min(max(t0, t1), CLOUD_MAX_MARCH);
    return tFar > tNear;
}

// ---- March -------------------------------------------------------------------------------------------

public struct CloudResult {
    public float3 scattering;   // linear BT.709 radiance scattered toward the viewer
    public float transmittance; // what fraction of the sky behind the deck survives
};

// Vanilla's CLOUD_COLOR for the camera's position, applied hue-only. Same reasoning as the fog tint:
// preserving the deck's own luminance means a dark authored cloud colour cannot black out a sunlit
// deck, and the time-of-day response computed from the atmosphere survives the tint.
float3 tintCloud(float3 scattering, float3 tint709, float strength) {
    float clamped = clamp(strength, 0.0, 1.0);
    if (clamped <= 0.0) {
        return scattering;
    }
    float3 tint = bt709ToAcesCg(max(tint709, float3(0.0)));
    float tintLuma = dot(tint, ACESCG_LUMA);
    // Same trap as the fog tint: a black CLOUD_COLOR is missing data, not an instruction to render
    // black clouds. Leave the deck lit by the atmosphere when there is no usable tint.
    if (tintLuma <= 1.0e-3) {
        return scattering;
    }
    return lerp(scattering, scattering * (tint / tintLuma), clamped);
}

public CloudResult marchClouds(CloudParams c, SkyState state, float3 origin, float3 direction,
                               float3 tint709, float tintStrength) {
    CloudResult result;
    result.scattering = float3(0.0, 0.0, 0.0);
    result.transmittance = 1.0;
    if (!cloudsActive(c)) {
        return result;
    }
    float tNear;
    float tFar;
    if (!cloudSlabRange(c, origin, direction, tNear, tFar)) {
        return result;
    }

    // Whichever body is up drives the deck. Blending the two would double-light the deck through dawn and
    // dusk, when both are near the horizon and both transmittances are near zero anyway.
    bool sunUp = state.sunDir.y >= state.moonDir.y;
    float3 lightDir = sunUp ? state.sunDir : state.moonDir;
    float3 lightIrradiance = (sunUp ? state.sunIlluminance : state.moonIlluminance)
            * transmittanceToSpace(transmittanceLut, state.viewerRadiusKm, lightDir,
                                   sunUp ? state.sunAngularRadius : state.moonAngularRadius);

    // Multiple scattering inside the deck is not marched. Standing in for it: the sky's own radiance from
    // straight up, which is where a cloud's shadowed underside actually gets most of its light.
    float2 zenithUv = skyViewLutUv(state.viewerRadiusKm, 1.0,
            lightViewCosine(float3(0.0, 1.0, 0.0), lightDir), sunUp ? SKY_VIEW_BODY_SUN : SKY_VIEW_BODY_MOON);
    float3 ambient = skyViewLut.SampleLevel(zenithUv, 0.0).rgb * c.ambient;

    float phase = cloudPhase(dot(direction, lightDir));
    int viewSteps = int(max(float(CLOUD_VIEW_STEPS) * c.quality, 8.0));
    float stepSize = (tFar - tNear) / float(viewSteps);
    float t = tNear + stepSize * 0.5;

    for (int i = 0; i < viewSteps; ++i) {
        float3 position = origin + direction * t;
        float density = cloudDensity(c, position);
        if (density > 0.0) {
            float lightDepth = cloudLightOpticalDepth(c, position, lightDir);
            float3 inScatter = lightIrradiance * phase * cloudBeerPowder(lightDepth) + ambient;

            // Energy-conserving integration of the segment rather than a rectangle rule on radiance:
            // integrate(0..dt) L * sigma * e^(-sigma*s) ds = L * (1 - e^(-sigma*dt)). This stays correct as
            // step size changes, so raising CLOUD_VIEW_STEPS refines the silhouette without also changing
            // how bright the deck is.
            float segmentTransmittance = exp(-density * stepSize);
            result.scattering += result.transmittance * inScatter * (1.0 - segmentTransmittance);
            result.transmittance *= segmentTransmittance;
            if (result.transmittance < CLOUD_MIN_TRANSMITTANCE) {
                result.transmittance = 0.0;
                break;
            }
        }
        t += stepSize;
    }
    result.scattering = tintCloud(result.scattering, tint709, tintStrength);
    return result;
}
