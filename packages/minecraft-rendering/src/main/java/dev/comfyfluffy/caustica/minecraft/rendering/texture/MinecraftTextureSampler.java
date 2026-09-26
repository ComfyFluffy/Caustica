package dev.comfyfluffy.caustica.minecraft.rendering.texture;

import java.util.Objects;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import static org.lwjgl.vulkan.VK10.*;

/** Immutable, renderer-owned snapshot of Minecraft texture sampling state. */
public record MinecraftTextureSampler(Filter minFilter, Filter magFilter,
                                      AddressMode addressModeU, AddressMode addressModeV,
                                      MipmapMode mipmapMode, float maxLod, int maxAnisotropy) {
    public static final MinecraftTextureSampler PIXEL_ART = new MinecraftTextureSampler(
            Filter.NEAREST, Filter.NEAREST, AddressMode.REPEAT, AddressMode.REPEAT,
            MipmapMode.NEAREST, 0.25f, 1);

    public MinecraftTextureSampler {
        Objects.requireNonNull(minFilter, "minFilter");
        Objects.requireNonNull(magFilter, "magFilter");
        Objects.requireNonNull(addressModeU, "addressModeU");
        Objects.requireNonNull(addressModeV, "addressModeV");
        Objects.requireNonNull(mipmapMode, "mipmapMode");
        if (!Float.isFinite(maxLod) || maxLod < 0.0f) {
            throw new IllegalArgumentException("maxLod must be finite and non-negative");
        }
        if (maxAnisotropy < 1) {
            throw new IllegalArgumentException("maxAnisotropy must be positive");
        }
    }

    /**
     * This filtering across every level of a {@code mipLevels}-level view. A fractional LOD blends the two
     * nearest levels; an explicit LOD of zero still reads level zero alone.
     */
    public MinecraftTextureSampler withMipLevels(int mipLevels) {
        return new MinecraftTextureSampler(minFilter, magFilter, addressModeU, addressModeV,
                MipmapMode.LINEAR, mipLevels - 1, maxAnisotropy);
    }

    public enum Filter { NEAREST, LINEAR }
    public enum AddressMode { REPEAT, CLAMP_TO_EDGE }
    public enum MipmapMode { NEAREST, LINEAR }

    public VkSamplerCreateInfo write(VkSamplerCreateInfo info) {
        return info.magFilter(filter(magFilter)).minFilter(filter(minFilter))
                .mipmapMode(mipmapMode == MipmapMode.NEAREST
                        ? VK_SAMPLER_MIPMAP_MODE_NEAREST : VK_SAMPLER_MIPMAP_MODE_LINEAR)
                .addressModeU(address(addressModeU)).addressModeV(address(addressModeV))
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT).minLod(0.0f).maxLod(maxLod)
                .anisotropyEnable(maxAnisotropy > 1).maxAnisotropy(maxAnisotropy);
    }

    private static int filter(Filter filter) {
        return filter == Filter.NEAREST ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
    }

    private static int address(AddressMode address) {
        return address == AddressMode.REPEAT
                ? VK_SAMPLER_ADDRESS_MODE_REPEAT : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    }
}
