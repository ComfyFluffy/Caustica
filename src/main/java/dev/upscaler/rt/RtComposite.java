package dev.upscaler.rt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.upscaler.UpscalerMod;
import dev.upscaler.client.SodiumCompat;
import dev.upscaler.mixin.CommandEncoderAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * On-screen composite. Each frame, ray-trace into a screen-sized storage image, blend it 50/50
 * with a storage-capable copy of the upscaled world color, and copy the result back to the world
 * target at the end-of-world seam. Gated by {@code -Dupscaler.rt.composite=true}.
 *
 * <p>When {@link RtTerrain} has been extracted (P1), traces real terrain with perspective camera
 * rays (camera matrices captured each frame via {@link #captureFrame}); otherwise falls back to
 * the P0 triangle. Pipelines/SBT/descriptors are built once; screen-sized images rebuilt on resize.
 */
public final class RtComposite {
    public static final RtComposite INSTANCE = new RtComposite();
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("upscaler.rt.composite", "false"));
    /** Blend weight of RT over vanilla: 0 = vanilla only, 1 = RT only. {@code -Dupscaler.rt.blend}. */
    public static final float BLEND = parseBlend();

    // invViewProj(64) + camOffset(@64) + sectionTableAddr(@80) + accumFrame(@88) + debugView(@92)
    // + prevViewProj(@96) + camDelta(@160)
    private static final int WORLD_PUSH_SIZE = 172;
    private static final int GUIDE_COUNT = 5; // P4 guide buffers bound at world-pipeline bindings 3..7

    /** Debug guide-buffer view: 0 = normal render, 1 = normals, 2 = albedo, 3 = depth, 4 = roughness. */
    public static final int DEBUG_VIEW = Integer.getInteger("upscaler.rt.debugView", 0);

    private static float parseBlend() {
        try {
            return Math.clamp(Float.parseFloat(System.getProperty("upscaler.rt.blend", "0.5")), 0f, 1f);
        } catch (NumberFormatException e) {
            return 0.5f;
        }
    }

    // Monotonic per-composite frame counter, used by RtTerrain to time frames-in-flight-safe frees.
    private static volatile long frameCounter;

    public static long frameCounter() {
        return frameCounter;
    }

    private RtPipeline trianglePipeline;
    private RtPipeline worldPipeline;
    private RtBlendPipeline blendPipeline;
    private RtImage output;
    private RtImage baseCopy;
    // P4 guide buffers (first-hit attributes for the denoiser/DLSS-RR): normal+roughness, albedo, depth, motion.
    private RtImage gNormal;
    private RtImage gAlbedo;
    private RtImage gDepth;
    private RtImage gMotion;
    private RtImage gSpecAlbedo;
    // DLSS-RR denoised output (display res); copied back into `output` so the blend path is unchanged.
    private RtImage rrOutput;

    // Motion-vector reprojection state (P4.0b): the previous frame's camera-relative view-projection
    // and camera position. Held separately from the accumulation's last* fields (those are overwritten
    // before recordFrame); these are read into the push constant, then advanced at frame end.
    private final Matrix4f mvPrevProjView = new Matrix4f();
    private final Matrix4f mvCurProjView = new Matrix4f();
    private final Matrix4f mvPushMatrix = new Matrix4f();
    private double mvPrevCamX;
    private double mvPrevCamY;
    private double mvPrevCamZ;
    private float mvCamDeltaX;
    private float mvCamDeltaY;
    private float mvCamDeltaZ;
    private boolean mvHasPrev;
    private long boundTriangleTlas;
    private long boundWorldTlas;
    private long atlasSampler;
    private boolean failed;
    private boolean loggedActive;

    // Camera captured each frame from GameRenderer (unjittered level projection + camera rotation + pos).
    private final Matrix4f frameProjection = new Matrix4f();
    private final Matrix4f frameViewRotation = new Matrix4f();
    private double camX;
    private double camY;
    private double camZ;
    private boolean frameCaptured;

    // Temporal accumulation (P3.1b): frames accumulated for the current static viewpoint, pushed to
    // world.rgen. Reset to 0 whenever the camera moves, the TLAS changes (geometry edit/load/rebase),
    // or the screen images are recreated (resize) — i.e. whenever the previous estimate is invalid.
    private int accumFrame;
    private boolean haveLastCam;
    private final Matrix4f lastProjection = new Matrix4f();
    private final Matrix4f lastViewRotation = new Matrix4f();
    private double lastCamX;
    private double lastCamY;
    private double lastCamZ;
    private long accumTlas;
    private boolean accumNeedsReset; // set when images are recreated -> force a reset next frame

    private RtComposite() {
    }

    /** Capture the frame's camera for the next composite. Called from GameRendererMixin. */
    public void captureFrame(Matrix4f projection, Matrix4fc viewRotation, double cameraX, double cameraY, double cameraZ) {
        frameProjection.set(projection);
        frameViewRotation.set(viewRotation);
        camX = cameraX;
        camY = cameraY;
        camZ = cameraZ;
        frameCaptured = true;
    }

    public boolean composite(GpuTexture nativeColor, int width, int height) {
        frameCounter++; // advances once per frame; RtTerrain retires resources relative to it
        if (failed) {
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return false;
        }
        boolean useWorld = RtTerrain.currentOrNull() != null && frameCaptured;
        if (!useWorld && RtTriangleScene.currentOrNull() == null) {
            return false; // nothing to trace yet
        }
        try {
            if (blendPipeline == null) {
                blendPipeline = RtBlendPipeline.create(ctx);
            }
            ensureOutput(ctx, width, height);
            RtPipeline active = useWorld ? ensureWorld(ctx) : ensureTriangle(ctx);
            if (useWorld) {
                updateAccumulation(); // after ensureWorld so boundWorldTlas reflects this frame
                updateMotion();
            }
            recordFrame(active, useWorld, nativeColor, width, height);
            if (!loggedActive) {
                loggedActive = true;
                UpscalerMod.LOGGER.info("RT composite active ({}): {}x{}, RT blended at {} over the world target",
                        useWorld ? "terrain" : "triangle", width, height, BLEND);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            UpscalerMod.LOGGER.error("RT composite failed; reverting to vanilla/upscaler path", t);
            return false;
        }
    }

    private RtPipeline ensureWorld(RtContext ctx) {
        if (worldPipeline == null) {
            worldPipeline = RtPipeline.create(ctx, "world.rgen.spv",
                    new String[]{"world.rmiss.spv", "shadow.rmiss.spv"}, "world.rchit.spv", "world.rahit.spv",
                    WORLD_PUSH_SIZE, true, GUIDE_COUNT);
            if (output != null) {
                worldPipeline.setStorageImage(output.view);
                bindGuideImages();
            }
            worldPipeline.setAtlasSampler(blockAtlasView(), atlasSampler(ctx));
        }
        long tlas = RtTerrain.currentOrNull().tlas();
        if (boundWorldTlas != tlas) {
            worldPipeline.setTlas(tlas);
            boundWorldTlas = tlas;
        }
        return worldPipeline;
    }

    private RtPipeline ensureTriangle(RtContext ctx) {
        if (trianglePipeline == null) {
            trianglePipeline = RtPipeline.create(ctx, "triangle.rgen.spv", "triangle.rmiss.spv", "triangle.rchit.spv");
            if (output != null) {
                trianglePipeline.setStorageImage(output.view);
            }
        }
        long tlas = RtTriangleScene.currentOrNull().tlas();
        if (boundTriangleTlas != tlas) {
            trianglePipeline.setTlas(tlas);
            boundTriangleTlas = tlas;
        }
        return trianglePipeline;
    }

    /** Bind the three guide buffers into the world pipeline's extra storage-image slots (0..2). */
    private void bindGuideImages() {
        if (worldPipeline == null || gNormal == null) {
            return;
        }
        worldPipeline.setExtraStorageImage(0, gNormal.view);
        worldPipeline.setExtraStorageImage(1, gAlbedo.view);
        worldPipeline.setExtraStorageImage(2, gDepth.view);
        worldPipeline.setExtraStorageImage(3, gMotion.view);
        worldPipeline.setExtraStorageImage(4, gSpecAlbedo.view);
    }

    private void destroyGuideImages() {
        if (gNormal != null) {
            gNormal.destroy();
            gNormal = null;
        }
        if (gAlbedo != null) {
            gAlbedo.destroy();
            gAlbedo = null;
        }
        if (gDepth != null) {
            gDepth.destroy();
            gDepth = null;
        }
        if (gMotion != null) {
            gMotion.destroy();
            gMotion = null;
        }
        if (gSpecAlbedo != null) {
            gSpecAlbedo.destroy();
            gSpecAlbedo = null;
        }
        if (rrOutput != null) {
            rrOutput.destroy();
            rrOutput = null;
        }
    }

    private void ensureOutput(RtContext ctx, int width, int height) {
        if (output != null && baseCopy != null && output.width == width && output.height == height
                && baseCopy.width == width && baseCopy.height == height) {
            return;
        }
        ctx.waitIdle(); // resize is rare; no in-flight frame may use the old image/descriptor
        if (baseCopy != null) {
            baseCopy.destroy();
        }
        if (output != null) {
            output.destroy();
        }
        destroyGuideImages();
        // RT traces into an HDR (R16G16B16A16_SFLOAT) target so radiance > 1 survives to the tonemap
        // seam in blend.comp. baseCopy stays R8G8B8A8 to match the vanilla world target it is copied
        // to/from (vkCmdCopyImage requires texel-size-compatible formats).
        output = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
        baseCopy = ctx.createStorageImage(width, height);
        // Guide buffers match the trace resolution (render-res split arrives in P4.2).
        gNormal = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
        gAlbedo = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
        gDepth = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R32_SFLOAT);
        gMotion = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16_SFLOAT);
        gSpecAlbedo = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
        if (RtDlssRr.ENABLED) {
            // DLSS-RR output. P4.2a is native res (display == render); P4.2b makes this display-res.
            rrOutput = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
        }
        accumNeedsReset = true; // the recreated HDR target has no valid accumulation history
        mvHasPrev = false;      // recreated images -> first MV frame is zero
        if (trianglePipeline != null) {
            trianglePipeline.setStorageImage(output.view);
        }
        if (worldPipeline != null) {
            worldPipeline.setStorageImage(output.view);
            bindGuideImages();
        }
        blendPipeline.setImages(baseCopy.view, output.view);
    }

    /**
     * Advance (or reset) the temporal accumulation counter for this frame. The estimate stored in the
     * HDR target is only valid while the viewpoint is static and the geometry unchanged, so reset on
     * any camera movement, a TLAS swap, or an image recreate; otherwise keep accumulating.
     */
    private void updateAccumulation() {
        long tlas = boundWorldTlas; // set by ensureWorld earlier this frame
        boolean reset = accumNeedsReset || !haveLastCam
                || !frameProjection.equals(lastProjection)
                || !frameViewRotation.equals(lastViewRotation)
                || camX != lastCamX || camY != lastCamY || camZ != lastCamZ
                || tlas != accumTlas;
        accumFrame = reset ? 0 : accumFrame + 1;
        lastProjection.set(frameProjection);
        lastViewRotation.set(frameViewRotation);
        lastCamX = camX;
        lastCamY = camY;
        lastCamZ = camZ;
        accumTlas = tlas;
        haveLastCam = true;
        accumNeedsReset = false;
    }

    /**
     * Compute this frame's motion-vector push data: the matrix that projects a current world point
     * into the previous frame's clip space, plus the per-frame camera translation. On the first frame
     * (or after a reset) push the current view-projection with zero delta so MVs come out zero.
     */
    private void updateMotion() {
        mvCurProjView.set(frameProjection).mul(frameViewRotation);
        if (mvHasPrev) {
            mvPushMatrix.set(mvPrevProjView);
            mvCamDeltaX = (float) (camX - mvPrevCamX);
            mvCamDeltaY = (float) (camY - mvPrevCamY);
            mvCamDeltaZ = (float) (camZ - mvPrevCamZ);
        } else {
            mvPushMatrix.set(mvCurProjView);
            mvCamDeltaX = 0f;
            mvCamDeltaY = 0f;
            mvCamDeltaZ = 0f;
        }
        mvPrevProjView.set(mvCurProjView);
        mvPrevCamX = camX;
        mvPrevCamY = camY;
        mvPrevCamZ = camZ;
        mvHasPrev = true;
    }

    private void recordFrame(RtPipeline active, boolean useWorld, GpuTexture nativeColor, int width, int height) {
        long dstImage = vkImage(nativeColor);
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).upscaler$getBackend();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Make the previous frame's HDR-target writes visible before this frame's raygen reads it
            // back for the temporal running mean (cross-frame read-modify-write on the output image).
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
            if (useWorld) {
                RtTerrain terrain = RtTerrain.currentOrNull();
                ByteBuffer push = stack.malloc(WORLD_PUSH_SIZE);
                new Matrix4f(frameProjection).mul(frameViewRotation).invert().get(0, push);
                push.putFloat(64, (float) (camX - terrain.blockX));
                push.putFloat(68, (float) (camY - terrain.blockY));
                push.putFloat(72, (float) (camZ - terrain.blockZ));
                push.putLong(80, terrain.tableAddress());
                // DLSS-RR does its own temporal denoise, so feed it a single noisy frame (no
                // accumulate-when-static) by pushing accumFrame 0 whenever RR is enabled.
                push.putInt(88, RtDlssRr.ENABLED ? 0 : accumFrame);
                push.putInt(92, DEBUG_VIEW);
                mvPushMatrix.get(96, push);
                push.putFloat(160, mvCamDeltaX);
                push.putFloat(164, mvCamDeltaY);
                push.putFloat(168, mvCamDeltaZ);
                active.trace(cmd, width, height, push);
                // P4.2a: DLSS-RR denoise. The RT pass wrote the noisy color into `output` plus the guide
                // buffers; RR reads them and writes the denoised result into rrOutput, which we copy back
                // into `output` so the downstream tonemap/blend path is unchanged (native res, 1:1 copy).
                if (RtDlssRr.ENABLED && rrOutput != null
                        && RtDlssRr.INSTANCE.ensureFeature(cmd.address(), width, height, width, height)) {
                    VulkanCommandEncoder.memoryBarrier(cmd, stack); // RT writes visible to DLSS reads
                    if (RtDlssRr.INSTANCE.evaluate(cmd.address(), output, gDepth, gMotion, gAlbedo,
                            gSpecAlbedo, gNormal, rrOutput, width, height, width, height)) {
                        VulkanCommandEncoder.memoryBarrier(cmd, stack); // DLSS write visible to the copy
                        VK10.vkCmdCopyImage(cmd, rrOutput.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                                output.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, width, height));
                    }
                }
            } else {
                active.trace(cmd, width, height);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            VK10.vkCmdCopyImage(cmd, dstImage, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    baseCopy.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, width, height));
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            blendPipeline.dispatch(cmd, width, height, BLEND);
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            VK10.vkCmdCopyImage(cmd, baseCopy.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    dstImage, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, width, height));
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(rt composite) failed");
        }
        encoder.execute(cmd); // deferred into the frame's submission — correct for per-frame work
    }

    public void destroy() {
        if (RtDlssRr.ENABLED) {
            RtDlssRr.INSTANCE.destroy();
        }
        if (baseCopy != null) {
            baseCopy.destroy();
            baseCopy = null;
        }
        if (output != null) {
            output.destroy();
            output = null;
        }
        destroyGuideImages();
        if (blendPipeline != null) {
            blendPipeline.destroy();
            blendPipeline = null;
        }
        if (worldPipeline != null) {
            worldPipeline.destroy();
            worldPipeline = null;
        }
        if (trianglePipeline != null) {
            trianglePipeline.destroy();
            trianglePipeline = null;
        }
        if (atlasSampler != 0L) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null) {
                VK10.vkDestroySampler(ctx.vk(), atlasSampler, null);
            }
            atlasSampler = 0L;
        }
        boundTriangleTlas = 0L;
        boundWorldTlas = 0L;
    }

    private long atlasSampler(RtContext ctx) {
        if (atlasSampler == 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                        .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .minLod(0f).maxLod(0f);
                LongBuffer p = stack.mallocLong(1);
                if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSampler(block atlas) failed");
                }
                atlasSampler = p.get(0);
            }
        }
        return atlasSampler;
    }

    private static long blockAtlasView() {
        GpuTextureView view = Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        return vkImageView(view);
    }

    private static long vkImageView(GpuTextureView view) {
        Long sodiumHandle = SodiumCompat.vkImageView(view);
        if (sodiumHandle != null) {
            return sodiumHandle;
        }
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        throw new IllegalStateException("cannot resolve VkImageView for " + view);
    }

    private static long vkImage(GpuTexture texture) {
        Long sodiumHandle = SodiumCompat.vkImage(texture);
        if (sodiumHandle != null) {
            return sodiumHandle;
        }
        if (texture instanceof VulkanGpuTexture vulkanTexture) {
            return vulkanTexture.vkImage();
        }
        throw new IllegalStateException("cannot resolve VkImage for " + texture);
    }

    private static VkImageCopy.Buffer copyRegion(MemoryStack stack, int width, int height) {
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).extent().set(width, height, 1);
        return region;
    }
}
