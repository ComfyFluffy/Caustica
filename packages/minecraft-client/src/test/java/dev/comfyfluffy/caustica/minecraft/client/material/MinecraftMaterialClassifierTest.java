package dev.comfyfluffy.caustica.minecraft.client.material;

import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialProfile;
import dev.comfyfluffy.caustica.minecraft.content.material.OpenPbrDefaults;
import dev.comfyfluffy.caustica.settings.ResourceId;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftMaterialClassifierTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void classifiesVanillaIceIorByMaterialName() {
        for (String material : List.of("block/ice", "block/packed_ice", "block/blue_ice",
                "block/frosted_ice_0", "block/frosted_ice_1", "block/frosted_ice_2",
                "block/frosted_ice_3")) {
            assertEquals(MinecraftMaterialClassifier.ICE_IOR,
                    MinecraftMaterialClassifier.dielectricIor(ResourceId.of("minecraft", material)),
                    1.0e-6f, material);
        }
    }

    @Test
    void unknownDielectricsUseTheNeutralTransmissiveDefault() {
        assertEquals(MinecraftMaterialClassifier.GLASS_IOR,
                MinecraftMaterialClassifier.dielectricIor(ResourceId.parse("minecraft:block/glass")),
                1.0e-6f);
        assertEquals(OpenPbrDefaults.TRANSMISSIVE_SPECULAR_IOR,
                MinecraftMaterialClassifier.dielectricIor(ResourceId.parse("somemod:block/weird_crystal")),
                1.0e-6f);
        assertEquals(OpenPbrDefaults.TRANSMISSIVE_SPECULAR_IOR,
                MinecraftMaterialClassifier.dielectricIor(null), 1.0e-6f);
    }

    @Test
    void moddedTexturesWithVanillaPathsKeepTheNeutralDefault() {
        assertEquals(OpenPbrDefaults.TRANSMISSIVE_SPECULAR_IOR,
                MinecraftMaterialClassifier.dielectricIor(ResourceId.of("somemod", "block/ice")));
    }

    @Test
    void blockStateClassificationProducesNeutralProfilesAndGeometryIds() {
        var anvil = MinecraftMaterialClassifier.classify(Blocks.ANVIL.defaultBlockState());
        assertEquals(ResourceId.parse("minecraft:anvil"), anvil.geometry());
        assertEquals(MinecraftMaterialProfile.CONDUCTOR, anvil.profile());

        var quartz = MinecraftMaterialClassifier.classify(Blocks.SMOOTH_QUARTZ.defaultBlockState());
        assertEquals(MinecraftMaterialProfile.POLISHED_DIELECTRIC, quartz.profile());
    }

    @Test
    void torchFlamesRadiateAtTheTorchMultipleOfBlockEmission() {
        for (String torch : List.of("torch", "soul_torch", "copper_torch")) {
            assertTrue(net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(
                    net.minecraft.resources.Identifier.withDefaultNamespace(torch)), torch);
            assertEquals(MinecraftMaterialClassifier.TORCH_EMISSION_SCALE,
                    MinecraftMaterialClassifier.emissionScale(ResourceId.of("minecraft", "block/" + torch)));
        }
        assertEquals(1.0f, MinecraftMaterialClassifier.emissionScale(ResourceId.parse("minecraft:block/glowstone")));
        assertEquals(1.0f, MinecraftMaterialClassifier.emissionScale(ResourceId.of("somemod", "block/torch")));
    }

    @Test
    void dielectricDefaultsKeepIceBelowWater() {
        assertTrue(MinecraftMaterialClassifier.ICE_IOR < MinecraftMaterialClassifier.WATER_IOR);
        assertEquals(MinecraftMaterialClassifier.WATER_IOR,
                OpenPbrDefaults.TRANSMISSIVE_SPECULAR_IOR, 1.0e-6f);
    }

    @Test
    void glassCoverageFollowsBlockSemanticsRatherThanRasterLayers() {
        for (var block : List.of(Blocks.GLASS, Blocks.GLASS_PANE, Blocks.TINTED_GLASS)) {
            assertTrue(MinecraftMaterialClassifier.isGlass(block.defaultBlockState()));
        }
        var stained = net.minecraft.core.registries.BuiltInRegistries.BLOCK.stream()
                .filter(block -> block instanceof net.minecraft.world.level.block.StainedGlassBlock
                        || block instanceof net.minecraft.world.level.block.StainedGlassPaneBlock).toList();
        assertFalse(stained.isEmpty());
        for (var block : stained) assertTrue(MinecraftMaterialClassifier.isGlass(block.defaultBlockState()));
        for (var block : List.of(Blocks.IRON_BARS, Blocks.OAK_LEAVES, Blocks.SEA_LANTERN)) {
            assertFalse(MinecraftMaterialClassifier.isGlass(block.defaultBlockState()));
        }
        assertEquals(1.5f, MinecraftMaterialClassifier.dielectricIor(
                ResourceId.parse("minecraft:block/red_stained_glass_pane_top")));
    }
}
