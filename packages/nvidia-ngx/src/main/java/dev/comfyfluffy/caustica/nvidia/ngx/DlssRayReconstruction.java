package dev.comfyfluffy.caustica.nvidia.ngx;

import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * DLSS Ray Reconstruction backend for the RT renderer. Runs the DLSSD (Ray Reconstruction) feature
 * over path-traced color + guide buffers (normals/roughness, diffuse/specular albedo, depth, motion
 * vectors, reflection motion vectors, responsivity), denoising and upscaling (render res → display res)
 * in one pass.
 */
public final class DlssRayReconstruction {
    private static final Logger LOGGER = LoggerFactory.getLogger(DlssRayReconstruction.class);

    public record Settings(boolean enabled, int quality, int preset) {
        public Settings {
            if (quality < 0 || quality > 5 || quality == 4) {
                throw new IllegalArgumentException("unsupported DLSS-RR quality " + quality);
            }
            if (preset != 0 && preset != 4 && preset != 5) {
                throw new IllegalArgumentException("unsupported DLSS-RR render preset " + preset);
            }
        }
    }

    private final NgxRuntime runtime;
    private Settings settings;

    /** Desired RR mode for session resource sizing, including RT startup frames rendered by the source renderer. */
    public boolean configured() {
        return settings.enabled();
    }

    public boolean enabled() {
        return configured() && !failed;
    }

    // DLSS feature flags. IsHDR (bit 0): color is scene-linear ACEScg HDR (rgba16f). MVLowRes (bit 1):
    // motion vectors are at render/input resolution, not display. Depth is reverse hardware depth and
    // MVs are unjittered. DLSS-RR does not support the exposure or auto-exposure options.
    private static final int FEATURE_FLAG_IS_HDR = 1 << 0;
    private static final int FEATURE_FLAG_MV_LOW_RES = 1 << 1;
    private static final int FEATURE_FLAG_DEPTH_INVERTED = 1 << 3;
    private static final int FEATURE_FLAGS = FEATURE_FLAG_IS_HDR | FEATURE_FLAG_MV_LOW_RES
            | FEATURE_FLAG_DEPTH_INVERTED;

    public int quality() {
        return settings.quality();
    }

    private NgxLibrary lib;
    private MemorySegment feature = MemorySegment.NULL;
    private boolean initialized;
    private boolean failed;
    private boolean loggedAvailable;

    private DlssFeatureConfiguration featureConfiguration;

    private boolean resetHistory;
    private long lastFrameNanos;

    public DlssRayReconstruction(NgxRuntime runtime, Settings settings) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public void configure(Settings settings) {
        Settings next = Objects.requireNonNull(settings, "settings");
        if (!next.equals(this.settings)) {
            resetHistory();
        }
        this.settings = next;
    }

    public boolean isReady() {
        return initialized && !failed && !feature.equals(MemorySegment.NULL);
    }

    /** Discard temporal reconstruction state before the next evaluation. */
    public void resetHistory() {
        resetHistory = true;
        lastFrameNanos = 0L;
    }

    /**
     * Record a DLSS-RR evaluation: denoise + upscale the noisy path-traced color (at render res) using
     * the guide buffers, writing the display-res result into {@code out}. {@code responsivity} is the
     * render-resolution R16F mask in [0, 1]; larger values make RR favor the current frame over its
     * history. {@code jitterX/jitterY} is the sub-pixel camera jitter applied to the primary ray this
     * frame, in render pixels. Returns false (disabling RR) on failure. MVs are already in render-pixel
     * space (scale 1).
     */
    public boolean evaluate(VkCommandBuffer commandBuffer, GpuImage color, GpuImage depth, GpuImage motion,
                            GpuImage diffuseAlbedo, GpuImage specularAlbedo, GpuImage normals,
                            GpuImage specularMotion, GpuImage responsivity, GpuImage out,
                            int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                            float jitterX, float jitterY, float preExposure) {
        if (!isReady()) {
            return false;
        }
        try {
            long now = System.nanoTime();
            float frameMs = lastFrameNanos == 0 ? 16.6f
                    : Math.clamp((now - lastFrameNanos) / 1_000_000.0f, 0.1f, 200.0f);
            lastFrameNanos = now;

            int rc = lib.evaluateDlssd(commandBuffer.address(), feature,
                    color.view(), color.image(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    depth.view(), depth.image(), VK10.VK_FORMAT_R32_SFLOAT,
                    motion.view(), motion.image(), VK10.VK_FORMAT_R16G16_SFLOAT,
                    diffuseAlbedo.view(), diffuseAlbedo.image(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    specularAlbedo.view(), specularAlbedo.image(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    normals.view(), normals.image(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    specularMotion.view(), specularMotion.image(), VK10.VK_FORMAT_R16G16_SFLOAT,
                    responsivity.view(), responsivity.image(), VK10.VK_FORMAT_R16_SFLOAT,
                    out.view(), out.image(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    renderWidth, renderHeight, displayWidth, displayHeight,
                    // Jitter and motion vectors use render-pixel units.
                    jitterX, jitterY, 1.0f, 1.0f, resetHistory ? 1 : 0, frameMs, preExposure);
            resetHistory = false;
            if (NgxRuntime.ngxFailed(rc)) {
                throw new IllegalStateException("ngxshim_evaluate_dlssd failed: 0x" + Integer.toHexString(rc)
                        + " last=0x" + Integer.toHexString(lib.lastResult()));
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("DLSS-RR evaluate failed; RT composite continues without it", t);
            return false;
        }
    }

    /**
     * Query the render resolution required by the selected quality mode. Returns {@code null} when
     * RR is disabled. An active RR feature requires this exact size, so query failures propagate.
     */
    public int[] queryOptimalRenderSize(int displayWidth, int displayHeight) {
        if (!configured() || failed) {
            return null;
        }
        ensureInitialized();
        return lib.queryOptimalDlssd(displayWidth, displayHeight, quality());
    }

    public boolean featureReadyFor(int renderWidth, int renderHeight, int displayWidth, int displayHeight) {
        return featureConfiguration != null && featureConfiguration.matches(renderWidth, renderHeight,
                displayWidth, displayHeight, quality(), settings.preset());
    }

    /**
     * Ensure NGX is initialized and an RR feature exists for the given resolutions, creating it into
     * the supplied recording command buffer. Returns false (and disables itself) on any failure so the
     * caller falls back to the non-RR path. Before changing a feature, the caller must complete its prior GPU uses.
     */
    public boolean ensureFeature(VkCommandBuffer commandBuffer, int renderWidth, int renderHeight,
                                 int displayWidth, int displayHeight) {
        if (!enabled()) {
            return false;
        }
        try {
            ensureInitialized();
            int quality = quality();
            int preset = settings.preset();
            if (!featureReadyFor(renderWidth, renderHeight, displayWidth, displayHeight)) {
                releaseFeature();
                feature = lib.createDlssd(commandBuffer.address(), renderWidth, renderHeight,
                        displayWidth, displayHeight, quality, FEATURE_FLAGS, preset);
                if (feature.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("ngxshim_create_dlssd failed: last=0x"
                            + Integer.toHexString(lib.lastResult()));
                }
                featureConfiguration = new DlssFeatureConfiguration(renderWidth, renderHeight,
                        displayWidth, displayHeight, quality, preset);
                resetHistory();
                LOGGER.info("DLSS-RR feature created: {}x{} -> {}x{} (quality {}, preset {})",
                        renderWidth, renderHeight, displayWidth, displayHeight, quality, preset);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("DLSS-RR setup failed; RT composite continues without it", t);
            return false;
        }
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        // NGX init/shutdown is owned by the shared NgxRuntime so RR and Frame Generation can coexist
        // (releasing the RR feature must not tear NGX down while FG still holds a handle).
        lib = runtime.acquire();
        if (lib == null) {
            throw new IllegalStateException("NGX runtime unavailable; DLSS-RR cannot initialize");
        }
        boolean available = lib.dlssdAvailable();
        if (!loggedAvailable) {
            loggedAvailable = true;
            LOGGER.info("DLSS Ray Reconstruction available: {}", available);
        }
        if (!available) {
            throw new IllegalStateException("DLSS Ray Reconstruction is not available on this system");
        }
        initialized = true;
    }

    /**
     * Release the RR feature after all submitted work that can reference it has completed.
     */
    public void destroyAfterDeviceIdle() {
        releaseFeature();
        initialized = false;
        failed = false;
        lib = null;
        resetHistory();
    }

    private void releaseFeature() {
        if (!feature.equals(MemorySegment.NULL)) {
            lib.release(feature);
            feature = MemorySegment.NULL;
        }
        featureConfiguration = null;
    }

}
