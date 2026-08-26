package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.RtUiOverlay;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;
import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import dev.comfyfluffy.caustica.rt.terrain.RtWorkerPool;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import net.minecraft.network.chat.Component;

public final class CausticaClient implements ClientModInitializer {
	private static boolean rtInitDone = false;
	private static boolean rtRestartRequired = false;

	@Override
	public void onInitializeClient() {
		CausticaMod.LOGGER.info("Caustica client initialized");

		// Class-init runs DebugScreenEntries.register(...) via its ID field; touching the class here
		// makes the entry discoverable in F3's entry list. Off by default -- the player opts in the
		// same way as any other optional vanilla entry (e.g. GPU utilization).
		@SuppressWarnings("unused")
		Object registerExposureDebugEntry = RtExposureDebugEntry.ID;

		// The GpuDevice exists well before the first tick, so a one-shot at tick start
		// runs on the render thread with the device idle between frames.
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			if (rtRestartRequired) {
				return;
			}
			if (!VanillaRenderController.rtRuntimeWorkRequested()) {
				if (rtInitDone) {
					shutdownRt(false);
				}
				return;
			}

			// Bring up the RT device/context once; terrain residency + the composite follow below.
			if (!rtInitDone && RtDeviceBringup.rtRequested()) {
				RtContext ctx = RtContext.get();
				if (ctx != null) {
					rtInitDone = true;
				}
			}

			// Once RT is up, keep section residency synced to vanilla's loaded chunks around
			// the player — builds newly-in-range sections, frees out-of-range ones, per tick.
			if (rtInitDone) {
				RtContext ctx = RtContext.currentOrNull();
				if (ctx != null) {
					RtFrameStats.FRAME.beginIfInactive();
					// Bring the world pipeline + LabPBR atlases up before terrain tessellates, so per-prim
					// material flags resolve from the first section (PBR on join, no re-extract). No-op
					// until we're in a world with the block atlas loaded, or once already created.
					RtComposite.INSTANCE.ensureResourcesReady(ctx);
					if (!CaptureSession.active()) {
						RtTerrain.update(ctx);
					}
					// Log DLSS-FG availability once when frame generation is enabled (capability query only;
					// the present-loop integration that consumes it is built separately).
					if (dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.enabled()) {
						dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.probeAvailabilityOnce();
					}
				}
			}
		});

		// Vanilla's full render-state invalidation (LevelExtractor.allChanged(): dimension change via
		// setLevel, render-distance change, F3+A) — drop RT terrain residency so it rebuilds for the new
		// world. Fixes stale geometry persisting across an End→Overworld switch (coords alone aren't
		// world-unique). Resource reloads do NOT fire this; that path is handled separately.
		InvalidateRenderStateCallback.EVENT.register(() -> {
			UltraScreenshot.INSTANCE.abort(
					Component.translatable("caustica.status.ultraScreenshot.invalidated"));
			RtTerrain.requestFullClear();
			RtComposite.INSTANCE.resetExposureHistory();
			RtComposite.INSTANCE.resetFailureLatch(); // F3+A doubles as manual RT recovery after a latched failure
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			shutdownRt(true);
		});
	}

	private static void shutdownRt(boolean finalClientStop) {
		if (finalClientStop) {
			UltraScreenshot.INSTANCE.shutdown();
		} else {
			// A temporary RT teardown must not release a pending vanilla PNG write. Its callback
			// remains the owner of the shared lease, preventing a second capture after RT restarts.
			UltraScreenshot.INSTANCE.stopRenderer();
		}
		WorldRenderScaler.INSTANCE.destroy();
		RtUiOverlay.destroy(); // GUI redirect is not gated by rtInitDone; always release its TextureTarget
		if (!rtInitDone) {
			RtWorkerPool.INSTANCE.shutdown();
			return;
		}

		RtContext ctx = RtContext.currentOrNull();
		if (ctx != null) {
			// Let the terrain epoch cancel queued work and release every active-task token before
			// shutdownNow(): discarded worker Runnables cannot deliver their terminal callbacks.
			RtTerrain.shutdown(ctx);
		}
		RtWorkerPool.INSTANCE.shutdown();
		if (ctx != null) {
			RtEntities.INSTANCE.shutdown();
		}
		boolean rrReleased = RtComposite.INSTANCE.destroy();
		RtEntityTextures.INSTANCE.reset();
		RtBlockMaterials.INSTANCE.destroy();
		dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.destroy();
		if (ctx != null) {
			dev.comfyfluffy.caustica.rt.RtFramePresenter.INSTANCE.destroy(ctx.device());
			dev.comfyfluffy.caustica.rt.RtReflex.INSTANCE.destroy(ctx.device().vkDevice());
		}
		// NGX and the Vulkan device may be destroyed only after every native feature released its owner.
		boolean ngxReleased = rrReleased
				&& dev.comfyfluffy.caustica.ngx.NgxRuntime.INSTANCE.shutdown();
		boolean restartRequired = teardownRequiresRestart(rrReleased, ngxReleased);
		if (ctx != null && !restartRequired) {
			ctx.destroy();
		}
		rtInitDone = false;
		if (restartRequired) {
			rtRestartRequired = true;
			CausticaMod.LOGGER.error("RT teardown retained native NGX ownership; restart is required");
		}
	}

	static boolean teardownRequiresRestart(boolean rrReleased, boolean ngxReleased) {
		return !rrReleased || !ngxReleased;
	}

	/** Native teardown ownership failures keep RT off until process restart. */
	public static boolean rtRestartRequired() {
		return rtRestartRequired;
	}
}
