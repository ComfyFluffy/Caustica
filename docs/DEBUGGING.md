# Agent debugging

Launch a development client with `./gradlew :packages:minecraft-client:runClient -PdebugAgent=true -PquickPlayWorld=YOUR_WORLD` (`gradlew.bat` on Windows). For an installed build use `-Dcaustica.debug.enabled=true`. Prefer a copy of a world for experiments that change it.

Add `-PvulkanValidation=true` to enable Minecraft's Vulkan validation option for a development run. Check startup logs for `VK_LAYER_KHRONOS_validation`; the host reports when the requested layer is unavailable. Validation changes execution overhead, so keep it out of performance comparisons.

Use the repository uv environment for packages and execution: `uv sync --locked`, then `uv run python ...`. Add dependencies with `uv add`; NumPy, OpenEXR and Pillow are declared in the workspace.

The opt-in HTTP service binds to loopback. Connection details are in `run/caustica-debug/session.json`; do not share this file or its token. Run scripts from the repository root, or pass `--session` with an absolute path.

```powershell
uv run python tools/debug/caustica_debug.py status
uv run python tools/debug/caustica_debug.py schema
uv run python tools/debug/caustica_debug.py settings.get
uv run python tools/debug/caustica_debug.py jfr.start
uv run python tools/debug/caustica_debug.py wait '{"frames":300}'
uv run python tools/debug/caustica_debug.py jfr.stop
uv run python tools/debug/caustica_debug.py screenshot
uv run python tools/debug/caustica_debug.py view.set '{"name":"normals"}'
uv run python tools/debug/caustica_debug.py image.capture '{"name":"normal-roughness"}'
uv run python tools/debug/caustica_debug.py view.set '{"name":"off"}'
uv run python tools/debug/measure.py --warmup 120 --frames 300 --output tmp/run.json
```

If shell JSON quoting is awkward, use `--json-file args.json`. Operations complete through jobs; the Python client waits and prints the completed result. `--timeout 180` changes the client deadline; `timeoutMs` in the request changes the game job deadline. Expiry does not undo commands already applied.

## Script control

`memory.capture` saves the active renderer allocator's detailed VMA JSON inventory and returns its path. It includes memory types, heaps, blocks and individual allocations, but excludes allocations owned by Minecraft, NGX or profiling tools. Run it outside measured intervals.

`terrain.status` collects loaded-column, resident-geometry, publication and pending-request counts on the terrain coordinator. Use stable scene-population counts as well as worker readiness when warming up static benchmarks; zero outstanding builds alone does not mean chunk loading has finished. Collection scans tracked sections, so keep it outside measured intervals.

Add `tools/debug` to Python's import path and use `Client.call(op, **arguments)`. The CLI and imports use the same protocol; no test runner is required.

| Operation | Arguments / result |
| --- | --- |
| `schema` | Available operations, named views and raw image names |
| `status` | World/player/camera, renderer state, settings and latest recorded counters. For integrated worlds, `world.directory` identifies the save directory independently of its display name; use it to guard disposable-world experiments. `window.width/height` are framebuffer pixels; `window.screenWidth/screenHeight` are window dimensions, with `window.fullscreen` reporting its mode. `screen` is the current screen class name, or an empty string during gameplay. The player includes `currentFlyingSpeed`, `flying`, and `sprinting`. |
| `settings.get` | Setting IDs, encoded values and whether JVM overrides apply. Optional `feature` selects a registered feature, such as `caustica:minecraft` for fog and sky; omission selects the renderer. Unset optional strings use an empty string; returned values can be passed to `settings.set`. |
| `settings.set` | `values` mapping setting IDs to primitive values; optional `feature` as above. JVM overrides cannot be changed. |
| `runtime.set` | Required `enabled` boolean applies the normal Minecraft RT toggle. Returns `requested`, `active`, `frameActive`, and `overridden`; the next client tick starts the lifecycle transition. JVM overrides cannot be changed. `status.runtime` reports the same fields. |
| `window.resize` | Positive integer `width` and `height` request Minecraft's windowed size through `Window.setWindowed`. This leaves fullscreen. Completion acknowledges the request; poll `status.window` for the resulting window/framebuffer dimensions before capturing. The window manager can constrain the requested size. |
| `window.maximize`, `window.restore` | Request the normal GLFW window-manager action on the client thread; fullscreen must be disabled. Poll `status.window.maximized` and dimensions, then capture early and settled frames. This works without desktop input; render-target screenshots do not capture remote-desktop transport artifacts. |
| `window.fullscreen` | Required `enabled` boolean requests Minecraft's normal fullscreen toggle using the configured monitor video mode. Completion acknowledges the request; poll `status.window.fullscreen` and framebuffer dimensions after subsequent frames. Fullscreen does not guarantee the same resolution as the windowed target. Restore the original mode and dimensions after comparisons. |
| `resources.reload` | Reloads the currently selected resource packs through Minecraft's public reload entry point. Completes when the reload future completes, or reports its failure. RT recovery happens on subsequent client ticks; wait for active RT separately before capturing. Does not change selected packs. |
| `world.leave` | Leaves through Minecraft's normal disconnect/save/menu flow. Requires a world. Optional `onlyIfTerrainBusy:true` returns `left:false` without leaving when the immediate terrain-build sample is zero, allowing a caller to retry without a separate status/action gap. Returns `terrainBuildsBeforeDisconnect`; workers can advance after the sample. Poll status for no world/player and drained terrain work. `status.terrainOutstandingBuilds` exposes the same counter across epochs, including retired worlds. The process-wide RT session can remain active at menus. |
| `view.set` | `name` from schema; `off` restores normal presentation |
| `screen.close` | Calls the current screen's normal close handler once; returns whether a screen was present. May return to a parent screen or initiate an asynchronous transition. Use this for screens with completion actions, such as End credits; poll status for the resulting screen/world. |
| `screen.open` | Required `screen`: `video` opens vanilla Video Settings; any other value opens the Caustica options page with that id: `tone-mapping`, `exposure`, `upscaling`, `overlays`, or an extension feature id such as `caustica:bloom` or `caustica:minecraft`. A page without rows has no id to open. The page returns to Video Settings, which returns to Options. Opens through the same screen switch as the in-game buttons; returns the opened screen's class name. The integrated server pauses while a screen is open. |
| `input.set` | `forward` and `sprint` booleans (default false); closes the current screen and holds the corresponding game keys for continuous flight. Optional `flyingSpeed` sets the spectator ability speed: a finite number from 0 to 0.2, matching the mouse-wheel range. Omission leaves speed unchanged. Returns `previousFlyingSpeed` and `currentFlyingSpeed` alongside key states. Call with no arguments to release both keys. |
| `wait` | `frames` counts frames with a successfully recorded RT world composite; runtime activity alone does not count. This is a command-recording milestone, not GPU completion or display cadence. `ticks` counts unpaused world ticks. World required, counts must advance. Use ticks while RT is disabled. |
| `command` | `command` without slash; default `target:server` executes on the integrated server with player permissions and returns a result; `target:client` sends through the normal network connection and confirms dispatch only |
| `screenshot` | PNG path after completion. Optional `target` is `main` (default, final game target, including menus) or `ui` (the allocated hand/GUI overlay before composition). UI capture requires an allocated overlay; it can contain an older frame if RT is inactive. `frameId` is the latest RT telemetry serial, not a menu frame counter. Calls capture independently; separate calls do not promise the same frame. |
| `image.capture` | Raw EXR `name`, or `names` for a same-frame bundle; paths and metadata |
| `jfr.start` | Start a raw recording with JVM profile events and Caustica events; optional `events` array selects Caustica names such as `Frame`, `GpuStage`, `TerrainState` |
| `jfr.dump` | Save current recording without stopping |
| `jfr.stop` | Stop/save recording and return its path |
| `nsight.status` | No arguments. Report whether Windows Nsight GPU Trace is already injected, numeric `status` (0–4), readable `state` (`inactive`, `active`, `tracing`, `draining`, `errored`) and SDK `result` (0 on success) |
| `nsight.start` | No arguments. Trigger an active Vulkan GPU Trace through the injected SDK; return observed state, renderer frame ID and trigger wall time in milliseconds |
| `job` | `jobId`; returns pending/completed/failed state |
| `client.stop` | Stop the debug client gracefully after returning acknowledgement |
| `passes.capture` | Records the next GameRenderer frame's host render-pass labels, coarse phases and color attachment identities/dimensions. Phase is `world` initially, then the most recently visited capture boundary (`after-world`, `after-hand`, `before-ui`). `main` identifies the current main texture by object identity. Includes host render passes only, not external renderer commands, transfers or clears. No image readback or GPU wait is added. |

Both capture operations accept optional `phase`: `frame-end` (default), `after-world`, `after-hand`, or `before-ui`. `after-world` runs after a successful world composite and its renderer-owned UI passes, before Minecraft's hand, screen-effect and GUI draws. `after-hand` runs after the redirected hand, before screen effects and GUI. `before-ui` runs immediately before the final SDR UI blend, after the overlay has been populated. The selected boundary must be reached or the job times out; `before-ui` requires SDR presentation. Raw readback submits preceding host commands at each phase. Separate requests still observe separate frames; use a raw bundle to compare targets at one phase in one frame.

For repeatable flight routes, use spectator mode and a level camera. Ability speed defaults to `0.05`; `0.2` is the maximum mouse-wheel speed. With sprint held, steady horizontal speeds are approximately 21.78 and 87.11 blocks per second respectively at 20 ticks per second. The ability value is an acceleration parameter, not blocks per second. Read `status.player.currentFlyingSpeed` to record the actual configuration; a previous mouse-wheel adjustment can change it.

Save the `previousFlyingSpeed` returned by the initial `input.set` call. In the benchmark's `finally` block, call `input.set` with `forward:false`, `sprint:false`, and `flyingSpeed` set to that saved value while still in spectator mode. Releasing keys alone leaves the ability speed unchanged. The override uses the same client ability setter as spectator mouse-wheel input.

For RT lifecycle checks, save `status.runtime.requested`, call `runtime.set` with `enabled:false`, wait for ticks and `status.runtime.active:false`, and inspect vanilla terrain at the current camera. Restore the saved value afterward and wait for `active:true` when re-enabling RT. Perform toggles and image capture outside performance recordings. Omit a JVM `caustica.rt` override when the run needs this toggle.

For continuous camera turns, `input.set` also accepts `turnDegreesPerSecond` between -360 and 360. It applies ordinary player look input each render frame, without issuing teleport commands or changing position. Omission stops the turn. Combine it with a low spectator `flyingSpeed` for a slow circular route; call `input.set` without held keys or turn speed in `finally`, restoring the saved flying speed. Paused frames do not accumulate a turn to apply on resume.

After resource reload, successful composites do not imply that distant terrain preparation has finished. Keep the camera fixed and inspect both early and later captures before diagnosing missing geometry. A fixed frame or tick count is an observation interval, not a terrain-completion signal.

Direct clients POST JSON `{ "op": "status" }` to the discovery `baseUrl` plus `/api`, with `Authorization: Bearer TOKEN`. Replies are `{ok:true,jobId}` or `{ok:true,result}`; poll `job` for asynchronous completion. Errors have `ok:false,error` or a failed job. Discovery includes `protocolVersion` and process ID.

## Record, then analyze

`ComputeBatch` records asynchronous compute host timing: command-pool acquisition, command recording,
submission setup and host-lock acquisition, timeline waiting, and completion callbacks. Its queue delay
belongs before the batch and must not be added to the batch's elapsed time. A queue delay of `-1` means
the job was enqueued before recording was enabled. Batch lifetimes overlap because up to two batches
can be outstanding; summing event durations does not measure worker utilization. Deferred timeline
waiting excludes GPU progress made during another batch's recording and includes host scheduling;
it is not a GPU execution-time measurement. The event is disabled by default.

The game emits raw observations without hitch thresholds, percentile filtering or CSV logging. Warm up, start a recording, wait a useful number of frames, stop, then capture images outside the measured interval. Repeat comparable runs when performance differences are small.

```powershell
uv run python tools/debug/analyze_recording.py run/caustica-debug/recording-ID.jfr --raw tmp/events.json
```

This external Python analysis computes descriptive statistics and exposure changes. It requires the JDK `jfr` executable on PATH (or `--jfr PATH`). The exported JSON retains all Caustica events for ad hoc analysis. Use JDK `jfr print` separately for JVM events. Record settings, camera, workload and build revision alongside measurements; wall time includes scheduling and waits.

Duration summaries use milliseconds, including `threadCpuMs` and `cpuMs`. Unavailable CPU-clock readings remain in `samples` but are excluded from `finiteSamples` and timing statistics. Monotonic timestamps are retained in the raw export rather than summarized as durations.

If a measurement fails after recording starts, its JSON retains the available conditions even when the client cannot stop JFR. Missing `end` or `recording` fields indicate an incomplete experiment, not a completed timing sample.

Before contacting the client, `measure.py` reads the revision and working-tree status of the checkout containing the helper, regardless of the caller's directory. Git failures abort the measurement. These fields describe the observed source checkout, not the build loaded by an already-running client; launch the intended build before comparing measurements.

For JVM hotspot attribution, export with `jfr print --json --stack-depth 64 --events jdk.ExecutionSample,jdk.ObjectAllocationSample RECORDING.jfr`.
The command's default stack display depth is five frames and can hide application callers even when the recording contains deeper stacks.

For a GPU trace synchronized to gameplay, launch the client through Nsight Graphics with `--start-with-ngfx-sdk` and a bounded frame/duration limit. Poll `nsight.status` until `status` is 1, establish the intended movement, then call `nsight.start` and retain its trigger metadata with the JFR recording. These operations use the installed SDK 0.9.2 ABI through an already injected `WarpVizTarget.dll`; they do not load or inject a profiler. They require Windows and the opt-in debug service. Launch-time delays do not provide a verified capture boundary because profiler initialization precedes data-collection readiness.

For shadow-traversal diagnosis without Nsight, add `-PshadowDiagnostics=true` to `runClient` (direct JVM equivalent: `-Dcaustica.rt.shadowDiagnostics=true`) and include `ShadowTraversal` with `GpuStage` in `jfr.start.events`. This compiles an instrumented shader; restart without the flag for normal performance measurements. Completed events identify the source GPU frame, not the later callback frame. The `pass` field distinguishes `fill stable planes` from `material visibility`. Each visibility dispatch publishes its own event, so multiple events can share a source frame and pass; aggregate their maxima and sum their anomaly counts. Maxima are recorded only above 32 transparent-ray restarts, 256 candidate `RayQuery.Proceed` iterations per query, or 512 candidates per visibility call; zero means no exceedance, not no traversal. Unchanged ray origins and consecutive hits of the same accepted geometry record/primitive are counted separately, with one anomaly's pixel and geometry IDs. Candidate counts do not count hardware BVH node visits. Counter storage and asynchronous readback are owned through graphics completion; ordinary shaders compile out the instrumentation.

If `jfr.stop` fails while saving its file, the service retains the stopped recording. Correct the storage problem and retry `jfr.stop` to save and close it before starting another recording. It collects no further events while stopped; shutting down the client discards an unsaved recording.

`measure.py` selects frame, CPU/GPU stage, counter and exposure events by default. Terrain state snapshots traverse section state and can substantially perturb a large-world CPU profile; enable them for streaming diagnosis with `--events` or a full `jfr.start`. All enabled events are retained. Query pools warm up when GPU recording first starts; account for initial samples in comparisons.

- `Frame`: CPU envelope, including RT tick preparation when present; not display cadence or generated-frame FPS.
- `Frame.threadCpuNanos` and `allocatedBytes`: render-thread CPU and allocated-byte deltas over the envelope. CPU-clock resolution is platform-dependent; these exclude other threads. `FramePreparation` also reports task CPU/allocation deltas and its monotonic start time.
- `GpuWait`: explicit graphics timeline wait intervals. These do not include every host presentation or driver wait.
- `CpuStage`: each elapsed CPU scope, with stage and frame ID. Nested scopes overlap; do not sum everything.
- Command recording scopes distinguish `frame.acquireCommands` (pool acquisition, command-buffer begin and descriptor binding), `frame.endCommands` (`vkEndCommandBuffer`), and `frame.submitCommands` (handoff to the host encoder). `frame.beginTraceCommands` and `frame.beginPostCommands` contain acquisition and any preceding buffer finalization. They measure CPU elapsed time, including driver calls and scheduling, not GPU execution. Include `HostSubmission` to observe host setup and retirement segments around its GPU-completion await; the monotonic gap between a submission's `beforeWait` end and `afterWait` start brackets that await. JVM `jdk.NativeMethodSample` events can identify native calls sampled during these intervals, but sample presence alone does not establish their duration.
- `GpuStage`: command-stage timestamps on the graphics queue; query pools are reused after existing completion. No telemetry wait is added. Named build/local-bake/fill intervals sit inside `world resources and trace`; do not add them to that parent interval. These intervals are not utilization counters or a total display frame time. Recording GPU timestamps still perturbs execution; compare runs with the same instrumentation.
- `FrameCounter`: raw per-frame count or gauge. A latest snapshot is only updated while frame collection is enabled by a recording.
- `GpuMemoryBlock`: renderer VMA device-memory block allocation/free notifications with size, memory type, native handle and calling stack. Enable explicitly. Allocation notifications occur after successful `vkAllocateMemory`; free notifications precede `vkFreeMemory`. These are instantaneous markers, not allocation durations, buffer suballocations or GPU-residency measurements. Minecraft and NGX allocators are outside this event's scope. See the [VMA callback contract](https://gpuopen-librariesandsdks.github.io/VulkanMemoryAllocator/html/group__group__init.html).
- `ChunkSkylight`: elapsed initialization of one chunk's skylight-source heightmap, with chunk coordinates. Enable explicitly. Use the event thread to distinguish render-thread packet installation from integrated-server/world-generation workers. This measures `fillFrom`, not packet decoding, the full light engine or GPU lighting.
- `GeometryVisibility`: raw extraction/publication/assembly timestamps. Assembly is not GPU completion or a guarantee that the object contributes visible pixels.
- `EntityMeshFrame`: source and assembled mesh revisions/frames, including prior values. Superseded revisions need not appear.
- `EntityMeshPublication`: ready and publication timestamps for a prepared entity mesh. Correlate with frame start times to separate engine publication delay from entity extraction and GPU preparation.
- `EntityMeshUpload`: queue/start/finish timestamps, worker CPU time, and allocated bytes for entity packing and mesh submission. GPU preparation and retained publication occur afterward; correlate by entity key and timestamps.
- `FramePreparation`: elapsed time for each preparation chunk, with phase and workload size. `scene-revision` measures retained CPU scene assembly; `trace-revision` measures the complete geometry/light preparation, including GPU completion. Both run on the serial scene worker. `tlas-reserve`, `tlas-pack`, and `tlas-ready` separate allocation, input packing, and waiting for the GPU build on that worker. `plan` and `pack` report instance counts; `light-index` and `lights` report light counts. Nested and parallel worker scopes overlap; do not add them to render-thread time.
- `TraceRanges`: active geometry-record and emitter-byte counts versus the highest addressed range ends. Their difference measures holes in the stable per-scene layout; actual buffer allocations may be larger because retired revision storage retains capacity for reuse.
- `TerrainState`: actual section/resident, neighbor-wait and worker/queue state each client tick. `observedFrameId` is the last render serial, not a claim that the tick has appeared on screen.
- `TerrainJob`: dispatch, CPU completion, GPU preparation, publication and supersession observations keyed by epoch/revision/section coordinates. Derive queue and update latency externally; a superseded revision need not publish.
- `OpacityMicromapBuild`: recorded micromap triangle counts, subdivision level, opaque/transparent/unknown microtriangle counts, and storage bytes. Enable explicitly through `jfr.start`; recording is not GPU completion or simultaneous residency. See [opacity micromaps](OPACITY_MICROMAPS.md).
- `TerrainPublication`: worker publication duration, CPU time and allocated bytes, with group/section counts and publication/failure flags. Include it explicitly in recordings that select events. It covers edit preparation and atomic commit on the publication worker. Render capture retains the published root through a separate short mutex. Abandoned attempts are retained with `published: false`.
- `TerrainDispatchPlan`: worker candidate ranking duration and changed, retained, and selected candidate counts. The render thread consumes completed selections and validates request identity before palette extraction. `terrain.paletteCapture` measures that Minecraft extraction inside `terrain.snapshotDispatch`.
- `NeeFrame`: retained light counts, candidate count and history validity; `rendererFrameId` uses the renderer's separate frame namespace.
- `Exposure`: source `frameId`, later `observedFrameId`, latched pre-exposure and completed controller values. `controllerResidualExposure` is derived from controller state, not sampled from the display image. Compare auto samples across consecutive source frames within one reset sequence. The last few GPU readbacks can arrive after recording stops; retain padding frames when measuring a bounded interval.

JFR timestamps describe event emission; explicit frame IDs and `System.nanoTime` fields describe original work. JFR JSON represents annotated elapsed times as ISO durations. Keep CPU, GPU, controller and display measurements distinct.

## Images and debugging recipes

PNG captures show the presented render target. Debug views are convenient visualizations; raw EXRs retain native-resolution float components without display mapping. Read each EXR's returned/embedded encoding and pre-exposure metadata before comparing values. Capture synchronizes GPU readback and perturbs frame timing.

The debug service submits Minecraft's deferred encoder once before a raw-image bundle. The readback's device wait then includes the frame described by its metadata; waiting without that submit would cover only older GPU submissions.

For raw inspection, run `uv run python tools/debug/inspect_buffer.py CAPTURE.exr --view normal --preview tmp/normals.png`. It reports finite ranges and normal statistics; preview scaling never changes the raw data. The normal view maps signed XYZ to RGB, and is not a color-managed beauty-image preview.

Under the NRD temporal route, normal/roughness and NRD signal buffers contain the final plane 0 inputs after per-plane preparation. They do not contain every plane or original material texture values. NRD signal radiance uses the documented fixed scale in capture metadata, not the ordinary trace pre-exposure scale. Generic motion/depth/albedo remain trace guides.

With NRD followed by DLSS-SR, `trace-color` is reused for the composed denoised input to SR;
it no longer contains the original noisy trace output when captured at frame end.
`reconstructed-color` contains the SR result, including any NGX debug indicator.

`display-color` captures the SDR display-mapped RGBA8 image before copying to Minecraft's target and adding UI. Its EXR stores normalized byte values, not scene radiance. Compare it with `screenshot` targets `main` and `ui` to investigate presentation corruption.

`main-color` and `ui-color` read Minecraft's final target and hand/GUI overlay directly into EXR, preserving all RGBA components. Include them with `display-color` in one `image.capture` bundle to compare the same submitted frame without going through Minecraft's PNG readback path.

- **NRD guides:** capture normal/roughness, NRD view Z and diffuse/specular signals together. Inspect finite values, edges and packing before blaming denoising. Named views help inspect spatial errors quickly.
- **Periodic darkness:** record stationary-camera exposure; find jumps in Python, then compare trace/reconstructed radiance. Stable controller exposure alone does not prove stable lighting. Use short capture bursts only for visual diagnosis.
- **Streaming:** issue repeated teleports with tick waits, inspect screenshots and geometry queue/resident counters, then stop and let work drain. Counts can suggest leaks or stalls but do not prove exact chunk coverage.
- **Material filtering:** inspect a known sharp normal/emission texture boundary near and far. Compare raw guides/radiance with display output, then inspect shader sampling. Linear filtering and linear color encoding are different questions.
- **Mesh delay:** correlate raw geometry and entity revision events in Python. Separate submit-to-ready from assembly delay, and validate apparent changes with a visible object.

Restore changed settings and debug view after experiments. Report observed data separately from suspected causes; keep recordings/images out of Git.

`scene-effect-color` captures the jittered trace-resolution scene-effect result before RR or SR (after NRD when using separate denoising). It uses the frame pre-exposure scale. Fog diagnostic modes override this signal before reconstruction: use this buffer for raw scattering/transmittance analysis. Reconstructed diagnostic output is temporally filtered and may contain history residuals or a nonzero numerical floor even when the corresponding raw signal is zero.
