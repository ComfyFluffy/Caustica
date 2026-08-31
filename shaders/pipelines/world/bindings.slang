// Complete descriptor and push-constant interface for the world ray-tracing pipeline.
// Every world stage imports this module instead of redeclaring its own subset.

import world_common;

[[vk::push_constant]] public WorldPushConstants pc;

[[vk::binding(0, 0)]] public RaytracingAccelerationStructure topLevelAS;
[[vk::binding(1, 0)]] [format("rgba16f")] public RWTexture2D<float4> outImage;

[[vk::binding(3, 0)]] [format("rgba16f")] public RWTexture2D<float4> gNormal;
[[vk::binding(4, 0)]] [format("rgba16f")] public RWTexture2D<float4> gAlbedo;
[[vk::binding(5, 0)]] [format("r32f")] public RWTexture2D<float> gDepth;
[[vk::binding(6, 0)]] [format("rg16f")] public RWTexture2D<float2> gMotion;
[[vk::binding(7, 0)]] [format("rgba16f")] public RWTexture2D<float4> gSpecAlbedo;
[[vk::binding(8, 0)]] [format("rg16f")] public RWTexture2D<float2> gSpecMotion;

// ReSTIR temporal reservoirs. Both are bound permanently and swap roles each frame via
// worldPush.restir.y, rather than the descriptors themselves being rewritten: descriptor sets are
// ring-buffered across frames in flight, so a per-frame rewrite would mutate a set an earlier frame is
// still reading. A push constant costs nothing and cannot race.
// Double-width — two texels per pixel, see restir.slang. rgba32f because the emitter position needs
// full float precision.
[[vk::binding(12, 0)]] [format("rgba32f")] public RWTexture2D<float4> reservoirA;
[[vk::binding(13, 0)]] [format("rgba32f")] public RWTexture2D<float4> reservoirB;

// Precomputed cloud shape field. R = 4-octave base, G = 3-octave erosion detail, tiling in all three
// axes. Replaces hundreds of ALU ops per density sample with one filtered fetch — see RtCloudNoise.
[[vk::binding(14, 0)]] public Sampler3D cloudNoiseTex;

[[vk::binding(2, 0)]] public Sampler2D blockAlbedoAtlas;
[[vk::binding(9, 0)]] public Sampler2D celestialsAtlas;
[[vk::binding(10, 0)]] public Sampler2D skyViewLut;
[[vk::binding(11, 0)]] public Sampler2D transmittanceLut;

[[vk::binding(0, 1)]] public Sampler2D entityAlbedoTex[];
[[vk::binding(1, 1)]] public Sampler2D materialSurface0Tex[];
[[vk::binding(2, 1)]] public Sampler2D materialNormalAoTex[];
[[vk::binding(3, 1)]] public Sampler2D materialSurface1Tex[];
