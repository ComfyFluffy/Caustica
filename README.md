# Sodium Upscaler (FSR / DLSS) — dev project

Temporal upscaling for Minecraft 26.2's Vulkan backend. Plan and milestones:
`../sodium-26.2-beta/UPSCALER_PLAN.md` (this project implements "Part 2").

Pinned versions: Minecraft `26.2-rc-1`, Fabric Loader `0.19.3`, Loom
`1.16-SNAPSHOT`, Fabric API `0.152.0+26.2`, Java `25`.

## Status

**M1 complete (2026-06-12):** FFM bindings to `amd_fidelityfx_vk.dll` (FSR 3.1.4,
FidelityFX SDK 1.1.4), device-extension mixin, and an in-game smoke test that
creates a real FSR upscale context against the live game `VkDevice`, runs all
quality/jitter/memory queries, and destroys it. All on Java 25 FFM — zero JNI,
zero native compilation.

Next: M2 — render-resolution scaling spike (world at 50% + bilinear blit, UI at
native). See the plan.

## Layout

- `src/main/java/dev/upscaler/ffx/` — hand-written FFM bindings for the ffx-api
  (struct offsets annotated against the SDK 1.1.4 headers in
  `../fsr-sdk/ffx-api/include/ffx_api/`).
- `src/main/java/dev/upscaler/client/FsrSmokeTest.java` — staged in-game probe,
  runs once at first client tick.
- `src/main/java/dev/upscaler/tools/StandaloneFsrProbe.java` — same probe without
  Minecraft (own VkInstance/VkDevice); used to isolate native crashes. Run via
  `java -cp <classes;lwjgl;lwjgl-natives;lwjgl-vulkan> dev.upscaler.tools.StandaloneFsrProbe`.
- `src/main/java/dev/upscaler/mixin/VulkanBackendMixin.java` — adds device
  extensions at `vkCreateDevice` (mini "Phase 0").
- `run/natives/amd_fidelityfx_vk.dll` — signed FSR runtime
  (from `../fsr-sdk/PrebuiltSignedDLL/`; source: FidelityFX-SDK v1.1.4 release).

## Running / validating

```powershell
./runClient.ps1     # or: gradlew runClient
```

Requirements for the smoke test to run:
1. `run/options.txt` must contain `preferredGraphicsBackend:"vulkan"`.
2. `run/natives/amd_fidelityfx_vk.dll` present (or `-Dupscaler.ffx.path=<path>`).

Expected log lines (`run/logs/latest.log`):

```
(upscaler) Enabling device extension VK_KHR_get_memory_requirements2 ...
(Minecraft) Using graphics backend Vulkan, ...
(upscaler) FSR smoke test [stage 1]: 2 upscale provider version(s): [... 3.1.4 ... 2.3.3]
(upscaler) FSR smoke test [stage 2]: context created OK in ... ms
(upscaler) FSR smoke test: quality=Quality -> render ...x... (ratio 1.50x)
(upscaler) FSR smoke test: all stages PASSED
```

## Gotchas (hard-won)

- **Vulkan crash sentinel:** if the game crashes while on the Vulkan backend,
  the *next* boot silently reverts to OpenGL, resets
  `preferredGraphicsBackend` to `"default"` in options.txt, and consumes the
  sentinel. After any crash: run once (it'll be OpenGL), then re-set the option
  to `"vulkan"` and run again.
- **FFX KHR-alias NULL-call bug:** FSR's VK backend resolves
  `vkGetImageMemoryRequirements2KHR` etc. via `vkGetDeviceProcAddr` using the
  KHR-suffixed names. Per spec these resolve to NULL unless
  `VK_KHR_get_memory_requirements2` / `VK_KHR_dedicated_allocation` are enabled
  at device creation — the functionality being core since Vulkan 1.1 doesn't
  help. FFX calls the NULL pointer unguarded → access violation at
  `amd_fidelityfx_vk.dll+0x1e5b0` (it's mid-`VkMemoryRequirements2` setup; the
  magic constant 0x3B9D0453 in the crash-site disassembly is
  VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2). `VulkanBackendMixin` fixes this.
  Keep this in mind for every future FFX/NGX feature: **check the SDK's
  required device extensions and enable them explicitly.**
- Nsight Graphics' injection DLLs (`ngfx-capture-*.dll`) load into the process
  on this machine; they were investigated and are *not* the source of the above
  crash (reproduced in a clean standalone process). Nsight remains usable for
  GPU debugging of later milestones.
- The mod needs `--enable-native-access=ALL-UNNAMED` (set in `build.gradle`
  loom run config) to silence Java 25 FFM restricted-method warnings.
