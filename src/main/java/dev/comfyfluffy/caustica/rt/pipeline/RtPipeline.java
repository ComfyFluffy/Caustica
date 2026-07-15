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
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;

import static dev.comfyfluffy.caustica.rt.RtContext.check;
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
    private static final String SHADER_DIR = "/caustica/rt/";
    /** Bindless entity-texture channels per slot — binding 0 = albedo, 1 = LabPBR _n, 2 = _s. */
    public static final int BINDLESS_BINDINGS = 3;
    // A ring of descriptor sets: setTlas writes the next slot (long-unused) rather than mutating the
    // slot in-flight frames are still reading, so the TLAS can be swapped without a device drain.
    // The TLAS is rebuilt + rebound every frame (dynamic content), so a slot is reused every RING
    // frames; RING must exceed the max frames-in-flight (vanilla MC ≤ 3) for the reused slot to be off
    // all queues. 6 gives margin and matches the KEEP_FRAMES-style horizon used for resource frees.
    private static final int RING = 6;

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long[] descriptorSets;
    private int currentSet;
    private final long pipelineLayout;
    private final long pipeline;
    private final RtBuffer sbt;
    private final long sbtStride;
    private final int missCount;
    private final int hitGroupCount;
    private final int pushConstantSize;
    private final int pushConstantStages;
    private final int firstExtraBinding;
    // Optional second descriptor set (set 1) holding a bindless runtime sampler2D[] the closest-hit
    // indexes per-prim. Single (not ringed) + update-after-bind: the RenderType→slot registry is
    // append-only, so existing slots never change and new slots are written before the frame that uses
    // them. 0 when the pipeline was created without bindless textures.
    private final long bindlessLayout;
    private final long bindlessPool;
    private final long bindlessSet;
    private final int skyAtlasBinding;
    private boolean destroyed;

    private RtPipeline(RtContext ctx, long dsl, long pool, long[] sets, long layout, long pipeline, RtBuffer sbt, long stride, int missCount, int hitGroupCount, int pushConstantSize, int pushConstantStages, int firstExtraBinding,
                       long bindlessLayout, long bindlessPool, long bindlessSet, int skyAtlasBinding) {
        this.ctx = ctx;
        this.descriptorSetLayout = dsl;
        this.descriptorPool = pool;
        this.descriptorSets = sets;
        this.currentSet = 0;
        this.pipelineLayout = layout;
        this.pipeline = pipeline;
        this.sbt = sbt;
        this.sbtStride = stride;
        this.missCount = missCount;
        this.hitGroupCount = hitGroupCount;
        this.pushConstantSize = pushConstantSize;
        this.pushConstantStages = pushConstantStages;
        this.firstExtraBinding = firstExtraBinding;
        this.bindlessLayout = bindlessLayout;
        this.bindlessPool = bindlessPool;
        this.bindlessSet = bindlessSet;
        this.skyAtlasBinding = skyAtlasBinding;
    }

    /**
     * Builds the RT pipeline. {@code rahit} (nullable) adds any-hit-capable triangle hit records. With the
     * world pipeline, the hit SBT region is laid out to match {@link RtAccel}'s terrain bucket/ray-type
     * constants: radiance records first, shadow records second, then entity records. {@code extraStorageImages}
     * adds that many raygen-visible storage images at bindings 3.. (the DLSS-RR guide buffers);
     * write them with {@link #setExtraStorageImage}.
     */
    public static RtPipeline create(RtContext ctx, String rgen, String[] rmiss, String rchit, String rahit, int pushConstantSize, boolean withAtlasSampler, int extraStorageImages, int bindlessTextures, boolean skyAtlas) {
        VkDevice vk = ctx.vk();
        boolean hasAhit = rahit != null;
        String label = "world RT pipeline";
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int firstExtraBinding = withAtlasSampler ? 3 : 2;
            int materialBase = firstExtraBinding + extraStorageImages;
            // Sky rewrite: the vanilla celestials atlas (sun + moon phases), sampled by world.rmiss to
            // draw the sun/moon discs. Canonical material pages live in the bindless set, not set 0.
            int skyBinding = skyAtlas ? materialBase : -1;
            int skySamplers = skyAtlas ? 1 : 0;
            int bindingCount = firstExtraBinding + extraStorageImages + skySamplers;
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
            for (int e = 0; e < extraStorageImages; e++) {
                binds.get(firstExtraBinding + e).binding(firstExtraBinding + e).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            }
            if (skyAtlas) {
                binds.get(skyBinding).binding(skyBinding).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_MISS_BIT_KHR);
            }
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout");
            long dsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, label + " descriptor set layout");

            int combinedSamplers = (withAtlasSampler ? 1 : 0) + skySamplers; // block atlas + celestials
            int poolSizeCount = 2 + (combinedSamplers > 0 ? 1 : 0);
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(poolSizeCount, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(RING);
            // output image (binding 1) + the extra guide images share the storage-image type.
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(RING * (1 + extraStorageImages));
            if (combinedSamplers > 0) {
                poolSizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(RING * combinedSamplers);
            }
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

            // Optional bindless set (set 1) — a partially-bound, update-after-bind combined-image-sampler
            // array the closest-hit samples per-prim for entity textures.
            long bindlessLayout = 0L, bindlessPool = 0L, bindlessSet = 0L;
            if (bindlessTextures > 0) {
                // BINDLESS_BINDINGS parallel arrays per slot — binding 0 = albedo, 1 = LabPBR _n,
                // 2 = LabPBR _s. The closest-hit samples all three at the same slot (tint.w) for entities.
                int nb = BINDLESS_BINDINGS;
                VkDescriptorSetLayoutBinding.Buffer bl = VkDescriptorSetLayoutBinding.calloc(nb, stack);
                // Sampled by the closest-hit (shading) and, for entity cutout, the any-hit (alpha test).
                int bindlessStages = VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR | (hasAhit ? VK_SHADER_STAGE_ANY_HIT_BIT_KHR : 0);
                java.nio.IntBuffer bindFlags = stack.mallocInt(nb);
                for (int b = 0; b < nb; b++) {
                    bl.get(b).binding(b).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .descriptorCount(bindlessTextures).stageFlags(bindlessStages);
                    bindFlags.put(b, VK12.VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT | VK12.VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT);
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

            // Stages: raygen, one miss per rmiss entry, the closest-hit, then (optionally) the any-hit.
            // Groups are raygen + N miss + the hit records selected by traceRayEXT's SBT offset/stride.
            int missCount = rmiss.length;
            int hitGroupCount = hasAhit ? RtAccel.SBT_HIT_GROUP_COUNT : 1;
            int groupCount = 1 + missCount + hitGroupCount;
            int hitGroupIdx = 1 + missCount;
            int chitStage = 1 + missCount;
            int ahitStage = chitStage + 1;
            int stageCount = 1 + missCount + 1 + (hasAhit ? 1 : 0);
            long mGen = loadModule(vk, stack, rgen);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, mGen, label + " " + rgen);
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
            RtBuffer sbt = ctx.createBuffer(stride * groupCount, VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR, true,
                    label + " shader binding table");
            for (int g = 0; g < groupCount; g++) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(handles) + (long) g * handleSize, sbt.mapped + g * stride, handleSize);
            }
            return new RtPipeline(ctx, dsl, pool, sets, layout, pipeline, sbt, stride, missCount, hitGroupCount, pushConstantSize, pcStages, firstExtraBinding,
                    bindlessLayout, bindlessPool, bindlessSet, skyBinding);
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
        return true;
    }

    /**
     * Bind a new TLAS into the next ring slot (which in-flight frames are no longer reading, since
     * swaps are many frames apart) and make it current, so the binding can change without a drain.
     */
    public void setTlas(long tlas) {
        currentSet = (currentSet + 1) % RING;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSetAccelerationStructureKHR asWrite = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR).pAccelerationStructures(stack.longs(tlas));
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().pNext(asWrite.address()).dstSet(descriptorSets[currentSet]).dstBinding(0)
                    .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
            VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
        }
    }

    /** Write the storage image into every ring slot (set once at init / on resize, when idle). */
    public void setStorageImage(long imageView) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack);
            imgInfo.get(0).imageView(imageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(RING, stack);
            for (int i = 0; i < RING; i++) {
                write.get(i).sType$Default().dstSet(descriptorSets[i]).dstBinding(1)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(imgInfo);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
        }
    }

    /** Write an extra storage image (DLSS-RR guide buffer) into binding {@code firstExtraBinding + slot} across every ring slot. */
    public void setExtraStorageImage(int slot, long imageView) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack);
            imgInfo.get(0).imageView(imageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(RING, stack);
            for (int i = 0; i < RING; i++) {
                write.get(i).sType$Default().dstSet(descriptorSets[i]).dstBinding(firstExtraBinding + slot)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(imgInfo);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
        }
    }

    /** Bind the block atlas (combined image sampler) into every ring slot — only valid if created withAtlasSampler. */
    public void setAtlasSampler(long imageView, long sampler) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
            info.get(0).sampler(sampler).imageView(imageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(RING, stack);
            for (int i = 0; i < RING; i++) {
                write.get(i).sType$Default().dstSet(descriptorSets[i]).dstBinding(2)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
        }
    }

    /** Bind the vanilla celestials atlas (sun + moon phases), sampled by world.rmiss for the discs. */
    public void setSkyAtlas(long imageView, long sampler) {
        writeAtlasBinding(skyAtlasBinding, imageView, sampler);
    }

    public boolean hasSkyAtlas() {
        return skyAtlasBinding >= 0;
    }

    private void writeAtlasBinding(int binding, long imageView, long sampler) {
        if (binding < 0) {
            return;
        }
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

    /** Write one texture into bindless {@code binding} (entity albedo/_n/_s or a canonical page bundle)
     * at {@code slot} in set 1. Update-after-bind + append-only makes new entity slots safe mid-frame;
     * material-page slots are populated together at the resource-epoch boundary. */
    public void setBindlessTexture(int binding, int slot, long imageView, long sampler) {
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
        trace(cmd, width, height, null);
    }

    /** Record bind (+ optional raygen push constants) + trace into the given command buffer. */
    public void trace(VkCommandBuffer cmd, int width, int height, java.nio.ByteBuffer pushConstants) {
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "trace rays")) {
            VK10.vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline);
            java.nio.LongBuffer boundSets = bindlessSet != 0L
                    ? stack.longs(descriptorSets[currentSet], bindlessSet)
                    : stack.longs(descriptorSets[currentSet]);
            VK10.vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipelineLayout, 0, boundSets, null);
            if (pushConstants != null && pushConstantSize > 0) {
                VK10.vkCmdPushConstants(cmd, pipelineLayout, pushConstantStages, 0, pushConstants);
            }
            VkStridedDeviceAddressRegionKHR raygen = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(sbt.deviceAddress).stride(sbtStride).size(sbtStride);
            VkStridedDeviceAddressRegionKHR miss = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(sbt.deviceAddress + sbtStride).stride(sbtStride).size((long) missCount * sbtStride);
            VkStridedDeviceAddressRegionKHR hit = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(sbt.deviceAddress + (1L + missCount) * sbtStride).stride(sbtStride).size((long) hitGroupCount * sbtStride);
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
