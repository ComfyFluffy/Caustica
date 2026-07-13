package dev.comfyfluffy.caustica.rt.entity;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.mixin.ModelPartAccessor;
import dev.comfyfluffy.caustica.mixin.RenderSetupAccessor;
import dev.comfyfluffy.caustica.mixin.RenderTypeAccessor;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
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
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.util.RandomSource;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;

import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
import dev.comfyfluffy.caustica.rt.material.RtEntityMaterials;
import dev.comfyfluffy.caustica.rt.material.RtParallelAtlas;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link SubmitNodeCollector} that intercepts entity model submissions and renders them straight into
 * an {@link RtEntityCapture}, reusing all of vanilla's posing/animation. {@code submitModel} is the
 * hook (it is where {@code LivingEntityRenderer.submit} sends the body + each feature layer). Every
 * other submit* path (name tags, leashes, shadows, flames, held items, block models, custom geometry,
 * particles, gizmos) is a no-op — body geometry is what the RT path needs.
 *
 * <p>Driven once per entity per frame: {@link #begin} sets the capture, then {@code
 * EntityRenderDispatcher.submit} fans out into {@code submitModel} here. Reused across entities.
 */
public final class RtEntityCollector implements SubmitNodeCollector {
    private static final Direction[] DIRECTIONS = Direction.values();

    private RtEntityCapture capture;
    private boolean profileDynamicEntity;
    private final RtEntityCapture parityCapture = new RtEntityCapture();
    private final RtCuboidEmitter cuboidEmitter = new RtCuboidEmitter();
    private ModelBlockRenderer blockRenderer; // lazily-built mesher for moving (falling) blocks
    // Set by order(int) for the very next submitModel call (banner/shield pattern-layer stacking), then
    // consumed. Baked-quad paths (addQuad) don't use ordering and always reset the capture's order to 0.
    private int pendingOrder;
    private final RtTextVertexConsumer textVertexConsumer = new RtTextVertexConsumer();
    private final TextGlyphVisitor textGlyphVisitor = new TextGlyphVisitor();
    // Vanilla already computes the Glowing-effect outline colour (opaque team colour, or 0 when not
    // glowing) per submitModel call — see EntityRenderer.extractCommon's outlineColor. Every submitModel
    // call for one entity carries the same value, so the last non-zero one seen this entity is enough.
    private int outlineColor;

    /**
     * Point the collector at the capture buffer for the next {@code dispatcher.submit}, resetting the
     * outline colour for the entity about to be captured. The end-of-entity {@code begin(null)} detach
     * call must NOT reset it — {@link RtEntities} reads {@link #outlineColor()} after that detach.
     */
    public void begin(RtEntityCapture capture, boolean profileDynamicEntity) {
        this.capture = capture;
        this.profileDynamicEntity = capture != null && profileDynamicEntity;
        if (capture != null) {
            this.outlineColor = 0;
            this.pendingOrder = 0;
        }
    }

    /** This entity's Glowing-effect outline colour (opaque ARGB), or 0 if it isn't glowing. */
    public int outlineColor() {
        return outlineColor;
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                int lightCoords, int overlayCoords, int tintedColor, TextureAtlasSprite sprite,
                                int outlineColor, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        if (capture == null) {
            return;
        }
        if (outlineColor != 0) {
            this.outlineColor = outlineColor;
        }
        capture.currentOrder = pendingOrder; // banner/shield pattern-layer stacking rank; consumed once
        pendingOrder = 0;
        if (profileDynamicEntity) {
            RtFrameStats.FRAME.count("entityModelSubmissions", 1);
        }
        long materialStart = profileDynamicEntity ? RtFrameStats.FRAME.startStage() : 0L;
        // Resolve this submission's texture to a bindless slot; the capture stamps it on every prim.
        // Block-entity models (chests/signs/beds) texture from an atlas SPRITE: use that atlas + remap
        // the ModelPart 0..1 UVs into the sprite's region. Mobs use a full texture (sprite == null).
        try {
            capture.currentBlockAtlas = false; // model bodies use per-type bindless _s/_n, not the block atlas
            if (sprite != null) {
                capture.setUvRemap(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
                if (TextureAtlas.LOCATION_BLOCKS.equals(sprite.atlasLocation())) {
                    // A block entity drawing from the block atlas (rare) → reuse the terrain _s/_n atlases.
                    capture.currentTexSlot = RtEntityTextures.INSTANCE.slotForAtlas(sprite.atlasLocation());
                    setBlockMaterial(sprite);
                } else {
                    // Block-entity sprite atlas (chest/sign/bed/shulker/banner/…): bind its parallel _s/_n into
                    // the bindless slot and flag per-sprite presence → world.rchit's bindless material path.
                    capture.currentTexSlot = RtEntityTextures.INSTANCE.slotForBlockEntityAtlas(sprite.atlasLocation());
                    int flags = RtEntityMaterials.INSTANCE.ensure(sprite);
                    capture.currentHasS = (flags & RtParallelAtlas.HAS_S) != 0;
                    capture.currentHasN = (flags & RtParallelAtlas.HAS_N) != 0;
                }
            } else {
                // Mobs use a per-type texture — pick up its LabPBR _n/_s presence for the prim flags.
                int slot = RtEntityTextures.INSTANCE.slotFor(renderType);
                capture.currentTexSlot = slot;
                capture.currentHasS = RtEntityTextures.INSTANCE.slotHasSpec(slot);
                capture.currentHasN = RtEntityTextures.INSTANCE.slotHasNormal(slot);
                capture.clearUvRemap();
            }
            // Alpha-blended model layers (slime / sulfur-cube shells, ghosts, spectral overlays, …) get
            // stochastic transparency in world.rahit so the inner content shows through; opaque/cutout mob
            // surfaces keep the binary cutout. Block-entity sprite submissions (chests/signs) are opaque.
            capture.currentTranslucent = sprite == null && isTranslucent(renderType);
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.submit.material", materialStart);
        }
        // Pose the model from its render state (idempotent re-pose; mirrors what the renderer does for
        // its feature layers), then render the posed parts into the capture. renderToBuffer applies the
        // PoseStack to every vertex/normal, so the capture receives world-/camera-relative geometry.
        long setupStart = profileDynamicEntity ? RtFrameStats.FRAME.startStage() : 0L;
        try {
            model.setupAnim(state);
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.submit.setupAnim", setupStart);
        }
        if (profileDynamicEntity && RtFrameStats.enabled()) {
            long metricsStart = RtFrameStats.FRAME.startStage();
            try {
                RtFrameStats.FRAME.count("entityCuboids", countVisibleCuboids(model.root()));
            } finally {
                RtFrameStats.FRAME.endStage("entity.capture.submit.metrics", metricsStart);
            }
        }
        int color = tintedColor == 0 ? -1 : tintedColor; // 0 would be fully transparent black; treat as white
        int vertStart = capture.verts.size();
        int idxStart = capture.idx.size();
        int uvStart = capture.uvList.size();
        int primStart = capture.prim.size();
        RtCuboidEmitter.ModelTemplate directTemplate = cuboidEmitter.prepare(model);
        long directCubeCounts = 0L;
        long drawStart = profileDynamicEntity ? RtFrameStats.FRAME.startStage() : 0L;
        try {
            if (directTemplate != null) {
                directCubeCounts = cuboidEmitter.emit(directTemplate, poseStack, capture, color);
            } else {
                model.renderToBuffer(poseStack, capture, lightCoords, overlayCoords, color);
            }
        } finally {
            RtFrameStats.FRAME.endStage(directTemplate != null
                    ? "entity.capture.submit.modelDraw.direct"
                    : "entity.capture.submit.modelDraw.fallback", drawStart);
            RtFrameStats.FRAME.endStage("entity.capture.submit.modelDraw", drawStart);
        }
        int addedVertices = (capture.verts.size() - vertStart) / 3;
        int addedQuads = (capture.idx.size() - idxStart) / 6;
        if (profileDynamicEntity) {
            RtFrameStats.FRAME.count("entityModelVertices", addedVertices);
            RtFrameStats.FRAME.count("entityModelQuads", addedQuads);
            RtFrameStats.FRAME.count(directTemplate != null ? "entityDirectSubmissions" : "entityDirectFallbacks", 1);
            if (directTemplate != null) {
                RtFrameStats.FRAME.count("entityDirectVertices", addedVertices);
                RtFrameStats.FRAME.count("entityDirectQuads", addedQuads);
                RtFrameStats.FRAME.count("entitySpecializedCuboids", directCubeCounts >>> 32);
                RtFrameStats.FRAME.count("entityGenericCuboids", directCubeCounts & 0xffffffffL);
            }
        }

        if (CausticaConfig.Rt.Entities.CAPTURE_PARITY.value()) {
            parityCapture.reset(addedVertices);
            capture.copySubmissionStateTo(parityCapture);
            long parityStart = profileDynamicEntity ? RtFrameStats.FRAME.startStage() : 0L;
            try {
                model.renderToBuffer(poseStack, parityCapture, lightCoords, overlayCoords, color);
                capture.assertSubmissionBitwiseIdentical(vertStart, idxStart, uvStart, primStart,
                        parityCapture, "model " + model.getClass().getName());
                if (profileDynamicEntity) {
                    RtFrameStats.FRAME.count("entityParityChecks", 1);
                }
            } finally {
                RtFrameStats.FRAME.endStage("entity.capture.submit.parity", parityStart);
            }
        }
    }

    private static int countVisibleCuboids(ModelPart part) {
        if (!part.visible) {
            return 0;
        }
        ModelPartAccessor access = (ModelPartAccessor) (Object) part;
        int count = part.skipDraw ? 0 : access.caustica$cubes().size();
        for (ModelPart child : access.caustica$children().values()) {
            count += countVisibleCuboids(child);
        }
        return count;
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        // Single un-ordered capture sink reused synchronously (no queuing): the caller always issues the
        // very next submission immediately after requesting an order, so stashing it for that one
        // submitModel call (see there) is enough to recover banner/shield pattern-layer stacking.
        pendingOrder = order;
        return this;
    }

    /** Capture a list of baked quads (items / block models), each textured from its sprite's atlas. */
    private void addQuads(Matrix4f pose, List<BakedQuad> quads, int[] tintLayers) {
        int idxStart = capture.idx.size();
        long started = profileDynamicEntity ? RtFrameStats.FRAME.startStage() : 0L;
        try {
            for (BakedQuad q : quads) {
                addQuad(pose, q, tintLayers);
            }
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.submit.bakedQuads", started);
            countBakedOutput(idxStart);
        }
    }

    private void countBakedOutput(int idxStart) {
        if (!profileDynamicEntity) {
            return;
        }
        int quads = (capture.idx.size() - idxStart) / 6;
        RtFrameStats.FRAME.count("entityBakedQuads", quads);
        RtFrameStats.FRAME.count("entityBakedVertices", (long) quads * 4L);
    }

    /** Capture one baked quad, resolving its atlas (block vs item) to a bindless slot stamped per-prim. */
    private void addQuad(Matrix4f pose, BakedQuad q, int[] tintLayers) {
        TextureAtlasSprite sprite = q.materialInfo().sprite();
        capture.currentTexSlot = sprite != null
                ? RtEntityTextures.INSTANCE.slotForAtlas(sprite.atlasLocation())
                : 0;
        setBlockMaterial(sprite); // block-atlas sprites (block items/falling/contained blocks) → terrain _s/_n
        capture.currentTranslucent = false; // block/item geometry is opaque (the inner content we want solid)
        capture.currentOrder = 0; // baked-quad paths never stack decal layers
        capture.addBakedQuad(pose, q, tintColor(q.materialInfo().tintIndex(), tintLayers));
    }

    /**
     * Flag the capture's LabPBR _s/_n source for the next quad. Block-like entities (block items, falling
     * blocks, contained block displays) sample the block atlas, so their material maps live in the terrain
     * parallel atlases (blockSpecAtlas/blockNormalAtlas) at the SAME UV — reuse {@link RtBlockMaterials}'s
     * per-sprite presence and let world.rchit sample those (mat code 2). Non-block-atlas sprites (the item
     * atlas, mob/BE sprite atlases) have no block-atlas material → flags stay off (current behaviour).
     */
    private void setBlockMaterial(TextureAtlasSprite sprite) {
        if (sprite != null && TextureAtlas.LOCATION_BLOCKS.equals(sprite.atlasLocation())) {
            int flags = RtBlockMaterials.INSTANCE.ensure(sprite);
            capture.currentBlockAtlas = true;
            capture.currentHasS = (flags & RtBlockMaterials.HAS_S) != 0;
            capture.currentHasN = (flags & RtBlockMaterials.HAS_N) != 0;
        } else {
            capture.currentBlockAtlas = false;
            capture.currentHasS = false;
            capture.currentHasN = false;
        }
    }

    /** Whether a render type is alpha-blended (translucent) — its pipeline's color target has a blend
     *  function. Cutout/solid have none. Drives stochastic entity transparency in world.rahit. */
    private static boolean isTranslucent(RenderType renderType) {
        if (renderType == null) {
            return false;
        }
        try {
            // RenderSetup is final, so the accessor cast goes through Object (the interface is mixed in at
            // runtime), mirroring RtEntityTextures#textureLocation.
            Object setup = ((RenderTypeAccessor) renderType).caustica$state();
            RenderPipeline pipeline = ((RenderSetupAccessor) setup).caustica$pipeline();
            ColorTargetState cts = pipeline.getColorTargetState();
            return cts != null && cts.blendFunction().isPresent();
        } catch (Throwable t) {
            return false; // unknown → treat as opaque (the safe default: no behavior change)
        }
    }

    /** Resolve a quad's tint colour from its tint index + the submission's tint layers (white if untinted). */
    private static int tintColor(int tintIndex, int[] tintLayers) {
        if (tintIndex < 0 || tintLayers == null || tintIndex >= tintLayers.length) {
            return -1; // white
        }
        return tintLayers[tintIndex] | 0xFF000000; // force opaque; capture uses only the rgb
    }

    /**
     * Re-mesh a contained block-model display (the sulfur cube's swallowed block, item-frame blocks, …)
     * into the active capture. Driven by {@code BlockModelRenderStateMixin} during RT capture, because the
     * display block-model set may wrap a block in a special renderer the normal submit path can't capture
     * (it submitted zero quads). We mesh from the WORLD block-state model set instead — the same source
     * falling blocks use, whose {@code SingleVariant} yields real parts — and push the quads through {@link
     * #addQuads} (block-atlas textured, opaque). Tints default to white (biome tint is a follow-up).
     */
    public void captureBlockState(BlockState blockState, Matrix4fc transform, PoseStack poseStack) {
        if (capture == null || blockState.isAir()) {
            return;
        }
        BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(blockState);
        if (model == null) {
            return;
        }
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(42L), parts);
        if (parts.isEmpty()) {
            return;
        }
        poseStack.pushPose();
        if (transform != null) {
            poseStack.mulPose(transform);
        }
        Matrix4f pose = poseStack.last().pose();
        for (BlockStateModelPart part : parts) {
            addQuads(pose, part.getQuads(null), null);
            for (Direction d : DIRECTIONS) {
                addQuads(pose, part.getQuads(d), null);
            }
        }
        poseStack.popPose();
    }

    // --- Everything below is intentionally a no-op: not entity body geometry. ---

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    }

    @Override
    public void submitNameTag(PoseStack poseStack, Vec3 nameTagAttachment, int offset, Component name,
                              boolean seeThrough, int lightCoords, CameraRenderState camera) {
    }


    // Sign text (AbstractSignRenderer) and any other in-world text (maps, …) land here. Mirrors
    // TextFeatureRenderer.buildGroup's own flush path exactly (same Font.prepareText/prepare8xTextOutline
    // + GlyphVisitor calls) but visits glyphs straight into the capture instead of a real vertex buffer,
    // so sign text gets real ray-traced geometry instead of being dropped.
    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow,
                           Font.DisplayMode displayMode, int lightCoords, int color, int backgroundColor, int outlineColor) {
        if (capture == null) {
            return;
        }
        Matrix4f pose = poseStack.last().pose();
        Font font = Minecraft.getInstance().font;
        textGlyphVisitor.pose = pose;
        textGlyphVisitor.lightCoords = lightCoords;
        if (outlineColor == 0) {
            textGlyphVisitor.displayMode = displayMode;
            font.prepareText(string, x, y, color, dropShadow, false, backgroundColor).visit(textGlyphVisitor);
        } else {
            // Same two-pass outline technique as vanilla: an 8-directional expanded copy first (flat,
            // NORMAL), then the real text on top with a polygon-offset display mode so it doesn't z-fight
            // the outline it's sitting on.
            Font.PreparedText outline = font.prepare8xTextOutline(string, x, y, outlineColor);
            Font.PreparedText text = font.prepareText(string, x, y, color, false, false, 0);
            textGlyphVisitor.displayMode = Font.DisplayMode.NORMAL;
            outline.visit(textGlyphVisitor);
            textGlyphVisitor.displayMode = Font.DisplayMode.POLYGON_OFFSET;
            text.visit(textGlyphVisitor);
        }
    }

    /** Resolves each glyph's render type to a bindless slot and renders it into {@link #textVertexConsumer}. */
    private final class TextGlyphVisitor implements Font.GlyphVisitor {
        Matrix4f pose;
        int lightCoords;
        Font.DisplayMode displayMode;

        @Override
        public void acceptRenderable(TextRenderable renderable) {
            RenderType renderType = renderable.renderType(displayMode);
            capture.currentTexSlot = RtEntityTextures.INSTANCE.slotFor(renderType);
            capture.currentHasS = false;
            capture.currentHasN = false;
            capture.currentBlockAtlas = false;
            capture.currentTranslucent = isTranslucent(renderType); // AA glyph edges → stochastic alpha
            capture.currentOrder = 0;
            capture.clearUvRemap(); // glyph U/V are already atlas-space
            renderable.render(pose, textVertexConsumer, lightCoords, false);
        }
    }

    /**
     * Adapts the builder-style {@link VertexConsumer} calls glyph rendering makes ({@code
     * addVertex(pose,x,y,z).setColor(c).setUv(u,v).setLight(light)}) into {@link RtEntityCapture}'s bulk
     * {@code addVertex}. {@code setUv2} (light, via the default {@code setLight}) is always the last call
     * per vertex in {@code BakedSheetGlyph}, so committing there is safe — no vertex is ever left pending.
     */
    private final class RtTextVertexConsumer implements VertexConsumer {
        private float vx, vy, vz;
        private int vColor = -1;
        private float vu, vv;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vx = x;
            vy = y;
            vz = z;
            vColor = -1;
            vu = 0f;
            vv = 0f;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            vColor = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            vColor = color;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            vu = u;
            vv = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this; // overlay unused by the capture
        }

        @Override
        public VertexConsumer setUv2(int lightU, int lightV) {
            // Inverts VertexConsumer#setLight's default packing; light itself is unused by the capture
            // (entities are fully path-traced), so any value round-trips fine.
            int light = (lightU & 0xFFFF) | (lightV << 16);
            capture.addVertex(vx, vy, vz, vColor, vu, vv, 0, light, 0f, 0f, 0f);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this; // planar glyph quad → emitQuad's geometric fallback is exact
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
    }

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
    }

    // Falling blocks render here. Mesh the block model (vanilla's mesher, same as terrain) into the
    // capture; the -0.5,0,-0.5 centring is already baked into poseStack by FallingBlockRenderer, so the
    // [0,1] block-model quads transform straight by poseStack.last().pose(). Block-atlas textured.
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
                setBlockMaterial(quad.materialInfo().sprite()); // block-atlas sprite → terrain _s/_n (mat code 2)
                capture.currentTranslucent = false; // falling blocks are opaque
                capture.currentOrder = 0; // baked-quad paths never stack decal layers
                capture.addBakedQuad(pose, quad, -1); // white tint (falling blocks rarely biome-tinted)
            }
        };
        // No local catch: a falling block is an entity (captured via dispatcher.submit), so a meshing
        // throw propagates to the entity-capture handler in RtEntities, which fails loud like any other
        // entity rather than silently dropping it.
        int idxStart = capture.idx.size();
        long started = profileDynamicEntity ? RtFrameStats.FRAME.startStage() : 0L;
        try {
            blockRenderer.tesselateBlock(out, 0, 0, 0, state, state.blockPos, bs, model, bs.getSeed(state.blockPos));
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.submit.bakedQuads", started);
            countBakedOutput(idxStart);
        }
    }

    // FallingBlockEntity renders its block model here. Capture every part's quads (direction-independent
    // + all six cullface lists), block-atlas textured (slot 0).
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

    // Held weapons/tools (via the in-hand layer) + dropped items (ItemEntity) render here as baked
    // quads on the block atlas. Capture them block-atlas textured (slot 0).
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

    @Override
    public <T extends SubmitNode> void submitCustom(SubmitRenderPhase<T> phase, T node) {
    }
}
