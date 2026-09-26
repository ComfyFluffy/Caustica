package dev.comfyfluffy.caustica.minecraft.client.program;

import dev.comfyfluffy.caustica.minecraft.client.CausticaMod;
import dev.comfyfluffy.caustica.api.pass.*;
import dev.comfyfluffy.caustica.api.program.*;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeCompletion;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeJob;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFrameSelector;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFrameSelectionInstaller;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFrameCaptureInstaller;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftFrameCaptureState;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftProvidersExtension;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftTelemetry;
import dev.comfyfluffy.caustica.minecraft.api.*;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.client.material.*;
import dev.comfyfluffy.caustica.minecraft.client.overlay.WorldOverlayPass;
import dev.comfyfluffy.caustica.minecraft.rendering.entity.MinecraftEntityGeometry;
import dev.comfyfluffy.caustica.minecraft.rendering.entity.MinecraftVulkanEntityUploader;
import dev.comfyfluffy.caustica.minecraft.rendering.material.MinecraftMaterialEpochCompiler;
import dev.comfyfluffy.caustica.minecraft.rendering.material.MinecraftMaterialLookup;
import dev.comfyfluffy.caustica.minecraft.rendering.material.MinecraftProgramResources;
import dev.comfyfluffy.caustica.minecraft.rendering.program.MinecraftPrograms;
import dev.comfyfluffy.caustica.minecraft.rendering.provider.MinecraftLightProvider;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.SkyLutPass;
import dev.comfyfluffy.caustica.renderer.presentation.fog.FogPass;
import dev.comfyfluffy.caustica.renderer.presentation.fog.FogVolume;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SpatialMedium;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftCapturedFrame;
import dev.comfyfluffy.caustica.minecraft.client.terrain.MinecraftTerrainSession;
import dev.comfyfluffy.caustica.minecraft.client.terrain.RtTerrain;
import dev.comfyfluffy.caustica.minecraft.client.entity.*;
import dev.comfyfluffy.caustica.settings.*;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;

import java.util.ArrayList;
import java.util.List;

/** Owns replaceable Minecraft programs, material epochs, and retained world producers for one session. */
public final class MinecraftProgramSession implements MinecraftWorldSessionContribution {
    private static final ShaderSource SHADERS = ShaderSource.classpath(
            MinecraftProgramSession.class, "/caustica/shaders/minecraft", "surface", "sky");
    private static final ResourceId OVERWORLD = ResourceId.of("minecraft", "overworld");
    private static final ResourceId NETHER = ResourceId.of("minecraft", "the_nether");
    private static final ResourceId END = ResourceId.of("minecraft", "the_end");

    private final MinecraftWorldSessionContext context;
    private final MinecraftProgramResources resources;
    private final MinecraftMaterialEpochCompiler materialEpochs;
    private final MinecraftFrameSelectionInstaller frameSelections;
    private final MinecraftFrameCaptureState frames;
    private MinecraftFrameCaptureInstaller.Lease frameCapture;
    private final FogVolume fogVolume;
    private final MinecraftFogInputs fogInputs = new MinecraftFogInputs();
    private final MinecraftLightProvider lights;
    private final PassRegistration lightRegistration;
    private final PassRegistration overlayRegistration;
    private final PassRegistration fogRegistration;
    private final RtEntities entities;
    private final RtEntityTextures entityTextures;
    private final RtTerrain terrain;
    private final OptionLookup options;
    private Pending pending;
    private Active active;
    private boolean stopped;

    private MinecraftProgramSession(MinecraftWorldSessionContext context, MinecraftProgramResources resources,
                                    MinecraftMaterialEpochCompiler materialEpochs,
                                    MinecraftFrameSelectionInstaller frameSelections,
                                    MinecraftFrameCaptureState frames,
                                    FogVolume fogVolume,
                                    MinecraftLightProvider lights, PassRegistration lightRegistration,
                                    PassRegistration overlayRegistration, PassRegistration fogRegistration,
                                    RtEntities entities,
                                    RtEntityTextures entityTextures,
                                    RtTerrain terrain, OptionLookup options) {
        this.context = context;
        this.resources = resources;
        this.materialEpochs = materialEpochs;
        this.frameSelections = frameSelections;
        this.frames = frames;
        this.fogVolume = fogVolume;
        this.lights = lights;
        this.lightRegistration = lightRegistration;
        this.overlayRegistration = overlayRegistration;
        this.fogRegistration = fogRegistration;
        this.entities = entities;
        this.entityTextures = entityTextures;
        this.terrain = terrain;
        this.options = options;
    }

    public static MinecraftProgramSession open(MinecraftWorldSessionContext context,
                                               MinecraftFrameSelectionInstaller frameSelections,
                                               MinecraftFrameCaptureInstaller frameCaptures,
                                               MinecraftMaterialEpochCompiler materialEpochs,
                                               MinecraftLightingCalibration calibration,
                                               RtEntityTextures entityTextures,
                                               RtEntities entities,
                                               RtTerrain terrain, OptionLookup options,
                                               MinecraftTelemetry.Instrumentation instrumentation) {
        java.util.Objects.requireNonNull(frameSelections, "frameSelections");
        java.util.Objects.requireNonNull(frameCaptures, "frameCaptures");
        java.util.Objects.requireNonNull(materialEpochs, "materialEpochs");
        java.util.Objects.requireNonNull(calibration, "calibration");
        java.util.Objects.requireNonNull(terrain, "terrain");
        java.util.Objects.requireNonNull(options, "options");
        java.util.Objects.requireNonNull(instrumentation, "instrumentation");
        entityTextures.reset();
        MinecraftProgramResources resources = new MinecraftProgramResources(
                context.renderSession().gpu(), context.renderSession().compute(),
                context.renderSession().resources());
        MinecraftFrameCaptureState frames = new MinecraftFrameCaptureState();
        MinecraftFrameCaptureInstaller.Lease frameCapture = null;
        MinecraftLightProvider lights = null;
        PassRegistration lightRegistration = null;
        PassRegistration overlayRegistration = null;
        PassRegistration fogRegistration = null;
        FogVolume fogVolume = null;
        try {
            fogVolume = new FogVolume(context.renderSession().gpu(), context.renderSession().resources());
            lights = new MinecraftLightProvider(context.renderSession().scene(), context.scene(),
                    () -> celestialSettings(options.snapshot().options(MinecraftProvidersExtension.ID)),
                    frames::lightFrame);
            MinecraftLightProvider installedLights = lights;
            lightRegistration = context.renderSession().passes().addWorldResourcePass(
                    setup -> new LightUpdatePass(installedLights, instrumentation));
            overlayRegistration = context.renderSession().passes().addUiPass(
                    WorldOverlayPass.ID, setup -> new WorldOverlayPass(setup, entities, terrain, context.renderSession().resources()));
            fogRegistration = context.renderSession().passes().addSceneEffectPass(FogPass.ID,
                    setup -> new FogPass(setup, context.renderSession().resources(),
                            () -> options.snapshot().options(MinecraftProvidersExtension.ID)));
            MinecraftProgramSession session = new MinecraftProgramSession(
                    context, resources, materialEpochs, frameSelections, frames, fogVolume,
                    lights, lightRegistration, overlayRegistration, fogRegistration, entities, entityTextures, terrain, options);
            frameCapture = java.util.Objects.requireNonNull(frameCaptures.install(new MinecraftFrameCaptureInstaller.Sink() {
                @Override public void update(MinecraftCapturedFrame frame) { frames.update(frame); }

                @Override public SpatialMedium<?, ?> spatialMedium(Camera camera, double originX, double originY,
                                                                   double originZ, double metersPerSceneUnit) {
                    return session.captureSpatialMedium(originX, originY, originZ, metersPerSceneUnit);
                }
            }, calibration), "frame capture lease");
            session.frameCapture = frameCapture;
            session.beginReplacement(context.resourcePackEpoch());
            return session;
        } catch (RuntimeException | Error failure) {
            var releases = new ArrayList<Runnable>();
            if (frameCapture != null) releases.add(frameCapture::close);
            if (fogRegistration != null) releases.add(fogRegistration::close);
            if (overlayRegistration != null) releases.add(overlayRegistration::close);
            if (lightRegistration != null) releases.add(lightRegistration::close);
            if (lights != null) releases.add(lights::close);
            if (fogVolume != null) releases.add(fogVolume::close);
            releases.add(resources::close);
            ResourceLifetime.closeAfterFailure(failure, releases.toArray(Runnable[]::new));
            throw failure;
        }
    }

    private synchronized SpatialMedium<?, ?> captureSpatialMedium(double originX, double originY,
                                                                  double originZ, double metersPerSceneUnit) {
        if (active == null) return null;
        OptionValues capturedOptions = options();
        return fogVolume.capture(active.registration.exports().fogVolume(),
                fogInputs.capture(frames.fogFrame(), capturedOptions), capturedOptions,
                originX, originY, originZ, metersPerSceneUnit);
    }

    @Override public synchronized void resourcePackChanged(ResourcePackEpoch epoch) {
        entityTextures.reset();
        if (!stopped) beginReplacement(epoch);
    }

    private synchronized void beginReplacement(ResourcePackEpoch resourcePack) {
        MinecraftMaterialLookup lookup;
        try {
            lookup = materialEpochs.compile(resourcePack, List.of());
        } catch (RuntimeException | Error failure) {
            CausticaMod.LOGGER.error("Minecraft material epoch {} could not be compiled",
                    resourcePack.generation(), failure);
            return;
        }
        Pending request = new Pending(resourcePack.generation(), lookup);
        try {
            MinecraftProgramResources.PreparedUpload prepared = resources.prepareUpload(
                    lookup, completion -> uploadCompleted(request, completion));
            request.upload = prepared.job();
            request.prepared = prepared.epoch();
        } catch (RuntimeException | Error failure) {
            ResourceLifetime.closeAfterFailure(failure, request::close);
            CausticaMod.LOGGER.error("Minecraft material epoch {} could not be prepared",
                    resourcePack.generation(), failure);
            return;
        }
        Pending displaced = pending;
        pending = request;
        if (displaced != null) displaced.close();
    }

    private synchronized void uploadCompleted(Pending request, GpuComputeCompletion completion) {
        request.upload = null;
        if (pending != request || stopped) return;
        if (!(completion instanceof GpuComputeCompletion.Succeeded)) {
            pending = null;
            request.close();
            if (completion instanceof GpuComputeCompletion.Failed failed) {
                CausticaMod.LOGGER.error("Minecraft material epoch {} GPU upload failed",
                        request.generation, failed.failure());
            }
            return;
        }
        try {
            resources.seal(request.prepared.gpu());
            request.registration = registerPrograms(
                    context.renderSession().program(), roots(request.prepared.gpu()), context.dimension().id());
            request.registration.whenComplete(result -> completed(request, result));
        } catch (RuntimeException | Error failure) {
            pending = null;
            ResourceLifetime.closeAfterFailure(failure, request::close);
            CausticaMod.LOGGER.error("Minecraft material epoch {} could not publish its resources",
                    request.generation, failure);
        }
    }

    private synchronized void completed(Pending request, ProgramRegistration.Completion completion) {
        if (pending != request || stopped) return;
        if (!(completion instanceof ProgramRegistration.Ready)) {
            pending = null;
            request.close();
            if (completion instanceof ProgramRegistration.Failed failed) {
                CausticaMod.LOGGER.error("Minecraft program epoch {} failed: {}",
                        request.generation, failed.failure().diagnostics());
            }
            return;
        }
        try {
            activate(request);
        } catch (RuntimeException | Error failure) {
            pending = null;
            ResourceLifetime.closeAfterFailure(failure, request::close);
            CausticaMod.LOGGER.error("Minecraft program epoch {} could not bind its producers",
                    request.generation, failure);
        }
    }

    private void activate(Pending request) {
        ProgramRegistration<Exports> registration = request.registration;
        MinecraftPrograms programs = registration.exports().minecraft();
        Active displaced = active;

        MinecraftTerrainSession terrainSession = new MinecraftTerrainSession(
                context.renderSession().gpu(), context.renderSession().resources(), terrain, entityTextures);
        MinecraftFrameSelectionInstaller.Lease frameSelection = null;
        MinecraftEntityGeometry entityGeometry = null;
        RtEntities.Lease entityLease = null;
        MinecraftFrameSelector frameSelector = null;
        PassRegistration sky = createSky(programs, request.generation);
        try {
            if (displaced != null) displaced.stopSceneProducers();
            terrainSession.bind(programs, context.renderSession().meshes(),
                    context.renderSession().scene(), context.scene());
            terrainSession.publishMaterialLookup(request.lookup);
            frameSelector = new MinecraftFrameSelector(context.scene(), programs.waterVolume(),
                    request.prepared.gpu().fallbackBindingData(),
                    request.prepared.gpu().fallbackInstanceData());
            frameSelection = java.util.Objects.requireNonNull(frameSelections.install(frameSelector),
                    "frame selection lease");
            entityGeometry = new MinecraftEntityGeometry(context.renderSession().meshes(), context.renderSession().scene(), context.scene(),
                    new MinecraftVulkanEntityUploader(context.renderSession().gpu(), request.lookup,
                            programs, entityTextures, context.renderSession().resources()));
            entityLease = java.util.Objects.requireNonNull(entities.install(entityGeometry),
                    "entity capture lease");
        } catch (RuntimeException | Error failure) {
            var releases = new ArrayList<Runnable>();
            if (entityLease != null) releases.add(entityLease::close);
            if (entityGeometry != null) {
                releases.add(entityGeometry::stop);
                releases.add(entityGeometry::close);
            }
            if (frameSelection != null) releases.add(frameSelection::close);
            releases.add(terrainSession::stop);
            if (sky != null) releases.add(sky::close);
            ResourceLifetime.closeAfterFailure(failure, releases.toArray(Runnable[]::new));
            throw failure;
        }

        ArrayList<RetiredPrograms> delayed = new ArrayList<>();
        if (displaced != null) {
            delayed.addAll(displaced.delayed);
            delayed.add(new RetiredPrograms(displaced.sky, displaced.registration, displaced.epoch));
        }
        Active replacement = new Active(request.generation, registration, request.prepared.gpu(), terrainSession,
                frameSelector, frameSelection, entityGeometry, entityLease, sky, delayed);
        request.registration = null;
        request.prepared = null;
        pending = null;
        active = replacement;
        if (context.dimension().id().equals(NETHER) || context.dimension().id().equals(END)) {
            // Procedural dimension skies have no textures or frame data to retain.
            try (var data = MinecraftProgramTypes.ENVIRONMENT_BINDING_DATA.data(0L)) {
                context.environment().select(new EnvironmentBinding<>(programs.environment(), data));
            }
        }
        if (sky == null) replacement.releaseDisplacedPrograms();
    }

    private PassRegistration createSky(MinecraftPrograms programs, long generation) {
        if (!context.dimension().id().equals(OVERWORLD)) return null;
        return context.renderSession().passes().addWorldResourcePass(setup -> {
            SkyLutPass sky = new SkyLutPass(setup.gpu(), this::options,
                    frames::skyFrame, programs.environment(), context.environment(),
                    context.renderSession().resources(), generation);
            return new FirstRecordPass(sky, () -> skySelected(generation));
        });
    }

    private synchronized void skySelected(long generation) {
        if (active != null && active.generation == generation) active.releaseDisplacedPrograms();
    }

    private static Roots roots(MinecraftProgramResources.Epoch epoch) {
        return new Roots(epoch.implementationData(), epoch.fallbackBindingData(),
                epoch.fallbackInstanceData());
    }

    static ProgramRegistration<Exports> registerPrograms(ProgramChannel channel, Roots roots,
                                                          ResourceId dimension) {
        return channel.register(builder -> {
            var coverage = SHADERS.definition("caustica_minecraft_coverage", "MinecraftCoverage");
            var material = new SurfaceDefinition<>(
                    SHADERS.definition("caustica_minecraft_surface", "MinecraftSurface"),
                    coverage, roots.implementation(), MinecraftProgramTypes.PRIMITIVE_DATA,
                    MinecraftProgramTypes.INSTANCE_DATA);
            MinecraftPrograms minecraft = new MinecraftPrograms(builder.surface(material),
                    builder.surface(SurfaceDefinition.of(
                            SHADERS.definition("caustica_water_surface", "WaterSurface"),
                            coverage, roots.implementation(), MinecraftProgramTypes.PRIMITIVE_DATA,
                            MinecraftProgramTypes.INSTANCE_DATA)),
                    builder.surface(SurfaceDefinition.of(
                            SHADERS.definition("caustica_portal_surface", "PortalSurface"),
                            coverage, roots.implementation(), MinecraftProgramTypes.PRIMITIVE_DATA,
                            MinecraftProgramTypes.INSTANCE_DATA)),
                    builder.volume(new VolumeDefinition<>(
                            SHADERS.definition("caustica_water_surface", "WaterVolume"),
                            roots.implementation(), MinecraftProgramTypes.PRIMITIVE_DATA,
                            MinecraftProgramTypes.INSTANCE_DATA)),
                    builder.volume(new VolumeDefinition<>(
                            SHADERS.definition("caustica_minecraft_material", "MinecraftDielectricVolume"),
                            roots.implementation(), MinecraftProgramTypes.PRIMITIVE_DATA,
                            MinecraftProgramTypes.INSTANCE_DATA)),
                    builder.environment(new EnvironmentDefinition<>(
                            dimension.equals(NETHER)
                                    ? SHADERS.definition("caustica_minecraft_dimension_skies", "MinecraftNetherSky")
                                    : dimension.equals(END)
                                    ? SHADERS.definition("caustica_minecraft_dimension_skies", "MinecraftEndSky")
                                    : SHADERS.definition("caustica_minecraft_overworld_sky", "MinecraftOverworldSky"),
                            MinecraftProgramTypes.ENVIRONMENT_BINDING_DATA)));
            try (var implementation = FogVolume.BINDING_DATA.data(0L)) {
                return new Exports(minecraft, builder.volume(new VolumeDefinition<>(FogVolume.definition(),
                        implementation, FogVolume.BINDING_DATA, FogVolume.INSTANCE_DATA)));
            }
        });
    }

    private OptionValues options() {
        return options.snapshot().options(MinecraftProvidersExtension.ID);
    }

    static MinecraftLightProvider.CelestialSettings celestialSettings(OptionValues values) {
        return new MinecraftLightProvider.CelestialSettings(
                values.get(SkyLutPass.SUN_NOON_SOUTH_TILT_DEGREES),
                values.get(SkyLutPass.SUN_ANGULAR_RADIUS_DEGREES),
                values.get(SkyLutPass.MOON_ANGULAR_RADIUS_DEGREES),
                values.get(SkyLutPass.MOON_ILLUMINANCE_LUX));
    }

    @Override public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        Active retiring = active;
        Pending cancelled = pending;
        pending = null;
        active = null;
        new ResourceLifetime(frameCapture::close, fogRegistration::close, overlayRegistration::close, lightRegistration::close,
                () -> { if (retiring != null && retiring.sky != null) retiring.sky.close(); },
                () -> { if (retiring != null) retiring.stopSceneProducers(); },
                lights::close,
                () -> { if (cancelled != null) cancelled.close(); },
                () -> { if (retiring != null) retiring.closePrograms(); },
                fogVolume::close,
                terrain::shutdown).close();
    }

    @Override public void close() { resources.close(); }

    record Exports(MinecraftPrograms minecraft, VolumeId<FogVolume.Binding, FogVolume.Instance> fogVolume) { }

    record Roots(ShaderData<MinecraftProgramTypes.ImplementationData> implementation,
                 ShaderData<MinecraftProgramTypes.PrimitiveData> fallbackBinding,
                 ShaderData<MinecraftProgramTypes.InstanceData> fallbackInstance) {
        Roots {
            java.util.Objects.requireNonNull(implementation, "implementation");
            java.util.Objects.requireNonNull(fallbackBinding, "fallbackBinding");
            java.util.Objects.requireNonNull(fallbackInstance, "fallbackInstance");
            if (implementation.bits() == 0L || fallbackBinding.bits() == 0L || fallbackInstance.bits() == 0L) {
                throw new IllegalArgumentException("Minecraft program roots must be nonzero");
            }
        }
    }

    static final class Pending {
        final long generation;
        final MinecraftMaterialLookup lookup;
        GpuComputeJob upload;
        ProgramRegistration<Exports> registration;
        MinecraftProgramResources.PreparedEpoch prepared;
        Pending(long generation, MinecraftMaterialLookup lookup) {
            this.generation = generation;
            this.lookup = lookup;
        }
        void close() {
            var releases = new ArrayList<Runnable>();
            if (upload != null) releases.add(upload::close);
            if (registration != null) releases.add(registration::close);
            if (prepared != null) releases.add(prepared.gpu()::close);
            upload = null;
            registration = null;
            prepared = null;
            new ResourceLifetime(releases.toArray(Runnable[]::new)).close();
        }
    }

    private final class Active {
        final long generation;
        final ProgramRegistration<Exports> registration;
        final MinecraftProgramResources.Epoch epoch;
        final MinecraftTerrainSession terrain;
        final MinecraftFrameSelector frameSelector;
        final MinecraftFrameSelectionInstaller.Lease frameSelection;
        final MinecraftEntityGeometry entityGeometry;
        final RtEntities.Lease entityLease;
        final PassRegistration sky;
        final ArrayList<RetiredPrograms> delayed;
        boolean producersStopped;
        Active(long generation, ProgramRegistration<Exports> registration,
               MinecraftProgramResources.Epoch epoch,
               MinecraftTerrainSession terrain, MinecraftFrameSelector frameSelector,
               MinecraftFrameSelectionInstaller.Lease frameSelection,
               MinecraftEntityGeometry entityGeometry,
               RtEntities.Lease entityLease,
               PassRegistration sky, ArrayList<RetiredPrograms> delayed) {
            this.generation = generation;
            this.registration = registration;
            this.epoch = epoch;
            this.terrain = terrain;
            this.frameSelector = frameSelector;
            this.frameSelection = frameSelection;
            this.entityGeometry = entityGeometry;
            this.entityLease = entityLease;
            this.sky = sky;
            this.delayed = delayed;
        }
        void stopSceneProducers() {
            if (producersStopped) return;
            producersStopped = true;
            new ResourceLifetime(frameSelection::close, entityLease::close,
                    entityGeometry::stop, entityGeometry::close, terrain::stop).close();
        }
        void releaseDisplacedPrograms() {
            Runnable[] releases = delayed.stream().<Runnable>map(retired -> retired::close)
                    .toArray(Runnable[]::new);
            delayed.clear();
            new ResourceLifetime(releases).close();
        }
        void closePrograms() {
            new ResourceLifetime(() -> { if (sky != null) sky.close(); },
                    this::releaseDisplacedPrograms, registration::close, epoch::close).close();
        }
    }

    private record RetiredPrograms(PassRegistration sky, ProgramRegistration<Exports> registration,
                                   MinecraftProgramResources.Epoch epoch) {
        void close() {
            new ResourceLifetime(() -> { if (sky != null) sky.close(); },
                    registration::close, epoch::close).close();
        }
    }

    static final class LightUpdatePass implements Pass<PassFrame> {
        private final MinecraftLightProvider lights;
        private final MinecraftTelemetry.Instrumentation instrumentation;
        LightUpdatePass(MinecraftLightProvider lights, MinecraftTelemetry.Instrumentation instrumentation) {
            this.lights = lights;
            this.instrumentation = instrumentation;
        }
        @Override public void record(PassFrame frame) {
            long started = instrumentation.startStage();
            try {
                lights.update();
            } finally {
                instrumentation.endStage("terrain.lightScenePublish", started);
            }
        }
        @Override public void close() { }
    }

    static final class FirstRecordPass implements Pass<PassFrame> {
        private final Pass<PassFrame> delegate;
        private final Runnable firstRecord;
        private boolean recorded;
        FirstRecordPass(Pass<PassFrame> delegate, Runnable firstRecord) {
            this.delegate = delegate;
            this.firstRecord = firstRecord;
        }
        @Override public void record(PassFrame frame) {
            delegate.record(frame);
            if (!recorded) {
                recorded = true;
                firstRecord.run();
            }
        }
        @Override public void close() { delegate.close(); }
    }
}
