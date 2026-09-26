module caustica_minecraft_overworld_sky;

import caustica_types;
import caustica_resources;
import caustica_color;
import caustica_environment;
import caustica_minecraft_sky_atmosphere;
import caustica_minecraft_celestial_dressing;
import caustica_minecraft_sky_bindings;

// The Overworld sky owns its textured celestial discs, procedural stars, and night airglow. These
// dressing choices stay with the Minecraft atmosphere model so the renderer-generic miss shader only
// evaluates the selected IEnvironmentModel implementation.

// This sky's own choice of star look, passed into the shared, parameterized stars() below.
static const float STAR_CELLS      = 21.0;  // cube-face grid resolution; with the threshold gives ~1500 stars
static const float STAR_THRESHOLD  = 0.86;  // cell occupancy cutoff (higher = fewer stars)
static const float3 STAR_COOL      = float3(0.78, 0.60, 0.42);
static const float3 STAR_WARM      = float3(0.50, 0.62, 0.95);

/** Atmosphere in-scatter for a world direction: both bodies' slices of this frame's sky-view LUT. */
float3 skyDome(float3 dir, SkyState state, MinecraftEnvironmentBinding binding) {
    float2 sunUv = skyViewLutUv(state.viewerRadiusKm, dir.y,
            lightViewCosine(dir, state.sunDir), SKY_VIEW_BODY_SUN);
    float2 moonUv = skyViewLutUv(state.viewerRadiusKm, dir.y,
            lightViewCosine(dir, state.moonDir), SKY_VIEW_BODY_MOON);
    return sampleTexture2DLod(binding.skyView, binding.lutSampler, sunUv, 0.0).rgb
            + sampleTexture2DLod(binding.skyView, binding.lutSampler, moonUv, 0.0).rgb;
}

float3 lutSkyEvaluateEnvironment(EnvironmentQuery query) {
    // Derives SkyState from the queue-ordered LUT-lifetime buffer referenced by this environment binding.
    MinecraftEnvironmentBinding binding = minecraftEnvironmentBinding(query);
    SkyState state = skyState(ShaderDataPtr<SkyInputs>(binding.skyInputsAddress)[0]);
    float3 dir = query.direction;

    float3 color = skyDome(dir, state, binding);
    // Airglow plus integrated unresolved starlight: the real floor of a moonless night sky (~1e-3 cd/m²),
    // isotropic because it is emitted by the upper atmosphere itself rather than scattered from a body.
    color += state.airglowLuminance;

    // Extinction along the view ray: stars redden and dim into the horizon on exactly the curve the sky
    // itself follows, because it is the same LUT that produced the sky. Zero angular radius — a star is a
    // point, so it sets at the horizon line.
    float3 extinction = transmittanceToSpace(binding.transmittance, binding.lutSampler,
            state.viewerRadiusKm, dir, 0.0);
    // Negative angle: rotating the LOOKUP direction is the inverse of rotating star geometry, so
    // -starAngle makes the field wheel the SAME way the sun and moon do.
    color += stars(dir, normalize(celestialAxis(state.noonTilt)), state.starAngle, state.starLuminance,
            extinction, STAR_CELLS, STAR_THRESHOLD, STAR_COOL, STAR_WARM);

    if (query.showEnvironmentEmitters) {
        float2 local;
        if (celestialSquareLocal(dir, state.sunDir, state.sunDiscHalfAngle, local)) {
            // Tint from the body's CENTRE, not from this ray. The drawn quad spans tens of degrees
            // (vanilla's size, not the real sun's half degree), so per-ray extinction would redden its
            // lower limb by several stops relative to its upper one — an accurate answer to a question
            // about a body that is 60x too large. The centre keeps the sprite one colour, as vanilla
            // draws it.
            float3 sunExtinction = transmittanceToSpace(binding.transmittance, binding.lutSampler,
                    state.viewerRadiusKm,
                    state.sunDir, state.sunAngularRadius);
            float2 uv = lerp(state.sunUv.xy, state.sunUv.zw, local * 0.5 + 0.5);
            float3 texel = sampleTexture2DLod(binding.celestialsAtlas,
                    binding.celestialSampler, uv, 0.0).rgb;
            // Vanilla's sun sprite bakes a soft glow gradient into its texels; scaling that raw to HDR
            // makes a huge ring around the disc. Raising the texture luminance to a high power keeps only
            // the bright core and collapses the painted halo to ~0 — the real halo comes from the Mie
            // in-scatter in the LUT, which is where it belongs.
            float core = pow(clamp(dot(texel, texel) * 0.45, 0.0, 1.0), 6.0);
            color += discRadiance(state.sunIlluminance * state.beamTransmittance, state.sunDiscHalfAngle)
                   * sunExtinction * core;
        }
        if (celestialSquareLocal(dir, state.moonDir, state.moonDiscHalfAngle, local)) {
            float2 uv = lerp(state.moonUv.xy, state.moonUv.zw, local * 0.5 + 0.5);
            float3 texel = srgbToLinear(
                    sampleTexture2DLod(binding.celestialsAtlas,
                            binding.celestialSampler, uv, 0.0).rgb);
            float3 moonExtinction = transmittanceToSpace(binding.transmittance, binding.lutSampler,
                    state.viewerRadiusKm,
                    state.moonDir, state.moonAngularRadius);
            // The phase sprite already carries the lit shape; a contrast ramp suppresses its faint
            // painted halo while keeping that shape.
            float shape = smoothstep(0.0, 1.0, min(length(texel), 1.0));
            color += discRadiance(state.moonIlluminance * state.beamTransmittance, state.moonDiscHalfAngle)
                   * moonExtinction * texel * shape;
        }
        // No separate horizon gate on either body. A set sun or moon goes dark because its extinction is
        // zero below the horizon.
    }

    // Atmosphere and celestial colors use linear BT.709; convert once at the environment output.
    return bt709ToAcesCg(max(color, float3(0.0)));
}

public struct MinecraftOverworldSky : IEnvironmentModel {
    public float3 evaluateEnvironment(EnvironmentQuery query) {
        return lutSkyEvaluateEnvironment(query);
    }
};
