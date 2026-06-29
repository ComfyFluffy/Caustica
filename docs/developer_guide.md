# Developer Guide

## Required Environment

Set these before configuring CMake:

```bash
export DLSS_SDK=/path/to/dlss-sdk
export VULKAN_SDK=/path/to/vulkan-sdk
```

`DLSS_SDK` must contain the NGX headers and static library. `VULKAN_SDK` must
contain Vulkan headers.

## Build and Run

```bash
cmake -S native/ngx_shim -B build/cmake/ngx_shim/release -DCMAKE_BUILD_TYPE=Release
cmake --build build/cmake/ngx_shim/release
```

## Run Client

On NixOS with NVIDIA offload, run the Vulkan RT/DLSS-RR client with:

```bash
JAVA_TOOL_OPTIONS='-Xmx8G -Dupscaler.renderScale=0.5 -Dupscaler.rt.composite=true -Dupscaler.rt.output=rt -Dupscaler.rt.dlssRr=true -Dupscaler.rt.exposure.key=0.12 -Dupscaler.rt.exposure.maxEv=2.0 -Dupscaler.rt.exposure.minEv=0.0 -Dupscaler.rt.cancelVanillaWorld=true -Dupscaler.rt.workerThreads=4 -Dupscaler.rt.sunNoonSouthDeg=30' nvidia-offload gradle runClient --args='--renderDebugLabels --graphicsBackend VULKAN'
```
