package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtSharcCache;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** Dedicated SHaRC controls for the directional-SH runtime that this jar actually packages. */
public final class RtSharcOptionsScreen extends OptionsSubScreen {
    private final Screen parentScreen;

    public RtSharcOptionsScreen(Screen parentScreen) {
        super(parentScreen, Minecraft.getInstance().options,
                Component.translatable("caustica.options.rt.sharcMenu.title"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void addOptions() {
        list.addHeader(Component.translatable("caustica.options.rt.sharcMenu.runtimeHeader"));
        list.addSmall(sharcEnabled(), cacheExponent());
        list.addSmall(primarySurfaceDebug(), antiFirefly());
        list.addSmall(updateTileSize(), accumulationFrames());
        list.addSmall(staleFrames(), sceneScale());
        list.addSmall(radianceScale(), roughnessThreshold());
        list.addSmall(gridLogarithmBase(), gridLevelBias());

        list.addHeader(Component.translatable("caustica.options.rt.sharcMenu.statusHeader"));
        list.addSmall(List.of(disabledButton(Component.translatable(
                "caustica.options.rt.sharcMenu.status.runtime", RtComposite.INSTANCE.sharcStatus()))));
        long memoryMiB = RtSharcCache.memoryBytesForExponent(CausticaConfig.Rt.Sharc.CACHE_EXPONENT.value())
                / (1024L * 1024L);
        list.addSmall(List.of(disabledButton(Component.translatable(
                "caustica.options.rt.sharcMenu.status.memory", memoryMiB))));
        list.addSmall(List.of(disabledButton(Component.translatable(
                "caustica.options.rt.sharcMenu.status.layout"))));

        list.addHeader(Component.translatable("caustica.options.rt.sharcMenu.actionsHeader"));
        list.addSmall(List.of(
                Button.builder(Component.translatable("caustica.options.rt.sharcMenu.clear"), button -> {
                    RtComposite.INSTANCE.requestSharcReset();
                    Minecraft.getInstance().setScreenAndShow(new RtSharcOptionsScreen(parentScreen));
                }).build(),
                Button.builder(Component.translatable("caustica.options.rt.sharcMenu.defaults"), button -> {
                    restoreDefaults();
                    Minecraft.getInstance().setScreenAndShow(new RtSharcOptionsScreen(parentScreen));
                }).build()));
    }

    @Override
    public void removed() {
        CausticaConfig.save();
        super.removed();
    }

    private static OptionInstance<Boolean> sharcEnabled() {
        return bool("caustica.options.rt.sharcEnabled", CausticaConfig.Rt.Sharc.ENABLED);
    }

    private static OptionInstance<Integer> cacheExponent() {
        IntSetting setting = CausticaConfig.Rt.Sharc.CACHE_EXPONENT;
        return integer("caustica.options.rt.sharcCacheExponent", 16, 23, setting.value(), setting::set,
                value -> "2^" + value);
    }

    private static OptionInstance<Boolean> primarySurfaceDebug() {
        return bool("caustica.options.rt.sharcPrimarySurfaceDebug",
                CausticaConfig.Rt.Sharc.PRIMARY_SURFACE_DEBUG);
    }

    private static OptionInstance<Boolean> antiFirefly() {
        return bool("caustica.options.rt.sharcAntiFirefly", CausticaConfig.Rt.Sharc.ANTI_FIREFLY);
    }

    private static OptionInstance<Integer> updateTileSize() {
        IntSetting setting = CausticaConfig.Rt.Sharc.UPDATE_TILE_SIZE;
        return integer("caustica.options.rt.sharcUpdateTileSize", 2, 64, setting.value(), setting::set,
                value -> value + "x" + value);
    }

    private static OptionInstance<Integer> accumulationFrames() {
        IntSetting setting = CausticaConfig.Rt.Sharc.ACCUMULATION_FRAMES;
        return integer("caustica.options.rt.sharcAccumulationFrames", 1, 1024, setting.value(), setting::set,
                Object::toString);
    }

    private static OptionInstance<Integer> staleFrames() {
        IntSetting setting = CausticaConfig.Rt.Sharc.STALE_FRAMES;
        return integer("caustica.options.rt.sharcStaleFrames", 8, 1024, setting.value(), setting::set,
                Object::toString);
    }

    private static OptionInstance<Integer> sceneScale() {
        FloatSetting setting = CausticaConfig.Rt.Sharc.SCENE_SCALE;
        return integer("caustica.options.rt.sharcSceneScale", 100, 10000,
                Math.round(setting.value() * 100.0f), value -> setting.set(value / 100.0f),
                value -> String.format(Locale.ROOT, "%.2f", value / 100.0f));
    }

    private static OptionInstance<Integer> radianceScale() {
        FloatSetting setting = CausticaConfig.Rt.Sharc.RADIANCE_SCALE;
        return integer("caustica.options.rt.sharcRadianceScale", 50, 1000,
                Math.round(setting.value()), value -> setting.set((float) value), Object::toString);
    }

    private static OptionInstance<Integer> roughnessThreshold() {
        FloatSetting setting = CausticaConfig.Rt.Sharc.ROUGHNESS_THRESHOLD;
        return integer("caustica.options.rt.sharcRoughnessThreshold", 0, 100,
                Math.round(setting.value() * 100.0f), value -> setting.set(value / 100.0f),
                value -> String.format(Locale.ROOT, "%.2f", value / 100.0f));
    }

    private static OptionInstance<Integer> gridLogarithmBase() {
        FloatSetting setting = CausticaConfig.Rt.Sharc.GRID_LOGARITHM_BASE;
        return integer("caustica.options.rt.sharcGridLogarithmBase", 101, 1600,
                Math.round(setting.value() * 100.0f), value -> setting.set(value / 100.0f),
                value -> String.format(Locale.ROOT, "%.2f", value / 100.0f));
    }

    private static OptionInstance<Integer> gridLevelBias() {
        FloatSetting setting = CausticaConfig.Rt.Sharc.GRID_LEVEL_BIAS;
        return integer("caustica.options.rt.sharcGridLevelBias", -160, 160,
                Math.round(setting.value() * 10.0f), value -> setting.set(value / 10.0f),
                value -> String.format(Locale.ROOT, "%.1f", value / 10.0f));
    }

    private static OptionInstance<Boolean> bool(String key, CausticaConfig.BooleanSetting setting) {
        return OptionInstance.createBoolean(key,
                OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
                setting.value(), setting::set);
    }

    private static OptionInstance<Integer> integer(String key, int minimum, int maximum, int initial,
                                                   java.util.function.IntConsumer setter,
                                                   java.util.function.Function<Integer, String> formatter) {
        return new OptionInstance<>(key,
                OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
                (caption, value) -> Options.genericValueLabel(caption, Component.literal(formatter.apply(value))),
                new OptionInstance.IntRange(minimum, maximum), Math.clamp(initial, minimum, maximum),
                setter::accept);
    }

    private static Button disabledButton(Component label) {
        Button button = Button.builder(label, ignored -> { }).build();
        button.active = false;
        return button;
    }

    private static void restoreDefaults() {
        CausticaConfig.Rt.Sharc.ENABLED.set(CausticaConfig.Rt.Sharc.ENABLED.defaultValue());
        CausticaConfig.Rt.Sharc.CACHE_EXPONENT.set(CausticaConfig.Rt.Sharc.CACHE_EXPONENT.defaultValue());
        CausticaConfig.Rt.Sharc.PRIMARY_SURFACE_DEBUG.set(
                CausticaConfig.Rt.Sharc.PRIMARY_SURFACE_DEBUG.defaultValue());
        CausticaConfig.Rt.Sharc.ANTI_FIREFLY.set(CausticaConfig.Rt.Sharc.ANTI_FIREFLY.defaultValue());
        CausticaConfig.Rt.Sharc.UPDATE_TILE_SIZE.set(CausticaConfig.Rt.Sharc.UPDATE_TILE_SIZE.defaultValue());
        CausticaConfig.Rt.Sharc.ACCUMULATION_FRAMES.set(
                CausticaConfig.Rt.Sharc.ACCUMULATION_FRAMES.defaultValue());
        CausticaConfig.Rt.Sharc.STALE_FRAMES.set(CausticaConfig.Rt.Sharc.STALE_FRAMES.defaultValue());
        CausticaConfig.Rt.Sharc.SCENE_SCALE.set(CausticaConfig.Rt.Sharc.SCENE_SCALE.defaultValue());
        CausticaConfig.Rt.Sharc.RADIANCE_SCALE.set(CausticaConfig.Rt.Sharc.RADIANCE_SCALE.defaultValue());
        CausticaConfig.Rt.Sharc.GRID_LOGARITHM_BASE.set(
                CausticaConfig.Rt.Sharc.GRID_LOGARITHM_BASE.defaultValue());
        CausticaConfig.Rt.Sharc.GRID_LEVEL_BIAS.set(CausticaConfig.Rt.Sharc.GRID_LEVEL_BIAS.defaultValue());
        CausticaConfig.Rt.Sharc.ROUGHNESS_THRESHOLD.set(
                CausticaConfig.Rt.Sharc.ROUGHNESS_THRESHOLD.defaultValue());
        RtComposite.INSTANCE.requestSharcReset();
    }
}
