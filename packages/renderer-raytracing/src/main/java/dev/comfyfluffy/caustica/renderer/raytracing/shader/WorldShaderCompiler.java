package dev.comfyfluffy.caustica.renderer.raytracing.shader;

import dev.comfyfluffy.caustica.api.program.ShaderDefinition;
import dev.comfyfluffy.caustica.api.program.ShaderSource;
import dev.comfyfluffy.caustica.engine.program.ProgramBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import dev.comfyfluffy.caustica.engine.program.ProgramKey;
import dev.comfyfluffy.caustica.slang.SlangRuntime;
import dev.comfyfluffy.caustica.slang.SlangSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles one immutable engine {@link ProgramComposition} into specialized world shader stages.
 *
 * <p>Stage methods may run concurrently. Each compile borrows an idle runtime and uses that runtime's
 * session, opened on first use; the native layer serializes compiles per runtime, so distinct runtimes
 * compile in parallel. A stored binary replaces the compile whose inputs it matches. {@link #close()}
 * requires that no compile is in progress.
 */
public final class WorldShaderCompiler implements ProgramBackend.CompiledProgram, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldShaderCompiler.class);
    public static final String ENVIRONMENT_MISS_MODULE = "environment_miss";
    public static final String CLOSEST_HIT_MODULE = "closest_hit";
    public static final String BUILD_STABLE_PLANES_MODULE = "build_stable_planes";
    public static final String VISIBILITY_RAYS_MODULE = "visibility_rays";
    public static final String FILL_STABLE_PLANES_MODULE = "fill_stable_planes";
    public static final String FILL_STABLE_PLANES_SER_MODULE = "fill_stable_planes_ser";
    public static final String RADIANCE_ANY_HIT_MODULE = "radiance_any_hit_rahit";
    public static final String ENTRY_POINT = "main";

    private static final String WORLD_SHADER_ROOT = "/caustica/shaders/world/";
    private static final String API_ROOT = "/caustica/shaders/api/";
    private static final String FALLBACK_ROOT = "/caustica/shaders/fallback/";
    private static final String COMPOSITION_MODULE = "caustica_composition";
    private static final String COMPOSITION_TYPE = "Composition";
    private static final Pattern IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*;");

    private static final List<String> WORLD_MODULES = List.of(
            "bindings.slang", "world_common.slang", "world_minimal.slang", "build_stable_planes.slang",
            "fill_stable_planes.slang", "fill_stable_planes_ser.slang", "resolve_stable_planes.slang", "closest_hit.slang", "environment_miss.slang",
            "radiance_any_hit.rahit.slang", "guide.rmiss.slang",
            "retained_lights.slang", "surface_bsdf.slang", "path_queue_types.slang",
            "trace_transport.slang", "visibility_rays.slang",
            "trace_diagnostics.slang", "trace_diagnostics_types.slang", "trace_diagnostics_config.slang", "path_tracer.slang", "retained_trace_policy.slang",
            "retained_trace_ordinary.slang", "retained_trace_reordered.slang", "stable_planes.slang",
            "stable_plane_types.slang", "nrd_signals.slang", "sharc_types.slang", "sharc_policy.slang",
            "sharc_bridge.slang", "sharc_resolve.slang", "fill_sharc_query.slang", "fill_sharc_query_ser.slang",
            "fill_sharc_update.slang", "fill_sharc_update_ser.slang");
    private static final List<String> API_MODULES = List.of(
            "caustica_api.slang", "caustica_color.slang", "caustica_coverage.slang",
            "caustica_environment.slang", "caustica_resources.slang", "caustica_surface.slang",
            "caustica_types.slang", "caustica_volume.slang");
    private static final List<String> FALLBACK_MODULES = List.of(
            "surface/caustica_error_surface.slang", "surface/caustica_error_coverage.slang",
            "environment/caustica_builtin_environment.slang", "environment/caustica_error_environment.slang");
    private static final Set<String> PROVIDED_MODULES = moduleNames(WORLD_MODULES, API_MODULES, FALLBACK_MODULES);

    private final BlockingQueue<SlangRuntime> idleRuntimes;
    /** A runtime's session is opened and used only by the thread that borrowed that runtime. */
    private final Map<SlangRuntime, SlangSession> sessions = new ConcurrentHashMap<>();
    private final List<Path> searchPaths;
    private final ShaderBinaryCache binaries;
    private final Path worldDirectory;
    private final Path cleanupDirectory;
    private final Composition composition;
    private final Map<StageKey, byte[]> spirvByKey = new ConcurrentHashMap<>();

    /** Composition identity is fixed by the compiler that owns this cache. */
    private record StageKey(String module, String entryPoint, boolean specialized) { }

    private WorldShaderCompiler(List<SlangRuntime> runtimes, List<Path> searchPaths, ShaderBinaryCache binaries,
                                Path worldDirectory, Path cleanupDirectory, Composition composition) {
        this.idleRuntimes = new ArrayBlockingQueue<>(runtimes.size(), false, runtimes);
        this.searchPaths = searchPaths;
        this.binaries = binaries;
        this.worldDirectory = worldDirectory;
        this.cleanupDirectory = cleanupDirectory;
        this.composition = composition;
    }

    public static WorldShaderCompiler create(SlangRuntime runtime, Path cacheDirectory,
                                             ProgramComposition program)
            throws IOException {
        return create(List.of(runtime), cacheDirectory, program, null, null, null);
    }

    /** @param sharcHeaders verified SHaRC SDK headers ({@link SharcSdk}), which the SHaRC stages include */
    public static WorldShaderCompiler create(SlangRuntime runtime, Path cacheDirectory,
                                             ProgramComposition program, Path sharcHeaders)
            throws IOException {
        return create(List.of(runtime), cacheDirectory, program, null, null, sharcHeaders);
    }

    /**
     * Creates a compiler over its own temporary source tree, deleted on close. Stages compile concurrently on
     * up to one session per runtime; a stage stored in {@code binaries}, when given, is loaded instead.
     *
     * @param sharcHeaders verified SHaRC SDK headers, or null for a program without SHaRC stages
     */
    public static WorldShaderCompiler createIsolated(List<SlangRuntime> runtimes, Path cacheRoot,
                                                     ProgramComposition program, ShaderBinaryCache binaries,
                                                     Path sharcHeaders)
            throws IOException {
        Files.createDirectories(cacheRoot);
        Path directory = Files.createTempDirectory(cacheRoot, "runtime-");
        try {
            return create(runtimes, directory, program, directory, binaries, sharcHeaders);
        } catch (IOException | RuntimeException | Error failure) {
            deleteDirectory(directory);
            throw failure;
        }
    }

    private static WorldShaderCompiler create(List<SlangRuntime> runtimes, Path cacheDirectory,
                                              ProgramComposition program, Path cleanupDirectory,
                                              ShaderBinaryCache binaries, Path sharcHeaders) throws IOException {
        Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        Objects.requireNonNull(program, "program");
        Path worldDirectory = cacheDirectory.resolve("world");
        Path apiDirectory = cacheDirectory.resolve("api");
        Path fallbackDirectory = cacheDirectory.resolve("fallback");
        Path extensionDirectory = cacheDirectory.resolve("extensions");
        Path compositionDirectory = cacheDirectory.resolve("composition");

        Map<String, byte[]> sources = new LinkedHashMap<>();
        extractClasspath(WORLD_SHADER_ROOT, WORLD_MODULES, worldDirectory, "world", sources);
        byte[] diagnosticsConfig = ("module trace_diagnostics_config;\n\n"
                + "public static const bool SHADOW_DIAGNOSTICS_ENABLED = "
                + Boolean.getBoolean("caustica.rt.shadowDiagnostics") + ";\n").getBytes(StandardCharsets.UTF_8);
        Files.write(worldDirectory.resolve("trace_diagnostics_config.slang"), diagnosticsConfig);
        sources.put("world/trace_diagnostics_config.slang", diagnosticsConfig);
        writeSpecializedModuleAlias(worldDirectory, "radiance_any_hit.rahit.slang", RADIANCE_ANY_HIT_MODULE);
        extractClasspath(API_ROOT, API_MODULES, apiDirectory, "api", sources);
        extractClasspath(FALLBACK_ROOT, FALLBACK_MODULES, fallbackDirectory, "fallback", sources);

        List<ProgramComposition.Declaration> declarations = program.declarations();
        Map<String, byte[]> modules = resolveModules(declarations);
        Files.createDirectories(extensionDirectory);
        for (var module : modules.entrySet()) {
            Path destination = extensionDirectory.resolve(module.getKey().replace('.', '/') + ".slang");
            Files.createDirectories(destination.getParent());
            Files.write(destination, module.getValue());
            sources.put("extension/" + module.getKey() + ".slang", module.getValue());
        }

        GeneratedComposition generated = compositionRoot(program);
        Files.createDirectories(compositionDirectory);
        Files.writeString(compositionDirectory.resolve(COMPOSITION_MODULE + ".slang"), generated.source(),
                StandardCharsets.UTF_8);
        List<Path> searchPaths = new ArrayList<>(List.of(worldDirectory, apiDirectory, fallbackDirectory,
                extensionDirectory, compositionDirectory));
        if (sharcHeaders != null) {
            searchPaths.add(sharcHeaders);
            // Stored binaries are keyed by source content, and the SHaRC stages include these headers.
            for (String header : SharcSdk.HEADERS.keySet().stream().sorted().toList()) {
                sources.put("sharc/" + header, Files.readAllBytes(sharcHeaders.resolve(header)));
            }
        }
        Composition composition = Composition.create(generated.data(),
                COMPOSITION_MODULE, COMPOSITION_TYPE, generated.source(), sources);
        return new WorldShaderCompiler(runtimes, searchPaths, binaries, worldDirectory, cleanupDirectory,
                composition);
    }

    Composition composition() {
        return composition;
    }

    /** Returns the packed implementation constants consumed by the compiled composition. */
    public List<Long> implementationData() {
        return composition.implementationData();
    }

    public byte[] compileSpecialized(String engineModule, String entryPoint) {
        var key = new StageKey(engineModule, entryPoint, true);
        return cached(key, session -> session.compileSpecialized(engineModule, entryPoint,
                composition.rootModule(), composition.rootType()).spirv(),
                engineModule + ':' + entryPoint + " for " + composition.contentHash().substring(0, 12));
    }

    public byte[] compileEnvironmentMiss() { return compileSpecialized(ENVIRONMENT_MISS_MODULE, ENTRY_POINT); }
    public byte[] compileClosestHit() { return compileSpecialized(CLOSEST_HIT_MODULE, ENTRY_POINT); }
    public byte[] compileRadianceAnyHit() { return compileSpecialized(RADIANCE_ANY_HIT_MODULE, ENTRY_POINT); }
    public byte[] compileBuildStablePlanes() { return compileSpecialized(BUILD_STABLE_PLANES_MODULE, ENTRY_POINT); }
    public byte[] compileVisibilityRays() { return compileSpecialized(VISIBILITY_RAYS_MODULE, ENTRY_POINT); }
    public byte[] compileVolumeLighting() { return compileSpecialized(VISIBILITY_RAYS_MODULE, "volumeLighting"); }
    public byte[] compileFillStablePlanes(boolean reordered) {
        return compileSpecialized(reordered ? FILL_STABLE_PLANES_SER_MODULE : FILL_STABLE_PLANES_MODULE, ENTRY_POINT);
    }

    /** The SHaRC update or query variant of the fill; the compiler must have the SHaRC headers. */
    public byte[] compileSharcFill(boolean reordered, boolean update) {
        return compileSpecialized("fill_sharc_" + (update ? "update" : "query") + (reordered ? "_ser" : ""),
                ENTRY_POINT);
    }

    /** The SHaRC resolve compute; the compiler must have the SHaRC headers. */
    public byte[] compileSharcResolve() {
        return compilePlain("sharc_resolve.slang", ENTRY_POINT);
    }

    public byte[] compilePlain(String moduleFileName, String entryPoint) {
        Objects.requireNonNull(moduleFileName, "moduleFileName");
        Objects.requireNonNull(entryPoint, "entryPoint");
        return cached(new StageKey(moduleFileName, entryPoint, false), session -> {
            Path file = worldDirectory.resolve(moduleFileName);
            try {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String base = moduleFileName.substring(0, moduleFileName.length() - ".slang".length());
                return session.compile(base.replace('.', '_'), file.toString(), source, entryPoint).spirv();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + file, e);
            }
        }, moduleFileName + ':' + entryPoint);
    }

    private byte[] cached(StageKey key, Function<SlangSession, byte[]> compile, String label) {
        byte[] known = spirvByKey.get(key);
        if (known != null) return known;
        String dumpDirectory = System.getProperty("caustica.debug.shader-dump-directory");
        // A dump exports what this process compiles, so it bypasses stored binaries.
        String storedKey = binaries == null || dumpDirectory != null ? null
                : binaries.key(composition.contentHash(), key.module(), key.entryPoint(), key.specialized());
        byte[] spirv = storedKey == null ? null : binaries.load(storedKey);
        if (spirv != null) {
            LOGGER.info("Loaded cached world shader {} ({} bytes SPIR-V)", label, spirv.length);
        } else {
            long startNanos = System.nanoTime();
            spirv = withSession(compile);
            if (storedKey != null) binaries.store(storedKey, spirv);
            if (dumpDirectory != null) {
                try {
                    Path directory = Files.createDirectories(Path.of(dumpDirectory));
                    Path dump = Files.createTempFile(directory, key.module() + "-" + key.entryPoint() + "-", ".spv");
                    Files.write(dump, spirv);
                    LOGGER.info("World shader {} binary: {}", label, dump.toAbsolutePath());
                } catch (IOException e) {
                    throw new UncheckedIOException("Could not export compiled world shader " + label, e);
                }
            }
            LOGGER.info(String.format(Locale.ROOT,
                    "Compiled world shader %s in %.1f ms (%d bytes SPIR-V)", label,
                    (System.nanoTime() - startNanos) / 1.0e6, spirv.length));
        }
        // computeIfAbsent would compile under a map bin lock and serialize stages whose keys share a bin.
        byte[] raced = spirvByKey.putIfAbsent(key, spirv);
        return raced == null ? spirv : raced;
    }

    private byte[] withSession(Function<SlangSession, byte[]> compile) {
        SlangRuntime runtime;
        try {
            runtime = idleRuntimes.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a Slang runtime", e);
        }
        try {
            SlangSession session = sessions.get(runtime);
            if (session == null) {
                session = runtime.openSession(searchPaths, true, true);
                sessions.put(runtime, session);
            }
            return compile.apply(session);
        } finally {
            idleRuntimes.add(runtime);
        }
    }

    private static GeneratedComposition compositionRoot(ProgramComposition program) {
        List<ProgramComposition.Surface> surfaces = new ArrayList<>();
        List<ProgramComposition.Volume> volumes = new ArrayList<>();
        List<ProgramComposition.Environment> environments = new ArrayList<>();
        program.declarations().forEach(declaration -> {
            switch (declaration) {
                case ProgramComposition.Surface surface -> surfaces.add(surface);
                case ProgramComposition.Volume volume -> volumes.add(volume);
                case ProgramComposition.Environment environment -> environments.add(environment);
            }
        });
        Map<ProgramKey, Integer> dataOffsets = new LinkedHashMap<>();
        List<Long> data = new ArrayList<>();
        for (ProgramComposition.Surface surface : surfaces) {
            dataOffsets.put(surface.key(), data.size());
            data.add(surface.definition().implementationData().bits());
        }
        for (ProgramComposition.Volume volume : volumes) {
            dataOffsets.put(volume.key(), data.size());
            data.add(volume.definition().implementationData().bits());
        }

        StringBuilder source = new StringBuilder("module ").append(COMPOSITION_MODULE).append(";\n\n")
                .append("import caustica_api;\nimport caustica_types;\nimport caustica_surface;\n")
                .append("import caustica_coverage;\nimport caustica_volume;\nimport caustica_environment;\n")
                .append("import caustica_error_surface;\nimport caustica_error_coverage;\n")
                .append("import caustica_builtin_environment;\nimport caustica_error_environment;\n");
        program.declarations().stream().flatMap(WorldShaderCompiler::definitions).map(ShaderDefinition::module)
                .distinct().sorted().forEach(module -> source.append("import ").append(module).append(";\n"));

        source.append("\npublic struct SurfaceDispatch : ISurfaceDispatch {\n")
                .append("    public OpenPbrSurface evaluateSurface(uint implementation, SurfaceInput input) {\n")
                .append("        switch (implementation) {\n");
        for (ProgramComposition.Surface value : surfaces) {
            source.append("            case ").append(value.key().implementationIndex())
                    .append("u: { input.implementationData = ")
                    .append("ShaderDataPtr<uint64_t>(input.shaderRoot.compositionData)[")
                    .append(dataOffsets.get(value.key())).append("u]; ")
                    .append(value.definition().surface().type())
                    .append(" value; return value.evaluateSurface(input); }\n");
        }
        source.append("            default: { ErrorSurface value; return value.evaluateSurface(input); }\n")
                .append("        }\n    }\n};\n\n")
                .append("public struct CoverageDispatch : ICoverageDispatch {\n")
                .append("    public float evaluateCoverage(uint implementation, CoverageInput input) {\n")
                .append("        switch (implementation) {\n");
        for (ProgramComposition.Surface value : surfaces) {
            ShaderDefinition coverage = value.definition().coverage();
            if (coverage == null) continue;
            source.append("            case ").append(value.key().implementationIndex())
                    .append("u: { input.implementationData = ")
                    .append("ShaderDataPtr<uint64_t>(input.shaderRoot.compositionData)[")
                    .append(dataOffsets.get(value.key())).append("u]; ").append(coverage.type())
                    .append(" value; return value.evaluateCoverage(input); }\n");
        }
        source.append("            default: { ErrorCoverage value; return value.evaluateCoverage(input); }\n")
                .append("        }\n    }\n};\n\n")
                .append("public struct VacuumVolume : IVolumeModel {};\n\n")
                .append("public struct VolumeDispatch : IVolumeDispatch {\n")
                .append("    public VolumeProperties evaluateVolume(uint implementation, VolumeInput input) {\n")
                .append("        switch (implementation) {\n");
        appendVolumeCases(source, volumes, dataOffsets, "input", "evaluateVolume");
        source.append("            default: { VacuumVolume value; return value.evaluateVolume(input); }\n")
                .append("        }\n    }\n\n")
                .append("    public float3 evaluateBoundaryLighting(uint implementation, VolumeBoundaryLightingInput input) {\n")
                .append("        switch (implementation) {\n");
        appendVolumeCases(source, volumes, dataOffsets, "input.volume", "evaluateBoundaryLighting");
        source.append("            default: { VacuumVolume value; return value.evaluateBoundaryLighting(input); }\n")
                .append("        }\n    }\n\n")
                .append("    public SpatialMediumProperties evaluateSpatialMedium(uint implementation, SpatialMediumInput input) {\n")
                .append("        switch (implementation) {\n");
        appendVolumeCases(source, volumes, dataOffsets, "input", "evaluateSpatialMedium");
        source.append("            default: { VacuumVolume value; return value.evaluateSpatialMedium(input); }\n")
                .append("        }\n    }\n};\n\n")
                .append("public struct EnvironmentDispatch : IEnvironmentDispatch {\n")
                .append("    public float3 evaluateEnvironment(uint implementation, EnvironmentQuery query) {\n")
                .append("        switch (implementation) {\n")
                .append("            case 0u: { BuiltinEnvironment value; return value.evaluateEnvironment(query); }\n");
        for (ProgramComposition.Environment value : environments) {
            source.append("            case ").append(value.key().implementationIndex())
                    .append("u: { ")
                    .append(value.definition().implementation().type())
                    .append(" value; return value.evaluateEnvironment(query); }\n");
        }
        source.append("            default: { ErrorEnvironment value; return value.evaluateEnvironment(query); }\n")
                .append("        }\n    }\n};\n\n")
                .append("public struct Composition : IComposition {\n")
                .append("    public typealias Surfaces = SurfaceDispatch;\n")
                .append("    public typealias Coverages = CoverageDispatch;\n")
                .append("    public typealias Volumes = VolumeDispatch;\n")
                .append("    public typealias Environments = EnvironmentDispatch;\n};\n");
        return new GeneratedComposition(source.toString(), data);
    }

    private static void appendVolumeCases(StringBuilder source, List<ProgramComposition.Volume> volumes,
                                          Map<ProgramKey, Integer> offsets, String dataInput, String method) {
        for (ProgramComposition.Volume value : volumes) {
            source.append("            case ").append(value.key().implementationIndex()).append("u: { ")
                    .append(dataInput).append(".implementationData = ")
                    .append("ShaderDataPtr<uint64_t>(").append(dataInput)
                    .append(".shaderRoot.compositionData)[").append(offsets.get(value.key())).append("u]; ")
                    .append(value.definition().implementation().type()).append(" value; return value.")
                    .append(method).append("(input); }\n");
        }
    }

    private static java.util.stream.Stream<ShaderDefinition> definitions(
            ProgramComposition.Declaration declaration) {
        return switch (declaration) {
            case ProgramComposition.Surface surface -> surface.definition().coverage() == null
                    ? java.util.stream.Stream.of(surface.definition().surface())
                    : java.util.stream.Stream.of(surface.definition().surface(), surface.definition().coverage());
            case ProgramComposition.Volume volume -> java.util.stream.Stream.of(volume.definition().implementation());
            case ProgramComposition.Environment environment ->
                    java.util.stream.Stream.of(environment.definition().implementation());
        };
    }

    private static Map<String, byte[]> resolveModules(
            List<ProgramComposition.Declaration> declarations) throws IOException {
        List<ShaderSource> sources = new ArrayList<>();
        Set<ShaderSource> identities = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        declarations.stream().flatMap(WorldShaderCompiler::definitions).map(ShaderDefinition::source)
                .forEach(source -> { if (identities.add(source)) sources.add(source); });
        Map<String, byte[]> resolved = new LinkedHashMap<>();
        for (ProgramComposition.Declaration declaration : declarations) {
            for (ShaderDefinition definition : definitions(declaration).toList()) {
                resolveModule(definition.module(), sources, resolved);
            }
        }
        return resolved;
    }

    private static void resolveModule(String name, List<ShaderSource> sources,
                                      Map<String, byte[]> resolved)
            throws IOException {
        if (PROVIDED_MODULES.contains(name) || resolved.containsKey(name)) return;
        byte[] bytes = null;
        for (ShaderSource source : sources) {
            byte[] candidate = readModule(source, name);
            if (candidate == null) continue;
            if (bytes != null && !java.util.Arrays.equals(bytes, candidate)) {
                throw new IOException("ambiguous extension shader module " + name);
            }
            bytes = candidate;
        }
        if (bytes == null) throw new IOException("missing extension shader module " + name);
        // Register before following imports so shared dependencies are read once; Slang validates the graph.
        resolved.put(name, bytes);
        Matcher imports = IMPORT.matcher(new String(bytes, StandardCharsets.UTF_8));
        while (imports.find()) resolveModule(imports.group(1), sources, resolved);
    }

    private static byte[] readModule(ShaderSource source, String name) throws IOException {
        try (InputStream input = source.openModule(name)) {
            return input == null ? null : input.readAllBytes();
        }
    }

    private static void extractClasspath(String root, List<String> names, Path destination,
                                         String group, Map<String, byte[]> sources) throws IOException {
        Files.createDirectories(destination);
        for (String name : names) {
            try (InputStream input = WorldShaderCompiler.class.getResourceAsStream(root + name)) {
                if (input == null) throw new IOException("Missing bundled Slang source: " + root + name);
                byte[] bytes = input.readAllBytes();
                Files.write(destination.resolve(flatFileName(name)), bytes);
                sources.put(group + '/' + name, bytes);
            }
        }
    }

    private static void writeSpecializedModuleAlias(Path directory, String sourceFile, String module)
            throws IOException {
        Files.copy(directory.resolve(sourceFile), directory.resolve(module + ".slang"));
    }

    private static String flatFileName(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    @SafeVarargs
    private static Set<String> moduleNames(List<String>... groups) {
        Set<String> names = new LinkedHashSet<>();
        for (List<String> group : groups) {
            for (String file : group) {
                String stem = flatFileName(file);
                stem = stem.substring(0, stem.length() - ".slang".length());
                names.add(stem.replace('.', '_'));
                names.add(stem);
            }
        }
        return Set.copyOf(names);
    }

    @Override
    public void close() {
        sessions.values().forEach(SlangSession::retainUntilProcessExit);
        if (cleanupDirectory != null) deleteDirectory(cleanupDirectory);
    }

    private static void deleteDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("Could not delete temporary shader sources at {}", directory, e);
        }
    }

    private record GeneratedComposition(String source, List<Long> data) { }
}
