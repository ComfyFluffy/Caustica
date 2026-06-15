package dev.upscaler.rt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.upscaler.UpscalerMod;
import dev.upscaler.mixin.GpuDeviceAccessor;
import dev.upscaler.ngx.NgxLibrary;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkInstance;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * DLSS Ray Reconstruction backend for the RT renderer. Where {@link dev.upscaler.client.DlssPipeline}
 * runs DLSS Super Resolution over the rasterizer, this runs the DLSSD (Ray Reconstruction) feature
 * over our path-traced color + guide buffers (normals/roughness, diffuse/specular albedo, depth,
 * motion vectors), denoising and (later, P4.2b) upscaling in one pass.
 *
 * <p>P4.2a wires NGX init, the runtime RR-available gate, and feature creation at native resolution
 * (render == display, denoise only). The per-frame evaluate + composite, the render-res split, and
 * jitter follow.
 */
public final class RtDlssRr {
    public static final RtDlssRr INSTANCE = new RtDlssRr();
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("upscaler.rt.dlssRr", "false"));

    private static final String SHIM_DLL = "ngxshim.dll";
    private static final String RR_DLL = "nvngx_dlssd.dll";
    private static final int QUALITY_MAX_QUALITY = 2; // NVSDK_NGX_PerfQuality_Value_MaxQuality
    // DLSS feature flags. IsHDR (bit 0): color is linear HDR (rgba16f) — RR requires it ("HDR Color
    // required"). MVLowRes (bit 1): motion vectors are at render/input resolution, not display — RR
    // requires it ("Low resolution Motion Vectors required"). AutoExposure (bit 6): in HDR mode DLSS
    // needs the scene exposure (exposure texture or auto-estimate); without it the output is black, so
    // let DLSS estimate exposure from the color itself. MVs are unjittered and depth is linear, so no
    // MV_JITTERED / DEPTH_INVERTED.
    private static final int FEATURE_FLAG_IS_HDR = 1 << 0;
    private static final int FEATURE_FLAG_MV_LOW_RES = 1 << 1;
    private static final int FEATURE_FLAG_AUTO_EXPOSURE = 1 << 6;
    private static final int FEATURE_FLAGS = FEATURE_FLAG_IS_HDR | FEATURE_FLAG_MV_LOW_RES | FEATURE_FLAG_AUTO_EXPOSURE;
    // 0 = let the RR DLL pick its per-mode default preset (tuned in P4.3).
    private static final int RENDER_PRESET = Integer.getInteger("upscaler.rt.dlssRr.preset", 0);

    private final int quality = Integer.getInteger("upscaler.rt.dlssRr.quality", QUALITY_MAX_QUALITY);

    private NgxLibrary lib;
    private MemorySegment feature = MemorySegment.NULL;
    private boolean initialized;
    private boolean ngxInitialized;
    private boolean failed;
    private boolean loggedAvailable;

    private int featureRenderWidth = -1;
    private int featureRenderHeight = -1;
    private int featureDisplayWidth = -1;
    private int featureDisplayHeight = -1;

    private boolean resetHistory;
    private long lastFrameNanos;

    private RtDlssRr() {
    }

    public boolean isReady() {
        return initialized && !failed && !isNull(feature);
    }

    /**
     * Record a DLSS-RR evaluation: denoise (+ upscale, once render &lt; display) the noisy path-traced
     * color using the guide buffers, writing the result into {@code out}. Returns false (disabling RR)
     * on failure. P4.2a: native res, no jitter, MVs already in render-pixel space (scale 1).
     */
    public boolean evaluate(long cmd, RtImage color, RtImage depth, RtImage motion,
                            RtImage diffuseAlbedo, RtImage specularAlbedo, RtImage normals, RtImage out,
                            int renderWidth, int renderHeight, int displayWidth, int displayHeight) {
        if (!isReady()) {
            return false;
        }
        try {
            long now = System.nanoTime();
            float frameMs = lastFrameNanos == 0 ? 16.6f
                    : Math.clamp((now - lastFrameNanos) / 1_000_000.0f, 0.1f, 200.0f);
            lastFrameNanos = now;

            int rc = lib.evaluateDlssd(cmd, feature,
                    color.view, color.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    depth.view, depth.image, VK10.VK_FORMAT_R32_SFLOAT,
                    motion.view, motion.image, VK10.VK_FORMAT_R16G16_SFLOAT,
                    diffuseAlbedo.view, diffuseAlbedo.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    specularAlbedo.view, specularAlbedo.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    normals.view, normals.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    out.view, out.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    renderWidth, renderHeight, displayWidth, displayHeight,
                    0.0f, 0.0f, 1.0f, 1.0f, resetHistory ? 1 : 0, frameMs);
            resetHistory = false;
            if (ngxFailed(rc)) {
                throw new IllegalStateException("ngxshim_evaluate_dlssd failed: 0x" + Integer.toHexString(rc)
                        + " last=0x" + Integer.toHexString(lib.lastResult()));
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            UpscalerMod.LOGGER.error("DLSS-RR evaluate failed; RT composite continues without it", t);
            return false;
        }
    }

    /**
     * Ensure NGX is initialized and an RR feature exists for the given resolutions, creating it into
     * the supplied recording command buffer. Returns false (and disables itself) on any failure so the
     * caller falls back to the non-RR path. P4.2a uses render == display (native-res denoise).
     */
    public boolean ensureFeature(long cmd, int renderWidth, int renderHeight, int displayWidth, int displayHeight) {
        if (!ENABLED || failed) {
            return false;
        }
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).upscaler$getBackend() instanceof VulkanDevice device)) {
            return false;
        }
        try {
            ensureInitialized(device);
            if (featureRenderWidth != renderWidth || featureRenderHeight != renderHeight
                    || featureDisplayWidth != displayWidth || featureDisplayHeight != displayHeight
                    || isNull(feature)) {
                releaseFeature(device);
                feature = lib.createDlssd(cmd, renderWidth, renderHeight, displayWidth, displayHeight,
                        quality, FEATURE_FLAGS, RENDER_PRESET);
                if (isNull(feature)) {
                    throw new IllegalStateException("ngxshim_create_dlssd failed: last=0x"
                            + Integer.toHexString(lib.lastResult()));
                }
                featureRenderWidth = renderWidth;
                featureRenderHeight = renderHeight;
                featureDisplayWidth = displayWidth;
                featureDisplayHeight = displayHeight;
                resetHistory = true; // a fresh feature has no temporal history
                UpscalerMod.LOGGER.info("DLSS-RR feature created: {}x{} -> {}x{} (quality {})",
                        renderWidth, renderHeight, displayWidth, displayHeight, quality);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            UpscalerMod.LOGGER.error("DLSS-RR setup failed; RT composite continues without it", t);
            return false;
        }
    }

    private void ensureInitialized(VulkanDevice device) {
        if (initialized) {
            return;
        }
        Path shim = locate(SHIM_DLL);
        if (shim == null) {
            throw new IllegalStateException(SHIM_DLL + " not found (run dir natives/ or -Dupscaler.ngx.path)");
        }
        Path nativesDir = shim.getParent();
        if (nativesDir != null && !Files.isRegularFile(nativesDir.resolve(RR_DLL))) {
            UpscalerMod.LOGGER.warn("{} not found next to {}; DLSS-RR availability will fail", RR_DLL, SHIM_DLL);
        }

        lib = NgxLibrary.load(shim);

        Path dataPath = FabricLoader.getInstance().getGameDir().resolve("upscaler-ngx");
        try {
            Files.createDirectories(dataPath);
        } catch (Exception e) {
            UpscalerMod.LOGGER.warn("Could not create NGX data path {}", dataPath, e);
        }

        VkInstance instance = device.vkDevice().getPhysicalDevice().getInstance();
        try (Arena arena = Arena.ofConfined()) {
            long gdpa;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                gdpa = VK10.vkGetInstanceProcAddr(instance, stack.ASCII("vkGetDeviceProcAddr"));
            }
            int rc = lib.init(0L, wideString(arena, dataPath.toString()),
                    instance.address(), device.vkDevice().getPhysicalDevice().address(), device.vkDevice().address(),
                    0L, gdpa, wideString(arena, nativesDir == null ? "" : nativesDir.toString()));
            if (ngxFailed(rc)) {
                throw new IllegalStateException("ngxshim_init failed: 0x" + Integer.toHexString(rc)
                        + " last=0x" + Integer.toHexString(lib.lastResult()));
            }
            ngxInitialized = true;
            boolean available = lib.dlssdAvailable();
            if (!loggedAvailable) {
                loggedAvailable = true;
                UpscalerMod.LOGGER.info("DLSS Ray Reconstruction available: {}", available);
            }
            if (!available) {
                throw new IllegalStateException("DLSS Ray Reconstruction is not available on this system");
            }
        }
        initialized = true;
    }

    public void destroy() {
        if (((GpuDeviceAccessor) RenderSystem.getDevice()).upscaler$getBackend() instanceof VulkanDevice device) {
            releaseFeature(device);
            if (lib != null && ngxInitialized) {
                lib.shutdown(device.vkDevice().address());
                ngxInitialized = false;
            }
        }
        initialized = false;
        lib = null;
    }

    private void releaseFeature(VulkanDevice device) {
        if (!isNull(feature)) {
            VK10.vkDeviceWaitIdle(device.vkDevice());
            lib.release(feature);
            feature = MemorySegment.NULL;
        }
        featureRenderWidth = -1;
        featureRenderHeight = -1;
        featureDisplayWidth = -1;
        featureDisplayHeight = -1;
    }

    private static boolean isNull(MemorySegment segment) {
        return segment == null || segment.equals(MemorySegment.NULL);
    }

    /** NVSDK_NGX_Result: failure when top 12 bits == 0xBAD. */
    private static boolean ngxFailed(int result) {
        return (result & 0xFFF00000) == 0xBAD00000;
    }

    private static MemorySegment wideString(Arena arena, String s) {
        byte[] utf16 = s.getBytes(StandardCharsets.UTF_16LE);
        MemorySegment seg = arena.allocate(utf16.length + 2L);
        MemorySegment.copy(utf16, 0, seg, ValueLayout.JAVA_BYTE, 0, utf16.length);
        seg.set(ValueLayout.JAVA_BYTE, utf16.length, (byte) 0);
        seg.set(ValueLayout.JAVA_BYTE, utf16.length + 1, (byte) 0);
        return seg;
    }

    private static Path locate(String name) {
        String override = System.getProperty("upscaler.ngx.path");
        if (override != null && name.equals(SHIM_DLL)) {
            Path p = Path.of(override);
            return Files.isRegularFile(p) ? p : null;
        }
        Path runDir = FabricLoader.getInstance().getGameDir();
        Path[] candidates = {runDir.resolve("natives").resolve(name), runDir.resolve(name)};
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
