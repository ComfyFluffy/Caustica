package dev.upscaler.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.upscaler.client.FsrPipeline;
import dev.upscaler.client.WorldRenderScaler;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
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

	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
	private void upscaler$beginWorldScale(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		WorldRenderScaler.INSTANCE.begin(this.mainRenderTarget);
	}

	// After fogRenderer.endFrame() and *before* the pre-GUI depth clear's arguments
	// are evaluated (injecting at the clearDepthTexture INVOKE itself would be too
	// late: the depth texture argument is fetched while low-res is still swapped in).
	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V",
					shift = At.Shift.AFTER))
	private void upscaler$endWorldScale(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		WorldRenderScaler.INSTANCE.end(this.mainRenderTarget);
	}

	// Sub-pixel camera jitter (M3): premultiply a clip-space translation onto the
	// level projection matrix as it is uploaded. Only the Matrix4f getBuffer
	// overload is the level projection (the 3D-HUD call uses the Projection
	// overload); the frustum-culling matrices are built elsewhere from the
	// unmodified cameraState matrix and stay unjittered.
	@ModifyArg(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"),
			index = 0)
	private Matrix4f upscaler$jitterLevelProjection(Matrix4f projection) {
		if (!WorldRenderScaler.INSTANCE.isEnabled()) {
			return projection;
		}

		// Capture the unjittered frame matrices for the MV reprojection pass
		// (this projection already includes view bobbing, exactly as rendered).
		var cameraState = this.gameRenderState().levelRenderState.cameraRenderState;
		FsrPipeline.INSTANCE.captureFrame(projection, cameraState.viewRotationMatrix, cameraState.pos);

		float jx = FsrPipeline.INSTANCE.jitterNdcX();
		float jy = FsrPipeline.INSTANCE.jitterNdcY();
		if (jx == 0.0f && jy == 0.0f) {
			return projection;
		}
		return new Matrix4f().translation(jx, jy, 0.0f).mul(projection);
	}

	@Shadow
	public abstract net.minecraft.client.renderer.state.GameRenderState gameRenderState();
}
