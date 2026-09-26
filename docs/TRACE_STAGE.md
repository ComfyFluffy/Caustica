# Trace stage

The trace stage runs global light preparation, `BuildStablePlanes`, local NEE-AT baking, and `FillStablePlanes`, followed by the selected reconstruction route.

## Pass contracts

- **Build:** explores deterministic reflection/transmission branches into at most three planes. Each endpoint stores a 64-byte restart record containing the incoming ray, RGB throughput after endpoint-segment attenuation, medium absorption/IOR, depth, and flags. Branch exploration stops at the plane/depth budget without discarding unexpanded lobes: Fill samples them. Build writes deterministic emission once and exports the dominant endpoint's current depth and motion.
- **Local bake:** reprojects previous coherent feedback with current pixel motion and rejects incompatible linear depth. It maps retained light identities into the current scene and builds the local distribution. Missing history falls back to global sampling. Ordinary camera motion and animation do not invalidate all local history.
- **Fill:** retraces a short interval around each endpoint, then keeps stochastic path state in raygen. Closest-hit resolves surface/volume data. Candidate selection uses a cheap BSDF PDF target; the selected visible light receives full BSDF evaluation. Diffuse/specular component probabilities agree with the emitter-hit MIS calculation. The terminal ray consumes emission before stopping.
- **Texture footprint:** Build and Fill pass each surface a ray-cone width as `SurfaceInput.textureFootprint`. The cone starts at the eye with the trace-resolution pixel spread, and delta events keep that spread. Build's width is therefore spread times unfolded path length. Fill restarts from the camera distance of the endpoint origin. That equals Build's length except in folded multi-delta chains, where the restart cone is narrower. Each continuous scatter widens the spread by 0.3 times the apex angle of the cone whose solid angle is 1/PDF (RTXPT's heuristic). Shadow visibility evaluates surfaces with a zero footprint, and coverage never receives one.
- **Reconstruction:** deterministic radiance stays separate from noisy diffuse/specular signals. NRD prepares/evaluates/merges each plane. DLSS-RR receives combined signals and dominant-plane guides.

The build writes every restart slot each frame. Extent-owned scratch is reused on the ordered graphics queue; resize requires drained GPU use. The local-bake barriers make Build's records/guides visible to compute and make both Build's records and compute's local distribution visible to Fill.

Smooth surface lobes are integrated delta events. Thick transmissive boundaries use Fresnel/refraction and update absorption/IOR. Shadow visibility visits nearest boundaries in order and accumulates each medium segment, including the remaining segment to the light; any-hit only handles coverage. The medium state holds one active medium, not a nested medium stack. Visibility uses inline ray queries, so the ray-tracing pipeline has only the radiance closest-hit, coverage any-hit, and environment miss stages, and the retained hit table has one record per geometry.

## Validation, 2026-09-05

The `renderer-raytracing:check`, `renderer-runtime:test`, `minecraft-client:compileShaders`, and `renderer-presentation:compilePresentationShaders` tasks passed, including ordinary/SER shader compilation, SPIR-V validation, generated ABI reproducibility, and packaging checks. The obsolete source-substring check for the removed closest-hit estimator was removed. A temporary Vulkan BSDF harness reported 7,623 numeric checks with zero failures.

The existing debug client ran the rewritten shaders in `caustica-trace-rewrite-20260905`, a copy of the debug workbench. Raw, NRD, and DLSS-RR captures had no nonfinite color/normal/signal components. Separate raw captures at bounce limits 0, 1, and 4 also had no nonfinite color, albedo, depth, motion, view-Z, or deterministic-radiance components. Settings and camera were restored after experiments.

The repeated camera-yaw experiment recorded 410 frames with `localHistoryValid=true` throughout. This verifies the history gate stays enabled, not the number of individual pixels accepted by GPU reprojection. At 427x240, eight NEE candidates, and 4,786 retained lights, the broad `world resources and trace` GPU interval measured median 1.850 ms / p95 2.444 ms. It includes resource work and is not an isolated trace-pass timing. An earlier baseline launch crashed, so no performance improvement is established.

Local artifacts (not committed): `tmp/trace-validation/routes.json`, `bounces.json`, `motion.json`, `final-motion-events.json`, and `final-motion-analysis.json`; the recording and captures they reference are under `run/caustica-debug` and `run/screenshots`.

## Remaining limits

This adopts RTXPT's build/bake/fill structure rather than its complete implementation. Refraction guide unfolding uses a local rotation; local reprojection uses a conservative 2% current/previous view-depth comparison without predicted previous depth. The current capture checks are runtime/finite-value tests, not a converged unbiasedness or full RTXPT parity test. Nested volumes, statistical forward/reverse light-estimator agreement, and broader scene/performance coverage need separate validation.
