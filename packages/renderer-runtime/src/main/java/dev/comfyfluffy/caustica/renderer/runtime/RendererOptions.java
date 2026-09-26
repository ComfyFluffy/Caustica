package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.renderer.presentation.RtToneMapping;
import dev.comfyfluffy.caustica.settings.Option;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Renderer-owned option declarations; registration and persistence belong to the host. */
public final class RendererOptions {
    private RendererOptions() { }

    public static List<Option<?>> settings() {
        List<Option<?>> settings = new ArrayList<>(List.of(
                Rt.Composite.DEBUG_VIEW, Rt.Composite.MAX_BOUNCES,
                Rt.Composite.JITTER_SIGN_X, Rt.Composite.JITTER_SIGN_Y,
                Rt.DlssRr.PRESET, Rt.DlssRr.QUALITY, Rt.DlssRr.RESPONSIVITY, Rt.DlssSr.PRESET, Rt.DlssSr.QUALITY,
                Rt.Denoising.ROUTE, Rt.Denoising.METHOD, Rt.Fg.ENABLED,
                Rt.Reflex.ENABLED, Rt.Reflex.LOW_LATENCY_BOOST, Rt.Reflex.MINIMUM_INTERVAL_US,
                Rt.Exposure.MODE, Rt.Exposure.RESPONSE, Rt.Exposure.MANUAL_EV, Rt.Exposure.KEY,
                Rt.Exposure.ADAPT_DARKEN, Rt.Exposure.ADAPT_BRIGHTEN,
                Rt.Exposure.LOW_PERCENTILE, Rt.Exposure.HIGH_PERCENTILE, Rt.Exposure.STRIDE,
                Rt.Exposure.CENTER_WEIGHT_SIGMA, Rt.Exposure.CENTER_WEIGHT_FLOOR,
                Rt.Exposure.ENVIRONMENT_WEIGHT_CAP, Rt.Exposure.EMISSIVE_WEIGHT_CAP, Rt.Exposure.PRE_EXPOSURE,
                Rt.Tonemap.GAMMA,
                Rt.Tonemap.SDR_MAPPER, Rt.Tonemap.HDR_MAPPER, Rt.Tonemap.PAPER_WHITE_NITS,
                Rt.Tonemap.QUICK_TOGGLE_A, Rt.Tonemap.QUICK_TOGGLE_B,
                Rt.Screenshots.EXR_ENABLED,
                Rt.Hdr.ENABLED, Rt.Hdr.UI_NITS, Rt.Hdr.PEAK_NITS));
        Rt.Tonemap.SDR_CONTROLS.values().forEach(settings::addAll);
        Rt.Tonemap.HDR_CONTROLS.values().forEach(settings::addAll);
        settings.addAll(Rt.Sharc.OPTIONS);
        return List.copyOf(settings);
    }

    public static final class Rt {
        private Rt() { }
        public static final class Composite {
            private Composite() { }
            public static final Option<Integer> DEBUG_VIEW = clampedInt("caustica.rt.debugView", "composite.debug-view", 0, 0, 15).inGroup("debug");
            public static final Option<Integer> MAX_BOUNCES = clampedInt("caustica.rt.maxBounces", "composite.max-bounces", 4, 2, 8).inGroup("quality");
            public static final Option<Float> JITTER_SIGN_X = finiteFloat("caustica.rt.jitterSignX", "composite.jitter-sign-x", 1.0f);
            public static final Option<Float> JITTER_SIGN_Y = finiteFloat("caustica.rt.jitterSignY", "composite.jitter-sign-y", -1.0f);
        }

        public static final class DlssRr {
            private DlssRr() { }
            public static final List<Integer> PRESET_STEPS = List.of(0, 4, 5);
            public static final Option<Integer> PRESET = intChoice("caustica.rt.dlssRr.preset", "dlss-rr.preset", 5, PRESET_STEPS);
            public static final List<Integer> QUALITY_STEPS = List.of(3, 0, 1, 2, 5);
            public static final Option<Integer> QUALITY = intChoice("caustica.rt.dlssRr.quality", "dlss-rr.quality", 1, QUALITY_STEPS).inGroup("upscaling");
            /**
             * Responsivity-mask value for every pixel whose history RR may reuse: 0 keeps the most accumulated
             * history, 1 favors the current frame everywhere. Sky that the camera sees directly or through
             * delta transmission is always 1.
             */
            public static final Option<Float> RESPONSIVITY = clampedFloat("caustica.rt.dlssRr.responsivity", "dlss-rr.responsivity", 0.0f, 0.0f, 1.0f).inGroup("upscaling");
        }

        public static final class DlssSr {
            private DlssSr() { }
            public static final Option<Integer> PRESET = intChoice("caustica.rt.dlssSr.preset", "dlss-sr.preset", 0, List.of(0, 10, 11, 12, 13));
            public static final List<Integer> QUALITY_STEPS = List.of(3, 0, 1, 2, 5);
            public static final Option<Integer> QUALITY = intChoice("caustica.rt.dlssSr.quality", "dlss-sr.quality", 2, QUALITY_STEPS).inGroup("upscaling");
        }

        public static final class Denoising {
            private Denoising() { }
            public static final Option<String> ROUTE = stringChoice("caustica.rt.denoisingRoute", "denoising.route", "ray_reconstruction", List.of("ray_reconstruction", "temporal_denoiser", "raw")).inGroup("upscaling");
            public static final Option<String> METHOD = stringChoice("caustica.rt.denoisingMethod", "denoising.method", "reblur", List.of("relax", "reblur")).inGroup("upscaling");
        }

        public static final class Fg {
            private Fg() { }
            public static final Option<Boolean> ENABLED = bool("caustica.rt.fg", "frame-generation.enabled", false).inGroup("upscaling");
        }

        public static final class Reflex {
            private Reflex() { }
            public static final Option<Boolean> ENABLED = bool("caustica.rt.reflex", "reflex.enabled", false).inGroup("upscaling");
            public static final Option<Boolean> LOW_LATENCY_BOOST = bool("caustica.rt.reflex.boost", "reflex.low-latency-boost", false);
            public static final Option<Integer> MINIMUM_INTERVAL_US = intAtLeast("caustica.rt.reflex.minIntervalUs", "reflex.minimum-interval-us", 0, 0);
        }

        public static final class Exposure {
            private Exposure() { }
            public static final List<String> MODES = List.of("auto", "manual");
            public static final Option<String> MODE = stringChoice("caustica.rt.exposure.mode", "exposure.mode", "auto", MODES).inGroup("exposure");
            /** Auto-exposure response names; {@code RtExposure.Response} owns their knots and bounds. */
            public static final List<String> RESPONSES = List.of("adaptation", "curve");
            public static final Option<String> RESPONSE = stringChoice("caustica.rt.exposure.response", "exposure.response", "adaptation", RESPONSES).inGroup("exposure");
            public static final Option<Float> MANUAL_EV = clampedFloat("caustica.rt.exposure.manualEv", "exposure.manual-ev", 0.0f, -15.0f, 15.0f).inGroup("exposure");
            public static final Option<Float> KEY = exposureScale("caustica.rt.exposure.key", "exposure.key", 0.18f).sliderRange(0.05, 0.5);
            public static final Option<Float> ADAPT_DARKEN = exposureScale("caustica.rt.exposure.adaptDarken", "exposure.adapt-darken", 2.0f).sliderRange(0.1, 20.0);
            public static final Option<Float> ADAPT_BRIGHTEN = exposureScale("caustica.rt.exposure.adaptBrighten", "exposure.adapt-brighten", 0.4f).sliderRange(0.1, 20.0);
            public static final Option<Float> LOW_PERCENTILE = clampedFloat("caustica.rt.exposure.lowPercentile", "exposure.low-percentile", 0.50f, 0.0f, 1.0f);
            public static final Option<Float> HIGH_PERCENTILE = clampedFloat("caustica.rt.exposure.highPercentile", "exposure.high-percentile", 0.95f, 0.0f, 1.0f);
            public static final Option<Integer> STRIDE = clampedInt("caustica.rt.exposure.stride", "exposure.stride", 2, 1, 8);
            public static final Option<Float> CENTER_WEIGHT_SIGMA = clampedFloat("caustica.rt.exposure.centerWeightSigma", "exposure.center-weight-sigma", 0.35f, 0.01f, 2.0f);
            public static final Option<Float> CENTER_WEIGHT_FLOOR = clampedFloat("caustica.rt.exposure.centerWeightFloor", "exposure.center-weight-floor", 0.15f, 0.0f, 1.0f);
            public static final Option<Float> ENVIRONMENT_WEIGHT_CAP = clampedFloat("caustica.rt.exposure.environmentWeightCap", "exposure.environment-weight-cap", 0.25f, 0.0f, 1.0f);
            public static final Option<Float> EMISSIVE_WEIGHT_CAP = clampedFloat("caustica.rt.exposure.emissiveWeightCap", "exposure.emissive-weight-cap", 0.10f, 0.0f, 1.0f);
            public static final Option<Boolean> PRE_EXPOSURE = bool("caustica.rt.exposure.preExposure", "exposure.pre-exposure", true);
        }

        /**
         * Display rendering. The mappers take RtToneMapping's config names. Each analytic mapper's
         * controls are config-file settings at {@code sdr.<mapper>.<control>} or
         * {@code hdr.<mapper>.<control>}, listed in the mapper's shader parameter order. A psycho
         * compression of 0 derives the exponent from the display peak, except for PsychoV31, whose
         * compression is its C-infinity shoulder strength.
         */
        public static final class Tonemap {
            private Tonemap() { }
            public static final Option<Float> GAMMA = clampedFloat("caustica.rt.tonemap.gamma", "tonemap.gamma", 1.0f, 0.1f, 5.0f).inGroup("look").sliderRange(0.5f, 1.5f);
            public static final Option<String> SDR_MAPPER = stringChoice("caustica.rt.sdr.toneMapper", "sdr.tone-mapper",
                    RtToneMapping.SdrMode.DEFAULT.configName(), RtToneMapping.sdrConfigNames()).inGroup("output");
            public static final Option<String> HDR_MAPPER = stringChoice("caustica.rt.hdr.toneMapper", "hdr.tone-mapper",
                    RtToneMapping.HdrMode.DEFAULT.configName(), RtToneMapping.hdrConfigNames()).inGroup("output");
            public static final Option<Float> PAPER_WHITE_NITS = clampedFloat("caustica.rt.hdr.paperWhiteNits", "hdr.paper-white-nits", 200.0f, 80.0f, 500.0f).inGroup("output");
            // The two mappers the quick-toggle key flips between; a name an output does not offer
            // leaves that output's mapper unchanged.
            public static final Option<String> QUICK_TOGGLE_A = stringChoice("caustica.rt.tonemap.quickToggleA", "tonemap.quick-toggle-a",
                    RtToneMapping.SdrMode.ACES_2_0.configName(), RtToneMapping.quickToggleNames()).inGroup("output");
            public static final Option<String> QUICK_TOGGLE_B = stringChoice("caustica.rt.tonemap.quickToggleB", "tonemap.quick-toggle-b",
                    RtToneMapping.SdrMode.PSYCHOV31.configName(), RtToneMapping.quickToggleNames()).inGroup("output");

            public static final Map<RtToneMapping.SdrMode, List<Option<Float>>> SDR_CONTROLS = sdrControls();
            public static final Map<RtToneMapping.HdrMode, List<Option<Float>>> HDR_CONTROLS = hdrControls();

            private static Map<RtToneMapping.SdrMode, List<Option<Float>>> sdrControls() {
                Map<RtToneMapping.SdrMode, List<Option<Float>>> controls = new EnumMap<>(RtToneMapping.SdrMode.class);
                controls.put(RtToneMapping.SdrMode.AGX, List.of(
                        control("sdr.agx.contrast", 1.0f, 0.0f, 2.0f),
                        control("sdr.agx.saturation", 1.0f, 0.0f, 3.0f)));
                controls.put(RtToneMapping.SdrMode.PBR_NEUTRAL, List.of(
                        control("sdr.pbr-neutral.start-compression", 0.76f, 0.0f, 0.99f),
                        control("sdr.pbr-neutral.desaturation", 0.15f, 0.0f, 1.0f)));
                controls.put(RtToneMapping.SdrMode.REINHARD, List.of(
                        control("sdr.reinhard.white-point", 4.0f, 1.0f, 20.0f)));
                controls.put(RtToneMapping.SdrMode.ACES, List.of(
                        control("sdr.aces.exposure", 1.0f, 0.0f, 4.0f)));
                controls.put(RtToneMapping.SdrMode.LOTTES, List.of(
                        control("sdr.lottes.contrast", 2.0f, 0.1f, 5.0f),
                        control("sdr.lottes.shoulder", 1.0f, 0.1f, 5.0f),
                        control("sdr.lottes.hdr-max", 16.0f, 1.0f, 64.0f),
                        control("sdr.lottes.mid-in", 0.18f, 0.01f, 1.0f),
                        control("sdr.lottes.mid-out", 0.18f, 0.01f, 1.0f)));
                controls.put(RtToneMapping.SdrMode.UNCHARTED_2, List.of(
                        control("sdr.uncharted2.a", 0.15f, 0.01f, 1.0f),
                        control("sdr.uncharted2.b", 0.50f, 0.01f, 2.0f),
                        control("sdr.uncharted2.c", 0.10f, 0.0f, 1.0f),
                        control("sdr.uncharted2.d", 0.20f, 0.01f, 2.0f),
                        control("sdr.uncharted2.e", 0.02f, 0.0f, 1.0f),
                        control("sdr.uncharted2.f", 0.30f, 0.01f, 2.0f),
                        control("sdr.uncharted2.white-point", 11.2f, 1.0f, 32.0f)));
                controls.put(RtToneMapping.SdrMode.GT, List.of(
                        control("sdr.gt.contrast", 1.0f, 0.1f, 4.0f),
                        control("sdr.gt.linear-start", 0.22f, 0.01f, 0.99f),
                        control("sdr.gt.linear-length", 0.40f, 0.01f, 4.0f),
                        control("sdr.gt.black-curve", 1.33f, 0.1f, 4.0f),
                        control("sdr.gt.black-lift", 0.0f, -0.5f, 0.5f)));
                controls.put(RtToneMapping.SdrMode.PSYCHOVISUAL, List.of(
                        control("sdr.psychovisual.compression", 1.2f, 0.0f, 8.0f),
                        control("sdr.psychovisual.gamut-compression", 0.0f, 0.0f, 1.0f),
                        control("sdr.psychovisual.highlights", 1.0f, 0.0f, 3.0f),
                        control("sdr.psychovisual.shadows", 1.0f, 0.0f, 3.0f),
                        control("sdr.psychovisual.contrast", 1.0f, 0.1f, 3.0f),
                        control("sdr.psychovisual.purity", 1.0f, 0.0f, 3.0f),
                        control("sdr.psychovisual.hue-restore", 1.0f, 0.0f, 1.0f)));
                controls.put(RtToneMapping.SdrMode.PRISM, List.of(
                        control("sdr.prism.compression", 4.0f, 0.1f, 8.0f),
                        control("sdr.prism.anchor", 0.18f, 0.01f, 1.0f),
                        control("sdr.prism.model-red-x", 0.5041f, 0.0f, 1.0f),
                        control("sdr.prism.model-red-y", 0.3574f, 0.0f, 1.0f),
                        control("sdr.prism.model-green-x", 0.3253f, 0.0f, 1.0f),
                        control("sdr.prism.model-green-y", 0.5329f, 0.0f, 1.0f),
                        control("sdr.prism.model-blue-x", 0.1984f, 0.0f, 1.0f),
                        control("sdr.prism.model-blue-y", 0.1556f, 0.0f, 1.0f)));
                controls.put(RtToneMapping.SdrMode.REINHARD_JODIE, List.of(
                        control("sdr.reinhard-jodie.white-point", 4.0f, 1.0f, 20.0f)));
                controls.put(RtToneMapping.SdrMode.PSYCHOV31, List.of(
                        control("sdr.psychov31.compression", 1.0f, 0.1f, 8.0f),
                        control("sdr.psychov31.gamut-compression", 0.0f, 0.0f, 1.0f),
                        control("sdr.psychov31.highlights", 1.0f, 0.0f, 3.0f),
                        control("sdr.psychov31.shadows", 1.0f, 0.0f, 3.0f),
                        control("sdr.psychov31.contrast", 1.0f, 0.1f, 3.0f),
                        control("sdr.psychov31.purity", 1.0f, 0.0f, 3.0f),
                        control("sdr.psychov31.source-awareness", 1.0f, 0.0f, 1.0f)));
                controls.put(RtToneMapping.SdrMode.PSYCHOV30, List.of(
                        control("sdr.psychov30.compression", 1.0f, 0.0f, 8.0f),
                        control("sdr.psychov30.gamut-compression", 0.0f, 0.0f, 1.0f),
                        control("sdr.psychov30.highlights", 1.0f, 0.0f, 3.0f),
                        control("sdr.psychov30.shadows", 1.0f, 0.0f, 3.0f),
                        control("sdr.psychov30.contrast", 1.0f, 0.1f, 3.0f),
                        control("sdr.psychov30.purity", 1.0f, 0.0f, 3.0f)));
                controls.put(RtToneMapping.SdrMode.PSYCHOV69, List.of(
                        control("sdr.psychov69.compression", 1.0f, 0.0f, 8.0f),
                        control("sdr.psychov69.gamut-compression", 0.0f, 0.0f, 1.0f),
                        control("sdr.psychov69.highlights", 1.0f, 0.0f, 3.0f),
                        control("sdr.psychov69.shadows", 1.0f, 0.0f, 3.0f),
                        control("sdr.psychov69.contrast", 1.0f, 0.1f, 3.0f),
                        control("sdr.psychov69.purity", 1.0f, 0.0f, 3.0f),
                        control("sdr.psychov69.exposure", 1.0f, 0.0f, 64.0f),
                        control("sdr.psychov69.source-awareness", 1.0f, 0.0f, 1.0f)));
                controls.put(RtToneMapping.SdrMode.PSYCHOV24, List.of(
                        control("sdr.psychov24.compression", 1.0f, 0.0f, 8.0f),
                        control("sdr.psychov24.gamut-compression", 0.0f, 0.0f, 1.0f),
                        control("sdr.psychov24.highlights", 1.0f, 0.0f, 3.0f),
                        control("sdr.psychov24.shadows", 1.0f, 0.0f, 3.0f),
                        control("sdr.psychov24.contrast", 1.0f, 0.1f, 3.0f),
                        control("sdr.psychov24.purity", 1.0f, 0.0f, 3.0f)));
                return Collections.unmodifiableMap(controls);
            }

            private static Map<RtToneMapping.HdrMode, List<Option<Float>>> hdrControls() {
                Map<RtToneMapping.HdrMode, List<Option<Float>>> controls = new EnumMap<>(RtToneMapping.HdrMode.class);
                controls.put(RtToneMapping.HdrMode.PSYCHOVISUAL, List.of(
                        control("hdr.psychovisual.compression", 1.5f, 0.0f, 8.0f),
                        control("hdr.psychovisual.gamut-compression", 1.0f, 0.0f, 1.0f),
                        control("hdr.psychovisual.highlights", 1.0f, 0.0f, 3.0f),
                        control("hdr.psychovisual.shadows", 1.0f, 0.0f, 3.0f),
                        control("hdr.psychovisual.contrast", 1.0f, 0.1f, 3.0f),
                        control("hdr.psychovisual.purity", 1.0f, 0.0f, 3.0f),
                        control("hdr.psychovisual.hue-restore", 1.0f, 0.0f, 1.0f)));
                controls.put(RtToneMapping.HdrMode.PRISM, List.of(
                        control("hdr.prism.compression", 4.0f, 0.1f, 8.0f),
                        control("hdr.prism.anchor", 0.18f, 0.01f, 1.0f),
                        control("hdr.prism.model-red-x", 0.5041f, 0.0f, 1.0f),
                        control("hdr.prism.model-red-y", 0.3574f, 0.0f, 1.0f),
                        control("hdr.prism.model-green-x", 0.3253f, 0.0f, 1.0f),
                        control("hdr.prism.model-green-y", 0.5329f, 0.0f, 1.0f),
                        control("hdr.prism.model-blue-x", 0.1984f, 0.0f, 1.0f),
                        control("hdr.prism.model-blue-y", 0.1556f, 0.0f, 1.0f)));
                controls.put(RtToneMapping.HdrMode.PSYCHOV31, List.of(
                        control("hdr.psychov31.compression", 1.5f, 0.1f, 8.0f),
                        control("hdr.psychov31.gamut-compression", 1.0f, 0.0f, 1.0f),
                        control("hdr.psychov31.highlights", 1.0f, 0.0f, 3.0f),
                        control("hdr.psychov31.shadows", 1.0f, 0.0f, 3.0f),
                        control("hdr.psychov31.contrast", 1.0f, 0.1f, 3.0f),
                        control("hdr.psychov31.purity", 1.0f, 0.0f, 3.0f),
                        control("hdr.psychov31.source-awareness", 1.0f, 0.0f, 1.0f)));
                controls.put(RtToneMapping.HdrMode.PSYCHOV30, List.of(
                        control("hdr.psychov30.compression", 0.0f, 0.0f, 8.0f),
                        control("hdr.psychov30.gamut-compression", 1.0f, 0.0f, 1.0f),
                        control("hdr.psychov30.highlights", 1.0f, 0.0f, 3.0f),
                        control("hdr.psychov30.shadows", 1.0f, 0.0f, 3.0f),
                        control("hdr.psychov30.contrast", 1.0f, 0.1f, 3.0f),
                        control("hdr.psychov30.purity", 1.0f, 0.0f, 3.0f)));
                controls.put(RtToneMapping.HdrMode.PSYCHOV69, List.of(
                        control("hdr.psychov69.compression", 0.0f, 0.0f, 8.0f),
                        control("hdr.psychov69.gamut-compression", 1.0f, 0.0f, 1.0f),
                        control("hdr.psychov69.highlights", 1.0f, 0.0f, 3.0f),
                        control("hdr.psychov69.shadows", 1.0f, 0.0f, 3.0f),
                        control("hdr.psychov69.contrast", 1.0f, 0.1f, 3.0f),
                        control("hdr.psychov69.purity", 1.0f, 0.0f, 3.0f),
                        control("hdr.psychov69.exposure", 1.0f, 0.0f, 64.0f),
                        control("hdr.psychov69.source-awareness", 1.0f, 0.0f, 1.0f)));
                controls.put(RtToneMapping.HdrMode.PSYCHOV24, List.of(
                        control("hdr.psychov24.compression", 0.0f, 0.0f, 8.0f),
                        control("hdr.psychov24.gamut-compression", 1.0f, 0.0f, 1.0f),
                        control("hdr.psychov24.highlights", 1.0f, 0.0f, 3.0f),
                        control("hdr.psychov24.shadows", 1.0f, 0.0f, 3.0f),
                        control("hdr.psychov24.contrast", 1.0f, 0.1f, 3.0f),
                        control("hdr.psychov24.purity", 1.0f, 0.0f, 3.0f)));
                return Collections.unmodifiableMap(controls);
            }

            /** Override keys camel-case the path, e.g. caustica.rt.sdr.pbrNeutral.startCompression. */
            private static Option<Float> control(String path, float fallback, float min, float max) {
                StringBuilder key = new StringBuilder("caustica.rt.");
                for (int i = 0; i < path.length(); i++) {
                    char c = path.charAt(i);
                    key.append(c == '-' ? Character.toUpperCase(path.charAt(++i)) : c);
                }
                return clampedFloat(key.toString(), path, fallback, min, max);
            }
        }

        public static final class Screenshots {
            private Screenshots() { }
            public static final Option<Boolean> EXR_ENABLED = bool("caustica.rt.screenshots.exr", "screenshots.exr-enabled", false).inGroup("debug");
        }

        public static final class Hdr {
            private Hdr() { }
            public static final Option<Boolean> ENABLED = bool("caustica.rt.hdr", "hdr.enabled", false).inGroup("output");
            public static final Option<Float> UI_NITS = clampedFloat("caustica.rt.hdr.uiNits", "hdr.ui-nits", 200.0f, 80.0f, 500.0f).inGroup("output");
            // The display's peak brightness. Analytic HDR mappers target it exactly; ACES 2.0 renders
            // through the packaged LUT nearest to it (RtToneLut.nearestHdrLutNits).
            public static final Option<Float> PEAK_NITS = clampedFloat("caustica.rt.hdr.peakNits", "hdr.peak-nits", 1000.0f, 80.0f, 5000.0f).inGroup("output").step(10.0);
        }

        /** The SHaRC radiance cache; it runs only in a SHaRC SDK build on a device with its features. */
        public static final class Sharc {
            private Sharc() { }
            public static final Option<Boolean> ENABLED = bool("caustica.rt.sharc.enabled", "sharc.enabled", true).inGroup("radiance-cache");
            /** Entries per cache table as a power of two. */
            public static final Option<Integer> CACHE_EXPONENT = clampedInt("caustica.rt.sharc.cacheExponent", "sharc.cache-exponent", 22, 16, 23).inGroup("radiance-cache");
            public static final Option<Integer> UPDATE_TILE_SIZE = clampedInt("caustica.rt.sharc.updateTileSize", "sharc.update-tile-size", 3, 2, 64).inGroup("radiance-cache");
            public static final Option<Integer> ACCUMULATION_FRAMES = clampedInt("caustica.rt.sharc.accumulationFrames", "sharc.accumulation-frames", 384, 1, 1024).inGroup("radiance-cache");
            public static final Option<Integer> STALE_FRAMES = clampedInt("caustica.rt.sharc.staleFrames", "sharc.stale-frames", 128, 8, 1024).inGroup("radiance-cache");
            public static final Option<Float> SCENE_SCALE = clampedFloat("caustica.rt.sharc.sceneScale", "sharc.scene-scale", 32.0f, 1.0f, 100.0f).inGroup("radiance-cache");
            public static final Option<Float> GRID_LOGARITHM_BASE = clampedFloat("caustica.rt.sharc.gridLogarithmBase", "sharc.grid-logarithm-base", 3.0f, 1.01f, 16.0f).inGroup("radiance-cache");
            public static final Option<Float> GRID_LEVEL_BIAS = clampedFloat("caustica.rt.sharc.gridLevelBias", "sharc.grid-level-bias", 0.0f, -16.0f, 16.0f).inGroup("radiance-cache");
            /** Accumulator quantization steps per unit of pre-exposed radiance. */
            public static final Option<Float> RADIANCE_SCALE = clampedFloat("caustica.rt.sharc.radianceScale", "sharc.radiance-scale", 1000.0f, 50.0f, 1000.0f).inGroup("radiance-cache");
            /** Perceptual roughness a surface must exceed to own cache entries. */
            public static final Option<Float> ROUGHNESS_THRESHOLD = clampedFloat("caustica.rt.sharc.roughnessThreshold", "sharc.roughness-threshold", 0.0f, 0.0f, 1.0f).inGroup("radiance-cache");
            public static final Option<Boolean> ANTI_FIREFLY = bool("caustica.rt.sharc.antiFirefly", "sharc.anti-firefly", true).inGroup("radiance-cache");
            /** Primary surfaces query the cache too, showing its content directly on screen. */
            public static final Option<Boolean> PRIMARY_SURFACE_DEBUG = bool("caustica.rt.sharc.primarySurfaceDebug", "sharc.primary-surface-debug", false).inGroup("debug");
            public static final List<Option<?>> OPTIONS = List.of(ENABLED, CACHE_EXPONENT, UPDATE_TILE_SIZE,
                    ACCUMULATION_FRAMES, STALE_FRAMES, SCENE_SCALE, GRID_LOGARITHM_BASE, GRID_LEVEL_BIAS,
                    RADIANCE_SCALE, ROUGHNESS_THRESHOLD, ANTI_FIREFLY, PRIMARY_SURFACE_DEBUG);
        }
    }

    private static Option<Boolean> bool(String key, String path, boolean fallback) {
        return Option.bool(path, fallback).storage(path, key);
    }
    private static Option<Integer> intAtLeast(String key, String path, int fallback, int min) {
        return clampedInt(key, path, fallback, min, Integer.MAX_VALUE);
    }
    private static Option<Integer> clampedInt(String key, String path, int fallback, int min, int max) {
        return Option.integer(path, min, max, fallback).storage(path, key);
    }
    private static Option<Float> finiteFloat(String key, String path, float fallback) {
        return clampedFloat(key, path, fallback, -Float.MAX_VALUE, Float.MAX_VALUE);
    }
    private static Option<Float> clampedFloat(String key, String path, float fallback, float min, float max) {
        return Option.range(path, min, max, fallback).storage(path, key);
    }
    private static Option<Integer> intChoice(String key, String path, int fallback, List<Integer> choices) {
        return Option.intChoice(path, fallback, choices).storage(path, key);
    }
    private static Option<Float> exposureScale(String key, String path, float fallback) {
        return clampedFloat(key, path, fallback, 1.0e-4f, 1.0e4f);
    }
    private static Option<String> stringChoice(String key, String path, String fallback, List<String> choices) {
        return Option.stringChoice(path, fallback, choices).storage(path, key);
    }
}
