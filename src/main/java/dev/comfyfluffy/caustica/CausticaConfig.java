package dev.comfyfluffy.caustica;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.UnaryOperator;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central mutable runtime configuration. Each setting resolves its value, in order of precedence, from a
 * {@code -Dcaustica.*} system property, then the {@code config/caustica.toml} file, then a hardcoded
 * default. The settings UI and any other code call the same {@code set(...)} methods, and {@link #save()}
 * writes the current values back to the TOML file.
 *
 * <p>The system property namespace ({@code caustica.rt.foo}) and the TOML layout are independent: the file
 * uses real nested tables (e.g. {@code [omm]} with a {@code subdivision} key) grouped for readability, while
 * the property namespace stays flat and dotted for convenient one-off {@code -D} overrides.
 */
public final class CausticaConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");
    private static final List<RuntimeSetting<?>> SETTINGS = new CopyOnWriteArrayList<>();

    private static final Path CONFIG_PATH = resolveConfigPath();
    private static final CommentedFileConfig FILE = loadFile(CONFIG_PATH);

    private CausticaConfig() {
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
            Rt.ENABLED, Rt.Composite.SPP, Rt.Composite.MAX_BOUNCES, Rt.Terrain.ASYNC_DISPATCH_PER_PASS, Rt.Omm.ENABLED,
            Rt.Entities.ENABLED, Rt.Entities.GLOW_ENABLED, Rt.EntityTextures.MAX_TEXTURES, Rt.DlssRr.ENABLED, Rt.Fg.ENABLED,
            Rt.Reflex.ENABLED, Rt.Fog.DENSITY, Rt.Clouds.COVERAGE, Rt.Grade.ENABLED, Rt.Tonemap.VIEW_TRANSFORM, Rt.Pom.DEPTH, Rt.Restir.TEMPORAL,
            Rt.Weather.RAIN_OVERRIDE, Rt.Lod.ENABLED, Rt.Exposure.MODE, Rt.Tonemap.GAMMA, Rt.FrameStats.ENABLED,
            Rt.Screenshots.EXR_ENABLED, Rt.Hdr.ENABLED, Ngx.PATH,
        };
    }

    /** Writes the default config file if it does not exist yet. */
    public static void saveIfMissing() {
        ensureRegistered();
        if (FILE.valueMap().isEmpty()) {
            save();
        }
    }

    /** Serializes all registered settings to the TOML config file. */
    public static synchronized void save() {
        ensureRegistered();
        writeComments();
        for (RuntimeSetting<?> setting : SETTINGS) {
            setting.writeToFile(FILE);
        }
        FILE.save();
    }

    private static void writeComments() {
        FILE.setComment("enabled",
                " Caustica ray-tracing settings. A matching -Dcaustica.* system property overrides a value here.");
        FILE.setComment("terrain",
                " Controls terrain loading. Higher limits can load terrain faster but use more CPU and GPU time.");
        FILE.setComment("frame-generation",
                " DLSS Frame Generation. Requires supported NVIDIA hardware and drivers.\n"
                        + " multi-frame-count sets generated frames per rendered frame (1 = 2x, 2 = 3x, ...).");
        FILE.setComment("reflex",
                " NVIDIA Reflex. Requires supported NVIDIA hardware and drivers.\n"
                        + " minimum-interval-us controls frame limiting; 0 disables the limit.");
        FILE.setComment("fog",
                " Exponential height fog. density is extinction per block at height (0 disables it);\n"
                        + " scale-height is how fast it thins going up. Fog colour is taken from the sky, not set here.");
        FILE.setComment("clouds",
                " Volumetric cloud deck. coverage 0 disables it entirely (and costs nothing).\n"
                        + " altitude is the deck base in world Y; thickness is its depth in blocks.\n"
                        + " Clouds are lit from the sun/moon through the same atmosphere as the sky, so they have no colour setting.");
        FILE.setComment("lights",
                " Controls direct lighting from glowing blocks such as torches, glowstone, and lava.\n"
                        + " Set ris-candidates to 0 to disable it. stats, dump, and dump-radius are debugging options.");
        FILE.setComment("tonemap",
                " Controls the final image. gamma: 1 is neutral; lower values brighten midtones.");
        FILE.setComment("exposure",
                " Controls automatic exposure. manual-ev sets exposure in manual mode and adjusts it in auto mode.\n"
                        + " adapt-darken and adapt-brighten control adjustment speed in seconds.\n"
                        + " sky-weight-cap and emissive-weight-cap limit how much bright areas affect exposure.");
        FILE.setComment("hdr",
                " HDR display output. Requires operating system and display support.\n"
                        + " ui-nits controls UI brightness; peak-nits must be 500, 1000, 2000, or 4000.");
        FILE.setComment("screenshots",
                " exr-enabled saves an ACEScg EXR beside the normal F2 PNG while ray tracing is active.");
    }

    private static Path resolveConfigPath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("caustica.toml");
        } catch (Throwable t) {
            return Path.of("config", "caustica.toml");
        }
    }

    private static CommentedFileConfig loadFile(Path path) {
        CommentedFileConfig config = CommentedFileConfig.builder(path, TomlFormat.instance())
                .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
                .preserveInsertionOrder()
                .sync()
                .build();
        try {
            config.load();
        } catch (Exception e) {
            LOGGER.warn("Failed to read Caustica config {}: {}", path, e.toString());
        }
        return config;
    }

    private static Boolean fileBoolean(String tomlPath) {
        return FILE.contains(tomlPath) ? FILE.<Boolean>get(tomlPath) : null;
    }

    private static Number fileNumber(String tomlPath) {
        return FILE.contains(tomlPath) ? FILE.<Number>get(tomlPath) : null;
    }

    private static String fileString(String tomlPath) {
        return FILE.contains(tomlPath) ? FILE.<String>get(tomlPath) : null;
    }

    public interface RuntimeSetting<T> {
        /** The {@code -Dcaustica.*} system property name that overrides this setting. */
        String key();

        /** The dotted path of this setting inside the nested {@code config/caustica.toml} tables. */
        String tomlPath();

        T defaultValue();

        T get();

        void set(T value);

        void reloadFromSystemProperties();

        /** Writes this setting's current value into the given config at {@link #tomlPath()}. */
        void writeToFile(CommentedConfig config);
    }

    public static final class BooleanSetting implements RuntimeSetting<Boolean> {
        private final String key;
        private final String tomlPath;
        private final boolean defaultValue;
        private volatile boolean value;

        private BooleanSetting(String key, String tomlPath, boolean defaultValue) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = defaultValue;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
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
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        private boolean resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                return Boolean.parseBoolean(prop.trim());
            }
            Boolean fromFile = fileBoolean(tomlPath);
            return fromFile != null ? fromFile : defaultValue;
        }
    }

    public static final class IntSetting implements RuntimeSetting<Integer> {
        private final String key;
        private final String tomlPath;
        private final int defaultValue;
        private final IntUnaryOperator sanitize;
        private volatile int value;

        private IntSetting(String key, String tomlPath, int defaultValue, IntUnaryOperator sanitize) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = sanitize.applyAsInt(defaultValue);
            this.sanitize = sanitize;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
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
            String prop = System.getProperty(key);
            if (prop == null) {
                this.value = defaultValue;
                return;
            }
            try {
                this.value = sanitize.applyAsInt(Integer.parseInt(prop.trim()));
            } catch (NumberFormatException e) {
                this.value = defaultValue;
            }
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        private int resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                try {
                    return sanitize.applyAsInt(Integer.parseInt(prop.trim()));
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
            Number fromFile = fileNumber(tomlPath);
            return fromFile != null ? sanitize.applyAsInt(fromFile.intValue()) : defaultValue;
        }
    }

    public static final class FloatSetting implements RuntimeSetting<Float> {
        private final String key;
        private final String tomlPath;
        private final float defaultValue;
        // Maps a raw external number (system property, file, or the constructor's raw default) into the
        // stored value domain, e.g. degrees -> radians.
        private final DoubleUnaryOperator inputTransform;
        // Inverse of inputTransform: maps the stored value domain back to the raw external domain (e.g.
        // radians -> degrees) for writeToFile, so a value round-trips through the file unchanged instead
        // of having inputTransform re-applied to an already-transformed number on the next load.
        private final DoubleUnaryOperator outputTransform;
        // Idempotent guard on a value-domain number (clamp / finite check); safe to apply to any source.
        private final DoubleUnaryOperator valueClamp;
        private volatile float value;

        private FloatSetting(String key, String tomlPath, float rawDefault, DoubleUnaryOperator inputTransform,
                             DoubleUnaryOperator outputTransform, DoubleUnaryOperator valueClamp) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.inputTransform = inputTransform;
            this.outputTransform = outputTransform;
            this.valueClamp = valueClamp;
            this.defaultValue = (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(rawDefault));
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
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
        public void writeToFile(CommentedConfig config) {
            // Round-trip through Float.toString() so the file gets the shortest decimal that reproduces
            // this float (e.g. "0.6"), not outputTransform's raw double with float's binary noise spelled
            // out to 17 digits (e.g. 0.6000000487130328).
            float raw = (float) outputTransform.applyAsDouble(value);
            config.set(tomlPath, Double.parseDouble(Float.toString(raw)));
        }

        private float resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                try {
                    return (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(Double.parseDouble(prop.trim())));
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
            Number fromFile = fileNumber(tomlPath);
            if (fromFile == null) {
                return defaultValue;
            }
            return (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(fromFile.doubleValue()));
        }
    }

    public static final class StringSetting implements RuntimeSetting<String> {
        private final String key;
        private final String tomlPath;
        private final String defaultValue;
        private final UnaryOperator<String> sanitize;
        private volatile String value;

        private StringSetting(String key, String tomlPath, String defaultValue, UnaryOperator<String> sanitize) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = sanitize.apply(defaultValue);
            this.sanitize = sanitize;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
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
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        private String resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                return sanitize.apply(prop);
            }
            String fromFile = fileString(tomlPath);
            return sanitize.apply(fromFile != null ? fromFile : defaultValue);
        }
    }

    public static final class OptionalStringSetting implements RuntimeSetting<String> {
        private final String key;
        private final String tomlPath;
        private volatile String value;

        private OptionalStringSetting(String key, String tomlPath) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
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
        public void writeToFile(CommentedConfig config) {
            if (value != null) {
                config.set(tomlPath, value);
            } else {
                config.remove(tomlPath);
            }
        }

        private String resolveInitial() {
            String prop = System.getProperty(key);
            return prop != null ? prop : fileString(tomlPath);
        }
    }

    public static final class Rt {
        public static final BooleanSetting ENABLED = bool("caustica.rt", "enabled", true);
        public static final IntSetting WORKER_THREADS =
                intAtLeast("caustica.rt.workerThreads", "worker-threads", defaultWorkerThreads(), 1);

        private Rt() {
        }

        public static final class Composite {
            public static final IntSetting DEBUG_VIEW = intValue("caustica.rt.debugView", "composite.debug-view", 0);
            public static final IntSetting SPP = intAtLeast("caustica.rt.spp", "composite.spp", 1, 1);
            public static final IntSetting MAX_BOUNCES =
                    clampedInt("caustica.rt.maxBounces", "composite.max-bounces", 4, 2, 8);
            public static final BooleanSetting WATER_WAVES =
                    bool("caustica.rt.waterWaves", "composite.water-waves", true);
            // Sun/moon angular radii and the noon south tilt moved into the versioned look package
            // (look.json "sky"): they shape the sky alongside the exposure curve, the LMT and the
            // photometric anchors that were already authored there, and splitting them across two
            // sources meant a package could not fully describe its own look.
            public static final FloatSetting JITTER_SIGN_X =
                    finiteFloat("caustica.rt.jitterSignX", "composite.jitter-sign-x", 1.0f);
            public static final FloatSetting JITTER_SIGN_Y =
                    finiteFloat("caustica.rt.jitterSignY", "composite.jitter-sign-y", -1.0f);

            private Composite() {
            }
        }

        public static final class Terrain {
            // External keys retain their historical "per-tick" names for config compatibility; terrain
            // streaming is render-pass driven and these Java names reflect the actual scheduling unit.
            public static final IntSetting ASYNC_DISPATCH_PER_PASS =
                    intAtLeast("caustica.rt.asyncDispatchPerTick", "terrain.async-dispatch-per-tick", 32, 0);
            public static final IntSetting COMPLETION_RESULTS_PER_PASS =
                    intAtLeast("caustica.rt.sectionResultsPerTick", "terrain.section-results-per-tick", 32, 0);
            public static final IntSetting MAX_INFLIGHT_SECTIONS =
                    intAtLeast("caustica.rt.maxInflightSections", "terrain.max-inflight-sections", 32, 0);
            public static final IntSetting SECTION_TABLE_INITIAL_CAPACITY =
                    intAtLeast("caustica.rt.sectionTableInitialCapacity", "terrain.section-table-initial-capacity", 512, 1);
            public static final IntSetting REBASE_DISTANCE_BLOCKS =
                    intAtLeast("caustica.rt.rebaseDistanceBlocks", "terrain.rebase-distance-blocks", 128, 0);
            public static final BooleanSetting BLAS_COMPACTION =
                    bool("caustica.rt.blasCompaction", "terrain.blas-compaction", true);

            private Terrain() {
            }
        }

        /** RIS block-emitter lights. {@code ris-candidates = 0} disables everything. */
        public static final class Lights {
            public static final IntSetting RIS_CANDIDATES =
                    intAtLeast("caustica.rt.risCandidates", "lights.ris-candidates", 8, 0);
            public static final FloatSetting MIN_FILL_RATIO =
                    finiteFloat("caustica.rt.lightMinFillRatio", "lights.min-fill-ratio", 0.25f);
            public static final BooleanSetting STATS = bool("caustica.rt.lightStats", "lights.stats", false);
            public static final BooleanSetting DUMP = bool("caustica.rt.lightDump", "lights.dump", false);
            public static final IntSetting DUMP_RADIUS =
                    intAtLeast("caustica.rt.lightDumpRadius", "lights.dump-radius", 12, 1);

            private Lights() {
            }
        }

        public static final class Omm {
            public static final BooleanSetting ENABLED = bool("caustica.rt.omm", "omm.enabled", true);
            public static final IntSetting SUBDIVISION =
                    clampedInt("caustica.rt.ommSubdivision", "omm.subdivision", 4, 0, 6);
            public static final BooleanSetting STATS = bool("caustica.rt.ommStats", "omm.stats", false);

            private Omm() {
            }
        }

        public static final class Entities {
            public static final BooleanSetting ENABLED = bool("caustica.rt.entities", "entities.enabled", true);
            public static final BooleanSetting PARTICLES_ENABLED =
                    bool("caustica.rt.particles", "particles.enabled", true);
            public static final BooleanSetting GLOW_ENABLED =
                    bool("caustica.rt.glow", "entities.glow.enabled", true);
            public static final BooleanSetting NAME_TAGS_ENABLED =
                    bool("caustica.rt.nameTags", "entities.name-tags.enabled", true);
            /** Debug-only: render each model submission twice and require bitwise-identical CPU captures. */
            public static final BooleanSetting CAPTURE_PARITY =
                    bool("caustica.rt.entityCaptureParity", "entities.debug.capture-parity", false);
            public static final IntSetting MAX_ORDINARY_ENTITIES =
                    intAtLeast("caustica.rt.maxOrdinaryEntities", "entities.max-ordinary-entities", 1024, 0);
            public static final IntSetting MAX_BLOCK_ENTITIES =
                    intAtLeast("caustica.rt.maxBlockEntities", "entities.block-entities.max-entities", 1024, 0);
            public static final IntSetting MAX_PARTICLES =
                    intAtLeast("caustica.rt.maxParticles", "particles.max-particles", 1024, 0);
            public static final IntSetting BE_VIEW_CHUNKS =
                    intAtLeast("caustica.rt.beViewChunks", "entities.block-entities.view-chunks", 8, 0);
            public static final IntSetting BE_BUILDS_PER_FRAME =
                    intAtLeast("caustica.rt.beBuildsPerFrame", "entities.block-entities.builds-per-frame", 64, 0);
            public static final BooleanSetting REFIT_ENABLED =
                    bool("caustica.rt.entityRefit", "entities.refit.enabled", true);

            private Entities() {
            }

            public static int maxEntities() {
                return Math.addExact(Math.addExact(
                        MAX_ORDINARY_ENTITIES.value(), MAX_BLOCK_ENTITIES.value()), MAX_PARTICLES.value());
            }

            public static int entityListCapacity() {
                return Math.max(16, maxEntities());
            }

            public static int entityMapCapacity() {
                // Fastutil expected-size constructors apply their own load-factor headroom.
                return Math.max(16, MAX_ORDINARY_ENTITIES.value());
            }
        }

        public static final class EntityTextures {
            public static final IntSetting MAX_TEXTURES =
                    intAtLeast("caustica.rt.maxEntityTextures", "entities.textures.max-textures", 256, 1);
            public static final BooleanSetting PBR = bool("caustica.rt.entityPbr", "entities.textures.pbr", true);

            private EntityTextures() {
            }
        }

        public static final class Overlay {
            public static final BooleanSetting BLOCK_OUTLINE_ENABLED =
                    bool("caustica.rt.blockOutline", "overlay.block-outline.enabled", true);

            private Overlay() {
            }
        }

        public static final class DlssRr {
            public static final BooleanSetting ENABLED = bool("caustica.rt.dlssRr", "dlss-rr.enabled", true);
            public static final IntSetting PRESET = intValue("caustica.rt.dlssRr.preset", "dlss-rr.preset", 0);

            // NVSDK_NGX_PerfQuality_Value. Per NVIDIA's DLSS-RR programming guide, Ray Reconstruction only
            // supports Performance(0), Balanced(1), Quality(2), Ultra-Performance(3), and DLAA(5) —
            // Ultra Quality(4) is not a valid PerfQualityValue for RR (its optimal-settings query returns a
            // zeroed render size for it) and is deliberately excluded here.
            public static final List<Integer> QUALITY_STEPS = List.of(3, 0, 1, 2, 5);
            public static final IntSetting QUALITY =
                    intChoice("caustica.rt.dlssRr.quality", "dlss-rr.quality", 0, QUALITY_STEPS);

            private DlssRr() {
            }
        }

        /** DLSS Frame Generation. Default off; gated additionally by hardware/driver availability. */
        public static final class Fg {
            public static final BooleanSetting ENABLED = bool("caustica.rt.fg", "frame-generation.enabled", false);
            public static final IntSetting MULTI_FRAME_COUNT =
                    intAtLeast("caustica.rt.fg.multiFrameCount", "frame-generation.multi-frame-count", 1, 1);

            private Fg() {
            }
        }

        /**
         * NVIDIA Reflex ({@code VK_NV_low_latency2}). Default off; gated additionally by device support.
         * The renderer configures the swapchain latency mode, paces frames with {@code vkLatencySleepNV},
         * and emits simulation, render-submit, and present latency markers.
         */
        /**
         * Exponential height fog. Off by default: {@code density} is the extinction per block at
         * {@code height}, so 0 leaves the renderer bit-identical to a build without this feature.
         * Colour is not configurable on purpose — the fog is lit from the sky-view LUT, so it tracks
         * time of day and sun direction on its own.
         */
        public static final class Fog {
            /** Extinction per block at the reference height. ~0.004 is a light haze, ~0.03 is thick. */
            public static final FloatSetting DENSITY =
                    clampedFloat("caustica.rt.fog.density", "fog.density", 0.0f, 0.0f, 1.0f);
            /** Blocks over which density falls by 1/e going up. Large values approach uniform distance fog. */
            public static final FloatSetting SCALE_HEIGHT =
                    clampedFloat("caustica.rt.fog.scaleHeight", "fog.scale-height", 24.0f, 1.0f, 1024.0f);
            /** World Y at which density equals DENSITY. Sea level by default. */
            public static final FloatSetting HEIGHT =
                    clampedFloat("caustica.rt.fog.height", "fog.height", 62.0f, -512.0f, 1024.0f);
            /**
             * How strongly rain thickens the fog, as a multiple of density at full rain. Rain haze is
             * the single biggest visual cue that it is raining at distance, so this defaults high.
             */
            public static final FloatSetting WEATHER_RESPONSE =
                    clampedFloat("caustica.rt.fog.weatherResponse", "fog.weather-response", 3.0f, 0.0f, 32.0f);
            /**
             * How strongly biome climate modulates fog. Cold and wet biomes get more haze, hot and dry
             * biomes less. 0 makes fog uniform everywhere.
             */
            public static final FloatSetting BIOME_RESPONSE =
                    clampedFloat("caustica.rt.fog.biomeResponse", "fog.biome-response", 0.6f, 0.0f, 1.0f);
            /**
             * Diagnostic. 1 replaces the whole image with the fog's in-scatter colour, so you can see
             * directly whether that colour is black. Black screen means the sky lookup is failing;
             * a coloured screen means the lookup is fine and the composition is at fault. 0 = off.
             */
            public static final IntSetting DEBUG =
                    clampedInt("caustica.rt.fog.debug", "fog.debug", 0, 0, 1);
            /** Single-scattering albedo. 1 scatters everything; lower values also absorb, darkening distance. */
            public static final FloatSetting ALBEDO =
                    clampedFloat("caustica.rt.fog.albedo", "fog.albedo", 0.92f, 0.0f, 1.0f);

            private Fog() {
            }
        }

        /**
         * Volumetric cloud deck. Off by default: {@code coverage} 0 skips the march entirely, so a
         * default build pays nothing. The deck is marched only on rays that may see the sun and moon
         * discs (primary and specular), so it does not contribute to bounce lighting.
         */
        public static final class Clouds {
            /** 0 clear, 1 overcast. Raising it grows existing clouds outward rather than fading in haze. */
            public static final FloatSetting COVERAGE =
                    clampedFloat("caustica.rt.clouds.coverage", "clouds.coverage", 0.0f, 0.0f, 1.0f);
            /** Extinction per block inside fully dense cloud. Higher reads as darker, more solid cumulus. */
            public static final FloatSetting DENSITY =
                    clampedFloat("caustica.rt.clouds.density", "clouds.density", 0.045f, 0.0f, 1.0f);
            /**
             * Follow vanilla's CLOUD_HEIGHT environment attribute instead of {@link #ALTITUDE}. On by
             * default so a dimension or datapack that moves its cloud layer moves this deck with it.
             */
            public static final BooleanSetting VANILLA_HEIGHT =
                    bool("caustica.rt.clouds.vanillaHeight", "clouds.vanilla-height", true);
            /**
             * How strongly vanilla's CLOUD_COLOR tints the deck. Applied hue-only, so it recolours
             * without overriding the atmosphere's own time-of-day response. 0 leaves the deck lit
             * purely by the sun and sky.
             */
            public static final FloatSetting VANILLA_TINT =
                    clampedFloat("caustica.rt.clouds.vanillaTint", "clouds.vanilla-tint", 0.5f, 0.0f, 1.0f);
            /** Deck base, world Y. Used when vanilla-height is off, or when vanilla reports none. */
            public static final FloatSetting ALTITUDE =
                    clampedFloat("caustica.rt.clouds.altitude", "clouds.altitude", 192.0f, -512.0f, 4096.0f);
            /** Deck depth in blocks. Thin decks read as stratus, thick ones as cumulus. */
            public static final FloatSetting THICKNESS =
                    clampedFloat("caustica.rt.clouds.thickness", "clouds.thickness", 90.0f, 1.0f, 2048.0f);
            /**
             * Blocks per noise period — roughly the width of one cloud mass. Smaller gives more, tighter
             * clouds. 220 puts several masses inside a normal render distance; the original hardcoded
             * value behaved like ~830, which is why the sky looked almost empty.
             */
            public static final FloatSetting FEATURE_SIZE =
                    clampedFloat("caustica.rt.clouds.featureSize", "clouds.feature-size",
                            160.0f, 16.0f, 4096.0f);
            /**
             * Domain drift in blocks per second. 0 freezes the deck. Judge this against feature-size:
             * drift only reads as motion when it covers a noticeable fraction of a cloud per second.
             */
            public static final FloatSetting WIND_SPEED =
                    clampedFloat("caustica.rt.clouds.windSpeed", "clouds.wind-speed", 12.0f, 0.0f, 256.0f);
            /** High-frequency edge erosion. 0 gives smooth blobs, 1 gives wispy, broken silhouettes. */
            public static final FloatSetting DETAIL =
                    clampedFloat("caustica.rt.clouds.detail", "clouds.detail", 0.45f, 0.0f, 1.0f);
            /**
             * How strongly vanilla weather drives the deck. 1 takes coverage to overcast and thickens
             * and darkens the deck as rain and thunder ramp; 0 ignores weather entirely.
             */
            public static final FloatSetting WEATHER_RESPONSE =
                    clampedFloat("caustica.rt.clouds.weatherResponse", "clouds.weather-response", 1.0f, 0.0f, 1.0f);
            /**
             * How strongly the deck shadows the world. 0 leaves the ground lit as if the sky were clear
             * and skips the shadow march entirely; 1 is full Beer's law through the coarse deck.
             */
            public static final FloatSetting SHADOW_STRENGTH =
                    clampedFloat("caustica.rt.clouds.shadowStrength", "clouds.shadow-strength", 1.0f, 0.0f, 4.0f);
            /**
             * Floor the cloud shadow cannot darken past, standing in for light that scatters through the
             * deck and arrives diffusely. 0 gives physically-too-dark overcast; real overcast sits around
             * 0.1-0.2 of clear-sky direct.
             */
            public static final FloatSetting SHADOW_FLOOR =
                    clampedFloat("caustica.rt.clouds.shadowFloor", "clouds.shadow-floor", 0.15f, 0.0f, 1.0f);
            /**
             * How wet rain makes sky-exposed surfaces: darker and glossier while it rains. 0 disables the
             * effect and its upward visibility ray. Snowy biomes are excluded automatically — snow does
             * not wet a surface.
             */
            public static final FloatSetting WETNESS =
                    clampedFloat("caustica.rt.clouds.wetness", "clouds.wetness", 1.0f, 0.0f, 1.0f);
            /**
             * Scales the cloud march's step counts. 1.0 is reference quality; 0.5 halves the samples
             * and is close to invisible on a moving camera, because the deck is smooth and Ray
             * Reconstruction treats it as low-frequency detail. This is the main FPS dial for clouds —
             * reach for it before dropping coverage, which changes how the sky looks.
             */
            public static final FloatSetting QUALITY =
                    clampedFloat("caustica.rt.clouds.quality", "clouds.quality", 1.0f, 0.1f, 1.0f);
            /** Stand-in for in-cloud multiple scattering, as a fraction of zenith sky radiance. */
            public static final FloatSetting AMBIENT =
                    clampedFloat("caustica.rt.clouds.ambient", "clouds.ambient", 0.35f, 0.0f, 4.0f);

            private Clouds() {
            }
        }

        /**
         * Scene-referred colour grading and output sharpening, both applied in the display pass.
         *
         * <p>The grade defaults reproduce the "Ultra Realism Tonemapper for UE5" post-process volume.
         * They transfer exactly rather than approximately because UE's default working colour space is
         * AP1, the same space this renderer paths in. Note the guide tuned those numbers against AgX
         * Punchy; with the ACES 2.0 view transform they will land somewhere different, which is a
         * reason to set {@code view-transform} to match, not a reason to change the numbers.
         */
        public static final class Grade {
            /** Master switch. Off leaves the display pass bit-identical to an ungraded build. */
            public static final BooleanSetting ENABLED =
                    bool("caustica.rt.grade.enabled", "grade.enabled", false);
            public static final FloatSetting SATURATION =
                    clampedFloat("caustica.rt.grade.saturation", "grade.saturation", 0.75f, 0.0f, 4.0f);
            public static final FloatSetting CONTRAST =
                    clampedFloat("caustica.rt.grade.contrast", "grade.contrast", 1.05f, 0.1f, 4.0f);
            public static final FloatSetting GAIN =
                    clampedFloat("caustica.rt.grade.gain", "grade.gain", 1.30f, 0.0f, 8.0f);
            /** Multiplies the global saturation inside the highlight region, as UE composes them. */
            public static final FloatSetting HIGHLIGHT_SATURATION =
                    clampedFloat("caustica.rt.grade.highlightSaturation", "grade.highlight-saturation",
                            0.95f, 0.0f, 4.0f);
            /** Multiplies the global gain inside the highlight region. */
            public static final FloatSetting HIGHLIGHT_GAIN =
                    clampedFloat("caustica.rt.grade.highlightGain", "grade.highlight-gain",
                            1.60f, 0.0f, 8.0f);
            /** Luma at which the highlight region starts blending in (UE's ColorCorrectionHighlightsMin). */
            public static final FloatSetting HIGHLIGHTS_MIN =
                    clampedFloat("caustica.rt.grade.highlightsMin", "grade.highlights-min",
                            0.28f, 0.0f, 1.0f);
            /**
             * RCAS strength, 0 disables it and its four extra taps. Applied after the output transform
             * on display code values — PQ code values on the HDR path — so a highlight receives the
             * same apparent enhancement as a midtone rather than hundreds of times more overshoot.
             */
            public static final FloatSetting SHARPNESS =
                    clampedFloat("caustica.rt.grade.sharpness", "grade.sharpness", 0.0f, 0.0f, 1.0f);

            private Grade() {
            }
        }

        /**
         * Parallax occlusion mapping from the LabPBR height channel (_n alpha). Gives packs that author
         * depth into the height map — Patrix and similar — real surface parallax instead of flat
         * normal shading. Silhouettes stay flat: this shifts texture coordinates, it does not displace
         * geometry.
         */
        public static final class Pom {
            /**
             * Depth in sprite-widths. 0 disables it and its march entirely. 0.05 is subtle and safe;
             * past ~0.12 the flat silhouettes start giving the illusion away at grazing angles.
             */
            public static final FloatSetting DEPTH =
                    clampedFloat("caustica.rt.pom.depth", "pom.depth", 0.0f, 0.0f, 0.5f);
            /** Scales the march's step count. Below 1 is faster and steppier on tall height fields. */
            public static final FloatSetting QUALITY =
                    clampedFloat("caustica.rt.pom.quality", "pom.quality", 1.0f, 0.1f, 2.0f);
            /** Ray-cone LOD at which POM has faded out. Lower means it stops sooner with distance. */
            public static final FloatSetting FADE_LOD =
                    clampedFloat("caustica.rt.pom.fadeLod", "pom.fade-lod", 4.0f, 0.0f, 12.0f);

            private Pom() {
            }
        }

        /**
         * ReSTIR temporal reuse for the block-emitter reservoirs. Off by default. Reuses last frame's
         * chosen emitter where the surface is unchanged, so a pixel accumulates far more effective
         * candidates than one frame's budget allows — the difference shows up in caves and at night,
         * where the light sampling is the noise floor.
         */
        /**
         * Manual weather override for the renderer's fog, cloud and wetness response.
         *
         * <p>This exists because servers frequently never send rain packets — Wynncraft being the
         * case in point — so {@code level.getRainLevel()} sits at zero forever and none of the storm
         * visuals can be seen or tuned. Client-side weather mods solve this properly by driving
         * vanilla's own rain state; this is the fallback for when no such mod is available for your
         * Minecraft version.
         *
         * <p>It overrides only what the RENDERER reads. It does not make it rain: no particles, no
         * sound, no gameplay effect. Vanilla's own weather is untouched.
         */
        public static final class Weather {
            /** Rain level 0..1, or -1 to use whatever the world reports. */
            public static final FloatSetting RAIN_OVERRIDE =
                    clampedFloat("caustica.rt.weather.rainOverride", "weather.rain-override",
                            -1.0f, -1.0f, 1.0f);
            /** Thunder level 0..1, or -1 to use the world's. Layers on top of rain, as vanilla does. */
            public static final FloatSetting THUNDER_OVERRIDE =
                    clampedFloat("caustica.rt.weather.thunderOverride", "weather.thunder-override",
                            -1.0f, -1.0f, 1.0f);

            private Weather() {
            }
        }

        /**
         * Distant terrain from Distant Horizons' database. Off by default.
         *
         * <p>DH is used as a WORLD DATABASE only — its own rendering should be disabled. Caustica reads
         * LOD terrain through DH's public API, meshes it, and puts it in the acceleration structure, so
         * distant terrain is traced like everything else: it casts shadows, appears in reflections and
         * contributes to global illumination, which a rasterised LOD renderer cannot do.
         *
         * <p>Requires Distant Horizons installed with data for the current world. On a server that means
         * something has populated its database — on Wynncraft, the WynnLODGrabber download.
         */
        public static final class Lod {
            /** Master switch. Off skips every DH query, mesh and BLAS build. */
            public static final BooleanSetting ENABLED =
                    bool("caustica.rt.lod.enabled", "lod.enabled", false);
            /**
             * DH detail level. Each virtual block covers 2^detail world blocks, and one LOD section
             * therefore spans 16*2^detail blocks for the triangle cost of one ordinary section:
             * 3 gives 128-block sections, 4 gives 256. Lower is sharper and far more expensive.
             */
            public static final IntSetting DETAIL =
                    clampedInt("caustica.rt.lod.detail", "lod.detail", 3, 1, 6);
            /** Radius in LOD sections around the player. 8 at detail 3 is roughly 1024 blocks. */
            public static final IntSetting RADIUS =
                    clampedInt("caustica.rt.lod.radius", "lod.radius", 8, 1, 64);
            /**
             * Vertical span in LOD sections, centred on sea level. Distant terrain rarely needs the
             * full world height, and every extra layer is a full ring of sections.
             */
            public static final IntSetting HEIGHT_SECTIONS =
                    clampedInt("caustica.rt.lod.heightSections", "lod.height-sections", 2, 1, 16);
            /**
             * LOD sections started per frame. This is the streaming throttle: each one is a DH database
             * query plus a mesh plus a BLAS build, so a high value stutters while the world loads.
             */
            public static final IntSetting SECTIONS_PER_FRAME =
                    clampedInt("caustica.rt.lod.sectionsPerFrame", "lod.sections-per-frame", 2, 1, 32);

            private Lod() {
            }
        }

        public static final class Restir {
            /**
             * Strength of temporal reuse, 0 disables it and both reservoir image accesses. 1 is full
             * reuse up to the shader's M cap; lower values shorten the effective history.
             */
            public static final FloatSetting TEMPORAL =
                    clampedFloat("caustica.rt.restir.temporal", "restir.temporal", 0.0f, 0.0f, 1.0f);

            /**
             * Screen-space neighbour taps per pixel, 0 for temporal-only. Helps exactly where temporal
             * reuse cannot: disoccluded edges and the frame after a camera cut, which is where the eye
             * is. Capped at 8 in the shader.
             */
            public static final IntSetting SPATIAL_TAPS =
                    intAtLeast("caustica.rt.restir.spatialTaps", "restir.spatial-taps", 3, 0);
            /** Spatial tap radius in render pixels. Wider borrows more but mismatches more often. */
            public static final FloatSetting SPATIAL_RADIUS =
                    clampedFloat("caustica.rt.restir.spatialRadius", "restir.spatial-radius",
                            8.0f, 1.0f, 64.0f);

            private Restir() {
            }
        }

        public static final class Reflex {
            public static final BooleanSetting ENABLED = bool("caustica.rt.reflex", "reflex.enabled", false);
            public static final BooleanSetting LOW_LATENCY_BOOST =
                    bool("caustica.rt.reflex.boost", "reflex.low-latency-boost", false);
            public static final IntSetting MINIMUM_INTERVAL_US =
                    intAtLeast("caustica.rt.reflex.minIntervalUs", "reflex.minimum-interval-us", 0, 0);

            private Reflex() {
            }
        }

        public static final class Exposure {
            // Control points are measured-EV100 : compensation-EV.
            // Rendered median (log) = log2(key) + comp(evScene), so comp IS the rendered offset in EV
            // from the noon reference.
            //
            // Fitted to measured in-game EV100 and the current emissive baseline:
            //   noon sand       +17.45 -> -0.01   renders at key, the reference
            //   noon blue sky   +16.50 -> -0.17
            //   daylight shade   +7.00 -> -1.82
            //   lit night room   +7.00 -> -1.82   (same measured luminance as daylight shade)
            //   night street     +1.50 -> -3.01
            //   starlit sky      -8.00 -> -5.00   (floor)
            // Effective slope is 0.79 / 0.78 / 0.83 across the three segments, compressing 25 EV of
            // scene range to 5.0 EV of rendered difference.
            //
            // Daylight shade and a lit interior at night measure the SAME (~EV 7), so no luminance-only
            // curve can separate them -- what does is the asymmetric temporal adaptation above, which
            // holds a low exposure when you step from noon sun into shade. That is a real limit of this
            // controller, not a tuning miss.
            public static final StringSetting MODE =
                    string("caustica.rt.exposure.mode", "exposure.mode", "auto", Exposure::sanitizeMode);
            public static final FloatSetting MANUAL_EV =
                    clampedFloat("caustica.rt.exposure.manualEv", "exposure.manual-ev",
                            0.0f, -15.0f, 15.0f);
            public static final FloatSetting KEY = exposureScale("caustica.rt.exposure.key", "exposure.key", 0.18f);
            // Bounds on the ABSOLUTE exposure multiplier. Sized from what the curve above actually asks
            // for at the measured scene extremes: -16.9 EV at noon sand, +3.5 EV at the starlit-sky
            // floor. A clamp should be a guard rail, not the controller, so these sit just outside that.
            //
            // max-ev was +10 and blew out the frame: with 13 EV of headroom above what the curve wants,
            // exposure ran away whenever the camera held something very dark, and anything bright
            // entering the frame then arrived pre-blown. +5 keeps 1.5 EV over the curve's own demand.
            //
            // min-ev deliberately does NOT cover a zoomed-in sun (which asks for about -20.8): letting
            // the whole frame go black because the sun is in shot is worse than clamping it. The sky
            // metering cap already bounds the sun's share, so in practice this only engages on a
            // near-full-screen sun.
            /**
             * Adaptation time constants in seconds, applied in EV space by the resolve. Named for what
             * the SCENE did: walking into a dark cave is "darken" (exposure has to rise), stepping back
             * out is "brighten".
             *
             * <p>Asymmetric on purpose, and in the direction human vision actually works — light
             * adaptation takes seconds, dark adaptation takes minutes. Every shipping game compresses
             * that, but keeping the sign right is what makes a sunrise read as a sunrise instead of as a
             * lens. The names describe the scene change, not the inverse movement of the exposure multiplier.
             */
            public static final FloatSetting ADAPT_DARKEN =
                    exposureScale("caustica.rt.exposure.adaptDarken", "exposure.adapt-darken", 2.0f);
            public static final FloatSetting ADAPT_BRIGHTEN =
                    exposureScale("caustica.rt.exposure.adaptBrighten", "exposure.adapt-brighten", 0.4f);
            public static final FloatSetting LOW_PERCENTILE =
                    clampedFloat("caustica.rt.exposure.lowPercentile", "exposure.low-percentile", 0.50f, 0.0f, 1.0f);
            public static final FloatSetting HIGH_PERCENTILE =
                    clampedFloat("caustica.rt.exposure.highPercentile", "exposure.high-percentile", 0.95f, 0.0f, 1.0f);
            public static final IntSetting STRIDE =
                    clampedInt("caustica.rt.exposure.stride", "exposure.stride", 2, 1, 8);
            public static final FloatSetting CENTER_WEIGHT_SIGMA =
                    clampedFloat("caustica.rt.exposure.centerWeightSigma",
                            "exposure.center-weight-sigma", 0.35f, 0.01f, 2.0f);
            public static final FloatSetting CENTER_WEIGHT_FLOOR =
                    clampedFloat("caustica.rt.exposure.centerWeightFloor",
                            "exposure.center-weight-floor", 0.15f, 0.0f, 1.0f);
            public static final FloatSetting SKY_WEIGHT_CAP =
                    clampedFloat("caustica.rt.exposure.skyWeightCap",
                            "exposure.sky-weight-cap", 0.25f, 0.0f, 1.0f);
            public static final FloatSetting EMISSIVE_WEIGHT_CAP =
                    clampedFloat("caustica.rt.exposure.emissiveWeightCap",
                            "exposure.emissive-weight-cap", 0.10f, 0.0f, 1.0f);
            /**
             * Pre-exposure: raygen multiplies scene radiance by the previous frame's exposure before
             * the fp16 write, and the display pass divides it back out, so stored values sit near
             * {@code key} instead of spanning the ~26 EV physical photometric units require. The two
             * cancel algebraically, so <b>toggling this must not change the
             * image</b>; it exists as an A/B switch for exactly that check, and as an escape hatch
             * if DLSS-RR ever proves sensitive to its history being at the previous frame's scale.
             */
            public static final BooleanSetting PRE_EXPOSURE =
                    bool("caustica.rt.exposure.preExposure", "exposure.pre-exposure", true);

            private Exposure() {
            }

            public static float minEv() {
                return dev.comfyfluffy.caustica.rt.RtLookPackage.current().exposure().minEv();
            }

            public static float maxEv() {
                return dev.comfyfluffy.caustica.rt.RtLookPackage.current().exposure().maxEv();
            }

            public static String curve() {
                return dev.comfyfluffy.caustica.rt.RtLookPackage.current().exposure().curve();
            }

            /**
             * Sanity bound on an exposure multiplier, not an artistic one. It must remain below the
             * 3.8e-6 multiplier requested by {@code -18 EV}; min-ev/max-ev provides the artistic bound.
             */
            public static float clampScale(float value) {
                return Math.clamp(value, 1.0e-8f, 1.0e8f);
            }

            private static String sanitizeMode(String value) {
                if ("auto".equalsIgnoreCase(value)) {
                    return "auto";
                }
                if ("manual".equalsIgnoreCase(value)) {
                    return "manual";
                }
                return "auto";
            }

        }

        /** Scene-referred look transform and baked SDR/HDR ACES display transforms. */
        public static final class Tonemap {
            public static final FloatSetting GAMMA =
                    clampedFloat("caustica.rt.tonemap.gamma", "tonemap.gamma", 1.0f, 0.1f, 5.0f);
            /**
             * SDR view transform: {@code aces2} (default), {@code agx-punchy}, or {@code agx-base}.
             * The AgX options are baked from sobotka/AgX — the same config the "Ultra Realism
             * Tonemapper for UE5" guide installs into Unreal — so this renderer and that Unreal setup
             * resolve to the same image transform.
             *
             * <p>SDR ONLY. AgX has no HDR output transform; every view in that config terminates in a
             * ~100 nit display encoding. When HDR output is enabled the PQ path stays ACES 2.0 whatever
             * this is set to, so on an HDR display this setting changes nothing that reaches the screen.
             */
            public static final StringSetting VIEW_TRANSFORM =
                    string("caustica.rt.tonemap.viewTransform", "tonemap.view-transform",
                            "aces2", Tonemap::sanitizeViewTransform);

            private static String sanitizeViewTransform(String value) {
                if ("agx-punchy".equalsIgnoreCase(value)) {
                    return "agx-punchy";
                }
                if ("agx-base".equalsIgnoreCase(value)) {
                    return "agx-base";
                }
                return "aces2";
            }

            /**
             * HDR view transform resource. {@code agx-punchy} maps to the AgX HDR bake; everything
             * else, including {@code agx-base}, stays on ACES 2.0 at the requested peak.
             *
             * <p>The AgX HDR bake is a construction, not an upstream transform: AgX has no HDR output
             * transform, so this is AgX's tone curve and look presented through PQ with diffuse white
             * at BT.2408's 203 nits. You get the AgX image on an HDR display. You do NOT get AgX with
             * HDR highlight range — its sigmoid still rolls off to its own white point. Pick ACES 2.0
             * if highlight range is what you are after.
             */
            public static String hdrLutResource(int peakNits) {
                if ("agx-punchy".equals(VIEW_TRANSFORM.get())) {
                    return "hdr_agx_punchy_rec2020.bin";
                }
                return "hdr_aces2_rec2020_" + peakNits + "nit.bin";
            }

            /** Resource name under {@code /caustica/color/luts/} for the selected SDR view transform. */
            public static String sdrLutResource() {
                switch (VIEW_TRANSFORM.get()) {
                    case "agx-punchy":
                        return "sdr_agx_punchy_rec709.bin";
                    case "agx-base":
                        return "sdr_agx_base_rec709.bin";
                    default:
                        return "sdr_aces2_rec709.bin";
                }
            }

            private Tonemap() {
            }
        }

        /** Render-frame timing + hitch logging. See {@code RtFrameStats}. */
        public static final class FrameStats {
            public static final BooleanSetting ENABLED = bool("caustica.rt.frameStats", "frame-stats.enabled", false);

            private FrameStats() {
            }
        }

        /** Optional high-dynamic-range screenshot output paired with vanilla's F2 PNG. */
        public static final class Screenshots {
            public static final BooleanSetting EXR_ENABLED =
                    bool("caustica.rt.screenshots.exr", "screenshots.exr-enabled", false);

            private Screenshots() {
            }
        }

        /** Startup Vulkan inventory + {@code VK_EXT_device_fault} reporting on device loss. See {@code VulkanDiagnostics}. */
        public static final class Diagnostics {
            /** Heavy driver-side crash diagnostics: vendor diagnostics-config extensions (shader debug
             * info, resource tracking, automatic checkpoints, shader error reporting) and the
             * {@code deviceFaultVendorBinary} feature (vendor-format crash dump on device loss). Off by
             * default: measured ~10x BLAS build time / -20% fps when enabled. Plain {@code deviceFault}
             * reporting (fault addresses + vendor records) is always on and unaffected. Turn on only
             * while chasing a live device-loss crash. */
            public static final BooleanSetting HEAVY_CRASH_DIAGNOSTICS =
                    bool("caustica.rt.heavyCrashDiagnostics", "diagnostics.heavy-crash-diagnostics", false);

            private Diagnostics() {
            }
        }

        /**
         * HDR display output. When enabled the swapchain is created in PQ (ST.2084/HDR10 — the display-ready
         * encoding both HDR10 swapchains and DLSS Frame Generation require; whatever pixel format the surface
         * pairs with that color space, commonly a 10-bit UNORM), falling back to SDR if the surface doesn't
         * advertise it. The ACES LUT owns scene-to-display mapping; {@code uiNits} places SDR-authored UI
         * in that PQ output, while {@code peakNits} selects the LUT's mastering target.
         */
        public static final class Hdr {
            public static final BooleanSetting ENABLED = bool("caustica.rt.hdr", "hdr.enabled", false);
            public static final FloatSetting UI_NITS =
                    clampedFloat("caustica.rt.hdr.uiNits", "hdr.ui-nits", 200.0f, 80.0f, 500.0f);

            // ACES HDR LUTs are available only for these mastering targets.
            public static final List<Integer> PEAK_NITS_STEPS = List.of(500, 1000, 2000, 4000);
            public static final IntSetting PEAK_NITS =
                    intChoice("caustica.rt.hdr.peakNits", "hdr.peak-nits", 1000, PEAK_NITS_STEPS);

            // Surface capability and current swapchain state are separate: HDR controls remain available
            // while the swapchain is native SDR, so enabling HDR can recreate it in PQ.
            private static volatile boolean SWAPCHAIN_PQ_AVAILABLE = false;
            private static volatile boolean SWAPCHAIN_PQ_ACTIVE = false;

            private Hdr() {
            }

            public static void setSwapchainPqAvailable(boolean available) {
                SWAPCHAIN_PQ_AVAILABLE = available;
            }

            public static void setSwapchainPqActive(boolean active) {
                SWAPCHAIN_PQ_ACTIVE = active;
            }

            /**
             * Whether this session's surface can create a PQ swapchain, independent of which format the
             * current swapchain uses.
             */
            public static boolean swapchainPqAvailable() {
                return SWAPCHAIN_PQ_AVAILABLE;
            }

            /** Whether the currently configured swapchain is HDR10/PQ rather than native SDR. */
            public static boolean swapchainPqActive() {
                return SWAPCHAIN_PQ_ACTIVE;
            }

            /**
             * Whether the HDR display path (world HDR + PQ swapchain + UI overlay) should be active this
             * frame. The option invalidates the surface configuration after changing {@link #ENABLED};
             * the ordinary resize/configure path recreates the swapchain in SDR or PQ.
             */
            public static boolean enabled() {
                return SWAPCHAIN_PQ_ACTIVE && ENABLED.value();
            }

            /** Absolute brightness assigned to SDR-authored UI in the PQ output. */
            public static float uiNits() {
                return UI_NITS.value();
            }

        }
    }

    public static final class Ngx {
        public static final OptionalStringSetting PATH = optionalString("caustica.ngx.path", "ngx.path");

        private Ngx() {
        }
    }

    private static BooleanSetting bool(String key, String tomlPath, boolean fallback) {
        return new BooleanSetting(key, tomlPath, fallback);
    }

    private static StringSetting string(String key, String tomlPath, String fallback, UnaryOperator<String> sanitize) {
        return new StringSetting(key, tomlPath, fallback, sanitize);
    }

    private static OptionalStringSetting optionalString(String key, String tomlPath) {
        return new OptionalStringSetting(key, tomlPath);
    }

    private static IntSetting intValue(String key, String tomlPath, int fallback) {
        return new IntSetting(key, tomlPath, fallback, v -> v);
    }

    private static IntSetting intAtLeast(String key, String tomlPath, int fallback, int min) {
        return new IntSetting(key, tomlPath, fallback, v -> Math.max(min, v));
    }

    private static IntSetting intChoice(String key, String tomlPath, int fallback, List<Integer> choices) {
        return new IntSetting(key, tomlPath, fallback, v -> choices.contains(v) ? v : fallback);
    }

    private static IntSetting clampedInt(String key, String tomlPath, int fallback, int min, int max) {
        return new IntSetting(key, tomlPath, fallback, v -> Math.clamp(v, min, max));
    }

    private static FloatSetting finiteFloat(String key, String tomlPath, float fallback) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Double.isFinite(v) ? v : fallback);
    }

    private static FloatSetting exposureScale(String key, String tomlPath, float fallback) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Math.clamp(v, 1.0e-4, 1.0e4));
    }

    private static FloatSetting clampedFloat(String key, String tomlPath, float fallback, float min, float max) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Math.clamp(v, min, max));
    }

    private static FloatSetting radians(String key, String tomlPath, float fallbackDegrees) {
        return new FloatSetting(key, tomlPath, fallbackDegrees, Math::toRadians, Math::toDegrees, v -> Double.isFinite(v) ? v : 0.0);
    }

    private static int defaultWorkerThreads() {
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
    }
}
