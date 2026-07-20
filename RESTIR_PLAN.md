# ReSTIR DI — port plan (main, post material-system refactor)

Status: PLAN (2026-07-18). No ReSTIR code exists on `main`. The previous implementation lives on
branch `restir` (5 commits: `aab3ff5` Stage 0, `3e61c9e` Stage 1, `2df12bc` quad lights/footprint/
soft shadows, `5047429` temporal reuse) but is based 143 commits behind `main` — before the Slang
shader migration, the `dev.upscaler.rt` → `dev.comfyfluffy.caustica.rt` split, the material-system
refactor (#18), the persistent COW section table, and the async terrain pipeline. Every file it
touches has been renamed or rewritten.

**Strategy: re-implement on `main`, using `restir` as an algorithm reference** (`git show
restir:src/main/java/dev/upscaler/rt/RtLights.java`, `git show restir:shaders/rt/world.rgen`).
No rebase, no cherry-pick. All the algorithmic decisions on that branch were GPU-validated and
carry over verbatim; only the plumbing changes.

## What ReSTIR DI buys

`world.rgen.slang` has NEE only for the sun/moon delta light. Block emitters (torches, glowstone,
lava, lanterns…) contribute **only when a path ray happens to land on them** (the emission add in
`tracePath`) — very noisy for small emitters. ReSTIR DI samples them explicitly:

- **Stage 0** — enumerate emissive quads into a samplable light list (one area light per quad).
- **Stage 1** — RIS NEE at each diffuse vertex: M candidates → weighted-reservoir select 1 by
  unshadowed p̂ = luminance(f·Le·G·area) → ONE shadow ray. `W = (wSum/M)/p̂`.
- **Stage 3** — power/nearby-weighted candidate sampling (ReGIR grid, done). Spatial reuse remains
  open work.

Sun/moon NEE stays separate and additive. `risCandidates = 0` ⇒ everything off, bit-identical to
today's always-gather behaviour.

**Stage 2 (temporal reservoir reuse) was implemented and then removed (2026-07-20).** Persistent
per-pixel reservoirs (64 B ping-pong buffers, MV reprojection, permutation sampling) worked and were
GPU-verified, but temporal reuse fights DLSS-RR's own history/AA rather than complementing it, and
single-frame RIS over the ReGIR grid is already sufficient noise-wise. The removed machinery is
recoverable from git history (`d1b22e3` "temporal reuse" and neighboring commits on this branch) if
temporal reuse is revisited later; it is intentionally not kept around dark in the shipping path.

## Why not the RTXDI SDK

Decision (2026-07-18): use RTXDI as a **reference codebase only**, no dependency. The C++ host
library is unusable from the Java/LWJGL host outright; the HLSL shader headers assume they own the
resampling loop via the `RAB_*` application bridge, which conflicts with our inline single-rgen
tracer and its MC-specific behavior (membership/always-gather gate, water/glass paths, RR guide
capture, BDA std430 ABI). Our ~22k lights don't need machinery sized for millions. Licensing is
NOT the blocker (Apache-2.0 since RTX Kit). What we port as ideas, per stage: power-based presampling
tiles OR ReGIR for the S3 sampler (ReGIR's world-space grid is a natural fit for MC sections),
pairwise MIS for S3 spatial, approximate-p̂/exact-Le-at-shade split (S3 color).

## What the material system replaces (simplifications vs the old branch)

The old `RtLights` scraped sprites ad-hoc (`SPRITE_CACHE`, `SpriteContentsAccessor.originalImage`,
manual `_s.png` resource reads, hand-rolled hasS branching). All of that is gone:

1. **Le comes from the registry.** `RtMaterialDesc` already carries `emissionSource`
   (NONE/OVERRIDE/LAB_PBR/HEURISTIC_MASK/STATE_UNIFORM), `emissionStrength`, and an
   `EmissionSummary` (average RGB of emissive texels, integratedLuminance, coverage) computed at
   the resource epoch from the same canonical pages the GPU samples. The light builder branches on
   `emissionSource` exactly like `world.rchit` does via `MaterialHeader.features` — consistency
   with the direct-hit emission term is structural, not maintained-by-hand.
2. **Per-quad emissive footprint becomes a per-MATERIAL precompute.** Old branch scanned a 16×16
   grid per quad per section rebuild (NativeImage reads on the meshing path). Instead: at material
   compile time, reduce the canonical emission channel to a 16×16 summary grid per emissive
   material (per-cell emissive luminance + RGB). Per quad, the footprint scan (emissive centroid,
   emissive-fraction area shrink, emissive-only mean Le, fill-ratio gate) is then a read of that
   grid over the quad's sprite-local UV rect — no image decode anywhere near the mesher. Store it
   on the material entry next to `EmissionSummary` (CPU-side only; the GPU never needs it).
3. **Lava probably fixes itself.** Old bug: fluid path passed a null sprite → white light. Fluids
   now carry a real `materialId` through `RtFluidMesher`, so Le falls out of the registry like any
   other material. Verify with the light dump.
4. **Jack-o'-lantern / `_s`-replaces-block-light semantics** are already encoded in
   `EmissionSource.LAB_PBR` — no special-casing in the light builder.
5. **`EMISSIVE_STRENGTH` is 5.0 now** (`world.rgen.slang`), not the old branch's 6.0. Keep the
   light builder's constant literally the same symbol/value — a mismatch is a visible seam between
   NEE-lit and directly-viewed emitters.

## Integration points that changed (vs the old branch's plumbing)

1. **Membership flag** (in-light-buffer vs always-gather). Old: SIGN of `prim.normal.w` —
   `normal.w` now carries emission + the cutout `+2` flag. Use **bit 0 of the free
   `TerrainPrim.flags` lane** instead (currently written as 0 by `RtTerrainMesher.emit`). rchit
   forwards it in a free `Payload.flags` bit (bits 0..6 taken; use bit 7). rgen gates the
   direct-hit emission add ONLY for in-buffer emitters; excluded emitters keep the always-gather
   path (bit-identical to no-NEE, no energy lost).
2. **Section-local lights, global buffer rebuilt on publish, not per frame.** Geometry is
   section-local with the TLAS instance transform `sectionOrigin − rebaseOrigin`; rebase only
   happens past `REBASE_DISTANCE_BLOCKS`. So: build `SectionLights` (packed float array) on the
   worker in `RtSectionBuilder`/mesher alongside geometry (pure CPU, async-safe); flatten resident
   sections' lights into one device-local global buffer in `applyBuildChanges` — only when the
   resident set or rebase origin changes. Cheaper than the old per-frame reassembly.
   **Atomicity rule:** the light buffer must swap in the same publish step as the instance list /
   section table it was built from — a light for a section not in this frame's TLAS is a ghost
   emitter (its shadow ray finds no occluder and it brightens the scene). Retire the old buffer
   with the same in-flight-frame lifecycle the section table generations use.
3. **Push constants are generated.** Add to `WorldPush` in `world_common.slang`:
   `uint64_t lightBufAddr`, `uint lightCount`, `uint risCandidates`. Java serialization regenerates
   from Slang reflection — none of the old branch's manual 336→368 offset bookkeeping.
4. **Shader port is GLSL → Slang.** `Light` (48 B: posArea, normalPad, le) becomes a struct read via
   `ConstPtr<T>` (std430 — the layout parameter is load-bearing, see world_common banner). Helpers
   to port: `risInitial`, `evalSampleContrib`, `shadeReservoir`. `tracePath` gains `(int2 pix,
   float2 size, bool primarySample)` like the old branch.
5. **Guides already exist.** `gv_emission`/`gv_emissionSource` are captured at bounce 0; the RIS
   shading slots in next to the sun NEE block; visibility uses the existing hit-object
   `visibility()` (SER-safe).
6. **Config** goes through `CausticaConfig` runtime settings (system-property override works the
   same as the old `-D` flags): `risCandidates` (int, def 8, 0=off), `lightMinFillRatio`
   (float, def 0.25), `lightStats` / `lightDump` / `lightDumpRadius` debug settings.

## Stages

### S0 — light collection + global buffer + debug dump (no shading change)
**Status: IMPLEMENTED 2026-07-18** (working tree, compiles; NOT GPU-verified, counts not yet compared
to the old branch). Landed: `RtEmissionGrid` + grids compiled in `RtBlockMaterials.decode` /
`computeSpriteStats`, exposed via `RtMaterialRegistry.Snapshot.emissionGrid(id)`; `RtLightCollector`
(worker-side, pre-pack, stamps `TerrainPrim.flags` bit 0); lights ride
`PackedSection → PreparedSection → SectionGeom`; global buffer rebuilt in
`RtTerrain.applyBuildChanges` with graphics-fence retirement (`lightBufferAddress()`/`lightCount()`);
config `caustica.rt.risCandidates` / `lightMinFillRatio` / `lightStats` / `lightDump` /
`lightDumpRadius`. Known cost: every sprite's emitting variant retains a 4 KiB albedo grid (~8 MiB
CPU at vanilla sprite counts) — trim later if it matters.
- Per-material 16×16 emission summary grid at registry compile.
- `RtLightCollector` (terrain package): quad reconstruction from bucket buffers — `emit()` still
  writes 2 lockstep triangles (0,1,2)(0,2,3) over 4 consecutive verts with prim/cornerUv in step,
  so quad k = tris 2k, 2k+1. One light per emissive quad: position = emissive-texel centroid via
  barycentric map, area = quad area × emissive fraction, Le = emissive-only mean × strength ×
  EMISSIVE_STRENGTH, membership gate (Le luminance eps + fill-ratio density gate).
- **Light record is a RECTANGLE with a UV frame, not the old 48 B disc.** ~64–80 B: center,
  rectangle half-axes (emissive-footprint bounding rect), mean Le, normal, materialId + affine UV
  frame (the rectangle's (s,t) parameterization maps linearly to sprite-local UV). Rationale: the
  old committed version sampled a disc (rectangle was tried and reverted, but for failing to fix
  the redstone self-occlusion, not on its own merits); the rectangle doesn't overshoot the emitter
  shape (torch stick), and the UV frame is what enables exact-Le shading later (S3) without a
  buffer reformat. ~22k lights ⇒ ~1.7 MB, irrelevant.
- **Light color policy:** S0–S2 use the per-quad emissive-mean RGB (the GPU-validated baseline;
  whole-sprite averages are too coarse — a torch would cast mostly-dark-texel grey). Pixel-accurate
  color is deliberately NOT in v1: NEE color only affects cast light, where texel-scale chroma is
  blurred by falloff + DLSS-RR; directly-viewed emitter faces are already pixel-accurate via path
  hits. Exact per-texel Le lands in S3 (see below) — the mean stays as the RIS target p̂ even then.
- `SectionLights` on `SectionGeom`; global buffer assembly + lifecycle in `applyBuildChanges`;
  `TerrainPrim.flags` bit 0 membership write.
- Debug: `lightStats` totals, `lightDump` radius dump (pos/√area/Le/lum). **Verify:** counts and
  per-light records sane vs the old branch's numbers (~22k lights full distance after quad merge);
  lava colored; no NativeImage access from workers.

### S1 — RIS NEE (Slang) + membership gating
**Status: IMPLEMENTED 2026-07-18** (working tree, `compileShaders compileJava` clean; NOT
GPU-verified). Landed: `Light` struct + `WorldPush.lightBufAddr/lightCount/risCandidates` +
`PAYLOAD_EMITTER_IN_LIST` (bit 7) in world_common; membership forward in world.rchit;
`Reservoir`/`evalSampleContrib`/`risInitial` (rectangle sampling, pdf 1/area)/`shadeReservoir` in
world.rgen with the RIS block after the sun NEE and the showCelestial-aware emission gate; push
values wired in RtComposite. GPU verify list below still open.
- Port `risInitial`/`evalSampleContrib`/`shadeReservoir`; per-diffuse-vertex RIS with M =
  `risCandidates` uniform candidates, rectangle sampling on the light for soft shadows (uniform
  (s,t) over the half-axes; pdf = 1/area, same estimator shape as the old disc), ONE shadow ray.
- rchit: forward membership payload bit; rgen: gate the direct-hit emission add on it.
- **Verify (GPU):** A/B `risCandidates=0` — no energy shift on a scene with only excluded
  emitters; torch/glowstone noise visibly drops; no seam between an emitter's lit surroundings and
  its directly-viewed face; `compileJava compileShaders -q` clean.

### S2 — temporal reservoir reuse (implemented, then removed)
Was implemented 1:1 per the old branch's validated design: 64 B ping-pong reservoirs, MV
reprojection, RTXDI permutation sampling, rebase correction, disocclusion reject, M cap, visibility
reuse. GPU-verified working. **Removed 2026-07-20**: temporal reuse fights DLSS-RR's own
history/AA rather than complementing it, and single-frame RIS over the ReGIR grid (S3) is already
sufficient. Recoverable from git history (`d1b22e3` "temporal reuse" on this branch) if revisited.

### S3 — candidate sampling + spatial reuse (new work, in this order)
1. **Power/nearby-weighted candidate sampling** — uniform RIS over the whole list starves
   small/distant lights at ~22k lights; this was the known bigger win before spatial reuse.
   Simplest adequate structure: per-section light ranges already exist — build a coarse
   section-level power CDF (total Le·area per section, distance-attenuated per frame or per
   rebuild), sample section then light-within-section. Evaluate before reaching for a full
   light BVH.
   **Hierarchy refactor started 2026-07-19.** ReGIR cells now reference stable section slots;
   section headers own contiguous light ranges and power-weighted local aliases. Global light packing,
   global alias construction, local aliases, and the ReGIR grid are prepared by one coalescing worker
   and uploaded/published as one immutable generation. The previous complete generation remains active
   until its replacement is GPU-complete—normal light edits no longer clear ReGIR or alternate through
   a global-only fallback. A retained generation carries its own rebase origin; light centers and its
   ReGIR grid origin are translated by `hierarchyRebase-currentRebase` while a rebased replacement is
   pending. This first generation deliberately retains compact shared `Light[]` storage;
   replacing those ranges with independently updateable section pages is the next hierarchy step and
   does not require another shader sampling-contract change.
2. **Exact texel Le at shade time (pixel-accurate light color).** RTXDI-style split: the RIS
   candidate loop keeps the cheap per-quad mean (no texture fetch × M candidates), but the ONE
   selected sample fetches the true emission texel at its (s,t) → UV point from the canonical
   material pages (`materialId` + the record's UV frame; plumbing exists via `MaterialHeader`).
   RIS is unbiased under an approximate target p̂, so this is exact, at one fetch/pixel/frame.
   Main visual payoff: lava and fire — large, mottled, ANIMATED emitters where the epoch-frozen
   mean is visibly wrong; an at-shade fetch of live albedo × emission mask tracks the animation.
3. **Spatial reuse** — no longer planned; spatial reservoir merging has the same
   fights-DLSS-RR issue that got temporal reuse removed (see S2). Revisit only if single-frame RIS
   over a wider/cascaded ReGIR grid turns out insufficient.

## Known deferred issues (unchanged from the old branch)
- **Redstone-torch self-occlusion**: the emitter's own solid two-layer mesh shadows its lights
  (any-hit hacks don't work — OMM makes cutout opaque and the torch is a solid mesh). The material
  refactor did NOT fix this by itself; candidate fixes (shadow-ray instance masking for the
  emitter's own quads, or accepting near-field bias) belong to a later pass. Merge-coincident-quads
  (redstone ≈ 6 stacked lights) also still open.
- **NEE specular glint on self-lit faces** with PBR on.
- **Entity/block-entity emitters** (`Prim.normal.w` per-prim emission exists now) — out of scope
  for v1; terrain lights only.
- Biome-tinted emitters; per-triangle (vs per-quad-mean) footprint.

## Build / verify
`./gradlew compileJava compileShaders -q` (git on PATH in PowerShell only). GPU verification per
stage as listed; every stage lands behind its config flag with `risCandidates=0` as the hard
off-switch.
