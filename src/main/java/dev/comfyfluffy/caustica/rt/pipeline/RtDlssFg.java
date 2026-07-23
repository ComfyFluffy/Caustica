package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.ngx.NgxLibrary;
import dev.comfyfluffy.caustica.ngx.NgxRuntime;

import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VK10;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * DLSS Frame Generation (DLSSG) backend. Shares the NGX instance with DLSS-RR via {@link NgxRuntime};
 * owns only the DLSSG feature handle. This turn provides availability detection and the feature
 * create/destroy lifecycle — the per-frame {@code evaluate} + the multi-present loop that consumes it land
 * with the present-path refactor. Gated by {@code caustica.rt.fg} (default off) and hardware/driver support.
 */
public final class RtDlssFg {
    public static final RtDlssFg INSTANCE = new RtDlssFg();

    public static boolean enabled() {
        return CausticaConfig.Rt.Fg.ENABLED.value();
    }

    private NgxLibrary lib;
    private MemorySegment feature = MemorySegment.NULL;
    private boolean initialized;
    private boolean failed;
    private boolean probed;
    private boolean available;
    private int multiFrameCountMax;

    private int featureWidth = -1;
    private int featureHeight = -1;
    private int featureRenderWidth = -1;
    private int featureRenderHeight = -1;
    private int featureBackbufferFormat = Integer.MIN_VALUE;

    private RtDlssFg() {
    }

    public boolean isAvailable() {
        return available;
    }

    /** Driver-reported maximum multi-frame-generation count (1 = 2x only); 0 until probed. */
    public int multiFrameCountMax() {
        return multiFrameCountMax;
    }

    /** Requested generated-frame count clamped to the driver maximum (>=1 once available). */
    public int effectiveMultiFrameCount() {
        int requested = CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.value();
        return multiFrameCountMax > 0 ? Math.clamp(requested, 1, multiFrameCountMax) : requested;
    }

    public boolean isReady() {
        return initialized && !failed && !isNull(feature);
    }

    /** Whether a live feature already matches these dimensions/format (no recreate needed). */
    public boolean featureReadyFor(int width, int height, int renderWidth, int renderHeight, int backbufferFormat) {
        return isReady() && featureWidth == width && featureHeight == height
                && featureRenderWidth == renderWidth && featureRenderHeight == renderHeight
                && featureBackbufferFormat == backbufferFormat;
    }

    /**
     * Probe DLSSG availability once (after NGX is up) and log the result + MFG cap. Safe to call every tick
     * when FG is enabled; no-op after the first successful probe. Needs no command buffer (capability query).
     */
    public void probeAvailabilityOnce() {
        // Defense-in-depth: today's only caller (CausticaClient) already gates on enabled(), but every
        // other entry point into NGX/GPU-touching work on this class (ensureFeature, evaluate) self-gates
        // the same way, so this shouldn't be the one exception a future caller could trip over.
        if (!enabled() || probed || failed) {
            return;
        }
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return;
        }
        NgxLibrary l = NgxRuntime.INSTANCE.acquire(device);
        if (l == null) {
            return; // NGX not up yet; try again next tick
        }
        probed = true;
        lib = l;
        if (!l.hasDlssg()) {
            CausticaMod.LOGGER.warn("DLSS-FG: loaded ngxshim.dll has no DLSSG ABI — rebuild the shim "
                    + "(cmake --build native/ngx_shim/build --config Release)");
            return;
        }
        available = l.dlssgAvailable();
        multiFrameCountMax = l.dlssgMultiFrameCountMax();
        CausticaMod.LOGGER.info("DLSS Frame Generation available: {} (multi-frame max {})", available, multiFrameCountMax);
    }

    /**
     * Ensure a DLSSG feature exists for the given backbuffer/render size + native backbuffer format, creating
     * it into the supplied recording command buffer. Returns false (and disables itself) on failure.
     */
    public boolean ensureFeature(long cmd, int width, int height, int renderWidth, int renderHeight, int backbufferFormat) {
        if (!enabled() || failed) {
            return false;
        }
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return false;
        }
        try {
            if (lib == null) {
                lib = NgxRuntime.INSTANCE.acquire(device);
            }
            if (lib == null || !lib.hasDlssg()) {
                throw new IllegalStateException("NGX/DLSSG unavailable; cannot create FG feature");
            }
            if (!probed) {
                probeAvailabilityOnce();
            }
            if (!available) {
                throw new IllegalStateException("DLSS Frame Generation is not available on this system");
            }
            if (featureWidth != width || featureHeight != height
                    || featureRenderWidth != renderWidth || featureRenderHeight != renderHeight
                    || featureBackbufferFormat != backbufferFormat || isNull(feature)) {
                releaseFeature(device);
                feature = lib.createDlssg(cmd, width, height, renderWidth, renderHeight, backbufferFormat);
                if (isNull(feature)) {
                    throw new IllegalStateException("ngxshim_create_dlssg failed: last=0x"
                            + Integer.toHexString(lib.lastResult()));
                }
                featureWidth = width;
                featureHeight = height;
                featureRenderWidth = renderWidth;
                featureRenderHeight = renderHeight;
                featureBackbufferFormat = backbufferFormat;
                initialized = true;
                CausticaMod.LOGGER.info("DLSS-FG feature created: {}x{} (render {}x{}, backbuffer format {})",
                        width, height, renderWidth, renderHeight, backbufferFormat);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("DLSS-FG setup failed; frame generation disabled", t);
            return false;
        }
    }

    /**
     * Record one DLSSG evaluation: generate interpolated frame {@code multiFrameIndex} of
     * {@code multiFrameCount} from the final {@code backbuffer} + HW {@code depth} + {@code mvec} into
     * {@code outputInterp}. {@code hudless} (the main scene before the combined UI overlay) and {@code ui}
     * (premultiplied combined overlay: RT world overlays, hand/screen effects and GUI) help the driver avoid
     * ghosting/smearing screen-fixed content in the generated frame; both are optional — pass 0 handles
     * (view/image/format) to skip, same as {@code outputReal}. Matrices are jitter-free (NGX left-multiply
     * layout); pass {@code null} to leave one out. Returns false on failure.
     */
    public boolean evaluate(long cmd,
            long backbufferView, long backbufferImage, int backbufferFormat,
            long depthView, long depthImage, int depthFormat,
            long mvecView, long mvecImage, int mvecFormat,
            long hudlessView, long hudlessImage, int hudlessFormat,
            long uiView, long uiImage, int uiFormat,
            long outputInterpView, long outputInterpImage, int outputInterpFormat,
            int width, int height, int mvecDepthWidth, int mvecDepthHeight,
            int multiFrameCount, int multiFrameIndex, float mvScaleX, float mvScaleY,
            boolean depthInverted, boolean colorBuffersHDR, boolean cameraMotionIncluded, boolean reset,
            Matrix4fc clipToPrevClip, Matrix4fc prevClipToClip) {
        if (!isReady()) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment clipToPrev = matrixSegment(arena, clipToPrevClip);
            MemorySegment prevToClip = matrixSegment(arena, prevClipToClip);
            int rc = lib.evaluateDlssg(cmd, feature,
                    backbufferView, backbufferImage, backbufferFormat,
                    depthView, depthImage, depthFormat,
                    mvecView, mvecImage, mvecFormat,
                    hudlessView, hudlessImage, hudlessFormat, // 0/0/0 = skip (no hudless capture this frame)
                    uiView, uiImage, uiFormat, // 0/0/0 = skip (UI overlay not active this frame)
                    outputInterpView, outputInterpImage, outputInterpFormat,
                    0L, 0L, 0, // outputReal (skip; MC presents the real frame itself)
                    width, height, mvecDepthWidth, mvecDepthHeight,
                    multiFrameCount, multiFrameIndex, mvScaleX, mvScaleY,
                    depthInverted ? 1 : 0, colorBuffersHDR ? 1 : 0, cameraMotionIncluded ? 1 : 0, reset ? 1 : 0,
                    MemorySegment.NULL, MemorySegment.NULL, clipToPrev, prevToClip);
            if (NgxRuntime.ngxFailed(rc)) {
                throw new IllegalStateException("ngxshim_evaluate_dlssg failed: 0x" + Integer.toHexString(rc)
                        + " last=0x" + Integer.toHexString(lib.lastResult()));
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("DLSS-FG evaluate failed; frame generation disabled", t);
            return false;
        }
    }

    /** Format a JOML matrix into NGX left-multiply (row-major) layout, or NULL for a null matrix. */
    private static MemorySegment matrixSegment(Arena arena, Matrix4fc m) {
        if (m == null) {
            return MemorySegment.NULL;
        }
        MemorySegment seg = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 0, m.m00());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 1, m.m01());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 2, m.m02());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 3, m.m03());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 4, m.m10());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 5, m.m11());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 6, m.m12());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 7, m.m13());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 8, m.m20());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 9, m.m21());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 10, m.m22());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 11, m.m23());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 12, m.m30());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 13, m.m31());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 14, m.m32());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 15, m.m33());
        return seg;
    }

    /** Release the FG feature. NGX itself is shut down by {@link NgxRuntime} at device teardown. */
    public void destroy() {
        if (((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
            releaseFeature(device);
        }
        initialized = false;
        lib = null;
    }

    private void releaseFeature(VulkanDevice device) {
        if (lib != null && !isNull(feature)) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null && ctx.device() == device) {
                ctx.waitIdle();
            } else {
                VK10.vkDeviceWaitIdle(device.vkDevice());
            }
            lib.release(feature);
        }
        feature = MemorySegment.NULL;
        featureWidth = -1;
        featureHeight = -1;
        featureRenderWidth = -1;
        featureRenderHeight = -1;
        featureBackbufferFormat = Integer.MIN_VALUE;
    }

    private static boolean isNull(MemorySegment segment) {
        return segment == null || segment.equals(MemorySegment.NULL);
    }
}
