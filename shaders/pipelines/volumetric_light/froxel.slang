// Froxel-grid geometry and the participating-medium model shared by the volumetric-light passes.
//
// A froxel grid is a voxelisation of the camera frustum: xy are screen tiles and z walks away from the
// eye, so every cell already knows which pixels can see it. Light shafts are therefore built from the
// medium itself rather than from anything the frame happens to draw. That is the whole reason this
// replaced radial screen-space sampling: a radial blur can only smear whatever pixels sit around the
// light on screen (the drawn sun/moon quad, the star field), so its shafts inherit the SHAPE of that
// sprite and vanish the moment the body leaves the frame. Here the celestial body contributes only a
// direction and an illuminance, so shafts keep their shape and still form when the sun is off-screen or
// behind the camera.
//
// Slices are distributed exponentially so near-camera cells stay small while the grid still reaches far
// terrain; every pass derives boundaries from the same two functions below, so injection, integration,
// and the composite lookup cannot drift apart.
module froxel;

import world_common;
import display_common;
import sky;

// Aerosol dust variation. Real air is not homogeneous, and a perfectly smooth medium makes a shaft look
// like a hard-edged solid. Slow drift keeps it alive without reading as noise.
public static const float FROXEL_NOISE_SCALE = 0.055;    // cycles per block
public static const float FROXEL_NOISE_STRENGTH = 0.42;  // peak fractional density deviation
public static const float FROXEL_NOISE_DRIFT = 0.035;    // blocks per second

// Screen-space shadow march (the fallback path). Sample distances follow a squared curve, so taps crowd
// near the froxel — where the occluder that shapes a shaft's edge is — and spread out far away, where the
// depth guide is coarse anyway. The curve reaches the same distance at every quality step, so a lower
// step count samples that reach more thinly instead of giving up on distant occluders.
public static const float FROXEL_SHADOW_REACH = 72.0;    // blocks toward the light

// Traced occlusion. The ray is bounded rather than infinite: beyond the streamed terrain radius there is
// nothing in the acceleration structure to hit, so a longer ray only pays for BVH traversal that can
// never change the result.
public static const float FROXEL_RAY_REACH = 256.0;      // blocks toward the light
public static const float FROXEL_RAY_BIAS = 0.03;        // blocks, keeps a froxel off a coincident face
// Sky-access probe length. A roof, a canopy or an overhang is close by definition, so a short ray answers
// "can light from the sky reach this point at all" for a fraction of the cost of a full escape ray.
public static const float FROXEL_SKY_REACH = 40.0;       // blocks toward the sky
// Fraction of the sky's own luminance a point in the open sees. The sky covers a hemisphere, so half of
// an isotropic sphere's worth reaches any point with clear access to it.
public static const float FROXEL_SKY_HEMISPHERE = 0.5;
// Below this in-scattered luminance the ambient term cannot be seen against anything, so its sky probe is
// skipped outright — which is what keeps a moonless night from paying for a ray per froxel.
public static const float FROXEL_AMBIENT_PROBE_FLOOR = 1.0e-3; // cd/m²

// Visibility mask of the secondary-ray class, matching the path tracer's own shadow rays: instances that
// exist only for the primary view (the first-person camera owner's billboards) must not cast shafts.
public static const uint FROXEL_CULL_MASK = 0x01u;

// Terrain BLAS geometry order (RtAccel.BUCKET_*). Only the solid bucket carries VK_GEOMETRY_OPAQUE_BIT,
// so the other three reach a ray query as candidates and this pass decides what light may pass through.
public static const uint FROXEL_BUCKET_CUTOUT = 1u;

// Air absorbs a little of what it scatters (soot, water droplets); extinction is therefore slightly
// larger than scattering. Submerged, absorption is left out entirely: the path tracer already applied
// the water body's own Beer-Lambert term to everything behind the froxel.
public static const float FROXEL_AIR_ABSORPTION = 0.08;

// Underwater shafts are much more strongly forward-scattering than air, and the surface refraction
// steepens them. Both are physical properties of the medium, not artistic knobs, so they live here.
public static const float FROXEL_WATER_ANISOTROPY = 0.82;
public static const float FROXEL_WATER_SURFACE_TRANSMISSION = 0.94; // Fresnel at a near-normal air/water crossing
public static const float FROXEL_CAUSTIC_SCALE = 0.22;              // cycles per block across the surface
public static const float FROXEL_CAUSTIC_STRENGTH = 0.55;
public static const float FROXEL_CAUSTIC_DRIFT = 0.09;              // surface pattern drift, blocks per second

// ---- Slice distribution ---------------------------------------------------------------------------

/** Distance of the grid coordinate w in [0,1]; w = 0 is the near boundary, w = 1 the far one. */
public float froxelSliceDistance(float nearDistance, float maxDistance, float w) {
    float ratio = max(maxDistance / max(nearDistance, 1.0e-3), 1.001);
    return max(nearDistance, 1.0e-3) * pow(ratio, w);
}

/** Inverse of {@link froxelSliceDistance}: the grid coordinate a distance in blocks lands on. */
public float froxelSliceCoord(float nearDistance, float maxDistance, float distance) {
    float near = max(nearDistance, 1.0e-3);
    float ratio = max(maxDistance / near, 1.001);
    return log(clamp(distance, near, maxDistance) / near) / log(ratio);
}

// ---- Camera-relative reconstruction ---------------------------------------------------------------
//
// invViewProj maps clip space to CAMERA-RELATIVE world space (see primary.rgen), and gDepth holds the
// same reversed-Z hardware depth the rasterizer convention uses (near = 1, sky = 0), so a depth-guide
// texel unprojects straight back to the world point the primary ray hit.

public float3 froxelUnproject(WorldPush world, float2 uv, float depth) {
    float2 ndc = uv * 2.0 - 1.0;
    float4 h = mul(world.invViewProj, float4(ndc.x, ndc.y, depth, 1.0));
    return h.xyz / h.w;
}

/** Unit camera ray through uv. Two unprojected points, because view bobbing bakes the eye into the projection. */
public float3 froxelViewDirection(WorldPush world, float2 uv) {
    float3 nearP = froxelUnproject(world, uv, 1.0);
    float3 farP = froxelUnproject(world, uv, 0.0);
    return normalize(farP - nearP);
}

// ---- Sampling offsets -----------------------------------------------------------------------------

/** Per-froxel, per-frame hash in [0,1). Decorrelates the shadow march so its error is noise, not banding. */
public float froxelHash(uint3 cell, uint frameIndex) {
    uint h = cell.x * 73856093u ^ cell.y * 19349663u ^ cell.z * 83492791u ^ frameIndex * 2654435761u;
    h ^= h >> 13;
    h *= 0x85EBCA6Bu;
    h ^= h >> 16;
    return float(h & 0x00FFFFFFu) / float(0x01000000u);
}

/**
 * Golden-ratio (R1) offset of this frame's slice centres within their slice, shared by the whole grid.
 *
 * <p>Successive frames evaluate a different depth inside every froxel, and the temporal blend turns that
 * sequence into an integral over the cell, which is what dissolves the layered "slabs" a fixed slice
 * centre produces at any affordable slice count.
 *
 * <p>Deliberately depth-only. Jittering x and y as well samples a finer lattice in principle, but it
 * walks each froxel's sample point across block silhouettes from frame to frame, and since visibility
 * there is a hard 0 or 1 the blend cannot converge — it flickers along every edge instead.
 */
public float froxelSliceJitter(uint frameIndex) {
    return frac(float(frameIndex) * 0.6180339887498949);
}

/**
 * A stable per-position dither pair in [0,1).
 *
 * <p>Keyed on a half-block lattice in world space, NOT on the frame: an estimator that is sampled
 * stochastically per frame turns a binary visibility test into flicker, while dithering it across space
 * lets the grid's own trilinear filter average neighbouring cells instead. World-locked rather than
 * grid-locked, so the pattern belongs to the world and a turning camera does not drag it around.
 */
public float2 froxelWorldDither(float3 posRebased) {
    int3 lattice = int3(floor(posRebased * 2.0)) + 0x4000;
    uint3 key = uint3(max(lattice, int3(0)));
    return float2(froxelHash(key, 0u), froxelHash(key, 0x9E3779B9u));
}

float froxelValueHash(float3 cell) {
    float3 p = frac(cell * float3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yzx + 33.33);
    return frac((p.x + p.y) * p.z);
}

float froxelValueNoise(float3 p) {
    float3 base = floor(p);
    float3 f = frac(p);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = froxelValueHash(base + float3(0.0, 0.0, 0.0));
    float n100 = froxelValueHash(base + float3(1.0, 0.0, 0.0));
    float n010 = froxelValueHash(base + float3(0.0, 1.0, 0.0));
    float n110 = froxelValueHash(base + float3(1.0, 1.0, 0.0));
    float n001 = froxelValueHash(base + float3(0.0, 0.0, 1.0));
    float n101 = froxelValueHash(base + float3(1.0, 0.0, 1.0));
    float n011 = froxelValueHash(base + float3(0.0, 1.0, 1.0));
    float n111 = froxelValueHash(base + float3(1.0, 1.0, 1.0));
    return lerp(lerp(lerp(n000, n100, f.x), lerp(n010, n110, f.x), f.y),
                lerp(lerp(n001, n101, f.x), lerp(n011, n111, f.x), f.y), f.z);
}

/** Two octaves are enough: the froxel grid cannot resolve anything finer than its own cell size. */
public float froxelFbm(float3 p) {
    return froxelValueNoise(p) * 0.65 + froxelValueNoise(p * 2.17 + 11.3) * 0.35;
}

// ---- Medium ---------------------------------------------------------------------------------------

/**
 * Scattering coefficient (1/block) at a camera-relative point.
 *
 * <p>Air thins out with altitude above the density base (sea level), which is what keeps mountaintops
 * clear while valleys and caves — everything at or below the base — carry the full amount. A submerged
 * eye replaces the whole model: the camera is inside one body of water, so its density is uniform.
 */
public float froxelDensity(VolumetricLightPush pc, WorldPush world, float3 posCamRel, bool underwater) {
    if (underwater) {
        return max(pc.underwaterDensity, 0.0);
    }
    float altitude = pc.cameraAltitude + posCamRel.y;
    float height = exp(-max(altitude, 0.0) / max(pc.heightFalloff, 1.0));
    // Anchored in rebased world space (camOffset is the camera there), not camera space: dust that
    // drifted with the viewer would read as a fixed pattern painted on the lens.
    float3 domain = (posCamRel + world.camOffset) * FROXEL_NOISE_SCALE
            + float3(1.0, 0.37, 0.71) * (world.waterParams.w * FROXEL_NOISE_DRIFT);
    float dust = 1.0 + FROXEL_NOISE_STRENGTH * (froxelFbm(domain) * 2.0 - 1.0);
    return max(pc.density * height * dust, 0.0);
}

/** Henyey-Greenstein phase function. g > 0 biases scattering forward, into the light's own direction. */
public float froxelPhase(float cosTheta, float g) {
    float clamped = clamp(g, -0.95, 0.95);
    float g2 = clamped * clamped;
    float d = max(1.0 + g2 - 2.0 * clamped * cosTheta, 1.0e-4);
    return (1.0 - g2) / (4.0 * SKY_PI * d * sqrt(d));
}

/**
 * Light that reaches a submerged froxel, refracted through the flat air/water interface.
 *
 * <p>Snell's law compresses the sun's whole above-water hemisphere into a 48.6-degree cone below the
 * surface, so underwater shafts are always steeper than the sun's real elevation. Their colour is the
 * body's biome tint, and a slowly drifting surface pattern breaks the beam into the moving bands a real
 * wavy surface projects.
 */
public void froxelUnderwaterLight(WorldPush world, float3 posCamRel,
                                  inout float3 lightDir, inout float3 illuminance) {
    // refract() takes the light's own propagation direction (downward) through the upward-facing
    // surface normal; the result is flipped back to point at the apparent, refracted light.
    float3 downward = refract(-lightDir, float3(0.0, 1.0, 0.0), 1.0 / WATER_IOR);
    if (dot(downward, downward) > 1.0e-6) {
        lightDir = -normalize(downward);
    }
    // Trace the refracted ray back up to the plane through the eye and pattern it in rebased world
    // space, so the bands belong to the water above rather than sliding with the viewer.
    float toSurface = -posCamRel.y / max(lightDir.y, 1.0e-2);
    float2 surface = posCamRel.xz + lightDir.xz * toSurface + world.camOffset.xz;
    float2 domain = surface * FROXEL_CAUSTIC_SCALE + world.waterParams.w * FROXEL_CAUSTIC_DRIFT;
    float bands = froxelValueNoise(float3(domain, world.waterParams.w * 0.05));
    float caustic = 1.0 + FROXEL_CAUSTIC_STRENGTH * (bands * 2.0 - 1.0);
    illuminance *= clamp(world.waterParams.xyz, 0.0, 1.0)
            * (FROXEL_WATER_SURFACE_TRANSMISSION * max(caustic, 0.0));
}

// ---- Occlusion ------------------------------------------------------------------------------------

/**
 * A direction inside the light's own angular radius, from two uniform numbers.
 *
 * <p>The sun and moon are discs, not points, so their shadow edge has a penumbra that widens with the
 * distance to the occluder. One jittered ray per froxel per frame, resolved by the temporal filter,
 * integrates that disc over time — which is why the shaft edges are soft without any extra rays.
 */
public float3 froxelSampleLightCone(float3 lightDir, float halfAngle, float2 u) {
    float cosMax = cos(clamp(halfAngle, 0.0, 0.2));
    float cosTheta = lerp(cosMax, 1.0, u.x);
    float sinTheta = sqrt(max(1.0 - cosTheta * cosTheta, 0.0));
    float phi = 2.0 * SKY_PI * u.y;
    float3 up = abs(lightDir.y) < 0.95 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0);
    float3 tangent = normalize(cross(up, lightDir));
    float3 bitangent = cross(lightDir, tangent);
    return normalize(lightDir * cosTheta
            + tangent * (sinTheta * cos(phi)) + bitangent * (sinTheta * sin(phi)));
}

/** Cosine-weighted direction about +Y, which is the distribution a flat sky illuminates a point with. */
public float3 froxelSampleSkyDirection(float2 u) {
    float radius = sqrt(saturate(u.x));
    float phi = 2.0 * SKY_PI * u.y;
    return normalize(float3(radius * cos(phi), sqrt(max(1.0 - u.x, 1.0e-4)), radius * sin(phi)));
}

/**
 * Visibility of the celestial light from a froxel, traced against the world acceleration structure.
 *
 * <p>This is the same geometry the path tracer shades, so occluders behind the camera or outside the
 * frustum shadow correctly, and nothing depends on what the frame happens to show. Inline ray queries
 * cannot run the any-hit shaders the pipeline's shadow rays use, so alpha is resolved by geometry class
 * instead: the solid bucket is flagged opaque and commits itself, the cutout bucket (leaves, grass,
 * fences) is committed here so foliage casts shafts, and the translucent and water buckets are skipped so
 * light still reaches through glass and through a water surface into a submerged view. Where the cutout
 * bucket carries an opacity micromap, hardware already resolves the fully transparent and fully opaque
 * parts of a texel grid before the candidate ever surfaces here.
 */
public float froxelTracedVisibility(RaytracingAccelerationStructure worldTlas,
                                    float3 originRebased, float3 lightDir, float tmax) {
    RayDesc ray;
    ray.Origin = originRebased;
    ray.TMin = FROXEL_RAY_BIAS;
    ray.Direction = lightDir;
    ray.TMax = max(tmax, FROXEL_RAY_BIAS * 2.0);

    RayQuery<RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> query;
    query.TraceRayInline(worldTlas, RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH, FROXEL_CULL_MASK, ray);
    while (query.Proceed()) {
        if (query.CandidateType() != CANDIDATE_NON_OPAQUE_TRIANGLE) {
            continue;
        }
        uint instance = query.CandidateInstanceID();
        bool entity = (instance & ENTITY_BIT) != 0u;
        // Particle billboards deliberately do not shadow: they are camera-facing quads whose shadow would
        // be a flicker of the particle count rather than a feature of the scene.
        bool particle = (instance & PARTICLE_BIT) != 0u;
        bool cutout = !entity && !particle && query.CandidateGeometryIndex() == FROXEL_BUCKET_CUTOUT;
        if (entity || cutout) {
            query.CommitNonOpaqueTriangleHit();
        }
    }
    return query.CommittedStatus() == COMMITTED_NOTHING ? 1.0 : 0.0;
}

/**
 * Visibility of the celestial light from a froxel, resolved by marching the primary depth guide.
 *
 * <p>The fallback for devices or settings without traced occlusion. Each step is projected into the frame
 * and compared against the depth the primary ray recorded for that pixel; both distances are measured
 * from the eye along the SAME reconstructed ray, so the comparison itself is exact.
 *
 * <p>What it cannot do is see past the frame. A step beyond {@code shadowThickness} behind a surface is
 * unknowable — the guide records one front face and cannot say how deep that object goes — and a step
 * that leaves the frustum or lands behind the camera was never rasterised at all. Both end the march lit,
 * which is what leaks light into rooms whose window is off-screen, and a step that passes behind an
 * on-screen silhouette without the light path actually entering it is what smears a dark tail along the
 * light direction. Traced occlusion exists because neither is fixable from a depth buffer.
 */
public float froxelShadow(WorldPush world, VolumetricLightPush pc, Sampler2D depthImage,
                          float3 posCamRel, float3 lightDir, float jitter) {
    uint steps = clamp(pc.sampleCount, 2u, 64u);
    float invSteps = 1.0 / float(steps);
    for (uint i = 0u; i < steps; ++i) {
        // The jitter advances the whole schedule by a fraction of a step, so consecutive frames test
        // different points and the temporal blend turns the march into a continuous occlusion estimate.
        float u = (float(i) + jitter) * invSteps;
        float3 p = posCamRel + lightDir * (FROXEL_SHADOW_REACH * u * u);

        float4 clip = mul(world.curViewProj, float4(p, 1.0));
        if (clip.w <= 1.0e-5) {
            break;
        }
        float2 uv = clip.xy / clip.w * 0.5 + 0.5;
        if (any(uv < float2(0.0)) || any(uv > float2(1.0))) {
            break;
        }
        float depth = depthImage.SampleLevel(uv, 0.0).r;
        if (depth <= 1.0e-6) {
            break; // sky texel: the march has reached open air
        }
        float sceneDistance = length(froxelUnproject(world, uv, depth));
        float behind = length(p) - sceneDistance;
        // Bias grows with distance: one depth-guide texel covers more world space the farther it is, and
        // a fixed bias would self-shadow the very surfaces the shafts graze.
        float bias = max(0.05, 0.02 * sceneDistance);
        if (behind > bias) {
            return behind < pc.shadowThickness ? 0.0 : 1.0;
        }
    }
    return 1.0;
}
