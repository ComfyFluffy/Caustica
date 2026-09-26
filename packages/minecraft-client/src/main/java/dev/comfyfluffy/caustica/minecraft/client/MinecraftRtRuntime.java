package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.renderer.runtime.RendererOptions;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaConfig;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionValues;
import dev.comfyfluffy.caustica.renderer.runtime.RtRenderSettings;
import dev.comfyfluffy.caustica.renderer.runtime.pipeline.RtJitter;
import dev.comfyfluffy.caustica.engine.session.RenderSessionHost;
import dev.comfyfluffy.caustica.minecraft.client.session.MinecraftEngineWorldSession;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.frame.SceneResources;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.minecraft.client.session.MinecraftWorldSessionHost;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import dev.comfyfluffy.caustica.nvidia.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.nvidia.ngx.DlssFrameGeneration;
import dev.comfyfluffy.caustica.nvidia.ngx.DlssRayReconstruction;
import dev.comfyfluffy.caustica.nvidia.ngx.DlssSuperResolution;
import dev.comfyfluffy.caustica.nvidia.nrd.NrdBackendFactory;
import dev.comfyfluffy.caustica.nvidia.nrd.NrdDevice;
import dev.comfyfluffy.caustica.nvidia.nrd.NrdLibrary;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackendFactory;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserRoute;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserSignalEncoding;
import dev.comfyfluffy.caustica.renderer.runtime.RtDenoisingSettings;
import dev.comfyfluffy.caustica.renderer.runtime.RtFrameRenderer;
import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry;
import dev.comfyfluffy.caustica.renderer.runtime.pass.RtPassSchedulerBackend;
import dev.comfyfluffy.caustica.renderer.raytracing.RtProgramBackend;
import dev.comfyfluffy.caustica.renderer.raytracing.scene.RtRetainedSceneBackend;
import dev.comfyfluffy.caustica.renderer.raytracing.shader.SharcSdk;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanRendererBackend;
import dev.comfyfluffy.caustica.spi.host.RuntimeHost;
import dev.comfyfluffy.caustica.renderer.presentation.RtExposure;
import dev.comfyfluffy.caustica.renderer.presentation.AcquiredSwapchainTarget;
import dev.comfyfluffy.caustica.renderer.presentation.BorrowedImage;
import dev.comfyfluffy.caustica.renderer.presentation.PresentationSwapchain;
import dev.comfyfluffy.caustica.renderer.presentation.RtFramePresenter;
import dev.comfyfluffy.caustica.slang.SlangRuntime;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;

import java.io.IOException;
import java.nio.file.Path;
import org.lwjgl.vulkan.VkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the live RT session and publishes one immutable rendering mode for each frame. */
public final class MinecraftRtRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftRtRuntime.class);
    enum State {
        OFF,
        STARTING,
        ACTIVE,
        STOPPING,
        FAILED
    }

    public enum WorldReplacement {
        READY,
        FRAME_INACTIVE,
        DEVICE_UNAVAILABLE,
        RESOURCE_TRANSITION
    }

    private State state = State.OFF;
    private Session session;
    private boolean frameActive;
    private RuntimeHost host;
    private final RenderSessionHost apiHost;
    private final MinecraftWorldSessionHost minecraftSessionHost;
    private final Path shaderCacheRoot;
    private final SlangRuntime slangRuntime;
    private final RtTelemetry telemetry;
    private final NgxRuntime.Settings ngxSettings;
    private VulkanRendererBackend vulkanBackend;
    private VulkanDeviceContext vulkanContext;
    private NgxRuntime ngxRuntime;
    private DenoiserBackendFactory denoiserFactory;
    private final MinecraftRtLifecycle lifecycle = new MinecraftRtLifecycle(
            new MinecraftRtLifecycle.Listener() {
                @Override
                public void resourcePackReloadStarting(ResourcePackEpoch pending) {
                    if (session != null) {
                        session.resourceReloadStarting();
                    }
                }

                @Override
                public void resourcePackReloadFailed(ResourcePackEpoch pending,
                                                     Throwable failure) {
                    if (session != null) {
                        session.resourceReloadFailed(failure);
                    }
                }

            });

    public MinecraftRtRuntime(RenderSessionHost apiHost, MinecraftWorldSessionHost minecraftSessionHost,
                     SlangRuntime slangRuntime, Path shaderCacheRoot, RtTelemetry telemetry,
                     NgxRuntime.Settings ngxSettings) {
        this.apiHost = java.util.Objects.requireNonNull(apiHost, "apiHost");
        this.minecraftSessionHost = java.util.Objects.requireNonNull(minecraftSessionHost, "minecraftSessionHost");
        this.slangRuntime = java.util.Objects.requireNonNull(slangRuntime, "slangRuntime");
        this.shaderCacheRoot = java.util.Objects.requireNonNull(shaderCacheRoot, "shaderCacheRoot")
                .toAbsolutePath().normalize();
        this.telemetry = java.util.Objects.requireNonNull(telemetry, "telemetry");
        this.ngxSettings = java.util.Objects.requireNonNull(ngxSettings, "ngxSettings");
    }

    /** Renderer telemetry integration backed by renderer-owned collectors and sinks. */
    public RtTelemetry telemetry() {
        return telemetry;
    }

    /** Active gameplay ticks belong to the upcoming render frame, including work before the runtime tick. */
    public RtTelemetry.Scope beginTickProfile() {
        return state == State.ACTIVE ? MinecraftFrameMetrics.beginTick(telemetry) : RtTelemetry.Scope.NOOP;
    }

    /** Times host-owned mod work, including producer edits before the next rendered frame. */
    public RtTelemetry.Scope profileStage(String name) {
        return state == State.ACTIVE || frameActive
                ? MinecraftFrameMetrics.stage(telemetry, name) : RtTelemetry.Scope.NOOP;
    }

    /** Attach the host backend whose device lifetime encloses every RT activation. */
    public synchronized void installVulkanBackend(VulkanRendererBackend backend) {
        VulkanRendererBackend installed = java.util.Objects.requireNonNull(backend, "backend");
        if (vulkanContext != null && vulkanContext.backend() != installed) {
            throw new IllegalStateException("Cannot replace a Vulkan backend while its device context is live");
        }
        vulkanBackend = installed;
    }

    /** The device context already created for this runtime, without starting device work. */
    public synchronized VulkanDeviceContext vulkanContextOrNull() {
        return vulkanContext;
    }

    /** Create or return the single device context owned by this runtime. */
    public synchronized VulkanDeviceContext requireVulkanContext() {
        if (vulkanContext != null) {
            return vulkanContext;
        }
        VulkanRendererBackend backend = java.util.Objects.requireNonNull(
                vulkanBackend, "Vulkan renderer backend is not installed");
        VulkanDeviceContext created = VulkanDeviceContext.create(backend);
        NgxRuntime createdNgxRuntime = null;
        DenoiserBackendFactory createdDenoiserFactory = null;
        try {
            createdNgxRuntime = new NgxRuntime(created, ngxSettings);
            NrdLibrary createdNrdLibrary = NrdLibrary.loadBundled(
                    shaderCacheRoot.getParent().resolve("natives"));
            var physicalDevice = backend.device().getPhysicalDevice();
            createdDenoiserFactory = NrdBackendFactory.open(createdNrdLibrary, new NrdDevice(
                    physicalDevice.getInstance().address(), physicalDevice.address(),
                    backend.device().address(), backend.graphicsQueue().familyIndex(),
                    VulkanCommandEncoder.MAX_SUBMITS_IN_FLIGHT));
            vulkanContext = created;
            ngxRuntime = createdNgxRuntime;
            denoiserFactory = createdDenoiserFactory;
            return created;
        } catch (Throwable failure) {
            try {
                try {
                    if (createdDenoiserFactory != null) createdDenoiserFactory.close();
                } finally {
                    if (createdNgxRuntime != null) createdNgxRuntime.shutdown();
                }
            } finally {
                created.destroy();
            }
            throw failure;
        }
    }

    private OptionValues settings = CausticaConfig.snapshot();
    private boolean swapchainPqAvailable;
    private boolean swapchainPqActive;
    private boolean requestedHdr;

    public void setSwapchainPqAvailable(boolean available) { swapchainPqAvailable = available; }
    public void setSwapchainPqActive(boolean active) { swapchainPqActive = active; }
    public boolean swapchainPqAvailable() { return swapchainPqAvailable; }
    public boolean hdrEnabled() { return swapchainPqActive && settings.get(RendererOptions.Rt.Hdr.ENABLED); }
    public boolean settingAvailable(Option<?> option) {
        if (RendererOptions.Rt.Sharc.OPTIONS.contains(option)) return sharcAvailable();
        return (option != RendererOptions.Rt.Hdr.ENABLED && option != RendererOptions.Rt.Hdr.UI_NITS
                && option != RendererOptions.Rt.Hdr.PEAK_NITS && option != RendererOptions.Rt.Tonemap.HDR_MAPPER
                && option != RendererOptions.Rt.Tonemap.PAPER_WHITE_NITS) || swapchainPqAvailable;
    }

    /** SHaRC runs in a build whose SHaRC headers verified, on a device that enabled their features. */
    private boolean sharcAvailable() {
        VulkanDeviceContext context = vulkanContextOrNull();
        return SharcSdk.includeDirectory() != null && context != null
                && context.backend().capabilities().shaderBufferInt64Atomics()
                && context.backend().capabilities().storageBuffer16BitAccess();
    }

    private NgxRuntime requireNgxRuntime() {
        requireVulkanContext();
        return java.util.Objects.requireNonNull(ngxRuntime, "NGX runtime was not created with the Vulkan device");
    }

    private DlssRayReconstruction.Settings rayReconstructionSettings(RtDenoisingSettings denoising) {
        return new DlssRayReconstruction.Settings(
                denoising.route() == DenoiserRoute.RAY_RECONSTRUCTION,
                dev.comfyfluffy.caustica.minecraft.client.UltraCaptureSession.effectiveDlssQuality(
                        settings.get(RendererOptions.Rt.DlssRr.QUALITY)),
                settings.get(RendererOptions.Rt.DlssRr.PRESET));
    }

    private DlssSuperResolution.Settings superResolutionSettings(RtDenoisingSettings denoising) {
        return superResolutionSettings(denoising,
                settings.get(RendererOptions.Rt.DlssSr.QUALITY), settings.get(RendererOptions.Rt.DlssSr.PRESET));
    }

    static DlssSuperResolution.Settings superResolutionSettings(
            RtDenoisingSettings denoising, int quality, int preset) {
        return new DlssSuperResolution.Settings(
                denoising.route() == DenoiserRoute.TEMPORAL_DENOISER,
                quality, preset);
    }

    private RtDenoisingSettings denoisingSettings() {
        return denoisingSettings(settings.get(RendererOptions.Rt.Denoising.ROUTE),
                settings.get(RendererOptions.Rt.Denoising.METHOD));
    }

    static RtDenoisingSettings denoisingSettings(String routeValue, String methodValue) {
        DenoiserRoute route = switch (routeValue) {
            case "raw" -> DenoiserRoute.RAW;
            case "temporal_denoiser" -> DenoiserRoute.TEMPORAL_DENOISER;
            case "ray_reconstruction" -> DenoiserRoute.RAY_RECONSTRUCTION;
            default -> throw new IllegalStateException("unsupported denoising route");
        };
        DenoiserSignalEncoding encoding = route == DenoiserRoute.TEMPORAL_DENOISER
                && methodValue.equals("reblur")
                ? DenoiserSignalEncoding.YCOCG_NORMALIZED_HIT_DISTANCE
                : DenoiserSignalEncoding.LINEAR_RGB_ABSOLUTE_HIT_DISTANCE;
        return new RtDenoisingSettings(route, encoding);
    }

    private DenoiserBackendFactory requireDenoiserFactory() {
        requireVulkanContext();
        return java.util.Objects.requireNonNull(denoiserFactory,
                "denoiser factory was not created with the Vulkan device");
    }

    private DlssFrameGeneration.Settings frameGenerationSettings() {
        return new DlssFrameGeneration.Settings(settings.get(RendererOptions.Rt.Fg.ENABLED));
    }

    private RtFramePresenter.Settings presentationSettings() {
        return new RtFramePresenter.Settings(hdrEnabled(),
                swapchainPqActive, settings.get(RendererOptions.Rt.Hdr.UI_NITS));
    }

    public void installHost(RuntimeHost installedHost) {
        host = installedHost;
    }

    /** Process-scoped extension host used when the renderer creates its engine session services. */
    public RenderSessionHost apiHost() {
        return apiHost;
    }

    /** Observe a resource pack that is available to the client, including the initial title-screen pack. */
    public void observeResourcePackAvailable() {
        lifecycle.observeResourcePackAvailable();
    }

    /** Begin the host-visible half of a resource-pack reload before its old images are destroyed. */
    public long beginResourcePackReload() {
        return lifecycle.beginResourcePackReload().generation();
    }

    /** Attach a manual-reload future; its result is applied on the next client tick. */
    public void trackResourcePackReload(long generation,
                                        java.util.concurrent.CompletableFuture<?> future) {
        lifecycle.trackResourcePackReload(generation, future);
    }

    /** Whether a completed resource pack can be replayed into newly created session instances. */
    public boolean hasAppliedResourcePack() {
        return lifecycle.resourcePackEpoch() != null && lifecycle.pendingResourcePackEpoch() == null;
    }

    /** Monotonic index of RT composite attempts in the current renderer instance. */
    public long frameCounter() {
        return session != null && session.renderer != null ? session.renderer.frameCounter() : 0L;
    }

    public String exposureSummary() {
        RtExposure exposure = session != null && session.renderer != null ? session.renderer.exposure() : null;
        return exposure != null && exposure.ready() ? exposure.debugSummaryLine() : null;
    }

    public boolean exportLatestResidualExposureExr(Path outputPath) throws IOException {
        if (!frameActive || session == null || session.renderer == null) return false;
        // The paired PNG follows this encoder; submit its frame before the EXR's direct GPU readback.
        com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().submit();
        return session.renderer.exportLatestResidualExposureExr(outputPath);
    }

    public RtFrameRenderer.DebugImageCapture exportLatestDebugImage(String name, Path outputPath) throws IOException {
        return frameActive && session != null && session.renderer != null
                ? session.renderer.exportLatestDebugImage(name, outputPath) : null;
    }

    public boolean requiresSourceWorldFallback() {
        return session == null || session.requiresSourceFallback();
    }

    public void resetExposureHistory() {
        if (session != null && session.renderer != null) session.renderer.resetExposureHistory();
    }

    public void resetSceneHistory() {
        if (session != null && session.renderer != null) session.renderer.resetSceneHistory();
    }

    public void captureFrame(FrameSnapshot snapshot) {
        if (session != null && session.renderer != null) session.renderer.captureFrame(snapshot);
    }

    /**
     * Jitter phases covering the current window at the live reconstruction render size. A capture
     * completes after exactly this many fresh reconstructed frames.
     */
    public int jitterPhaseCount(int displayWidth, int displayHeight) {
        if (session == null || session.rayReconstruction == null) {
            return 32;
        }
        int renderWidth = displayWidth;
        int[] optimal = session.rayReconstruction.queryOptimalRenderSize(displayWidth, displayHeight);
        if (optimal != null && optimal.length >= 2 && optimal[0] > 0) {
            renderWidth = optimal[0];
        }
        return RtJitter.phaseCount(renderWidth, displayWidth);
    }

    public void beginFrame() {
        try (var ignored = profileStage("runtime.frameSetup")) {
            if (frameActive && session != null && session.renderer != null) session.renderer.beginFrame();
        }
    }

    public void recordUiPasses(dev.comfyfluffy.caustica.api.vulkan.OwnedGpuImage uiLayer) {
        if (session != null && session.renderer != null) session.renderer.recordUiPasses(uiLayer);
    }

    public void finishGraphicsUse() {
        if (session != null && session.renderer != null) session.renderer.finishGraphicsUse();
    }

    /** Finalize observations after host presentation and render-thread texture retirement. */
    public void endFrame() {
        telemetry.endFrame();
    }

    public boolean composite(long nativeColorImage, int width, int height) {
        return session != null && session.renderer != null
                && session.renderer.composite(nativeColorImage, width, height);
    }

    public boolean isHdrPresentActive() {
        return session != null && session.presenter.isHdrPresentActive();
    }

    public boolean isPqSdrPresentActive() {
        return session != null && session.presenter.isPqSdrPresentActive();
    }

    public boolean presentHdr(GraphicsSubmission submission, AcquiredSwapchainTarget target,
            UiPresentationResources ui) {
        if (session == null || !session.presenter.isHdrPresentActive()) return false;
        session.presenter.presentHdr(submission, target, ui);
        return true;
    }

    public boolean presentSdrToPq(GraphicsSubmission submission, AcquiredSwapchainTarget target,
            dev.comfyfluffy.caustica.api.vulkan.OwnedGpuImage source) {
        if (session == null) return false;
        session.presenter.presentSdrToPq(submission, target, source);
        return true;
    }

    public boolean frameGenerationActive(boolean sceneAvailable) {
        return frameActive && session != null && session.presenter.isActive(sceneAvailable);
    }

    public dev.comfyfluffy.caustica.api.vulkan.GpuImage hdrBackbuffer() {
        return session != null ? session.presenter.hdrBackbuffer() : null;
    }

    public void prepareGeneratedFrame(GraphicsSubmission submission, PresentationSwapchain swapchain,
            BorrowedImage source, boolean hdrBackbuffer, UiPresentationResources ui) {
        if (session != null) {
            session.presenter.prepareGeneratedFrame(submission, swapchain, source, hdrBackbuffer, ui);
        }
    }

    public void prepareGeneratedFrame(GraphicsSubmission submission, PresentationSwapchain swapchain,
            dev.comfyfluffy.caustica.api.vulkan.GpuImage source,
            boolean hdrBackbuffer, UiPresentationResources ui) {
        if (session != null) {
            session.presenter.prepareGeneratedFrame(submission, swapchain, source, hdrBackbuffer, ui);
        }
    }

    public void flushGeneratedPresent(PresentationSwapchain swapchain, VkQueue presentQueue) {
        if (session != null) session.presenter.flushPendingPresent(swapchain, presentQueue);
    }

    public void captureHudless(BorrowedImage source, UiPresentationResources ui) {
        try (var ignored = profileStage("presentation.captureHudless")) {
            if (session != null) session.presenter.captureHudless(source, ui);
        }
    }

    public RuntimeHost host() {
        RuntimeHost installedHost = host;
        if (installedHost == null) {
            throw new IllegalStateException("RT runtime host is not installed");
        }
        return installedHost;
    }

    /** Reconcile the requested mode and advance session startup at the client-tick boundary. */
    public void tick(SceneResources sceneResources, boolean startupSceneReady, long worldEpoch,
                     MinecraftDimensionKey dimension,
                     int displayWidth, int displayHeight, Runnable reconfigureSurface) {
        settings = CausticaConfig.snapshot();
        boolean hdr = settings.get(RendererOptions.Rt.Hdr.ENABLED);
        if (hdr != requestedHdr) {
            requestedHdr = hdr;
            reconfigureSurface.run();
        }
        lifecycle.drainResourcePackCompletions();
        boolean requested = settings.get(MinecraftOptions.Rt.ENABLED);
        if (!requested) {
            if (state == State.STARTING || state == State.ACTIVE) {
                stop(reconfigureSurface);
            } else if (state == State.FAILED) {
                state = State.OFF;
            }
            return;
        }

        if (state == State.OFF) {
            start();
        }
        if (state != State.STARTING && state != State.ACTIVE) {
            return;
        }

        boolean starting = state == State.STARTING;
        // Scene work failing is a renderer defect, not a condition to present around: let it surface.
        boolean sessionReady = session.tick(sceneResources, worldEpoch, dimension,
                displayWidth, displayHeight, starting);
        if (!sessionReady) {
            return;
        }
        if (!starting) {
            return;
        }
        if (!startupSceneReady || !session.renderer.completeStartupBoundary()) {
            return;
        }

        state = State.ACTIVE;
        session.renderer.resetExposureHistory();
        host().resetPresentationFailure();
        if (settings.get(RendererOptions.Rt.Hdr.ENABLED)) {
            reconfigureSurface.run();
        }
        LOGGER.info("RT runtime active");
    }

    /** Latch the session state consumed by every hook in this render frame. */
    public void beginRenderFrame() {
        try (var ignored = profileStage("runtime.frameSetup")) {
            settings = CausticaConfig.snapshot();
            if (session != null && session.renderer != null) {
                session.renderer.configureSettings(RtRenderSettings.capture(settings, swapchainPqActive));
                session.renderer.latchSceneReadiness();
            }
            frameActive = state == State.ACTIVE;
        }
    }

    public void shutdown() {
        frameActive = false;
        swapchainPqAvailable = false;
        swapchainPqActive = false;
        try {
            if (session != null) {
                state = State.STOPPING;
                Session closing = session;
                session = null;
                closing.close();
            }
        } finally {
            session = null;
            try {
                VulkanDeviceContext context = vulkanContext;
                NgxRuntime closingNgxRuntime = ngxRuntime;
                DenoiserBackendFactory closingDenoiserFactory = denoiserFactory;
                vulkanContext = null;
                ngxRuntime = null;
                denoiserFactory = null;
                if (context != null) {
                    try {
                        context.waitIdle();
                        try {
                            if (closingDenoiserFactory != null) closingDenoiserFactory.close();
                        } finally {
                            if (closingNgxRuntime != null) closingNgxRuntime.shutdown();
                        }
                    } finally {
                        context.destroy();
                    }
                }
            } finally {
                try {
                    lifecycle.clear();
                } finally {
                    try {
                        slangRuntime.shutdown();
                    } finally {
                        vulkanBackend = null;
                        state = State.OFF;
                    }
                }
            }
        }
    }

    State state() {
        return state;
    }

    public WorldReplacement worldReplacement() {
        if (!frameActive) {
            return WorldReplacement.FRAME_INACTIVE;
        } else if (vulkanContext == null) {
            return WorldReplacement.DEVICE_UNAVAILABLE;
        } else if (requiresSourceWorldFallback()) {
            return WorldReplacement.RESOURCE_TRANSITION;
        }
        return WorldReplacement.READY;
    }

    /**
     * True after startup has completed, including setup work immediately before the next render frame.
     *
     * <p>Stable across a whole frame: the state only advances on the client tick, which runs before both
     * {@code GameRenderer.extract} and {@code GameRenderer.render}. The extract-side and render-side hooks
     * that must agree on scene ownership therefore never disagree within one frame.</p>
     */
    public boolean active() {
        return state == State.ACTIVE;
    }

    /** True only for hooks participating in the current, already-latched render frame. */
    public boolean frameActive() {
        return frameActive;
    }

    /** True while a session exists, including source-rendered startup. */
    public boolean hasSession() {
        return session != null;
    }

    /** PQ belongs to an active RT session; Off and Starting use the host's native SDR swapchain. */
    public boolean wantsPqSwapchain() {
        return active() && settings.get(RendererOptions.Rt.Hdr.ENABLED);
    }

    private void start() {
        VulkanRendererBackend backend = vulkanBackend;
        if (backend == null || !backend.capabilities().rayTracing()) {
            state = State.FAILED;
            LOGGER.warn("RT runtime unavailable: the Vulkan device was not provisioned for ray tracing");
            return;
        }
        try {
            VulkanDeviceContext context = requireVulkanContext();
            DlssFrameGeneration frameGeneration = new DlssFrameGeneration(
                    requireNgxRuntime(), frameGenerationSettings());
            session = new Session(context, frameGeneration,
                    new RtFramePresenter(context, frameGeneration, this::presentationSettings));
            state = State.STARTING;
            LOGGER.info("RT runtime starting; source presentation remains active");
        } catch (Throwable failure) {
            try {
                if (session != null) {
                    session.close();
                    session = null;
                }
            } finally {
                state = State.FAILED;
                LOGGER.error("RT runtime could not create its render session", failure);
            }
        }
    }

    /**
     * Hand world rendering back to the source renderer. Its visibility state and resources remain live
     * throughout the RT session, so no rebuild or wait is required.
     */
    private void stop(Runnable reconfigureSurface) {
        state = State.STOPPING;
        frameActive = false;
        Session closing = session;
        session = null;
        try {
            reconfigureSurface.run();
        } finally {
            try {
                closing.close();
            } finally {
                state = State.OFF;
            }
        }
        LOGGER.info("RT runtime off; source presentation restored");
    }

    private final class Session {
        private final DlssFrameGeneration frameGeneration;
        private final RtFramePresenter presenter;
        private VulkanDeviceContext context;
        private RtProgramBackend programs;
        private RtRetainedSceneBackend scenes;
        private RtPassSchedulerBackend passes;
        private DlssRayReconstruction rayReconstruction;
        private DlssSuperResolution superResolution;
        private MinecraftEngineWorldSession world;
        private RtFrameRenderer renderer;
        private long worldEpoch;

        private Session(VulkanDeviceContext context, DlssFrameGeneration frameGeneration,
                        RtFramePresenter presenter) {
            this.context = context;
            this.frameGeneration = frameGeneration;
            this.presenter = presenter;
        }

        private boolean requiresSourceFallback() {
            return renderer == null || renderer.requiresSourceWorldFallback();
        }

        boolean tick(SceneResources sceneResources, long requestedWorldEpoch,
                     MinecraftDimensionKey dimension, int displayWidth, int displayHeight,
                     boolean starting) {
            frameGeneration.configure(frameGenerationSettings());
            ResourcePackEpoch applied = lifecycle.resourcePackEpoch();
            if (requestedWorldEpoch == 0L || dimension == null || applied == null
                    || lifecycle.pendingResourcePackEpoch() != null) {
                closeWorld();
                return false;
            }
            if (world == null || worldEpoch != requestedWorldEpoch) {
                closeWorld();
                openWorld(requestedWorldEpoch, dimension, applied);
            }
            RtDenoisingSettings denoising = denoisingSettings();
            rayReconstruction.configure(rayReconstructionSettings(denoising));
            superResolution.configure(superResolutionSettings(denoising));
            renderer.configureDenoising(denoising);

            // A capture owns one immutable scene: suspend scene mutation (streaming, entity motion,
            // atlas animation) while the composited frames keep reconstructing the frozen state.
            if (!dev.comfyfluffy.caustica.minecraft.client.UltraCaptureSession.active()) {
                world.progress();
            }
            boolean resourcesReady = programs.hasActive();
            if (resourcesReady) {
                telemetry.beginFrameIfInactive();
            }
            if (!sceneResources.sceneReady()) {
                return false;
            }
            if (starting && (displayWidth <= 0 || displayHeight <= 0
                    || !renderer.ensurePresentationResourcesReady(
                    requestedWorldEpoch, displayWidth, displayHeight))) {
                return false;
            }
            if (frameGeneration.enabled()) {
                frameGeneration.probeAvailabilityOnce();
            }
            return resourcesReady;
        }

        private void openWorld(long epoch, MinecraftDimensionKey dimension,
                               ResourcePackEpoch resourcePackEpoch) {
            try {
                requireNgxRuntime().beginActivation();
                programs = new RtProgramBackend(context,
                        slangRuntime,
                        shaderCacheRoot,
                        SharcSdk.includeDirectory());
                scenes = new RtRetainedSceneBackend(context);
                passes = new RtPassSchedulerBackend(context,
                        org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                        org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT,
                        org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM);
                RtDenoisingSettings denoising = denoisingSettings();
                rayReconstruction = new DlssRayReconstruction(
                        requireNgxRuntime(), rayReconstructionSettings(denoising));
                superResolution = new DlssSuperResolution(
                        requireNgxRuntime(), superResolutionSettings(denoising));
                world = new MinecraftEngineWorldSession(apiHost(),
                        minecraftSessionHost, context, context.gpuExecutor(),
                        programs, scenes, new dev.comfyfluffy.caustica.renderer.raytracing.scene.RtMeshPreparer(context),
                        passes, dimension, resourcePackEpoch,
                        failure -> LOGGER.error("Engine world-session failure", failure));
                renderer = new RtFrameRenderer(context, programs, scenes, passes,
                        world.services(), presenter, rayReconstruction,
                        superResolution,
                        requireDenoiserFactory(), denoising, telemetry, RtRenderSettings.capture(settings, swapchainPqActive));
                worldEpoch = epoch;
            } catch (Throwable failure) {
                ResourceLifetime.closeAfterFailure(failure, this::closeWorld);
                throw failure;
            }
        }

        private void resourceReloadStarting() {
            // Host atlases and model sets are replaced in place. Drain every borrower before that starts.
            closeWorld();
        }

        private void resourceReloadFailed(Throwable failure) {
            LOGGER.warn("Resource-pack reload failed; rebuilding from the prior resource-pack epoch", failure);
        }

        /**
         * Detaches the world first, then runs every teardown step even after an earlier one fails, so a
         * failure cannot strand device resources; the first failure is rethrown with the rest suppressed.
         */
        private void closeWorld() {
            if (world == null && programs == null && scenes == null
                    && renderer == null && rayReconstruction == null && superResolution == null) return;
            MinecraftEngineWorldSession closingWorld = world;
            RtFrameRenderer closingRenderer = renderer;
            DlssRayReconstruction closingRayReconstruction = rayReconstruction;
            DlssSuperResolution closingSuperResolution = superResolution;
            RtRetainedSceneBackend closingScenes = scenes;
            RtProgramBackend closingPrograms = programs;
            world = null;
            renderer = null;
            rayReconstruction = null;
            superResolution = null;
            scenes = null;
            programs = null;
            passes = null;
            worldEpoch = 0L;
            new ResourceLifetime(
                    host()::resetFrameBridge,
                    () -> { if (closingRenderer != null) closingRenderer.releaseCapturedFrame(); },
                    () -> { if (closingWorld != null) closingWorld.close(); },
                    telemetry::resetPublications,
                    () -> { if (context != null) context.drainAndWaitIdle(); },
                    // A constructed renderer owns the DLSS features and destroys them itself.
                    () -> { if (closingRenderer != null) closingRenderer.destroy(); },
                    () -> {
                        if (closingRenderer == null && closingRayReconstruction != null) {
                            closingRayReconstruction.destroyAfterDeviceIdle();
                        }
                    },
                    () -> {
                        if (closingRenderer == null && closingSuperResolution != null) {
                            closingSuperResolution.destroyAfterDeviceIdle();
                        }
                    },
                    () -> { if (closingScenes != null) closingScenes.shutdownAfterDeviceIdle(); },
                    () -> { if (closingPrograms != null) closingPrograms.close(); }).close();
        }

        void close() {
            VulkanDeviceContext closingContext = context;
            new ResourceLifetime(
                    this::closeWorld,
                    host()::destroyUiPresentation,
                    () -> { if (closingContext != null) closingContext.waitIdle(); },
                    () -> { if (closingContext != null) presenter.destroy(closingContext.vk()); },
                    () -> {
                        if (closingContext != null) closingContext.backend().lowLatency().destroy(closingContext.vk());
                    },
                    () -> context = null).close();
        }
    }
}
