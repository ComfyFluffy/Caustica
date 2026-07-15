// Ray miss = sky. Per-frame sky data comes from the WorldPush BDA buffer addressed by the 8-byte push
// constant. The shader writes full sky radiance (Rayleigh/Mie/ozone atmosphere + transmittance-tinted
// sun/moon discs + soft horizon + starfield) into payload.albedo; raygen consumes that value for both
// accumulated radiance and bounce-0 denoiser guides.
//
// Celestial discs (sun/moon) are gated by a packed payload flag, which raygen sets per-ray from the path
// state: true on the primary ray and after every specular/dielectric bounce (so the sun/moon show up in
// water/glass/metal reflections + refractions), false after a diffuse vertex did sun/moon NEE (so the
// disc isn't double-counted against the direct light → fireflies). The atmosphere gradient + stars are
// added on every miss (any bounce): stars aren't a NEE light, so they never double-count.
import world_common;

[[vk::push_constant]] PushAddr pcAddr;

// Vanilla celestials atlas (sun + moon-phase sprites), bound by RtComposite. Sampled with an explicit
// LOD (no derivatives in a miss shader). The sun/moon discs are drawn from its real texels.
[[vk::binding(9, 0)]] Sampler2D celestialsAtlas;

static const float PI = 3.14159265359;
float pow2(float x) { return x * x; }

// Near-white top-of-atmosphere disc radiances: the visible warm/red tint comes from multiplying by
// transmittanceToSpace(dir) at draw time — at noon the sun reads warm-white, at the horizon it reddens
// on exactly the sky's own sunset curve.
static const float3 SUN_DISC_RADIANCE  = float3(24.0, 23.0, 21.5);
static const float3 MOON_DISC_RADIANCE = float3(1.7, 1.85, 2.2); // lit-side moon (HDR, bright at night exposure)
// Vibrance applied to the atmosphere in-scatter only (not discs/stars/night ambient): luminance-
// preserving, so GI energy is unchanged.
static const float SKY_SATURATION = 1.2;
// VISIBLE disc half-angles, matched to vanilla: the sun/moon quads are drawn at half-width 30/20 at
// distance 100 → half-angle = atan(0.30) / atan(0.20). Decoupled from the NEE light radius (≈0.6°).
static const float SUN_DISC_HALF_ANGLE  = 0.29146; // atan(30/100), vanilla sun size
static const float MOON_DISC_HALF_ANGLE = 0.19740; // atan(20/100), vanilla moon size
// Faint night-sky ambient so the night isn't pure black where the atmosphere in-scatter falls to zero.
static const float3 NIGHT_ZENITH  = float3(0.004, 0.008, 0.022) / 2.0;
static const float3 NIGHT_HORIZON = float3(0.015, 0.022, 0.045) / 2.0;

// ---- Physically-based atmosphere (Nishita / O'Neil single scattering + ozone absorption). A view ray
// is marched through the atmospheric shell; at each step the transmittance toward the sun is integrated
// (a second, shorter march), giving Rayleigh (blue-sky / red-sunset) + Mie (sun halo) in-scattered
// radiance. World up = +Y; the camera sits a couple km above a spherical planet. Output is HDR in the
// same units as tracePath.
static const float  PLANET_R   = 6371000.0;
static const float  ATMOS_R    = 6471000.0;        // 100 km shell
static const float3 RAY_BETA   = float3(5.5e-6, 13.0e-6, 22.4e-6); // Rayleigh scattering at sea level
static const float  MIE_BETA   = 21.0e-6;          // Mie scattering at sea level
static const float  RAY_SCALE_H = 8000.0;          // Rayleigh density scale height
static const float  MIE_SCALE_H = 1200.0;          // Mie density scale height
static const float  MIE_G      = 0.758;            // Mie anisotropy (forward-scattering)
// Ozone: absorption only (no scattering), tent-shaped density peaking at 25 km. Its green-heavy
// absorption keeps the zenith deep blue at noon and turns twilight purple-orange.
static const float3 OZONE_BETA = float3(0.650e-6, 1.881e-6, 0.085e-6);
static const float  OZONE_CENTER_H = 25000.0;
static const float  OZONE_WIDTH_H  = 15000.0;
static const float  SUN_INTENSITY = 22.0;
static const int    ATMOS_PRIMARY_STEPS = 16;
static const int    ATMOS_LIGHT_STEPS   = 8;

float ozoneDensity(float h) { return max(0.0, 1.0 - abs(h - OZONE_CENTER_H) / OZONE_WIDTH_H); }

// Smaller root of ray vs sphere of radius r centred at origin (no-hit handled by x > y).
float2 raySphere(float3 o, float3 d, float r) {
    float b = dot(o, d);
    float c = dot(o, o) - r * r;
    float disc = b * b - c;
    if (disc < 0.0) return float2(1.0e9, -1.0e9);
    disc = sqrt(disc);
    return float2(-b - disc, -b + disc);
}

float3 atmosphere(float3 dir, float3 sunDir) {
    float3 orig = float3(0.0, PLANET_R + 2000.0, 0.0);
    float2 t = raySphere(orig, dir, ATMOS_R);
    if (t.x > t.y) return float3(0.0, 0.0, 0.0);      // ray misses the atmosphere entirely
    float tStart = max(t.x, 0.0);
    float tEnd = t.y;
    float2 tGround = raySphere(orig, dir, PLANET_R);
    if (tGround.x > 0.0) tEnd = min(tEnd, tGround.x); // clip the march at the planet surface
    float segLen = (tEnd - tStart) / float(ATMOS_PRIMARY_STEPS);

    float mu = dot(dir, sunDir);
    float phaseR = 3.0 / (16.0 * PI) * (1.0 + mu * mu);
    float g = MIE_G;
    float phaseM = 3.0 / (8.0 * PI) * ((1.0 - g * g) * (1.0 + mu * mu))
                 / ((2.0 + g * g) * pow(max(1.0 + g * g - 2.0 * g * mu, 0.0), 1.5));

    float3 sumR = float3(0.0, 0.0, 0.0), sumM = float3(0.0, 0.0, 0.0);
    float odR = 0.0, odM = 0.0, odO = 0.0;            // view-ray optical depth accumulators
    float tCur = tStart;
    for (int i = 0; i < ATMOS_PRIMARY_STEPS; i++) {
        float3 p = orig + dir * (tCur + segLen * 0.5);
        float h = length(p) - PLANET_R;
        float hr = exp(-h / RAY_SCALE_H) * segLen;
        float hm = exp(-h / MIE_SCALE_H) * segLen;
        odR += hr; odM += hm; odO += ozoneDensity(h) * segLen;

        // March toward the sun to accumulate the light-ray optical depth (transmittance to space).
        float2 tl = raySphere(p, sunDir, ATMOS_R);
        float segLenL = tl.y / float(ATMOS_LIGHT_STEPS);
        float tlCur = 0.0, odRL = 0.0, odML = 0.0, odOL = 0.0;
        bool blocked = false;
        for (int j = 0; j < ATMOS_LIGHT_STEPS; j++) {
            float3 pl = p + sunDir * (tlCur + segLenL * 0.5);
            float hl = length(pl) - PLANET_R;
            if (hl < 0.0) { blocked = true; break; } // sun is below this point's horizon → in shadow
            odRL += exp(-hl / RAY_SCALE_H) * segLenL;
            odML += exp(-hl / MIE_SCALE_H) * segLenL;
            odOL += ozoneDensity(hl) * segLenL;
            tlCur += segLenL;
        }
        if (!blocked) {
            float3 tau = RAY_BETA * (odR + odRL) + MIE_BETA * 1.1 * (odM + odML)
                       + OZONE_BETA * (odO + odOL);
            float3 atten = exp(-tau);
            sumR += hr * atten;
            sumM += hm * atten;
        }
        tCur += segLen;
    }
    return SUN_INTENSITY * (sumR * RAY_BETA * phaseR + sumM * MIE_BETA * phaseM);
}

// Extinction from the camera to space along `dir` (Rayleigh + Mie + ozone; the sun-below-horizon case
// needs no explicit planet test — a grazing/downward path picks up enormous optical depth, so the
// transmittance rolls to zero smoothly on its own). Tints the sun/moon discs at draw time, and is ported
// verbatim to RtComposite.writeSky so the NEE sunlight on terrain follows the identical sunset curve —
// the disc, the sky, and the light can never disagree.
float3 transmittanceToSpace(float3 dir) {
    float3 orig = float3(0.0, PLANET_R + 2000.0, 0.0);
    float2 t = raySphere(orig, dir, ATMOS_R);
    if (t.y <= 0.0) return float3(1.0, 1.0, 1.0);
    float segLen = t.y / float(ATMOS_LIGHT_STEPS);
    float odR = 0.0, odM = 0.0, odO = 0.0;
    for (int i = 0; i < ATMOS_LIGHT_STEPS; i++) {
        float3 p = orig + dir * (segLen * (float(i) + 0.5));
        float h = length(p) - PLANET_R;
        odR += exp(-h / RAY_SCALE_H) * segLen;
        odM += exp(-h / MIE_SCALE_H) * segLen;
        odO += ozoneDensity(h) * segLen;
    }
    return exp(-(RAY_BETA * odR + MIE_BETA * 1.1 * odM + OZONE_BETA * odO));
}

// Build the celestial body's square tangent frame: `right` along the arc-travel direction, `up`
// perpendicular within the sky. Shared by the visible disc and the NEE square sampling in raygen.
void celestialFrame(float3 dir, float3 celestialAxis, out float3 right, out float3 up) {
    right = normalize(cross(dir, celestialAxis));
    up = cross(right, dir);
}

// Gnomonic projection of `dir` into the body's tangent square, in units of the half-angle. local ∈
// [-1,1]² is inside the square. Returns 0 behind the body. The square (not a cone) matches MC's quad.
float squareBody(float3 dir, float3 bodyDir, float halfAngle, float3 celestialAxis, out float2 local) {
    float c = dot(dir, bodyDir);
    if (c <= 1.0e-3) { local = float2(2.0, 2.0); return 0.0; }
    float3 d = dir / c;                  // d·bodyDir == 1 (point on the tangent plane)
    float3 r, u;
    celestialFrame(bodyDir, celestialAxis, r, u);
    float t = tan(halfAngle);
    local = float2(dot(d, r), dot(d, u)) / t;
    float m = max(abs(local.x), abs(local.y));
    return 1.0 - smoothstep(0.90, 1.0, m); // soft square edge
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

// Procedural starfield, a faithful port of vanilla's buildStars(): ~1500 discrete stars at random
// sphere directions, each a small SQUARE billboard of random size + random rotation. A cube-map grid is
// the spatial accelerator — at most one star per cell, placed at a hashed position INSET from the cell
// edges (so its square never crosses into a neighbour), drawn as a hash-rotated square of hashed size.
// The lookup direction is rotated about the celestial pole by -STAR_ANGLE so the field wheels with
// world time like the real sky.
static const float STAR_CELLS     = 21.0;  // cube-face grid resolution; with the threshold gives ~1500 stars
static const float STAR_THRESHOLD = 0.86;  // cell occupancy cutoff (higher = fewer stars)
static const float STAR_HALF      = 0.045; // star half-size as a fraction of a cell (≈ vanilla 0.11° at center)
float3 stars(float3 dir, float starBrightness, float VdotS, float4 celestial) {
    if (starBrightness <= 0.0 || dir.y <= 0.0) return float3(0.0, 0.0, 0.0);
    // Negative angle: rotating the LOOKUP direction is the inverse of rotating star geometry, so
    // -starAngle makes the field wheel the SAME way as the sun/moon (whose world dirs are
    // forward-rotated on the CPU).
    float3 sdir = rotateAxis(dir, normalize(celestial.xyz), -celestial.w);
    // Cube-map face + 2D gnomonic uv → uniform square cells, no pole/equator distortion.
    float3 a = abs(sdir);
    float2 uv;
    float face;
    if (a.x >= a.y && a.x >= a.z)      { uv = sdir.yz / a.x; face = sdir.x > 0.0 ? 0.0 : 1.0; }
    else if (a.y >= a.z)               { uv = sdir.xz / a.y; face = sdir.y > 0.0 ? 2.0 : 3.0; }
    else                               { uv = sdir.xy / a.z; face = sdir.z > 0.0 ? 4.0 : 5.0; }
    float2 g = uv * STAR_CELLS;
    float3 id = float3(floor(g), face);
    if (hash13(id) < STAR_THRESHOLD) return float3(0.0, 0.0, 0.0); // no star in this cell
    // Per-star randoms: size, centre (inset so the rotated square stays inside the cell), rotation.
    float sz = STAR_HALF * (0.6 + 0.8 * hash13(id + 53.0));
    float inset = sz * 1.5; // keeps the rotated square clear of the cell edge
    float2 center = inset + (1.0 - 2.0 * inset) * float2(hash13(id + 11.0), hash13(id + 23.0));
    float2 q = frac(g) - center;
    float ang = hash13(id + 91.0) * 6.2831853;        // random per-star rotation (vanilla zRot)
    float cs = cos(ang), sn = sin(ang);
    q = float2(cs * q.x - sn * q.y, sn * q.x + cs * q.y); // GLSL mat2(cs,-sn,sn,cs) * q, written out
    float box = max(abs(q.x), abs(q.y));               // Chebyshev distance → square
    float star = 1.0 - smoothstep(sz * 0.6, sz, box);  // soft-edged square (helps DLSS stability)
    if (star <= 0.0) return float3(0.0, 0.0, 0.0);
    float bright = 0.55 + 0.45 * hash13(id + 71.0);    // mild per-star brightness variation
    star *= bright * min(dir.y * 3.0, 1.0) * max(0.0, 1.0 - pow(abs(VdotS) * 1.002, 100.0));
    // Per-star colour temperature: hash picks a spot on a warm-orange ↔ blue-white ramp (real star
    // populations). Average luminance close to the old uniform constant so night exposure doesn't shift.
    float3 starCol = lerp(float3(0.78, 0.60, 0.42), float3(0.50, 0.62, 0.95), hash13(id + 137.0));
    return 0.2 * star * starCol * starBrightness;
}

[shader("miss")]
void main(inout Payload payload) {
    WorldPush pc = ConstPtr<WorldPush>(pcAddr.worldPushAddr)[0];
    float3 dir = normalize(WorldRayDirection());
    float day = pc.sunDir.w;
    float3 sd = pc.sunDir.xyz;
    float SdotU = sd.y;
    float VdotS = dot(dir, sd);
    float sunUp = clamp((SdotU + 0.0625) / 0.125, 0.0, 1.0); // 0 well below horizon .. 1 above
    float starBrightness = pc.lightRadiance.w;
    bool showCelestial = (payload.flags & PAYLOAD_SHOW_CELESTIAL) != 0u;

    // Physically-based in-scattering: blue zenith, bright/desaturated horizon, warm Mie halo + red
    // sunset all fall out of the Rayleigh/Mie/ozone march. At night the sun is below the horizon so the
    // light march is blocked → near-zero; a faint night ambient + the rotating starfield sit underneath.
    //
    // Soft horizon: below-horizon rays used to march into the planet-surface clip, drawing a hard fixed
    // line across the sky. Instead, march the ray flattened onto the horizon (same azimuth, y=0) so the
    // gradient continues seamlessly downward, with an exponential falloff so it settles into darkness
    // instead of a uniform bright band.
    float3 mdir = dir;
    float downFade = 1.0;
    if (dir.y < 0.0) {
        float2 flat2 = float2(dir.x, dir.z);
        float fl = length(flat2);
        mdir = fl > 1.0e-4 ? float3(flat2.x / fl, 0.0, flat2.y / fl) : float3(1.0, 0.0, 0.0);
        downFade = exp(dir.y * 3.0);
    }
    float3 col = atmosphere(mdir, sd);
    // Luminance-preserving vibrance on the in-scatter only (see SKY_SATURATION).
    float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = max(lerp(float3(lum, lum, lum), col, SKY_SATURATION), float3(0.0, 0.0, 0.0));
    float up = clamp(dir.y, 0.0, 1.0);
    float3 nightAmbient = lerp(NIGHT_HORIZON, NIGHT_ZENITH, up);
    col += nightAmbient * (1.0 - day);
    col *= downFade;
    col += stars(dir, starBrightness, VdotS, pc.celestial);

    // Sun & moon discs — sampled from the vanilla celestials atlas, gated to rays that haven't already
    // accounted for the light via diffuse NEE. squareBody gives the body-local [-1,1] coord; the
    // sprite's own texel coverage (alpha) shapes the disc.
    if (showCelestial) {
        float2 local;
        squareBody(dir, sd, SUN_DISC_HALF_ANGLE, pc.celestial.xyz, local);
        if (all(abs(local) < float2(1.0, 1.0))) {
            float2 uv = lerp(pc.sunUv.xy, pc.sunUv.zw, local * 0.5 + 0.5);
            float3 t = celestialsAtlas.SampleLevel(uv, 0.0).rgb;
            // The vanilla sun sprite bakes a soft glow gradient into its texels; scaling that raw to HDR
            // makes a huge ring. Raise the texture luminance to a high power — only the bright core
            // survives, the halo collapses to ~0.
            float core = pow(clamp(dot(t, t) * 0.45, 0.0, 1.0), 6.0);
            // transmittanceToSpace reddens+dims the disc through the sunset on the sky's own curve.
            col += SUN_DISC_RADIANCE * transmittanceToSpace(dir) * core * sunUp;
        }
        // Same horizon gate as the sun: with the soft-horizon change, below-horizon rays no longer clip
        // at the planet, so without this the moon disc would stay visible after it set.
        float moonUp = clamp((pc.moonDir.y + 0.0625) / 0.125, 0.0, 1.0);
        squareBody(dir, pc.moonDir.xyz, MOON_DISC_HALF_ANGLE, pc.celestial.xyz, local);
        if (all(abs(local) < float2(1.0, 1.0)) && moonUp > 0.0) {
            float2 uv = lerp(pc.moonUv.xy, pc.moonUv.zw, local * 0.5 + 0.5);
            float3 t = celestialsAtlas.SampleLevel(uv, 0.0).rgb;
            // Moon: a smoothstep contrast ramp suppresses the faint halo while keeping the lit phase
            // shape from the texture. The transmittance tint gives a warm amber moon at moonrise/set.
            float m = smoothstep(0.0, 1.0, min(length(t), 1.0)) * 1.3;
            col += MOON_DISC_RADIANCE * transmittanceToSpace(dir) * t * m * pow2(1.0 - day) * moonUp;
        }
    }

    payload.albedo = max(col, float3(0.0, 0.0, 0.0)); // raygen reads this as sky radiance + bounce-0 guide
    payload.hitT = -1.0;
    payload.normal = float3(0.0, 0.0, 0.0);
    payload.motionPrev = float3(0.0, 0.0, 0.0);
    payload.f0 = float3(0.0, 0.0, 0.0);
    payload.flags = 0u;
    payload.roughMetal = packHalf2(float2(1.0, 0.0));
    payload.emissionSss = packHalf2(float2(0.0, 0.0));
    payload.rayCone = 0u;
}
