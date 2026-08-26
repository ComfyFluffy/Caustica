// Ray miss = sky. Two sky-view LUT fetches (sun slice + moon slice) give the whole atmosphere; the disc
// sprites, the starfield and the night airglow floor are added on top. Everything physical lives in
// sky.slang, shared verbatim with the LUT bakes, so the sky a ray sees and the sky the LUT holds cannot
// diverge.
//
// Atmospheric scattering is amortized into the per-frame LUT. Analytic stars, airglow, and textured
// celestial discs are combined with that physical result here.
//
// Celestial discs are gated by a packed payload flag, which raygen sets per-ray from the path state: true
// on the primary ray and after every specular/dielectric bounce (so the sun/moon appear in water, glass
// and metal reflections/refractions), false after a diffuse vertex did sun/moon NEE — otherwise the disc
// would be counted twice against the direct light and show up as fireflies. The atmosphere and the stars
// are added on every miss at any bounce: stars are not a NEE light, so they never double-count.
import world_common;
import sky;
import bindings;

// Vanilla celestials atlas (sun + moon-phase sprites), bound by RtComposite. Sampled with an explicit LOD
// (a miss shader has no derivatives). Keeping the real sprites means a resource pack's sun and moon still
// show up, which a procedural disc would silently drop.
// Drawn disc radiance is derived from illuminance and the solid angle the body is actually DRAWN at
// (L = E / Ω), deliberately not from its true surface luminance. Vanilla's sun quad subtends ~60x the real
// sun, i.e. ~3900x its solid angle; painting it at the physical 1.6e9 cd/m² would put ~3900x the sun's
// power into every path that sees it. Specular and dielectric bounces do see it and have already taken the
// sun through NEE, so that double-count would go from harmless to dominant, with fireflies off every
// glossy lobe. Deriving from illuminance keeps the drawn body's total power equal to the real one's at
// whatever size it is drawn, and costs nothing visually: the result still sits ~15 EV over an 18%-grey
// noon surface, which the ACES 2.0 output transform renders as pure white either way.
float3 discRadiance(float illuminance, float halfAngle) {
    float side = 2.0 * tan(halfAngle);      // the square spans this much on a side, gnomonically
    return float3(illuminance / max(side * side, 1.0e-8));
}

// Dave Hoskins' hash13 (https://www.shadertoy.com/view/4djSRW): well-distributed and pattern-free.
float hash13(float3 p3) {
    p3 = frac(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return frac((p3.x + p3.y) * p3.z);
}

// Rotate v about a unit axis by angle (Rodrigues). Used to wheel the starfield about the celestial pole.
float3 rotateAxis(float3 v, float3 axis, float ang) {
    float c = cos(ang), s = sin(ang);
    return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);
}

// Procedural starfield, a faithful port of vanilla's buildStars(): ~1500 discrete stars at random sphere
// directions, each a small SQUARE billboard of random size and rotation. A cube-map grid is the spatial
// accelerator — at most one star per cell, at a hashed position inset from the cell edges (so its square
// never crosses into a neighbour), drawn as a hash-rotated square of hashed size. The lookup direction is
// rotated about the celestial pole by -starAngle so the field wheels with world time like the real sky.
static const float STAR_CELLS     = 21.0;  // cube-face grid resolution; with the threshold gives ~1500 stars
static const float STAR_THRESHOLD = 0.86;  // cell occupancy cutoff (higher = fewer stars)
static const float STAR_HALF      = 0.045; // star half-size as a fraction of a cell (≈ vanilla 0.11° at centre)
float3 stars(float3 dir, SkyState state, float3 extinction) {
    if (state.starLuminance <= 0.0 || dir.y <= 0.0) {
        return float3(0.0);
    }
    // Negative angle: rotating the LOOKUP direction is the inverse of rotating star geometry, so
    // -starAngle makes the field wheel the SAME way the sun and moon do.
    float3 sdir = rotateAxis(dir, normalize(celestialAxis(state.noonTilt)), -state.starAngle);
    // Cube-map face + 2D gnomonic uv → uniform square cells, no pole/equator distortion.
    float3 a = abs(sdir);
    float2 uv;
    float face;
    if (a.x >= a.y && a.x >= a.z)      { uv = sdir.yz / a.x; face = sdir.x > 0.0 ? 0.0 : 1.0; }
    else if (a.y >= a.z)               { uv = sdir.xz / a.y; face = sdir.y > 0.0 ? 2.0 : 3.0; }
    else                               { uv = sdir.xy / a.z; face = sdir.z > 0.0 ? 4.0 : 5.0; }
    float2 g = uv * STAR_CELLS;
    float3 id = float3(floor(g), face);
    if (hash13(id) < STAR_THRESHOLD) {
        return float3(0.0); // no star in this cell
    }
    float sz = STAR_HALF * (0.6 + 0.8 * hash13(id + 53.0));
    float inset = sz * 1.5; // keeps the rotated square clear of the cell edge
    float2 center = inset + (1.0 - 2.0 * inset) * float2(hash13(id + 11.0), hash13(id + 23.0));
    float2 q = frac(g) - center;
    float ang = hash13(id + 91.0) * 6.2831853;            // random per-star rotation (vanilla zRot)
    float cs = cos(ang), sn = sin(ang);
    q = float2(cs * q.x - sn * q.y, sn * q.x + cs * q.y); // GLSL mat2(cs,-sn,sn,cs) * q, written out
    float box = max(abs(q.x), abs(q.y));                  // Chebyshev distance → square
    float star = 1.0 - smoothstep(sz * 0.6, sz, box);     // soft-edged square (helps DLSS stability)
    if (star <= 0.0) {
        return float3(0.0);
    }
    float bright = 0.55 + 0.45 * hash13(id + 71.0);       // mild per-star brightness variation
    // Per-star colour temperature: a hash picks a spot on a warm-orange to blue-white ramp, matching real
    // star populations. Extinction reddens and dims them toward the horizon on the atmosphere's curve.
    float3 starColor = lerp(float3(0.78, 0.60, 0.42), float3(0.50, 0.62, 0.95), hash13(id + 137.0));
    return star * bright * starColor * state.starLuminance * extinction;
}

/** Atmosphere in-scatter for a world direction: both bodies' slices of this frame's sky-view LUT. */
float3 skyDome(float3 dir, SkyState state) {
    float2 sunUv = skyViewLutUv(state.viewerRadiusKm, dir.y,
            lightViewCosine(dir, state.sunDir), SKY_VIEW_BODY_SUN);
    float2 moonUv = skyViewLutUv(state.viewerRadiusKm, dir.y,
            lightViewCosine(dir, state.moonDir), SKY_VIEW_BODY_MOON);
    return skyViewLut.SampleLevel(sunUv, 0.0).rgb + skyViewLut.SampleLevel(moonUv, 0.0).rgb;
}

// SkyRenderer.buildEndSky() draws six 200x200 quads around the camera. This is the ray equivalent of
// those faces, including the per-face UV orientation and the 0..16 repeat range of end_sky.png.
float2 endSkyUv(float3 dir) {
    float3 a = abs(dir);
    float t = 100.0 / max(max(a.x, a.y), a.z);
    float3 p = dir * t;
    if (a.x >= a.y && a.x >= a.z) {
        if (dir.x > 0.0) return (float2(p.y, p.z) + 100.0) * (16.0 / 200.0);
        return (float2(-p.y, p.z) + 100.0) * (16.0 / 200.0);
    }
    if (a.y >= a.z) {
        if (dir.y > 0.0) return (float2(p.x, -p.z) + 100.0) * (16.0 / 200.0);
        return (float2(p.x, p.z) + 100.0) * (16.0 / 200.0);
    }
    if (dir.z > 0.0) return (float2(p.x, p.y) + 100.0) * (16.0 / 200.0);
    return (float2(p.x, -p.y) + 100.0) * (16.0 / 200.0);
}

float3 endFlashRadiance(float3 dir, WorldPush worldPush) {
    if ((worldPush.skyFlags & SKY_FLAG_END_FLASH) == 0u) {
        return float3(0.0);
    }
    // Matches SkyRenderer.renderEndFlash's Y(180-y) * X(-90-x) pose, translation (0,100,0),
    // and 60x60 quad scale.
    static const float PI = 3.14159265359;
    float y = PI - worldPush.skyParams.w;
    float x = -0.5 * PI - worldPush.skyParams.z;
    float cy = cos(y), sy = sin(y), cx = cos(x), sx = sin(x);
    float3 right = float3(cy, 0.0, -sy);
    float3 up = float3(sy * sx, cx, cy * sx);
    float3 forward = float3(sy * cx, -sx, cy * cx);
    float3 center = up * 100.0;
    float denom = dot(dir, up);
    if (abs(denom) < 1.0e-5) return float3(0.0);
    float hitDistance = dot(center, up) / denom;
    if (hitDistance <= 0.0) return float3(0.0);
    float3 q = dir * hitDistance - center;
    float2 local = float2(dot(q, right), dot(q, forward)) / 60.0;
    if (max(abs(local.x), abs(local.y)) > 1.0) return float3(0.0);
    float2 uv = lerp(worldPush.endFlashUv.xy, worldPush.endFlashUv.zw, local * 0.5 + 0.5);
    float4 texel = celestialsAtlas.SampleLevel(uv, 0.0);
    float intensity = worldPush.skyParams.y;
    return srgbToLinear(texel.rgb) * texel.a * intensity * intensity;
}

[shader("miss")]
void main(inout Payload payload) {
    WorldPush worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    SkyState state = skyState(worldPush);
    float3 dir = normalize(WorldRayDirection());

    if (worldPush.skybox == SKYBOX_NONE) {
        packSky(payload, bt709ToAcesCg(max(worldPush.skyColor.rgb, float3(0.0))));
        payload.hitT = -1.0;
        payload.flags = 0u;
        payload.roughMetal = packHalf2(float2(1.0, 0.0));
        payload.emissionSss = packHalf2(float2(0.0, 0.0));
        payload.iorTransmission = packHalf2(float2(1.0, 0.0));
        payload.rayCone = 0u;
        return;
    }

    if (worldPush.skybox == SKYBOX_END) {
        float4 texel = endSkyTexture.SampleLevel(endSkyUv(dir), 0.0);
        float3 endSkyColor = srgbToLinear(texel.rgb) * (40.0 / 255.0);
        float3 color = lerp(worldPush.skyColor.rgb, endSkyColor, clamp(texel.a, 0.0, 1.0));
        if ((payload.flags & PAYLOAD_PRIMARY_SKY) != 0u) {
            color += endFlashRadiance(dir, worldPush);
        }
        packSky(payload, bt709ToAcesCg(max(color, float3(0.0))));
        payload.hitT = -1.0;
        payload.flags = 0u;
        payload.roughMetal = packHalf2(float2(1.0, 0.0));
        payload.emissionSss = packHalf2(float2(0.0, 0.0));
        payload.iorTransmission = packHalf2(float2(1.0, 0.0));
        payload.rayCone = 0u;
        return;
    }

    bool showCelestial = (payload.flags & PAYLOAD_SHOW_CELESTIAL) != 0u;

    float3 color = skyDome(dir, state);
    // Airglow plus integrated unresolved starlight: the real floor of a moonless night sky (~1e-3 cd/m²),
    // isotropic because it is emitted by the upper atmosphere itself rather than scattered from a body.
    // Multiple scattering is in the LUT, so this remains a flat, physically small constant.
    color += state.airglowLuminance;

    // Extinction along the view ray: stars redden and dim into the horizon on exactly the curve the sky
    // itself follows, because it is the same LUT that produced the sky. Zero angular radius — a star is a
    // point, so it sets at the horizon line.
    float3 extinction = transmittanceToSpace(transmittanceLut, state.viewerRadiusKm, dir, 0.0);
    color += stars(dir, state, extinction);

    if (showCelestial) {
        float2 local;
        if (celestialSquareLocal(dir, state.sunDir, state.sunDiscHalfAngle, local)) {
            // Tint from the body's CENTRE, not from this ray. The drawn quad spans ~33 degrees (vanilla's
            // size, not the real sun's half degree), so per-ray extinction would redden its lower limb by
            // several stops relative to its upper one — an accurate answer to a question about a body that
            // is 60x too large. The centre keeps the sprite one colour, as vanilla draws it.
            float3 sunExtinction = transmittanceToSpace(transmittanceLut, state.viewerRadiusKm,
                    state.sunDir, state.sunAngularRadius);
            float2 uv = lerp(state.sunUv.xy, state.sunUv.zw, local * 0.5 + 0.5);
            float3 texel = celestialsAtlas.SampleLevel(uv, 0.0).rgb;
            // Vanilla's sun sprite bakes a soft glow gradient into its texels; scaling that raw to HDR
            // makes a huge ring around the disc. Raising the texture luminance to a high power keeps only
            // the bright core and collapses the painted halo to ~0 — the real halo comes from the Mie
            // in-scatter in the LUT, which is where it belongs.
            float core = pow(clamp(dot(texel, texel) * 0.45, 0.0, 1.0), 6.0);
            color += discRadiance(state.sunIlluminance, state.sunDiscHalfAngle) * sunExtinction * core;
        }
        if (celestialSquareLocal(dir, state.moonDir, state.moonDiscHalfAngle, local)) {
            float2 uv = lerp(state.moonUv.xy, state.moonUv.zw, local * 0.5 + 0.5);
            float3 texel = srgbToLinear(celestialsAtlas.SampleLevel(uv, 0.0).rgb);
            float3 moonExtinction = transmittanceToSpace(transmittanceLut, state.viewerRadiusKm,
                    state.moonDir, state.moonAngularRadius);
            // The phase sprite already carries the lit shape; a contrast ramp suppresses its faint painted
            // halo while keeping that shape.
            float shape = smoothstep(0.0, 1.0, min(length(texel), 1.0));
            color += discRadiance(state.moonIlluminance, state.moonDiscHalfAngle)
                   * moonExtinction * texel * shape;
        }
        // No separate horizon gate on either body. A set sun or moon goes dark because its extinction is
        // zero below the horizon, matching the NEE visibility test.
    }

    // The sky goes out in absolute scene units at full fp32 — packSky writes the payload's three surface
    // words as raw floats rather than halves, which is why the sun disc's ~1e5 cd/m² needs neither a
    // pre-exposure scale nor a clamp to stay representable. The fp16 ceiling reappears only at world.rgen's
    // outImage store, where the finite-half clamp is applied. packSky also
    // overwrites the normal view, so there is no separate normal clear below.
    packSky(payload, bt709ToAcesCg(max(color, float3(0.0))));
    payload.hitT = -1.0;
    payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
    payload.f0 = half3(0.0h, 0.0h, 0.0h);
    payload.flags = 0u;
    payload.roughMetal = packHalf2(float2(1.0, 0.0));
    payload.emissionSss = packHalf2(float2(0.0, 0.0));
    payload.iorTransmission = packHalf2(float2(1.0, 0.0));
    payload.rayCone = 0u;
}
