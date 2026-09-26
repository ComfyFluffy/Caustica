package dev.comfyfluffy.caustica.renderer.raytracing.pipeline;

import dev.comfyfluffy.caustica.renderer.raytracing.scene.RtRetainedGeometryPlan;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtPipelineRetainedHitPackingTest {
    @Test
    void duplicatesFixedGroupHandlesInPlacementGeometryOrder() {
        int groupCount = RtRetainedGeometryPlan.HitGroup.values().length;
        ByteBuffer handles = ByteBuffer.allocate(groupCount * 4);
        for (int group = 0; group < groupCount; group++) handles.putInt(group * 4, 100 + group);
        handles.position(3);

        ByteBuffer packed = RtPipeline.packRetainedHitRecords(handles, 4, 8, List.of(
                RtRetainedGeometryPlan.HitGroup.RADIANCE_CUTOUT,
                RtRetainedGeometryPlan.HitGroup.RADIANCE_OPAQUE,
                RtRetainedGeometryPlan.HitGroup.RADIANCE_OPAQUE,
                RtRetainedGeometryPlan.HitGroup.RADIANCE_CUTOUT));

        assertEquals(101, packed.getInt(0));
        assertEquals(100, packed.getInt(8));
        assertEquals(100, packed.getInt(16));
        assertEquals(101, packed.getInt(24));
        for (int record = 0; record < 4; record++) assertEquals(0, packed.getInt(record * 8 + 4));
        assertEquals(3, handles.position());
        assertEquals(0, packed.position());
        assertEquals(32, packed.remaining());
    }
}
