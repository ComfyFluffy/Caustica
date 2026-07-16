package dev.comfyfluffy.caustica.rt.material;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * Semantic profile classifier used only while resolving a compiled material ID. Physical values are
 * copied into immutable {@link RtMaterialDesc} records at resource load; terrain primitives carry IDs.
 */
public final class RtMaterials {
    private RtMaterials() {}

    public enum Profile {
        // The first four are the sprite-classifiable profiles ({@link #profile} can only return these);
        // RtTerrainMaterials' variant index depends on their ordinals (checked at its class load).
        // WATER/LAVA exist only for the dedicated fluid singleton headers.
        DEFAULT(0.9f, 0.0f),
        METAL(0.3f, 1.0f),
        GLASS(0.1f, 0.0f),
        SMOOTH(0.35f, 0.0f),
        WATER(0.08f, 0.0f),
        LAVA(0.7f, 0.0f);

        private final float roughness;
        private final float metalness;

        Profile(float roughness, float metalness) {
            this.roughness = roughness;
            this.metalness = metalness;
        }

        public float roughness() {
            return roughness;
        }

        public float metalness() {
            return metalness;
        }
    }

    /** Water roughness; near-smooth so DLSS-RR resolves stable reflections. */
    public static final float WATER_ROUGH = Profile.WATER.roughness();
    /** Lava: opaque emitter, moderately rough. */
    public static final float LAVA_ROUGH = Profile.LAVA.roughness();
    /** Default entity roughness. */
    public static final float ENTITY_ROUGH = 0.8f;

    private static final Set<Block> SMOOTH = Set.of(
            Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_BRICKS, Blocks.QUARTZ_PILLAR,
            Blocks.SMOOTH_STONE, Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN,
            Blocks.POLISHED_GRANITE, Blocks.POLISHED_DIORITE, Blocks.POLISHED_ANDESITE,
            Blocks.POLISHED_DEEPSLATE, Blocks.POLISHED_BLACKSTONE,
            Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE);

    /** Perceptual roughness for this block's surface. */
    public static float roughness(BlockState state) {
        return profile(state).roughness();
    }

    /** Stable, finite heuristic profile used as part of a compiled material key. */
    public static Profile profile(BlockState state) {
        if (state == null) return Profile.DEFAULT;
        SoundType sound = state.getSoundType();
        if (isMetal(sound)) return Profile.METAL;
        if (sound == SoundType.GLASS) return Profile.GLASS;
        if (SMOOTH.contains(state.getBlock())) return Profile.SMOOTH;
        return Profile.DEFAULT;
    }

    /** Metalness (1 = conductor: F0 tinted by albedo, no diffuse; 0 = dielectric). */
    public static float metalness(BlockState state) {
        return profile(state).metalness();
    }

    private static boolean isMetal(SoundType sound) {
        return sound == SoundType.METAL || sound == SoundType.COPPER
                || sound == SoundType.NETHERITE_BLOCK || sound == SoundType.ANVIL
                || sound == SoundType.CHAIN;
    }
}
