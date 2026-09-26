package dev.comfyfluffy.caustica.minecraft.rendering.texture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MinecraftTextureSamplerTest {
    @Test
    void mipLevelsReachTheWholeChainWithTheSourceFiltering() {
        var atlas = new MinecraftTextureSampler(MinecraftTextureSampler.Filter.NEAREST,
                MinecraftTextureSampler.Filter.NEAREST, MinecraftTextureSampler.AddressMode.CLAMP_TO_EDGE,
                MinecraftTextureSampler.AddressMode.CLAMP_TO_EDGE, MinecraftTextureSampler.MipmapMode.NEAREST,
                0.25f, 1);

        MinecraftTextureSampler mipmapped = atlas.withMipLevels(5);

        assertEquals(new MinecraftTextureSampler(MinecraftTextureSampler.Filter.NEAREST,
                MinecraftTextureSampler.Filter.NEAREST, MinecraftTextureSampler.AddressMode.CLAMP_TO_EDGE,
                MinecraftTextureSampler.AddressMode.CLAMP_TO_EDGE, MinecraftTextureSampler.MipmapMode.LINEAR,
                4.0f, 1), mipmapped);
    }

    @Test
    void aSingleLevelViewStaysAtLevelZero() {
        MinecraftTextureSampler single = MinecraftTextureSampler.PIXEL_ART.withMipLevels(1);

        assertEquals(0.0f, single.maxLod());
        assertEquals(MinecraftTextureSampler.PIXEL_ART.maxAnisotropy(), single.maxAnisotropy());
    }
}
