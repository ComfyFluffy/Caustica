package dev.comfyfluffy.caustica.minecraft.rendering.sky;

import dev.comfyfluffy.caustica.api.vulkan.*;
import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.rendering.CelestialAtlasImage;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftCelestialFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftSkyFrame;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftEnvironmentSelector;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.gen.*;
import dev.comfyfluffy.caustica.settings.*;
import dev.comfyfluffy.caustica.support.SharedResource;
import dev.comfyfluffy.caustica.vulkan.*;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;

import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.VK10.*;
import static dev.comfyfluffy.caustica.vulkan.ResourceLifetime.closeAfterFailure;

/** Owns the Overworld atmosphere LUTs and publishes immutable environment binding revisions. */
public final class SkyLutPass implements Pass<PassFrame> {
    public static final ResourceId ID = ResourceId.of("caustica", "sky_lut");
    private static final String SHADER_ROOT = "/caustica/shaders/pipelines/sky/";
    static final int TRANSMITTANCE_WIDTH = 256, TRANSMITTANCE_HEIGHT = 64;
    static final int MULTISCATTER_WIDTH = 32, MULTISCATTER_HEIGHT = 32;
    static final int SKY_VIEW_WIDTH = 192, SKY_VIEW_HEIGHT = 216;
    static final long PRIOR_SKY_READ_STAGE = KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR;
    static final long PRIOR_SKY_READ_ACCESS = VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT
            | VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
    static final long SKY_WRITE_STAGE = VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
    static final long SKY_WRITE_ACCESS = VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;

    public static final String GROUP = "sky";
    public static final Option<Float> SUN_NOON_SOUTH_TILT_DEGREES = option("sun-noon-south-tilt-degrees", -89, 89, 30);
    public static final Option<Float> SUN_ANGULAR_RADIUS_DEGREES = option("sun-angular-radius-degrees", 0, 20, .6f);
    public static final Option<Float> MOON_ANGULAR_RADIUS_DEGREES = option("moon-angular-radius-degrees", 0, 20, 1.5f);
    /**
     * Full-moon illuminance at the top of the atmosphere in lux, before the phase scale. The physical
     * full moon delivers about 0.3 lux; zero gives a moonless night lit only by airglow and stars.
     */
    public static final Option<Float> MOON_ILLUMINANCE_LUX = option("moon-illuminance-lux", 0, 10, .3f);
    public static final Option<Float> SUN_DISC_HALF_ANGLE_DEGREES = option("sun-disc-half-angle-degrees", 0, 45, 16.7f);
    public static final Option<Float> MOON_DISC_HALF_ANGLE_DEGREES = option("moon-disc-half-angle-degrees", 0, 45, 11.31f);
    public static final Option<Float> GROUND_ALBEDO = option("ground-albedo", 0, 1, .1f);
    public static final Option<Float> HORIZON_SOFTEN_DEGREES = option("horizon-soften-degrees", 0, 90, 15);
    public static final List<Option<?>> OPTIONS = List.of(SUN_NOON_SOUTH_TILT_DEGREES,
            SUN_ANGULAR_RADIUS_DEGREES, MOON_ANGULAR_RADIUS_DEGREES, MOON_ILLUMINANCE_LUX, SUN_DISC_HALF_ANGLE_DEGREES,
            MOON_DISC_HALF_ANGLE_DEGREES, GROUND_ALBEDO, HORIZON_SOFTEN_DEGREES);

    private final GpuDevice gpu;
    private final Supplier<OptionValues> options;
    private final Supplier<MinecraftSkyFrame> frames;
    private final EnvironmentId<MinecraftProgramTypes.EnvironmentBindingData> environment;
    private final MinecraftEnvironmentSelector selector;
    private final ResourceFactory resourceFactory;
    private final VmaImage2D transmittance, multiScatter, skyView;
    private final VulkanSampler lutSampler, celestialSampler;
    private final VmaMappedBuffer skyInputs;
    private final ShaderObjectCompute transmittanceShader, multiScatterShader, skyViewShader;
    private final SharedResource<ResourceLifetime> resources;
    private final AtomicLong resourcePackEpoch;
    private ResourceOwner bindingOwner;
    private SharedResource<AtlasEntry> atlas;
    private boolean initialized, baked, closed;
    private float bakedGroundAlbedo;

    public SkyLutPass(GpuDevice gpu, Supplier<OptionValues> options,
                      Supplier<MinecraftSkyFrame> frames,
                      EnvironmentId<MinecraftProgramTypes.EnvironmentBindingData> environment,
                      MinecraftEnvironmentSelector selector, ResourceFactory resourceFactory, long epoch) {
        this.gpu = Objects.requireNonNull(gpu, "gpu");
        this.options = Objects.requireNonNull(options, "options");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.selector = Objects.requireNonNull(selector, "selector");
        this.resourceFactory = Objects.requireNonNull(resourceFactory, "resourceFactory");
        resourcePackEpoch = new AtomicLong(epoch);
        List<Runnable> allocated = new ArrayList<>();
        try {
            transmittance = VmaImage2D.create(gpu, TRANSMITTANCE_WIDTH, TRANSMITTANCE_HEIGHT,
                    VK_FORMAT_R16G16B16A16_SFLOAT, ID + " transmittance");
            allocated.add(transmittance::close);
            multiScatter = VmaImage2D.create(gpu, MULTISCATTER_WIDTH, MULTISCATTER_HEIGHT,
                    VK_FORMAT_R16G16B16A16_SFLOAT, ID + " multiscatter");
            allocated.add(multiScatter::close);
            skyView = VmaImage2D.create(gpu, SKY_VIEW_WIDTH, SKY_VIEW_HEIGHT,
                    VK_FORMAT_R16G16B16A16_SFLOAT, ID + " sky view");
            allocated.add(skyView::close);
            lutSampler = VulkanSampler.linearClamp(gpu);
            allocated.add(lutSampler::close);
            celestialSampler = VulkanSampler.nearestClamp(gpu);
            allocated.add(celestialSampler::close);
            skyInputs = createEmptyBuffer(gpu, SkyInputsData.BYTE_SIZE, "Minecraft sky inputs");
            allocated.add(skyInputs::close);
            transmittanceShader = ShaderObjectCompute.load(gpu, SkyLutPass.class, SHADER_ROOT + "transmittance.comp.spv");
            allocated.add(transmittanceShader::close);
            multiScatterShader = ShaderObjectCompute.load(gpu, SkyLutPass.class, SHADER_ROOT + "multiscatter.comp.spv");
            allocated.add(multiScatterShader::close);
            skyViewShader = ShaderObjectCompute.load(gpu, SkyLutPass.class, SHADER_ROOT + "view.comp.spv");
            allocated.add(skyViewShader::close);
            resources = SharedResource.owned(new ResourceLifetime(skyInputs::close, skyView::close,
                    multiScatter::close, transmittance::close, celestialSampler::close, lutSampler::close),
                    ResourceLifetime::close);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure, allocated.reversed().toArray(Runnable[]::new));
            throw failure;
        }
    }

    public void invalidate(long epoch) { resourcePackEpoch.accumulateAndGet(epoch, Math::max); }

    @Override public void record(PassFrame frame) {
        boolean hasPriorGpuUse = initialized;
        if (!initialized) {
            ComputeSynchronization.initializeImages(frame.commandBuffer(), List.of(transmittance, multiScatter, skyView));
            initialized = true;
        }
        MinecraftSkyFrame captured = frames.get();
        if (captured == null) return;
        if (hasPriorGpuUse) priorRayReadsToSkyWrites(frame.commandBuffer());
        SkyState state = gather(options.get(), captured.celestial());
        AtlasSnapshot snapshot = atlasSnapshot(captured.atlas());
        SkyInputsData inputs = skyInputs(state, snapshot);
        ensureBinding(snapshot);
        if (baked && Float.compare(bakedGroundAlbedo, state.groundAlbedo()) != 0) baked = false;
        if (!baked) {
            dispatch(transmittanceShader, frame, transmittance, inputs, skyInputsAddress());
            ComputeSynchronization.betweenDispatches(frame.commandBuffer());
            dispatch(multiScatterShader, frame, multiScatter, inputs, skyInputsAddress());
            ComputeSynchronization.betweenDispatches(frame.commandBuffer());
            bakedGroundAlbedo = state.groundAlbedo();
            baked = true;
        }
        dispatch(skyViewShader, frame, skyView, inputs, skyInputsAddress());
    }

    private void ensureBinding(AtlasSnapshot snapshot) {
        long epoch = resourcePackEpoch.get();
        if (atlas != null && sameBindingEpoch(atlas.get().image.vkImage(), atlas.get().epoch,
                snapshot.image().vkImage(), epoch)) return;
        SharedResource<AtlasEntry> replacement = AtlasEntry.create(
                gpu, snapshot.image(), snapshot.baseMipLevel(), snapshot.mipLevels(), epoch);
        Runnable releaseBinding = () -> { };
        ResourceOwner owner;
        try {
            BindingResources binding = createBinding(replacement.retain());
            releaseBinding = binding::close;
            owner = resourceFactory.create(releaseBinding);
            // Once registered, the producer claim owns cleanup; published readers may retain it.
            releaseBinding = owner::close;
            publishBinding(owner, environment, selector, binding.root.deviceRange().address().value());
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure, releaseBinding, replacement::close);
            throw failure;
        }
        ResourceOwner previousOwner = bindingOwner;
        SharedResource<AtlasEntry> previousAtlas = atlas;
        bindingOwner = owner;
        atlas = replacement;
        baked = false;
        new ResourceLifetime(() -> {
            if (previousOwner != null) previousOwner.close();
        }, () -> {
            if (previousAtlas != null) previousAtlas.close();
        }).close();
    }

    static void publishBinding(ResourceOwner owner,
                               EnvironmentId<MinecraftProgramTypes.EnvironmentBindingData> environment,
                               MinecraftEnvironmentSelector selector, long address) {
        try (var data = MinecraftProgramTypes.ENVIRONMENT_BINDING_DATA.data(address, owner)) {
            selector.select(new EnvironmentBinding<>(environment, data));
        }
    }

    static boolean sameBindingEpoch(long image, long epoch, long nextImage, long nextEpoch) {
        return image == nextImage && epoch == nextEpoch;
    }

    private BindingResources createBinding(SharedResource<AtlasEntry> atlasLease) {
        VmaMappedBuffer root = null;
        try {
            root = createBuffer(MinecraftEnvironmentBindingData.BYTE_SIZE, bytes ->
                    new MinecraftEnvironmentBindingData(
                            sampled(skyView.sampledIndex()), sampled(transmittance.sampledIndex()),
                            sampled(atlasLease.get().index()), sampler(lutSampler.index()),
                            sampler(celestialSampler.index()),
                            skyInputsAddress()).write(bytes));
            return new BindingResources(root, atlasLease, resources.retain());
        } catch (RuntimeException | Error failure) {
            VmaMappedBuffer allocatedRoot = root;
            closeAfterFailure(failure, () -> {
                if (allocatedRoot != null) allocatedRoot.close();
            }, atlasLease::close);
            throw failure;
        }
    }

    private long skyInputsAddress() { return skyInputs.deviceRange().address().value(); }

    private VmaMappedBuffer createBuffer(int size, java.util.function.Consumer<ByteBuffer> writer) {
        VmaMappedBuffer buffer = VmaMappedBuffer.create(
                gpu, size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "Minecraft sky buffer");
        try {
            writer.accept(buffer.mapped().order(ByteOrder.LITTLE_ENDIAN));
            buffer.flush(0, size);
            return buffer;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure, buffer::close);
            throw failure;
        }
    }

    private static VmaMappedBuffer createEmptyBuffer(GpuDevice gpu, int size, String name) {
        VmaMappedBuffer buffer = VmaMappedBuffer.create(gpu, size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, name);
        try {
            ByteBuffer bytes = buffer.mapped();
            for (int i = 0; i < size; i++) bytes.put(i, (byte) 0);
            buffer.flush(0, size);
            return buffer;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure, buffer::close);
            throw failure;
        }
    }

    private static MinecraftEnvironmentBindingData.SampledTexture2DIndex sampled(GpuDescriptorIndex.Resource index) {
        return new MinecraftEnvironmentBindingData.SampledTexture2DIndex(index.value());
    }
    private static MinecraftEnvironmentBindingData.SamplerIndex sampler(GpuDescriptorIndex.Sampler index) {
        return new MinecraftEnvironmentBindingData.SamplerIndex(index.value());
    }

    private void dispatch(ShaderObjectCompute shader, PassFrame frame, VmaImage2D destination,
                          SkyInputsData inputs, long skyInputsAddress) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(SkyLutPushData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            new SkyLutPushData(destination.storageIndex().value(),
                    new SkyLutPushData.SampledTexture2DIndex(transmittance.sampledIndex().value()),
                    new SkyLutPushData.SampledTexture2DIndex(multiScatter.sampledIndex().value()),
                    new SkyLutPushData.SamplerIndex(lutSampler.index().value()), pushInputs(inputs),
                    skyInputsAddress).write(push);
            shader.dispatch(frame.commandBuffer(), push, groups(destination.width()), groups(destination.height()), 1);
        }
    }

    private static SkyLutPushData.SkyInputs pushInputs(SkyInputsData v) {
        return new SkyLutPushData.SkyInputs(vec(v.celestial()), vec(v.skyLook0()), vec(v.skyLook1()),
                vec(v.skyLook2()), vec(v.skyLook3()), vec(v.skyLook4()), vec(v.sunUv()), vec(v.moonUv()));
    }
    private static SkyLutPushData.Float4 vec(SkyInputsData.Float4 v) {
        return new SkyLutPushData.Float4(v.x(), v.y(), v.z(), v.w());
    }
    static int groups(int extent) { return (extent + 7) / 8; }

    static SkyInputsData skyInputs(SkyState s, AtlasSnapshot a) {
        return new SkyInputsData(new SkyInputsData.Float4(s.sunAngleRadians(), s.moonAngleRadians(),
                s.starAngleRadians(), s.starBrightness()), new SkyInputsData.Float4(s.sunIlluminanceLux(),
                s.moonIlluminanceLux(), s.nightAirglowLuminance(), s.starLuminance()),
                new SkyInputsData.Float4(s.noonTiltRadians(), s.sunAngularRadiusRadians(),
                        s.moonAngularRadiusRadians(), s.moonPhaseFixedFraction()),
                new SkyInputsData.Float4(s.sunDiscHalfAngleRadians(), s.moonDiscHalfAngleRadians(),
                        s.viewerAltitudeKm(), s.moonPhaseIndex()),
                new SkyInputsData.Float4(s.groundAlbedo(), s.horizonSoftenRadians(), s.daylightFactor(),
                        s.beamTransmittance()),
                new SkyInputsData.Float4(s.skySaturation(), 0, 0, 0),
                a.sunUv(), a.moonUv());
    }

    static SkyState gather(OptionValues options, MinecraftCelestialFrame captured) {
        float altitude = viewerAltitudeKm(captured.cameraY(), captured.seaLevel(),
                captured.metersPerSceneUnit());
        float r = (float) (Math.PI / 180.0);
        MinecraftLightingCalibration l = captured.lighting();
        return new SkyState(captured.sunAngleRadians(), captured.moonAngleRadians(),
                captured.starAngleRadians(), captured.starBrightness(), l.sunIlluminanceLux(),
                options.get(MOON_ILLUMINANCE_LUX), l.nightAirglowLuminanceCdM2(), l.starLuminanceCdM2(),
                options.get(SUN_NOON_SOUTH_TILT_DEGREES) * r, options.get(SUN_ANGULAR_RADIUS_DEGREES) * r,
                options.get(MOON_ANGULAR_RADIUS_DEGREES) * r, l.moonPhaseFixedFraction(),
                options.get(SUN_DISC_HALF_ANGLE_DEGREES) * r,
                options.get(MOON_DISC_HALF_ANGLE_DEGREES) * r, altitude,
                captured.moonPhaseIndex(), options.get(GROUND_ALBEDO),
                options.get(HORIZON_SOFTEN_DEGREES) * r, captured.weather().daylightFactor(),
                captured.weather().beamTransmittance(), captured.weather().skySaturation());
    }

    static float viewerAltitudeKm(double cameraY, double seaLevel, double metersPerSceneUnit) {
        return Math.clamp((float) ((cameraY - seaLevel) * metersPerSceneUnit / 1000.0), 0, 99);
    }

    private static AtlasSnapshot atlasSnapshot(MinecraftSkyFrame.CelestialAtlas atlas) {
        return new AtlasSnapshot(atlas.image(), atlas.baseMipLevel(), atlas.mipLevels(),
                uv(atlas.sunUv()), uv(atlas.moonUv()));
    }
    private static SkyInputsData.Float4 uv(MinecraftSkyFrame.Uv uv) {
        return new SkyInputsData.Float4(uv.u0(), uv.v0(), uv.u1(), uv.v1());
    }

    private void priorRayReadsToSkyWrites(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK14.vkCmdPipelineBarrier2(commandBuffer,
                    priorRayReadsToSkyWritesDependency(stack, skyView));
        }
    }

    private static VkDependencyInfo priorRayReadsToSkyWritesDependency(MemoryStack stack, VmaImage2D skyView) {
        VkMemoryBarrier2.Buffer buffers = VkMemoryBarrier2.calloc(1, stack);
        buffers.get(0).sType$Default()
                .srcStageMask(PRIOR_SKY_READ_STAGE)
                .srcAccessMask(PRIOR_SKY_READ_ACCESS)
                .dstStageMask(SKY_WRITE_STAGE)
                .dstAccessMask(SKY_WRITE_ACCESS);
        VkImageMemoryBarrier2.Buffer images = VkImageMemoryBarrier2.calloc(1, stack);
        images.get(0).sType$Default()
                .srcStageMask(PRIOR_SKY_READ_STAGE)
                .srcAccessMask(VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                .dstStageMask(SKY_WRITE_STAGE)
                .dstAccessMask(SKY_WRITE_ACCESS)
                .oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(skyView.image());
        images.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        return VkDependencyInfo.calloc(stack).sType$Default()
                .pMemoryBarriers(buffers).pImageMemoryBarriers(images);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        ResourceOwner owner = bindingOwner;
        SharedResource<AtlasEntry> previousAtlas = atlas;
        bindingOwner = null;
        atlas = null;
        new ResourceLifetime(() -> {
            if (owner != null) owner.close();
        }, () -> {
            if (previousAtlas != null) previousAtlas.close();
        }, skyViewShader::close, multiScatterShader::close, transmittanceShader::close,
                resources::close).close();
    }

    private record BindingResources(VmaMappedBuffer root, SharedResource<AtlasEntry> atlas,
                                    SharedResource<ResourceLifetime> resources) implements AutoCloseable {
        @Override public void close() {
            new ResourceLifetime(root::close, atlas::close, resources::close).close();
        }
    }

    private static final class AtlasEntry {
        final CelestialAtlasImage image; final long epoch;
        final GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptor;
        AtlasEntry(CelestialAtlasImage image, long epoch,
                   GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptor) {
            this.image = image; this.epoch = epoch; this.descriptor = descriptor;
        }
        static SharedResource<AtlasEntry> create(GpuDevice gpu, CelestialAtlasImage image,
                                                 int baseMipLevel, int mipLevels, long epoch) {
            image.retainViews();
            GpuDescriptorRange<GpuDescriptorIndex.Resource> range = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                range = gpu.descriptorHeap().allocateResources(1);
                GpuDescriptorRange<GpuDescriptorIndex.Resource> allocated = range;
                VkImageViewCreateInfo view = VkImageViewCreateInfo.calloc(stack).sType$Default()
                        .image(image.vkImage())
                        .viewType(VK_IMAGE_VIEW_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM);
                view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(baseMipLevel)
                        .levelCount(mipLevels).baseArrayLayer(0).layerCount(1);
                VkImageDescriptorInfoEXT info = VkImageDescriptorInfoEXT.calloc(stack).sType$Default()
                        .pView(view).layout(VK_IMAGE_LAYOUT_GENERAL);
                gpu.descriptorHeap().writer().writeResource(allocated, 0,
                        VkResourceDescriptorInfoEXT.calloc(stack).sType$Default()
                                .type(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).data(d -> d.pImage(info)));
                return SharedResource.owned(new AtlasEntry(image, epoch, allocated), AtlasEntry::close);
            } catch (RuntimeException | Error failure) {
                GpuDescriptorRange<GpuDescriptorIndex.Resource> allocated = range;
                closeAfterFailure(failure, () -> {
                    if (allocated != null) allocated.destroy();
                }, image::releaseViews);
                throw failure;
            }
        }
        void close() {
            new ResourceLifetime(descriptor::destroy, image::releaseViews).close();
        }
        GpuDescriptorIndex.Resource index() { return descriptor.firstIndex(); }
    }

    record AtlasSnapshot(CelestialAtlasImage image, int baseMipLevel, int mipLevels,
                         SkyInputsData.Float4 sunUv, SkyInputsData.Float4 moonUv) { }
    record SkyState(float sunAngleRadians, float moonAngleRadians, float starAngleRadians, float starBrightness,
                    float sunIlluminanceLux, float moonIlluminanceLux, float nightAirglowLuminance,
                    float starLuminance, float noonTiltRadians, float sunAngularRadiusRadians,
                    float moonAngularRadiusRadians, float moonPhaseFixedFraction, float sunDiscHalfAngleRadians,
                    float moonDiscHalfAngleRadians, float viewerAltitudeKm, float moonPhaseIndex,
                    float groundAlbedo, float horizonSoftenRadians, float daylightFactor,
                    float beamTransmittance, float skySaturation) { }

    private static Option<Float> option(String name, float min, float max, float value) {
        return Option.range("sky." + name, min, max, value).inGroup(GROUP);
    }
}
