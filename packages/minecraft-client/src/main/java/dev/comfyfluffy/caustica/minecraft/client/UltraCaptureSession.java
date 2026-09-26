package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.minecraft.client.config.CausticaConfig;
import dev.comfyfluffy.caustica.renderer.runtime.RendererOptions;
import dev.comfyfluffy.caustica.settings.Option;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

/** Owns the single immutable renderer snapshot used by finite capture modes. */
public final class UltraCaptureSession {
    public enum Owner {
        ULTRA_SCREENSHOT
    }

    private static Owner owner;
    private static Object levelIdentity;
    private static int settingsSignature;
    private static boolean remoteSnapshot;
    private static long nextScreenshotToken;
    private static long screenshotToken;
    private static boolean screenshotIsUltra;
    private static final ThreadLocal<Long> SCREENSHOT_THREAD_TOKEN = new ThreadLocal<>();

    private UltraCaptureSession() {
    }

    public static boolean begin(Minecraft minecraft, Owner requestedOwner) {
        if (owner != null || requestedOwner == null || minecraft.level == null || minecraft.player == null) {
            return false;
        }
        Object nextLevelIdentity = minecraft.level;
        int nextSettingsSignature = settingsSignature(minecraft);
        boolean nextRemoteSnapshot = !shouldPauseIntegratedServer(minecraft);
        if (minecraft.gameMode != null) {
            minecraft.gameMode.stopDestroyBlock();
        }
        KeyMapping.releaseAll();
        owner = requestedOwner;
        levelIdentity = nextLevelIdentity;
        settingsSignature = nextSettingsSignature;
        remoteSnapshot = nextRemoteSnapshot;
        return true;
    }

    public static void end() {
        owner = null;
        levelIdentity = null;
        remoteSnapshot = false;
    }

    public static boolean active() {
        return owner != null;
    }

    public static boolean ownedBy(Owner expected) {
        return owner == expected;
    }

    public static boolean valid(Minecraft minecraft) {
        return active()
                && minecraft.level != null
                && minecraft.player != null
                && minecraft.level == levelIdentity
                && minecraft.gui.screen() == null
                && settingsSignature == settingsSignature(minecraft);
    }

    public static boolean shouldPause(Minecraft minecraft) {
        return active() && shouldPauseIntegratedServer(minecraft);
    }

    /** Serializes asynchronous manual PNG writes and rejects callbacks from an older capture. */
    public static synchronized long acquireScreenshot(boolean ultra) {
        if (screenshotToken != 0L) {
            return 0L;
        }
        screenshotToken = ++nextScreenshotToken;
        screenshotIsUltra = ultra;
        return screenshotToken;
    }

    public static synchronized boolean screenshotIsUltra(long token) {
        return token != 0L && token == screenshotToken && screenshotIsUltra;
    }

    /** Releases the lease from the final vanilla PNG callback or a cancelled capture. */
    public static synchronized void releaseScreenshot(long token) {
        if (token != 0L && token == screenshotToken) {
            screenshotToken = 0L;
            screenshotIsUltra = false;
        }
    }

    public static synchronized void discardScreenshotsForShutdown() {
        screenshotToken = 0L;
        screenshotIsUltra = false;
        SCREENSHOT_THREAD_TOKEN.remove();
    }

    public static long screenshotThreadToken() {
        Long token = SCREENSHOT_THREAD_TOKEN.get();
        return token == null ? 0L : token;
    }

    public static void bindScreenshotThreadToken(long token) {
        SCREENSHOT_THREAD_TOKEN.set(token);
    }

    public static void clearScreenshotThreadToken(long token) {
        if (screenshotThreadToken() == token) {
            SCREENSHOT_THREAD_TOKEN.remove();
        }
    }

    /** Runtime-only DLSS quality override; quality 5 is NVIDIA's DLAA mode. */
    public static int effectiveDlssQuality(int configured) {
        return owner == Owner.ULTRA_SCREENSHOT ? UltraScreenshot.DLAA_QUALITY : configured;
    }

    static boolean shouldPauseIntegratedServer(Minecraft minecraft) {
        if (!minecraft.hasSingleplayerServer()) {
            return false;
        }
        IntegratedServer server = minecraft.getSingleplayerServer();
        return server != null && shouldPauseIntegratedServer(true, server.isPublished());
    }

    static boolean shouldPauseIntegratedServer(boolean hasIntegratedServer, boolean publishedToLan) {
        return hasIntegratedServer && !publishedToLan;
    }

    private static int settingsSignature(Minecraft minecraft) {
        int hash = 1;
        for (Option<?> option : RendererOptions.settings()) {
            Object value = CausticaConfig.get(option);
            hash = 31 * hash + option.id().hashCode();
            hash = 31 * hash + (value == null ? 0 : value.hashCode());
        }
        hash = 31 * hash + minecraft.options.fov().get().hashCode();
        hash = 31 * hash + minecraft.options.renderDistance().get().hashCode();
        hash = 31 * hash + minecraft.options.entityDistanceScaling().get().hashCode();
        hash = 31 * hash + minecraft.options.biomeBlendRadius().get().hashCode();
        hash = 31 * hash + minecraft.options.gamma().get().hashCode();
        hash = 31 * hash + minecraft.options.getCameraType().hashCode();
        return hash;
    }
}
