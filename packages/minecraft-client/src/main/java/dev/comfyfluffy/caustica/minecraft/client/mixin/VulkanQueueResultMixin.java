package dev.comfyfluffy.caustica.minecraft.client.mixin;

import com.mojang.blaze3d.vulkan.VulkanQueue;
import dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition;
import dev.comfyfluffy.caustica.minecraft.client.vulkan.MinecraftVulkanBackend;
import dev.comfyfluffy.caustica.engine.vulkan.GpuCrashHistory;
import dev.comfyfluffy.caustica.engine.vulkan.VulkanDiagnostics;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import java.util.Map;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Serializes host submits with the renderer's queue operations and routes native shared-submit failures
 * through the host's Vulkan failure reporting.
 */
@Mixin(VulkanQueue.Submission.class)
public abstract class VulkanQueueResultMixin {
    @Redirect(method = "close()V", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/KHRSynchronization2;vkQueueSubmit2KHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkSubmitInfo2$Buffer;J)I"))
    private int caustica$checkSubmit(VkQueue queue, VkSubmitInfo2.Buffer submits, long fence) {
        GpuCrashHistory.record(GpuCrashHistory.Event.HOST_SUBMIT_BEGIN, queue.address(), 0, submits.remaining(), fence);
        MinecraftVulkanBackend backend = CausticaClientComposition.current().vulkanBackend().currentOrNull();
        int result = backend == null ? KHRSynchronization2.vkQueueSubmit2KHR(queue, submits, fence)
                : backend.synchronizedQueueOperation(() -> KHRSynchronization2.vkQueueSubmit2KHR(queue, submits, fence));
        GpuCrashHistory.record(GpuCrashHistory.Event.HOST_SUBMIT, queue.address(), 0, submits.remaining(), result);
        if (result == VK10.VK_ERROR_DEVICE_LOST) {
            VulkanDiagnostics.reportDeviceLost(queue.getDevice(), "vkQueueSubmit2KHR(host)", Map.of("graphics", queue));
        }
        VulkanDeviceContext.check(result, "vkQueueSubmit2KHR(host)");
        return result;
    }
}
