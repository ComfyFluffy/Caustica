package dev.comfyfluffy.caustica.slang;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SlangRuntimeSiblingsTest {
    private static final String SOURCE = """
            RWStructuredBuffer<uint> output;

            [shader("compute")]
            [numthreads(1, 1, 1)]
            void main(uint3 id : SV_DispatchThreadID) { output[id.x] = 7; }
            """;

    @Test
    void siblingsAreCreatedOnceAndShutDownWithTheirRuntime(@TempDir Path sources) {
        SlangRuntime runtime = new SlangRuntime(new SlangRuntimeConfig(sources.resolve("extraction"),
                Optional.of(Path.of(System.getProperty("caustica.test.slangRuntimeDir")))));
        List<SlangRuntime> siblings = runtime.siblings(2);
        assertNotSame(siblings.get(0), siblings.get(1));
        assertSame(siblings.getFirst(), runtime.siblings(1).getFirst());
        assertEquals(siblings, runtime.siblings(2));
        assertTrue(runtime.compilerIdentity().startsWith(System.getProperty("caustica.test.slangVersion")));

        SlangSession session = siblings.get(1).openSession(List.of(sources), false, true);
        byte[] spirv = session.compile("sibling", "sibling.slang", SOURCE, "main").spirv();
        assertEquals(0x07230203, ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt());
        // Only a session request creates a global session.
        assertFalse(runtime.isInitialized());
        assertFalse(siblings.getFirst().isInitialized());
        assertTrue(siblings.get(1).isInitialized());

        runtime.shutdown();
        assertThrows(IllegalStateException.class, () -> session.compile("sibling", "sibling.slang", SOURCE, "main"));
        assertThrows(IllegalStateException.class, () -> siblings.getFirst().openSession(List.of(), false, true));
        assertThrows(IllegalStateException.class, () -> runtime.siblings(1));
    }
}
