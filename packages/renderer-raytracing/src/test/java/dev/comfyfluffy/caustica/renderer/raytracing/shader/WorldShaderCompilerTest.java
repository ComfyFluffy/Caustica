package dev.comfyfluffy.caustica.renderer.raytracing.shader;

import dev.comfyfluffy.caustica.api.program.EnvironmentDefinition;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.ShaderDefinition;
import dev.comfyfluffy.caustica.api.program.ShaderSource;
import dev.comfyfluffy.caustica.api.program.SurfaceDefinition;
import dev.comfyfluffy.caustica.api.program.VolumeDefinition;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import dev.comfyfluffy.caustica.engine.program.ProgramKey;
import dev.comfyfluffy.caustica.slang.SlangRuntime;
import dev.comfyfluffy.caustica.slang.SlangRuntimeConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Arrays;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class WorldShaderCompilerTest {
    private static final ShaderSource BUILTINS = ShaderSource.classpath(WorldShaderCompilerTest.class,
            "/caustica/shaders/fallback", "surface", "environment");
    private static final byte[] SPIRV_DEBUG_INFO_IMPORT =
            "NonSemantic.Shader.DebugInfo.100\0".getBytes(StandardCharsets.US_ASCII);
    private static final ShaderDataType<Object> DATA = ShaderDataType.create("test-data");
    private static final ShaderDataType<Object> BINDING = ShaderDataType.create("test-binding");
    private static final ShaderDataType<Object> INSTANCE = ShaderDataType.create("test-instance");
    private static SlangRuntime runtime;

    @BeforeAll
    static void createRuntime() {
        runtime = new SlangRuntime(new SlangRuntimeConfig(
                Path.of("build/test-slang-extraction"),
                Optional.empty()));
    }

    @AfterAll
    static void stopRuntime() {
        runtime.shutdown();
    }

    @Test
    void preservesStableCategorySlotsAndBuildsImplementationDataTable(@TempDir Path cache) throws Exception {
        ProgramKey surfaceKey = new ProgramKey(ProgramKey.Kind.SURFACE, 1);
        ProgramKey volumeKey = new ProgramKey(ProgramKey.Kind.VOLUME, 2);
        ProgramKey environmentKey = new ProgramKey(ProgramKey.Kind.ENVIRONMENT, 3);
        ProgramComposition program = new ProgramComposition(
                List.of(new ProgramComposition.Surface(surfaceKey, SurfaceDefinition.of(
                                shader("caustica_error_surface", "ErrorSurface"),
                                shader("caustica_error_coverage", "ErrorCoverage"), DATA.data(41), BINDING, INSTANCE)),
                        new ProgramComposition.Volume(volumeKey, new VolumeDefinition<>(
                                shader("caustica_water_surface", "WaterVolume"), DATA.data(42), BINDING, INSTANCE)),
                        new ProgramComposition.Environment(environmentKey, new EnvironmentDefinition<>(
                                shader("caustica_builtin_environment", "BuiltinEnvironment"), BINDING))));

        // Use the builtin surface as a stand-in volume only for source-generation assertions; the
        // generated volume type is not specialized in this test.
        ProgramComposition sourceOnly = new ProgramComposition(List.of(
                program.declarations().get(0), program.declarations().get(2)));
        try (WorldShaderCompiler compiler = WorldShaderCompiler.create(runtime, cache, sourceOnly)) {
            assertTrue(compiler.composition().rootSource().contains("case 3u:"));
            assertEquals(List.of(41L), compiler.composition().implementationData());
            assertTrue(compiler.composition().rootSource().contains("ShaderDataPtr<uint64_t>"));
            assertTrue(compiler.composition().rootSource().contains(
                    "case 0u: { BuiltinEnvironment value;"));
            assertTrue(compiler.composition().rootSource().contains(
                    "default: { ErrorEnvironment value;"));
            assertFalse(compiler.composition().rootSource().contains("SurfaceModifier"));
            byte[] closest = compiler.compileClosestHit();
            byte[] radianceAny = compiler.compileRadianceAnyHit();
            byte[] environment = compiler.compileEnvironmentMiss();
            for (byte[] stage : List.of(closest, radianceAny, environment)) {
                assertSpirv(stage);
                assertEquals(28, incomingPayloadBytes(stage));
            }
            assertVulkan14(cache.resolve("radiance-closest.spv"), closest);
            assertVulkan14(cache.resolve("radiance-any.spv"), radianceAny);
            assertVulkan14(cache.resolve("environment-miss.spv"), environment);
            assertSpirv(compiler.compileEnvironmentMiss());
            assertSpirv(compiler.compilePlain("guide.rmiss.slang", WorldShaderCompiler.ENTRY_POINT));
            byte[] build = compiler.compileBuildStablePlanes();
            assertSpirv(build);
            assertTrue(assertRadianceTraceRouting(build) > 0);
            byte[] resolve = compiler.compilePlain("resolve_stable_planes.slang", WorldShaderCompiler.ENTRY_POINT);
            assertSpirv(resolve);
            assertVulkan14(cache.resolve("resolve-stable-planes.spv"), resolve);
            byte[] ordinary = compiler.compileFillStablePlanes(false);
            byte[] reordered = compiler.compileFillStablePlanes(true);
            assertSpirv(ordinary);
            assertSpirv(reordered);
            assertVulkan14(cache.resolve("fill-stable-planes-ordinary.spv"), ordinary);
            assertVulkan14(cache.resolve("fill-stable-planes-ser.spv"), reordered);
            assertShadowTraceRouting(ordinary);
            assertShadowTraceRouting(reordered);
            byte[] visibility = compiler.compileVisibilityRays();
            assertSpirv(visibility);
            assertVulkan14(cache.resolve("visibility-rays.spv"), visibility);
            assertShadowTraceRouting(visibility);
            byte[] volumeLighting = compiler.compileVolumeLighting();
            assertSpirv(volumeLighting);
            assertVulkan14(cache.resolve("volume-lighting.spv"), volumeLighting);
            assertShadowTraceRouting(volumeLighting);
        }
    }

    @Test
    @ResourceLock("java.lang.System.properties")
    void shadowDiagnosticsAtomicsAreAbsentUnlessExplicitlyEnabled(@TempDir Path cache) throws Exception {
        String property = "caustica.rt.shadowDiagnostics";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "false");
            String ordinaryHash;
            var program = new ProgramComposition(List.of());
            try (WorldShaderCompiler compiler = WorldShaderCompiler.create(runtime, cache.resolve("normal"), program)) {
                byte[] normal = compiler.compileFillStablePlanes(false);
                assertEquals(0, countOpcode(normal, 239)); // OpAtomicUMax
                assertEquals(0, countOpcode(normal, 230)); // OpAtomicCompareExchange
                ordinaryHash = compiler.composition().contentHash();
            }
            System.setProperty(property, "true");
            try (WorldShaderCompiler compiler = WorldShaderCompiler.create(runtime, cache.resolve("diagnostics"), program)) {
                assertFalse(ordinaryHash.equals(compiler.composition().contentHash()));
                for (boolean reordered : new boolean[]{false, true}) {
                    byte[] diagnostic = compiler.compileFillStablePlanes(reordered);
                    assertTrue(countOpcode(diagnostic, 239) > 0);
                    assertTrue(countOpcode(diagnostic, 230) > 0);
                    assertVulkan14(cache.resolve("diagnostics-" + reordered + ".spv"), diagnostic);
                }
            }
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Test
    void spatialAndHomogeneousVolumesCompileThroughTheSameDispatch(@TempDir Path cache) throws Exception {
        ShaderSource source = ShaderSource.classpath(WorldShaderCompilerTest.class, "/caustica-test");
        var program = new ProgramComposition(List.of(
                new ProgramComposition.Volume(new ProgramKey(ProgramKey.Kind.VOLUME, 1),
                        new VolumeDefinition<>(source.definition("spatial_medium_test", "SpatialTestVolume"),
                                DATA.data(37), BINDING, INSTANCE)),
                new ProgramComposition.Volume(new ProgramKey(ProgramKey.Kind.VOLUME, 2),
                        new VolumeDefinition<>(source.definition("spatial_medium_test", "HomogeneousTestVolume"),
                                DATA.data(41), BINDING, INSTANCE))));
        try (WorldShaderCompiler compiler = WorldShaderCompiler.create(runtime, cache, program)) {
            assertEquals(List.of(37L, 41L), compiler.composition().implementationData());
            byte[] build = compiler.compileBuildStablePlanes();
            byte[] fill = compiler.compileFillStablePlanes(false);
            assertVulkan14(cache.resolve("spatial-build.spv"), build);
            assertVulkan14(cache.resolve("spatial-fill.spv"), fill);
        }
    }

    @Test
    @ResourceLock("java.lang.System.properties")
    void storedBinariesReplaceMatchingCompilesExceptForDumps(@TempDir Path cache) throws Exception {
        String property = "caustica.debug.shader-dump-directory";
        String previous = System.getProperty(property);
        var program = new ProgramComposition(List.of());
        ShaderBinaryCache binaries = ShaderBinaryCache.open(cache.resolve("spirv"), runtime.compilerIdentity());
        byte[] stored = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(0x07230203).array();
        try {
            System.clearProperty(property);
            try (WorldShaderCompiler compiler = WorldShaderCompiler.createIsolated(
                    List.of(runtime), cache.resolve("sources"), program, binaries, null)) {
                byte[] compiled = compiler.compilePlain("guide.rmiss.slang", WorldShaderCompiler.ENTRY_POINT);
                String key = binaries.key(compiler.composition().contentHash(),
                        "guide.rmiss.slang", WorldShaderCompiler.ENTRY_POINT, false);
                assertArrayEquals(compiled, binaries.load(key));
                binaries.store(key, stored);
            }
            try (WorldShaderCompiler compiler = WorldShaderCompiler.createIsolated(
                    List.of(runtime), cache.resolve("sources"), program, binaries, null)) {
                assertArrayEquals(stored, compiler.compilePlain("guide.rmiss.slang", WorldShaderCompiler.ENTRY_POINT));
            }

            System.setProperty(property, cache.resolve("dumps").toString());
            try (WorldShaderCompiler compiler = WorldShaderCompiler.createIsolated(
                    List.of(runtime), cache.resolve("sources"), program, binaries, null)) {
                byte[] dumped = compiler.compilePlain("guide.rmiss.slang", WorldShaderCompiler.ENTRY_POINT);
                assertSpirv(dumped);
                try (var dumps = Files.list(cache.resolve("dumps"))) {
                    assertArrayEquals(dumped, Files.readAllBytes(dumps.findFirst().orElseThrow()));
                }
            }
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Test
    void stagesCompileConcurrentlyOnSiblingRuntimes(@TempDir Path cache) throws Exception {
        List<SlangRuntime> runtimes = new ArrayList<>(List.of(runtime));
        runtimes.addAll(runtime.siblings(1));
        ExecutorService threads = Executors.newFixedThreadPool(4);
        try (WorldShaderCompiler compiler = WorldShaderCompiler.createIsolated(
                runtimes, cache, new ProgramComposition(List.of()), null, null)) {
            List<Callable<byte[]>> compiles = List.of(
                    () -> compiler.compilePlain("guide.rmiss.slang", WorldShaderCompiler.ENTRY_POINT),
                    () -> compiler.compilePlain("resolve_stable_planes.slang", WorldShaderCompiler.ENTRY_POINT),
                    compiler::compileClosestHit,
                    compiler::compileEnvironmentMiss);
            List<Future<byte[]>> stages = new ArrayList<>();
            for (Callable<byte[]> compile : compiles) stages.add(threads.submit(compile));
            for (Future<byte[]> stage : stages) assertSpirv(stage.get(2, TimeUnit.MINUTES));
        } finally {
            threads.shutdown();
        }
    }

    @Test
    void sharcHeadersKeyStoredBinaries(@TempDir Path cache) throws Exception {
        var program = new ProgramComposition(List.of());
        Path first = Files.createDirectories(cache.resolve("first"));
        Path second = Files.createDirectories(cache.resolve("second"));
        for (String header : SharcSdk.HEADERS.keySet()) {
            Files.writeString(first.resolve(header), "// " + header);
            Files.writeString(second.resolve(header), "// " + header);
        }
        Files.writeString(second.resolve("SharcCommon.h"), "// changed");
        List<String> hashes = new ArrayList<>();
        for (Path headers : Arrays.asList(null, first, second)) {
            try (WorldShaderCompiler compiler = WorldShaderCompiler.create(runtime,
                    cache.resolve("sources-" + hashes.size()), program, headers)) {
                hashes.add(compiler.composition().contentHash());
            }
        }
        assertEquals(3, Set.copyOf(hashes).size(), hashes::toString);
    }

    @Test
    void sharcStagesCompileAgainstThePinnedSdk(@TempDir Path cache) throws Exception {
        String sdk = System.getProperty("caustica.test.sharcSdk");
        assumeTrue(sdk != null, "built without -PsharcSdk");
        Path headers = Path.of(sdk).resolve("include");
        assertNull(SharcSdk.difference(headers));
        var program = new ProgramComposition(List.of(new ProgramComposition.Surface(
                new ProgramKey(ProgramKey.Kind.SURFACE, 1), SurfaceDefinition.of(
                        shader("caustica_error_surface", "ErrorSurface"),
                        shader("caustica_error_coverage", "ErrorCoverage"), DATA.data(7), BINDING, INSTANCE))));
        try (WorldShaderCompiler compiler = WorldShaderCompiler.create(runtime, cache, program, headers)) {
            for (boolean reordered : new boolean[]{false, true}) {
                byte[] query = compiler.compileSharcFill(reordered, false);
                byte[] update = compiler.compileSharcFill(reordered, true);
                assertSpirv(query);
                assertSpirv(update);
                assertVulkan14(cache.resolve("sharc-query-" + reordered + ".spv"), query);
                assertVulkan14(cache.resolve("sharc-update-" + reordered + ".spv"), update);
                assertShadowTraceRouting(query);
                assertShadowTraceRouting(update);
                // Only the update inserts hash grid keys, a 64-bit compare-exchange.
                assertEquals(0, countOpcode(query, 230));
                assertTrue(countOpcode(update, 230) > 0);
            }
            byte[] resolve = compiler.compileSharcResolve();
            assertSpirv(resolve);
            assertVulkan14(cache.resolve("sharc-resolve.spv"), resolve);
            // A descriptor-heap shader object carries no set or binding decoration.
            assertEquals(0, countDecorations(resolve, 33) + countDecorations(resolve, 34));
        }
    }

    private static int countDecorations(byte[] spirv, int decoration) {
        var words = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        int found = 0;
        for (int offset = 5; offset < words.limit(); offset += words.get(offset) >>> 16) {
            if ((words.get(offset) & 65535) == 71 && words.get(offset + 2) == decoration) found++; // OpDecorate
        }
        return found;
    }

    private static ShaderDefinition shader(String module, String type) {
        return new ShaderDefinition(BUILTINS, module, type);
    }

    private static Map<Integer, int[]> spirvDefinitions(byte[] spirv) {
        var words = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        Map<Integer, int[]> definitions = new HashMap<>();
        for (int offset = 5; offset < words.limit();) {
            int count = words.get(offset) >>> 16;
            int opcode = words.get(offset) & 65535;
            int[] instruction = new int[count];
            for (int i = 0; i < count; i++) instruction[i] = words.get(offset + i);
            if (opcode >= 20 && opcode <= 32) definitions.put(instruction[1], instruction);
            if (opcode == 43 || opcode == 59) definitions.put(instruction[2], instruction);
            offset += count;
        }
        return definitions;
    }

    private static int typeBytes(Map<Integer, int[]> definitions, int id) {
        int[] type = definitions.get(id);
        return switch (type[0] & 65535) {
            case 20 -> 4;
            case 21, 22 -> type[2] / 8;
            case 23 -> typeBytes(definitions, type[2]) * type[3];
            case 30 -> {
                int bytes = 0;
                for (int i = 2; i < type.length; i++) bytes += typeBytes(definitions, type[i]);
                yield bytes;
            }
            case 32 -> typeBytes(definitions, type[3]);
            default -> throw new AssertionError("Unexpected payload type opcode " + (type[0] & 65535));
        };
    }

    private static int incomingPayloadBytes(byte[] spirv) {
        var definitions = spirvDefinitions(spirv);
        return definitions.values().stream()
                .filter(instruction -> (instruction[0] & 65535) == 59 && instruction[3] == 5342)
                .mapToInt(instruction -> typeBytes(definitions, instruction[1])).findFirst().orElseThrow();
    }

    /** Returns the radiance trace count after checking each trace's SBT routing and payload. */
    private static int assertRadianceTraceRouting(byte[] spirv) {
        var definitions = spirvDefinitions(spirv);
        var words = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        int traces = 0;
        for (int offset = 5; offset < words.limit(); offset += words.get(offset) >>> 16) {
            int opcode = words.get(offset) & 65535;
            if (opcode != 4445 && opcode != 5316) continue; // OpTraceRayKHR, OpHitObjectTraceRayEXT
            // Radiance rays address one hit record per geometry and the only miss record, the environment.
            int flags = offset + (opcode == 4445 ? 2 : 3);
            int[] payload = definitions.get(words.get(flags + 9));
            assertEquals(28, typeBytes(definitions, payload[1]));
            assertEquals(0, definitions.get(words.get(flags))[3]);
            assertEquals(0, definitions.get(words.get(flags + 2))[3]);
            assertEquals(1, definitions.get(words.get(flags + 3))[3]);
            assertEquals(0, definitions.get(words.get(flags + 4))[3]);
            traces++;
        }
        return traces;
    }

    private static void assertShadowTraceRouting(byte[] spirv) {
        assertRadianceTraceRouting(spirv);
        var definitions = spirvDefinitions(spirv);
        var words = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        int shadowQueries = 0;
        for (int offset = 5; offset < words.limit();) {
            int count = words.get(offset) >>> 16;
            if ((words.get(offset) & 65535) == 4473) { // OpRayQueryInitializeKHR
                // NoneKHR: geometry opacity and opacity micromaps decide which candidates reach the shader.
                assertEquals(0, definitions.get(words.get(offset + 3))[3]);
                assertEquals(1, definitions.get(words.get(offset + 4))[3]); // Secondary mask
                shadowQueries++;
            }
            offset += count;
        }
        assertTrue(shadowQueries > 0);
        assertTrue(countOpcode(spirv, 4477) > 0); // OpRayQueryProceedKHR
        assertTrue(countOpcode(spirv, 4476) > 0); // OpRayQueryConfirmIntersectionKHR
    }

    private static int countOpcode(byte[] spirv, int opcode) {
        var words = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        int found = 0;
        for (int offset = 5; offset < words.limit(); offset += words.get(offset) >>> 16) {
            if ((words.get(offset) & 65535) == opcode) found++;
        }
        return found;
    }

    private static void assertSpirv(byte[] spirv) {
        assertEquals(0x07230203, ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt());
        assertTrue(spirv.length > 256);
        assertTrue(contains(spirv, SPIRV_DEBUG_INFO_IMPORT),
                "SPIR-V must embed NonSemantic.Shader.DebugInfo.100");
    }

    private static boolean contains(byte[] contents, byte[] expected) {
        for (int offset = 0; offset <= contents.length - expected.length; offset++) {
            int index = 0;
            while (index < expected.length && contents[offset + index] == expected[index]) index++;
            if (index == expected.length) return true;
        }
        return false;
    }

    private static void assertVulkan14(Path output, byte[] spirv) throws Exception {
        Files.write(output, spirv);
        Process validator = new ProcessBuilder(System.getProperty("caustica.test.spirvVal"),
                "--target-env", "vulkan1.4", output.toString()).redirectErrorStream(true).start();
        String diagnostics = new String(validator.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, validator.waitFor(), diagnostics);
    }


}
