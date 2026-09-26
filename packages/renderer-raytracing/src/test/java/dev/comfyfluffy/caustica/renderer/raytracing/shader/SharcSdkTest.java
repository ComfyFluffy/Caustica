package dev.comfyfluffy.caustica.renderer.raytracing.shader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class SharcSdkTest {
    @Test
    void missingOrDifferentHeadersAreRejected(@TempDir Path include) throws Exception {
        assertTrue(SharcSdk.difference(include).endsWith(" is missing"));
        for (String header : SharcSdk.HEADERS.keySet()) {
            Files.writeString(include.resolve(header), "#define SHARC_VERSION_MAJOR 1\n");
        }
        assertTrue(SharcSdk.difference(include).endsWith(" is not the SHaRC 1.8.0.0 header"));
    }

    @Test
    void pinnedHeadersVerifyWithEitherLineEnding(@TempDir Path copy) throws Exception {
        String sdk = System.getProperty("caustica.test.sharcSdk");
        assumeTrue(sdk != null, "built without -PsharcSdk");
        Path include = Path.of(sdk).resolve("include");
        assertNull(SharcSdk.difference(include));
        for (String header : SharcSdk.HEADERS.keySet()) {
            String text = Files.readString(include.resolve(header), StandardCharsets.UTF_8);
            Files.writeString(copy.resolve(header), text.contains("\r\n")
                    ? text.replace("\r\n", "\n") : text.replace("\n", "\r\n"), StandardCharsets.UTF_8);
        }
        assertNull(SharcSdk.difference(copy));
    }
}
