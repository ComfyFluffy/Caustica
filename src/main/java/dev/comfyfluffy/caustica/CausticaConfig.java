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
            Rt.ENABLED, Rt.Composite.SPP, Rt.Composite.MAX_BOUNCES, Rt.Sharc.ENABLED,
            Rt.Terrain.ASYNC_DISPATCH_PER_PASS, Rt.Omm.ENABLED, Rt.Lights.RIS_CANDIDATES,
            Rt.Entities.ENABLED, Rt.Entities.GLOW_ENABLED, Rt.EntityTextures.MAX_TEXTURES, Rt.DlssRr.ENABLED, Rt.Fg.ENABLED,
            Rt.Reflex.ENABLED, Rt.Exposure.MODE, Rt.Exposure.LOW_PERCENTILE, Rt.Exposure.HIGH_PERCENTILE,
            Rt.Exposure.PRE_EXPOSURE, Rt.Tonemap.GAMMA,
            Rt.Sdr.TONE_MAPPER, Rt.Hdr.TONE_MAPPER,
            Rt.FrameStats.ENABLED,
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
        FILE.setComment("lights",
                " Controls direct lighting from glowing blocks such as torches, glowstone, and lava.\n"
                        + " Set ris-candidates to 0 to disable it. stats, dump, and dump-radius are debugging options.");
        FILE.setComment("tonemap",
                " Controls the final image. ACES 2.0 is the SDR and HDR default;\n"
                        + " PsychoV24 and the analytical operators are opt-in, with BT.2390 as the standards-based HDR\n"
                        + " alternative. gamma: 1 is neutral; lower values brighten midtones.");
        FILE.setComment("exposure",
                " Controls automatic exposure. manual-ev sets exposure in manual mode and adjusts it in auto mode.\n"
                        + " low/high-percentile define the histogram window; pre-exposure keeps stored radiance near mid-grey.\n"
                        + " adapt-darken and adapt-brighten control adjustment speed in seconds.\n"
                        + " sky-weight-cap and emissive-weight-cap limit how much bright areas affect exposure.");
        FILE.setComment("hdr",
                " HDR display output. Requires operating system and display support.\n"
                        + " ui-nits controls UI brightness; peak-nits uses 50-nit increments from 50 to 5000.\n"
                        + " ACES 2.0 uses the nearest baked HDR mastering target; analytical HDR modes use the exact value.");
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
            /** Debug value that exposes the full-resolution path-traced image before reconstruction. */
            public static final int RAW_DEBUG_VIEW = 10;
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

        /** Runtime-safe controls for the optional, separately packaged SHaRC directional cache. */
        public static final class Sharc {
            public static final BooleanSetting ENABLED = bool("caustica.rt.sharc.enabled", "sharc.enabled", true);
            public static final IntSetting CACHE_EXPONENT =
                    clampedInt("caustica.rt.sharc.cacheExponent", "sharc.cache-exponent", 22, 16, 23);
            public static final BooleanSetting ANTI_FIREFLY = bool(
                    "caustica.rt.sharc.antiFirefly", "sharc.anti-firefly", true);
            /** Developer comparison mode; production keeps camera-visible primary surfaces live. */
            public static final BooleanSetting PRIMARY_SURFACE_DEBUG = bool(
                    "caustica.rt.sharc.primarySurfaceDebug", "sharc.primary-surface-debug", false);
            public static final IntSetting UPDATE_TILE_SIZE =
                    clampedInt("caustica.rt.sharc.updateTileSize", "sharc.update-tile-size", 3, 2, 64);
            public static final IntSetting ACCUMULATION_FRAMES =
                    clampedInt("caustica.rt.sharc.accumulationFrames", "sharc.accumulation-frames", 384, 1, 1024);
            public static final IntSetting STALE_FRAMES =
                    clampedInt("caustica.rt.sharc.staleFrames", "sharc.stale-frames", 128, 8, 1024);
            public static final FloatSetting SCENE_SCALE = finiteClampedFloat(
                    "caustica.rt.sharc.sceneScale", "sharc.scene-scale", 32.0f, 1.0f, 100.0f);
            public static final FloatSetting RADIANCE_SCALE = finiteClampedFloat(
                    "caustica.rt.sharc.radianceScale", "sharc.radiance-scale", 1000.0f, 50.0f, 1000.0f);
            public static final FloatSetting GRID_LOGARITHM_BASE = finiteClampedFloat(
                    "caustica.rt.sharc.gridLogarithmBase", "sharc.grid-logarithm-base", 3.0f, 1.01f, 16.0f);
            public static final FloatSetting GRID_LEVEL_BIAS = finiteClampedFloat(
                    "caustica.rt.sharc.gridLevelBias", "sharc.grid-level-bias", 0.0f, -16.0f, 16.0f);
            /** Additional minimum linear roughness for SHaRC diffuse ownership; zero preserves the mirror cutoff. */
            public static final FloatSetting ROUGHNESS_THRESHOLD = finiteClampedFloat(
                    "caustica.rt.sharc.roughnessThreshold", "sharc.roughness-threshold", 0.0f, 0.0f, 1.0f);

            private Sharc() {
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
                    clampedInt("caustica.rt.risCandidates", "lights.ris-candidates", 8, 0, 32);
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
                    percentile("caustica.rt.exposure.lowPercentile", "exposure.low-percentile", 0.50f);
            public static final FloatSetting HIGH_PERCENTILE =
                    percentile("caustica.rt.exposure.highPercentile", "exposure.high-percentile", 0.95f);
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
                String trimmed = value == null ? "" : value.trim();
                if ("auto".equalsIgnoreCase(trimmed)) {
                    return "auto";
                }
                if ("manual".equalsIgnoreCase(trimmed)) {
                    return "manual";
                }
                return "auto";
            }

        }

        /** Scene-referred look transform and baked SDR/HDR ACES display transforms. */
        public static final class Tonemap {
            public static final FloatSetting GAMMA =
                    clampedFloat("caustica.rt.tonemap.gamma", "tonemap.gamma", 1.0f, 0.1f, 5.0f);

            private Tonemap() {
            }
        }

        /** Selectable SDR operators. ACES 2.0 is the default baked-LUT path. */
        public static final class Sdr {
            public static final StringSetting TONE_MAPPER =
                    string("caustica.rt.sdr.toneMapper", "sdr.tone-mapper", "aces2.0",
                            Sdr::sanitizeToneMapper);
            public static final FloatSetting AGX_CONTRAST =
                    finiteClampedFloat("caustica.rt.sdr.agx.contrast", "sdr.agx.contrast", 1.0f, 0.0f, 2.0f);
            public static final FloatSetting AGX_SATURATION =
                    finiteClampedFloat("caustica.rt.sdr.agx.saturation", "sdr.agx.saturation", 1.0f, 0.0f, 3.0f);
            public static final FloatSetting PBR_START_COMPRESSION =
                    finiteClampedFloat("caustica.rt.sdr.pbrNeutral.startCompression",
                            "sdr.pbr-neutral.start-compression", 0.76f, 0.0f, 0.99f);
            public static final FloatSetting PBR_DESATURATION =
                    finiteClampedFloat("caustica.rt.sdr.pbrNeutral.desaturation",
                            "sdr.pbr-neutral.desaturation", 0.15f, 0.0f, 1.0f);
            public static final FloatSetting REINHARD_WHITE_POINT =
                    finiteClampedFloat("caustica.rt.sdr.reinhard.whitePoint",
                            "sdr.reinhard.white-point", 4.0f, 1.0f, 20.0f);
            public static final FloatSetting ACES_EXPOSURE =
                    finiteClampedFloat("caustica.rt.sdr.aces.exposure",
                            "sdr.aces.exposure", 1.0f, 0.0f, 4.0f);
            public static final FloatSetting LOTTES_CONTRAST =
                    finiteClampedFloat("caustica.rt.sdr.lottes.contrast",
                            "sdr.lottes.contrast", 1.0f, 0.1f, 5.0f);
            public static final FloatSetting LOTTES_SHOULDER =
                    finiteClampedFloat("caustica.rt.sdr.lottes.shoulder",
                            "sdr.lottes.shoulder", 1.0f, 0.1f, 5.0f);
            public static final FloatSetting LOTTES_HDR_MAX =
                    finiteClampedFloat("caustica.rt.sdr.lottes.hdrMax",
                            "sdr.lottes.hdr-max", 16.0f, 1.0f, 64.0f);
            public static final FloatSetting LOTTES_MID_IN =
                    finiteClampedFloat("caustica.rt.sdr.lottes.midIn",
                            "sdr.lottes.mid-in", 0.18f, 0.01f, 1.0f);
            public static final FloatSetting LOTTES_MID_OUT =
                    finiteClampedFloat("caustica.rt.sdr.lottes.midOut",
                            "sdr.lottes.mid-out", 0.18f, 0.01f, 1.0f);
            public static final FloatSetting UNCHARTED_A =
                    finiteClampedFloat("caustica.rt.sdr.uncharted2.a", "sdr.uncharted2.a", 0.15f, 0.01f, 1.0f);
            public static final FloatSetting UNCHARTED_B =
                    finiteClampedFloat("caustica.rt.sdr.uncharted2.b", "sdr.uncharted2.b", 0.50f, 0.01f, 2.0f);
            public static final FloatSetting UNCHARTED_C =
                    finiteClampedFloat("caustica.rt.sdr.uncharted2.c", "sdr.uncharted2.c", 0.10f, 0.0f, 1.0f);
            public static final FloatSetting UNCHARTED_D =
                    finiteClampedFloat("caustica.rt.sdr.uncharted2.d", "sdr.uncharted2.d", 0.20f, 0.01f, 2.0f);
            public static final FloatSetting UNCHARTED_E =
                    finiteClampedFloat("caustica.rt.sdr.uncharted2.e", "sdr.uncharted2.e", 0.02f, 0.0f, 1.0f);
            public static final FloatSetting UNCHARTED_F =
                    finiteClampedFloat("caustica.rt.sdr.uncharted2.f", "sdr.uncharted2.f", 0.30f, 0.01f, 2.0f);
            public static final FloatSetting UNCHARTED_WHITE_POINT =
                    finiteClampedFloat("caustica.rt.sdr.uncharted2.whitePoint",
                            "sdr.uncharted2.white-point", 11.2f, 1.0f, 32.0f);
            public static final FloatSetting GT_CONTRAST =
                    finiteClampedFloat("caustica.rt.sdr.gt.contrast", "sdr.gt.contrast", 1.0f, 0.1f, 4.0f);
            public static final FloatSetting GT_LINEAR_START =
                    finiteClampedFloat("caustica.rt.sdr.gt.linearStart", "sdr.gt.linear-start", 0.22f, 0.01f, 0.99f);
            public static final FloatSetting GT_LINEAR_LENGTH =
                    finiteClampedFloat("caustica.rt.sdr.gt.linearLength", "sdr.gt.linear-length", 0.40f, 0.01f, 4.0f);
            public static final FloatSetting GT_BLACK_CURVE =
                    finiteClampedFloat("caustica.rt.sdr.gt.blackCurve", "sdr.gt.black-curve", 1.33f, 0.1f, 4.0f);
            public static final FloatSetting GT_BLACK_LIFT =
                    finiteClampedFloat("caustica.rt.sdr.gt.blackLift", "sdr.gt.black-lift", 0.0f, -0.5f, 0.5f);
            public static final FloatSetting PSYCHOV24_COMPRESSION =
                    finiteClampedFloat("caustica.rt.sdr.psychov24.compression",
                            "sdr.psychov24.compression", 1.0f, 0.0f, 8.0f);
            public static final FloatSetting PSYCHOV24_GAMUT_COMPRESSION =
                    finiteClampedFloat("caustica.rt.sdr.psychov24.gamutCompression",
                            "sdr.psychov24.gamut-compression", 1.0f, 0.0f, 1.0f);
            public static final FloatSetting PSYCHOV24_HIGHLIGHTS =
                    finiteClampedFloat("caustica.rt.sdr.psychov24.highlights",
                            "sdr.psychov24.highlights", 1.0f, 0.0f, 3.0f);
            public static final FloatSetting PSYCHOV24_SHADOWS =
                    finiteClampedFloat("caustica.rt.sdr.psychov24.shadows",
                            "sdr.psychov24.shadows", 1.0f, 0.0f, 3.0f);
            public static final FloatSetting PSYCHOV24_CONTRAST =
                    finiteClampedFloat("caustica.rt.sdr.psychov24.contrast",
                            "sdr.psychov24.contrast", 1.0f, 0.1f, 3.0f);
            public static final FloatSetting PSYCHOV24_PURITY =
                    finiteClampedFloat("caustica.rt.sdr.psychov24.purity",
                            "sdr.psychov24.purity", 1.0f, 0.0f, 3.0f);
            private Sdr() {
            }

            private static String sanitizeToneMapper(String value) {
                return dev.comfyfluffy.caustica.rt.pipeline.RtToneMapping.SdrMode.parse(value).canonicalName();
            }

        }

        /** Render-frame timing + hitch logging. See {@code RtFrameStats}. */
        public static final class FrameStats {
            public static final BooleanSetting ENABLED = bool("caustica.rt.frameStats", "frame-stats.enabled", false);

            private FrameStats() {
            }
        }

        /** Optional scene-linear HDR screenshot output paired with vanilla's F2 PNG. */
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
         * advertise it. The selected display transform owns scene-to-display mapping; {@code uiNits} places
         * SDR-authored UI in that PQ output, while {@code peakNits} controls the display peak. ACES 2.0 is
         * the default and selects the nearest baked HDR LUT; analytical modes use the exact configured peak.
         */
        public static final class Hdr {
            public static final BooleanSetting ENABLED = bool("caustica.rt.hdr", "hdr.enabled", false);
            public static final FloatSetting UI_NITS =
                    clampedFloat("caustica.rt.hdr.uiNits", "hdr.ui-nits", 200.0f, 80.0f, 500.0f);
            public static final FloatSetting PAPER_WHITE_NITS =
                    finiteClampedFloat("caustica.rt.hdr.paperWhiteNits", "hdr.paper-white-nits", 200.0f, 80.0f, 500.0f);
            public static final StringSetting TONE_MAPPER =
                    string("caustica.rt.hdr.toneMapper", "hdr.tone-mapper", "aces2.0",
                            Hdr::sanitizeToneMapper);
            public static final FloatSetting PSYCHOV24_COMPRESSION =
                    finiteClampedFloat("caustica.rt.hdr.psychov24.compression",
                            "hdr.psychov24.compression", 0.0f, 0.0f, 8.0f);
            public static final FloatSetting PSYCHOV24_GAMUT_COMPRESSION =
                    finiteClampedFloat("caustica.rt.hdr.psychov24.gamutCompression",
                            "hdr.psychov24.gamut-compression", 1.0f, 0.0f, 1.0f);
            public static final FloatSetting PSYCHOV24_HIGHLIGHTS =
                    finiteClampedFloat("caustica.rt.hdr.psychov24.highlights",
                            "hdr.psychov24.highlights", 1.0f, 0.0f, 3.0f);
            public static final FloatSetting PSYCHOV24_SHADOWS =
                    finiteClampedFloat("caustica.rt.hdr.psychov24.shadows",
                            "hdr.psychov24.shadows", 1.0f, 0.0f, 3.0f);
            public static final FloatSetting PSYCHOV24_CONTRAST =
                    finiteClampedFloat("caustica.rt.hdr.psychov24.contrast",
                            "hdr.psychov24.contrast", 1.0f, 0.1f, 3.0f);
            public static final FloatSetting PSYCHOV24_PURITY =
                    finiteClampedFloat("caustica.rt.hdr.psychov24.purity",
                            "hdr.psychov24.purity", 1.0f, 0.0f, 3.0f);
            public static final int PEAK_NITS_MIN = 50;
            public static final int PEAK_NITS_MAX = 5000;
            public static final int PEAK_NITS_STEP = 50;
            // ACES 2.0 HDR LUTs are baked only for these mastering targets. Analytical HDR modes do
            // not depend on this list and can use every 50-nit peak exposed by the control.
            public static final List<Integer> ACES_LUT_NITS = List.of(500, 1000, 2000, 4000);
            public static final IntSetting PEAK_NITS =
                    quantizedInt("caustica.rt.hdr.peakNits", "hdr.peak-nits", 1000,
                            PEAK_NITS_MIN, PEAK_NITS_MAX, PEAK_NITS_STEP);

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

            public static float paperWhiteNits() {
                // Keep an invalid persisted paper-white value from exceeding the selected display peak.
                // The raw setting remains intact so raising the peak restores the user's requested value.
                return Math.min(PAPER_WHITE_NITS.value(), PEAK_NITS.value());
            }

            /** Highlight headroom above paper white, in paper-white-referred units. */
            public static float headroom() {
                return Math.max(1.0f, PEAK_NITS.value() / Math.max(1.0f, paperWhiteNits()));
            }

            /** Selects the nearest packaged ACES 2.0 HDR LUT for the requested display peak. */
            public static int nearestAcesLutNits(int requestedNits) {
                int nearest = ACES_LUT_NITS.get(0);
                int nearestDistance = Math.abs(requestedNits - nearest);
                for (int candidate : ACES_LUT_NITS) {
                    int distance = Math.abs(requestedNits - candidate);
                    if (distance < nearestDistance) {
                        nearest = candidate;
                        nearestDistance = distance;
                    }
                }
                return nearest;
            }

            /** Peak represented by the currently active HDR transform and its presentation metadata. */
            public static int effectivePeakNits() {
                return dev.comfyfluffy.caustica.rt.pipeline.RtToneMapping.HdrMode.parse(TONE_MAPPER.get())
                        == dev.comfyfluffy.caustica.rt.pipeline.RtToneMapping.HdrMode.ACES_2_0
                        ? nearestAcesLutNits(PEAK_NITS.value())
                        : PEAK_NITS.value();
            }

            private static String sanitizeToneMapper(String value) {
                return dev.comfyfluffy.caustica.rt.pipeline.RtToneMapping.HdrMode.parse(value).canonicalName();
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

    private static IntSetting quantizedInt(String key, String tomlPath, int fallback,
                                           int min, int max, int step) {
        return new IntSetting(key, tomlPath, fallback,
                v -> Math.clamp(Math.round(v / (float) step) * step, min, max));
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

    private static FloatSetting percentile(String key, String tomlPath, float fallback) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v,
                v -> Double.isFinite(v) ? Math.clamp(v, 0.0, 1.0) : fallback);
    }

    private static FloatSetting clampedFloat(String key, String tomlPath, float fallback, float min, float max) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Math.clamp(v, min, max));
    }

    private static FloatSetting finiteClampedFloat(String key, String tomlPath, float fallback,
                                                   float min, float max) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v,
                v -> Double.isFinite(v) ? Math.clamp(v, min, max) : fallback);
    }

    private static FloatSetting radians(String key, String tomlPath, float fallbackDegrees) {
        return new FloatSetting(key, tomlPath, fallbackDegrees, Math::toRadians, Math::toDegrees, v -> Double.isFinite(v) ? v : 0.0);
    }

    private static int defaultWorkerThreads() {
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
    }
}
