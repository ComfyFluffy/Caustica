package dev.comfyfluffy.caustica.renderer.raytracing;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;

import dev.comfyfluffy.caustica.api.program.ProgramFailure;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.engine.program.ProgramBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings;
import dev.comfyfluffy.caustica.renderer.raytracing.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.renderer.raytracing.pipeline.RtShaderCode;
import dev.comfyfluffy.caustica.renderer.raytracing.pipeline.RtSharcResolve;
import dev.comfyfluffy.caustica.renderer.raytracing.shader.ShaderBinaryCache;
import dev.comfyfluffy.caustica.renderer.raytracing.shader.WorldShaderCompiler;
import dev.comfyfluffy.caustica.slang.SlangRuntime;
import dev.comfyfluffy.caustica.support.SharedResource;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import org.lwjgl.vulkan.VK10;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Compiles engine program compositions and publishes complete descriptor-heap RT programs. */
public final class RtProgramBackend implements ProgramBackend, AutoCloseable {
    public static final int VISIBILITY_RAYGEN_INDEX = 2;
    public static final int VOLUME_LIGHTING_RAYGEN_INDEX = 3;
    public static final int RESOLVE_STABLE_PLANES_RAYGEN_INDEX = 4;
    /** SHaRC fill variants; they share the world pipeline because its hit table holds this pipeline's handles. */
    public static final int SHARC_QUERY_RAYGEN_INDEX = 5;
    public static final int SHARC_UPDATE_RAYGEN_INDEX = 6;
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    /** Concurrent stage compiles; each compiles on its own Slang runtime. */
    private static final int STAGE_COMPILERS = 4;

    private final VulkanDeviceContext context;
    private final SlangRuntime slangRuntime;
    private final Path cacheRoot;
    private final Path sharcHeaders;
    private final ExecutorService compiler = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Caustica program compiler-" + THREAD_ID.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService stageCompilers = Executors.newFixedThreadPool(STAGE_COMPILERS, task -> {
        Thread thread = new Thread(task, "Caustica stage compiler-" + THREAD_ID.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    /** Opened by the first build on the program compiler thread. */
    private ShaderBinaryCache binaries;
    private Candidate active;
    private boolean closed;

    /**
     * Compiles world programs from temporary source trees under {@code cacheRoot} and keeps their SPIR-V
     * across launches in its sibling {@code spirv} directory.
     *
     * @param sharcHeaders verified SHaRC SDK headers, or null. Programs carry the SHaRC stages when the
     *                     headers are given and the device enabled the buffer int64 atomics and 16-bit
     *                     storage those stages declare.
     */
    public RtProgramBackend(VulkanDeviceContext context, SlangRuntime slangRuntime, Path cacheRoot,
                            Path sharcHeaders) {
        this.context = Objects.requireNonNull(context, "context");
        this.slangRuntime = Objects.requireNonNull(slangRuntime, "slangRuntime");
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot").toAbsolutePath().normalize();
        var capabilities = context.backend().capabilities();
        this.sharcHeaders = capabilities.shaderBufferInt64Atomics() && capabilities.storageBuffer16BitAccess()
                ? sharcHeaders : null;
    }

    /** Whether every program of this backend carries the SHaRC fill variants and resolve. */
    public boolean compilesSharc() {
        return sharcHeaders != null;
    }

    @Override
    public void compile(ProgramComposition composition, Consumer<? super Compilation> completion) {
        Objects.requireNonNull(composition, "composition");
        Objects.requireNonNull(completion, "completion");
        synchronized (this) {
            if (closed) throw new IllegalStateException("program backend is closed");
        }
        compiler.execute(() -> {
            Compilation result;
            try {
                result = new Compilation.Succeeded(build(composition));
            } catch (Throwable failure) {
                result = new Compilation.Failed(new ProgramFailure(
                        "World program compilation failed", diagnostics(failure)));
            }
            completion.accept(result);
        });
    }

    @Override
    public void publish(CompiledProgram program, Runnable previousRetired) {
        Candidate next = requireCandidate(program);
        Objects.requireNonNull(previousRetired, "previousRetired");
        Candidate previous;
        synchronized (this) {
            if (closed) throw new IllegalStateException("program backend is closed");
            if (next.state != CandidateState.CANDIDATE) {
                throw new IllegalStateException("program candidate is already published or retiring");
            }
            previous = active;
            active = next;
            next.state = CandidateState.ACTIVE;
            if (previous != null) {
                previous.state = CandidateState.RETIRING;
                previous.retired = previousRetired;
            }
        }
        if (previous == null) {
            previousRetired.run();
        } else {
            previous.close();
        }
    }

    @Override
    public void drainPublishedUses() {
        context.drainAndWaitIdle();
    }

    /** Whether a complete renderer program is available for capture. */
    public synchronized boolean hasActive() {
        return active != null;
    }

    /** Capture the program before recording; publication cannot revoke this frame's claim. */
    public synchronized SharedResource<Published> acquire() {
        return active == null ? null : active.lifetime.retain();
    }

    @Override
    public void close() {
        Candidate previous;
        synchronized (this) {
            if (closed) return;
            closed = true;
            previous = active;
            active = null;
            if (previous != null) previous.state = CandidateState.RETIRING;
        }
        compiler.shutdown();
        awaitTerminationUninterruptibly(compiler);
        // A finished build has awaited all of its stage compiles.
        stageCompilers.shutdown();
        awaitTerminationUninterruptibly(stageCompilers);
        if (previous != null) previous.close();
    }

    static void awaitTerminationUninterruptibly(ExecutorService executor) {
        boolean interrupted = false;
        while (true) {
            try {
                if (executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private Candidate build(ProgramComposition composition) throws IOException {
        WorldShaderCompiler shaderCompiler = null;
        VmaMappedBuffer table = null;
        RtPipeline pipeline = null;
        RtSharcResolve sharcResolve = null;
        try {
            if (binaries == null) {
                binaries = ShaderBinaryCache.open(cacheRoot.resolveSibling("spirv"), slangRuntime.compilerIdentity());
            }
            List<SlangRuntime> runtimes = new ArrayList<>(STAGE_COMPILERS);
            runtimes.add(slangRuntime);
            runtimes.addAll(slangRuntime.siblings(STAGE_COMPILERS - 1));
            WorldShaderCompiler stages = WorldShaderCompiler.createIsolated(runtimes, cacheRoot, composition, binaries,
                    sharcHeaders);
            shaderCompiler = stages;
            List<Long> data = stages.implementationData();
            table = createImplementationTable(data);
            boolean reordered = context.backend().capabilities().shaderExecutionReordering();
            boolean sharc = sharcHeaders != null;
            List<Future<RtShaderCode>> submitted = new ArrayList<>();
            // Longest compiles first, so the slowest stages start immediately.
            Future<RtShaderCode> fill = submitStage(submitted, "fill-stable-planes",
                    () -> stages.compileFillStablePlanes(reordered));
            Future<RtShaderCode> sharcQuery = sharc ? submitStage(submitted, "fill-sharc-query",
                    () -> stages.compileSharcFill(reordered, false)) : null;
            Future<RtShaderCode> sharcUpdate = sharc ? submitStage(submitted, "fill-sharc-update",
                    () -> stages.compileSharcFill(reordered, true)) : null;
            Future<RtShaderCode> build = submitStage(submitted, "build-stable-planes",
                    stages::compileBuildStablePlanes);
            Future<RtShaderCode> volumeLighting = submitStage(submitted, "volume-lighting",
                    stages::compileVolumeLighting);
            Future<RtShaderCode> visibility = submitStage(submitted, "visibility-rays", stages::compileVisibilityRays);
            Future<RtShaderCode> radiance = submitStage(submitted, "radiance-any-hit", stages::compileRadianceAnyHit);
            Future<RtShaderCode> resolve = submitStage(submitted, "resolve-stable-planes",
                    () -> stages.compilePlain("resolve_stable_planes.slang", WorldShaderCompiler.ENTRY_POINT));
            Future<RtShaderCode> environment = submitStage(submitted, "environment", stages::compileEnvironmentMiss);
            Future<RtShaderCode> closest = submitStage(submitted, "closest-hit", stages::compileClosestHit);
            Future<RtShaderCode> sharcResolveCode = sharc
                    ? submitStage(submitted, "sharc-resolve", stages::compileSharcResolve) : null;
            awaitStages(submitted);
            RtShaderCode[] raygens = sharc
                    ? new RtShaderCode[]{build.resultNow(), fill.resultNow(), visibility.resultNow(),
                            volumeLighting.resultNow(), resolve.resultNow(), sharcQuery.resultNow(),
                            sharcUpdate.resultNow()}
                    : new RtShaderCode[]{build.resultNow(), fill.resultNow(), visibility.resultNow(),
                            volumeLighting.resultNow(), resolve.resultNow()};
            if (sharc) sharcResolve = RtSharcResolve.create(context, sharcResolveCode.resultNow().spirv());
            pipeline = RtPipeline.create(context, raygens, new RtShaderCode[]{environment.resultNow()},
                    closest.resultNow(), radiance.resultNow());
            return new Candidate(composition, shaderCompiler, table, pipeline, sharcResolve);
        } catch (IOException | RuntimeException | Error failure) {
            if (pipeline != null) pipeline.destroy();
            if (sharcResolve != null) sharcResolve.destroy();
            if (table != null) table.close();
            if (shaderCompiler != null) shaderCompiler.close();
            throw failure;
        }
    }

    private Future<RtShaderCode> submitStage(List<Future<RtShaderCode>> submitted, String name,
                                             Supplier<byte[]> stage) {
        Future<RtShaderCode> future = stageCompilers.submit(() -> new RtShaderCode(name, stage.get()));
        submitted.add(future);
        return future;
    }

    /** Waits for every stage, so none outlives its shader compiler, then rethrows the first failure. */
    private static void awaitStages(List<Future<RtShaderCode>> stages) {
        boolean interrupted = false;
        Throwable failure = null;
        for (Future<RtShaderCode> stage : stages) {
            while (true) {
                try {
                    stage.get();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    if (failure == null) failure = e.getCause();
                    break;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    private Candidate requireCandidate(CompiledProgram program) {
        if (!(program instanceof Candidate candidate) || candidate.owner != this) {
            throw new IllegalArgumentException("program belongs to another backend");
        }
        return candidate;
    }

    private static String diagnostics(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getName() + (message == null ? "" : ": " + message);
    }

    /** Renderer-facing immutable state of one published program. */
    public interface Published extends CompiledProgram {
        ProgramComposition composition();
        RtPipeline pipeline();
        VulkanDeviceAddress compositionDataAddress();
        /** The SHaRC resolve, or null for a program without the SHaRC stages. */
        default RtSharcResolve sharcResolve() { return null; }

        default int resolve(SurfaceId<?, ?> id) { return composition().resolve(id); }
        default int resolve(VolumeId<?, ?> id) { return composition().resolve(id); }
        default int resolve(EnvironmentId<?> id) { return composition().resolve(id); }

        /** Writes this program's implementation table address into a complete world binding root. */
        default void writeCompositionDataAddress(ByteBuffer roots) {
            Objects.requireNonNull(roots, "roots");
            if (roots.remaining() != RtBindings.WORLD_PUSH_CONSTANT_SIZE) {
                throw new IllegalArgumentException("world binding root has the wrong size");
            }
            roots.duplicate().order(ByteOrder.nativeOrder()).putLong(
                    roots.position() + RtBindings.WORLD_COMPOSITION_DATA_ADDRESS_OFFSET,
                    compositionDataAddress().value());
        }
    }

    private final class Candidate implements Published {
        private final RtProgramBackend owner = RtProgramBackend.this;
        private final ProgramComposition composition;
        private final WorldShaderCompiler compiler;
        private final VmaMappedBuffer table;
        private final RtPipeline pipeline;
        private final RtSharcResolve sharcResolve;
        private final SharedResource<Published> lifetime;
        private Runnable retired = () -> { };
        private CandidateState state = CandidateState.CANDIDATE;

        private Candidate(ProgramComposition composition, WorldShaderCompiler compiler,
                          VmaMappedBuffer table, RtPipeline pipeline, RtSharcResolve sharcResolve) {
            this.composition = composition;
            this.compiler = compiler;
            this.table = table;
            this.pipeline = pipeline;
            this.sharcResolve = sharcResolve;
            lifetime = SharedResource.owned(this, ignored -> context.deferDestroy(this::destroy));
        }

        @Override public ProgramComposition composition() { return composition; }
        @Override public RtPipeline pipeline() { return pipeline; }
        @Override public RtSharcResolve sharcResolve() { return sharcResolve; }
        @Override public VulkanDeviceAddress compositionDataAddress() { return table.deviceRange().address(); }

        @Override public void close() {
            synchronized (RtProgramBackend.this) {
                if (state == CandidateState.DISPOSED) return;
                if (state == CandidateState.ACTIVE) {
                    throw new IllegalStateException("cannot close the active program");
                }
                state = CandidateState.DISPOSED;
                lifetime.close();
            }
        }

        private void destroy() {
            try (compiler; table) {
                pipeline.destroy();
                if (sharcResolve != null) sharcResolve.destroy();
            } finally {
                retired.run();
            }
        }
    }

    private enum CandidateState { CANDIDATE, ACTIVE, RETIRING, DISPOSED }

    private VmaMappedBuffer createImplementationTable(List<Long> words) {
        long size = Math.max(Long.BYTES, Math.multiplyExact((long) words.size(), Long.BYTES));
        VmaMappedBuffer storage = VmaMappedBuffer.create(
                context, size, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "program data");
        try {
            ByteBuffer mapped = storage.mapped().order(ByteOrder.nativeOrder());
            for (long word : words) mapped.putLong(word);
            if (words.isEmpty()) mapped.putLong(0L);
            storage.flush(0L, size);
            return storage;
        } catch (RuntimeException | Error failure) {
            storage.close();
            throw failure;
        }
    }
}
