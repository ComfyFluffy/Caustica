package dev.comfyfluffy.caustica.minecraft.client.settings;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftProvidersExtension;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaConfig;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftOptions;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaOptions;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.SkyLutPass;
import dev.comfyfluffy.caustica.renderer.presentation.bloom.BloomExtension;
import dev.comfyfluffy.caustica.renderer.presentation.fog.FogPass;
import dev.comfyfluffy.caustica.settings.FeatureSettings;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every key the screen derives has to exist in {@code en_us.json}, because a missing one renders as the raw
 * key in-game rather than failing anywhere. The keys are derived and the entries are hand-written, so this
 * is the seam where the two drift apart.
 */
final class LangKeysTest {
    @TempDir Path configDir;
    private static JsonObject lang() throws IOException {
        try (InputStream stream = LangKeysTest.class.getResourceAsStream("/assets/caustica/lang/en_us.json")) {
            assertNotNull(stream, "en_us.json is not on the test classpath");
            return JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** The key a translatable component would look up, so the test checks what the screen actually renders. */
    private static String keyOf(Component component) {
        return ((TranslatableContents) component.getContents()).getKey();
    }

    private final List<String> checked = new ArrayList<>();

    private void require(JsonObject lang, List<String> missing, Component component) {
        String key = keyOf(component);
        checked.add(key);
        if (!lang.has(key)) {
            missing.add(key);
        }
    }

    @Test
    void everyDerivedKeyHasAnEnglishEntry() throws IOException {
        JsonObject lang = lang();
        SettingsRegistry registry = new SettingsRegistry();
        registry.feature(ResourceId.of("caustica", "bloom"))
                .group("bloom")
                .option(Option.bool("bloom.enabled", true).inGroupAsHeader("bloom"))
                .option(Option.range("bloom.strength", 0.0f, 2.0f, 0.35f).inGroup("bloom"))
                .register();
        List<String> missing = new ArrayList<>();

        for (Option<?> setting : MinecraftOptions.allSettings()) {
            if (setting.group() == null) {
                continue;
            }
            require(lang, missing, LangKeys.optionLabel(CausticaConfig.FEATURE, setting));
            require(lang, missing, LangKeys.optionTooltip(CausticaConfig.FEATURE, setting));
            setting.choices().forEach(choice ->
                    require(lang, missing, LangKeys.optionChoice(CausticaConfig.FEATURE, setting, choice)));
        }

        for (FeatureSettings feature : registry.all()) {
            for (Option<?> option : feature.options()) {
                require(lang, missing, LangKeys.optionLabel(feature.id(), option));
                require(lang, missing, LangKeys.optionTooltip(feature.id(), option));
            }
            for (String group : feature.optionGroups()) {
                require(lang, missing, LangKeys.optionGroup(feature.id(), group));
            }
        }

        assertTrue(missing.isEmpty(), "missing en_us.json entries: " + missing);
        // Guards against the loops above silently iterating nothing and passing vacuously.
        assertTrue(checked.size() > 20, "only checked " + checked.size() + " keys");
        assertTrue(checked.contains("caustica.setting.composite.max-bounces"), checked.toString());
        assertTrue(checked.contains("caustica.option.caustica.bloom.bloom.strength"), checked.toString());
    }

    /** The shell's own strings, which no derivation reaches. */
    @Test
    void theScreenChromeHasEntries() throws IOException {
        JsonObject lang = lang();
        List<String> missing = new ArrayList<>();
        for (String key : List.of(
                "caustica.options.header", "caustica.page.reset", "caustica.page.reset.tooltip",
                "caustica.page.reset_page", "caustica.page.reset_page.tooltip",
                "caustica.page.section.show_advanced", "caustica.page.section.hide_advanced",
                "caustica.group.other")) {
            if (!lang.has(key)) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "missing en_us.json entries: " + missing);
    }

    /**
     * Pages also show ungrouped renderer options and one mapper's and one denoiser's rows at a time, so this walks
     * every page in every HDR, tone-mapper and denoiser state rather than the declarations alone.
     */
    @Test
    void everyPageRowAndHeadingHasAnEnglishEntry() throws IOException {
        JsonObject lang = lang();
        List<String> missing = new ArrayList<>();
        SettingsRegistry registry = new SettingsRegistry();
        MinecraftOptions.register(registry);
        new BloomExtension().registerSettings(registry);
        registry.feature(MinecraftProvidersExtension.ID).group(SkyLutPass.GROUP).options(SkyLutPass.OPTIONS)
                .group(FogPass.GROUP).options(FogPass.OPTIONS).register();
        CausticaOptions options = CausticaOptions.load(configDir.resolve("pages.toml"), registry);
        CausticaPages.Engine engine = new CausticaPages.Engine(registry, options, ignored -> true);

        List<SettingsPage> pages = new ArrayList<>(CausticaPagesTest.everyPageState(engine));
        List<CausticaPages.Link> links = CausticaPages.links(registry, options, ignored -> true);
        links.forEach(link -> pages.add(link.page().get()));
        for (SettingsPage page : pages) {
            for (SettingGroup section : page.sections()) {
                require(lang, missing, section.title());
                for (SettingControl control : section.rows()) {
                    require(lang, missing, control.label());
                    require(lang, missing, control.tooltip());
                }
            }
        }
        for (SettingControl control : CausticaPages.videoSettings(registry, options, ignored -> true)) {
            require(lang, missing, control.label());
            require(lang, missing, control.tooltip());
        }
        for (CausticaPages.Link link : links) {
            if (link.title().getContents() instanceof TranslatableContents) {
                require(lang, missing, link.title());
                require(lang, missing, link.tooltip());
            }
        }

        assertTrue(missing.isEmpty(), "missing en_us.json entries: " + missing);
        assertTrue(checked.contains("caustica.setting.exposure.adapt-darken.tooltip"), checked.toString());
        assertTrue(checked.contains("caustica.page.upscaling.nrd"), checked.toString());
        assertTrue(checked.contains("caustica.page.tone-mapping.mapper"), checked.toString());
        assertTrue(checked.contains("caustica.setting.hdr.psychov69.source-awareness.tooltip"), checked.toString());
        assertTrue(checked.contains("caustica.group.engine.entities"), checked.toString());
        assertTrue(checked.contains("caustica.group.caustica.minecraft.fog"), checked.toString());
        assertTrue(checked.contains("feature.caustica.bloom.description"), checked.toString());
        assertTrue(checked.contains("caustica.page.radiance-cache.tooltip"), checked.toString());
        assertTrue(checked.contains("caustica.setting.sharc.anti-firefly.tooltip"), checked.toString());
    }
}
