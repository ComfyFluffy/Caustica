// DLSS-RR guide state and everything that resolves it: the gv_* captures, the foreground specular
// surface and its reflection motion vector, and the image writes.
// PRIMARY PASS ONLY. Depends on trace, water and segment.

// Primary-visibility guide attributes. Pass A traces exactly one sample per pixel; a split reflection
// leaf supplies the spec endpoint and its transmission leaf may replace the ordinary tuple.

import world_common;
import world_core;
import bindings;
import math;
import medium;
import water;
import trace;

public static float3 gv_normal = float3(0.0, 0.0, 0.0);
public static float3 gv_albedo = float3(0.0, 0.0, 0.0);
// The diffuse-albedo guide's alpha lane is not consumed by DLSS-RR. Preserve whether the visible
// guide endpoint emits so downstream exposure metering can cap large lava/glowstone populations
// without another material-ID image.
public static bool gv_emissive = false;
public static float gv_rough = 0.0;
public static bool gv_primarySky = false;
public static bool gv_primaryParticle = false;
public static bool gv_transmittedSky = false;
public static float3 gv_hitCamRel = float3(0.0, 0.0, 0.0);       // primary-surface hit position relative to the current camera
public static bool gv_motionUseRefracted = false; // true when the MV tracks the refracted hit (its own reprojection delta)
// World-space displacement of the motion-guide surface since the previous frame (0 for static
// terrain/sky, an entity's per-vertex/object motion for a dynamic hit).
public static float3 gv_motionObjDisp = float3(0.0, 0.0, 0.0);
// Transmission replaces the ordinary guide tuple with the visible destination, so RR's specular inputs
// (specular albedo + reflection MV) need the foreground glass/water interface kept separately. This is
// the surface the reflection physically lives on; for an opaque primary hit it is that hit itself.
public struct SpecSurface {
    public float3 camRel;         // interface position relative to the current camera
    public float3 normal;         // FP32: reflection reprojection amplifies normal quantization at distance
    public float3 previousNormal; // the same normal one frame ago; equal to `normal` unless the surface animates
    public float3 biasNormal;     // geometric normal, used only to offset a ray origin off the surface
    public float3 motionPrev;     // current-minus-previous displacement of the reflecting surface
    public float roughness;
    public float3 albedo;         // specular albedo fed to DLSS-RR for demodulation (0 = pure diffuse)
};
public static SpecSurface gv_spec = {};

// Static surfaces reuse one normal for all three roles. Water overrides them (wave-displaced shading
// normal now and last frame, flat geometric normal for the bias) via the six-argument form.
public SpecSurface makeSpecSurface(float3 camRel, float3 normal, float3 previousNormal,
                            float3 biasNormal, float3 motionPrev, float roughness, float3 albedo) {
    SpecSurface s;
    s.camRel = camRel;
    s.normal = normal;
    s.previousNormal = previousNormal;
    s.biasNormal = biasNormal;
    s.motionPrev = motionPrev;
    s.roughness = roughness;
    s.albedo = albedo;
    return s;
}

public SpecSurface makeSpecSurface(float3 camRel, float3 normal, float roughness, float3 albedo) {
    return makeSpecSurface(camRel, normal, normal, normal,
            float3(0.0, 0.0, 0.0), roughness, albedo);
}

public SpecSurface makeSpecSurface(float3 camRel, float3 normal, float3 motionPrev,
                            float roughness, float3 albedo) {
    return makeSpecSurface(camRel, normal, normal, normal, motionPrev, roughness, albedo);
}
// angle can land behind the eye even though the reflector itself is comfortably in view.
public float2 projectPrevNdc(float3 worldPos, out bool valid) {
    float4 clip = mul(worldPush.prevViewProj, float4(worldPos - worldPush.camOffset + worldPush.camDelta, 1.0));
    valid = clip.w > 0.0;
    return valid ? clip.xy / clip.w : float2(0.0, 0.0);
}

// Project an infinite sky direction into the previous camera. W=0 intentionally removes camera
// translation: an infinitely distant sky does not move when the camera walks, but it does move when
// the camera rotates.
public float2 projectPrevSkyNdc(float3 direction, out bool valid) {
    float4 clip = mul(worldPush.prevViewProj, float4(direction, 0.0));
    valid = clip.w > 0.0 && clip.w == clip.w && abs(clip.w) <= 1.0e20;
    return valid ? clip.xy / clip.w : float2(0.0, 0.0);
}

// Previous-frame screen position of a planar reflection. The reflected image of a world point P seen
// in a planar reflector is the MIRROR image V = mirror(P) across the surface plane: the eye sees V
// along a straight line, so the reflection appears at proj(V). Project the mirror image directly.
// Gotcha: intersecting a reflection ray with the surface plane divides by dot(mirrorDir, n), which
// explodes at grazing angles and produces non-zero MV in a static scene. Mirroring is division-free, so
// static MV is bit-exact zero at any incidence. The reflector is assumed static (terrain / water);
// reflectedMotionPrev carries the reflected content's own displacement.
public float2 previousReflectionNdc(float3 surfacePos, float3 surfaceNormal, float3 surfaceMotionPrev,
                             float3 reflectedWorldPos, float3 reflectedMotionPrev, out bool valid) {
    float3 n = normalize(surfaceNormal); // mirror(P) is orientation-independent, so no viewer flip needed
    float3 previousSurfacePos = surfacePos - surfaceMotionPrev;
    float3 prevHit = reflectedWorldPos - reflectedMotionPrev;
    float3 mirroredHit = prevHit - 2.0 * n * dot(prevHit - previousSurfacePos, n);
    return projectPrevNdc(mirroredHit, valid);
}

// Reflection guides remain owned by the physical foreground interface even when transmission later
// replaces the ordinary guide tuple. The same non-SER probe resolves both motion and, for exact
// mirrors, the reflected terminal's diffuse content. Destinations without a stable diffuse albedo keep
// the foreground material reflectance: sky, emissive surfaces, metals, and another dielectric/mirror
// would otherwise give RR a zero or recursively defined demodulation signal.
public float2 resolveSpecularGuides(SpecSurface surface, float3 primaryDir,
                             float2 currentNdc, float2 size, float primaryConeSpread,
                             out float3 specAlbedo) {
    specAlbedo = surface.albedo;
    if (length(surface.normal) < 0.5
            || max(surface.albedo.r, max(surface.albedo.g, surface.albedo.b)) <= 0.001
            || surface.roughness > SPEC_MOTION_ALPHA_MAX) {
        return float2(0.0, 0.0);
    }

    float3 surfacePos = surface.camRel + worldPush.camOffset;
    float3 n = normalize(surface.normal);
    float3 specDir = reflect(primaryDir, n);

    // Pass A stops radiance traversal at the first split, so reflection motion keeps one dedicated,
    // deterministic guide probe.
    traceGuide(CULL_SECONDARY,
            offsetSurfaceOrigin(surfacePos, surface.biasNormal, specDir, SURF_BIAS),
            RAY_TMIN, specDir, 10000.0,
            max(length(surface.camRel) * primaryConeSpread, RAY_CONE_MIN_WIDTH),
            max(primaryConeSpread, RAY_CONE_MIN_SPREAD));
    float3 reflectedHit;
    float3 reflectedMotionPrev;
    if (payload.hitT > 0.0) {
        reflectedHit = surfacePos + specDir * payload.hitT;
        uint reflectedMaterial = payloadMaterial();
        // Closest-hit writes motionPrev for every material. Terrain/water are zero; dynamic opaque and
        // dielectric entities carry their per-vertex/object displacement.
        reflectedMotionPrev = payload.motionPrev;
        if (surface.roughness <= MIRROR_ALPHA_MAX
                && (reflectedMaterial == MATERIAL_OPAQUE
                    || reflectedMaterial == MATERIAL_PARTICLE)
                && payloadEmission() <= 0.0) {
            float3 reflectedAlbedo = payloadAlbedo();
            float3 reflectedDiffuse = reflectedMaterial == MATERIAL_PARTICLE
                    ? reflectedAlbedo
                    : reflectedAlbedo * (1.0 - clamp(payloadMetalness(), 0.0, 1.0));
            if (max(reflectedDiffuse.r, max(reflectedDiffuse.g, reflectedDiffuse.b)) > 0.001) {
                specAlbedo = surface.albedo * reflectedDiffuse;
            }
        }
    } else {
        reflectedHit = surfacePos + specDir * 1.0e6;
        reflectedMotionPrev = float3(0.0, 0.0, 0.0);
    }
    float3 previousN = length(surface.previousNormal) >= 0.5
            ? normalize(surface.previousNormal) : n;
    bool prevValid;
    float2 prevNdc = previousReflectionNdc(
            surfacePos, previousN, surface.motionPrev,
            reflectedHit, reflectedMotionPrev, prevValid);
    return prevValid ? (prevNdc - currentNdc) * 0.5 * size : float2(0.0, 0.0);
}

public void setTransmissionGuide(float3 hitCamRel, float3 motionPrev, float3 normal,
                          float roughness, float3 diffuseAlbedo, bool emissive, bool particle) {
    gv_transmittedSky = false;
    gv_primaryParticle = particle;
    gv_hitCamRel = hitCamRel;
    gv_motionObjDisp = motionPrev;
    gv_motionUseRefracted = true;
    gv_normal = normal;
    gv_rough = roughness;
    gv_albedo = diffuseAlbedo;
    gv_emissive = emissive;
}

// Deterministic ordinary guide behind the first transmitted interface. This is guide-only work:
// radiance reflection/transmission continuations are queued at the first split and traced by Pass B.
public void resolveTransmissionGuide(float3 surfacePos, float3 transmittedDir,
                              float3 surfaceBiasNormal, MediumStack medium, float rayBias,
                              float rayConeWidth, float rayConeSpread, float3 guideFilter) {
    if (dot(transmittedDir, transmittedDir) <= 0.0) return;

    float3 direction = normalize(transmittedDir);
    float3 ro = offsetSurfaceOrigin(surfacePos, surfaceBiasNormal, direction, rayBias);

    // The camera interface consumed bounce 0; the remaining configured bounce budget is the natural
    // cap for deterministic guide crossings too.
    for (uint crossing = 0u; crossing < worldPush.maxBounces; ++crossing) {
        traceGuide(CULL_PRIMARY, ro, RAY_TMIN, direction, 10000.0,
                rayConeWidth, rayConeSpread);
        if (payload.hitT <= 0.0) {
            setTransmissionGuide((ro + direction * 1.0e6) - worldPush.camOffset,
                    float3(0.0, 0.0, 0.0), float3(0.0, 0.0, 0.0), 1.0,
                    guideFilter * SKY_DIFF_ALBEDO, false, false);
            gv_transmittedSky = true;
            return;
        }

        uint material = payloadMaterial();
        float3 interfacePos = ro + direction * payload.hitT;
        if (material == MATERIAL_OPAQUE || material == MATERIAL_PARTICLE) {
            float endpointRoughness = material == MATERIAL_PARTICLE
                    ? 1.0 : clamp(payloadRoughness(), 0.0, 1.0);
            float3 hitAlbedo = payloadAlbedo();
            float3 endpointAlbedo = material == MATERIAL_PARTICLE
                    ? hitAlbedo
                    : hitAlbedo * (1.0 - clamp(payloadMetalness(), 0.0, 1.0));
            setTransmissionGuide(interfacePos - worldPush.camOffset, payload.motionPrev,
                    payloadNormal(), endpointRoughness, guideFilter * endpointAlbedo,
                    payloadEmission() > 0.0, material == MATERIAL_PARTICLE);
            return;
        }
        if (material != MATERIAL_WATER && material != MATERIAL_DIELECTRIC) return;

        bool isWater = material == MATERIAL_WATER;
        bool entering = payloadDielectricEntering();
        float3 geometricNormal = normalize(payloadNormal());
        float3 interfaceNormal = geometricNormal;
        if (isWater && (worldPush.flags & 16u) != 0u) {
            float waterFootprint = rayConeWidth
                    / max(abs(dot(-direction, geometricNormal)), 0.2);
            interfaceNormal = applyWaterWaves(geometricNormal,
                    interfacePos.xz + worldPush.waterAnchor.xy,
                    worldPush.waterParams.w, waterFootprint);
        }

        float transmission = clamp(payloadTransmission(), 0.0, 1.0);
        Medium entered = makeDielectricMedium(isWater, max(payloadIor(), 1.0),
                                              payloadAlbedo(), transmission);
        float etaT = entering ? entered.ior : medium.outer.ior;
        float3 nextDirection = refract(direction, interfaceNormal, medium.current.ior / etaT);
        if (dot(nextDirection, nextDirection) <= 0.0) {
            // Never let a TIR reflection become ordinary diffuse/depth.
            setTransmissionGuide(interfacePos - worldPush.camOffset,
                    isWater ? float3(0.0, 0.0, 0.0) : float3(payload.motionPrev),
                    interfaceNormal, 0.0, float3(0.0, 0.0, 0.0), false, false);
            return;
        }
        if (!isWater && entering) {
            guideFilter *= payloadAlbedo();
        }
        if (entering) {
            mediumPush(medium, entered);
        } else {
            mediumPop(medium);
        }
        direction = normalize(nextDirection);
        ro = offsetSurfaceOrigin(interfacePos, geometricNormal, direction,
                isWater ? SURF_BIAS : INSET_TRANSMIT_BIAS);
    }
}

// Resolve the captured gv_* guide state into the DLSS-RR guide images after Pass A queues continuations.
public void writeGuides(int2 pix, float3 primaryDir, float2 jndc, float2 size, float primaryConeSpread) {
    // Reflection remains owned by the foreground interface even when a dielectric replaced the ordinary
    // tuple with the transmitted destination.
    float3 specAlbedo;
    float2 specMotion = resolveSpecularGuides(
            gv_spec, primaryDir, jndc, size, primaryConeSpread, specAlbedo);

    // Hardware (non-linear, reversed-Z) depth for DLSS-RR + Frame Generation: project the camera-relative
    // primary hit through the forward view-projection and take ndc z/w — exactly the value a rasterizer
    // would write. Reversed-Z (near=1, far=0), so DLSS gets the DepthInverted flag. A primary sky miss
    // is a far-plane guide, not a finite hit point: publish exact reversed-Z far depth.
    // Clear transmission uses destination depth so the entire ordinary tuple describes one layer.
    float2 motion;
    float depth;
    if (gv_transmittedSky) {
        // A transmitted sky has no stable destination correspondence behind its foreground interface.
        // Keep its ordinary motion invalid so RR cannot smear glass-to-sky pixels into splotches.
        depth = 0.0;
        motion = float2(0.0, 0.0);
    } else if (gv_primarySky) {
        depth = 0.0;
        bool previousSkyValid;
        float2 previousSkyNdc = projectPrevSkyNdc(primaryDir, previousSkyValid);
        motion = previousSkyValid
                ? (previousSkyNdc - jndc) * 0.5 * size
                : float2(0.0, 0.0);
    } else {
        float4 curClip = mul(worldPush.curViewProj, float4(gv_hitCamRel, 1.0));
        bool clipWFinite = curClip.w > 0.0 && curClip.w == curClip.w && abs(curClip.w) <= 1.0e20;
        float projectedDepth = clipWFinite ? curClip.z / curClip.w : 0.0;
        bool depthValid = clipWFinite
                && projectedDepth == projectedDepth && abs(projectedDepth) <= 1.0e20;
        depth = depthValid ? clamp(projectedDepth, 0.0, 1.0) : 0.0;

        // The primary ray was cast through the jittered NDC (jndc). Subtracting it keeps the
        // motion guide jitter-free, which is required because MVJittered is not enabled.
        float4 prevClip = mul(worldPush.prevViewProj,
                float4(gv_hitCamRel + worldPush.camDelta - gv_motionObjDisp, 1.0));
        float2 curNdc = jndc;
        if (gv_motionUseRefracted) {
            curNdc = depthValid ? curClip.xy / curClip.w : float2(0.0, 0.0);
        }
        if (prevClip.w > 0.0 && (!gv_motionUseRefracted || depthValid)) {
            motion = (prevClip.xy / prevClip.w - curNdc) * 0.5 * size;
        } else {
            motion = float2(0.0, 0.0);
        }
    }

    // Guide buffers: first-hit or coherently replaced attributes consumed by the denoiser/DLSS-RR.
    gNormal[pix] = float4(gv_normal, gv_rough);
    gAlbedo[pix] = float4(gv_albedo, gv_emissive ? 1.0 : 0.0);
    gDepth[pix] = depth;
    gMotion[pix] = motion;
    gSpecAlbedo[pix] = float4(specAlbedo, 1.0);
    gSpecMotion[pix] = specMotion;
    // Primary and transmitted sky have no stable surface correspondence; responsivity tells DLSSD not
    // to reuse their history. Transmission replaces the ordinary guide tuple, so both sky branches must
    // classify identically with the depth/motion invalidation above. Display needs a separate primary-sky
    // classification because transmitted sky remains a visible foreground surface there.
    // Particle classification prevents dynamic particle pixels from being accumulated as opaque terrain.
    // Only a visible primary miss is eligible for native End-sky replacement. A transmitted sky stays
    // a surface feature so the display pass cannot overwrite the foreground glass/water layer.
    gResponsivity[pix] = (gv_primarySky || gv_transmittedSky) ? 1.0 : 0.0;
    gSkyClassification[pix] = gv_primarySky ? 1.0 : 0.0;
    gParticleMask[pix] = gv_primaryParticle ? 1u : 0u;
}
