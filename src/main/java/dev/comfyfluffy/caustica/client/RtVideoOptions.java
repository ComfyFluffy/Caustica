package dev.comfyfluffy.caustica.client;

import com.mojang.serialization.Codec;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.BooleanSetting;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import dev.comfyfluffy.caustica.CausticaConfig.StringSetting;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * Builds the {@link OptionInstance} widgets shown in the RT section of the vanilla Video Settings screen
 * (injected by {@code VideoSettingsScreenMixin}). Each option is bound straight to a {@link CausticaConfig}
 * runtime setting: the initial value is read from the current config, and the value-update listener writes
 * back through {@code set(...)} so changes take effect on the next frame.
 *
 * <p>Only settings the renderer re-reads per-frame are exposed here — toggles that would require a device or
 * buffer-pool rebuild (worker threads, OMM, max-entity capacities, PBR material flags) are intentionally
 * left to the {@code -Dcaustica.*} startup surface. DLSS-RR quality is the exception: the render resolution
 * is queried from NGX for the chosen quality mode on every resize (see
 * {@code RtDlssRr.queryOptimalRenderSize}), and the RR feature itself is recreated live whenever
 * {@code quality} changes (see {@code RtDlssRr.ensureFeature}), so it is safe to expose here.
 */
public final class RtVideoOptions {
    private RtVideoOptions() {
    }

    /**
     * Runtime-tunable RT options, in display order. Paired two-per-row by {@code OptionsList.addSmall}.
     * The HDR entries are omitted entirely (not just disabled) when this session's swapchain isn't
     * PQ-capable ({@code CausticaConfig.Rt.Hdr.swapchainPqAvailable()}) — offering a toggle/sliders that
     * can never do anything is worse than not showing them, and unlike most settings here this one is
     * fixed by hardware/OS/compositor at surface-creation time. The current swapchain may still be native
     * SDR; changing the toggle invalidates its configuration and recreates it in the selected format.
     */
    public static OptionInstance<?>[] runtimeOptions() {
        List<OptionInstance<?>> options = new ArrayList<>(List.of(
            exposureMode(),
            manualEv(),
            gamma(),
            spp(),
            maxBounces(),
            entities(),
            particles(),
            firstPersonCompat(),
            firstPersonShadowTransmittance(),
            waterWaves(),
            dlssQuality()
        ));
        if (CausticaConfig.Rt.Hdr.swapchainPqAvailable()) {
            options.add(hdrEnabled());
            options.add(hdrUiBrightness());
            options.add(hdrPeak());
        }
        options.add(debugView());
        return options.toArray(OptionInstance<?>[]::new);
    }

    private static OptionInstance<String> exposureMode() {
        StringSetting setting = CausticaConfig.Rt.Exposure.MODE;
        return new OptionInstance<>(
            "caustica.options.rt.exposureMode",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.exposureMode.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.exposureMode." + value),
            new OptionInstance.Enum<>(List.of("auto", "manual"), Codec.STRING),
            setting.get(),
            setting::set);
    }

    private static OptionInstance<Integer> manualEv() {
        FloatSetting setting = CausticaConfig.Rt.Exposure.MANUAL_EV;
        return new OptionInstance<>(
            "caustica.options.rt.manualEv",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.manualEv.tooltip")),
            (caption, tenths) -> {
                float ev = tenths / 10.0f;
                String sign = ev > 0.0f ? "+" : "";
                return Options.genericValueLabel(caption,
                        Component.literal(sign + String.format(Locale.ROOT, "%.1f EV", ev)));
            },
            new OptionInstance.IntRange(-150, 150),
            Math.clamp(Math.round(setting.value() * 10.0f), -150, 150),
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Integer> gamma() {
        FloatSetting setting = CausticaConfig.Rt.Tonemap.GAMMA;
        return new OptionInstance<>(
            "caustica.options.rt.gamma",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.gamma.tooltip")),
            (caption, hundredths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.2f", hundredths / 100.0f))),
            new OptionInstance.IntRange(50, 150),
            Math.clamp(Math.round(setting.value() * 100.0f), 50, 150),
            hundredths -> setting.set(hundredths / 100.0f));
    }

    private static OptionInstance<Integer> spp() {
        IntSetting setting = CausticaConfig.Rt.Composite.SPP;
        return new OptionInstance<>(
            "caustica.options.rt.spp",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.spp.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(1, 8),
            Math.clamp(setting.value(), 1, 8),
            setting::set);
    }

    private static OptionInstance<Integer> maxBounces() {
        IntSetting setting = CausticaConfig.Rt.Composite.MAX_BOUNCES;
        return new OptionInstance<>(
            "caustica.options.rt.maxBounces",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.maxBounces.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(2, 8),
            Math.clamp(setting.value(), 2, 8),
            setting::set);
    }

    private static OptionInstance<Boolean> entities() {
        return bool("caustica.options.rt.entities", CausticaConfig.Rt.Entities.ENABLED);
    }

    private static OptionInstance<Boolean> particles() {
        return bool("caustica.options.rt.particles", CausticaConfig.Rt.Entities.PARTICLES_ENABLED);
    }

    private static OptionInstance<Boolean> firstPersonCompat() {
        return bool("caustica.options.rt.firstPersonCompat",
                CausticaConfig.Rt.Entities.FIRST_PERSON_COMPAT_ENABLED);
    }

    private static OptionInstance<Integer> firstPersonShadowTransmittance() {
        FloatSetting setting = CausticaConfig.Rt.Entities.FIRST_PERSON_SHADOW_TRANSMITTANCE;
        return new OptionInstance<>(
            "caustica.options.rt.firstPersonShadowTransmittance",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("caustica.options.rt.firstPersonShadowTransmittance.tooltip")),
            (caption, percent) -> Options.genericValueLabel(caption, Component.literal(percent + "%")),
            new OptionInstance.IntRange(0, 100),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 100),
            percent -> setting.set(percent / 100.0f));
    }

    private static OptionInstance<Boolean> waterWaves() {
        return bool("caustica.options.rt.waterWaves", CausticaConfig.Rt.Composite.WATER_WAVES);
    }

    private static OptionInstance<Integer> dlssQuality() {
        IntSetting setting = CausticaConfig.Rt.DlssRr.QUALITY;
        List<Integer> steps = CausticaConfig.Rt.DlssRr.QUALITY_STEPS;
        int initialQuality = steps.contains(setting.value()) ? setting.value() : 0;
        int initialPosition = steps.indexOf(initialQuality);
        return new OptionInstance<>(
            "caustica.options.rt.dlssQuality",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.dlssQuality.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.dlssQuality." + steps.get(position))),
            new OptionInstance.IntRange(0, steps.size() - 1),
            initialPosition,
            position -> setting.set(steps.get(position)));
    }

    private static OptionInstance<Boolean> hdrEnabled() {
        BooleanSetting setting = CausticaConfig.Rt.Hdr.ENABLED;
        return OptionInstance.createBoolean(
            "caustica.options.rt.hdr",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdr.tooltip")),
            setting.value(),
            enabled -> {
                if (setting.value() != enabled) {
                    setting.set(enabled);
                    // Reuse the framebuffer-resize path at the next safe frame boundary. GpuSurface
                    // refuses configure() while an image is acquired, so doing it directly here is unsafe.
                    Minecraft.getInstance().invalidateSurfaceConfiguration();
                }
            });
    }

    private static OptionInstance<Integer> hdrUiBrightness() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.UI_NITS;
        return new OptionInstance<>(
            "caustica.options.rt.hdrUiBrightness",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrUiBrightness.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 500),
            Math.clamp(Math.round(setting.value()), 80, 500),
            nits -> setting.set(nits.floatValue()));
    }

    // Each step selects a baked ACES HDR mastering target. Changes take effect on the next frame.
    private static OptionInstance<Integer> hdrPeak() {
        IntSetting setting = CausticaConfig.Rt.Hdr.PEAK_NITS;
        List<Integer> steps = CausticaConfig.Rt.Hdr.PEAK_NITS_STEPS;
        int initialPeak = steps.contains(setting.value()) ? setting.value() : 1000;
        int initialPosition = steps.indexOf(initialPeak);
        return new OptionInstance<>(
            "caustica.options.rt.hdrPeak",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPeak.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption, Component.literal(steps.get(position) + " nits")),
            new OptionInstance.IntRange(0, steps.size() - 1),
            Math.max(initialPosition, 0),
            position -> setting.set(steps.get(position)));
    }

    private static OptionInstance<Integer> debugView() {
        IntSetting setting = CausticaConfig.Rt.Composite.DEBUG_VIEW;
        return new OptionInstance<>(
            "caustica.options.rt.debugView",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.debugView.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.debugView." + value),
            new OptionInstance.Enum<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), Codec.INT),
            Math.clamp(setting.value(), 0, 9),
            setting::set);
    }

    private static OptionInstance<Boolean> bool(String captionKey, BooleanSetting setting) {
        return OptionInstance.createBoolean(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            setting.value(),
            setting::set);
    }
}
