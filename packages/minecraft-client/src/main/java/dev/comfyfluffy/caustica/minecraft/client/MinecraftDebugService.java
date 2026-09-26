package dev.comfyfluffy.caustica.minecraft.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.comfyfluffy.caustica.engine.vulkan.VulkanDiagnostics;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaConfig;
import dev.comfyfluffy.caustica.minecraft.client.screen.CausticaPageScreen;
import dev.comfyfluffy.caustica.minecraft.client.settings.CausticaPages;
import dev.comfyfluffy.caustica.renderer.runtime.RendererOptions;
import dev.comfyfluffy.caustica.renderer.presentation.BorrowedImage;
import dev.comfyfluffy.caustica.renderer.runtime.RtFrameCapture;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.ResourceId;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Opt-in loopback control. Game state is read and changed only on the client or server thread. */
public final class MinecraftDebugService implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private static MinecraftDebugService instance;
    private static final List<String> VIEWS = List.of("off", "normals", "albedo", "depth", "roughness",
            "motion", "specular", "specular-motion", "exposure", "metering-weight", "stable-validity",
            "stable-type", "primary-depth", "primary-motion", "trace-radiance", "stable-metadata");
    private final Minecraft client;
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService httpExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final String token = UUID.randomUUID().toString();
    private final Path directory;
    private final Map<String, CompletableFuture<Object>> jobs = new ConcurrentHashMap<>();
    private final List<FrameWait> waits = new ArrayList<>();
    private final Map<CapturePhase, List<Runnable>> captures = new EnumMap<>(CapturePhase.class);
    private long frames;
    private long ticks;
    private Recording recording;
    private CompletableFuture<Object> passCapture;
    private List<Object> passTrace;
    private String passPhase;
    private double turnDegreesPerSecond;
    private long previousTurnNanos;

    private record FrameWait(long frames, long ticks, CompletableFuture<Object> future) { }

    /** Render-thread boundaries shared by queued captures and host pass tracing. */
    public enum CapturePhase {
        AFTER_WORLD("after-world"), AFTER_HAND("after-hand"), BEFORE_UI("before-ui"), FRAME_END("frame-end");

        private final String id;

        CapturePhase(String id) { this.id = id; }

        private static CapturePhase parse(String id) {
            for (var phase : values()) if (phase.id.equals(id)) return phase;
            throw new IllegalArgumentException("Unknown capture phase: " + id);
        }
    }

    public static void start(Minecraft client) {
        if (!Boolean.getBoolean("caustica.debug.enabled")) return;
        try {
            instance = new MinecraftDebugService(client);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start Caustica debug service", e);
        }
    }

    private MinecraftDebugService(Minecraft client) throws IOException {
        this.client = client;
        directory = client.gameDirectory.toPath().resolve("caustica-debug").toAbsolutePath();
        Files.createDirectories(directory);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", this::handle);
        server.setExecutor(httpExecutor);
        server.start();
        int port = server.getAddress().getPort();
        Files.writeString(directory.resolve("session.json"), JSON.toJson(Map.of("protocolVersion", 1,
                "pid", ProcessHandle.current().pid(), "port", port, "token", token,
                "baseUrl", "http://127.0.0.1:" + port)));
        CausticaMod.LOGGER.info("Caustica debug service listening on 127.0.0.1:{}", port);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals("/api") || !exchange.getRequestMethod().equals("POST")) {
                reply(exchange, 405, Map.of("ok", false, "error", "Use POST /api"));
                return;
            }
            if (! ("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                reply(exchange, 401, Map.of("ok", false, "error", "Invalid token"));
                return;
            }
            try {
                JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readNBytes(65537),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                String op = request.get("op").getAsString();
                if (op.equals("job")) {
                    String id = request.get("jobId").getAsString();
                    var future = jobs.get(id);
                    if (future == null) throw new IllegalArgumentException("Unknown job " + id);
                    Map<String, Object> result = new LinkedHashMap<>();
                    if (!future.isDone()) result.put("state", "pending");
                    else {
                        try {
                            result.put("result", future.join());
                            result.put("state", "completed");
                        } catch (Exception e) {
                            result.put("state", "failed");
                            result.put("error", e.getCause() == null ? e.toString() : e.getCause().toString());
                        }
                    }
                    reply(exchange, 200, Map.of("ok", true, "result", result));
                    return;
                }
                long timeout = request.has("timeoutMs") ? request.get("timeoutMs").getAsLong() : 30000;
                if (timeout < 1 || timeout > 600000) throw new IllegalArgumentException("timeoutMs must be 1..600000");
                var future = new CompletableFuture<Object>().orTimeout(timeout, TimeUnit.MILLISECONDS);
                String id = UUID.randomUUID().toString();
                jobs.put(id, future);
                future.whenComplete((result, error) -> CompletableFuture.delayedExecutor(10, TimeUnit.MINUTES)
                        .execute(() -> jobs.remove(id, future)));
                client.execute(() -> {
                    try (var hostWork = MinecraftHostTelemetry.work("debug.task")) {
                        if (future.isDone()) return;
                        try { execute(op, request, future); }
                        catch (Exception e) { future.completeExceptionally(e); }
                    }
                });
                reply(exchange, 202, Map.of("ok", true, "jobId", id));
            } catch (Exception e) {
                reply(exchange, 400, Map.of("ok", false, "error", e.toString()));
            }
        }
    }

    private static void reply(HttpExchange exchange, int status, Object response) throws IOException {
        byte[] bytes = JSON.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void execute(String op, JsonObject request, CompletableFuture<Object> future) throws Exception {
        switch (op) {
            case "schema" -> future.complete(Map.of("views", VIEWS,
                    "images", debugImageNames(),
                    "operations", List.of("schema", "status", "terrain.status", "memory.capture", "passes.capture",
                    "settings.get", "settings.set", "runtime.set", "resources.reload", "world.leave", "view.set", "screen.close", "screen.open", "window.resize", "window.fullscreen", "window.maximize", "window.restore", "input.set", "wait", "command", "screenshot", "image.capture", "jfr.start", "jfr.dump", "jfr.stop", "nsight.status", "nsight.start", "client.stop", "job")));
            case "nsight.status", "nsight.start" -> future.complete(NsightDebug.execute(op.equals("nsight.start"),
                    CausticaClientComposition.current().runtime().telemetry().frameSerial()));
            case "passes.capture" -> {
                if (passCapture != null && !passCapture.isDone())
                    throw new IllegalStateException("A pass capture is already pending");
                passCapture = future;
            }
            case "status" -> future.complete(status());
            case "terrain.status" -> CausticaClientComposition.current().terrain().readiness()
                    .whenComplete((value, failure) -> {
                        if (failure != null) future.completeExceptionally(failure);
                        else future.complete(value);
                    });
            case "memory.capture" -> {
                Path output = directory.resolve("memory-" + UUID.randomUUID() + ".json");
                Files.writeString(output, VulkanDiagnostics.memoryStatistics());
                future.complete(Map.of("path", output.toAbsolutePath().toString()));
            }
            case "window.resize" -> {
                int width = request.get("width").getAsInt();
                int height = request.get("height").getAsInt();
                if (width < 1 || height < 1) throw new IllegalArgumentException("Window dimensions must be positive");
                client.getWindow().setWindowed(width, height);
                future.complete(Map.of("requestedWidth", width, "requestedHeight", height));
            }
            case "window.fullscreen" -> {
                boolean enabled = request.get("enabled").getAsBoolean();
                var window = client.getWindow();
                if (window.isFullscreen() != enabled) window.toggleFullScreen();
                future.complete(Map.of("requestedFullscreen", enabled));
            }
            case "window.maximize", "window.restore" -> {
                var window = client.getWindow();
                if (window.isFullscreen()) throw new IllegalStateException("Window must be windowed");
                if (op.equals("window.maximize")) GLFW.glfwMaximizeWindow(window.handle());
                else GLFW.glfwRestoreWindow(window.handle());
                future.complete(Map.of("requested", op));
            }
            case "settings.get" -> future.complete(settings(settingsFeature(request)));
            case "runtime.set" -> {
                var enabled = request.get("enabled");
                if (enabled == null || !enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean())
                    throw new IllegalArgumentException("enabled must be a boolean");
                var option = MinecraftOptions.Rt.ENABLED;
                if (CausticaConfig.store().overridden(CausticaConfig.FEATURE, option))
                    throw new IllegalArgumentException("Setting has a JVM override: " + option.id());
                CausticaConfig.store().apply(CausticaConfig.FEATURE, option, enabled.getAsBoolean());
                future.complete(runtimeStatus());
            }
            case "settings.set" -> {
                ResourceId feature = settingsFeature(request);
                applySettings(feature, request.getAsJsonObject("values"));
                future.complete(settings(feature));
            }
            case "view.set" -> {
                int view = VIEWS.indexOf(request.get("name").getAsString());
                if (view < 0) throw new IllegalArgumentException("Unknown view; see schema");
                var option = RendererOptions.Rt.Composite.DEBUG_VIEW;
                if (CausticaConfig.store().overridden(CausticaConfig.FEATURE, option))
                    throw new IllegalArgumentException("Debug view has a JVM override");
                CausticaConfig.store().apply(CausticaConfig.FEATURE, option, view);
                future.complete(Map.of("name", VIEWS.get(view), "value", view));
            }
            case "wait" -> {
                long frameCount = request.has("frames") ? request.get("frames").getAsLong() : 0;
                long tickCount = request.has("ticks") ? request.get("ticks").getAsLong() : 0;
                if (frameCount < 0 || tickCount < 0 || frameCount + tickCount < 1)
                    throw new IllegalArgumentException("Specify positive frames or ticks");
                requireWorld();
                waits.add(new FrameWait(Math.addExact(frames, frameCount), Math.addExact(ticks, tickCount), future));
            }
            case "world.leave" -> {
                requireWorld();
                int pending = CausticaClientComposition.current().terrain().outstandingBuilds();
                if (request.has("onlyIfTerrainBusy") && request.get("onlyIfTerrainBusy").getAsBoolean()
                        && pending == 0) {
                    future.complete(Map.of("left", false, "terrainBuildsBeforeDisconnect", 0));
                    break;
                }
                client.disconnectFromWorld(net.minecraft.network.chat.Component.translatable("menu.disconnect"));
                future.complete(Map.of("left", true, "terrainBuildsBeforeDisconnect", pending));
            }
            case "screen.close" -> {
                var screen = client.gui.screen();
                // Screens can send protocol actions on close, including respawn after the End credits.
                if (screen != null) screen.onClose();
                future.complete(Map.of("closed", screen != null));
            }
            case "screen.open" -> {
                String name = request.get("screen").getAsString();
                var video = new VideoSettingsScreen(new OptionsScreen(null, client.options, client.level != null),
                        client, client.options);
                if (name.equals("video")) {
                    client.gui.setScreen(video);
                } else {
                    var composition = CausticaClientComposition.current();
                    var services = composition.apiServices();
                    var link = CausticaPages.links(services.settings(), services.options(),
                                    composition.runtime()::settingAvailable).stream()
                            .filter(candidate -> candidate.id().equals(name)).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Unknown options page " + name));
                    client.gui.setScreen(new CausticaPageScreen(video, link.page(), services.options()));
                }
                future.complete(Map.of("screen", client.gui.screen().getClass().getName()));
            }
            case "input.set" -> {
                requireWorld();
                double turnSpeed = request.has("turnDegreesPerSecond")
                        ? request.get("turnDegreesPerSecond").getAsDouble() : 0.0;
                if (!Double.isFinite(turnSpeed) || Math.abs(turnSpeed) > 360.0)
                    throw new IllegalArgumentException("turnDegreesPerSecond must be finite and between -360 and 360");
                var abilities = client.player.getAbilities();
                float previousFlyingSpeed = abilities.getFlyingSpeed();
                if (request.has("flyingSpeed")) {
                    float flyingSpeed = request.get("flyingSpeed").getAsFloat();
                    if (!client.player.isSpectator())
                        throw new IllegalArgumentException("flyingSpeed requires spectator mode");
                    if (!Float.isFinite(flyingSpeed) || flyingSpeed < 0.0f || flyingSpeed > 0.2f)
                        throw new IllegalArgumentException("flyingSpeed must be finite and between 0 and 0.2");
                    abilities.setFlyingSpeed(flyingSpeed);
                }
                client.setScreenAndShow(null);
                turnDegreesPerSecond = turnSpeed;
                previousTurnNanos = System.nanoTime();
                client.options.keyUp.setDown(request.has("forward") && request.get("forward").getAsBoolean());
                client.options.keySprint.setDown(request.has("sprint") && request.get("sprint").getAsBoolean());
                future.complete(Map.of("forward", client.options.keyUp.isDown(),
                        "sprint", client.options.keySprint.isDown(),
                        "previousFlyingSpeed", previousFlyingSpeed,
                        "currentFlyingSpeed", abilities.getFlyingSpeed(),
                        "turnDegreesPerSecond", turnDegreesPerSecond));
            }
            case "command" -> command(request, future);
            case "resources.reload" -> client.reloadResourcePacks().whenComplete((result, error) -> {
                if (error != null) future.completeExceptionally(error);
                else future.complete(Map.of("reloaded", true));
            });
            case "screenshot", "image.capture" -> {
                if (op.equals("image.capture")) requireWorld();
                String phase = request.has("phase") ? request.get("phase").getAsString() : "frame-end";
                var queue = captures.computeIfAbsent(CapturePhase.parse(phase), key -> new ArrayList<>());
                queue.add(() -> {
                    if (future.isDone()) return;
                    try {
                        if (op.equals("screenshot")) screenshot(request, future);
                        else future.complete(captureImages(request));
                    } catch (Exception e) { future.completeExceptionally(e); }
                });
            }
            case "jfr.start" -> {
                if (recording != null) throw new IllegalStateException("Debug recording already running");
                recording = startRecording(request);
                future.complete(Map.of("recordingId", recording.getId()));
            }
            case "jfr.dump", "jfr.stop" -> {
                if (recording == null) throw new IllegalStateException("No debug recording running");
                Path output = directory.resolve("recording-" + UUID.randomUUID() + ".jfr");
                if (op.equals("jfr.stop")) {
                    stopRecording(recording, output);
                    recording = null;
                } else recording.dump(output);
                future.complete(Map.of("path", output.toString()));
            }
            case "client.stop" -> {
                future.complete(Map.of("stopping", true));
                CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(() -> client.execute(client::stop));
            }
            default -> throw new IllegalArgumentException("Unknown operation " + op);
        }
    }

    static Recording startRecording(JsonObject request) throws IOException, java.text.ParseException {
        List<String> events = new ArrayList<>();
        if (request.has("events")) {
            for (var name : request.getAsJsonArray("events")) events.add(name.getAsString());
        } else {
            events.addAll(List.of("HostLoop", "HostWork", "HostCallbackTotals", "HostSubmission", "Frame", "CpuStage", "FramePreparation", "TraceRanges", "FrameCounter", "GeometryVisibility",
                    "EntityMeshFrame", "EntityMeshPublication", "EntityMeshUpload", "Exposure",
                    "GpuStage", "GpuWait", "NeeFrame", "TerrainState", "TerrainJob", "TerrainPublication",
                    "TerrainDispatchPlan", "ComputeBatch"));
        }
        var recording = new Recording(Configuration.getConfiguration("profile"));
        try {
            recording.setName("Caustica debug");
            for (String name : events)
                recording.enable("dev.comfyfluffy.caustica." + name).withThreshold(java.time.Duration.ZERO);
            recording.start();
            return recording;
        } catch (RuntimeException | Error failure) {
            dev.comfyfluffy.caustica.vulkan.ResourceLifetime.closeAfterFailure(failure, recording::close);
            throw failure;
        }
    }

    static void stopRecording(Recording recording, Path output) throws IOException {
        // A failed dump keeps the stopped recording available for another save attempt.
        if (recording.getState() == RecordingState.RUNNING) recording.stop();
        recording.dump(output);
        recording.close();
    }

    /** Validate every requested value before applying any setting in the batch. */
    private static ResourceId settingsFeature(JsonObject request) {
        return request.has("feature") ? ResourceId.parse(request.get("feature").getAsString())
                : CausticaConfig.FEATURE;
    }

    private void applySettings(ResourceId feature, JsonObject values) {
        var options = CausticaConfig.store().declaredOptions(feature);
        Map<Option<?>, Object> normalized = new LinkedHashMap<>();
        for (var entry : values.entrySet()) {
            var option = options.stream().filter(o -> o.id().equals(entry.getKey())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown setting " + entry.getKey()));
            if (CausticaConfig.store().overridden(feature, option))
                throw new IllegalArgumentException("Setting has a JVM override: " + option.id());
            normalized.put(option, option.normalize(JSON.fromJson(entry.getValue(), Object.class)));
        }
        normalized.forEach((option, value) -> CausticaConfig.store().apply(feature, option, value));
    }

    private void requireWorld() {
        if (client.level == null || client.player == null) throw new IllegalStateException("No world/player loaded");
    }

    private void command(JsonObject request, CompletableFuture<Object> future) {
        requireWorld();
        String command = request.get("command").getAsString();
        if (command.startsWith("/")) command = command.substring(1);
        String text = command;
        String target = request.has("target") ? request.get("target").getAsString() : "server";
        if (target.equals("client")) {
            client.player.connection.sendCommand(text);
            future.complete(Map.of("sent", true));
        } else if (target.equals("server")) {
            var server = client.getSingleplayerServer();
            if (server == null) throw new IllegalStateException("No integrated server; use target client to send normally");
            UUID playerId = client.player.getUUID();
            server.execute(() -> {
                if (future.isDone()) return;
                try {
                    var player = server.getPlayerList().getPlayer(playerId);
                    if (player == null) throw new IllegalStateException("Player disconnected");
                    var source = player.createCommandSourceStack();
                    int result = server.getCommands().getDispatcher().execute(text, source);
                    future.complete(Map.of("result", result));
                } catch (Exception e) { future.completeExceptionally(e); }
            });
        } else throw new IllegalArgumentException("target must be client or server");
    }

    private Map<String, Object> settings() {
        return settings(CausticaConfig.FEATURE);
    }

    private Map<String, Object> settings(ResourceId feature) {
        Map<String, Object> result = new LinkedHashMap<>();
        var values = CausticaConfig.store().snapshot().options(feature);
        for (Option<?> option : CausticaConfig.store().declaredOptions(feature)) result.put(option.id(), Map.of(
                "value", option.encode(values.get(option)).orElse(""),
                "overridden", CausticaConfig.store().overridden(feature, option)));
        return result;
    }

    private Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ready", client.level != null && client.player != null);
        result.put("paused", client.isPaused());
        var screen = client.gui.screen();
        result.put("screen", screen == null ? "" : screen.getClass().getName());
        var window = client.getWindow();
        result.put("window", Map.of("width", window.getWidth(), "height", window.getHeight(),
                "screenWidth", window.getScreenWidth(), "screenHeight", window.getScreenHeight(),
                "fullscreen", window.isFullscreen(),
                "maximized", GLFW.glfwGetWindowAttrib(window.handle(), GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE));
        result.put("frames", frames);
        result.put("ticks", ticks);
        result.put("frameActive", CausticaClientComposition.current().runtime().frameActive());
        result.put("runtime", runtimeStatus());
        result.put("terrainOutstandingBuilds", CausticaClientComposition.current().terrain().outstandingBuilds());
        if (client.level != null) {
            var world = new LinkedHashMap<String, Object>();
            world.put("dimension", client.level.dimension().toString());
            world.put("gameTime", client.level.getGameTime());
            var server = client.getSingleplayerServer();
            if (server != null) world.put("directory", server.getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT).normalize().getFileName().toString());
            result.put("world", world);
        }
        if (client.player != null) result.put("player", Map.of("x", client.player.getX(), "y", client.player.getY(),
                "z", client.player.getZ(), "yaw", client.player.getYRot(), "pitch", client.player.getXRot(),
                "currentFlyingSpeed", client.player.getAbilities().getFlyingSpeed(),
                "flying", client.player.getAbilities().flying, "sprinting", client.player.isSprinting()));
        if (client.level != null) {
            var camera = client.gameRenderer.mainCamera();
            var position = camera.position();
            result.put("camera", Map.of("x", position.x, "y", position.y, "z", position.z, "yaw", camera.yaw()));
        }
        result.put("settings", settings());
        result.put("latestFrame", CausticaClientComposition.current().runtime().telemetry().latestFrame());
        return result;
    }

    private Map<String, Object> runtimeStatus() {
        var runtime = CausticaClientComposition.current().runtime();
        var option = MinecraftOptions.Rt.ENABLED;
        return Map.of("requested", CausticaConfig.get(option), "active", runtime.active(),
                "frameActive", runtime.frameActive(),
                "overridden", CausticaConfig.store().overridden(CausticaConfig.FEATURE, option));
    }

    private Object captureImages(JsonObject request) throws IOException {
        // Raw readback submits directly; publish this frame's deferred host commands before it waits.
        com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().submit();
        if (!request.has("names")) return captureImage(request.get("name").getAsString());
        List<Object> images = new ArrayList<>();
        for (var name : request.getAsJsonArray("names")) images.add(captureImage(name.getAsString()));
        return Map.of("images", images);
    }

    private static List<String> debugImageNames() {
        var names = new ArrayList<>(dev.comfyfluffy.caustica.renderer.runtime.RtFrameRenderer.debugImageNames());
        names.addAll(List.of("main-color", "ui-color"));
        return names;
    }

    private Object captureImage(String name) throws IOException {
        Path output = directory.resolve("image-" + UUID.randomUUID() + ".exr");
        if (name.equals("main-color") || name.equals("ui-color")) {
            var composition = CausticaClientComposition.current();
            var target = name.equals("main-color") ? client.gameRenderer.mainRenderTarget()
                    : composition.uiOverlay().captureTarget();
            var view = (com.mojang.blaze3d.vulkan.VulkanGpuTextureView) target.getColorTextureView();
            var gpu = composition.runtime().vulkanContextOrNull();
            long frame = composition.runtime().telemetry().frameSerial();
            String encoding = "RGBA: normalized UNORM8 host target, before file-format conversion";
            // The render-thread target remains owned for this synchronous readback; no sampled descriptor is needed.
            var image = new BorrowedImage(view.texture().vkImage(), view.vkImageView(),
                    org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM, target.width, target.height);
            RtFrameCapture.exportRaw(gpu, image, output,
                    Map.of("causticaBuffer", name, "causticaFrame", Long.toString(frame),
                            "causticaEncoding", encoding));
            return Map.of("path", output.toString(), "metadata", Map.of("name", name,
                    "frameSerial", frame, "width", target.width, "height", target.height,
                    "vulkanFormat", org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM, "encoding", encoding));
        }
        var metadata = CausticaClientComposition.current().runtime().exportLatestDebugImage(name, output);
        if (metadata == null) throw new IllegalStateException("No completed renderer image available");
        return Map.of("path", output.toString(), "metadata", metadata);
    }

    private void screenshot(JsonObject request, CompletableFuture<Object> future) throws IOException {
        String targetName = request.has("target") ? request.get("target").getAsString() : "main";
        var target = switch (targetName) {
            case "main" -> client.gameRenderer.mainRenderTarget();
            case "ui" -> CausticaClientComposition.current().uiOverlay().captureTarget();
            default -> throw new IllegalArgumentException("Screenshot target must be main or ui");
        };
        String name = "caustica-debug-" + UUID.randomUUID() + ".png";
        Path output = client.gameDirectory.toPath().resolve("screenshots").resolve(name).toAbsolutePath();
        long frameId = CausticaClientComposition.current().runtime().telemetry().frameSerial();
        Screenshot.grab(client.gameDirectory, name, target, 1, message -> {
            if (Files.isRegularFile(output)) future.complete(Map.of("path", output.toString(), "frameId", frameId,
                    "target", targetName));
            else future.completeExceptionally(new IOException(message.getString()));
        });
    }

    public static void frameRendered(boolean composited) {
        if (instance == null) return;
        if (instance.passTrace != null) {
            instance.passCapture.complete(Map.of("passes", instance.passTrace));
            instance.passTrace = null;
        }
        if (composited && instance.client.level != null) instance.frames++;
        captureBoundary(CapturePhase.FRAME_END);
        instance.advanceWaits();
    }

    /** Detaches this boundary's work before running it, so newly queued captures wait for its next visit. */
    public static void captureBoundary(CapturePhase phase) {
        try (var hostWork = MinecraftHostTelemetry.work("debug.captureBoundary")) {
            if (instance == null) return;
            instance.passPhase = phase.id;
            var pending = instance.captures.remove(phase);
            if (pending != null) pending.forEach(Runnable::run);
        }
    }

    public static void beginFrame() {
        if (instance == null) return;
        instance.passPhase = "world";
        if (instance.passCapture != null && !instance.passCapture.isDone())
            instance.passTrace = new ArrayList<>();
    }

    /** Records host attachment identities only during an explicitly requested frame trace. */
    public static void renderPass(com.mojang.blaze3d.systems.RenderPassDescriptor descriptor) {
        try (var hostWork = MinecraftHostTelemetry.work("debug.renderPass")) {
            if (instance == null || instance.passTrace == null) return;
            var main = instance.client.gameRenderer.mainRenderTarget().getColorTexture();
            var colors = new ArrayList<Object>();
            for (var attachment : descriptor.colorAttachments()) {
                if (attachment == null) continue;
                var view = (com.mojang.blaze3d.vulkan.VulkanGpuTextureView) attachment.textureView();
                colors.add(Map.of("image", Long.toUnsignedString(view.texture().vkImage()),
                        "view", Long.toUnsignedString(view.vkImageView()), "main", view.texture() == main,
                        "width", view.getWidth(0), "height", view.getHeight(0)));
            }
            instance.passTrace.add(Map.of("phase", instance.passPhase, "label", descriptor.label().get(), "colors", colors));
        }
    }

    /** Apply continuous look input at render cadence through the same player operation as the mouse. */
    public static void advanceCameraInput() {
        if (instance == null || instance.turnDegreesPerSecond == 0.0) return;
        long now = System.nanoTime();
        long elapsed = now - instance.previousTurnNanos;
        instance.previousTurnNanos = now;
        if (instance.client.player == null || instance.client.isPaused()) return;
        // Entity.turn converts mouse deltas to degrees with a factor of 0.15.
        instance.client.player.turn(instance.turnDegreesPerSecond * (elapsed / 1e9) / 0.15, 0.0);
    }

    public static void tick() {
        if (instance == null) return;
        if (instance.client.level != null && !instance.client.isPaused()) instance.ticks++;
        instance.advanceWaits();
    }

    private void advanceWaits() {
        waits.removeIf(wait -> {
            if (wait.future().isDone()) return true;
            if (client.level == null) wait.future().completeExceptionally(new IllegalStateException("World unloaded"));
            else if (frames >= wait.frames() && ticks >= wait.ticks())
                wait.future().complete(Map.of("frames", frames, "ticks", ticks));
            return wait.future().isDone();
        });
    }

    public static void stop() {
        if (instance != null) { instance.close(); instance = null; }
    }

    @Override public void close() {
        server.stop(0);
        httpExecutor.close();
        jobs.values().forEach(job -> job.completeExceptionally(new IllegalStateException("Client shutting down")));
        if (recording != null) { recording.close(); recording = null; }
        try { Files.deleteIfExists(directory.resolve("session.json")); }
        catch (IOException e) { CausticaMod.LOGGER.warn("Cannot remove debug discovery file", e); }
    }
}
