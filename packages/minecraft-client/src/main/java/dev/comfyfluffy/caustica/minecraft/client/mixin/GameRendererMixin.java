package dev.comfyfluffy.caustica.minecraft.client.mixin;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftHostTelemetry;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftDebugCapture;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftDebugService;
import dev.comfyfluffy.caustica.minecraft.client.UltraScreenshot;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftDebugService.CapturePhase;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftUiOverlay;
import dev.comfyfluffy.caustica.minecraft.client.vulkan.MinecraftVulkanBackend;
import dev.comfyfluffy.caustica.renderer.presentation.BorrowedImage;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanLowLatency;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the world frame, composites its reconstructed image before the hand,
 * and routes hand, screen effects and GUI through the shared presentation overlay.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	@Unique private boolean caustica$worldComposited;

	// Reset the UI overlay's per-frame clear latch at the very start of the frame (before the world, hand,
	// or GUI render into it).
	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
	private void caustica$beginOverlayFrame(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		try (var hostWork = MinecraftHostTelemetry.work("frame.begin")) {
			caustica$worldComposited = false;
			UltraScreenshot.INSTANCE.beginFrame(Minecraft.getInstance());
			MinecraftDebugService.beginFrame();
			CausticaClientComposition.current().runtime().beginRenderFrame();
			if (!CausticaClientComposition.current().runtime().frameActive()) {
				return;
			}
			CausticaClientComposition.current().uiOverlay().beginFrame();
			// Clear the stale HDR-present flag every frame: composite() only runs while a level renders, so on
			// menu frames it would otherwise stay true from the last world frame and present a black HDR image.
			CausticaClientComposition.current().runtime().beginFrame();
			// Reflex RENDERSUBMIT_START: render-graph recording begins here; RENDERSUBMIT_END is set at
			// VulkanGpuSurface.present() HEAD (VulkanGpuSurfaceMixin), just before the real present.
			MinecraftVulkanBackend backend = CausticaClientComposition.current().vulkanBackend().currentOrNull();
			VulkanLowLatency lowLatency = backend == null ? null : backend.lowLatency();
			if (lowLatency != null && lowLatency.active()) {
				long swapchain = lowLatency.appliedSwapchain();
				if (swapchain != 0L
						&& ((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
					lowLatency.marker(device.vkDevice(), swapchain, VulkanLowLatency.RENDER_SUBMIT_START,
							lowLatency.currentSimulationId());
				}
			}
		}
	}

	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("TAIL"))
	private void caustica$endRtFrameStats(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		try (var hostWork = MinecraftHostTelemetry.work("debug.frameEnd")) {
			MinecraftDebugService.frameRendered(
					caustica$worldComposited);
			MinecraftDebugCapture.poll(Minecraft.getInstance(),
					caustica$worldComposited);
			UltraScreenshot.INSTANCE.frameRendered(Minecraft.getInstance(),
					caustica$worldComposited);
		}
	}

	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
	private void caustica$beginWorldComposite(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		try (var hostWork = MinecraftHostTelemetry.work("world.begin")) {
			CausticaClientComposition.current().worldComposite().begin(this.mainRenderTarget);
		}
	}

	// Redirect the held-item/hand render into the combined UI overlay. SDR and HDR then feed DLSS-FG the same
	// shape: hudless excludes the screen-fixed hand, while pUI carries hand + screen effects + GUI overlays.
	// try/finally guarantees the output overrides are cleared even if the hand render throws.
	@WrapOperation(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;FLorg/joml/Matrix4fc;)V"))
	private void caustica$redirectHandToOverlay(GameRenderer self, CameraRenderState cameraState, float deltaPartialTick,
			Matrix4fc modelViewMatrix, Operation<Void> original) {
		MinecraftUiOverlay overlay;
		boolean redirect;
		try (var hostWork = MinecraftHostTelemetry.work("ui.redirectBegin")) {
			overlay = CausticaClientComposition.current().uiOverlay();
			redirect = overlay.enabled();
			if (redirect) overlay.beginOutputRedirect(this.mainRenderTarget);
		}
		try {
			original.call(self, cameraState, deltaPartialTick, modelViewMatrix);
		} finally {
			try (var hostWork = MinecraftHostTelemetry.work("ui.redirectEnd")) {
				if (redirect) overlay.endOutputRedirect();
			}
		}
		try (var hostWork = MinecraftHostTelemetry.work("debug.afterHand")) {
			if (redirect) MinecraftDebugService.captureBoundary(CapturePhase.AFTER_HAND);
		}
	}

	// Redirect the screen-effect flush (fire, underwater, view-blocking-block overlays submitted by
	// ScreenEffectRenderer.submit) into the same combined UI overlay as the hand. This is the renderAllFeatures
	// call in the "screenEffects" section of renderLevel — distinct from the one inside renderItemInHand.
	// The spyglass scope and worn-pumpkin blur are drawn by the GUI/HUD instead, so they already reach the
	// overlay via the GuiRenderer redirect.
	@WrapOperation(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures(Lnet/minecraft/client/renderer/SubmitNodeStorage;)V"))
	private void caustica$redirectScreenEffectsToOverlay(FeatureRenderDispatcher self, SubmitNodeStorage storage,
			Operation<Void> original) {
		MinecraftUiOverlay overlay;
		boolean redirect;
		try (var hostWork = MinecraftHostTelemetry.work("ui.redirectBegin")) {
			overlay = CausticaClientComposition.current().uiOverlay();
			redirect = overlay.enabled();
			if (redirect) overlay.beginOutputRedirect(this.mainRenderTarget);
		}
		try {
			original.call(self, storage);
		} finally {
			try (var hostWork = MinecraftHostTelemetry.work("ui.redirectEnd")) {
				if (redirect) overlay.endOutputRedirect();
			}
		}
	}

	// Safety net only: the primary end-of-window is caustica$endWorldCompositeBeforeHand
	// inside renderLevel. This catches any path where renderLevel bailed early
	// (end() no-ops when the window is already closed).
	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V",
					shift = At.Shift.AFTER))
	private void caustica$endWorldComposite(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		try (var hostWork = MinecraftHostTelemetry.work("world.endSafetyNet")) {
			CausticaClientComposition.current().worldComposite().endSafetyNet(this.mainRenderTarget);
		}
	}

	// Capture the exact level projection while retaining the base projection needed to move view-effect
	// translation into the RT camera origin. The host matrix itself remains unmodified.
	@ModifyArg(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"),
			index = 0)
	private Matrix4f caustica$captureLevelProjection(Matrix4f projection) {
		try (var hostWork = MinecraftHostTelemetry.work("world.capture")) {
			if (!CausticaClientComposition.current().renderController().rtRuntimeWorkRequested()) {
				return projection;
			}

			var cameraState = this.gameRenderState().levelRenderState.cameraRenderState;
			var snapshot = CausticaClientComposition.current().frameAdapter().capture(
					Minecraft.getInstance(), cameraState.projectionMatrix, projection, cameraState.viewRotationMatrix,
					cameraState.pos.x, cameraState.pos.y, cameraState.pos.z);
			if (snapshot == null) {
				return projection;
			}
			CausticaClientComposition.current().runtime().captureFrame(snapshot);
			CausticaClientComposition.current().renderController().markProjectionCaptured();
			return projection;
		}
	}

	// Composite after the 3D-HUD projection is set, before the hand's depth clear.
	// Screen-fixed content is drawn afterward into the UI overlay, keeping it out
	// of reconstruction inputs and the hudless image used for frame generation.
	@Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V",
					ordinal = 1,
					shift = At.Shift.AFTER))
	private void caustica$endWorldCompositeBeforeHand(DeltaTracker deltaTracker, CallbackInfo ci) {
		try (var hostWork = MinecraftHostTelemetry.work("world.compositeAndUi")) {
			// A dimension transition may have an active runtime while its new scene is still preparing.
			// UI extensions require the retained frame produced by a successful world composite.
			caustica$worldComposited = CausticaClientComposition.current().worldComposite().end(this.mainRenderTarget);
			if (!caustica$worldComposited) {
				return;
			}
			// Fold RT world overlays into the shared transparent UI image before hand/screen effects and the GUI
			// add their own layers. MinecraftUiOverlay then performs the single final blend to SDR/HDR.
			try {
				var target = CausticaClientComposition.current().uiOverlay()
						.uiPassTarget(this.mainRenderTarget);
				if (target != null) CausticaClientComposition.current().runtime().recordUiPasses(target);
			} finally {
				// Completion follows the world and owned UI command buffers in the host submission.
				CausticaClientComposition.current().runtime().finishGraphicsUse();
			}
			MinecraftDebugService.captureBoundary(CapturePhase.AFTER_WORLD);
		}
	}

	// Composite the redirected UI overlay back over the world once the GUI has fully rendered into it.
	// This seam runs once per frame in both gameplay and menus.
	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V",
					shift = At.Shift.AFTER))
	private void caustica$compositeUiOverlay(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		try (var hostWork = MinecraftHostTelemetry.work("ui.composite")) {
			try (var ignored = CausticaClientComposition.current().runtime().profileStage("ui.composite")) {
				if (!CausticaClientComposition.current().runtime().frameActive()) {
					return;
				}
				// DLSS-FG quality: snapshot the main target before the combined UI overlay composites back below.
				// Hand/screen effects, world overlays and GUI are carried by the optional DLSSG UI resource.
		        long mainImage = this.mainRenderTarget.getColorTexture() instanceof VulkanGpuTexture texture
		                ? texture.vkImage() : 0L;
		        CausticaClientComposition.current().runtime().captureHudless(new BorrowedImage(
		                        mainImage, 0L, org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM,
		                        this.mainRenderTarget.width, this.mainRenderTarget.height),
						CausticaClientComposition.current().uiOverlay().capturePresentation());
				CausticaClientComposition.current().uiOverlay().compositeIfUsed();
			}
		}
	}

	@Shadow
	public abstract net.minecraft.client.renderer.state.GameRenderState gameRenderState();
}
