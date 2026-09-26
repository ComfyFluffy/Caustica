package dev.comfyfluffy.caustica.minecraft.client.mixin;

import com.mojang.blaze3d.vulkan.VulkanQueue;
import dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition;
import dev.comfyfluffy.caustica.minecraft.client.vulkan.MinecraftVulkanBackend;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Serializes Blaze3D's queue idle waits with the renderer's queue operations and device-wide waits. */
@Mixin(VulkanQueue.class)
public abstract class VulkanQueueWaitMixin {
    @Redirect(method = "waitIdle()V", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkQueueWaitIdle(Lorg/lwjgl/vulkan/VkQueue;)I"))
    private int caustica$synchronizeWaitIdle(VkQueue queue) {
        MinecraftVulkanBackend backend = CausticaClientComposition.current().vulkanBackend().currentOrNull();
        return backend == null ? VK12.vkQueueWaitIdle(queue)
                : backend.synchronizedQueueOperation(() -> VK12.vkQueueWaitIdle(queue));
    }
}
