package dev.comfyfluffy.caustica.minecraft.client.settings;

import dev.comfyfluffy.caustica.minecraft.client.config.CausticaConfig;
import dev.comfyfluffy.caustica.settings.Option;
import net.minecraft.network.chat.Component;
import dev.comfyfluffy.caustica.settings.ResourceId;

/**
 * Every translation key the settings screen uses, derived rather than declared. An extension gets labelled
 * rows by registering options and adding lang entries — there is no place to hand the engine a label, which
 * is what keeps {@code Option} free of display text.
 *
 * <p>{@code Component.translatable} renders a missing key verbatim, so a gap shows up in-game as the key
 * itself rather than as blank space.
 */
public final class LangKeys {
    private LangKeys() {
    }

    /** {@code caustica.group.engine.<groupId>}. */
    public static Component engineGroup(String groupId) {
        return Component.translatable("caustica.group.engine." + groupId);
    }

    /**
     * Renderer options use {@code caustica.setting.<optionId>}; extensions use
     * {@code caustica.option.<namespace>.<path>.<optionId>}.
     */
    public static Component optionLabel(ResourceId featureId, Option<?> option) {
        return Component.translatable(optionKey(featureId, option));
    }

    public static Component optionTooltip(ResourceId featureId, Option<?> option) {
        return Component.translatable(optionKey(featureId, option) + ".tooltip");
    }

    public static Component optionChoice(ResourceId featureId, Option<?> option, Object value) {
        return Component.translatable(optionKey(featureId, option) + "." + value);
    }

    /**
     * A numeric value as a row shows it. {@code <option key>.value.<number>} names one exact value, such as a
     * debug view. Otherwise {@code <option key>.value} is a template that receives the number and the
     * number as a percentage ({@code "%s EV"}, {@code "%2$s%%"}); an option with neither shows the number.
     */
    public static Component optionValue(ResourceId featureId, Option<?> option, String number, String percent) {
        String key = optionKey(featureId, option) + ".value";
        return Component.translatableWithFallback(key + "." + number, "%s",
                Component.translatableWithFallback(key, "%s", number, percent));
    }

    private static String optionKey(ResourceId featureId, Option<?> option) {
        if (featureId.equals(CausticaConfig.FEATURE)) return "caustica.setting." + option.id();
        return "caustica.option." + featureId.namespace() + "." + featureId.path() + "." + option.id();
    }

    /** {@code caustica.group.<namespace>.<path>.<groupId>}. */
    public static Component optionGroup(ResourceId featureId, String groupId) {
        return Component.translatable(
                "caustica.group." + featureId.namespace() + "." + featureId.path() + "." + groupId);
    }

}
