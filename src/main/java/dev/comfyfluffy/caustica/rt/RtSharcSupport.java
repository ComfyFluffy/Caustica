package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaMod;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Optional-build and device-capability gate for the pinned NVIDIA SHaRC 1.8 shader family. */
public final class RtSharcSupport {
    public static final String VERSION = "1.8.0.0";
    public static final String COMMIT = "e19ccacd511f42a3df6f850052d508c13c9e9737";

    private static final Properties METADATA = loadMetadata();
    private static final boolean ARTIFACTS_PRESENT = verifyArtifacts();
    private static volatile boolean shaderBufferInt64Atomics;
    private static volatile boolean shaderFloat16;
    private static volatile boolean storageBuffer16BitAccess;
    private static volatile String failure;

    private RtSharcSupport() {
    }

    /** True only when this jar was built with the exact SDK and contains SHaRC artifacts/license metadata. */
    public static boolean packaged() {
        return "true".equalsIgnoreCase(METADATA.getProperty("artifacts"))
                && VERSION.equals(METADATA.getProperty("version"))
                && COMMIT.equalsIgnoreCase(METADATA.getProperty("commit"))
                && "true".equalsIgnoreCase(METADATA.getProperty("directionalSh"))
                && ARTIFACTS_PRESENT;
    }

    /** Called during Vulkan device creation after the optional feature bits were queried. */
    public static void setDeviceFeaturesEnabled(boolean int64Atomics, boolean float16, boolean storage16) {
        shaderBufferInt64Atomics = int64Atomics;
        shaderFloat16 = float16;
        storageBuffer16BitAccess = storage16;
    }

    /** Latched runtime failure disables only SHaRC; ordinary Caustica RT continues. */
    public static void fail(String reason, Throwable cause) {
        failure = reason;
        if (cause == null) {
            CausticaMod.LOGGER.warn("SHaRC disabled: {}", reason);
        } else {
            CausticaMod.LOGGER.warn("SHaRC disabled: " + reason, cause);
        }
    }

    public static void clearFailure() {
        failure = null;
    }

    public static boolean available() {
        return packaged() && RtDeviceBringup.rtRequested()
                && shaderBufferInt64Atomics && shaderFloat16 && storageBuffer16BitAccess
                && failure == null;
    }

    public static String status() {
        if (!packaged()) return "unavailable (jar has no SHaRC artifacts)";
        if (!RtDeviceBringup.rtRequested()) return "unavailable (ray tracing device not enabled)";
        if (!shaderBufferInt64Atomics) return "unavailable (shaderBufferInt64Atomics unsupported)";
        if (!shaderFloat16) return "unavailable (shaderFloat16 unsupported)";
        if (!storageBuffer16BitAccess) return "unavailable (storageBuffer16BitAccess unsupported)";
        return failure == null ? "available (SHaRC " + VERSION + ")" : "unavailable (" + failure + ")";
    }

    private static Properties loadMetadata() {
        Properties properties = new Properties();
        try (InputStream in = RtSharcSupport.class.getResourceAsStream("/caustica/sharc.properties")) {
            if (in != null) properties.load(in);
        } catch (IOException e) {
            CausticaMod.LOGGER.warn("Could not read SHaRC build metadata", e);
        }
        return properties;
    }

    /** Validate the fixed SHaRC resource set once when the class is initialized, never per frame. */
    private static boolean verifyArtifacts() {
        String[] resources = {
                "/caustica/shaders/pipelines/world/indirect_sharc_query.rgen.spv",
                "/caustica/shaders/pipelines/world/indirect_sharc_ser_query.rgen.spv",
                "/caustica/shaders/pipelines/world/indirect_sharc_update.rgen.spv",
                "/caustica/shaders/pipelines/world/indirect_sharc_ser_update.rgen.spv",
                "/caustica/shaders/sharc/sharc_resolve.comp.spv",
                "/META-INF/licenses/nvidia/NVIDIA-SHARC-SDK.txt"
        };
        for (String resource : resources) {
            try (InputStream in = RtSharcSupport.class.getResourceAsStream(resource)) {
                if (in == null || in.read() < 0) {
                    CausticaMod.LOGGER.warn("Missing SHaRC packaged resource: {}", resource);
                    return false;
                }
            } catch (IOException e) {
                CausticaMod.LOGGER.warn("Could not validate SHaRC packaged resource: " + resource, e);
                return false;
            }
        }
        return true;
    }
}
