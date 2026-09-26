package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.geometry.MeshPreparer;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import dev.comfyfluffy.caustica.api.scene.SceneChannel;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.engine.pass.PassContributionChannel;
import dev.comfyfluffy.caustica.engine.pass.PassFailureHandler;
import dev.comfyfluffy.caustica.engine.pass.PassSchedulerBackend;
import dev.comfyfluffy.caustica.engine.pass.PassSession;
import dev.comfyfluffy.caustica.engine.program.ProgramBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramContributionChannel;
import dev.comfyfluffy.caustica.engine.program.ProgramEngineFailureHandler;
import dev.comfyfluffy.caustica.engine.program.ProgramSession;
import dev.comfyfluffy.caustica.engine.resource.ResourceDirectory;
import dev.comfyfluffy.caustica.engine.compute.ComputeQueueSession;
import dev.comfyfluffy.caustica.engine.compute.ComputeQueueSession.ComputeContributionQueue;
import dev.comfyfluffy.caustica.engine.scene.SceneContributionChannel;
import dev.comfyfluffy.caustica.engine.scene.MeshPreparationBackend;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneBackend;
import dev.comfyfluffy.caustica.engine.scene.SceneDirectory;
import dev.comfyfluffy.caustica.engine.scene.SceneRetirementFailureHandler;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Shared renderer services and owner-scope factory for one concrete render session. */
public final class EngineSessionServices implements ContributionScopeFactory, AutoCloseable {
    private final GpuDevice gpu;
    private final ComputeQueueSession compute;
    private final ProgramSession programs;
    private final SceneDirectory scenes;
    private final PassSession passes;
    private final ResourceDirectory resources;
    private final Set<Scope> scopes = new LinkedHashSet<>();
    private boolean accepting = true;

    public EngineSessionServices(
            GpuDevice gpu,
            GpuComputeQueue computeBackend,
            ProgramBackend programBackend,
            RetainedSceneBackend sceneBackend,
            MeshPreparationBackend meshBackend,
            PassSchedulerBackend passBackend,
            ProgramEngineFailureHandler programFailures,
            SceneRetirementFailureHandler sceneFailures,
            PassFailureHandler passFailures) {
        this.gpu = Objects.requireNonNull(gpu, "gpu");
        compute = new ComputeQueueSession(computeBackend, sceneFailures::report);
        resources = new ResourceDirectory(sceneFailures::report);
        programs = new ProgramSession(resources, programBackend, programFailures);
        scenes = new SceneDirectory(programs, resources, sceneBackend, meshBackend);
        passes = new PassSession(passBackend, passFailures);
    }

    @Override
    public synchronized ContributionScope create(ContributionOwner owner) {
        Objects.requireNonNull(owner, "owner");
        if (!accepting) throw new IllegalStateException("session services are closed");
        Scope scope = new Scope(owner);
        scopes.add(scope);
        return scope;
    }

    /** Host program resolution and publication state. */
    public ProgramSession programs() { return programs; }

    /** Host scene creation, removal, snapshots, and retained publication state. */
    public SceneDirectory scenes() { return scenes; }

    /** Host pass dispatch and scheduler progress. */
    public PassSession passes() { return passes; }

    /** Host resource validation and renderer lease acquisition. */
    public ResourceDirectory resources() { return resources; }

    /** Advances compute terminals, compiler publication, and eligible pass closes. */
    public void progress() {
        compute.progress();
        programs.progress();
        passes.progress();
    }

    /**
     * Stops new owner scopes after the render-session orchestrator has closed its contributions. Every
     * service closes even after an earlier one fails; the first failure is rethrown with the rest suppressed.
     */
    @Override
    public synchronized void close() {
        if (!accepting) return;
        if (!scopes.isEmpty()) {
            throw new IllegalStateException("contribution scopes remain open");
        }
        accepting = false;
        Throwable failure = EngineWorldSession.runClosing(null, passes::close);
        failure = EngineWorldSession.runClosing(failure, this::progress);
        failure = EngineWorldSession.runClosing(failure, resources::close);
        failure = EngineWorldSession.runClosing(failure, compute::close);
        if (failure != null) throw EngineWorldSession.propagate(failure, "session services close failed");
    }

    private final class Scope implements ContributionScope {
        private final ContributionOwner owner;
        private final ProgramContributionChannel program;
        private final ComputeContributionQueue compute;
        private final PassContributionChannel pass;
        private final SceneContributionChannel scene;
        private final ResourceFactory resources;
        private boolean quiesced;
        private boolean invalidated;
        private boolean drained;
        private boolean closed;

        private Scope(ContributionOwner owner) {
            this.owner = owner;
            compute = EngineSessionServices.this.compute.openChannel();
            program = programs.openChannel();
            pass = passes.openChannel();
            scene = scenes.openChannel();
            resources = EngineSessionServices.this.resources.openFactory(owner);
        }

        @Override public GpuDevice gpu() { return gpu; }
        @Override public GpuComputeQueue compute() { return compute; }
        @Override public ProgramChannel program() { return program; }
        @Override public PassChannel passes() { return pass; }
        @Override public MeshPreparer meshes() { return scene; }
        @Override public SceneChannel scene() { return scene; }
        @Override public ResourceFactory resources() { return resources; }

        @Override
        public void quiesce() {
            if (quiesced) return;
            compute.quiesce();
            pass.quiesce();
            program.quiesce();
            scene.quiesce();
            EngineSessionServices.this.resources.quiesce(owner);
            quiesced = true;
        }

        @Override
        public void invalidate() {
            if (invalidated) return;
            quiesce();
            compute.invalidate();
            pass.invalidate();
            scene.invalidate();
            program.invalidate();
            EngineSessionServices.this.resources.invalidate(owner);
            invalidated = true;
        }

        @Override
        public void drain() {
            if (drained) return;
            invalidate();
            compute.drain();
            pass.drain();
            scene.drain();
            program.drain();
            EngineSessionServices.this.resources.drain(scenes::settleFrameUses);
            drained = true;
        }

        @Override
        public void close() {
            if (closed) return;
            if (!drained) throw new IllegalStateException("scope must drain before close");
            compute.closeChannel();
            synchronized (EngineSessionServices.this) {
                scopes.remove(this);
            }
            closed = true;
        }
    }
}
