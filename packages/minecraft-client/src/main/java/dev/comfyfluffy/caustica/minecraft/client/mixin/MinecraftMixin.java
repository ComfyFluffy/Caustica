package dev.comfyfluffy.caustica.minecraft.client.mixin;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftHostTelemetry;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftTextureLifetime;
import dev.comfyfluffy.caustica.minecraft.client.TonemapperQuickToggle;
import dev.comfyfluffy.caustica.minecraft.client.vulkan.MinecraftVulkanBackend;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanLowLatency;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.File;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Runs Reflex sleep at the start of {@link Minecraft#runTick}, before simulation.
 * Retired texture leases and frame observations are finalized at its end, after presentation.
 *
 * <p>SIMULATION_START/END bracket the tick/extract work that happens before rendering (from here through
 * just before {@code renderFrame} is called); RENDERSUBMIT/PRESENT markers are set from
 * {@code GameRendererMixin}/{@code VulkanGpuSurfaceMixin} respectively. No-ops entirely unless Reflex is
 * enabled and sleep mode has already been applied to the current swapchain.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "close", at = @At("HEAD"))
	private void caustica$destroyUiOverlayBeforeRendererShutdown(CallbackInfo ci) {
		dev.comfyfluffy.caustica.minecraft.client.UltraScreenshot.INSTANCE.shutdown();
		dev.comfyfluffy.caustica.minecraft.client.MinecraftDebugService.stop();
		CausticaClientComposition.current().uiOverlay().destroy();
		CausticaClientComposition.current().runtime().shutdown();
		MinecraftTextureLifetime.drain();
	}

	@Inject(method = "handleGlobalKeyPress", at = @At("HEAD"), cancellable = true)
	private void caustica$handleKeys(InputConstants.Key key, boolean controlDown,
			CallbackInfoReturnable<Boolean> cir) {
		Minecraft minecraft = (Minecraft) (Object) this;
		if (minecraft.options.keyDebugModifier.isDown()) return;
		if (dev.comfyfluffy.caustica.minecraft.client.UltraScreenshot.KEY.matches(key)) {
			dev.comfyfluffy.caustica.minecraft.client.UltraScreenshot.INSTANCE.toggle(minecraft);
			cir.setReturnValue(true);
		} else if (TonemapperQuickToggle.KEY.matches(key)) {
			TonemapperQuickToggle.flip();
			cir.setReturnValue(true);
		}
	}

	/** Rejects panorama capture before vanilla mutates the camera, window, or render target. */
	@WrapMethod(method = "grabPanoramixScreenshot(Ljava/io/File;)Lnet/minecraft/network/chat/Component;")
	private Component caustica$guardPanorama(File directory, Operation<Component> original) {
		if (dev.comfyfluffy.caustica.minecraft.client.UltraCaptureSession.active()) {
			return Component.translatable("caustica.status.ultraScreenshot.busy");
		}
		return original.call(directory);
	}

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void caustica$suppressCaptureAttack(CallbackInfoReturnable<Boolean> cir) {
		if (dev.comfyfluffy.caustica.minecraft.client.UltraCaptureSession.active()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void caustica$suppressCaptureAttackHold(boolean leftClick, CallbackInfo ci) {
		if (dev.comfyfluffy.caustica.minecraft.client.UltraCaptureSession.active()) {
			ci.cancel();
		}
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void caustica$suppressCaptureUse(CallbackInfo ci) {
		if (dev.comfyfluffy.caustica.minecraft.client.UltraCaptureSession.active()) {
			ci.cancel();
		}
	}

	// Session and resize work uses client-tick cadence, after the per-frame Reflex sleep.
	@Inject(method = "tick", at = @At("HEAD"))
	private void caustica$tickRuntime(CallbackInfo ci) {
		try (var hostWork = MinecraftHostTelemetry.work("runtime.tick")) {
			CausticaClientComposition.current().tickRuntime((Minecraft) (Object) this);
		}
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void caustica$debugTick(CallbackInfo ci) {
		try (var hostWork = MinecraftHostTelemetry.work("debug.tick")) {
			dev.comfyfluffy.caustica.minecraft.client.MinecraftDebugService.tick();
		}
	}

	@Inject(method = "runTick", at = @At("HEAD"))
	private void caustica$reflexSleepAndSimStart(boolean advanceGameTime, CallbackInfo ci) {
		MinecraftHostTelemetry.beginLoop(CausticaClientComposition.current().runtime().telemetry().frameSerial());
		try (var hostWork = MinecraftHostTelemetry.work("reflex.sleepAndSimStart")) {
			VulkanLowLatency lowLatency = caustica$lowLatency();
			VulkanDevice device = caustica$reflexDevice();
			long swapchain = lowLatency == null ? 0L : lowLatency.appliedSwapchain();
			if (lowLatency == null || device == null || swapchain == 0L) {
				return;
			}
			lowLatency.sleep(device.vkDevice(), swapchain);
			lowLatency.marker(device.vkDevice(), swapchain, VulkanLowLatency.SIMULATION_START,
					lowLatency.currentSimulationId());
		}
	}

	@Inject(method = "runTick", at = @At("TAIL"))
	private void caustica$releaseRetiredTextures(boolean advanceGameTime, CallbackInfo ci) {
		dev.comfyfluffy.caustica.minecraft.client.MinecraftRtRuntime runtime;
		try (var hostWork = MinecraftHostTelemetry.work("frame.tailLookup")) {
			runtime = CausticaClientComposition.current().runtime();
		}
		try (var hostWork = MinecraftHostTelemetry.work("texture.retire");
			 var ignored = runtime.profileStage("host.textureRetire")) {
			MinecraftTextureLifetime.drain();
		} finally {
			try (var hostWork = MinecraftHostTelemetry.work("telemetry.finalize")) {
				runtime.endFrame();
			} finally {
				MinecraftHostTelemetry.endLoop(runtime.telemetry().frameSerial(), runtime.frameActive());
			}
		}
	}

	@Inject(method = "runTick",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"))
	private void caustica$reflexSimEnd(boolean advanceGameTime, CallbackInfo ci) {
		try (var hostWork = MinecraftHostTelemetry.work("debug.cameraInput")) {
			dev.comfyfluffy.caustica.minecraft.client.MinecraftDebugService.advanceCameraInput();
		}
		try (var hostWork = MinecraftHostTelemetry.work("reflex.simEnd")) {
			VulkanLowLatency lowLatency = caustica$lowLatency();
			VulkanDevice device = caustica$reflexDevice();
			long swapchain = lowLatency == null ? 0L : lowLatency.appliedSwapchain();
			if (lowLatency == null || device == null || swapchain == 0L) {
				return;
			}
			lowLatency.marker(device.vkDevice(), swapchain, VulkanLowLatency.SIMULATION_END,
					lowLatency.currentSimulationId());
		}
	}

	private static VulkanLowLatency caustica$lowLatency() {
		MinecraftVulkanBackend backend = CausticaClientComposition.current().vulkanBackend().currentOrNull();
		return backend != null && backend.lowLatency().active() ? backend.lowLatency() : null;
	}

	private static VulkanDevice caustica$reflexDevice() {
		if (caustica$lowLatency() == null) {
			return null;
		}
		return ((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device
				? device : null;
	}
}
