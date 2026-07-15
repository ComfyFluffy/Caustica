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
import dev.comfyfluffy.caustica.rt.gen.PushAddrData;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.BreakEntry;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float2;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float3;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float4;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Int4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
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
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
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
import dev.comfyfluffy.caustica.rt.material.RtTerrainMaterials;
import dev.comfyfluffy.caustica.rt.pipeline.RtDisplayPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import dev.comfyfluffy.caustica.rt.overlay.RtWorldOverlay;
import dev.comfyfluffy.caustica.rt.pipeline.RtHdrCompositePipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtSdrPresentPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtExposure;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

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
    // tableAddr/entityTableAddr/frameIndex are duplicated so hit shaders skip a global-memory dereference;
    // PushAddrData is generated from the same Slang module and owns this second ABI as well.
    private static final int GUIDE_COUNT = 6; // RR guide buffers bound at world-pipeline bindings 3..8
    // Frames a retired per-frame TLAS must outlive before it's freed (> frames-in-flight); matches
    // RtTerrain's deferred-free horizon. The frame TLAS is built + traced this frame, then freed once
    // the composite frame counter has advanced this far past it (so no in-flight frame still reads it).
    private static final int KEEP_FRAMES = 4;

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

    // Finite sun/moon angular sizes let NEE shadow rays sample the light disk (soft, contact-hardening
    // penumbrae). Radii in degrees; the real sun/moon are ~0.27°, but a touch larger reads pleasantly.
    private static final int WATER_ANCHOR_MASK = 4095;
    private static final Identifier SUN_ID = Identifier.withDefaultNamespace("sun");
    private static final Identifier[] MOON_IDS = createMoonIds();
    // Celestial rotation axis (the pole the sun/moon arc about): perpendicular to the east-west arc,
    // tilted by SUN_NOON_SOUTH_TILT. Pushed so the sky shader can build the sun/moon square's tangent
    // frame (right = travel direction) and wheel the starfield. = normalize(noonDir x sunriseDir).
    // Sign of the sub-pixel jitter as reported to DLSS-RR + applied to the primary ray, mirroring the
    // validated DLSS-SR convention (Vulkan flipped clip space wants Y negated).
    private static float jitterSignX() {
        return CausticaConfig.Rt.Composite.JITTER_SIGN_X.value();
    }

    private static float jitterSignY() {
        return CausticaConfig.Rt.Composite.JITTER_SIGN_Y.value();
    }

    private static float sunNoonTilt() {
        return CausticaConfig.Rt.Composite.SUN_NOON_SOUTH_TILT.value();
    }

    private static float sunNoonY() {
        return Mth.cos(sunNoonTilt());
    }

    private static float sunNoonZ() {
        return Mth.sin(sunNoonTilt());
    }

    private static float celestialAxisY() {
        return -sunNoonZ();
    }

    private static float celestialAxisZ() {
        return sunNoonY();
    }

    // Monotonic per-composite frame counter, used by RtTerrain to time frames-in-flight-safe frees.
    private static volatile long frameCounter;

    public static long frameCounter() {
        return frameCounter;
    }

    private RtPipeline worldPipeline;
    // Set at the HEAD of Minecraft.reloadResourcePacks() (mixin): a resource reload recreates the block
    // atlas + entity textures. We tear down the world pipeline there (drops all descriptor references) and
    // rebuild it once the NEW atlas is in place — detected by the atlas view handle changing away from
    // boundAtlasHandle to a fresh non-zero value (MC's deferred free keeps the old handle live for a few
    // frames, so "handle != 0" alone isn't enough to tell old from new).
    private volatile boolean reloadRebindRequested;
    // The block-atlas view handle currently bound into the world pipeline (set by bindWorldTextures).
    private long boundAtlasHandle;
    private int bindlessTextureCapacity;
    // True after the LabPBR atlases have been resolved/bound for the currently alive world pipeline.
    private boolean materialBindingsReady;
    // Set when a new material epoch is published. The first composite returns to vanilla so the next
    // client tick can apply RtTerrain's full-clear before any old-epoch primitive IDs are traced.
    private boolean materialEpochTraceGate;
    // World push data (256 B) lives in a host-visible BDA ring; only the 8-byte slot address is pushed
    // inline (256-byte NVIDIA push constant ceiling is otherwise exhausted by the world push struct).
    // One slot per in-flight frame, cycled per frame so an in-flight slot is never overwritten.
    private static final int PUSH_RING = 6;
    private RtBuffer[] pushRing;
    private int pushSlot;
    private RtDisplayPipeline displayPipeline;
    private RtImage output;
    private RtImage displayImage;
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
    private long pendingTerrainGraphicsUse;

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
            long atlas = blockAtlasView();
            return atlas == 0L || atlas == boundAtlasHandle;
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
        if (pendingTerrainGraphicsUse != 0L) {
            throw new IllegalStateException("Previous RT terrain graphics use was never completed");
        }
        RtFrameStats.FRAME.beginIfInactive();
        hdrWrittenThisFrame = false;
    }

    /** Record terrain retirement completion after the frame's final TLAS consumer (world overlay). */
    public void finishTerrainGraphicsUse() {
        long graphicsUse = pendingTerrainGraphicsUse;
        if (graphicsUse == 0L) {
            return;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            throw new IllegalStateException("RT context disappeared before terrain graphics use completed");
        }
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice()
                .createCommandEncoder()).caustica$getBackend();
        ctx.gpuExecutor().endGraphicsTerrainUse(encoder, graphicsUse);
        pendingTerrainGraphicsUse = 0L;
    }

    public void endFrame() {
        RtFrameStats.FRAME.end();
    }

    public boolean composite(GpuTexture nativeColor, int width, int height) {
        frameCounter++; // global frame serial used by remaining per-frame/entity rings and diagnostics
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
            // A resource reload re-stitches the block atlas. We've already torn down the world pipeline
            // (onResourceReloadStart) so nothing references the old atlas, but MC's deferred free keeps the
            // old view handle live for a few frames, then swaps in the new atlas (whose GPU upload may lag,
            // leaving the handle 0 transiently). Skip RT — vanilla renders — until the handle becomes a
            // fresh, non-zero value different from what we last bound; only then rebuild against it.
            if (reloadRebindRequested) {
                long atlas = blockAtlasView();
                if (atlas == 0L || atlas == boundAtlasHandle) {
                    return false;
                }
            }
            ensureOutput(ctx, width, height);
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
        if (Minecraft.getInstance().level == null || blockAtlasView() == 0L) {
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
            bindlessTextureCapacity = RtEntityTextures.maxTextures();
            worldPipeline = RtPipeline.create(ctx, RtDeviceBringup.worldRaygenShader(),
                    new String[]{"world.rmiss.spv"}, "world.rchit.spv", "world.rahit.spv",
                    PushAddrData.BYTE_SIZE, true, GUIDE_COUNT, bindlessTextureCapacity, true);
            // Per-frame push data lives in this BDA ring; the pipeline only pushes its address.
            if (pushRing == null) {
                pushRing = new RtBuffer[PUSH_RING];
                for (int i = 0; i < PUSH_RING; i++) {
                    pushRing[i] = ctx.createBuffer(WORLD_PUSH_SIZE,
                            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "rt world push " + i);
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
        long atlasView = blockAtlasView();
        boundAtlasHandle = atlasView; // remember what we bound so a reload can detect the new atlas
        worldPipeline.setAtlasSampler(atlasView, sampler);
        // Bindless slot 0 = fallback texture (the block atlas) so an entity whose texture can't be
        // resolved samples something defined rather than an unbound (partially-bound) descriptor.
        RtBlockMaterials.INSTANCE.reset();
        RtMaterialOverrides materialOverrides = RtMaterialOverrides.load();
        RtEmissionSemantics emissionSemantics = RtEmissionSemantics.analyze();
        RtBlockMaterials.INSTANCE.prepareAll(ctx, bindlessTextureCapacity, emissionSemantics, materialOverrides);
        RtEntityTextures.INSTANCE.reset(bindlessTextureCapacity, RtBlockMaterials.INSTANCE.pageCount());
        worldPipeline.setBindlessTexture(0, 0, atlasView, sampler); // binding 0 (albedo), slot 0 fallback
        RtBlockMaterials.INSTANCE.bindPages(worldPipeline, sampler);
        RtTerrainMaterials.INSTANCE.rebuild(ctx, RtBlockMaterials.INSTANCE, materialOverrides);
        materialBindingsReady = true;
        // Sky rewrite: bind the vanilla celestials atlas (sun + moon phases) for world.rmiss. The view
        // handle is stable across frames; the shader only samples it inside the sun/moon discs (sky
        // directions), so the block-atlas fallback is never read if the celestials atlas isn't ready.
        long celView = celestialsAtlasView();
        if (worldPipeline.hasSkyAtlas()) {
            worldPipeline.setSkyAtlas(celView != 0L ? celView : atlasView, sampler);
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
            RtTerrainMaterials.INSTANCE.destroy();
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
        if (rrOutput != null) {
            rrOutput.destroy();
            rrOutput = null;
        }
    }

    private void ensureOutput(RtContext ctx, int width, int height) {
        boolean rrEnabled = RtDlssRr.enabled();
        int rrQuality = rrEnabled ? RtDlssRr.quality() : Integer.MIN_VALUE;
        if (output != null && displayImage != null && hdrDisplayImage != null && rrOutput != null && exposure.ready()
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
        if (output != null) {
            output.destroy();
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

        // RT traces into an HDR (R16G16B16A16_SFLOAT) target so radiance > 1 survives to the display
        // mapping seam. displayImage stays R8G8B8A8 to match the main target it is copied into
        // (vkCmdCopyImage requires texel-size-compatible formats).
        output = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "trace color " + renderW + "x" + renderH);
        displayImage = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM, "RT display image " + width + "x" + height);
        // PQ-encoded ([0,1], ST.2084) HDR display image, written in parallel by display.comp when HDR mode is active.
        hdrDisplayImage = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "RT HDR display image " + width + "x" + height);
        // Guide buffers match the trace (render) resolution; DLSS-RR consumes them at render res.
        gNormal = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide normal roughness " + renderW + "x" + renderH);
        gAlbedo = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide diffuse albedo " + renderW + "x" + renderH);
        gDepth = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R32_SFLOAT, "guide linear depth " + renderW + "x" + renderH);
        gMotion = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16_SFLOAT, "guide motion " + renderW + "x" + renderH);
        gSpecAlbedo = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide specular albedo " + renderW + "x" + renderH);
        gSpecMotion = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16_SFLOAT, "guide specular motion " + renderW + "x" + renderH);
        // Display-res RT image the display mapper reads. Always present (DLSS-RR target, or blit-upscale fallback).
        rrOutput = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "DLSS-RR output " + width + "x" + height);
        exposure.ensureResources(ctx);

        mvHasPrev = false; // recreated images -> first MV frame is zero
        if (worldPipeline != null) {
            worldPipeline.setStorageImage(output.view);
            bindGuideImages();
        }
        displayPipeline.setImages(displayImage.view, rrOutput.view, exposure.image().view, hdrDisplayImage.view);
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
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_COMMAND_BUFFER, cmd.address(), "composite command buffer");
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope frameLabel = RtDebugLabels.scope(ctx, cmd, "composite frame")) {
            // RR drives the upscale: trace + jitter at render res, DLSS-RR denoises+upscales to display.
            // Jitter is suppressed for the no-RR reference and for the debug guide views (raw inspection).
            int debugView = debugView();
            boolean rrPath = RtDlssRr.enabled() && debugView == 0;
            float jitterX = 0f;
            float jitterY = 0f;
            if (rrPath) {
                CausticaJitter.INSTANCE.prepare(renderW, renderH, displayW);
                jitterX = CausticaJitter.INSTANCE.jitterPixelsX() * jitterSignX();
                jitterY = CausticaJitter.INSTANCE.jitterPixelsY() * jitterSignY();
            }

            boolean rrDone = false;
            RtTerrain terrain = RtTerrain.currentOrNull();
            // Select the next BDA ring slot; the generated WorldPushData serializer fills it once all
            // frame-derived values (including entity addresses and block-breaking entries) are known.
            pushSlot = (pushSlot + 1) % PUSH_RING;
            RtBuffer pushBuf = pushRing[pushSlot];
            ByteBuffer push = MemoryUtil.memByteBuffer(pushBuf.mapped, WORLD_PUSH_SIZE);
            frameInvViewProj.set(frameProjection).mul(frameViewRotation).invert();
            // flags: PBR BRDF (bit 1, always on) + camera-in-water (so the path tracer starts in the water
            // medium when the eye is submerged, fixing the air→water first-segment orientation).
            int flags = 0b10;
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
                flags |= 0b10000; // W1: animated water wave normals
            }

            // W1/W2 water parameters: camera-biome tint plus wrapped animation time. Per-water-body tint
            // comes from the primitive; this is the fallback for a camera already inside the medium.
            float wtr = 0.25f, wtg = 0.46f, wtb = 0.9f; // neutral ocean-ish default if no level/biome
            if (level != null) {
                int wc = BiomeColors.getAverageWaterColor(level, cameraBlockPos);
                wtr = ((wc >> 16) & 0xFF) / 255f;
                wtg = ((wc >> 8) & 0xFF) / 255f;
                wtb = (wc & 0xFF) / 255f;
            }
            Float4 waterParams = new Float4(wtr, wtg, wtb,
                    (float) (System.nanoTime() / 1.0e9 % 3600.0));
            // W1 wave-domain anchor: the terrain rebase origin reduced mod 4096 (kept small for shader
            // float precision). hitPos.xz (rebased) + anchor reconstructs a world-pinned coordinate, so the
            // ripple pattern stays fixed in the world as the player moves and the rebase origin shifts.
            Float4 waterAnchor = new Float4(terrain.blockX & WATER_ANCHOR_MASK,
                    terrain.blockZ & WATER_ANCHOR_MASK, 0f, 0f);

            // Rebuild the TLAS this frame from static section instances merged with dynamic entity
            // instances, bind it into the pipeline's descriptor ring, record the build, then barrier so
            // the trace sees the finished TLAS. Section BLASes are already built (async, by RtTerrain);
            // only the cheap instance-level TLAS is rebuilt per frame. Retired terrain geometry/table
            // generations are reclaimed by graphics-timeline completion.
            // Entity BLASes are built inline below and merged into the per-frame TLAS. geomTableAddr
            // feeds the hit shader entity path (per-prim normal/tint) and motion vectors.
            RtEntities.FrameEntities fe = RtEntities.INSTANCE.beginFrame(ctx, terrain.staticInstances(),
                    terrain.blockX, terrain.blockY, terrain.blockZ, camX, camY, camZ, frameProjection, frameViewRotation);
            // Block-breaking overlay: resolves each destroy-stage RenderType's texture into the
            // SAME bindless entity-texture array (destroy_stage_N.png is a standalone Sampler0 texture,
            // not a block-atlas sprite — see ModelBakery.BREAKING_LOCATIONS/DESTROY_TYPES), so any newly
            // resolved slot rides along with the uploadPending() call right below.
            BreakEntry[] breaking = breakingEntries(terrain);
            SkyPush sky = skyPush();
            new WorldPushData(
                    frameInvViewProj,
                    new Float3((float) (camX - terrain.blockX), (float) (camY - terrain.blockY),
                            (float) (camZ - terrain.blockZ)),
                    terrain.tableAddress(),
                    debugView,
                    (int) frameCounter,
                    mvPushMatrix,
                    new Float3(mvCamDeltaX, mvCamDeltaY, mvCamDeltaZ),
                    spp(),
                    new Float2(jitterX, jitterY),
                    fe.geomTableAddr(),
                    flags,
                    maxBounces(),
                    sky.sunDir(),
                    sky.lightDir(),
                    sky.lightRadiance(),
                    sky.moonDir(),
                    sky.celestial(),
                    sky.sunUv(),
                    sky.moonUv(),
                    waterParams,
                    waterAnchor,
                    mvCurProjView,
                    breaking.length,
                    breaking
            ).write(push);
            // Upload any entity textures registered this frame into the bindless set before the trace.
            RtEntityTextures.INSTANCE.uploadPending(active, atlasSampler(ctx));
            // Build the entity BLAS this frame, then the TLAS that references them (+ the already-built
            // terrain BLAS), then the trace — each separated by a barrier. The frame TLAS is retired
            // KEEP_FRAMES later (entity meshes/BLAS are retired by RtEntities on the same horizon).
            if (!fe.blas().isEmpty()) {
                try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("entity.blasRecord")) {
                    RtAccel.recordBlasBuilds(ctx, cmd, fe.blas());
                }
                VulkanCommandEncoder.memoryBarrier(cmd, stack); // entity BLAS writes visible to the TLAS build
            }
            RtAccel.PreparedTlas frameTlas;
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.prepareTlas")) {
                frameTlas = RtAccel.prepareTlas(ctx, fe.instances(), tlasRing);
            }
            active.setTlas(frameTlas.accel.handle);
            currentTlasHandle = frameTlas.accel.handle;
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.recordTlas")) {
                RtAccel.recordTlasBuild(ctx, cmd, frameTlas);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // TLAS build visible to the trace

            // Push the BDA ring slot's address plus the small hot subset that rchit/rahit read on every
            // hit (tableAddr/entityTableAddr/frameIndex) as real inline push constants, so those lookups
            // don't pay for a second global-memory dereference through pcAddr.worldPushAddr first.
            ByteBuffer pushAddr = stack.malloc(PushAddrData.BYTE_SIZE);
            new PushAddrData(pushBuf.deviceAddress, terrain.tableAddress(), fe.geomTableAddr(),
                    RtTerrainMaterials.INSTANCE.tableAddress(),
                    (int) frameCounter).write(pushAddr);
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world trace");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.trace")) {
                active.trace(cmd, renderW, renderH, pushAddr);
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

            // When DLSS-RR did not produce the display-res image (disabled, debug view, or a runtime
            // failure), bring the render-res trace up to display res with a linear blit so the display mapper
            // always has a display-res RT image. With RR off render == display, so this is a 1:1 copy.
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
                exposure.record(ctx, cmd, stack, rrOutput);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // exposure image visible to the display mapper

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "map RT to display");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.displayMap")) {
                displayPipeline.dispatch(cmd, displayW, displayH, CausticaConfig.Rt.Hdr.enabled(),
                        CausticaConfig.Rt.Hdr.paperWhiteNits(), CausticaConfig.Rt.Hdr.headroom());
            }
            hdrWrittenThisFrame = CausticaConfig.Rt.Hdr.enabled();
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
        RtGpuExecutor gpuExecutor = ctx.gpuExecutor();
        long graphicsUse = gpuExecutor.beginGraphicsTerrainUse(encoder);
        encoder.execute(cmd); // deferred into the frame's submission — correct for per-frame work
        pendingTerrainGraphicsUse = graphicsUse;
    }

    /**
     * Block-breaking overlay: mirrors vanilla's {@code ClientLevel.destructionProgress()} (populated
     * by network packets, independent of the cancelled {@code LevelRenderer.render()} — see
     * [[rt-native-overlay-tier1]]) into the push's {@code breaking[]} list, so {@code world.rchit} can blend
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

    private record SkyPush(Float4 sunDir, Float4 lightDir, Float4 lightRadiance, Float4 moonDir,
                           Float4 celestial, Float4 sunUv, Float4 moonUv) {}

    private record CelestialUv(Float4 sun, Float4 moon) {}

    /**
     * Derive the celestial light from Minecraft's time of day as typed values for {@link WorldPushData}.
     * Celestial angles come from the camera's {@link EnvironmentAttributeProbe} (partial-tick
     * interpolated). {@code caustica.rt.sunNoonSouthDeg} tilts the east-west arc toward south (+Z) at
     * noon.
     */
    private SkyPush skyPush() {
        float sunX, sunY, sunZ, dayFactor, lx, ly, lz, rr, rg, rb, lightRadius;
        float moonX, moonY, moonZ, moonPhase, starAngle, starBrightness;
        Minecraft mc = Minecraft.getInstance();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var probe = mc.gameRenderer.mainCamera().attributeProbe();
        float sunAngle = probe.getValue(EnvironmentAttributes.SUN_ANGLE, partial) * (float) (Math.PI / 180.0);
        float moonAngle = probe.getValue(EnvironmentAttributes.MOON_ANGLE, partial) * (float) (Math.PI / 180.0);
        float sunNoon = Mth.cos(sunAngle);
        sunX = -Mth.sin(sunAngle); sunY = sunNoonY() * sunNoon; sunZ = sunNoonZ() * sunNoon;
        float moonNoon = Mth.cos(moonAngle);
        moonX = -Mth.sin(moonAngle); moonY = sunNoonY() * moonNoon; moonZ = sunNoonZ() * moonNoon;
        moonPhase = probe.getValue(EnvironmentAttributes.MOON_PHASE, partial).index(); // 0 full .. 4 new
        // Stars: use Minecraft's actual celestial rotation + brightness (the same values vanilla's
        // SkyRenderer uses), so the starfield wheels about the celestial pole tied to world time and
        // fades in/out at dusk/dawn exactly like vanilla. STAR_ANGLE is in degrees -> radians.
        starAngle = probe.getValue(EnvironmentAttributes.STAR_ANGLE, partial) * (float) (Math.PI / 180.0);
        starBrightness = probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, partial);
        dayFactor = smoothstep(-0.08f, 0.10f, sunY);
        float[] trans = new float[3];
        if (sunY > -0.05f) {
            // Sun stays the NEE light through the whole sunset: its colour/intensity is the atmosphere's
            // own transmittance (same Rayleigh+Mie+ozone march as the sky shader — see
            // atmosphereTransmittance), so it whitens overhead and reddens+dims into the horizon on
            // exactly the curve the visible sky follows. The old hand-tuned warmth ramp switched to the
            // moon at sunY == 0 while the sun was still at ~16% strength, which read as a hard light pop
            // at sunset/sunrise; transmittance is already near zero at the horizon, and the short
            // smoothstep below carries the remainder to exactly zero before the moon takes over.
            atmosphereTransmittance(sunX, sunY, sunZ, trans);
            float fade = smoothstep(-0.05f, 0.005f, sunY);
            float sunPeak = 21.0f;
            lx = sunX; ly = sunY; lz = sunZ;
            rr = sunPeak * trans[0] * fade;
            rg = sunPeak * trans[1] * fade;
            rb = sunPeak * trans[2] * fade;
            lightRadius = CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.value();
        } else {
            // Moon: dim cool light, ramping up from zero at the sun→moon handoff (sunY = -0.05, where
            // the sun fade also reaches zero) so the switch is invisible. Scaled by the lit fraction so
            // a new moon gives near-zero moonlight, and tinted by the same transmittance so a low moon
            // is warm amber, silver once high (or zero while it is below the horizon).
            atmosphereTransmittance(moonX, moonY, moonZ, trans);
            float moonStrength = smoothstep(0.04f, 0.22f, -sunY);
            float litFraction = 1.0f - Math.abs(moonPhase - 4.0f) / 4.0f; // 0 new .. 1 full
            float moonPeak = 0.30f * (0.15f + 0.85f * litFraction);
            lx = moonX; ly = moonY; lz = moonZ;
            rr = 0.30f * moonPeak * moonStrength * trans[0];
            rg = 0.36f * moonPeak * moonStrength * trans[1];
            rb = 0.55f * moonPeak * moonStrength * trans[2];
            lightRadius = CausticaConfig.Rt.Composite.MOON_ANGULAR_RADIUS.value();
        }
        CelestialUv uv = celestialUv(moonPhase);
        return new SkyPush(
                new Float4(sunX, sunY, sunZ, dayFactor),
                new Float4(lx, ly, lz, lightRadius),
                new Float4(rr, rg, rb, starBrightness),
                new Float4(moonX, moonY, moonZ, moonPhase),
                new Float4(0f, celestialAxisY(), celestialAxisZ(), starAngle),
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

    /** Hermite smoothstep matching GLSL semantics (0 below edge0, 1 above edge1). */
    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /**
     * RGB transmittance from the camera to space along {@code dir} — a verbatim port of
     * {@code world.rmiss}'s {@code transmittanceToSpace} (Rayleigh + Mie + ozone optical depth, 8-step
     * march from 2 km altitude; constants must stay in lock-step with the shader). This is what colours
     * the NEE sun/moonlight: because the sky shader tints its visible discs with the identical function,
     * the light on terrain and the sky's sunset can never disagree. A direction below the geometric
     * horizon accumulates enormous optical depth, so the result rolls to zero smoothly on its own —
     * no explicit planet-shadow test needed.
     */
    private static void atmosphereTransmittance(float dx, float dy, float dz, float[] out) {
        final double planetR = 6371000.0, atmosR = 6471000.0;
        final double[] rayBeta = {5.5e-6, 13.0e-6, 22.4e-6};
        final double mieBeta = 21.0e-6 * 1.1;
        final double[] ozoneBeta = {0.650e-6, 1.881e-6, 0.085e-6};
        final double oy = planetR + 2000.0;
        // Larger root of ray vs atmosphere sphere, origin (0, oy, 0).
        double b = oy * dy;
        double tEnd = -b + Math.sqrt(Math.max(b * b - (oy * oy - atmosR * atmosR), 0.0));
        double seg = tEnd / 8.0;
        double odR = 0.0, odM = 0.0, odO = 0.0;
        for (int i = 0; i < 8; i++) {
            double t = seg * (i + 0.5);
            double px = dx * t, py = oy + dy * t, pz = dz * t;
            double h = Math.sqrt(px * px + py * py + pz * pz) - planetR;
            odR += Math.exp(-h / 8000.0) * seg;
            odM += Math.exp(-h / 1200.0) * seg;
            odO += Math.max(0.0, 1.0 - Math.abs(h - 25000.0) / 15000.0) * seg;
        }
        for (int i = 0; i < 3; i++) {
            out[i] = (float) Math.exp(-(rayBeta[i] * odR + mieBeta * odM + ozoneBeta[i] * odO));
        }
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
        destroyGuideImages();
        exposure.destroy();
        if (displayPipeline != null) {
            displayPipeline.destroy();
            displayPipeline = null;
        }
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
        bindlessTextureCapacity = 0;
        materialBindingsReady = false;
        materialEpochTraceGate = false;
        RtTerrainMaterials.INSTANCE.destroy();
        if (pushRing != null) {
            for (RtBuffer b : pushRing) {
                if (b != null) {
                    b.destroy();
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

    private static long blockAtlasView() {
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
                    hdrCompositePipeline.dispatch(cmd, src.width, src.height, CausticaConfig.Rt.Hdr.paperWhiteNits());
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
        return CausticaConfig.Rt.Hdr.enabled()
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
            sdrPresentPipeline.dispatch(cmd, dst.width, dst.height, CausticaConfig.Rt.Hdr.paperWhiteNits());

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
