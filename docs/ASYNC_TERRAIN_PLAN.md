# Full-Async Terrain Pipeline — Plan of Record

Status: **M2 implemented; runtime validation pending** (2026-07-12). M0 is compile-tested and M1 is
Vulkan-validation clean. M2 moves buffer fill plus OMM/BLAS creation/enqueue into worker-owned jobs,
batches up to 32 recordings per executor submission, removes the single `Pending` build restriction,
and adds a configurable native-resource admission cap using conservative 16 MiB section reservations.
Supersedes the
incremental worker-direct-to-mapped-buffer change
(already in the working tree) as the target architecture; that change survives as the M2 worker job's
buffer-fill step.

## Goal

Move the entire per-section terrain pipeline — mesh, buffer alloc/fill, OMM + BLAS create, **GPU build
submission**, and destroy — off the render thread. The render thread keeps only what it must own:
world-state snapshots, residency/dirty bookkeeping, and the publish swap (replacing old BLAS instances
with built ones in the section table / instance list). Also: split the 2650-line `RtTerrain.java`.

```
render thread                worker pool (N)                 GPU executor (1 thread)
─────────────                ───────────────                 ───────────────────────
window sync / dirty drain
snapshot (RtSectionSnapshots)
dispatch job ──────────────► mesh (CPU)
                             create+fill VMA buffers
                             create OMM + BLAS + scratch
                             enqueue build ────────────────► batch N builds → 1 cmd buf
                                                             submit on COMPUTE queue
                                                             signal timeline value V
                                                             wait V (blocking, own thread)
                                                             free scratch
publish queue ◄──────────────────────────────────────────── completion {section, V}
drain: token check, swap
  into table/instances,
retire old geom ───────────────────────────────────────────► destroy after graphics completion G
frame submit: device-side
  wait on build timeline max(V)
  signal graphics timeline G
per-frame TLAS (unchanged)
```

Retirement is completion-driven, not frame-count-driven: an object used by graphics submission `G`
is destroyed only after the graphics timeline reports `completed >= G`. There is no `KEEP_FRAMES`
heuristic in the async terrain path.

## Verified facts this design rests on

1. **Vanilla creates a dedicated compute queue it never uses.** `VulkanPhysicalDevice` picks
   graphics + compute + transfer families; `VulkanDevice.computeQueue()` exists with **zero call
   sites** in blaze3d (26.2). On devices where it aliases `graphicsQueue`, Caustica detects equal
   `VkQueue` handles and fails initialization. AS builds are legal on any compute-capable queue.
2. **Vanilla's frame submission supports timeline semaphores natively.**
   `VulkanQueue.Submission.waitSemaphore(sem, value, stageMask)` / `signalSemaphore(...)` build
   `VkSemaphoreSubmitInfo` for `vkQueueSubmit2KHR`. The composite cmd buffer goes through
   `encoder.execute(cmd)` into that frame Submission, so the encoder directly brackets that command
   with the build wait and graphics-completion signal.
3. **VMA is internally synchronized** (allocator not created with
   `EXTERNALLY_SYNCHRONIZED`); `RtContext.createBuffer` / `RtBuffer.destroy` are already called from
   workers in the current working tree. This protects allocator bookkeeping only: worker writes to
   mapped allocations are explicitly flushed before GPU submission unless the allocation is known
   host-coherent.
4. **`RtParallelAtlas.ensure` is worker-safe** (ConcurrentHashMap hot path; blit documented
   parallel-safe) — though we keep hasS/hasN patching on the render thread at publish for now (it's a
   tiny mapped write; see Non-goals).
5. `vkCreateAccelerationStructureKHR` / `vkCreateMicromapEXT` / destroys are externally synchronized
   **only on the objects themselves** — parallel creation on distinct objects is spec-legal. Target
   hardware is NVIDIA-only (OMM, DLSS), where this is routine engine behavior.

## Components (also the file split)

| File | Owns | Runs on |
|---|---|---|
| `RtTerrain.java` (~900 ln) | residency window, dirty/missing/reextract queues + heaps, dispatch decisions, publish swap, `clear()` | render thread |
| `RtSectionSnapshots.java` (exists) | persistent palette snapshot cache | render thread |
| `RtTerrainMesher.java` (~900 ln) | `WorkerTessState`, `tessellate`, `QuadCapture`, `FluidCapture`, `SectionMesh`, `Geom`, `SpriteList`, `PendingQuad`, translucent-avg cache | workers |
| `RtSectionBuilder.java` (~250 ln) | the worker job body: mesh → `packToBuffers` → OMM/BLAS create + scratch → enqueue build; `destroy*` helpers for discarded results | workers |
| `RtSectionTable.java` (~200 ln) | section-slot registry, graphics-completion-guarded table generations, `writeSectionEntry`, instance transforms/rebase | render thread |
| `RtGpuExecutor.java` (~300 ln, new) | dedicated thread; compute queue + command pool; build + graphics timeline semaphores; build-batch queue; completion-driven destroy queue | its own thread |

`RtGpuExecutor` is deliberately generic — future users: LOD proxy BLAS builds (LOD plan M2), entity
BLAS pre-builds, standalone compute prepasses.

```java
final class RtGpuExecutor {
    /** Enqueue GPU work; returns the timeline value that will signal when it completes. */
    long enqueueBuild(Consumer<VkCommandBuffer> record, Runnable onComplete);
    /** Highest build-timeline value known complete (host-side cache of vkGetSemaphoreCounterValue). */
    long completedBuildValue();
    /** Destroy a published object after the graphics submission that last used it completes. */
    void enqueueDestroyAfterGraphics(long lastUseValue, Runnable destroy);
    /** Destroy an unpublished object whose build has completed; no graphics submission can reference it. */
    void enqueueDestroyUnpublished(Runnable destroy);
    /** Highest graphics-timeline value known complete. */
    long completedGraphicsValue();
    /** Build value every future graphics submit must wait on until a submit acknowledges it. */
    long pendingPublishWaitValue();
    void flushAndJoin();   // teardown: drain queues, wait timeline, then caller does waitIdle
}
```

### Queue ownership

M1 implementation order at init:
1. **Vanilla's reserved `computeQueue()`** when its handle differs from `graphicsQueue()`. Vanilla
   already reserves a distinct queue index when the device exposes one, so M1 borrows that currently
   unused queue rather than changing device bring-up. Re-verify that ownership on every MC version
   bump.
2. **Aliased/single-queue devices are unsupported:** initialization throws when compute and graphics
   resolve to the same `VkQueue`. Silently submitting from the executor would violate Vulkan's
   external-synchronization requirement; routing submission back through the render thread would no
   longer be a fully asynchronous terrain path.

If vanilla begins using its compute queue, replace item 1 with a private queue acquired at bring-up:
`RtDeviceBringup` can bump the
   compute family's `queueCount` by 1 when the family has capacity and `vkGetDeviceQueue` our own
   private queue. If graphics and compute share a family, extend the existing
   `VkDeviceQueueCreateInfo` and priority array rather than adding a duplicate family entry; request
   the extra queue only when `requestedCount < VkQueueFamilyProperties.queueCount`, then retrieve the
   new queue index. Immune to vanilla ever starting to use `computeQueue()`.
This private-queue path is deliberately deferred because current vanilla bring-up already reserves the
queue M1 needs; it becomes mandatory if that ownership assumption changes.

### Cross-queue correctness

- **Sharing:** terrain mesh buffers, AS backing, OMM backing move to
  `VK_SHARING_MODE_CONCURRENT` across {graphics, compute} families (new `createBuffer` overload).
  Avoids EXCLUSIVE-mode queue-family ownership transfers entirely; buffer reads via BDA on NVIDIA are
  insensitive to CONCURRENT. Only needed when the executor runs on a different family.
- **Execution/memory dependency — the device-side wait is REQUIRED, not optional.** The executor
  signals the timeline at `ACCELERATION_STRUCTURE_BUILD_BIT_KHR`; the graphics queue must carry a
  device-side `waitSemaphore(timeline, V)` before work that reads the published BLASes. Host-observing
  the timeline value does **not** substitute: it gives execution ordering trivially, but the memory-
  visibility chain it would need (device writes → host-domain availability via host wait → back to
  device visibility via `vkQueueSubmit`'s implicit host→device operation) leans on spec text worded
  around *host writes* (§7.9), is weaker still for a `vkGetSemaphoreCounterValue` poll than for a real
  host wait, and "works on NVIDIA" would hide the gap from sync validation. The signal/wait semaphore
  pair is the canonical, unambiguous cross-queue memory dependency — use it.
  M1 attaches the wait directly to the existing `VulkanCommandEncoder` immediately before
  `encoder.execute(rtCmd)`, and attaches the graphics timeline signal immediately afterward. All
  three operations therefore enter vanilla's same real frame `Submission` in the required order,
  with no mixin and no extra queue submits.
- **Host-side timeline poll = scheduling only.** The render thread publishes a section only once
  `completedBuildValue() >= V` — this keeps the device wait always-already-signaled (zero stall), but it is
  a scheduling gate, not the correctness mechanism.
- **Graphics completion is a separate timeline.** Every successful graphics frame submission signals
  monotonically increasing value `G` at the end of its work. A resource replaced before submission
  `G+1` is retired against the latest successfully submitted `G`, and is destroyed only after
  `completedGraphicsValue() >= G`. The build timeline proves construction completed; it never proves
  that an older graphics frame stopped tracing a published BLAS.
- **Wait acknowledgment:** `pendingPublishWaitValue()` is not cleared when queried or when the render
  thread merely records a frame. It remains attached to subsequent graphics submissions until one is
  successfully queued with that wait. Repeating a wait on an already-signaled timeline value is safe;
  losing the wait on a skipped or failed frame is not.
- **Timeline feature:** core 1.2 (`timelineSemaphore`); vanilla's own Submission uses semaphore
  values already. Confirm enabled at bring-up; force-enable in `RtDeviceBringup` like the other
  feature structs if not.

The preferred frame integration adds both the build-timeline wait and graphics-timeline signal to
vanilla's real frame `Submission`. If that mixin is too version-fragile, use two render-thread-owned
submits on the same graphics queue: a wait-only submit immediately before the frame submit, and a
signal-only submit immediately after it. Queue submission order then brackets the frame without
concurrent access to the queue; the post-submit value is acknowledged only after all three submits
were queued successfully.

### Host writes and queue-family sharing

- `VMA_ALLOCATION_CREATE_HOST_ACCESS_*` plus `MAPPED` does not guarantee host-coherent memory. After
  a worker fills positions, indices, UVs, material, or OMM input buffers, call
  `vmaFlushAllocation`/`RtBuffer.flush()` before enqueueing the build (a coherent allocation may make
  the helper a no-op). VMA's internal synchronization protects allocator metadata, not visibility of
  host writes to the device.
- Concurrent sharing (or explicit ownership transfer) is an **M1 prerequisite**, not a later M2
  detail. The first submission on a different compute family must already use cross-family sharing
  for every resource subsequently consumed by graphics: mesh/shading buffers, BLAS backing, OMM
  backing, and persistent OMM data. Compute-only scratch remains exclusive to the compute family.

### Job lifecycle

1. **Dispatch (render thread)** — unchanged: snapshot region, token, submit to `RtWorkerPool`.
2. **Worker job:** mesh → if empty, complete(empty) (no GPU objects) → else create+fill buffers →
   OMM create → BLAS create + sizes + scratch → `enqueueBuild(record, onComplete)` where `record` is
   today's `recordBlasBuilds` content (OMM build → barrier → BLAS build) and `onComplete` pushes
   `{job, geom, V}` onto the render-thread completion queue. The worker returns immediately — it never
   blocks on GPU. Meshing is fail-fast: block-model and fluid meshing exceptions are not caught,
   logged, or skipped per block. The worker boundary transports the failure to the render thread after
   releasing any job-owned partial native resources; the render-thread drain throws with section
   coordinates so the existing RT failure path handles it. There is no log-and-continue mode that can
   silently publish incomplete terrain.
3. **Executor loop:** block on job queue → drain pending build requests up to the configured
   build-count/scratch-byte limits (batching preserved — one submit per bounded batch, the property the
   old per-section-submit stall fix bought) → record → submit with signal `V` →
   `vkWaitSemaphores(V)` (blocking is fine, it owns the thread) → free scratches → run `onComplete`s →
   run destroy jobs whose graphics completion value passed.
4. **Publish (render thread, per frame):** drain completion queue under the existing token check.
   Stale → `enqueueDestroyUnpublished` (BLAS was built but never referenced by any TLAS — safe).
   Valid → existing `applyBuildChanges` swap logic (slot reuse, table patch, instance swap), old geom
   retired against `latestSuccessfullySubmittedGraphicsValue`. Dirty groups keep group-atomic publish:
   a group completes when all members' completions have arrived.
5. **Frame submit:** a device-side `waitSemaphore(timeline, pendingPublishWaitValue(), ...)` gates the
   graphics queue whenever terrain published since the last acknowledged submit, and that frame
   signals the next graphics timeline value `G` (required for both memory visibility and precise
   retirement — see Cross-queue correctness).

Dies with this: `Pending`, `startBuild`, `finalizePending`, the **one-build-in-flight** restriction
(builds pipeline through the executor now), `prepared`/`removed` cross-frame accumulators (publish
consumes completions directly), the render-thread `deferred` free list, and most of
`drainTessellation`'s upload work (it becomes pure bookkeeping).

### Backpressure and job ownership

`maxInflight` covers the whole native-resource lifetime, not merely the worker `Future`. A job keeps
its permit through snapshot → mesh → allocation → executor queue → GPU completion → publish or stale
destruction. The worker returning after `enqueueBuild` does not free the permit. Model the lifecycle
explicitly (`CPU_QUEUED`, `BUILD_QUEUED`, `GPU_IN_FLIGHT`, `READY`, `PUBLISHED`/`DISCARDED`) so cancel,
dimension change, and teardown each have exactly one release path.

Also cap native bytes, not only section count: queued mesh/backing bytes, scratch bytes, and builds per
batch. Section complexity and OMM subdivision make a count-only cap an insufficient VRAM bound. Before
allocation, coalesce obsolete tokens for the same section; once submitted, mark an obsolete build stale
and reclaim it only after its build value completes.

### Destroy path (all destroys on the executor)

- Retired geometry (`retire`) → `enqueueDestroyAfterGraphics(lastUseG, geom::destroy)`.
- Stale/cancelled results whose compute build completed but which were never published →
  `enqueueDestroyUnpublished` immediately.
- Old section-table generations on grow/replacement → retire against their last graphics-use value.
- Executor polls both timeline counters each loop iteration; a wake-up after each graphics submission
  guarantees destroy progress even when no builds are queued.
- `clear()/shutdown`: stop dispatch → `cancelJobs()` (joins workers — already implemented) →
  `executor.flushAndJoin()` → `waitIdle` → destroy remainder synchronously. Executor holds no world
  state, so teardown ordering stays simple.

### Section-table mutation

Completion-driven destruction does not make an in-place write safe while an older frame reads the same
mapped table buffer. `RtSectionTable` therefore uses copy-on-write generations (or an equivalently
guarded ring): publish patches a generation not referenced by any submitted frame, the next graphics
submission captures that generation and its signal value `G`, and replacement/grow retires the old
generation after its last `G` completes. Never patch a table generation still owned by the graphics
queue. This replaces both table `KEEP_FRAMES` and implicit fixed-frames-in-flight assumptions.

## Non-goals / kept on render thread

- **Snapshots** — must read client-thread world state; render thread == client thread.
- **Publish swap + section table writes** — per-frame TLAS reads these; generation reuse is guarded by
  graphics-timeline completion.
- **`resolveMaterials` hasS/hasN patch** — stays at publish (tiny mapped write). `ensure()` is
  worker-safe if we later want it in the job, but the win is negligible and the flush() interaction
  needs its own audit.
- **Per-frame TLAS build** — part of frame recording, graphics queue.
- **Entity pipeline** — untouched (per-frame transient, inherently frame-coupled).

## Milestones (each compiles + play-verifiable)

- **M0 — file split, zero behavior change.** Extract `RtTerrainMesher`, `RtSectionBuilder`,
  `RtSectionTable`. Pure code motion; verify by compile + short play test.
- **M1 — executor + compute queue, minimal scope.** Introduce `RtGpuExecutor` (borrow vanilla's
  reserved distinct compute queue with a fail-fast alias check, build + graphics timelines,
  required frame wait/signal integration,
  concurrent-sharing buffer creation, mapped-write flush helper) and move **only** the existing batched
  build submission (formerly `startBuild`'s `submitAsync`) onto it. BLAS/OMM creation stays render-thread. This
  isolates the cross-queue machinery — the riskiest part — into a small, verifiable diff.
  GPU-verify with validation layers + sync validation.
- **M2 — worker-side BLAS/OMM + per-batch publish.** Move create/scratch into the worker job;
  completions flow through the executor; make inflight permits span GPU completion/publish and add
  native-byte/batch limits; delete `Pending`/`startBuild`/`finalizePending`; adapt dirty groups.
  GPU-verify (flythrough + heavy block edits + dimension change).
- **M3 — completion-driven destroys and table generations.** Route retire/stale/cancel/table-grow
  frees through the build/graphics completion paths, convert table mutation to completion-guarded
  generations, delete `KEEP_FRAMES` and the render-thread deferred list.
- **M4 — cleanup.** Budget rebalance (dispatch stage is now snapshot-only; drain is bookkeeping),
  stats renames, `RtWorkerPool` javadoc contract update ("no Vulkan" → "no graphics-queue access"),
  drop `clear()`'s waitIdle where `flushAndJoin` suffices (keep for teardown).

## Risks & review

1. **Cross-queue sync bug ⇒ GPU hang or corrupted TLAS input.** Highest-consequence risk.
   Mitigations: M1 isolates it; double gate (host poll AND device wait); CONCURRENT sharing removes
   the ownership-transfer class of bugs; sync validation layer during bring-up.
2. **Vanilla starts using `computeQueue()` in a future MC version** ⇒ silent queue race. Mitigation:
   prefer the bring-up-patched private queue (option 1); if borrowing vanilla's, assert/verify per
   version bump. This is the argument for doing option 1 up front.
3. **Multi-threaded `vkCreate*KHR/EXT`** — spec-legal, NVIDIA-routine, but if a driver misbehaves the
   fix is funneling creates through the executor thread (still async; one flag). Cheap to hold in
   reserve; not worth pre-pessimizing.
4. **VRAM in flight** — jobs now hold buffers+scratch+backing pre-publish. The permit remains held
   through GPU completion and publish/discard, with explicit native-byte, scratch-byte, and batch-size
   caps; scratch is freed per completed build batch. Frame-time feedback can further pause dispatch
   when async AS work contends with tracing.
5. **Executor blocking per batch** adds latency vs. hypothetical pipelined builds — but current code
   is one-build-in-flight too, so this is ≥ today's throughput. If it ever matters: allow 2 batches in
   flight (ring the command buffers) — noted, not planned.
6. **Frame-submit mixin fragility** — injecting into vanilla's frame Submission is a new mixin
   surface. The device-side wait and graphics-completion signal are NOT optional, but the mixin is:
   render-thread-owned wait-only/frame/signal-only submits achieve the same ordering with no concurrent
   queue access. Decide at M1 which we ship; prefer attaching both operations to the real frame
   Submission when the injection point is stable because it avoids two extra queue submits.
7. **Teardown races** — worker join is done (cancelJobs); executor `flushAndJoin` must run before
   `waitIdle`+context destroy. Covered in `clear()` ordering above.
8. **Async queue contention** — a distinct Vulkan queue is not guaranteed to be a distinct hardware
   engine; OMM/BLAS builds still consume compute, cache, and bandwidth. Batch/byte limits plus optional
   GPU-frame-time feedback keep background terrain work from extending foreground RT frames.
9. **Worker failure cleanup** — meshing/build failures are deliberately fatal to the RT terrain pass,
   not swallowed. The job boundary must destroy every buffer/OMM/BLAS object allocated before the
   failure, release its inflight permit exactly once, and forward the original cause to the render
   thread; validation includes injecting failures at each allocation/build-preparation stage.

## Deferred / follow-ups

- Entity BLAS builds through the executor (frees the render thread of `entity.blasRecord` — but
  entities are frame-coupled; needs its own latency analysis).
- LOD proxy BLAS builds (LOD plan M2) — natural second client of `RtGpuExecutor`.
- `resolveMaterials` into the worker job (needs flush() audit).
- Two-batches-in-flight executor pipelining.
