package dev.upscaler.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import dev.upscaler.UpscalerConfig;
import dev.upscaler.UpscalerMod;
import dev.upscaler.rt.RtHdr;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * HDR Phase 0 capability logging + Phase 2 step B scRGB swapchain selection.
 *
 * <p>The {@link VulkanGpuSurface} constructor holds both the live {@code VkSurfaceKHR} and the physical
 * device, so we enumerate the surface's formats/color spaces there (once) for diagnostics.
 *
 * <p>When {@code upscaler.rt.hdr.scrgbSwapchain} is on and the surface advertises scRGB
 * (R16G16B16A16_SFLOAT / EXTENDED_SRGB_LINEAR), we steer Minecraft's swapchain to it: vanilla
 * {@code pickSwapchainSurfaceFormat} only accepts SDR (color space 0, format 37/44) and {@code configure}
 * hardcodes {@code imageColorSpace(0)}. We override the picked format and the color-space arg. Falls back to
 * the vanilla SDR path when the flag is off or scRGB is unavailable. NOTE: this only creates the swapchain
 * in scRGB — the presented content is still Minecraft's SDR main target blitted in, so the image looks wrong
 * until the HDR compositor (step C) writes correct scRGB; default off.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {
	private static final int VK_FORMAT_R16G16B16A16_SFLOAT = 97;
	private static final int VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT = 1000104002;

	@Shadow
	@Final
	private VulkanDevice device;

	@Shadow
	@Final
	private long surface;

	@Shadow
	@Final
	private int swapchainImageFormat;

	@Unique
	private int upscaler$colorSpace = 0;

	@Inject(method = "<init>(Lcom/mojang/blaze3d/vulkan/VulkanDevice;J)V", at = @At("TAIL"))
	private void upscaler$logHdrCapabilities(VulkanDevice device, long windowHandle, CallbackInfo ci) {
		try {
			RtHdr.logSurfaceCapabilities(this.device.vkDevice().getPhysicalDevice(), this.surface, this.swapchainImageFormat);
		} catch (Throwable t) {
			// Diagnostics only — never let HDR logging break surface creation.
		}
	}

	/**
	 * Pick an scRGB surface format when requested + available, before vanilla's SDR-only selection runs.
	 * Sets {@link #upscaler$colorSpace} so {@code configure} can pass the matching color space.
	 */
	@Inject(method = "pickSwapchainSurfaceFormat", at = @At("HEAD"), cancellable = true)
	private void upscaler$pickScrgbFormat(VkSurfaceFormatKHR.Buffer formats, CallbackInfoReturnable<VkSurfaceFormatKHR> cir) {
		if (!UpscalerConfig.Rt.Hdr.SCRGB_SWAPCHAIN.value()) {
			return;
		}
		for (int i = 0; i < formats.capacity(); i++) {
			VkSurfaceFormatKHR f = formats.get(i);
			if (f.format() == VK_FORMAT_R16G16B16A16_SFLOAT && f.colorSpace() == VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT) {
				this.upscaler$colorSpace = VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT;
				UpscalerMod.LOGGER.info("HDR: selecting scRGB swapchain (format=R16G16B16A16_SFLOAT, colorSpace=EXTENDED_SRGB_LINEAR)");
				cir.setReturnValue(f);
				return;
			}
		}
		UpscalerMod.LOGGER.warn("HDR: scRGB swapchain requested but EXTENDED_SRGB_LINEAR not advertised by the surface; using SDR (enable Windows HDR / check VK_EXT_swapchain_colorspace)");
	}

	/** Replace the hardcoded {@code imageColorSpace(0)} with the scRGB color space when one was selected. */
	@ModifyArg(method = "configure",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageColorSpace(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"),
			index = 0)
	private int upscaler$overrideColorSpace(int original) {
		return this.upscaler$colorSpace != 0 ? this.upscaler$colorSpace : original;
	}
}
