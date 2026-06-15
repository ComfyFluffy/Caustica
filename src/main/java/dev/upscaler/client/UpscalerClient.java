package dev.upscaler.client;

import dev.upscaler.UpscalerMod;
import dev.upscaler.rt.RtContext;
import dev.upscaler.rt.RtDeviceBringup;
import dev.upscaler.rt.RtSelfTest;
import dev.upscaler.rt.RtComposite;
import dev.upscaler.rt.RtTerrain;
import dev.upscaler.rt.RtTriangleScene;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class UpscalerClient implements ClientModInitializer {
	private static boolean smokeTestDone = false;
	private static boolean rtInitDone = false;

	@Override
	public void onInitializeClient() {
		UpscalerMod.LOGGER.info("Sodium Upscaler client initialized");

		// The GpuDevice exists well before the first tick, so a one-shot at tick start
		// runs on the render thread with the device idle between frames.
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			if (!smokeTestDone) {
				FsrSmokeTest.run();
				DlssSmokeTest.run();
				smokeTestDone = true;
			}
			// Build the RT triangle scene once (also what RtComposite traces); self-test the path.
			if (!rtInitDone && RtDeviceBringup.rtRequested()) {
				RtContext ctx = RtContext.get();
				if (ctx != null) {
					RtTriangleScene scene = RtTriangleScene.get(ctx);
					if (RtSelfTest.ENABLED) {
						RtSelfTest.run(ctx, scene.tlas());
					}
					rtInitDone = true;
				}
			}

			// P2: once RT is up, keep section residency synced to vanilla's loaded chunks around
			// the player — builds newly-in-range sections, frees out-of-range ones, per tick.
			if (rtInitDone && RtTerrain.ENABLED) {
				RtContext ctx = RtContext.currentOrNull();
				if (ctx != null) {
					RtTerrain.update(ctx);
				}
			}
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			DlssPipeline.INSTANCE.destroy();
			FsrPipeline.INSTANCE.destroy();
			WorldRenderScaler.INSTANCE.destroy();

			RtContext ctx = RtContext.currentOrNull();
			if (ctx != null) {
				ctx.waitIdle();
				RtTerrain.shutdown(ctx);
			}
			RtComposite.INSTANCE.destroy();
			RtTriangleScene scene = RtTriangleScene.currentOrNull();
			if (scene != null) {
				scene.destroy();
			}
			if (ctx != null) {
				ctx.destroy();
			}
		});
	}
}
