package dev.comfyfluffy.caustica.minecraft.client.mixin;

import dev.comfyfluffy.caustica.minecraft.client.UltraCaptureSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes unshared single-player captures through Minecraft's normal pause decision. */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "isPausing", at = @At("HEAD"), cancellable = true)
    private void caustica$pauseLocalCapture(CallbackInfoReturnable<Boolean> cir) {
        if (UltraCaptureSession.shouldPause(Minecraft.getInstance())) {
            cir.setReturnValue(true);
        }
    }
}
