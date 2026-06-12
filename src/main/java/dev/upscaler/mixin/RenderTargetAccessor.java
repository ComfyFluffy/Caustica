package dev.upscaler.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {
	@Accessor("colorTexture")
	GpuTexture upscaler$getColorTexture();

	@Accessor("colorTexture")
	void upscaler$setColorTexture(GpuTexture texture);

	@Accessor("colorTextureView")
	GpuTextureView upscaler$getColorTextureView();

	@Accessor("colorTextureView")
	void upscaler$setColorTextureView(GpuTextureView view);

	@Accessor("depthTexture")
	GpuTexture upscaler$getDepthTexture();

	@Accessor("depthTexture")
	void upscaler$setDepthTexture(GpuTexture texture);

	@Accessor("depthTextureView")
	GpuTextureView upscaler$getDepthTextureView();

	@Accessor("depthTextureView")
	void upscaler$setDepthTextureView(GpuTextureView view);
}
