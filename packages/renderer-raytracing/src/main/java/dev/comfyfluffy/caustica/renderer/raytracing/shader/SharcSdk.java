package dev.comfyfluffy.caustica.renderer.raytracing.shader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;

/**
 * Locates the NVIDIA SHaRC 1.8.0.0 shader headers for the runtime world-shader compiler.
 *
 * <p>A build made with {@code -PsharcSdk} records the SDK directory in {@code caustica/sharc.properties};
 * the {@code caustica.rt.sharc.sdk} system property overrides it. The headers are a separately licensed
 * external input: they are compiled where they are after their content is verified, and are never
 * copied or packaged.
 */
public final class SharcSdk {
    private static final Logger LOGGER = LoggerFactory.getLogger(SharcSdk.class);
    static final String METADATA = "/caustica/sharc.properties";
    static final String LOCATION_PROPERTY = "caustica.rt.sharc.sdk";
    /** SHA-256 of each header with LF line endings, SHaRC 1.8.0.0 commit e19ccacd. */
    static final Map<String, String> HEADERS = Map.of(
            "SharcCommon.h", "d4b6e2765828a4b1c71bbf40609b2dbe72b4aec8ff4d80ccda21a83ab634ecc4",
            "SharcTypes.h", "cde3f200e4e84f029968dffd9eede5228f2ae0b0bbc166d2babb1aa88dc044b3",
            "HashGridCommon.h", "5a6d67186a88e61f0d47518f5986021f309f2897ccb482ab9e011a7a08749e7f",
            "HashGridTypes.h", "34012bcffff0f2545c108a7c9dcb223298a70ebc2c377a86fbddfd1ecd76d614");
    private static final Path INCLUDE_DIRECTORY = locate();

    private SharcSdk() {
    }

    /** The verified header directory, or null when this build recorded no SDK or its headers differ. */
    public static Path includeDirectory() {
        return INCLUDE_DIRECTORY;
    }

    private static Path locate() {
        Properties metadata = new Properties();
        try (InputStream input = SharcSdk.class.getResourceAsStream(METADATA)) {
            if (input == null) return null;
            metadata.load(input);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        Path include = Path.of(System.getProperty(LOCATION_PROPERTY, metadata.getProperty("sdk")))
                .toAbsolutePath().normalize().resolve("include");
        String difference = difference(include);
        if (difference != null) {
            LOGGER.warn("SHaRC disabled: {}", difference);
            return null;
        }
        LOGGER.info("SHaRC 1.8.0.0 headers verified at {}", include);
        return include;
    }

    /** Null when every pinned header exists with its pinned content, otherwise the first difference. */
    static String difference(Path include) {
        for (var header : HEADERS.entrySet()) {
            Path file = include.resolve(header.getKey());
            if (!Files.isRegularFile(file)) return file + " is missing";
            if (!sha256(file).equals(header.getValue())) return file + " is not the SHaRC 1.8.0.0 header";
        }
        return null;
    }

    private static String sha256(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
