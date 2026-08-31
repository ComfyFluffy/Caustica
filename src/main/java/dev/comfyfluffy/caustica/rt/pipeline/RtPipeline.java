package dev.comfyfluffy.caustica.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBindingFlagsCreateInfo;
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

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;

import static dev.comfyfluffy.caustica.rt.RtContext.check;
import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.*;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_PIPELINE_CREATE_RAY_TRACING_OPACITY_MICROMAP_BIT_EXT;
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
 * An RT pipeline with an SBT of {raygen + N miss + triangle hit groups} and a descriptor
 * set of {binding 0 = TLAS, binding 1 = storage image}. Built from SPIR-V resources. Update the
 * bindings with {@link #setTlas}/{@link #setStorageImage}, then {@link #trace}. Reusable across
 * the triangle spike and terrain (extend the descriptor layout there as needed). Multiple miss
 * shaders (e.g. a primary sky miss at index 0 plus a shadow/visibility miss at index 1) are
 * supported by passing an array; {@code traceRayEXT}'s {@code missIndex} selects among them.
 */
public final class RtPipeline {
    private static final String SHADER_DIR = "/caustica/shaders/pipelines/world/";
    // A ring of descriptor sets: setTlas waits for the selected slot's exact prior graphics use before
    // rewriting it. Ring depth is only a performance choice that avoids routine host waits.
    private static final int RING = 6;

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long[] descriptorSets;
    private final RtGpuExecutor.TrackedGraphicsUse[] descriptorSetUses;
    private int currentSet;
    private final long pipelineLayout;
    private final long pipeline;
    private final RtBuffer sbt;
    private final long sbtStride;
    private final int raygenCount;
    private final int missCount;
    private final int hitGroupCount;
    private final int pushConstantSize;
    private final int pushConstantStages;
    // Optional second descriptor set (set 1) holding entity albedo and canonical material-page arrays.
    // Only entity albedo is update-after-bind: its RenderType→slot registry is append-only. Material
    // pages are populated once at the resource-epoch boundary. 0 when created without bindless textures.
    private final long bindlessLayout;
    private final long bindlessPool;
    private final long bindlessSet;
    private boolean destroyed;

    private RtPipeline(RtContext ctx, long dsl, long pool, long[] sets, long layout, long pipeline,
                       RtBuffer sbt, long stride, int raygenCount, int missCount, int hitGroupCount,
                       int pushConstantSize, int pushConstantStages, long bindlessLayout,
                       long bindlessPool, long bindlessSet) {
        this.ctx = ctx;
        this.descriptorSetLayout = dsl;
        this.descriptorPool = pool;
        this.descriptorSets = sets;
        this.descriptorSetUses = new RtGpuExecutor.TrackedGraphicsUse[sets.length];
        for (int i = 0; i < descriptorSetUses.length; i++) {
            descriptorSetUses[i] = new RtGpuExecutor.TrackedGraphicsUse();
        }
        this.currentSet = 0;
        this.pipelineLayout = layout;
        this.pipeline = pipeline;
        this.sbt = sbt;
        this.sbtStride = stride;
        this.raygenCount = raygenCount;
        this.missCount = missCount;
        this.hitGroupCount = hitGroupCount;
        this.pushConstantSize = pushConstantSize;
        this.pushConstantStages = pushConstantStages;
        this.bindlessLayout = bindlessLayout;
        this.bindlessPool = bindlessPool;
        this.bindlessSet = bindlessSet;
    }

    /**
     * Builds the RT pipeline. {@code rahit} (nullable) adds any-hit-capable triangle hit records. With the
     * world pipeline, the hit SBT region is laid out to match {@link RtAccel}'s terrain bucket/ray-type
     * constants: radiance records first, shadow records second, then entity records. The fixed world
     * descriptor layout is declared in {@code shaders/rt_bindings.slang}.
     *
     * <p>{@code rgen} may hold several raygen shaders. They share this pipeline's descriptor set, miss
     * table and hit table; {@link #trace(VkCommandBuffer, int, int, ByteBuffer, int)} picks one per
     * dispatch by index.
     */
    public static RtPipeline create(RtContext ctx, String[] rgen, String[] rmiss, String rchit,
                                    String rahit, int pushConstantSize, int bindlessTextures) {
        VkDevice vk = ctx.vk();
        boolean hasAhit = rahit != null;
        String label = "world RT pipeline";
        if (bindlessTextures > 0) {
            long requiredCombinedSamplers = Math.addExact(
                    Math.multiplyExact((long) bindlessTextures, WORLD_BINDLESS_COUNT), 1L);
            long deviceLimit = ctx.updateAfterBindCombinedImageSamplerLimit();
            if (requiredCombinedSamplers > deviceLimit) {
                throw new UnsupportedOperationException("Configured bindless texture capacity " + bindlessTextures
                        + " requires " + requiredCombinedSamplers + " combined image samplers, device limit is "
                        + deviceLimit);
            }
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(
                    WORLD_SET_BINDING_COUNT, stack);
            binds.get(WORLD_TLAS).binding(WORLD_TLAS).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            binds.get(WORLD_OUTPUT).binding(WORLD_OUTPUT).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            int atlasStages = VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | (hasAhit ? VK_SHADER_STAGE_ANY_HIT_BIT_KHR : 0);
            binds.get(WORLD_BLOCK_ALBEDO).binding(WORLD_BLOCK_ALBEDO)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(atlasStages);
            for (int binding = WORLD_G_NORMAL; binding <= WORLD_G_SPEC_MOTION; binding++) {
                binds.get(binding).binding(binding).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            }
            // ReSTIR reservoirs. Raygen only — the reservoirs are built and consumed there; no hit or
            // miss stage touches them, and narrowing the stage flags keeps the driver from having to
            // make them visible to shader stages that never read them.
            for (int binding = WORLD_RESERVOIR_A; binding <= WORLD_RESERVOIR_B; binding++) {
                binds.get(binding).binding(binding).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            }
            // Cloud noise: a sampled 3D texture, read by the miss shader (view march) and the raygen
            // (shadow march), so both stages need visibility.
            binds.get(WORLD_CLOUD_NOISE).binding(WORLD_CLOUD_NOISE)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_MISS_BIT_KHR);
            binds.get(WORLD_CELESTIALS).binding(WORLD_CELESTIALS)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_MISS_BIT_KHR);
            binds.get(WORLD_SKY_VIEW).binding(WORLD_SKY_VIEW)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_MISS_BIT_KHR);
            binds.get(WORLD_TRANSMITTANCE).binding(WORLD_TRANSMITTANCE)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_MISS_BIT_KHR | VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout");
            long dsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, label + " descriptor set layout");

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(RING);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(RING * WORLD_SET_STORAGE_IMAGE_COUNT);
            poolSizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(RING * WORLD_SET_SAMPLER_COUNT);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(RING).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool");
            long pool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, label + " descriptor pool");
            LongBuffer layouts = stack.mallocLong(RING);
            for (int i = 0; i < RING; i++) {
                layouts.put(i, dsl);
            }
            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(layouts);
            LongBuffer pSet = stack.mallocLong(RING);
            check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet), "vkAllocateDescriptorSets");
            long[] sets = new long[RING];
            pSet.get(sets);
            for (int i = 0; i < RING; i++) {
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, sets[i], label + " descriptor set " + i);
            }

            // Optional bindless set (set 1): entity albedo plus canonical material page arrays.
            long bindlessLayout = 0L, bindlessPool = 0L, bindlessSet = 0L;
            if (bindlessTextures > 0) {
                // Entity albedo and canonical material pages have independent index spaces. All arrays
                // use the configured capacity here; material pages occupy compact indices from zero.
                int nb = WORLD_BINDLESS_COUNT;
                VkDescriptorSetLayoutBinding.Buffer bl = VkDescriptorSetLayoutBinding.calloc(nb, stack);
                java.nio.IntBuffer bindFlags = stack.mallocInt(nb);
                for (int b = 0; b < nb; b++) {
                    int stages = VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
                    if (b == WORLD_ENTITY_ALBEDO && hasAhit) stages |= VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
                    bl.get(b).binding(b).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .descriptorCount(bindlessTextures).stageFlags(stages);
                    int flags = VK12.VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT;
                    if (b == WORLD_ENTITY_ALBEDO) flags |= VK12.VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT;
                    bindFlags.put(b, flags);
                }
                VkDescriptorSetLayoutBindingFlagsCreateInfo bf = VkDescriptorSetLayoutBindingFlagsCreateInfo.calloc(stack).sType$Default()
                        .pBindingFlags(bindFlags);
                VkDescriptorSetLayoutCreateInfo bdslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                        .pNext(bf.address()).flags(VK12.VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT).pBindings(bl);
                check(VK10.vkCreateDescriptorSetLayout(vk, bdslci, null, p), "vkCreateDescriptorSetLayout(bindless)");
                bindlessLayout = p.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, bindlessLayout, label + " bindless descriptor set layout");
                VkDescriptorPoolSize.Buffer bps = VkDescriptorPoolSize.calloc(1, stack);
                bps.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(bindlessTextures * nb);
                VkDescriptorPoolCreateInfo bdpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                        .flags(VK12.VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT).maxSets(1).pPoolSizes(bps);
                check(VK10.vkCreateDescriptorPool(vk, bdpci, null, p), "vkCreateDescriptorPool(bindless)");
                bindlessPool = p.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, bindlessPool, label + " bindless descriptor pool");
                VkDescriptorSetAllocateInfo bdsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(bindlessPool).pSetLayouts(stack.longs(bindlessLayout));
                LongBuffer bpSet = stack.mallocLong(1);
                check(VK10.vkAllocateDescriptorSets(vk, bdsai, bpSet), "vkAllocateDescriptorSets(bindless)");
                bindlessSet = bpSet.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, bindlessSet, label + " bindless descriptor set");
            }

            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(bindlessTextures > 0 ? stack.longs(dsl, bindlessLayout) : stack.longs(dsl));
            // Push constants are visible to raygen + closest-hit + miss (+ any-hit when present).
            // vkCmdPushConstants must be called with exactly these stages, so store them for trace().
            // Miss reads pc for the dynamic sky; widening the stage mask is the whole cost — no gotcha #3.
            int pcStages = VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | VK_SHADER_STAGE_MISS_BIT_KHR
                    | (hasAhit ? VK_SHADER_STAGE_ANY_HIT_BIT_KHR : 0);
            if (pushConstantSize > 0) {
                VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(pcStages)
                        .offset(0).size(pushConstantSize);
                plci.pPushConstantRanges(pcr);
            }
            check(VK10.vkCreatePipelineLayout(vk, plci, null, p), "vkCreatePipelineLayout");
            long layout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout, label + " pipeline layout");

            // Stages: one per rgen entry, one miss per rmiss entry, the closest-hit, then (optionally)
            // the any-hit. Groups are N raygen + M miss + the hit records selected by traceRayEXT's SBT
            // offset/stride. Multiple raygens share one pipeline and are selected at dispatch by pointing
            // the raygen SBT region at a different record — that is how the primary/guide pass and the
            // indirect pass coexist without duplicating the hit and miss tables.
            int raygenCount = rgen.length;
            int missCount = rmiss.length;
            int hitGroupCount = hasAhit ? RtAccel.SBT_HIT_GROUP_COUNT : 1;
            int groupCount = raygenCount + missCount + hitGroupCount;
            int hitGroupIdx = raygenCount + missCount;
            int chitStage = raygenCount + missCount;
            int ahitStage = chitStage + 1;
            int stageCount = raygenCount + missCount + 1 + (hasAhit ? 1 : 0);
            long[] mGen = new long[raygenCount];
            for (int g = 0; g < raygenCount; g++) {
                mGen[g] = loadModule(vk, stack, rgen[g]);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, mGen[g], label + " " + rgen[g]);
            }
            long[] mMiss = new long[missCount];
            for (int m = 0; m < missCount; m++) {
                mMiss[m] = loadModule(vk, stack, rmiss[m]);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, mMiss[m], label + " " + rmiss[m]);
            }
            long mHit = loadModule(vk, stack, rchit);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, mHit, label + " " + rchit);
            long mAhit = hasAhit ? loadModule(vk, stack, rahit) : 0L;
            if (hasAhit) {
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, mAhit, label + " " + rahit);
            }
            ByteBuffer entry = stack.UTF8("main");
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(stageCount, stack);
            for (int g = 0; g < raygenCount; g++) {
                stages.get(g).sType$Default().stage(VK_SHADER_STAGE_RAYGEN_BIT_KHR).module(mGen[g]).pName(entry);
            }
            for (int m = 0; m < missCount; m++) {
                stages.get(raygenCount + m).sType$Default().stage(VK_SHADER_STAGE_MISS_BIT_KHR).module(mMiss[m]).pName(entry);
            }
            stages.get(chitStage).sType$Default().stage(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR).module(mHit).pName(entry);
            if (hasAhit) {
                stages.get(ahitStage).sType$Default().stage(VK_SHADER_STAGE_ANY_HIT_BIT_KHR).module(mAhit).pName(entry);
            }

            VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(groupCount, stack);
            for (int g = 0; g < raygenCount; g++) {
                groups.get(g).sType$Default().type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                        .generalShader(g).closestHitShader(VK_SHADER_UNUSED_KHR).anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
            }
            for (int m = 0; m < missCount; m++) {
                groups.get(raygenCount + m).sType$Default().type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                        .generalShader(raygenCount + m).closestHitShader(VK_SHADER_UNUSED_KHR).anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
            }
            for (int h = 0; h < hitGroupCount; h++) {
                groups.get(hitGroupIdx + h).sType$Default().type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                        .generalShader(VK_SHADER_UNUSED_KHR).closestHitShader(chitStage)
                        .anyHitShader(hasAhit && hitGroupUsesAnyHit(h) ? ahitStage : VK_SHADER_UNUSED_KHR)
                        .intersectionShader(VK_SHADER_UNUSED_KHR);
            }

            VkRayTracingPipelineCreateInfoKHR.Buffer rtpci = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
            // Depth 1: secondary shadow/visibility rays are issued sequentially from raygen (not
            // nested in closest-hit), so each traceRayEXT is depth 1 — no recursion budget needed.
            rtpci.get(0).sType$Default().pStages(stages).pGroups(groups).maxPipelineRayRecursionDepth(1).layout(layout);
            if (RtDeviceBringup.ommEnabled()) {
                rtpci.get(0).flags(VK_PIPELINE_CREATE_RAY_TRACING_OPACITY_MICROMAP_BIT_EXT);
            }
            LongBuffer pPipeline = stack.mallocLong(1);
            check(vkCreateRayTracingPipelinesKHR(vk, VK10.VK_NULL_HANDLE, VK10.VK_NULL_HANDLE, rtpci, null, pPipeline),
                    "vkCreateRayTracingPipelinesKHR");
            long pipeline = pPipeline.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, label);

            for (int g = 0; g < raygenCount; g++) {
                VK10.vkDestroyShaderModule(vk, mGen[g], null);
            }
            for (int m = 0; m < missCount; m++) {
                VK10.vkDestroyShaderModule(vk, mMiss[m], null);
            }
            VK10.vkDestroyShaderModule(vk, mHit, null);
            if (hasAhit) {
                VK10.vkDestroyShaderModule(vk, mAhit, null);
            }

            // SBT: one record per group. Over-align the stride so every region start is base-aligned and
            // every individual record satisfies shaderGroupHandleAlignment.
            int handleSize = ctx.shaderGroupHandleSize();
            ByteBuffer handles = stack.malloc(groupCount * handleSize);
            check(vkGetRayTracingShaderGroupHandlesKHR(vk, pipeline, 0, groupCount, handles), "vkGetRayTracingShaderGroupHandlesKHR");
            long stride = align(handleSize,
                    Math.max(ctx.shaderGroupBaseAlignment(), ctx.shaderGroupHandleAlignment()));
            if (stride > Integer.toUnsignedLong(ctx.maxShaderGroupStride())) {
                throw new UnsupportedOperationException("SBT stride " + stride + " exceeds maxShaderGroupStride "
                        + Integer.toUnsignedLong(ctx.maxShaderGroupStride()));
            }
            RtBuffer sbt = ctx.createAlignedBuffer(stride * groupCount,
                    VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR, true,
                    label + " shader binding table", ctx.shaderGroupBaseAlignment());
            for (int g = 0; g < groupCount; g++) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(handles) + (long) g * handleSize, sbt.mapped + g * stride, handleSize);
            }
            sbt.flush();
            return new RtPipeline(ctx, dsl, pool, sets, layout, pipeline, sbt, stride,
                    raygenCount, missCount, hitGroupCount, pushConstantSize, pcStages,
                    bindlessLayout, bindlessPool, bindlessSet);
        }
    }

    private static boolean hitGroupUsesAnyHit(int relativeHitGroup) {
        if (relativeHitGroup < RtAccel.SBT_ENTITY_OFFSET) {
            int rayType = relativeHitGroup / RtAccel.TERRAIN_BUCKETS;
            int bucket = relativeHitGroup % RtAccel.TERRAIN_BUCKETS;
            if (rayType == RtAccel.SBT_RAY_RADIANCE) {
                return bucket == RtAccel.BUCKET_CUTOUT;
            }
            return bucket != RtAccel.BUCKET_SOLID;
        }
        int entityBucket = (relativeHitGroup - RtAccel.SBT_ENTITY_OFFSET) % RtAccel.TERRAIN_BUCKETS;
        return entityBucket == RtAccel.ENTITY_BUCKET_ANY_HIT;
    }

    /** Bind a new TLAS after the selected descriptor slot's exact prior graphics use completes. */
    public void setTlas(long tlas, RtGpuExecutor.GraphicsUse graphicsUse,
                        RtGpuExecutor.GraphicsUseWaiter graphicsUseWaiter) {
        currentSet = (currentSet + 1) % RING;
        RtGpuExecutor.TrackedGraphicsUse slotUse = descriptorSetUses[currentSet];
        graphicsUseWaiter.await(slotUse);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSetAccelerationStructureKHR asWrite = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR).pAccelerationStructures(stack.longs(tlas));
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().pNext(asWrite.address()).dstSet(descriptorSets[currentSet])
                    .dstBinding(WORLD_TLAS)
                    .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
            VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
        }
        slotUse.mark(graphicsUse);
    }

    /** Write the storage image into every ring slot (set once at init / on resize, when idle). */
    public void setStorageImage(long imageView) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack);
            imgInfo.get(0).imageView(imageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(RING, stack);
            for (int i = 0; i < RING; i++) {
                write.get(i).sType$Default().dstSet(descriptorSets[i]).dstBinding(WORLD_OUTPUT)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(imgInfo);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
        }
    }

    /** Write one DLSS-RR guide image into its canonical world binding across every ring slot. */
    /**
     * Binds the two ReSTIR reservoir images. Written once at creation and again on resize, never
     * per-frame: the descriptor sets are ring-buffered across frames in flight, so rewriting them each
     * frame would mutate a set an earlier frame is still reading. The two images swap roles through a
     * push constant instead — see {@code worldPush.restir.y}.
     */
    public void setReservoirImages(long viewA, long viewB) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer infoA = VkDescriptorImageInfo.calloc(1, stack);
            infoA.get(0).imageView(viewA).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer infoB = VkDescriptorImageInfo.calloc(1, stack);
            infoB.get(0).imageView(viewB).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(RING * 2, stack);
            for (int i = 0; i < RING; i++) {
                write.get(i * 2).sType$Default().dstSet(descriptorSets[i]).dstBinding(WORLD_RESERVOIR_A)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(infoA);
                write.get(i * 2 + 1).sType$Default().dstSet(descriptorSets[i]).dstBinding(WORLD_RESERVOIR_B)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(infoB);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
        }
    }

    public void setExtraStorageImage(int slot, long imageView) {
        if (slot < 0 || slot >= WORLD_GUIDE_COUNT) {
            throw new IllegalArgumentException("Guide slot out of range: " + slot);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack);
            imgInfo.get(0).imageView(imageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(RING, stack);
            for (int i = 0; i < RING; i++) {
                write.get(i).sType$Default().dstSet(descriptorSets[i]).dstBinding(WORLD_G_NORMAL + slot)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(imgInfo);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
        }
    }

    /** Bind the block albedo atlas into every ring slot. */
    public void setBlockAlbedoAtlas(long imageView, long sampler) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
            info.get(0).sampler(sampler).imageView(imageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(RING, stack);
            for (int i = 0; i < RING; i++) {
                write.get(i).sType$Default().dstSet(descriptorSets[i]).dstBinding(WORLD_BLOCK_ALBEDO)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
        }
    }

    /** Bind the vanilla celestials atlas (sun + moon phases), sampled by world.rmiss for the discs. */
    public void setSkyAtlas(long imageView, long sampler) {
        writeAtlasBinding(WORLD_CELESTIALS, imageView, sampler);
    }

    /**
     * Bind the precomputed cloud shape field. Written once when the texture is created; it is
     * immutable for the session, so there is no per-frame descriptor traffic here.
     */
    public void setCloudNoise(long imageView, long sampler) {
        writeAtlasBinding(WORLD_CLOUD_NOISE, imageView, sampler);
    }

    public boolean hasSkyAtlas() {
        return true;
    }

    /** Bind this frame's atmosphere LUTs (see {@link RtSkyLut}); both share the LUT's own sampler. */
    public void setSkyLuts(long skyViewImageView, long transmittanceImageView, long sampler) {
        writeAtlasBinding(WORLD_SKY_VIEW, skyViewImageView, sampler);
        writeAtlasBinding(WORLD_TRANSMITTANCE, transmittanceImageView, sampler);
    }

    private void writeAtlasBinding(int binding, long imageView, long sampler) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
            info.get(0).sampler(sampler).imageView(imageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(RING, stack);
            for (int i = 0; i < RING; i++) {
                write.get(i).sType$Default().dstSet(descriptorSets[i]).dstBinding(binding)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
        }
    }

    /** Append or initialize one entity-albedo slot. Existing slots never change while frames are in flight. */
    public void setEntityAlbedoTexture(int slot, long imageView, long sampler) {
        setBindlessTexture(WORLD_ENTITY_ALBEDO, slot, imageView, sampler);
    }

    /** Bind one compact canonical page bundle at a resource-epoch boundary. */
    public void setMaterialPage(int page, long surface0View, long normalAoView, long surface1View,
                                long sampler) {
        setBindlessTexture(WORLD_MATERIAL_SURFACE0, page, surface0View, sampler);
        setBindlessTexture(WORLD_MATERIAL_NORMAL_AO, page, normalAoView, sampler);
        setBindlessTexture(WORLD_MATERIAL_SURFACE1, page, surface1View, sampler);
    }

    private void setBindlessTexture(int binding, int slot, long imageView, long sampler) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
            info.get(0).sampler(sampler).imageView(imageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().dstSet(bindlessSet).dstBinding(binding).dstArrayElement(slot)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
            VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
        }
    }

    /** True if this pipeline was created with a bindless entity-texture set. */
    public boolean hasBindless() {
        return bindlessSet != 0L;
    }

    public void trace(VkCommandBuffer cmd, int width, int height) {
        trace(cmd, width, height, null, 0);
    }

    public void trace(VkCommandBuffer cmd, int width, int height, java.nio.ByteBuffer pushConstants) {
        trace(cmd, width, height, pushConstants, 0);
    }

    /**
     * Record bind (+ optional raygen push constants) + trace into the given command buffer.
     * {@code raygenIndex} selects which raygen record of the SBT this dispatch launches; the miss and
     * hit regions are shared, so passes over the same scene differ only in this index.
     */
    public void trace(VkCommandBuffer cmd, int width, int height, java.nio.ByteBuffer pushConstants, int raygenIndex) {
        if (raygenIndex < 0 || raygenIndex >= raygenCount) {
            throw new IllegalArgumentException("raygen index " + raygenIndex + " out of range [0, " + raygenCount + ")");
        }
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "trace rays")) {
            VK10.vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline);
            java.nio.LongBuffer boundSets = bindlessSet != 0L
                    ? stack.longs(descriptorSets[currentSet], bindlessSet)
                    : stack.longs(descriptorSets[currentSet]);
            VK10.vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipelineLayout, 0, boundSets, null);
            if (pushConstants != null && pushConstantSize > 0) {
                VK10.vkCmdPushConstants(cmd, pipelineLayout, pushConstantStages, 0, pushConstants);
            }
            // The raygen region must name exactly one record (size == stride), so selecting a pass is a
            // matter of which record it points at.
            VkStridedDeviceAddressRegionKHR raygen = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(sbt.deviceAddress + (long) raygenIndex * sbtStride).stride(sbtStride).size(sbtStride);
            VkStridedDeviceAddressRegionKHR miss = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(sbt.deviceAddress + (long) raygenCount * sbtStride).stride(sbtStride).size((long) missCount * sbtStride);
            VkStridedDeviceAddressRegionKHR hit = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(sbt.deviceAddress + (long) (raygenCount + missCount) * sbtStride).stride(sbtStride).size((long) hitGroupCount * sbtStride);
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
        if (bindlessPool != 0L) {
            VK10.vkDestroyDescriptorPool(vk, bindlessPool, null);
        }
        if (bindlessLayout != 0L) {
            VK10.vkDestroyDescriptorSetLayout(vk, bindlessLayout, null);
        }
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
