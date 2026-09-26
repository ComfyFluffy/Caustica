package dev.comfyfluffy.caustica.renderer.raytracing.pipeline;

import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** The SHaRC resolve compute of one world program; its push data is the frame's SharcFrame address. */
public final class RtSharcResolve {
    // numthreads of sharc_resolve.slang.
    private static final int GROUP_SIZE = 256;
    private final ShaderObjectCompute shader;

    private RtSharcResolve(ShaderObjectCompute shader) {
        this.shader = shader;
    }

    public static RtSharcResolve create(GpuDevice gpu, byte[] spirv) {
        ByteBuffer code = MemoryUtil.memAlloc(spirv.length).put(spirv).flip();
        try {
            return new RtSharcResolve(ShaderObjectCompute.create(gpu, code, "main"));
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    /** Resolves every entry of a cache whose capacity is a power of two of at least the group size. */
    public void dispatch(VkCommandBuffer commandBuffer, VulkanDeviceAddress sharcFrame, int capacity) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(Long.BYTES).order(ByteOrder.nativeOrder());
            push.putLong(0, sharcFrame.value());
            shader.dispatch(commandBuffer, push, capacity / GROUP_SIZE, 1, 1);
        }
    }

    public void destroy() {
        shader.close();
    }
}
