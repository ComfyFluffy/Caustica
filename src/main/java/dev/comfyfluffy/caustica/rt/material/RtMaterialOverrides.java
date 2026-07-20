package dev.comfyfluffy.caustica.rt.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.CausticaMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.state.BlockState;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Optional resource-pack material properties compiled ahead of LabPBR and engine heuristics. */
public final class RtMaterialOverrides {
    public static final int FORMAT = 1;
    public static final RtMaterialOverrides EMPTY = new RtMaterialOverrides(List.of());

    private final List<Rule> rules;

    private RtMaterialOverrides(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static RtMaterialOverrides load() {
        Map<Identifier, Resource> resources = Minecraft.getInstance().getResourceManager().listResources(
                "caustica/materials", id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> ordered = new ArrayList<>(resources.entrySet());
        ordered.sort(Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString)));
        List<Rule> rules = new ArrayList<>();
        for (Map.Entry<Identifier, Resource> entry : ordered) {
            try (Reader reader = entry.getValue().openAsReader()) {
                rules.add(parse(JsonParser.parseReader(reader).getAsJsonObject(), entry.getKey()));
            } catch (Throwable throwable) {
                CausticaMod.LOGGER.warn("Ignoring invalid RT material override {}", entry.getKey(), throwable);
            }
        }
        // More-specific block+sprite rules win over sprite-wide rules. Ties use the resource identifier,
        // while the resource manager has already selected the highest-priority pack for each identifier.
        rules.sort(Comparator.comparing((Rule rule) -> rule.block() == null)
                .thenComparing(rule -> rule.source().toString()));
        CausticaMod.LOGGER.info("RT material overrides: format={}, rules={}", FORMAT, rules.size());
        return rules.isEmpty() ? EMPTY : new RtMaterialOverrides(rules);
    }

    static Rule parse(JsonObject root, Identifier source) {
        int format = requiredInt(root, "format");
        if (format != FORMAT) throw new IllegalArgumentException("Unsupported material format " + format);
        JsonObject match = requiredObject(root, "match");
        Identifier sprite = Identifier.parse(requiredString(match, "sprite"));
        Identifier block = match.has("block") ? Identifier.parse(match.get("block").getAsString()) : null;

        Integer model = null;
        if (root.has("model")) {
            model = switch (root.get("model").getAsString()) {
                case "opaque" -> RtMaterialRegistry.MODEL_OPAQUE;
                case "volume_dielectric" -> RtMaterialRegistry.MODEL_WATER;
                case "thin_dielectric" -> RtMaterialRegistry.MODEL_GLASS;
                default -> throw new IllegalArgumentException("Unknown material model");
            };
        }
        Float roughness = null;
        Float metalness = null;
        if (root.has("base")) {
            JsonObject base = root.getAsJsonObject("base");
            roughness = optionalFloat(base, "roughness");
            metalness = optionalFloat(base, "metalness");
        }
        Float emissionStrength = null;
        if (root.has("emission")) {
            JsonObject emission = root.getAsJsonObject("emission");
            emissionStrength = optionalFloat(emission, "strength");
            if (emission.has("color_source") && !"albedo".equals(emission.get("color_source").getAsString())) {
                throw new IllegalArgumentException("format 1 only supports emission color_source=albedo");
            }
        }
        Float transmission = null;
        Float ior = null;
        if (root.has("transmission")) {
            JsonObject value = root.getAsJsonObject("transmission");
            transmission = optionalFloat(value, "factor");
            ior = optionalFloat(value, "ior");
        }
        validate01("roughness", roughness);
        validate01("metalness", metalness);
        validate01("transmission.factor", transmission);
        if (ior != null && (!Float.isFinite(ior) || ior <= 0.0f)) {
            throw new IllegalArgumentException("transmission.ior must be positive");
        }
        if (emissionStrength != null && (!Float.isFinite(emissionStrength)
                || emissionStrength < 0.0f || emissionStrength > 5.0f)) {
            throw new IllegalArgumentException("emission.strength must be in [0,5]");
        }
        return new Rule(source, sprite, block, model, roughness, metalness, ior, transmission,
                emissionStrength);
    }

    public List<Rule> rules() {
        return rules;
    }

    public record Rule(Identifier source, Identifier sprite, Identifier block, Integer model,
                       Float roughness, Float metalness, Float ior, Float transmission,
                       /**
                        * Multiplier on whatever emission the material naturally resolves to (LabPBR
                        * {@code _s}, heuristic mask, or state-uniform block light) — NOT a replacement.
                        * A material with no natural emission stays unlit no matter this value; this
                        * cannot make a block glow that wasn't already emissive.
                        */
                       Float emissionStrength) {
        boolean matchesSprite(TextureAtlasSprite value) {
            return value != null && sprite.equals(value.contents().name());
        }

        boolean matchesEntity(Identifier value) {
            return block == null && sprite.equals(value);
        }

        boolean matches(TextureAtlasSprite value, BlockState state) {
            if (!matchesSprite(value)) return false;
            return block == null || state != null && block.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        }

        RtMaterialDesc apply(RtMaterialDesc base) {
            int nextModel = model != null ? model : base.model();
            float nextRoughness = roughness != null ? roughness : base.roughness();
            float nextMetalness = metalness != null ? metalness : base.metalness();
            float nextIor = ior != null ? ior
                    : (model != null ? defaultIor(nextModel) : base.ior());
            float nextTransmission = transmission != null ? transmission
                    : (model != null ? defaultTransmission(nextModel) : base.transmission());
            // A multiplier on the base's already-resolved strength (0 when emissionSource is NONE):
            // this can brighten/dim an existing emitter but never light up a genuinely non-emissive one.
            float nextEmissionStrength = emissionStrength != null
                    ? base.emissionStrength() * emissionStrength : base.emissionStrength();
            return new RtMaterialDesc(nextModel, RtMaterialDesc.Source.OVERRIDE, base.features(),
                    nextRoughness, nextMetalness, nextIor, nextTransmission,
                    base.emissionSource(), nextEmissionStrength, base.emissionSummary());
        }

        private static float defaultIor(int model) {
            return model == RtMaterialRegistry.MODEL_WATER ? 1.333f
                    : model == RtMaterialRegistry.MODEL_GLASS ? 1.52f : 1.0f;
        }

        private static float defaultTransmission(int model) {
            return model == RtMaterialRegistry.MODEL_WATER || model == RtMaterialRegistry.MODEL_GLASS
                    ? 1.0f : 0.0f;
        }
    }

    private static JsonObject requiredObject(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonObject()) throw new IllegalArgumentException("Missing object " + name);
        return element.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) throw new IllegalArgumentException("Missing string " + name);
        return element.getAsString();
    }

    private static int requiredInt(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) throw new IllegalArgumentException("Missing integer " + name);
        return element.getAsInt();
    }

    private static Float optionalFloat(JsonObject object, String name) {
        return object.has(name) ? object.get(name).getAsFloat() : null;
    }

    private static void validate01(String name, Float value) {
        if (value != null && (!Float.isFinite(value) || value < 0.0f || value > 1.0f)) {
            throw new IllegalArgumentException(name + " must be in [0,1]");
        }
    }
}
