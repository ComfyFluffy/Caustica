package dev.upscaler.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.upscaler.client.VanillaRenderController;
import dev.upscaler.client.WorldRenderScaler;
import dev.upscaler.rt.RtComposite;
import dev.upscaler.rt.RtReflex;
import dev.upscaler.rt.RtUiOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets the level-rendering section of {@link GameRenderer#render} with the
 * render-scale window: low-res textures are swapped into the main target just
 * before {@code renderLevel} (so the level frame graph, sky, entity outline and
 * post chains all run at reduced resolution) and restored + upscaled right
 * before the pre-GUI depth clear.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	// Reset the UI overlay's per-frame clear latch at the very start of the frame (before the world, hand,
	// or GUI render into it).
	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
	private void upscaler$beginOverlayFrame(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		RtUiOverlay.beginFrame();
		// Clear the stale HDR-present flag every frame: composite() only runs while a level renders, so on
		// menu frames it would otherwise stay true from the last world frame and present a black HDR image.
		RtComposite.INSTANCE.beginFrame();
		// Reflex RENDERSUBMIT_START: render-graph recording begins here; RENDERSUBMIT_END is set at
		// VulkanGpuSurface.present() HEAD (VulkanGpuSurfaceMixin), just before the real present.
		if (RtReflex.enabled()) {
			long swapchain = RtReflex.INSTANCE.appliedSwapchain();
			if (swapchain != 0L
					&& ((GpuDeviceAccessor) RenderSystem.getDevice()).upscaler$getBackend() instanceof VulkanDevice device) {
				RtReflex.INSTANCE.marker(device.vkDevice(), swapchain, RtReflex.MARKER_RENDERSUBMIT_START,
						RtReflex.INSTANCE.currentSimFrameId());
			}
		}
	}

	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
	private void upscaler$beginWorldScale(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		WorldRenderScaler.INSTANCE.begin(this.mainRenderTarget);
	}

	// HDR mode: redirect the held-item/hand render into the UI overlay so it composites over the HDR world
	// (at paper white) instead of the SDR main target that the HDR present bypasses. try/finally guarantees
	// the output overrides are cleared even if the hand render throws (a leak would route everything after
	// into the overlay). Non-HDR frames are untouched.
	@WrapOperation(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;FLorg/joml/Matrix4fc;)V"))
	private void upscaler$redirectHandToOverlay(GameRenderer self, CameraRenderState cameraState, float deltaPartialTick,
			Matrix4fc modelViewMatrix, Operation<Void> original) {
		boolean hdr = RtComposite.INSTANCE.isHdrPresentActive();
		if (hdr) {
			RtUiOverlay.beginOutputRedirect(this.mainRenderTarget);
		}
		try {
			original.call(self, cameraState, deltaPartialTick, modelViewMatrix);
		} finally {
			if (hdr) {
				RtUiOverlay.endOutputRedirect();
			}
		}
	}

	// HDR mode: redirect the screen-effect flush (fire, underwater, view-blocking-block overlays submitted
	// by ScreenEffectRenderer.submit) into the UI overlay, same as the hand. This is the renderAllFeatures
	// call in the "screenEffects" section of renderLevel — distinct from the one inside renderItemInHand
	// (that's a different method, so this @WrapOperation on renderLevel matches only the screen-effect flush).
	// The spyglass scope and worn-pumpkin blur are drawn by the GUI/HUD instead, so they already reach the
	// overlay via the GuiRenderer redirect. try/finally guarantees the overrides clear even on a throw.
	@WrapOperation(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures(Lnet/minecraft/client/renderer/SubmitNodeStorage;)V"))
	private void upscaler$redirectScreenEffectsToOverlay(FeatureRenderDispatcher self, SubmitNodeStorage storage,
			Operation<Void> original) {
		boolean hdr = RtComposite.INSTANCE.isHdrPresentActive();
		if (hdr) {
			RtUiOverlay.beginOutputRedirect(this.mainRenderTarget);
		}
		try {
			original.call(self, storage);
		} finally {
			if (hdr) {
				RtUiOverlay.endOutputRedirect();
			}
		}
	}

	// Safety net only: the primary end-of-window is upscaler$endWorldScaleBeforeHand
	// inside renderLevel. This catches any path where renderLevel bailed early
	// (end() no-ops when the window is already closed).
	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V",
					shift = At.Shift.AFTER))
	private void upscaler$endWorldScale(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		WorldRenderScaler.INSTANCE.endSafetyNet(this.mainRenderTarget);
	}

	// Capture the frame's camera for the RT composite at the exact point the level projection is built
	// (this projection already includes view bobbing, exactly as rendered). The RT path jitters the
	// primary ray in the shader, so the projection matrix itself is left unmodified — we only read it.
	@ModifyArg(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"),
			index = 0)
	private Matrix4f upscaler$captureLevelProjection(Matrix4f projection) {
		if (!VanillaRenderController.rtRuntimeWorkRequested()) {
			return projection;
		}

		var cameraState = this.gameRenderState().levelRenderState.cameraRenderState;
		RtComposite.INSTANCE.captureFrame(projection, cameraState.viewRotationMatrix,
				cameraState.pos.x, cameraState.pos.y, cameraState.pos.z);
		VanillaRenderController.INSTANCE.markProjectionCaptured();
		return projection;
	}

	// Primary end-of-window: right after the 3D-HUD projection is set and *before*
	// vanilla's pre-hand depth clear. The world (incl. entity outline targets and
	// translucency compositing) has fully rendered at low res by this point; the
	// upscale runs here, then the hand, screen effects and 3D crosshair draw at
	// native resolution on top — keeping the screen-fixed hand out of the FSR
	// inputs entirely (camera-reprojection MVs would be exactly wrong for it).
	@Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V",
					ordinal = 1,
					shift = At.Shift.AFTER))
	private void upscaler$endWorldScaleBeforeHand(DeltaTracker deltaTracker, CallbackInfo ci) {
		WorldRenderScaler.INSTANCE.end(this.mainRenderTarget);
	}

	// Composite the redirected UI overlay back over the world once the GUI has fully rendered into it.
	// Done here (not at GuiRenderer.draw TAIL) because that TAIL inject did not fire on in-game HUD frames;
	// this INVOKE-after seam runs unconditionally once per frame in both gameplay and menus.
	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V",
					shift = At.Shift.AFTER))
	private void upscaler$compositeUiOverlay(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		dev.upscaler.rt.RtUiOverlay.compositeIfUsed();
	}

	@Shadow
	public abstract net.minecraft.client.renderer.state.GameRenderState gameRenderState();
}
