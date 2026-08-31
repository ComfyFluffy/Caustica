package dev.comfyfluffy.caustica.rt.pipeline;

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
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.gen.DisplayPushData;

import static dev.comfyfluffy.caustica.rt.RtContext.check;
import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.*;

/** Maps the display-res scene-linear ACEScg RT image to sRGB SDR and, when enabled, PQ/BT.2020 HDR. */
public final class RtDisplayPipeline {
    private static final String SHADER_DIR = "/caustica/shaders/pipelines/display/";
    /** Push constants: output/look LUT state plus gamma and HDR peak nits. */
    private static final int PUSH_BYTES = DisplayPushData.BYTE_SIZE;

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private long boundOutputView;
    private long boundRtView;
    private long boundExposureView;
    private long boundHdrView;
    private long boundLutView;
    private long boundLutSampler;
    private long boundHdrLutView;
    private long boundHdrLutSampler;
    private long boundLookLutView;
    private long boundLookLutSampler;
    private long boundBloomView;
    private long boundBloomSampler;
    private boolean destroyed;

    private RtDisplayPipeline(RtContext ctx, long dsl, long pool, long set, long layout, long pipeline) {
        this.ctx = ctx;
        this.descriptorSetLayout = dsl;
        this.descriptorPool = pool;
        this.descriptorSet = set;
        this.pipelineLayout = layout;
        this.pipeline = pipeline;
    }

    public static RtDisplayPipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(DISPLAY_BINDING_COUNT, stack);
            binds.get(DISPLAY_OUTPUT).binding(DISPLAY_OUTPUT).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(DISPLAY_RT_IMAGE).binding(DISPLAY_RT_IMAGE).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(DISPLAY_EXPOSURE).binding(DISPLAY_EXPOSURE).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(DISPLAY_HDR_OUTPUT).binding(DISPLAY_HDR_OUTPUT).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            // Baked ACES 2.0 display-transform LUTs; see RtToneLut.
            binds.get(DISPLAY_SDR_TONE_LUT).binding(DISPLAY_SDR_TONE_LUT).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(DISPLAY_HDR_TONE_LUT).binding(DISPLAY_HDR_TONE_LUT).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(DISPLAY_LOOK_LUT).binding(DISPLAY_LOOK_LUT).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(DISPLAY_BLOOM).binding(DISPLAY_BLOOM).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout(rt display)");
            long dsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, "display descriptor set layout");

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(4);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(4);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(rt display)");
            long pool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, "display descriptor pool");

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet), "vkAllocateDescriptorSets(rt display)");
            long set = pSet.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, set, "display descriptor set");

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(pushRange);
            check(VK10.vkCreatePipelineLayout(vk, plci, null, p), "vkCreatePipelineLayout(rt display)");
            long layout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout, "display pipeline layout");

            long module = loadModule(vk, stack, "main.comp.spv");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, module, "display shader module");
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(layout);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, pPipeline),
                    "vkCreateComputePipelines(rt display)");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pPipeline.get(0), "display compute pipeline");
            VK10.vkDestroyShaderModule(vk, module, null);

            return new RtDisplayPipeline(ctx, dsl, pool, set, layout, pPipeline.get(0));
        }
    }

    public void setImages(long outputImageView, long rtImageView, long exposureImageView, long hdrImageView,
                           long lutView, long lutSampler, long hdrLutView, long hdrLutSampler,
                           long lookLutView, long lookLutSampler, long bloomView, long bloomSampler) {
        if (boundOutputView == outputImageView && boundRtView == rtImageView
                && boundExposureView == exposureImageView && boundHdrView == hdrImageView
                && boundLutView == lutView && boundLutSampler == lutSampler
                && boundHdrLutView == hdrLutView && boundHdrLutSampler == hdrLutSampler
                && boundLookLutView == lookLutView && boundLookLutSampler == lookLutSampler
                && boundBloomView == bloomView && boundBloomSampler == bloomSampler) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer outputInfo = VkDescriptorImageInfo.calloc(1, stack);
            outputInfo.get(0).imageView(outputImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer rtInfo = VkDescriptorImageInfo.calloc(1, stack);
            rtInfo.get(0).imageView(rtImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer exposureInfo = VkDescriptorImageInfo.calloc(1, stack);
            exposureInfo.get(0).imageView(exposureImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer hdrInfo = VkDescriptorImageInfo.calloc(1, stack);
            hdrInfo.get(0).imageView(hdrImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer lutInfo = VkDescriptorImageInfo.calloc(1, stack);
            lutInfo.get(0).imageView(lutView).sampler(lutSampler).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer hdrLutInfo = VkDescriptorImageInfo.calloc(1, stack);
            hdrLutInfo.get(0).imageView(hdrLutView).sampler(hdrLutSampler).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer lookLutInfo = VkDescriptorImageInfo.calloc(1, stack);
            lookLutInfo.get(0).imageView(lookLutView).sampler(lookLutSampler).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer bloomInfo = VkDescriptorImageInfo.calloc(1, stack);
            bloomInfo.get(0).imageView(bloomView).sampler(bloomSampler)
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(DISPLAY_BINDING_COUNT, stack);
            writes.get(DISPLAY_OUTPUT).sType$Default().dstSet(descriptorSet).dstBinding(DISPLAY_OUTPUT)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(outputInfo);
            writes.get(DISPLAY_RT_IMAGE).sType$Default().dstSet(descriptorSet).dstBinding(DISPLAY_RT_IMAGE)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(rtInfo);
            writes.get(DISPLAY_EXPOSURE).sType$Default().dstSet(descriptorSet).dstBinding(DISPLAY_EXPOSURE)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(exposureInfo);
            writes.get(DISPLAY_HDR_OUTPUT).sType$Default().dstSet(descriptorSet).dstBinding(DISPLAY_HDR_OUTPUT)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(hdrInfo);
            writes.get(DISPLAY_SDR_TONE_LUT).sType$Default().dstSet(descriptorSet).dstBinding(DISPLAY_SDR_TONE_LUT)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(lutInfo);
            writes.get(DISPLAY_HDR_TONE_LUT).sType$Default().dstSet(descriptorSet).dstBinding(DISPLAY_HDR_TONE_LUT)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(hdrLutInfo);
            writes.get(DISPLAY_LOOK_LUT).sType$Default().dstSet(descriptorSet).dstBinding(DISPLAY_LOOK_LUT)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(lookLutInfo);
            writes.get(DISPLAY_BLOOM).sType$Default().dstSet(descriptorSet).dstBinding(DISPLAY_BLOOM)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(bloomInfo);
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
        boundOutputView = outputImageView;
        boundRtView = rtImageView;
        boundExposureView = exposureImageView;
        boundHdrView = hdrImageView;
        boundLutView = lutView;
        boundLutSampler = lutSampler;
        boundHdrLutView = hdrLutView;
        boundHdrLutSampler = hdrLutSampler;
        boundLookLutView = lookLutView;
        boundLookLutSampler = lookLutSampler;
        boundBloomView = bloomView;
        boundBloomSampler = bloomSampler;
    }

    /**
     * Run the display mapping through the baked ACES 2.0 LUTs: SDR
     * (binding 0) always writes; the PQ-encoded HDR image (binding 3) also writes when
     * {@code hdrEnabled}. The HDR LUT is baked for a fixed mastering-nits peak (see
     * {@code CausticaConfig.Rt.Hdr.PEAK_NITS_STEPS}), selected host-side by which LUT resource is bound.
     */
    public void dispatch(VkCommandBuffer cmd, int width, int height, boolean hdrEnabled, int lutSize,
                         float gamma, float hdrPeakNits, boolean lookEnabled, int lookLutSize,
                         float bloomStrength, boolean gradeEnabled, float saturation, float contrast,
                         float gain, float highlightSaturation, float highlightGain,
                         float highlightsMin, float sharpness) {
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "display compute")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSet), null);
            ByteBuffer push = stack.malloc(DisplayPushData.BYTE_SIZE);
            new DisplayPushData(hdrEnabled ? 1 : 0, (float) lutSize, gamma, hdrPeakNits,
                    lookEnabled ? 1 : 0, (float) lookLutSize, bloomStrength,
                    gradeEnabled ? 1 : 0, saturation, contrast, gain,
                    highlightSaturation, highlightGain, highlightsMin, sharpness).write(push);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (width + 15) / 16, (height + 15) / 16, 1);
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        destroyed = true;
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtDisplayPipeline.class.getResourceAsStream(SHADER_DIR + name)) {
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
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, pModule), "vkCreateShaderModule(" + name + ")");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
