package dev.comfyfluffy.caustica.renderer.raytracing.shader;

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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldShaderFallbackTest {
    private static final ShaderSource BUILTINS = ShaderSource.classpath(
            WorldShaderFallbackTest.class, "/caustica/shaders/fallback", "surface", "environment");
    private static final ShaderDataType<Object> ROOT = ShaderDataType.create("root");
    private static final ShaderDataType<Object> BINDING = ShaderDataType.create("binding");
    private static final ShaderDataType<Object> INSTANCE = ShaderDataType.create("instance");
    private static SlangRuntime runtime;

    @BeforeAll
    static void createRuntime() {
        runtime = new SlangRuntime(new SlangRuntimeConfig(
                Path.of("build/test-slang-fallback-extraction"), Optional.empty()));
    }

    @AfterAll
    static void stopRuntime() {
        runtime.shutdown();
    }

    @Test
    void removedSurfaceAndVolumeSlotsCompileToFallbackOnlyDispatch(@TempDir Path cache) throws Exception {
        ProgramKey surface = new ProgramKey(ProgramKey.Kind.SURFACE, 7);
        ProgramKey volume = new ProgramKey(ProgramKey.Kind.VOLUME, 9);
        ProgramComposition registered = new ProgramComposition(List.of(
                new ProgramComposition.Surface(surface, SurfaceDefinition.opaque(
                        shader("caustica_error_surface", "ErrorSurface"), ROOT.data(1), BINDING, INSTANCE)),
                new ProgramComposition.Volume(volume, new VolumeDefinition<>(
                        shader("caustica_error_surface", "ErrorSurface"), ROOT.data(2), BINDING, INSTANCE))));

        try (WorldShaderCompiler compiler = WorldShaderCompiler.create(runtime, cache.resolve("registered"),
                registered)) {
            assertEquals(List.of(1L, 2L), compiler.composition().implementationData());
        }

        try (WorldShaderCompiler compiler = WorldShaderCompiler.create(runtime, cache.resolve("removed"),
                new ProgramComposition(List.of()))) {
            assertTrue(compiler.composition().implementationData().isEmpty());
            assertSpirv(compiler.compileClosestHit());
            assertSpirv(compiler.compileRadianceAnyHit());
        }
    }

    private static ShaderDefinition shader(String module, String type) {
        return new ShaderDefinition(BUILTINS, module, type);
    }

    private static void assertSpirv(byte[] spirv) {
        assertEquals(0x07230203, ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt());
        assertTrue(spirv.length > 256);
    }
}
