# Developer Guide

## Windows

1. Install the Vulkan SDK from <https://vulkan.lunarg.com/sdk/home>.
   The installer sets `VULKAN_SDK` automatically. Caustica requires Slang
   2026.4 or newer for its raw-device-pointer layout declarations; on Windows,
   Vulkan SDK 1.4.350.0 is a known-good baseline. `VULKAN_SDK` must point to
   that compatible SDK so `slangc` and `spirv-val` come from the same install.
2. Clone the DLSS SDK from <https://github.com/NVIDIA/DLSS> and check out commit
   `a291cc7d2cc642a51566f3dfd5376f635cd1b284`. Set `DLSS_SDK` to that clean Git
   checkout. Gradle validates the commit, clean status, headers, static library,
   and runtime libraries before building or packaging the NGX shim.

   To set it permanently for your Windows user account, run PowerShell with:

   ```powershell
   [Environment]::SetEnvironmentVariable("DLSS_SDK", "C:\path\to\dlss-sdk", "User")
   ```

   Restart your terminal after setting it. To set it only for the current
   PowerShell session, use:

   ```powershell
   $env:DLSS_SDK = "C:\path\to\dlss-sdk"
   ```

3. To include NVIDIA SHaRC, use a clean SHaRC 1.8 checkout at commit
   `e19ccacd511f42a3df6f850052d508c13c9e9737`, pass its path as
   `-PsharcSdk=C:\path\to\SHARC-1.8.0.0`, and pass
   `-PacceptSharcLicense=true` after reviewing its license. Without that
   explicit SDK and acceptance, Gradle builds the ordinary RT variants only.

4. Configure and build the native shim directly when working on its C++ code:

```powershell
cmake -S native/ngx_shim -B build/cmake/ngx_shim/release -DCMAKE_BUILD_TYPE=Release
cmake --build build/cmake/ngx_shim/release --config Release
```

5. Run the client:

```powershell
$env:JAVA_TOOL_OPTIONS = "-Xmx8G -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+UseZGC"
.\gradlew.bat runClient --args="--renderDebugLabels --graphicsBackend VULKAN"
```

## Linux

Use the same clean pinned DLSS checkout and Vulkan SDK described above, then
set `DLSS_SDK` and `VULKAN_SDK` before configuring CMake:

```bash
export DLSS_SDK=/path/to/dlss-sdk
export VULKAN_SDK=/path/to/vulkan-sdk
```

Gradle validates the exact DLSS commit, clean status, headers, selected static
library, and selected runtime payloads. `VULKAN_SDK` must provide Slang 2026.4
or newer plus `spirv-val`. To include SHaRC, also pass the pinned checkout as
`-PsharcSdk=/path/to/SHARC-1.8.0.0 -PacceptSharcLicense=true`.

Then configure and build the native shim:

```bash
cmake -S native/ngx_shim -B build/cmake/ngx_shim/release -DCMAKE_BUILD_TYPE=Release
cmake --build build/cmake/ngx_shim/release
```

On NixOS, enter the development shell from `flake.nix` instead of setting up
the toolchain by hand:

```bash
nix develop
cmake -S native/ngx_shim -B build/cmake/ngx_shim/release -DCMAKE_BUILD_TYPE=Release
cmake --build build/cmake/ngx_shim/release
```

## Native Bundling

Gradle bundles NGX natives for the current host platform by default:

```bash
./gradlew build
```

Release builds that already have both platform shims available can request a
cross-platform native bundle:

```bash
./gradlew build -PngxPlatforms=windows-x64,linux-x64
```

Run the Vulkan RT/DLSS-RR client with:

```bash
JAVA_TOOL_OPTIONS='-Xmx8G -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+UseZGC' nvidia-offload ./gradlew runClient --args='--renderDebugLabels --graphicsBackend VULKAN'
```
