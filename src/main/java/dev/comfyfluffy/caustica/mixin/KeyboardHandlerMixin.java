package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.client.CaptureSession;
import dev.comfyfluffy.caustica.client.UltraScreenshot;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppresses keyboard mutations during capture while preserving releases and the F4 cancel action. */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void caustica$freezeCaptureKeys(long window, int action, KeyEvent event, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean ultraToggle = action == GLFW.GLFW_PRESS
                && !minecraft.options.keyDebugModifier.isDown()
                && UltraScreenshot.KEY.matches(event);
        if (shouldSuppressCaptureKey(CaptureSession.active(), action, ultraToggle)) {
            ci.cancel();
        }
    }

    static boolean shouldSuppressCaptureKey(boolean captureActive, int action, boolean ultraToggle) {
        return captureActive
                && action != GLFW.GLFW_RELEASE
                && !(action == GLFW.GLFW_PRESS && ultraToggle);
    }
}
