package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.renderer.presentation.RtFramePresenter;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.renderer.presentation.PresentationResources;
import dev.comfyfluffy.caustica.renderer.presentation.RtExposure;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceExtent;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceResources;
import dev.comfyfluffy.caustica.nvidia.ngx.DlssRayReconstruction;
import dev.comfyfluffy.caustica.nvidia.ngx.DlssSuperResolution;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserRoute;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;

/** Coordinates trace and presentation resource owners at the root renderer lifetime. */
final class RtFrameResources {
    private final RtFramePresenter presenter;
    private final DlssRayReconstruction rayReconstruction;
    private final DlssSuperResolution upscaler;
    private final TraceResources trace = new TraceResources();
    private final PresentationResources presentation;
    private DenoiserRoute renderSizeRoute;
    private int renderSizeConfiguration = Integer.MIN_VALUE;
    private RtSharcCache sharc;
    private int sharcExponent;

    RtFrameResources(RtFramePresenter presenter, DlssRayReconstruction rayReconstruction,
                     DlssSuperResolution upscaler, RtExposure.Settings exposureSettings) {
        this.presenter = presenter;
        this.rayReconstruction = rayReconstruction;
        this.upscaler = upscaler;
        this.presentation = new PresentationResources(exposureSettings);
    }

    TraceResources trace() {
        return trace;
    }

    /** The SHaRC tables, or null while the cache is off. */
    RtSharcCache sharc() {
        return sharc;
    }

    /**
     * Keeps SHaRC tables of the configured size while the cache is wanted. Submitted frames read the
     * tables, so replacing or releasing them waits for the device; only a settings change does that.
     */
    void ensureSharc(VulkanDeviceContext context, boolean wanted, int exponent) {
        if (sharc != null && wanted && sharcExponent == exponent) return;
        if (sharc != null) {
            context.waitIdle();
            sharc.destroy();
            sharc = null;
        }
        if (wanted) {
            sharc = RtSharcCache.create(context, exponent);
            sharcExponent = exponent;
        }
    }

    PresentationResources presentation() {
        return presentation;
    }

    /** Resizes both owners after one shared drain of all prior frame use. */
    boolean ensureSized(VulkanDeviceContext context, int width, int height, DenoiserRoute route,
                        Runnable beforeResizeAfterIdle) {
        boolean rrEnabled = usesRayReconstructionRenderSize(route, rayReconstruction.configured());
        boolean upscalerEnabled = usesTemporalUpscalerRenderSize(route, upscaler.configured());
        int configuration = rrEnabled ? rayReconstruction.quality()
                : upscalerEnabled ? upscaler.configurationKey() : Integer.MIN_VALUE;
        if (presentation.matches(width, height) && trace.hasDisplayExtent(width, height)
                && renderSizeRoute == route && renderSizeConfiguration == configuration) {
            return false;
        }

        presenter.invalidateRenderedFrame();
        context.waitIdle();
        beforeResizeAfterIdle.run();
        int[] optimal = rrEnabled ? rayReconstruction.queryOptimalRenderSize(width, height)
                : upscalerEnabled ? upscaler.queryOptimalRenderSize(width, height) : null;
        int renderWidth = optimal != null ? optimal[0] : width;
        int renderHeight = optimal != null ? optimal[1] : height;
        trace.resize(context, new TraceExtent(renderWidth, renderHeight, width, height));
        presentation.resize(context, width, height);
        renderSizeRoute = route;
        renderSizeConfiguration = configuration;
        return true;
    }

    static boolean usesRayReconstructionRenderSize(DenoiserRoute route, boolean configured) {
        return route == DenoiserRoute.RAY_RECONSTRUCTION && configured;
    }

    static boolean usesTemporalUpscalerRenderSize(DenoiserRoute route, boolean configured) {
        return route == DenoiserRoute.TEMPORAL_DENOISER && configured;
    }

    void destroy() {
        renderSizeRoute = null;
        renderSizeConfiguration = Integer.MIN_VALUE;
        RtSharcCache closing = sharc;
        sharc = null;
        new ResourceLifetime(trace::destroy, presentation::destroy,
                () -> { if (closing != null) closing.destroy(); }).close();
    }
}
