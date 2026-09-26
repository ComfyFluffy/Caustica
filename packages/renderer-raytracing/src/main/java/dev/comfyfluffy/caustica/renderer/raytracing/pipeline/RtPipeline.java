package dev.comfyfluffy.caustica.renderer.raytracing.pipeline;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.renderer.raytracing.scene.RtRetainedGeometryPlan;
import dev.comfyfluffy.caustica.renderer.raytracing.scene.RtRetainedGeometryPlan.HitGroup;
import dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext.check;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;

/** Descriptor-heap-native world ray-tracing pipeline and shader binding table. */
public final class RtPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger(RtPipeline.class);
    // Pipeline creation and retained-table packing use the same private handle order.
    private static final HitGroup[] HIT_GROUPS = HitGroup.values();
    static final int TLAS_DESCRIPTOR_SET = 0;
    static final int TLAS_DESCRIPTOR_BINDING = 0;
    private final VulkanDeviceContext context;
    private final long pipeline;
    private final VmaMappedBuffer sbt;
    private final long stride;
    private final int handleSize;
    private final int raygenCount;
    private final int missCount;
    private final ByteBuffer hitHandles;
    private boolean destroyed;

    private RtPipeline(VulkanDeviceContext context, long pipeline, VmaMappedBuffer sbt, long stride, int handleSize,
                       int raygenCount, int missCount, ByteBuffer hitHandles) {
        this.context = context;
        this.pipeline = pipeline;
        this.sbt = sbt;
        this.stride = stride;
        this.handleSize = handleSize;
        this.raygenCount = raygenCount;
        this.missCount = missCount;
        this.hitHandles = hitHandles;
    }

    /** Creates a KHR ray-tracing pipeline whose shaders directly address the two descriptor heaps. */
    public static RtPipeline create(VulkanDeviceContext context, RtShaderCode[] raygen, RtShaderCode[] miss,
                                    RtShaderCode closestHit, RtShaderCode radianceAnyHit) {
        if (raygen.length == 0 || miss.length == 0) throw new IllegalArgumentException("empty RT stage array");
        for (RtShaderCode shader : raygen) requireDescriptorHeapCompatible(shader);
        for (RtShaderCode shader : miss) requireDescriptorHeapCompatible(shader);
        requireDescriptorHeapCompatible(closestHit);
        requireDescriptorHeapCompatible(radianceAnyHit);
        VkDevice device = context.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int raygenCount = raygen.length;
            int missCount = miss.length;
            int hitCount = HIT_GROUPS.length;
            int closestStage = raygenCount + missCount;
            int radianceStage = closestStage + 1;
            int stageCount = closestStage + 2;
            int groupCount = raygenCount + missCount + hitCount;
            long[] modules = new long[stageCount];
            try {
                for (int i = 0; i < raygenCount; i++) modules[i] = module(device, stack, raygen[i]);
                for (int i = 0; i < missCount; i++) modules[raygenCount + i] = module(device, stack, miss[i]);
                modules[closestStage] = module(device, stack, closestHit);
                modules[radianceStage] = module(device, stack, radianceAnyHit);

                ByteBuffer entry = stack.UTF8("main");
                TlasPushIndexMapping tlasMapping = tlasPushIndexMapping(
                        context.descriptorHeap().properties().resourceDescriptorStrideBytes());
                VkDescriptorSetAndBindingMappingEXT.Buffer mappings = VkDescriptorSetAndBindingMappingEXT
                        .calloc(1, stack);
                tlasMapping.write(mappings.get(0));
                VkShaderDescriptorSetAndBindingMappingInfoEXT mappingInfo =
                        VkShaderDescriptorSetAndBindingMappingInfoEXT.calloc(stack).sType$Default()
                                .pMappings(mappings);
                VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(stageCount, stack);
                for (int i = 0; i < raygenCount; i++) stage(stages.get(i), VK_SHADER_STAGE_RAYGEN_BIT_KHR,
                        modules[i], entry, mappingInfo);
                for (int i = 0; i < missCount; i++) stage(stages.get(raygenCount + i), VK_SHADER_STAGE_MISS_BIT_KHR,
                        modules[raygenCount + i], entry, mappingInfo);
                stage(stages.get(closestStage), VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, modules[closestStage], entry,
                        mappingInfo);
                stage(stages.get(radianceStage), VK_SHADER_STAGE_ANY_HIT_BIT_KHR, modules[radianceStage], entry,
                        mappingInfo);

                VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(groupCount, stack);
                for (int i = 0; i < raygenCount + missCount; i++) {
                    groups.get(i).sType$Default().type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                            .generalShader(i).closestHitShader(VK_SHADER_UNUSED_KHR)
                            .anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
                }
                int firstHit = raygenCount + missCount;
                for (int i = 0; i < hitCount; i++) {
                    groups.get(firstHit + i).sType$Default().type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                            .generalShader(VK_SHADER_UNUSED_KHR)
                            .closestHitShader(closestStage)
                            .anyHitShader(anyHitStage(HIT_GROUPS[i], radianceStage))
                            .intersectionShader(VK_SHADER_UNUSED_KHR);
                }

                long flags = EXTDescriptorHeap.VK_PIPELINE_CREATE_2_DESCRIPTOR_HEAP_BIT_EXT;
                if (context.backend().capabilities().opacityMicromap()) {
                    flags |= EXTOpacityMicromap.VK_PIPELINE_CREATE_RAY_TRACING_OPACITY_MICROMAP_BIT_EXT;
                }
                VkPipelineCreateFlags2CreateInfo flags2 = VkPipelineCreateFlags2CreateInfo.calloc(stack)
                        .sType$Default().flags(flags);
                VkRayTracingPipelineCreateInfoKHR.Buffer info = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
                info.get(0).sType$Default().pNext(flags2.address()).pStages(stages).pGroups(groups)
                        .maxPipelineRayRecursionDepth(1).layout(VK10.VK_NULL_HANDLE);
                LongBuffer out = stack.mallocLong(1);
                // The driver compiles the stages on every thread joined to the deferred operation. run() returns
                // after the operation completes, so this frame's create infos and the modules outlive it.
                long startNanos = System.nanoTime();
                DeferredHostOperation.Execution creation = DeferredHostOperation.run(device, operation ->
                        vkCreateRayTracingPipelinesKHR(device, operation, VK10.VK_NULL_HANDLE, info, null, out));
                check(creation.result(), "vkCreateRayTracingPipelinesKHR");
                LOGGER.info(String.format(Locale.ROOT, "Created world RT pipeline in %.1f ms (%d stages, %s)",
                        (System.nanoTime() - startNanos) / 1.0e6, stageCount, creation.summary()));
                long pipeline = out.get(0);
                return createBindingTable(context, stack, pipeline, raygenCount, missCount);
            } finally {
                for (long module : modules) if (module != 0L) VK10.vkDestroyShaderModule(device, module, null);
            }
        }
    }

    /** Owns the new pipeline until its SBT and retained hit handles are ready. */
    private static RtPipeline createBindingTable(VulkanDeviceContext context, MemoryStack stack,
                                                 long pipeline, int raygenCount, int missCount) {
        VkDevice device = context.vk();
        int firstHit = raygenCount + missCount;
        int hitCount = HIT_GROUPS.length;
        int groupCount = firstHit + hitCount;
        VmaMappedBuffer sbt = null;
        try {
            RtDebugLabels.name(context, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, "world RT pipeline");
            int handleSize = context.shaderGroupHandleSize();
            ByteBuffer handles = stack.malloc(groupCount * handleSize);
            check(vkGetRayTracingShaderGroupHandlesKHR(device, pipeline, 0, groupCount, handles),
                    "vkGetRayTracingShaderGroupHandlesKHR");
            long stride = align(handleSize, Math.max(context.shaderGroupBaseAlignment(),
                    context.shaderGroupHandleAlignment()));
            if (stride > Integer.toUnsignedLong(context.maxShaderGroupStride())) {
                throw new UnsupportedOperationException("SBT stride exceeds maxShaderGroupStride");
            }
            // Scene-specific hit tables use CPU handles; only raygen and miss records live in this SBT.
            ByteBuffer hitHandles = ByteBuffer.allocate(hitCount * handleSize);
            hitHandles.put(0, handles, firstHit * handleSize, hitHandles.capacity());
            long sbtSize = stride * firstHit;
            sbt = VmaMappedBuffer.create(context, sbtSize,
                    VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR,
                    context.shaderGroupBaseAlignment(), "world shader binding table");
            ByteBuffer mapped = sbt.mapped();
            for (int i = 0; i < firstHit; i++) {
                mapped.put(Math.toIntExact(i * stride), handles, i * handleSize, handleSize);
            }
            sbt.flush(0L, sbtSize);
            return new RtPipeline(context, pipeline, sbt, stride, handleSize,
                    raygenCount, missCount, hitHandles);
        } catch (RuntimeException | Error failure) {
            VmaMappedBuffer allocatedSbt = sbt;
            ResourceLifetime.closeAfterFailure(failure,
                    () -> { if (allocatedSbt != null) allocatedSbt.close(); },
                    () -> VK10.vkDestroyPipeline(device, pipeline, null));
            throw failure;
        }
    }

    /** Dispatches with a scene-specific hit table while retaining the pipeline-owned raygen and miss tables. */
    public void trace(VkCommandBuffer commandBuffer, int width, int height, ByteBuffer roots,
                      int raygenIndex, HitTable retainedHits) {
        trace(commandBuffer, width, height, 1, roots, raygenIndex, retainedHits);
    }

    public void trace(VkCommandBuffer commandBuffer, int width, int height, int depth, ByteBuffer roots,
                      int raygenIndex, HitTable retainedHits) {
        if (destroyed) throw new IllegalStateException("pipeline is destroyed");
        if (raygenIndex < 0 || raygenIndex >= raygenCount) throw new IllegalArgumentException("raygen index out of range");
        if (roots.remaining() != RtBindings.WORLD_PUSH_CONSTANT_SIZE) {
            throw new IllegalArgumentException("world binding root must be exactly "
                    + RtBindings.WORLD_PUSH_CONSTANT_SIZE + " bytes");
        }
        try (MemoryStack stack = MemoryStack.stackPush();
             var ignored = RtDebugLabels.scope(context, commandBuffer, "trace rays")) {
            context.bindDescriptorHeaps(commandBuffer);
            context.pushData(commandBuffer, 0, roots);
            VK10.vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline);
            VkStridedDeviceAddressRegionKHR rgen = region(stack,
                    sbt.deviceRange().address().addBytes((long) raygenIndex * stride), stride, stride);
            VkStridedDeviceAddressRegionKHR rmiss = region(stack,
                    sbt.deviceRange().address().addBytes((long) raygenCount * stride),
                    stride, (long) missCount * stride);
            VkStridedDeviceAddressRegionKHR hit = hitRegion(stack, retainedHits);
            vkCmdTraceRaysKHR(commandBuffer, rgen, rmiss, hit,
                    VkStridedDeviceAddressRegionKHR.calloc(stack), width, height, depth);
        }
    }

    public int retainedHitRecordStride() { return Math.toIntExact(stride); }

    /** An empty scene has no hit records; Vulkan consumes a zero-sized SBT region for its miss-only rays. */
    static VkStridedDeviceAddressRegionKHR hitRegion(MemoryStack stack, HitTable hits) {
        return hits == null ? VkStridedDeviceAddressRegionKHR.calloc(stack)
                : region(stack, hits.bytes().address(), hits.stride(), hits.bytes().byteSize());
    }

    public long retainedHitTableAlignment() { return context.shaderGroupBaseAlignment(); }

    /** Addressable scene-specific hit SBT region. Its owner retains the buffer through the trace use. */
    public record HitTable(VulkanDeviceAddressRange bytes, long stride) {
        public HitTable {
            if (stride <= 0L || bytes.byteSize() % stride != 0L) {
                throw new IllegalArgumentException("invalid retained hit table region");
            }
        }
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        new ResourceLifetime(sbt::close, () -> VK10.vkDestroyPipeline(context.vk(), pipeline, null)).close();
    }

    /** CPU image for a scene-specific hit table; the caller owns uploading and retiring its SBT buffer. */
    public ByteBuffer retainedHitRecords(List<RtRetainedGeometryPlan.HitGroup> groups) {
        if (destroyed) throw new IllegalStateException("pipeline is destroyed");
        return packRetainedHitRecords(hitHandles, handleSize, Math.toIntExact(stride), groups);
    }

    static ByteBuffer packRetainedHitRecords(ByteBuffer fixedHitHandles, int handleSize, int recordStride,
                                             List<RtRetainedGeometryPlan.HitGroup> groups) {
        if (recordStride < handleSize) throw new IllegalArgumentException("record stride is smaller than a handle");
        ByteBuffer packed = ByteBuffer.allocate(Math.multiplyExact(recordStride, groups.size()));
        for (int record = 0; record < groups.size(); record++) {
            int source = groups.get(record).ordinal() * handleSize;
            int target = record * recordStride;
            packed.put(target, fixedHitHandles, source, handleSize);
        }
        return packed;
    }

    static long align(long value, long alignment) {
        if (value < 0 || alignment <= 0) throw new IllegalArgumentException("invalid alignment input");
        long remainder = value % alignment;
        return remainder == 0 ? value : Math.addExact(value, alignment - remainder);
    }

    static void requireDescriptorHeapCompatible(RtShaderCode shader) {
        byte[] code = shader.spirv();
        if (code.length < 5 * Integer.BYTES || (code.length & 3) != 0) {
            throw new IllegalArgumentException(shader.debugName() + " is not a complete SPIR-V module");
        }
        ByteBuffer words = ByteBuffer.wrap(code).order(ByteOrder.LITTLE_ENDIAN);
        if (words.getInt(0) != 0x07230203) {
            throw new IllegalArgumentException(shader.debugName() + " has an invalid SPIR-V magic number");
        }
        int word = 5;
        int wordCount = code.length / Integer.BYTES;
        Map<Integer, Integer> descriptorSets = new HashMap<>();
        Map<Integer, Integer> bindings = new HashMap<>();
        while (word < wordCount) {
            int instruction = words.getInt(word * Integer.BYTES);
            int instructionWords = instruction >>> 16;
            int opcode = instruction & 0xffff;
            if (instructionWords == 0 || instructionWords > wordCount - word) {
                throw new IllegalArgumentException(shader.debugName() + " has a malformed SPIR-V instruction");
            }
            if (opcode == 71 && instructionWords >= 3) { // OpDecorate
                int target = words.getInt((word + 1) * Integer.BYTES);
                int decoration = words.getInt((word + 2) * Integer.BYTES);
                if ((decoration == 33 || decoration == 34) && instructionWords < 4) {
                    throw new IllegalArgumentException(shader.debugName() + " has an incomplete descriptor decoration");
                }
                if (decoration == 33) { // Binding
                    bindings.put(target, words.getInt((word + 3) * Integer.BYTES));
                } else if (decoration == 34) { // DescriptorSet
                    descriptorSets.put(target, words.getInt((word + 3) * Integer.BYTES));
                }
            }
            word += instructionWords;
        }
        for (int target : descriptorSets.keySet()) {
            if (!bindings.containsKey(target)) rejectUnmappedDescriptor(shader);
        }
        for (int target : bindings.keySet()) {
            if (!descriptorSets.containsKey(target)
                    || descriptorSets.get(target) != TLAS_DESCRIPTOR_SET
                    || bindings.get(target) != TLAS_DESCRIPTOR_BINDING) {
                rejectUnmappedDescriptor(shader);
            }
        }
    }

    private static void rejectUnmappedDescriptor(RtShaderCode shader) {
        throw new IllegalArgumentException(shader.debugName()
                + " contains a descriptor binding other than the mapped world TLAS");
    }

    static TlasPushIndexMapping tlasPushIndexMapping(long resourceDescriptorStride) {
        return new TlasPushIndexMapping(TLAS_DESCRIPTOR_SET, TLAS_DESCRIPTOR_BINDING,
                RtBindings.WORLD_TOP_LEVEL_AS_INDEX_OFFSET, Math.toIntExact(resourceDescriptorStride));
    }

    record TlasPushIndexMapping(int descriptorSet, int binding, int pushOffset, int heapIndexStride) {
        TlasPushIndexMapping {
            if (descriptorSet < 0 || binding < 0) throw new IllegalArgumentException("negative descriptor binding");
            if (pushOffset < 0 || (pushOffset & 3) != 0) {
                throw new IllegalArgumentException("TLAS push offset must be a non-negative multiple of four");
            }
            if (heapIndexStride <= 0) throw new IllegalArgumentException("resource heap stride must be positive");
        }

        void write(VkDescriptorSetAndBindingMappingEXT mapping) {
            mapping.sType$Default().descriptorSet(descriptorSet).firstBinding(binding).bindingCount(1)
                    .resourceMask(EXTDescriptorHeap.VK_SPIRV_RESOURCE_TYPE_ACCELERATION_STRUCTURE_BIT_EXT)
                    .source(EXTDescriptorHeap.VK_DESCRIPTOR_MAPPING_SOURCE_HEAP_WITH_PUSH_INDEX_EXT)
                    .sourceData(data -> data.pushIndex(pushIndex -> pushIndex
                            .heapOffset(0).pushOffset(pushOffset)
                            .heapIndexStride(heapIndexStride).heapArrayStride(heapIndexStride)));
        }
    }

    private static VkStridedDeviceAddressRegionKHR region(MemoryStack stack, VulkanDeviceAddress address,
                                                           long stride, long size) {
        return VkStridedDeviceAddressRegionKHR.calloc(stack).deviceAddress(address.value()).stride(stride).size(size);
    }

    private static void stage(VkPipelineShaderStageCreateInfo info, int stage, long module, ByteBuffer entry,
                              VkShaderDescriptorSetAndBindingMappingInfoEXT mappingInfo) {
        info.sType$Default().pNext(mappingInfo).stage(stage).module(module).pName(entry);
    }

    static int anyHitStage(HitGroup group, int radiance) {
        return switch (group) {
            case RADIANCE_OPAQUE -> VK_SHADER_UNUSED_KHR;
            case RADIANCE_CUTOUT -> radiance;
        };
    }

    private static long module(VkDevice device, MemoryStack stack, RtShaderCode shader) {
        ByteBuffer code = MemoryUtil.memAlloc(shader.spirv().length).put(shader.spirv()).flip();
        try {
            LongBuffer out = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(device,
                    VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, out),
                    "vkCreateShaderModule(" + shader.debugName() + ')');
            return out.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

}
