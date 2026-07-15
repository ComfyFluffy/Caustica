package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.CausticaMod;

/** Resource-epoch material diagnostics. Kept out of extraction hot paths. */
public final class RtMaterialDiagnostics {
    private RtMaterialDiagnostics() {
    }

    public static void reportAtlas(String label, int spriteCount, int specCount, int normalCount,
                                   int width, int height, int bytesPerTexel, int textureCount) {
        int complete = Math.min(specCount, normalCount);
        int fallback = Math.max(0, spriteCount - Math.max(specCount, normalCount));
        long bytes = Math.multiplyExact(Math.multiplyExact((long) width, height),
                Math.multiplyExact(bytesPerTexel, textureCount));
        CausticaMod.LOGGER.info(
                "RT material atlas {}: sprites={}, spec={}, normal={}, complete<={}, neutralFallback={}, baseLevelMiB={}",
                label, spriteCount, specCount, normalCount, complete, fallback,
                String.format(java.util.Locale.ROOT, "%.2f", bytes / (1024.0 * 1024.0)));
    }
}
