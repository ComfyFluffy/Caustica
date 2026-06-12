package dev.upscaler.client;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.upscaler.UpscalerMod;
import dev.upscaler.mixin.RenderTargetAccessor;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * M2 spike: renders the world at a reduced internal resolution, then upscales
 * into the native-resolution main target before the GUI pass.
 *
 * <p>Approach: the main {@link RenderTarget} <em>object</em> is referenced from
 * many places (LevelRenderer re-imports it every frame, but SkyRenderer captures
 * it at construction), so instead of swapping the reference we temporarily swap
 * the object's <em>internal textures</em> for low-res ones during level
 * rendering. Every consumer — frame graph, sky, entity outline, post chains —
 * automatically renders at the reduced size, because they all fetch the texture
 * views from the same object each pass. Afterwards the native textures are
 * restored and the low-res color is upscaled with a bilinear fullscreen blit
 * (vanilla TRACY_BLIT pipeline + linear sampler).
 *
 * <p>Scale comes from {@code -Dupscaler.renderScale} (default: FSR Quality,
 * 1/1.5); set it to 1.0 to disable. Values are clamped to [0.1, 1.0].
 */
public final class WorldRenderScaler {
	public static final WorldRenderScaler INSTANCE = new WorldRenderScaler();
	private static final float DEFAULT_SCALE = 1.0f / 1.5f;

	private final float scale;

	// low-res resources, owned by this class
	private GpuTexture lowResColor;
	private GpuTextureView lowResColorView;
	private GpuTexture lowResDepth;
	private GpuTextureView lowResDepthView;
	private GpuTexture sceneDepth;
	private GpuTextureView sceneDepthView;
	private int lowResWidth = -1;
	private int lowResHeight = -1;
	private boolean sceneDepthValid;

	// native textures stashed during the level-render window
	private GpuTexture savedColor;
	private GpuTextureView savedColorView;
	private GpuTexture savedDepth;
	private GpuTextureView savedDepthView;
	private int savedWidth;
	private int savedHeight;
	private boolean active;
	private boolean loggedActivation;

	private WorldRenderScaler() {
		float configured = DEFAULT_SCALE;
		String prop = System.getProperty("upscaler.renderScale");
		if (prop != null) {
			try {
				configured = Float.parseFloat(prop);
			} catch (NumberFormatException e) {
				UpscalerMod.LOGGER.warn("Invalid upscaler.renderScale '{}', using FSR Quality scale {}", prop, DEFAULT_SCALE);
			}
		}
		this.scale = Math.clamp(configured, 0.1f, 1.0f);
	}

	public boolean isEnabled() {
		return this.scale < 1.0f;
	}

	/** Swap low-res textures into the main target. Call right before level rendering. */
	public void begin(RenderTarget mainTarget) {
		if (!isEnabled() || this.active) {
			return;
		}

		int renderWidth = Math.max(1, Math.round(mainTarget.width * this.scale));
		int renderHeight = Math.max(1, Math.round(mainTarget.height * this.scale));
		ensureLowResTargets(renderWidth, renderHeight);
		FsrPipeline.INSTANCE.prepareFrameJitter(renderWidth, renderHeight, mainTarget.width);

		var accessor = (RenderTargetAccessor) mainTarget;
		this.savedColor = accessor.upscaler$getColorTexture();
		this.savedColorView = accessor.upscaler$getColorTextureView();
		this.savedDepth = accessor.upscaler$getDepthTexture();
		this.savedDepthView = accessor.upscaler$getDepthTextureView();
		this.savedWidth = mainTarget.width;
		this.savedHeight = mainTarget.height;

		accessor.upscaler$setColorTexture(this.lowResColor);
		accessor.upscaler$setColorTextureView(this.lowResColorView);
		accessor.upscaler$setDepthTexture(this.lowResDepth);
		accessor.upscaler$setDepthTextureView(this.lowResDepthView);
		mainTarget.width = renderWidth;
		mainTarget.height = renderHeight;
		this.active = true;
		this.sceneDepthValid = false;

		if (!this.loggedActivation) {
			this.loggedActivation = true;
			UpscalerMod.LOGGER.info("World render scale active: {}x{} -> {}x{} (scale {})",
					renderWidth, renderHeight, this.savedWidth, this.savedHeight, this.scale);
		}
	}

	/**
	 * Vanilla clears the main depth buffer before drawing the first-person hand.
	 * Since the main target is currently swapped to our low-res textures, preserve
	 * world depth here so FSR does not receive a depth buffer containing only hand
	 * pixels.
	 */
	public void captureSceneDepthBeforeHand() {
		if (!this.active || this.lowResDepth == null || this.sceneDepth == null) {
			return;
		}

		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
				this.lowResDepth, this.sceneDepth, 0, 0, 0, 0, 0, this.lowResWidth, this.lowResHeight);
		this.sceneDepthValid = true;
	}

	/** Restore native textures and upscale the low-res world into them. Call before GUI rendering. */
	public void end(RenderTarget mainTarget) {
		if (!this.active) {
			return;
		}
		this.active = false;

		var accessor = (RenderTargetAccessor) mainTarget;
		accessor.upscaler$setColorTexture(this.savedColor);
		accessor.upscaler$setColorTextureView(this.savedColorView);
		accessor.upscaler$setDepthTexture(this.savedDepth);
		accessor.upscaler$setDepthTextureView(this.savedDepthView);
		mainTarget.width = this.savedWidth;
		mainTarget.height = this.savedHeight;
		this.savedColor = null;
		this.savedColorView = null;
		this.savedDepth = null;
		this.savedDepthView = null;

		// Preferred path: FSR 3.1 temporal upscale low-res color/depth -> native color.
		GpuTexture fsrDepth = this.sceneDepthValid ? this.sceneDepth : this.lowResDepth;
		GpuTextureView fsrDepthView = this.sceneDepthValid ? this.sceneDepthView : this.lowResDepthView;
		boolean fsrDone = FsrPipeline.INSTANCE.dispatch(
				this.lowResColor, fsrDepth, fsrDepthView, this.lowResWidth, this.lowResHeight,
				mainTarget.getColorTexture(), this.savedWidth, this.savedHeight);
		if (fsrDone) {
			return;
		}

		// Fallback: bilinear upscale low-res world color -> native main target color.
		// Mirrors RenderTarget.blitAndBlendToTexture, but with the non-blending
		// TRACY_BLIT pipeline and a linear sampler.
		try (RenderPass pass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(() -> "Upscaler world blit",
						mainTarget.getColorTextureView(), Optional.empty(),
						mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(RenderPipelines.TRACY_BLIT);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("InSampler", this.lowResColorView,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.draw(3, 1, 0, 0);
		}
	}

	private void ensureLowResTargets(int width, int height) {
		if (width == this.lowResWidth && height == this.lowResHeight && this.lowResColor != null) {
			return;
		}
		destroyLowResTargets();

		var device = RenderSystem.getDevice();
		// usage 15 = same flag set vanilla RenderTarget.createBuffers uses
		this.lowResColor = device.createTexture(() -> "Upscaler world / Color", 15, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
		this.lowResColorView = device.createTextureView(this.lowResColor);
		this.lowResDepth = device.createTexture(() -> "Upscaler world / Depth", 15, GpuFormat.D32_FLOAT, width, height, 1, 1);
		this.lowResDepthView = device.createTextureView(this.lowResDepth);
		this.sceneDepth = device.createTexture(() -> "Upscaler scene depth snapshot", 15, GpuFormat.D32_FLOAT, width, height, 1, 1);
		this.sceneDepthView = device.createTextureView(this.sceneDepth);
		this.lowResWidth = width;
		this.lowResHeight = height;
		this.sceneDepthValid = false;
	}

	private void destroyLowResTargets() {
		if (this.lowResColorView != null) {
			this.lowResColorView.close();
			this.lowResColorView = null;
		}
		if (this.lowResColor != null) {
			this.lowResColor.close();
			this.lowResColor = null;
		}
		if (this.lowResDepthView != null) {
			this.lowResDepthView.close();
			this.lowResDepthView = null;
		}
		if (this.lowResDepth != null) {
			this.lowResDepth.close();
			this.lowResDepth = null;
		}
		if (this.sceneDepthView != null) {
			this.sceneDepthView.close();
			this.sceneDepthView = null;
		}
		if (this.sceneDepth != null) {
			this.sceneDepth.close();
			this.sceneDepth = null;
		}
		this.lowResWidth = -1;
		this.lowResHeight = -1;
		this.sceneDepthValid = false;
	}
}
