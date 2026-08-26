package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

/** Owns the single immutable renderer snapshot used by finite capture modes. */
public final class CaptureSession {
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

    private CaptureSession() {
    }

    public static boolean begin(Minecraft minecraft, Owner requestedOwner) {
        if (owner != null || requestedOwner == null || minecraft.level == null || minecraft.player == null) {
            return false;
        }
        Object nextLevelIdentity = minecraft.level;
        int nextSettingsSignature = settingsSignature(minecraft);
        boolean nextRemoteSnapshot = !shouldPauseIntegratedServer(minecraft);
        boolean entitiesStarted = false;
        boolean compositeStarted = false;
        try {
            if (minecraft.gameMode != null) {
                minecraft.gameMode.stopDestroyBlock();
            }
            entitiesStarted = true;
            RtEntities.INSTANCE.beginCaptureSession();
            compositeStarted = true;
            RtComposite.INSTANCE.beginCaptureSession();
            KeyMapping.releaseAll();
            owner = requestedOwner;
            levelIdentity = nextLevelIdentity;
            settingsSignature = nextSettingsSignature;
            remoteSnapshot = nextRemoteSnapshot;
            return true;
        } catch (Throwable failure) {
            Throwable cleanupFailure = null;
            if (compositeStarted) {
                try {
                    RtComposite.INSTANCE.endCaptureSession();
                } catch (Throwable t) {
                    cleanupFailure = appendFailure(cleanupFailure, t);
                }
            }
            if (entitiesStarted) {
                try {
                    RtEntities.INSTANCE.endCaptureSession();
                } catch (Throwable t) {
                    cleanupFailure = appendFailure(cleanupFailure, t);
                }
            }
            if (nextRemoteSnapshot) {
                try {
                    RtTerrain.requestFullClear();
                } catch (Throwable t) {
                    cleanupFailure = appendFailure(cleanupFailure, t);
                }
            }
            if (cleanupFailure != null) {
                failure.addSuppressed(cleanupFailure);
            }
            throwUnchecked(failure);
            return false;
        }
    }

    public static void end() {
        if (owner == null) {
            return;
        }
        boolean rebuildRemoteScene = remoteSnapshot;
        Throwable failure = null;
        try {
            RtComposite.INSTANCE.endCaptureSession();
        } catch (Throwable t) {
            failure = appendFailure(failure, t);
        }
        try {
            RtEntities.INSTANCE.endCaptureSession();
        } catch (Throwable t) {
            failure = appendFailure(failure, t);
        }
        if (rebuildRemoteScene) {
            try {
                RtTerrain.requestFullClear();
            } catch (Throwable t) {
                failure = appendFailure(failure, t);
            }
        }
        owner = null;
        levelIdentity = null;
        remoteSnapshot = false;
        if (failure != null) {
            throwUnchecked(failure);
        }
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

    /** Serializes asynchronous manual PNG writes and rejects callbacks from an older renderer instance. */
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

    /** Releases the lease from the final vanilla PNG callback or a cancelled F4 capture. */
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

    /** Runtime-only SPP override; the configured setting is never mutated or serialized. */
    public static int effectiveSpp(int configured) {
        return owner == Owner.ULTRA_SCREENSHOT ? UltraScreenshot.SCREENSHOT_SPP : configured;
    }

    /** Runtime-only DLSS quality override; quality 5 is NVIDIA's DLAA mode. */
    public static int effectiveDlssQuality(int configured) {
        return owner == Owner.ULTRA_SCREENSHOT ? UltraScreenshot.DLAA_QUALITY : configured;
    }

    private static boolean shouldPauseIntegratedServer(Minecraft minecraft) {
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
        CausticaConfig.ensureRegistered();
        int hash = 1;
        for (CausticaConfig.RuntimeSetting<?> setting : CausticaConfig.settings()) {
            Object value = setting.get();
            hash = 31 * hash + setting.key().hashCode();
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

    private static Throwable appendFailure(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new RuntimeException(failure);
    }
}
