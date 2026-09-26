package dev.comfyfluffy.caustica.minecraft.client.settings;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftDisplayText;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaConfig;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaOptions;
import dev.comfyfluffy.caustica.renderer.presentation.RtToneMapping;
import dev.comfyfluffy.caustica.renderer.runtime.RendererOptions.Rt.Tonemap;
import dev.comfyfluffy.caustica.settings.DisplayText;
import dev.comfyfluffy.caustica.settings.FeatureSettings;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Derives the Video Settings section and the options pages from the registered settings. Nothing here knows
 * about widgets, so every page is exercised in tests without a GUI stack.
 *
 * <p>Renderer pages select rows by option id prefix or group, so an option under a page's prefix or group appears
 * there without a UI change; a page lists its everyday rows by id and folds every other row it owns into an
 * advanced section. The Tone Mapping page takes the selected mapper's controls from the renderer's per-mapper
 * lists. Grouped renderer rows no page owns land on the Overlays & Debug page under their group titles, and
 * every other feature gets one page built from its declared groups. A page without rows gets no button.
 */
public final class CausticaPages {
    /** The master switch, which Video Settings gives a whole row. */
    public static final String RAY_TRACING = "enabled";
    /** Renderer rows shown directly in Video Settings: the master switch, then two-column pairs. */
    private static final List<String> VIDEO_SETTINGS = List.of(RAY_TRACING,
            "composite.max-bounces", "composite.water-waves",
            "entities.enabled", "particles.enabled");
    private static final String HDR_ENABLED = "hdr.enabled";
    private static final String DENOISING_ROUTE = "denoising.route";
    private static final List<String> EXPOSURE_EVERYDAY = List.of("exposure.mode", "exposure.response", "exposure.manual-ev",
            "exposure.key", "exposure.adapt-darken", "exposure.adapt-brighten");
    private static final List<String> RADIANCE_CACHE_EVERYDAY = List.of("sharc.enabled");

    /** Every per-mapper control of both outputs; the Tone Mapping page shows the selected mapper's only. */
    private static final Set<String> MAPPER_CONTROLS = Stream.concat(Tonemap.SDR_CONTROLS.values().stream(),
            Tonemap.HDR_CONTROLS.values().stream()).flatMap(List::stream).map(Option::id)
            .collect(Collectors.toUnmodifiableSet());

    private static final Predicate<Option<?>> TONE_MAPPING = prefix("tonemap.", "sdr.", "hdr.");
    private static final Predicate<Option<?>> EXPOSURE = prefix("exposure.");
    private static final Predicate<Option<?>> RAY_RECONSTRUCTION = grouped(prefix("dlss-rr."));
    private static final Predicate<Option<?>> NRD = grouped(prefix("denoising."))
            .and(option -> !option.id().equals(DENOISING_ROUTE));
    private static final Predicate<Option<?>> SUPER_RESOLUTION = grouped(prefix("dlss-sr."));
    private static final Predicate<Option<?>> LATENCY = grouped(prefix("frame-generation.", "reflex."));
    private static final Predicate<Option<?>> UPSCALING = RAY_RECONSTRUCTION.or(NRD).or(SUPER_RESOLUTION)
            .or(LATENCY).or(option -> option.id().equals(DENOISING_ROUTE));
    private static final Predicate<Option<?>> RADIANCE_CACHE = option -> "radiance-cache".equals(option.group());
    /** Every row a renderer page or the Video Settings section already owns. */
    private static final Predicate<Option<?>> CLAIMED = TONE_MAPPING.or(EXPOSURE).or(UPSCALING).or(RADIANCE_CACHE)
            .or(option -> VIDEO_SETTINGS.contains(option.id()));

    /** A page the Video Settings section opens; the supplier re-derives it from current preferences. */
    public record Link(String id, Component title, Component tooltip, Supplier<SettingsPage> page) {
    }

    private CausticaPages() {
    }

    /** The renderer rows Video Settings shows before its page buttons. */
    public static List<SettingControl> videoSettings(SettingsRegistry registry, CausticaOptions options,
                                                     Predicate<Option<?>> available) {
        Engine engine = new Engine(registry, options, available);
        return VIDEO_SETTINGS.stream().map(id -> engine.control(engine.option(id))).toList();
    }

    /**
     * Page buttons in display order: the renderer's pages, one page per other feature, and last the page of
     * overlays and diagnostics. Pages without rows are left out.
     */
    public static List<Link> links(SettingsRegistry registry, CausticaOptions options,
                                   Predicate<Option<?>> available) {
        Engine engine = new Engine(registry, options, available);
        List<Link> links = new ArrayList<>();
        rendererLink(links, "tone-mapping", () -> toneMapping(engine));
        rendererLink(links, "exposure", () -> exposure(engine));
        rendererLink(links, "upscaling", () -> upscaling(engine));
        rendererLink(links, "radiance-cache", () -> radianceCache(engine));
        for (FeatureSettings feature : registry.all()) {
            if (feature.id().equals(CausticaConfig.FEATURE)) continue;
            SettingsPage page = feature(feature, options);
            if (page.sections().isEmpty()) continue;
            links.add(new Link(feature.id().toString(), page.title(), featureTooltip(feature, page),
                    () -> feature(feature, options)));
        }
        rendererLink(links, "overlays", () -> overlays(engine));
        return List.copyOf(links);
    }

    private static void rendererLink(List<Link> links, String id, Supplier<SettingsPage> page) {
        if (!page.get().sections().isEmpty()) {
            links.add(new Link(id, Component.translatable("caustica.page." + id),
                    Component.translatable("caustica.page." + id + ".tooltip"), page));
        }
    }

    /** The feature's description, or the titles of its sections when it declares none. */
    private static Component featureTooltip(FeatureSettings feature, SettingsPage page) {
        if (!feature.description().equals(DisplayText.EMPTY)) {
            return MinecraftDisplayText.component(feature.description());
        }
        return ComponentUtils.formatList(page.sections().stream().map(SettingGroup::title).toList(),
                Component.literal(", "));
    }

    /**
     * The output path's tone mapper on a whole row, HDR Output, the path's own rows and the display transform's
     * shared rows, then the selected mapper's controls in their declared order. The HDR path applies only while
     * HDR is requested and the swapchain can present it.
     */
    static SettingsPage toneMapping(Engine engine) {
        boolean hdr = engine.hdrActive();
        String path = hdr ? "hdr" : "sdr";
        Option<String> selector = hdr ? Tonemap.HDR_MAPPER : Tonemap.SDR_MAPPER;
        String mapper = (String) engine.value(selector);
        List<SettingControl> output = new ArrayList<>();
        output.add(engine.control(selector));
        output.add(engine.control(engine.option(HDR_ENABLED)));
        output.addAll(engine.controls(option -> (option.id().startsWith("tonemap.")
                || option.id().startsWith(path + "."))
                && !option.id().equals(selector.id()) && !option.id().equals(HDR_ENABLED)
                && !MAPPER_CONTROLS.contains(option.id())));
        List<Option<Float>> controls = hdr
                ? Tonemap.HDR_CONTROLS.getOrDefault(RtToneMapping.HdrMode.of(mapper), List.of())
                : Tonemap.SDR_CONTROLS.getOrDefault(RtToneMapping.SdrMode.of(mapper), List.of());

        List<SettingGroup> sections = new ArrayList<>();
        section(sections, path + "-output", title("tone-mapping", path + "-output"), output);
        section(sections, path + "." + mapper,
                Component.translatable("caustica.page.tone-mapping.mapper",
                        LangKeys.optionChoice(CausticaConfig.FEATURE, selector, mapper)),
                controls.stream().map(engine::control).toList());
        return new SettingsPage("tone-mapping", Component.translatable("caustica.page.tone-mapping"), sections,
                Set.of(selector.id()));
    }

    /** Mode, compensation and adaptation; the meter's internals are folded under Advanced. */
    static SettingsPage exposure(Engine engine) {
        List<SettingGroup> sections = new ArrayList<>();
        everydayAndAdvanced(sections, "exposure", title("exposure", "exposure"), engine.controls(EXPOSURE),
                EXPOSURE_EVERYDAY);
        return page("exposure", sections);
    }

    /** The denoiser, then only the settings of the route it selects, then frame generation and latency. */
    static SettingsPage upscaling(Engine engine) {
        Option<?> route = engine.option(DENOISING_ROUTE);
        List<SettingGroup> sections = new ArrayList<>();
        section(sections, "denoiser", title("upscaling", "denoiser"), List.of(engine.control(route)));
        switch ((String) engine.value(route)) {
            case "ray_reconstruction" -> section(sections, "dlss-rr", title("upscaling", "dlss-rr"),
                    engine.controls(RAY_RECONSTRUCTION));
            case "temporal_denoiser" -> {
                List<SettingControl> rows = new ArrayList<>(engine.controls(NRD));
                rows.addAll(engine.controls(SUPER_RESOLUTION));
                section(sections, "nrd", title("upscaling", "nrd"), rows);
            }
            default -> { }
        }
        section(sections, "latency", title("upscaling", "latency"), engine.controls(LATENCY));
        return page("upscaling", sections);
    }

    /**
     * The SHaRC switch; the cache's size, grid and accumulation tuning are folded under Advanced. The cache view
     * for directly visible surfaces is a diagnostic and stays on the Overlays & Debug page. Where the build or
     * device cannot run SHaRC, the host's availability check keeps these rows visible but disabled.
     */
    static SettingsPage radianceCache(Engine engine) {
        List<SettingGroup> sections = new ArrayList<>();
        everydayAndAdvanced(sections, "radiance-cache", title("radiance-cache", "radiance-cache"),
                engine.controls(RADIANCE_CACHE), RADIANCE_CACHE_EVERYDAY);
        return page("radiance-cache", sections);
    }

    /** Grouped renderer rows no other page owns, such as overlays and diagnostics, under their group titles. */
    static SettingsPage overlays(Engine engine) {
        List<SettingGroup> sections = new ArrayList<>();
        for (String group : engine.feature().optionGroups()) {
            section(sections, group, LangKeys.engineGroup(group),
                    engine.controls(option -> group.equals(option.group()) && !CLAIMED.test(option)));
        }
        return page("overlays", sections);
    }

    /** One section per declared group, led by its header bool, then ungrouped rows. */
    static SettingsPage feature(FeatureSettings feature, CausticaOptions options) {
        List<SettingGroup> sections = new ArrayList<>();
        for (String group : feature.optionGroups()) {
            SettingControl.BoolControl header = null;
            List<SettingControl> rows = new ArrayList<>();
            for (Option<?> option : feature.options()) {
                if (!group.equals(option.group())) continue;
                SettingControl control = OptionControls.of(options, feature.id(), option);
                if (control == null) continue;
                if (option.isGroupHeader()) {
                    header = (SettingControl.BoolControl) control;
                    rows.addFirst(control);
                } else {
                    rows.add(control);
                }
            }
            if (!rows.isEmpty()) {
                sections.add(new SettingGroup(group, LangKeys.optionGroup(feature.id(), group), header, rows,
                        false));
            }
        }
        List<SettingControl> ungrouped = feature.options().stream().filter(option -> option.group() == null)
                .map(option -> OptionControls.of(options, feature.id(), option)).filter(Objects::nonNull).toList();
        section(sections, "other", Component.translatable("caustica.group.other"), ungrouped);
        return new SettingsPage(feature.id().toString(), MinecraftDisplayText.component(feature.title()),
                sections);
    }

    private static SettingsPage page(String id, List<SettingGroup> sections) {
        return new SettingsPage(id, Component.translatable("caustica.page." + id), sections);
    }

    private static void section(List<SettingGroup> sections, String id, Component title, List<SettingControl> rows) {
        if (!rows.isEmpty()) {
            sections.add(new SettingGroup(id, title, rows));
        }
    }

    /**
     * The rows listed in {@code everyday} under {@code title}, and every other row after them in a folded
     * advanced section, so an option added to the page later is always reachable.
     */
    private static void everydayAndAdvanced(List<SettingGroup> sections, String id, Component title,
                                            List<SettingControl> rows, List<String> everyday) {
        List<SettingControl> basic = rows.stream().filter(row -> everyday.contains(row.id())).toList();
        List<SettingControl> internals = rows.stream().filter(row -> !everyday.contains(row.id())).toList();
        if (!basic.isEmpty()) {
            sections.add(new SettingGroup(id, title, basic));
        }
        if (!internals.isEmpty()) {
            sections.add(new SettingGroup(id + ".advanced", title, null, internals, true));
        }
    }

    private static Component title(String page, String section) {
        return Component.translatable("caustica.page." + page + "." + section);
    }

    private static Predicate<Option<?>> prefix(String... prefixes) {
        List<String> all = List.of(prefixes);
        return option -> all.stream().anyMatch(option.id()::startsWith);
    }

    private static Predicate<Option<?>> grouped(Predicate<Option<?>> predicate) {
        return predicate.and(option -> option.group() != null);
    }

    /** The renderer feature's declarations, the store, and the host's availability check. */
    record Engine(FeatureSettings feature, CausticaOptions store, Predicate<Option<?>> available) {
        Engine(SettingsRegistry registry, CausticaOptions store, Predicate<Option<?>> available) {
            this(registry.settings(CausticaConfig.FEATURE), store, available);
        }

        Option<?> option(String id) {
            return feature.option(id);
        }

        Object value(Option<?> option) {
            return store.options(CausticaConfig.FEATURE).get(option);
        }

        SettingControl control(Option<?> option) {
            return OptionControls.of(store, CausticaConfig.FEATURE, option, available);
        }

        /** Rows in declaration order; options without a native row are left out. */
        List<SettingControl> controls(Predicate<Option<?>> includes) {
            return feature.options().stream().filter(includes).map(this::control).filter(Objects::nonNull)
                    .toList();
        }

        /** HDR output applies only while it is requested and the swapchain can present it. */
        boolean hdrActive() {
            Option<?> hdr = option(HDR_ENABLED);
            return (Boolean) value(hdr) && available.test(hdr);
        }
    }
}
