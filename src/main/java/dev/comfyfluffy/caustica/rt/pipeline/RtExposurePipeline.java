package dev.comfyfluffy.caustica.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.gen.ExposureHistPushData;
import dev.comfyfluffy.caustica.rt.gen.ExposureResolvePushData;

import static dev.comfyfluffy.caustica.rt.RtContext.check;
import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.*;

/** Compute pipelines for histogram auto-exposure over the RT HDR trace output. */
final class RtExposurePipeline {
    private static final String SHADER_DIR = "/caustica/shaders/pipelines/";
    private static final int HISTOGRAM_WORKGROUP_SIZE = 16;
    private static final long MAX_WEIGHTED_SAMPLES = 0xffff_ffffL / 256L;

    private final RtContext ctx;
    private final long histDescriptorSetLayout;
    private final long histDescriptorPool;
    private final long histDescriptorSet;
    private final long histPipelineLayout;
    private final long histPipeline;
    private final long resolveDescriptorSetLayout;
    private final long resolveDescriptorPool;
    private final long resolveDescriptorSet;
    private final long resolvePipelineLayout;
    private final long resolvePipeline;

    private long boundColorView;
    private long boundDepthView;
    private long boundAlbedoView;
    private long boundHistogramBufferForHist;
    private long boundHistogramBufferForResolve;
    private long boundExposureView;
    private long boundStateBuffer;
    private boolean destroyed;

    private RtExposurePipeline(RtContext ctx,
                               long histDescriptorSetLayout, long histDescriptorPool, long histDescriptorSet,
                               long histPipelineLayout, long histPipeline,
                               long resolveDescriptorSetLayout, long resolveDescriptorPool, long resolveDescriptorSet,
                               long resolvePipelineLayout, long resolvePipeline) {
        this.ctx = ctx;
        this.histDescriptorSetLayout = histDescriptorSetLayout;
        this.histDescriptorPool = histDescriptorPool;
        this.histDescriptorSet = histDescriptorSet;
        this.histPipelineLayout = histPipelineLayout;
        this.histPipeline = histPipeline;
        this.resolveDescriptorSetLayout = resolveDescriptorSetLayout;
        this.resolveDescriptorPool = resolveDescriptorPool;
        this.resolveDescriptorSet = resolveDescriptorSet;
        this.resolvePipelineLayout = resolvePipelineLayout;
        this.resolvePipeline = resolvePipeline;
    }

    static RtExposurePipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);

            VkDescriptorSetLayoutBinding.Buffer histBinds = VkDescriptorSetLayoutBinding.calloc(EXPOSURE_HIST_BINDING_COUNT, stack);
            histBinds.get(EXPOSURE_HIST_COLOR).binding(EXPOSURE_HIST_COLOR).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            histBinds.get(EXPOSURE_HIST_BINS).binding(EXPOSURE_HIST_BINS).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            histBinds.get(EXPOSURE_HIST_DEPTH).binding(EXPOSURE_HIST_DEPTH).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            histBinds.get(EXPOSURE_HIST_ALBEDO).binding(EXPOSURE_HIST_ALBEDO).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo histDslci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(histBinds);
            check(VK10.vkCreateDescriptorSetLayout(vk, histDslci, null, p), "vkCreateDescriptorSetLayout(rt exposure hist)");
            long histDsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, histDsl, "exposure histogram descriptor set layout");
            long histPool = createPool(vk, stack, 3, 1, "hist");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, histPool, "exposure histogram descriptor pool");
            long histSet = allocateSet(vk, stack, histPool, histDsl, "hist");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, histSet, "exposure histogram descriptor set");
            long histLayout = createPipelineLayout(vk, stack, histDsl, ExposureHistPushData.BYTE_SIZE, "hist");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, histLayout, "exposure histogram pipeline layout");
            long histModule = loadModule(vk, stack, "exposure_hist/main.comp.spv");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, histModule, "exposure histogram shader module");
            long histPipeline = createComputePipeline(vk, stack, histLayout, histModule, "hist");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, histPipeline, "exposure histogram pipeline");
            VK10.vkDestroyShaderModule(vk, histModule, null);

            VkDescriptorSetLayoutBinding.Buffer resolveBinds = VkDescriptorSetLayoutBinding.calloc(EXPOSURE_RESOLVE_BINDING_COUNT, stack);
            resolveBinds.get(EXPOSURE_RESOLVE_HIST_BINS).binding(EXPOSURE_RESOLVE_HIST_BINS).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            resolveBinds.get(EXPOSURE_RESOLVE_IMAGE).binding(EXPOSURE_RESOLVE_IMAGE).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            resolveBinds.get(EXPOSURE_RESOLVE_STATE).binding(EXPOSURE_RESOLVE_STATE).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo resolveDslci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(resolveBinds);
            check(VK10.vkCreateDescriptorSetLayout(vk, resolveDslci, null, p), "vkCreateDescriptorSetLayout(rt exposure resolve)");
            long resolveDsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, resolveDsl, "exposure resolve descriptor set layout");
            long resolvePool = createPool(vk, stack, 1, 2, "resolve");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, resolvePool, "exposure resolve descriptor pool");
            long resolveSet = allocateSet(vk, stack, resolvePool, resolveDsl, "resolve");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, resolveSet, "exposure resolve descriptor set");
            long resolveLayout = createPipelineLayout(vk, stack, resolveDsl, ExposureResolvePushData.BYTE_SIZE, "resolve");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, resolveLayout, "exposure resolve pipeline layout");
            long resolveModule = loadModule(vk, stack, "exposure_resolve/main.comp.spv");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, resolveModule, "exposure resolve shader module");
            long resolvePipeline = createComputePipeline(vk, stack, resolveLayout, resolveModule, "resolve");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, resolvePipeline, "exposure resolve pipeline");
            VK10.vkDestroyShaderModule(vk, resolveModule, null);

            return new RtExposurePipeline(ctx, histDsl, histPool, histSet, histLayout, histPipeline,
                    resolveDsl, resolvePool, resolveSet, resolveLayout, resolvePipeline);
        }
    }

    void setResources(long colorView, long depthView, long albedoView,
                      RtBuffer histogram, long exposureView, RtBuffer state) {
        if (boundColorView != colorView || boundDepthView != depthView || boundAlbedoView != albedoView
                || boundHistogramBufferForHist != histogram.handle) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorImageInfo.Buffer colorInfo = VkDescriptorImageInfo.calloc(1, stack);
                colorInfo.get(0).imageView(colorView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                VkDescriptorBufferInfo.Buffer histInfo = VkDescriptorBufferInfo.calloc(1, stack);
                histInfo.get(0).buffer(histogram.handle).offset(0).range(histogram.size);
                VkDescriptorImageInfo.Buffer depthInfo = VkDescriptorImageInfo.calloc(1, stack);
                depthInfo.get(0).imageView(depthView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                VkDescriptorImageInfo.Buffer albedoInfo = VkDescriptorImageInfo.calloc(1, stack);
                albedoInfo.get(0).imageView(albedoView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(EXPOSURE_HIST_BINDING_COUNT, stack);
                writes.get(EXPOSURE_HIST_COLOR).sType$Default().dstSet(histDescriptorSet).dstBinding(EXPOSURE_HIST_COLOR)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(colorInfo);
                writes.get(EXPOSURE_HIST_BINS).sType$Default().dstSet(histDescriptorSet).dstBinding(EXPOSURE_HIST_BINS)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(histInfo);
                writes.get(EXPOSURE_HIST_DEPTH).sType$Default().dstSet(histDescriptorSet).dstBinding(EXPOSURE_HIST_DEPTH)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(depthInfo);
                writes.get(EXPOSURE_HIST_ALBEDO).sType$Default().dstSet(histDescriptorSet).dstBinding(EXPOSURE_HIST_ALBEDO)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(albedoInfo);
                VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
            }
            boundColorView = colorView;
            boundDepthView = depthView;
            boundAlbedoView = albedoView;
            boundHistogramBufferForHist = histogram.handle;
        }
        if (boundHistogramBufferForResolve != histogram.handle || boundExposureView != exposureView
                || boundStateBuffer != state.handle) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorBufferInfo.Buffer histInfo = VkDescriptorBufferInfo.calloc(1, stack);
                histInfo.get(0).buffer(histogram.handle).offset(0).range(histogram.size);
                VkDescriptorImageInfo.Buffer exposureInfo = VkDescriptorImageInfo.calloc(1, stack);
                exposureInfo.get(0).imageView(exposureView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                VkDescriptorBufferInfo.Buffer stateInfo = VkDescriptorBufferInfo.calloc(1, stack);
                stateInfo.get(0).buffer(state.handle).offset(0).range(state.size);
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(EXPOSURE_RESOLVE_BINDING_COUNT, stack);
                writes.get(EXPOSURE_RESOLVE_HIST_BINS).sType$Default().dstSet(resolveDescriptorSet).dstBinding(EXPOSURE_RESOLVE_HIST_BINS)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(histInfo);
                writes.get(EXPOSURE_RESOLVE_IMAGE).sType$Default().dstSet(resolveDescriptorSet).dstBinding(EXPOSURE_RESOLVE_IMAGE)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(exposureInfo);
                writes.get(EXPOSURE_RESOLVE_STATE).sType$Default().dstSet(resolveDescriptorSet).dstBinding(EXPOSURE_RESOLVE_STATE)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(stateInfo);
                VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
            }
            boundHistogramBufferForResolve = histogram.handle;
            boundExposureView = exposureView;
            boundStateBuffer = state.handle;
        }
    }

    void dispatchHistogram(org.lwjgl.vulkan.VkCommandBuffer cmd, int width, int height,
                           RtExposure.AutoConfig config) {
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "exposure histogram")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, histPipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, histPipelineLayout, 0,
                    stack.longs(histDescriptorSet), null);
            ByteBuffer push = stack.malloc(ExposureHistPushData.BYTE_SIZE);
            int stride = effectiveStride(width, height, config.stride());
            new ExposureHistPushData(stride, config.centerWeightSigma(), config.centerWeightFloor())
                    .write(push);
            VK10.vkCmdPushConstants(cmd, histPipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, dispatchGroups(width, stride), dispatchGroups(height, stride), 1);
        }
    }

    static int safeStride(int stride) {
        return Math.max(stride, 1);
    }

    static int effectiveStride(int width, int height, int requested) {
        int stride = safeStride(requested);
        int maxDimension = Math.max(Math.max(width, height), 1);
        while (weightedSampleCount(width, height, stride) > MAX_WEIGHTED_SAMPLES
                && stride < maxDimension) {
            int next = stride > maxDimension / 2 ? maxDimension : stride * 2;
            if (next == stride) {
                break;
            }
            stride = next;
        }
        int low = safeStride(requested);
        int high = stride;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (weightedSampleCount(width, height, middle) <= MAX_WEIGHTED_SAMPLES) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }

    private static long weightedSampleCount(int width, int height, int stride) {
        long columns = sampledExtent(width, stride);
        long rows = sampledExtent(height, stride);
        if (columns > Long.MAX_VALUE / rows) {
            return Long.MAX_VALUE;
        }
        return columns * rows;
    }

    static int sampledExtent(int extent, int stride) {
        long positiveExtent = Math.max((long) extent, 1L);
        long safeDivisor = safeStride(stride);
        long samples = (positiveExtent + safeDivisor - 1L) / safeDivisor;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(samples, 1L));
    }

    static int dispatchGroups(int extent, int stride) {
        long samples = sampledExtent(extent, stride);
        long groups = (samples + HISTOGRAM_WORKGROUP_SIZE - 1L) / HISTOGRAM_WORKGROUP_SIZE;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(groups, 1L));
    }

    void dispatchResolve(org.lwjgl.vulkan.VkCommandBuffer cmd, RtExposure.AutoConfig config, float frameTimeSeconds) {
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "exposure resolve")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, resolvePipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, resolvePipelineLayout, 0,
                    stack.longs(resolveDescriptorSet), null);
            ByteBuffer push = stack.malloc(ExposureResolvePushData.BYTE_SIZE);
            RtExposure.ExposureCurve curve = config.curve();
            new ExposureResolvePushData(
                    config.key(), config.minEv(), config.maxEv(), config.adaptDarken(), config.adaptBrighten(),
                    frameTimeSeconds, config.evBias(), config.lowPercentile(), config.highPercentile(),
                    config.skyWeightCap(), curve.scene0(), curve.compensation0(), curve.scene1(),
                    curve.compensation1(), curve.scene2(), curve.compensation2(), curve.scene3(),
                    curve.compensation3(), config.emissiveWeightCap(), config.evOffset(), config.preExposure(),
                    config.resetSequence()
            ).write(push);
            VK10.vkCmdPushConstants(cmd, resolvePipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, 1, 1, 1);
        }
    }

    void destroy() {
        if (destroyed) {
            return;
        }
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, resolvePipeline, null);
        VK10.vkDestroyPipelineLayout(vk, resolvePipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, resolveDescriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, resolveDescriptorSetLayout, null);
        VK10.vkDestroyPipeline(vk, histPipeline, null);
        VK10.vkDestroyPipelineLayout(vk, histPipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, histDescriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, histDescriptorSetLayout, null);
        destroyed = true;
    }

    private static long createPool(VkDevice vk, MemoryStack stack, int storageImages, int storageBuffers, String label) {
        int poolCount = (storageImages > 0 ? 1 : 0) + (storageBuffers > 0 ? 1 : 0);
        VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(poolCount, stack);
        int idx = 0;
        if (storageImages > 0) {
            sizes.get(idx++).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(storageImages);
        }
        if (storageBuffers > 0) {
            sizes.get(idx).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(storageBuffers);
        }
        VkDescriptorPoolCreateInfo ci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes);
        LongBuffer p = stack.mallocLong(1);
        check(VK10.vkCreateDescriptorPool(vk, ci, null, p), "vkCreateDescriptorPool(rt exposure " + label + ")");
        return p.get(0);
    }

    private static long allocateSet(VkDevice vk, MemoryStack stack, long pool, long layout, String label) {
        VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(pool).pSetLayouts(stack.longs(layout));
        LongBuffer pSet = stack.mallocLong(1);
        check(VK10.vkAllocateDescriptorSets(vk, ai, pSet), "vkAllocateDescriptorSets(rt exposure " + label + ")");
        return pSet.get(0);
    }

    private static long createPipelineLayout(VkDevice vk, MemoryStack stack, long setLayout, int pushBytes, String label) {
        VkPipelineLayoutCreateInfo ci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(setLayout));
        if (pushBytes > 0) {
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushBytes);
            ci.pPushConstantRanges(pcr);
        }
        LongBuffer p = stack.mallocLong(1);
        check(VK10.vkCreatePipelineLayout(vk, ci, null, p), "vkCreatePipelineLayout(rt exposure " + label + ")");
        return p.get(0);
    }

    private static long createComputePipeline(VkDevice vk, MemoryStack stack, long layout, long module, String label) {
        VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
        VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
        cpci.get(0).sType$Default().stage(stage).layout(layout);
        LongBuffer pPipeline = stack.mallocLong(1);
        check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, pPipeline),
                "vkCreateComputePipelines(rt exposure " + label + ")");
        return pPipeline.get(0);
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtExposurePipeline.class.getResourceAsStream(SHADER_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER_DIR + name);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER_DIR + name, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo ci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, ci, null, pModule), "vkCreateShaderModule(" + name + ")");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
