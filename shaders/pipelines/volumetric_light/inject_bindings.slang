module inject_bindings;

[[vk::binding(0, 0)]] [format("rgba16f")] public RWTexture3D<float4> scatterImage;
[[vk::binding(1, 0)]] public Sampler3D historyImage;
[[vk::binding(2, 0)]] public Sampler2D depthImage;
[[vk::binding(3, 0)]] public Sampler2D transmittanceLut;
