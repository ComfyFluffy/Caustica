package dev.comfyfluffy.caustica.minecraft.client.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import dev.comfyfluffy.caustica.minecraft.client.mixin.CommandEncoderAccessor;
import dev.comfyfluffy.caustica.minecraft.client.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftRtRuntime;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.spi.vulkan.DebugMarkers;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanQueueRef;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanRendererBackend;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanDeviceCapabilities;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanLowLatency;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.util.function.IntSupplier;

/** Adapts Blaze3D's deferred Vulkan encoder and device wrappers to renderer-owned interfaces. */
public final class MinecraftVulkanBackend implements VulkanRendererBackend {
    private final MinecraftDeviceBringup deviceBringup;
    private final MinecraftRtRuntime runtime;
    private VulkanDevice device;
    private VulkanQueueRef graphicsQueue;
    private VulkanQueueRef computeQueue;
    private DebugMarkers debugMarkers;
    private VulkanDeviceCapabilities capabilities;
    private VulkanLowLatency lowLatency;

    public MinecraftVulkanBackend(MinecraftDeviceBringup deviceBringup,
                                  MinecraftRtRuntime runtime) {
        this.deviceBringup = java.util.Objects.requireNonNull(deviceBringup, "deviceBringup");
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
    }

    private void initialize(VulkanDevice device, MinecraftDeviceBringup.NegotiatedDevice negotiated) {
        this.device = device;
        VulkanQueue graphics = device.graphicsQueue();
        VulkanQueue compute = new VulkanQueue(device, negotiated.computeQueue().familyIndex(),
                negotiated.computeQueue().queueIndex());
        this.graphicsQueue = new VulkanQueueRef(graphics.vkQueue(), graphics.queueFamilyIndex());
        this.computeQueue = new VulkanQueueRef(compute.vkQueue(), compute.queueFamilyIndex());
        this.debugMarkers = new MinecraftDebugMarkers(device);
        this.capabilities = negotiated.capabilities();
        this.lowLatency = new MinecraftLowLatency(capabilities, runtime);
    }

    public void installCurrent() {
        if (device != null) return;
        Object backend = ((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend();
        if (backend instanceof VulkanDevice device) {
            MinecraftDeviceBringup.NegotiatedDevice negotiated = deviceBringup.consume(device.vkDevice());
            if (negotiated != null && negotiated.capabilities().rayTracing()) {
                initialize(device, negotiated);
                runtime.installVulkanBackend(this);
            }
        }
    }

    public MinecraftVulkanBackend currentOrNull() { return device == null ? null : this; }

    /**
     * Runs a Blaze3D queue submit, present, or wait under the renderer device's queue lock. Before the
     * renderer context exists no renderer thread uses the device's queues.
     */
    public int synchronizedQueueOperation(IntSupplier operation) {
        VulkanDeviceContext context = runtime.vulkanContextOrNull();
        return context == null ? operation.getAsInt() : context.synchronizedQueueOperation(operation);
    }

    public static GraphicsSubmission wrap(VulkanCommandEncoder encoder) {
        return new Submission(encoder);
    }

    @Override
    public VkDevice device() {
        return device.vkDevice();
    }

    @Override
    public VulkanQueueRef graphicsQueue() {
        return graphicsQueue;
    }

    @Override
    public VulkanQueueRef computeQueue() {
        return computeQueue;
    }

    @Override
    public GraphicsSubmission createGraphicsSubmission() {
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice()
                .createCommandEncoder()).caustica$getBackend();
        return new Submission(encoder);
    }

    @Override
    public void assertRenderThread() {
        RenderSystem.assertOnRenderThread();
    }

    @Override
    public DebugMarkers debugMarkers() {
        return debugMarkers;
    }

    @Override
    public VulkanDeviceCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public VulkanLowLatency lowLatency() {
        return lowLatency;
    }

    private record Submission(VulkanCommandEncoder encoder) implements GraphicsSubmission {
        @Override
        public VkCommandBuffer beginTransientCommandBuffer() {
            return encoder.allocateAndBeginTransientCommandBuffer();
        }

        @Override
        public void waitSemaphore(long semaphore, long value, long stageMask) {
            encoder.waitSemaphore(semaphore, value, stageMask);
        }

        @Override
        public void execute(VkCommandBuffer commandBuffer) {
            encoder.execute(commandBuffer);
        }

        @Override
        public void signalSemaphore(long semaphore, long value, long stageMask) {
            encoder.signalSemaphore(semaphore, value, stageMask);
        }
    }

    private record MinecraftDebugMarkers(VulkanDevice device) implements DebugMarkers {
        @Override
        public void nameObject(int objectType, long handle, String label) {
            device.instance().debug().setObjectName(device.vkDevice(), objectType, handle, label);
        }

        @Override
        public Scope begin(VkCommandBuffer commandBuffer, String label) {
            device.instance().debug().beginDebugGroup(commandBuffer, () -> label);
            return () -> device.instance().debug().endDebugGroup(commandBuffer);
        }
    }
}
