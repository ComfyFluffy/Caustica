// Complete descriptor and push-constant interface for the world ray-tracing pipeline.
// Every world stage imports this module instead of redeclaring its own subset.

import world_common;

#ifdef CAUSTICA_SHARC_VARIANT
import sharc_types;
[[vk::push_constant]] public SharcPushConstants pc;
#else
[[vk::push_constant]] public WorldPushConstants pc;
#endif

[[vk::binding(0, 0)]] public RaytracingAccelerationStructure topLevelAS;
[[vk::binding(1, 0)]] [format("rgba16f")] public RWTexture2D<float4> outImage;

[[vk::binding(3, 0)]] [format("rgba16f")] public RWTexture2D<float4> gNormal;
[[vk::binding(4, 0)]] [format("rgba16f")] public RWTexture2D<float4> gAlbedo;
[[vk::binding(5, 0)]] [format("r32f")] public RWTexture2D<float> gDepth;
[[vk::binding(6, 0)]] [format("rg16f")] public RWTexture2D<float2> gMotion;
[[vk::binding(7, 0)]] [format("rgba16f")] public RWTexture2D<float4> gSpecAlbedo;
[[vk::binding(8, 0)]] [format("rg16f")] public RWTexture2D<float2> gSpecMotion;
[[vk::binding(9, 0)]] [format("r16f")] public RWTexture2D<float> gResponsivity;
[[vk::binding(10, 0)]] [format("r8ui")] public RWTexture2D<uint> gParticleMask;
[[vk::binding(11, 0)]] [format("r16f")] public RWTexture2D<float> gSkyClassification;

[[vk::binding(2, 0)]] public Sampler2D blockAlbedoAtlas;
[[vk::binding(12, 0)]] public Sampler2D celestialsAtlas;
[[vk::binding(13, 0)]] public Sampler2D skyViewLut;
[[vk::binding(14, 0)]] public Sampler2D transmittanceLut;
[[vk::binding(15, 0)]] public Sampler2D endSkyTexture;

[[vk::binding(0, 1)]] public Sampler2D entityAlbedoTex[];
[[vk::binding(1, 1)]] public Sampler2D materialSurface0Tex[];
[[vk::binding(2, 1)]] public Sampler2D materialNormalAoTex[];
[[vk::binding(3, 1)]] public Sampler2D materialSurface1Tex[];
