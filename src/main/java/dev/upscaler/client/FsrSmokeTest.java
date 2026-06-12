package dev.upscaler.client;

import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.upscaler.UpscalerMod;
import dev.upscaler.ffx.FfxLibrary;
import dev.upscaler.ffx.FfxUpscaleContext;
import dev.upscaler.mixin.GpuDeviceAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * M1 milestone: prove the full FFM -> amd_fidelityfx_vk.dll -> FSR 3.1 upscale
 * context lifecycle against the live game Vulkan device. Creates a context sized
 * to the current window, runs every query we will need later (render resolutions
 * per quality mode, jitter phase count and offsets, VRAM usage), then destroys it.
 * No rendering is affected.
 */
public final class FsrSmokeTest {
	private static final String DLL_NAME = "amd_fidelityfx_vk.dll";

	private FsrSmokeTest() {
	}

	/** @return true if the test ran (successfully or not) and should not be retried */
	public static boolean run() {
		GpuDeviceBackend backend = ((GpuDeviceAccessor) RenderSystem.getDevice()).upscaler$getBackend();
		if (!(backend instanceof VulkanDevice vulkanDevice)) {
			UpscalerMod.LOGGER.warn("FSR smoke test skipped: graphics backend is {} — set Video Settings -> Graphics API -> Vulkan and restart",
					backend.getClass().getSimpleName());
			return true;
		}

		Path dll = locateDll();
		if (dll == null) {
			UpscalerMod.LOGGER.error("FSR smoke test failed: {} not found. Place it in the run directory under natives/, or set -Dupscaler.ffx.path=<full path>", DLL_NAME);
			return true;
		}

		try {
			runWithDevice(vulkanDevice, dll);
		} catch (Throwable t) {
			UpscalerMod.LOGGER.error("FSR smoke test FAILED", t);
		}
		return true;
	}

	private static void runWithDevice(VulkanDevice vulkanDevice, Path dll) {
		UpscalerMod.LOGGER.info("FSR smoke test [stage 0]: loading {}", dll);
		FfxLibrary lib = FfxLibrary.load(dll);

		// Stage 1: context-less version query. Touches no device — validates the FFM
		// plumbing and descriptor layout in isolation.
		var versions = FfxUpscaleContext.queryAvailableVersions(lib);
		UpscalerMod.LOGGER.info("FSR smoke test [stage 1]: {} upscale provider version(s): {}",
				versions.size(), versions);

		VkDevice vkDevice = vulkanDevice.vkDevice();
		VkPhysicalDevice physicalDevice = vkDevice.getPhysicalDevice();
		VkInstance instance = physicalDevice.getInstance();

		long fpGetDeviceProcAddr;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			fpGetDeviceProcAddr = VK10.vkGetInstanceProcAddr(instance, stack.ASCII("vkGetDeviceProcAddr"));
		}
		if (fpGetDeviceProcAddr == 0L) {
			throw new IllegalStateException("vkGetInstanceProcAddr(vkGetDeviceProcAddr) returned NULL");
		}

		var window = Minecraft.getInstance().getWindow();
		int displayWidth = Math.max(1, window.getWidth());
		int displayHeight = Math.max(1, window.getHeight());

		// Stage 2: minimal context creation — no flags, no message callback.
		UpscalerMod.LOGGER.info("FSR smoke test [stage 2]: creating upscale context, minimal config (display {}x{}, device=0x{}, physicalDevice=0x{}, getDeviceProcAddr=0x{})",
				displayWidth, displayHeight,
				Long.toHexString(vkDevice.address()), Long.toHexString(physicalDevice.address()),
				Long.toHexString(fpGetDeviceProcAddr));

		long start = System.nanoTime();
		try (FfxUpscaleContext context = FfxUpscaleContext.create(lib,
				vkDevice.address(), physicalDevice.address(), fpGetDeviceProcAddr,
				displayWidth, displayHeight,   // maxRenderSize = display so NativeAA also fits
				displayWidth, displayHeight,
				0, false)) {
			long createdMs = (System.nanoTime() - start) / 1_000_000;
			UpscalerMod.LOGGER.info("FSR smoke test [stage 2]: context created OK in {} ms", createdMs);

			String[] names = {"NativeAA", "Quality", "Balanced", "Performance", "UltraPerformance"};
			int renderWidthForJitter = displayWidth;
			for (int mode = 0; mode <= 4; mode++) {
				var res = context.queryRenderResolution(displayWidth, displayHeight, mode);
				float ratio = context.queryUpscaleRatio(mode);
				UpscalerMod.LOGGER.info("FSR smoke test: quality={} -> render {}x{} (ratio {}x)",
						names[mode], res.width(), res.height(), String.format("%.2f", ratio));
				if (mode == FfxUpscaleContext.QUALITY_QUALITY) {
					renderWidthForJitter = res.width();
				}
			}

			int phaseCount = context.queryJitterPhaseCount(renderWidthForJitter, displayWidth);
			UpscalerMod.LOGGER.info("FSR smoke test: jitter phase count for render width {} = {}", renderWidthForJitter, phaseCount);
			for (int i = 0; i < Math.min(4, phaseCount); i++) {
				var jitter = context.queryJitterOffset(i, phaseCount);
				UpscalerMod.LOGGER.info("FSR smoke test: jitter[{}] = ({}, {})", i,
						String.format("%.4f", jitter.x()), String.format("%.4f", jitter.y()));
			}

			var mem = context.queryGpuMemoryUsage();
			UpscalerMod.LOGGER.info("FSR smoke test: upscaler GPU memory = {} MiB total, {} MiB aliasable",
					mem.totalBytes() / (1024 * 1024), mem.aliasableBytes() / (1024 * 1024));
		}
		UpscalerMod.LOGGER.info("FSR smoke test [stage 2]: context destroyed OK");

		// Stage 3: full config — debug checking + message callback upcall.
		UpscalerMod.LOGGER.info("FSR smoke test [stage 3]: creating upscale context with DEBUG_CHECKING + message callback");
		try (FfxUpscaleContext context = FfxUpscaleContext.create(lib,
				vkDevice.address(), physicalDevice.address(), fpGetDeviceProcAddr,
				displayWidth, displayHeight,
				displayWidth, displayHeight,
				FfxUpscaleContext.FLAG_DEBUG_CHECKING, true)) {
			UpscalerMod.LOGGER.info("FSR smoke test [stage 3]: context created OK");
		}

		UpscalerMod.LOGGER.info("FSR smoke test: all stages PASSED");
	}

	private static Path locateDll() {
		String override = System.getProperty("upscaler.ffx.path");
		if (override != null) {
			Path p = Path.of(override);
			return Files.isRegularFile(p) ? p : null;
		}

		Path runDir = FabricLoader.getInstance().getGameDir();
		Path[] candidates = {
				runDir.resolve("natives").resolve(DLL_NAME),
				runDir.resolve(DLL_NAME),
		};
		for (Path candidate : candidates) {
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}
		return null;
	}
}
