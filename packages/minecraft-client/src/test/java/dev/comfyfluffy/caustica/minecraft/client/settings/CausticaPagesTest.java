package dev.comfyfluffy.caustica.minecraft.client.settings;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftOptions;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaConfig;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaOptions;
import dev.comfyfluffy.caustica.renderer.presentation.RtToneMapping;
import dev.comfyfluffy.caustica.renderer.runtime.RendererOptions;
import dev.comfyfluffy.caustica.renderer.runtime.RendererOptions.Rt.Tonemap;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CausticaPagesTest {
    private static final Option<Boolean> HDR = Option.bool("hdr.enabled", false);
    private static final Option<String> ROUTE =
            Option.stringChoice("denoising.route", "raw", List.of("raw"));
    private static final Option<Float> CONTRAST = Option.range("tonemap.contrast", 0, 2, 1);
    private static final Option<Float> PAPER_WHITE = Option.range("hdr.paper-white-nits", 80, 500, 200);
    private static final Option<Float> KEY = Option.range("exposure.key", 0, 1, 0.18f);
    private static final Option<Float> FUTURE_METER = Option.range("exposure.future-meter", 0, 1, 0.5f);

    @TempDir Path directory;

    /** A renderer feature that declares options the pages have never heard of, known only by their ids. */
    private CausticaPages.Engine synthetic(Predicate<Option<?>> available, Option<?>... options) {
        SettingsRegistry registry = registry(options);
        CausticaOptions store = CausticaOptions.load(directory.resolve("synthetic.toml"), registry);
        return new CausticaPages.Engine(registry, store, available);
    }

    private static SettingsRegistry registry(Option<?>... options) {
        SettingsRegistry registry = new SettingsRegistry();
        var feature = registry.feature(CausticaConfig.FEATURE);
        Stream.of(options).map(Option::group).filter(Objects::nonNull).distinct().forEach(feature::group);
        feature.options(List.of(options)).register();
        return registry;
    }

    private CausticaPages.Engine renderer() {
        SettingsRegistry registry = new SettingsRegistry();
        MinecraftOptions.register(registry);
        CausticaOptions store = CausticaOptions.load(directory.resolve("renderer.toml"), registry);
        return new CausticaPages.Engine(registry, store, ignored -> true);
    }

    private static List<String> ids(SettingsPage page) {
        return page.allControls().stream().map(SettingControl::id).toList();
    }

    private static List<String> ids(SettingGroup section) {
        return section.rows().stream().map(SettingControl::id).toList();
    }

    private static List<String> sectionIds(SettingsPage page) {
        return page.sections().stream().map(SettingGroup::id).toList();
    }

    private static SettingGroup section(SettingsPage page, String id) {
        return page.sections().stream().filter(section -> section.id().equals(id)).findFirst().orElseThrow();
    }

    /** Selects ACES 2.0 on both outputs, the LUT path, which has no per-mapper controls. */
    private static CausticaPages.Engine lutMappers(CausticaPages.Engine engine) {
        var store = engine.store();
        store.apply(CausticaConfig.FEATURE, Tonemap.SDR_MAPPER, RtToneMapping.SdrMode.ACES_2_0.configName());
        store.apply(CausticaConfig.FEATURE, Tonemap.HDR_MAPPER, RtToneMapping.HdrMode.ACES_2_0.configName());
        return engine;
    }

    @Test
    void theHdrPathAppliesOnlyWhileHdrIsRequestedAndPresentable() {
        Option<?>[] options = {HDR, Tonemap.SDR_MAPPER, Tonemap.HDR_MAPPER, CONTRAST, PAPER_WHITE, KEY};
        CausticaPages.Engine sdr = lutMappers(synthetic(ignored -> true, options));
        SettingsPage page = CausticaPages.toneMapping(sdr);
        assertEquals(List.of("sdr-output"), sectionIds(page));
        assertEquals(List.of("sdr.tone-mapper", "hdr.enabled", "tonemap.contrast"), ids(page));
        assertEquals(Set.of("sdr.tone-mapper"), page.wide());

        CausticaPages.Engine unavailable = lutMappers(synthetic(option -> option != HDR, options));
        unavailable.store().apply(CausticaConfig.FEATURE, HDR, true);
        assertEquals(List.of("sdr-output"), sectionIds(CausticaPages.toneMapping(unavailable)));

        CausticaPages.Engine hdr = lutMappers(synthetic(ignored -> true, options));
        hdr.store().apply(CausticaConfig.FEATURE, HDR, true);
        SettingsPage hdrPage = CausticaPages.toneMapping(hdr);
        assertEquals(List.of("hdr-output"), sectionIds(hdrPage));
        assertEquals(List.of("hdr.tone-mapper", "hdr.enabled", "tonemap.contrast", "hdr.paper-white-nits"),
                ids(hdrPage));
        assertEquals(Set.of("hdr.tone-mapper"), hdrPage.wide());
        assertNotEquals(page.shape(), hdrPage.shape());
    }

    @Test
    void theRendererToneMappingPageLeadsWithTheMapperAndHdrOutput() {
        CausticaPages.Engine engine = lutMappers(renderer());
        List<String> sdr = ids(CausticaPages.toneMapping(engine));
        assertEquals(List.of("sdr.tone-mapper", "hdr.enabled", "tonemap.gamma"), sdr.subList(0, 3));
        assertTrue(sdr.stream().noneMatch(id -> id.startsWith("hdr.") && !id.equals("hdr.enabled")),
                sdr::toString);

        engine.store().apply(CausticaConfig.FEATURE, RendererOptions.Rt.Hdr.ENABLED, true);
        List<String> hdr = ids(CausticaPages.toneMapping(engine));
        assertEquals(List.of("hdr.tone-mapper", "hdr.enabled", "tonemap.gamma"), hdr.subList(0, 3));
        assertTrue(hdr.containsAll(List.of("hdr.paper-white-nits", "hdr.ui-nits", "hdr.peak-nits")),
                hdr::toString);
        assertTrue(hdr.stream().noneMatch(id -> id.startsWith("sdr.")), hdr::toString);
    }

    @Test
    void everyMapperShowsExactlyItsDeclaredControlsUnderItsName() {
        CausticaPages.Engine engine = renderer();
        for (RtToneMapping.SdrMode mode : RtToneMapping.SdrMode.values()) {
            engine.store().apply(CausticaConfig.FEATURE, Tonemap.SDR_MAPPER, mode.configName());
            assertMapperSection(CausticaPages.toneMapping(engine), "sdr", mode.configName(),
                    Tonemap.SDR_CONTROLS.getOrDefault(mode, List.of()));
        }
        engine.store().apply(CausticaConfig.FEATURE, RendererOptions.Rt.Hdr.ENABLED, true);
        for (RtToneMapping.HdrMode mode : RtToneMapping.HdrMode.values()) {
            engine.store().apply(CausticaConfig.FEATURE, Tonemap.HDR_MAPPER, mode.configName());
            assertMapperSection(CausticaPages.toneMapping(engine), "hdr", mode.configName(),
                    Tonemap.HDR_CONTROLS.getOrDefault(mode, List.of()));
        }
    }

    private static void assertMapperSection(SettingsPage page, String path, String mapper,
                                            List<Option<Float>> controls) {
        if (controls.isEmpty()) {
            assertEquals(List.of(path + "-output"), sectionIds(page), mapper);
            return;
        }
        assertEquals(List.of(path + "-output", path + "." + mapper), sectionIds(page), mapper);
        SettingGroup section = page.sections().getLast();
        assertEquals(controls.stream().map(Option::id).toList(), ids(section), mapper);
        TranslatableContents title = (TranslatableContents) section.title().getContents();
        assertEquals("caustica.page.tone-mapping.mapper", title.getKey());
        assertEquals("caustica.setting." + path + ".tone-mapper." + mapper,
                ((TranslatableContents) ((Component) title.getArgs()[0]).getContents()).getKey());
        assertTrue(ids(page.sections().getFirst()).stream().noneMatch(id -> id.startsWith(path + "." + mapper)),
                mapper);
    }

    @Test
    void everydayExposureRowsLeadAndEveryOtherExposureRowIsFoldedUnderAdvanced() {
        SettingsPage page = CausticaPages.exposure(renderer());
        SettingGroup basic = section(page, "exposure");
        SettingGroup advanced = section(page, "exposure.advanced");
        assertFalse(basic.advanced());
        assertTrue(advanced.advanced());
        assertEquals(List.of("exposure.mode", "exposure.response", "exposure.manual-ev", "exposure.key",
                "exposure.adapt-darken",
                "exposure.adapt-brighten"), ids(basic));
        assertEquals(List.of("exposure.low-percentile", "exposure.high-percentile", "exposure.stride",
                "exposure.center-weight-sigma", "exposure.center-weight-floor", "exposure.environment-weight-cap",
                "exposure.emissive-weight-cap", "exposure.pre-exposure"), ids(advanced));

        SettingsPage synthetic = CausticaPages.exposure(synthetic(ignored -> true, HDR, KEY, FUTURE_METER));
        assertEquals(List.of("exposure.key"), ids(section(synthetic, "exposure")));
        assertEquals(List.of("exposure.future-meter"), ids(section(synthetic, "exposure.advanced")));
    }

    private static SettingsPage linkedPage(List<CausticaPages.Link> links, String id) {
        return links.stream().filter(link -> link.id().equals(id)).findFirst().orElseThrow().page().get();
    }

    @Test
    void theRadianceCachePageLeadsWithTheCacheSwitchAndFoldsItsTuningUnderAdvanced() {
        SettingsPage page = CausticaPages.radianceCache(renderer());
        SettingGroup basic = section(page, "radiance-cache");
        SettingGroup advanced = section(page, "radiance-cache.advanced");
        assertFalse(basic.advanced());
        assertTrue(advanced.advanced());
        assertEquals(List.of("sharc.enabled"), ids(basic));
        assertEquals(List.of("sharc.cache-exponent", "sharc.update-tile-size", "sharc.accumulation-frames",
                "sharc.stale-frames", "sharc.scene-scale", "sharc.grid-logarithm-base", "sharc.grid-level-bias",
                "sharc.radiance-scale", "sharc.roughness-threshold", "sharc.anti-firefly"), ids(advanced));
    }

    @Test
    void everySharcOptionIsOnExactlyOneLinkedPageWithTheCacheViewAmongTheDiagnostics() {
        SettingsRegistry registry = new SettingsRegistry();
        MinecraftOptions.register(registry);
        CausticaOptions store = CausticaOptions.load(directory.resolve("sharc.toml"), registry);
        List<CausticaPages.Link> links = CausticaPages.links(registry, store, ignored -> true);
        for (Option<?> option : RendererOptions.Rt.Sharc.OPTIONS) {
            List<String> pages = links.stream().filter(link -> ids(link.page().get()).contains(option.id()))
                    .map(CausticaPages.Link::id).toList();
            String owner = option == RendererOptions.Rt.Sharc.PRIMARY_SURFACE_DEBUG ? "overlays" : "radiance-cache";
            assertEquals(List.of(owner), pages, option.id());
        }
        assertTrue(ids(section(linkedPage(links, "overlays"), "debug")).contains("sharc.primary-surface-debug"));
    }

    /** A build without the SHaRC SDK, or a device without its features, reports every SHaRC option unavailable. */
    @Test
    void sharcRowsStayVisibleButDisabledWhereSharcCannotRun() {
        SettingsRegistry registry = new SettingsRegistry();
        MinecraftOptions.register(registry);
        CausticaOptions store = CausticaOptions.load(directory.resolve("stock.toml"), registry);
        Predicate<Option<?>> withoutSharc = option -> !RendererOptions.Rt.Sharc.OPTIONS.contains(option);
        List<CausticaPages.Link> links = CausticaPages.links(registry, store, withoutSharc);

        SettingsPage cache = linkedPage(links, "radiance-cache");
        assertEquals(RendererOptions.Rt.Sharc.OPTIONS.size() - 1, cache.allControls().size());
        cache.allControls().forEach(row -> assertFalse(row.enabled(), row.id()));
        assertEquals(LangKeys.optionTooltip(CausticaConfig.FEATURE, RendererOptions.Rt.Sharc.ENABLED),
                section(cache, "radiance-cache").rows().getFirst().tooltip());
        linkedPage(links, "overlays").allControls().forEach(row ->
                assertEquals(!row.id().equals("sharc.primary-surface-debug"), row.enabled(), row.id()));

        store.apply(CausticaConfig.FEATURE, RendererOptions.Rt.Sharc.CACHE_EXPONENT, 20);
        cache.reset();
        assertEquals(20, store.options(CausticaConfig.FEATURE).get(RendererOptions.Rt.Sharc.CACHE_EXPONENT));

        SettingsPage available = linkedPage(CausticaPages.links(registry, store, ignored -> true), "radiance-cache");
        available.allControls().forEach(row -> assertTrue(row.enabled(), row.id()));
    }

    @Test
    void theUpscalingPageShowsOnlyTheSelectedRoutesSettings() {
        CausticaPages.Engine engine = renderer();
        var route = RendererOptions.Rt.Denoising.ROUTE;
        engine.store().apply(CausticaConfig.FEATURE, route, "ray_reconstruction");
        SettingsPage rayReconstruction = CausticaPages.upscaling(engine);
        assertEquals(List.of("denoiser", "dlss-rr", "latency"), sectionIds(rayReconstruction));
        List<String> rayReconstructionRows = ids(section(rayReconstruction, "dlss-rr"));
        assertEquals("dlss-rr.quality", rayReconstructionRows.getFirst());
        assertTrue(rayReconstructionRows.stream().allMatch(id -> id.startsWith("dlss-rr.")), rayReconstructionRows::toString);
        assertEquals(List.of("frame-generation.enabled", "reflex.enabled"),
                ids(section(rayReconstruction, "latency")));

        engine.store().apply(CausticaConfig.FEATURE, route, "temporal_denoiser");
        SettingsPage temporal = CausticaPages.upscaling(engine);
        assertEquals(List.of("denoiser", "nrd", "latency"), sectionIds(temporal));
        assertEquals(List.of("denoising.method", "dlss-sr.quality"), ids(section(temporal, "nrd")));

        engine.store().apply(CausticaConfig.FEATURE, route, "raw");
        assertEquals(List.of("denoiser", "latency"), sectionIds(CausticaPages.upscaling(engine)));
        assertFalse(ids(rayReconstruction).contains("dlss-rr.preset"));
    }

    /** Every page in every HDR, tone-mapper and denoiser state, so rows that appear conditionally are included. */
    static List<SettingsPage> everyPageState(CausticaPages.Engine engine) {
        List<SettingsPage> pages = new ArrayList<>();
        for (boolean hdr : List.of(false, true)) {
            engine.store().apply(CausticaConfig.FEATURE, RendererOptions.Rt.Hdr.ENABLED, hdr);
            Option<String> selector = hdr ? Tonemap.HDR_MAPPER : Tonemap.SDR_MAPPER;
            for (String mapper : selector.choices()) {
                engine.store().apply(CausticaConfig.FEATURE, selector, mapper);
                pages.add(CausticaPages.toneMapping(engine));
            }
        }
        for (Object route : RendererOptions.Rt.Denoising.ROUTE.choices()) {
            engine.store().apply(CausticaConfig.FEATURE, RendererOptions.Rt.Denoising.ROUTE, route);
            pages.add(CausticaPages.upscaling(engine));
        }
        pages.addAll(List.of(CausticaPages.exposure(engine), CausticaPages.radianceCache(engine),
                CausticaPages.overlays(engine)));
        return pages;
    }

    @Test
    void everyGroupedOrPageOwnedRendererOptionIsReachable() {
        SettingsRegistry registry = new SettingsRegistry();
        MinecraftOptions.register(registry);
        CausticaOptions store = CausticaOptions.load(directory.resolve("reachable.toml"), registry);
        CausticaPages.Engine engine = new CausticaPages.Engine(registry, store, ignored -> true);
        Set<String> reachable = new HashSet<>();
        CausticaPages.videoSettings(registry, store, ignored -> true).forEach(row -> reachable.add(row.id()));
        everyPageState(engine).forEach(page -> reachable.addAll(ids(page)));

        List<String> missing = new ArrayList<>();
        for (Option<?> option : MinecraftOptions.allSettings()) {
            boolean pageOwned = List.of("exposure.", "tonemap.", "sdr.", "hdr.").stream()
                    .anyMatch(option.id()::startsWith);
            boolean hasRow = OptionControls.of(store, CausticaConfig.FEATURE, option) != null;
            if (hasRow && (option.group() != null || pageOwned) && !reachable.contains(option.id())) {
                missing.add(option.id());
            }
        }
        assertTrue(missing.isEmpty(), "unreachable options: " + missing);
    }

    @Test
    void noRowAppearsOnTwoPages() {
        CausticaPages.Engine engine = renderer();
        engine.store().apply(CausticaConfig.FEATURE, RendererOptions.Rt.Hdr.ENABLED, true);
        List<SettingsPage> pages = List.of(CausticaPages.toneMapping(engine), CausticaPages.exposure(engine),
                CausticaPages.upscaling(engine), CausticaPages.radianceCache(engine), CausticaPages.overlays(engine));
        Set<String> seen = new HashSet<>();
        for (SettingsPage page : pages) {
            for (String id : ids(page)) {
                assertTrue(seen.add(id), id + " appears on two pages");
            }
        }
        assertEquals(List.of("entities.glow.enabled", "overlay.block-outline.enabled", "composite.debug-view",
                "screenshots.exr-enabled", "sharc.primary-surface-debug"), ids(CausticaPages.overlays(engine)));
    }

    @Test
    void videoSettingsShowsTheEverydayRowsAndOpensEveryPage() {
        SettingsRegistry registry = new SettingsRegistry();
        MinecraftOptions.register(registry);
        ResourceId bloom = ResourceId.of("caustica", "bloom");
        registry.feature(bloom).group("bloom")
                .option(Option.bool("bloom.enabled", true).inGroupAsHeader("bloom"))
                .option(Option.range("bloom.strength", 0, 2, 0.35f).inGroup("bloom")).register();
        registry.feature(ResourceId.of("test", "empty")).register();
        CausticaOptions store = CausticaOptions.load(directory.resolve("links.toml"), registry);

        assertEquals(List.of(CausticaPages.RAY_TRACING, "composite.max-bounces", "composite.water-waves",
                        "entities.enabled", "particles.enabled"),
                CausticaPages.videoSettings(registry, store, ignored -> true).stream()
                        .map(SettingControl::id).toList());
        List<CausticaPages.Link> links = CausticaPages.links(registry, store, ignored -> true);
        assertEquals(List.of("tone-mapping", "exposure", "upscaling", "radiance-cache", bloom.toString(), "overlays"),
                links.stream().map(CausticaPages.Link::id).toList());

        SettingsPage page = links.get(4).page().get();
        SettingGroup section = page.sections().getFirst();
        assertEquals(List.of("bloom.enabled", "bloom.strength"), ids(section));
        assertSame(section.rows().getFirst(), section.gate());
        section.gate().set(false);
        assertTrue(section.editable(section.gate()));
        assertFalse(section.editable(section.rows().getLast()));
    }

    @Test
    void aRendererPageWithoutRowsGetsNoButton() {
        SettingsRegistry registry = registry(HDR, Tonemap.SDR_MAPPER, Tonemap.HDR_MAPPER, ROUTE);
        CausticaOptions store = CausticaOptions.load(directory.resolve("sparse.toml"), registry);
        List<CausticaPages.Link> links = CausticaPages.links(registry, store, ignored -> true);
        assertEquals(List.of("tone-mapping", "upscaling"), links.stream().map(CausticaPages.Link::id).toList());
    }

    @Test
    void anExtensionPageKeepsUngroupedRowsAndSkipsOptionsWithoutRows() {
        SettingsRegistry registry = new SettingsRegistry();
        var loose = registry.feature(ResourceId.of("test", "loose")).option(Option.bool("loose", true)).register();
        var pathOnly = registry.feature(ResourceId.of("test", "path")).option(Option.optionalString("path"))
                .register();
        CausticaOptions store = CausticaOptions.load(directory.resolve("extensions.toml"), registry);

        SettingsPage page = CausticaPages.feature(loose, store);
        assertEquals(List.of("other"), sectionIds(page));
        assertEquals(List.of("loose"), ids(page));
        assertTrue(CausticaPages.feature(pathOnly, store).sections().isEmpty());
    }

    @Test
    void resettingAPageRestoresEveryEditableRow() {
        CausticaPages.Engine engine = lutMappers(synthetic(ignored -> true, HDR, Tonemap.SDR_MAPPER,
                Tonemap.HDR_MAPPER, CONTRAST, PAPER_WHITE, KEY, FUTURE_METER));
        engine.store().apply(CausticaConfig.FEATURE, HDR, true);
        engine.store().apply(CausticaConfig.FEATURE, PAPER_WHITE, 300f);
        engine.store().apply(CausticaConfig.FEATURE, KEY, 0.5f);
        engine.store().apply(CausticaConfig.FEATURE, FUTURE_METER, 0.9f);
        CausticaPages.toneMapping(engine).reset();
        CausticaPages.exposure(engine).reset();

        assertFalse((Boolean) engine.value(HDR));
        assertEquals(200f, engine.value(PAPER_WHITE));
        assertEquals(0.18f, engine.value(KEY));
        assertEquals(0.5f, engine.value(FUTURE_METER));
        assertEquals(List.of("sdr-output"), sectionIds(CausticaPages.toneMapping(engine)));
    }

    @Test
    void resettingTheToneMappingPageRestoresTheMapperAndItsControls() {
        CausticaPages.Engine engine = renderer();
        Option<Float> saturation = Tonemap.SDR_CONTROLS.get(RtToneMapping.SdrMode.AGX).getLast();
        engine.store().apply(CausticaConfig.FEATURE, Tonemap.SDR_MAPPER, RtToneMapping.SdrMode.AGX.configName());
        engine.store().apply(CausticaConfig.FEATURE, saturation, 2.0f);
        CausticaPages.toneMapping(engine).reset();

        assertEquals(Tonemap.SDR_MAPPER.defaultValue(), engine.value(Tonemap.SDR_MAPPER));
        assertEquals(saturation.defaultValue(), engine.value(saturation));
    }
}
