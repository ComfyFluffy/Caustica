package dev.comfyfluffy.caustica.rt.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtDisplayShaderContractTest {
    private static final Path DISPLAY_SHADER = Path.of(System.getProperty("user.dir"),
            "shaders", "pipelines", "display", "main.comp.slang");

    @Test
    void acesAndAnalyticalModesUseTheirOwnedSceneSignals() throws IOException {
        String source = Files.readString(DISPLAY_SHADER).replaceAll("\\s+", " ");

        assertTrue(source.contains("float3 exposedAcesCg = sceneLinearAcesCg * exposure;"));
        assertTrue(source.contains("exposedAcesCg += sampleBloom(pix, w, h) * max(pc.bloomStrength, 0.0);"));
        assertTrue(source.contains("if (pc.sdrMode == 0 || (pc.hdrEnabled != 0 && pc.hdrMode == 0)) { "
                + "lookedAcesCg = applyLook(exposedAcesCg); }"));
        assertTrue(source.contains("? tonemap(lookedAcesCg) : localSdrToneMap(exposedAcesCg);"));
        assertTrue(source.contains("? float4(tonemapHdr(lookedAcesCg), 1.0) : float4(displayGammaHdr(localHdrToneMap(exposedAcesCg)), 1.0);"));
        assertFalse(source.contains("localSdrToneMap(lookedAcesCg)"));
        assertFalse(source.contains("localHdrToneMap(lookedAcesCg)"));
        assertEquals(1, occurrences(source, "applyLook(exposedAcesCg)"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
