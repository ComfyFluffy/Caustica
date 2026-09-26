package dev.comfyfluffy.caustica.minecraft.client.screen;

import dev.comfyfluffy.caustica.minecraft.client.EnglishText;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftOptions;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftProvidersExtension;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaConfig;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaOptions;
import dev.comfyfluffy.caustica.minecraft.client.screen.widget.SettingWidgets;
import dev.comfyfluffy.caustica.minecraft.client.settings.CausticaPages;
import dev.comfyfluffy.caustica.minecraft.client.settings.SettingControl;
import dev.comfyfluffy.caustica.minecraft.client.settings.SettingsPage;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.SkyLutPass;
import dev.comfyfluffy.caustica.renderer.presentation.bloom.BloomExtension;
import dev.comfyfluffy.caustica.renderer.presentation.fog.FogPass;
import dev.comfyfluffy.caustica.renderer.runtime.RendererOptions;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Captions are measured with the default font's real glyph widths in English, the only maintained locale:
 * a caption that scrolls inside its control is cut off at both ends in a still frame.
 */
final class CaptionFitTest {
    /** A 1280x720 window at GUI scale 3, the narrowest layout the pages are expected to hold. */
    private static final int ROW_WIDTH = CausticaPageList.rowWidth(427);
    private static final Predicate<Option<?>> ALL = ignored -> true;

    @TempDir Path directory;

    private SettingsRegistry registry() {
        SettingsRegistry registry = new SettingsRegistry();
        MinecraftOptions.register(registry);
        new BloomExtension().registerSettings(registry);
        registry.feature(MinecraftProvidersExtension.ID).group(SkyLutPass.GROUP).options(SkyLutPass.OPTIONS)
                .group(FogPass.GROUP).options(FogPass.OPTIONS).register();
        return registry;
    }

    /** Every page in every HDR, tone-mapper and denoiser state. */
    private static List<SettingsPage> pages(SettingsRegistry registry, CausticaOptions store) {
        List<CausticaPages.Link> links = CausticaPages.links(registry, store, ALL);
        List<SettingsPage> pages = new ArrayList<>();
        for (boolean hdr : List.of(false, true)) {
            store.apply(CausticaConfig.FEATURE, RendererOptions.Rt.Hdr.ENABLED, hdr);
            Option<String> selector = hdr ? RendererOptions.Rt.Tonemap.HDR_MAPPER
                    : RendererOptions.Rt.Tonemap.SDR_MAPPER;
            for (String mapper : selector.choices()) {
                store.apply(CausticaConfig.FEATURE, selector, mapper);
                links.forEach(link -> pages.add(link.page().get()));
            }
        }
        for (Object route : RendererOptions.Rt.Denoising.ROUTE.choices()) {
            store.apply(CausticaConfig.FEATURE, RendererOptions.Rt.Denoising.ROUTE, route);
            links.forEach(link -> pages.add(link.page().get()));
        }
        return pages;
    }

    @Test
    void everyPageCaptionFitsAtLeastAWholeRow() {
        try (EnglishText ignored = EnglishText.install()) {
            SettingsRegistry registry = registry();
            CausticaOptions store = CausticaOptions.load(directory.resolve("pages.toml"), registry);
            List<String> clipped = new ArrayList<>();
            for (SettingsPage page : pages(registry, store)) {
                for (SettingControl control : page.allControls()) {
                    int needed = CausticaPageList.captionWidth(control, EnglishText::width);
                    if (needed > CausticaPageList.controlWidth(ROW_WIDTH, true)) {
                        clipped.add(page.id() + " " + control.id() + ": " + widest(control));
                    }
                }
            }
            assertTrue(clipped.isEmpty(), "captions wider than a whole row: " + clipped);
        }
    }

    /** Vanilla's list gives these half a row at its fixed width; none should need its whole-row fallback. */
    @Test
    void videoSettingsRowsAndPageButtonsFitHalfAVanillaRow() {
        try (EnglishText ignored = EnglishText.install()) {
            SettingsRegistry registry = registry();
            CausticaOptions store = CausticaOptions.load(directory.resolve("video.toml"), registry);
            List<String> wide = new ArrayList<>();
            for (SettingControl control : CausticaPages.videoSettings(registry, store, ALL)) {
                if (CausticaPageList.captionWidth(control, EnglishText::width) > Button.DEFAULT_WIDTH) {
                    wide.add(widest(control));
                }
            }
            for (CausticaPages.Link link : CausticaPages.links(registry, store, ALL)) {
                Component label = link.title();
                if (EnglishText.width(label) + 2 * SettingWidgets.CAPTION_MARGIN > Button.DEFAULT_WIDTH) {
                    wide.add(label.getString());
                }
            }
            assertTrue(wide.isEmpty(), "wider than half a vanilla row: " + wide);
        }
    }

    private static String widest(SettingControl control) {
        return SettingWidgets.captions(control).stream()
                .max(Comparator.comparingInt(EnglishText::width)).orElseThrow().getString();
    }
}
