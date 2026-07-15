package dev.comfyfluffy.caustica.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;

public final class VanillaRenderController {
	public static final VanillaRenderController INSTANCE = new VanillaRenderController();

	private boolean frameStarted;
	private boolean baseReady;
	private boolean projectionCaptured;
	private boolean worldSkipped;
	private boolean failureLatched;
	private boolean loggedActive;
	private boolean loggedWaitingForRtPlayerSection;
	private boolean loggedRtPlayerSectionReady;
	private boolean rtActive = true;
	private Boolean lastLoggedRtActive;
	private String inactiveReason;
	private String lastLoggedInactiveReason;

	private VanillaRenderController() {
	}

	public void beginFrame(RenderTarget mainTarget) {
		this.frameStarted = true;
		this.projectionCaptured = false;
		this.worldSkipped = false;
		this.baseReady = false;
		this.inactiveReason = null;
		this.rtActive = RtComposite.enabled();

		if (!Boolean.valueOf(this.rtActive).equals(this.lastLoggedRtActive)) {
			this.lastLoggedRtActive = this.rtActive;
			CausticaMod.LOGGER.info("RT output mode: {}", this.rtActive ? "rt" : "vanilla");
		}

		if (!this.rtActive) {
			return;
		}

		this.inactiveReason = findInactiveReason(mainTarget);
		this.baseReady = this.inactiveReason == null;
		if (this.baseReady) {
			if (!this.loggedActive) {
				this.loggedActive = true;
				CausticaMod.LOGGER.info("Vanilla world rendering cancellation active; using existing RT composite seam");
			}
		} else {
			logInactive(this.inactiveReason);
		}
	}

	public void markProjectionCaptured() {
		this.projectionCaptured = true;
	}

	public boolean shouldCancelLevelRenderer() {
		return this.shouldCancelLevelRenderer(false);
	}

	public boolean shouldCancelLevelRenderer(boolean waitingForRtPlayerSection) {
		if (!this.rtActive) {
			return false;
		}
		if (!this.frameStarted) {
			logInactive("frame controller was not started");
			return false;
		}
		if (!this.baseReady) {
			return false;
		}
		if (!this.projectionCaptured) {
			logInactive("level projection was not captured");
			return false;
		}
		if (waitingForRtPlayerSection && !this.loggedWaitingForRtPlayerSection) {
			this.loggedWaitingForRtPlayerSection = true;
			CausticaMod.LOGGER.info("Keeping vanilla LevelRenderer canceled while waiting for RT player section residency");
		}
		return true;
	}

	public void markRtPlayerSectionReady() {
		if (!this.loggedRtPlayerSectionReady) {
			this.loggedRtPlayerSectionReady = true;
			CausticaMod.LOGGER.info("Satisfied vanilla terrain-load callback from RT player section residency");
		}
	}

	public void markWorldSkipped() {
		this.worldSkipped = true;
	}

	public boolean wasWorldSkippedThisFrame() {
		return this.worldSkipped;
	}

	public boolean shouldCompositeRt() {
		return this.rtActive;
	}

	/** Runtime work switch for per-frame RT work; mirrors {@link RtComposite#enabled()}. */
	public static boolean rtRuntimeWorkRequested() {
		return RtComposite.enabled();
	}

	public void markRtCompositeResult(boolean success) {
		if (this.worldSkipped && !success) {
			latchFailure("RT composite did not produce a replacement frame");
		}
	}

	public void markMissedBeforeHandSeam() {
		if (this.worldSkipped) {
			latchFailure("missed before-hand RT composite seam after vanilla world was skipped");
		}
	}

	private String findInactiveReason(RenderTarget mainTarget) {
		if (this.failureLatched || RtComposite.INSTANCE.hasFailed()) {
			return "RT composite failure latch is set";
		}
		if (!RtComposite.enabled()) {
			return "caustica.rt is false";
		}
		if (RtContext.currentOrNull() == null) {
			return "RT context is not ready";
		}
		if (RtTerrain.currentOrNull() == null) {
			return "RT terrain is not ready";
		}
		if (RtComposite.INSTANCE.requiresVanillaWorldFallback()) {
			return "RT resources are crossing an epoch boundary";
		}
		if (mainTarget == null || mainTarget.getColorTexture() == null || mainTarget.getDepthTexture() == null) {
			return "main render target textures are not ready";
		}
		return null;
	}

	private void latchFailure(String reason) {
		if (!this.failureLatched) {
			CausticaMod.LOGGER.warn("Disabling vanilla world cancellation: {}", reason);
		}
		this.failureLatched = true;
		this.baseReady = false;
		this.inactiveReason = reason;
	}

	private void logInactive(String reason) {
		if (reason == null || reason.equals(this.lastLoggedInactiveReason)) {
			return;
		}
		CausticaMod.LOGGER.info("Vanilla world cancellation inactive: {}", reason);
		this.lastLoggedInactiveReason = reason;
	}
}
