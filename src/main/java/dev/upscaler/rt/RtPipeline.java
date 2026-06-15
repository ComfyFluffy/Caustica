package dev.upscaler.rt;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkRayTracingPipelineCreateInfoKHR;
import org.lwjgl.vulkan.VkRayTracingShaderGroupCreateInfoKHR;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static dev.upscaler.rt.RtContext.check;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.vkCmdTraceRaysKHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.vkCreateRayTracingPipelinesKHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.vkGetRayTracingShaderGroupHandlesKHR;

/**
 * An RT pipeline with an SBT of {raygen + N miss + one triangle hit group} and a descriptor
 * set of {binding 0 = TLAS, binding 1 = storage image}. Built from SPIR-V resources. Update the
 * bindings with {@link #setTlas}/{@link #setStorageImage}, then {@link #trace}. Reusable across
 * the triangle spike and P1 terrain (extend the descriptor layout there as needed). Multiple miss
 * shaders (e.g. a primary sky miss at index 0 plus a shadow/visibility miss at index 1) are
 * supported by passing an array; {@code traceRayEXT}'s {@code missIndex} selects among them.
 */
public final class RtPipeline {
    private static final String SHADER_DIR = "/upscaler/rt/";

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private final RtBuffer sbt;
    private final long sbtStride;
    private final int missCount;
    private final int pushConstantSize;
    private final int pushConstantStages;
    private boolean destroyed;

    private RtPipeline(RtContext ctx, long dsl, long pool, long set, long layout, long pipeline, RtBuffer sbt, long stride, int missCount, int pushConstantSize, int pushConstantStages) {
        this.ctx = ctx;
        this.descriptorSetLayout = dsl;
        this.descriptorPool = pool;
        this.descriptorSet = set;
        this.pipelineLayout = layout;
        this.pipeline = pipeline;
        this.sbt = sbt;
        this.sbtStride = stride;
        this.missCount = missCount;
        this.pushConstantSize = pushConstantSize;
        this.pushConstantStages = pushConstantStages;
    }

    public static RtPipeline create(RtContext ctx, String rgen, String rmiss, String rchit) {
        return create(ctx, rgen, rmiss, rchit, 0);
    }

    public static RtPipeline create(RtContext ctx, String rgen, String rmiss, String rchit, int pushConstantSize) {
        return create(ctx, rgen, rmiss, rchit, pushConstantSize, false);
    }

    public static RtPipeline create(RtContext ctx, String rgen, String rmiss, String rchit, int pushConstantSize, boolean withAtlasSampler) {
        return create(ctx, rgen, new String[]{rmiss}, rchit, null, pushConstantSize, withAtlasSampler);
    }

    public static RtPipeline create(RtContext ctx, String rgen, String[] rmiss, String rchit, int pushConstantSize, boolean withAtlasSampler) {
        return create(ctx, rgen, rmiss, rchit, null, pushConstantSize, withAtlasSampler);
    }

    /**
     * Full form. {@code rahit} (nullable) adds an any-hit shader to the single triangle hit group for
     * alpha-tested cutout geometry — it's an extra pipeline stage but not an extra SBT group, so the
     * record layout is unchanged. When present, the atlas sampler and push constants are also made
     * visible to the any-hit stage.
     */
    public static RtPipeline create(RtContext ctx, String rgen, String[] rmiss, String rchit, String rahit, int pushConstantSize, boolean withAtlasSampler) {
        VkDevice vk = ctx.vk();
        boolean hasAhit = rahit != null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int bindingCount = withAtlasSampler ? 3 : 2;
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(bindingCount, stack);
            binds.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            binds.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            if (withAtlasSampler) {
                int atlasStages = VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR | (hasAhit ? VK_SHADER_STAGE_ANY_HIT_BIT_KHR : 0);
                binds.get(2).binding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1).stageFlags(atlasStages);
            }
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout");
            long dsl = p.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(bindingCount, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            if (withAtlasSampler) {
                poolSizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            }
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool");
            long pool = p.get(0);
            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet), "vkAllocateDescriptorSets");
            long set = pSet.get(0);

            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(dsl));
            // Push constants are visible to raygen + closest-hit (+ any-hit when present). vkCmdPushConstants
            // must be called with exactly these stages, so store them for trace().
            int pcStages = VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | (hasAhit ? VK_SHADER_STAGE_ANY_HIT_BIT_KHR : 0);
            if (pushConstantSize > 0) {
                VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(pcStages)
                        .offset(0).size(pushConstantSize);
                plci.pPushConstantRanges(pcr);
            }
            check(VK10.vkCreatePipelineLayout(vk, plci, null, p), "vkCreatePipelineLayout");
            long layout = p.get(0);

            // Stages: raygen, one miss per rmiss entry, the closest-hit, then (optionally) the any-hit.
            // The closest-hit stage index is 1 + missCount; the any-hit, if any, follows it. Groups are
            // raygen + N miss + ONE triangle hit group (the any-hit shares that group, so it adds a
            // stage but not a group — the SBT record count is unchanged).
            int missCount = rmiss.length;
            int groupCount = 1 + missCount + 1;
            int hitGroupIdx = 1 + missCount;
            int chitStage = 1 + missCount;
            int ahitStage = chitStage + 1;
            int stageCount = groupCount + (hasAhit ? 1 : 0);
            long mGen = loadModule(vk, stack, rgen);
            long[] mMiss = new long[missCount];
            for (int m = 0; m < missCount; m++) {
                mMiss[m] = loadModule(vk, stack, rmiss[m]);
            }
            long mHit = loadModule(vk, stack, rchit);
            long mAhit = hasAhit ? loadModule(vk, stack, rahit) : 0L;
            ByteBuffer entry = stack.UTF8("main");
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(stageCount, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_RAYGEN_BIT_KHR).module(mGen).pName(entry);
            for (int m = 0; m < missCount; m++) {
                stages.get(1 + m).sType$Default().stage(VK_SHADER_STAGE_MISS_BIT_KHR).module(mMiss[m]).pName(entry);
            }
            stages.get(chitStage).sType$Default().stage(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR).module(mHit).pName(entry);
            if (hasAhit) {
                stages.get(ahitStage).sType$Default().stage(VK_SHADER_STAGE_ANY_HIT_BIT_KHR).module(mAhit).pName(entry);
            }

            VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(groupCount, stack);
            groups.get(0).sType$Default().type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                    .generalShader(0).closestHitShader(VK_SHADER_UNUSED_KHR).anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
            for (int m = 0; m < missCount; m++) {
                groups.get(1 + m).sType$Default().type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                        .generalShader(1 + m).closestHitShader(VK_SHADER_UNUSED_KHR).anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
            }
            groups.get(hitGroupIdx).sType$Default().type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                    .generalShader(VK_SHADER_UNUSED_KHR).closestHitShader(chitStage)
                    .anyHitShader(hasAhit ? ahitStage : VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);

            VkRayTracingPipelineCreateInfoKHR.Buffer rtpci = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
            // Depth 1: secondary shadow/visibility rays are issued sequentially from raygen (not
            // nested in closest-hit), so each traceRayEXT is depth 1 — no recursion budget needed.
            rtpci.get(0).sType$Default().pStages(stages).pGroups(groups).maxPipelineRayRecursionDepth(1).layout(layout);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(vkCreateRayTracingPipelinesKHR(vk, VK10.VK_NULL_HANDLE, VK10.VK_NULL_HANDLE, rtpci, null, pPipeline),
                    "vkCreateRayTracingPipelinesKHR");
            long pipeline = pPipeline.get(0);

            VK10.vkDestroyShaderModule(vk, mGen, null);
            for (int m = 0; m < missCount; m++) {
                VK10.vkDestroyShaderModule(vk, mMiss[m], null);
            }
            VK10.vkDestroyShaderModule(vk, mHit, null);
            if (hasAhit) {
                VK10.vkDestroyShaderModule(vk, mAhit, null);
            }

            // SBT: one record per group, each region 64-aligned (stride over-aligned to baseAlignment).
            int handleSize = ctx.shaderGroupHandleSize();
            ByteBuffer handles = stack.malloc(groupCount * handleSize);
            check(vkGetRayTracingShaderGroupHandlesKHR(vk, pipeline, 0, groupCount, handles), "vkGetRayTracingShaderGroupHandlesKHR");
            long stride = align(handleSize, ctx.shaderGroupBaseAlignment());
            RtBuffer sbt = ctx.createBuffer(stride * groupCount, VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR, true);
            for (int g = 0; g < groupCount; g++) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(handles) + (long) g * handleSize, sbt.mapped + g * stride, handleSize);
            }
            return new RtPipeline(ctx, dsl, pool, set, layout, pipeline, sbt, stride, missCount, pushConstantSize, pcStages);
        }
    }

    public void setTlas(long tlas) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSetAccelerationStructureKHR asWrite = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR).pAccelerationStructures(stack.longs(tlas));
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().pNext(asWrite.address()).dstSet(descriptorSet).dstBinding(0)
                    .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
            VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
        }
    }

    public void setStorageImage(long imageView) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack);
            imgInfo.get(0).imageView(imageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().dstSet(descriptorSet).dstBinding(1)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(imgInfo);
            VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
        }
    }

    /** Bind the block atlas (combined image sampler) at binding 2 — only valid if created withAtlasSampler. */
    public void setAtlasSampler(long imageView, long sampler) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
            info.get(0).sampler(sampler).imageView(imageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().dstSet(descriptorSet).dstBinding(2)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
            VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
        }
    }

    public void trace(VkCommandBuffer cmd, int width, int height) {
        trace(cmd, width, height, null);
    }

    /** Record bind (+ optional raygen push constants) + trace into the given command buffer. */
    public void trace(VkCommandBuffer cmd, int width, int height, java.nio.ByteBuffer pushConstants) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipelineLayout, 0, stack.longs(descriptorSet), null);
            if (pushConstants != null && pushConstantSize > 0) {
                VK10.vkCmdPushConstants(cmd, pipelineLayout, pushConstantStages, 0, pushConstants);
            }
            VkStridedDeviceAddressRegionKHR raygen = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(sbt.deviceAddress).stride(sbtStride).size(sbtStride);
            VkStridedDeviceAddressRegionKHR miss = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(sbt.deviceAddress + sbtStride).stride(sbtStride).size((long) missCount * sbtStride);
            VkStridedDeviceAddressRegionKHR hit = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(sbt.deviceAddress + (1L + missCount) * sbtStride).stride(sbtStride).size(sbtStride);
            VkStridedDeviceAddressRegionKHR callable = VkStridedDeviceAddressRegionKHR.calloc(stack);
            vkCmdTraceRaysKHR(cmd, raygen, miss, hit, callable, width, height, 1);
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        VkDevice vk = ctx.vk();
        sbt.destroy();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        destroyed = true;
    }

    private static long align(long v, long a) {
        return (v + a - 1) & ~(a - 1);
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtPipeline.class.getResourceAsStream(SHADER_DIR + name)) {
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
