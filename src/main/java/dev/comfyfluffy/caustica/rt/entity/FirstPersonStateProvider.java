package dev.comfyfluffy.caustica.rt.entity;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Provides the first-person body render state for the camera entity.
 * <p>
 * Implementations return a pre-extracted {@link EntityRenderState} that was produced by
 * vanilla's or a mod's frame extraction. The returned state must be valid for the current
 * frame and belong to the camera entity. Caustica does not perform position offsets, part
 * hiding, or pose modifications — the provider must return a complete first-person state.
 */
public interface FirstPersonStateProvider {
    /**
     * Returns the first-person body render state for the camera entity, or {@code null} if
     * unavailable this frame.
     * <p>
     * This is an observational query on the current render frame. Caustica calls it <em>before</em> it
     * extracts the camera entity's ordinary body, so an implementation must not mutate the camera entity,
     * any world entity, vanilla's render state list, any render state object or its fields, Caustica's
     * config, or the provider registry — any such mutation would change the body's extraction result.
     *
     * @param camera the camera entity (typically the local player)
     * @param partialTick sub-tick interpolation fraction
     * @return the first-person render state, or {@code null} if not available
     * @throws Exception if state extraction fails
     */
    @Nullable
    EntityRenderState provideState(Entity camera, float partialTick) throws Exception;
}
