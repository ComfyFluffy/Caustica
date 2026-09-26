package dev.comfyfluffy.caustica.minecraft.client.mixin;

import dev.comfyfluffy.caustica.renderer.runtime.RendererOptions;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaConfig;
import dev.comfyfluffy.caustica.minecraft.client.RtScreenshotExporter;
import dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.util.function.Consumer;

/** Hooks only vanilla's auto-named F2 capture; named panorama/debug captures remain PNG-only. */
@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {
    @Inject(
            method = "grab(Ljava/io/File;Ljava/lang/String;Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void caustica$exportResidualExposureExr(
            File workDir,
            @Nullable String forceName,
            RenderTarget target,
            int downscaleFactor,
            Consumer<Component> callback,
            CallbackInfo ci
    ) {
        boolean captureOwned = dev.comfyfluffy.caustica.minecraft.client.UltraCaptureSession.screenshotThreadToken() != 0L;
        if (dev.comfyfluffy.caustica.minecraft.client.UltraCaptureSession.active() && !captureOwned) {
            // One PNG write lease system-wide: manual grabs wait until the capture's write completes.
            callback.accept(Component.translatable("caustica.status.ultraScreenshot.busy"));
            ci.cancel();
            return;
        }
        if (!captureOwned && forceName == null && downscaleFactor == 1
                && CausticaClientComposition.current().runtime().frameActive()
                && CausticaConfig.get(RendererOptions.Rt.Screenshots.EXR_ENABLED)) {
            String pairedPngName = RtScreenshotExporter.exportPaired(workDir, callback);
            if (pairedPngName != null) {
                // Re-enter vanilla's named path with the paired PNG name. The non-null name bypasses
                // this hook on the nested call and makes both outputs use exactly one basename.
                Screenshot.grab(workDir, pairedPngName, target, downscaleFactor, callback);
                ci.cancel();
            }
        }
    }
}
