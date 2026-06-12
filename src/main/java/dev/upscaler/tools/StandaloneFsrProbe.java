package dev.upscaler.tools;

import dev.upscaler.ffx.FfxLibrary;
import dev.upscaler.ffx.FfxUpscaleContext;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;
import java.nio.file.Path;

import static org.lwjgl.vulkan.VK13.*;

/**
 * Standalone FSR context-creation repro, no Minecraft involved. Creates a minimal
 * Vulkan 1.3 instance + device and runs the same FFX calls as the in-game smoke
 * test. Run configs:
 *   args[0] = path to amd_fidelityfx_vk.dll
 *   args[1] = "minimal" (default) or "full" (enables shaderInt16/shaderFloat16/subgroupSizeControl)
 */
public final class StandaloneFsrProbe {
	public static void main(String[] args) {
		Path dll = Path.of(args.length > 0 ? args[0]
				: "C:/Users/i/Developer/mc/fsr-sdk/PrebuiltSignedDLL/amd_fidelityfx_vk.dll");
		boolean full = args.length > 1 && args[1].equalsIgnoreCase("full");

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkApplicationInfo app = VkApplicationInfo.calloc(stack)
					.sType$Default()
					.pApplicationName(stack.UTF8("fsr-probe"))
					.apiVersion(VK_API_VERSION_1_3);
			VkInstanceCreateInfo instanceCi = VkInstanceCreateInfo.calloc(stack)
					.sType$Default()
					.pApplicationInfo(app);
			PointerBuffer pInstance = stack.mallocPointer(1);
			check(vkCreateInstance(instanceCi, null, pInstance), "vkCreateInstance");
			VkInstance instance = new VkInstance(pInstance.get(0), instanceCi);
			System.out.println("[probe] instance created");

			IntBuffer count = stack.mallocInt(1);
			check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices(count)");
			PointerBuffer physDevices = stack.mallocPointer(count.get(0));
			check(vkEnumeratePhysicalDevices(instance, count, physDevices), "vkEnumeratePhysicalDevices");
			VkPhysicalDevice physicalDevice = new VkPhysicalDevice(physDevices.get(0), instance);
			VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
			vkGetPhysicalDeviceProperties(physicalDevice, props);
			System.out.println("[probe] physical device: " + props.deviceNameString());

			IntBuffer queueCount = stack.mallocInt(1);
			vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, queueCount, null);
			VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(queueCount.get(0), stack);
			vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, queueCount, families);
			int graphicsFamily = -1;
			for (int i = 0; i < families.capacity(); i++) {
				if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
					graphicsFamily = i;
					break;
				}
			}
			System.out.println("[probe] graphics queue family: " + graphicsFamily);

			VkDeviceQueueCreateInfo.Buffer queueCi = VkDeviceQueueCreateInfo.calloc(1, stack);
			queueCi.get(0).sType$Default()
					.queueFamilyIndex(graphicsFamily)
					.pQueuePriorities(stack.floats(1.0f));

			VkPhysicalDeviceVulkan13Features f13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
			VkPhysicalDeviceVulkan12Features f12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
			VkPhysicalDeviceFeatures2 f2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
			if (full) {
				f2.features().shaderInt16(true);
				f12.shaderFloat16(true);
				f13.subgroupSizeControl(true);
				System.out.println("[probe] feature config: full (shaderInt16, shaderFloat16, subgroupSizeControl)");
			} else {
				System.out.println("[probe] feature config: minimal (no optional features)");
			}
			f12.pNext(f13.address());
			f2.pNext(f12.address());

			// FFX resolves vkGetImageMemoryRequirements2KHR & co. by their KHR-suffixed
			// extension names; vkGetDeviceProcAddr returns NULL for those unless the
			// corresponding (core-in-1.1) extensions are explicitly enabled.
			PointerBuffer extensions = stack.pointers(
					stack.UTF8("VK_KHR_get_memory_requirements2"),
					stack.UTF8("VK_KHR_dedicated_allocation"));

			VkDeviceCreateInfo deviceCi = VkDeviceCreateInfo.calloc(stack)
					.sType$Default()
					.pNext(f2.address())
					.ppEnabledExtensionNames(extensions)
					.pQueueCreateInfos(queueCi);
			PointerBuffer pDevice = stack.mallocPointer(1);
			check(vkCreateDevice(physicalDevice, deviceCi, null, pDevice), "vkCreateDevice");
			VkDevice device = new VkDevice(pDevice.get(0), physicalDevice, deviceCi);
			System.out.println("[probe] device created: 0x" + Long.toHexString(device.address()));

			long fpGetDeviceProcAddr = vkGetInstanceProcAddr(instance, stack.ASCII("vkGetDeviceProcAddr"));
			System.out.println("[probe] vkGetDeviceProcAddr = 0x" + Long.toHexString(fpGetDeviceProcAddr));

			FfxLibrary lib = FfxLibrary.load(dll);
			System.out.println("[probe] ffx loaded from " + dll);
			System.out.println("[probe] providers: " + FfxUpscaleContext.queryAvailableVersions(lib));

			System.out.println("[probe] creating upscale context (minimal flags)...");
			try (FfxUpscaleContext ctx = FfxUpscaleContext.create(lib,
					device.address(), physicalDevice.address(), fpGetDeviceProcAddr,
					1920, 1080, 1920, 1080, 0, false)) {
				System.out.println("[probe] context created OK");
				System.out.println("[probe] renderRes(Quality@1920x1080) = " + ctx.queryRenderResolution(1920, 1080, FfxUpscaleContext.QUALITY_QUALITY));
				System.out.println("[probe] gpuMem = " + ctx.queryGpuMemoryUsage());
			}
			System.out.println("[probe] context destroyed OK");

			vkDestroyDevice(device, null);
			vkDestroyInstance(instance, null);
			System.out.println("[probe] PASS");
		}
	}

	private static void check(int result, String what) {
		if (result != VK_SUCCESS) {
			throw new IllegalStateException(what + " failed: VkResult " + result);
		}
	}
}
