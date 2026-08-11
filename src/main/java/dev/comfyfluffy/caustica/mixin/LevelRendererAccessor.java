package dev.comfyfluffy.caustica.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the level render state so optional first-person compatibility bridges can read the entity
 * render states vanilla extracted this frame. {@code LevelExtractor.extract} clears and repopulates
 * {@code entityRenderStates} before the render phase runs, so the list a bridge sees during Caustica's
 * capture holds exactly this frame's states.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("levelRenderState")
    LevelRenderState caustica$getLevelRenderState();
}
