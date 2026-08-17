module inject_bindings;

[[vk::binding(0, 0)]] [format("rgba16f")] public RWTexture3D<float4> scatterImage;
[[vk::binding(1, 0)]] public Sampler3D historyImage;
[[vk::binding(2, 0)]] public Sampler2D transmittanceLut;

// The world TLAS lives in its own set because its handle changes every frame: it is written into a small
// ring of single-binding sets (the same one the world-overlay occlusion pass uses) while set 0's image
// bindings stay fixed for the lifetime of the render targets.
[[vk::binding(0, 1)]] public RaytracingAccelerationStructure worldTlas;
