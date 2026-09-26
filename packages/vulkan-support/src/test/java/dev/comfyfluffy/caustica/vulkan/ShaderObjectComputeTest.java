package dev.comfyfluffy.caustica.vulkan;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSpecializationInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ShaderObjectComputeTest {
    @Test
    void pushDataRequiresDirectAlignedStorage() {
        assertDoesNotThrow(() -> ShaderObjectCompute.validatePushData(ByteBuffer.allocateDirect(40)));
        assertThrows(IllegalArgumentException.class,
                () -> ShaderObjectCompute.validatePushData(ByteBuffer.allocate(40)));
        assertThrows(IllegalArgumentException.class,
                () -> ShaderObjectCompute.validatePushData(ByteBuffer.allocateDirect(6)));
    }

    @Test
    void dispatchDimensionsMustBePositive() {
        assertDoesNotThrow(() -> ShaderObjectCompute.validateGroupCounts(1, 2, 3));
        assertThrows(IllegalArgumentException.class,
                () -> ShaderObjectCompute.validateGroupCounts(0, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ShaderObjectCompute.validateGroupCounts(1, -1, 1));
    }

    @Test
    void heapShadersRejectDescriptorSetDecorationsBeforeVulkanCreation() {
        assertDoesNotThrow(() -> ShaderObjectCompute.validateDescriptorHeapSpirv(module()));
        assertThrows(IllegalArgumentException.class,
                () -> ShaderObjectCompute.validateDescriptorHeapSpirv(decoratedModule(33)));
        assertThrows(IllegalArgumentException.class,
                () -> ShaderObjectCompute.validateDescriptorHeapSpirv(decoratedModule(34)));
        assertThrows(IllegalArgumentException.class,
                () -> ShaderObjectCompute.validateDescriptorHeapSpirv(
                        ByteBuffer.allocateDirect(5 * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(0x07230203).putInt(0).putInt(0).putInt(0).putInt(0).flip()
                                .putInt(0, 0)));
    }

    @Test
    void specializationConstantIBindsSpecIdI() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSpecializationInfo info = ShaderObjectCompute.specialization(stack, new int[] {7, 1, -3});
            assertEquals(3, info.mapEntryCount());
            assertEquals(3 * Integer.BYTES, info.dataSize());
            ByteBuffer data = info.pData();
            for (int id = 0; id < 3; id++) {
                assertEquals(id, info.pMapEntries().get(id).constantID());
                assertEquals(id * Integer.BYTES, info.pMapEntries().get(id).offset());
                assertEquals(Integer.BYTES, info.pMapEntries().get(id).size());
            }
            assertEquals(7, data.getInt(0));
            assertEquals(1, data.getInt(Integer.BYTES));
            assertEquals(-3, data.getInt(2 * Integer.BYTES));
        }
    }

    private static ByteBuffer module() {
        return header(5);
    }

    private static ByteBuffer decoratedModule(int decoration) {
        ByteBuffer module = header(8);
        module.limit(module.capacity()).position(5 * Integer.BYTES);
        module.putInt((3 << 16) | 71).putInt(1).putInt(decoration).flip();
        return module;
    }

    private static ByteBuffer header(int words) {
        ByteBuffer module = ByteBuffer.allocateDirect(words * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        module.putInt(0x07230203).putInt(0x00010600).putInt(0).putInt(2).putInt(0).flip();
        return module;
    }
}
