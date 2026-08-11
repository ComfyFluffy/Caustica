package dev.comfyfluffy.caustica.rt.entity;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

/**
 * Declares whether the provided first-person geometry is camera-safe.
 * <p>
 * Camera-safe means the geometry will not wrap or intersect the camera origin when rendered.
 * This declaration must be made per-frame, as safety depends on dynamic factors like part
 * visibility and position offsets.
 */
public interface CameraSafetyDeclaration {
    /**
     * Returns {@code true} if the first-person geometry is safe to render for primary camera
     * rays, {@code false} otherwise.
     * <p>
     * If this returns {@code false}, throws an exception, or the provider does not implement
     * this interface, the first-person instance will not be created.
     * <p>
     * This is an observational query on the current render frame. Caustica calls it <em>before</em> it
     * extracts the camera entity's ordinary body, so an implementation must not mutate the camera entity,
     * any world entity, vanilla's render state list, any render state object or its fields, Caustica's
     * config, or the provider registry — any such mutation would change the body's extraction result.
     *
     * @param camera the camera entity
     * @param state the first-person render state to evaluate
     * @param partialTick sub-tick interpolation fraction
     * @return {@code true} if camera-safe, {@code false} otherwise
     * @throws Exception if safety cannot be determined
     */
    boolean isCameraSafe(Entity camera, EntityRenderState state, float partialTick) throws Exception;
}
