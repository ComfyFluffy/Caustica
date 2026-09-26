package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.support.SharedResource;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackendFactory;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserRoute;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserSignalEncoding;
import dev.comfyfluffy.caustica.renderer.presentation.RtFramePresenter;
import dev.comfyfluffy.caustica.renderer.presentation.BorrowedImage;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GraphicsUse;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GraphicsQueue;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanBarriers;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.VulkanDiagnostics;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.view.ViewMedium;
import dev.comfyfluffy.caustica.api.view.SpatialMedium;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.resource.ResourceOwners;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.engine.session.EngineSessionServices;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.SharcFrameData;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.WorldPushData;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.WorldPushData.Float2;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.WorldPushData.Float3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCopyImageInfo2;
import org.lwjgl.vulkan.VkImageCopy2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.comfyfluffy.caustica.renderer.raytracing.RtProgramBackend;
import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.nvidia.ngx.DlssRayReconstruction;
import dev.comfyfluffy.caustica.nvidia.ngx.DlssSuperResolution;
import dev.comfyfluffy.caustica.renderer.presentation.RtExposure;
import dev.comfyfluffy.caustica.renderer.presentation.PresentationResources;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceExtent;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceImages;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceResources;
import dev.comfyfluffy.caustica.renderer.runtime.pass.RtPassSchedulerBackend;
import dev.comfyfluffy.caustica.renderer.raytracing.scene.RtRetainedSceneBackend;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings;
import dev.comfyfluffy.caustica.renderer.presentation.RtToneLut;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;

/**
 * Records trace, reconstruction, and presentation from one captured frame. Temporal inputs become
 * history only after submission; frame resources remain owned through the host's final UI consumer.
 */
public final class RtFrameRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(RtFrameRenderer.class);
    // WorldPushData and its serializer are generated from Slang's reflected Std430DataLayout. Java never
    // owns or calculates a shader byte offset, struct size, array stride, or fixed-array capacity.
    private static final int WORLD_PUSH_SIZE = WorldPushData.BYTE_SIZE;
    // Modulus of the world-pinned procedural domain anchor. Documented engine constant, not a per-surface
    // tunable: a very low-frequency field could alias across it where the wave spectrum does not.
    private static final int PROCEDURAL_ANCHOR_MASK = 4095;
    // Host-frame serial also detects gaps where the host rendered without an RT composite.
    private volatile long frameCounter;

    public long frameCounter() {
        return frameCounter;
    }

    private final VulkanDeviceContext context;
    private final RtProgramBackend programs;
    private final RtRetainedSceneBackend scenes;
    private final RtScenePublication<RtSceneRequest, RtSceneRevision> scenePublication;
    private final RtPassSchedulerBackend passes;
    private final EngineSessionServices services;
    private final RtFramePresenter presenter;
    private final RtReconstruction reconstruction;
    private final RtTelemetry telemetry;
    private final RtGpuTiming gpuTiming;
    private final RtShadowDiagnostics shadowDiagnostics = new RtShadowDiagnostics();
    private final RtFrameResources frameResources;
    private RtRenderSettings settings;
    private final RtFrameHistory history = new RtFrameHistory();
    private final RtSubmittedRevision<RtSceneRevision> submittedScenes = new RtSubmittedRevision<>();
    private SharedResource<RtCapturedFrame> frameSnapshot;
    private SharedResource<RtSceneRevision> frameScenes;
    private boolean sceneReady;
    private SceneOrigin requestedOrigin = SceneOrigin.ZERO;
    private float requestedMetersPerSceneUnit = 1.0f;
    private FrameExecution execution;
    private boolean loggedActive;
    private long debugCaptureFrameSerial = -1;
    private GpuImage debugCaptureSceneColor;
    private GpuImage debugCaptureSceneEffectColor;
    private float debugCapturePreExposure;
    private RtDenoisingSettings debugCaptureDenoising;
    private DebugProjection debugCaptureProjection;
    // Advances by one per submitted frame that continues history and by two across a break, so temporal
    // passes comparing consecutive indices follow RT history rather than raw render frames.
    private long temporalFrameIndex;

    private static final class FrameExecution {
        final GraphicsUse graphicsUse;
        final ResourceOwners resources = new ResourceOwners();
        RtFrameInput frame;
        long temporalFrameIndex;
        RtRetainedSceneBackend.PreparedTrace trace;
        RtProgramBackend.Published program;
        VulkanDeviceAddress worldPushAddress;
        // This frame's SharcFrame, or null when the frame does not use the SHaRC cache.
        VulkanDeviceAddress sharcFrameAddress;

        FrameExecution(GraphicsUse graphicsUse) {
            this.graphicsUse = graphicsUse;
            graphicsUse.keepAlive(resources);
        }
    }

    public RtFrameRenderer(VulkanDeviceContext context, RtProgramBackend programs, RtRetainedSceneBackend scenes,
                    RtPassSchedulerBackend passes, EngineSessionServices services,
                    RtFramePresenter presenter, DlssRayReconstruction rayReconstruction,
                    DlssSuperResolution upscaler,
                    DenoiserBackendFactory denoiserFactory, RtDenoisingSettings denoisingSettings,
                    RtTelemetry telemetry, RtRenderSettings settings) {
        this.settings = settings;
        this.context = Objects.requireNonNull(context, "context");
        this.programs = Objects.requireNonNull(programs, "programs");
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.scenePublication = new RtScenePublication<>(request -> RtSceneRevision.prepare(request, scenes));
        scenes.bindScenePreparationQuiescer(this::clearScenePublication);
        this.passes = Objects.requireNonNull(passes, "passes");
        this.services = Objects.requireNonNull(services, "services");
        this.presenter = Objects.requireNonNull(presenter, "presenter");
        this.reconstruction = new RtReconstruction(context, rayReconstruction, upscaler,
                denoiserFactory, denoisingSettings, telemetry);
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.gpuTiming = new RtGpuTiming(context);
        this.frameResources = new RtFrameResources(presenter, rayReconstruction, upscaler, settings.exposure());
    }

    public void configureSettings(RtRenderSettings settings) {
        this.settings = Objects.requireNonNull(settings);
    }

    /** Applies one mutually exclusive output route at the next frame boundary. */
    public void configureDenoising(RtDenoisingSettings settings) {
        settings = Objects.requireNonNull(settings, "settings");
        if (reconstruction.configured(settings)) return;
        context.waitIdle();
        reconstruction.configureAfterIdle(settings);
        resetSceneHistory();
    }

    /** Read-only access to the auto-exposure controller, for diagnostics (F3 entry, frame stats log). */
    public RtExposure exposure() {
        return presentationResources().exposure();
    }

    private TraceResources traceResources() {
        return frameResources.trace();
    }

    private TraceImages traceImages() {
        return traceResources().images();
    }

    private TraceExtent traceExtent() {
        return traceResources().extent();
    }

    private PresentationResources presentationResources() {
        return frameResources.presentation();
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
        context.backend().assertRenderThread();
        if (presentationResources().exposure().image() == null || execution != null) {
            return false;
        }

        RtFrameCapture.exportResidualExposureExr(context, traceImages().reconstructedColor(), traceExtent(),
                presentationResources().exposure(), frameCounter, outputPath);
        return true;
    }

    public record DebugImageCapture(String name, long frameSerial, int width, int height,
                                    int vulkanFormat, String encoding, float preExposure,
                                    String denoiserRoute, String signalEncoding, DebugProjection projection) { }

    /** Submitted camera-relative transform; exported image rows run opposite to Vulkan image rows. */
    public record DebugProjection(List<Float> inverseProjectionView, List<Double> cameraWorld,
                                  int renderWidth, int renderHeight, List<Float> jitterPixels,
                                  double metersPerWorldUnit, String convention) {
        String json() {
            return "{\"inverseProjectionView\":" + inverseProjectionView
                    + ",\"cameraWorld\":" + cameraWorld + ",\"renderWidth\":" + renderWidth
                    + ",\"renderHeight\":" + renderHeight + ",\"jitterPixels\":" + jitterPixels
                    + ",\"metersPerWorldUnit\":" + metersPerWorldUnit
                    + ",\"convention\":\"" + convention + "\"}";
        }

        static DebugProjection capture(RtFrameInput frame) {
            float[] values = new Matrix4f(frame.projectionView()).invert().get(new float[16]);
            var matrix = new java.util.ArrayList<Float>(16);
            for (float value : values) matrix.add(value);
            var snapshot = frame.snapshot();
            return new DebugProjection(List.copyOf(matrix),
                    List.of(snapshot.cameraX(), snapshot.cameraY(), snapshot.cameraZ()),
                    frame.extent().renderWidth(), frame.extent().renderHeight(),
                    List.of(frame.jitterX(), frame.jitterY()), snapshot.metersPerWorldUnit(),
                    "Column-major inverse unjittered projection * view rotation; camera-relative world units. "
                    + "Top-down post UV=((x+0.5)/W,1-(y+0.5)/H); primary-depth UV="
                    + "((x+0.5+jitterX)/renderWidth,1-(y+0.5-jitterY)/renderHeight). "
                    + "q=M*(2*UV-1,reverseZ,1); world=cameraWorld+q.xyz/q.w; zero depth is environment.");
        }
    }

    public static List<String> debugImageNames() {
        return List.of("display-color", "scene-color", "scene-effect-color", "reconstructed-color", "trace-color", "normal-roughness", "diffuse-albedo",
                "specular-albedo", "depth", "primary-depth", "motion", "specular-motion", "nrd-view-z",
                "nrd-diffuse", "nrd-specular", "nrd-stable-radiance", "stable-plane-metadata");
    }

    /** Synchronous render-thread capture of a submitted frame, with no display or exposure transform. */
    public DebugImageCapture exportLatestDebugImage(String name, Path output) throws IOException {
        context.backend().assertRenderThread();
        if (!debugImageNames().contains(name)) throw new IllegalArgumentException("Unknown debug image: " + name);
        if (debugCaptureFrameSerial < 0 || execution != null) return null;
        TraceImages images = traceImages();
        GpuImage image = switch (name) {
            case "display-color" -> presentationResources().displayImage();
            case "scene-color" -> debugCaptureSceneColor;
            case "scene-effect-color" -> debugCaptureSceneEffectColor;
            case "reconstructed-color" -> images.reconstructedColor();
            case "trace-color" -> images.traceColor();
            case "normal-roughness" -> images.normalRoughness();
            case "diffuse-albedo" -> images.diffuseAlbedo();
            case "specular-albedo" -> images.specularAlbedo();
            case "depth" -> images.depth();
            case "primary-depth" -> images.primaryDepth();
            case "motion" -> images.motion();
            case "specular-motion" -> images.specularMotion();
            case "nrd-view-z" -> images.nrdViewZ();
            case "nrd-diffuse" -> images.diffuseRadianceHitDistance();
            case "nrd-specular" -> images.specularRadianceHitDistance();
            case "nrd-stable-radiance" -> images.nrdStableRadiance();
            case "stable-plane-metadata" -> images.stablePlaneMetadata();
            default -> throw new IllegalArgumentException(name);
        };
        String encoding = switch (name) {
            case "display-color" -> "RGBA: SDR display output, normalized UNORM8, before Minecraft UI composition";
            case "reconstructed-color", "trace-color" -> "RGB: ACEScg scene radiance * preExposure";
            case "scene-color" -> "RGB: post-chain ACEScg radiance * preExposure; effect diagnostic modes override the signal";
            case "scene-effect-color" -> "RGB: jittered trace-resolution scene effects before reconstruction; ACEScg radiance * preExposure; diagnostic modes override the signal";
            case "nrd-stable-radiance" -> "RGB: ACEScg scene-linear radiance (unexposed)";
            case "stable-plane-metadata" -> "R: available planes / 3; G: dominant plane index / 2; "
                    + "B: dominant endpoint delta depth / 9; A: dominant path crossed transmission (0 or 1)";
            case "normal-roughness" -> "RGB: world-space unit normal; A: roughness";
            case "diffuse-albedo", "specular-albedo" -> "RGB: dimensionless ACEScg BSDF estimate; A: 1";
            case "depth" -> "R: dominant virtual endpoint reverse-Z device depth (dimensionless)";
            case "primary-depth" -> "R: physical first-hit reverse-Z device depth; zero denotes environment";
            case "motion", "specular-motion" -> "RG: previous minus current position in render pixels";
            case "nrd-view-z" -> "R: absolute view Z in scene distance units; invalid: 65504";
            case "nrd-diffuse", "nrd-specular" -> "RGB: demodulated scene-linear radiance; A: hit distance; scale: "
                    + (debugCaptureDenoising.route() == DenoiserRoute.TEMPORAL_DENOISER ? "1/4096 (peak limited to 250)" : "1")
                    + "; packing: "
                    + debugCaptureDenoising.signalEncoding().name();
            default -> throw new IllegalArgumentException(name);
        };
        if (debugCaptureDenoising.route() == DenoiserRoute.TEMPORAL_DENOISER
                && List.of("normal-roughness", "nrd-view-z", "nrd-diffuse", "nrd-specular").contains(name)) {
            encoding += "; NRD plane 0 input (last evaluated plane)";
        }
        DebugImageCapture result = new DebugImageCapture(name, debugCaptureFrameSerial,
                image.width(), image.height(), image.format(), encoding, debugCapturePreExposure,
                debugCaptureDenoising.route().name(), debugCaptureDenoising.signalEncoding().name(), debugCaptureProjection);
        RtFrameCapture.exportRaw(context, BorrowedImage.of(image), output, java.util.Map.of(
                "causticaBuffer", name, "causticaFrame", Long.toString(result.frameSerial()),
                "causticaEncoding", encoding, "causticaVulkanFormat", Integer.toString(image.format()),
                "causticaPreExposure", Float.toString(result.preExposure()),
                "causticaDenoiserRoute", result.denoiserRoute(), "causticaSignalEncoding", result.signalEncoding(),
                "causticaChannels", "Native components in RGBA; absent G/B = 0, absent A = 1",
                "causticaOrientation", "Top row first; vertically flipped from Vulkan image rows",
                "causticaProjection", result.projection().json()));
        return result;
    }

    public boolean requiresSourceWorldFallback() {
        return !sceneReady;
    }

    /**
     * Complete the material epoch while startup is retaining source presentation. The runtime calls this
     * only after the scene update has applied the pending full clear, so activation depends on resource
     * readiness rather than an additional tick or rendered frame.
     */
    public boolean completeStartupBoundary() {
        services.progress();
        requestScenePreparation();
        latchSceneReadiness();
        return sceneReady;
    }

    private void requestScenePreparation() {
        var request = RtSceneRequest.capture(programs::acquire, scenes::captureSnapshot,
                telemetry::publicationCutoff, requestedOrigin, requestedMetersPerSceneUnit);
        scenePublication.request(request.key(), request);
    }

    /** Latches completed-scene availability before the host decides which renderer owns this frame. */
    public void latchSceneReadiness() {
        try (var revision = scenePublication.acquire()) {
            sceneReady = revision != null && revision.get().traceScenes() != null;
        }
    }

    /** Capture the immutable host frame for the next composite. Called from the host render adapter. */
    public void captureFrame(FrameSnapshot snapshot) {
        try (var ignored = telemetry.frame().stage("frame.capture")) {
            requestedOrigin = snapshot.sceneOrigin();
            requestedMetersPerSceneUnit = (float) snapshot.metersPerWorldUnit();
            services.progress();
            releaseCapturedFrame();
            try (RtTelemetry.Scope sceneCapture = telemetry.frame().stage("frame.captureScenes")) {
                requestScenePreparation();
                frameScenes = scenePublication.acquire();
                var captured = RtCapturedFrame.capture(Objects.requireNonNull(snapshot, "snapshot"));
                frameSnapshot = SharedResource.owned(captured, RtCapturedFrame::close);
            }
        }
    }

    /** Reset exposure filtering after an explicit render-state invalidation such as F3+A. */
    public void resetExposureHistory() {
        presentationResources().exposure().requestReset();
    }

    /**
     * Invalidate the published presentation snapshot at the start of host rendering. Menu and loading
     * frames do not call {@link #composite()}, so retaining the previous scene snapshot would present stale
     * HDR content instead of selecting the SDR-to-PQ path.
     */
    public void beginFrame() {
        if (execution != null) {
            throw new IllegalStateException("Previous RT graphics use was never completed");
        }
        debugCaptureFrameSerial = -1;
        debugCaptureSceneColor = null;
        debugCaptureSceneEffectColor = null;
        frameCounter++;
        telemetry.beginRenderFrame();
        telemetry.beginFrameIfInactive();
        presenter.beginFrame();
        releaseCapturedFrame();
        execution = null;
    }

    /** Records UI passes in an owned heap command buffer after the host UI layer is available. */
    public void recordUiPasses(dev.comfyfluffy.caustica.api.vulkan.OwnedGpuImage uiLayer) {
        try (var ignored = telemetry.frame().stage("frame.recordUi")) {
            Objects.requireNonNull(uiLayer, "uiLayer");
            if (execution == null || execution.trace == null || frameSnapshot == null) {
                throw new IllegalStateException("no retained frame is available for UI recording");
            }
            try (RtFrameCommands commands = new RtFrameCommands(context, gpuTiming, execution.graphicsUse,
                    telemetry.frameSerial(), telemetry.frame())) {
                execution.graphicsUse.keepAlive(uiLayer.retain());
                VkCommandBuffer commandBuffer = commands.heap("UI extensions");
                passes.beginFrame(passFrame(commandBuffer, execution.graphicsUse,
                        new RtPassSchedulerBackend.UiState(uiLayer, execution.frame.projectionView().get(new float[16]),
                                execution.trace.tlasDescriptor())));
                try {
                    services.passes().recordUi();
                } finally {
                    passes.endFrame();
                }
                commands.submit(context.backend().createGraphicsSubmission());
            }
        }
    }

    /** Signals this frame's completion reservation after the host has recorded any UI consumers. */
    public void finishGraphicsUse() {
        try (var ignored = telemetry.frame().stage("frame.finishGraphicsUse")) {
            if (execution == null) {
                return;
            }
            GraphicsUse graphicsUse = execution.graphicsUse;
            execution = null;
            GraphicsSubmission submission = context.backend().createGraphicsSubmission();
            context.graphics().resolveGraphicsUse(submission, graphicsUse);
        }
    }

    public void endFrame() {
        telemetry.endFrame();
    }

    public boolean composite(long nativeColorImage, int width, int height) {
        try (var ignored = telemetry.frame().stage("frame.composite")) {
            return compositeFrame(nativeColorImage, width, height);
        }
    }

    private boolean compositeFrame(long nativeColorImage, int width, int height) {
        VulkanDiagnostics.setInFlight("graphics-latest", "frame=" + frameCounter + " size=" + width + "x" + height);
        SharedResource<RtCapturedFrame> captured = frameSnapshot;
        if (captured == null) {
            // No scene was captured this frame. Skip RT so the present path falls back to the host image.
            return false;
        }
        FrameSnapshot snapshot = captured.get().inputs();
        try {
            try (var ignored = telemetry.frame().stage("frame.ensurePresentation")) {
                ensurePresentationResources(width, height);
            }
            if (frameScenes == null || frameScenes.get().traceScenes() == null) return false;
            var traceRevision = frameScenes.get().traceScenes().get();
            if (!traceRevision.contains(snapshot.view().entryScene())) return false;
            snapshot = snapshot.withSceneCoordinates(traceRevision.origin(), traceRevision.metersPerSceneUnit());
            if (history.changesScene(snapshot)) resetSceneHistory();
            recordFrame(context, captured, frameScenes, nativeColorImage, snapshot);
            if (!loggedActive) {
                loggedActive = true;
                LOGGER.info("RT composite active: {}x{}, RT output replaces the world target", width, height);
            }
            return true;
        } catch (Throwable t) {
            presenter.invalidateRenderedFrame();
            try {
                finishGraphicsUse();
            } catch (Throwable resolutionFailure) {
                t.addSuppressed(resolutionFailure);
            }
            resetSceneHistory();
            LOGGER.error("RT composite failed", t);
            if (t instanceof RuntimeException runtime) throw runtime;
            if (t instanceof Error error) throw error;
            throw new IllegalStateException("RT composite failed", t);
        }
    }

    /** Build every display-sized resource while startup is still presenting the source renderer. */
    public boolean ensurePresentationResourcesReady(long sceneId, int width, int height) {
        if (sceneId == 0L) {
            return false;
        }
        try {
            ensurePresentationResources(width, height);
            return true;
        } catch (IOException failure) {
            // Shader and pipeline bring-up has no partial success worth presenting around.
            throw new IllegalStateException("RT presentation resource bring-up failed", failure);
        }
    }

    private void ensurePresentationResources(int width, int height)
            throws IOException {
        presentationResources().configureExposure(settings.exposure());
        frameResources.presentation().ensurePipelines(context, settings.hdrLutNits());
        if (frameResources.ensureSized(context, width, height, reconstruction.settings().route(),
                reconstruction::closeBackendAfterIdle)) {
            resetSceneHistory();
        }
        reconstruction.ensureBackend(traceExtent());
        frameResources.ensureSharc(context, settings.sharc().enabled() && programs.compilesSharc(),
                settings.sharc().cacheExponent());
    }

    /**
     * Invalidates temporal consumers before the host replaces pack-owned images. Resource owners and
     * program registrations retain their image epochs until the graphics timeline retires them.
     */
    public void onResourceReloadStart() {
        resetSceneHistory();
    }

    /** Notify runtime-activation-scoped passes after the host has published the replacement pack epoch. */
    public void onResourcePackApplied() {
        services.progress();
    }

    /** Invalidate renderer state that cannot cross a provider-requested scene discontinuity. */
    public void resetSceneHistory() {
        resetExposureHistory();
        RtSharcCache sharc = frameResources.sharc();
        if (sharc != null) sharc.requestReset();
        reconstruction.resetHistory();
        presenter.resetSceneHistory();
        history.reset();
        submittedScenes.close();
        scenes.releaseView(this);
    }

    private void recordFrame(VulkanDeviceContext ctx,
                             SharedResource<RtCapturedFrame> captured,
                             SharedResource<RtSceneRevision> revision,
                             long nativeColorImage,
                             FrameSnapshot snapshot) {
        GraphicsSubmission submission;
        try (var ignored = telemetry.frame().stage("frame.createSubmission")) {
            submission = ctx.backend().createGraphicsSubmission();
        }
        GraphicsQueue graphics = ctx.graphics();
        GraphicsUse graphicsUse = graphics.beginGraphicsUse();
        execution = new FrameExecution(graphicsUse);
        graphicsUse.keepAlive(captured.retain());
        graphicsUse.keepAlive(revision.retain());
        RtProgramBackend.Published program = revision.get().program().get();
        presentationResources().exposure().beginFrame(graphicsUse, telemetry.frameSerial());
        execution.frame = history.capture(snapshot, frameCounter, System.nanoTime(), traceExtent(),
                reconstruction.settings().route(), exposure().preExposure(), settings.jitterSignX(), settings.jitterSignY());
        execution.temporalFrameIndex = temporalFrameIndex + (execution.frame.historyContinuous() ? 1 : 2);
        try (RtTelemetry.Scope ignored = telemetry.frame().stage("frame.assembleScenes")) {
            try (var previous = execution.frame.historyContinuous() ? submittedScenes.acquire() : null) {
                scenes.beginFrame(revision.get().traceScenes(),
                        previous == null ? null : previous.get().traceScenes(), graphicsUse);
            }
        }
        if (!execution.frame.historyContinuous()) {
            reconstruction.resetHistory();
            telemetry.frame().count(execution.frame.historyBreak().metric, 1L);
        }
        int debugView = settings.debugView();
        try (RtFrameCommands commands = new RtFrameCommands(ctx, gpuTiming, graphicsUse,
                telemetry.frameSerial(), telemetry.frame());
             MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd;
            try (var ignored = telemetry.frame().stage("frame.beginTraceCommands")) {
                cmd = commands.heap("world resources and trace");
            }
            recordTrace(ctx, cmd, stack, graphicsUse, program, execution.frame, commands,
                    revision.get().publicationCutoff());
            var output = reconstruction.record(commands, stack, graphicsUse, execution.frame,
                    traceResources(), source -> recordSceneEffects(commands, stack, graphicsUse, source));
            VkCommandBuffer postCommands;
            try (var ignored = telemetry.frame().stage("frame.beginPostCommands")) {
                postCommands = commands.heap("post processing and display");
            }
            recordPostProcessing(ctx, commands, postCommands, stack,
                    graphicsUse, output, nativeColorImage, debugView);
            try (var ignored = telemetry.frame().stage("frame.submitCommands")) {
                commands.submit(submission);
            }
            debugCaptureFrameSerial = telemetry.frameSerial();
            debugCapturePreExposure = execution.frame.preExposure();
            debugCaptureDenoising = reconstruction.settings();
            debugCaptureProjection = DebugProjection.capture(execution.frame);
            history.submitted(execution.frame);
            temporalFrameIndex = execution.temporalFrameIndex;
            submittedScenes.submitted(revision);
            reconstruction.submitted(execution.frame);
        }
        // Submission makes every frame-owned address reachable until the final overlay consumer.
        presentationResources().exposure().markStateReadbackUse(graphicsUse);
        presenter.publish(new RtFramePresenter.RenderedFrame(
                presentationResources().hdrDisplayImage(), traceImages().motion(), traceImages().depth(),
                traceExtent().renderWidth(), traceExtent().renderHeight(),
                new Matrix4f(execution.frame.projectionView()), new Matrix4f(execution.frame.previousProjectionView()),
                settings.hdr() && debugView == 0));
    }

    /** Release unsubmitted input ownership; accepted executions keep their independent claims. */
    public void releaseCapturedFrame() {
        var captured = frameSnapshot;
        var revision = frameScenes;
        frameSnapshot = null;
        frameScenes = null;
        try (revision; captured) {
            // Detach both claims before disposal; submitted executions retain their own copies.
        }
    }

    private void clearScenePublication() {
        sceneReady = false;
        history.reset();
        submittedScenes.close();
        scenes.releaseView(this);
        releaseCapturedFrame();
        scenePublication.clear();
    }

    private void recordTrace(VulkanDeviceContext ctx, VkCommandBuffer cmd, MemoryStack stack,
            GraphicsUse graphicsUse, RtProgramBackend.Published program, RtFrameInput frame,
            RtFrameCommands commands, long publicationCutoff) {
        FrameSnapshot snapshot = frame.snapshot();
        SceneOrigin sceneOrigin = snapshot.sceneOrigin();
        SceneId entryScene = snapshot.view().entryScene();
        GpuBuffer pushBuf = ctx.createMappedGpuUploadBuffer(WORLD_PUSH_SIZE,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "rt world push");
        graphicsUse.whenComplete(pushBuf::destroy);
        GpuBuffer pathScratch = traceResources().pathScratchBuffer();
        ByteBuffer push = MemoryUtil.memByteBuffer(pushBuf.mapped(), WORLD_PUSH_SIZE);
        Matrix4f frameInvViewProj = new Matrix4f(frame.projectionView()).invert();
        int flags = snapshot.proceduralSurfaceAnimationEnabled() ? 0b10000 : 0;
        float time = (float) (snapshot.timeSeconds() % 3600.0);
        // Procedural domain anchor: the scene rebase origin reduced mod 4096 (kept small for shader
        // float precision). hitPos.xz (rebased) + anchor reconstructs a world-pinned coordinate, so a
        // pattern stays fixed in the world as the player moves and the rebase origin shifts.
        double proceduralPeriod = PROCEDURAL_ANCHOR_MASK + 1.0;
        Float3 proceduralDomainOffset = new Float3(sceneOrigin.wrappedX(proceduralPeriod),
                sceneOrigin.wrappedY(proceduralPeriod), sceneOrigin.wrappedZ(proceduralPeriod));
        EnvironmentBinding<?> environment = scenes.content(entryScene, graphicsUse).environment();
        telemetry.frameAssembled(publicationCutoff);
        EnvironmentPush environmentState = environmentPush(environment,
                environment == null ? 0 : program.resolve(environment.implementation()));
        var geometryHistory = scenes.geometry(entryScene, graphicsUse);
        SceneOrigin previousOrigin = geometryHistory.previousOrigin();
        Float3 previousOriginDelta = new Float3((float) (previousOrigin.x() - sceneOrigin.x()),
                (float) (previousOrigin.y() - sceneOrigin.y()), (float) (previousOrigin.z() - sceneOrigin.z()));

        new WorldPushData(
                frameInvViewProj,
                frame.cameraOffset(),
                (int) frameCounter,
                new Matrix4f(frame.previousProjectionView()),
                frame.cameraDelta(),
                new Float2(frame.jitterX(), frame.jitterY()),
                flags,
                settings.maxBounces(),
                time,
                proceduralDomainOffset,
                new Matrix4f(frame.projectionView()),
                frame.previousProceduralTime(),
                // Must be the SAME value the exposure resolve divides out this frame (it reads it
                // from the same RtExposure accessor), or the two stop cancelling.
                frame.preExposure(),
                environmentState.bindingData(),
                environmentState.implementation(),
                geometryHistory.currentInstances().value(),
                geometryHistory.previousInstances() == null ? 0L : geometryHistory.previousInstances().value(),
                geometryHistory.previousMask(), previousOriginDelta,
                settings.rrResponsivity()
        ).write(push);
        pushBuf.flush(0L, WORLD_PUSH_SIZE);
        VulkanBarriers.memoryBarrier(cmd, stack);
        RtRetainedSceneBackend.PreparedLighting lighting;
        try (RtTelemetry.Scope ignored = telemetry.frame().stage("frame.prepareLighting")) {
            lighting = scenes.prepareLighting(entryScene,
                    new RtRetainedSceneBackend.LightingFrame(this, traceExtent().renderWidth(), traceExtent().renderHeight(),
                            frameCounter, (float) snapshot.metersPerWorldUnit(),
                            frame.historyContinuous()), cmd, graphicsUse);
        }
        boolean lightingFinished = false;
        try {
            RtRetainedSceneBackend.PreparedTrace trace;
            try (RtTelemetry.Scope ignored = telemetry.frame().stage("frame.finishTrace")) {
                trace = scenes.finishTrace(entryScene, graphicsUse, lighting);
            }
            execution.trace = trace;
            execution.program = program;
            execution.worldPushAddress = pushBuf.deviceAddress();
            // Tables exist only while the cache is on for a backend whose programs carry the SHaRC stages.
            RtSharcCache sharc = frameResources.sharc();
            execution.sharcFrameAddress = null;
            if (sharc != null) {
                Float3 offset = frame.cameraOffset();
                SharcFrameData.Float3 camera = new SharcFrameData.Float3(offset.x(), offset.y(), offset.z());
                SharcFrameData.Float3 previousCamera = sharc.beginFrame(sceneOrigin, camera, settings.sharc());
                GpuBuffer sharcFrame = sharc.writeFrame(ctx, (int) frameCounter, camera, previousCamera,
                        frame.preExposure(), traceExtent().renderWidth(), traceExtent().renderHeight());
                graphicsUse.whenComplete(sharcFrame::destroy);
                execution.sharcFrameAddress = sharcFrame.deviceAddress();
            }
            ByteBuffer roots = stack.calloc(RtBindings.WORLD_PUSH_CONSTANT_SIZE).order(ByteOrder.nativeOrder());
            writeFrameRoots(roots, pushBuf.deviceAddress(), snapshot, pathScratch, program);
            program.writeCompositionDataAddress(roots);
            trace.writeWorldRoots(roots);

            passes.beginFrame(passFrame(cmd, graphicsUse, null));

            try {
                services.passes().recordWorldResources();
            } finally {
                passes.endFrame();
            }
            VulkanBarriers.worldResourcesToPrimary(cmd, stack);

            try (var gpu = commands.time("build stable planes");
                 var ignored = RtDebugLabels.scope(ctx, cmd, "build stable planes");
                 RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.buildStablePlanes")) {
                program.pipeline().trace(cmd, traceExtent().renderWidth(), traceExtent().renderHeight(),
                        roots, 0, trace.hitTable());
            }
            try (var gpu = commands.time("local NEE bake");
                 var ignored = RtDebugLabels.scope(ctx, cmd, "local NEE bake");
                 RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.bakeLocal")) {
                scenes.bakeLocal(lighting, cmd,
                        storageIndex(traceImages().nrdViewZ()), storageIndex(traceImages().motion()));
            }
            if (sharc != null) {
                // SHaRC update -> resolve -> query: the resolve drains the update's accumulation into
                // the resolved table that the query fill reads, so each pass waits for the one before.
                sharc.recordPendingClear(cmd);
                VulkanBarriers.memoryBarrier(cmd, stack);
                try (var gpu = commands.time("SHaRC sparse update");
                     var ignored = RtDebugLabels.scope(ctx, cmd, "SHaRC sparse update");
                     RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.sharcUpdate")) {
                    int tile = settings.sharc().updateTileSize();
                    program.pipeline().trace(cmd, Math.ceilDiv(traceExtent().renderWidth(), tile),
                            Math.ceilDiv(traceExtent().renderHeight(), tile), roots,
                            RtProgramBackend.SHARC_UPDATE_RAYGEN_INDEX, trace.hitTable());
                }
                VulkanBarriers.memoryBarrier(cmd, stack);
                try (var gpu = commands.time("SHaRC resolve");
                     var ignored = RtDebugLabels.scope(ctx, cmd, "SHaRC resolve");
                     RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.sharcResolve")) {
                    program.sharcResolve().dispatch(cmd, execution.sharcFrameAddress, sharc.capacity());
                }
                VulkanBarriers.memoryBarrier(cmd, stack);
            }
            // The first frames after a cache reset trace the ordinary fill while the cache warms up.
            int fillRaygen = sharc != null && sharc.queryReady() ? RtProgramBackend.SHARC_QUERY_RAYGEN_INDEX : 1;
            RtShadowDiagnostics.Reservation shadowCounters = null;
            if (RtShadowDiagnostics.ENABLED) {
                shadowCounters = shadowDiagnostics.begin(ctx, cmd, stack, graphicsUse, telemetry.frameSerial(), "fill stable planes");
                roots.putLong(RtBindings.WORLD_SHADOW_DIAGNOSTICS_ADDRESS_OFFSET, shadowCounters.address().value());
            }
            try (var gpu = commands.time("fill stable planes");
                 var ignored = RtDebugLabels.scope(ctx, cmd, "fill stable planes");
                 RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.fillStablePlanes")) {
                program.pipeline().trace(cmd, traceExtent().renderWidth(), traceExtent().renderHeight(),
                        RtDenoiserState.PLANE_COUNT, roots, fillRaygen, trace.hitTable());
            }
            try (var gpu = commands.time("resolve stable planes");
                 var ignored = RtDebugLabels.scope(ctx, cmd, "resolve stable planes")) {
                VulkanBarriers.memoryBarrier(cmd, stack);
                program.pipeline().trace(cmd, traceExtent().renderWidth(), traceExtent().renderHeight(),
                        roots, RtProgramBackend.RESOLVE_STABLE_PLANES_RAYGEN_INDEX, trace.hitTable());
            }
            if (shadowCounters != null) {
                shadowCounters.copy(cmd, stack, graphicsUse);
                roots.putLong(RtBindings.WORLD_SHADOW_DIAGNOSTICS_ADDRESS_OFFSET, 0L);
            }
            try (var checkpoint = commands.checkpoint(cmd, "post-fill memory barrier")) {
                VulkanBarriers.memoryBarrier(cmd, stack); // RT writes visible to reconstruction reads
            }
            try (var checkpoint = commands.checkpoint(cmd, "finish lighting")) {
                scenes.finishLighting(entryScene, lighting, cmd, graphicsUse);
            }
            lightingFinished = true;
        } catch (Throwable failure) {
            scenes.abandonLighting(entryScene, lighting);
            throw failure;
        }
    }

    private dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage recordSceneEffects(RtFrameCommands commands, MemoryStack stack,
            GraphicsUse graphicsUse, GpuImage source) {
        VkCommandBuffer cmd = commands.heap("scene effects before reconstruction");
        passes.beginFrame(passFrame(cmd, graphicsUse, null, source,
                traceImages().sceneEffectColorA(), traceImages().sceneEffectColorB()));
        try (var gpu = commands.time("scene effects");
             var label = RtDebugLabels.scope(context, cmd, "scene effects");
             var cpu = telemetry.frame().stage("frame.sceneEffects")) {
            VulkanBarriers.memoryBarrier(cmd, stack);
            services.passes().recordSceneEffects();
            VulkanBarriers.memoryBarrier(cmd, stack);
            debugCaptureSceneEffectColor = passes.sceneColor();
            return (dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage) passes.sceneColor();
        } finally {
            passes.endFrame();
        }
    }

    private void recordPostProcessing(VulkanDeviceContext ctx, RtFrameCommands commands, VkCommandBuffer cmd, MemoryStack stack,
            GraphicsUse graphicsUse, dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage output, long dstImage, int debugView) {
        passes.beginFrame(passFrame(cmd, graphicsUse, null, output));
        try {
            VulkanBarriers.memoryBarrier(cmd, stack); // reconstructed output visible to exposure histogram

            // Meter the scene after participating media composition, before exposure-dependent effects.
            try (var ignored = RtDebugLabels.scope(ctx, cmd, "exposure");
                 RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.exposure")) {
                presentationResources().exposure().record(ctx, cmd, stack, (dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage) passes.sceneColor(),
                        traceImages().depth(), traceImages().diffuseAlbedo());
                presentationResources().exposure().recordStateReadback(cmd, stack);
            }
            VulkanBarriers.memoryBarrier(cmd, stack); // exposure image visible to downstream passes

            try (var ignored = RtDebugLabels.scope(ctx, cmd, "post chain");
                 RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.postChain")) {
                services.passes().recordPostEffects();
            }
            debugCaptureSceneColor = (GpuImage) passes.sceneColor();
            RtToneLut displayLookLut = presentationResources().lookLut();
            try (var ignored = RtDebugLabels.scope(ctx, cmd, "map RT to display");
                 RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.displayMap")) {
                presentationResources().displayPipeline().dispatch(cmd, presentationResources().displayImage(),
                        passes.sceneColor(), presentationResources().exposure().image(),
                        presentationResources().hdrDisplayImage(), presentationResources().sdrToneLut(),
                        presentationResources().hdrToneLut(), displayLookLut,
                        settings.hdr(), settings.exposure().gamma(),
                        presentationResources().loadedHdrLutNits(), true, settings.toneMapping());
            }
            VulkanBarriers.memoryBarrier(cmd, stack); // display output visible to debug composite

            if (debugView != 0) {
                // Debug content is composited only after the real scene has completed trace, reconstruction,
                // exposure, and display mapping. It therefore observes the renderer without perturbing
                // exposure history or feeding literal diagnostic colors through ACES. Debug presentation
                // remains SDR for now; a PQ swapchain uses the existing SDR->PQ conversion path.
                try (var ignored = RtDebugLabels.scope(ctx, cmd, "debug present");
                     RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.debugPresent")) {
                    presentationResources().debugPresentPipeline().dispatch(cmd,
                            presentationResources().displayImage(), traceImages().normalRoughness(),
                            traceImages().diffuseAlbedo(), traceImages().depth(), traceImages().motion(),
                            traceImages().specularAlbedo(), traceImages().specularMotion(),
                            traceImages().reconstructedColor(), presentationResources().exposure().image(),
                            traceImages().traceColor(), traceImages().stablePlaneMetadata(),
                            presentationResources().exposure().stateBuffer(), debugView,
                            settings.exposure().centerWeightSigma(),
                            settings.exposure().centerWeightFloor());
                }
            }
            VulkanBarriers.memoryBarrier(cmd, stack);

            try (var ignored = RtDebugLabels.scope(ctx, cmd, "copy composite to main target");
                 RtTelemetry.Scope ignoredStats = telemetry.frame().stage("frame.copyOutput")) {
                VK13.vkCmdCopyImage2(cmd, VkCopyImageInfo2.calloc(stack).sType$Default()
                        .srcImage(presentationResources().displayImage().image()).srcImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .dstImage(dstImage).dstImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .pRegions(copyRegion(stack, traceExtent().displayWidth(), traceExtent().displayHeight())));
            }
            VulkanBarriers.memoryBarrier(cmd, stack);
        } finally {
            passes.endFrame();
        }
    }

    private RtPassSchedulerBackend.FrameState passFrame(
            VkCommandBuffer commandBuffer, GraphicsUse graphicsUse,
            RtPassSchedulerBackend.UiState ui) {
        return passFrame(commandBuffer, graphicsUse, ui, traceImages().reconstructedColor());
    }

    private RtPassSchedulerBackend.FrameState passFrame(VkCommandBuffer commandBuffer, GraphicsUse graphicsUse,
                                                         RtPassSchedulerBackend.UiState ui, GpuImage output) {
        return passFrame(commandBuffer, graphicsUse, ui, output,
                presentationResources().postColorA(), presentationResources().postColorB());
    }

    private RtPassSchedulerBackend.FrameState passFrame(VkCommandBuffer commandBuffer, GraphicsUse graphicsUse,
            RtPassSchedulerBackend.UiState ui, GpuImage output, GpuImage targetA, GpuImage targetB) {
        return new RtPassSchedulerBackend.FrameState(commandBuffer, graphicsUse, execution.resources,
                execution.temporalFrameIndex,
                execution.frame.snapshot().view(), execution.frame.snapshot().timeSeconds(),
                execution.frame.snapshot().metersPerWorldUnit(),
                traceExtent().renderWidth(), traceExtent().renderHeight(), output,
                presentationResources().exposure().image(), targetA,
                targetB, traceImages().depth(), traceImages().primaryDepth(),
                new Matrix4f(execution.frame.projection()).mul(execution.frame.viewRotation()).invert().get(new float[16]),
                execution.trace.tlasDescriptor(),
                new float[] { execution.frame.cameraOffset().x(), execution.frame.cameraOffset().y(),
                        execution.frame.cameraOffset().z() },
                new float[] { execution.frame.jitterX(), execution.frame.jitterY() },
                execution.frame.preExposure(), spatialMediumImplementation(execution.frame.snapshot().view().spatialMedium(),
                        execution.program) != 0, this::recordVisibility, ui);
    }

    private void recordVisibility(VkCommandBuffer commandBuffer, VulkanDeviceAddress rays,
                                  VulkanDeviceAddress results, int width, int height, boolean volumeLighting) {
        try (MemoryStack stack = MemoryStack.stackPush();
             var ignored = RtDebugLabels.scope(context, commandBuffer,
                     volumeLighting ? "volume direct lighting" : "material visibility")) {
            ByteBuffer roots = stack.calloc(RtBindings.WORLD_PUSH_CONSTANT_SIZE).order(ByteOrder.nativeOrder());
            writeFrameRoots(roots, execution.worldPushAddress, execution.frame.snapshot(),
                    traceResources().pathScratchBuffer(), execution.program);
            execution.program.writeCompositionDataAddress(roots);
            execution.trace.writeWorldRoots(roots);
            roots.putLong(RtBindings.WORLD_VISIBILITY_RAYS_ADDRESS_OFFSET, rays.value());
            roots.putLong(RtBindings.WORLD_VISIBILITY_RESULTS_ADDRESS_OFFSET, results.value());
            VulkanBarriers.memoryBarrier(commandBuffer, stack);
            RtShadowDiagnostics.Reservation shadowCounters = null;
            if (RtShadowDiagnostics.ENABLED) {
                shadowCounters = shadowDiagnostics.begin(context, commandBuffer, stack, execution.graphicsUse,
                        telemetry.frameSerial(), volumeLighting ? "volume direct lighting" : "material visibility");
                roots.putLong(RtBindings.WORLD_SHADOW_DIAGNOSTICS_ADDRESS_OFFSET, shadowCounters.address().value());
            }
            execution.program.pipeline().trace(commandBuffer, width, height, roots,
                    volumeLighting ? RtProgramBackend.VOLUME_LIGHTING_RAYGEN_INDEX : RtProgramBackend.VISIBILITY_RAYGEN_INDEX,
                    execution.trace.hitTable());
            if (shadowCounters != null) shadowCounters.copy(commandBuffer, stack, execution.graphicsUse);
            VulkanBarriers.memoryBarrier(commandBuffer, stack);
        }
    }

    private void writeFrameRoots(ByteBuffer roots, VulkanDeviceAddress worldPushAddress, FrameSnapshot snapshot,
                                 GpuBuffer pathScratch, RtProgramBackend.Published program) {
        ByteBuffer target = roots.duplicate().order(ByteOrder.nativeOrder());
        int base = roots.position();
        target.putLong(base + RtBindings.WORLD_PUSH_ADDRESS_OFFSET, worldPushAddress.value());
        target.putLong(base + RtBindings.WORLD_PATH_QUEUE_ADDRESS_OFFSET,
                pathScratch.deviceAddress().value());
        target.putFloat(base + RtBindings.WORLD_RECONSTRUCTION_MICRO_JITTER_SCALE_OFFSET,
                execution.frame.route() == DenoiserRoute.RAY_RECONSTRUCTION ? 0.1f : 0.0f);
        target.putLong(base + RtBindings.WORLD_STABLE_PLANE_BUFFER_ADDRESS_OFFSET,
                traceResources().stablePlaneBuffer().deviceAddress().value());
        target.putInt(base + RtBindings.WORLD_OUTPUT_IMAGE_INDEX_OFFSET, storageIndex(traceImages().traceColor()));
        target.putInt(base + RtBindings.WORLD_STABLE_PLANE_METADATA_IMAGE_INDEX_OFFSET,
                storageIndex(traceImages().stablePlaneMetadata()));
        target.putInt(base + RtBindings.WORLD_NORMAL_GUIDE_INDEX_OFFSET, storageIndex(traceImages().normalRoughness()));
        target.putInt(base + RtBindings.WORLD_ALBEDO_GUIDE_INDEX_OFFSET, storageIndex(traceImages().diffuseAlbedo()));
        target.putInt(base + RtBindings.WORLD_DEPTH_GUIDE_INDEX_OFFSET, storageIndex(traceImages().depth()));
        target.putInt(base + RtBindings.WORLD_PRIMARY_DEPTH_INDEX_OFFSET, storageIndex(traceImages().primaryDepth()));
        target.putInt(base + RtBindings.WORLD_MOTION_GUIDE_INDEX_OFFSET, storageIndex(traceImages().motion()));
        target.putInt(base + RtBindings.WORLD_SPECULAR_ALBEDO_GUIDE_INDEX_OFFSET,
                storageIndex(traceImages().specularAlbedo()));
        target.putInt(base + RtBindings.WORLD_SPECULAR_MOTION_GUIDE_INDEX_OFFSET,
                storageIndex(traceImages().specularMotion()));
        target.putInt(base + RtBindings.WORLD_RESPONSIVITY_GUIDE_INDEX_OFFSET,
                storageIndex(traceImages().responsivity()));
        target.putInt(base + RtBindings.WORLD_DIFFUSE_RADIANCE_HIT_DISTANCE_INDEX_OFFSET,
                storageIndex(traceImages().diffuseRadianceHitDistance()));
        target.putInt(base + RtBindings.WORLD_SPECULAR_RADIANCE_HIT_DISTANCE_INDEX_OFFSET,
                storageIndex(traceImages().specularRadianceHitDistance()));
        target.putInt(base + RtBindings.WORLD_NRD_VIEW_Z_INDEX_OFFSET,
                storageIndex(traceImages().nrdViewZ()));
        target.putInt(base + RtBindings.WORLD_DENOISED_DIFFUSE_RADIANCE_HIT_DISTANCE_INDEX_OFFSET,
                storageIndex(traceImages().denoisedDiffuseRadianceHitDistance()));
        target.putInt(base + RtBindings.WORLD_DENOISED_SPECULAR_RADIANCE_HIT_DISTANCE_INDEX_OFFSET,
                storageIndex(traceImages().denoisedSpecularRadianceHitDistance()));
        target.putInt(base + RtBindings.WORLD_NRD_STABLE_RADIANCE_INDEX_OFFSET,
                storageIndex(traceImages().nrdStableRadiance()));
        target.putInt(base + RtBindings.WORLD_NRD_SIGNAL_ENCODING_OFFSET,
                reconstruction.settings().signalEncoding() == DenoiserSignalEncoding.YCOCG_NORMALIZED_HIT_DISTANCE
                        ? 1 : 0);
        target.putLong(base + RtBindings.WORLD_SHARC_FRAME_ADDRESS_OFFSET,
                execution.sharcFrameAddress == null ? 0L : execution.sharcFrameAddress.value());
        ViewMedium medium = snapshot.view().medium();
        int implementation = medium instanceof ViewMedium.Volume<?, ?> volume
                ? program.resolve(volume.implementation()) : 0;
        writeInitialVolumeRoots(roots, medium, implementation);
        SpatialMedium<?, ?> spatial = snapshot.view().spatialMedium();
        writeSpatialMediumRoots(roots, spatial, spatialMediumImplementation(spatial, program), snapshot.sceneOrigin());
    }

    private static int spatialMediumImplementation(SpatialMedium<?, ?> spatial, RtProgramBackend.Published program) {
        return spatial == null ? 0 : program.resolve(spatial.implementation());
    }

    static void writeSpatialMediumRoots(ByteBuffer roots, SpatialMedium<?, ?> spatial, int implementation,
                                       SceneOrigin traceOrigin) {
        ByteBuffer target = roots.duplicate().order(ByteOrder.nativeOrder());
        int base = roots.position();
        boolean active = spatial != null && implementation != 0;
        target.putLong(base + RtBindings.WORLD_SPATIAL_MEDIUM_BINDING_DATA_OFFSET,
                active ? spatial.bindingData().bits() : 0L);
        target.putLong(base + RtBindings.WORLD_SPATIAL_MEDIUM_INSTANCE_DATA_OFFSET,
                active ? spatial.instanceData().bits() : 0L);
        target.putInt(base + RtBindings.WORLD_SPATIAL_MEDIUM_IMPLEMENTATION_OFFSET, active ? implementation : 0);
        target.putInt(base + RtBindings.WORLD_SPATIAL_MEDIUM_ACTIVE_OFFSET,
                active ? 1 : 0);
        target.putFloat(base + RtBindings.WORLD_SPATIAL_MEDIUM_ORIGIN_X_OFFSET,
                active ? (float) (traceOrigin.x() - spatial.originX()) : 0);
        target.putFloat(base + RtBindings.WORLD_SPATIAL_MEDIUM_ORIGIN_Y_OFFSET,
                active ? (float) (traceOrigin.y() - spatial.originY()) : 0);
        target.putFloat(base + RtBindings.WORLD_SPATIAL_MEDIUM_ORIGIN_Z_OFFSET,
                active ? (float) (traceOrigin.z() - spatial.originZ()) : 0);
    }

    static void writeInitialVolumeRoots(ByteBuffer roots, ViewMedium medium,
                                        int implementation) {
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(medium, "medium");
        if (implementation < 0) throw new IllegalArgumentException("volume implementation must be non-negative");
        if (roots.remaining() != RtBindings.WORLD_PUSH_CONSTANT_SIZE) {
            throw new IllegalArgumentException("world binding root has the wrong size");
        }
        ByteBuffer target = roots.duplicate().order(ByteOrder.nativeOrder());
        int base = roots.position();
        target.putInt(base + RtBindings.WORLD_INITIAL_VOLUME_IMPLEMENTATION_OFFSET, 0);
        target.putInt(base + RtBindings.WORLD_INITIAL_VOLUME_ACTIVE_OFFSET, 0);
        target.putLong(base + RtBindings.WORLD_INITIAL_VOLUME_BINDING_OFFSET, 0L);
        target.putLong(base + RtBindings.WORLD_INITIAL_VOLUME_INSTANCE_OFFSET, 0L);
        if (implementation != 0) {
            if (!(medium instanceof ViewMedium.Volume<?, ?> volume)) {
                throw new IllegalArgumentException("active initial volume has no typed data");
            }
            target.putInt(base + RtBindings.WORLD_INITIAL_VOLUME_IMPLEMENTATION_OFFSET,
                    implementation);
            target.putInt(base + RtBindings.WORLD_INITIAL_VOLUME_ACTIVE_OFFSET, 1);
            target.putLong(base + RtBindings.WORLD_INITIAL_VOLUME_BINDING_OFFSET,
                    volume.bindingData().bits());
            target.putLong(base + RtBindings.WORLD_INITIAL_VOLUME_INSTANCE_OFFSET,
                    volume.instanceData().bits());
        }
    }

    static EnvironmentPush environmentPush(EnvironmentBinding<?> binding,
                                           int implementation) {
        if (binding == null) return new EnvironmentPush(0L, 0);
        if (implementation < 0) throw new IllegalArgumentException("environment implementation must be non-negative");
        return implementation == 0
                ? new EnvironmentPush(0L, 0)
                : new EnvironmentPush(binding.bindingData().bits(), implementation);
    }

    record EnvironmentPush(long bindingData, int implementation) { }

    private static int storageIndex(GpuImage image) {
        return image.descriptor(GpuImageDescriptorKind.STORAGE).index().value();
    }

    /** Releases renderer resources after device idle; each step runs even after an earlier one fails. */
    public void destroy() {
        new ResourceLifetime(
                scenePublication::close,
                () -> scenes.releaseView(this),
                gpuTiming::close,
                shadowDiagnostics::close,
                reconstruction::close,
                presenter::invalidateRenderedFrame,
                frameResources::destroy,
                history::reset,
                submittedScenes::close,
                () -> loggedActive = false,
                this::releaseCapturedFrame,
                () -> execution = null).close();
    }

    private static VkImageCopy2.Buffer copyRegion(MemoryStack stack, int width, int height) {
        VkImageCopy2.Buffer region = VkImageCopy2.calloc(1, stack);
        region.get(0).sType$Default();
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).extent().set(width, height, 1);
        return region;
    }

}
