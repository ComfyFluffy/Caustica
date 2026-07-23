# Safe-mode bisection protocol

Context: device-specific GPU corruption (broken blue strips on translucent materials, RIS-related)
and device-loss/freeze reports, worse or exclusive to NVIDIA 30-series, clean on 50-series, and not
flagged by standard validation, sync validation, or GPU-assisted validation on the machine where it's
been tested. This doc is the runbook for using the `rt.safe.*` levers landed on this branch to narrow
down the cause on an affected device, plus a record of what was checked and ruled out along the way so
this isn't re-derived from scratch next time.

## The levers

All under `CausticaConfig.Rt.Safe` / `-Dcaustica.rt.safe.*`. Each trades performance for removing one
specific piece of the unsafe-operation surface, so a device that stops misbehaving with a given lever
on tells you *which* mechanism is implicated.

| Flag | What it removes | Frozen at startup? |
|---|---|---|
| `singleQueue` | The async-compute queue + dedicated background thread for terrain/entity BLAS builds. Builds run inline on the render thread via the graphics queue instead (`RtGpuExecutor.pumpSingleQueue`). Removes cross-queue timeline sync *and* cross-thread queue submission as variables simultaneously. | Yes (restart to change) |
| `destroyMarginFrames=N` | Nothing removed, but adds N frames of margin before any timeline-gated destroy runs. A bug that disappears with margin > 0 means some last-use value is recorded too early (a race/off-by-one), not that the resource's contents are wrong. | No (live) |
| `leak` | Skips the destroy callback entirely once a job's (margin-adjusted) threshold is met — intentionally leaks GPU/host memory. If corruption survives `destroyMarginFrames` alone but disappears with `leak`, the destroy is running at a time that *looks* correct but isn't: the resource is read again after the timeline value it was gated on. | No (live) |
| `noPushRing` | WorldPush's 6-deep buffer ring (reuse relied on ring depth alone, no explicit completion check) → fresh buffer per frame, destroyed via the timeline-gated queue. | No (live) |
| `noTlasRing` | The 4-deep TLAS ring (already `waitForGraphicsValue`-gated before reuse, but still rebuilds the same AS in place) → fresh AS/instance-buffer/scratch per frame. | No (live) |
| `noEntityRigidReuse` | The cross-frame rigid-reuse fast path (re-reference a possibly-many-frames-old entity AS via just an instance transform, no rebuild) → forces the full rebuild path every frame. **Does not** touch the deeper per-entity ring-slot allocator (see Known gaps). | No (live) |
| `noNullBda` | Every "0 = not published" light-buffer BDA sentinel (`lightBufAddr`, `lightAliasAddr`, `lightLocalAliasAddr`, `lightGridCellAddr`, `lightGridSpanAddr`) is backed by a small zeroed dummy buffer instead of literal address 0. Diagnostic only — see below. | No (live) |

## What's already been verified clean (don't re-litigate these)

- **BLAS scratch is fresh-per-build everywhere** (`RtAccel.createScratchBuffer`, 8 call sites). No
  shared-scratch-across-builds exists today, so "barriers between BLAS builds sharing scratch" has no
  current target — it's forward-looking guidance for `docs/BLAS_SLAB_PLAN.md`, confirmed *not*
  implemented.
- **ReSTIR temporal reservoirs**: not on this branch (lives in the `restir-2` / `regir-unbounded-candidates`
  stashes). Not a factor here.
- **Material reload is synchronous end to end**: `MinecraftReloadMixin` → `RtComposite.onResourceReloadStart`
  → next-tick `ensureWorld`/`bindWorldTextures` → `RtMaterialRegistry.rebuild` → `RtMaterialPageTexture`'s
  constructor, which uploads via a blocking `ctx.submitSync`. No thread/executor handoff anywhere in the
  `material` package.
- **Buffer/image queue-family sharing audit**: every resource written on one queue family and read on
  another uses `VK_SHARING_MODE_CONCURRENT` (`RtContext.createAsyncBuffer`/`createAsyncAlignedBuffer`);
  every same-queue-family EXCLUSIVE resource stays on one queue throughout. No EXCLUSIVE cross-queue-family
  resource lacking an ownership-transfer barrier was found.
- **RR/FG disabled boundary**: both self-gate (or are only reachable through a gated caller) at every
  NGX/GPU-touching entry point. `RtDlssFg.probeAvailabilityOnce` and `RtComposite.fgInterpolate` lacked
  their *own* internal guard (relied entirely on caller discipline) — hardened with an internal
  `enabled()` check as defense-in-depth; no live bug, since every current caller already gated correctly.

## Known gaps (not covered by any lever above)

- **Entity per-entity AS ring** (`RtEntities`, inside `appendPackedEntity`): even with `noEntityRigidReuse`
  on, the full-rebuild path still writes into a per-entity ring slot's *retired* backing buffer, not a
  fresh allocation. Removing this needs a dedicated pass against the `EntitySlot` ring/eviction/motion-arena
  machinery — deliberately not attempted in the same pass as the other levers given the file's size
  (~100 KB, the largest in the codebase) and the risk of a rushed change there.
- **Terrain has no ring/pool to remove**: `RtLightGridManager` already allocates a fresh arena + upload
  buffer per light-grid regeneration (proper generation-based publish/retire, not round-robin reuse), so
  there was no "terrain arena" lever to build — verified, not skipped.

## The bisect protocol

Run on the affected 30-series device with the RIS blue-strip repro (enter world, wait ~30s) and/or
whatever triggers the field DEVICE_LOST reports.

1. **Baseline**: all levers off (current default). Confirm the bug reproduces on this device/build.
2. **`singleQueue` alone.** If this alone fixes it: the bug is in cross-queue/cross-thread submission or
   sync specifically (queue-family timing, the async-compute submission path) — highest prior given the
   async-terrain commit (`22505f5`) is where field DEVICE_LOST regressed.
3. **`destroyMarginFrames=4` alone** (no other lever). If this fixes it: some resource's last-use value is
   recorded before its true last GPU read — a race or off-by-one in a retire/publish path. Bisect *which*
   resource by re-enabling one ring lever at a time (step 4) with margin still on, watching for the bug to
   return only once a specific ring is back in non-safe mode.
4. **Ring levers one at a time**: `noPushRing`, then `noTlasRing`, then `noEntityRigidReuse` (each alone,
   levers from step 2/3 off). Whichever one fixes it in isolation names the resource whose reuse timing is
   wrong.
5. **`leak` on top of whichever ring lever from step 4 didn't fully fix it, plus `destroyMarginFrames=0`.**
   If corruption disappears under `leak` but survived plain margin, the destroy runs at a time that looks
   timeline-correct but isn't (the resource is genuinely read again later) — stronger and more specific
   than step 3 alone.
6. **`noNullBda` alone**, independent of the above. This one targets a *different, already-observed*
   symptom: with `risCandidates=0` (RIS logically dead — `risOn` false at every gate) the blue-strip bug is
   unchanged, but compile-time elimination of the RIS shader code fixes it. That rules out the light *data*
   as the cause and points at the mere *presence* of the RIS code in the compiled SPIR-V — most plausibly a
   driver speculating a `PhysicalStorageBuffer` load through one of the light-BDA fields past the
   source-level `lightCount`/`hasGrid` branch that's supposed to guard it, where those fields are literal
   address 0 whenever no light data is published. If `noNullBda` alone fixes the blue-strip bug, this is
   confirmed. Log fault addresses (`VK_EXT_device_fault` / Nsight Aftermath) from any device-loss captured
   during this run — near-zero implicates this exact path; deep in a freed-arena's address range points
   back at step 5 instead.
7. **Combine**: whichever single lever fixes it, re-verify it's necessary by turning it off again on top of
   an otherwise-clean run (regression should return). Record driver version + exact repro steps alongside
   the result.

Each step's outcome is the actual deliverable — this doc exists so the *next* debugging session doesn't
have to re-derive why these specific levers exist or what a positive/negative result on each one means.
