package dev.comfyfluffy.caustica.api.pass;

import dev.comfyfluffy.caustica.api.resource.FrameResources;
import dev.comfyfluffy.caustica.api.view.SceneView;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * What every pass gets while recording one frame, whatever stage it records in.
 *
 * <p>The command buffer is mid-recording: the engine owns submission and the barriers into and out of each
 * stage, but barriers <em>between one pass's own dispatches</em> are that pass's job. The renderer's one
 * resource descriptor heap and one sampler descriptor heap are already bound before each pass records and
 * remain bound for the callback. A pass indexes those heaps directly and must not replace their bindings.
 *
 * <p>Subtypes expose stage-specific resources: {@link PostEffectFrame} provides the scene image and effect
 * chain, while {@link UiFrame} provides the UI layer and world-overlay resources.
 *
 * <p>The frame, command buffer, resource-retention capability, images, descriptor views, and subtype capabilities
 * are borrowed only for the current {@link Pass#record} invocation on that thread. Do not retain any of
 * them or call their methods after the callback returns. Commands recorded during the callback may continue
 * using the borrowed GPU resources; the renderer owns that asynchronous lifetime.
 */
public interface PassFrame extends FrameResources {
    /** The command buffer currently being recorded. Record work directly onto it. */
    VkCommandBuffer commandBuffer();

    /**
     * The renderer's temporal frame index. It increases by one for each rendered frame that continues
     * temporal history and by more across a history break (scene change, camera cut, resize, or an
     * interruption), so a value exactly one above the previous frame's means history may be reused.
     *
     * <p>Use this value to share a stable snapshot across an extension's passes in the same frame.
     */
    long frameIndex();

    /** The immutable camera, entry scene, and containing medium sampled for this rendered frame. */
    SceneView view();

    /** Renderer time coherently sampled for this rendered frame, in seconds. */
    double timeSeconds();

    /** Physical metres represented by one coordinate unit in {@link #view()}'s entry scene. */
    double metersPerSceneUnit();

    /**
     * The resolution the world was traced at this frame, which is not the display resolution: an upscaler
     * reconstructs to display size afterwards.
     *
     * <p>The value may change after a display resize or an upscaler quality change. Rebuild trace-sized
     * resources when it differs from the size used to create them.
     */
    int renderWidth();

    int renderHeight();
}
