package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.renderer.presentation.gen.ExposureHistPushData;
import dev.comfyfluffy.caustica.renderer.presentation.gen.ExposureResolvePushData;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

/** Descriptor-heap shader objects for histogram auto exposure. */
final class RtExposurePipeline {
    private static final String ROOT = "/caustica/shaders/pipelines/";
    private final VulkanDeviceContext context;
    private final ShaderObjectCompute histogramShader;
    private final ShaderObjectCompute resolveShader;

    private RtExposurePipeline(VulkanDeviceContext context, ShaderObjectCompute histogramShader,
                               ShaderObjectCompute resolveShader) {
        this.context = context;
        this.histogramShader = histogramShader;
        this.resolveShader = resolveShader;
    }

    static RtExposurePipeline create(VulkanDeviceContext context) {
        ShaderObjectCompute histogram = ShaderObjectCompute.load(context, RtExposurePipeline.class,
                ROOT + "exposure_hist/main.comp.spv");
        try {
            return new RtExposurePipeline(context, histogram,
                    ShaderObjectCompute.load(context, RtExposurePipeline.class,
                            ROOT + "exposure_resolve/main.comp.spv"));
        } catch (RuntimeException | Error failure) {
            histogram.close();
            throw failure;
        }
    }

    void dispatchHistogram(VkCommandBuffer command, GpuImage color, GpuImage depth, GpuImage albedo,
                           GpuBuffer histogram, RtExposure.AutoConfig config) {
        try (MemoryStack stack = MemoryStack.stackPush();
             var ignored = RtDebugLabels.scope(context, command, "exposure histogram")) {
            ByteBuffer push = stack.malloc(ExposureHistPushData.BYTE_SIZE);
            new ExposureHistPushData(storage(color), storage(depth), storage(albedo),
                    histogram.deviceAddress().value(), config.stride(), config.centerWeightSigma(),
                    config.centerWeightFloor()).write(push);
            int sampleWidth = Math.ceilDiv(color.width(), config.stride());
            int sampleHeight = Math.ceilDiv(color.height(), config.stride());
            histogramShader.dispatch(command, push, Math.ceilDiv(sampleWidth, 16),
                    Math.ceilDiv(sampleHeight, 16), 1);
        }
    }

    void dispatchResolve(VkCommandBuffer command, GpuBuffer histogram, GpuImage exposure,
                         GpuBuffer state, RtExposure.AutoConfig config, float frameTimeSeconds) {
        try (MemoryStack stack = MemoryStack.stackPush();
             var ignored = RtDebugLabels.scope(context, command, "exposure resolve")) {
            // Four ordered scene-EV/compensation-EV knots shape the metered exposure response.
            RtExposure.Response response = config.response();
            ByteBuffer push = stack.malloc(ExposureResolvePushData.BYTE_SIZE);
            new ExposureResolvePushData(histogram.deviceAddress().value(), state.deviceAddress().value(), storage(exposure),
                    config.key(), config.minEv(), config.maxEv(), config.adaptDarken(), config.adaptBrighten(),
                    frameTimeSeconds, config.evBias(), config.lowPercentile(), config.highPercentile(),
                    config.environmentWeightCap(),
                    response.sceneEv(0), response.compensationEv(0),
                    response.sceneEv(1), response.compensationEv(1),
                    response.sceneEv(2), response.compensationEv(2),
                    response.sceneEv(3), response.compensationEv(3),
                    config.emissiveWeightCap(), config.evOffset(), config.preExposure(),
                    config.resetSequence()).write(push);
            resolveShader.dispatch(command, push, 1, 1, 1);
        }
    }

    private static int storage(GpuImage image) {
        return image.descriptor(GpuImageDescriptorKind.STORAGE).index().value();
    }

    void destroy() {
        resolveShader.close();
        histogramShader.close();
    }
}
