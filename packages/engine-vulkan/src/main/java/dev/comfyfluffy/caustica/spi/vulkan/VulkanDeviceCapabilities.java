package dev.comfyfluffy.caustica.spi.vulkan;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuRasterCapabilities;
import org.lwjgl.vulkan.VK10;

/** Immutable result of host Vulkan-device negotiation and entry-point validation. */
public record VulkanDeviceCapabilities(
        boolean rayTracing,
        boolean shaderExecutionReordering,
        boolean opacityMicromap,
        boolean lowLatency,
        boolean presentIds,
        boolean hdrMetadata,
        boolean shaderBufferInt64Atomics,
        boolean storageBuffer16BitAccess,
        GpuRasterCapabilities raster) {
    public VulkanDeviceCapabilities {
        if (!rayTracing && (shaderExecutionReordering || opacityMicromap)) {
            throw new IllegalArgumentException("Ray-tracing subfeatures require ray tracing");
        }
        if (presentIds && !lowLatency) {
            throw new IllegalArgumentException("Present IDs are negotiated only with low-latency support");
        }
    }

    public static VulkanDeviceCapabilities unavailable() {
        return new VulkanDeviceCapabilities(false, false, false, false, false, false, false, false,
                new GpuRasterCapabilities(false, 1.0f, VK10.VK_SAMPLE_COUNT_1_BIT));
    }
}
