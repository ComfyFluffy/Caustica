package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.CausticaConfig;
import java.util.Arrays;
import java.util.List;

/**
 * Central registry of stable tone-mapper mode IDs, config names, and aliases. Owns only the
 * mode table and the immutable {@link Settings} record read by the display dispatch. Does not
 * own Vulkan resources, shader compilation, pipeline lifetime, config file I/O, or Minecraft widgets.
 *
 * <p>ACES 2.0 is the SDR and HDR default and remains mode 0 for the reference LUT path. Unknown
 * config values fall back to that default. The
 * integer IDs are mirrored by the display shader's mode switch.
 */
public final class RtToneMapping {
    private static final List<String> SDR_CONFIG_NAMES =
            Arrays.stream(SdrMode.values()).map(SdrMode::canonicalName).toList();
    private static final List<String> HDR_CONFIG_NAMES =
            Arrays.stream(HdrMode.values()).map(HdrMode::canonicalName).toList();
    private static volatile Settings cachedSettings;

    private RtToneMapping() {
    }

    /** Stable SDR tone-mapper modes. IDs mirror the display shader's mode switch. */
    public enum SdrMode {
        ACES_2_0(0, "aces2.0", "aces-2.0", "aces2"),
        AGX(1, "agx"),
        PBR_NEUTRAL(2, "pbr-neutral"),
        REINHARD(3, "reinhard"),
        ACES(4, "aces"),
        LOTTES(5, "lottes"),
        UNCHARTED_2(6, "uncharted2", "uncharted-2"),
        GT(7, "gt", "uchimura"),
        PSYCHOV24(
                8,
                "psychov24",
                "psychovisual",
                "psycho-visual",
                "psychov",
                "psychov11",
                "psychov23",
                "psychov24-experimental");

        private final int id;
        private final String canonicalName;
        private final List<String> aliases;

        SdrMode(int id, String canonicalName, String... aliases) {
            this.id = id;
            this.canonicalName = canonicalName;
            this.aliases = List.of(aliases);
        }

        public int id() {
            return id;
        }

        public String canonicalName() {
            return canonicalName;
        }

        /** Case-insensitive parse with whitespace trimming; unknown values use the ACES 2.0 default. */
        public static SdrMode parse(String value) {
            SdrMode known = find(value);
            return known != null ? known : ACES_2_0;
        }

        /** Returns whether the value is a canonical name or a committed compatibility alias. */
        public static boolean isKnown(String value) {
            return find(value) != null;
        }

        private static SdrMode find(String value) {
            if (value != null) {
                String trimmed = value.trim();
                for (SdrMode mode : values()) {
                    if (mode.canonicalName.equalsIgnoreCase(trimmed)) {
                        return mode;
                    }
                    for (String alias : mode.aliases) {
                        if (alias.equalsIgnoreCase(trimmed)) {
                            return mode;
                        }
                    }
                }
            }
            return null;
        }
    }

    /** Stable HDR tone-mapper modes. IDs mirror the display shader's mode switch. */
    public enum HdrMode {
        ACES_2_0(0, "aces2.0", "aces-2.0", "aces2"),
        BT2390(3, "bt2390", "bt-2390", "bt.2390", "standard", "standard-hdr"),
        PSYCHOV24(
                2,
                "psychov24",
                "psychovisual",
                "psycho-visual",
                "psychov",
                "psychov11",
                "psychov23",
                "psychov24-experimental");

        private final int id;
        private final String canonicalName;
        private final List<String> aliases;

        HdrMode(int id, String canonicalName, String... aliases) {
            this.id = id;
            this.canonicalName = canonicalName;
            this.aliases = List.of(aliases);
        }

        public int id() {
            return id;
        }

        public String canonicalName() {
            return canonicalName;
        }

        /** Case-insensitive parse with whitespace trimming; unknown values use the ACES 2.0 default. */
        public static HdrMode parse(String value) {
            HdrMode known = find(value);
            return known != null ? known : ACES_2_0;
        }

        /** Returns whether the value is a canonical name or a committed compatibility alias. */
        public static boolean isKnown(String value) {
            return find(value) != null;
        }

        private static HdrMode find(String value) {
            if (value != null) {
                String trimmed = value.trim();
                for (HdrMode mode : values()) {
                    if (mode.canonicalName.equalsIgnoreCase(trimmed)) {
                        return mode;
                    }
                    for (String alias : mode.aliases) {
                        if (alias.equalsIgnoreCase(trimmed)) {
                            return mode;
                        }
                    }
                }
            }
            return null;
        }
    }

    /** Immutable snapshot of the current display tone-mapping settings, read every display dispatch. */
    public record Settings(
            boolean hdrEnabled,
            int sdrMode,
            int hdrMode,
            float paperWhiteNits,
            float headroom,
            Parameters sdrParameters,
            Parameters hdrParameters) {
    }

    /** Eight mode-dependent scalars mirrored by the display shader's push-constant parameter blocks. */
    public record Parameters(
            float param0,
            float param1,
            float param2,
            float param3,
            float param4,
            float param5,
            float param6,
            float param7) {
        public static final Parameters NONE =
                new Parameters(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    /** Immutable canonical SDR mode names in enum order, for the Video Settings selection slider. */
    public static List<String> sdrConfigNames() {
        return SDR_CONFIG_NAMES;
    }

    /** Immutable canonical HDR mode names in enum order, for the Video Settings selection slider. */
    public static List<String> hdrConfigNames() {
        return HDR_CONFIG_NAMES;
    }

    /** Read the current sanitized config values, reusing the immutable snapshot while unchanged. */
    public static Settings current() {
        SdrMode sdrMode = SdrMode.parse(CausticaConfig.Rt.Sdr.TONE_MAPPER.get());
        HdrMode hdrMode = HdrMode.parse(CausticaConfig.Rt.Hdr.TONE_MAPPER.get());
        boolean hdrEnabled = CausticaConfig.Rt.Hdr.enabled();
        float paperWhiteNits = CausticaConfig.Rt.Hdr.paperWhiteNits();
        float headroom = CausticaConfig.Rt.Hdr.headroom();
        Settings cached = cachedSettings;
        if (cached != null
                && cached.hdrEnabled() == hdrEnabled
                && cached.sdrMode() == sdrMode.id()
                && cached.hdrMode() == hdrMode.id()
                && same(cached.paperWhiteNits(), paperWhiteNits)
                && same(cached.headroom(), headroom)
                && matchesSdrParameters(cached.sdrParameters(), sdrMode)
                && matchesHdrParameters(cached.hdrParameters(), hdrMode)) {
            return cached;
        }

        Settings fresh = new Settings(
                hdrEnabled,
                sdrMode.id(),
                hdrMode.id(),
                paperWhiteNits,
                headroom,
                sdrParameters(sdrMode),
                hdrParameters(hdrMode));
        cachedSettings = fresh;
        return fresh;
    }

    private static boolean matchesSdrParameters(Parameters parameters, SdrMode mode) {
        return switch (mode) {
            case ACES_2_0 -> parameters == Parameters.NONE;
            case AGX -> same(parameters.param0(), CausticaConfig.Rt.Sdr.AGX_CONTRAST.value())
                    && same(parameters.param1(), CausticaConfig.Rt.Sdr.AGX_SATURATION.value());
            case PBR_NEUTRAL -> same(parameters.param0(), CausticaConfig.Rt.Sdr.PBR_START_COMPRESSION.value())
                    && same(parameters.param1(), CausticaConfig.Rt.Sdr.PBR_DESATURATION.value());
            case REINHARD -> same(parameters.param0(), CausticaConfig.Rt.Sdr.REINHARD_WHITE_POINT.value());
            case ACES -> same(parameters.param0(), CausticaConfig.Rt.Sdr.ACES_EXPOSURE.value());
            case LOTTES -> same(parameters.param0(), CausticaConfig.Rt.Sdr.LOTTES_CONTRAST.value())
                    && same(parameters.param1(), CausticaConfig.Rt.Sdr.LOTTES_SHOULDER.value())
                    && same(parameters.param2(), CausticaConfig.Rt.Sdr.LOTTES_HDR_MAX.value())
                    && same(parameters.param3(), CausticaConfig.Rt.Sdr.LOTTES_MID_IN.value())
                    && same(parameters.param4(), CausticaConfig.Rt.Sdr.LOTTES_MID_OUT.value());
            case UNCHARTED_2 -> same(parameters.param0(), CausticaConfig.Rt.Sdr.UNCHARTED_A.value())
                    && same(parameters.param1(), CausticaConfig.Rt.Sdr.UNCHARTED_B.value())
                    && same(parameters.param2(), CausticaConfig.Rt.Sdr.UNCHARTED_C.value())
                    && same(parameters.param3(), CausticaConfig.Rt.Sdr.UNCHARTED_D.value())
                    && same(parameters.param4(), CausticaConfig.Rt.Sdr.UNCHARTED_E.value())
                    && same(parameters.param5(), CausticaConfig.Rt.Sdr.UNCHARTED_F.value())
                    && same(parameters.param6(), CausticaConfig.Rt.Sdr.UNCHARTED_WHITE_POINT.value());
            case GT -> same(parameters.param0(), CausticaConfig.Rt.Sdr.GT_CONTRAST.value())
                    && same(parameters.param1(), CausticaConfig.Rt.Sdr.GT_LINEAR_START.value())
                    && same(parameters.param2(), CausticaConfig.Rt.Sdr.GT_LINEAR_LENGTH.value())
                    && same(parameters.param3(), CausticaConfig.Rt.Sdr.GT_BLACK_CURVE.value())
                    && same(parameters.param4(), CausticaConfig.Rt.Sdr.GT_BLACK_LIFT.value());
            case PSYCHOV24 -> matchesPsychoParameters(parameters,
                    CausticaConfig.Rt.Sdr.PSYCHOV24_COMPRESSION.value(),
                    CausticaConfig.Rt.Sdr.PSYCHOV24_GAMUT_COMPRESSION.value(),
                    CausticaConfig.Rt.Sdr.PSYCHOV24_HIGHLIGHTS.value(),
                    CausticaConfig.Rt.Sdr.PSYCHOV24_SHADOWS.value(),
                    CausticaConfig.Rt.Sdr.PSYCHOV24_CONTRAST.value(),
                    CausticaConfig.Rt.Sdr.PSYCHOV24_PURITY.value());
        };
    }

    private static boolean matchesHdrParameters(Parameters parameters, HdrMode mode) {
        return switch (mode) {
            case ACES_2_0, BT2390 -> parameters == Parameters.NONE;
            case PSYCHOV24 -> matchesPsychoParameters(parameters,
                    CausticaConfig.Rt.Hdr.PSYCHOV24_COMPRESSION.value(),
                    CausticaConfig.Rt.Hdr.PSYCHOV24_GAMUT_COMPRESSION.value(),
                    CausticaConfig.Rt.Hdr.PSYCHOV24_HIGHLIGHTS.value(),
                    CausticaConfig.Rt.Hdr.PSYCHOV24_SHADOWS.value(),
                    CausticaConfig.Rt.Hdr.PSYCHOV24_CONTRAST.value(),
                    CausticaConfig.Rt.Hdr.PSYCHOV24_PURITY.value());
        };
    }

    private static boolean matchesPsychoParameters(Parameters parameters, float compression,
                                                    float gamutCompression, float highlights,
                                                    float shadows, float contrast, float purity) {
        return same(parameters.param0(), compression)
                && same(parameters.param1(), gamutCompression)
                && same(parameters.param2(), highlights)
                && same(parameters.param3(), shadows)
                && same(parameters.param4(), contrast)
                && same(parameters.param5(), purity)
                && same(parameters.param6(), 0.0f)
                && same(parameters.param7(), 0.0f);
    }

    private static boolean same(float left, float right) {
        return Float.floatToIntBits(left) == Float.floatToIntBits(right);
    }

    private static Parameters sdrParameters(SdrMode mode) {
        return switch (mode) {
            case ACES_2_0 -> Parameters.NONE;
            case AGX -> new Parameters(
                    CausticaConfig.Rt.Sdr.AGX_CONTRAST.value(),
                    CausticaConfig.Rt.Sdr.AGX_SATURATION.value(),
                    0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
            case PBR_NEUTRAL -> new Parameters(
                    CausticaConfig.Rt.Sdr.PBR_START_COMPRESSION.value(),
                    CausticaConfig.Rt.Sdr.PBR_DESATURATION.value(),
                    0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
            case REINHARD -> new Parameters(
                    CausticaConfig.Rt.Sdr.REINHARD_WHITE_POINT.value(),
                    0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
            case ACES -> new Parameters(
                    CausticaConfig.Rt.Sdr.ACES_EXPOSURE.value(),
                    0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
            case LOTTES -> new Parameters(
                    CausticaConfig.Rt.Sdr.LOTTES_CONTRAST.value(),
                    CausticaConfig.Rt.Sdr.LOTTES_SHOULDER.value(),
                    CausticaConfig.Rt.Sdr.LOTTES_HDR_MAX.value(),
                    CausticaConfig.Rt.Sdr.LOTTES_MID_IN.value(),
                    CausticaConfig.Rt.Sdr.LOTTES_MID_OUT.value(),
                    0.0f, 0.0f, 0.0f);
            case UNCHARTED_2 -> new Parameters(
                    CausticaConfig.Rt.Sdr.UNCHARTED_A.value(),
                    CausticaConfig.Rt.Sdr.UNCHARTED_B.value(),
                    CausticaConfig.Rt.Sdr.UNCHARTED_C.value(),
                    CausticaConfig.Rt.Sdr.UNCHARTED_D.value(),
                    CausticaConfig.Rt.Sdr.UNCHARTED_E.value(),
                    CausticaConfig.Rt.Sdr.UNCHARTED_F.value(),
                    CausticaConfig.Rt.Sdr.UNCHARTED_WHITE_POINT.value(),
                    0.0f);
            case GT -> new Parameters(
                    CausticaConfig.Rt.Sdr.GT_CONTRAST.value(),
                    CausticaConfig.Rt.Sdr.GT_LINEAR_START.value(),
                    CausticaConfig.Rt.Sdr.GT_LINEAR_LENGTH.value(),
                    CausticaConfig.Rt.Sdr.GT_BLACK_CURVE.value(),
                    CausticaConfig.Rt.Sdr.GT_BLACK_LIFT.value(),
                    0.0f, 0.0f, 0.0f);
            case PSYCHOV24 -> psychoParameters(
                    CausticaConfig.Rt.Sdr.PSYCHOV24_COMPRESSION.value(),
                    CausticaConfig.Rt.Sdr.PSYCHOV24_GAMUT_COMPRESSION.value(),
                    CausticaConfig.Rt.Sdr.PSYCHOV24_HIGHLIGHTS.value(),
                    CausticaConfig.Rt.Sdr.PSYCHOV24_SHADOWS.value(),
                    CausticaConfig.Rt.Sdr.PSYCHOV24_CONTRAST.value(),
                    CausticaConfig.Rt.Sdr.PSYCHOV24_PURITY.value());
        };
    }

    private static Parameters hdrParameters(HdrMode mode) {
        return switch (mode) {
            case ACES_2_0, BT2390 -> Parameters.NONE;
            case PSYCHOV24 -> psychoParameters(
                    CausticaConfig.Rt.Hdr.PSYCHOV24_COMPRESSION.value(),
                    CausticaConfig.Rt.Hdr.PSYCHOV24_GAMUT_COMPRESSION.value(),
                    CausticaConfig.Rt.Hdr.PSYCHOV24_HIGHLIGHTS.value(),
                    CausticaConfig.Rt.Hdr.PSYCHOV24_SHADOWS.value(),
                    CausticaConfig.Rt.Hdr.PSYCHOV24_CONTRAST.value(),
                    CausticaConfig.Rt.Hdr.PSYCHOV24_PURITY.value());
        };
    }

    private static Parameters psychoParameters(
            float compression,
            float gamutCompression,
            float highlights,
            float shadows,
            float contrast,
            float purity) {
        return new Parameters(
                compression,
                gamutCompression,
                highlights,
                shadows,
                contrast,
                purity,
                0.0f,
                0.0f);
    }
}
