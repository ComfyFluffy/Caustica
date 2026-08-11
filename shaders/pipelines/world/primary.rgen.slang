// Primary/guide pass of the wavefront renderer.
//
// Traces one camera radiance ray, captures DLSS-RR guides, and writes one or two resumable
// continuations per pixel. At the first eligible dielectric it queues both post-interface branches and
// stops; every later radiance trace belongs to Pass B. Deterministic guide probes may continue through
// refraction, but Pass A does no NEE, RIS, SSS, or BSDF sampling.
//
// Perspective setup: invViewProj = inverse(proj * viewRot) maps clip to camera-relative world. Primary
// rays are reconstructed from two unprojected points (near and far plane) rather than assuming the eye
// is at the origin, because Minecraft bakes view bobbing into the projection, so the effective eye comes
// from the matrix. camOffset shifts camera-relative space into the terrain's rebased coordinates. Depth
// is reversed-Z (near=1, far=0).
//
// Pass A deliberately uses ordinary TraceRay. Its short, visually coherent direct/interface walk is
// latency-sensitive and does not amortize an SER reorder barrier.
import world_common;
import world_core;
import bindings;
import math;
import medium;
import segment;
import water;
import trace;
import guides;

public PathSegment tracePrimary(PathSegment seg,
                                DevicePtr<PackedPathSegment> queue,
                                uint splitRecord,
                                out uint nextRecord) {
    float3 ro = seg.ro;
    float3 rd = seg.rd;
    float3 throughput = seg.throughput;
    MediumStack medium = seg.medium;
    float rayConeWidth = seg.rayConeWidth;
    float rayConeSpread = max(seg.rayConeSpread, RAY_CONE_MIN_SPREAD);
    uint seed = seg.seed;
    bool showCelestial = seg.showCelestial;
    bool waterWaves = (worldPush.flags & 16u) != 0u;
    // The local view published this frame: camera-visible transmission chains then keep the local-view
    // domain and seed the continuity bit; unset falls back to interface-derived domains, same frame.
    bool localViewPresent = (worldPush.flags & 4u) != 0u;
    nextRecord = PATH_NO_NEXT;

    {
        int bounce = seg.bounce;
        // Replayed by Pass B as the camera ray, so its domain field is normalized rather than derived.
        PathSegment terminal = makePathSegment(ro, rd, throughput, medium,
                                               rayConeWidth, rayConeSpread, seed, bounce,
                                               showCelestial, SECONDARY_DOMAIN_WORLD, localViewPresent);
        traceRadiance(CULL_PRIMARY, ro, 0.0, rd, 10000.0,
                      showCelestial, rayConeWidth, rayConeSpread);

        if (payload.hitT < 0.0) {
            if (bounce == 0) {
                gv_normal = float3(0.0, 0.0, 0.0);
                gv_albedo = SKY_DIFF_ALBEDO;
                gv_emissive = false;
                gv_rough = 1.0;
                gv_hitCamRel = rd * 1.0e6;
                gv_motionUseRefracted = false;
                gv_motionObjDisp = float3(0.0, 0.0, 0.0);
                gv_spec = makeSpecSurface(gv_hitCamRel, gv_normal, 1.0, SKY_SPEC_ALBEDO);
            }
            return terminal;
        }

        float3 n = payloadNormal();
        float3 hitPos = ro + rd * payload.hitT;
        uint material = payloadMaterial();
        if (material != MATERIAL_WATER && material != MATERIAL_DIELECTRIC) {
            float rough = material == MATERIAL_PARTICLE
                    ? 1.0 : clamp(payloadRoughness(), 0.0, 1.0);
            bool exactSpecular = material == MATERIAL_OPAQUE && isDeltaAlpha(rough);
            rough = exactSpecular ? 0.0 : rough;
            float metal = material == MATERIAL_PARTICLE
                    ? 0.0 : clamp(payloadMetalness(), 0.0, 1.0);
            float3 hitAlbedo = payloadAlbedo();
            float3 diffAlb = material == MATERIAL_PARTICLE
                    ? hitAlbedo : hitAlbedo * (1.0 - metal);
            if (bounce == 0) {
                gv_normal = n;
                gv_hitCamRel = hitPos - worldPush.camOffset;
                gv_motionUseRefracted = false;
                gv_motionObjDisp = payload.motionPrev;
                gv_emissive = payloadEmission() > 0.0;
                if (material == MATERIAL_PARTICLE) {
                    gv_albedo = hitAlbedo;
                    gv_rough = 1.0;
                    gv_spec = makeSpecSurface(gv_hitCamRel, n, 1.0,
                                              float3(0.0, 0.0, 0.0));
                } else {
                    float3 v = -rd;
                    gv_albedo = diffAlb;
                    gv_rough = rough;
                    // A local-view surface's reflection probe belongs to the reflection domain — never
                    // its own domain, so the probe cannot see either player representation. World
                    // surfaces keep the world secondary domain; particles above always belong to it.
                    gv_spec = makeSpecSurface(gv_hitCamRel, n, n, n, float3(payload.motionPrev), rough,
                                              rrSpecularAlbedo(payload.f0, rough, dot(n, v)),
                                              payloadSurfaceLocalView()
                                                      ? CULL_REFLECTION : CULL_SECONDARY);
                }
            }

            return terminal;
        }

        // This hit is consumed in pass A, so carry its travelled-medium absorption and cone growth into
        // the record for the next trace. The terminal hit above is deliberately not consumed here.
        if (any(medium.current.extinction > 0.0)) {
            throughput *= exp(-medium.current.extinction * payload.hitT);
        }
        rayConeWidth = max(rayConeWidth + rayConeSpread * max(payload.hitT, 0.0),
                           RAY_CONE_MIN_WIDTH);

        bool isWater = material == MATERIAL_WATER;
        bool entering = payloadDielectricEntering();
        float3 geometricNormal = n;
        float3 previousNormal = n;
        if (isWater && waterWaves) {
            float2 waterDomain = hitPos.xz + worldPush.waterAnchor.xy;
            float waterFootprint = rayConeWidth / max(abs(dot(-rd, geometricNormal)), 0.2);
            if (bounce == 0 && abs(geometricNormal.y) >= 0.5) {
                float2 currentGrad;
                float2 previousGrad;
                waterWaveGradTemporal(waterDomain, worldPush.waterParams.w,
                                      worldPush.waterAnchor.z, waterFootprint, currentGrad, previousGrad);
                float orientation = geometricNormal.y >= 0.0 ? 1.0 : -1.0;
                n = orientation * normalize(float3(-currentGrad.x, 1.0, -currentGrad.y));
                previousNormal = orientation
                        * normalize(float3(-previousGrad.x, 1.0, -previousGrad.y));
            } else {
                n = applyWaterWaves(geometricNormal, waterDomain,
                                    worldPush.waterParams.w, waterFootprint);
            }
        }

        float transmission = clamp(payloadTransmission(), 0.0, 1.0);
        Medium entered = makeDielectricMedium(payloadMediumId(), max(payloadIor(), 1.0),
                                              payloadAlbedo(), transmission);
        float transmitBias = isWater ? SURF_BIAS : INSET_TRANSMIT_BIAS;
        // Identity resolution decides whether this face is an optical event at all: entering and
        // current-layer exits run Fresnel and refraction; a deeper or unmatched exit lets the ray pass
        // straight through, at most dropping the matched layer (see medium.slang).
        uint exitMatch = entering ? MEDIUM_EXIT_NO_MATCH : mediumResolveExit(medium, entered.mediumId);
        bool opticalEvent = entering || exitMatch == MEDIUM_EXIT_CURRENT;
        MediumStack transmittedMedium = medium;
        bool transmissionAvailable = true;
        float F = 0.0;
        float3 transmittedDir = rd;
        if (opticalEvent) {
            float etaI = medium.current.ior;
            float etaT = entering ? entered.ior : medium.parent1.ior;
            F = fresnelDielectric(clamp(dot(-rd, n), 0.0, 1.0), etaI, etaT);
            transmittedDir = refract(rd, n, etaI / etaT);
            if (entering) {
                transmissionAvailable = mediumPush(transmittedMedium, entered);
            } else {
                mediumCommitExit(transmittedMedium, exitMatch);
            }
        } else if (mediumExitIsDeep(exitMatch)) {
            mediumCommitExit(transmittedMedium, exitMatch);
        }
        // This interface's domains, read once here — before any guide probe below overwrites the global
        // payload. Every continuation domain and guide mask in this branch derives from these: the
        // transmission side keeps the interface's own secondary domain, the reflection side takes the
        // reflection domain when the interface is local view.
        bool interfaceLocalView = payloadSurfaceLocalView();
        uint interfaceSecondaryDomain = secondaryDomainForSurface(payload.flags);
        uint interfaceSecondaryMask = secondaryMaskForDomain(interfaceSecondaryDomain);
        // Camera-visible transmission keeps the local-view domain whenever the local view is published,
        // even through world water and glass; the reflection side is never part of that chain.
        uint transmissionSecondaryDomain = localViewPresent
                ? SECONDARY_DOMAIN_LOCAL_VIEW : interfaceSecondaryDomain;
        uint interfaceReflectionDomain = interfaceLocalView
                ? SECONDARY_DOMAIN_REFLECTION : SECONDARY_DOMAIN_WORLD;

        if (bounce == 0) {
            gv_normal = n;
            gv_rough = 0.0;
            gv_albedo = float3(0.0, 0.0, 0.0);
            gv_emissive = false;
            gv_hitCamRel = hitPos - worldPush.camOffset;
            gv_motionUseRefracted = false;
            gv_motionObjDisp = isWater ? float3(0.0, 0.0, 0.0) : payload.motionPrev;
            gv_spec = makeSpecSurface(gv_hitCamRel, n, previousNormal, geometricNormal,
                                      isWater ? float3(0.0, 0.0, 0.0) : float3(payload.motionPrev),
                                      gv_rough, float3(F, F, F),
                                      secondaryMaskForDomain(interfaceReflectionDomain));
            if (dot(transmittedDir, transmittedDir) > 0.0 && transmissionAvailable) {
                // Guide-only: keeps the baseline's deliberate particle visibility (CULL_PRIMARY) while
                // also reaching the player geometry the matching radiance path sees.
                resolveTransmissionGuide(CULL_PRIMARY | interfaceSecondaryMask,
                        hitPos, transmittedDir, geometricNormal,
                        transmittedMedium, transmitBias, rayConeWidth, rayConeSpread,
                        !isWater && entering
                                ? payloadAlbedo() : float3(1.0, 1.0, 1.0));
            }
        }

        // Split once, write both post-interface continuations, and stop Pass A radiance traversal.
        // Pass B owns every radiance trace after this point.
        bool splitEligible = opticalEvent && dot(transmittedDir, transmittedDir) > 0.0
                && F > 0.0 && F < 1.0;
        if (splitEligible && !transmissionAvailable) {
            // The transmission branch fails closed on a full stack; only the reflection survives.
            float3 reflectedDir = reflect(rd, n);
            return makePathSegment(
                    offsetSurfaceOrigin(hitPos, geometricNormal, reflectedDir, SURF_BIAS),
                    reflectedDir, throughput * F, medium,
                    rayConeWidth, rayConeSpread, seed, bounce + 1,
                    true, interfaceReflectionDomain, false);
        }
        if (splitEligible) {
            float3 deferredDir = normalize(transmittedDir);
            PathSegment deferred = makePathSegment(
                    offsetSurfaceOrigin(hitPos, geometricNormal, deferredDir, transmitBias),
                    deferredDir, throughput * (1.0 - F), transmittedMedium,
                    rayConeWidth, rayConeSpread, seed, bounce + 1,
                    true, transmissionSecondaryDomain, localViewPresent);
            queue[splitRecord] = packPathSegment(deferred, PATH_NO_NEXT);
            nextRecord = splitRecord;
            float3 reflectedDir = reflect(rd, n);
            PathSegment reflected = makePathSegment(
                    offsetSurfaceOrigin(hitPos, geometricNormal, reflectedDir, SURF_BIAS),
                    reflectedDir, throughput * F, medium,
                    rayConeWidth, rayConeSpread, seed ^ 0xa511e9b3u, bounce + 1,
                    true, interfaceReflectionDomain, false);
            return reflected;
        }

        if (!opticalEvent) {
            // A deep or unmatched exit is optically inert: continue straight with unchanged direction
            // and weight, carrying at most the deep removal. It still extends a transmission chain.
            return makePathSegment(
                    offsetSurfaceOrigin(hitPos, geometricNormal, rd, transmitBias),
                    rd, throughput, transmittedMedium,
                    rayConeWidth, rayConeSpread, seed, bounce + 1,
                    true, transmissionSecondaryDomain, localViewPresent);
        }

        bool hasTransmission = dot(transmittedDir, transmittedDir) > 0.0;
        PathSegment continuation;
        if (!hasTransmission || F >= 1.0) {
            float3 reflectedDir = reflect(rd, n);
            continuation = makePathSegment(
                    offsetSurfaceOrigin(hitPos, geometricNormal, reflectedDir, SURF_BIAS),
                    reflectedDir, throughput * F, medium,
                    rayConeWidth, rayConeSpread, seed, bounce + 1,
                    true, interfaceReflectionDomain, false);
        } else {
            float3 deferredDir = normalize(transmittedDir);
            if (!transmissionAvailable) {
                // Pure transmission with a full stack cannot proceed; fail closed with zero weight.
                return makePathSegment(
                        offsetSurfaceOrigin(hitPos, geometricNormal, deferredDir, transmitBias),
                        deferredDir, float3(0.0, 0.0, 0.0), medium,
                        rayConeWidth, rayConeSpread, seed, bounce + 1,
                        true, transmissionSecondaryDomain, localViewPresent);
            }
            continuation = makePathSegment(
                    offsetSurfaceOrigin(hitPos, geometricNormal, deferredDir, transmitBias),
                    deferredDir, throughput * (1.0 - F), transmittedMedium,
                    rayConeWidth, rayConeSpread, seed, bounce + 1,
                    true, transmissionSecondaryDomain, localViewPresent);
        }
        return continuation;
    }
}

[shader("raygeneration")]
void main() {
    worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    uint2 dispatchIndex = DispatchRaysIndex().xy;
    uint2 dimensions = DispatchRaysDimensions().xy;
    int2 pix = int2(dispatchIndex);
    float2 size = float2(dimensions);
    float2 uv = (float2(pix) + 0.5) / size;
    float2 jndc = (uv + worldPush.jitter / size) * 2.0 - 1.0;
    float4 nearH = mul(worldPush.invViewProj, float4(jndc.x, jndc.y, 1.0, 1.0));
    float4 farH = mul(worldPush.invViewProj, float4(jndc.x, jndc.y, 0.0, 1.0));
    float3 nearP = nearH.xyz / nearH.w;
    float3 farP = farH.xyz / farH.w;
    float3 origin = nearP + worldPush.camOffset;
    float3 dir = normalize(farP - nearP);
    float rayConeSpread = primaryRayConeSpread(jndc, size, dir);
    uint seed = (dispatchIndex.x * 1973u + dispatchIndex.y * 9277u + 26699u)
            ^ (worldPush.frameIndex * 2654435761u);
    MediumStack cameraMedium = makeMediumStack((worldPush.flags & 1u) != 0u
            ? makeDielectricMedium(MEDIUM_ID_WATER, WATER_IOR, worldPush.waterParams.xyz, 1.0)
            : airMedium());
    uint pixelIndex = dispatchIndex.y * dimensions.x + dispatchIndex.x;
    uint baseRecordCount = dimensions.x * dimensions.y;
    uint splitRecord = baseRecordCount + pixelIndex;
    DevicePtr<PackedPathSegment> queue = DevicePtr<PackedPathSegment>(pc.pathQueueAddr);

    seed = pcg(seed);
    PathSegment current = makePathSegment(origin, dir, float3(1.0, 1.0, 1.0),
            cameraMedium, 0.0, rayConeSpread, seed, 0, true, SECONDARY_DOMAIN_WORLD, false);
    uint nextRecord;
    PathSegment terminal = tracePrimary(
            current, queue, splitRecord, nextRecord);
    queue[pixelIndex] = packPathSegment(terminal, nextRecord);

    writeGuides(pix, dir, jndc, size, rayConeSpread);
}
