package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forwards vanilla's block-dirty signal to the RT renderer so edited sections (and their boundary
 * neighbours) re-extract. In 26.2 the dirty methods live on {@link LevelExtractor}. We hook the
 * <em>block-change</em> entry points and let {@link RtTerrain#markBlocksDirty} expand to sections:
 *
 * <ul>
 *   <li>{@code blockChanged(BlockPos, int)} — packet/prediction block changes.</li>
 *   <li>{@code setBlocksDirty(int×6)} — multi-block changes (explosions, etc.) and the
 *       {@code setBlockDirty(pos, old, new)} render-shape path.</li>
 * </ul>
 *
 * <p>We deliberately do <em>not</em> hook {@code setSectionDirty}: lighting-only invalidations
 * ({@code ClientChunkCache.onLightUpdate}) route straight through it, and we ray-trace lighting, so a
 * light change never alters our geometry. Hooking the block entry points keeps us off that churn.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
    @Inject(method = "blockChanged(Lnet/minecraft/core/BlockPos;I)V", at = @At("HEAD"))
    private void caustica$rtBlockChanged(BlockPos pos, int updateFlags, CallbackInfo ci) {
        RtTerrain.markBlocksDirty(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
    }

    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"))
    private void caustica$rtBlocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        RtTerrain.markBlocksDirty(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
