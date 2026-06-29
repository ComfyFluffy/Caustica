# RT CPU worker pool plan

## Motivation

`RtTerrain.tick(ctx)` runs entirely on the client render thread (called from
`UpscalerClient`'s `START_CLIENT_TICK`). The expensive part is **CPU
tessellation**: for every newly-resident section it walks all 4096 blocks and
calls vanilla `ModelBlockRenderer.tesselateBlock` + `FluidRenderer.tesselate`
into a `SectionMesh` (`prepareSection`, `RtTerrain.java`). This is throttled by
`ASYNC_DISPATCH_PER_TICK` (default 64) purely to bound the per-frame cost — i.e. we already
*know* it's too heavy to do unbounded on the render thread, and the cap just
trades it for slow terrain fill-in.

The only thing currently asynchronous is the **GPU** BLAS build
(`ctx.submitAsync(cmd -> RtAccel.recordBlasBuilds(...))`, one batch in flight).
The CPU mesh build that feeds it is still synchronous.

Goal: move CPU-heavy RT work onto a worker pool so the render thread only does
(a) cheap bookkeeping and (b) Vulkan calls, and so terrain can fill faster
without frame hitches. Generalize the pool so LOD build (future) and other
CPU jobs reuse it.

## Hard constraints (why we can't just `executor.submit(this::prepareSection)`)

1. **Live-level reads are not thread-safe.** `LevelView` (the
   `BlockAndTintGetter` we hand to the renderers) reads the live `ClientLevel`
   directly — `getBlockState`, `getFluidState`, `getLightEngine`,
   `getBlockTint`, `getBlockEntity`. Vanilla never does this from its chunk-
   compile workers; it builds an immutable region **snapshot**
   (`RenderChunkRegion`-style: block states + light + biome/colors + block
   entities for the 18³ region) on the main thread first, then tessellates the
   snapshot off-thread. We must do the same. **This is the core of the work.**

2. **One shared graphics queue.** `RtContext.submitSync/submitAsync/get` are
   `synchronized`, but the `VkQueue` is shared with Minecraft's render thread,
   which we do not synchronize against. So **all `vkQueueSubmit` stays on the
   render thread.** Workers never submit.

3. **Keep GPU object creation on the render thread (v1).** `ctx.createBuffer`
   goes through VMA (internally synchronized) and `prepareTrianglesBlas` queries
   AS sizes; these are *probably* safe off-thread, but the conservative split is
   workers produce a pure-CPU `SectionMesh` and the render thread does every
   Vulkan touch. Revisit moving allocation/upload to workers only if profiling
   says the render-thread upload is the new bottleneck.

4. **Renderer/capture instances are stateful** (`ModelBlockRenderer`,
   `FluidRenderer`, `QuadCapture`/`FluidCapture`, the `BlockColors`-keyed
   capture). Today one set is made per tick and reused across sections
   sequentially — fine single-threaded. For the pool they must be **per-worker
   (ThreadLocal) or per-task**, never shared across threads.

5. **Resource reload.** Baked models / atlas sprites are immutable post-bake and
   safe to read concurrently, but a resource reload swaps them. Guard against a
   reload landing mid-build (drop in-flight results across a reload, same as
   vanilla discards its compile tasks).

## Design

### Thread split

- **Render thread (`tick`)** — unchanged residency logic, plus:
  1. compute desired/missing sections (as today),
  2. for each missing section, capture a **`SectionSnapshot`** of its 18³ region
     from the live level,
  3. enqueue `{key, epoch, snapshot}` to the pool (priority = nearest-first),
  4. drain *completed* `SectionMesh` results (bounded per tick), do the Vulkan
     (buffer create + upload + `prepareTrianglesBlas`), batch into the existing
     `startBuild` → `submitAsync`.

- **Worker pool** — pure CPU: `tessellate(snapshot) -> SectionMesh` using a
  per-thread renderer/capture set and a `BlockAndTintGetter` backed by the
  **snapshot** instead of `LevelView`.

### `SectionSnapshot`

Mirror vanilla's `RenderChunkRegion` (verify the exact 26.2 class name; if it's
usable directly, prefer constructing vanilla's own snapshot to inherit correct
tint/light semantics). Must carry, for the section + 1-block border (18³):
- block states (and thus fluid states),
- block + sky light (for AO/lightmap),
- biome tint inputs (`getBlockTint` / `ColorResolver` cache — the fiddly bit),
- block entities only if terrain extraction needs them (normal-block tessellation
  generally does not; BE meshing lives in `RtEntities`, stays render-thread).

Snapshot cost on the main thread should be ≪ tessellation cost; that's the whole
point. Keep `ASYNC_DISPATCH_PER_TICK` as the *snapshot/dispatch* cap, with a separate
`SECTION_RESULTS_PER_TICK` cap for the Vulkan side.

### Pool

- Fixed `ExecutorService`, daemon threads named `rt-worker-N`, sized
  `-Dupscaler.rt.workerThreads` (default `clamp(availableProcessors/2, 1, 4)` —
  leave cores for MC's own chunk meshers).
- Bounded in-flight work (don't snapshot faster than workers drain) — natural
  backpressure via a max-pending count.
- Completion via a concurrent result queue drained in `tick`.

### Staleness / cancellation

A section can leave the window, unload, or be re-dirtied while its task is in
flight. Tag every task with `(key, epoch)` where `epoch` bumps when the section
is dirtied/removed. On completion, **discard** the `SectionMesh` if the section
is no longer desired or `epoch` changed. This prevents uploading stale geometry
and is cheaper than trying to cancel running tasks.

### Pipelining the GPU build

Independent from the pool, but enabled by it: with many meshes ready we can
deepen the BLAS pipeline beyond "one batch in flight" (small ring), still
submitting only from the render thread. Optional follow-up, separate knob.

## Rollout

1. **[DONE — always on]** Snapshot extraction. `LevelView` (live-level reads) is
   gone; tessellation runs against vanilla's `RenderSectionRegion`, captured on
   the render thread with `RenderRegionCache.createRegion(level,
   SectionPos.asLong(scx,scy,scz))` (26.2's class names — *not* `RenderChunkRegion`;
   note the mod's internal `sectionKey` packs bits differently from
   `SectionPos.asLong`, so build a real section long for the call). `prepareSection`
   split into pure-CPU `tessellate()` + render-thread `uploadSection()`. LabPBR
   `RtBlockMaterials.ensure()` (GPU texture create/upload + non-concurrent cache)
   is deferred off the meshing path via `SectionMesh.triSprites` (one sprite per
   prim record, `null` for fluids) and resolved in `resolveMaterials()` at upload.
   **Verify: byte-for-byte identical terrain vs before (no visual change).**
2. **[DONE — always-on]** `RtWorkerPool` +
   dispatch/drain with token-based staleness drop. All Vulkan stays on the
   render thread. Two bugs found and fixed during GPU testing:
   - **Fill latency**: `tick()` runs at 20 TPS and the `pending` early-return
     built only one batch every *other* tick. Fixed by falling through after
     `finalizePending` (build every tick) + larger async-only batch caps.
   - **Flicker on edits**: re-dirtied sections were evicted immediately but
     rebuilt several ticks later (sync rebuilds same-tick, so no gap). Fixed by
     in-place re-extraction — keep the old geom resident and traced until the new
     mesh swaps in (`dispatchReextract`; `startBuild`/drain retire the replaced
     geom). GPU-confirmed fixed.
3. **[TODO] Tune** worker count + per-tick caps under sustained load.
4. **[TODO] Generalize** to a typed job queue so LOD build and atlas stitching
   (`RtBlockMaterials`) reuse it.

Knobs added: `-Dupscaler.rt.workerThreads` (clamp(cores/2,1,4)),
`-Dupscaler.rt.asyncDispatchPerTick` (64), `-Dupscaler.rt.sectionResultsPerTick` (64),
`-Dupscaler.rt.maxInflightSections` (192).

## Out of scope / explicitly render-thread

- **Entity + block-entity capture** (`RtEntities`): per-frame, must be ready the
  same frame for that frame's TLAS — async would add a frame of latency. Leave on
  the render thread.
- **All `vkQueueSubmit`** (constraint 2).

## Future: LOD build

Distant sections → coarser/merged meshes are the same shape (snapshot → CPU
build → GPU upload) and slot straight into the typed pool: a `LodJob` produces a
decimated/merged `SectionMesh` for a multi-section region, uploaded and built via
the same render-thread Vulkan path. The snapshot for LOD spans several sections;
budget it separately from near-field terrain so distant rebuilds never starve
near fill-in.
