package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.rt.gen.VolumetricLightPushData;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
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
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import static dev.comfyfluffy.caustica.rt.RtContext.check;
import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.*;

/**
 * Post-RR volumetric light scattering. A half-resolution radial additive-sampling pass derives the exact
 * dominant celestial source from the frame's {@code WorldPush}, masks source radiance with the current
 * primary depth guide, then a full-resolution pass adds the resulting crepuscular rays back to the
 * reconstructed scene. The output remains pre-exposed scene-linear ACEScg for the ordinary exposure,
 * bloom, debug, EXR, and SDR/HDR display chain.
 */
public final class RtVolumetricLightPipeline {
    private static final String SHADER_DIR = "/caustica/shaders/pipelines/volumetric_light/";

    private final RtContext ctx;
    private final long scatterDescriptorSetLayout;
    private final long scatterDescriptorPool;
    private final long scatterDescriptorSet;
    private final long scatterPipelineLayout;
    private final long scatterPipeline;
    private final long compositeDescriptorSetLayout;
    private final long compositeDescriptorPool;
    private final long compositeDescriptorSet;
    private final long compositePipelineLayout;
    private final long compositePipeline;
    private final long linearSampler;
    private final long nearestSampler;

    private long boundOutputView;
    private long boundScatterView;
    private long boundSourceView;
    private long boundDepthView;
    private long boundTransmittanceView;
    private long boundTransmittanceSampler;
    private boolean destroyed;

    private RtVolumetricLightPipeline(RtContext ctx,
                                      long scatterDescriptorSetLayout, long scatterDescriptorPool,
                                      long scatterDescriptorSet, long scatterPipelineLayout,
                                      long scatterPipeline,
                                      long compositeDescriptorSetLayout, long compositeDescriptorPool,
                                      long compositeDescriptorSet, long compositePipelineLayout,
                                      long compositePipeline, long linearSampler, long nearestSampler) {
        this.ctx = ctx;
        this.scatterDescriptorSetLayout = scatterDescriptorSetLayout;
        this.scatterDescriptorPool = scatterDescriptorPool;
        this.scatterDescriptorSet = scatterDescriptorSet;
        this.scatterPipelineLayout = scatterPipelineLayout;
        this.scatterPipeline = scatterPipeline;
        this.compositeDescriptorSetLayout = compositeDescriptorSetLayout;
        this.compositeDescriptorPool = compositeDescriptorPool;
        this.compositeDescriptorSet = compositeDescriptorSet;
        this.compositePipelineLayout = compositePipelineLayout;
        this.compositePipeline = compositePipeline;
        this.linearSampler = linearSampler;
        this.nearestSampler = nearestSampler;
    }

    public static RtVolumetricLightPipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.mallocLong(1);

            VkDescriptorSetLayoutBinding.Buffer scatterBindings =
                    VkDescriptorSetLayoutBinding.calloc(VOLUMETRIC_SCATTER_BINDING_COUNT, stack);
            scatterBindings.get(VOLUMETRIC_SCATTER_OUTPUT).binding(VOLUMETRIC_SCATTER_OUTPUT)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            scatterBindings.get(VOLUMETRIC_SCATTER_SOURCE).binding(VOLUMETRIC_SCATTER_SOURCE)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            scatterBindings.get(VOLUMETRIC_SCATTER_DEPTH).binding(VOLUMETRIC_SCATTER_DEPTH)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            scatterBindings.get(VOLUMETRIC_SCATTER_TRANSMITTANCE).binding(VOLUMETRIC_SCATTER_TRANSMITTANCE)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo scatterLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(scatterBindings);
            check(VK10.vkCreateDescriptorSetLayout(vk, scatterLayoutInfo, null, handle),
                    "vkCreateDescriptorSetLayout(rt volumetric scatter)");
            long scatterDsl = handle.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, scatterDsl,
                    "volumetric scatter descriptor set layout");

            VkDescriptorPoolSize.Buffer scatterPoolSizes = VkDescriptorPoolSize.calloc(2, stack);
            scatterPoolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            scatterPoolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(3);
            VkDescriptorPoolCreateInfo scatterPoolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(1).pPoolSizes(scatterPoolSizes);
            check(VK10.vkCreateDescriptorPool(vk, scatterPoolInfo, null, handle),
                    "vkCreateDescriptorPool(rt volumetric scatter)");
            long scatterPool = handle.get(0);
            long scatterSet = allocateSet(vk, stack, scatterPool, scatterDsl,
                    "volumetric scatter descriptor set");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, scatterPool,
                    "volumetric scatter descriptor pool");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, scatterSet,
                    "volumetric scatter descriptor set");

            VkPushConstantRange.Buffer scatterPushRange = VkPushConstantRange.calloc(1, stack);
            scatterPushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0)
                    .size(VolumetricLightPushData.BYTE_SIZE);
            long scatterPipelineLayout = createPipelineLayout(vk, stack, scatterDsl,
                    scatterPushRange, "volumetric scatter");
            long scatterPipeline = createComputePipeline(ctx, stack, scatterPipelineLayout,
                    "scatter.comp.spv", "volumetric scatter");

            VkDescriptorSetLayoutBinding.Buffer compositeBindings =
                    VkDescriptorSetLayoutBinding.calloc(VOLUMETRIC_COMPOSITE_BINDING_COUNT, stack);
            compositeBindings.get(VOLUMETRIC_COMPOSITE_OUTPUT).binding(VOLUMETRIC_COMPOSITE_OUTPUT)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            compositeBindings.get(VOLUMETRIC_COMPOSITE_SOURCE).binding(VOLUMETRIC_COMPOSITE_SOURCE)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            compositeBindings.get(VOLUMETRIC_COMPOSITE_SCATTER).binding(VOLUMETRIC_COMPOSITE_SCATTER)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo compositeLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(compositeBindings);
            check(VK10.vkCreateDescriptorSetLayout(vk, compositeLayoutInfo, null, handle),
                    "vkCreateDescriptorSetLayout(rt volumetric composite)");
            long compositeDsl = handle.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, compositeDsl,
                    "volumetric composite descriptor set layout");

            VkDescriptorPoolSize.Buffer compositePoolSizes = VkDescriptorPoolSize.calloc(2, stack);
            compositePoolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2);
            compositePoolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            VkDescriptorPoolCreateInfo compositePoolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(1).pPoolSizes(compositePoolSizes);
            check(VK10.vkCreateDescriptorPool(vk, compositePoolInfo, null, handle),
                    "vkCreateDescriptorPool(rt volumetric composite)");
            long compositePool = handle.get(0);
            long compositeSet = allocateSet(vk, stack, compositePool, compositeDsl,
                    "volumetric composite descriptor set");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, compositePool,
                    "volumetric composite descriptor pool");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, compositeSet,
                    "volumetric composite descriptor set");

            long compositePipelineLayout = createPipelineLayout(vk, stack, compositeDsl, null,
                    "volumetric composite");
            long compositePipeline = createComputePipeline(ctx, stack, compositePipelineLayout,
                    "composite.comp.spv", "volumetric composite");
            long linearSampler = createSampler(ctx, stack, VK10.VK_FILTER_LINEAR,
                    "volumetric linear sampler");
            long nearestSampler = createSampler(ctx, stack, VK10.VK_FILTER_NEAREST,
                    "volumetric depth sampler");

            return new RtVolumetricLightPipeline(ctx,
                    scatterDsl, scatterPool, scatterSet, scatterPipelineLayout, scatterPipeline,
                    compositeDsl, compositePool, compositeSet, compositePipelineLayout,
                    compositePipeline, linearSampler, nearestSampler);
        }
    }

    public void setImages(long outputView, long scatterView, long sourceView, long depthView,
                          long transmittanceView, long transmittanceSampler) {
        if (boundOutputView == outputView && boundScatterView == scatterView
                && boundSourceView == sourceView && boundDepthView == depthView
                && boundTransmittanceView == transmittanceView
                && boundTransmittanceSampler == transmittanceSampler) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer images = VkDescriptorImageInfo.calloc(7, stack);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(7, stack);
            int i = 0;

            images.get(i).imageView(scatterView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(i).sType$Default().dstSet(scatterDescriptorSet)
                    .dstBinding(VOLUMETRIC_SCATTER_OUTPUT).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(VkDescriptorImageInfo.create(images.address(i), 1));
            i++;
            images.get(i).imageView(sourceView).sampler(linearSampler)
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(i).sType$Default().dstSet(scatterDescriptorSet)
                    .dstBinding(VOLUMETRIC_SCATTER_SOURCE).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(VkDescriptorImageInfo.create(images.address(i), 1));
            i++;
            images.get(i).imageView(depthView).sampler(nearestSampler)
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(i).sType$Default().dstSet(scatterDescriptorSet)
                    .dstBinding(VOLUMETRIC_SCATTER_DEPTH).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(VkDescriptorImageInfo.create(images.address(i), 1));
            i++;
            images.get(i).imageView(transmittanceView).sampler(transmittanceSampler)
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(i).sType$Default().dstSet(scatterDescriptorSet)
                    .dstBinding(VOLUMETRIC_SCATTER_TRANSMITTANCE).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(VkDescriptorImageInfo.create(images.address(i), 1));
            i++;

            images.get(i).imageView(outputView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(i).sType$Default().dstSet(compositeDescriptorSet)
                    .dstBinding(VOLUMETRIC_COMPOSITE_OUTPUT).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(VkDescriptorImageInfo.create(images.address(i), 1));
            i++;
            images.get(i).imageView(sourceView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(i).sType$Default().dstSet(compositeDescriptorSet)
                    .dstBinding(VOLUMETRIC_COMPOSITE_SOURCE).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(VkDescriptorImageInfo.create(images.address(i), 1));
            i++;
            images.get(i).imageView(scatterView).sampler(linearSampler)
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(i).sType$Default().dstSet(compositeDescriptorSet)
                    .dstBinding(VOLUMETRIC_COMPOSITE_SCATTER).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(VkDescriptorImageInfo.create(images.address(i), 1));

            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
        boundOutputView = outputView;
        boundScatterView = scatterView;
        boundSourceView = sourceView;
        boundDepthView = depthView;
        boundTransmittanceView = transmittanceView;
        boundTransmittanceSampler = transmittanceSampler;
    }

    public void dispatch(VkCommandBuffer cmd, RtImage scatter, RtImage output,
                         long worldPushAddress, int samples, float intensity, float density, float decay) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "volumetric light scattering")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, scatterPipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    scatterPipelineLayout, 0, stack.longs(scatterDescriptorSet), null);
            ByteBuffer push = stack.malloc(VolumetricLightPushData.BYTE_SIZE);
            new VolumetricLightPushData(worldPushAddress, samples, intensity, density, decay).write(push);
            VK10.vkCmdPushConstants(cmd, scatterPipelineLayout,
                    VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (scatter.width + 7) / 8, (scatter.height + 7) / 8, 1);
            com.mojang.blaze3d.vulkan.VulkanCommandEncoder.memoryBarrier(cmd, stack);

            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, compositePipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    compositePipelineLayout, 0, stack.longs(compositeDescriptorSet), null);
            VK10.vkCmdDispatch(cmd, (output.width + 15) / 16, (output.height + 15) / 16, 1);
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, scatterPipeline, null);
        VK10.vkDestroyPipelineLayout(vk, scatterPipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, scatterDescriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, scatterDescriptorSetLayout, null);
        VK10.vkDestroyPipeline(vk, compositePipeline, null);
        VK10.vkDestroyPipelineLayout(vk, compositePipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, compositeDescriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, compositeDescriptorSetLayout, null);
        VK10.vkDestroySampler(vk, linearSampler, null);
        VK10.vkDestroySampler(vk, nearestSampler, null);
        destroyed = true;
    }

    private static long allocateSet(VkDevice vk, MemoryStack stack, long pool, long layout,
                                    String operation) {
        VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(layout));
        LongBuffer set = stack.mallocLong(1);
        check(VK10.vkAllocateDescriptorSets(vk, allocateInfo, set),
                "vkAllocateDescriptorSets(" + operation + ")");
        return set.get(0);
    }

    private static long createPipelineLayout(VkDevice vk, MemoryStack stack, long descriptorSetLayout,
                                             VkPushConstantRange.Buffer pushRange, String operation) {
        VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default().pSetLayouts(stack.longs(descriptorSetLayout));
        if (pushRange != null) {
            info.pPushConstantRanges(pushRange);
        }
        LongBuffer layout = stack.mallocLong(1);
        check(VK10.vkCreatePipelineLayout(vk, info, null, layout),
                "vkCreatePipelineLayout(" + operation + ")");
        return layout.get(0);
    }

    private static long createComputePipeline(RtContext ctx, MemoryStack stack, long layout,
                                              String resource, String label) {
        VkDevice vk = ctx.vk();
        long module = loadModule(vk, stack, resource);
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, module, label + " shader module");
        VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                .module(module).pName(stack.UTF8("main"));
        VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
        info.get(0).sType$Default().stage(stage).layout(layout);
        LongBuffer pipeline = stack.mallocLong(1);
        check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, info, null, pipeline),
                "vkCreateComputePipelines(" + label + ")");
        VK10.vkDestroyShaderModule(vk, module, null);
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline.get(0), label + " pipeline");
        return pipeline.get(0);
    }

    private static long createSampler(RtContext ctx, MemoryStack stack, int filter, String label) {
        VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(filter).minFilter(filter)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .minLod(0.0f).maxLod(0.0f);
        LongBuffer sampler = stack.mallocLong(1);
        check(VK10.vkCreateSampler(ctx.vk(), info, null, sampler), "vkCreateSampler(" + label + ")");
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, sampler.get(0), label);
        return sampler.get(0);
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String resource) {
        byte[] bytes;
        try (InputStream input = RtVolumetricLightPipeline.class.getResourceAsStream(SHADER_DIR + resource)) {
            if (input == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER_DIR + resource);
            }
            bytes = input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER_DIR + resource, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default().pCode(code);
            LongBuffer module = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, info, null, module),
                    "vkCreateShaderModule(" + resource + ")");
            return module.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
