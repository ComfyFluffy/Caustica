package dev.comfyfluffy.caustica.ngx;

import com.mojang.blaze3d.vulkan.VulkanDevice;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import net.fabricmc.loader.api.FabricLoader;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkInstance;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Shared NVIDIA NGX lifetime for the mod. Loads the native shim, extracts the bundled NGX feature DLLs,
 * and runs {@code ngxshim_init} / {@code ngxshim_shutdown} exactly once per Vulkan device. Multiple NGX
 * features (DLSS Ray Reconstruction, and later Frame Generation) share this single initialized
 * {@link NgxLibrary}; each feature owns only its own create/evaluate/release. NGX is shut down only at
 * device teardown (so releasing one feature can't tear NGX down while another still holds a handle).
 */
public final class NgxRuntime {
    public static final NgxRuntime INSTANCE = new NgxRuntime();

    private static final PlatformNatives PLATFORM_NATIVES = PlatformNatives.current();

    private NgxLibrary lib;
    private boolean initialized;
    private boolean failed;
    private long initializedDevice;
    private boolean instanceExtensionsNegotiated;
    private boolean deviceExtensionsNegotiated;
    private boolean extensionNegotiationFailed;

    private NgxRuntime() {
    }

    /**
     * Ensure NGX is loaded and initialized for {@code device}, returning the shared {@link NgxLibrary}, or
     * {@code null} if it is unavailable. Idempotent; latches initialization failure so it is not retried
     * every frame. Extension negotiation remains fail-closed for the current Vulkan instance.
     */
    public synchronized NgxLibrary acquire(VulkanDevice device) {
        if (initialized) {
            if (initializedDevice == device.vkDevice().address()) {
                return lib;
            }
            if (!shutdown()) {
                return null;
            }
        }
        if (failed) {
            return null;
        }
        try {
            init(device);
            initialized = true;
            initializedDevice = device.vkDevice().address();
            return lib;
        } catch (Throwable t) {
            failed = true;
            initializedDevice = 0L;
            lib = null;
            CausticaMod.LOGGER.error("NGX init failed; DLSS features disabled", t);
            return null;
        }
    }

    public synchronized boolean isInitialized() {
        return initialized;
    }

    /** Allow an explicit render-state recovery action to retry a failed shared NGX initialization. */
    public synchronized void resetFailureLatch() {
        if (!initialized && !extensionNegotiationFailed) {
            failed = false;
        }
    }

    /** Query the shim before Vulkan creation and add every NGX-required extension only as one valid set. */
    public synchronized void negotiateRequiredExtensions(boolean deviceExtensions,
                                                          Collection<String> requested,
                                                          Predicate<String> supported) {
        if (!deviceExtensions && !initialized) {
            instanceExtensionsNegotiated = false;
            deviceExtensionsNegotiated = false;
            extensionNegotiationFailed = false;
            failed = false;
        }
        if (extensionNegotiationFailed) {
            return;
        }
        String scope = deviceExtensions ? "device" : "instance";
        try {
            if (!PLATFORM_NATIVES.supported()) {
                throw new IllegalStateException("NGX natives are not bundled for "
                        + PLATFORM_NATIVES.platformDir());
            }
            Path shim = locateShim();
            if (shim == null) {
                throw new IllegalStateException(PLATFORM_NATIVES.shimName() + " is unavailable");
            }
            if (lib == null) {
                lib = NgxLibrary.load(shim);
            }
            List<String> required = queryRequiredExtensions(lib, deviceExtensions);
            List<String> missing = required.stream().filter(extension -> !supported.test(extension)).toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("required " + scope + " extensions are unavailable: " + missing);
            }
            for (String extension : required) {
                if (requested.add(extension)) {
                    CausticaMod.LOGGER.info("Enabling {} extension {} required by NGX", scope, extension);
                }
            }
            if (deviceExtensions) {
                deviceExtensionsNegotiated = true;
            } else {
                instanceExtensionsNegotiated = true;
            }
        } catch (Throwable t) {
            extensionNegotiationFailed = true;
            failed = true;
            lib = null;
            CausticaMod.LOGGER.warn("NGX {} extension negotiation failed; DLSS features disabled", scope, t);
        }
    }

    /**
     * Shut down NGX. Call only at device teardown, after every feature has been released. Uses the
     * device captured at initialization; no-op if NGX was never initialized. Returns false while NGX
     * retains native device ownership and the Vulkan device must stay alive.
     */
    public synchronized boolean shutdown() {
        if (lib != null && initialized && initializedDevice != 0L) {
            try {
                int result = lib.shutdown(initializedDevice);
                if (ngxFailed(result)) {
                    throw new IllegalStateException("ngxshim_shutdown returned 0x"
                            + Integer.toHexString(result));
                }
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("NGX shutdown failed; native ownership is retained until restart", t);
                return false;
            }
        }
        initialized = false;
        failed = extensionNegotiationFailed;
        initializedDevice = 0L;
        lib = null;
        return true;
    }

    /** NVSDK_NGX_Result: failure when the top 12 bits == 0xBAD. Shared by all NGX feature wrappers. */
    public static boolean ngxFailed(int result) {
        return (result & 0xFFF00000) == 0xBAD00000;
    }

    private void init(VulkanDevice device) {
        if (extensionNegotiationFailed || !instanceExtensionsNegotiated || !deviceExtensionsNegotiated) {
            throw new IllegalStateException("NGX Vulkan extensions were not negotiated before device creation");
        }
        if (!PLATFORM_NATIVES.supported()) {
            throw new IllegalStateException("NGX natives are not bundled for " + PLATFORM_NATIVES.platformDir());
        }
        Path shim = locateShim();
        if (shim == null) {
            throw new IllegalStateException(PLATFORM_NATIVES.shimName()
                    + " not found (bundled natives or -Dcaustica.ngx.path)");
        }
        Path nativesDir = shim.getParent();
        if (nativesDir != null) {
            List<String> missingFeatures = missingFeatureLibraries(nativesDir);
            if (!missingFeatures.isEmpty()) {
                CausticaMod.LOGGER.warn("NGX feature libraries {} not found next to {}; those features will be unavailable",
                        missingFeatures, PLATFORM_NATIVES.shimName());
            }
        }

        lib = NgxLibrary.load(shim);

        Path dataPath = FabricLoader.getInstance().getGameDir().resolve("caustica-ngx");
        try {
            Files.createDirectories(dataPath);
        } catch (Exception e) {
            CausticaMod.LOGGER.warn("Could not create NGX data path {}", dataPath, e);
        }

        VkInstance instance = device.vkDevice().getPhysicalDevice().getInstance();
        try (Arena arena = Arena.ofConfined()) {
            long gipa = VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr");
            if (gipa == 0L) {
                throw new IllegalStateException("vkGetInstanceProcAddr is unavailable");
            }
            long gdpa;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                gdpa = VK10.vkGetInstanceProcAddr(instance, stack.ASCII("vkGetDeviceProcAddr"));
            }
            int rc = lib.init(0L, wideString(arena, dataPath.toString()),
                    instance.address(), device.vkDevice().getPhysicalDevice().address(), device.vkDevice().address(),
                    gipa, gdpa, wideString(arena, nativesDir == null ? "" : nativesDir.toString()));
            if (ngxFailed(rc)) {
                throw new IllegalStateException("ngxshim_init failed: 0x" + Integer.toHexString(rc)
                        + " last=0x" + Integer.toHexString(lib.lastResult()));
            }
        }
        CausticaMod.LOGGER.info("NGX initialized (shim {})", shim);
    }

    private static List<String> queryRequiredExtensions(NgxLibrary library, boolean deviceExtensions) {
        final int capacity = 8192;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(capacity, 1);
            int count = library.requiredExtensions(deviceExtensions, buffer, capacity);
            if (count < 0) {
                throw new IllegalStateException("ngxshim_required_extensions returned " + count);
            }
            byte[] bytes = buffer.toArray(ValueLayout.JAVA_BYTE);
            int length = 0;
            while (length < bytes.length && bytes[length] != 0) {
                length++;
            }
            List<String> extensions = new String(bytes, 0, length, StandardCharsets.UTF_8).lines()
                    .map(String::strip).filter(name -> !name.isEmpty()).toList();
            if (extensions.size() != count) {
                throw new IllegalStateException("NGX extension list was truncated or malformed: expected "
                        + count + " names, got " + extensions.size());
            }
            return extensions;
        }
    }

    private static Path locateShim() {
        String override = CausticaConfig.Ngx.PATH.get();
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            if (Files.isDirectory(p)) {
                p = p.resolve(PLATFORM_NATIVES.shimName());
            }
            return Files.isRegularFile(p) ? p : null;
        }
        return extractBundledNatives();
    }

    private static Path extractBundledNatives() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("caustica-ngx")
                .resolve("natives").resolve(PLATFORM_NATIVES.platformDir());
        try {
            Files.createDirectories(dir);
            boolean hasShim = extractBundledNative(PLATFORM_NATIVES.shimName(), dir.resolve(PLATFORM_NATIVES.shimName()));
            extractBundledFeatureLibraries(dir);
            return hasShim && Files.isRegularFile(dir.resolve(PLATFORM_NATIVES.shimName()))
                    ? dir.resolve(PLATFORM_NATIVES.shimName()) : null;
        } catch (IOException e) {
            CausticaMod.LOGGER.warn("Could not extract bundled NGX natives to {}", dir, e);
            return null;
        }
    }

    private static boolean extractBundledNative(String name, Path dst) throws IOException {
        String resource = PLATFORM_NATIVES.resourceDir() + name;
        try (InputStream in = NgxRuntime.class.getResourceAsStream(resource)) {
            if (in == null) {
                return false;
            }
            byte[] bytes = in.readAllBytes();
            if (!sameBytes(dst, bytes)) {
                Files.write(dst, bytes);
            }
            return true;
        }
    }

    private static void extractBundledFeatureLibraries(Path dir) throws IOException {
        Set<String> current = new HashSet<>();
        for (String name : PLATFORM_NATIVES.exactFeatureNames()) {
            if (extractBundledNative(name, dir.resolve(name))) {
                current.add(name);
            }
        }
        for (String name : bundledFeatureLibraryNames()) {
            if (extractBundledNative(name, dir.resolve(name))) {
                current.add(name);
            }
        }
        List<Path> stale;
        try (Stream<Path> files = Files.list(dir)) {
            stale = files.filter(Files::isRegularFile)
                    .filter(path -> PLATFORM_NATIVES.isFeatureLibrary(path.getFileName().toString()))
                    .filter(path -> !current.contains(path.getFileName().toString()))
                    .toList();
        }
        for (Path path : stale) {
            Files.deleteIfExists(path);
        }
    }

    private static List<String> bundledFeatureLibraryNames() {
        List<String> names = new ArrayList<>();
        FabricLoader.getInstance().getModContainer("caustica").ifPresent(container -> {
            String nativeDir = "caustica/natives/" + PLATFORM_NATIVES.platformDir();
            for (Path root : container.getRootPaths()) {
                Path dir = root.resolve(nativeDir);
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(dir)) {
                    files.map(path -> path.getFileName().toString())
                            .filter(PLATFORM_NATIVES::isFeatureLibrary)
                            .forEach(names::add);
                } catch (IOException e) {
                    CausticaMod.LOGGER.warn("Could not list bundled NGX natives in {}", dir, e);
                }
            }
        });
        return names;
    }

    private static List<String> missingFeatureLibraries(Path dir) {
        List<String> missing = new ArrayList<>();
        for (String name : PLATFORM_NATIVES.exactFeatureNames()) {
            if (!Files.isRegularFile(dir.resolve(name))) {
                missing.add(name);
            }
        }
        List<String> names;
        try (Stream<Path> files = Files.list(dir)) {
            names = files.map(path -> path.getFileName().toString()).toList();
        } catch (IOException e) {
            return PLATFORM_NATIVES.featureDescriptions();
        }
        for (String prefix : PLATFORM_NATIVES.featureNamePrefixes()) {
            if (names.stream().noneMatch(name -> name.startsWith(prefix))) {
                missing.add(prefix + "*");
            }
        }
        return missing;
    }

    private static boolean sameBytes(Path path, byte[] bytes) throws IOException {
        try {
            return Files.size(path) == bytes.length && Arrays.equals(Files.readAllBytes(path), bytes);
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    // Native wchar_t width differs by platform: 2 bytes (UTF-16) on Windows, 4 bytes (UTF-32)
    // on Linux. Encode paths to the platform width expected by the NGX C ABI.
    private static final boolean WCHAR_IS_UTF16 =
            System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final Charset WCHAR_CHARSET =
            WCHAR_IS_UTF16 ? StandardCharsets.UTF_16LE : Charset.forName("UTF-32LE");
    private static final int WCHAR_SIZE = WCHAR_IS_UTF16 ? 2 : 4;

    private static MemorySegment wideString(Arena arena, String s) {
        byte[] data = s.getBytes(WCHAR_CHARSET);
        MemorySegment seg = arena.allocate((long) data.length + WCHAR_SIZE);
        MemorySegment.copy(data, 0, seg, ValueLayout.JAVA_BYTE, 0, data.length);
        for (int i = 0; i < WCHAR_SIZE; i++) {
            seg.set(ValueLayout.JAVA_BYTE, data.length + i, (byte) 0);
        }
        return seg;
    }

    private record PlatformNatives(String platformDir, String shimName, List<String> exactFeatureNames,
                                   List<String> featureNamePrefixes, boolean supported) {
        private static PlatformNatives current() {
            String os = System.getProperty("os.name", "").toLowerCase();
            String arch = System.getProperty("os.arch", "").toLowerCase();
            boolean x64 = arch.equals("x86_64") || arch.equals("amd64");
            if (os.contains("win") && x64) {
                return new PlatformNatives("windows-x64", "ngxshim.dll",
                        List.of("nvngx_dlssd.dll", "nvngx_dlssg.dll"), List.of(), true);
            }
            if (os.contains("linux") && x64) {
                return new PlatformNatives("linux-x64", "libngxshim.so", List.of(),
                        List.of("libnvidia-ngx-dlssd.so", "libnvidia-ngx-dlssg.so"), true);
            }
            return new PlatformNatives(os + "/" + arch, System.mapLibraryName("ngxshim"), List.of(), List.of(), false);
        }

        private String resourceDir() {
            return "/caustica/natives/" + platformDir + "/";
        }

        private boolean isFeatureLibrary(String name) {
            return exactFeatureNames.contains(name)
                    || featureNamePrefixes.stream().anyMatch(name::startsWith);
        }

        private List<String> featureDescriptions() {
            List<String> descriptions = new ArrayList<>(exactFeatureNames);
            featureNamePrefixes.stream()
                    .map(prefix -> prefix + "*")
                    .forEach(descriptions::add);
            return descriptions;
        }
    }
}
