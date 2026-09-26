package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GraphicsUse;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanBarriers;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.nvidia.ngx.DlssRayReconstruction;
import dev.comfyfluffy.caustica.nvidia.ngx.DlssSuperResolution;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserBackendFactory;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserCommonSettings;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserExtent;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserFrame;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserImage;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserInputs;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserReset;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserRoute;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserSignalEncoding;
import dev.comfyfluffy.caustica.renderer.raytracing.RtNrdComposePipeline;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceExtent;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceImages;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceResources;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.NrdPlaneFrameData;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.WorldPushData.Float3;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBlitImageInfo2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCopyImageInfo2;
import org.lwjgl.vulkan.VkImageBlit2;
import org.lwjgl.vulkan.VkImageCopy2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.function.UnaryOperator;
/** Owns output reconstruction; every route returns the image consumed by presentation. */
final class RtReconstruction implements AutoCloseable {
    static final float NRD_DENOISING_RANGE = 50_000.0f;
    private final VulkanDeviceContext context;
    private final DlssRayReconstruction rayReconstruction;
    private final DlssSuperResolution upscaler;
    private final RtDenoiserState denoiser;
    private final RtTelemetry telemetry;
    private RtNrdComposePipeline nrdComposePipeline;

    RtReconstruction(VulkanDeviceContext context, DlssRayReconstruction rayReconstruction,
                     DlssSuperResolution upscaler, DenoiserBackendFactory factory,
                     RtDenoisingSettings settings, RtTelemetry telemetry) {
        this.context = context;
        this.rayReconstruction = rayReconstruction;
        this.upscaler = upscaler;
        this.denoiser = new RtDenoiserState(factory, settings);
        this.telemetry = telemetry;
    }

    RtDenoisingSettings settings() { return denoiser.settings(); }
    boolean configured(RtDenoisingSettings settings) { return denoiser.configured(settings); }
    void configureAfterIdle(RtDenoisingSettings settings) { denoiser.configureAfterIdle(settings); }
    void closeBackendAfterIdle() { denoiser.closeBackendAfterIdle(); }
    void ensureBackend(TraceExtent extent) {
        if (settings().route() == DenoiserRoute.TEMPORAL_DENOISER && nrdComposePipeline == null) {
            nrdComposePipeline = RtNrdComposePipeline.create(context);
        }
        denoiser.ensureBackend(new DenoiserExtent(extent.renderWidth(), extent.renderHeight()));
    }
    void resetHistory() {
        denoiser.resetHistory();
        rayReconstruction.resetHistory();
        upscaler.resetHistory();
    }
    void submitted(RtFrameInput frame) {
        if (frame.route() == DenoiserRoute.TEMPORAL_DENOISER) denoiser.frameSubmitted();
    }

    GpuImage record(RtFrameCommands commands, MemoryStack stack, GraphicsUse graphicsUse,
                    RtFrameInput frame, TraceResources trace, UnaryOperator<GpuImage> sceneEffects) {
        GpuImage source = trace.images().traceColor();
        GpuImage output = trace.images().reconstructedColor();
        boolean reconstructed = switch (frame.route()) {
            case RAW -> {
                source = sceneEffects.apply(source);
                copyImage(commands.external("raw trace copy"), stack, source, output);
                yield true;
            }
            case RAY_RECONSTRUCTION -> {
                source = sceneEffects.apply(source);
                yield recordRayReconstruction(commands, frame, trace.images(), source);
            }
            case TEMPORAL_DENOISER -> {
                boolean upscale = upscaler.configured();
                GpuImage denoised = upscale ? source : output;
                boolean reset;
                try (RtTelemetry.Scope ignored = telemetry.frame().stage("frame.nrd")) {
                    reset = recordTemporalDenoiser(commands, stack, graphicsUse,
                            frame, trace, denoised);
                }
                source = sceneEffects.apply(denoised);
                if (!upscale) {
                    if (source != output) copyImage(commands.external("scene effect copy"), stack, source, output);
                    yield true;
                }
                try (RtTelemetry.Scope ignored = telemetry.frame().stage("frame.upscale")) {
                    VkCommandBuffer command = commands.external("DLSS super resolution");
                    TraceExtent extent = frame.extent();
                    boolean ready;
                    try (var checkpoint = commands.checkpoint(command, "NGX DLSS-SR ensure feature")) {
                        ready = upscaler.ensureFeature(command, extent.renderWidth(), extent.renderHeight(),
                                extent.displayWidth(), extent.displayHeight());
                    }
                    boolean success = false;
                    if (ready) {
                        try (var checkpoint = commands.checkpoint(command, "NGX DLSS-SR evaluate")) {
                            success = upscaler.evaluate(command, source, trace.images().depth(), trace.images().motion(), output,
                                    extent.renderWidth(), extent.renderHeight(), extent.displayWidth(), extent.displayHeight(),
                                    -frame.jitterX(), -frame.jitterY(), reset, frame.preExposure());
                        }
                    }
                    if (!success) upscaler.resetHistory();
                    yield success;
                }
            }
        };
        if (!reconstructed) {
            VkCommandBuffer command = commands.external("reconstruction fallback");
            VulkanBarriers.memoryBarrier(command, stack);
            blitUpscale(command, stack, source, output);
        }
        return output;
    }

    private boolean recordRayReconstruction(RtFrameCommands commands, RtFrameInput frame, TraceImages images, GpuImage source) {
        if (!rayReconstruction.enabled()) return false;
        TraceExtent extent = frame.extent();
        if (!rayReconstruction.featureReadyFor(extent.renderWidth(), extent.renderHeight(),
                extent.displayWidth(), extent.displayHeight())) context.waitIdle();
        VkCommandBuffer command = commands.external("DLSS ray reconstruction");
        try (var checkpoint = commands.checkpoint(command, "NGX DLSS-RR ensure feature")) {
            if (!rayReconstruction.ensureFeature(command, extent.renderWidth(), extent.renderHeight(),
                    extent.displayWidth(), extent.displayHeight())) return false;
        }
        try (var checkpoint = commands.checkpoint(command, "NGX DLSS-RR evaluate");
             RtTelemetry.Scope ignored = telemetry.frame().stage("frame.dlssRr")) {
            boolean success = rayReconstruction.evaluate(command, source, images.depth(), images.motion(),
                    images.diffuseAlbedo(), images.specularAlbedo(), images.normalRoughness(), images.specularMotion(),
                    images.responsivity(), images.reconstructedColor(), extent.renderWidth(), extent.renderHeight(),
                    extent.displayWidth(), extent.displayHeight(), -frame.jitterX(), -frame.jitterY(), frame.preExposure());
            if (!success) rayReconstruction.resetHistory();
            return success;
        }
    }

    private boolean recordTemporalDenoiser(RtFrameCommands commands,
                                           MemoryStack stack, GraphicsUse graphicsUse,
                                           RtFrameInput frame, TraceResources trace,
                                           GpuImage output) {
        DenoiserReset frameReset = denoiser.frameReset(frame.historyContinuous());
        boolean reset = frameReset == DenoiserReset.CLEAR_AND_RESTART;
        DenoiserCommonSettings common = nrdCommonSettings(frame, frameReset);
        DenoiserInputs inputs = new DenoiserInputs(
                denoiserImage(trace.images().diffuseRadianceHitDistance()),
                denoiserImage(trace.images().specularRadianceHitDistance()),
                denoiserImage(trace.images().normalRoughness()),
                denoiserImage(trace.images().nrdViewZ()),
                denoiserImage(trace.images().nrdMotion()),
                denoiserImage(trace.images().denoisedDiffuseRadianceHitDistance()),
                denoiserImage(trace.images().denoisedSpecularRadianceHitDistance()),
                Optional.of(denoiserImage(trace.images().nrdDisocclusionThresholdMix())), Optional.empty());

        GpuBuffer frameBuffer = context.createMappedGpuUploadBuffer(NrdPlaneFrameData.BYTE_SIZE,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "NRD stable-plane frame");
        graphicsUse.whenComplete(frameBuffer::destroy);
        ByteBuffer frameData = MemoryUtil.memByteBuffer(frameBuffer.mapped(), NrdPlaneFrameData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        new NrdPlaneFrameData(frame.projectionView(), frame.previousProjectionView(),
                new NrdPlaneFrameData.Float3(frame.cameraOffset().x(), frame.cameraOffset().y(), frame.cameraOffset().z()),
                frame.preExposure(),
                new NrdPlaneFrameData.Float3(frame.cameraDelta().x(), frame.cameraDelta().y(), frame.cameraDelta().z()),
                RtNrdComposePipeline.RADIANCE_SCALE, frame.extent().renderWidth(), frame.extent().renderHeight()).write(frameData);
        frameBuffer.flush(0L, NrdPlaneFrameData.BYTE_SIZE);

        long stablePlaneAddress = trace.stablePlaneBuffer().deviceAddress().value();
        long frameAddress = frameBuffer.deviceAddress().value();
        RtNrdComposePipeline.Exchange exchange = new RtNrdComposePipeline.Exchange(output,
                trace.images().diffuseRadianceHitDistance(), trace.images().specularRadianceHitDistance(),
                trace.images().normalRoughness(), trace.images().nrdViewZ(), trace.images().nrdMotion(),
                trace.images().nrdDisocclusionThresholdMix(),
                trace.images().denoisedDiffuseRadianceHitDistance(),
                trace.images().denoisedSpecularRadianceHitDistance(), trace.images().nrdStableRadiance());
        for (int plane = RtDenoiserState.PLANE_COUNT - 1; plane >= 0; plane--) {
            VkCommandBuffer commandBuffer = commands.heap("NRD plane " + plane + " prepare");
            nrdComposePipeline.prepare(commandBuffer,
                    stablePlaneAddress, frameAddress, plane, nrdSignalEncoding(), exchange);
            VulkanBarriers.memoryBarrier(commandBuffer, stack);
            commandBuffer = commands.external("NRD plane " + plane + " evaluate");
            denoiser.backend(plane).record(new DenoiserFrame(commandBuffer.address(), common, inputs));
            commandBuffer = commands.heap("NRD plane " + plane + " merge");
            nrdComposePipeline.merge(commandBuffer,
                    stablePlaneAddress, frameAddress, plane, nrdSignalEncoding(), exchange);
            VulkanBarriers.memoryBarrier(commandBuffer, stack);
        }
        return reset;
    }

    private static DenoiserCommonSettings nrdCommonSettings(RtFrameInput frame, DenoiserReset frameReset) {
        boolean reset = frameReset == DenoiserReset.CLEAR_AND_RESTART;
        Matrix4f previousWorldToView = nrdPreviousWorldToView(reset, frame.viewRotation(),
                frame.previousViewRotation(), frame.cameraDelta().x(), frame.cameraDelta().y(), frame.cameraDelta().z());
        var previousViewToClip = reset ? frame.projection() : frame.previousProjection();
        float previousJitterX = reset ? frame.jitterX() : frame.previousJitterX();
        float previousJitterY = reset ? frame.jitterY() : frame.previousJitterY();
        // The trace writes old = new + MV in render pixels; NRD consumes the same sign in screen units.
        float inverseWidth = 1.0f / frame.extent().renderWidth();
        float inverseHeight = 1.0f / frame.extent().renderHeight();
        return new DenoiserCommonSettings(
                frame.viewRotation().get(new float[16]), previousWorldToView.get(new float[16]),
                frame.projection().get(new float[16]), previousViewToClip.get(new float[16]),
                frame.jitterX(), frame.jitterY(), previousJitterX, previousJitterY,
                inverseWidth, inverseHeight, 1.0f, NRD_DENOISING_RANGE,
                0.03f, 0.2f, frame.frameTimeMilliseconds(), (int) (frame.number() & 0x7fff_ffffL),
                false,
                frameReset);
    }

    private static DenoiserImage denoiserImage(GpuImage image) {
        return new DenoiserImage(image.image(), image.format(), VK10.VK_IMAGE_LAYOUT_GENERAL,
                new DenoiserExtent(image.width(), image.height()));
    }

    private int nrdSignalEncoding() {
        return denoiser.settings().signalEncoding()
                == DenoiserSignalEncoding.YCOCG_NORMALIZED_HIT_DISTANCE ? 1 : 0;
    }

    static Matrix4f nrdPreviousWorldToView(boolean reset, org.joml.Matrix4fc currentWorldToView,
                                           org.joml.Matrix4fc previousWorldToView,
                                           float currentMinusPreviousX,
                                           float currentMinusPreviousY,
                                           float currentMinusPreviousZ) {
        return reset ? new Matrix4f(currentWorldToView) : new Matrix4f(previousWorldToView)
                .translate(currentMinusPreviousX, currentMinusPreviousY, currentMinusPreviousZ);
    }


    @Override public void close() {
        var compose = nrdComposePipeline;
        nrdComposePipeline = null;
        new ResourceLifetime(denoiser::close, rayReconstruction::destroyAfterDeviceIdle,
                upscaler::destroyAfterDeviceIdle, () -> {
                    if (compose != null) compose.destroy();
                }).close();
    }

    private static VkImageCopy2.Buffer copyRegion(MemoryStack stack, int width, int height) {
        VkImageCopy2.Buffer region = VkImageCopy2.calloc(1, stack);
        region.get(0).sType$Default();
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).extent().set(width, height, 1);
        return region;
    }

    private static void copyImage(VkCommandBuffer commandBuffer, MemoryStack stack,
                                  GpuImage source, GpuImage destination) {
        if (source.width() != destination.width() || source.height() != destination.height()) {
            throw new IllegalArgumentException("copied images must have matching extents");
        }
        VK13.vkCmdCopyImage2(commandBuffer, VkCopyImageInfo2.calloc(stack).sType$Default()
                .srcImage(source.image()).srcImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .dstImage(destination.image()).dstImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .pRegions(copyRegion(stack, source.width(), source.height())));
    }

    private static void blitUpscale(VkCommandBuffer cmd, MemoryStack stack, GpuImage src, GpuImage dst) {
        VkImageBlit2.Buffer region = VkImageBlit2.calloc(1, stack);
        region.get(0).sType$Default();
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                .baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                .baseArrayLayer(0).layerCount(1);
        region.get(0).srcOffsets(1).set(src.width(), src.height(), 1);
        region.get(0).dstOffsets(1).set(dst.width(), dst.height(), 1);
        VK13.vkCmdBlitImage2(cmd, VkBlitImageInfo2.calloc(stack).sType$Default()
                .srcImage(src.image()).srcImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .dstImage(dst.image()).dstImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .filter(VK10.VK_FILTER_LINEAR).pRegions(region));
    }

}
