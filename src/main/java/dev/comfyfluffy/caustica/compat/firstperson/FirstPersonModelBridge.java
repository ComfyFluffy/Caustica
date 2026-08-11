package dev.comfyfluffy.caustica.compat.firstperson;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.LevelRendererAccessor;
import dev.comfyfluffy.caustica.rt.entity.CameraSafetyDeclaration;
import dev.comfyfluffy.caustica.rt.entity.FirstPersonStateProvider;
import dev.comfyfluffy.caustica.rt.entity.FirstPersonStateRegistry;
import dev.tr7zw.firstperson.access.LivingEntityRenderStateAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Supplies Caustica with the first-person body state produced by the FirstPerson Model mod.
 *
 * <p>The mod appends one extra render state for the camera entity during vanilla's extract phase, taken
 * with the entity temporarily displaced by its computed offset, and marks that state — and only that
 * state — as the camera entity. Caustica therefore rebuilds no first-person geometry: it picks that
 * state up and feeds it through the ordinary capture path, and the offset already baked into
 * {@code x/y/z} places the instance correctly.
 *
 * <p>This class links against the mod, so it must only be touched once the loader has confirmed the mod
 * is present. Nothing on the render path references it.
 */
public final class FirstPersonModelBridge implements FirstPersonStateProvider, CameraSafetyDeclaration {
    private static final String PROVIDER_ID = "firstperson-model";
    private static final int PROVIDER_PRIORITY = 200;

    private boolean warnedAmbiguousCandidates;

    private FirstPersonModelBridge() {
    }

    public static void register() {
        FirstPersonModelBridge bridge = new FirstPersonModelBridge();
        FirstPersonStateRegistry.instance().register(PROVIDER_ID, PROVIDER_PRIORITY, bridge, bridge);
    }

    @Nullable
    @Override
    public EntityRenderState provideState(Entity camera, float partialTick) {
        LevelRenderer levelRenderer = Minecraft.getInstance().levelRenderer;
        if (levelRenderer == null) {
            return null;
        }
        LevelRenderState level = ((LevelRendererAccessor) levelRenderer).caustica$getLevelRenderState();
        if (level == null) {
            return null;
        }

        int cameraId = camera.getId();
        EntityRenderState found = null;
        for (EntityRenderState state : level.entityRenderStates) {
            // The mod's marker interface is mixed in at runtime, so the cast goes through the vanilla
            // supertype rather than through AvatarRenderState.
            if (!(state instanceof AvatarRenderState avatar)
                    || avatar.id != cameraId
                    || !((LivingEntityRenderStateAccess) state).isCameraEntity()) {
                continue;
            }
            if (found != null) {
                // Two marked states for one camera entity contradicts the mod's own invariant; picking
                // either by list order would be a guess, so this frame yields nothing.
                if (!warnedAmbiguousCandidates) {
                    warnedAmbiguousCandidates = true;
                    CausticaMod.LOGGER.warn("FirstPerson Model marked more than one render state for entity {};"
                            + " skipping the first-person instance", cameraId);
                }
                return null;
            }
            found = state;
        }
        return found;
    }

    @Override
    public boolean isCameraSafe(Entity camera, EntityRenderState state, float partialTick) {
        // The mod hides the head whenever it marks a state as the camera entity, so a marked state never
        // encloses the camera origin. Selection already rejected every unmarked state.
        return true;
    }
}
