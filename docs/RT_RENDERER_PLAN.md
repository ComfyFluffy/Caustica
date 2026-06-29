# Plan: A Hardware Ray-Traced Renderer for Minecraft (Java, 26.2 / Vulkan)

Status: **P0–P4 complete** (2026-06-17). P3 path-traced lighting GPU-verified; P4 DLSS Ray
Reconstruction working — render-res split + sub-pixel jitter, vanilla renders full res while the path
tracer runs at `-Dupscaler.rt.renderScale` and DLSS-RR upscales to display. The P4.2b jitter bugs are
fixed: motion vectors are now jitter-free (subtract the jittered ndc) and the reported `InJitterOffset`
is negated, both validated against `mcvr-ref`. **The renderer is now DLSS-RR-only** — the legacy FSR
and DLSS Super-Resolution rasterizer paths and the P0 triangle self-test were deleted (compatibility /
vendor-agnostic denoiser fallback deferred to the end; recoverable from git history). **Next: P5
(dynamic content — entities/block-entities as rigid cuboid instances + per-object motion vectors).**
Supersedes the "augment Sodium for RT" approach in `sodium-26.2-beta/RENDER_API_PLAN.md` (Phases 3–4
there). The upscaler work in `dlss-mod/` (`UPSCALER_PLAN.md`) is reused as the denoise/upscale backend.
Validated against prior art: **Radiance** (Java/Fabric) + its C++ engine **MCVR**,
both studied 2026-06-14 (see "Prior art" below) — local checkouts `radiance-ref/`,
`mcvr-ref/`.

**Progress (2026-06-17):** Implemented through **P4** inside `dlss-mod` (package `dev.upscaler.rt`,
step-tagged commits on `main`; run flags + internals in the `rt-renderer-p0` auto-memory). P3 + P4
notes are below; P0–P2 detail follows.

- **P0 complete** — device bring-up → AS → RT pipeline/SBT → on-screen triangle.
- **P1 complete** — on-screen, camera-driven, **textured** terrain with **biome tint** (resolved
  from `BlockColors`/`BlockTintSource`, unlit), **alpha-cutout foliage/glass** (native any-hit), and
  **real ray-traced AO + hard sun shadows**: the primary closest-hit returns a deferred G-buffer
  (albedo/normal/hitT) and raygen traces secondary visibility rays — one sun shadow + a 16-sample
  cosine hemisphere for sky-visibility AO (the "real AO for free" milestone). Iterative-in-raygen
  (no closest-hit recursion → `maxRayRecursionDepth` 1); shadow/AO rays use a 2nd SBT miss shader
  (`shadow.rmiss`, missIndex 1) with `TerminateOnFirstHit | SkipClosestHit`.
- **P2 complete** — section-based residency synced to vanilla's loaded chunks (render-distance
  window polled per tick, gated by `hasChunk`), block-edit dirty rebuilds via a `LevelExtractor`
  hook, per-section BLAS with section-local vertices + a BDA section table
  (`{primAddr, idxAddr, uvAddr}` indexed by `gl_InstanceCustomIndexEXT`), camera-relative TLAS
  rebases (rebase = player block per rebuild), and **fully async builds**: one build in flight at a
  time (prepare → `submitAsync` → poll fence → swap), a 3-slot descriptor-set ring so the TLAS
  rebinds without a device drain, and a frames-in-flight-safe deferred-free queue. Zero drains on the
  render hot path; fly far from origin without leaks / corruption / precision cracks.

**Deviations from the plan below:** extraction uses vanilla `ModelBlockRenderer` (not the Sodium
mesher tap); residency + dirty tracking are driven by `hasChunk` polling + a
`LevelExtractor.setBlocksDirty` inject (renderer-agnostic, coexists with Sodium's `@Overwrite`),
not Sodium internals; the TLAS is rebuilt **on geometry change** (load/unload/edit/rebase), not
literally every frame — the per-frame `camOffset` push constant absorbs intra-rebuild camera
motion; async builds run on the **graphics queue** (async submit, not a secondary queue) with
simple nearest-first ordering + a per-tick budget (no distance/staleness scheduler yet). Geometry
is kept **double-sided** (no back-face cull) with a viewer-facing normal flip. AO is single-frame
(per-pixel-stable grain) until the P4 denoiser.

**Deferred P1 polish:** grass cross-model z-fight dedup, fluids (water/lava), and alpha-tested
cutout shadows (shadow/AO rays currently force-opaque → foliage casts solid shadows).

## Thesis

Build a **parallel Vulkan renderer that consumes vanilla world data and replaces
the world-rendering stage** — not RT effects bolted onto the rasterizer, and not a
reimplementation of Minecraft.

The decision to drop Sodium as the render base (decided 2026-06-13, after shipping
the DLSS/FSR upscaler):
- Sodium's value is **culling what the camera can't see** (occlusion/back-face
  culling, draw batching). RT **needs what the camera can't see** — off-screen
  geometry for reflections, back-faces for shadows, distant geometry for GI. The
  core optimization is antithetical to RT.
- Sodium's geometry is rasterization-shaped (20-bit packed positions needing an AS
  decode, no PBR material channel, region-batched). Every RT feature fights it.
- The renderer must be replaced wholesale for a path tracer anyway (HDR, compute/RT
  pipelines, denoise) — that's not a Sodium addition.
- The pains hit while building the upscaler were all symptoms of bolting RT-shaped
  needs onto an LDR rasterizer: entity motion vectors (fighting vanilla entity
  rasterization), DLSS exposure (no real HDR/exposure to provide). Both dissolve
  when we own the renderer.

Note: "drop Sodium as the **render base**" is about its render/culling path, not its
meshing code. Borrowing Sodium's block→quad *mesher* as an extraction scaffold (below)
is a separate, pragmatic tactical choice and does not contradict this.

## Prior art: Radiance / MCVR — this has been shipped (on GL)

[Radiance](https://github.com/Minecraft-Radiance/Radiance) (Fabric, MC 1.21.4) + its
C++ engine [MCVR](https://github.com/Minecraft-Radiance/MCVR) already ship **hardware-RT
path tracing for Java Minecraft**: DLSS-RR/FSR3/XeSS/NRD, LabPBR + Disney BRDF, ReSTIR
direct light, SHARC GI, volumetrics, FFT water. So this is **proven possible, not novel
as a concept** — drop any "first ever" framing. Two strategic takeaways:

1. **Their architecture is the "standalone replacement on GL" we contrasted — and its
   cost is visible in the code.** Because vanilla is GL, MCVR stands up its *own*
   VkInstance/device/swapchain (volk + glfw + VMA) and Radiance must re-route *all* of
   Blaze3D into it: **50 `vulkan_render_integration` + 17 `vanilla_resource_tracker`
   mixins (67 total) + ~12 JNI proxy classes** reimplementing the whole GL pipeline on
   Vulkan dynamic state (`PipelineStateProxy`: blend/depth/stencil/cull/scissor/viewport),
   buffers, shader translation (`ShaderTranslator`), textures, and the window. They even
   render **UI/text/particles/hand** inside their RT pipeline (`text.rahit`, `render_text`,
   `world_post_text`, `hand.rmiss`). **That whole layer is the GL tax — exactly what
   riding vanilla's Vulkan device deletes.** Our mixin surface is the world seam +
   geometry extraction + RT extensions; vanilla keeps drawing UI/hand into the shared
   target. This is our real differentiator, not novelty.
2. **We use FFM; they use JNI + `sun.misc.Unsafe`.** Their `WindowsTraps.txt`/README
   document the JDK-bundled `msvcp140.dll`/`vcruntime140.dll` crash and the
   `long`-is-32-bit-on-Windows trap — JNI-boundary pains our Java-25 FFM stack avoids.

**Design choices MCVR validates (we converge):** RT pipeline + SBT (`.rgen/.rchit/
.rahit/.rmiss` + `sbt.cpp`), per-section BLAS, `VK_GEOMETRY_OPAQUE_BIT` for solid blocks
vs `NO_DUPLICATE_ANY_HIT_INVOCATION` for cutouts, ReSTIR for emitter-dense direct light,
ray cones for texture LOD (`ray_cone.glsl`), LabPBR ingestion.
**Where MCVR goes further than our draft:** GI via a **SHARC radiance cache**
(`sharc_resolve.comp`, not brute-force bounce count); a **separate position buffer**
(`R32G32B32_SFLOAT`) split from a fat **material buffer**, both addressed by BDA in hit
shaders; **integer section coords + a sliding-window grid origin** (`chunkStorageSectionPos`)
for precision *and* AS memory; **batched, async BLAS builds** on a secondary queue with a
**distance+staleness priority scheduler** (`Chunk1::buildFactor`, `D_HALF=128` blocks),
version-based dirty tracking, and a **deferred resource retainer** for frames-in-flight-safe
frees. **Where we differ deliberately:** they reuse vanilla's mesher (we scaffold from
Sodium's); they target AMD too so use **no SER** — `VK_NV_ray_tracing_invocation_reorder`
is an NVIDIA/Blackwell-only edge available to us.

## Layer boundaries (what we keep vs. replace)

| Layer | Decision | Why |
|---|---|---|
| Game / world sim (block states, chunk data, entity pos+animation, ticks, resource packs) | **Consume vanilla as-is** | Enormous, not our value-add, RT still needs all of it |
| Geometry extraction (world+entities → GPU geometry + materials) | **Borrow Sodium's block→quad mesher as a temporary scaffold; emit an RT-native vertex format** | Sodium already does the hard part (model resolution, cullface, fluids) fast and threaded; we tap its quad sink, write `(section-local pos, normal, UV, materialID)`, and skip AO/compaction. Replace with a clean RT-native extractor later if fork-tracking hurts |
| Renderer (shading → pixels) | **Replace entirely** with a Vulkan RT renderer | Path tracing has nothing in common with Blaze3D rasterization |
| Frame composite (world → UI/hand) | **Reuse the existing seam** | `WorldRenderScaler`-style: our renderer fills the world target, vanilla UI/hand draw on top |

We consume vanilla **data**; we own the **renderer**. We never rebuild the game.

## Data we need from vanilla

- **Block geometry**: vanilla `BakedModel` quads per block state (positions, UVs,
  tint index, face, cullface, light emission). This is the authoritative source — but
  we reach it via Sodium's mesher (which already walks it correctly and fast) rather
  than re-implementing the traversal, tapping the quad sink and emitting our format.
- **Biome tint** (grass/foliage/water color) — applied during vanilla meshing via
  `BlockColors`; we consume it as an albedo modulation (it's the block's real color,
  not a fake). **We do NOT replicate vanilla's baked AO** — ray-traced sky/contact
  visibility gives us real AO, which is the entire point of owning a path tracer.
- **Block light emission** (`BlockState.getLightEmission`) for the emitter table.
- **Entity/block-entity geometry**: vanilla entity models (`EntityModel`,
  `ModelPart` cuboids) + per-frame pose. These are **rigid cuboid hierarchies, not
  skinned meshes** — instance rigid boxes with per-part transforms (see P5).
- **Atlas textures**: the block/item atlas (`TextureAtlas`) for albedo; sampled in
  hit shaders by UV.
- **Camera**: position (double), view rotation, FOV, near/far — we already pull
  these (`cameraRenderState`). The double position is load-bearing for precision.
- **PBR resource pack** (LabPBR-style): normal/roughness/metallic/emissive/SSS
  channels. Required for real PBR; without one, fall back to heuristics
  (albedo-derived roughness, emission from the light table).

## The renderer pipeline (per frame)

1. **Geometry residency**: maintain RT-native buffers for loaded chunks. Split a
   **position-only buffer** (`R32G32B32_SFLOAT`, section-local) consumed by the BLAS
   from a **fat material buffer** (normal, UV, tint, materialID) fetched in hit shaders
   — both reached by **buffer device address** via a per-chunk lookup (MCVR's pattern:
   `chunk_lookup.glsl` + per-chunk index/position/material BDA arrays). Dirty chunks
   re-extracted off-thread.
2. **AS management**: one BLAS per chunk section (compacted, static between edits;
   `OPAQUE` flag for solid blocks, `NO_DUPLICATE_ANY_HIT_INVOCATION` for cutouts);
   per-entity BLAS (refit per frame). One TLAS rebuilt per frame from in-range
   instances. **Build BLAS batched and async** on a secondary queue (one shared scratch
   buffer per batch), prioritized by **camera distance + staleness** (cf. MCVR
   `Chunk1::buildFactor`), with version-based dirty tracking and a **deferred resource
   retainer** so frees wait out frames-in-flight. Sliding-window grid over loaded
   chunks; AS memory budget.
   **Coordinate precision (camera-relative):** chunk identity is **integer section
   coords** against a **sliding-window grid origin** (MCVR `chunkStorageSectionPos`),
   and BLAS vertices are stored *section-local* (small → f32-exact); each frame's TLAS
   instance transform is `sectionOrigin − cameraPos` computed in **double** then narrowed
   to float — the same trick vanilla uses so an absolute 30M-block coordinate never
   reaches f32. Because the TLAS is already rebuilt per frame, this rebasing is free.
3. **Ray dispatch** (`VK_KHR_ray_tracing_pipeline` — raygen/miss/closest-hit/any-hit
   + a Shader Binding Table): primary visibility straight into path tracing. Drive the
   path tracer as an **iterative loop in raygen** (trace → read payload → continue),
   not recursion from closest-hit, to keep the stack shallow at high bounce counts.
   Native **any-hit** handles alpha-tested cutouts; on Blackwell, **Shader Execution
   Reordering** (`VK_NV_ray_tracing_invocation_reorder`) cuts the divergence cost of
   incoherent bounce/material rays. Raw Vulkan — Blaze3D has no compute/RT, so own
   SPIR-V + pipelines created against `vkDevice()`.
4. **Lighting / path tracing**: GI is not a separate subsystem — same trace loop, more
   bounces. But "just raise the bounce count" is too noisy/expensive at real-time sample
   budgets: MCVR (a shipped product) uses **ReSTIR for direct light** (reservoirs +
   spatial/temporal reuse + precomputed light neighborhoods — for emitter-dense caves)
   and a **SHARC radiance cache for GI** (`sharc_resolve.comp`) rather than brute-force
   multi-bounce. So: NEE + MIS, Russian-roulette termination, firefly clamping, ReSTIR DI,
   and a radiance cache (SHARC) for indirect. Accumulate radiance in **HDR**. (A simple
   brute-force "vanilla-pt" loop is still the right *first* lit milestone — MCVR ships
   both a `vanilla-pt` and an `advanced` tier; we follow the same simple→advanced split.)
5. **Denoise + temporal + upscale**: **DLSS Ray Reconstruction** (denoise+upscale in
   one) as the NVIDIA path; **SVGF** (with blue-noise) + temporal accumulation, or
   **NRD**, as the vendor-agnostic fallback; then DLSS-SR / FSR3 / **XeSS** for upscale.
   (MCVR ships all of these as swappable modules.) Reuse `dlss-mod`'s NGX/FFX plumbing.
   We generate motion vectors and HDR ourselves, so jitter/MV/exposure are clean and owned.
6. **Tonemap** HDR → LDR, output to the world target.
7. **Composite**: vanilla hand/UI draw on top (existing seam).

## Vulkan requirements

Device extensions (added via the proven device-negotiation hook — same mechanism
that enabled the NGX/FFX extensions): `VK_KHR_acceleration_structure`,
`VK_KHR_ray_tracing_pipeline` (committed — we drive shading through the SBT, not ray
query), `VK_KHR_deferred_host_operations`, `VK_KHR_buffer_device_address`, and
`VK_NV_ray_tracing_invocation_reorder` (SER, Blackwell), plus the pNext feature
structs. The RTX 5070 Ti supports all of these; MC's device can be extended exactly
as we did for NGX.

## What transfers from the upscaler work (reuse, don't rebuild)

- **The NGX/FFX plumbing** (`dlss-mod` `ngx/`, `ffx/`, the C shim, FFM bindings,
  device-extension negotiation). RT is noisy and expensive → temporal denoise+upscale
  is mandatory, not optional. **DLSS Ray Reconstruction** is the natural denoiser —
  but note it's a *distinct NGX feature* (`RayReconstruction`/DLSSD), not the
  SuperSampling feature we shipped: the extension/FFM/shim plumbing is reused verbatim,
  but the feature path is new and demands extra **guide buffers** (separated diffuse/
  specular albedo, world normals, roughness, hit distance) we must generate correctly.
  The exposure problem disappears: we own HDR and feed a real exposure.
- **Device-extension negotiation** (Sodium Phase 0 / the standalone
  `VulkanBackendMixin`) — needed verbatim for the RT extensions.
- **The world-composite seam** (`WorldRenderScaler` / `GameRendererMixin`): the
  integration model is identical — fill the world target, UI/hand on top. Swap
  "rasterize at low res + upscale" for "ray trace + denoise + upscale."
- **All Vulkan interop plumbing** learned: raw command buffers via the encoder
  accessor, VMA images, barriers, `VK_IMAGE_LAYOUT_GENERAL` policy, the crash-
  sentinel/dev-loop workflow.

What does *not* transfer: the original RT plan's "BLAS from Sodium arenas, Sodium
owns the TLAS." We own geometry, so we own the AS. (We borrow Sodium's mesher *code*
to fill our own buffers — we do not consume its packed/culled render output.)

## Phased plan (each phase ends at a visible, testable milestone)

- **P0 — RT bring-up.** ✅ Done. Register RT device extensions; confirm support; stand up a
  minimal `VK_KHR_ray_tracing_pipeline` (raygen + miss + closest-hit) with a **Shader
  Binding Table** and trace a hardcoded triangle BVH into a texture shown on screen.
  Validates the raw-VK RT pipeline + SBT + SPIR-V + device negotiation. (Mirrors the
  FSR stage-1 spike; the SBT is the one genuinely fiddly bit — budget for it here.)
- **P1 — Sky-lit static terrain.** ✅ Done (extended past the original spec with biome tint, any-hit
  cutout, and sun shadows; extraction via vanilla `ModelBlockRenderer` rather than the Sodium tap).
  Extract loaded chunk geometry by **borrowing
  Sodium's block→quad mesher** (tap its quad sink; emit section-local pos, normal, UV,
  materialID; AO/compaction off) → per-section BLAS → camera-relative TLAS → trace
  primary rays → shade `albedo × skyVisibility`, where the **miss shader returns a
  constant sky** and a cosine sky-visibility ray gives **real AO for free**. No vanilla
  AO/tint parity — this is a path-traced look, not a vanilla impersonation. Borrowing
  Sodium's mesher means all model shapes + fluids come along for free, so this is the
  full pipeline-validating spike (extraction, AS, SBT shading, compositing, real
  precision) without rebuilding a mesher. **The go/no-go proof.**
- **P2 — Geometry lifecycle.** ✅ Done (async builds, descriptor-set ring, deferred frees; TLAS
  rebuilt on change rather than per-frame). Chunk load/unload/edit → BLAS create/refit/free;
  per-frame TLAS with camera-relative rebasing; AS memory budget + sliding window over
  render distance. Goal: fly around (including far from world origin) without leaks,
  corruption, or precision cracks.
- **P3 — Path-traced lighting.** ✅ Done (GPU-verified). Brute-force "vanilla-pt" lit pass: NEE +
  MIS, Russian roulette, firefly clamping; sun/sky + emissive from the block light table; HDR
  accumulation. ReSTIR DI + SHARC GI deferred to a later optimization pass (P3.3). Temporal
  accumulation was removed once DLSS-RR owned temporal reuse (1 spp + per-frame seed).
- **P4 — Denoise + temporal + upscale.** ✅ Done. **DLSS Ray Reconstruction** as a *new* NGX feature
  path (guide buffers: diffuse/specular albedo, world normals, roughness packed in normal.w, linear
  hit distance, render-res motion vectors); render-res trace → RR denoise+upscale to display; HDR
  tonemap in `blend.comp`. Jitter (Halton, applied to the primary ray in `world.rgen`, reported
  negated to RR) and jitter-free MVs validated against `mcvr-ref`. **Implementation is DLSS-RR-only**:
  the FSR/DLSS-SR rasterizer paths were removed; the SVGF/NRD/FSR/XeSS vendor-agnostic fallback in
  step 5 above is deferred to the end of the project. Exposure: AutoExposure (fixed-exposure A/B is a
  P-final tuning item). Goal met: clean real-time image at ~1/4 the ray work.
- **P5 — Dynamic content.** ← **P5.1 (dynamic entities) DONE.** Mobs, items (held + dropped), falling
  blocks, and block entities (chests/signs/…) all ray-trace with their real `ModelPart`/baked-quad
  geometry, per-type bindless textures, alpha-cutout transparency, and per-object motion vectors. Built
  on a per-frame TLAS (P5.1a) that merges static terrain BLAS with per-frame per-entity BLAS. Remaining
  deferred: water/translucency (refraction) + biome water tint = **P5.2**; per-vertex entity MV (spin/
  swing) = P5.1c-2; sign text + special-renderer items (shields/banners) + special-geometry BEs
  (conduit/beacon beam). Commits 9870dc5 → 1ff2c7f.
  - **P5.1a — dynamic per-frame TLAS plumbing (done; commit `9870dc5`; GPU-verified terrain unchanged).**
    The traced TLAS moved out of `RtTerrain` (which still builds the section BLAS async) into a
    **per-frame rebuild recorded inline in the composite's frame command buffer**: `RtTerrain` now
    publishes a static-instance list (`staticInstances()`) + the section table; `RtComposite` each
    frame does `prepareTlas(staticInstances)` → `setTlas` → `recordTlasBuild` → AS-build→trace barrier
    → trace, retiring the frame TLAS `KEEP_FRAMES` later via a deferred-free queue. The descriptor ring
    grew to 6 (per-frame rebind cycles a slot every frame; must exceed frames-in-flight). De-risking
    milestone: **terrain image unchanged, no leaks/corruption flying around** — the foundation entities
    (P5.1b) and per-object MVs (P5.1c) build on.
  - **P5.1b-1 — entity bounding-box instances (done; commit `356578f`; GPU-verified in-game).**
    Dynamic entities enter the trace as **flat-shaded AABB boxes**: a single unit-cube
    BLAS (`RtEntities`, built once) is instanced per entity into the per-frame TLAS with a scale+translate
    transform from the entity's interpolated hitbox (rebase-relative, so no per-frame BLAS builds — only
    the cheap per-frame TLAS P5.1a already rebuilds). `RtAccel.Instance` gained an explicit `customIndex`;
    entity instances set the `ENTITY_BIT` (0x800000) flag so `world.rchit` flat-shades a box (face normal
    from `gl_PrimitiveID>>1`) instead of reading the section table. Boxes are opaque (cast + receive
    shadows/GI). Gated by `-Dupscaler.rt.entities`. **Coarse but real**: lit, shadow-casting dynamic
    objects. Deferred to **P5.1b-2**: real `ModelPart` model capture (actual mob shapes + entity textures
    via a capturing `SubmitNodeCollector` — `ModelPart.Cube.compile` emits the same bulk `addVertex` the
    fluid path already taps).
  - **P5.1c — per-object motion vectors (done; commit `688f7e2`; GPU-verified).** Moving entities get
    correct (non-camera) motion vectors so they stop ghosting
    under DLSS-RR. `RtEntities` tracks each entity's interpolated world position across frames and writes
    its per-frame displacement (`cur − prev`) into a per-entity table (a 6-slot fixed-size buffer ring,
    indexed by the entity instance index); `world.rchit` reads it on entity hits into the payload, and
    `world.rgen` subtracts it in the MV reprojection (`prevClip = prevViewProj·(hitCamRel + camDelta −
    objDisp)`; `objDisp` = 0 for static terrain/sky → the validated camera-only MV is unchanged). New
    push field `entityTableAddr@184` (WORLD_PUSH 184→192). Exact for the rigid boxes; for animated
    models (P5.1b-2) it's the rigid-body approximation (limb motion is unmodeled) — fine for DLSS-RR.
  - **P5.1b-2 — real model capture (staged).** Replaces the AABB boxes with actual `ModelPart` mob
    geometry via a capturing `SubmitNodeCollector` (`submitModel` → `setupAnim` + `renderToBuffer` into
    an `RtEntityCapture` VertexConsumer; the rest no-op). **Step 1 (done; commit `a4342e8`; GPU-verified
    via the probe — 107/112 entities captured, 0 failed, sane meshes):** the capture infra
    (`RtEntityCapture`, `RtEntityCollector`) + a gated/throttled verification probe (`RtEntities.probe`,
    `-Dupscaler.rt.entityProbe`). **Step 2 (done; commit `7649211`; GPU-verified):** replaces the AABB
    boxes with the real captured meshes — per model entity a per-frame BLAS built inline in the composite
    cmd buffer (entity BLAS → barrier → TLAS → trace), an entity geometry table `{primAddr, idxAddr,
    uvAddr, disp}` (48-byte ring) so `world.rchit` reads real per-triangle normals + vertex-colour tint +
    the per-object MV (the CUBE_N box path is removed); captured rebase-relative → identity instance
    transform. Per-frame BLAS/buffer churn is heavy (cap `-Dupscaler.rt.maxEntities`; pooling/refit deferred).
  - **P5.1b-2b — bindless entity textures.** Entities use per-type texture files (not the block atlas),
    so each `RenderType`'s texture (resolved via the public `RenderType.prepare().textures()`) gets a
    slot in a **bindless `sampler2D[]`** (descriptor set 1, partially-bound + update-after-bind; needs
    the VK12 descriptor-indexing features, enabled in `RtDeviceBringup`). The capture stamps a per-prim
    texture slot into the free `tint.w`; the hit shader interpolates the entity UV and samples
    `entityTex[nonuniformEXT(slot)] × tint`. **Done; commits `a4342e8` (b1 resolution + probe) +
    `707dee5` (b2 device features + bindless set + per-prim slot + UV sampling); GPU-verified.**
    Multi-texture layers (armor/eyes) handled per-prim. Block-entity models texture from an atlas
    *sprite* (non-null `submitModel` sprite), so their ModelPart UVs are remapped into the sprite region.
  - **P5.1b-2c — entity cutout transparency (done; commit `dba50ab`).** Entity BLAS built non-opaque;
    `world.rahit` alpha-tests the per-prim bindless texture (cutoff 0.1). Hat/jacket overlays, eyes,
    capes are see-through + cast cutout shadows. 2D item sprites get cutout for free.
  - **P5.1b-2d/e — items + falling blocks (done; commit `4d8abdc`).** Held weapons + dropped items via
    `submitItem` (resolve each quad's atlas — items use a *separate* item atlas — to its own bindless
    slot); falling blocks via `submitMovingBlock` (mesh the block model). `RtEntityCapture.addBakedQuad`.
  - **P5.1b-2f — block entities (done; commit `1ff2c7f`).** Chests/signs/beds/… aren't in
    `entitiesForRendering()` → a second pass scans loaded chunks (`BE_VIEW_CHUNKS`) and submits each via
    `BlockEntityRenderDispatcher` into the same collector (static, zero MV). `beginFrame` refactored into
    a shared `FrameBuild`/`appendCapture` tail across the entity + block-entity passes.
- **P6 — PBR materials.** Proper BRDF + LabPBR resource-pack ingestion (normal/roughness/metallic/
  emissive/SSS), heuristic fallback when no PBR pack.
  - **P6.1 — GGX BRDF + heuristic materials + DLSS-RR specular/water fix (DONE in working tree).**
    The path tracer gained a Cook–Torrance diffuse + GGX specular BRDF (VNDF importance sampling, sun
    glint via specular NEE) in `world.rgen`. Each surface carries `(roughness, metalness)` in a new third
    per-prim `vec4 mat` (`Prim` is now 48 B; `world.rchit`/`world.rahit` + all prim writers — `RtTerrain`
    block & fluid, `RtEntityCapture` — updated in lockstep). A heuristic classifier (`RtMaterials`,
    `SoundType`-keyed: metal/glass + a smooth-block set) assigns the pair at extraction; metals tint F0 by
    albedo, dielectrics use F0 0.04. **The RR fix:** the first-hit guides now feed DLSS-RR the *demodulated*
    diffuse albedo (`gAlbedo`), the **specular albedo `F0` (`gSpecAlbedo`, previously always 0)**, and the
    **real roughness (`gNormal.w`, previously 1.0)** — so RR resolves specular/water as a stable specular
    surface instead of shimmering it as noisy diffuse at 1 spp. Water is fed `diffuse 0 / specAlb = water
    F0(0.02) / low roughness`; camera-underwater is fixed via a `flags` push bit (`@192`, also carrying the
    `-Dupscaler.rt.pbr` toggle). No NGX/Java-NGX change (the `gSpecAlbedo` image + packed-roughness path
    already existed). A/B: `-Dupscaler.rt.pbr=false` reverts to the legacy Lambertian look.
  - **P6.2 — LabPBR per-texel ingestion.** Build our own `_n`/`_s` parallel atlases from a LabPBR pack
    (vanilla builds none) and sample roughness/metallic/normal/emissive/SSS per-texel. Heuristic stays
    the fallback when no pack/sprite data is present.
    - **P6.2a — terrain `_s` (specular) → per-texel roughness/metalness (DONE in working tree; builds;
      not yet GPU-verified).** NEW `RtBlockMaterials` builds a parallel `_s` atlas mirroring the block
      atlas sprite layout (a `NativeImage`→`DynamicTexture`, MC's own upload path — no Vulkan staging),
      filled **lazily** from the sprites terrain extraction sees (`BakedQuad.materialInfo().sprite()`; no
      atlas enumeration). `RtTerrain` flags `_s`-backed prims in the free `mat.z` lane; `world.rchit`
      samples `blockSpecAtlas` (set 0, binding 8) at the same UV as albedo. One extra plain sampler —
      **no bindless**. Gated by `-Dupscaler.rt.pbr`. **Fuller LabPBR `_s` decode:** red → roughness
      `(1-r)²`; green → reflectance (dielectric F0 0–229, the 8 predefined metals via hardcoded N/K →
      F0, generic metal albedo); alpha → emission (255 ignored). F0 flows through a new `Payload.f0`
      into the GGX specular + the `gSpecAlbedo` RR guide. **Deferred:** blue channel (porosity/SSS).
    - **P6.2b — `_n` normal map for terrain (DONE in working tree; builds; not yet GPU-verified; needs a
      FULL RESTART — device feature).** Uses `VK_KHR_ray_tracing_position_fetch` (enabled in
      `RtDeviceBringup` + `ALLOW_DATA_ACCESS` on every BLAS via the shared `RtAccel.buildFlags`) so the
      closest-hit reads the hit triangle's vertex positions (`gl_HitTriangleVertexPositionsEXT`) and
      builds a per-triangle TBN from positions + UVs — no positions buffer / no geometry-table change.
      A second parallel `_n` atlas (RtBlockMaterials, binding 9) is sampled at the albedo UV; `mat.w`
      flags `_n`-backed prims. Decodes LabPBR normal (RG → tangent XY, Z reconstructed) → world normal;
      blue → mild AO into albedo. Skips alpha (height/POM). Raises the device requirement to
      position-fetch-capable (all RTX; non-RTX already can't run RT).
    - **P6.2c (DEFERRED) — entity `_n`/`_s`** via the existing bindless array. Deferred refinements: `_n`
      height/POM, `_s` blue porosity/SSS, animated maps (frame 0 only), position-fetch fallback.
- **P6.3 — Dynamic sky (day/night) (DONE in working tree; builds; not yet GPU-verified).** The sun was a
  hardcoded `SUN_DIR`/`SUN_RADIANCE` const and the sky a fixed gradient in `world.rmiss` — no day/night in
  the RT path at all. Now the celestial lighting is driven by the game's time of day, recomputed each frame
  on the CPU and pushed to `world.rgen`. Independent of ReSTIR/P7 (no rework risk); chosen over entity `_n`/`_s` as the
  higher-impact next feature.
  - **CPU (`RtComposite.writeSky`):** read the partial-tick `SUN_ANGLE`/`MOON_ANGLE` from the camera's
    26.2 `EnvironmentAttributeProbe`; the vanilla sun transform reduces to world dir `(-sin a, cos a, 0)`
    (arcs east-west, up at noon, down at midnight). Derive a `dayFactor` (smoothstep over sun elevation),
    a warm→white sun radiance that fades out as the sun sets, and a dim cool moon radiance that fades in.
    The **active NEE light is the sun while above the horizon, else the moon**, so surfaces still get soft
    moonlight at night. Three new 16-byte-aligned `vec4`s pushed at `@208/@224/@240` (`sunDir`+dayFactor /
    `lightDir` / `lightRadiance`); `WORLD_PUSH_SIZE` 196→256.
  - **Shader (`world.rgen`):** the static sun consts are gone; NEE reads `pc.lightDir`/`pc.lightRadiance`
    (so sun glint + diffuse track the moving light). A new `skyRadiance(dir, primary)` computes a
    day/night-blended zenith↔horizon gradient + a warm sun halo that reddens/intensifies near the horizon
    (sunrise/sunset) + a bright sun disc. **The disc is added on PRIMARY rays only** — NEE already supplies
    the sun's direct light, so adding it to indirect rays would double-count + firefly. The miss branch now
    calls `skyRadiance` instead of using `payload.albedo`; `world.rmiss` is reduced to just flagging the
    miss (it can't see the pushed sun direction).
  - **VERIFY (GPU, pending):** with `-Dupscaler.rt.composite=true -Dupscaler.rt.blend=1
    -Dupscaler.rt.dlssRr=true`, `/time set` through a full cycle — shadows swing with the sun; warm low
    sun + reddened horizon halo at sunrise/sunset; dim blue moonlit night with shadows from the moon
    direction; sky gradient day↔night; one crisp sun disc, no fireflies from it (disc is primary-only);
    GI ambient tints with the sky. Watch: light-direction pop at the sun/moon horizon switch (both dim there — acceptable; note if jarring).
  - **P6.3b — soft shadows (DONE in working tree; builds; not yet GPU-verified).** The sun/moon was a
    delta directional light → hard shadow edges. Now the light has a finite angular size: `world.rgen`'s
    NEE jitters the shadow-ray direction within a cone (`sampleCone`, uniform over the spherical cap,
    half-angle = `pc.lightDir.w` radians) — the same sampled direction drives `ndl`, the shadow ray, and
    the specular half-vector (finite sun glint). Averaged over frames by DLSS-RR this gives soft penumbrae
    that **widen with occluder distance for free** (contact-hardening), and energy is preserved (the
    light's irradiance is unchanged; only the visibility becomes a soft average — ideal noise for RR).
    CPU pushes the radius in `lightDir.w`: `-Dupscaler.rt.sunAngularRadius` (deg, default 0.6),
    `-Dupscaler.rt.moonAngularRadius` (default 1.5; softer night shadows). **VERIFY:** shadow edges soften with
    distance from the caster (sharp at contact, blurry far away), no banding, night shadows softer than day.
  - **Deferred:** weather (rain/overcast dimming the sun + greying the sky via `getRainLevel`); moon-phase
    brightness; stars/moon disc in the night sky; a physical (Hosek-Wilkie) sky model; nether/end skies.
- **P7 — Perf & polish.** AS compaction, SER tuning, texture-LOD via ray cones,
  distant-geometry LOD or hybrid far-field, variable sample counts, settings UI.
  - **Material architecture refactor (agreed direction; do here, not mid-P6).** (a) **CPU-bake LabPBR →
    a canonical engine material format** (e.g. `{rough,metal,AO,emission}` + `{normalXY,F0}`) instead of
    decoding LabPBR in `world.rchit`. Not for shader perf (the in-shader decode is cheap, hit-only) but
    for: multi-format pack support (LabPBR/old-PBR/future branched on CPU, shader format-agnostic);
    **roughness-aware mipmapping** (Toksvig / roughness-from-normal-variance) which must be baked and is
    required for ray-cone texture-LOD to avoid specular aliasing; and steadier DLSS-RR guides at distance.
    Nearly free to add (the CPU blit already visits every texel); awkward bit = colored metal F0 needs 3
    channels. (b) **Bindless unification:** keep the terrain parallel-atlas (per-sprite bindless for
    blocks is a regression — atlas-space UVs make per-prim slots/UV-remap unnecessary), but unify all
    atlases/textures (block albedo/`_s`/`_n` + item atlas + entity textures) into ONE bindless
    `sampler2D[]` indexed by a per-hit material-set id — collapses fixed bindings 2/8/9 + the set-1 entity
    array, composes with the CPU-bake.
  - **Entity-path perf (deferred from P5.1, which prioritized correctness):** (1) pool + **refit**
    per-entity BLAS instead of rebuilding ~6 buffers + a fresh BLAS per entity per frame (biggest win;
    also relieves `maxMemoryAllocationCount`) — **DONE: step 1 pool/recycle (commit `9594939`) + step 2
    UPDATE-mode refit keyed by entity id (commit `9d87723`), both GPU-verified; `RtBufferPool` +
    `RtAccel.prepareUpdatableBlasBuild`/`refitUpdate`, gated `-Dupscaler.rt.entityRefit`. Entities only**;
    (2) **static BLAS cache for block entities — DONE in working tree (builds; not yet GPU-verified).**
    This was the new-chunk-loading **stutter**: every block entity in a 289-chunk window was re-extracted,
    re-meshed, and given a fresh BLAS *every frame* (P5-perf #1 was entities-only and never covered BEs).
    Now each BE's mesh + persistent pooled BLAS is **cached keyed by `BlockPos`** (`RtEntities.beCache`) and
    reused every frame — a cached BE costs only a geom-table write + a TLAS instance. Captured in
    **block-local** space (identity submit pose) so the cache survives rebase changes (placement is a
    translate-only instance transform `blockPos − rebase`, like terrain). Each frame the BE is re-meshed
    (cheap) and its mesh **hashed (FNV-1a)**; the expensive BLAS is rebuilt **only when the hash changed** —
    so static BEs do zero GPU work while animating ones (chest lid, spawner) rebuild every frame and stay
    smooth (a handful of small cheap builds, never the old hundreds-at-once). Rebuilds capped at
    `-Dupscaler.rt.beBuildsPerFrame` (default 8) so a chunk-load burst can't spike — over-budget BEs keep
    their last geometry / pop in over the next frames (like the terrain dispatch/drain caps). A changed BE gets a fresh
    AS (old one defer-freed) rather than an in-place refit, so no write races an in-flight trace. Reuses the
    entity `prepareUpdatableBlasBuild`/pool/deferred-free machinery; evicted (off-queue) out of the BE window.
    **Still per-frame:** the 289-chunk *scan* itself (cheap: chunk + `getBlockEntities` iteration, no GPU
    work) — full event-driven residency via load/unload is the remaining, lower-value follow-up; (3) per-RenderType
    **OPAQUE flag** so solid mob bodies skip the cutout any-hit (the transparency step made all entity
    geometry non-opaque); (4) reusable TLAS instance/scratch ring; (5) tap vanilla's extracted render
    states instead of re-extracting each frame; (6) distance-cull captured entities. (Terrain P0–P4 is
    already optimized.)

P0–P1 alone prove the whole concept. P4 is where it becomes real-time-viable.

## Hard problems specific to Minecraft RT (called out early)

- **Infinite world / AS memory**: can't BVH the whole world. Sliding window of
  loaded-chunk BLAS + per-frame TLAS; budget VRAM; far-field LOD or hybrid (RT near,
  cheaper far). The dominant scaling problem.
- **Coordinate precision**: f32 can't hold absolute world coords (±30M). Solved the
  same way vanilla does — **camera-relative**: section-local BLAS vertices + a
  `sectionOrigin − cameraPos` (double→float) TLAS instance transform per frame. Never
  let an absolute coordinate reach f32.
- **Chunk-edit churn**: block changes → re-extract + rebuild BLAS. We own the
  extraction, so dirty-tracking is clean — but block updates can be frequent.
- **Foliage / cutout**: alpha-tested leaves/grass need **any-hit shaders** to
  discard transparent texels — correct but a per-ray cost.
- **Water / glass / translucency**: refraction + ordered transparency in a path
  tracer; water surface normals/animation.
- **Texture LOD in hit shaders**: no hardware mip derivatives along a ray — naive
  mip-0 atlas sampling moirés on distant blocks. Need **ray-cone / ray-differential**
  LOD plus careful atlas mip + sprite-bleed handling.
- **Materials**: vanilla has only albedo (+ tint). Real PBR needs a resource pack;
  otherwise heuristic roughness/metalness like shaderpacks do.
- **Special-cased / procedural models**: some blocks don't survive generic extraction.
  Radiance *ships replacement models* for `redstone_dust_*` and `grass_block` and has
  dedicated hit shaders for `end_portal`/`end_gateway` — expect per-block handling for
  connected/tinted wire, procedural/animated, and view-dependent blocks.
- **Emissive mapping**: which blocks emit and how much (lava/glowstone/torches) —
  from the light-emission table and/or PBR emissive channel. Many emitters per scene →
  needs a light structure (grid/alias table, then ReSTIR), not uniform sampling.
- **Entity geometry**: `ModelPart` cuboids are **rigid boxes, not skinned meshes** —
  instance a unit-cube BLAS per part with per-part transforms, or refit a per-entity
  BLAS only on pose change. Far cheaper than the skinned framing; "many mobs" is a
  TLAS-instance-count problem, not a per-vertex skinning one.
- **Biome tint**: applied in vanilla meshing (`BlockColors`); consume it as albedo.
  (We do *not* replicate baked AO — sky-visibility rays give real AO.)
- **Sky / atmosphere**: physical sky model or sampling vanilla's sky for the miss
  shader and sun direction.
- **RT debugging / TDR**: heavy path-tracing dispatches can trip Windows' 2 s GPU
  watchdog (device-lost) — split work / raise `TdrDelay`. Use Nsight Graphics for AS
  inspection + the AS validation layers; capture GPU crash dumps for device-lost. The
  SBT is the most error-prone surface — validate it early.

## Integration shape

A new Fabric client mod (own repo, e.g. `mc-rt`), reusing `dlss-mod`'s `ngx/`+`ffx/`
packages (extract to a shared module, or depend on it). Mixins limited to: the
world-render seam (take over the world target), device creation (RT extensions),
and data access (atlas, baked models, entity render states, camera) — **not** the
67-mixin GL-translation surface Radiance needed, because vanilla already renders
UI/hand/particles into the Vulkan target we share. Native interop is **FFM (Java 25),
no JNI** — sidestepping Radiance's `msvcp140`/`vcruntime140` JDK-runtime crash class.
Geometry extraction borrows Sodium's mesher as a scaffold; everything else is our own
Vulkan code against `vkDevice()`. A toggle to fall back to vanilla/Sodium rasterization
when RT is off or unsupported.

## Scope & effort

The DLSS+FSR upscaler (~1-day) was *integration of two prebuilt SDKs over an existing
renderer* — there was a signed DLL to bind. This project **is** the renderer: an
extractor, AS lifecycle, the full RT-pipeline + SBT + path-tracing shaders, a sampling/
lighting system, and a denoiser integration. There's no SDK to bind, so the upscaler's
velocity does not transfer past bring-up. Realistic shape:
- **P0** (RT-pipeline + SBT bring-up): days. The SBT is the fiddly part; the triangle
  trace itself is quick.
- **P1** (sky-lit terrain): genuinely near-term *because* we borrow Sodium's mesher
  instead of rebuilding one — the model-shape/fluid long tail comes for free. This is
  the go/no-go proof and the first thing worth screenshotting.
- **P2–P4** (lifecycle → path-traced lighting → denoised real-time): the substantial
  middle. Architecture is small (one bounce-parameterized loop); the time goes into
  sampling quality (NEE/MIS/ReSTIR), denoiser guide-buffer correctness, and tuning.
- **P5–P7** (dynamic content, PBR, perf): long-tail polish that never fully ends.

Still the largest thing in this workspace by an order of magnitude over the upscaler.
This is **not novel** — Radiance/MCVR shipped hardware-RT path tracing for Java MC. Our
differentiator is the **integration model**: a co-tenant on vanilla's Vulkan device that
skips the ~67-mixin GL-translation layer Radiance needed on GL, via FFM rather than JNI.
Radiance is also the honest effort yardstick — a two-repo engine with ReSTIR/SHARC/
volumetrics/multiple denoisers is months of work; we save the GL layer, not the RT engine.

## Immediate next step

P0–P5 + P6.1/P6.2a/P6.2b are done; the renderer is DLSS-RR-only. **P6.3 — dynamic sky (day/night)** is
DONE in the working tree (builds; sun/moon driven by the game's time of day, dynamic sky gradient + glow +
disc) and needs **GPU verification** (`/time set` through a full cycle — see
P6.3 VERIFY). Entity PBR (P6.2c) is deferred. After P6.3 verifies, the next substantial features are
**ReSTIR DI + SHARC GI** (P3.3 — the principled many-light/GI upgrade; overlaps P7 material work) and the
smaller P5 close-outs (biome water tint, grass cross-model dedup, water sun glint).

Smaller deferred items that can slot in anytime: **grass cross-model dedup** (collapse coincident
`cross` quads to kill the z-fight); the **ReSTIR DI + SHARC GI** quality pass (P3.3); a
**vendor-agnostic denoiser/upscaler fallback** (SVGF/NRD + FSR/XeSS) and a non-RTX path — explicitly
deferred to the end of the project; and DLSS-RR fine-tuning (RR preset sweep, fixed-exposure A/B vs
AutoExposure, jitter-sign reconfirm via the dev DLL).
