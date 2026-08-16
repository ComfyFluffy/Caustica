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
 * Post-RR froxel volumetric lighting: three compute passes over a voxelised view frustum.
 *
 * <p>Injection evaluates the medium and the celestial light's screen-space shadow once per froxel and
 * accumulates the result into the previous frame's grid; integration turns each depth column into
 * prefix-accumulated in-scattering and transmittance; the composite applies the volume to the
 * reconstructed scene at display resolution. Output stays pre-exposed scene-linear ACEScg for the
 * ordinary exposure, bloom, debug, EXR, and SDR/HDR display chain.
 *
 * <p>The two scattering volumes are ping-ponged: the frame's parity picks which one is written and which
 * is sampled as history. Both orientations get their own descriptor set, allocated once, so a frame never
 * rewrites a descriptor set an in-flight frame may still be reading.
 */
public final class RtVolumetricLightPipeline {
    private static final String SHADER_DIR = "/caustica/shaders/pipelines/volumetric_light/";

    private final RtContext ctx;
    private final long descriptorPool;
    private final long injectDescriptorSetLayout;
    private final long[] injectDescriptorSets;
    private final long injectPipelineLayout;
    private final long injectPipeline;
    private final long integrateDescriptorSetLayout;
    private final long[] integrateDescriptorSets;
    private final long integratePipelineLayout;
    private final long integratePipeline;
    private final long compositeDescriptorSetLayout;
    private final long compositeDescriptorSet;
    private final long compositePipelineLayout;
    private final long compositePipeline;
    private final long linearSampler;
    private final long nearestSampler;

    private long boundOutputView;
    private long boundSourceView;
    private long boundDepthView;
    private long boundTransmittanceView;
    private long boundTransmittanceSampler;
    private long boundScatterViewA;
    private long boundScatterViewB;
    private long boundIntegratedView;
    private boolean destroyed;

    private RtVolumetricLightPipeline(RtContext ctx, long descriptorPool,
                                      long injectDescriptorSetLayout, long[] injectDescriptorSets,
                                      long injectPipelineLayout, long injectPipeline,
                                      long integrateDescriptorSetLayout, long[] integrateDescriptorSets,
                                      long integratePipelineLayout, long integratePipeline,
                                      long compositeDescriptorSetLayout, long compositeDescriptorSet,
                                      long compositePipelineLayout, long compositePipeline,
                                      long linearSampler, long nearestSampler) {
        this.ctx = ctx;
        this.descriptorPool = descriptorPool;
        this.injectDescriptorSetLayout = injectDescriptorSetLayout;
        this.injectDescriptorSets = injectDescriptorSets;
        this.injectPipelineLayout = injectPipelineLayout;
        this.injectPipeline = injectPipeline;
        this.integrateDescriptorSetLayout = integrateDescriptorSetLayout;
        this.integrateDescriptorSets = integrateDescriptorSets;
        this.integratePipelineLayout = integratePipelineLayout;
        this.integratePipeline = integratePipeline;
        this.compositeDescriptorSetLayout = compositeDescriptorSetLayout;
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

            VkDescriptorSetLayoutBinding.Buffer injectBindings =
                    VkDescriptorSetLayoutBinding.calloc(VOLUMETRIC_INJECT_BINDING_COUNT, stack);
            storageImage(injectBindings, VOLUMETRIC_INJECT_OUTPUT);
            sampledImage(injectBindings, VOLUMETRIC_INJECT_HISTORY);
            sampledImage(injectBindings, VOLUMETRIC_INJECT_DEPTH);
            sampledImage(injectBindings, VOLUMETRIC_INJECT_TRANSMITTANCE);
            long injectDsl = createSetLayout(ctx, stack, injectBindings, "volumetric inject");

            VkDescriptorSetLayoutBinding.Buffer integrateBindings =
                    VkDescriptorSetLayoutBinding.calloc(VOLUMETRIC_INTEGRATE_BINDING_COUNT, stack);
            storageImage(integrateBindings, VOLUMETRIC_INTEGRATE_OUTPUT);
            storageImage(integrateBindings, VOLUMETRIC_INTEGRATE_SCATTER);
            long integrateDsl = createSetLayout(ctx, stack, integrateBindings, "volumetric integrate");

            VkDescriptorSetLayoutBinding.Buffer compositeBindings =
                    VkDescriptorSetLayoutBinding.calloc(VOLUMETRIC_COMPOSITE_BINDING_COUNT, stack);
            storageImage(compositeBindings, VOLUMETRIC_COMPOSITE_OUTPUT);
            storageImage(compositeBindings, VOLUMETRIC_COMPOSITE_SOURCE);
            sampledImage(compositeBindings, VOLUMETRIC_COMPOSITE_VOLUME);
            sampledImage(compositeBindings, VOLUMETRIC_COMPOSITE_DEPTH);
            long compositeDsl = createSetLayout(ctx, stack, compositeBindings, "volumetric composite");

            // 5 sets: two ping-pong orientations each for inject and integrate, one composite.
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(8);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(8);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(5).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, poolInfo, null, handle),
                    "vkCreateDescriptorPool(rt volumetric)");
            long pool = handle.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, "volumetric descriptor pool");

            long[] injectSets = {
                    allocateSet(vk, stack, pool, injectDsl, "volumetric inject descriptor set 0"),
                    allocateSet(vk, stack, pool, injectDsl, "volumetric inject descriptor set 1")};
            long[] integrateSets = {
                    allocateSet(vk, stack, pool, integrateDsl, "volumetric integrate descriptor set 0"),
                    allocateSet(vk, stack, pool, integrateDsl, "volumetric integrate descriptor set 1")};
            long compositeSet = allocateSet(vk, stack, pool, compositeDsl,
                    "volumetric composite descriptor set");
            for (int i = 0; i < injectSets.length; i++) {
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, injectSets[i],
                        "volumetric inject descriptor set " + i);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, integrateSets[i],
                        "volumetric integrate descriptor set " + i);
            }
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, compositeSet,
                    "volumetric composite descriptor set");

            // All three passes read the same push block, so one range description serves them all.
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0)
                    .size(VolumetricLightPushData.BYTE_SIZE);

            long injectLayout = createPipelineLayout(vk, stack, injectDsl, pushRange, "volumetric inject");
            long injectPipeline = createComputePipeline(ctx, stack, injectLayout,
                    "inject.comp.spv", "volumetric inject");
            long integrateLayout = createPipelineLayout(vk, stack, integrateDsl, pushRange,
                    "volumetric integrate");
            long integratePipeline = createComputePipeline(ctx, stack, integrateLayout,
                    "integrate.comp.spv", "volumetric integrate");
            long compositeLayout = createPipelineLayout(vk, stack, compositeDsl, pushRange,
                    "volumetric composite");
            long compositePipeline = createComputePipeline(ctx, stack, compositeLayout,
                    "composite.comp.spv", "volumetric composite");

            // Trilinear filtering of the froxel volume is what upsamples it: it smooths across screen
            // tiles AND across depth slices, so neither the grid's coarse xy nor its slice boundaries
            // survive into the image. The depth guide stays point-filtered so an opaque silhouette is
            // never interpolated with the sky behind it.
            long linearSampler = createSampler(ctx, stack, VK10.VK_FILTER_LINEAR, "volumetric linear sampler");
            long nearestSampler = createSampler(ctx, stack, VK10.VK_FILTER_NEAREST, "volumetric nearest sampler");

            return new RtVolumetricLightPipeline(ctx, pool,
                    injectDsl, injectSets, injectLayout, injectPipeline,
                    integrateDsl, integrateSets, integrateLayout, integratePipeline,
                    compositeDsl, compositeSet, compositeLayout, compositePipeline,
                    linearSampler, nearestSampler);
        }
    }

    /**
     * Bind this frame's images. {@code scatterViewA}/{@code scatterViewB} are the ping-pong froxel
     * volumes; {@code integratedView} receives the accumulated volume the composite samples.
     */
    public void setImages(long outputView, long sourceView, long depthView,
                          long transmittanceView, long transmittanceSampler,
                          long scatterViewA, long scatterViewB, long integratedView) {
        if (boundOutputView == outputView && boundSourceView == sourceView
                && boundDepthView == depthView && boundTransmittanceView == transmittanceView
                && boundTransmittanceSampler == transmittanceSampler
                && boundScatterViewA == scatterViewA && boundScatterViewB == scatterViewB
                && boundIntegratedView == integratedView) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int writeCount = 2 * VOLUMETRIC_INJECT_BINDING_COUNT + 2 * VOLUMETRIC_INTEGRATE_BINDING_COUNT
                    + VOLUMETRIC_COMPOSITE_BINDING_COUNT;
            VkDescriptorImageInfo.Buffer images = VkDescriptorImageInfo.calloc(writeCount, stack);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(writeCount, stack);
            int i = 0;

            long[] scatterViews = {scatterViewA, scatterViewB};
            for (int parity = 0; parity < 2; parity++) {
                long writeView = scatterViews[parity];
                long historyView = scatterViews[1 - parity];
                i = write(images, writes, i, injectDescriptorSets[parity], VOLUMETRIC_INJECT_OUTPUT,
                        VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, writeView, VK10.VK_NULL_HANDLE);
                i = write(images, writes, i, injectDescriptorSets[parity], VOLUMETRIC_INJECT_HISTORY,
                        VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, historyView, linearSampler);
                i = write(images, writes, i, injectDescriptorSets[parity], VOLUMETRIC_INJECT_DEPTH,
                        VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, depthView, nearestSampler);
                i = write(images, writes, i, injectDescriptorSets[parity], VOLUMETRIC_INJECT_TRANSMITTANCE,
                        VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, transmittanceView, transmittanceSampler);

                i = write(images, writes, i, integrateDescriptorSets[parity], VOLUMETRIC_INTEGRATE_OUTPUT,
                        VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, integratedView, VK10.VK_NULL_HANDLE);
                i = write(images, writes, i, integrateDescriptorSets[parity], VOLUMETRIC_INTEGRATE_SCATTER,
                        VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, writeView, VK10.VK_NULL_HANDLE);
            }

            i = write(images, writes, i, compositeDescriptorSet, VOLUMETRIC_COMPOSITE_OUTPUT,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, outputView, VK10.VK_NULL_HANDLE);
            i = write(images, writes, i, compositeDescriptorSet, VOLUMETRIC_COMPOSITE_SOURCE,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, sourceView, VK10.VK_NULL_HANDLE);
            i = write(images, writes, i, compositeDescriptorSet, VOLUMETRIC_COMPOSITE_VOLUME,
                    VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, integratedView, linearSampler);
            write(images, writes, i, compositeDescriptorSet, VOLUMETRIC_COMPOSITE_DEPTH,
                    VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, depthView, nearestSampler);

            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
        boundOutputView = outputView;
        boundSourceView = sourceView;
        boundDepthView = depthView;
        boundTransmittanceView = transmittanceView;
        boundTransmittanceSampler = transmittanceSampler;
        boundScatterViewA = scatterViewA;
        boundScatterViewB = scatterViewB;
        boundIntegratedView = integratedView;
    }

    /**
     * Record injection, integration, and the display-resolution composite.
     *
     * @param scatter the froxel volume this frame writes; its extent is the grid's dimensions
     * @param parity  which ping-pong orientation {@code scatter} is (0 = A, 1 = B)
     */
    public void dispatch(VkCommandBuffer cmd, RtImage scatter, RtImage output,
                         VolumetricLightPushData pushData, int parity) {
        int slot = parity & 1;
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "volumetric light scattering")) {
            ByteBuffer push = stack.malloc(VolumetricLightPushData.BYTE_SIZE);
            pushData.write(push);

            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, injectPipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    injectPipelineLayout, 0, stack.longs(injectDescriptorSets[slot]), null);
            VK10.vkCmdPushConstants(cmd, injectPipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (scatter.width + 7) / 8, (scatter.height + 7) / 8, scatter.depth);
            com.mojang.blaze3d.vulkan.VulkanCommandEncoder.memoryBarrier(cmd, stack);

            // One thread per screen tile walks the whole depth column, so this dispatch is 2D.
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, integratePipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    integratePipelineLayout, 0, stack.longs(integrateDescriptorSets[slot]), null);
            VK10.vkCmdPushConstants(cmd, integratePipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (scatter.width + 7) / 8, (scatter.height + 7) / 8, 1);
            com.mojang.blaze3d.vulkan.VulkanCommandEncoder.memoryBarrier(cmd, stack);

            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, compositePipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    compositePipelineLayout, 0, stack.longs(compositeDescriptorSet), null);
            VK10.vkCmdPushConstants(cmd, compositePipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (output.width + 7) / 8, (output.height + 7) / 8, 1);
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, injectPipeline, null);
        VK10.vkDestroyPipelineLayout(vk, injectPipelineLayout, null);
        VK10.vkDestroyPipeline(vk, integratePipeline, null);
        VK10.vkDestroyPipelineLayout(vk, integratePipelineLayout, null);
        VK10.vkDestroyPipeline(vk, compositePipeline, null);
        VK10.vkDestroyPipelineLayout(vk, compositePipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, injectDescriptorSetLayout, null);
        VK10.vkDestroyDescriptorSetLayout(vk, integrateDescriptorSetLayout, null);
        VK10.vkDestroyDescriptorSetLayout(vk, compositeDescriptorSetLayout, null);
        VK10.vkDestroySampler(vk, linearSampler, null);
        VK10.vkDestroySampler(vk, nearestSampler, null);
        destroyed = true;
    }

    private static void storageImage(VkDescriptorSetLayoutBinding.Buffer bindings, int binding) {
        bindings.get(binding).binding(binding)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
    }

    private static void sampledImage(VkDescriptorSetLayoutBinding.Buffer bindings, int binding) {
        bindings.get(binding).binding(binding)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
    }

    private static long createSetLayout(RtContext ctx, MemoryStack stack,
                                        VkDescriptorSetLayoutBinding.Buffer bindings, String label) {
        VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default().pBindings(bindings);
        LongBuffer handle = stack.mallocLong(1);
        check(VK10.vkCreateDescriptorSetLayout(ctx.vk(), info, null, handle),
                "vkCreateDescriptorSetLayout(rt " + label + ")");
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, handle.get(0),
                label + " descriptor set layout");
        return handle.get(0);
    }

    private static int write(VkDescriptorImageInfo.Buffer images, VkWriteDescriptorSet.Buffer writes,
                             int index, long set, int binding, int type, long view, long sampler) {
        images.get(index).imageView(view).sampler(sampler).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        writes.get(index).sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1)
                .descriptorType(type)
                .pImageInfo(VkDescriptorImageInfo.create(images.address(index), 1));
        return index + 1;
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
