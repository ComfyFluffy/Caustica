package dev.comfyfluffy.caustica.renderer.raytracing.shader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShaderBinaryCacheTest {
    @Test
    void storedBinaryIsReturnedByTheSameKeyInALaterInstance(@TempDir Path directory) throws Exception {
        ShaderBinaryCache first = ShaderBinaryCache.open(directory, "2026.14.1|bundle");
        byte[] binary = spirv(64);
        first.store(first.key("content", "fill_stable_planes", "main", true), binary);

        ShaderBinaryCache second = ShaderBinaryCache.open(directory, "2026.14.1|bundle");
        assertArrayEquals(binary, second.load(second.key("content", "fill_stable_planes", "main", true)));
    }

    @Test
    void everyKeyInputSelectsADifferentEntry(@TempDir Path directory) throws Exception {
        ShaderBinaryCache cache = ShaderBinaryCache.open(directory, "compiler-a");
        ShaderBinaryCache otherCompiler = ShaderBinaryCache.open(directory, "compiler-b");
        Set<String> keys = new HashSet<>(List.of(
                cache.key("content", "module", "main", true),
                cache.key("content-2", "module", "main", true),
                cache.key("content", "module-2", "main", true),
                cache.key("content", "module", "other", true),
                cache.key("content", "module", "main", false),
                otherCompiler.key("content", "module", "main", true)));
        assertEquals(6, keys.size());
    }

    @Test
    void missingAndCorruptEntriesAreNotReturned(@TempDir Path directory) throws Exception {
        ShaderBinaryCache cache = ShaderBinaryCache.open(directory, "compiler");
        String key = cache.key("content", "module", "main", true);
        assertNull(cache.load(key));

        Path entry = directory.resolve(key + ".spv");
        Files.write(entry, new byte[20]);
        assertNull(cache.load(key));
        assertFalse(Files.exists(entry));
        assertTrue(ShaderBinaryCache.isSpirv(spirv(5)));
        assertFalse(ShaderBinaryCache.isSpirv(spirv(4)));
    }

    @Test
    void openingKeepsTheMostRecentlyUsedEntries(@TempDir Path directory) throws Exception {
        int total = ShaderBinaryCache.MAX_ENTRIES + 3;
        for (int index = 0; index < total; index++) {
            Path file = directory.resolve(String.format("%04d.spv", index));
            Files.write(file, spirv(5));
            Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000L + index * 1000L));
        }
        Files.writeString(directory.resolve("interrupted.tmp"), "partial");

        ShaderBinaryCache cache = ShaderBinaryCache.open(directory, "compiler");
        assertEquals(ShaderBinaryCache.MAX_ENTRIES, entries(directory).size());
        assertEquals("0003.spv", entries(directory).getFirst());

        // Loading the oldest remaining entry makes 0004 the least recently used one.
        cache.load("0003");
        Files.write(directory.resolve("new.spv"), spirv(5));
        ShaderBinaryCache.open(directory, "compiler");
        List<String> kept = entries(directory);
        assertEquals(ShaderBinaryCache.MAX_ENTRIES, kept.size());
        assertTrue(kept.contains("0003.spv"));
        assertFalse(kept.contains("0004.spv"));
    }

    private static List<String> entries(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static byte[] spirv(int words) {
        ByteBuffer buffer = ByteBuffer.allocate(words * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x07230203);
        for (int word = 1; word < words; word++) buffer.putInt(word);
        return buffer.array();
    }
}
