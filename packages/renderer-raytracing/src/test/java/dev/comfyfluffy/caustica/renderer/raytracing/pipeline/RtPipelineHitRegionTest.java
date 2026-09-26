package dev.comfyfluffy.caustica.renderer.raytracing.pipeline;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import dev.comfyfluffy.caustica.renderer.raytracing.scene.RtRetainedGeometryPlan.HitGroup;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtPipelineHitRegionTest {
    @Test
    void onlyCutoutGroupsRunCoverageAnyHit() {
        assertEquals(VK_SHADER_UNUSED_KHR, RtPipeline.anyHitStage(HitGroup.RADIANCE_OPAQUE, 8));
        assertEquals(8, RtPipeline.anyHitStage(HitGroup.RADIANCE_CUTOUT, 8));
    }

    @Test
    void emptySceneUsesAnEmptyHitRegionAndPopulatedSceneRetainsItsRecords() {
        try (var stack = MemoryStack.stackPush()) {
            var empty = RtPipeline.hitRegion(stack, null);
            assertEquals(0L, empty.deviceAddress());
            assertEquals(0L, empty.stride());
            assertEquals(0L, empty.size());

            var populated = RtPipeline.hitRegion(stack, new RtPipeline.HitTable(
                    new VulkanDeviceAddressRange(new VulkanDeviceAddress(4096L), 192L), 64L));
            assertEquals(4096L, populated.deviceAddress());
            assertEquals(64L, populated.stride());
            assertEquals(192L, populated.size());
        }
    }
}
