package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.client.CaptureSession;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the capture camera immutable without blocking GLFW event processing. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void caustica$freezeCaptureScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (CaptureSession.active()) {
            ci.cancel();
        }
    }

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void caustica$freezeCaptureCamera(double frameTime, CallbackInfo ci) {
        if (CaptureSession.active()) {
            ci.cancel();
        }
    }
}
