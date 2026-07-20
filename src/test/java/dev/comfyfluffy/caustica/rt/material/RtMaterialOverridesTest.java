package dev.comfyfluffy.caustica.rt.material;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtMaterialOverridesTest {
    @Test
    void parsesVersionedExtensibleMaterialProperties() {
        var rule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":1,"match":{"block":"minecraft:blue_stained_glass",
                "sprite":"minecraft:block/blue_stained_glass"},"model":"thin_dielectric",
                "base":{"roughness":0.06,"metalness":0.0},
                "emission":{"strength":0.0,"color_source":"albedo"},
                "transmission":{"factor":1.0,"ior":1.52}}
                """).getAsJsonObject(), Identifier.parse("test:caustica/materials/glass.json"));
        assertEquals(Identifier.parse("minecraft:block/blue_stained_glass"), rule.sprite());
        assertEquals(RtMaterialRegistry.MODEL_GLASS, rule.model());
        assertEquals(1.52f, rule.ior());
        assertEquals(0.0f, rule.emissionStrength());
        RtMaterialDesc base = new RtMaterialDesc(RtMaterialRegistry.MODEL_OPAQUE,
                RtMaterialDesc.Source.LAB_PBR, RtMaterialRegistry.FEATURE_SPEC,
                0.8f, 0.0f, 1.0f, 0.0f, RtMaterialDesc.EmissionSource.LAB_PBR,
                1.0f, new RtMaterialDesc.EmissionSummary(0.2f, 0.1f, 0.05f, 0.1f, 0.5f));
        RtMaterialDesc applied = rule.apply(base, RtMaterialDesc.EmissionSummary.NONE);
        assertEquals(RtMaterialDesc.Source.OVERRIDE, applied.source());
        assertEquals(RtMaterialDesc.EmissionSource.NONE, applied.emissionSource());
        assertEquals(0.06f, applied.roughness());
        assertEquals(1.0f, applied.transmission());
    }

    @Test
    void rejectsUnknownVersionsAndOutOfRangePhysicalValues() {
        assertThrows(IllegalArgumentException.class, () -> RtMaterialOverrides.parse(
                JsonParser.parseString("{\"format\":2,\"match\":{\"sprite\":\"minecraft:block/stone\"}}")
                        .getAsJsonObject(), Identifier.parse("test:bad.json")));
        assertThrows(IllegalArgumentException.class, () -> RtMaterialOverrides.parse(
                JsonParser.parseString("{\"format\":1,\"match\":{\"sprite\":\"minecraft:block/stone\"},"
                        + "\"base\":{\"metalness\":2}}")
                        .getAsJsonObject(), Identifier.parse("test:bad.json")));
    }

    @Test
    void spriteWideRulesApplyToCompiledEntityResources() {
        var entityRule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":1,"match":{"sprite":"minecraft:entity/zombie/zombie"},
                "base":{"roughness":0.7}}
                """).getAsJsonObject(), Identifier.parse("test:entity.json"));
        var blockRule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":1,"match":{"sprite":"minecraft:entity/zombie/zombie",
                "block":"minecraft:stone"}}
                """).getAsJsonObject(), Identifier.parse("test:block.json"));

        assertTrue(entityRule.matchesEntity(Identifier.parse("minecraft:entity/zombie/zombie")));
        assertFalse(entityRule.matchesEntity(Identifier.parse("minecraft:entity/zombie/husk")));
        assertFalse(blockRule.matchesEntity(Identifier.parse("minecraft:entity/zombie/zombie")));
    }
}
