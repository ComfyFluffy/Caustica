// Exponential height fog, integrated analytically per path segment. Depends on sky and bindings.
//
// This is aerial perspective for the block-scale world, not the planet-scale atmosphere: sky.slang's LUTs
// already carry Rayleigh/Mie/ozone over 100 km, but their density at the 0-320 block range the player
// actually looks through is far too low to read as fog. This module adds a second, thin, tunable medium
// that lives entirely inside the render distance.
//
// It is applied as an AFFINE operation on radiance, not as a throughput multiply. A throughput-only
// exp(-sigma*t) — which is all the existing water/glass path does — is pure absorption, and pure
// absorption sends the horizon to black rather than to sky colour. Fog needs the in-scatter term too:
//
//     L' = L * T + Lin * (1 - T)
//
// so every call site has to hand over both the accumulated radiance and the throughput. That is why this
// is a helper taking two inout parameters rather than something foldable into MediumStack: the medium
// stack is deliberately a pure-extinction abstraction and this would have quietly broken that invariant
// for water and glass as well.
//
// The in-scatter radiance is the SKY VIEW LUT sampled in the ray's own direction, not a configured
// colour. That is the whole reason this reads as atmosphere rather than as a grey wash: fog at sunset is
// orange toward the sun and blue away from it, fog at night falls to the airglow floor, and fog under an
// overcast look package tracks whatever the LUT bake produced — all for free, and all guaranteed to match
// the sky the miss shader draws, because it is literally the same texture.
//
// Transmittance is deliberately scalar rather than per-channel. Real fog is Mie-dominated and close to
// spectrally flat at these distances, and a scalar T lets the whole thing cost one exp and one LUT pair.

import world_common;
import sky;
import medium;
import bindings;

// The trace tmax. Integrating a miss out to this distance is correct rather than arbitrary: an upward ray
// converges (the exponential integral is finite as t goes to infinity), and a horizontal ray at the
// reference height saturates to full fog, which is exactly what a fogged horizon should do.
public static const float FOG_MAX_DISTANCE = 10000.0;

// Keeps exp() finite for a viewer far under the reference plane or a steep downward ray in a thin fog.
// e^60 is already past anything that survives the 1 - T below, so clamping here costs nothing visible.
static const float FOG_EXP_CLAMP = 60.0;

public struct FogParams {
    public float sigma;       // extinction per block at referenceY. 0 disables the whole module.
    public float falloff;     // 1 / scale height, per block
    public float referenceY;  // rebased world Y at which density is sigma
    public float albedo;      // single-scattering albedo; 1 is a perfectly scattering (non-absorbing) fog
};

public FogParams makeFogParams(WorldPush push) {
    FogParams f;
    f.sigma = max(push.fog.x, 0.0);
    f.falloff = max(push.fog.y, 0.0);
    f.referenceY = push.fog.z;
    f.albedo = clamp(push.fog.w, 0.0, 1.0);
    return f;
}

public bool fogActive(FogParams f) {
    return f.sigma > 0.0;
}

// Fog exists in air only. Inside water or glass that medium's own Beer-Lambert extinction is already the
// correct and complete answer, and layering a second medium on top would double-count the attenuation.
public bool fogAppliesTo(Medium m) {
    return !m.water && all(m.extinction <= 0.0);
}

// Optical depth of an exponential-density slab along ro + rd*s for s in [0, t].
//
//   n(y)  = exp(-(y - referenceY) * falloff)
//   tau   = sigma * integral of n(ro.y + rd.y*s) ds
//         = sigma * a * (1 - exp(-b*t)) / b,   a = n(ro.y), b = rd.y * falloff
//
// The b -> 0 limit (a level ray, which is the common case when looking at the horizon) is the removable
// singularity sigma*a*t, taken by an explicit branch rather than by an epsilon in the denominator.
public float fogOpticalDepth(FogParams f, float3 ro, float3 rd, float t) {
    float travel = clamp(t, 0.0, FOG_MAX_DISTANCE);
    if (travel <= 0.0) {
        return 0.0;
    }
    float density = exp(-clamp((ro.y - f.referenceY) * f.falloff, -FOG_EXP_CLAMP, FOG_EXP_CLAMP));
    float b = rd.y * f.falloff;
    float integral;
    if (abs(b) < 1.0e-5) {
        integral = density * travel;
    } else {
        integral = density * (1.0 - exp(-clamp(b * travel, -FOG_EXP_CLAMP, FOG_EXP_CLAMP))) / b;
    }
    return f.sigma * max(integral, 0.0);
}

// Radiance scattered into the ray by the fog. Two LUT fetches, matching the miss shader's skyDome: the
// sun slice plus the moon slice, then the isotropic airglow floor so night fog settles at the same
// luminance the night sky does instead of going black. sky.slang works in linear BT.709; the path
// integral is ACEScg, so this crosses the same seam the miss shader does, in the same place.
// Vanilla's FOG_COLOR for wherever the camera is, and how hard to pull scattering toward it. In the
// Overworld this is a light tint over the physically computed sky, so a swamp reads green-grey without
// losing the atmosphere's time-of-day behaviour. In the Nether and the End there is no atmosphere worth
// sampling, so the caller sends full strength and this becomes the entire fog colour.
public float3 fogTint(FogParams f, float3 inscatter, WorldPush push) {
    float strength = clamp(push.dimFog.w, 0.0, 1.0);
    if (strength <= 0.0) {
        return inscatter;
    }
    // Tint by hue, not by replacement: preserve the scattering's own luminance so a dark fog colour
    // cannot black out a bright sunlit haze, and a bright one cannot light up a night scene.
    float3 tint = bt709ToAcesCg(max(push.dimFog.rgb, float3(0.0)));
    float tintLuma = dot(tint, ACESCG_LUMA);
    // A BLACK tint means "no colour information", not "no light". Clamping the divisor was not enough:
    // dividing a black tint by a tiny epsilon still yields zero, so the multiply below wiped the fog
    // out and every distant surface went to black. A server or dimension that reports no FOG_COLOR is
    // exactly this case, and the correct response is to leave the atmosphere's own colour alone.
    if (tintLuma <= 1.0e-3) {
        return inscatter;
    }
    float3 normalizedTint = tint / tintLuma;
    return lerp(inscatter, inscatter * normalizedTint, strength);
}

public float3 fogInscatter(FogParams f, float3 rd, SkyState state) {
    // THE SKY-VIEW LUT HAS NO DATA BELOW THE HORIZON, and this is where that bites.
    //
    // sky.rmiss only ever samples it for rays that escaped to space, which are above the horizon by
    // construction. Fog is different: most fogged rays point slightly DOWN, at distant terrain. Sampling
    // the LUT there returns black, so every long ray toward the ground accumulated black in-scatter and
    // the horizon went to pitch black — worse the denser the fog, because denser fog means more of that
    // black and less of the surface behind it.
    //
    // Physically the fix is not a clamp for its own sake: light scattered into a downward ray comes from
    // the sky above the scattering point, and near the horizon that is the horizon sky. So the lookup
    // direction is folded up to just above the horizon while the LIGHT-relative angle keeps the true
    // direction, which is what preserves the orange-toward-the-sun behaviour.
    float horizonCos = horizonZenithCos(state.viewerRadiusKm);
    float lookupZenithCos = max(rd.y, horizonCos + 1.0e-3);
    float2 sunUv = skyViewLutUv(state.viewerRadiusKm, lookupZenithCos,
            lightViewCosine(rd, state.sunDir), SKY_VIEW_BODY_SUN);
    float2 moonUv = skyViewLutUv(state.viewerRadiusKm, lookupZenithCos,
            lightViewCosine(rd, state.moonDir), SKY_VIEW_BODY_MOON);
    float3 sky709 = skyViewLut.SampleLevel(sunUv, 0.0).rgb
            + skyViewLut.SampleLevel(moonUv, 0.0).rgb
            + state.airglowLuminance;
    return bt709ToAcesCg(max(sky709, float3(0.0))) * f.albedo;
}

// Fold one travelled segment into the path. Call once per segment, BEFORE the segment's endpoint
// contributes anything to L, so that the in-scatter enters at this segment's throughput and everything
// beyond it is attenuated by this segment's transmittance.
// Debug: replace the path's radiance with the raw fog in-scatter colour, so the value can be SEEN
// rather than inferred. Three fog fixes have now been attempted from reasoning alone and all three
// were wrong about the cause; this is the cheapest way to find out whether the in-scatter is black
// (a lookup problem) or bright (a composition problem), which need opposite fixes.
public bool fogDebugInscatter(FogParams f, WorldPush push, float3 rd, SkyState state,
                              inout float3 L, inout float3 throughput) {
    if (push.fog2.x < 0.5) {
        return false;
    }
    L = fogTint(f, fogInscatter(f, rd, state), push);
    throughput = float3(0.0);
    return true;
}

public void applyFogSegment(FogParams f, SkyState state, Medium medium, WorldPush push,
                            float3 ro, float3 rd, float t,
                            inout float3 L, inout float3 throughput) {
    if (fogDebugInscatter(f, push, rd, state, L, throughput)) {
        return;
    }
    if (!fogActive(f) || !fogAppliesTo(medium)) {
        return;
    }
    float tau = fogOpticalDepth(f, ro, rd, t);
    if (tau <= 0.0) {
        return;
    }
    float transmittance = exp(-tau);
    L += throughput * fogTint(f, fogInscatter(f, rd, state), push) * (1.0 - transmittance);
    throughput *= transmittance;
}
