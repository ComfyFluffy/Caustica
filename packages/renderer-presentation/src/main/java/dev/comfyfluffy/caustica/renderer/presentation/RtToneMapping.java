package dev.comfyfluffy.caustica.renderer.presentation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The selectable display tone mappers and the per-frame settings the display pass reads.
 *
 * <p>A mode's id is the display shader's specialization constant, mirrored by the mode constants in
 * {@code tone_mapping.slang}. ACES 2.0 is mode 0 on both outputs and renders through the baked LUTs
 * with the scene look; every other mode is an analytic operator on the un-looked exposed signal.
 * Declaration order is the selection order.
 *
 * <p>PsychoVisual and PsychoV24 are distinct operators: PsychoVisual is the Test24 core with the
 * shared BT.2020-triangle gamut squeeze and a hue-restore blend, PsychoV24 the CIE 170-2 operator.
 */
public final class RtToneMapping {
    private RtToneMapping() {
    }

    /** SDR tone-mapper modes. */
    public enum SdrMode {
        AGX(1, "agx"),
        PBR_NEUTRAL(2, "pbr-neutral"),
        REINHARD(3, "reinhard"),
        ACES_2_0(0, "aces2.0"),
        ACES(4, "aces"),
        LOTTES(5, "lottes"),
        UNCHARTED_2(6, "uncharted2"),
        GT(7, "gt"),
        PSYCHOVISUAL(10, "psychovisual"),
        PRISM(11, "prism"),
        REINHARD_JODIE(12, "reinhard-jodie"),
        PSYCHOV31(9, "psychov31"),
        PSYCHOV30(13, "psychov30"),
        PSYCHOV69(14, "psychov69"),
        PSYCHOV24(8, "psychov24");

        public static final SdrMode DEFAULT = PSYCHOV31;

        private final int id;
        private final String configName;

        SdrMode(int id, String configName) {
            this.id = id;
            this.configName = configName;
        }

        public int id() {
            return id;
        }

        public String configName() {
            return configName;
        }

        public static SdrMode of(String configName) {
            return Arrays.stream(values()).filter(mode -> mode.configName.equals(configName))
                    .findFirst().orElseThrow();
        }
    }

    /** HDR tone-mapper modes. */
    public enum HdrMode {
        CAUSTICA(1, "caustica"),
        ACES_2_0(0, "aces2.0"),
        PSYCHOVISUAL(5, "psychovisual"),
        PRISM(6, "prism"),
        BT2390(3, "bt2390"),
        PSYCHOV31(4, "psychov31"),
        PSYCHOV30(7, "psychov30"),
        PSYCHOV69(8, "psychov69"),
        PSYCHOV24(2, "psychov24");

        public static final HdrMode DEFAULT = PSYCHOV31;

        private final int id;
        private final String configName;

        HdrMode(int id, String configName) {
            this.id = id;
            this.configName = configName;
        }

        public int id() {
            return id;
        }

        public String configName() {
            return configName;
        }

        public static HdrMode of(String configName) {
            return Arrays.stream(values()).filter(mode -> mode.configName.equals(configName))
                    .findFirst().orElseThrow();
        }
    }

    /**
     * The display pass's tone-mapping inputs for one frame. {@code headroom} is the display peak over
     * paper white; the HDR mode and parameters only apply while HDR output is enabled.
     */
    public record Settings(
            SdrMode sdrMode,
            HdrMode hdrMode,
            float paperWhiteNits,
            float headroom,
            Parameters sdrParameters,
            Parameters hdrParameters) {
    }

    /** A mapper's controls in its shader parameter order; unused trailing parameters are zero. */
    public record Parameters(
            float param0,
            float param1,
            float param2,
            float param3,
            float param4,
            float param5,
            float param6,
            float param7) {
        public static final int COUNT = 8;

        public static Parameters of(float... values) {
            float[] padded = Arrays.copyOf(values, COUNT);
            return new Parameters(padded[0], padded[1], padded[2], padded[3],
                    padded[4], padded[5], padded[6], padded[7]);
        }
    }

    private static final List<String> SDR_CONFIG_NAMES =
            Arrays.stream(SdrMode.values()).map(SdrMode::configName).toList();
    private static final List<String> HDR_CONFIG_NAMES =
            Arrays.stream(HdrMode.values()).map(HdrMode::configName).toList();
    private static final List<String> QUICK_TOGGLE_NAMES = sdrThenHdrOnlyNames();

    /** SDR config names in selection order. */
    public static List<String> sdrConfigNames() {
        return SDR_CONFIG_NAMES;
    }

    /** HDR config names in selection order. */
    public static List<String> hdrConfigNames() {
        return HDR_CONFIG_NAMES;
    }

    /** SDR config names, then the HDR-only names: every name a quick-toggle preselect may hold. */
    public static List<String> quickToggleNames() {
        return QUICK_TOGGLE_NAMES;
    }

    private static List<String> sdrThenHdrOnlyNames() {
        List<String> names = new ArrayList<>(SDR_CONFIG_NAMES);
        for (String name : HDR_CONFIG_NAMES) {
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }
}
