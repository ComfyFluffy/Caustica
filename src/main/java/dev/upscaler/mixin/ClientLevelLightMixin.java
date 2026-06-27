package dev.upscaler.mixin;

import dev.upscaler.client.VanillaRenderController;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelLightEngine.class)
public class ClientLevelLightMixin {
    private static final boolean CANCEL_LIGHT_UPDATES = Boolean.parseBoolean(System.getProperty("upscaler.rt.cancelLightUpdates", "true"));

    @Inject(method = "runLightUpdates()I", at = @At("HEAD"), cancellable = true, require = 0)
    private void upscaler$skipClientLightCompute(CallbackInfoReturnable<Integer> cir) {
        if (CANCEL_LIGHT_UPDATES && VanillaRenderController.rtRuntimeWorkRequested()) {
            cir.setReturnValue(0);
        }
    }
}
