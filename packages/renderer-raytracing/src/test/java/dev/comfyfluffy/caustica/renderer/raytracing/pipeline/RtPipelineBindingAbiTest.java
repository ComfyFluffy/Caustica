package dev.comfyfluffy.caustica.renderer.raytracing.pipeline;

import dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings;
import org.junit.jupiter.api.Test;

import static dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings.WORLD_COMPOSITION_DATA_ADDRESS_OFFSET;
import static dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings.WORLD_GEOMETRY_TABLE_ADDRESS_OFFSET;
import static dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings.WORLD_OUTPUT_IMAGE_INDEX_OFFSET;
import static dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings.WORLD_PATH_QUEUE_ADDRESS_OFFSET;
import static dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings.WORLD_PUSH_ADDRESS_OFFSET;
import static dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings.WORLD_PUSH_CONSTANT_SIZE;
import static dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings.WORLD_STABLE_PLANE_METADATA_IMAGE_INDEX_OFFSET;
import static dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings.WORLD_SPECULAR_MOTION_GUIDE_INDEX_OFFSET;
import static dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings.WORLD_TOP_LEVEL_AS_INDEX_OFFSET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class RtPipelineBindingAbiTest {
    @Test
    void reflectionPreservesDeviceAddressRootsBeforeHeapIndices() {
        assertEquals(0, WORLD_PUSH_ADDRESS_OFFSET);
        assertEquals(8, WORLD_COMPOSITION_DATA_ADDRESS_OFFSET);
        assertEquals(16, WORLD_GEOMETRY_TABLE_ADDRESS_OFFSET);
        assertEquals(24, WORLD_PATH_QUEUE_ADDRESS_OFFSET);
        assertEquals(32, WORLD_TOP_LEVEL_AS_INDEX_OFFSET);
        assertEquals(36, WORLD_OUTPUT_IMAGE_INDEX_OFFSET);
        assertEquals(40, WORLD_STABLE_PLANE_METADATA_IMAGE_INDEX_OFFSET);
        assertEquals(56, RtBindings.WORLD_PRIMARY_DEPTH_INDEX_OFFSET);
        assertEquals(68, WORLD_SPECULAR_MOTION_GUIDE_INDEX_OFFSET);
        assertEquals(72, RtBindings.WORLD_DIFFUSE_RADIANCE_HIT_DISTANCE_INDEX_OFFSET);
        assertEquals(76, RtBindings.WORLD_SPECULAR_RADIANCE_HIT_DISTANCE_INDEX_OFFSET);
        assertEquals(80, RtBindings.WORLD_NRD_VIEW_Z_INDEX_OFFSET);
        assertEquals(84, RtBindings.WORLD_DENOISED_DIFFUSE_RADIANCE_HIT_DISTANCE_INDEX_OFFSET);
        assertEquals(88, RtBindings.WORLD_DENOISED_SPECULAR_RADIANCE_HIT_DISTANCE_INDEX_OFFSET);
        assertEquals(92, RtBindings.WORLD_NRD_STABLE_RADIANCE_INDEX_OFFSET);
        assertEquals(96, RtBindings.WORLD_RESPONSIVITY_GUIDE_INDEX_OFFSET);
        assertEquals(100, RtBindings.WORLD_INITIAL_VOLUME_IMPLEMENTATION_OFFSET);
        assertEquals(104, RtBindings.WORLD_INITIAL_VOLUME_ACTIVE_OFFSET);
        assertEquals(112, RtBindings.WORLD_INITIAL_VOLUME_BINDING_OFFSET);
        assertEquals(120, RtBindings.WORLD_INITIAL_VOLUME_INSTANCE_OFFSET);
        assertEquals(128, RtBindings.WORLD_NEE_AT_STATE_ADDRESS_OFFSET);
        assertEquals(136, RtBindings.WORLD_NRD_SIGNAL_ENCODING_OFFSET);
        assertEquals(140, RtBindings.WORLD_RECONSTRUCTION_MICRO_JITTER_SCALE_OFFSET);
        assertEquals(144, RtBindings.WORLD_STABLE_PLANE_BUFFER_ADDRESS_OFFSET);
        assertEquals(152, RtBindings.WORLD_VISIBILITY_RAYS_ADDRESS_OFFSET);
        assertEquals(160, RtBindings.WORLD_VISIBILITY_RESULTS_ADDRESS_OFFSET);
        assertEquals(168, RtBindings.WORLD_SPATIAL_MEDIUM_BINDING_DATA_OFFSET);
        assertEquals(176, RtBindings.WORLD_SPATIAL_MEDIUM_INSTANCE_DATA_OFFSET);
        assertEquals(184, RtBindings.WORLD_SPATIAL_MEDIUM_IMPLEMENTATION_OFFSET);
        assertEquals(188, RtBindings.WORLD_SPATIAL_MEDIUM_ACTIVE_OFFSET);
        assertEquals(192, RtBindings.WORLD_SPATIAL_MEDIUM_ORIGIN_X_OFFSET);
        assertEquals(196, RtBindings.WORLD_SPATIAL_MEDIUM_ORIGIN_Y_OFFSET);
        assertEquals(200, RtBindings.WORLD_SPATIAL_MEDIUM_ORIGIN_Z_OFFSET);
        assertEquals(208, RtBindings.WORLD_SHADOW_DIAGNOSTICS_ADDRESS_OFFSET);
        assertEquals(216, RtBindings.WORLD_SHARC_FRAME_ADDRESS_OFFSET);
        assertEquals(224, WORLD_PUSH_CONSTANT_SIZE);
    }

    @Test
    void worldLayoutContainsNoDescriptorSetOrProviderTextureConstants() {
        var names = java.util.Arrays.stream(RtBindings.class.getFields())
                .map(java.lang.reflect.Field::getName)
                .filter(name -> name.startsWith("WORLD_"))
                .toList();
        assertFalse(names.stream().anyMatch(name -> name.contains("DESCRIPTOR_SET")));
        assertFalse(names.stream().anyMatch(name -> name.contains("PROVIDER_TEXTURE")));
        assertFalse(names.stream().anyMatch(name -> name.contains("INSTANCE_HISTORY")));
        assertFalse(names.stream().anyMatch(name -> name.contains("MATERIAL_TABLE")));
        assertFalse(names.stream().anyMatch(name -> name.contains("MATERIAL_SURFACE")));
        assertFalse(names.stream().anyMatch(name -> name.contains("RESERVED")));
    }
}
