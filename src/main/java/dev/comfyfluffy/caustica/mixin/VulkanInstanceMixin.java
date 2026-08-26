package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vulkan.VulkanInstance;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.rt.VulkanDiagnostics;
import java.util.Set;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds Caustica's supported Vulkan instance extensions before instance creation. Swapchain colorspace
 * exposes HDR color spaces to {@code vkGetPhysicalDeviceSurfaceFormatsKHR}; NGX requirements come from the
 * selected shim so its instance and device contracts stay in sync.
 *
 * <p>Gated on availability — requesting an unsupported instance extension would fail {@code vkCreateInstance}
 * and crash startup.
 */
@Mixin(VulkanInstance.class)
public abstract class VulkanInstanceMixin {
	private static final String SWAPCHAIN_COLORSPACE = "VK_EXT_swapchain_colorspace";

	@Shadow
	@Final
	private Set<String> enabledExtensions;

	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/util/Set;size()I"))
	private void caustica$addColorSpaceExtension(int debugVerbosity, boolean wantsDebugLabels, boolean validation,
			CallbackInfo ci, @Local(ordinal = 0) Set<String> availableExtensions) {
		if (availableExtensions.contains(SWAPCHAIN_COLORSPACE)) {
			if (this.enabledExtensions.add(SWAPCHAIN_COLORSPACE)) {
				CausticaMod.LOGGER.info("Enabling instance extension {} for HDR swapchain color spaces", SWAPCHAIN_COLORSPACE);
			}
		} else {
			CausticaMod.LOGGER.warn("Instance extension {} unavailable; HDR color spaces will not be queryable on this platform", SWAPCHAIN_COLORSPACE);
		}
		NgxRuntime.INSTANCE.negotiateRequiredExtensions(false, this.enabledExtensions,
				availableExtensions::contains);
	}

	@ModifyArg(
			method = "<init>",
			at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateInstance(Lorg/lwjgl/vulkan/VkInstanceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"),
			index = 0)
	private VkInstanceCreateInfo caustica$logInstanceLayers(VkInstanceCreateInfo createInfo) {
		VulkanDiagnostics.logInstanceLayers(createInfo);
		return createInfo;
	}
}
