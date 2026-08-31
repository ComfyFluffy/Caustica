package dev.comfyfluffy.caustica.rt;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.client.CausticaJitter;
import dev.comfyfluffy.caustica.mixin.CommandEncoderAccessor;
import dev.comfyfluffy.caustica.rt.gen.WorldPushConstantsData;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.BreakEntry;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float2;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float3;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float4;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Int4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.material.FluidState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;
import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
import dev.comfyfluffy.caustica.rt.material.RtEmissionSemantics;
import dev.comfyfluffy.caustica.rt.material.RtMaterialOverrides;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import dev.comfyfluffy.caustica.rt.pipeline.RtDebugPresentPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtBloomPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtSkyLut;
import dev.comfyfluffy.caustica.rt.pipeline.RtDisplayPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import dev.comfyfluffy.caustica.rt.overlay.RtWorldOverlay;
import dev.comfyfluffy.caustica.rt.pipeline.RtHdrCompositePipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtSdrPresentPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtExposure;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtToneLut;
import dev.comfyfluffy.caustica.rt.terrain.RtCloudNoise;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Objects;

/**
 * On-screen composite. Each frame, ray-trace into a render-res storage image (+ guide buffers), use
 * DLSS Ray Reconstruction to denoise and upscale it to display res, write that into a storage-capable
 * copy of the world color, and copy the result back to the world target at the
 * end-of-world seam. Gated by {@code -Dcaustica.rt=true}.
 *
 * <p>The path tracer and its guide buffers run at the configured render scale of display res with a per-frame
 * sub-pixel camera jitter; DLSS-RR ({@link RtDlssRr}) reconstructs the display-res image. With RR
 * disabled the trace runs at 1:1 and a linear blit stands in for the upscale (a raw, noisy reference).
 *
 * <p>Traces the extracted {@link RtTerrain} with perspective camera rays (camera matrices captured
 * each frame via {@link #captureFrame}); writes nothing until terrain is available.
 * Pipelines/SBT/descriptors are built once; sized images rebuilt on resize.
 */
public final class RtComposite {
    public static final RtComposite INSTANCE = new RtComposite();

    public static boolean enabled() {
        return CausticaConfig.Rt.ENABLED.value();
    }

    // WorldPushData and its serializer are generated from Slang's reflected Std430DataLayout. Java never
    // owns or calculates a shader byte offset, struct size, array stride, or fixed-array capacity.
    private static final int WORLD_PUSH_SIZE = WorldPushData.BYTE_SIZE;
    // Real inline push constants (fast constant-bank reads), separate from the WorldPush BDA ring above.
    // Hot addresses/frameIndex avoid unnecessary global-memory dereferences; WorldPushConstantsData is
    // generated from the same Slang module and owns this second ABI as well. debugView is no longer
    // part of it -- no world shader reads it anymore; debug views are a downstream compute pass.
    private static final long PATH_RECORD_BYTES = 48L;
    private static int debugView() {
        return CausticaConfig.Rt.Composite.DEBUG_VIEW.value();
    }

    private static int spp() {
        return CausticaConfig.Rt.Composite.SPP.value();
    }

    private static int maxBounces() {
        return CausticaConfig.Rt.Composite.MAX_BOUNCES.value();
    }

    private static boolean waterWaves() {
        return CausticaConfig.Rt.Composite.WATER_WAVES.value();
    }

    private static final int WATER_ANCHOR_MASK = 4095;
    // The versioned look package owns every photometric anchor and the sky geometry. Its sun illuminance is the
    // photometric solar constant at the top of the atmosphere; the shader's transmittance LUT brings that
    // to ~117,000 lux under a zenith sun and reddens/dims it through sunset, and because world.rmiss tints
    // the visible disc from the same LUT, the light on terrain and the sky's sunset are one number.
    //
    // world.rgen consumes it as ILLUMINANCE at normal incidence (lux) — the NEE term is brdf·E·ndl with no
    // solid-angle factor, and the diffuse BRDF's 1/π turns 100,000 lux into
    // 31,800 cd/m² white / 5,730 cd/m² 18%-grey noon surface. It is therefore independent of the sky
    // package's angular radii, which only jitter the shadow ray and so only set penumbra softness.
    private static final RtLookPackage LOOK = RtLookPackage.current();
    private static final Identifier SUN_ID = Identifier.withDefaultNamespace("sun");
    private static final Identifier[] MOON_IDS = createMoonIds();
    // Sign of the sub-pixel jitter as reported to DLSS-RR + applied to the primary ray, mirroring the
    // validated DLSS-SR convention (Vulkan flipped clip space wants Y negated).
    private static float jitterSignX() {
        return CausticaConfig.Rt.Composite.JITTER_SIGN_X.value();
    }

    private static float jitterSignY() {
        return CausticaConfig.Rt.Composite.JITTER_SIGN_Y.value();
    }

    // Monotonic per-composite frame counter used for cache eviction, shader sampling, and diagnostics.
    private static volatile long frameCounter;

    public static long frameCounter() {
        return frameCounter;
    }

    private RtPipeline worldPipeline;
    // Set at the HEAD of Minecraft.reloadResourcePacks() (mixin): a resource reload recreates the block
    // atlas + entity textures. We tear down the world pipeline there (drops all descriptor references) and
    // rebuild it once the NEW atlas is in place — detected by the atlas view handle changing away from
    // boundBlockAlbedoAtlasHandle to a fresh non-zero value (MC's deferred free keeps the old handle live for a few
    // frames, so "handle != 0" alone isn't enough to tell old from new).
    private volatile boolean reloadRebindRequested;
    // The block-atlas view handle currently bound into the world pipeline (set by bindWorldTextures).
    private long boundBlockAlbedoAtlasHandle;
    private int bindlessTextureCapacity;
    // True after the LabPBR atlases have been resolved/bound for the currently alive world pipeline.
    private boolean materialBindingsReady;
    // Set when a new material epoch is published. The first composite returns to vanilla so the next
    // client tick can apply RtTerrain's full-clear before any old-epoch primitive IDs are traced.
    private boolean materialEpochTraceGate;
    // World push data lives in a host-visible BDA ring; only the slot address and a small hot subset are
    // pushed inline (the full generated structure exceeds NVIDIA's 256-byte push-constant ceiling).
    // Exact graphics completion guards host writes; ring depth only avoids routine waits.
    private static final int PUSH_RING = 6;
    private PushSlot[] pushRing;
    private int pushSlot;
    private RtDisplayPipeline displayPipeline;
    private RtBloomPipeline bloomPipeline;
    // Atmosphere LUTs (transmittance + multiple scattering + this frame's sky view). Device-lifetime; the
    // two static tables are baked on the first frame that records the pass.
    private RtSkyLut skyLut;
    private RtDebugPresentPipeline debugPresentPipeline;
    private RtToneLut sdrToneLut;
    private RtToneLut hdrToneLut;
    private RtToneLut lookLut;
    private int loadedHdrLutNits = -1;
    private RtImage output;
    // Packed primary -> indirect continuations. Pass A is fixed at one sample and owns two records per
    // render pixel (base + optional transmission); Pass B resamples them at the configured SPP.
    private RtBuffer continuationQueue;
    private RtImage displayImage;
    // Bloom pyramid, finest first: level 0 is half display resolution and each level halves again. The
    // display mapper reads level 0, which the upsample sweep leaves holding the sum of every band.
    private RtImage[] bloomLevels = new RtImage[0];
    // Parallel PQ-encoded ([0,1], ST.2084) HDR display image. Written alongside displayImage when HDR is
    // enabled. When the PQ swapchain is active, the combined UI overlay is composited over this image, then
    // this image is blitted straight to the swapchain.
    private RtImage hdrDisplayImage;
    // Set true after this frame's display dispatch wrote hdrDisplayImage (HDR enabled + RT ran); gates the
    // HDR present blit so a frame where RT did not run falls back to the vanilla SDR present.
    private boolean hdrWrittenThisFrame;
    // DLSS-FG "hudless" resource: a copy of the main render target before the combined UI overlay
    // composites back on top. Lazily allocated (only meaningful once FG + the UI overlay redirect are both
    // active), resized on demand.
    private RtImage fgHudlessImage;
    // Same idea as fgHudlessImage but for the HDR present path: a copy of hdrDisplayImage taken in
    // presentHdr right before its own combined-UI composite dispatch overwrites it in place (see
    // captureFgHdrHudless). Already PQ-encoded (same as hdrDisplayImage), so this is a plain image copy, not
    // a format conversion — DLSS-FG requires a display-ready EOTF-encoded [0,1] signal (its programming
    // guide explicitly disallows scRGB), and PQ is exactly that.
    private RtImage fgHdrHudlessImage;
    // Step C.2: composites the combined UI overlay over hdrDisplayImage at paper white, just before present.
    private RtHdrCompositePipeline hdrCompositePipeline;
    private long hdrUiSampler;

    private static final class PushSlot {
        final RtBuffer buffer;
        final RtGpuExecutor.TrackedGraphicsUse graphicsUse = new RtGpuExecutor.TrackedGraphicsUse();

        PushSlot(RtBuffer buffer) {
            this.buffer = buffer;
        }
    }
    // Menu/non-RT present: converts the SDR main target (sRGB) to PQ-encoded at paper white so menus,
    // the title panorama and the loading screen present correctly to the PQ swapchain instead of being
    // raw-copied (misdisplayed). Lazily created; the image is sized to the swapchain.
    private RtSdrPresentPipeline sdrPresentPipeline;
    private RtImage sdrPresentImage;
    // DLSS Frame Generation: per-generated-frame interpolated output images (backbuffer size/format), and
    // the jitter-free reprojection matrices derived from the MV view-projections each frame. In HDR mode
    // these hold DLSSG's raw PQ-encoded output, which is blitted straight to the (PQ) swapchain — no decode
    // needed since the swapchain itself is PQ-native.
    private RtImage[] fgInterp = new RtImage[0];
    private int fgInterpW = -1;
    private int fgInterpH = -1;
    private int fgInterpFormat = Integer.MIN_VALUE;
    private boolean fgReset = true;
    private final Matrix4f fgClipToPrev = new Matrix4f();
    private final Matrix4f fgPrevToClip = new Matrix4f();
    private final Matrix4f fgMatTmp = new Matrix4f();
    // Guide buffers (first-hit attributes for DLSS-RR): normal+roughness, albedo, depth, motion,
    // specular albedo, and reflection motion.
    private RtImage gNormal;
    private RtImage gAlbedo;
    private RtImage gDepth;
    private RtImage gMotion;
    private RtImage gSpecAlbedo;
    private RtImage gSpecMotion;
    /**
     * ReSTIR temporal reservoirs. Two images, both bound for the pipeline's lifetime, swapping the
     * roles of history and destination each frame via a push constant rather than by rewriting
     * descriptors — see {@link RtPipeline#setReservoirImages}. Double-width: two texels per pixel.
     */
    /**
     * Precomputed cloud shape field. Created once and never rewritten — the wind offset moves the
     * sampling domain rather than the data, so nothing here changes after upload.
     */
    private RtImage cloudNoise;
    private long cloudNoiseSampler;
    private RtImage reservoirA;
    private RtImage reservoirB;
    /** Flips each frame; 0 means A is history. */
    private int reservoirParity;
    /** Set whenever history becomes meaningless — first frame, resize, dimension change. */
    private boolean reservoirHistoryValid;
    private Object reservoirDimension;
    // Display-res RT image the display mapper reads: DLSS-RR writes it (render -> display denoise+upscale), or a
    // linear blit of `output` fills it when RR is off/unavailable (the no-RR reference / fallback).
    private RtImage rrOutput;
    private final RtExposure exposure = new RtExposure();

    // Trace + guide buffers run at render res; composite (display-mapping) runs at display res.
    private int displayW = -1;
    private int displayH = -1;
    private int renderW = -1;
    private int renderH = -1;
    // What ensureOutput last sized the render/guide images for, so a quality change (or RR being
    // toggled) at a fixed window size is noticed even though displayW/displayH didn't change.
    private boolean renderSizeRrEnabled;
    private int renderSizeRrQuality = Integer.MIN_VALUE;

    // Motion-vector reprojection state: the previous frame's camera-relative view-projection and
    // camera position, read into the push constant each frame then advanced at frame end.
    private final Matrix4f mvPrevProjView = new Matrix4f();
    private final Matrix4f mvCurProjView = new Matrix4f();
    private final Matrix4f mvPushMatrix = new Matrix4f();
    private final Matrix4f frameInvViewProj = new Matrix4f();
    private final BlockPos.MutableBlockPos cameraBlockPos = new BlockPos.MutableBlockPos();
    private double mvPrevCamX;
    private double mvPrevCamY;
    private double mvPrevCamZ;
    private float mvCamDeltaX;
    private float mvCamDeltaY;
    private float mvCamDeltaZ;
    private boolean mvHasPrev;
    private float previousWaterWaveTime;
    private boolean waterWaveTimeValid;
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
    private long celestialUvAtlasHandle;
    private int celestialUvMoonPhase = -1;
    private float sunU0;
    private float sunV0;
    private float sunU1 = 1f;
    private float sunV1 = 1f;
    private float moonU0;
    private float moonV0;
    private float moonU1 = 1f;
    private float moonV1 = 1f;

    // Per-frame TLAS resources, rebuilt in place from a small ring of persistent slots (see
    // RtAccel.TlasRing — replaces the old create-and-defer-destroy-per-frame churn whose VMA slow path
    // showed up as rare multi-ms prepareTlas spikes).
    private final RtAccel.TlasRing tlasRing = new RtAccel.TlasRing();

    // This frame's TLAS handle, published after prepareTlas so the world-overlay pass (block outline's
    // rayQueryEXT occlusion test) can bind the exact same acceleration structure the primary trace used —
    // same-queue submission order (RtWorldOverlay's transient buffer runs later, same graphics queue)
    // makes the TLAS build's writes visible without an extra semaphore, matching every other overlay
    // feature's reliance on in-order queue execution for this frame's world content.
    private volatile long currentTlasHandle;
    private RtGpuExecutor.GraphicsUse pendingGraphicsUse;

    private RtComposite() {
    }

    /** This frame's TLAS handle (0 if none built yet), for {@code dev.comfyfluffy.caustica.rt.overlay} occlusion queries. */
    public long currentTlasHandle() {
        return currentTlasHandle;
    }

    private static Identifier[] createMoonIds() {
        MoonPhase[] phases = MoonPhase.values();
        Identifier[] ids = new Identifier[phases.length];
        for (int i = 0; i < phases.length; i++) {
            ids[i] = Identifier.withDefaultNamespace("moon/" + phases[i].getSerializedName());
        }
        return ids;
    }

    public boolean hasFailed() {
        return this.failed;
    }

    /** Read-only access to the auto-exposure controller, for diagnostics (F3 entry, frame stats log). */
    public RtExposure exposure() {
        return exposure;
    }

    /**
     * Export the latest RT scene image at the exact input seam of the Look/LMT stage.
     *
     * <p>The GPU image stores {@code sceneLinear * preExposure} in fp16. This readback multiplies RGB by
     * the display shader's current 1x1 {@code residualExposure}, in float32, then quantizes the resulting
     * exposure-adjusted scene-linear image to fp16 EXR. Metadata keeps both factors so the original scene-linear
     * values can be reconstructed with {@code RGB / (preExposure * residualExposure)}.
     *
     * @return {@code true} when a current RT frame was available and written
     */
    public boolean exportLatestResidualExposureExr(Path outputPath) throws java.io.IOException {
        RenderSystem.assertOnRenderThread();
        RtContext ctx = RtContext.currentOrNull();
        if (!enabled() || failed || ctx == null || rrOutput == null || exposure.image() == null
                || displayW <= 0 || displayH <= 0 || pendingGraphicsUse != null) {
            return false;
        }

        long pixelCount = Math.multiplyExact((long) displayW, (long) displayH);
        long rgbaBytes = Math.multiplyExact(pixelCount, 4L * Short.BYTES);
        long totalBytes = Math.addExact(rgbaBytes, Float.BYTES);
        if (pixelCount > Integer.MAX_VALUE / 4L) {
            throw new IllegalArgumentException("EXR capture is too large for a Java array: "
                    + displayW + "x" + displayH);
        }

        // All ordinary frame commands have been submitted before the F2 key is handled. Drain them before
        // a private one-shot copy so rrOutput and the exposure image describe the same completed frame.
        ctx.waitIdle();
        RtBuffer readback = ctx.createReadbackBuffer(totalBytes, "residual-exposure EXR readback");
        try {
            ctx.submitSync(cmd -> recordExrReadback(ctx, cmd, readback, rgbaBytes));
            readback.invalidate();

            float residualExposure = MemoryUtil.memGetFloat(readback.mapped + rgbaBytes);
            RtExposure.CaptureMetadata exposureMetadata = exposure.captureMetadata(residualExposure);
            short[] exposedRgba = new short[Math.toIntExact(pixelCount * 4L)];
            for (int sample = 0; sample < exposedRgba.length; sample++) {
                short storedHalf = MemoryUtil.memGetShort(readback.mapped + (long) sample * Short.BYTES);
                float value = Float.float16ToFloat(storedHalf);
                if ((sample & 3) != 3) {
                    value *= residualExposure;
                }
                // Residual exposure is expected to keep this seam comfortably centred in fp16. Clamp only
                // true outliers/infinities so a pathological light cannot poison a grading application.
                value = Math.clamp(value, -65504.0f, 65504.0f);
                exposedRgba[sample] = Float.floatToFloat16(value);
            }

            RtOpenExrWriter.write(outputPath, displayW, displayH, exposedRgba,
                    new RtOpenExrWriter.Metadata(
                            exposureMetadata.preExposure(),
                            exposureMetadata.residualExposure(),
                            exposureMetadata.absoluteExposure(),
                            exposureMetadata.mode(),
                            exposureMetadata.evScene(),
                            exposureMetadata.evTarget(),
                            exposureMetadata.evApplied(),
                            LOOK.id() + "@" + LOOK.packageVersion(),
                            frameCounter));
            return true;
        } finally {
            readback.destroy();
        }
    }

    private void recordExrReadback(RtContext ctx, VkCommandBuffer cmd, RtBuffer readback, long exposureOffset) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd,
                     "residual-exposure EXR readback")) {
            VkImageMemoryBarrier.Buffer imageBarriers = VkImageMemoryBarrier.calloc(2, stack);
            imageBarriers.get(0).sType$Default()
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT | VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(rrOutput.image);
            imageBarriers.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1).layerCount(1);
            imageBarriers.get(1).sType$Default()
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT | VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(exposure.image().image);
            imageBarriers.get(1).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1).layerCount(1);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, imageBarriers);

            VkBufferImageCopy.Buffer sceneCopy = VkBufferImageCopy.calloc(1, stack);
            sceneCopy.get(0).bufferOffset(0L);
            sceneCopy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            sceneCopy.get(0).imageExtent().set(displayW, displayH, 1);
            VK10.vkCmdCopyImageToBuffer(cmd, rrOutput.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    readback.handle, sceneCopy);

            VkBufferImageCopy.Buffer exposureCopy = VkBufferImageCopy.calloc(1, stack);
            exposureCopy.get(0).bufferOffset(exposureOffset);
            exposureCopy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            exposureCopy.get(0).imageExtent().set(1, 1, 1);
            VK10.vkCmdCopyImageToBuffer(cmd, exposure.image().image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    readback.handle, exposureCopy);

            VkMemoryBarrier.Buffer hostBarrier = VkMemoryBarrier.calloc(1, stack);
            hostBarrier.get(0).sType$Default().srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_HOST_BIT, 0, hostBarrier, null, null);
        }
    }

    /**
     * Whether the current frame must retain vanilla world rendering while RT resource state converges.
     *
     * <p>The composite still runs at the normal seam so it can consume the one-frame epoch gate or observe
     * the newly uploaded atlas. This method only prevents {@code LevelRenderer} from being cancelled before
     * a deliberately transient {@link #composite} return. Such a return is not a renderer failure and must
     * not trip {@code VanillaRenderController}'s permanent safety latch.</p>
     */
    public boolean requiresVanillaWorldFallback() {
        // Pipeline creation publishes a new material epoch and deliberately makes composite() return
        // false once so RtTerrain can apply the matching full clear. Keep vanilla alive for that bring-up
        // frame; otherwise LevelRenderer is cancelled before composite() discovers it must fall back and
        // VanillaRenderController permanently latches the resulting missing replacement frame.
        if (worldPipeline == null || !materialBindingsReady) {
            return true;
        }
        if (materialEpochTraceGate) {
            return true;
        }
        if (RtEntityTextures.maxTextures() > bindlessTextureCapacity) {
            return true;
        }
        if (reloadRebindRequested) {
            long atlas = blockAlbedoAtlasView();
            return atlas == 0L || atlas == boundBlockAlbedoAtlasHandle;
        }
        return false;
    }

    /**
     * Clear the failure latch on an explicit render-state invalidation (F3+A, dimension change) so RT
     * re-arms after a transient error instead of staying on vanilla until restart. A deterministic
     * failure just latches again on the next frame (bounded log spam: one error line per invalidation).
     */
    public void resetFailureLatch() {
        if (failed) {
            failed = false;
            CausticaMod.LOGGER.info("RT failure latch cleared by render-state invalidation; retrying RT");
        }
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

    /** Reset exposure filtering after an explicit render-state invalidation such as F3+A. */
    public void resetExposureHistory() {
        exposure.requestReset();
    }

    /**
     * The frame's forward camera-relative view-projection (jitter-free), exactly what {@code world.rgen}
     * traced with — overlay raster passes ({@code dev.comfyfluffy.caustica.rt.overlay}) reuse it so their content lands
     * pixel-exact on the RT image. Valid after {@code updateMotion} ran this frame; do not mutate.
     */
    public Matrix4fc currentViewProjection() {
        return mvCurProjView;
    }

    /**
     * Reset per-frame present state at the very start of {@link net.minecraft.client.renderer.GameRenderer}
     * render (before any RT work). Critical for menu/no-world frames: {@link #composite()} is only called
     * while a level is rendering ({@code WorldRenderScaler} opens its window in {@code renderLevel}), so on
     * menu frames {@code composite} never runs and {@code hdrWrittenThisFrame} would otherwise keep its stale
     * {@code true} from the last world frame — presenting a black/stale HDR image behind the menu. Clearing it
     * here every frame makes {@link #isHdrPresentActive()} false on menu frames so the SDR convert-present path
     * runs instead.
     */
    public void beginFrame() {
        if (pendingGraphicsUse != null) {
            throw new IllegalStateException("Previous RT graphics use was never completed");
        }
        RtFrameStats.FRAME.beginIfInactive();
        hdrWrittenThisFrame = false;
    }

    /** This frame's completion token, valid until {@link #finishGraphicsUse()} signals it. */
    public RtGpuExecutor.GraphicsUse currentGraphicsUse() {
        RenderSystem.assertOnRenderThread();
        return pendingGraphicsUse;
    }

    /** Signal this RT frame's shared completion token after its final TLAS consumer (world overlay). */
    public void finishGraphicsUse() {
        RtGpuExecutor.GraphicsUse graphicsUse = pendingGraphicsUse;
        if (graphicsUse == null) {
            return;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            throw new IllegalStateException("RT context disappeared before graphics use completed");
        }
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice()
                .createCommandEncoder()).caustica$getBackend();
        ctx.gpuExecutor().endGraphicsUse(encoder, graphicsUse);
        pendingGraphicsUse = null;
    }

    public void endFrame() {
        RtFrameStats.FRAME.end();
    }

    public boolean composite(GpuTexture nativeColor, int width, int height) {
        frameCounter++; // global frame serial used by remaining per-frame/entity rings and diagnostics
        VulkanDiagnostics.setInFlight("graphics-latest", "frame=" + frameCounter + " size=" + width + "x" + height);
        hdrWrittenThisFrame = false; // set true again below once this frame's HDR display image is written
        if (failed) {
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return false;
        }
        ctx.gpuExecutor().throwIfFailed();
        // Count-bounded terrain streaming (dispatch/drain/build kick) runs here once per render frame — before
        // the ready gate below, because it is what MAKES terrain ready during the initial fill.
        try {
            RtTerrain.frame(ctx);
        } catch (Throwable t) {
            ctx.gpuExecutor().throwIfFailed();
            failed = true;
            CausticaMod.LOGGER.error("RT terrain streaming failed; reverting to vanilla path", t);
            return false;
        }
        if (RtTerrain.currentOrNull() == null || !frameCaptured || Minecraft.getInstance().level == null) {
            // No world this frame (incl. after quitting to the title — terrain residency + frameCaptured can
            // linger until an explicit invalidate, which would otherwise present a stale/empty HDR image as a
            // black menu background). Skip RT so the present path falls back to vanilla SDR / the PQ SDR
            // convert path, which shows the menu + panorama correctly.
            return false;
        }
        try {
            if (displayPipeline == null) {
                displayPipeline = RtDisplayPipeline.create(ctx);
            }
            if (bloomPipeline == null) {
                bloomPipeline = RtBloomPipeline.create(ctx);
            }
            if (skyLut == null) {
                // Normally already created by ensureWorld before the pipeline exists at all; this only
                // fires if render() somehow runs before the tick-driven ensureResourcesReady has, which
                // ensureWorld's own binding order otherwise guarantees never happens.
                skyLut = RtSkyLut.create(ctx);
            }
            if (debugPresentPipeline == null) {
                debugPresentPipeline = RtDebugPresentPipeline.create(ctx);
            }
            if (sdrToneLut == null) {
                // SDR view transform is selectable: ACES 2.0 (the renderer's default) or either AgX
                // appearance baked from sobotka/AgX. Only the SDR path branches — AgX has no HDR output
                // transform, so the PQ path below stays ACES 2.0 regardless of this setting.
                sdrToneLut = RtToneLut.load(ctx, CausticaConfig.Rt.Tonemap.sdrLutResource());
            }
            // The mastering target is live, so track it each frame.
            int wantedHdrNits = CausticaConfig.Rt.Hdr.PEAK_NITS.value();
            if (hdrToneLut == null || loadedHdrLutNits != wantedHdrNits) {
                // The AgX HDR LUT has no per-peak variants: its tone curve rolls off to its own white
                // point rather than to the display's, so a 4000 nit bake would be identical to a 500
                // nit one. That is the honest consequence of AgX having no HDR output transform — this
                // is the AgX look in a PQ container, not AgX extended to HDR range.
                String hdrLutName = CausticaConfig.Rt.Tonemap.hdrLutResource(wantedHdrNits);
                RtToneLut newHdrLut = RtToneLut.load(ctx, hdrLutName);
                if (newHdrLut.size != sdrToneLut.size) {
                    // display.comp's lutSize push constant is shared by both LUT samples (see
                    // lutTexCoord()); bake_display_lut.py currently always sizes both the same, but
                    // this would silently misalign one LUT's edge texels if that ever changed.
                    newHdrLut.destroy();
                    throw new IllegalStateException("SDR/HDR tone LUT size mismatch: "
                            + sdrToneLut.size + " vs " + newHdrLut.size);
                }
                if (hdrToneLut != null) {
                    ctx.waitIdle(); // nits-step change is rare; no in-flight frame may sample the old LUT
                    hdrToneLut.destroy();
                }
                hdrToneLut = newHdrLut;
                loadedHdrLutNits = wantedHdrNits;
            }
            // The scene-referred LMT is part of the immutable versioned look package and shared by
            // both SDR and HDR output transforms. It cannot be switched independently from the
            // package's exposure and photometric anchors.
            if (lookLut == null) {
                lookLut = RtToneLut.loadResource(ctx, LOOK.lmtResource());
            }
            // A resource reload re-stitches the block atlas. We've already torn down the world pipeline
            // (onResourceReloadStart) so nothing references the old atlas, but MC's deferred free keeps the
            // old view handle live for a few frames, then swaps in the new atlas (whose GPU upload may lag,
            // leaving the handle 0 transiently). Skip RT — vanilla renders — until the handle becomes a
            // fresh, non-zero value different from what we last bound; only then rebuild against it.
            if (reloadRebindRequested) {
                long atlas = blockAlbedoAtlasView();
                if (atlas == 0L || atlas == boundBlockAlbedoAtlasHandle) {
                    return false;
                }
            }
            ensureOutput(ctx, width, height);
            // ensureOutput's rebuild path (only taken on resize/RR-setting change) already rebinds
            // displayPipeline's descriptor set; this covers the case ensureOutput early-returned but
            // hdrToneLut/lookLut may have been hot-swapped just above; setImages is a no-op if the bound
            // views already match, so this is cheap on every other frame.
            RtToneLut boundLookLut = lookLut;
            displayPipeline.setImages(displayImage.view, rrOutput.view, exposure.image().view, hdrDisplayImage.view,
                    sdrToneLut.view(), sdrToneLut.sampler(), hdrToneLut.view(), hdrToneLut.sampler(),
                    boundLookLut.view(), boundLookLut.sampler(), bloomLevels[0].view, bloomPipeline.sampler());
            bloomPipeline.setImages(rrOutput.view, exposure.image().view, bloomLevels);
            debugPresentPipeline.setImages(displayImage.view, gNormal.view, gAlbedo.view, gDepth.view,
                    gMotion.view, gSpecAlbedo.view, gSpecMotion.view, rrOutput.view, exposure.image().view,
                    exposure.stateBuffer());
            // Cheap idempotent check every frame (not just on resize): if the exposure mode is switched
            // manual -> auto at runtime (video settings), the auto-mode histogram/state/pipeline must be
            // allocated before recordFrame's exposure.record() below needs them, or it throws.
            exposure.ensureResources(ctx);
            refreshPipelineShapeIfNeeded(ctx);
            RtPipeline active = ensureWorld(ctx);
            if (materialEpochTraceGate) {
                materialEpochTraceGate = false;
                return false;
            }
            refreshMaterialBindingsIfNeeded(ctx);
            updateMotion();
            recordFrame(ctx, active, nativeColor);
            if (!loggedActive) {
                loggedActive = true;
                CausticaMod.LOGGER.info("RT composite active (terrain): {}x{}, RT output replaces the world target", width, height);
            }
            return true;
        } catch (Throwable t) {
            ctx.gpuExecutor().throwIfFailed();
            failed = true;
            CausticaMod.LOGGER.error("RT composite failed; reverting to vanilla path", t);
            return false;
        }
    }

    /**
     * Bring the world pipeline + LabPBR atlases up as soon as we're in a world and the block atlas is
     * loaded — <em>before</em> terrain tessellates — so the immutable material snapshot is available to
     * the first worker section. Driven from the client tick ahead of {@link RtTerrain#update}. No-op once
     * the pipeline exists, while a reload rebuild is pending (the reload path rebuilds against the new
     * atlas), or until we're in a world with the atlas ready. The heavy {@code _s}/{@code _n} atlases are
     * deliberately not built at the menu — only once a world is entered.
     */
    public void ensureResourcesReady(RtContext ctx) {
        if (failed || worldPipeline != null || reloadRebindRequested) {
            return;
        }
        if (Minecraft.getInstance().level == null || blockAlbedoAtlasView() == 0L) {
            return;
        }
        try {
            ensureWorld(ctx);
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("RT resource bring-up failed; reverting to vanilla path", t);
        }
    }

    private RtPipeline ensureWorld(RtContext ctx) {
        if (worldPipeline == null) {
            // Must exist before bindWorldTextures below writes the sky-LUT descriptors. This is the
            // earliest possible bind: ensureResourcesReady drives this from the client tick, ahead of the
            // render()/composite path. bindWorldTextures only ever runs again on a
            // resource reload, so a skyLut that is still null on this first call stays permanently unbound
            // and every miss/raygen sky sample reads the pre-vkUpdateDescriptorSets undefined descriptor
            // (VUID-vkCmdTraceRaysKHR-None-08114).
            if (skyLut == null) {
                skyLut = RtSkyLut.create(ctx);
            }
            bindlessTextureCapacity = RtEntityTextures.maxTextures();
            worldPipeline = RtPipeline.create(ctx, new String[]{
                            RtDeviceBringup.worldPrimaryRaygenShader(),
                            RtDeviceBringup.worldRaygenShader()},
                    new String[]{"sky.rmiss.spv", "guide.rmiss.spv"},
                    "closest_hit.rchit.spv", "any_hit.rahit.spv",
                    WorldPushConstantsData.BYTE_SIZE, bindlessTextureCapacity);
            // Cloud noise is a descriptor owned by the world pipeline. Create/bind it only after the pipeline
            // exists; recreating the pipeline simply rebinds the device-lifetime texture and sampler.
            ensureCloudNoise(ctx);
            // Per-frame world data lives in this BDA ring; the pipeline pushes its address and hot fields.
            if (pushRing == null) {
                pushRing = new PushSlot[PUSH_RING];
                for (int i = 0; i < PUSH_RING; i++) {
                    pushRing[i] = new PushSlot(ctx.createBuffer(WORLD_PUSH_SIZE,
                            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "rt world push " + i));
                }
            }
            if (output != null) {
                worldPipeline.setStorageImage(output.view);
                bindGuideImages();
            }
            bindWorldTextures(ctx);
            reloadRebindRequested = false;
        }
        // The TLAS is rebuilt and bound per frame in recordFrame since dynamic entity content animates
        // the instance set every frame.
        return worldPipeline;
    }

    private void refreshPipelineShapeIfNeeded(RtContext ctx) {
        if (worldPipeline == null || reloadRebindRequested) {
            return;
        }
        int desiredBindlessCapacity = RtEntityTextures.maxTextures();
        if (desiredBindlessCapacity <= bindlessTextureCapacity) {
            return;
        }
        ctx.waitIdle();
        worldPipeline.destroy();
        worldPipeline = null;
        bindlessTextureCapacity = 0;
        materialBindingsReady = false;
    }

    /**
     * Resolve + bind every world-pipeline texture: the block atlas (binding 2 + bindless fallback slot 0)
     * and the canonical material page bundles in reserved bindless slots. Shared by first creation and
     * the post-reload rebind. Resets the entity bindless registry, recreates material pages, builds
     * the shared material registry, and invalidates old-epoch geometry before tracing resumes.
     */
    private void bindWorldTextures(RtContext ctx) {
        long sampler = atlasSampler(ctx);
        long atlasView = blockAlbedoAtlasView();
        boundBlockAlbedoAtlasHandle = atlasView; // remember what we bound so a reload can detect the new atlas
        worldPipeline.setBlockAlbedoAtlas(atlasView, sampler);
        // Bindless slot 0 = fallback texture (the block atlas) so an entity whose texture can't be
        // resolved samples something defined rather than an unbound (partially-bound) descriptor.
        RtBlockMaterials.INSTANCE.reset();
        RtMaterialOverrides materialOverrides = RtMaterialOverrides.load();
        RtEmissionSemantics emissionSemantics = RtEmissionSemantics.analyze();
        RtBlockMaterials.INSTANCE.prepareAll(ctx, bindlessTextureCapacity, emissionSemantics, materialOverrides);
        RtEntityTextures.INSTANCE.reset(bindlessTextureCapacity);
        worldPipeline.setEntityAlbedoTexture(0, atlasView, sampler);
        RtBlockMaterials.INSTANCE.bindPages(worldPipeline, sampler);
        RtMaterialRegistry.INSTANCE.rebuild(ctx, RtBlockMaterials.INSTANCE, materialOverrides);
        materialBindingsReady = true;
        // Sky rewrite: bind the vanilla celestials atlas (sun + moon phases) for world.rmiss. The view
        // handle is stable across frames; the shader only samples it inside the sun/moon discs (sky
        // directions), so the block-atlas fallback is never read if the celestials atlas isn't ready.
        long celView = celestialsAtlasView();
        if (worldPipeline.hasSkyAtlas()) {
            worldPipeline.setSkyAtlas(celView != 0L ? celView : atlasView, sampler);
            // Atmosphere LUTs live for the device's lifetime, but the world pipeline's descriptor sets do
            // not (a resource reload rebuilds it), so rebind them alongside the atlas.
            if (skyLut != null) {
                worldPipeline.setSkyLuts(skyLut.skyViewView(), skyLut.transmittanceView(),
                        skyLut.sampler());
            }
        }
        setCelestialUvAtlas(celView);
        // Atlas UVs and material IDs are one resource epoch. Drop old terrain as a unit rather than
        // incrementally displaying old UVs/IDs against the new atlas/table.
        RtTerrain.requestFullClear();
        materialEpochTraceGate = true;
    }

    private void refreshMaterialBindingsIfNeeded(RtContext ctx) {
        if (worldPipeline == null || reloadRebindRequested) {
            return;
        }
        if (!materialBindingsReady) {
            bindWorldTextures(ctx);
        }
    }

    /** Vulkan image-view of the vanilla celestials atlas (sun + moon-phase sprites), or 0 if unavailable. */
    private static long celestialsAtlasView() {
        try {
            GpuTextureView view = Minecraft.getInstance().getAtlasManager()
                    .getAtlasOrThrow(AtlasIds.CELESTIALS).getTextureView();
            return vkImageView(view);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Hooked at the HEAD of {@link net.minecraft.client.Minecraft#reloadResourcePacks()} (mixin). A
     * resource reload re-stitches the block atlas (and reloads entity textures): MC frees the old GPU
     * images via its deferred destruction queue, which refuses while any descriptor set still references
     * them ("in use by VkDescriptorSet" → device lost). So we drain in-flight frames and then <b>destroy
     * the world pipeline outright</b> — dropping every descriptor reference (block atlas binding 2 +
     * bindless set) — so MC can free its textures cleanly. The pipeline is cheap to rebuild (no terrain
     * re-upload); {@code ensureWorld} recreates it on the first world frame after the reload, once the new
     * atlas is ready (gated in {@link #composite}). The new material epoch clears terrain before trace.
     */
    public void onResourceReloadStart() {
        reloadRebindRequested = true;
        materialBindingsReady = false;
        setCelestialUvAtlas(0L);
        RtEntities.INSTANCE.onResourceReload();
        RtContext ctx = RtContext.currentOrNull();
        if (ctx != null) {
            ctx.waitIdle();
            if (worldPipeline != null) {
                worldPipeline.destroy();
                worldPipeline = null;
                bindlessTextureCapacity = 0;
            }
            RtMaterialRegistry.INSTANCE.destroy();
        }
    }

    /** Bind the guide buffers into the world pipeline's extra storage-image slots. */
    private void bindGuideImages() {
        if (worldPipeline == null || gNormal == null) {
            return;
        }
        worldPipeline.setExtraStorageImage(0, gNormal.view);
        worldPipeline.setExtraStorageImage(1, gAlbedo.view);
        worldPipeline.setExtraStorageImage(2, gDepth.view);
        worldPipeline.setExtraStorageImage(3, gMotion.view);
        worldPipeline.setExtraStorageImage(4, gSpecAlbedo.view);
        worldPipeline.setExtraStorageImage(5, gSpecMotion.view);
        worldPipeline.setReservoirImages(reservoirA.view, reservoirB.view);
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
        if (gSpecMotion != null) {
            gSpecMotion.destroy();
            gSpecMotion = null;
        }
        if (reservoirA != null) {
            reservoirA.destroy();
            reservoirA = null;
        }
        if (reservoirB != null) {
            reservoirB.destroy();
            reservoirB = null;
        }
        if (rrOutput != null) {
            rrOutput.destroy();
            rrOutput = null;
        }
    }

    private void ensureOutput(RtContext ctx, int width, int height) {
        // Debug presentation is downstream of the ordinary frame graph and must not change the image
        // being inspected. In particular, toggling it must not rebuild at native resolution or disable
        // the RR path whose render-resolution guide inputs the debug pass visualizes.
        boolean rrEnabled = RtDlssRr.enabled();
        int rrQuality = rrEnabled ? RtDlssRr.quality() : Integer.MIN_VALUE;
        if (output != null && continuationQueue != null
                && displayImage != null && hdrDisplayImage != null && rrOutput != null
                && bloomLevels.length > 0 && exposure.ready()
                && displayW == width && displayH == height
                && renderSizeRrEnabled == rrEnabled && renderSizeRrQuality == rrQuality) {
            return;
        }
        ctx.waitIdle(); // resize is rare; no in-flight frame may use the old image/descriptor
        if (displayImage != null) {
            displayImage.destroy();
        }
        if (hdrDisplayImage != null) {
            hdrDisplayImage.destroy();
        }
        destroyBloomLevels();
        if (output != null) {
            output.destroy();
        }
        if (continuationQueue != null) {
            continuationQueue.destroy();
            continuationQueue = null;
        }
        destroyGuideImages();

        displayW = width;
        displayH = height;
        // The path tracer + its guide buffers run at render res; DLSS-RR (or a fallback blit) upscales
        // to display res. With RR off there is no reconstruction pass, so trace at 1:1 for a faithful reference.
        // With RR on, ask NGX what render resolution its chosen quality mode actually expects rather
        // than assuming a fixed ratio: different quality modes (and driver versions) use different
        // ratios, and DLSSD's own optimal-settings query is the source of truth for what it will accept.
        int[] optimal = rrEnabled ? RtDlssRr.INSTANCE.queryOptimalRenderSize(width, height) : null;
        renderW = optimal != null ? optimal[0] : width;
        renderH = optimal != null ? optimal[1] : height;
        renderSizeRrEnabled = rrEnabled;
        renderSizeRrQuality = rrQuality;

        // RT traces and DLSS-RR reconstruct scene-linear ACEScg in an HDR R16G16B16A16_SFLOAT target,
        // so radiance > 1 and wide-gamut colour survive to the display seam. displayImage stays
        // R8G8B8A8 to match the main target it is copied into
        // (vkCmdCopyImage requires texel-size-compatible formats).
        output = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "trace color " + renderW + "x" + renderH);
        long pixelRecords = Math.multiplyExact((long) renderW, (long) renderH);
        long continuationBytes = Math.multiplyExact(
                Math.multiplyExact(pixelRecords, 2L), PATH_RECORD_BYTES);
        continuationQueue = ctx.createBuffer(continuationBytes,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false,
                "path continuation queue " + renderW + "x" + renderH + "x2");
        displayImage = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM, "RT display image " + width + "x" + height);
        // PQ-encoded ([0,1], ST.2084) HDR display image, written in parallel by display.comp when HDR mode is active.
        hdrDisplayImage = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "RT HDR display image " + width + "x" + height);
        // Bloom pyramid. Level 0 is half display resolution (the prefilter's 13-tap already covers a 5x5
        // display-pixel footprint, so nothing is lost by starting there); each further level halves again
        // until the look package's level count or the smallest useful size is reached.
        int bloomWidth = Math.max(1, (width + 1) / 2);
        int bloomHeight = Math.max(1, (height + 1) / 2);
        int bloomLevelCount = RtBloomPipeline.levelsFor(bloomWidth, bloomHeight, LOOK.bloom().levels());
        bloomLevels = new RtImage[bloomLevelCount];
        for (int level = 0; level < bloomLevelCount; level++) {
            bloomLevels[level] = ctx.createStorageImage(bloomWidth, bloomHeight,
                    VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "RT bloom level " + level + " " + bloomWidth + "x" + bloomHeight);
            bloomWidth = Math.max(1, bloomWidth / 2);
            bloomHeight = Math.max(1, bloomHeight / 2);
        }
        // Guide buffers match the trace (render) resolution; DLSS-RR consumes them at render res.
        gNormal = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide normal roughness " + renderW + "x" + renderH);
        gAlbedo = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide diffuse albedo " + renderW + "x" + renderH);
        gDepth = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R32_SFLOAT, "guide linear depth " + renderW + "x" + renderH);
        gMotion = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16_SFLOAT, "guide motion " + renderW + "x" + renderH);
        gSpecAlbedo = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide specular albedo " + renderW + "x" + renderH);
        gSpecMotion = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16_SFLOAT, "guide specular motion " + renderW + "x" + renderH);
        // ReSTIR reservoirs, at RENDER resolution and double width (two texels per pixel). rgba32f
        // because the stored emitter position is a rebased world coordinate: a half there quantises
        // light positions into visible banding across a large room. Recreated with the guides, so a
        // resolution or DLSS-quality change reallocates them and invalidates history along with it.
        reservoirA = ctx.createStorageImage(renderW * 2, renderH, VK10.VK_FORMAT_R32G32B32A32_SFLOAT,
                "ReSTIR reservoir A " + renderW + "x" + renderH);
        reservoirB = ctx.createStorageImage(renderW * 2, renderH, VK10.VK_FORMAT_R32G32B32A32_SFLOAT,
                "ReSTIR reservoir B " + renderW + "x" + renderH);
        reservoirHistoryValid = false;
        reservoirParity = 0;
        // Zero both images at creation. The history-valid flag guards the frame AFTER a reallocation,
        // but it is a whole-frame switch and reservoir writes are per-pixel: a pixel the raygen never
        // reaches — sky, or any pixel whose primary ray found no emitter-lit surface — is never written,
        // so once the flag flips on, that pixel's neighbours read whatever the allocator left in memory.
        // packedValid() rejects M<=0, and zeroed memory gives exactly that; undefined memory does not.
        ctx.submitSync(cmd -> {
            try (MemoryStack clearStack = MemoryStack.stackPush()) {
                VkClearColorValue zero = VkClearColorValue.calloc(clearStack);
                VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, clearStack);
                range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                VK10.vkCmdClearColorImage(cmd, reservoirA.image, VK10.VK_IMAGE_LAYOUT_GENERAL, zero, range);
                VK10.vkCmdClearColorImage(cmd, reservoirB.image, VK10.VK_IMAGE_LAYOUT_GENERAL, zero, range);
            }
        });
        // Display-res RT image the display mapper reads. Always present (DLSS-RR target, or blit-upscale fallback).
        rrOutput = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "DLSS-RR output " + width + "x" + height);
        exposure.ensureResources(ctx);

        mvHasPrev = false; // recreated images -> first MV frame is zero
        waterWaveTimeValid = false;
        if (worldPipeline != null) {
            worldPipeline.setStorageImage(output.view);
            bindGuideImages();
        }
        RtToneLut boundLookLut = lookLut;
        displayPipeline.setImages(displayImage.view, rrOutput.view, exposure.image().view, hdrDisplayImage.view,
                sdrToneLut.view(), sdrToneLut.sampler(), hdrToneLut.view(), hdrToneLut.sampler(),
                boundLookLut.view(), boundLookLut.sampler(), bloomLevels[0].view, bloomPipeline.sampler());
        bloomPipeline.setImages(rrOutput.view, exposure.image().view, bloomLevels);
        debugPresentPipeline.setImages(displayImage.view, gNormal.view, gAlbedo.view, gDepth.view,
                gMotion.view, gSpecAlbedo.view, gSpecMotion.view, rrOutput.view, exposure.image().view,
                exposure.stateBuffer());
    }

    private void destroyBloomLevels() {
        for (RtImage level : bloomLevels) {
            if (level != null) {
                level.destroy();
            }
        }
        bloomLevels = new RtImage[0];
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

    private void recordFrame(RtContext ctx, RtPipeline active, GpuTexture nativeColor) {
        long dstImage = vkImage(nativeColor);
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).caustica$getBackend();
        RtGpuExecutor gpuExecutor = ctx.gpuExecutor();
        // Reserve the graphics-use value that guards this frame's reusable TLAS and entity resources.
        RtGpuExecutor.GraphicsUse graphicsUse = gpuExecutor.beginGraphicsUse(encoder);
        RtGpuExecutor.GraphicsUseWaiter graphicsUseWaiter = gpuExecutor.graphicsUseWaiter();
        // Reuse a completed readback slot, then latch one pre-exposure value for both raygen and resolve.
        // This belongs after the timeline snapshot and before any world push data is written.
        exposure.beginFrame(graphicsUseWaiter);
        pendingGraphicsUse = graphicsUse;
        RtEntities.FrameEntities frameEntities = null;
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_COMMAND_BUFFER, cmd.address(), "composite command buffer");
        int debugView = debugView();
        RtTerrain terrain = RtTerrain.currentOrNull();
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope frameLabel = RtDebugLabels.scope(ctx, cmd, "composite frame")) {
            // RR drives the upscale: trace + jitter at render res, DLSS-RR denoises+upscales to display.
            // A debug view observes this ordinary path; it never changes jitter or disables RR.
            boolean rrPath = RtDlssRr.enabled();
            float jitterX = 0f;
            float jitterY = 0f;
            if (rrPath) {
                CausticaJitter.INSTANCE.prepare(renderW, renderH, displayW);
                jitterX = CausticaJitter.INSTANCE.jitterPixelsX() * jitterSignX();
                jitterY = CausticaJitter.INSTANCE.jitterPixelsY() * jitterSignY();
            }

            boolean rrDone = false;
            // Select the next BDA ring slot; the generated WorldPushData serializer fills it once all
            // frame-derived values (including entity addresses and block-breaking entries) are known.
            pushSlot = (pushSlot + 1) % PUSH_RING;
            PushSlot selectedPushSlot = pushRing[pushSlot];
            graphicsUseWaiter.await(selectedPushSlot.graphicsUse);
            selectedPushSlot.graphicsUse.mark(graphicsUse);
            RtBuffer pushBuf = selectedPushSlot.buffer;
            ByteBuffer push = MemoryUtil.memByteBuffer(pushBuf.mapped, WORLD_PUSH_SIZE);
            frameInvViewProj.set(frameProjection).mul(frameViewRotation).invert();
            // flags: camera-in-water (so the path tracer starts in the water medium when the eye is
            // submerged, fixing the air→water first-segment orientation) and animated water normals.
            // Bit 1 remains unused to avoid conflicting with stale external readers.
            int flags = 0;
            var level = Minecraft.getInstance().level;
            if (level != null) {
                cameraBlockPos.set(Mth.floor(camX), Mth.floor(camY), Mth.floor(camZ));
                // Height-aware, mirroring vanilla's own Camera.getFluidInCamera(): a plain block-granular
                // test wrongly flags the eye submerged anywhere in a water column's top block, even well
                // above its actual surface (shallow/flowing water, or standing with your head just over a
                // source block).
                FluidState fs = level.getFluidState(cameraBlockPos);
                if (fs.is(FluidTags.WATER) && camY < cameraBlockPos.getY() + fs.getHeight(level, cameraBlockPos)) {
                    flags |= 0b01;
                }
            }
            if (waterWaves()) {
                flags |= 0b10000; // animated water wave normals
            }

            // Water parameters: camera-biome tint plus wrapped animation time. Per-water-body tint
            // comes from the primitive; this is the fallback for a camera already inside the medium.
            float wtr = 0.25f, wtg = 0.46f, wtb = 0.9f; // neutral ocean-ish default if no level/biome
            if (level != null) {
                int wc = BiomeColors.getAverageWaterColor(level, cameraBlockPos);
                wtr = ((wc >> 16) & 0xFF) / 255f;
                wtg = ((wc >> 8) & 0xFF) / 255f;
                wtb = (wc & 0xFF) / 255f;
            }
            float waterWaveTime = (float) (System.nanoTime() / 1.0e9 % 3600.0);
            float waterWaveDelta = waterWaveTime - previousWaterWaveTime;
            // A first frame, long pause, or one-hour phase wrap has no adjacent wave frame to reproject.
            // Use the current phase so the reflection MV is neutral instead of manufacturing a huge jump.
            float priorWaterWaveTime = waterWaveTimeValid
                    && waterWaveDelta >= 0f && waterWaveDelta <= 0.25f
                    ? previousWaterWaveTime : waterWaveTime;
            previousWaterWaveTime = waterWaveTime;
            waterWaveTimeValid = true;
            Float4 waterParams = linearAcesCgFromSrgb(wtr, wtg, wtb, waterWaveTime);
            // Wave-domain anchor: the terrain rebase origin reduced mod 4096 (kept small for shader
            // float precision). hitPos.xz (rebased) + anchor reconstructs a world-pinned coordinate, so the
            // ripple pattern stays fixed in the world as the player moves and the rebase origin shifts.
            Float4 waterAnchor = new Float4(terrain.blockX & WATER_ANCHOR_MASK,
                    terrain.blockZ & WATER_ANCHOR_MASK, priorWaterWaveTime, 0f);

            // Rebuild the TLAS this frame from static section instances merged with dynamic entity
            // instances, bind it into the pipeline's descriptor ring, record the build, then barrier so
            // the trace sees the finished TLAS. Section BLASes are already built (async, by RtTerrain);
            // only the cheap instance-level TLAS is rebuilt per frame. Retired terrain geometry/table
            // generations are reclaimed by graphics-timeline completion.
            // Entity BLASes are built inline below and merged into the per-frame TLAS. geomTableAddr
            // feeds the hit shader entity path (per-prim normal/tint) and motion vectors.
            RtEntities.FrameEntities fe = RtEntities.INSTANCE.beginFrame(ctx, terrain.staticInstances(),
                    terrain.blockX, terrain.blockY, terrain.blockZ, camX, camY, camZ, frameProjection, frameViewRotation);
            frameEntities = fe;
            // Block-breaking overlay: resolves each destroy-stage RenderType's texture into the
            // SAME bindless entity-texture array (destroy_stage_N.png is a standalone Sampler0 texture,
            // not a block-atlas sprite — see ModelBakery.BREAKING_LOCATIONS/DESTROY_TYPES), so any newly
            // resolved slot rides along with the uploadPending() call right below.
            BreakEntry[] breaking = breakingEntries(terrain);
            SkyPush sky = skyPush();
            // Height fog. The reference plane is authored in world Y but the shader works in rebased
            // coordinates, so the terrain origin is subtracted here rather than pushing the origin into
            // the shader: it keeps the value small enough for fp32 no matter how far the player walks.
            // falloff is 1/scale-height; the clamp only guards a hand-edited config, since the setting
            // itself is already bounded away from zero.
            // Weather and biome climate. Both are read once per frame from the camera's position: fog and
            // a cloud deck are whole-sky properties, so sampling them per-pixel would buy nothing but a
            // seam where two biomes meet.
            //
            // Vanilla interpolates rain and thunder over ~½ second, and getRainLevel already returns the
            // smoothed value, so weather ramps in and out without any easing of ours. Thunder is layered
            // on top of rain rather than replacing it — vanilla raises both during a storm.
            float rainLevel = 0f;
            float thunderLevel = 0f;
            // Biome climate stands in for the per-biome sky colours that 26.2 no longer carries:
            // BiomeSpecialEffects was reduced to water and foliage tints, so temperature and whether the
            // biome has precipitation are the only atmosphere-relevant signals vanilla still exposes.
            // Cold and wet reads as hazy, hot and dry as clear — the mapping a desert-vs-taiga eye test
            // would produce anyway.
            float biomeHaze = 1f;
            float wetness = 0f;
            if (level != null) {
                rainLevel = Mth.clamp(level.getRainLevel(1.0f), 0f, 1f);
                thunderLevel = Mth.clamp(level.getThunderLevel(1.0f), 0f, 1f);
                // A negative override means "use the world". Applied after reading rather than instead
                // of it, so turning the override off returns to live weather without a reload.
                float rainOverride = CausticaConfig.Rt.Weather.RAIN_OVERRIDE.value();
                if (rainOverride >= 0f) {
                    rainLevel = rainOverride;
                }
                float thunderOverride = CausticaConfig.Rt.Weather.THUNDER_OVERRIDE.value();
                if (thunderOverride >= 0f) {
                    thunderLevel = thunderOverride;
                }
                var biome = level.getBiome(cameraBlockPos).value();
                // getBaseTemperature is roughly 0 (snowy) to 2 (desert/nether) in vanilla data.
                float temperature = Mth.clamp(biome.getBaseTemperature(), 0f, 2f);
                float climate = (1f - temperature * 0.5f) * (biome.hasPrecipitation() ? 1f : 0.35f);
                float biomeResponse = CausticaConfig.Rt.Fog.BIOME_RESPONSE.value();
                biomeHaze = Mth.lerp(biomeResponse, 1f, 0.4f + climate * 1.2f);
                // Wetness needs actual liquid water falling. A biome with no precipitation (desert) gets
                // nothing, and one cold enough for snow gets nothing either — snow accumulates, it does
                // not soak in, and a glossy snowfield would be plainly wrong. The 0.15 cutoff is where
                // vanilla's own precipitation type flips.
                boolean rainsHere = biome.hasPrecipitation() && biome.getBaseTemperature() > 0.15f;
                wetness = rainsHere ? rainLevel * CausticaConfig.Rt.Clouds.WETNESS.value() : 0f;
            }
            // Rain multiplies fog density rather than adding to it, so a configured density of 0 stays
            // 0 in a downpour. Weather driving a feature the user switched off would be a surprise.
            float fogWeather = 1f + rainLevel * CausticaConfig.Rt.Fog.WEATHER_RESPONSE.value();
            float fogScaleHeight = Math.max(CausticaConfig.Rt.Fog.SCALE_HEIGHT.value(), 1.0e-2f);
            Float4 fog = new Float4(
                    CausticaConfig.Rt.Fog.DENSITY.value() * fogWeather * biomeHaze,
                    1.0f / fogScaleHeight,
                    CausticaConfig.Rt.Fog.HEIGHT.value() - terrain.blockY,
                    CausticaConfig.Rt.Fog.ALBEDO.value());

            // Cloud deck. The wind offset is accumulated on the CPU from wall time rather than derived
            // in the shader from frameIndex, so the deck drifts at a fixed blocks-per-second regardless
            // of frame rate. Folding the terrain origin (mod 4096, same trick as the water wave anchor)
            // into the offset keeps the noise domain pinned to the world across a terrain rebase — without
            // it the whole sky would visibly slide sideways every time the player crossed a rebase
            // boundary. The mask keeps the value small enough that fp32 still resolves the detail octave.
            float cloudWind = CausticaConfig.Rt.Clouds.WIND_SPEED.value() * waterWaveTime;
            // Storm response. Coverage closes the remaining gap toward overcast rather than adding a
            // fixed amount, so a sky that is already at 0.8 does not overshoot past 1 and clip flat.
            // Density rises and ambient falls together: that pairing — thicker and less sky bouncing
            // around inside — is what makes a storm deck read as heavy rather than merely large.
            float cloudWeather = CausticaConfig.Rt.Clouds.WEATHER_RESPONSE.value()
                    * Math.min(rainLevel + thunderLevel * 0.5f, 1f);
            // Cloud colour and height come from the same attribute system. CLOUD_HEIGHT is a world Y,
            // so it is rebased alongside everything else below.
            float cloudR = 1f, cloudG = 1f, cloudB = 1f;
            float vanillaCloudHeight = Float.NaN;
            if (level != null) {
                var reader = level.environmentAttributes();
                int cloudColor = reader.getValue(EnvironmentAttributes.CLOUD_COLOR, cameraBlockPos);
                cloudR = srgbToLinear(((cloudColor >> 16) & 0xFF) / 255f);
                cloudG = srgbToLinear(((cloudColor >> 8) & 0xFF) / 255f);
                cloudB = srgbToLinear((cloudColor & 0xFF) / 255f);
                vanillaCloudHeight = reader.getValue(EnvironmentAttributes.CLOUD_HEIGHT, cameraBlockPos);
            }
            Float4 dimCloud = new Float4(cloudR, cloudG, cloudB,
                    CausticaConfig.Rt.Clouds.VANILLA_TINT.value());
            Float4 cloud3 = new Float4(CausticaConfig.Rt.Clouds.QUALITY.value(), 0f, 0f, 0f);
            Float4 fog2 = new Float4(CausticaConfig.Rt.Fog.DEBUG.value(), 0f, 0f, 0f);
            // Vanilla's cloud height wins when enabled and actually reported — a dimension with no cloud
            // layer leaves the attribute absent, in which case the configured altitude is the only
            // sensible answer rather than dropping the deck to zero.
            float cloudDeckBase = CausticaConfig.Rt.Clouds.VANILLA_HEIGHT.value()
                    && !Float.isNaN(vanillaCloudHeight)
                    ? vanillaCloudHeight
                    : CausticaConfig.Rt.Clouds.ALTITUDE.value();
            float cloudCoverage = CausticaConfig.Rt.Clouds.COVERAGE.value();
            cloudCoverage += (1f - cloudCoverage) * cloudWeather * 0.85f;
            Float4 cloud0 = new Float4(
                    cloudCoverage,
                    CausticaConfig.Rt.Clouds.DENSITY.value() * (1f + cloudWeather * 1.5f),
                    cloudDeckBase - terrain.blockY,
                    CausticaConfig.Rt.Clouds.THICKNESS.value());
            Float4 cloud1 = new Float4(
                    (terrain.blockX & WATER_ANCHOR_MASK) + cloudWind * 0.8f,
                    (terrain.blockZ & WATER_ANCHOR_MASK) + cloudWind * 0.6f,
                    CausticaConfig.Rt.Clouds.DETAIL.value(),
                    CausticaConfig.Rt.Clouds.AMBIENT.value() * (1f - cloudWeather * 0.55f));
            // Ground shadowing. The floor is lowered as the storm builds: a heavier deck really does pass
            // less diffuse light, and holding the clear-sky floor through a thunderstorm is what makes an
            // overcast world read as merely dimmed rather than actually overcast.
            Float4 cloud2 = new Float4(
                    CausticaConfig.Rt.Clouds.SHADOW_STRENGTH.value(),
                    CausticaConfig.Rt.Clouds.SHADOW_FLOOR.value() * (1f - cloudWeather * 0.5f),
                    wetness,
                    CausticaConfig.Rt.Clouds.FEATURE_SIZE.value());
            // Vanilla environment colours. 26.2 moved per-biome and per-dimension colour out of
            // BiomeSpecialEffects into the EnvironmentAttributes system, which samples and interpolates
            // across biome boundaries itself — so this is vanilla's own blended value at the camera,
            // not a nearest-biome lookup that would pop at a border.
            //
            // Sampled once per frame at the camera. Sky and fog colour are whole-view properties; a
            // per-pixel lookup would buy nothing and cost a chunk-lookup per ray.
            int skyboxMode = 0;
            float skyR = 0f, skyG = 0f, skyB = 0f;
            float fogR = 0f, fogG = 0f, fogB = 0f;
            float fogTintStrength = 0f;
            if (level != null) {
                var reader = level.environmentAttributes();
                int skyColor = reader.getValue(EnvironmentAttributes.SKY_COLOR, cameraBlockPos);
                int fogColor = reader.getValue(EnvironmentAttributes.FOG_COLOR, cameraBlockPos);
                skyR = srgbToLinear(((skyColor >> 16) & 0xFF) / 255f);
                skyG = srgbToLinear(((skyColor >> 8) & 0xFF) / 255f);
                skyB = srgbToLinear((skyColor & 0xFF) / 255f);
                fogR = srgbToLinear(((fogColor >> 16) & 0xFF) / 255f);
                fogG = srgbToLinear(((fogColor >> 8) & 0xFF) / 255f);
                fogB = srgbToLinear((fogColor & 0xFF) / 255f);
                skyboxMode = switch (level.dimensionType().skybox()) {
                    case OVERWORLD -> 0;
                    case END -> 1;
                    case NONE -> 2;
                };
                // In the Overworld the physical atmosphere is the truth and vanilla's fog colour is a
                // light artistic tint on top of it. Elsewhere there is no atmosphere to defer to, so
                // vanilla's colour becomes the whole answer.
                fogTintStrength = skyboxMode == 0
                        ? CausticaConfig.Rt.Fog.BIOME_RESPONSE.value() * 0.5f
                        : 1.0f;
            }
            Float4 dimSky = new Float4(skyR, skyG, skyB, skyboxMode);
            Float4 dimFog = new Float4(fogR, fogG, fogB, fogTintStrength);
            // ReSTIR temporal reuse. History is only meaningful if it describes this world in this
            // rebased space: a dimension change reuses the same images with positions from a different
            // world entirely, which would light the Nether with the Overworld's torches until the
            // reservoirs churned over. A resize already clears the flag when the images are recreated.
            Object currentDimension = level == null ? null : level.dimension();
            if (!java.util.Objects.equals(currentDimension, reservoirDimension)) {
                reservoirDimension = currentDimension;
                reservoirHistoryValid = false;
            }
            reservoirParity ^= 1;
            float restirStrength = CausticaConfig.Rt.Restir.TEMPORAL.value();
            Float4 restir = new Float4(
                    reservoirHistoryValid ? restirStrength : 0f,
                    reservoirParity,
                    CausticaConfig.Rt.Restir.SPATIAL_TAPS.value(),
                    CausticaConfig.Rt.Restir.SPATIAL_RADIUS.value());
            // Valid from the frame after the first one that wrote anything: the destination image this
            // frame becomes history next frame.
            if (restirStrength > 0f) {
                reservoirHistoryValid = true;
            } else {
                reservoirHistoryValid = false;
            }
            Float4 pom = new Float4(
                    CausticaConfig.Rt.Pom.DEPTH.value(),
                    CausticaConfig.Rt.Pom.QUALITY.value(),
                    CausticaConfig.Rt.Pom.FADE_LOD.value(),
                    0f);
            new WorldPushData(
                    frameInvViewProj,
                    new Float3((float) (camX - terrain.blockX), (float) (camY - terrain.blockY),
                            (float) (camZ - terrain.blockZ)),
                    (int) frameCounter,
                    mvPushMatrix,
                    new Float3(mvCamDeltaX, mvCamDeltaY, mvCamDeltaZ),
                    spp(),
                    new Float2(jitterX, jitterY),
                    flags,
                    maxBounces(),
                    sky.celestial(),
                    sky.look0(),
                    sky.look1(),
                    sky.look2(),
                    sky.look3(),
                    sky.sunUv(),
                    sky.moonUv(),
                    waterParams,
                    waterAnchor,
                    mvCurProjView,
                    breaking.length,
                    breaking,
                    // RIS emitter NEE: candidate count (0 = emitter NEE off; the shader also requires
                    // lightCount > 0, so an empty buffer leaves only direct-hit emission). The light buffer
                    // device addresses themselves are pc.light*Addr — every 64-bit address lives in the
                    // push-constant block now, not here.
                    new Float4(terrain.lightRebaseOffsetX(), terrain.lightRebaseOffsetY(),
                            terrain.lightRebaseOffsetZ(), terrain.lightInvGlobalPowerSum()),
                    new Float4(terrain.lightGridOriginX(), terrain.lightGridOriginY(), terrain.lightGridOriginZ(), 16f),
                    new Int4(terrain.lightGridDimX(), terrain.lightGridDimY(), terrain.lightGridDimZ(), 0),
                    terrain.lightCount(),
                    CausticaConfig.Rt.Lights.RIS_CANDIDATES.value(),
                    // Must be the SAME value the exposure resolve divides out this frame (it reads it
                    // from the same RtExposure accessor), or the two stop cancelling.
                    exposure.preExposure(),
                    fog,
                    cloud0,
                    cloud1,
                    cloud2,
                    pom,
                    dimSky,
                    dimFog,
                    dimCloud,
                    cloud3,
                    fog2,
                    restir
            ).write(push);
            pushBuf.flush(0L, WORLD_PUSH_SIZE);
            // Upload any entity textures registered this frame into the bindless set before the trace.
            RtEntityTextures.INSTANCE.uploadPending(active, atlasSampler(ctx));
            // Build the entity BLAS, the TLAS that references it and the terrain BLAS, then the trace.
            // Barriers separate each stage; the graphics-use timeline guards resource reuse.
            if (!fe.blas().isEmpty()) {
                try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("entity.blasRecord")) {
                    RtAccel.recordBlasBuilds(ctx, cmd, fe.blas());
                }
                VulkanCommandEncoder.memoryBarrier(cmd, stack); // entity BLAS writes visible to the TLAS build
            }
            RtAccel.PreparedTlas frameTlas;
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.prepareTlas")) {
                frameTlas = RtAccel.prepareTlas(ctx, fe.baseInstances(), fe.dynamicInstances(), tlasRing,
                        graphicsUse);
            }
            active.setTlas(frameTlas.accel.handle, graphicsUse, graphicsUseWaiter);
            currentTlasHandle = frameTlas.accel.handle;
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.recordTlas")) {
                RtAccel.recordTlasBuild(ctx, cmd, frameTlas);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // TLAS build visible to the trace

            // Push the BDA ring slot's address plus the small hot subset used directly by the shaders.
            // Every 64-bit device address the trace needs lives here, not behind worldPushAddr: the
            // section/entity/material tables are read from world.rahit/world.rchit, which never load
            // WorldPush at all, and the RIS light buffers are read from world.rgen's hot inner loop, so
            // none of them should cost an extra BDA dereference to find.
            ByteBuffer pushConstants = stack.malloc(WorldPushConstantsData.BYTE_SIZE);
            new WorldPushConstantsData(pushBuf.deviceAddress, terrain.tableAddress(), fe.geomTableAddr(),
                    RtMaterialRegistry.INSTANCE.tableAddress(),
                    terrain.lightBufferAddress(), terrain.lightAliasBufferAddress(),
                    terrain.lightLocalAliasBufferAddress(), terrain.lightGridCellBufferAddress(),
                    terrain.lightGridSpanBufferAddress(), continuationQueue.deviceAddress,
                    (int) frameCounter).write(pushConstants);
            // Sky LUTs, from the same WorldPush slot the trace is about to read: the sky the LUT holds and
            // the sky the frame shades are built from one set of angles, not two. Recorded here (after the
            // push flush, before the trace) so the miss shader's very first fetch sees this frame's dome.
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.skyLut")) {
                skyLut.record(cmd, pushBuf.deviceAddress);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // sky LUT writes visible to raygen/miss

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world primary trace");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.tracePrimary")) {
                active.trace(cmd, renderW, renderH, pushConstants, 0);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // continuation/guide writes visible to pass B
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world indirect trace");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.traceIndirect")) {
                active.trace(cmd, renderW, renderH, pushConstants, 1);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // RT writes visible to DLSS reads
            // DLSS-RR denoise + upscale. The RT pass wrote noisy color (render res) + guides;
            // RR reads them and writes the display-res denoised result straight into rrOutput.
            if (rrPath && RtDlssRr.INSTANCE.ensureFeature(cmd.address(), renderW, renderH, displayW, displayH)) {
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "DLSS-RR evaluate");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.dlssRr")) {
                    rrDone = RtDlssRr.INSTANCE.evaluate(cmd.address(), output, gDepth, gMotion, gAlbedo,
                            gSpecAlbedo, gNormal, gSpecMotion, rrOutput, renderW, renderH, displayW, displayH,
                            -jitterX, -jitterY, frameViewRotation, frameProjection);
                }
            }

            // When DLSS-RR did not produce the display-res image (disabled or a runtime failure), bring
            // the render-res trace up to display res with a linear blit so the display mapper and
            // downstream debug pass always have a valid display-res scene image. With RR off
            // render == display, so this is a 1:1 copy.
            if (!rrDone) {
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "fallback upscale");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.upscale")) {
                    blitUpscale(cmd, stack, output, rrOutput);
                }
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // rrOutput visible to exposure histogram

            // Auto-exposure meters rrOutput (the post-RR, denoised/converged image), not the raw
            // pre-RR trace: RR has no notion of exposure (DLSS-RR Integration Guide §3.7 — ignore
            // exposure/auto-exposure/sharpness entirely for RR), so this is purely our own metering
            // choice, independent of RR's pipeline placement. Metering the noisy pre-RR buffer made
            // the histogram's log-luminance average biased by Monte-Carlo noise (Jensen's inequality
            // on the concave log()), so the computed exposure drifted with SPP; rrOutput is stable
            // regardless of SPP, keeping exposure consistent.
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "exposure");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.exposure")) {
                exposure.record(ctx, cmd, stack, rrOutput, gDepth, gAlbedo);
                exposure.recordStateReadback(cmd, stack);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // exposure image visible to the display mapper

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "bloom");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.bloom")) {
                RtLookPackage.Bloom bloom = LOOK.bloom();
                // The tent radius is in source texels, so it needs no resolution scaling: the pyramid's
                // reach is set by its level count, and each level's texel already scales with the frame.
                bloomPipeline.dispatch(cmd, bloomLevels,
                        bloom.thresholdSceneLinear(), bloom.softKneeFraction(), bloom.radius());
            }

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "map RT to display");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.displayMap")) {
                displayPipeline.dispatch(cmd, displayW, displayH, CausticaConfig.Rt.Hdr.enabled(),
                        sdrToneLut.size, CausticaConfig.Rt.Tonemap.GAMMA.value(), loadedHdrLutNits,
                        true, lookLut.size, LOOK.bloom().strength() / bloomLevels.length,
                        CausticaConfig.Rt.Grade.ENABLED.value(),
                        CausticaConfig.Rt.Grade.SATURATION.value(),
                        CausticaConfig.Rt.Grade.CONTRAST.value(),
                        CausticaConfig.Rt.Grade.GAIN.value(),
                        CausticaConfig.Rt.Grade.HIGHLIGHT_SATURATION.value(),
                        CausticaConfig.Rt.Grade.HIGHLIGHT_GAIN.value(),
                        CausticaConfig.Rt.Grade.HIGHLIGHTS_MIN.value(),
                        CausticaConfig.Rt.Grade.SHARPNESS.value());
            }
            hdrWrittenThisFrame = CausticaConfig.Rt.Hdr.enabled();
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // display output visible to debug composite

            if (debugView != 0) {
                // Debug content is composited only after the real scene has completed trace, RR/fallback,
                // exposure, and display mapping. It therefore observes the renderer without perturbing
                // exposure history or feeding literal diagnostic colors through ACES. Debug presentation
                // remains SDR for now; a PQ swapchain uses the existing SDR->PQ conversion path.
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "debug present");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.debugPresent")) {
                    debugPresentPipeline.dispatch(cmd, displayW, displayH, debugView,
                            CausticaConfig.Rt.Exposure.CENTER_WEIGHT_SIGMA.value(),
                            CausticaConfig.Rt.Exposure.CENTER_WEIGHT_FLOOR.value());
                }
                hdrWrittenThisFrame = false;
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "copy composite to main target");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.copyOutput")) {
                VK10.vkCmdCopyImage(cmd, displayImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                        dstImage, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, displayW, displayH));
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(rt composite) failed");
        }
        encoder.execute(cmd); // deferred into the frame's submission — correct for per-frame work
        // Do not attach a merely reserved token: failed recording may never signal it. Once execute succeeds,
        // every owner in this frame's manifest is protected through the final overlay consumer.
        RtEntities.INSTANCE.markGraphicsUse(frameEntities, graphicsUse);
        exposure.markStateReadbackUse(graphicsUse);
    }

    /**
     * Block-breaking overlay: mirrors vanilla's {@code ClientLevel.destructionProgress()} (populated
     * by network packets, independent of the cancelled {@code LevelRenderer.render()}) into the push's
     * {@code breaking[]} list, so {@code world.rchit} can blend
     * the matching destroy-stage crack texture into a hit terrain block's albedo. Each block's own
     * destroy-stage texture ({@code minecraft:textures/block/destroy_stage_N.png}, resolved via
     * {@link ModelBakery#DESTROY_TYPES}) is a standalone {@code Sampler0} texture, not a block-atlas sprite,
     * so it rides the same bindless entity-texture array as entity textures ({@link RtEntityTextures}).
     */
    private BreakEntry[] breakingEntries(RtTerrain terrain) {
        BreakEntry[] result = new BreakEntry[WorldPushData.BREAKING_CAPACITY];
        int count = 0;
        var level = Minecraft.getInstance().level;
        if (level != null) {
            for (var entry : level.destructionProgress().long2ObjectEntrySet()) {
                if (count >= result.length) {
                    break;
                }
                var progresses = entry.getValue();
                if (progresses == null || progresses.isEmpty()) {
                    continue;
                }
                int stage = Mth.clamp(progresses.last().getProgress(), 0, 9);
                BlockPos pos = BlockPos.of(entry.getLongKey());
                int slot = RtEntityTextures.INSTANCE.slotFor(ModelBakery.DESTROY_TYPES.get(stage));
                result[count++] = new BreakEntry(new Int4(
                        pos.getX() - terrain.blockX,
                        pos.getY() - terrain.blockY,
                        pos.getZ() - terrain.blockZ,
                        slot));
            }
        }
        return count == result.length ? result : java.util.Arrays.copyOf(result, count);
    }

    private record SkyPush(Float4 celestial, Float4 look0, Float4 look1, Float4 look2, Float4 look3,
                           Float4 sunUv, Float4 moonUv) {}

    private record CelestialUv(Float4 sun, Float4 moon) {}

    /**
     * This frame's sky state: Minecraft's four eased celestial angles, its star brightness, the moon
     * phase, and the look package's sky constants. Nothing else.
     *
     * <p>Every direction, colour, level and atmospheric transmittance is derived in {@code sky.slang}
     * from these values, keeping atmospheric evaluation in one implementation.
     *
     * <p>The angles come from the camera's {@link EnvironmentAttributeProbe} rather than from the tick:
     * in 26.2 they are timeline tracks driven through a cubic-bezier ease, and a datapack can replace the
     * track outright, so the probe is the only source that stays correct for a custom dimension.
     *
     * <p>The sky-view LUT's viewer altitude tracks the camera's real world height above sea level, not
     * the look package's fixed reference altitude: a build-limit mod or a rocket/space mod climbing
     * toward the 100 km shell should see the atmosphere actually thin out. The block-to-km scale is
     * exaggerated 10x (100 blocks = 1 km, not the literal 1000) — vanilla's build range is under half a
     * real km, which would put the whole playable height range within a rounding error of one LUT texel
     * row; at 100:1 the same climb is a few km, enough to see the horizon and zenith actually shift.
     * Clamped to [0, 99] km so an absurd Y (or one beyond the modelled 100 km shell) degrades to the
     * shell edge instead of an LUT sample outside its baked domain. The shader applies its own lower
     * floor — see {@code sky.MIN_VIEWER_ALTITUDE_KM}, which is set by what fp32 can resolve at planet
     * radius, not by anything visual — so zero here is safe and means "at or below sea level".
     */
    private SkyPush skyPush() {
        Minecraft mc = Minecraft.getInstance();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var probe = mc.gameRenderer.mainCamera().attributeProbe();
        int seaLevel = mc.level != null ? mc.level.getSeaLevel() : 0;
        float viewerAltitudeKm = Math.clamp((float) ((camY - seaLevel) / 100.0), 0.0f, 99.0f);
        float toRadians = (float) (Math.PI / 180.0);
        float sunAngle = probe.getValue(EnvironmentAttributes.SUN_ANGLE, partial) * toRadians;
        float moonAngle = probe.getValue(EnvironmentAttributes.MOON_ANGLE, partial) * toRadians;
        // Stars use Minecraft's own celestial rotation and brightness (the values vanilla's SkyRenderer
        // uses), so the field wheels about the celestial pole tied to world time and fades in and out at
        // dusk/dawn exactly like vanilla's.
        float starAngle = probe.getValue(EnvironmentAttributes.STAR_ANGLE, partial) * toRadians;
        float starBrightness = probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, partial);
        float moonPhase = probe.getValue(EnvironmentAttributes.MOON_PHASE, partial).index(); // 0 full .. 4 new

        RtLookPackage.Sky sky = LOOK.sky();
        RtLookPackage.Lighting lighting = LOOK.lighting();
        CelestialUv uv = celestialUv(moonPhase);
        return new SkyPush(
                new Float4(sunAngle, moonAngle, starAngle, starBrightness),
                new Float4(lighting.sunIlluminanceLux(), lighting.moonIlluminanceLux(),
                        lighting.nightAirglowLuminanceCdM2(), lighting.starLuminanceCdM2()),
                new Float4(sky.sunNoonSouthTiltDegrees() * toRadians,
                        sky.sunAngularRadiusDegrees() * toRadians,
                        sky.moonAngularRadiusDegrees() * toRadians,
                        lighting.moonPhaseFixedFraction()),
                new Float4(sky.sunDiscHalfAngleDegrees() * toRadians,
                        sky.moonDiscHalfAngleDegrees() * toRadians,
                        viewerAltitudeKm, moonPhase),
                new Float4(sky.groundAlbedo(), sky.horizonSoftenDegrees() * toRadians, 0f, 0f),
                uv.sun(),
                uv.moon());
    }

    /**
     * Push the celestials-atlas UV rects (u0,v0,u1,v1) for the sun sprite and the current moon-phase
     * sprite, so world.rmiss can sample the real vanilla textures on the discs. Atlas-not-ready (early
     * boot / no resources) leaves full-range UVs and the shader's block-atlas fallback covers it.
     */
    private CelestialUv celestialUv(float moonPhaseIndex) {
        if (celestialUvAtlasHandle == 0L) {
            setCelestialUvAtlas(celestialsAtlasView());
        }
        int phase = Math.clamp((int) moonPhaseIndex, 0, MOON_IDS.length - 1);
        if (phase != celestialUvMoonPhase) {
            refreshCelestialUvCache(phase);
        }
        return new CelestialUv(
                new Float4(sunU0, sunV0, sunU1, sunV1),
                new Float4(moonU0, moonV0, moonU1, moonV1));
    }

    private void setCelestialUvAtlas(long atlasHandle) {
        if (celestialUvAtlasHandle == atlasHandle) {
            return;
        }
        celestialUvAtlasHandle = atlasHandle;
        celestialUvMoonPhase = -1;
        sunU0 = 0f; sunV0 = 0f; sunU1 = 1f; sunV1 = 1f;
        moonU0 = 0f; moonV0 = 0f; moonU1 = 1f; moonV1 = 1f;
    }

    private void refreshCelestialUvCache(int moonPhase) {
        sunU0 = 0f; sunV0 = 0f; sunU1 = 1f; sunV1 = 1f;
        moonU0 = 0f; moonV0 = 0f; moonU1 = 1f; moonV1 = 1f;
        try {
            if (celestialUvAtlasHandle != 0L) {
                TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.CELESTIALS);
                TextureAtlasSprite sun = atlas.getSprite(SUN_ID);
                sunU0 = sun.getU0(); sunV0 = sun.getV0(); sunU1 = sun.getU1(); sunV1 = sun.getV1();
                TextureAtlasSprite moon = atlas.getSprite(MOON_IDS[moonPhase]);
                moonU0 = moon.getU0(); moonV0 = moon.getV0(); moonU1 = moon.getU1(); moonV1 = moon.getV1();
            }
        } catch (Exception ignored) {
            // celestials atlas not yet loaded — keep full-range UVs (fallback texture is the block atlas)
        }
        celestialUvMoonPhase = moonPhase;
    }

    /**
     * sRGB EOTF for a single 0..1 channel. The environment-attribute colours arrive as packed sRGB
     * bytes; the shader tints in linear, so they cross the transfer function exactly once, here.
     */
    private static float srgbToLinear(float code) {
        return code <= 0.04045f ? code / 12.92f : (float) Math.pow((code + 0.055f) / 1.055f, 2.4);
    }

    private static Float4 linearAcesCgFromSrgb(double r, double g, double b, float w) {
        return linearAcesCgFromBt709(
                srgbToLinear(r), srgbToLinear(g), srgbToLinear(b), w);
    }

    /** OCIO cg-config-v4.0.0 ACES 2.0: Linear Rec.709 (sRGB)/D65 to ACEScg/AP1/D60. */
    private static Float4 linearAcesCgFromBt709(double r, double g, double b, float w) {
        return new Float4(
                (float) (0.61309743 * r + 0.33952314 * g + 0.04737945 * b),
                (float) (0.07019372 * r + 0.91635388 * g + 0.01345240 * b),
                (float) (0.02061559 * r + 0.10956977 * g + 0.86981463 * b),
                w);
    }

    private static double srgbToLinear(double value) {
        return value <= 0.04045 ? value / 12.92
                : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    public void destroy() {
        // Teardown runs after the device is idle (CLIENT_STOPPING waits), so the TLAS ring's slots are no
        // longer in flight and can be freed immediately.
        tlasRing.destroy();
        if (RtDlssRr.enabled()) {
            RtDlssRr.INSTANCE.destroy();
        }
        if (displayImage != null) {
            displayImage.destroy();
            displayImage = null;
        }
        if (hdrDisplayImage != null) {
            hdrDisplayImage.destroy();
            hdrDisplayImage = null;
        }
        destroyBloomLevels();
        if (fgHudlessImage != null) {
            fgHudlessImage.destroy();
            fgHudlessImage = null;
        }
        if (fgHdrHudlessImage != null) {
            fgHdrHudlessImage.destroy();
            fgHdrHudlessImage = null;
        }
        RtWorldOverlay.INSTANCE.destroy(); // overlay features/pipelines/scratch live on the same device lifetime
        if (output != null) {
            output.destroy();
            output = null;
        }
        if (continuationQueue != null) {
            continuationQueue.destroy();
            continuationQueue = null;
        }
        destroyGuideImages();
        exposure.destroy();
        if (displayPipeline != null) {
            displayPipeline.destroy();
            displayPipeline = null;
        }
        if (bloomPipeline != null) {
            bloomPipeline.destroy();
            bloomPipeline = null;
        }
        if (skyLut != null) {
            skyLut.destroy();
            skyLut = null;
        }
        if (debugPresentPipeline != null) {
            debugPresentPipeline.destroy();
            debugPresentPipeline = null;
        }
        if (sdrToneLut != null) {
            sdrToneLut.destroy();
            sdrToneLut = null;
        }
        if (hdrToneLut != null) {
            hdrToneLut.destroy();
            hdrToneLut = null;
        }
        if (lookLut != null) {
            lookLut.destroy();
            lookLut = null;
        }
        loadedHdrLutNits = -1;
        if (hdrCompositePipeline != null) {
            hdrCompositePipeline.destroy();
            hdrCompositePipeline = null;
        }
        if (hdrUiSampler != 0L) {
            RtContext hdrCtx = RtContext.currentOrNull();
            if (hdrCtx != null) {
                VK10.vkDestroySampler(hdrCtx.vk(), hdrUiSampler, null);
            }
            hdrUiSampler = 0L;
        }
        if (sdrPresentPipeline != null) {
            sdrPresentPipeline.destroy();
            sdrPresentPipeline = null;
        }
        if (sdrPresentImage != null) {
            sdrPresentImage.destroy();
            sdrPresentImage = null;
        }
        for (RtImage img : fgInterp) {
            if (img != null) {
                img.destroy();
            }
        }
        fgInterp = new RtImage[0];
        fgInterpW = -1;
        fgInterpH = -1;
        fgInterpFormat = Integer.MIN_VALUE;
        if (worldPipeline != null) {
            worldPipeline.destroy();
            worldPipeline = null;
        }
        if (cloudNoise != null) {
            cloudNoise.destroy();
            cloudNoise = null;
        }
        if (cloudNoiseSampler != 0L) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null) {
                VK10.vkDestroySampler(ctx.vk(), cloudNoiseSampler, null);
            }
            cloudNoiseSampler = 0L;
        }
        bindlessTextureCapacity = 0;
        materialBindingsReady = false;
        materialEpochTraceGate = false;
        RtMaterialRegistry.INSTANCE.destroy();
        if (pushRing != null) {
            for (PushSlot slot : pushRing) {
                if (slot != null) {
                    slot.buffer.destroy();
                }
            }
            pushRing = null;
        }
        if (atlasSampler != 0L) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null) {
                VK10.vkDestroySampler(ctx.vk(), atlasSampler, null);
            }
            atlasSampler = 0L;
        }
    }

    /**
     * Creates and binds the cloud shape texture, once. LINEAR filtering with REPEAT addressing in all
     * three axes: the field is generated to tile, so the hardware's wrap does the domain folding and
     * the shader needs no fract() on its coordinate.
     */
    private void ensureCloudNoise(RtContext ctx) {
        if (cloudNoise != null) {
            worldPipeline.setCloudNoise(cloudNoise.view, cloudNoiseSampler);
            return;
        }
        java.nio.ByteBuffer data = RtCloudNoise.generate();
        try {
            cloudNoise = ctx.createSampled3dImage(RtCloudNoise.SIZE, RtCloudNoise.SIZE, RtCloudNoise.SIZE,
                    VK10.VK_FORMAT_R8G8_UNORM, RtCloudNoise.BYTES_PER_TEXEL, data, "cloud noise 128^3");
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(data);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .minLod(0f).maxLod(0f);
            LongBuffer p = stack.mallocLong(1);
            if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateSampler(cloud noise) failed");
            }
            cloudNoiseSampler = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, cloudNoiseSampler, "cloud noise sampler");
        }
        worldPipeline.setCloudNoise(cloudNoise.view, cloudNoiseSampler);
    }

    private long atlasSampler(RtContext ctx) {
        if (atlasSampler == 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                        .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR)
                        .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .minLod(0f).maxLod(16f);
                LongBuffer p = stack.mallocLong(1);
                if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSampler(block atlas) failed");
                }
                atlasSampler = p.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, atlasSampler, "block atlas sampler");
            }
        }
        return atlasSampler;
    }

    private static long blockAlbedoAtlasView() {
        GpuTextureView view = Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        return vkImageView(view);
    }

    private static long vkImageView(GpuTextureView view) {
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        throw new IllegalStateException("cannot resolve VkImageView for " + view);
    }

    private static long vkImage(GpuTexture texture) {
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

    /** Whether the HDR present path (HDR image + combined UI -> PQ swapchain) should replace the vanilla SDR blit. */
    public boolean isHdrPresentActive() {
        return CausticaConfig.Rt.Hdr.enabled()
                && hdrWrittenThisFrame
                && hdrDisplayImage != null;
    }

    /**
     * DLSS-FG: the PQ-encoded HDR backbuffer (view/image), valid only right after {@link #presentHdr} has run
     * this frame (it's the same image {@code presentHdr} just composited UI into and blitted to the
     * swapchain) — used as the interpolation source for HDR frame generation instead of the SDR main target.
     * Already display-ready PQ, so it's fed to DLSSG directly with no extra encode step. 0 if HDR isn't
     * active this frame.
     */
    public long hdrBackbufferView() {
        return hdrDisplayImage != null ? hdrDisplayImage.view : 0L;
    }

    public long hdrBackbufferImage() {
        return hdrDisplayImage != null ? hdrDisplayImage.image : 0L;
    }

    /**
     * Blit this frame's PQ-encoded HDR image straight into the swapchain image, replacing Minecraft's SDR
     * blit. Replicates {@code VulkanGpuSurface.blitFromTexture}'s barrier + acquire-wait/present-signal
     * sequence with the HDR {@link RtImage} as the (GENERAL-layout) source; an added memory barrier makes the
     * display-compute writes visible to the blit read. The SDR main target is bypassed; the combined UI image
     * is blended over the HDR image here at paper white before the swapchain blit. The magic stage/access
     * values mirror vanilla {@code blitFromTexture} exactly. Y is flipped to match the vanilla swapchain blit.
     */
    public void presentHdr(VulkanCommandEncoder enc, long swapchainImage, int swapW, int swapH, long acquireSem, long presentSem) {
        RtImage src = hdrDisplayImage;
        int copyW = Math.min(swapW, src.width);
        int copyH = Math.min(swapH, src.height);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();

            // DLSS-FG "hudless" capture: hdrDisplayImage right now holds the RT world before the combined
            // UI overlay is blended in. Snapshot it before that composite overwrites it in place, mirroring
            // captureFgHudless's SDR pattern (pre-UI copy) but reusing this frame's already-open command
            // buffer.
            if (RtDlssFg.enabled()) {
                captureFgHdrHudless(cmd, stack, src);
            }

            // Step C.2: composite the combined UI overlay over the HDR world image (in place) at paper white,
            // before the swapchain blit. The overlay is an MC render target kept in GENERAL layout, sampled by
            // the compute pass. A memory barrier first makes the overlay writes + the world HDR writes visible
            // to the compute; the dep1 barrier below (ALL writes -> transfer read) then covers the compute's
            // HDR write for the blit.
            long overlayView = RtUiOverlay.populatedThisFrame() ? RtUiOverlay.overlayColorView() : 0L;
            if (overlayView != 0L) {
                ensureHdrUiResources();
                if (hdrCompositePipeline != null) {
                    VkMemoryBarrier2.Buffer pre = VkMemoryBarrier2.calloc(1, stack).sType$Default();
                    pre.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(2048L).dstAccessMask(98304L);
                    VkDependencyInfo preDep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(pre);
                    KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, preDep);
                    hdrCompositePipeline.setImages(hdrDisplayImage.view, overlayView, hdrUiSampler);
                    hdrCompositePipeline.dispatch(cmd, src.width, src.height, CausticaConfig.Rt.Hdr.uiNits());
                }
                RtUiOverlay.markConsumed();
            }
            // Swapchain UNDEFINED -> TRANSFER_DST, plus make the HDR compute writes visible to the blit read.
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

            // Blit HDR (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like vanilla.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1); // srcOffsets[0] = (0,0,0) from calloc
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL, swapchainImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

            // Swapchain TRANSFER_DST -> PRESENT_SRC_KHR (1000001002).
            VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep2);

            if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(hdr present) failed");
            }
            enc.waitSemaphore(acquireSem, 0L, 65536L);
            enc.execute(cmd);
            enc.signalSemaphore(presentSem, 0L, 4096L);
        }
    }

    /** Lazily create the HDR UI-composite compute pipeline + its nearest/clamp sampler (first HDR present). */
    private void ensureHdrUiResources() {
        if (hdrCompositePipeline != null) {
            return;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null || !ensureUiSampler(ctx)) {
            return;
        }
        hdrCompositePipeline = RtHdrCompositePipeline.create(ctx);
    }

    /** Ensure the shared nearest/clamp sampler used to sample SDR/overlay targets in the present compute. */
    private boolean ensureUiSampler(RtContext ctx) {
        if (hdrUiSampler != 0L) {
            return true;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            var p = stack.mallocLong(1);
            if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                return false;
            }
            hdrUiSampler = p.get(0);
        }
        return true;
    }

    /**
     * Whether a non-RT frame (menu, title panorama, loading screen) should be SDR-&gt;PQ converted for
     * present instead of vanilla's raw SDR blit. True when the PQ swapchain is active but this frame did
     * not produce an HDR image ({@link #isHdrPresentActive()} false).
     */
    public boolean isPqSdrPresentActive() {
        // The conversion is needed only while the CURRENT swapchain is PQ and this frame has no HDR
        // image (menus/loading, or the short interval after the toggle changed but before configure()).
        // Once configure recreates a native-SDR swapchain, vanilla's ordinary blit is correct.
        return CausticaConfig.Rt.Hdr.swapchainPqActive()
                && !isHdrPresentActive();
    }

    /**
     * Present a non-RT (menu/loading) frame to the PQ swapchain: convert the SDR main target (sRGB-encoded
     * rgba8, GENERAL layout, already holding the composited panorama + UI) to PQ-encoded at paper white via
     * a compute pass into {@link #sdrPresentImage}, then blit that into the swapchain. Mirrors
     * {@link #presentHdr} barrier-for-barrier; returns false (keep vanilla SDR blit) if resources are
     * unavailable.
     */
    public boolean presentSdrToPq(VulkanCommandEncoder enc, long swapchainImage, int swapW, int swapH,
            long sdrMainView, long acquireSem, long presentSem) {
        if (sdrMainView == 0L || failed) {
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null || !ensureUiSampler(ctx)) {
            return false;
        }
        if (sdrPresentPipeline == null) {
            sdrPresentPipeline = RtSdrPresentPipeline.create(ctx);
        }
        if (sdrPresentImage == null || sdrPresentImage.width != swapW || sdrPresentImage.height != swapH) {
            if (sdrPresentImage != null) {
                sdrPresentImage.destroy();
            }
            sdrPresentImage = ctx.createStorageImage(swapW, swapH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "RT SDR->PQ present image " + swapW + "x" + swapH);
        }
        RtImage dst = sdrPresentImage;
        int copyW = Math.min(swapW, dst.width);
        int copyH = Math.min(swapH, dst.height);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();

            // Make the prior GUI/overlay writes to the SDR main target visible to the compute sample.
            VkMemoryBarrier2.Buffer pre = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            pre.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(2048L).dstAccessMask(98304L);
            VkDependencyInfo preDep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(pre);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, preDep);

            sdrPresentPipeline.setImages(dst.view, sdrMainView, hdrUiSampler);
            sdrPresentPipeline.dispatch(cmd, dst.width, dst.height, CausticaConfig.Rt.Hdr.uiNits());

            // Swapchain UNDEFINED -> TRANSFER_DST, plus make the compute write visible to the blit read.
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

            // Blit converted PQ image (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like vanilla.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1); // srcOffsets[0] = (0,0,0) from calloc
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, swapchainImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

            // Swapchain TRANSFER_DST -> PRESENT_SRC_KHR (1000001002).
            VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep2);

            if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(sdr present) failed");
            }
            enc.waitSemaphore(acquireSem, 0L, 65536L);
            enc.execute(cmd);
            enc.signalSemaphore(presentSem, 0L, 4096L);
        }
        return true;
    }

    /**
     * Linear-filtered blit of the full render-res image into the full display-res image. Used as the
     * non-RR / fallback upscale so display mapping always sees a display-res RT image; a no-op stretch when
     * the two are the same size (RR disabled -> render == display).
     */
    private static void blitUpscale(VkCommandBuffer cmd, MemoryStack stack, RtImage src, RtImage dst) {
        VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).srcOffsets(1).set(src.width, src.height, 1); // srcOffsets[0] zeroed by calloc
        region.get(0).dstOffsets(1).set(dst.width, dst.height, 1);
        VK10.vkCmdBlitImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region, VK10.VK_FILTER_LINEAR);
    }

    /**
     * DLSS Frame Generation quality: capture a copy of {@code main} (the main render target) into
     * {@link #fgHudlessImage} for {@link #fgInterpolate} to feed DLSSG as the "hudless" resource. Call from
     * {@code GameRendererMixin} right after {@code GuiRenderer.render()} but BEFORE
     * {@link RtUiOverlay#compositeIfUsed()} — at that point, when the UI overlay redirect is active, {@code
     * main} still has no combined UI baked in (world overlays, hand/screen effects and GUI went to the
     * overlay target instead). No-op (and {@link #fgInterpolate} passes 0/0/0 for hudless, same as always)
     * unless both FG and the UI overlay redirect are active — capturing this without the redirect would just
     * copy the ALREADY-composited backbuffer, which is useless as a distinct hudless input.
     */
    public void captureFgHudless(RenderTarget main) {
        if (!RtDlssFg.enabled() || !RtUiOverlay.enabled() || main == null || main.getColorTexture() == null) {
            return;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            return;
        }
        long srcImage;
        try {
            srcImage = vkImage(main.getColorTexture());
        } catch (IllegalStateException e) {
            return; // not a Vulkan-backed texture (shouldn't happen on this backend)
        }
        if (fgHudlessImage == null || fgHudlessImage.width != main.width || fgHudlessImage.height != main.height) {
            if (fgHudlessImage != null) {
                fgHudlessImage.destroy();
            }
            fgHudlessImage = ctx.createStorageImage(main.width, main.height, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                    "FG hudless capture " + main.width + "x" + main.height);
        }
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).caustica$getBackend();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Make writes into `main` visible to the copy (the combined UI has not touched `main` yet this
            // frame — it went to the UI overlay target instead).
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
            VK10.vkCmdCopyImage(cmd, srcImage, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    fgHudlessImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, main.width, main.height));
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg hudless capture) failed");
        }
        encoder.execute(cmd);
    }

    /**
     * HDR counterpart of {@link #captureFgHudless} — copies {@code src} (this frame's {@code hdrDisplayImage},
     * before the combined UI overlay is blended in) into {@link #fgHdrHudlessImage} for {@link
     * #fgInterpolate}'s HDR path to feed DLSSG as the "hudless" resource. A plain copy, not a format
     * conversion: both images are
     * already PQ-encoded (the display-ready EOTF-encoded [0,1] signal DLSS-FG's programming guide requires),
     * so no encode step is needed. Called from {@link #presentHdr} using its already-open {@code cmd}/
     * {@code stack}, right before that method's own combined-UI composite dispatch overwrites
     * {@code hdrDisplayImage} in place — same "capture before the UI gets baked back in" timing as the SDR
     * version, just within a single method instead of split across a mixin hook.
     */
    private void captureFgHdrHudless(VkCommandBuffer cmd, MemoryStack stack, RtImage src) {
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            return;
        }
        if (fgHdrHudlessImage == null || fgHdrHudlessImage.width != src.width || fgHdrHudlessImage.height != src.height) {
            if (fgHdrHudlessImage != null) {
                fgHdrHudlessImage.destroy();
            }
            fgHdrHudlessImage = ctx.createStorageImage(src.width, src.height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "FG HDR hudless capture (PQ) " + src.width + "x" + src.height);
        }
        // Make composite()'s writes to hdrDisplayImage (an earlier submit this frame) visible to this copy;
        // the copy's write is then made visible to the UI-composite dispatch that follows (and to DLSSG's
        // read, in a later command buffer) by the same idiom.
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
        VK10.vkCmdCopyImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                fgHdrHudlessImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, src.width, src.height));
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
    }

    /**
     * DLSS Frame Generation: record the DLSSG evaluate for generated frame {@code index} of {@code count}
     * (backbuffer = the final frame; HW depth = {@code gDepth}; motion = {@code gMotion}) into Minecraft's
     * command encoder, returning the interpolated output image (backbuffer size) for {@link RtFramePresenter}
     * to blit into a generated swapchain image. On {@code index == 1} it ensures the feature (created in its
     * own synchronous submit), the per-index output images, and the jitter-free reprojection matrices.
     * Returns {@code null} (caller falls back to duplicating the real frame for this one frame, no session
     * impact) when there's simply no captured RT frame to interpolate from right now — routine and expected
     * on menu/loading/transition frames, since {@link RtFramePresenter#isActive} only gates on being in a
     * world, not on RT having actually produced a frame this tick. Throws instead for failures that should
     * never happen once RT is actively producing frames (DLSSG feature creation failing, an out-of-range
     * index, the evaluate itself failing) — the caller treats those as fatal and disables FG for the
     * session, same as any other FG present-record failure, rather than silently degrading to duplicated
     * (non-interpolated) frames forever with no visible sign anything is wrong. Rotation-only matrices;
     * camera translation is carried by the mvecs (cameraMotionIncluded).
     *
     * <p>{@code hdrBackbuffer} selects the HDR path. Per the DLSS-FG programming guide's HDR section, scRGB is
     * explicitly unsupported as a DLSS-FG input ("not suitable as inputs to DLSS-FG" — it wants a
     * display-ready, EOTF-encoded [0,1] signal, recommending HDR10/ST.2084) — since the renderer's whole HDR
     * pipeline is natively PQ-encoded, every image fed to {@code RtDlssFg.evaluate} in HDR mode is already in
     * that format with no extra conversion needed: the backbuffer is the raw {@code backbufferView}/
     * {@code backbufferImage} the caller passed in ({@link #hdrBackbufferView()}, already PQ + UI-composited
     * by {@link #presentHdr}); the hudless resource is {@link #fgHdrHudlessImage} (copied by {@link
     * #presentHdr} <em>before</em> its own UI composite ran, mirroring {@link #captureFgHudless}'s pre-UI
     * timing); and DLSSG's own (also PQ-encoded) output is returned as-is, since the swapchain itself is
     * PQ-native and can blit it directly. The UI resource itself needs no HDR-specific handling — it's the
     * same combined {@link RtUiOverlay} texture used by both present paths (only the *compositing* math that
     * consumes it differs, done separately by {@code presentHdr}/{@code RtUiOverlay}, not here).
     */
    public RtImage fgInterpolate(VulkanCommandEncoder enc, long backbufferView, long backbufferImage,
            int swapW, int swapH, int index, int count, boolean hdrBackbuffer) {
        if (failed || gDepth == null || gMotion == null || !frameCaptured) {
            return null;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            return null;
        }
        final int fmt = hdrBackbuffer ? VK10.VK_FORMAT_R16G16B16A16_SFLOAT : VK10.VK_FORMAT_R8G8B8A8_UNORM;
        if (index == 1) {
            if (!ensureFgFeature(ctx, swapW, swapH, renderW, renderH, fmt)) {
                throw new IllegalStateException("DLSSG feature not ready (ensureFgFeature failed)");
            }
            ensureFgInterp(ctx, count, swapW, swapH, fmt);
            // clipToPrevClip = prevVP * inverse(curVP); prevClipToClip = curVP * inverse(prevVP). Both from
            // the (rotation-only, camera-relative) MV view-projections, so jitter-free.
            fgMatTmp.set(mvCurProjView).invert();
            fgClipToPrev.set(mvPrevProjView).mul(fgMatTmp);
            fgMatTmp.set(mvPrevProjView).invert();
            fgPrevToClip.set(mvCurProjView).mul(fgMatTmp);
        }
        if (index < 1 || index > fgInterp.length || fgInterp[index - 1] == null) {
            throw new IllegalStateException(
                    "fgInterpolate index " + index + " out of range for fgInterp[" + fgInterp.length + "]");
        }
        RtImage out = fgInterp[index - 1];
        // Only feed hudless/ui when they exist AND match this frame's backbuffer size — a stale or mismatched
        // size (e.g. mid-resize) is worse than skipping, so fall back to 0/0/0 (DLSSG just does without).
        RtImage hudlessSrc = hdrBackbuffer ? fgHdrHudlessImage : fgHudlessImage;
        boolean hudlessReady = hudlessSrc != null && hudlessSrc.width == swapW && hudlessSrc.height == swapH;
        long hudlessView = hudlessReady ? hudlessSrc.view : 0L;
        long hudlessImg = hudlessReady ? hudlessSrc.image : 0L;
        int hudlessFmt = hdrBackbuffer ? VK10.VK_FORMAT_R16G16B16A16_SFLOAT : VK10.VK_FORMAT_R8G8B8A8_UNORM;
        boolean uiReady = RtUiOverlay.overlayWidth() == swapW && RtUiOverlay.overlayHeight() == swapH
                && RtUiOverlay.overlayColorView() != 0L && RtUiOverlay.overlayColorImage() != 0L;
        long uiView = uiReady ? RtUiOverlay.overlayColorView() : 0L;
        long uiImg = uiReady ? RtUiOverlay.overlayColorImage() : 0L;

        VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();
        boolean ok = RtDlssFg.INSTANCE.evaluate(cmd.address(),
                backbufferView, backbufferImage, fmt,
                gDepth.view, gDepth.image, VK10.VK_FORMAT_R32_SFLOAT,
                gMotion.view, gMotion.image, VK10.VK_FORMAT_R16G16_SFLOAT,
                hudlessView, hudlessImg, hudlessReady ? hudlessFmt : 0,
                uiView, uiImg, uiReady ? VK10.VK_FORMAT_R8G8B8A8_UNORM : 0,
                out.view, out.image, fmt,
                swapW, swapH, renderW, renderH, count, index, 1.0f, 1.0f,
                true /* depthInverted (reversed-Z) */, hdrBackbuffer /* colorBuffersHDR */,
                true /* cameraMotionIncluded (in mvecs) */, fgReset,
                fgClipToPrev, fgPrevToClip);
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg interpolate) failed");
        }
        fgReset = false;
        if (!ok) {
            throw new IllegalStateException("ngxshim_evaluate_dlssg failed (RtDlssFg.evaluate returned false)");
        }
        enc.execute(cmd);
        return out;
    }

    private boolean ensureFgFeature(RtContext ctx, int w, int h, int rw, int rh, int fmt) {
        if (RtDlssFg.INSTANCE.featureReadyFor(w, h, rw, rh, fmt)) {
            return true;
        }
        // Create the feature in its own submit + wait (not folded into MC's frame submit).
        ctx.submitSync(c -> RtDlssFg.INSTANCE.ensureFeature(c.address(), w, h, rw, rh, fmt));
        fgReset = true; // fresh feature has no temporal history
        return RtDlssFg.INSTANCE.featureReadyFor(w, h, rw, rh, fmt);
    }

    private void ensureFgInterp(RtContext ctx, int count, int w, int h, int fmt) {
        if (fgInterp.length == count && fgInterpW == w && fgInterpH == h && fgInterpFormat == fmt
                && (count == 0 || fgInterp[0] != null)) {
            return;
        }
        for (RtImage img : fgInterp) {
            if (img != null) {
                img.destroy();
            }
        }
        fgInterp = new RtImage[count];
        for (int i = 0; i < count; i++) {
            fgInterp[i] = ctx.createStorageImage(w, h, fmt, "FG interp " + i + " " + w + "x" + h);
        }
        fgInterpW = w;
        fgInterpH = h;
        fgInterpFormat = fmt;
    }
}
