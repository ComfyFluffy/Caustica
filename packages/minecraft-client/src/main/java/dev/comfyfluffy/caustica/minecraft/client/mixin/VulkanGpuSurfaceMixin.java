package dev.comfyfluffy.caustica.minecraft.client.mixin;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftHostTelemetry;
import dev.comfyfluffy.caustica.renderer.runtime.RtRenderSettings;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaConfig;
import dev.comfyfluffy.caustica.minecraft.client.CausticaMod;
import dev.comfyfluffy.caustica.minecraft.client.vulkan.MinecraftHdr;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftRtRuntime;
import dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftVulkanImage;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.minecraft.client.vulkan.MinecraftVulkanBackend;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.renderer.presentation.AcquiredSwapchainTarget;
import dev.comfyfluffy.caustica.renderer.presentation.BorrowedImage;
import dev.comfyfluffy.caustica.renderer.presentation.PresentationSwapchain;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanLowLatency;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRSurface;
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

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.EXTSwapchainColorspace.VK_COLOR_SPACE_HDR10_ST2084_EXT;

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
	private MinecraftVulkanImage caustica$sdrPresentationSource;

	@Inject(method = "destroySwapchain", at = @At("HEAD"))
	private void caustica$releaseSwapchainState(CallbackInfo ci) {
		caustica$presentationSwapchain = null;
		caustica$metadataSwapchain = 0L;
		caustica$metadataPeakNits = -1;
		var source = caustica$sdrPresentationSource;
		caustica$sdrPresentationSource = null;
		if (source != null) source.close();
	}
	@Unique
	private PresentationSwapchain caustica$presentationSwapchain;

	@Unique
	private long caustica$metadataSwapchain;

	@Unique
	private int caustica$metadataPeakNits = -1;

	@Inject(method = "<init>(Lcom/mojang/blaze3d/vulkan/VulkanDevice;J)V", at = @At("TAIL"))
	private void caustica$logHdrCapabilities(VulkanDevice device, long windowHandle, CallbackInfo ci) {
		try {
			MinecraftHdr.logSurfaceCapabilities(this.device.vkDevice().getPhysicalDevice(), this.surface, this.swapchainImageFormat);
		} catch (Throwable t) {
			// Diagnostics only — never let HDR logging break surface creation.
		}
	}

	/** Discover PQ capability during construction and select PQ only when HDR starts enabled. */
	@Inject(method = "pickSwapchainSurfaceFormat", at = @At("HEAD"), cancellable = true)
	private void caustica$pickPqFormat(VkSurfaceFormatKHR.Buffer formats, CallbackInfoReturnable<VkSurfaceFormatKHR> cir) {
		VkSurfaceFormatKHR pq = caustica$findPq(formats);
		CausticaClientComposition.current().runtime().setSwapchainPqAvailable(pq != null);
		this.caustica$colorSpace = 0;
		CausticaClientComposition.current().runtime().setSwapchainPqActive(false);
		if (CausticaClientComposition.current().runtime().wantsPqSwapchain() && pq != null) {
			this.caustica$colorSpace = VK_COLOR_SPACE_HDR10_ST2084_EXT;
			CausticaClientComposition.current().runtime().setSwapchainPqActive(true);
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
		try {
			List<MinecraftHdr.SurfaceFormat> formats = MinecraftHdr.surfaceFormats(
					this.device.vkDevice().getPhysicalDevice(), this.surface);
			MinecraftHdr.SurfaceFormat pq = caustica$findPqValue(formats);
			MinecraftHdr.SurfaceFormat sdr = caustica$findSdrValue(formats);
			CausticaClientComposition.current().runtime().setSwapchainPqAvailable(pq != null);
			boolean usePq = CausticaClientComposition.current().runtime().wantsPqSwapchain() && pq != null;
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
			CausticaClientComposition.current().runtime().setSwapchainPqActive(usePq);
			CausticaMod.LOGGER.info("HDR: recreating swapchain as {} (format={}, colorSpace={})",
					usePq ? "PQ" : "native SDR", this.swapchainImageFormat,
					usePq ? "HDR10_ST2084" : "SRGB_NONLINEAR");
		} catch (RuntimeException failure) {
			CausticaMod.LOGGER.warn("HDR: failed to enumerate swapchain formats during recreation", failure);
		}
	}

	@Unique
	private static MinecraftHdr.SurfaceFormat caustica$findPqValue(List<MinecraftHdr.SurfaceFormat> formats) {
		return formats.stream().filter(format -> format.colorSpace() == VK_COLOR_SPACE_HDR10_ST2084_EXT)
				.findFirst().orElse(null);
	}

	@Unique
	private static MinecraftHdr.SurfaceFormat caustica$findSdrValue(List<MinecraftHdr.SurfaceFormat> formats) {
		return formats.stream().filter(format -> format.colorSpace() == KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
				&& (format.format() == VK10.VK_FORMAT_R8G8B8A8_UNORM
				|| format.format() == VK10.VK_FORMAT_B8G8R8A8_UNORM)).findFirst().orElse(null);
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

	/** Replace the hardcoded {@code imageColorSpace(0)} with the PQ color space when one was selected. */
	@ModifyArg(method = "configure",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageColorSpace(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"),
			index = 0)
	private int caustica$overrideColorSpace(int original) {
		return this.caustica$colorSpace != 0 ? this.caustica$colorSpace : original;
	}

	/**
	 * Enables latency configuration on supported swapchains. Vulkan consumes the added pNext structure
	 * synchronously; restore the caller's chain before releasing its temporary storage.
	 */
	@Redirect(method = "configure",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private int caustica$createSwapchainWithReflex(VkDevice device, VkSwapchainCreateInfoKHR pCreateInfo,
			VkAllocationCallbacks pAllocator, LongBuffer pSwapchain) {
		MinecraftVulkanBackend backend = CausticaClientComposition.current().vulkanBackend().currentOrNull();
		if (backend == null || !backend.capabilities().lowLatency()) {
			return KHRSwapchain.vkCreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSwapchainLatencyCreateInfoNV latency = VkSwapchainLatencyCreateInfoNV.calloc(stack).sType$Default();
			latency.pNext(pCreateInfo.pNext());
			latency.latencyModeEnable(true);
			pCreateInfo.pNext(latency.address());
			try {
				return KHRSwapchain.vkCreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);
			} finally {
				pCreateInfo.pNext(latency.pNext());
			}
		}
	}

	/**
	 * Apply HDR metadata and Reflex sleep mode to the new swapchain. Both configurations are scoped to
	 * its native handle and must be applied again after recreation.
	 */
	@Inject(method = "configure", at = @At("TAIL"))
	private void caustica$applySwapchainExtensionState(GpuSurface.Configuration config, CallbackInfo ci) {
		caustica$applyHdrMetadataIfNeeded();
		MinecraftVulkanBackend backend = CausticaClientComposition.current().vulkanBackend().currentOrNull();
		if (backend != null && backend.capabilities().lowLatency()) {
			backend.lowLatency().applySleepMode(this.device.vkDevice(), this.swapchain);
		}
		// DLSS-FG diagnostic: MAILBOX/IMMEDIATE present modes let a later present silently replace/skip an
		// earlier queued-but-not-yet-scanned-out one, which would drop FG's generated frame before the
		// display ever shows it — even though our vkQueuePresentKHR call itself reports success. FIFO is the
		// only mode that guarantees every queued present gets its own vblank. Log once per (re)configure so
		// this is checkable without guessing at the in-game V-Sync setting.
		if (CausticaClientComposition.current().runtime().frameGenerationActive(Minecraft.getInstance().level != null)) {
			CausticaMod.LOGGER.info("DLSS-FG: swapchain present mode = {} (FIFO required for generated frames "
					+ "to actually display; MAILBOX/IMMEDIATE will silently drop them — enable V-Sync if not FIFO)",
					config.presentMode());
		}
	}

	/**
	 * Present under the renderer device's queue lock and mark the real frame's presentation for Reflex,
	 * attaching the same ID through {@code VK_KHR_present_id} when supported. Generated-frame presents
	 * carry no Reflex markers; Reflex paces the real frame. Requires sleep mode applied to this swapchain.
	 */
	@Redirect(method = "present",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
	private int caustica$presentWithReflex(VkQueue queue, VkPresentInfoKHR presentInfo) {
		MinecraftVulkanBackend backend;
		VulkanLowLatency lowLatency;
		boolean reflexActive;
		try (var hostWork = MinecraftHostTelemetry.work("reflex.presentGate")) {
			backend = CausticaClientComposition.current().vulkanBackend().currentOrNull();
			lowLatency = backend == null ? null : backend.lowLatency();
			reflexActive = lowLatency != null && lowLatency.active()
					&& this.swapchain == lowLatency.appliedSwapchain();
		}
		if (!reflexActive) {
			return backend == null ? KHRSwapchain.vkQueuePresentKHR(queue, presentInfo)
					: backend.synchronizedQueueOperation(() -> KHRSwapchain.vkQueuePresentKHR(queue, presentInfo));
		}
		VkDevice vkDevice = this.device.vkDevice();
		// Own counter (not currentSimFrameId()): Minecraft can present outside the normal tick loop (e.g.
		// Minecraft.setScreenAndShow's synchronous redraw when opening a world), so presentID must advance on
		// every actual vkQueuePresentKHR call, not just once per sleep()/runTick — otherwise a stale, already-
		// used id gets resent and VUID-VkPresentIdKHR-presentIds-04999 fires.
		long presentId;
		try (var hostWork = MinecraftHostTelemetry.work("reflex.presentStart")) {
			presentId = lowLatency.advancePresentId();
			lowLatency.marker(vkDevice, this.swapchain, VulkanLowLatency.RENDER_SUBMIT_END, presentId);
			lowLatency.marker(vkDevice, this.swapchain, VulkanLowLatency.PRESENT_START, presentId);
		}
		int result;
		if (backend.capabilities().presentIds()) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkPresentIdKHR vkPresentId = VkPresentIdKHR.calloc(stack).sType$Default()
						.pNext(presentInfo.pNext())
						.swapchainCount(1)
						.pPresentIds(stack.longs(presentId));
				presentInfo.pNext(vkPresentId.address());
				try {
					result = backend.synchronizedQueueOperation(() -> KHRSwapchain.vkQueuePresentKHR(queue, presentInfo));
				} finally {
					presentInfo.pNext(vkPresentId.pNext());
				}
			}
		} else {
			result = backend.synchronizedQueueOperation(() -> KHRSwapchain.vkQueuePresentKHR(queue, presentInfo));
		}
		try (var hostWork = MinecraftHostTelemetry.work("reflex.presentEnd")) {
			lowLatency.marker(vkDevice, this.swapchain, VulkanLowLatency.PRESENT_END, presentId);
		}
		return result;
	}

	/**
	 * HDR present path. When the RT renderer has a fresh PQ image and the swapchain is PQ, composite the
	 * SDR-authored UI and blit the result directly into the swapchain instead of Minecraft's SDR main target.
	 *
	 * <p>Because this cancels {@code blitFromTexture} at HEAD, the normal {@code caustica$prepareGeneratedFrame}
	 * TAIL inject below never runs on HDR frames, so DLSS-FG recording is invoked explicitly here,
	 * right after the real HDR frame is recorded, using the just-composited {@code hdrDisplayImage} (already
	 * UI-composited by {@code presentHdr}) as the interpolation source instead of the SDR main target.
	 */
	@Inject(method = "blitFromTexture", at = @At("HEAD"), cancellable = true)
	private void caustica$presentHdr(CommandEncoderBackend commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
		try (var hostWork = MinecraftHostTelemetry.work("presentation.hdr")) {
			try (var ignored = CausticaClientComposition.current().runtime().profileStage("presentation.hdr")) {
				// The mastering peak is a live option and selects a different baked ACES output LUT without forcing
				// swapchain recreation. Refresh the metadata once when that selected LUT changes.
				caustica$applyHdrMetadataIfNeeded();
				if (this.currentImageIndex < 0) {
					return;
				}
				MinecraftRtRuntime presentation = CausticaClientComposition.current().runtime();
				if (CausticaClientComposition.current().runtime().isHdrPresentActive()) {
					VulkanCommandEncoder enc = (VulkanCommandEncoder) commandEncoder;
					GraphicsSubmission submission = MinecraftVulkanBackend.wrap(enc);
					UiPresentationResources ui = CausticaClientComposition.current().uiOverlay().capturePresentation();
					presentation.presentHdr(submission, caustica$acquiredTarget(), ui);
					if (ui.populated() && ui.colorView() != 0L) {
						CausticaClientComposition.current().uiOverlay().markConsumed();
					}
					caustica$prepareGeneratedFrameHdr(submission, ui);
					ci.cancel();
					return;
				}
				// Non-RT frame (menu, title panorama, loading screen) on a PQ swapchain: vanilla's raw SDR blit would
				// misdisplay (SDR bytes reinterpreted as PQ codes). Convert sRGB -> PQ at paper white using the
				// active Vulkan context and RGBA8 source; conversion resources are created on demand.
				if (CausticaClientComposition.current().runtime().isPqSdrPresentActive()) {
					VulkanDeviceContext gpu = CausticaClientComposition.current().runtime().vulkanContextOrNull();
					if (gpu != null && textureView instanceof com.mojang.blaze3d.vulkan.VulkanGpuTextureView view
							&& view.texture().getFormat() == com.mojang.blaze3d.GpuFormat.RGBA8_UNORM) {
						if (caustica$sdrPresentationSource == null || !caustica$sdrPresentationSource.wraps(
								gpu, view, this.swapchainWidth, this.swapchainHeight)) {
							MinecraftVulkanImage old = caustica$sdrPresentationSource;
							caustica$sdrPresentationSource = MinecraftVulkanImage.sampled(gpu, view,
									this.swapchainWidth, this.swapchainHeight, VK10.VK_FORMAT_R8G8B8A8_UNORM);
							if (old != null) old.close();
						}
						if (presentation.presentSdrToPq(
							MinecraftVulkanBackend.wrap((VulkanCommandEncoder) commandEncoder),
							caustica$acquiredTarget(),
							caustica$sdrPresentationSource)) {
						ci.cancel();
						}
					}
				}
			}
		}
	}

	@Unique
	private void caustica$applyHdrMetadataIfNeeded() {
		MinecraftVulkanBackend backend = CausticaClientComposition.current().vulkanBackend().currentOrNull();
		if (this.caustica$colorSpace != VK_COLOR_SPACE_HDR10_ST2084_EXT || backend == null
				|| !backend.capabilities().hdrMetadata() || this.swapchain == 0L) {
			return;
		}
		int peakNits = RtRenderSettings.effectivePeakNits(CausticaConfig.snapshot());
		if (this.caustica$metadataSwapchain == this.swapchain
				&& this.caustica$metadataPeakNits == peakNits) {
			return;
		}
		MinecraftHdr.applyMasteringMetadata(this.device.vkDevice(), this.swapchain, peakNits);
		this.caustica$metadataSwapchain = this.swapchain;
		this.caustica$metadataPeakNits = peakNits;
	}

	@Unique
	private static long caustica$vkImageView(GpuTextureView view) {
		return view instanceof com.mojang.blaze3d.vulkan.VulkanGpuTextureView v ? v.vkImageView() : 0L;
	}

	/**
	 * Record interpolation into an additional swapchain image after Minecraft records the real frame.
	 * Presentation waits until the host submits both frames; {@code caustica$flushGeneratedPresent} then
	 * presents the generated frame first. HDR/PQ cancels this method at HEAD and records from its PQ
	 * backbuffer through the explicit HDR hook.
	 */
	@Inject(method = "blitFromTexture", at = @At("TAIL"))
	private void caustica$prepareGeneratedFrame(CommandEncoderBackend commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
		try (var hostWork = MinecraftHostTelemetry.work("presentation.frameGeneration")) {
			try (var ignored = CausticaClientComposition.current().runtime().profileStage("presentation.frameGeneration")) {
				if (this.currentImageIndex < 0
						|| !CausticaClientComposition.current().runtime().frameGenerationActive(Minecraft.getInstance().level != null)) {
					return;
				}
				long srcImage = textureView.texture() instanceof com.mojang.blaze3d.vulkan.VulkanGpuTexture t ? t.vkImage() : 0L;
				long srcView = caustica$vkImageView(textureView);
				if (srcImage == 0L) {
					return;
				}
				MinecraftRtRuntime presentation = CausticaClientComposition.current().runtime();
				presentation.prepareGeneratedFrame(
						MinecraftVulkanBackend.wrap((VulkanCommandEncoder) commandEncoder), caustica$swapchain(),
						new BorrowedImage(srcImage, srcView, VK10.VK_FORMAT_R8G8B8A8_UNORM,
								this.swapchainWidth, this.swapchainHeight), false,
						CausticaClientComposition.current().uiOverlay().capturePresentation());
			}
		}
	}

	/**
	 * Record interpolation from the composited PQ backbuffer. HDR frames bypass the SDR TAIL hook;
	 * both paths queue their generated image for presentation after host submission.
	 */
	@Unique
	private void caustica$prepareGeneratedFrameHdr(GraphicsSubmission submission,
			UiPresentationResources ui) {
		try (var ignored = CausticaClientComposition.current().runtime().profileStage("presentation.frameGeneration")) {
			if (this.currentImageIndex < 0
					|| !CausticaClientComposition.current().runtime().frameGenerationActive(Minecraft.getInstance().level != null)) {
				return;
			}
			MinecraftRtRuntime presentation = CausticaClientComposition.current().runtime();
			dev.comfyfluffy.caustica.api.vulkan.GpuImage hdr = presentation.hdrBackbuffer();
			if (hdr == null) {
				return;
			}
			presentation.prepareGeneratedFrame(submission, caustica$swapchain(), hdr, true, ui);
		}
	}

	@Unique
	private PresentationSwapchain caustica$swapchain() {
		PresentationSwapchain cached = this.caustica$presentationSwapchain;
		// configure destroys the previous swapchain first; its HEAD hook invalidates this description.
		if (cached != null) {
			return cached;
		}
		ArrayList<PresentationSwapchain.Image> images = new ArrayList<>(this.swapchainImages.size());
		for (int i = 0; i < this.swapchainImages.size(); i++) {
			images.add(new PresentationSwapchain.Image(
					this.swapchainImages.getLong(i), this.presentSemaphores[i]));
		}
		cached = new PresentationSwapchain(this.device.vkDevice(), this.swapchain,
				this.swapchainImageFormat, this.swapchainWidth, this.swapchainHeight, images);
		this.caustica$presentationSwapchain = cached;
		return cached;
	}

	@Unique
	private AcquiredSwapchainTarget caustica$acquiredTarget() {
		return new AcquiredSwapchainTarget(this.swapchainImages.getLong(this.currentImageIndex),
				this.swapchainWidth, this.swapchainHeight,
				this.acquireSemaphores[this.currentAcquireSemaphore],
				this.presentSemaphores[this.currentImageIndex]);
	}

	// Present the FG-generated frame acquired/recorded at blitFromTexture TAIL — at present() HEAD, after
	// Minecraft.java's encoder.submit() has flushed (so our present semaphores are signaled) and before MC
	// presents the real frame, giving display order generated-then-real.
	@Inject(method = "present", at = @At("HEAD"))
	private void caustica$flushGeneratedPresent(CallbackInfo ci) {
		try (var hostWork = MinecraftHostTelemetry.work("presentation.generatedPresent")) {
			try (var ignored = CausticaClientComposition.current().runtime().profileStage("presentation.generatedPresent")) {
				CausticaClientComposition.current().runtime().flushGeneratedPresent(caustica$swapchain(), this.presentQueue);
			}
		}
	}
}
