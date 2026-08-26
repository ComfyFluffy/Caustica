package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.client.CausticaKeyMappings;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/** Adds Caustica's capture key to the vanilla Controls screen. */
@Mixin(Options.class)
public abstract class OptionsMixin {
    @Shadow @Final @Mutable public KeyMapping[] keyMappings;

    @Inject(method = "<init>", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/Options;keyMappings:[Lnet/minecraft/client/KeyMapping;",
            shift = At.Shift.AFTER))
    private void caustica$addKeyMappings(CallbackInfo ci) {
        KeyMapping[] additions = CausticaKeyMappings.all();
        int originalLength = keyMappings.length;
        keyMappings = Arrays.copyOf(keyMappings, originalLength + additions.length);
        System.arraycopy(additions, 0, keyMappings, originalLength, additions.length);
    }
}
