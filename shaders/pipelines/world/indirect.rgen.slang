// Indirect pass of the wavefront renderer.
//
// Resumes the continuations the primary pass queued and runs the bounce loop: NEE toward the sun, RIS
// emitter lighting, thin-surface SSS, BSDF continuation and Russian roulette. It captures no guides and
// owns no motion vectors — guides and primary are deliberately absent from the include list
// below, so guide state cannot leak into this pass by accident.
//
// Lighting is iterative here rather than recursive from closest-hit, so maxRayRecursionDepth stays 1.
// At each surface vertex:
//   * NEE toward the sun — a single analytic directional light, gated by one hit-object visibility
//     query (TerminateOnFirstHit | SkipClosestHit). Lambertian BRDF = albedo/PI.
//   * a cosine-weighted Lambertian continuation ray (throughput *= albedo; the PI and cos cancel).
// Sky / ambient light needs no separate AO pass: continuation rays that escape land in world.rmiss,
// which returns sky radiance — so soft sky fill and contact AO both fall out of the path integral.
//
// Each frame emits a single SPP-averaged (still noisy) estimate; temporal convergence is the denoiser's
// job. With RR disabled the output is the raw noisy path trace — a reference view, not a converged
// image. Output is scene-linear ACEScg HDR (R16G16B16A16_SFLOAT); display mapping happens afterward.
//
// The build emits an ordinary TraceRay fallback and an optional EXT SER variant from this source.
import world_common;
import world_core;
import math;
import medium;
import segment;
import water;
import trace;
#ifdef CAUSTICA_ENABLE_EXT_SER
import trace_ser;
#endif
import lighting;
import sky;
import bindings;
import fog;
import clouds;
import restir;

// Atmospheric transmittance LUT (RtSkyLut), also bound to world.rmiss at the same binding: raygen reads it
// to colour the NEE sun/moonlight, the miss shader reads it to tint the visible discs and stars.
public float3 tracePath(PathSegment seg, uint2 pix, uint sampleIndex) {
    float3 L = float3(0.0, 0.0, 0.0);
    float3 ro = seg.ro;
    float3 rd = seg.rd;
    float3 throughput = seg.throughput;
    // Pass A is fixed at one sample per pixel. Decorrelate each Pass B resample here so configured SPP
    // produces independent lighting/BSDF paths from the shared terminal continuation.
    uint seed = seg.seed ^ sampleIndex * 2246822519u;
    seed = pcg(seed);
    float rayConeWidth = seg.rayConeWidth;
    float rayConeSpread = max(seg.rayConeSpread, RAY_CONE_MIN_SPREAD);
    int maxBounces = int(worldPush.maxBounces);
    int rrStart = maxBounces <= 3 ? 1 : 2;
    // RIS emitter NEE: direct lighting from block emitters is active when lights are published and the
    // candidate count is non-zero. Otherwise emitters contribute only when a path hits them.
    bool risOn = worldPush.risCandidates > 0u && worldPush.lightCount > 0u && pc.lightBufAddr != 0;
    // Independent light-proposal RNG. Screen-space coherent selection is intentionally deferred until
    // there is a real presampled RIS tile buffer; direct sharing made tile-shaped lighting noise visible.
    // Measured: making a 16x16 tile share this seed recovers at most 1.3ms, so a future pool should share
    // the POOL rather than the seed — the win is in removing dependent loads, not in coherence.
    uint proposalSeed = pix.x * 1973u + pix.y * 9277u + 26699u
            ^ worldPush.frameIndex * 2654435761u ^ sampleIndex * 2246822519u;
    proposalSeed = pcg(proposalSeed);
    // The medium the segment starts in. For the camera segment that is water when the eye is submerged
    // (so the first ray already carries the right relative index and absorption); for a segment split off
    // a dielectric it is whatever the parent was travelling through.
    MediumStack medium = seg.medium;
    FogParams fog = makeFogParams(worldPush);
    SkyState fogSky = skyState(worldPush);
    // Cloud shadowing runs on shadow rays, which every bounce casts, so unlike the view march it is
    // affordable here — a coarse 8-step march of a 2-octave field, and only when a deck exists at all.
    CloudParams shadowClouds = makeCloudParams(worldPush);
    float cloudShadowStrength = worldPush.cloud2.x;
    float cloudShadowFloor = worldPush.cloud2.y;
    float wetness = worldPush.cloud2.z;
    bool waterWaves = (worldPush.flags & 16u) != 0u; // animated wave-normal perturbation
    // Sky-disc gate (see world.rmiss): the sun/moon disc is shown to the primary ray and to rays spawned
    // by a specular/dielectric bounce (mirror reflections + refractions of the sun/moon), but hidden from
    // diffuse-sampled continuation rays — those are already covered by the sun NEE, so the tiny disc
    // would be a double-counted firefly. Reset true on every specular/water bounce, false on a diffuse one.
    bool showCelestial = seg.showCelestial;
    // Pass B treats every hit it sees as one indirect-depth step, regardless of material or selected
    // continuation lobe. Deliberately start at zero instead of seg.bounce: the first hit of either
    // queued split branch is still the primary-terminal shading point. Hits 0 and 1 retain SSS; RIS
    // remains active with its full configured candidate count at every hit. Pass A's primary/interface
    // prefix is outside this SSS quality budget.
    int indirectDepth = 0;
    // Pass A consumed the camera -> first-dielectric prefix, so Pass B never walks it and would leave the
    // fog off anything seen through water or glass. Re-integrate that prefix here as a straight segment
    // from the eye to this record's origin. Refraction bent the real path, but fog is a smooth function
    // of position over tens of blocks and the bend is sub-block, so the straight-line approximation is
    // below the noise floor. Both Fresnel branches get it, each weighted by its own throughput, and those
    // two throughputs sum to one — so the in-scatter is added once, not twice.
    if (seg.bounce > 0 && fogActive(fog)) {
        float3 fromEye = ro - worldPush.camOffset;
        float prefix = length(fromEye);
        if (prefix > 1.0e-3) {
            applyFogSegment(fog, fogSky, airMedium(), worldPush, worldPush.camOffset, fromEye / prefix, prefix,
                            L, throughput);
        }
    }
    for (int bounce = seg.bounce; bounce <= maxBounces; bounce++) {
        // Radiance SBT records run any-hit only for true alpha cutout. Translucent/water go straight to
        // closest-hit for dielectric handling. Geometry is double-sided; the chit flips the normal.
        // Primary (bounce 0) is the camera ray (CULL_PRIMARY): sees particles but not the first-person
        // player. Bounce rays are secondary (CULL_SECONDARY): exclude particles, include the player.
#ifdef CAUSTICA_ENABLE_EXT_SER
        // SER lifetime phase: keep paths that are not roulette-eligible, paths that may terminate via
        // roulette, and paths guaranteed to end at the bounce cap in separate coherence groups.
        uint pathPhaseHint = bounce >= maxBounces ? 2u : (bounce >= rrStart ? 1u : 0u);
        traceRadianceReordered(bounce == 0 ? CULL_PRIMARY : CULL_SECONDARY, ro, 0.0, rd, 10000.0,
                               showCelestial, rayConeWidth, rayConeSpread, pathPhaseHint);
#else
        traceRadiance(bounce == 0 ? CULL_PRIMARY : CULL_SECONDARY, ro, 0.0, rd, 10000.0,
                      showCelestial, rayConeWidth, rayConeSpread);
#endif

        if (payload.hitT < 0.0) {
            // A ray still in water should leave through a water interface. A miss instead means the
            // streamed/open volume has no known exit; do not reinterpret that unknown region as air
            // and reveal sky.
            if (medium.current.water) {
                break;
            }

            // world.rmiss writes sky radiance (gradient + sun/moon disc + stars) into the payload's
            // surface words as a full float3, so this is absolute scene units with nothing to undo. The
            // packed show-celestial flag decides whether this path segment may see the bright disc.
            // Fog first: the escaping ray still crossed the whole fogged column, so the sky it reveals is
            // attenuated by that column and the column's own in-scatter is added in front of it. At the
            // horizon the two converge on the same sky-view texel, which is why a fogged horizon fades
            // INTO the sky instead of forming a visible band against it.
            applyFogSegment(fog, fogSky, medium.current, worldPush, ro, rd, FOG_MAX_DISTANCE, L, throughput);
            float3 sky = payloadSky();
            L += throughput * sky; // escaped to sky
            break;
        }

        // Beer–Lambert: attenuate along the segment just travelled by the medium it lay inside. Applies
        // to every hit reached while inside a volume dielectric (its own exit face, or whatever content
        // lies within it), shifting the transmitted radiance with distance. Air's extinction is zero, so
        // the ordinary case costs one comparison.
        if (any(medium.current.extinction > 0.0)) {
            throughput *= exp(-medium.current.extinction * payload.hitT);
        }
        // Air only — applyFogSegment gates on the medium itself, so a segment inside water or glass is a
        // no-op here and its own extinction above remains the complete answer.
        applyFogSegment(fog, fogSky, medium.current, worldPush, ro, rd, payload.hitT, L, throughput);

        float3 n = payloadNormal(); // oriented toward the incoming ray by the closest-hit
        float3 hitPos = ro + rd * payload.hitT;
        rayConeWidth = max(rayConeWidth + rayConeSpread * max(payload.hitT, 0.0), RAY_CONE_MIN_WIDTH);
        uint material = payloadMaterial();
        int hitDepth = indirectDepth++;

        // ---- Dielectric interface: water, stained glass, ice. ONE handler and one behaviour — every
        // dielectric is a volume. Fresnel from the relative index across the face, then reflect or
        // refract; no diffuse response and no sun NEE, because the surface is specular. Transmission
        // pushes or pops a participating medium, and the segment travelled inside it is attenuated by
        // that medium's extinction rather than by a tint multiply at the interface.
        //
        // Water is the only material that adds anything: the animated wave normal, and a medium marked
        // for the caustic term. Which side of the face we are on comes from the geometry
        // (payloadDielectricEntering), so a stray or missing face cannot desync the medium for the rest
        // of the path.
        if (material == MATERIAL_WATER || material == MATERIAL_DIELECTRIC) {
            // A dielectric has no local shading term. At the path cap its continuation cannot be traced,
            // so avoid computing an unused Fresnel choice, medium update, and roulette step.
            if (bounce >= maxBounces) {
                break;
            }
            bool isWater = material == MATERIAL_WATER;
            bool entering = payloadDielectricEntering();
            float3 tint = payloadAlbedo();
            float transmission = clamp(payloadTransmission(), 0.0, 1.0);

            // Ripple the (near-flat) surface normal before any Fresnel/guide use → glints, broken
            // reflections, wobbling refraction. The wave domain is world-stable: rebased hit XZ plus the
            // pushed anchor (terrain origin mod 4096) survives the per-move terrain rebase.
            float3 geometricNormal = n; // unrippled; ray origins offset along this
            if (isWater && waterWaves) {
                float2 waterDomain = hitPos.xz + worldPush.waterAnchor.xy;
                float waterFootprint = rayConeWidth / max(abs(dot(-rd, geometricNormal)), 0.2);
                n = applyWaterWaves(geometricNormal, waterDomain,
                                    worldPush.waterParams.w, waterFootprint);
            }

            // The medium this face opens into, and the one it returns to on the way out — which is what
            // the stack remembers.
            Medium entered = makeDielectricMedium(isWater, max(payloadIor(), 1.0), tint, transmission);
            float etaI = medium.current.ior;
            float etaT = entering ? entered.ior : medium.outer.ior;

            float cosI = clamp(dot(-rd, n), 0.0, 1.0);
            float F = fresnelDielectric(cosI, etaI, etaT);
            float3 transmittedDir = refract(rd, n, etaI / etaT);
            // The translucent terrain layer is recessed by TRANSLUCENT_INSET (RtTerrainMesher), so a
            // glass/ice face touching a slab or stair sits a hair behind that neighbour's surface. A full
            // SURF_BIAS would restart the transmitted ray past the neighbour and see through it. Water
            // comes from the fluid mesher, which applies no inset, so it takes the ordinary bias.
            float transmitBias = isWater ? SURF_BIAS : INSET_TRANSMIT_BIAS;

            bool chooseReflection = rndf(seed) < F;
            if (chooseReflection) {
                rd = reflect(rd, n);
                ro = offsetSurfaceOrigin(hitPos, geometricNormal, rd, SURF_BIAS);
            } else {
                if (dot(transmittedDir, transmittedDir) <= 0.0) break; // TIR with F < 1 cannot happen
                rd = normalize(transmittedDir);
                ro = offsetSurfaceOrigin(hitPos, geometricNormal, rd, transmitBias);
                // Crossing into or out of the volume. Absorption is the medium's job from here, so the
                // tint is NOT also multiplied into the throughput — that would double-count it.
                if (entering) {
                    mediumPush(medium, entered);
                } else {
                    mediumPop(medium);
                }
            }
            showCelestial = true; // specular interface: the continuation ray may see the sun/moon disc
            if (bounce >= rrStart) {
                float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
                if (rndf(seed) > q) {
                    break;
                }
                throughput /= q;
            }
            continue;
        }

        // Particles (material == 2): camera-facing receiver billboard. The instance mask keeps particles
        // off secondary rays, so this only fires at bounce 0; they receive direct light and one diffuse
        // path continuation into the scene, but never appear in shadows/reflections/GI or emit radiance.
        if (material == MATERIAL_PARTICLE) {
            float3 albedo = payloadAlbedo();

            float3 lightDir = celestialLight.dir;
            float lightHalfAngle = celestialLight.halfAngle;
            if (lightHalfAngle > 0.0) {
                lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
            }
            // Billboards are effectively two-sided receivers. Bias from the side facing the light; shadow
            // rays exclude particles anyway, but this keeps the origin sane when the light is behind the
            // camera side.
            float signedNdl = dot(n, lightDir);
            float ndl = abs(signedNdl);
            if (ndl > 0.0) {
                float3 shadowOrigin = hitPos + (signedNdl >= 0.0 ? n : -n) * SURF_BIAS;
                float3 vis = visibility(shadowOrigin, lightDir, 10000.0).transmittance;
                // Particles get their own evaluation: this branch returns before the terrain shading
                // vertex below, so there is no shared value to hoist here. Same bounce cap.
                if (bounce <= 1) {
                    vis *= cloudShadowTransmittance(shadowClouds, hitPos, lightDir,
                                                    cloudShadowStrength, cloudShadowFloor);
                }
                if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                    L += throughput * albedo * INV_PI * celestialLight.illuminance * ndl * vis;
                }
            }

            // RIS direct lighting from block emitters (lava, glowstone, torches, ...) — same reservoir
            // sampler as terrain/entities, but two-sided: a billboard has no back face, so light
            // striking either side of the quad should still land (matches the sun/moon NEE above).
            if (risOn) {
                float3 v = -rd;
                Reservoir r = risInitial(hitPos, n, v, rd, albedo, float3(0.0, 0.0, 0.0), 1.0,
                                         true, 0.0, seed, proposalSeed);
                L += throughput * shadeReservoir(r, hitPos, n, v, rd, albedo, float3(0.0, 0.0, 0.0), 1.0,
                                                 true, 0.0);
            }

            if (bounce >= maxBounces) {
                break;
            }
            throughput *= albedo;
            ro = hitPos + n * SURF_BIAS;
            rd = cosineDir(n, seed);
            rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
            showCelestial = false; // diffuse receiver: direct sun/moon was handled by NEE above
            continue;
        }

        float3 albedo = payloadAlbedo();
        float sss = payloadSss(); // LabPBR SSS strength (0 when absent)
        float3 p = hitPos + n * SURF_BIAS;
        float3 v = -rd; // view direction (toward the camera / incoming ray)

        // Material split. Metals tint F0 by albedo and have no diffuse lobe; dielectrics use a fixed
        // 0.04 F0, sourced from the chit (LabPBR custom/metal F0, or the dielectric default it applies).
        float rough = clamp(payloadRoughness(), 0.0, 1.0);
        float metal = clamp(payloadMetalness(), 0.0, 1.0);
        // Unresolvably tight lobes become exact delta mirrors; everything else keeps its authored value.
        // Snapping to 0 also propagates into the guide buffers, which is what tells RR to hold the
        // reflection sharp instead of accumulating it as a glossy lobe.
        bool exactSpecular = isDeltaAlpha(rough);
        rough = exactSpecular ? 0.0 : rough;
        // Rain wetness. The upward visibility test is an extra ray per shaded vertex, so it is gated on
        // there being rain at all: in clear weather this costs one comparison. The ray is short — 320
        // blocks clears any plausible build without paying for a full-height trace — and only its
        // luminance is used, so a surface under stained glass is treated as partly exposed, which is
        // closer to right than a binary test.
        if (wetness > 0.0 && n.y > 0.0) {
            float skyExposure = luminance(visibility(p, float3(0.0, 1.0, 0.0), 320.0).transmittance);
            applyWetness(wetness, skyExposure, n, albedo, rough);
        }
        float3 diffAlb = albedo * (1.0 - metal);
        float3 F0 = payload.f0;

        // Emissive surfaces (lava, glowstone, torches, ...) add radiance directly, colored by albedo.
        // RIS emitter NEE: the direct-hit emission term is gated only for emitters RIS actually samples
        // (payload emitter-in-list bit) — and then only off diffuse continuation rays (showCelestial
        // false), which the previous vertex's RIS already accounted for; the primary ray and
        // specular/dielectric bounces (showCelestial true) still add it, since no emitter NEE happened
        // along those. Emitters NOT in the light buffer (sparse/sub-threshold footprints, entities) are
        // never NEE-sampled, so they always gather — bit-identical to the no-NEE path, no energy lost.
        // Emission is already the full HDR luminance (look-package baseline or absolute cd/m² override
        // multiplier baked in Java at material-compile time — see MaterialHeader.features packing).
        float emission = payloadEmission();
        // One gate drives both the RIS call below and the direct-hit emission suppression here. They must
        // agree: gateEmitter exists only because RIS already accounted for this emitter, so a bounce where
        // RIS is skipped must also gather emission directly or that light is lost outright.
        bool gateEmitter = risOn && payloadEmitterInList();
        if (emission > 0.0 && (!gateEmitter || showCelestial)) {
            L += throughput * albedo * emission;
        }

        // NEE: direct light from the dominant celestial body (sun by day, moon by night) — Lambert
        // diffuse + GGX specular, one shadow ray. Jitter the light direction within its square angular
        // extent (celestialLight.halfAngle) so the shadow ray samples the light's quad — soft penumbrae
        // over accumulation. The same sampled direction drives ndl, the shadow ray, and the specular
        // half-vector.
        float3 lightDir = celestialLight.dir;
        float lightHalfAngle = celestialLight.halfAngle;
        if (lightHalfAngle > 0.0) {
            lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
        }
        // Cloud shadow, computed ONCE per shading vertex. It was previously evaluated at each of the
        // three NEE sites below, all of which use the same shading point and the same light direction —
        // so two thirds of the work was recomputing an identical number. Each evaluation is an 8-step
        // march of a 2-octave field, so this is the single cheapest large saving available here.
        //
        // Also capped by bounce: the deck's shadow on a second-order bounce is a small correction to an
        // already-dim contribution, and skipping it there costs nothing visible while removing the
        // march from the majority of rays in a deep path.
        float cloudShadow = bounce <= 1
                ? cloudShadowTransmittance(shadowClouds, hitPos, lightDir,
                                           cloudShadowStrength, cloudShadowFloor)
                : 1.0;
        float ndl = max(0.0, dot(n, lightDir));
        if (ndl > 0.0) {
            VisibilityResult shadow = visibility(p, lightDir, 10000.0);
            // The deck attenuates the light before it ever reaches the geometry, so it multiplies the
            // shadow ray's visibility rather than the illuminance: same term, and it stays correct when
            // the caustic factor below scales the whole NEE contribution.
            float3 vis = shadow.transmittance * cloudShadow;
            // Underwater receiver whose shadow ray crossed a water surface: scale the direct light by
            // the wave-refraction caustic at the exit point. Focusing scales the incident irradiance,
            // so it applies to the whole NEE term (diffuse + specular).
            if (medium.current.water && waterWaves && shadow.waterHitT > 0.0) {
                vis *= waterCaustic(p + lightDir * shadow.waterHitT, lightDir, shadow.waterHitT);
            }
            if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                float3 brdf = diffAlb * INV_PI; // Lambertian diffuse (f = albedo/PI)
                float3 h = normalize(lightDir + v);
                float ndh = max(0.0, dot(n, h));
                float ndv = max(1.0e-4, dot(n, v));
                float vdh = max(0.0, dot(v, h));
                float D = ggxD(ndh, rough);
                float G = ggxG1(ndv, rough) * ggxG1(ndl, rough); // separable Smith masking
                float3 F = fresnelSchlick(vdh, F0);
                brdf += (D * G) * F / (4.0 * ndv * ndl); // Cook–Torrance specular
                L += throughput * brdf * celestialLight.illuminance * ndl * vis;
            }
        }

        // RIS direct lighting from block emitters, with optional ReSTIR temporal reuse at the primary
        // hit (restir.slang).
        // Front lobe + SSS backlighting are resampled together and share ONE shadow ray — a light can
        // only be in front of n or behind it, never both, so combining them into one target function is
        // exact, not an approximation. The SSS term is gated off past MAX_SSS_INDIRECT_DEPTH by passing
        // sss=0 there (falls back to plain front-only RIS).
        if (risOn) {
            float activeSss = hitDepth <= MAX_SSS_INDIRECT_DEPTH ? sss : 0.0;
            // ReSTIR temporal reuse, primary hit only. A secondary bounce has no stable screen-space
            // identity to reproject through, so reusing history there would combine unrelated surfaces.
            Reservoir r = risInitial(hitPos, n, v, rd, diffAlb, F0, rough, false, activeSss,
                                     seed, proposalSeed);
            // Primary hit only, and only for the camera's own path: bounce > 0 and the queued
            // dielectric leaves have no stable screen-space identity to reproject through, so reusing
            // history there would be combining unrelated surfaces into one pixel's reservoir.
            float restirStrength = worldPush.restir.x;
            bool restirPixel = restirStrength > 0.0 && bounce == 0 && seg.bounce == 0;
            if (restirPixel) {
                uint parity = uint(worldPush.restir.y + 0.5);
                // Reprojection through the surface motion vector the guides already produce for DLSS,
                // so history follows a moving surface rather than a fixed screen position.
                float2 motion = gMotion[int2(pix)];
                int2 prevPix = int2(floor(float2(pix) + 0.5 - motion));
                uint reservoirW;
                uint reservoirH;
                reservoirA.GetDimensions(reservoirW, reservoirH);
                // The images are double-width, so the addressable pixel count is half the texel width.
                int renderWidth = int(reservoirW) / 2;
                // Clamped rather than skipped: a pixel whose reprojection left the screen still has
                // usable neighbours, and anchoring the spatial taps at the edge is better than
                // dropping reuse entirely for everything moving off-frame.
                prevPix = int2(clamp(prevPix.x, 0, renderWidth - 1),
                               clamp(prevPix.y, 0, int(reservoirH) - 1));
                {
                    PackedReservoir history = loadReservoir(prevPix, parity);
                    // Geometric rejection: same depth and normal, or the sample belonged to another
                    // surface that merely happened to land on this pixel last frame.
                    float currentDepth = payload.hitT;
                    float historyDepth = gDepth[prevPix];
                    float3 historyNormal = gNormal[prevPix].xyz;
                    if (temporalSurfaceMatches(currentDepth, historyDepth, n, historyNormal)) {
                        r = temporalCombine(r, history, hitPos, n, v, rd, diffAlb, F0, rough,
                                            false, activeSss, seed);
                    }
                }
                // Spatial reuse. Taps read the same history image, for the reason given in
                // restir.slang: sampling this frame's output would race the concurrent writes of the
                // very dispatch doing the sampling.
                int spatialTaps = min(int(worldPush.restir.z), RESTIR_MAX_SPATIAL_TAPS);
                float spatialRadius = worldPush.restir.w;
                for (int tap = 0; tap < spatialTaps; ++tap) {
                    int2 tapPix = prevPix + spatialTapOffset(spatialRadius, seed);
                    if (any(tapPix < int2(0, 0)) || tapPix.x >= renderWidth
                            || tapPix.y >= int(reservoirH)) {
                        continue;
                    }
                    if (!temporalSurfaceMatches(payload.hitT, gDepth[tapPix], n, gNormal[tapPix].xyz)) {
                        continue;
                    }
                    r = temporalCombine(r, loadReservoir(tapPix, parity), hitPos, n, v, rd,
                                        diffAlb, F0, rough, false, activeSss, seed);
                }
                storeReservoir(int2(pix), parity, toPacked(r));
            }
            L += throughput * shadeReservoir(r, hitPos, n, v, rd, diffAlb, F0, rough, false,
                                             activeSss);
        }

        // Thin-surface SSS transmission. Light entering from the back face scatters through toward the
        // viewer via a forward-biased HG phase (leaves/grass backlit by the sun glow when you look
        // through them toward the light source). Shadow ray fires from the back face to avoid
        // self-occlusion. cosT = dot(lightDir, rd): rd tracks the ray toward the sun when the sun is
        // behind the slab, giving cosT ≈ 1 → forward-scatter peak.
        if (sss > 0.0 && hitDepth <= MAX_SSS_INDIRECT_DEPTH) {
            float backNdl = max(0.0, dot(-n, lightDir));
            if (backNdl > 0.0) {
                VisibilityResult shadowBack = visibility(hitPos - n * SURF_BIAS, lightDir, 10000.0);
                float3 visB = shadowBack.transmittance;
                // Same caustic as the front-face NEE — underwater kelp/seagrass transmission should
                // flicker with the same light bands as the floor around it.
                if (medium.current.water && waterWaves && shadowBack.waterHitT > 0.0) {
                    visB *= waterCaustic(hitPos + lightDir * shadowBack.waterHitT,
                                         lightDir, shadowBack.waterHitT);
                }
                visB *= cloudShadow;
                if (max(visB.r, max(visB.g, visB.b)) > 0.0) {
                    float cosT = dot(lightDir, rd);
                    L += throughput * diffAlb * sss * SSS_STRENGTH * hg(cosT, SSS_G) * backNdl * celestialLight.illuminance * visB;
                }
            }
        }

        // Preserve all local lighting at the terminal hit, but do not sample a continuation that the
        // bounce loop cannot trace.
        if (bounce >= maxBounces) {
            break;
        }

        // Indirect continuation: importance-sample the diffuse OR the GGX specular lobe, chosen by their
        // relative reflectance.
        float ps = exactSpecular && luminance(diffAlb) <= 1.0e-6
                       ? 1.0
                       : clamp(luminance(F0) / (luminance(F0) + luminance(diffAlb) + 1.0e-4), 0.1, 0.9);
        if (rndf(seed) < ps) {
            float3 l;
            if (exactSpecular) {
                // Authored zero is a delta distribution, not a narrow finite GGX lobe. This avoids the
                // degenerate VNDF at alpha=0 and preserves a genuinely sharp reflection without a floor.
                l = reflect(rd, n);
                throughput *= fresnelSchlick(clamp(dot(n, v), 0.0, 1.0), F0) / ps;
            } else {
                float3 h = sampleGGXVNDF(n, v, rough, seed);
                l = reflect(rd, h); // rd = -v: reflect the incoming ray about the microfacet normal
                float ndl2 = dot(n, l);
                if (ndl2 <= 0.0) {
                    break; // microfacet reflects below the surface: terminate this path
                }
                // VNDF + separable Smith ⇒ weight = F · G2/G1(v) = F · G1(l); divide by the lobe pdf ps.
                float3 F = fresnelSchlick(max(0.0, dot(v, h)), F0);
                throughput *= F * ggxG1(ndl2, rough) / ps;
                rayConeSpread = max(rayConeSpread, rough * RAY_CONE_GLOSSY_SPREAD_SCALE);
            }
            ro = p;
            rd = l;
            showCelestial = true; // specular lobe: this ray is a (glossy) mirror — show the disc
        } else {
            throughput *= diffAlb / (1.0 - ps);
            ro = p;
            rd = cosineDir(n, seed);
            rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
            showCelestial = false; // diffuse lobe: disc covered by NEE, hide it (anti-firefly)
        }

        // Russian roulette on deeper bounces: survive with prob = max channel, rescale survivors.
        if (bounce >= rrStart) {
            float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
            if (rndf(seed) > q) {
                break;
            }
            throughput /= q;
        }
    }
    return L;
}

[shader("raygeneration")]
void main() {
    worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    // Derive this dispatch's directional light once. The same transmittance LUT drives terrain lighting
    // and the visible sun/moon, keeping atmospheric tint consistent across both consumers.
    celestialLight = dominantCelestialLight(skyState(worldPush), transmittanceLut);
    // The path integral is ACEScg end to end; sky.slang works in linear BT.709, so this is the one seam.
    celestialLight.illuminance = bt709ToAcesCg(celestialLight.illuminance);
    uint2 dispatchIndex = DispatchRaysIndex().xy;
    uint2 dimensions = DispatchRaysDimensions().xy;
    int2 pix = int2(dispatchIndex);
    uint spp = max(worldPush.spp, 1u);
    uint pixelIndex = dispatchIndex.y * dimensions.x + dispatchIndex.x;
    ConstPtr<PackedPathSegment> queue = ConstPtr<PackedPathSegment>(pc.pathQueueAddr);
    float3 frameRadiance = float3(0.0, 0.0, 0.0);
    uint recordIndex = pixelIndex;
    for (uint leaf = 0u; leaf < MAX_PATH_SEGMENTS; ++leaf) {
        PackedPathSegment packed = queue[recordIndex];
        PathSegment segment = unpackPathSegment(packed);
        for (uint s = 0u; s < spp; ++s) {
            frameRadiance += tracePath(segment, uint2(pix), s + leaf * spp);
        }
        if (packed.nextRecord == PATH_NO_NEXT) {
            break;
        }
        recordIndex = packed.nextRecord;
    }
    // Pre-exposure is applied here and nowhere else: the entire path-traced result above is in absolute
    // scene units, and only the fp16 store is normalized.
    //
    // outImage is rgba16f, so anything over 65504 would round to +inf and propagate as NaN
    // through the denoiser. Pre-exposed, it only bites when a pixel would render >65504x mid-grey — the
    // sun mid-transition, a firefly off a tiny emitter — and ACES 2.0 renders anything a few EV over
    // white as white, so it costs nothing visible.
    float3 stored = frameRadiance * (worldPush.preExposure / float(spp));
    outImage[pix] = float4(clamp(stored, float3(0.0), float3(HALF_MAX)), 1.0);
}
