package dev.upscaler.rt.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.upscaler.UpscalerConfig;
import dev.upscaler.UpscalerMod;
import dev.upscaler.rt.accel.RtImage;
import dev.upscaler.mixin.GpuDeviceAccessor;
import dev.upscaler.ngx.NgxLibrary;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkInstance;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * DLSS Ray Reconstruction backend for the RT renderer. Runs the DLSSD (Ray Reconstruction) feature
 * over path-traced color + guide buffers (normals/roughness, diffuse/specular albedo, depth, motion
 * vectors, reflection motion vectors), denoising and upscaling (render res → display res) in one pass.
 */
public final class RtDlssRr {
    public static final RtDlssRr INSTANCE = new RtDlssRr();
    public static boolean enabled() {
        return UpscalerConfig.Rt.DlssRr.ENABLED.value();
    }

    private static final PlatformNatives PLATFORM_NATIVES = PlatformNatives.current();
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
    // 0 = let the RR DLL pick its per-mode default preset.
    private static int renderPreset() {
        return UpscalerConfig.Rt.DlssRr.PRESET.value();
    }

    private static int quality() {
        return UpscalerConfig.Rt.DlssRr.QUALITY.value();
    }

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
    private int featureQuality = Integer.MIN_VALUE;
    private int featurePreset = Integer.MIN_VALUE;

    private boolean resetHistory;
    private long lastFrameNanos;

    private RtDlssRr() {
    }

    public boolean isReady() {
        return initialized && !failed && !isNull(feature);
    }

    /**
     * Record a DLSS-RR evaluation: denoise + upscale the noisy path-traced color (at render res) using
     * the guide buffers, writing the display-res result into {@code out}. {@code jitterX/jitterY} is the
     * sub-pixel camera jitter applied to the primary ray this frame, in render pixels. Returns false
     * (disabling RR) on failure. MVs are already in render-pixel space (scale 1).
     */
    public boolean evaluate(long cmd, RtImage color, RtImage depth, RtImage motion,
                            RtImage diffuseAlbedo, RtImage specularAlbedo, RtImage normals,
                            RtImage specularMotion, RtImage out,
                            int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                            float jitterX, float jitterY, Matrix4fc worldToView, Matrix4fc viewToClip) {
        if (!isReady()) {
            return false;
        }
        try {
            long now = System.nanoTime();
            float frameMs = lastFrameNanos == 0 ? 16.6f
                    : Math.clamp((now - lastFrameNanos) / 1_000_000.0f, 0.1f, 200.0f);
            lastFrameNanos = now;

            int rc;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment worldToViewMatrix = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
                MemorySegment viewToClipMatrix = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
                putNgxLeftMultiplyMatrix(worldToView, worldToViewMatrix);
                putNgxLeftMultiplyMatrix(viewToClip, viewToClipMatrix);
                rc = lib.evaluateDlssd(cmd, feature,
                        color.view, color.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                        depth.view, depth.image, VK10.VK_FORMAT_R32_SFLOAT,
                        motion.view, motion.image, VK10.VK_FORMAT_R16G16_SFLOAT,
                        diffuseAlbedo.view, diffuseAlbedo.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                        specularAlbedo.view, specularAlbedo.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                        normals.view, normals.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                        specularMotion.view, specularMotion.image, VK10.VK_FORMAT_R16G16_SFLOAT,
                        0L, 0L, 0,
                        out.view, out.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                        renderWidth, renderHeight, displayWidth, displayHeight,
                        // jitter in render pixels; MVs are already in render-pixel units, so MV scale = 1.
                        jitterX, jitterY, 1.0f, 1.0f, resetHistory ? 1 : 0, frameMs,
                        worldToViewMatrix, viewToClipMatrix);
            }
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
     * caller falls back to the non-RR path.
     */
    public boolean ensureFeature(long cmd, int renderWidth, int renderHeight, int displayWidth, int displayHeight) {
        if (!enabled() || failed) {
            return false;
        }
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).upscaler$getBackend() instanceof VulkanDevice device)) {
            return false;
        }
        try {
            ensureInitialized(device);
            int quality = quality();
            int preset = renderPreset();
            if (featureRenderWidth != renderWidth || featureRenderHeight != renderHeight
                    || featureDisplayWidth != displayWidth || featureDisplayHeight != displayHeight
                    || featureQuality != quality || featurePreset != preset
                    || isNull(feature)) {
                releaseFeature(device);
                feature = lib.createDlssd(cmd, renderWidth, renderHeight, displayWidth, displayHeight,
                        quality, FEATURE_FLAGS, preset);
                if (isNull(feature)) {
                    throw new IllegalStateException("ngxshim_create_dlssd failed: last=0x"
                            + Integer.toHexString(lib.lastResult()));
                }
                featureRenderWidth = renderWidth;
                featureRenderHeight = renderHeight;
                featureDisplayWidth = displayWidth;
                featureDisplayHeight = displayHeight;
                featureQuality = quality;
                featurePreset = preset;
                resetHistory = true; // a fresh feature has no temporal history
                UpscalerMod.LOGGER.info("DLSS-RR feature created: {}x{} -> {}x{} (quality {}, preset {})",
                        renderWidth, renderHeight, displayWidth, displayHeight, quality, preset);
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
        if (!PLATFORM_NATIVES.supported) {
            throw new IllegalStateException("DLSS-RR NGX natives are not bundled for "
                    + PLATFORM_NATIVES.platformDir);
        }
        Path shim = locateShim();
        if (shim == null) {
            throw new IllegalStateException(PLATFORM_NATIVES.shimName
                    + " not found (bundled natives or -Dupscaler.ngx.path)");
        }
        Path nativesDir = shim.getParent();
        if (nativesDir != null && !hasFeatureLibrary(nativesDir)) {
            UpscalerMod.LOGGER.warn("{} not found next to {}; DLSS-RR availability will fail",
                    PLATFORM_NATIVES.featureDescription(), PLATFORM_NATIVES.shimName);
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
        featureQuality = Integer.MIN_VALUE;
        featurePreset = Integer.MIN_VALUE;
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

    private static void putNgxLeftMultiplyMatrix(Matrix4fc m, MemorySegment dst) {
        // NGX wants row-major matrices used with left-multiplied row vectors. Our JOML/GLSL matrices are
        // used with column vectors, so the equivalent NGX matrix is the transpose; JOML's normal storage
        // order is exactly row-major storage of that transpose.
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 0, m.m00());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 1, m.m01());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 2, m.m02());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 3, m.m03());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 4, m.m10());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 5, m.m11());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 6, m.m12());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 7, m.m13());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 8, m.m20());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 9, m.m21());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 10, m.m22());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 11, m.m23());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 12, m.m30());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 13, m.m31());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 14, m.m32());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 15, m.m33());
    }

    private static Path locateShim() {
        String override = UpscalerConfig.Ngx.PATH.get();
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            if (Files.isDirectory(p)) {
                p = p.resolve(PLATFORM_NATIVES.shimName);
            }
            return Files.isRegularFile(p) ? p : null;
        }
        Path bundled = extractBundledNatives();
        if (bundled != null) {
            return bundled;
        }
        return null;
    }

    private static Path extractBundledNatives() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("upscaler-ngx")
                .resolve("natives").resolve(PLATFORM_NATIVES.platformDir);
        try {
            Files.createDirectories(dir);
            boolean hasShim = extractBundledNative(PLATFORM_NATIVES.shimName, dir.resolve(PLATFORM_NATIVES.shimName));
            extractBundledFeatureLibraries(dir);
            return hasShim && Files.isRegularFile(dir.resolve(PLATFORM_NATIVES.shimName))
                    ? dir.resolve(PLATFORM_NATIVES.shimName) : null;
        } catch (IOException e) {
            UpscalerMod.LOGGER.warn("Could not extract bundled NGX natives to {}", dir, e);
            return null;
        }
    }

    private static boolean extractBundledNative(String name, Path dst) throws IOException {
        String resource = PLATFORM_NATIVES.resourceDir() + name;
        try (InputStream in = RtDlssRr.class.getResourceAsStream(resource)) {
            if (in == null) {
                return false;
            }
            byte[] bytes = in.readAllBytes();
            if (!sameBytes(dst, bytes)) {
                Files.write(dst, bytes);
            }
            return true;
        }
    }

    private static void extractBundledFeatureLibraries(Path dir) throws IOException {
        if (PLATFORM_NATIVES.exactFeatureName != null) {
            extractBundledNative(PLATFORM_NATIVES.exactFeatureName, dir.resolve(PLATFORM_NATIVES.exactFeatureName));
            return;
        }
        for (String name : bundledFeatureLibraryNames()) {
            extractBundledNative(name, dir.resolve(name));
        }
    }

    private static List<String> bundledFeatureLibraryNames() {
        List<String> names = new ArrayList<>();
        FabricLoader.getInstance().getModContainer("upscaler").ifPresent(container -> {
            String nativeDir = "upscaler/natives/" + PLATFORM_NATIVES.platformDir;
            for (Path root : container.getRootPaths()) {
                Path dir = root.resolve(nativeDir);
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(dir)) {
                    files.map(path -> path.getFileName().toString())
                            .filter(PLATFORM_NATIVES::isFeatureLibrary)
                            .forEach(names::add);
                } catch (IOException e) {
                    UpscalerMod.LOGGER.warn("Could not list bundled NGX natives in {}", dir, e);
                }
            }
        });
        return names;
    }

    private static boolean hasFeatureLibrary(Path dir) {
        if (PLATFORM_NATIVES.exactFeatureName != null) {
            return Files.isRegularFile(dir.resolve(PLATFORM_NATIVES.exactFeatureName));
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString()).anyMatch(PLATFORM_NATIVES::isFeatureLibrary);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean sameBytes(Path path, byte[] bytes) throws IOException {
        try {
            return Files.size(path) == bytes.length && Arrays.equals(Files.readAllBytes(path), bytes);
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    private record PlatformNatives(String platformDir, String shimName, String exactFeatureName,
                                   String featureNamePrefix, boolean supported) {
        private static PlatformNatives current() {
            String os = System.getProperty("os.name", "").toLowerCase();
            String arch = System.getProperty("os.arch", "").toLowerCase();
            boolean x64 = arch.equals("x86_64") || arch.equals("amd64");
            if (os.contains("win") && x64) {
                return new PlatformNatives("windows-x64", "ngxshim.dll", "nvngx_dlssd.dll", null, true);
            }
            if (os.contains("linux") && x64) {
                return new PlatformNatives("linux-x64", "libngxshim.so", null, "libnvidia-ngx-dlssd.so", true);
            }
            return new PlatformNatives(os + "/" + arch, System.mapLibraryName("ngxshim"), null, null, false);
        }

        private String resourceDir() {
            return "/upscaler/natives/" + platformDir + "/";
        }

        private boolean isFeatureLibrary(String name) {
            return exactFeatureName != null ? exactFeatureName.equals(name) : name.startsWith(featureNamePrefix);
        }

        private String featureDescription() {
            return exactFeatureName != null ? exactFeatureName : featureNamePrefix + "*";
        }
    }
}
