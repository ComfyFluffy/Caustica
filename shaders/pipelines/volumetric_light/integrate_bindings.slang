module integrate_bindings;

[[vk::binding(0, 0)]] [format("rgba16f")] public RWTexture3D<float4> integratedImage;
[[vk::binding(1, 0)]] [format("rgba16f")] public RWTexture3D<float4> scatterImage;
