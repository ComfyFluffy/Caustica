package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.client.UltraScreenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Drains in-flight RT work at the start of a user-triggered resource reload (F3+T, or applying a
 * resource pack), before MC re-stitches the block atlas and destroys the old GPU image. The RT
 * descriptors still reference that image, so destroying it while one of our frames is in flight trips
 * the Vulkan validation-layer {@code vkDestroyImage} abort (the "sometimes crash" on a pack switch).
 *
 * <p>The no-arg {@code reloadResourcePacks()} is the user-facing entry (F3+T and the pack screen);
 * startup loading uses a private overload, when RT isn't up yet, so it isn't hooked. The actual
 * re-resolve/rebind of every texture handle happens on the next world frame (see
 * {@link RtComposite#onResourceReloadStart()} → {@code ensureWorld}), once the new atlas exists.
 */
@Mixin(Minecraft.class)
public class MinecraftReloadMixin {
    @Inject(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"))
    private void caustica$rtReloadStart(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        UltraScreenshot.INSTANCE.abort(Component.translatable("caustica.status.ultraScreenshot.invalidated"));
        RtComposite.INSTANCE.onResourceReloadStart();
    }
}
