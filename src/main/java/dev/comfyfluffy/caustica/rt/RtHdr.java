package dev.comfyfluffy.caustica.rt;

import java.nio.IntBuffer;
import java.util.List;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTHdrMetadata;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkHdrMetadataEXT;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;

import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;

/**
 * HDR display support — capability detection/logging plus static mastering metadata for PQ swapchains.
 * Surface enumeration tells the swapchain-ownership code whether HDR10 is available on the current driver,
 * window system, compositor, and monitor; {@code VK_EXT_hdr_metadata}, when supported, describes the
 * selected Rec.2020/D65 virtual mastering display to that presentation stack.
 *
 * <p>Extended color spaces are reported only when the instance enables
 * {@code VK_EXT_swapchain_colorspace}. {@code VulkanInstanceMixin} enables it when available; this class
 * then reports the surface formats and selects HDR10/PQ capability from the advertised pairs.
 */
public final class RtHdr {
    // VK_EXT_swapchain_colorspace color-space enum values (not all are in the LWJGL VK10 constants).
    private static final int CS_SRGB_NONLINEAR = 0;
    private static final int CS_DISPLAY_P3_NONLINEAR = 1000104001;
    private static final int CS_EXTENDED_SRGB_LINEAR = 1000104002;
    private static final int CS_DISPLAY_P3_LINEAR = 1000104003;
    private static final int CS_DCI_P3_NONLINEAR = 1000104004;
    private static final int CS_BT709_LINEAR = 1000104005;
    private static final int CS_BT709_NONLINEAR = 1000104006;
    private static final int CS_BT2020_LINEAR = 1000104007;
    private static final int CS_HDR10_ST2084 = 1000104008;
    private static final int CS_DOLBYVISION = 1000104009;
    private static final int CS_HDR10_HLG = 1000104010;
    private static final int CS_ADOBERGB_LINEAR = 1000104011;
    private static final int CS_ADOBERGB_NONLINEAR = 1000104012;
    private static final int CS_PASS_THROUGH = 1000104013;
    private static final int CS_EXTENDED_SRGB_NONLINEAR = 1000104014;

    private static volatile boolean surfaceLogged;
    private static volatile boolean hdrMetadataExtensionEnabled;

    private RtHdr() {
    }

    /**
     * Opportunistically enables {@code VK_EXT_hdr_metadata}. It is a function-only device extension, so
     * there is no feature struct to chain into device creation. HDR presentation still works when it is
     * absent; only the mastering hints to the presentation engine are unavailable.
     */
    public static void addDeviceExtension(List<String> augmentedExtensions, VulkanPhysicalDevice physicalDevice) {
        hdrMetadataExtensionEnabled = false;
        String extension = EXTHdrMetadata.VK_EXT_HDR_METADATA_EXTENSION_NAME;
        if (!physicalDevice.hasDeviceExtension(extension)) {
            CausticaMod.LOGGER.warn("HDR: device [{}] does not support {}; static mastering metadata disabled",
                    physicalDevice.deviceName(), extension);
            return;
        }
        if (!augmentedExtensions.contains(extension)) {
            augmentedExtensions.add(extension);
        }
        hdrMetadataExtensionEnabled = true;
        CausticaMod.LOGGER.info("HDR: enabling {} for PQ swapchain mastering metadata", extension);
    }

    /** Whether {@code VK_EXT_hdr_metadata} was included in the device extension list. */
    public static boolean metadataExtensionEnabled() {
        return hdrMetadataExtensionEnabled;
    }

    /**
     * Assigns SMPTE ST 2086 / CTA-861.3 static metadata to one PQ swapchain.
     *
     * <p>The active HDR output transform supplies the Rec.2020/D65 virtual master peak, so metadata matches
     * the actual displayed transform. ACES 2.0 uses the nearest packaged LUT peak; analytical modes use the
     * exact configured peak. MaxFALL cannot be known without analysing every rendered frame; Vulkan explicitly
     * permits unknown fields to be zero, which is more truthful than inventing a scene-average value.
     */
    public static boolean applyMasteringMetadata(VkDevice device, long swapchain, int masteringPeakNits) {
        if (!hdrMetadataExtensionEnabled || swapchain == 0L) {
            return false;
        }
        MasteringMetadata values = masteringMetadata(masteringPeakNits);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkHdrMetadataEXT.Buffer metadata = VkHdrMetadataEXT.calloc(1, stack);
            VkHdrMetadataEXT entry = metadata.get(0).sType$Default();
            entry.displayPrimaryRed().set(values.red().x(), values.red().y());
            entry.displayPrimaryGreen().set(values.green().x(), values.green().y());
            entry.displayPrimaryBlue().set(values.blue().x(), values.blue().y());
            entry.whitePoint().set(values.white().x(), values.white().y());
            entry.maxLuminance(values.maxLuminance());
            entry.minLuminance(values.minLuminance());
            entry.maxContentLightLevel(values.maxContentLightLevel());
            entry.maxFrameAverageLightLevel(values.maxFrameAverageLightLevel());
            EXTHdrMetadata.vkSetHdrMetadataEXT(device, stack.longs(swapchain), metadata);
        }
        CausticaMod.LOGGER.info(
                "HDR: set swapchain mastering metadata: Rec.2020/D65, min={} nits, peak/MaxCLL={} nits, MaxFALL=unknown",
                values.minLuminance(), values.maxLuminance());
        return true;
    }

    static MasteringMetadata masteringMetadata(int masteringPeakNits) {
        if (masteringPeakNits <= 0) {
            throw new IllegalArgumentException("masteringPeakNits must be positive");
        }
        float peak = masteringPeakNits;
        return new MasteringMetadata(
                new Chromaticity(0.708f, 0.292f),
                new Chromaticity(0.170f, 0.797f),
                new Chromaticity(0.131f, 0.046f),
                new Chromaticity(0.3127f, 0.3290f),
                peak, 0.0001f, peak, 0.0f);
    }

    record Chromaticity(float x, float y) {
    }

    record MasteringMetadata(
            Chromaticity red,
            Chromaticity green,
            Chromaticity blue,
            Chromaticity white,
            float maxLuminance,
            float minLuminance,
            float maxContentLightLevel,
            float maxFrameAverageLightLevel) {
    }

    /** Logs the resolved HDR config once (cheap; safe to call repeatedly — guarded by the surface log). */
    public static void logConfig() {
        CausticaMod.LOGGER.info(
                "HDR config: enabled={} ui={}nits requestedPeak={}nits effectivePeak={}nits -> {}",
                CausticaConfig.Rt.Hdr.enabled(),
                CausticaConfig.Rt.Hdr.UI_NITS.value(), CausticaConfig.Rt.Hdr.PEAK_NITS.value(),
                CausticaConfig.Rt.Hdr.effectivePeakNits(),
                CausticaConfig.Rt.Hdr.enabled() ? "HDR display path active" : "SDR display path");
    }

    /**
     * Enumerates and logs the surface's supported (format, color space) pairs. Called once from the
     * {@code VulkanGpuSurface} constructor hook, where both the physical device and the live surface handle
     * are available. No-op on repeat calls.
     */
    public static void logSurfaceCapabilities(VkPhysicalDevice phys, long surface, int chosenFormat) {
        if (surfaceLogged) {
            return;
        }
        surfaceLogged = true;
        logConfig();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.callocInt(1);
            int r = KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(phys, surface, count, null);
            if (r < 0 || count.get(0) == 0) {
                CausticaMod.LOGGER.warn("HDR: could not enumerate surface formats (result={}, count={})", r, count.get(0));
                return;
            }
            int n = count.get(0);
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.calloc(n, stack);
            KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(phys, surface, count, formats);

            boolean anyHdr = false;
            CausticaMod.LOGGER.info("HDR: chosen swapchain image format={} ({}); surface advertises {} (format,colorSpace) pair(s):",
                    chosenFormat, formatName(chosenFormat), n);
            for (int i = 0; i < n; i++) {
                VkSurfaceFormatKHR f = formats.get(i);
                int cs = f.colorSpace();
                boolean hdr = isHdrColorSpace(cs);
                anyHdr |= hdr;
                CausticaMod.LOGGER.info("  [{}] format={} ({}), colorSpace={} ({}){}",
                        i, f.format(), formatName(f.format()), cs, colorSpaceName(cs), hdr ? "  <-- HDR-capable" : "");
            }
            if (anyHdr) {
                CausticaMod.LOGGER.info("HDR: at least one HDR-capable color space is exposed; PQ/HDR10 presentation is available.");
            } else {
                CausticaMod.LOGGER.warn("HDR: only SDR color spaces are exposed by this Vulkan surface. Enable OS/display HDR; on Linux, use native Wayland with HDR enabled in the compositor.");
            }
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("HDR: surface capability enumeration failed: {}", t.toString());
        }
    }

    private static boolean isHdrColorSpace(int cs) {
        return cs == CS_EXTENDED_SRGB_LINEAR || cs == CS_EXTENDED_SRGB_NONLINEAR
                || cs == CS_HDR10_ST2084 || cs == CS_HDR10_HLG || cs == CS_DOLBYVISION
                || cs == CS_BT2020_LINEAR || cs == CS_DISPLAY_P3_LINEAR;
    }

    private static String colorSpaceName(int cs) {
        return switch (cs) {
            case CS_SRGB_NONLINEAR -> "SRGB_NONLINEAR";
            case CS_DISPLAY_P3_NONLINEAR -> "DISPLAY_P3_NONLINEAR";
            case CS_EXTENDED_SRGB_LINEAR -> "EXTENDED_SRGB_LINEAR (scRGB)";
            case CS_DISPLAY_P3_LINEAR -> "DISPLAY_P3_LINEAR";
            case CS_DCI_P3_NONLINEAR -> "DCI_P3_NONLINEAR";
            case CS_BT709_LINEAR -> "BT709_LINEAR";
            case CS_BT709_NONLINEAR -> "BT709_NONLINEAR";
            case CS_BT2020_LINEAR -> "BT2020_LINEAR";
            case CS_HDR10_ST2084 -> "HDR10_ST2084 (PQ)";
            case CS_DOLBYVISION -> "DOLBYVISION";
            case CS_HDR10_HLG -> "HDR10_HLG";
            case CS_ADOBERGB_LINEAR -> "ADOBERGB_LINEAR";
            case CS_ADOBERGB_NONLINEAR -> "ADOBERGB_NONLINEAR";
            case CS_PASS_THROUGH -> "PASS_THROUGH";
            case CS_EXTENDED_SRGB_NONLINEAR -> "EXTENDED_SRGB_NONLINEAR";
            default -> "unknown";
        };
    }

    /** Names the few VkFormat values relevant to swapchain/HDR output; other formats print as the raw enum. */
    private static String formatName(int format) {
        return switch (format) {
            case 37 -> "R8G8B8A8_UNORM";
            case 43 -> "R8G8B8A8_SRGB";
            case 44 -> "B8G8R8A8_UNORM";
            case 50 -> "B8G8R8A8_SRGB";
            case 64 -> "A2R10G10B10_UNORM_PACK32";
            case 97 -> "R16G16B16A16_SFLOAT";
            default -> "VkFormat#" + format;
        };
    }
}
