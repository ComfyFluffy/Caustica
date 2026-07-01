package dev.upscaler.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;

import dev.upscaler.rt.RtReflex;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reflex Phase 1b: the per-frame sleep call must run at the very start of the frame, before input
 * sampling/simulation. {@link Minecraft#runTick} is Minecraft's per-loop-iteration entry point (called once
 * per {@code while (running)} iteration in {@link Minecraft#run()}, right after
 * {@code RenderSystem.pollEvents()}), so its HEAD is the earliest hookable point in this codebase for that
 * purpose — the alternative (hooking inside {@code run()}'s loop body directly) isn't a clean Mixin target
 * since it's inline in a loop, not a call to a named method.
 *
 * <p>SIMULATION_START/END bracket the tick/extract work that happens before rendering (from here through
 * just before {@code renderFrame} is called); RENDERSUBMIT/PRESENT markers are set from
 * {@code GameRendererMixin}/{@code VulkanGpuSurfaceMixin} respectively. No-ops entirely unless Reflex is
 * enabled AND {@link RtReflex#applySleepMode} has already succeeded for the current swapchain.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void upscaler$reflexSleepAndSimStart(boolean advanceGameTime, CallbackInfo ci) {
		VulkanDevice device = upscaler$reflexDevice();
		long swapchain = RtReflex.INSTANCE.appliedSwapchain();
		if (device == null || swapchain == 0L) {
			return;
		}
		RtReflex.INSTANCE.sleep(device.vkDevice(), swapchain);
		RtReflex.INSTANCE.marker(device.vkDevice(), swapchain, RtReflex.MARKER_SIMULATION_START,
				RtReflex.INSTANCE.currentSimFrameId());
	}

	@Inject(method = "runTick",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"))
	private void upscaler$reflexSimEnd(boolean advanceGameTime, CallbackInfo ci) {
		VulkanDevice device = upscaler$reflexDevice();
		long swapchain = RtReflex.INSTANCE.appliedSwapchain();
		if (device == null || swapchain == 0L) {
			return;
		}
		RtReflex.INSTANCE.marker(device.vkDevice(), swapchain, RtReflex.MARKER_SIMULATION_END,
				RtReflex.INSTANCE.currentSimFrameId());
	}

	private static VulkanDevice upscaler$reflexDevice() {
		if (!RtReflex.enabled()) {
			return null;
		}
		return ((GpuDeviceAccessor) RenderSystem.getDevice()).upscaler$getBackend() instanceof VulkanDevice device
				? device : null;
	}
}
