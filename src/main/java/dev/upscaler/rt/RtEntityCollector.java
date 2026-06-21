package dev.upscaler.rt;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.List;

/**
 * P5.1b-2 capture infrastructure: a {@link SubmitNodeCollector} that intercepts entity model
 * submissions and renders them straight into an {@link RtEntityCapture}, reusing all of vanilla's
 * posing/animation. {@code submitModel} is the hook (it is where {@code LivingEntityRenderer.submit}
 * sends the body + each feature layer); we pose the model from its render state and render it into the
 * capture. Every other submit* path (name tags, leashes, shadows, flames, held items, block models,
 * custom geometry, particles, gizmos) is a no-op — the entity *body geometry* is what we want for RT;
 * held items / custom geometry are not captured yet (acceptable, noted for P5.1b-2b).
 *
 * <p>Driven once per entity per frame: {@link #begin} sets the capture, then {@code
 * EntityRenderDispatcher.submit} fans out into {@code submitModel} here. Reused across entities.
 */
public final class RtEntityCollector implements SubmitNodeCollector {
    private static final Direction[] DIRECTIONS = Direction.values();

    private RtEntityCapture capture;
    private ModelBlockRenderer blockRenderer; // P5.1b-2e: lazily-built mesher for moving (falling) blocks

    /** Point the collector at the capture buffer for the next {@code dispatcher.submit}. */
    public void begin(RtEntityCapture capture) {
        this.capture = capture;
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                int lightCoords, int overlayCoords, int tintedColor, TextureAtlasSprite sprite,
                                int outlineColor, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        if (capture == null) {
            return;
        }
        // Resolve this submission's texture to a bindless slot; the capture stamps it on every prim.
        // Block-entity models (chests/signs/beds) texture from an atlas SPRITE: use that atlas + remap
        // the ModelPart 0..1 UVs into the sprite's region. Mobs use a full texture (sprite == null).
        if (sprite != null) {
            capture.currentTexSlot = RtEntityTextures.INSTANCE.slotForAtlas(sprite.atlasLocation());
            capture.currentHasS = false; // atlas-sourced (block-entity sprites) have no per-type _n/_s
            capture.currentHasN = false;
            capture.setUvRemap(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
        } else {
            // Mobs use a per-type texture — pick up its LabPBR _n/_s presence for the prim flags (P6.2c).
            int slot = RtEntityTextures.INSTANCE.slotFor(renderType);
            capture.currentTexSlot = slot;
            capture.currentHasS = RtEntityTextures.INSTANCE.slotHasSpec(slot);
            capture.currentHasN = RtEntityTextures.INSTANCE.slotHasNormal(slot);
            capture.clearUvRemap();
        }
        // Pose the model from its render state (idempotent re-pose; mirrors what the renderer does for
        // its feature layers), then render the posed parts into the capture. renderToBuffer applies the
        // PoseStack to every vertex/normal, so the capture receives world-/camera-relative geometry.
        model.setupAnim(state);
        int color = tintedColor == 0 ? -1 : tintedColor; // 0 would be fully transparent black; treat as white
        model.renderToBuffer(poseStack, capture, lightCoords, overlayCoords, color);
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return this; // single un-ordered capture sink — ordering is irrelevant for geometry extraction
    }

    /** Capture a list of baked quads (items / block models), each textured from its sprite's atlas. */
    private void addQuads(Matrix4f pose, List<BakedQuad> quads, int[] tintLayers) {
        for (BakedQuad q : quads) {
            addQuad(pose, q, tintLayers);
        }
    }

    /** Capture one baked quad, resolving its atlas (block vs item) to a bindless slot stamped per-prim. */
    private void addQuad(Matrix4f pose, BakedQuad q, int[] tintLayers) {
        TextureAtlasSprite sprite = q.materialInfo().sprite();
        capture.currentTexSlot = sprite != null
                ? RtEntityTextures.INSTANCE.slotForAtlas(sprite.atlasLocation())
                : 0;
        capture.currentHasS = false; // baked quads (items) are atlas-sourced — no per-type _n/_s
        capture.currentHasN = false;
        capture.addBakedQuad(pose, q, tintColor(q.materialInfo().tintIndex(), tintLayers));
    }

    /** Resolve a quad's tint colour from its tint index + the submission's tint layers (white if untinted). */
    private static int tintColor(int tintIndex, int[] tintLayers) {
        if (tintIndex < 0 || tintLayers == null || tintIndex >= tintLayers.length) {
            return -1; // white
        }
        return tintLayers[tintIndex] | 0xFF000000; // force opaque; capture uses only the rgb
    }

    // --- Everything below is intentionally a no-op: not entity body geometry. ---

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    }

    @Override
    public void submitNameTag(PoseStack poseStack, Vec3 nameTagAttachment, int offset, Component name,
                              boolean seeThrough, int lightCoords, CameraRenderState camera) {
    }

    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow,
                           Font.DisplayMode displayMode, int lightCoords, int color, int backgroundColor, int outlineColor) {
    }

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
    }

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
    }

    // P5.1b-2e: falling blocks render here. Mesh the block model (vanilla's mesher, same as terrain)
    // into the capture; the -0.5,0,-0.5 centring is already baked into poseStack by FallingBlockRenderer,
    // so the [0,1] block-model quads transform straight by poseStack.last().pose(). Block-atlas textured.
    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState state, int outlineColor) {
        if (capture == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        BlockState bs = state.blockState;
        BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(bs);
        if (model == null) {
            return;
        }
        if (blockRenderer == null) {
            blockRenderer = new ModelBlockRenderer(false, true, mc.getBlockColors());
        }
        final Matrix4f pose = poseStack.last().pose();
        final int slot = RtEntityTextures.INSTANCE.slotForAtlas(TextureAtlas.LOCATION_BLOCKS);
        BlockQuadOutput out = new BlockQuadOutput() {
            @Override
            public void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
                capture.currentTexSlot = slot;
                capture.currentHasS = false; // block-atlas sourced — no per-type _n/_s
                capture.currentHasN = false;
                capture.addBakedQuad(pose, quad, -1); // white tint (falling blocks rarely biome-tinted)
            }
        };
        // No local catch: a falling block is an entity (captured via dispatcher.submit), so a meshing
        // throw propagates to the entity-capture handler in RtEntities, which fails loud like any other
        // entity rather than silently dropping it.
        blockRenderer.tesselateBlock(out, 0, 0, 0, state, state.blockPos, bs, model, bs.getSeed(state.blockPos));
    }

    // P5.1b-2e: falling blocks (FallingBlockEntity) render their block model here. Capture every part's
    // quads (direction-independent + all six cullface lists), block-atlas textured (slot 0).
    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
                                 int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
        if (capture == null) {
            return;
        }
        Matrix4f pose = poseStack.last().pose();
        for (BlockStateModelPart part : parts) {
            addQuads(pose, part.getQuads(null), tintLayers);
            for (Direction d : DIRECTIONS) {
                addQuads(pose, part.getQuads(d), tintLayers);
            }
        }
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress) {
    }

    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color,
                                   float width, boolean afterTerrain) {
    }

    // P5.1b-2d: held weapons/tools (via the in-hand layer) + dropped items (ItemEntity) render here as
    // baked quads on the block atlas. Capture them block-atlas textured (slot 0).
    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords, int overlayCoords,
                           int outlineColor, int[] tintLayers, List<BakedQuad> quads, ItemStackRenderState.FoilType foilType) {
        if (capture == null) {
            return;
        }
        addQuads(poseStack.last().pose(), quads, tintLayers);
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                     SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
    }
}
