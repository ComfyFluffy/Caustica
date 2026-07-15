package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.CausticaMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Resource-epoch proof that an atlas sprite is used by at least one light-emitting block state. */
public final class RtEmissionSemantics {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int VARIANT_PROBES = 8;

    private final Map<TextureAtlasSprite, Integer> maxStateLight;
    private final int emittingStates;
    private final int failedStates;

    private RtEmissionSemantics(Map<TextureAtlasSprite, Integer> maxStateLight,
                                int emittingStates, int failedStates) {
        this.maxStateLight = maxStateLight;
        this.emittingStates = emittingStates;
        this.failedStates = failedStates;
    }

    public static RtEmissionSemantics analyze() {
        IdentityHashMap<TextureAtlasSprite, Integer> sprites = new IdentityHashMap<>();
        int states = 0;
        int failures = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                int light = state.getLightEmission();
                if (light <= 0 || state.getRenderShape() != RenderShape.MODEL) continue;
                states++;
                try {
                    collectState(state, light, sprites);
                } catch (Throwable throwable) {
                    failures++;
                    CausticaMod.LOGGER.debug("Could not analyze emissive material sprites for {}", state, throwable);
                }
            }
        }
        Map<TextureAtlasSprite, Integer> frozen = Collections.unmodifiableMap(new IdentityHashMap<>(sprites));
        CausticaMod.LOGGER.info("RT emission semantics: emittingStates={}, sprites={}, failedStates={}",
                states, frozen.size(), failures);
        return new RtEmissionSemantics(frozen, states, failures);
    }

    public boolean permits(TextureAtlasSprite sprite) {
        return maxStateLight.containsKey(sprite);
    }

    public int maxStateLight(TextureAtlasSprite sprite) {
        return maxStateLight.getOrDefault(sprite, 0);
    }

    public int spriteCount() {
        return maxStateLight.size();
    }

    public int emittingStates() {
        return emittingStates;
    }

    public int failedStates() {
        return failedStates;
    }

    private static void collectState(BlockState state, int light,
                                     IdentityHashMap<TextureAtlasSprite, Integer> sprites) {
        BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        if (model == null) return;
        for (int probe = 0; probe < VARIANT_PROBES; probe++) {
            List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(RandomSource.create(mixSeed(state.hashCode(), probe)), parts);
            for (BlockStateModelPart part : parts) {
                collectQuads(part.getQuads(null), light, sprites);
                for (Direction direction : DIRECTIONS) collectQuads(part.getQuads(direction), light, sprites);
            }
        }
    }

    private static void collectQuads(List<? extends BakedQuad> quads,
                                     int light, IdentityHashMap<TextureAtlasSprite, Integer> sprites) {
        for (var quad : quads) {
            TextureAtlasSprite sprite = quad.materialInfo().sprite();
            if (sprite != null) sprites.merge(sprite, light, Math::max);
        }
    }

    private static long mixSeed(int stateHash, int probe) {
        long seed = Integer.toUnsignedLong(stateHash) ^ (0x9E3779B97F4A7C15L * (probe + 1L));
        seed ^= seed >>> 30;
        seed *= 0xBF58476D1CE4E5B9L;
        seed ^= seed >>> 27;
        return seed;
    }
}
