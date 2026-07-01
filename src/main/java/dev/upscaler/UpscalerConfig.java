package dev.upscaler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.UnaryOperator;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central mutable runtime configuration. Each setting resolves its value, in order of precedence, from a
 * {@code -Dupscaler.*} system property, then the {@code config/upscaler.toml} file, then a hardcoded
 * default. The settings UI and any other code call the same {@code set(...)} methods, and {@link #save()}
 * writes the current values back to the TOML file.
 *
 * <p>The TOML file uses quoted, fully-qualified keys at the top level (e.g.
 * {@code "upscaler.rt.composite" = true}). Quoting sidesteps the dotted-key/table ambiguity that would
 * otherwise collide {@code upscaler.rt} (a boolean) with the {@code upscaler.rt.*} family.
 */
public final class UpscalerConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("upscaler-config");
    private static final List<RuntimeSetting<?>> SETTINGS = new CopyOnWriteArrayList<>();

    /** External values parsed from the TOML file, keyed by full setting key. Loaded once at class init. */
    private static final Map<String, String> FILE_VALUES = new HashMap<>();
    private static final Path CONFIG_PATH = resolveConfigPath();

    static {
        loadFileValues(CONFIG_PATH);
    }

    private UpscalerConfig() {
    }

    public static List<RuntimeSetting<?>> settings() {
        return List.copyOf(SETTINGS);
    }

    public static Path configPath() {
        return CONFIG_PATH;
    }

    public static void reloadFromSystemProperties() {
        for (RuntimeSetting<?> setting : SETTINGS) {
            setting.reloadFromSystemProperties();
        }
    }

    /**
     * Forces every settings holder to class-initialize so all settings are registered (and have applied
     * their file values). Call before {@link #save()} to write a complete file, and once at startup so the
     * file round-trips the full surface even for settings the renderer has not touched yet.
     */
    public static void ensureRegistered() {
        @SuppressWarnings("unused")
        Object[] touch = {
            Rt.ENABLED, Rt.Composite.ENABLED, Rt.Terrain.ASYNC_DISPATCH_PER_TICK, Rt.Omm.ENABLED,
            Rt.Entities.ENABLED, Rt.EntityTextures.MAX_TEXTURES, Rt.DlssRr.ENABLED,
            Rt.Exposure.MODE, Rt.BufferPool.STATS, Rt.Hdr.MODE, Ngx.PATH,
        };
    }

    /** Writes the default config file if it does not exist yet. */
    public static void saveIfMissing() {
        ensureRegistered();
        if (!Files.isRegularFile(CONFIG_PATH)) {
            save();
        }
    }

    /** Serializes all registered settings to the TOML config file. */
    public static synchronized void save() {
        ensureRegistered();
        List<RuntimeSetting<?>> sorted = new ArrayList<>(SETTINGS);
        sorted.sort(Comparator.comparing(RuntimeSetting::key));

        StringBuilder sb = new StringBuilder();
        sb.append("# Upscaler RT renderer configuration.\n");
        sb.append("# Generated automatically; edit while the game is closed.\n");
        sb.append("# Precedence: a matching -Dupscaler.* system property overrides this file.\n\n");
        for (RuntimeSetting<?> setting : sorted) {
            String value = setting.tomlValue();
            if (value == null) {
                continue;
            }
            sb.append('"').append(setting.key()).append("\" = ").append(value).append('\n');
        }

        try {
            Path parent = CONFIG_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(CONFIG_PATH, sb.toString());
        } catch (IOException e) {
            LOGGER.warn("Failed to write Upscaler config {}: {}", CONFIG_PATH, e.toString());
        }
    }

    private static Path resolveConfigPath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("upscaler.toml");
        } catch (Throwable t) {
            return Path.of("config", "upscaler.toml");
        }
    }

    private static void loadFileValues(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String raw : Files.readAllLines(file)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = unquote(line.substring(0, eq).trim());
                String rhs = line.substring(eq + 1).trim();
                // Strip a trailing inline comment from bare (unquoted) scalars only.
                if (!rhs.isEmpty() && rhs.charAt(0) != '"' && rhs.charAt(0) != '\'') {
                    int hash = rhs.indexOf('#');
                    if (hash >= 0) {
                        rhs = rhs.substring(0, hash).trim();
                    }
                }
                if (!key.isEmpty()) {
                    FILE_VALUES.put(key, unquote(rhs));
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to read Upscaler config {}: {}", file, e.toString());
        }
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char c = s.charAt(0);
            if ((c == '"' || c == '\'') && s.charAt(s.length() - 1) == c) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    /** TOML literal-string form; backslashes and Windows paths survive verbatim. */
    private static String tomlString(String value) {
        if (value.indexOf('\'') < 0) {
            return "'" + value + "'";
        }
        // Fall back to a basic string with the minimal escapes when a literal string can't hold the value.
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** The raw external value for a key: system property wins, then the TOML file, else {@code null}. */
    private static String externalRaw(String key) {
        String prop = System.getProperty(key);
        if (prop != null) {
            return prop;
        }
        return FILE_VALUES.get(key);
    }

    /** Whether the active external value for a key came from the file rather than a system property. */
    private static boolean externalFromFile(String key) {
        return System.getProperty(key) == null && FILE_VALUES.containsKey(key);
    }

    public interface RuntimeSetting<T> {
        String key();

        T defaultValue();

        T get();

        void set(T value);

        void reloadFromSystemProperties();

        /** This setting's value as a TOML right-hand-side literal, or {@code null} to omit it from the file. */
        String tomlValue();
    }

    public static final class BooleanSetting implements RuntimeSetting<Boolean> {
        private final String key;
        private final boolean defaultValue;
        private volatile boolean value;

        private BooleanSetting(String key, boolean defaultValue) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.value = readExternalBoolean(key, defaultValue);
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public Boolean defaultValue() {
            return defaultValue;
        }

        @Override
        public Boolean get() {
            return value;
        }

        public boolean value() {
            return value;
        }

        @Override
        public void set(Boolean value) {
            this.value = value != null ? value : defaultValue;
        }

        @Override
        public void reloadFromSystemProperties() {
            set(Boolean.parseBoolean(System.getProperty(key, Boolean.toString(defaultValue))));
        }

        @Override
        public String tomlValue() {
            return Boolean.toString(value);
        }

        private static boolean readExternalBoolean(String key, boolean fallback) {
            String value = externalRaw(key);
            return value != null ? Boolean.parseBoolean(value.trim()) : fallback;
        }
    }

    public static final class IntSetting implements RuntimeSetting<Integer> {
        private final String key;
        private final int defaultValue;
        private final IntUnaryOperator sanitize;
        private volatile int value;

        private IntSetting(String key, int defaultValue, IntUnaryOperator sanitize) {
            this.key = key;
            this.defaultValue = sanitize.applyAsInt(defaultValue);
            this.sanitize = sanitize;
            this.value = readExternalInt(key, this.defaultValue, sanitize);
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public Integer defaultValue() {
            return defaultValue;
        }

        @Override
        public Integer get() {
            return value;
        }

        public int value() {
            return value;
        }

        @Override
        public void set(Integer value) {
            this.value = sanitize.applyAsInt(value != null ? value : defaultValue);
        }

        @Override
        public void reloadFromSystemProperties() {
            this.value = readInt(key, defaultValue, sanitize);
        }

        @Override
        public String tomlValue() {
            return Integer.toString(value);
        }
    }

    public static final class FloatSetting implements RuntimeSetting<Float> {
        private final String key;
        private final float defaultValue;
        // Maps external input (system property / UI / default) into the stored value domain, e.g. degrees ->
        // radians. File values are already in the value domain, so they skip this transform.
        private final DoubleUnaryOperator inputTransform;
        // Idempotent guard on a value-domain number (clamp / finite check); safe to apply to any source.
        private final DoubleUnaryOperator valueClamp;
        private volatile float value;

        private FloatSetting(String key, float rawDefault, DoubleUnaryOperator inputTransform, DoubleUnaryOperator valueClamp) {
            this.key = key;
            this.inputTransform = inputTransform;
            this.valueClamp = valueClamp;
            this.defaultValue = (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(rawDefault));
            this.value = readExternalFloat(key, this.defaultValue, inputTransform, valueClamp);
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public Float defaultValue() {
            return defaultValue;
        }

        @Override
        public Float get() {
            return value;
        }

        public float value() {
            return value;
        }

        @Override
        public void set(Float value) {
            if (value == null) {
                this.value = defaultValue;
            } else {
                this.value = (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(value));
            }
        }

        @Override
        public void reloadFromSystemProperties() {
            String prop = System.getProperty(key);
            if (prop == null) {
                this.value = defaultValue;
                return;
            }
            try {
                this.value = (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(Double.parseDouble(prop.trim())));
            } catch (NumberFormatException e) {
                this.value = defaultValue;
            }
        }

        @Override
        public String tomlValue() {
            return Float.toString(value);
        }
    }

    public static final class StringSetting implements RuntimeSetting<String> {
        private final String key;
        private final String defaultValue;
        private final UnaryOperator<String> sanitize;
        private volatile String value;

        private StringSetting(String key, String defaultValue, UnaryOperator<String> sanitize) {
            this.key = key;
            this.defaultValue = sanitize.apply(defaultValue);
            this.sanitize = sanitize;
            String external = externalRaw(key);
            this.value = sanitize.apply(external != null ? external : defaultValue);
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String defaultValue() {
            return defaultValue;
        }

        @Override
        public String get() {
            return value;
        }

        @Override
        public void set(String value) {
            this.value = sanitize.apply(value != null ? value : defaultValue);
        }

        @Override
        public void reloadFromSystemProperties() {
            set(System.getProperty(key, defaultValue));
        }

        @Override
        public String tomlValue() {
            return tomlString(value);
        }
    }

    public static final class OptionalStringSetting implements RuntimeSetting<String> {
        private final String key;
        private volatile String value;

        private OptionalStringSetting(String key) {
            this.key = key;
            this.value = externalRaw(key);
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String defaultValue() {
            return null;
        }

        @Override
        public String get() {
            return value;
        }

        @Override
        public void set(String value) {
            this.value = value;
        }

        @Override
        public void reloadFromSystemProperties() {
            this.value = System.getProperty(key);
        }

        @Override
        public String tomlValue() {
            return value != null ? tomlString(value) : null;
        }
    }

    public static final class Rt {
        public static final BooleanSetting ENABLED = bool("upscaler.rt", true);
        public static final StringSetting OUTPUT_MODE = string("upscaler.rt.output", "rt", Rt::sanitizeOutputMode);
        public static final BooleanSetting CANCEL_VANILLA_WORLD = bool("upscaler.rt.cancelVanillaWorld", false);
        public static final BooleanSetting CANCEL_VANILLA_WORLD_LOG = bool("upscaler.rt.cancelVanillaWorld.log", true);
        public static final BooleanSetting PBR = bool("upscaler.rt.pbr", true);
        public static final IntSetting WORKER_THREADS = intAtLeast("upscaler.rt.workerThreads", defaultWorkerThreads(), 1);

        private Rt() {
        }

        public static final class Composite {
            public static final BooleanSetting ENABLED = bool("upscaler.rt.composite", false);
            public static final IntSetting DEBUG_VIEW = intValue("upscaler.rt.debugView", 0);
            public static final IntSetting SPP = intAtLeast("upscaler.rt.spp", 1, 1);
            public static final BooleanSetting WATER_WAVES = bool("upscaler.rt.waterWaves", true);
            public static final FloatSetting SUN_ANGULAR_RADIUS =
                    radians("upscaler.rt.sunAngularRadius", 0.6f);
            public static final FloatSetting MOON_ANGULAR_RADIUS =
                    radians("upscaler.rt.moonAngularRadius", 1.5f);
            public static final FloatSetting SUN_NOON_SOUTH_TILT =
                    radians("upscaler.rt.sunNoonSouthDeg", 0.0f);
            public static final FloatSetting RENDER_SCALE =
                    clampedFloat("upscaler.rt.renderScale", 0.5f, 0.25f, 1.0f);
            public static final FloatSetting JITTER_SIGN_X = finiteFloat("upscaler.rt.jitterSignX", 1.0f);
            public static final FloatSetting JITTER_SIGN_Y = finiteFloat("upscaler.rt.jitterSignY", -1.0f);

            private Composite() {
            }
        }

        public static final class Terrain {
            public static final IntSetting ASYNC_DISPATCH_PER_TICK =
                    intAtLeast("upscaler.rt.asyncDispatchPerTick", 64, 0);
            public static final IntSetting SECTION_RESULTS_PER_TICK =
                    intAtLeast("upscaler.rt.sectionResultsPerTick", 64, 0);
            public static final IntSetting ASYNC_DISPATCH_MOVING_PER_TICK =
                    intAtLeast("upscaler.rt.asyncDispatchMovingPerTick",
                            Math.min(ASYNC_DISPATCH_PER_TICK.value(), 24), 0);
            public static final IntSetting SECTION_RESULTS_MOVING_PER_TICK =
                    intAtLeast("upscaler.rt.sectionResultsMovingPerTick",
                            Math.min(SECTION_RESULTS_PER_TICK.value(), 24), 0);
            public static final IntSetting MAX_INFLIGHT_SECTIONS =
                    intAtLeast("upscaler.rt.maxInflightSections", 192, 0);
            public static final IntSetting SECTION_TABLE_INITIAL_CAPACITY =
                    intAtLeast("upscaler.rt.sectionTableInitialCapacity", 512, 1);
            public static final IntSetting REBASE_DISTANCE_BLOCKS =
                    intAtLeast("upscaler.rt.rebaseDistanceBlocks", 128, 0);

            private Terrain() {
            }
        }

        public static final class Omm {
            public static final BooleanSetting ENABLED = bool("upscaler.rt.omm", true);
            public static final IntSetting SUBDIVISION = clampedInt("upscaler.rt.ommSubdivision", 4, 0, 12);
            public static final BooleanSetting STATS = bool("upscaler.rt.ommStats", false);

            private Omm() {
            }
        }

        public static final class Entities {
            public static final BooleanSetting ENABLED = bool("upscaler.rt.entities", true);
            public static final BooleanSetting PARTICLES_ENABLED = bool("upscaler.rt.particles", true);
            public static final IntSetting MAX_ENTITIES = intAtLeast("upscaler.rt.maxEntities", 1024, 1);
            public static final IntSetting BE_VIEW_CHUNKS = intAtLeast("upscaler.rt.beViewChunks", 8, 0);
            public static final IntSetting BE_BUILDS_PER_FRAME = intAtLeast("upscaler.rt.beBuildsPerFrame", 8, 0);
            public static final BooleanSetting REFIT = bool("upscaler.rt.entityRefit", true);
            public static final IntSetting REFIT_REBUILD_INTERVAL =
                    intAtLeast("upscaler.rt.refitRebuildInterval", 120, 1);
            public static final IntSetting CAPTURE_INITIAL_VERTICES =
                    intAtLeast("upscaler.rt.entityCaptureInitialVertices", 1024, 1);

            private Entities() {
            }

            public static int entityListCapacity() {
                return Math.max(16, MAX_ENTITIES.value());
            }

            public static int entityBufferListCapacity() {
                return (int) Math.min(Integer.MAX_VALUE, (long) entityListCapacity() * 5L);
            }

            public static int entityMapCapacity() {
                return (int) Math.min(Integer.MAX_VALUE, Math.max(16L, (long) MAX_ENTITIES.value() * 2L));
            }
        }

        public static final class EntityTextures {
            public static final IntSetting MAX_TEXTURES = intAtLeast("upscaler.rt.maxEntityTextures", 256, 1);
            public static final BooleanSetting PBR = bool("upscaler.rt.entityPbr", true);

            private EntityTextures() {
            }
        }

        public static final class DlssRr {
            public static final BooleanSetting ENABLED = bool("upscaler.rt.dlssRr", false);
            public static final IntSetting PRESET = intValue("upscaler.rt.dlssRr.preset", 0);
            public static final IntSetting QUALITY = intValue("upscaler.rt.dlssRr.quality", 0);

            private DlssRr() {
            }
        }

        /** DLSS Frame Generation. Default off; gated additionally by hardware/driver availability. */
        public static final class Fg {
            public static final BooleanSetting ENABLED = bool("upscaler.rt.fg", false);
            // Frames generated per rendered frame: 1 = 2x (one interpolated), 2 = 3x, etc. Clamped at
            // runtime to the driver's reported DLSSG.MultiFrameCountMax.
            public static final IntSetting MULTI_FRAME_COUNT = intAtLeast("upscaler.rt.fg.multiFrameCount", 1, 1);

            private Fg() {
            }
        }

        /**
         * NVIDIA Reflex ({@code VK_NV_low_latency2}). Default off; gated additionally by device support.
         * Phase 0 (extension + capability probe only, see {@code RtDeviceBringup}/{@code RtReflex}) — the
         * per-frame sleep call + latency markers + the swapchain {@code VkSwapchainLatencyCreateInfoNV} the
         * spec requires for {@code vkSetLatencySleepModeNV} to take effect land in a later phase.
         */
        public static final class Reflex {
            public static final BooleanSetting ENABLED = bool("upscaler.rt.reflex", false);
            public static final BooleanSetting LOW_LATENCY_BOOST = bool("upscaler.rt.reflex.boost", false);
            // 0 = no framerate cap (Reflex just paces submission, doesn't limit fps).
            public static final IntSetting MINIMUM_INTERVAL_US = intAtLeast("upscaler.rt.reflex.minIntervalUs", 0, 0);

            private Reflex() {
            }
        }

        public static final class Exposure {
            public static final StringSetting MODE = string("upscaler.rt.exposure.mode", "auto", Exposure::sanitizeMode);
            public static final FloatSetting FIXED = exposureScale("upscaler.rt.exposure.fixed", 1.1f);
            public static final FloatSetting MANUAL_EV = finiteFloat("upscaler.rt.exposure.manualEv", 0.0f);
            public static final FloatSetting KEY = exposureScale("upscaler.rt.exposure.key", 0.18f);
            public static final FloatSetting MIN_EV = finiteFloat("upscaler.rt.exposure.minEv", -0.5f);
            public static final FloatSetting MAX_EV = finiteFloat("upscaler.rt.exposure.maxEv", 2.5f);
            public static final FloatSetting ADAPT_UP = exposureScale("upscaler.rt.exposure.adaptUp", 0.12f);
            public static final FloatSetting ADAPT_DOWN = exposureScale("upscaler.rt.exposure.adaptDown", 0.35f);

            private Exposure() {
            }

            public static float minEv() {
                return Math.min(MIN_EV.value(), MAX_EV.value());
            }

            public static float maxEv() {
                return Math.max(MIN_EV.value(), MAX_EV.value());
            }

            public static float clampScale(float value) {
                return Math.clamp(value, 1.0e-4f, 1.0e4f);
            }

            private static String sanitizeMode(String value) {
                if ("manual".equalsIgnoreCase(value) || "auto".equalsIgnoreCase(value)) {
                    return value.toLowerCase();
                }
                return "fixed";
            }
        }

        public static final class BufferPool {
            public static final BooleanSetting STATS = bool("upscaler.rt.poolStats", false);

            private BufferPool() {
            }
        }

        /**
         * HDR display output (Phase 0/1 scaffolding). {@code mode} selects the presentation convention:
         * {@code off} keeps the SDR AgX path, {@code scrgb} targets scRGB/linear extended range, {@code auto}
         * picks scRGB when the swapchain supports it (resolution happens in the later swapchain-ownership
         * phase). {@code forceSdr} is a debug override that pins SDR even when a mode is selected. The nit
         * values drive the scene-HDR → display mapping: SDR paper white maps to {@code paperWhiteNits}, and
         * highlights roll off toward {@code peakNits}.
         */
        public static final class Hdr {
            public static final StringSetting MODE = string("upscaler.rt.hdr.mode", "off", Hdr::sanitizeMode);
            public static final FloatSetting PAPER_WHITE_NITS =
                    clampedFloat("upscaler.rt.hdr.paperWhiteNits", 200.0f, 80.0f, 1000.0f);
            public static final FloatSetting PEAK_NITS =
                    clampedFloat("upscaler.rt.hdr.peakNits", 1000.0f, 80.0f, 10000.0f);
            public static final BooleanSetting FORCE_SDR = bool("upscaler.rt.hdr.forceSdr", false);
            /**
             * Phase 2 step A: render the vanilla GUI/HUD into a separate transparent overlay target and
             * composite it back over the world. Default off. In SDR this should look identical to vanilla; it
             * is the prerequisite for keeping UI out of the world tonemap once HDR presentation lands.
             */
            public static final BooleanSetting UI_OVERLAY = bool("upscaler.rt.hdr.uiOverlay", false);
            /**
             * Phase 2 step B: create Minecraft's swapchain in scRGB (R16G16B16A16_SFLOAT /
             * EXTENDED_SRGB_LINEAR) instead of SDR, when the surface advertises it. Default off, falls back to
             * SDR when unavailable. NOTE: until the HDR compositor (step C) writes correct scRGB content, the
             * presented image will look wrong with this on — it only proves the swapchain path.
             */
            public static final BooleanSetting SCRGB_SWAPCHAIN = bool("upscaler.rt.hdr.scrgbSwapchain", false);

            private Hdr() {
            }

            /** Whether the HDR display path should be active (a mode is selected and SDR isn't forced). */
            public static boolean enabled() {
                return !FORCE_SDR.value() && !"off".equals(MODE.get());
            }

            /** scRGB linear scale for SDR paper white (scRGB defines 1.0 = 80 nits). */
            public static float paperWhiteScale() {
                return PAPER_WHITE_NITS.value() / 80.0f;
            }

            /** Highlight headroom above paper white, in paper-white-referred units ({@code >= 1}). */
            public static float headroom() {
                return Math.max(1.0f, PEAK_NITS.value() / Math.max(1.0f, PAPER_WHITE_NITS.value()));
            }

            private static String sanitizeMode(String value) {
                if ("scrgb".equalsIgnoreCase(value) || "auto".equalsIgnoreCase(value)) {
                    return value.toLowerCase();
                }
                return "off";
            }
        }

        private static String sanitizeOutputMode(String value) {
            return "vanilla".equalsIgnoreCase(value) ? "vanilla" : "rt";
        }
    }

    public static final class Ngx {
        public static final OptionalStringSetting PATH = optionalString("upscaler.ngx.path");

        private Ngx() {
        }
    }

    private static BooleanSetting bool(String key, boolean fallback) {
        return new BooleanSetting(key, fallback);
    }

    private static StringSetting string(String key, String fallback, UnaryOperator<String> sanitize) {
        return new StringSetting(key, fallback, sanitize);
    }

    private static OptionalStringSetting optionalString(String key) {
        return new OptionalStringSetting(key);
    }

    private static IntSetting intValue(String key, int fallback) {
        return new IntSetting(key, fallback, v -> v);
    }

    private static IntSetting intAtLeast(String key, int fallback, int min) {
        return new IntSetting(key, fallback, v -> Math.max(min, v));
    }

    private static IntSetting clampedInt(String key, int fallback, int min, int max) {
        return new IntSetting(key, fallback, v -> Math.clamp(v, min, max));
    }

    private static FloatSetting finiteFloat(String key, float fallback) {
        return new FloatSetting(key, fallback, v -> v, v -> Double.isFinite(v) ? v : fallback);
    }

    private static FloatSetting exposureScale(String key, float fallback) {
        return new FloatSetting(key, fallback, v -> v, v -> Math.clamp(v, 1.0e-4, 1.0e4));
    }

    private static FloatSetting clampedFloat(String key, float fallback, float min, float max) {
        return new FloatSetting(key, fallback, v -> v, v -> Math.clamp(v, min, max));
    }

    private static FloatSetting radians(String key, float fallbackDegrees) {
        return new FloatSetting(key, fallbackDegrees, Math::toRadians, v -> Double.isFinite(v) ? v : 0.0);
    }

    private static int readInt(String key, int fallback, IntUnaryOperator sanitize) {
        String value = System.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return sanitize.applyAsInt(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int readExternalInt(String key, int fallback, IntUnaryOperator sanitize) {
        String value = externalRaw(key);
        if (value == null) {
            return fallback;
        }
        try {
            return sanitize.applyAsInt(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float readExternalFloat(String key, float fallback, DoubleUnaryOperator inputTransform, DoubleUnaryOperator valueClamp) {
        String value = externalRaw(key);
        if (value == null) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            double inDomain = externalFromFile(key) ? parsed : inputTransform.applyAsDouble(parsed);
            return (float) valueClamp.applyAsDouble(inDomain);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int defaultWorkerThreads() {
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
    }
}
