package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.renderer.presentation.RtToneLut;
import dev.comfyfluffy.caustica.renderer.presentation.RtToneMapping;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionValues;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtToneMappingTest {
    private static OptionValues options(Map<Option<?>, Object> overrides) {
        return new OptionValues() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T get(Option<T> option) {
                return (T) overrides.getOrDefault(option, option.defaultValue());
            }
        };
    }

    private static Option<Float> sdrControl(RtToneMapping.SdrMode mode, int index) {
        return RendererOptions.Rt.Tonemap.SDR_CONTROLS.get(mode).get(index);
    }

    private static Option<Float> hdrControl(RtToneMapping.HdrMode mode, int index) {
        return RendererOptions.Rt.Tonemap.HDR_CONTROLS.get(mode).get(index);
    }

    @Test
    void everyConfigNamePassesOptionNormalization() {
        for (RtToneMapping.SdrMode mode : RtToneMapping.SdrMode.values()) {
            String name = RendererOptions.Rt.Tonemap.SDR_MAPPER.normalize(mode.configName());
            assertEquals(mode, RtToneMapping.SdrMode.of(name));
        }
        for (RtToneMapping.HdrMode mode : RtToneMapping.HdrMode.values()) {
            String name = RendererOptions.Rt.Tonemap.HDR_MAPPER.normalize(mode.configName());
            assertEquals(mode, RtToneMapping.HdrMode.of(name));
        }
        assertEquals(RtToneMapping.sdrConfigNames(), RendererOptions.Rt.Tonemap.SDR_MAPPER.choices());
        assertEquals(RtToneMapping.hdrConfigNames(), RendererOptions.Rt.Tonemap.HDR_MAPPER.choices());
    }

    @Test
    void theSuiteIsSelectableWithPsychoV31AsTheDefault() {
        assertEquals(List.of("agx", "pbr-neutral", "reinhard", "aces2.0", "aces", "lottes", "uncharted2",
                        "gt", "psychovisual", "prism", "reinhard-jodie", "psychov31", "psychov30",
                        "psychov69", "psychov24"),
                RtToneMapping.sdrConfigNames());
        assertEquals(List.of("caustica", "aces2.0", "psychovisual", "prism", "bt2390", "psychov31",
                        "psychov30", "psychov69", "psychov24"),
                RtToneMapping.hdrConfigNames());
        assertEquals("psychov31", RendererOptions.Rt.Tonemap.SDR_MAPPER.defaultValue());
        assertEquals("psychov31", RendererOptions.Rt.Tonemap.HDR_MAPPER.defaultValue());
    }

    @Test
    void modeIdsMirrorTheDisplayShaderSpecializationConstants() {
        Map<RtToneMapping.SdrMode, Integer> sdr = new LinkedHashMap<>();
        for (RtToneMapping.SdrMode mode : RtToneMapping.SdrMode.values()) sdr.put(mode, mode.id());
        assertEquals(Map.ofEntries(
                Map.entry(RtToneMapping.SdrMode.ACES_2_0, 0), Map.entry(RtToneMapping.SdrMode.AGX, 1),
                Map.entry(RtToneMapping.SdrMode.PBR_NEUTRAL, 2), Map.entry(RtToneMapping.SdrMode.REINHARD, 3),
                Map.entry(RtToneMapping.SdrMode.ACES, 4), Map.entry(RtToneMapping.SdrMode.LOTTES, 5),
                Map.entry(RtToneMapping.SdrMode.UNCHARTED_2, 6), Map.entry(RtToneMapping.SdrMode.GT, 7),
                Map.entry(RtToneMapping.SdrMode.PSYCHOV24, 8), Map.entry(RtToneMapping.SdrMode.PSYCHOV31, 9),
                Map.entry(RtToneMapping.SdrMode.PSYCHOVISUAL, 10), Map.entry(RtToneMapping.SdrMode.PRISM, 11),
                Map.entry(RtToneMapping.SdrMode.REINHARD_JODIE, 12), Map.entry(RtToneMapping.SdrMode.PSYCHOV30, 13),
                Map.entry(RtToneMapping.SdrMode.PSYCHOV69, 14)), sdr);
        Map<RtToneMapping.HdrMode, Integer> hdr = new LinkedHashMap<>();
        for (RtToneMapping.HdrMode mode : RtToneMapping.HdrMode.values()) hdr.put(mode, mode.id());
        assertEquals(Map.of(
                RtToneMapping.HdrMode.ACES_2_0, 0, RtToneMapping.HdrMode.CAUSTICA, 1,
                RtToneMapping.HdrMode.PSYCHOV24, 2, RtToneMapping.HdrMode.BT2390, 3,
                RtToneMapping.HdrMode.PSYCHOV31, 4, RtToneMapping.HdrMode.PSYCHOVISUAL, 5,
                RtToneMapping.HdrMode.PRISM, 6, RtToneMapping.HdrMode.PSYCHOV30, 7,
                RtToneMapping.HdrMode.PSYCHOV69, 8), hdr);
    }

    @Test
    void quickTogglePreselectsTakeEverySdrAndHdrName() {
        List<String> expected = new ArrayList<>(RtToneMapping.sdrConfigNames());
        expected.addAll(List.of("caustica", "bt2390"));
        assertEquals(expected, RtToneMapping.quickToggleNames());
        for (String name : RtToneMapping.quickToggleNames()) {
            assertEquals(name, RendererOptions.Rt.Tonemap.QUICK_TOGGLE_A.normalize(name));
            assertEquals(name, RendererOptions.Rt.Tonemap.QUICK_TOGGLE_B.normalize(name));
        }
        assertEquals("aces2.0", RendererOptions.Rt.Tonemap.QUICK_TOGGLE_A.defaultValue());
        assertEquals("psychov31", RendererOptions.Rt.Tonemap.QUICK_TOGGLE_B.defaultValue());
    }

    @Test
    void perMapperControlsLiveUnderTheirOutputAndMapper() {
        Set<Option<?>> registered = Set.copyOf(RendererOptions.settings());
        RendererOptions.Rt.Tonemap.SDR_CONTROLS.forEach((mode, controls) -> {
            assertTrue(controls.size() <= RtToneMapping.Parameters.COUNT, mode.toString());
            for (Option<Float> control : controls) {
                assertTrue(control.id().startsWith("sdr." + mode.configName() + "."), control.id());
                assertEquals(control.id(), control.tomlPath());
                assertTrue(registered.contains(control), control.id());
            }
        });
        RendererOptions.Rt.Tonemap.HDR_CONTROLS.forEach((mode, controls) -> {
            assertTrue(controls.size() <= RtToneMapping.Parameters.COUNT, mode.toString());
            for (Option<Float> control : controls) {
                assertTrue(control.id().startsWith("hdr." + mode.configName() + "."), control.id());
                assertEquals(control.id(), control.tomlPath());
                assertTrue(registered.contains(control), control.id());
            }
        });
        assertEquals(EnumSet.of(RtToneMapping.SdrMode.ACES_2_0),
                EnumSet.complementOf(EnumSet.copyOf(RendererOptions.Rt.Tonemap.SDR_CONTROLS.keySet())));
        assertEquals(EnumSet.of(RtToneMapping.HdrMode.ACES_2_0, RtToneMapping.HdrMode.CAUSTICA,
                        RtToneMapping.HdrMode.BT2390),
                EnumSet.complementOf(EnumSet.copyOf(RendererOptions.Rt.Tonemap.HDR_CONTROLS.keySet())));
        assertEquals("caustica.rt.sdr.pbrNeutral.startCompression",
                sdrControl(RtToneMapping.SdrMode.PBR_NEUTRAL, 0).systemPropertyKey());
        assertEquals("caustica.rt.hdr.psychov69.sourceAwareness",
                hdrControl(RtToneMapping.HdrMode.PSYCHOV69, 7).systemPropertyKey());
    }

    @Test
    void defaultsCapturePsychoV31WithTheReferenceHdrShoulder() {
        var settings = RtRenderSettings.capture(options(Map.of()), true);
        assertEquals(RtToneMapping.SdrMode.PSYCHOV31, settings.toneMapping().sdrMode());
        assertEquals(RtToneMapping.HdrMode.PSYCHOV31, settings.toneMapping().hdrMode());
        assertEquals(RtToneMapping.Parameters.of(1.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f),
                settings.toneMapping().sdrParameters());
        assertEquals(RtToneMapping.Parameters.of(1.5f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f),
                settings.toneMapping().hdrParameters());
        assertEquals(200.0f, settings.toneMapping().paperWhiteNits());
        assertEquals(5.0f, settings.toneMapping().headroom());
    }

    @Test
    void captureRecordsTheSelectedMappersControlsInShaderOrder() {
        var overrides = new HashMap<Option<?>, Object>();
        overrides.put(RendererOptions.Rt.Tonemap.SDR_MAPPER, "psychov31");
        overrides.put(RendererOptions.Rt.Tonemap.HDR_MAPPER, "prism");
        overrides.put(sdrControl(RtToneMapping.SdrMode.PSYCHOV31, 6), 0.0f);
        overrides.put(hdrControl(RtToneMapping.HdrMode.PRISM, 1), 0.25f);
        var settings = RtRenderSettings.capture(options(overrides), true);
        assertEquals(RtToneMapping.SdrMode.PSYCHOV31, settings.toneMapping().sdrMode());
        assertEquals(RtToneMapping.HdrMode.PRISM, settings.toneMapping().hdrMode());
        assertEquals(RtToneMapping.Parameters.of(1.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f),
                settings.toneMapping().sdrParameters());
        assertEquals(RtToneMapping.Parameters.of(4.0f, 0.25f, 0.5041f, 0.3574f, 0.3253f, 0.5329f,
                0.1984f, 0.1556f), settings.toneMapping().hdrParameters());
    }

    @Test
    void psychoV31HdrDefaultsToTheReferenceShoulder() {
        var overrides = new HashMap<Option<?>, Object>();
        overrides.put(RendererOptions.Rt.Tonemap.HDR_MAPPER, "psychov31");
        var settings = RtRenderSettings.capture(options(overrides), true);
        assertEquals(RtToneMapping.Parameters.of(1.5f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f),
                settings.toneMapping().hdrParameters());
    }

    @Test
    void psychoVisualAndPsychoV24ReadTheirOwnControls() {
        var overrides = new HashMap<Option<?>, Object>();
        overrides.put(RendererOptions.Rt.Tonemap.SDR_MAPPER, "psychovisual");
        overrides.put(RendererOptions.Rt.Tonemap.HDR_MAPPER, "psychov24");
        overrides.put(sdrControl(RtToneMapping.SdrMode.PSYCHOVISUAL, 6), 0.5f);
        overrides.put(hdrControl(RtToneMapping.HdrMode.PSYCHOV24, 3), 0.75f);
        var settings = RtRenderSettings.capture(options(overrides), true);
        assertEquals(RtToneMapping.Parameters.of(1.2f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.5f),
                settings.toneMapping().sdrParameters());
        assertEquals(RtToneMapping.Parameters.of(0.0f, 1.0f, 1.0f, 0.75f, 1.0f, 1.0f),
                settings.toneMapping().hdrParameters());
        assertEquals("sdr.psychovisual.hue-restore", sdrControl(RtToneMapping.SdrMode.PSYCHOVISUAL, 6).id());
        assertEquals("hdr.psychov24.shadows", hdrControl(RtToneMapping.HdrMode.PSYCHOV24, 3).id());
    }

    @Test
    void headroomIsThePeakOverPaperWhite() {
        var overrides = new HashMap<Option<?>, Object>();
        overrides.put(RendererOptions.Rt.Tonemap.PAPER_WHITE_NITS, 250.0f);
        overrides.put(RendererOptions.Rt.Hdr.PEAK_NITS, 800.0f);
        var settings = RtRenderSettings.capture(options(overrides), true);
        assertEquals(250.0f, settings.toneMapping().paperWhiteNits());
        assertEquals(3.2f, settings.toneMapping().headroom());
    }

    @Test
    void paperWhiteIsClampedToThePeak() {
        var overrides = new HashMap<Option<?>, Object>();
        overrides.put(RendererOptions.Rt.Tonemap.PAPER_WHITE_NITS, 500.0f);
        overrides.put(RendererOptions.Rt.Hdr.PEAK_NITS, 400.0f);
        var settings = RtRenderSettings.capture(options(overrides), true);
        assertEquals(400.0f, settings.toneMapping().paperWhiteNits());
        assertEquals(1.0f, settings.toneMapping().headroom());
    }

    @Test
    void aces20RendersThroughTheNearestPackagedHdrLut() {
        assertEquals(500, RtToneLut.nearestHdrLutNits(80.0f));
        assertEquals(500, RtToneLut.nearestHdrLutNits(750.0f));
        assertEquals(1000, RtToneLut.nearestHdrLutNits(800.0f));
        assertEquals(4000, RtToneLut.nearestHdrLutNits(5000.0f));
        var overrides = new HashMap<Option<?>, Object>();
        overrides.put(RendererOptions.Rt.Tonemap.HDR_MAPPER, "aces2.0");
        overrides.put(RendererOptions.Rt.Hdr.PEAK_NITS, 1400.0f);
        assertEquals(1000, RtRenderSettings.capture(options(overrides), true).hdrLutNits());
        assertEquals(1000, RtRenderSettings.effectivePeakNits(options(overrides)));
    }

    @Test
    void analyticHdrMappersReportTheConfiguredPeak() {
        var overrides = new HashMap<Option<?>, Object>();
        overrides.put(RendererOptions.Rt.Tonemap.HDR_MAPPER, "bt2390");
        overrides.put(RendererOptions.Rt.Hdr.PEAK_NITS, 1400.0f);
        assertEquals(1400, RtRenderSettings.effectivePeakNits(options(overrides)));
        assertEquals(7.0f, RtRenderSettings.capture(options(overrides), true).toneMapping().headroom());
    }

    @Test
    void peakNitsLoadsAnyValueInItsRange() {
        assertEquals(800.0f, RendererOptions.Rt.Hdr.PEAK_NITS.normalize(800));
        assertEquals(80.0f, RendererOptions.Rt.Hdr.PEAK_NITS.normalize(10.0));
        assertEquals(5000.0f, RendererOptions.Rt.Hdr.PEAK_NITS.normalize(10000.0));
    }

    @Test
    void optionIdsAreUnique() {
        List<String> ids = RendererOptions.settings().stream().map(Option::id).toList();
        assertEquals(ids.size(), Set.copyOf(ids).size());
    }
}
