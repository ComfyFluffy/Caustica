package dev.comfyfluffy.caustica.rt;

import java.nio.IntBuffer;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;

/**
 * HDR display support — capability detection/logging. This class does not change any rendering behavior; it
 * only enumerates and logs what the swapchain surface can present so the swapchain-ownership code knows
 * whether HDR10 (PQ) is actually available on this driver, window system, compositor, and monitor.
 *
 * <p>Important: extended color spaces (scRGB linear, HDR10 PQ, …) are only reported by
 * {@code vkGetPhysicalDeviceSurfaceFormatsKHR} when the instance was created with
 * {@code VK_EXT_swapchain_colorspace} enabled. Minecraft's instance does not enable it, so on a stock
 * instance this enumeration is expected to show only {@code SRGB_NONLINEAR} (color space 0). Seeing only
 * color space 0 here is the concrete signal that the later phase must add an instance-extension hook before
 * any HDR swapchain is possible — that is exactly the gap this Phase 0 logging is meant to surface.
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

    private RtHdr() {
    }

    /** Logs the resolved HDR config once (cheap; safe to call repeatedly — guarded by the surface log). */
    public static void logConfig() {
        CausticaMod.LOGGER.info(
                "HDR config: enabled={} paperWhite={}nits peak={}nits -> {} (headroom={})",
                CausticaConfig.Rt.Hdr.enabled(),
                CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS.value(), CausticaConfig.Rt.Hdr.PEAK_NITS.value(),
                CausticaConfig.Rt.Hdr.enabled() ? "HDR display path active" : "SDR display path",
                CausticaConfig.Rt.Hdr.headroom());
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
