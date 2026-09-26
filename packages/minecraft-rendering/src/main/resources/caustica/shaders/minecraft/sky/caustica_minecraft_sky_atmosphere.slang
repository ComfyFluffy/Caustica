// Hillaire atmosphere model shared by the LUT bakes and the Overworld environment evaluator.
// Transmittance and multiple-scattering tables are reused between bakes; the sky-view table stores
// separate sun and moon slices each frame. Lengths are kilometres and extinction coefficients are 1/km.
// Illuminance is in lux and output luminance in cd/m^2. Colors use linear BT.709 until the environment
// evaluator converts the result to ACEScg.
module caustica_minecraft_sky_atmosphere;

import caustica_resources;


public static const float SKY_PI = 3.14159265359;
/** Luminance weights of linear BT.709. */
public static const float3 BT709_LUMINANCE = float3(0.2126, 0.7152, 0.0722);

// ---- Medium. Rayleigh/Mie/ozone coefficients are Hillaire's Earth reference values.
public static const float ATMOS_BOTTOM_KM = 6360.0;
public static const float ATMOS_TOP_KM    = 6460.0;
public static const float3 RAYLEIGH_SCATTERING = float3(0.005802, 0.013558, 0.033100);
public static const float  RAYLEIGH_SCALE_H_KM = 8.0;
public static const float  MIE_SCATTERING      = 0.003996;
public static const float  MIE_EXTINCTION      = 0.004440;   // scattering + absorption
public static const float  MIE_SCALE_H_KM      = 1.2;
public static const float  MIE_G               = 0.8;
// Ozone absorbs without scattering, in a tent peaking at 25 km. Its green-heavy absorption is what keeps
// a noon zenith deep blue and turns twilight through purple rather than straight from orange to grey.
public static const float3 OZONE_ABSORPTION = float3(0.000650, 0.001881, 0.000085);
public static const float  OZONE_CENTER_KM  = 25.0;
public static const float  OZONE_WIDTH_KM   = 15.0;

// ---- LUT dimensions. Mirrored by SkyLutPass; the dispatch extents and the parameterisations
// below must agree exactly.
public static const int TRANSMITTANCE_LUT_W = 256;
public static const int TRANSMITTANCE_LUT_H = 64;
public static const int MULTISCATTER_LUT_W  = 32;
public static const int MULTISCATTER_LUT_H  = 32;
public static const int SKY_VIEW_LUT_W      = 192;
public static const int SKY_VIEW_LUT_H      = 108;
// The sky-view image stacks one slice per celestial body: rows [0,108) are the sun's contribution and
// rows [108,216) the moon's. Two independent slices let moonlight and sunlight scatter through the same
// atmosphere without crossfading between the lighting solutions.
public static const int SKY_VIEW_BODY_COUNT = 2;
public static const int SKY_VIEW_LUT_TOTAL_H = SKY_VIEW_LUT_H * SKY_VIEW_BODY_COUNT;
public static const int SKY_VIEW_BODY_SUN  = 0;
public static const int SKY_VIEW_BODY_MOON = 1;

// Raymarch step counts. The sky-view LUT is amortised over the whole frame, so it can afford a count that
// leaves no visible banding at the horizon, which is where the parameterisation concentrates its texels.
public static const int TRANSMITTANCE_STEPS = 40;
public static const int SKY_VIEW_STEPS      = 32;
public static const int MULTISCATTER_STEPS  = 20;
public static const int MULTISCATTER_DIRS   = 64; // 8x8 sphere directions per texel

public struct MediumSample {
    public float3 scattering;  // Rayleigh + Mie (the part that redirects light)
    public float3 extinction;  // scattering + absorption (Mie absorption + ozone)
    public float3 rayleigh;
    public float  mie;
};

public MediumSample sampleMedium(float altitudeKm) {
    float rayleighDensity = exp(-max(altitudeKm, 0.0) / RAYLEIGH_SCALE_H_KM);
    float mieDensity = exp(-max(altitudeKm, 0.0) / MIE_SCALE_H_KM);
    float ozoneDensity = max(0.0, 1.0 - abs(altitudeKm - OZONE_CENTER_KM) / OZONE_WIDTH_KM);

    MediumSample m;
    m.rayleigh = RAYLEIGH_SCATTERING * rayleighDensity;
    m.mie = MIE_SCATTERING * mieDensity;
    m.scattering = m.rayleigh + float3(m.mie);
    m.extinction = m.rayleigh + float3(MIE_EXTINCTION * mieDensity)
                 + OZONE_ABSORPTION * ozoneDensity;
    return m;
}

public float rayleighPhase(float cosTheta) {
    return 3.0 / (16.0 * SKY_PI) * (1.0 + cosTheta * cosTheta);
}

// Cornette-Shanks, the phase function Hillaire's coefficients are fitted against.
public float miePhase(float cosTheta) {
    float g = MIE_G;
    float k = 3.0 / (8.0 * SKY_PI) * (1.0 - g * g) / (2.0 + g * g);
    return k * (1.0 + cosTheta * cosTheta) / pow(max(1.0 + g * g - 2.0 * g * cosTheta, 1.0e-4), 1.5);
}

public static const float ISOTROPIC_PHASE = 1.0 / (4.0 * SKY_PI);

/** Nearest non-negative ray/sphere (centred at the origin) intersection distance, or -1 for a miss. */
public float raySphereNearest(float3 origin, float3 dir, float radius) {
    float b = dot(origin, dir);
    float c = dot(origin, origin) - radius * radius;
    float disc = b * b - c;
    if (disc < 0.0) {
        return -1.0;
    }
    disc = sqrt(disc);
    float t0 = -b - disc;
    float t1 = -b + disc;
    if (t1 < 0.0) {
        return -1.0;
    }
    return t0 < 0.0 ? t1 : t0;
}

/** Farthest ray/sphere intersection distance (the exit point), or -1 when the ray misses entirely. */
public float raySphereFarthest(float3 origin, float3 dir, float radius) {
    float b = dot(origin, dir);
    float c = dot(origin, origin) - radius * radius;
    float disc = b * b - c;
    if (disc < 0.0) {
        return -1.0;
    }
    return -b + sqrt(disc);
}

// ---- Transmittance LUT parameterisation (Bruneton). The mapping is by DISTANCE to the top of the
// atmosphere rather than by the angle itself, which is what keeps texels dense exactly where the optical
// depth changes fastest: the grazing directions around the horizon.
public float2 transmittanceLutUv(float radiusKm, float cosZenith) {
    float h = sqrt(max(ATMOS_TOP_KM * ATMOS_TOP_KM - ATMOS_BOTTOM_KM * ATMOS_BOTTOM_KM, 0.0));
    float rho = sqrt(max(radiusKm * radiusKm - ATMOS_BOTTOM_KM * ATMOS_BOTTOM_KM, 0.0));
    float discriminant = radiusKm * radiusKm * (cosZenith * cosZenith - 1.0) + ATMOS_TOP_KM * ATMOS_TOP_KM;
    float d = max(0.0, -radiusKm * cosZenith + sqrt(max(discriminant, 0.0)));
    float dMin = ATMOS_TOP_KM - radiusKm;
    float dMax = rho + h;
    return float2((d - dMin) / max(dMax - dMin, 1.0e-6), rho / max(h, 1.0e-6));
}

public void transmittanceLutParams(float2 uv, out float radiusKm, out float cosZenith) {
    float h = sqrt(max(ATMOS_TOP_KM * ATMOS_TOP_KM - ATMOS_BOTTOM_KM * ATMOS_BOTTOM_KM, 0.0));
    float rho = h * uv.y;
    radiusKm = sqrt(rho * rho + ATMOS_BOTTOM_KM * ATMOS_BOTTOM_KM);
    float dMin = ATMOS_TOP_KM - radiusKm;
    float dMax = rho + h;
    float d = dMin + uv.x * (dMax - dMin);
    cosZenith = d == 0.0 ? 1.0
            : (h * h - rho * rho - d * d) / (2.0 * radiusKm * d);
    cosZenith = clamp(cosZenith, -1.0, 1.0);
}

// Map the parameterization's closed [0,1] interval to the outermost texel centers. Normalized texture
// coordinates 0 and 1 lie at texture edges, so sampling needs this half-texel adjustment.
public float2 unitToSubUv(float2 uv, float2 size) {
    return (uv * (size - 1.0) + 0.5) / size;
}

public float2 subUvToUnit(float2 uv, float2 size) {
    return (uv * size - 0.5) / (size - 1.0);
}

public float3 sampleTransmittance(SampledTexture2DIndex lut, SamplerIndex samplerIndex,
        float radiusKm, float cosZenith) {
    float2 uv = transmittanceLutUv(radiusKm, cosZenith);
    uv = unitToSubUv(saturate(uv), float2(float(TRANSMITTANCE_LUT_W), float(TRANSMITTANCE_LUT_H)));
    return sampleTexture2DLod(lut, samplerIndex, uv, 0.0).rgb;
}

/**
 * Viewer-to-space transmittance for a unit world direction, with +Y up.
 * The LUT ends at the ground-tangent ray, so lower directions need an explicit horizon gate.
 * The light's angular radius softens that gate over its apparent width.
 */
public float3 transmittanceToSpace(SampledTexture2DIndex lut, SamplerIndex samplerIndex,
        float viewerRadiusKm, float3 dir, float angularRadius) {
    float horizonCos = horizonZenithCos(viewerRadiusKm);
    float softness = max(sin(angularRadius), 1.0e-4);
    float aboveHorizon = smoothstep(horizonCos - softness, horizonCos + softness, dir.y);
    return sampleTransmittance(lut, samplerIndex, viewerRadiusKm, dir.y) * aboveHorizon;
}

// ---- Multiple-scattering LUT parameterisation: linear in cos(sun zenith) and in altitude. The stored
// quantity is per unit illuminance, so one LUT serves the sun and the moon alike.
public float2 multiScatterLutUv(float radiusKm, float cosSunZenith) {
    float2 uv = float2(cosSunZenith * 0.5 + 0.5,
                       (radiusKm - ATMOS_BOTTOM_KM) / max(ATMOS_TOP_KM - ATMOS_BOTTOM_KM, 1.0e-6));
    return saturate(uv);
}

public void multiScatterLutParams(float2 uv, out float radiusKm, out float cosSunZenith) {
    cosSunZenith = clamp(uv.x * 2.0 - 1.0, -1.0, 1.0);
    radiusKm = lerp(ATMOS_BOTTOM_KM, ATMOS_TOP_KM, uv.y);
    // Keep off the exact boundaries: a sample sitting precisely on the ground or on the outer shell makes
    // the ray/sphere tests degenerate. The bottom uses the same fp32-derived floor as the viewer — a
    // smaller nudge would be lost to the cancellation in `r*r - bottom*bottom` (see
    // MIN_VIEWER_ALTITUDE_KM), leaving the LUT's ground row as garbage rather than as ground.
    radiusKm = clamp(radiusKm, ATMOS_BOTTOM_KM + MIN_VIEWER_ALTITUDE_KM,
                     ATMOS_TOP_KM - MIN_VIEWER_ALTITUDE_KM);
}

public float3 sampleMultiScatter(SampledTexture2DIndex lut, SamplerIndex samplerIndex,
        float radiusKm, float cosSunZenith) {
    float2 uv = multiScatterLutUv(radiusKm, cosSunZenith);
    uv = unitToSubUv(uv, float2(float(MULTISCATTER_LUT_W), float(MULTISCATTER_LUT_H)));
    return sampleTexture2DLod(lut, samplerIndex, uv, 0.0).rgb;
}

// Keep the viewer off the ground shell: exact contact gives a zero-distance ground intersection.
// The 50 m floor also limits cancellation in fp32 radius-squared differences at planet scale.
public static const float MIN_VIEWER_ALTITUDE_KM = 0.05;

/** Cosine of the zenith angle of the ground-tangent direction: the viewer's geometric horizon. */
public float horizonZenithCos(float viewerRadiusKm) {
    return -sqrt(max(1.0 - (ATMOS_BOTTOM_KM * ATMOS_BOTTOM_KM)
            / (viewerRadiusKm * viewerRadiusKm), 0.0));
}

// ---- Sky-view LUT parameterisation (Hillaire). Azimuth is measured FROM the light, which the dome is
// symmetric about, so two angles describe the whole sphere. The elevation mapping is split at the
// geometric horizon and squared on each side, putting the most texels exactly where the sky's gradient is
// steepest, keeping the horizon continuous and well resolved.
public float2 skyViewLutUv(float viewerRadiusKm, float viewZenithCos, float lightViewCos, int body) {
    float horizonSin = sqrt(max(viewerRadiusKm * viewerRadiusKm
            - ATMOS_BOTTOM_KM * ATMOS_BOTTOM_KM, 0.0)) / max(viewerRadiusKm, 1.0e-6);
    float horizonAngle = acos(clamp(horizonSin, -1.0, 1.0)); // angle from the DOWN axis to the horizon
    float zenithHorizonAngle = SKY_PI - horizonAngle;        // angle from the UP axis to the horizon
    float viewZenithAngle = acos(clamp(viewZenithCos, -1.0, 1.0));

    float v;
    if (viewZenithAngle < zenithHorizonAngle) {
        float coord = viewZenithAngle / max(zenithHorizonAngle, 1.0e-6);
        coord = 1.0 - sqrt(max(1.0 - coord, 0.0));
        v = coord * 0.5;
    } else {
        float coord = (viewZenithAngle - zenithHorizonAngle) / max(horizonAngle, 1.0e-6);
        v = sqrt(max(coord, 0.0)) * 0.5 + 0.5;
    }
    float u = sqrt(max(0.5 - 0.5 * lightViewCos, 0.0));

    float2 uv = unitToSubUv(float2(u, saturate(v)),
            float2(float(SKY_VIEW_LUT_W), float(SKY_VIEW_LUT_H)));
    // Fold the slice offset in after the half-texel correction so a body's rows never bleed into its
    // neighbour's: v is already inside [0.5/108, 1-0.5/108] of its own slice.
    return float2(uv.x, (uv.y + float(body)) / float(SKY_VIEW_BODY_COUNT));
}

public void skyViewLutParams(float2 uv, float viewerRadiusKm,
                             out float viewZenithCos, out float lightViewCos) {
    float2 unit = subUvToUnit(saturate(uv), float2(float(SKY_VIEW_LUT_W), float(SKY_VIEW_LUT_H)));
    float horizonSin = sqrt(max(viewerRadiusKm * viewerRadiusKm
            - ATMOS_BOTTOM_KM * ATMOS_BOTTOM_KM, 0.0)) / max(viewerRadiusKm, 1.0e-6);
    float horizonAngle = acos(clamp(horizonSin, -1.0, 1.0));
    float zenithHorizonAngle = SKY_PI - horizonAngle;

    float viewZenithAngle;
    if (unit.y < 0.5) {
        float coord = 1.0 - unit.y * 2.0;
        viewZenithAngle = zenithHorizonAngle * (1.0 - coord * coord);
    } else {
        float coord = unit.y * 2.0 - 1.0;
        viewZenithAngle = zenithHorizonAngle + horizonAngle * coord * coord;
    }
    viewZenithCos = cos(viewZenithAngle);
    lightViewCos = clamp(1.0 - 2.0 * unit.x * unit.x, -1.0, 1.0);
}

/**
 * The canonical frame the sky-view LUT is baked in: world up is +Y and the light's azimuth is placed on
 * +X. Any world configuration maps onto it, because the dome is rotationally symmetric about up and
 * mirror-symmetric about the light's meridian.
 */
public void skyViewLocalDirections(float lightZenithCos, float viewZenithCos, float lightViewCos,
                                   out float3 viewDir, out float3 lightDir) {
    float lightZenithSin = sqrt(max(1.0 - lightZenithCos * lightZenithCos, 0.0));
    lightDir = float3(lightZenithSin, lightZenithCos, 0.0);
    float viewZenithSin = sqrt(max(1.0 - viewZenithCos * viewZenithCos, 0.0));
    float lightViewSin = sqrt(max(1.0 - lightViewCos * lightViewCos, 0.0));
    viewDir = float3(viewZenithSin * lightViewCos, viewZenithCos, viewZenithSin * lightViewSin);
}

/** Cosine of the azimuth between a view direction and a light, both in world space (up = +Y). */
public float lightViewCosine(float3 viewDir, float3 lightDir) {
    float2 viewAzimuth = viewDir.xz;
    float2 lightAzimuth = lightDir.xz;
    float viewLen = length(viewAzimuth);
    float lightLen = length(lightAzimuth);
    if (viewLen < 1.0e-5 || lightLen < 1.0e-5) {
        return 1.0; // straight up/down, or a light at the zenith: azimuth is undefined and irrelevant
    }
    return clamp(dot(viewAzimuth / viewLen, lightAzimuth / lightLen), -1.0, 1.0);
}

public struct ScatteringResult {
    public float3 luminance;      // cd/m²
    public float3 multiScatAs1;   // per-unit-illuminance response used to build the multiscatter LUT
};

/**
 * Single-scattering raymarch with an optional multiple-scattering lookup, following Hillaire's
 * IntegrateScatteredLuminance.
 *
 * <p>{@code lightAngularRadius} softens the planet's shadow on the atmosphere over the light's finite
 * angular size instead of switching it per sample, preventing the transition from landing on the
 * primary march's sample lattice.
 *
 * <p>{@code beamFraction} is the part of the light that arrives as a collimated beam and scatters through the
 * Rayleigh and Mie phase functions; the rest arrives diffused and scatters isotropically. Every phase function
 * integrates to one over the sphere, so the fraction moves in-scattered light between directions without
 * changing its total: zero leaves no Mie aureole around the light.
 *
 * <p>{@code clipGround} false marches straight past the planet to the far side of the shell and adds no
 * ground bounce. Evaluated exactly along the ground-tangent direction that is the limit of the
 * above-horizon sky, which is what {@code sky_view.comp} blends the below-horizon rows toward.
 */
public ScatteringResult integrateScatteredLuminance(
        float3 originKm, float3 dir, float3 lightDir, float3 illuminance,
        float lightAngularRadius, int steps, float beamFraction,
        bool useMultiScatter, SampledTexture2DIndex transmittanceLut,
        SampledTexture2DIndex multiScatterLut, SamplerIndex samplerIndex,
        float groundAlbedo, bool clipGround) {
    ScatteringResult result;
    result.luminance = float3(0.0);
    result.multiScatAs1 = float3(0.0);

    float tTop = raySphereFarthest(originKm, dir, ATMOS_TOP_KM);
    if (tTop <= 0.0) {
        return result; // outside the atmosphere looking away from it
    }
    float tGround = raySphereNearest(originKm, dir, ATMOS_BOTTOM_KM);
    bool hitGround = clipGround && tGround > 0.0;
    float tMax = hitGround ? tGround : tTop;

    float cosTheta = dot(dir, lightDir);
    float phaseR = lerp(ISOTROPIC_PHASE, rayleighPhase(cosTheta), beamFraction);
    float phaseM = lerp(ISOTROPIC_PHASE, miePhase(cosTheta), beamFraction);
    float shadowSoftness = max(sin(lightAngularRadius), 1.0e-4);

    float3 throughput = float3(1.0);
    float dt = tMax / float(steps);
    for (int i = 0; i < steps; i++) {
        float t = (float(i) + 0.5) * dt;
        float3 p = originKm + dir * t;
        float radius = length(p);
        float3 up = p / max(radius, 1.0e-6);
        float altitude = radius - ATMOS_BOTTOM_KM;
        MediumSample medium = sampleMedium(altitude);
        float3 stepTransmittance = exp(-medium.extinction * dt);

        // Planet shadow, softened over the light's angular radius: `clearance` is how far the light sits
        // above this sample's own geometric horizon, which rises as the sample does — so high-altitude
        // samples stay lit well after the ground below them is in shadow. That altitude dependence is
        // what makes a real twilight glow, and it is the reason no artificial fill term is needed.
        float horizonDipSin = sqrt(max(1.0 - (ATMOS_BOTTOM_KM * ATMOS_BOTTOM_KM) / (radius * radius), 0.0));
        float clearance = dot(up, lightDir) + horizonDipSin;
        float lightVisibility = smoothstep(-shadowSoftness, shadowSoftness, clearance);

        float cosLightZenith = dot(up, lightDir);
        float3 transmittanceToLight = sampleTransmittance(
                transmittanceLut, samplerIndex, radius, cosLightZenith);
        float3 inScatter = lightVisibility * transmittanceToLight
                * (medium.rayleigh * phaseR + medium.mie * phaseM);
        if (useMultiScatter) {
            // Multiple scattering arrives from every direction, so it couples to the total scattering
            // coefficient rather than to a phase function.
            inScatter += sampleMultiScatter(
                    multiScatterLut, samplerIndex, radius, cosLightZenith) * medium.scattering;
        }

        // Analytic integration of the constant-medium segment (Hillaire): exact for the step rather than
        // a midpoint sample, which is what lets this run at 32 steps without banding.
        float3 safeExtinction = max(medium.extinction, float3(1.0e-9));
        float3 scattered = illuminance * inScatter;
        result.luminance += throughput * (scattered - scattered * stepTransmittance) / safeExtinction;

        float3 msIntegral = (medium.scattering - medium.scattering * stepTransmittance) / safeExtinction;
        result.multiScatAs1 += throughput * msIntegral;

        throughput *= stepTransmittance;
    }

    if (hitGround && groundAlbedo > 0.0) {
        // Lambertian ground bounce. Almost always hidden behind terrain in a Minecraft world, but it is
        // what keeps a below-horizon direction (over a void, or through the world edge) a plausible dim
        // colour instead of black, with no separate below-horizon code path to seam against.
        float3 p = originKm + dir * tMax;
        float3 up = p / max(length(p), 1.0e-6);
        float cosLightZenith = dot(up, lightDir);
        float3 transmittanceToLight = sampleTransmittance(
                transmittanceLut, samplerIndex, ATMOS_BOTTOM_KM, cosLightZenith);
        float ndl = max(cosLightZenith, 0.0);
        result.luminance += throughput * illuminance * transmittanceToLight * ndl
                * (groundAlbedo / SKY_PI);
    }
    return result;
}

// ---- Celestial geometry ------------------------------------------------------------------------

/**
 * World direction of a celestial body from Minecraft's own eased angle (EnvironmentAttributes.SUN_ANGLE /
 * MOON_ANGLE, radians) and the configured noon tilt toward south. Angle 0 puts the body at its
 * highest point. Matches vanilla's arc: east-west travel, tilted toward +Z at its peak.
 */
public float3 celestialDirection(float angleRad, float noonTiltRad) {
    float peak = cos(angleRad);
    return float3(-sin(angleRad), cos(noonTiltRad) * peak, sin(noonTiltRad) * peak);
}

/** The pole the sun/moon arc about, and the axis the starfield wheels on. */
public float3 celestialAxis(float noonTiltRad) {
    return float3(0.0, -sin(noonTiltRad), cos(noonTiltRad));
}

/**
 * Tangent frame for a celestial body's square, kept level with the horizon: {@code right} is horizontal
 * and {@code up} lies in the vertical plane through the body. This keeps the sun and moon from rolling
 * about their own centres as they cross the sky.
 */
public void celestialSquareFrame(float3 bodyDir, out float3 right, out float3 up) {
    // Near the zenith the horizontal reference degenerates; the arc's tilt keeps the sun/moon well away
    // from it in practice, so any stable fallback will do.
    float3 reference = abs(bodyDir.y) > 0.9995 ? float3(0.0, 0.0, 1.0) : float3(0.0, 1.0, 0.0);
    right = normalize(cross(reference, bodyDir));
    up = cross(bodyDir, right);
}

/**
 * Gnomonic projection of {@code dir} into a body's tangent square, in units of its half-angle:
 * {@code local} within [-1,1]² is on the body. A square (not a disc) because vanilla draws both bodies as
 * textured quads; the environment evaluator derives the visible shape from the sampled texels.
 */
public bool celestialSquareLocal(float3 dir, float3 bodyDir, float halfAngle, out float2 local) {
    local = float2(2.0, 2.0);
    float c = dot(dir, bodyDir);
    if (c <= 1.0e-3) {
        return false;
    }
    float3 right;
    float3 up;
    celestialSquareFrame(bodyDir, right, up);
    float3 planar = dir / c; // the point where the ray pierces the body's tangent plane
    local = float2(dot(planar, right), dot(planar, up)) / tan(halfAngle);
    return all(abs(local) < float2(1.0, 1.0));
}

// The Overworld sky's own per-frame inputs: only what the CPU alone can know — Minecraft's three eased
// celestial angles (the 26.2 timeline drives them through a cubic-bezier ease and a datapack may replace
// the track, so they are read from the attribute probe rather than re-derived from the tick), its star
// brightness, moon phase and weather factors, the photometric anchors, and SkyLutPass's own sky.* geometry
// options. Every direction, colour, level and transmittance is derived from these below, so no atmosphere
// maths runs on the CPU.
//
// SkyLutPass fills one of these per frame and publishes it two ways: inline in its LUT dispatch push data
// and through the stable LUT-lifetime device buffer referenced by every live environment binding. The
// sky-view dispatch updates that buffer before world tracing in queue order, so both consumers read the
// same fields without republishing the retained scene. None of it is engine state
// — a sky for another dimension needs none of these fields — so it lives with the pass that owns it.
//
// SkyInputsData.java is generated from this reflected type, including its serializer and byte size.
public struct SkyInputs {
    // x sun angle (rad), y moon angle (rad), z star angle (rad), w MC star brightness 0..1
    public float4 celestial;
    // x sun illuminance (lux, top of atmosphere), y full-moon illuminance (lux), z night airglow
    // (cd/m²), w star luminance anchor (cd/m²).
    public float4 skyLook0;
    // x noon south tilt (rad), y sun angular half-angle (rad), z moon angular half-angle (rad),
    // w moon-phase fixed light fraction.
    public float4 skyLook1;
    // x drawn sun half-angle (rad), y drawn moon half-angle (rad), z viewer altitude (km),
    // w moon phase index (0 full .. 4 new).
    public float4 skyLook2;
    // x ground albedo, y horizon soften (rad), z weather daylight factor, w weather beam transmittance
    // (both 0..1, from MinecraftWeather).
    public float4 skyLook3;
    // x weather sky saturation (0..1, from MinecraftWeather), yzw reserved.
    public float4 skyLook4;
    public float4 sunUv;            // vanilla sun sprite atlas rect (u0,v0,u1,v1)
    public float4 moonUv;           // vanilla current-moon-phase sprite atlas rect
};

// ---- Per-frame sky state ------------------------------------------------------------------------
//
// SkyInputs carries Minecraft's three eased celestial
// angles, its star brightness, the moon phase, the weather factors, the photometric anchors, and
// SkyLutPass's sky.* options.
// Every direction, colour, level, and transmittance is derived here so atmospheric physics has one
// implementation — shared by the LUT bakes and the sky slot's LUT-lifetime device-address buffer.

public struct SkyState {
    public float3 sunDir;
    public float3 moonDir;
    public float  sunIlluminance;      // lux, top of atmosphere, after the weather's daylight darkening
    public float  moonIlluminance;     // lux, already scaled by the visible phase
    public float  beamTransmittance;   // fraction of each body's direct beam that passes the rain
    public float  skySaturation;       // fraction of the dome's saturation that the weather keeps
    public float  viewerRadiusKm;      // planet centre to viewer
    public float  sunAngularRadius;    // physical half-angle, also the planet-shadow softness
    public float  moonAngularRadius;
    public float  sunDiscHalfAngle;    // drawn size, decoupled from the physical radius
    public float  moonDiscHalfAngle;
    public float  starAngle;
    public float  starLuminance;       // cd/m², MC's star brightness times the lighting calibration's anchor
    public float  airglowLuminance;    // cd/m², the real moonless-night floor (airglow + unresolved stars)
    public float  noonTilt;
    public float  groundAlbedo;
    public float  horizonSoften;       // radians below the horizon over which the ground fades in
    public float4 sunUv;
    public float4 moonUv;
};

/** Minecraft's moon phases run 0 = full to 4 = new, then mirror back toward full through phase 7. */
public float moonLitFraction(float moonPhaseIndex) {
    return abs(moonPhaseIndex - 4.0) / 4.0;
}

public SkyState skyState(SkyInputs push) {
    SkyState s;
    s.noonTilt = push.skyLook1.x;
    s.sunDir = celestialDirection(push.celestial.x, s.noonTilt);
    s.moonDir = celestialDirection(push.celestial.y, s.noonTilt);
    // Weather darkens only sunlight, which drives the whole daylit sky; the moonlit sky and airglow are the
    // night floor that vanilla's weather keeps. Rain's zero star brightness arrives through the probe.
    s.sunIlluminance = push.skyLook0.x * push.skyLook3.z;
    s.beamTransmittance = push.skyLook3.w;
    s.skySaturation = push.skyLook4.x;
    // A fixed floor plus the visible phase: a new moon still casts a little directional light, which is
    // a gameplay calibration independent of the astronomical phase.
    float phaseFixed = push.skyLook1.w;
    s.moonIlluminance = push.skyLook0.y
            * (phaseFixed + (1.0 - phaseFixed) * moonLitFraction(push.skyLook2.w));
    s.viewerRadiusKm = ATMOS_BOTTOM_KM + max(push.skyLook2.z, MIN_VIEWER_ALTITUDE_KM);
    s.sunAngularRadius = push.skyLook1.y;
    s.moonAngularRadius = push.skyLook1.z;
    s.sunDiscHalfAngle = push.skyLook2.x;
    s.moonDiscHalfAngle = push.skyLook2.y;
    s.starAngle = push.celestial.z;
    s.starLuminance = push.celestial.w * push.skyLook0.w;
    s.airglowLuminance = push.skyLook0.z;
    s.groundAlbedo = push.skyLook3.x;
    s.horizonSoften = push.skyLook3.y;
    s.sunUv = push.sunUv;
    s.moonUv = push.moonUv;
    return s;
}

