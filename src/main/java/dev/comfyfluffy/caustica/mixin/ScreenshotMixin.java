package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.CaptureSession;
import dev.comfyfluffy.caustica.client.RtScreenshotExporter;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

import java.io.File;
import java.util.function.Consumer;

/** Preserves the F2 EXR pair while preventing vanilla readbacks during the renderer-owned F4 snapshot. */
@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {
    @WrapMethod(
            method = "grab(Ljava/io/File;Ljava/lang/String;Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V")
    private static void caustica$guardNamedPng(
            File workDir,
            @Nullable String forceName,
            RenderTarget target,
            int downscaleFactor,
            Consumer<Component> callback,
            Operation<Void> original
    ) {
        if (CaptureSession.active()) {
            callback.accept(Component.translatable("caustica.status.screenshot.busy"));
            return;
        }
        long token = CaptureSession.screenshotThreadToken();
        boolean inherited = CaptureSession.screenshotIsUltra(token);
        if (!inherited) {
            token = CaptureSession.acquireScreenshot(false);
            if (token == 0L) {
                callback.accept(Component.translatable("caustica.status.screenshot.busy"));
                return;
            }
        }
        long callbackToken = token;
        Consumer<Component> leasedCallback = result -> {
            try {
                callback.accept(result);
            } finally {
                CaptureSession.releaseScreenshot(callbackToken);
            }
        };
        try {
            String screenshotName = forceName;
            if (screenshotName == null && downscaleFactor == 1
                    && CausticaConfig.Rt.Screenshots.EXR_ENABLED.value()) {
                screenshotName = RtScreenshotExporter.exportPaired(workDir, callback);
            }
            original.call(workDir, screenshotName, target, downscaleFactor, leasedCallback);
        } catch (Throwable t) {
            CaptureSession.releaseScreenshot(callbackToken);
            throw t;
        }
    }

    @WrapMethod(
            method = "takeScreenshot(Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V")
    private static void caustica$guardAutomaticPng(
            RenderTarget target,
            Consumer<NativeImage> callback,
            Operation<Void> original
    ) {
        if (CaptureSession.active()) {
            return;
        }
        long token = CaptureSession.screenshotThreadToken();
        boolean inherited = CaptureSession.screenshotIsUltra(token);
        if (!inherited) {
            token = CaptureSession.acquireScreenshot(false);
            if (token == 0L) {
                return;
            }
        }
        long callbackToken = token;
        Consumer<NativeImage> leasedCallback = image -> {
            try {
                callback.accept(image);
            } finally {
                CaptureSession.releaseScreenshot(callbackToken);
            }
        };
        try {
            original.call(target, leasedCallback);
        } catch (Throwable t) {
            CaptureSession.releaseScreenshot(callbackToken);
            throw t;
        }
    }
}
