package dev.comfyfluffy.caustica.minecraft.client.screen;

import dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftOptions;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaConfig;
import dev.comfyfluffy.caustica.minecraft.client.screen.widget.SettingWidgets;
import dev.comfyfluffy.caustica.minecraft.client.settings.CausticaPages;
import dev.comfyfluffy.caustica.minecraft.client.settings.SettingControl;
import dev.comfyfluffy.caustica.settings.Option;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * The "Ray Tracing" section at the top of vanilla Video Settings: the master switch on a whole row, the
 * everyday renderer rows, then one button per options page. Rows write the shared preference store directly;
 * Video Settings saves it when it closes.
 *
 * <p>While ray tracing is on, the vanilla rows that only steer raster rendering the ray tracer replaces are
 * left out; switching ray tracing reopens Video Settings so they appear or disappear with it. Vanilla's list
 * offers half-row and whole-row widgets only, and one whose caption would not fit half a row takes the whole
 * row so it is never clipped.
 */
public final class CausticaVideoSettings {
    private CausticaVideoSettings() {
    }

    public static void addSection(OptionsList list, VideoSettingsScreen videoSettings, Screen lastScreen) {
        CausticaClientComposition composition = CausticaClientComposition.current();
        var services = composition.apiServices();
        Predicate<Option<?>> available = composition.runtime()::settingAvailable;
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        list.addHeader(Component.translatable("caustica.options.header"));
        List<AbstractWidget> pending = new ArrayList<>();
        for (SettingControl control : CausticaPages.videoSettings(services.settings(), services.options(), available)) {
            if (control.id().equals(CausticaPages.RAY_TRACING)) {
                SettingControl.BoolControl master = reopenOnChange((SettingControl.BoolControl) control,
                        () -> minecraft.gui.setScreen(
                                new VideoSettingsScreen(lastScreen, minecraft, minecraft.options)));
                list.addBig(SettingWidgets.control(master, () -> true, Button.BIG_WIDTH));
                continue;
            }
            add(list, pending, SettingWidgets.control(control, () -> true, Button.DEFAULT_WIDTH),
                    CausticaPageList.captionWidth(control, font::width));
        }
        flush(list, pending);
        for (CausticaPages.Link link : CausticaPages.links(services.settings(), services.options(), available)) {
            add(list, pending, Button.builder(link.title(), button -> minecraft.gui.setScreen(
                                    new CausticaPageScreen(videoSettings, link.page(), services.options())))
                            .tooltip(Tooltip.create(link.tooltip()))
                            .build(),
                    font.width(link.title()) + 2 * SettingWidgets.CAPTION_MARGIN);
        }
        flush(list, pending);
    }

    /**
     * {@code rows} without the vanilla options that only steer raster rendering, while ray tracing is on:
     * smooth lighting, entity shadows, clouds, improved transparency, weather radius, chunk update priority
     * and chunk fade-in, none of which the ray-traced world reads.
     */
    public static OptionInstance<?>[] withoutRasterOnly(OptionInstance<?>[] rows, Options options) {
        if (!CausticaConfig.get(MinecraftOptions.Rt.ENABLED)) {
            return rows;
        }
        List<OptionInstance<?>> rasterOnly = List.of(options.ambientOcclusion(), options.entityShadows(),
                options.cloudStatus(), options.cloudRange(), options.improvedTransparency(), options.weatherRadius(),
                options.prioritizeChunkUpdates(), options.chunkSectionFadeInTime());
        return Arrays.stream(rows).filter(row -> !rasterOnly.contains(row)).toArray(OptionInstance<?>[]::new);
    }

    /** {@code control}, running {@code changed} after each write. */
    private static SettingControl.BoolControl reopenOnChange(SettingControl.BoolControl control, Runnable changed) {
        return new SettingControl.BoolControl() {
            @Override public String id() { return control.id(); }
            @Override public Component label() { return control.label(); }
            @Override public Component tooltip() { return control.tooltip(); }
            @Override public boolean enabled() { return control.enabled(); }
            @Override public boolean get() { return control.get(); }
            @Override public boolean defaultValue() { return control.defaultValue(); }
            @Override public void set(boolean value) {
                control.set(value);
                changed.run();
            }
        };
    }

    private static void add(OptionsList list, List<AbstractWidget> pending, AbstractWidget widget, int needed) {
        if (needed > Button.DEFAULT_WIDTH) {
            flush(list, pending);
            list.addBig(widget);
        } else {
            pending.add(widget);
            if (pending.size() == 2) flush(list, pending);
        }
    }

    private static void flush(OptionsList list, List<AbstractWidget> pending) {
        if (!pending.isEmpty()) {
            list.addSmall(pending);
            pending.clear();
        }
    }
}
