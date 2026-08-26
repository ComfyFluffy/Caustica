package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static dev.comfyfluffy.caustica.rt.RtContext.check;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkCmdPushConstants;
import static org.lwjgl.vulkan.VK10.vkCreateComputePipelines;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;

/** Descriptor-free directional-SH resolve pass. */
public final class RtSharcResolvePipeline {
    private static final String SHADER = "/caustica/shaders/sharc/sharc_resolve.comp.spv";
    private static final int PUSH_BYTES = Long.BYTES;

    private final RtContext ctx;
    private final long layout;
    private final long pipeline;
    private boolean destroyed;

    private RtSharcResolvePipeline(RtContext ctx, long layout, long pipeline) {
        this.ctx = ctx;
        this.layout = layout;
        this.pipeline = pipeline;
    }

    public static RtSharcResolvePipeline create(RtContext ctx) {
        long layout = 0L;
        long module = 0L;
        long pipeline = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pPushConstantRanges(range);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreatePipelineLayout(ctx.vk(), layoutInfo, null, p),
                    "vkCreatePipelineLayout(SHaRC resolve)");
            layout = p.get(0);
            RtDebugLabels.name(ctx, org.lwjgl.vulkan.VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout,
                    "SHaRC resolve layout");
            module = loadModule(ctx, stack);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer createInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            createInfo.get(0).sType$Default().stage(stage).layout(layout);
            check(vkCreateComputePipelines(ctx.vk(), VK_NULL_HANDLE, createInfo, null, p),
                    "vkCreateComputePipelines(SHaRC resolve)");
            pipeline = p.get(0);
            vkDestroyShaderModule(ctx.vk(), module, null);
            module = 0L;
            RtDebugLabels.name(ctx, org.lwjgl.vulkan.VK10.VK_OBJECT_TYPE_PIPELINE, pipeline,
                    "SHaRC resolve");
            return new RtSharcResolvePipeline(ctx, layout, pipeline);
        } catch (Throwable t) {
            if (module != 0L) vkDestroyShaderModule(ctx.vk(), module, null);
            if (pipeline != 0L) vkDestroyPipeline(ctx.vk(), pipeline, null);
            if (layout != 0L) vkDestroyPipelineLayout(ctx.vk(), layout, null);
            throw t;
        }
    }

    public void dispatch(VkCommandBuffer cmd, long frameAddress, int capacity) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "SHaRC resolve")) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            ByteBuffer push = stack.malloc(PUSH_BYTES).putLong(0, frameAddress);
            vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(cmd, (capacity + 255) / 256, 1, 1);
        }
    }

    public void destroy() {
        if (destroyed) return;
        vkDestroyPipeline(ctx.vk(), pipeline, null);
        vkDestroyPipelineLayout(ctx.vk(), layout, null);
        destroyed = true;
    }

    private static long loadModule(RtContext ctx, MemoryStack stack) {
        byte[] bytes;
        try (InputStream in = RtSharcResolvePipeline.class.getResourceAsStream(SHADER)) {
            if (in == null) throw new IllegalStateException("missing SHaRC SPIR-V resource: " + SHADER);
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + SHADER, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        try {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateShaderModule(ctx.vk(), info, null, p), "vkCreateShaderModule(SHaRC resolve)");
            return p.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
