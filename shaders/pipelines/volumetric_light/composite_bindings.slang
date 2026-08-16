module composite_bindings;

[[vk::binding(0, 0)]] [format("rgba16f")] public RWTexture2D<float4> outputImage;
[[vk::binding(1, 0)]] [format("rgba16f")] public RWTexture2D<float4> sourceImage;
[[vk::binding(2, 0)]] public Sampler2D scatterImage;
