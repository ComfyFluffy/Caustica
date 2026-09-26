package dev.comfyfluffy.caustica.minecraft.client.settings;

import dev.comfyfluffy.caustica.minecraft.client.EnglishText;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftOptions;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftProvidersExtension;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaConfig;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaOptions;
import dev.comfyfluffy.caustica.renderer.presentation.RtToneMapping;
import dev.comfyfluffy.caustica.renderer.presentation.fog.FogPass;
import dev.comfyfluffy.caustica.renderer.runtime.RendererOptions;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class OptionControlsTest {
    private static final ResourceId FEATURE = ResourceId.of("test", "controls");
    @TempDir Path directory;

    private CausticaOptions store(Option<?>... options) {
        SettingsRegistry registry = new SettingsRegistry();
        registry.feature(FEATURE).options(List.of(options)).register();
        return CausticaOptions.load(directory.resolve("options.toml"), registry);
    }

    @Test
    void numericRowsWriteTypedValuesAndKeepTheDeclaredSliderSpan() {
        var count = Option.integer("count", 0, 100, 4).sliderRange(2, 8);
        var gain = Option.range("gain", 0, 100, 1).sliderRange(0, 2);
        var store = store(count, gain);
        var countRow = (SettingControl.RangeControl) OptionControls.of(store, FEATURE, count);
        var gainRow = (SettingControl.RangeControl) OptionControls.of(store, FEATURE, gain);

        countRow.set(6.6);
        gainRow.set(1.5);
        assertEquals(7, store.options(FEATURE).get(count));
        assertEquals(1.5f, store.options(FEATURE).get(gain));
        assertEquals(2, countRow.sliderMinimum());
        assertEquals(8, countRow.sliderMaximum());
        assertEquals(1, countRow.step());
        assertEquals(0, gainRow.step());
        gainRow.set(40);
        assertEquals(40, gainRow.get());
        assertEquals(1, gainRow.toSlider(gainRow.get()));
        countRow.reset();
        assertEquals(4, countRow.get());
    }

    @Test
    void choicesUseTheSameAdapterForRendererAndExtensions() {
        var quality = Option.intChoice("quality", 2, List.of(1, 2, 3));
        var mode = Option.stringChoice("mode", "auto", List.of("auto", "manual"));
        SettingsRegistry registry = new SettingsRegistry();
        registry.feature(CausticaConfig.FEATURE).option(quality).register();
        registry.feature(FEATURE).option(mode).register();
        var store = CausticaOptions.load(directory.resolve("choices.toml"), registry);
        var qualityRow = choice(OptionControls.of(store, CausticaConfig.FEATURE, quality), Integer.class);
        var modeRow = choice(OptionControls.of(store, FEATURE, mode), String.class);

        qualityRow.set(3);
        modeRow.set("manual");
        assertEquals(3, qualityRow.get());
        assertEquals("manual", modeRow.get());
        assertEquals(List.of(1, 2, 3), qualityRow.choices());
        assertEquals("caustica.setting.quality.3", key(qualityRow.labelOf(3)));
        assertEquals("caustica.option.test.controls.mode.manual", key(modeRow.labelOf("manual")));
    }

    @Test
    void availabilityIsSuppliedByTheHostAndDoesNotChangeTheRowLayout() {
        var option = Option.bool("enabled", true);
        var available = new AtomicBoolean(false);
        var row = (SettingControl.BoolControl) OptionControls.of(store(option), FEATURE, option,
                ignored -> available.get());
        assertFalse(row.enabled());
        available.set(true);
        assertTrue(row.enabled());
        row.set(false);
        assertFalse(row.get());
    }

    @Test
    void resetPageLeavesTemporaryOverridesAndTheirStoredPreferencesAlone() {
        var option = Option.bool("override", false).storage("override", "caustica.test.ui.override");
        String previous = System.getProperty(option.systemPropertyKey());
        try {
            System.setProperty(option.systemPropertyKey(), "true");
            var store = store(option);
            store.apply(FEATURE, option, true);
            store.save();
            var row = (SettingControl.BoolControl) OptionControls.of(store, FEATURE, option);
            var page = new SettingsPage("test", Component.empty(),
                    List.of(new SettingGroup("test", Component.empty(), List.of(row))));
            assertTrue(row.get());
            assertFalse(row.enabled());
            page.reset();
            store.save();
            assertTrue(row.get());
            System.clearProperty(option.systemPropertyKey());
            assertTrue(store(option).options(FEATURE).get(option));
        } finally {
            if (previous == null) System.clearProperty(option.systemPropertyKey());
            else System.setProperty(option.systemPropertyKey(), previous);
        }
    }

    @Test
    void optionalPathsDoNotCreateAnUnsupportedRow() {
        var path = Option.optionalString("path");
        assertNull(OptionControls.of(store(path), FEATURE, path));
    }

    @SuppressWarnings("unchecked")
    private static <T> SettingControl.ChoiceControl<T> choice(SettingControl control, Class<T> type) {
        assertInstanceOf(SettingControl.ChoiceControl.class, control);
        return (SettingControl.ChoiceControl<T>) control;
    }

    private static String key(Component component) {
        return ((TranslatableContents) component.getContents()).getKey();
    }

    /** Resolves through the shipped English file, so the templates are checked as Minecraft formats them. */
    @Test
    void numbersReadThroughTheirLangTemplatesAndNamedValues() {
        try (EnglishText ignored = EnglishText.install()) {
            SettingsRegistry registry = new SettingsRegistry();
            MinecraftOptions.register(registry);
            registry.feature(MinecraftProvidersExtension.ID).group(FogPass.GROUP).options(FogPass.OPTIONS).register();
            var store = CausticaOptions.load(directory.resolve("format.toml"), registry);
            var renderer = CausticaConfig.FEATURE;
            assertEquals("95%", format(store, renderer, RendererOptions.Rt.Exposure.HIGH_PERCENTILE, 0.95));
            assertEquals("1.5 EV", format(store, renderer, RendererOptions.Rt.Exposure.MANUAL_EV, 1.5));
            assertEquals("EV False Color", format(store, renderer, RendererOptions.Rt.Composite.DEBUG_VIEW, 8));
            assertEquals("1.25", format(store, renderer, RendererOptions.Rt.Tonemap.GAMMA, 1.25));
            assertEquals("Transmittance", format(store, MinecraftProvidersExtension.ID, FogPass.DEBUG, 1));
            assertEquals("64", format(store, MinecraftProvidersExtension.ID, FogPass.SAMPLES, 64));
            var psychov30 = RendererOptions.Rt.Tonemap.SDR_CONTROLS.get(RtToneMapping.SdrMode.PSYCHOV30).getFirst();
            assertEquals("Automatic", format(store, renderer, psychov30, 0));
            assertEquals("1.2", format(store, renderer, psychov30, 1.2));
            assertEquals("200 nits", format(store, renderer, RendererOptions.Rt.Tonemap.PAPER_WHITE_NITS, 200));
        }
    }

    private static String format(CausticaOptions store, ResourceId feature, Option<?> option, double value) {
        var row = (SettingControl.RangeControl) OptionControls.of(store, feature, option);
        return row.format(value).getString();
    }
}
