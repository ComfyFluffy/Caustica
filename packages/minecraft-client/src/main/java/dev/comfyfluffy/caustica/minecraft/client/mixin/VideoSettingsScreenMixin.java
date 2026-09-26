package dev.comfyfluffy.caustica.minecraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition;
import dev.comfyfluffy.caustica.minecraft.client.screen.CausticaVideoSettings;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts Caustica's "Ray Tracing" section at the top of vanilla Video Settings, leaves out the vanilla rows that
 * only steer raster rendering while ray tracing replaces it, and saves the rows Caustica's section edits when
 * the screen closes, including when it closes to open one of Caustica's pages.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
    @Inject(method = "addOptions", at = @At("HEAD"))
    private void caustica$addRayTracingSection(CallbackInfo ci) {
        OptionsSubScreenAccessor self = (OptionsSubScreenAccessor) (Object) this;
        CausticaVideoSettings.addSection(self.getList(), (VideoSettingsScreen) (Object) this, self.getLastScreen());
    }

    @WrapOperation(method = "addOptions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/options/VideoSettingsScreen;qualityOptions(Lnet/minecraft/client/Options;)[Lnet/minecraft/client/OptionInstance;"))
    private OptionInstance<?>[] caustica$withoutRasterQuality(Options options, Operation<OptionInstance<?>[]> original) {
        return CausticaVideoSettings.withoutRasterOnly(original.call(options), options);
    }

    @WrapOperation(method = "addOptions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/options/VideoSettingsScreen;preferenceOptions(Lnet/minecraft/client/Options;)[Lnet/minecraft/client/OptionInstance;"))
    private OptionInstance<?>[] caustica$withoutRasterPreferences(Options options,
                                                                  Operation<OptionInstance<?>[]> original) {
        return CausticaVideoSettings.withoutRasterOnly(original.call(options), options);
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void caustica$saveOptions(CallbackInfo ci) {
        CausticaClientComposition.current().apiServices().options().save();
    }
}
