package dev.comfyfluffy.caustica.renderer.raytracing.shader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Persists compiled world-shader SPIR-V across launches.
 *
 * <p>An entry key hashes the compiler identity, the composition's complete source content hash, and the
 * stage (module, entry point, specialization). Any changed input therefore selects a different entry; an
 * entry is never revalidated against sources. Entries are written atomically, so concurrent writers and
 * readers observe either no entry or a complete binary. Debug-info source paths inside a reused binary name
 * the temporary source directory of the launch that compiled it.
 */
public final class ShaderBinaryCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderBinaryCache.class);
    private static final int SPIRV_MAGIC = 0x07230203;
    private static final String SUFFIX = ".spv";
    private static final String TEMPORARY_SUFFIX = ".tmp";
    /** Most recently used entries kept when the cache opens: one per stage of a few dozen programs. */
    static final int MAX_ENTRIES = 384;

    private final Path directory;
    private final String compilerIdentity;

    private ShaderBinaryCache(Path directory, String compilerIdentity) {
        this.directory = directory;
        this.compilerIdentity = compilerIdentity;
    }

    /**
     * Opens the cache and removes interrupted writes and all but the most recently used entries. Like reads
     * and writes, pruning is best effort: a failure is logged and never fails compilation.
     */
    public static ShaderBinaryCache open(Path directory, String compilerIdentity) throws IOException {
        Files.createDirectories(directory);
        try {
            prune(directory);
        } catch (IOException e) {
            LOGGER.warn("Could not prune cached world shaders in {}", directory, e);
        }
        return new ShaderBinaryCache(directory, compilerIdentity);
    }

    String key(String contentHash, String module, String entryPoint, boolean specialized) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : List.of(compilerIdentity, contentHash, module, entryPoint,
                    specialized ? "specialized" : "plain")) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is required by the Java platform", e);
        }
    }

    /** Returns the stored binary, or null when it is absent or unreadable. */
    byte[] load(String key) {
        Path file = directory.resolve(key + SUFFIX);
        if (!Files.isRegularFile(file)) return null;
        try {
            byte[] spirv = Files.readAllBytes(file);
            if (!isSpirv(spirv)) {
                Files.deleteIfExists(file);
                return null;
            }
            // Pruning keeps the most recently used entries.
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
            return spirv;
        } catch (IOException e) {
            LOGGER.warn("Could not read cached world shader {}", file, e);
            return null;
        }
    }

    void store(String key, byte[] spirv) {
        Path file = directory.resolve(key + SUFFIX);
        try {
            Path temporary = Files.createTempFile(directory, key, TEMPORARY_SUFFIX);
            Files.write(temporary, spirv);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("Could not store compiled world shader {}", file, e);
        }
    }

    static boolean isSpirv(byte[] bytes) {
        return bytes.length >= 20 && bytes.length % 4 == 0
                && ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == SPIRV_MAGIC;
    }

    private static void prune(Path directory) throws IOException {
        List<Path> files;
        try (var listing = Files.list(directory)) {
            files = listing.toList();
        }
        List<Entry> binaries = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.endsWith(TEMPORARY_SUFFIX)) {
                Files.deleteIfExists(file);
            } else if (name.endsWith(SUFFIX)) {
                binaries.add(new Entry(file, Files.getLastModifiedTime(file)));
            }
        }
        binaries.sort(Comparator.comparing(Entry::used).reversed());
        for (Entry stale : binaries.subList(Math.min(MAX_ENTRIES, binaries.size()), binaries.size())) {
            Files.deleteIfExists(stale.file());
        }
    }

    private record Entry(Path file, FileTime used) { }
}
