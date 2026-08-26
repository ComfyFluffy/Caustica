package dev.comfyfluffy.caustica.client;

import com.mojang.serialization.Codec;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.BooleanSetting;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import dev.comfyfluffy.caustica.CausticaConfig.StringSetting;
import dev.comfyfluffy.caustica.rt.pipeline.RtToneMapping;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
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
    /** A tone-mapping submenu option paired with the source-defined default reset value. */
    public record ResettableControl(OptionInstance<?> option, Runnable reset) {
        public void resetToDefault() {
            reset.run();
        }
    }

    private RtVideoOptions() {
    }

    private static <T> ResettableControl control(OptionInstance<T> option, T defaultValue) {
        return new ResettableControl(option, () -> option.set(defaultValue));
    }

    static OptionInstance<?>[] optionInstances(ResettableControl[] controls) {
        OptionInstance<?>[] options = new OptionInstance<?>[controls.length];
        for (int i = 0; i < controls.length; i++) {
            options[i] = controls[i].option();
        }
        return options;
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
            exposureLowPercentile(),
            exposureHighPercentile(),
            preExposure(),
            gamma(),
            spp(),
            maxBounces(),
            risCandidates(),
            entities(),
            particles(),
            waterWaves(),
            dlssQuality()
        ));
        if (CausticaConfig.Rt.Hdr.swapchainPqAvailable()) {
            options.add(hdrEnabled());
            options.add(hdrUiBrightness());
            options.add(hdrPaperWhite());
            options.add(hdrPeak());
        }
        return options.toArray(OptionInstance<?>[]::new);
    }

    /** Exposure and display controls shown by {@link RtToneMappingOptionsScreen}. */
    public static ResettableControl[] exposureOptions() {
        return new ResettableControl[] {
            control(exposureMode(), CausticaConfig.Rt.Exposure.MODE.defaultValue()),
            control(manualEv(), Math.clamp(Math.round(CausticaConfig.Rt.Exposure.MANUAL_EV.defaultValue() * 10.0f), -150, 150)),
            control(gamma(), Math.clamp(Math.round(CausticaConfig.Rt.Tonemap.GAMMA.defaultValue() * 100.0f), 50, 150)),
        };
    }

    public static ResettableControl sdrToneMapper() {
        StringSetting setting = CausticaConfig.Rt.Sdr.TONE_MAPPER;
        return new ResettableControl(
                toneMapper("caustica.options.rt.sdrToneMapper", RtToneMapping.sdrConfigNames(), setting),
                () -> setting.set(setting.defaultValue()));
    }

    public static ResettableControl hdrToneMapper() {
        StringSetting setting = CausticaConfig.Rt.Hdr.TONE_MAPPER;
        return new ResettableControl(
                toneMapper("caustica.options.rt.hdrToneMapper", RtToneMapping.hdrConfigNames(), setting),
                () -> setting.set(setting.defaultValue()));
    }

    private static OptionInstance<Integer> toneMapper(String captionKey, List<String> values, StringSetting setting) {
        int currentIndex = Math.clamp(values.indexOf(setting.get()), 0, values.size() - 1);
        return new OptionInstance<>(
                captionKey,
                OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
                (caption, index) -> Component.translatable(
                        "caustica.options.rt.toneMapper." + values.get(Math.clamp(index, 0, values.size() - 1))),
                new OptionInstance.IntRange(0, values.size() - 1),
                currentIndex,
                index -> setting.set(values.get(Math.clamp(index, 0, values.size() - 1))));
    }

    /** HDR display controls shown in the tone-mapping submenu with reset-to-default support. */
    public static ResettableControl[] hdrDisplayOptions() {
        return new ResettableControl[] {
            control(
                    hdrPaperWhite(),
                    Math.clamp(Math.round(CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS.defaultValue()), 80, 500)),
            control(
                    hdrPeak(),
                    Math.clamp(
                            Math.round(CausticaConfig.Rt.Hdr.PEAK_NITS.defaultValue()
                                    / (float) CausticaConfig.Rt.Hdr.PEAK_NITS_STEP),
                            CausticaConfig.Rt.Hdr.PEAK_NITS_MIN / CausticaConfig.Rt.Hdr.PEAK_NITS_STEP,
                            CausticaConfig.Rt.Hdr.PEAK_NITS_MAX / CausticaConfig.Rt.Hdr.PEAK_NITS_STEP)),
        };
    }

    /** Controls for exactly the selected mapper; ACES 2.0 and BT.2390 have no extra parameters. */
    public static ResettableControl[] activeToneMapperOptions(boolean hdr) {
        if (hdr) {
            return switch (RtToneMapping.HdrMode.parse(CausticaConfig.Rt.Hdr.TONE_MAPPER.get())) {
                case ACES_2_0, BT2390 -> new ResettableControl[0];
                case PSYCHOV24 -> psychoV24Options(
                        CausticaConfig.Rt.Hdr.PSYCHOV24_COMPRESSION,
                        CausticaConfig.Rt.Hdr.PSYCHOV24_GAMUT_COMPRESSION,
                        CausticaConfig.Rt.Hdr.PSYCHOV24_HIGHLIGHTS,
                        CausticaConfig.Rt.Hdr.PSYCHOV24_SHADOWS,
                        CausticaConfig.Rt.Hdr.PSYCHOV24_CONTRAST,
                        CausticaConfig.Rt.Hdr.PSYCHOV24_PURITY);
            };
        }
        return switch (RtToneMapping.SdrMode.parse(CausticaConfig.Rt.Sdr.TONE_MAPPER.get())) {
            case ACES_2_0 -> new ResettableControl[0];
            case AGX -> new ResettableControl[] {
                scaledFloatControl("caustica.options.rt.agxContrast", CausticaConfig.Rt.Sdr.AGX_CONTRAST, 100, 0, 200, 2),
                scaledFloatControl("caustica.options.rt.agxSaturation", CausticaConfig.Rt.Sdr.AGX_SATURATION, 100, 0, 300, 2),
            };
            case PBR_NEUTRAL -> new ResettableControl[] {
                scaledFloatControl("caustica.options.rt.pbrStartCompression", CausticaConfig.Rt.Sdr.PBR_START_COMPRESSION, 100, 0, 99, 2),
                scaledFloatControl("caustica.options.rt.pbrDesaturation", CausticaConfig.Rt.Sdr.PBR_DESATURATION, 100, 0, 100, 2),
            };
            case REINHARD -> new ResettableControl[] {
                scaledFloatControl("caustica.options.rt.reinhardWhitePoint", CausticaConfig.Rt.Sdr.REINHARD_WHITE_POINT, 10, 10, 200, 1),
            };
            case ACES -> new ResettableControl[] {
                scaledFloatControl("caustica.options.rt.acesInputScale", CausticaConfig.Rt.Sdr.ACES_EXPOSURE, 100, 0, 400, 2),
            };
            case LOTTES -> new ResettableControl[] {
                scaledFloatControl("caustica.options.rt.lottesContrast", CausticaConfig.Rt.Sdr.LOTTES_CONTRAST, 100, 10, 500, 2),
                scaledFloatControl("caustica.options.rt.lottesShoulder", CausticaConfig.Rt.Sdr.LOTTES_SHOULDER, 100, 10, 500, 2),
                scaledFloatControl("caustica.options.rt.lottesHdrMax", CausticaConfig.Rt.Sdr.LOTTES_HDR_MAX, 10, 10, 640, 1),
                scaledFloatControl("caustica.options.rt.lottesMidIn", CausticaConfig.Rt.Sdr.LOTTES_MID_IN, 100, 1, 100, 2),
                scaledFloatControl("caustica.options.rt.lottesMidOut", CausticaConfig.Rt.Sdr.LOTTES_MID_OUT, 100, 1, 100, 2),
            };
            case UNCHARTED_2 -> new ResettableControl[] {
                scaledFloatControl("caustica.options.rt.unchartedShoulderStrength", CausticaConfig.Rt.Sdr.UNCHARTED_A, 100, 1, 100, 2),
                scaledFloatControl("caustica.options.rt.unchartedLinearStrength", CausticaConfig.Rt.Sdr.UNCHARTED_B, 100, 1, 200, 2),
                scaledFloatControl("caustica.options.rt.unchartedLinearAngle", CausticaConfig.Rt.Sdr.UNCHARTED_C, 100, 0, 100, 2),
                scaledFloatControl("caustica.options.rt.unchartedToeStrength", CausticaConfig.Rt.Sdr.UNCHARTED_D, 100, 1, 200, 2),
                scaledFloatControl("caustica.options.rt.unchartedToeNumerator", CausticaConfig.Rt.Sdr.UNCHARTED_E, 100, 0, 100, 2),
                scaledFloatControl("caustica.options.rt.unchartedToeDenominator", CausticaConfig.Rt.Sdr.UNCHARTED_F, 100, 1, 200, 2),
                scaledFloatControl("caustica.options.rt.unchartedWhitePoint", CausticaConfig.Rt.Sdr.UNCHARTED_WHITE_POINT, 10, 10, 320, 1),
            };
            case GT -> new ResettableControl[] {
                scaledFloatControl("caustica.options.rt.gtContrast", CausticaConfig.Rt.Sdr.GT_CONTRAST, 100, 10, 400, 2),
                scaledFloatControl("caustica.options.rt.gtLinearStart", CausticaConfig.Rt.Sdr.GT_LINEAR_START, 100, 1, 99, 2),
                scaledFloatControl("caustica.options.rt.gtLinearLength", CausticaConfig.Rt.Sdr.GT_LINEAR_LENGTH, 100, 1, 400, 2),
                scaledFloatControl("caustica.options.rt.gtBlackCurve", CausticaConfig.Rt.Sdr.GT_BLACK_CURVE, 100, 10, 400, 2),
                scaledFloatControl("caustica.options.rt.gtBlackLift", CausticaConfig.Rt.Sdr.GT_BLACK_LIFT, 100, -50, 50, 2),
            };
            case PSYCHOV24 -> psychoV24Options(
                    CausticaConfig.Rt.Sdr.PSYCHOV24_COMPRESSION,
                    CausticaConfig.Rt.Sdr.PSYCHOV24_GAMUT_COMPRESSION,
                    CausticaConfig.Rt.Sdr.PSYCHOV24_HIGHLIGHTS,
                    CausticaConfig.Rt.Sdr.PSYCHOV24_SHADOWS,
                    CausticaConfig.Rt.Sdr.PSYCHOV24_CONTRAST,
                    CausticaConfig.Rt.Sdr.PSYCHOV24_PURITY);
        };
    }

    public static Component activeToneMapperName(boolean hdr) {
        String name = hdr ? CausticaConfig.Rt.Hdr.TONE_MAPPER.get() : CausticaConfig.Rt.Sdr.TONE_MAPPER.get();
        return Component.translatable("caustica.options.rt.toneMapper." + name);
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

    private static OptionInstance<Integer> exposureLowPercentile() {
        return percentile("caustica.options.rt.exposureLowPercentile",
                CausticaConfig.Rt.Exposure.LOW_PERCENTILE);
    }

    private static OptionInstance<Integer> exposureHighPercentile() {
        return percentile("caustica.options.rt.exposureHighPercentile",
                CausticaConfig.Rt.Exposure.HIGH_PERCENTILE);
    }

    private static OptionInstance<Integer> percentile(String captionKey, FloatSetting setting) {
        return new OptionInstance<>(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            (caption, percent) -> Options.genericValueLabel(caption,
                    Component.literal(percent + "%")),
            new OptionInstance.IntRange(0, 100),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 100),
            percent -> setting.set(percent / 100.0f));
    }

    private static OptionInstance<Boolean> preExposure() {
        return bool("caustica.options.rt.preExposure", CausticaConfig.Rt.Exposure.PRE_EXPOSURE);
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

    private static OptionInstance<Integer> risCandidates() {
        IntSetting setting = CausticaConfig.Rt.Lights.RIS_CANDIDATES;
        return new OptionInstance<>(
            "caustica.options.rt.risCandidates",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.risCandidates.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption,
                    value == 0
                            ? Component.translatable("caustica.options.rt.risCandidates.off")
                            : Component.literal(value + " candidates")),
            new OptionInstance.IntRange(0, 32),
            Math.clamp(setting.value(), 0, 32),
            value -> {
                if (setting.value() == value) {
                    return;
                }
                setting.set(value);
                // Meshing omits emitter records while RIS is disabled; rebuild residency when the
                // setting changes so the selected light population reaches the next render. DLSS-RR
                // intentionally keeps its history here: this is a gradual lighting change, not a
                // camera, dimension, resolution, or feature discontinuity.
                RtTerrain.requestFullClear();
            });
    }

    private static OptionInstance<Boolean> entities() {
        return bool("caustica.options.rt.entities", CausticaConfig.Rt.Entities.ENABLED);
    }

    private static OptionInstance<Boolean> particles() {
        return bool("caustica.options.rt.particles", CausticaConfig.Rt.Entities.PARTICLES_ENABLED);
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

    private static OptionInstance<Integer> hdrPaperWhite() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS;
        return new OptionInstance<>(
            "caustica.options.rt.hdrPaperWhite",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPaperWhite.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 500),
            Math.clamp(Math.round(setting.value()), 80, 500),
            nits -> setting.set(nits.floatValue()));
    }

    // Each position is one 50-nit increment. ACES 2.0 selects the nearest baked LUT; analytical HDR
    // modes use the exact selected peak. Changes take effect on the next frame.
    private static OptionInstance<Integer> hdrPeak() {
        IntSetting setting = CausticaConfig.Rt.Hdr.PEAK_NITS;
        int minPosition = CausticaConfig.Rt.Hdr.PEAK_NITS_MIN / CausticaConfig.Rt.Hdr.PEAK_NITS_STEP;
        int maxPosition = CausticaConfig.Rt.Hdr.PEAK_NITS_MAX / CausticaConfig.Rt.Hdr.PEAK_NITS_STEP;
        int initialPosition = Math.clamp(
                Math.round(setting.value() / (float) CausticaConfig.Rt.Hdr.PEAK_NITS_STEP),
                minPosition,
                maxPosition);
        return new OptionInstance<>(
            "caustica.options.rt.hdrPeak",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPeak.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption,
                    Component.literal(position * CausticaConfig.Rt.Hdr.PEAK_NITS_STEP + " nits")),
            new OptionInstance.IntRange(minPosition, maxPosition),
            initialPosition,
            position -> setting.set(position * CausticaConfig.Rt.Hdr.PEAK_NITS_STEP));
    }

    private static ResettableControl[] psychoV24Options(
            FloatSetting compression,
            FloatSetting gamutCompression,
            FloatSetting highlights,
            FloatSetting shadows,
            FloatSetting contrast,
            FloatSetting purity) {
        return new ResettableControl[] {
            psychoCompressionControl(compression),
            percentageControl("caustica.options.rt.psychov24GamutCompression", gamutCompression),
            percentageControl("caustica.options.rt.psychov24Highlights", highlights, 0, 300),
            percentageControl("caustica.options.rt.psychov24Shadows", shadows, 0, 300),
            percentageControl("caustica.options.rt.psychov24Contrast", contrast, 10, 300),
            percentageControl("caustica.options.rt.psychov24Purity", purity, 0, 300),
        };
    }

    private static ResettableControl psychoCompressionControl(FloatSetting setting) {
        String captionKey = "caustica.options.rt.psychov24Compression";
        OptionInstance<Integer> option = new OptionInstance<>(
                captionKey,
                OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
                (caption, value) -> Options.genericValueLabel(caption,
                        value == 0
                                ? Component.translatable(captionKey + ".auto")
                                : Component.literal(String.format(Locale.ROOT, "%.2f", value / 100.0f))),
                new OptionInstance.IntRange(0, 800),
                Math.clamp(Math.round(setting.value() * 100.0f), 0, 800),
                value -> setting.set(value / 100.0f));
        return control(option, Math.clamp(Math.round(setting.defaultValue() * 100.0f), 0, 800));
    }

    private static ResettableControl scaledFloatControl(
            String captionKey, FloatSetting setting, int scale, int min, int max, int decimals) {
        return scaledFloatControl(captionKey, setting, scale, min, max, decimals, "");
    }

    private static ResettableControl scaledFloatControl(
            String captionKey, FloatSetting setting, int scale, int min, int max, int decimals, String suffix) {
        OptionInstance<Integer> option = new OptionInstance<>(
                captionKey,
                OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
                (caption, value) -> Options.genericValueLabel(caption,
                        Component.literal(String.format(Locale.ROOT, "%." + decimals + "f%s",
                                value / (float) scale, suffix))),
                new OptionInstance.IntRange(min, max),
                Math.clamp(Math.round(setting.value() * scale), min, max),
                value -> setting.set(value / (float) scale));
        return control(option, Math.clamp(Math.round(setting.defaultValue() * scale), min, max));
    }

    private static ResettableControl percentageControl(String captionKey, FloatSetting setting) {
        return percentageControl(captionKey, setting, 0, 100);
    }

    private static ResettableControl percentageControl(
            String captionKey, FloatSetting setting, int min, int max) {
        OptionInstance<Integer> option = new OptionInstance<>(
                captionKey,
                OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
                (caption, value) -> Options.genericValueLabel(caption, Component.literal(value + "%")),
                new OptionInstance.IntRange(min, max),
                Math.clamp(Math.round(setting.value() * 100.0f), min, max),
                value -> setting.set(value / 100.0f));
        return control(option, Math.clamp(Math.round(setting.defaultValue() * 100.0f), min, max));
    }

    /** Launcher paired with Debug View on the main Video Settings page. */
    public static Button toneMappingButton(Screen parent, Runnable beforeOpen) {
        return Button.builder(
                        Component.translatable("caustica.options.rt.toneMappingMenu"),
                        button -> {
                            beforeOpen.run();
                            Minecraft minecraft = Minecraft.getInstance();
                            minecraft.setScreenAndShow(new RtToneMappingOptionsScreen(parent, minecraft.options));
                        })
                .tooltip(Tooltip.create(Component.translatable("caustica.options.rt.toneMappingMenu.tooltip")))
                .build();
    }

    public static OptionInstance<Integer> debugView() {
        IntSetting setting = CausticaConfig.Rt.Composite.DEBUG_VIEW;
        return new OptionInstance<>(
            "caustica.options.rt.debugView",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.debugView.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.debugView." + value),
            new OptionInstance.Enum<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
                    CausticaConfig.Rt.Composite.RAW_DEBUG_VIEW), Codec.INT),
            Math.clamp(setting.value(), 0, CausticaConfig.Rt.Composite.RAW_DEBUG_VIEW),
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
