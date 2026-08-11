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
    // Domain for the reflection probe. The interface's own domain, carried here because the probe runs
    // from writeGuides — after tracePrimary returned and after the transmission chain has overwritten the
    // global payload, at which point the primary hit's domain is no longer recoverable.
    public uint secondaryRayMask;
};
public static SpecSurface gv_spec = {};

// Static surfaces reuse one normal for all three roles. Water overrides them (wave-displaced shading
// normal now and last frame, flat geometric normal for the bias) via the six-argument form.
public SpecSurface makeSpecSurface(float3 camRel, float3 normal, float3 previousNormal,
                            float3 biasNormal, float3 motionPrev, float roughness, float3 albedo,
                            uint secondaryRayMask) {
    SpecSurface s;
    s.camRel = camRel;
    s.normal = normal;
    s.previousNormal = previousNormal;
    s.biasNormal = biasNormal;
    s.motionPrev = motionPrev;
    s.roughness = roughness;
    s.albedo = albedo;
    s.secondaryRayMask = secondaryRayMask;
    return s;
}

// The convenience forms serve the static/sky/particle paths, which are always world surfaces.
public SpecSurface makeSpecSurface(float3 camRel, float3 normal, float roughness, float3 albedo) {
    return makeSpecSurface(camRel, normal, normal, normal,
            float3(0.0, 0.0, 0.0), roughness, albedo, CULL_SECONDARY);
}

public SpecSurface makeSpecSurface(float3 camRel, float3 normal, float3 motionPrev,
                            float roughness, float3 albedo) {
    return makeSpecSurface(camRel, normal, normal, normal, motionPrev, roughness, albedo,
            CULL_SECONDARY);
}
// angle can land behind the eye even though the reflector itself is comfortably in view.
public float2 projectPrevNdc(float3 worldPos, out bool valid) {
    float4 clip = mul(worldPush.prevViewProj, float4(worldPos - worldPush.camOffset + worldPush.camDelta, 1.0));
    valid = clip.w > 0.0;
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
    traceGuide(surface.secondaryRayMask,
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
                          float roughness, float3 diffuseAlbedo, bool emissive) {
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
public void resolveTransmissionGuide(uint rayMask, float3 surfacePos, float3 transmittedDir,
                              float3 surfaceBiasNormal, MediumStack medium, float rayBias,
                              float rayConeWidth, float rayConeSpread, float3 guideFilter) {
    if (dot(transmittedDir, transmittedDir) <= 0.0) return;
    // With the local view published, the whole chain keeps the union of the camera domain and the
    // local-view secondary domain — matching the radiance chain's representation, never mid-switching
    // to a mask that could hit the world stand-in. Unset keeps the per-crossing derivation below.
    bool localViewPresent = (worldPush.flags & 4u) != 0u;
    if (localViewPresent) {
        rayMask = CULL_PRIMARY | CULL_LOCAL_VIEW_SECONDARY;
    }

    float3 direction = normalize(transmittedDir);
    float3 ro = offsetSurfaceOrigin(surfacePos, surfaceBiasNormal, direction, rayBias);

    // The camera interface consumed bounce 0; the remaining configured bounce budget is the natural
    // cap for deterministic guide crossings too.
    for (uint crossing = 0u; crossing < worldPush.maxBounces; ++crossing) {
        traceGuide(rayMask, ro, RAY_TMIN, direction, 10000.0,
                rayConeWidth, rayConeSpread);
        if (payload.hitT <= 0.0) {
            setTransmissionGuide((ro + direction * 1.0e6) - worldPush.camOffset,
                    float3(0.0, 0.0, 0.0), float3(0.0, 0.0, 0.0), 1.0,
                    guideFilter * SKY_DIFF_ALBEDO, false);
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
                    payloadEmission() > 0.0);
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

        // An ice interface ends the guide chain: depth, position, normal and motion all describe this
        // interface, never a blend with the destination behind it. Shaped like the TIR endpoint above.
        if (material == MATERIAL_DIELECTRIC && payloadSurfaceIce()) {
            setTransmissionGuide(interfacePos - worldPush.camOffset,
                    float3(payload.motionPrev), interfaceNormal, 0.0,
                    float3(0.0, 0.0, 0.0), false);
            return;
        }

        float transmission = clamp(payloadTransmission(), 0.0, 1.0);
        Medium entered = makeDielectricMedium(payloadMediumId(), max(payloadIor(), 1.0),
                                              payloadAlbedo(), transmission);
        uint exitMatch = entering ? MEDIUM_EXIT_NO_MATCH : mediumResolveExit(medium, entered.mediumId);
        bool opticalEvent = entering || exitMatch == MEDIUM_EXIT_CURRENT;
        float3 nextDirection = direction;
        if (opticalEvent) {
            float etaT = entering ? entered.ior : medium.parent1.ior;
            nextDirection = refract(direction, interfaceNormal, medium.current.ior / etaT);
            if (dot(nextDirection, nextDirection) <= 0.0) {
                // Never let a TIR reflection become ordinary diffuse/depth.
                setTransmissionGuide(interfacePos - worldPush.camOffset,
                        isWater ? float3(0.0, 0.0, 0.0) : float3(payload.motionPrev),
                        interfaceNormal, 0.0, float3(0.0, 0.0, 0.0), false);
                return;
            }
            if (!isWater && entering) {
                guideFilter *= payloadAlbedo();
            }
            if (entering) {
                // A full stack ends the deterministic chain; the tuple keeps its last endpoint, the
                // same fail-closed shape as exhausting the crossing budget.
                if (!mediumPush(medium, entered)) {
                    return;
                }
            } else {
                mediumCommitExit(medium, exitMatch);
            }
        } else if (mediumExitIsDeep(exitMatch)) {
            mediumCommitExit(medium, exitMatch);
        }
        if (!localViewPresent) {
            // Re-derive from THIS crossing, or a chain that leaves the local-view representation and
            // enters world glass would keep probing in the local-view domain.
            rayMask = CULL_PRIMARY | secondaryMaskForSurface(payload.flags);
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
    // would write. Reversed-Z (near=1, far=0), so DLSS gets the DepthInverted flag; sky's far hit -> ~0.
    // Clear transmission uses destination depth so the entire ordinary tuple describes one layer.
    float4 curClip = mul(worldPush.curViewProj, float4(gv_hitCamRel, 1.0));
    float depth = curClip.w > 0.0 ? curClip.z / curClip.w : 0.0;

    // Motion vector: reproject this guide point through the previous frame's view-projection.
    // The primary ray was cast through the JITTERED ndc (jndc), so this hit's current screen position is
    // jndc, NOT the pixel centre. Subtract jndc so the MV is jitter-free (= 0 when static), which is
    // what DLSS expects with MVJittered unset. Scaled from NDC into render-pixel space.
    // For a dynamic surface, previous-frame position is the current point minus its world-space
    // displacement; subtracting gv_motionObjDisp de-cameras the MV.
    float4 prevClip = mul(worldPush.prevViewProj,
            float4(gv_hitCamRel + worldPush.camDelta - gv_motionObjDisp, 1.0));
    float2 curNdc = jndc; // primary hit's current screen position is exactly the jittered ray ndc
    // Transmitted content is its own feature: compare its previous and current projections rather than
    // subtracting the primary interface's jittered NDC.
    float4 curClipMotion = gv_motionUseRefracted
            ? mul(worldPush.curViewProj, float4(gv_hitCamRel, 1.0))
            : float4(0.0, 0.0, 0.0, 1.0); // unused; keeps the guard below uniform
    // Guard both perspective divides the way the depth above does. A guide point can land behind either
    // camera — most easily a transmitted destination seen at a grazing angle, or one that leaves the
    // frustum as the camera turns — and w at or below zero turns the divide into an arbitrarily large
    // vector that DLSS-RR reprojects with. No valid previous position exists in that case, so report no
    // motion and let RR fall back instead of handing it garbage.
    float2 motion;
    if (prevClip.w > 0.0 && (!gv_motionUseRefracted || curClipMotion.w > 0.0)) {
        if (gv_motionUseRefracted) curNdc = curClipMotion.xy / curClipMotion.w;
        motion = (prevClip.xy / prevClip.w - curNdc) * 0.5 * size;
    } else {
        motion = float2(0.0, 0.0);
    }

    // Guide buffers: first-hit or coherently replaced attributes consumed by the denoiser/DLSS-RR.
    gNormal[pix] = float4(gv_normal, gv_rough);
    gAlbedo[pix] = float4(gv_albedo, gv_emissive ? 1.0 : 0.0);
    gDepth[pix] = depth;
    gMotion[pix] = motion;
    gSpecAlbedo[pix] = float4(specAlbedo, 1.0);
    gSpecMotion[pix] = specMotion;
}
