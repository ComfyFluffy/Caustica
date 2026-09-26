package dev.comfyfluffy.caustica.slang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class SlangRuntime {
    private static final String RESOURCE_ROOT = "/caustica/natives/slang/";
    private static final Logger LOGGER = LoggerFactory.getLogger(SlangRuntime.class);

    private final SlangRuntimeConfig config;
    private SlangLibrary library;
    private MemorySegment runtime = MemorySegment.NULL;
    private Path runtimeDirectory;
    private String compilerIdentity;
    private final Set<SlangSession> sessions = new HashSet<>();
    private final List<SlangRuntime> siblings = new ArrayList<>();
    private boolean acceptingSessions = true;

    public SlangRuntime(SlangRuntimeConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    private SlangRuntime(SlangRuntimeConfig config, SlangLibrary library, Path runtimeDirectory) {
        this.config = config;
        this.library = library;
        this.runtimeDirectory = runtimeDirectory;
    }

    /**
     * Returns {@code count} additional runtimes that share this runtime's validated native library, creating
     * the missing ones. The native layer serializes all compilation on one runtime's global session, so only
     * sessions of different runtimes compile concurrently. A sibling creates its global session on its first
     * session request, stays owned by this runtime, and shuts down with it.
     */
    public synchronized List<SlangRuntime> siblings(int count) {
        if (!acceptingSessions) {
            throw new IllegalStateException("Slang runtime is shut down");
        }
        loadLibrary();
        while (siblings.size() < count) {
            siblings.add(new SlangRuntime(config, library, runtimeDirectory));
        }
        return List.copyOf(siblings.subList(0, count));
    }

    public synchronized SlangSession openSession(List<Path> searchPaths, boolean debugInformation,
                                                 boolean warningsAsErrors) {
        Objects.requireNonNull(searchPaths, "searchPaths");
        if (!acceptingSessions) {
            throw new IllegalStateException("Slang runtime is shut down");
        }
        initialize();
        int flags = (debugInformation ? SlangLibrary.SESSION_DEBUG_INFO : 0)
                | (warningsAsErrors ? SlangLibrary.SESSION_WARNINGS_AS_ERRORS : 0);
        MemorySegment sessionHandle = library.createSession(runtime, List.copyOf(searchPaths), flags);
        SlangSession session = new SlangSession(library, sessionHandle, this::removeSession);
        sessions.add(session);
        return session;
    }

    public synchronized String compilerVersion() {
        loadLibrary();
        return library.compilerVersion();
    }

    public synchronized Path runtimeDirectory() {
        loadLibrary();
        return runtimeDirectory;
    }

    /**
     * Identifies the compiler that produces this runtime's output: the Slang version and SHA-256 digests of
     * the native runtime. A bundled runtime is extracted into a directory named by its bundle digest; an
     * override is identified by its compiler, core, and shim libraries. Like the version, the identity does
     * not create the global session.
     */
    public synchronized String compilerIdentity() {
        loadLibrary();
        if (compilerIdentity == null) {
            compilerIdentity = library.compilerVersion() + '|' + (config.runtimeOverride().isPresent()
                    ? overrideDigest(SlangPlatform.current()) : runtimeDirectory.getFileName().toString());
        }
        return compilerIdentity;
    }

    public synchronized boolean isInitialized() {
        return !runtime.equals(MemorySegment.NULL);
    }

    public synchronized void shutdown() {
        acceptingSessions = false;
        for (SlangSession session : sessions) {
            session.retainUntilProcessExit();
        }
        siblings.forEach(SlangRuntime::shutdown);
    }

    private synchronized void removeSession(SlangSession session) {
        sessions.remove(session);
    }

    private void initialize() {
        if (!runtime.equals(MemorySegment.NULL)) {
            return;
        }
        loadLibrary();
        runtime = library.createRuntime();
    }

    /** Loads and validates the native library once; a sibling starts with its runtime's library. */
    private void loadLibrary() {
        if (library != null) {
            return;
        }
        SlangPlatform platform = SlangPlatform.current();
        Path directory = locateRuntime(platform);
        SlangLibrary loaded = SlangLibrary.load(directory, platform);
        String compilerVersion = loaded.compilerVersion();
        String expectedVersion = bundledVersion();
        if (!compilerVersion.startsWith(expectedVersion)) {
            throw new IllegalStateException("Bundled Slang version mismatch: expected " + expectedVersion
                    + ", loaded " + compilerVersion);
        }
        library = loaded;
        runtimeDirectory = directory;
        LOGGER.info("Slang compiler initialized (version {}, runtime {})", compilerVersion, directory);
    }

    private Path locateRuntime(SlangPlatform platform) {
        if (config.runtimeOverride().isPresent()) {
            Path path = config.runtimeOverride().get();
            Path directory = Files.isDirectory(path) ? path : path.getParent();
            if (directory == null || !Files.isRegularFile(directory.resolve(platform.shimName()))) {
                throw new IllegalStateException("Slang runtime override does not contain " + platform.shimName()
                        + ": " + path);
            }
            return directory;
        }
        return extractBundledRuntime(platform);
    }

    private Path extractBundledRuntime(SlangPlatform platform) {
        String version = bundledVersion();
        String manifestResource = RESOURCE_ROOT + version + "/" + platform.resourceName() + "/runtime.json";
        SlangRuntimeManifest manifest;
        try (InputStream input = SlangRuntime.class.getResourceAsStream(manifestResource)) {
            if (input == null) {
                throw new IllegalStateException("Bundled Slang runtime is missing for " + platform.resourceName());
            }
            manifest = SlangRuntimeManifest.read(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read bundled Slang runtime manifest", e);
        }
        if (!manifest.slangVersion().equals(version) || !manifest.platform().equals(platform.resourceName())
                || !manifest.shim().equals(platform.shimName())) {
            throw new IllegalStateException("Bundled Slang runtime manifest identity mismatch");
        }

        Path directory = extractionDirectory(config.extractionRoot(), version, platform,
                manifest.bundleSha256());
        try {
            Files.createDirectories(directory);
            for (SlangRuntimeManifest.FileEntry file : manifest.files()) {
                Path destination = directory.resolve(file.path()).normalize();
                if (!destination.startsWith(directory)) {
                    throw new IOException("Slang runtime path escaped extraction directory: " + file.path());
                }
                if (matches(destination, file)) {
                    continue;
                }
                String resource = RESOURCE_ROOT + version + "/" + platform.resourceName() + "/" + file.path();
                extractResource(resource, destination, file);
            }
            return directory;
        } catch (IOException e) {
            throw new IllegalStateException("Could not extract bundled Slang runtime to " + directory, e);
        }
    }

    /**
     * The bundle's directory under {@code root}, named by a 16-hex-digit prefix of its digest. Windows loads a
     * DLL only from a path shorter than 260 characters and the root sits inside a launcher's game directory, so
     * the name stays short; every extracted file is still verified against its full digest.
     */
    static Path extractionDirectory(Path root, String version, SlangPlatform platform, String bundleSha256) {
        return root.resolve(version).resolve(platform.resourceName()).resolve(bundleSha256.substring(0, 16));
    }

    private static String bundledVersion() {
        try (InputStream input = SlangRuntime.class.getResourceAsStream(RESOURCE_ROOT + "runtime.json")) {
            if (input == null) {
                throw new IllegalStateException("Bundled Slang runtime index is missing");
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (root.get("format").getAsInt() != 1) {
                throw new IllegalStateException("Unsupported bundled Slang runtime index");
            }
            return root.get("slangVersion").getAsString();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not read bundled Slang runtime index", e);
        }
    }

    private static void extractResource(String resource, Path destination,
                                        SlangRuntimeManifest.FileEntry expected) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".tmp");
        try (InputStream input = SlangRuntime.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Bundled Slang file is missing: " + resource);
            }
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (!matches(temporary, expected)) {
                throw new IOException("Bundled Slang file failed size/hash verification: " + resource);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String overrideDigest(SlangPlatform platform) {
        StringBuilder digests = new StringBuilder();
        try {
            for (String name : List.of(platform.compilerName(), platform.coreName(), platform.shimName())) {
                digests.append(sha256(runtimeDirectory.resolve(name)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not hash Slang runtime override " + runtimeDirectory, e);
        }
        return digests.toString();
    }

    private static boolean matches(Path path, SlangRuntimeManifest.FileEntry expected) throws IOException {
        return Files.isRegularFile(path) && Files.size(path) == expected.size()
                && sha256(path).equals(expected.sha256());
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is unavailable", e);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

}
