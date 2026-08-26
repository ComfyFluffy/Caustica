package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtFramePresenter;
import dev.comfyfluffy.caustica.rt.RtHdr;
import dev.comfyfluffy.caustica.rt.RtReflex;
import it.unimi.dsi.fastutil.longs.LongList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentIdKHR;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkSwapchainLatencyCreateInfoNV;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * HDR capability logging and PQ swapchain selection.
 *
 * <p>The {@link VulkanGpuSurface} constructor holds both the live {@code VkSurfaceKHR} and the physical
 * device, so we enumerate the surface's formats/color spaces there (once) for diagnostics.
 *
 * <p>When HDR is enabled and the surface advertises HDR10_ST2084 (paired with whatever pixel format the
 * surface offers for it), we steer Minecraft's swapchain to it. When HDR is disabled, configure selects
 * vanilla's native SDR pair. The options callback invalidates the surface configuration, so toggling HDR
 * reuses the same swapchain recreation path as resize.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {
	private static final int VK_COLOR_SPACE_HDR10_ST2084_EXT = 1000104008;

	@Shadow
	@Final
	private VulkanDevice device;

	@Shadow
	@Final
	private long surface;

	@Shadow
	@Final
	private org.lwjgl.vulkan.VkQueue presentQueue;

	@Shadow
	private long swapchain;

	@Shadow
	@Final
	@Mutable
	private int swapchainImageFormat;

	@Shadow
	@Final
	private LongList swapchainImages;

	@Shadow
	private int currentImageIndex;

	@Shadow
	private int swapchainWidth;

	@Shadow
	private int swapchainHeight;

	@Shadow
	@Final
	private long[] acquireSemaphores;

	@Shadow
	private int currentAcquireSemaphore;

	@Shadow
	private long[] presentSemaphores;

	@Unique
	private int caustica$colorSpace = 0;

	@Unique
	private long caustica$metadataSwapchain;

	@Unique
	private int caustica$metadataPeakNits = -1;

	@Inject(method = "<init>(Lcom/mojang/blaze3d/vulkan/VulkanDevice;J)V", at = @At("TAIL"))
	private void caustica$logHdrCapabilities(VulkanDevice device, long windowHandle, CallbackInfo ci) {
		try {
			RtHdr.logSurfaceCapabilities(this.device.vkDevice().getPhysicalDevice(), this.surface, this.swapchainImageFormat);
		} catch (Throwable t) {
			// Diagnostics only — never let HDR logging break surface creation.
		}
	}

	/** Discover PQ capability during construction and select PQ only when HDR starts enabled. */
	@Inject(method = "pickSwapchainSurfaceFormat", at = @At("HEAD"), cancellable = true)
	private void caustica$pickPqFormat(VkSurfaceFormatKHR.Buffer formats, CallbackInfoReturnable<VkSurfaceFormatKHR> cir) {
		VkSurfaceFormatKHR pq = caustica$findPq(formats);
		CausticaConfig.Rt.Hdr.setSwapchainPqAvailable(pq != null);
		this.caustica$colorSpace = 0;
		CausticaConfig.Rt.Hdr.setSwapchainPqActive(false);
		if (CausticaConfig.Rt.Hdr.ENABLED.value() && pq != null) {
			this.caustica$colorSpace = VK_COLOR_SPACE_HDR10_ST2084_EXT;
			CausticaConfig.Rt.Hdr.setSwapchainPqActive(true);
			CausticaMod.LOGGER.info("HDR: surface supports PQ (format={}, colorSpace=HDR10_ST2084); "
					+ "creating the initial swapchain in PQ", pq.format());
			cir.setReturnValue(pq);
		}
	}

	/**
	 * A framebuffer resize already recreates the swapchain through {@code configure}. Refresh the chosen
	 * (format,colorSpace) pair at that same boundary so the HDR option can use the identical path without
	 * recreating the window or Vulkan surface. Vanilla made swapchainImageFormat final because resize
	 * normally keeps it fixed; the mixin marks that field mutable specifically for this re-selection.
	 */
	@Inject(method = "configure", at = @At("HEAD"))
	private void caustica$refreshFormatForConfigure(GpuSurface.Configuration config, CallbackInfo ci) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer count = stack.callocInt(1);
			int countResult = KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(
					this.device.vkDevice().getPhysicalDevice(), this.surface, count, null);
			if (countResult != VK10.VK_SUCCESS || count.get(0) <= 0) {
				CausticaMod.LOGGER.warn("HDR: failed to enumerate swapchain formats during recreation: {}",
						countResult);
				return;
			}
			VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.calloc(count.get(0), stack);
			int formatsResult = KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(
					this.device.vkDevice().getPhysicalDevice(), this.surface, count, formats);
			if (formatsResult != VK10.VK_SUCCESS) {
				CausticaMod.LOGGER.warn("HDR: failed to read swapchain formats during recreation: {}",
						formatsResult);
				return;
			}
			formats.limit(Math.min(formats.capacity(), count.get(0)));

			VkSurfaceFormatKHR pq = caustica$findPq(formats);
			VkSurfaceFormatKHR sdr = caustica$findSdr(formats);
			CausticaConfig.Rt.Hdr.setSwapchainPqAvailable(pq != null);
			boolean usePq = CausticaConfig.Rt.Hdr.ENABLED.value() && pq != null;
			if (!usePq && sdr == null && pq != null) {
				// Extremely unusual, but safer than destroying the only viable presentation path.
				CausticaMod.LOGGER.warn("HDR: surface exposes PQ but no compatible native-SDR format; "
						+ "keeping the PQ swapchain and embedding SDR content");
				usePq = true;
			}
			if (usePq) {
				this.swapchainImageFormat = pq.format();
				this.caustica$colorSpace = VK_COLOR_SPACE_HDR10_ST2084_EXT;
			} else {
				if (sdr == null) {
					CausticaMod.LOGGER.warn("HDR: surface exposes no compatible SDR or PQ format during recreation");
					return;
				}
				this.swapchainImageFormat = sdr.format();
				this.caustica$colorSpace = 0;
			}
			CausticaConfig.Rt.Hdr.setSwapchainPqActive(usePq);
			CausticaMod.LOGGER.info("HDR: recreating swapchain as {} (format={}, colorSpace={})",
					usePq ? "PQ" : "native SDR", this.swapchainImageFormat,
					usePq ? "HDR10_ST2084" : "SRGB_NONLINEAR");
		}
	}

	@Unique
	private static VkSurfaceFormatKHR caustica$findPq(VkSurfaceFormatKHR.Buffer formats) {
		for (int i = 0; i < formats.capacity(); i++) {
			VkSurfaceFormatKHR f = formats.get(i);
			if (f.colorSpace() == VK_COLOR_SPACE_HDR10_ST2084_EXT) {
				return f;
			}
		}
		return null;
	}

	@Unique
	private static VkSurfaceFormatKHR caustica$findSdr(VkSurfaceFormatKHR.Buffer formats) {
		for (int i = 0; i < formats.capacity(); i++) {
			VkSurfaceFormatKHR f = formats.get(i);
			if (f.colorSpace() == 0 && (f.format() == 37 || f.format() == 44)) {
				return f;
			}
		}
		return null;
	}

	/** Replace the hardcoded {@code imageColorSpace(0)} with the PQ color space when one was selected. */
	@ModifyArg(method = "configure",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageColorSpace(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"),
			index = 0)
	private int caustica$overrideColorSpace(int original) {
		return this.caustica$colorSpace != 0 ? this.caustica$colorSpace : original;
	}

	/**
	 * Chain {@code VkSwapchainLatencyCreateInfoNV{latencyModeEnable=true}} into the swapchain's pNext at
	 * creation. {@code vkSetLatencySleepModeNV} only takes effect on a swapchain created with this flag,
	 * so it has to be set here,
	 * before there's any other reason to touch swapchain creation. Preserves whatever pNext was already
	 * there (currently nothing else chains one). The extra struct is stack-allocated and only needs to
	 * survive this call — Vulkan reads pNext chains synchronously during {@code vkCreateSwapchainKHR}, it
	 * doesn't retain the pointer afterward, so freeing it when this method's stack frame pops is safe even
	 * though {@code pCreateInfo} isn't touched again after this point in {@code configure()}. No-op (calls
	 * through unchanged) when Reflex isn't enabled + device-supported.
	 */
	@Redirect(method = "configure",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private int caustica$createSwapchainWithReflex(VkDevice device, VkSwapchainCreateInfoKHR pCreateInfo,
			VkAllocationCallbacks pAllocator, LongBuffer pSwapchain) {
		if (!RtDeviceBringup.reflexEnabled()) {
			return KHRSwapchain.vkCreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSwapchainLatencyCreateInfoNV latency = VkSwapchainLatencyCreateInfoNV.calloc(stack).sType$Default();
			latency.pNext(pCreateInfo.pNext());
			latency.latencyModeEnable(true);
			pCreateInfo.pNext(latency.address());
			return KHRSwapchain.vkCreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);
		}
	}

	/**
	 * Reapply the Reflex sleep-mode config for the configured swapchain. The configuration
	 * is scoped to a specific swapchain object, so it must be re-called whenever {@code configure()} builds a
	 * new one (e.g. resize) — {@link RtReflex#applySleepMode} is idempotent (no-op if unchanged), so calling
	 * it unconditionally here is cheap. No-op when Reflex isn't enabled + device-supported.
	 */
	@Inject(method = "configure", at = @At("TAIL"))
	private void caustica$applySwapchainExtensionState(GpuSurface.Configuration config, CallbackInfo ci) {
		caustica$applyHdrMetadataIfNeeded();
		if (RtDeviceBringup.reflexEnabled()) {
			RtReflex.INSTANCE.applySleepMode(this.device.vkDevice(), this.swapchain);
		}
		// DLSS-FG diagnostic: MAILBOX/IMMEDIATE present modes let a later present silently replace/skip an
		// earlier queued-but-not-yet-scanned-out one, which would drop FG's generated frame before the
		// display ever shows it — even though our vkQueuePresentKHR call itself reports success. FIFO is the
		// only mode that guarantees every queued present gets its own vblank. Log once per (re)configure so
		// this is checkable without guessing at the in-game V-Sync setting.
		if (dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.enabled()) {
			CausticaMod.LOGGER.info("DLSS-FG: swapchain present mode = {} (FIFO required for generated frames "
					+ "to actually display; MAILBOX/IMMEDIATE will silently drop them — enable V-Sync if not FIFO)",
					config.presentMode());
		}
	}

	/**
	 * Emit PRESENT_START/END markers around the real frame's present and, when
	 * {@code VK_KHR_present_id} is enabled) chaining a {@code VkPresentIdKHR} onto it so the marker's
	 * {@code presentID} correlates with this exact present call. The FG-generated extra presents
	 * ({@link RtFramePresenter}) are deliberately NOT marked/present-id'd — Reflex paces/measures the real
	 * frame only. No-op passthrough unless Reflex has successfully applied sleep mode for this swapchain.
	 */
	@Redirect(method = "present",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
	private int caustica$presentWithReflex(VkQueue queue, VkPresentInfoKHR presentInfo) {
		boolean reflexActive = RtReflex.enabled() && this.swapchain == RtReflex.INSTANCE.appliedSwapchain();
		if (!reflexActive) {
			return KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
		}
		VkDevice vkDevice = this.device.vkDevice();
		// Own counter (not currentSimFrameId()): Minecraft can present outside the normal tick loop (e.g.
		// Minecraft.setScreenAndShow's synchronous redraw when opening a world), so presentID must advance on
		// every actual vkQueuePresentKHR call, not just once per sleep()/runTick — otherwise a stale, already-
		// used id gets resent and VUID-VkPresentIdKHR-presentIds-04999 fires.
		long presentId = RtReflex.INSTANCE.advancePresentId();
		RtReflex.INSTANCE.marker(vkDevice, this.swapchain, RtReflex.MARKER_RENDERSUBMIT_END, presentId);
		RtReflex.INSTANCE.marker(vkDevice, this.swapchain, RtReflex.MARKER_PRESENT_START, presentId);
		int result;
		if (RtDeviceBringup.presentIdEnabled()) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkPresentIdKHR vkPresentId = VkPresentIdKHR.calloc(stack).sType$Default()
						.pNext(presentInfo.pNext())
						.swapchainCount(1)
						.pPresentIds(stack.longs(presentId));
				presentInfo.pNext(vkPresentId.address());
				result = KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
			}
		} else {
			result = KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
		}
		RtReflex.INSTANCE.marker(vkDevice, this.swapchain, RtReflex.MARKER_PRESENT_END, presentId);
		return result;
	}

	/**
	 * HDR present path. When the RT renderer has a fresh PQ image and the swapchain is PQ, composite the
	 * SDR-authored UI and blit the result directly into the swapchain instead of Minecraft's SDR main target.
	 *
	 * <p>Because this cancels {@code blitFromTexture} at HEAD, the normal {@code caustica$presentGeneratedFrames}
	 * TAIL inject below never runs on HDR frames — so DLSS-FG's extra-present step is invoked explicitly here,
	 * right after the real HDR frame is recorded, using the just-composited {@code hdrDisplayImage} (already
	 * UI-composited by {@code presentHdr}) as the interpolation source instead of the SDR main target.
	 */
	@Inject(method = "blitFromTexture", at = @At("HEAD"), cancellable = true)
	private void caustica$presentHdr(CommandEncoderBackend commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
		// The mastering peak is a live option. ACES selects the nearest packaged LUT while analytical modes
		// use the exact configured peak; neither requires swapchain recreation. Refresh metadata on change.
		caustica$applyHdrMetadataIfNeeded();
		if (this.currentImageIndex < 0) {
			return;
		}
		RtComposite rt = RtComposite.INSTANCE;
		long swapchainImage = this.swapchainImages.getLong(this.currentImageIndex);
		long acquireSem = this.acquireSemaphores[this.currentAcquireSemaphore];
		long presentSem = this.presentSemaphores[this.currentImageIndex];
		if (rt.isHdrPresentActive()) {
			VulkanCommandEncoder enc = (VulkanCommandEncoder) commandEncoder;
			rt.presentHdr(enc, swapchainImage, this.swapchainWidth, this.swapchainHeight, acquireSem, presentSem);
			caustica$presentGeneratedFramesHdr(enc, rt);
			ci.cancel();
			return;
		}
		// Non-RT frame (menu, title panorama, loading screen) on a PQ swapchain: vanilla's raw SDR blit would
		// misdisplay (SDR bytes reinterpreted as PQ codes). Convert sRGB -> PQ at paper white instead. Falls
		// through to vanilla SDR if conversion resources aren't ready or the source view is not a Vulkan view.
		if (rt.isPqSdrPresentActive()) {
			long sdrView = caustica$vkImageView(textureView);
			if (sdrView != 0L && rt.presentSdrToPq((VulkanCommandEncoder) commandEncoder, swapchainImage,
					this.swapchainWidth, this.swapchainHeight, sdrView, acquireSem, presentSem)) {
				ci.cancel();
			}
		}
	}

	@Unique
	private void caustica$applyHdrMetadataIfNeeded() {
		if (this.caustica$colorSpace != VK_COLOR_SPACE_HDR10_ST2084_EXT
				|| !RtHdr.metadataExtensionEnabled() || this.swapchain == 0L) {
			return;
		}
		int peakNits = CausticaConfig.Rt.Hdr.effectivePeakNits();
		if (this.caustica$metadataSwapchain == this.swapchain
				&& this.caustica$metadataPeakNits == peakNits) {
			return;
		}
		if (RtHdr.applyMasteringMetadata(this.device.vkDevice(), this.swapchain, peakNits)) {
			this.caustica$metadataSwapchain = this.swapchain;
			this.caustica$metadataPeakNits = peakNits;
		}
	}

	@Unique
	private static long caustica$vkImageView(GpuTextureView view) {
		return view instanceof com.mojang.blaze3d.vulkan.VulkanGpuTextureView v ? v.vkImageView() : 0L;
	}

	/**
	 * DLSS Frame Generation (slice 2): after Minecraft blits the real frame into its acquired swapchain image
	 * (but before {@code present()} shows it), present the generated frame(s) into additional swapchain images
	 * via {@link RtFramePresenter}, so the display order is generated-then-real. Runs only on the normal
	 * present path — the HDR/PQ present hooks cancel {@code blitFromTexture} at HEAD, so this TAIL is
	 * skipped there (HDR+FG deferred). Iteration 1 duplicates the final frame (no DLSSG eval yet).
	 */
	@Inject(method = "blitFromTexture", at = @At("TAIL"))
	private void caustica$presentGeneratedFrames(CommandEncoderBackend commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
		if (this.currentImageIndex < 0 || !RtFramePresenter.INSTANCE.isActive()) {
			return;
		}
		long srcImage = textureView.texture() instanceof com.mojang.blaze3d.vulkan.VulkanGpuTexture t ? t.vkImage() : 0L;
		long srcView = caustica$vkImageView(textureView);
		if (srcImage == 0L) {
			return;
		}
		int generatedCount = dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.effectiveMultiFrameCount();
		RtFramePresenter.INSTANCE.prepareExtraFrames((VulkanCommandEncoder) commandEncoder, this.device,
				this.swapchain, this.swapchainImages, this.presentSemaphores,
				this.swapchainWidth, this.swapchainHeight,
				srcView, srcImage, textureView.getWidth(0), textureView.getHeight(0), generatedCount, false);
	}

	/**
	 * DLSS-FG on the HDR present path: same extra-present mechanism as {@link #caustica$presentGeneratedFrames},
	 * but sourced from the HDR backbuffer ({@link RtComposite#hdrBackbufferView()}/{@code hdrBackbufferImage()})
	 * since HDR frames never reach that TAIL inject (HEAD cancels {@code blitFromTexture} above). No-op if FG
	 * isn't active or the HDR backbuffer isn't available (shouldn't happen right after a successful
	 * {@code presentHdr} call, but mirrors the defensive {@code srcImage == 0L} check in the SDR path).
	 */
	@Unique
	private void caustica$presentGeneratedFramesHdr(VulkanCommandEncoder enc, RtComposite rt) {
		if (this.currentImageIndex < 0 || !RtFramePresenter.INSTANCE.isActive()) {
			return;
		}
		long hdrView = rt.hdrBackbufferView();
		long hdrImage = rt.hdrBackbufferImage();
		if (hdrImage == 0L) {
			return;
		}
		int generatedCount = dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.effectiveMultiFrameCount();
		RtFramePresenter.INSTANCE.prepareExtraFrames(enc, this.device, this.swapchain, this.swapchainImages,
				this.presentSemaphores, this.swapchainWidth, this.swapchainHeight,
				hdrView, hdrImage, this.swapchainWidth, this.swapchainHeight, generatedCount, true);
	}

	// Present the FG-generated frame(s) acquired/recorded at blitFromTexture TAIL — at present() HEAD, after
	// Minecraft.java's encoder.submit() has flushed (so our present semaphores are signaled) and before MC
	// presents the real frame, giving display order generated-then-real.
	@Inject(method = "present", at = @At("HEAD"))
	private void caustica$flushGeneratedPresents(CallbackInfo ci) {
		RtFramePresenter.INSTANCE.flushPendingPresents(this.swapchain, this.presentQueue);
	}
}
