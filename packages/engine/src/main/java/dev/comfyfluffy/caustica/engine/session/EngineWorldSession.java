package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.pass.PassSchedulerBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramBackend;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneBackend;

import java.util.Objects;
import java.util.function.Consumer;

/** Generic render-session and root-scene epoch, closed before its injected device is destroyed. */
public final class EngineWorldSession implements AutoCloseable {
    /** Additional session owner which must cross the retained-scene close boundary with this engine. */
    public interface CloseParticipant {
        void invalidate();
        void drain();
    }

    private static final CloseParticipant NO_CLOSE_PARTICIPANT = new CloseParticipant() {
        @Override public void invalidate() { }
        @Override public void drain() { }
    };
    private final EngineSessionServices services;
    private final SceneId rootScene;
    private final EngineRenderSession renderSession;
    private boolean closed;

    public EngineWorldSession(
            RenderSessionHost renderHost,
            GpuDevice gpu,
            GpuComputeQueue compute,
            ProgramBackend programs,
            RetainedSceneBackend scenes,
            dev.comfyfluffy.caustica.engine.scene.MeshPreparationBackend meshes,
            PassSchedulerBackend passes,
            Consumer<? super Throwable> failures) {
        Objects.requireNonNull(renderHost, "renderHost");
        Objects.requireNonNull(failures, "failures");
        services = new EngineSessionServices(gpu, compute, programs, scenes, meshes, passes,
                failures::accept, failures::accept, (pass, failure) -> failures.accept(failure));
        rootScene = services.scenes().createScene();
        EngineRenderSession openedRender = null;
        try {
            openedRender = renderHost.openSession(services, failure -> failures.accept(failure.cause()));
            openedRender.processPendingChanges();
        } catch (Throwable failure) {
            throw propagate(closeResources(failure, NO_CLOSE_PARTICIPANT, openedRender),
                    "engine world session creation failed");
        }
        renderSession = openedRender;
    }

    public EngineSessionServices services() { return services; }
    public SceneId rootScene() { return rootScene; }

    /** Creates one environment-selection scope borrowing this session's root scene. */
    public EnvironmentSelectionScope openEnvironment() {
        requireOpen();
        return services.scenes().openEnvironment(rootScene);
    }

    /** Applies render registrations, then host-owned world changes, before backend completions. */
    public void progressWorld(Runnable hostWorldProgress) {
        requireOpen();
        renderSession.processPendingChanges();
        Objects.requireNonNull(hostWorldProgress, "hostWorldProgress").run();
        services.progress();
    }

    public void progress() {
        progressWorld(() -> { });
    }

    @Override
    public void close() {
        close(NO_CLOSE_PARTICIPANT);
    }

    /** Invalidates every owner, settles native work once, then drains and destroys the session. */
    public void close(CloseParticipant participant) {
        if (closed) return;
        Objects.requireNonNull(participant, "participant");
        closed = true;
        Throwable failure = closeResources(null, participant, renderSession);
        if (failure != null) throw propagate(failure, "engine world-session close failed");
    }

    private Throwable closeResources(Throwable failure, CloseParticipant participant,
                                     EngineRenderSession render) {
        failure = runClosing(failure, participant::invalidate);
        if (render != null) failure = runClosing(failure, render::beginClose);
        failure = runClosing(failure, services.scenes()::prepareForSessionClose);
        failure = runClosing(failure, participant::drain);
        if (render != null) failure = runClosing(failure, render::finishClose);
        failure = runClosing(failure, () -> services.scenes().dropScene(rootScene));
        failure = runClosing(failure, services::progress);
        failure = runClosing(failure, services::close);
        return failure;
    }

    static Throwable runClosing(Throwable primary, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (primary == null) return failure;
            if (primary != failure) primary.addSuppressed(failure);
        }
        return primary;
    }

    static RuntimeException propagate(Throwable failure, String message) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException(message, failure);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("engine world session is closed");
    }
}
