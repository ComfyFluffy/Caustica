#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

// Ray miss = sky. Per-frame sky data comes from the WorldPush BDA buffer addressed by an 8-byte push
// constant. Gotcha: the ray-tracing pipeline must include MISS in pcStages or this shader cannot read `pc`.
// The shader writes full sky radiance (atmosphere gradient + sun disc + moon phase disc + stars) into
// payload.albedo; raygen consumes that value for both accumulated radiance and bounce-0 denoiser guides.
//
// Celestial discs (sun/moon) are gated by a packed payload flag, which raygen sets per-ray from the path
// state: true on the primary ray and after every specular/dielectric bounce (so the sun/moon show up in
// water/glass/metal reflections + refractions), false after a diffuse vertex did sun/moon NEE (so the
// disc isn't double-counted against the direct light → fireflies). The atmosphere gradient + stars are
// added on every miss (any bounce): stars aren't a NEE light, so they never double-count.
struct WorldPush {
    mat4 invViewProj;
    vec3 camOffset;
    uint64_t tableAddr;
    uint debugView; uint frameIndex;
    mat4 prevViewProj;
    vec3 camDelta; uint spp;
    vec2 jitter;
    uint64_t entityTableAddr;
    uint flags;
    vec4 sunDir;         // 208 xyz true sun direction, w = dayFactor 0..1
    vec4 lightDir;       // 224 xyz active NEE light dir, w = square half-angle (rad)
    vec4 lightRadiance;  // 240 xyz HDR radiance of the active light, w = star brightness (vanilla, 0..1)
    vec4 moonDir;        // 256 xyz moon direction, w = moonPhase (0 full .. 4 new, Minecraft convention)
    vec4 celestial;      // 272 xyz celestial rotation axis (sun/moon frame + star pole), w = star angle (rad)
    vec4 sunUv;          // 288 vanilla sun sprite atlas rect (u0,v0,u1,v1)
    vec4 moonUv;         // 304 vanilla current-moon-phase sprite atlas rect (u0,v0,u1,v1)
};
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer WorldPushRef { WorldPush v; };
layout(push_constant) uniform PushAddr { uint64_t worldPushAddr; } pcAddr;
#define pc WorldPushRef(pcAddr.worldPushAddr).v

// Vanilla celestials atlas (sun + moon-phase sprites), bound by RtComposite. Sampled with an explicit
// LOD (no derivatives in a miss shader). The sun/moon discs are drawn from its real texels.
layout(binding = 11, set = 0) uniform sampler2D celestialsAtlas;

struct Payload {
    vec3 albedo;
    vec3 normal;
    float hitT;
    vec3 motionPrev;
    vec3 f0;
    uint flags;       // bits 0..1 = material, bit 2 = show celestial disc on miss
    uint roughMetal;  // packHalf2x16(vec2(roughness, metalness))
    uint emissionSss; // packHalf2x16(vec2(emission, sss))
    uint rayCone;     // packHalf2x16(vec2(width, spread))
};
layout(location = 0) rayPayloadInEXT Payload payload;

const uint PAYLOAD_SHOW_CELESTIAL = 4u;
bool payloadShowCelestial() { return (payload.flags & PAYLOAD_SHOW_CELESTIAL) != 0u; }

const float PI = 3.14159265359;
float pow2(float x) { return x * x; }

const vec3 SUN_DISC_RADIANCE  = vec3(22.0, 19.0, 15.0);
const vec3 MOON_DISC_RADIANCE = vec3(1.7, 1.85, 2.2);   // lit-side moon (HDR, reads bright at night exposure)
const vec3 STAR_COLOR         = vec3(0.62, 0.66, 0.80);
// VISIBLE disc half-angles, matched to vanilla: the sun/moon quads are drawn at half-width 30/20 at
// distance 100 → half-angle = atan(0.30) / atan(0.20). Decoupled from the NEE light radius (≈0.6°).
const float SUN_DISC_HALF_ANGLE  = 0.29146; // atan(30/100), vanilla sun size
const float MOON_DISC_HALF_ANGLE = 0.19740; // atan(20/100), vanilla moon size
// Faint night-sky ambient so the night isn't pure black where the atmosphere in-scatter falls to zero.
const vec3 NIGHT_ZENITH  = vec3(0.004, 0.008, 0.022) / 2.0;
const vec3 NIGHT_HORIZON = vec3(0.015, 0.022, 0.045) / 2.0;

// ---- Physically-based atmosphere (Nishita / O'Neil single scattering). A view ray is marched through
// the atmospheric shell; at each step the transmittance toward the sun is integrated (a second, shorter
// march), giving Rayleigh (blue-sky / red-sunset) + Mie (sun halo) in-scattered radiance. World up = +Y;
// the camera sits a couple km above a spherical planet. Output is HDR in the same units as tracePath.
const float PLANET_R   = 6371000.0;
const float ATMOS_R    = 6471000.0;        // 100 km shell
const vec3  RAY_BETA   = vec3(5.5e-6, 13.0e-6, 22.4e-6); // Rayleigh scattering at sea level
const float MIE_BETA   = 21.0e-6;          // Mie scattering at sea level
const float RAY_SCALE_H = 8000.0;          // Rayleigh density scale height
const float MIE_SCALE_H = 1200.0;          // Mie density scale height
const float MIE_G      = 0.758;            // Mie anisotropy (forward-scattering)
const float SUN_INTENSITY = 22.0;
const int   ATMOS_PRIMARY_STEPS = 16;
const int   ATMOS_LIGHT_STEPS   = 8;

// Smaller root of ray vs sphere of radius r centred at origin (negative if behind / no hit handled by x>y).
vec2 raySphere(vec3 o, vec3 d, float r) {
    float b = dot(o, d);
    float c = dot(o, o) - r * r;
    float disc = b * b - c;
    if (disc < 0.0) return vec2(1.0e9, -1.0e9);
    disc = sqrt(disc);
    return vec2(-b - disc, -b + disc);
}

vec3 atmosphere(vec3 dir, vec3 sunDir) {
    vec3 orig = vec3(0.0, PLANET_R + 2000.0, 0.0);
    vec2 t = raySphere(orig, dir, ATMOS_R);
    if (t.x > t.y) return vec3(0.0);              // ray misses the atmosphere entirely
    float tStart = max(t.x, 0.0);
    float tEnd = t.y;
    vec2 tGround = raySphere(orig, dir, PLANET_R);
    if (tGround.x > 0.0) tEnd = min(tEnd, tGround.x); // clip the march at the planet surface
    float segLen = (tEnd - tStart) / float(ATMOS_PRIMARY_STEPS);

    float mu = dot(dir, sunDir);
    float phaseR = 3.0 / (16.0 * PI) * (1.0 + mu * mu);
    float g = MIE_G;
    float phaseM = 3.0 / (8.0 * PI) * ((1.0 - g * g) * (1.0 + mu * mu))
                 / ((2.0 + g * g) * pow(max(1.0 + g * g - 2.0 * g * mu, 0.0), 1.5));

    vec3 sumR = vec3(0.0), sumM = vec3(0.0);
    float odR = 0.0, odM = 0.0;                   // view-ray optical depth accumulators
    float tCur = tStart;
    for (int i = 0; i < ATMOS_PRIMARY_STEPS; i++) {
        vec3 p = orig + dir * (tCur + segLen * 0.5);
        float h = length(p) - PLANET_R;
        float hr = exp(-h / RAY_SCALE_H) * segLen;
        float hm = exp(-h / MIE_SCALE_H) * segLen;
        odR += hr; odM += hm;

        // March toward the sun to accumulate the light-ray optical depth (transmittance to space).
        vec2 tl = raySphere(p, sunDir, ATMOS_R);
        float segLenL = tl.y / float(ATMOS_LIGHT_STEPS);
        float tlCur = 0.0, odRL = 0.0, odML = 0.0;
        bool blocked = false;
        for (int j = 0; j < ATMOS_LIGHT_STEPS; j++) {
            vec3 pl = p + sunDir * (tlCur + segLenL * 0.5);
            float hl = length(pl) - PLANET_R;
            if (hl < 0.0) { blocked = true; break; } // sun is below this point's horizon → in shadow
            odRL += exp(-hl / RAY_SCALE_H) * segLenL;
            odML += exp(-hl / MIE_SCALE_H) * segLenL;
            tlCur += segLenL;
        }
        if (!blocked) {
            vec3 tau = RAY_BETA * (odR + odRL) + MIE_BETA * 1.1 * (odM + odML);
            vec3 atten = exp(-tau);
            sumR += hr * atten;
            sumM += hm * atten;
        }
        tCur += segLen;
    }
    return SUN_INTENSITY * (sumR * RAY_BETA * phaseR + sumM * MIE_BETA * phaseM);
}

// Build the celestial body's square tangent frame: `right` along the arc-travel direction, `up`
// perpendicular within the sky. Shared by the visible disc and the NEE square sampling in raygen.
void celestialFrame(vec3 dir, out vec3 right, out vec3 up) {
    right = normalize(cross(dir, pc.celestial.xyz));
    up = cross(right, dir);
}

// Gnomonic projection of `dir` into the body's tangent square, in units of the half-angle. local ∈
// [-1,1]² is inside the square. Returns 0 behind the body. The square (not a cone) matches MC's quad.
float squareBody(vec3 dir, vec3 bodyDir, float halfAngle, out vec2 local) {
    float c = dot(dir, bodyDir);
    if (c <= 1.0e-3) { local = vec2(2.0); return 0.0; }
    vec3 d = dir / c;                  // d·bodyDir == 1 (point on the tangent plane)
    vec3 r, u; celestialFrame(bodyDir, r, u);
    float t = tan(halfAngle);
    local = vec2(dot(d, r), dot(d, u)) / t;
    float m = max(abs(local.x), abs(local.y));
    return 1.0 - smoothstep(0.90, 1.0, m); // soft square edge
}

// Dave Hoskins' hash13 (https://www.shadertoy.com/view/4djSRW): well-distributed and pattern-free.
// fract(sin(dot(...))) aliases — sin of a linear lattice ramp repeats in bands, so stars align on lines.
float hash13(vec3 p3) {
    p3 = fract(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return fract((p3.x + p3.y) * p3.z);
}

// Rotate v about a unit axis by angle (Rodrigues). Used to wheel the starfield about the celestial pole.
vec3 rotateAxis(vec3 v, vec3 axis, float ang) {
    float c = cos(ang), s = sin(ang);
    return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);
}

// Procedural starfield, a faithful port of vanilla's buildStars(): ~1500 discrete stars at random
// sphere directions, each a small SQUARE billboard of random size + random rotation. We can't loop 1500
// stars per pixel, so a cube-map grid is just a spatial accelerator — at most one star per cell, placed
// at a hashed position INSET from the cell edges (so its square never crosses into a neighbour → never
// clipped/deformed), drawn as a hash-rotated square of hashed size. The lookup direction is rotated about
// the celestial pole by -STAR_ANGLE so the field wheels with world time like the real sky.
const float STAR_CELLS     = 21.0;  // cube-face grid resolution; with the threshold gives ~1500 stars
const float STAR_THRESHOLD = 0.86;  // cell occupancy cutoff (higher = fewer stars)
const float STAR_HALF      = 0.045; // star half-size as a fraction of a cell (≈ vanilla 0.11° at center)
vec3 stars(vec3 dir, float starBrightness, float VdotS) {
    if (starBrightness <= 0.0 || dir.y <= 0.0) return vec3(0.0);
    // Negative angle: we rotate the LOOKUP direction, which is the inverse of rotating star geometry, so
    // -starAngle makes the field wheel the SAME way as the sun/moon (whose world dirs are forward-rotated
    // on the CPU). Rotating by +starAngle here counter-rotates the stars against the sun/moon.
    vec3 sdir = rotateAxis(dir, normalize(pc.celestial.xyz), -pc.celestial.w);
    // Cube-map face + 2D gnomonic uv → uniform square cells, no pole/equator distortion.
    vec3 a = abs(sdir);
    vec2 uv;
    float face;
    if (a.x >= a.y && a.x >= a.z)      { uv = sdir.yz / a.x; face = sdir.x > 0.0 ? 0.0 : 1.0; }
    else if (a.y >= a.z)               { uv = sdir.xz / a.y; face = sdir.y > 0.0 ? 2.0 : 3.0; }
    else                               { uv = sdir.xy / a.z; face = sdir.z > 0.0 ? 4.0 : 5.0; }
    vec2 g = uv * STAR_CELLS;
    vec3 id = vec3(floor(g), face);
    if (hash13(id) < STAR_THRESHOLD) return vec3(0.0); // no star in this cell
    // Per-star randoms: size, centre (inset so the rotated square stays inside the cell), rotation.
    float sz = STAR_HALF * (0.6 + 0.8 * hash13(id + 53.0));
    float inset = sz * 1.5; // > sz*sqrt(2)/... keeps the rotated square clear of the cell edge
    vec2 center = inset + (1.0 - 2.0 * inset) * vec2(hash13(id + 11.0), hash13(id + 23.0));
    vec2 q = fract(g) - center;
    float ang = hash13(id + 91.0) * 6.2831853;        // random per-star rotation (vanilla zRot)
    float cs = cos(ang), sn = sin(ang);
    q = mat2(cs, -sn, sn, cs) * q;
    float box = max(abs(q.x), abs(q.y));               // Chebyshev distance → square
    float star = 1.0 - smoothstep(sz * 0.6, sz, box);  // soft-edged square (helps DLSS stability)
    if (star <= 0.0) return vec3(0.0);
    float bright = 0.55 + 0.45 * hash13(id + 71.0);    // mild per-star brightness variation
    star *= bright * min(dir.y * 3.0, 1.0) * max(0.0, 1.0 - pow(abs(VdotS) * 1.002, 100.0));
    return 0.2 * star * STAR_COLOR * starBrightness;
}

void main() {
    vec3 dir = normalize(gl_WorldRayDirectionEXT);
    float day = pc.sunDir.w;
    vec3 sd = pc.sunDir.xyz;
    float SdotU = sd.y;
    float VdotS = dot(dir, sd);
    float sunUp = clamp((SdotU + 0.0625) / 0.125, 0.0, 1.0); // 0 well below horizon .. 1 above
    float starBrightness = pc.lightRadiance.w;

    // Physically-based in-scattering: blue zenith, bright/desaturated horizon, warm Mie halo + red
    // sunset all fall out of the Rayleigh/Mie march. At night the sun is below the horizon so the light
    // march is blocked → near-zero; we add a faint night ambient + the rotating starfield underneath.
    vec3 col = atmosphere(dir, sd);
    float up = clamp(dir.y, 0.0, 1.0);
    vec3 nightAmbient = mix(NIGHT_HORIZON, NIGHT_ZENITH, up);
    col += nightAmbient * (1.0 - day);
    col += stars(dir, starBrightness, VdotS);

    // Sun & moon discs — sampled from the vanilla celestials atlas, gated to rays that haven't already
    // accounted for the light via diffuse NEE. squareBody gives the body-local [-1,1] coord; the sprite's
    // own texel coverage (alpha) shapes the disc. The moon sprite is the current phase, so its art already
    // encodes the crescent/gibbous shape — no procedural terminator needed.
    if (payloadShowCelestial()) {
        vec2 local;
        squareBody(dir, sd, SUN_DISC_HALF_ANGLE, local);
        if (all(lessThan(abs(local), vec2(1.0)))) {
            vec2 uv = mix(pc.sunUv.xy, pc.sunUv.zw, local * 0.5 + 0.5);
            vec3 t = textureLod(celestialsAtlas, uv, 0.0).rgb;
            // The vanilla sun sprite bakes a soft glow gradient into its texels; scaling that raw to HDR
            // makes a huge ring. Raise the texture luminance to a high power — only the bright core
            // survives, the halo collapses to ~0.
            float core = pow(clamp(dot(t, t) * 0.45, 0.0, 1.0), 6.0);
            col += SUN_DISC_RADIANCE * core * sunUp;
        }
        squareBody(dir, pc.moonDir.xyz, MOON_DISC_HALF_ANGLE, local);
        if (all(lessThan(abs(local), vec2(1.0)))) {
            vec2 uv = mix(pc.moonUv.xy, pc.moonUv.zw, local * 0.5 + 0.5);
            vec3 t = textureLod(celestialsAtlas, uv, 0.0).rgb;
            // Moon: a smoothstep contrast ramp suppresses the faint halo while
            // keeping the lit phase shape from the texture.
            float m = smoothstep(0.0, 1.0, min(length(t), 1.0)) * 1.3;
            col += MOON_DISC_RADIANCE * t * m * pow2(1.0 - day);
        }
    }

    payload.albedo = max(col, vec3(0.0)); // raygen reads this as the sky radiance + bounce-0 guide
    payload.hitT = -1.0;
    payload.normal = vec3(0.0);
    payload.motionPrev = vec3(0.0);
    payload.f0 = vec3(0.0);
    payload.flags = 0u;
    payload.roughMetal = packHalf2x16(vec2(1.0, 0.0));
    payload.emissionSss = packHalf2x16(vec2(0.0, 0.0));
    payload.rayCone = 0u;
}
