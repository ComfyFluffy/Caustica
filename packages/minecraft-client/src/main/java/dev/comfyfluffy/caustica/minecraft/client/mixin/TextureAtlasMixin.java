package dev.comfyfluffy.caustica.minecraft.client.mixin;

import dev.comfyfluffy.caustica.minecraft.client.UltraCaptureSession;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops animated scene sprites while a capture owns one immutable scene. */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void caustica$freezeCaptureAnimations(CallbackInfo ci) {
        if (UltraCaptureSession.active()) {
            ci.cancel();
        }
    }
}
