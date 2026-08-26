package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtHdr;
import dev.comfyfluffy.caustica.rt.VulkanDiagnostics;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Vulkan device-negotiation hook: adds the device extensions the Caustica runtime needs to the extension
 * list vanilla enables at vkCreateDevice time.
 *
 * <p>FFX resolves vkGetImageMemoryRequirements2KHR etc. through
 * vkGetDeviceProcAddr using the KHR-suffixed extension names; per Vulkan spec
 * that returns NULL unless the corresponding extension was enabled — even
 * though the functionality is core since 1.1 — and FFX then calls the NULL
 * pointer (verified: crash at amd_fidelityfx_vk.dll+0x1e5b0 building
 * VkMemoryRequirements2). Enabling the alias extensions is a behavioral no-op
 * for the rest of the engine.
 */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
	private static final VulkanFeature STORAGE_IMAGE_WRITE_WITHOUT_FORMAT =
			new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "shaderStorageImageWriteWithoutFormat",
					VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEWRITEWITHOUTFORMAT);
	private static final List<VulkanFeature> SDK_SHADER_FEATURES = List.of(
			STORAGE_IMAGE_WRITE_WITHOUT_FORMAT,
			new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "shaderInt16", VkPhysicalDeviceFeatures.SHADERINT16),
			new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "shaderFloat16", VkPhysicalDeviceVulkan12Features.SHADERFLOAT16));

	private static final List<String> FFX_WANTED_EXTENSIONS = List.of(
			"VK_KHR_get_memory_requirements2",
			"VK_KHR_dedicated_allocation");

	private static final Set<String> loggedMissingSdkFeatures = new HashSet<>();

	/** NVIDIA advertises AMD markers too, but its native diagnostic checkpoints contain better fault context. */
	@WrapOperation(
			method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;",
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;hasDeviceExtension(Ljava/lang/String;)Z"))
	private boolean caustica$preferNvidiaCheckpoints(VulkanPhysicalDevice physicalDevice, String extension,
			Operation<Boolean> original) {
		if ("VK_AMD_buffer_marker".equals(extension)
				&& "NVIDIA".equals(physicalDevice.vendorName())
				&& physicalDevice.hasDeviceExtension("VK_NV_device_diagnostic_checkpoints")) {
			return false;
		}
		return original.call(physicalDevice, extension);
	}

	@ModifyArgs(
			method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;",
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;"))
	private void caustica$addDeviceExtensions(Args args) {
		VulkanPhysicalDevice physicalDevice = args.get(1);

		Collection<String> requested = args.get(0);
		var augmented = new ArrayList<>(requested);
		for (String extension : FFX_WANTED_EXTENSIONS) {
			if (augmented.contains(extension)) {
				continue;
			}
			if (physicalDevice.hasDeviceExtension(extension)) {
				augmented.add(extension);
				CausticaMod.LOGGER.info("Enabling device extension {} for the Caustica runtime", extension);
			} else {
				CausticaMod.LOGGER.warn("Device extension {} not supported by {} — upscaling will be unavailable",
						extension, physicalDevice.deviceName());
			}
		}
		if ("NVIDIA".equals(physicalDevice.vendorName())) {
			NgxRuntime.INSTANCE.negotiateRequiredExtensions(true, augmented,
					physicalDevice::hasDeviceExtension);
		}
		VulkanDiagnostics.addDeviceFaultExtension(augmented, physicalDevice);
		RtHdr.addDeviceExtension(augmented, physicalDevice);
		RtDeviceBringup.addExtensions(augmented, physicalDevice);
		args.set(0, augmented);

		caustica$addCoreDeviceFeatures(args, physicalDevice);
		VulkanDiagnostics.addDeviceFaultFeature(args);
		RtDeviceBringup.addFeatures(args, physicalDevice);
		VulkanDiagnostics.logEnabledExtensions(augmented);
	}

	@SuppressWarnings("unchecked")
	private void caustica$addCoreDeviceFeatures(Args args, VulkanPhysicalDevice physicalDevice) {
		Set<VulkanFeature> features = new HashSet<>((Set<VulkanFeature>) args.get(2));
		boolean changed = false;
		for (VulkanFeature feature : SDK_SHADER_FEATURES) {
			if (!caustica$supportsFeature(physicalDevice, feature)) {
				if (loggedMissingSdkFeatures.add(feature.name())) {
					CausticaMod.LOGGER.warn("Device [{}] lacks {}; FSR/DLSS SDK shaders may fail validation",
							physicalDevice.deviceName(), feature.name());
				}
				continue;
			}

			if (features.add(feature)) {
				changed = true;
				CausticaMod.LOGGER.info("Enabling Vulkan feature {} for FSR/DLSS SDK shaders", feature.name());
			}
		}
		if (changed) {
			args.set(2, features);
		}
	}

	private static boolean caustica$supportsFeature(VulkanPhysicalDevice physicalDevice, VulkanFeature feature) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceFeatures2 deviceFeatures = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
			feature.struct().findOrCreateStructInPNextChain(deviceFeatures, stack);
			VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), deviceFeatures);
			return feature.get(deviceFeatures);
		}
	}

	/**
	 * Once the RT-augmented device is created, confirm the RT entry
	 * points loaded and log the RT/AS limits. {@code device} is the local assigned just
	 * before {@code createVma} runs.
	 */
	@Inject(
			method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;",
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createVma(Lorg/lwjgl/vulkan/VkDevice;)J"))
	private void caustica$probeRayTracing(long window, ShaderSource defaultShaderSource, GpuDebugOptions debugOptions,
			Runnable criticalShaderLoader, CallbackInfoReturnable<GpuDevice> cir, @Local VkDevice device) {
		VulkanDiagnostics.probe(device);
		RtDeviceBringup.probe(device);
	}

	@Inject(
			method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
			at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
	private static void caustica$augmentDeviceCreateInfo(Collection<String> extensions,
			VulkanPhysicalDevice physicalDevice, Set<VulkanFeature> features,
			CallbackInfoReturnable<VkDevice> cir, @Local VkDeviceCreateInfo deviceCreateInfo) {
		RtDeviceBringup.reserveComputeQueue(deviceCreateInfo, physicalDevice, MemoryStack.stackGet());
		VulkanDiagnostics.attachNvDiagnosticsConfig(deviceCreateInfo, MemoryStack.stackGet());
	}
}
