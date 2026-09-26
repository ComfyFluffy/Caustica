package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.RtAccel;
import dev.comfyfluffy.caustica.renderer.raytracing.pipeline.RtPipeline;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtRetainedGeometryPlanTest {
    interface Binding { }
    interface Instance { }
    private static final ShaderDataType<Binding> BINDING = ShaderDataType.create("binding");
    private static final SurfaceId<Binding, Instance> SURFACE = new SurfaceId<>() { };
    private static final VolumeId<Binding, Instance> VOLUME = new VolumeId<>() { };
    private static final ProgramComposition PROGRAMS = new ProgramComposition(List.of(), java.util.Map.of(SURFACE, 1));

    @Test
    void onlyCertifiedSurfaceGetsBlockerFlagAndCapturePreservesItsCertificate() {
        var ordinary = new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(11), new MeshBuild.CoveragePolicy.Opaque());
        var blocker = new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(22), new MeshBuild.CoveragePolicy.Opaque(),
                MeshBuild.ShadowPolicy.GUARANTEED_BLOCKER);
        var uncertified = build(new MeshBuild.IndexRevision(7), 0x1000, ordinary, ordinary);
        var certified = build(new MeshBuild.IndexRevision(7), 0x1000, ordinary, blocker);
        assertEquals(RtRetainedGeometryPlan.blasRanges(uncertified), RtRetainedGeometryPlan.blasRanges(certified));
        assertTrue(RtRetainedGeometryPlan.canReuseBlas(uncertified, certified));
        try (var owners = new dev.comfyfluffy.caustica.engine.resource.ResourceOwners()) {
            var captured = owners.mesh(certified);
            assertEquals(blocker.shadow(), captured.geometries().get(1).surface().shadow());
            var records = RtRetainedGeometryPlan.records(RtRetainedGeometryPlan.resolve(captured, PROGRAMS), 3);
            assertEquals(0, records.get(0).flags() & RtRetainedGeometryPlan.GUARANTEED_SHADOW_BLOCKER);
            assertEquals(RtRetainedGeometryPlan.GUARANTEED_SHADOW_BLOCKER,
                    records.get(1).flags() & RtRetainedGeometryPlan.GUARANTEED_SHADOW_BLOCKER);
            assertEquals(List.of(RtRetainedGeometryPlan.HitGroup.RADIANCE_OPAQUE,
                            RtRetainedGeometryPlan.HitGroup.RADIANCE_OPAQUE),
                    RtRetainedGeometryPlan.hitGroups(records));
            assertEquals(records.get(1).flags(), RtRetainedGeometryPlan.pack(records)
                    .getInt(RtRetainedGeometryPlan.RECORD_BYTES + RtRetainedGeometryPlan.FLAGS_OFFSET));
        }
    }

    @Test void changedOpacityRequiresRebuildAndMicromapMeshesDoNotRefit() {
        var surface = new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(1), new MeshBuild.CoveragePolicy.Cutout(.5f));
        var opaque = new dev.comfyfluffy.caustica.api.geometry.OpacityMicromap(0,1,new byte[]{1});
        var transparent = new dev.comfyfluffy.caustica.api.geometry.OpacityMicromap(0,1,new byte[]{0});
        var first = new MeshBuild<>(stream(0x1000,96,12), stream(0x2000,12,4),8,
                new MeshBuild.IndexRevision(1), MeshBuild.BuildPolicy.REFITTABLE,
                List.of(new MeshBuild.Geometry<>(surface,null,0,3,opaque)));
        var changed = new MeshBuild<>(first.positions(),first.indices(),8,first.indexRevision(),first.buildPolicy(),
                List.of(new MeshBuild.Geometry<>(surface,null,0,3,transparent)));
        var moved = new MeshBuild<>(stream(0x3000,96,12),first.indices(),8,first.indexRevision(),first.buildPolicy(),first.geometries());
        assertTrue(RtRetainedGeometryPlan.canReuseBlas(first,first));
        assertFalse(RtRetainedGeometryPlan.canReuseBlas(first,changed));
        assertFalse(RtRetainedGeometryPlan.canRefitBlas(first,moved));
        assertEquals(RtRetainedGeometryPlan.hitGroups(RtRetainedGeometryPlan.records(
                RtRetainedGeometryPlan.resolve(first,PROGRAMS),0)),
                List.of(RtRetainedGeometryPlan.HitGroup.RADIANCE_CUTOUT));
    }

    @Test
    void blasRangesPreserveSliceOrderOffsetsAndCoverageClass() {
        MeshBuild<Instance> build = build(new MeshBuild.IndexRevision(7), 0x1000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(11), new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(22),
                        new MeshBuild.CoveragePolicy.Cutout(0.4f)));

        var ranges = RtRetainedGeometryPlan.blasRanges(build);

        assertEquals(List.of(
                new RtAccel.GeometryRange(3, 6, true),
                new RtAccel.GeometryRange(12, 9, false)), ranges);
    }

    @Test
    void stochasticCoverageUsesAnyHitAndPreservesItsGuideCutoff() {
        MeshBuild<Instance> build = build(new MeshBuild.IndexRevision(7), 0x1000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(11),
                        new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(22),
                        new MeshBuild.CoveragePolicy.Stochastic(0.35f)));
        var record = RtRetainedGeometryPlan.records(RtRetainedGeometryPlan.resolve(build, PROGRAMS), 17).get(1);

        assertFalse(RtRetainedGeometryPlan.blasRanges(build).get(1).opaque());
        assertTrue((record.flags() & RtRetainedGeometryPlan.STOCHASTIC) != 0);
        assertEquals(0.35f, record.alphaCutoff());
        assertEquals(17, record.instanceIndex());
        assertEquals(build.indices().bytes().address(), record.indexAddress());
        assertEquals(12, record.firstIndex());
        assertEquals(List.of(RtRetainedGeometryPlan.HitGroup.RADIANCE_CUTOUT),
                RtRetainedGeometryPlan.hitGroups(List.of(record)));
    }

    @Test
    void geometrySlicesShareThePublishedInstanceEntry() {
        MeshBuild<Instance> build = build(new MeshBuild.IndexRevision(7), 0x1000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(11),
                        new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(22),
                        new MeshBuild.CoveragePolicy.Opaque()));
        var records = RtRetainedGeometryPlan.records(RtRetainedGeometryPlan.resolve(build, PROGRAMS), 319);

        assertEquals(List.of(319, 319), records.stream().map(
                RtRetainedGeometryPlan.GeometryRecord::instanceIndex).toList());
    }

    @Test
    void reuseRequiresStableInputsRevisionAndGeometryShapeButNotBindingWords() {
        MeshBuild<Instance> first = build(new MeshBuild.IndexRevision(9), 0x1000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(1), new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(2),
                        new MeshBuild.CoveragePolicy.Cutout(0.25f)));
        MeshBuild<Instance> bindingOnly = build(new MeshBuild.IndexRevision(9), 0x1000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(10), new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(20),
                        new MeshBuild.CoveragePolicy.Cutout(0.75f)));
        MeshBuild<Instance> movedPositions = build(new MeshBuild.IndexRevision(9), 0x3000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(10), new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(20),
                        new MeshBuild.CoveragePolicy.Cutout(0.75f)));

        assertTrue(RtRetainedGeometryPlan.canReuseBlas(first, bindingOnly));
        assertFalse(RtRetainedGeometryPlan.canReuseBlas(first, movedPositions));
        assertFalse(RtRetainedGeometryPlan.canReuseBlas(first,
                build(null, 0x1000,
                        new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(10),
                                new MeshBuild.CoveragePolicy.Opaque()),
                        new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(20),
                                new MeshBuild.CoveragePolicy.Cutout(0.75f)))));
    }

    @Test
    void refitRequiresChangedPositionsAndAnIdenticalUpdateLayout() {
        MeshBuild<Instance> first = build(new MeshBuild.IndexRevision(9), 0x1000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(1), new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(2),
                        new MeshBuild.CoveragePolicy.Cutout(0.25f)));
        MeshBuild<Instance> moved = build(new MeshBuild.IndexRevision(9), 0x3000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(10), new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(20),
                        new MeshBuild.CoveragePolicy.Cutout(0.75f)));
        MeshBuild<Instance> changedTraversal = build(new MeshBuild.IndexRevision(9), 0x3000,
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(10), new MeshBuild.CoveragePolicy.Opaque()),
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(20),
                        new MeshBuild.CoveragePolicy.Opaque()));

        assertTrue(RtRetainedGeometryPlan.canRefitBlas(first, moved));
        assertFalse(RtRetainedGeometryPlan.canRefitBlas(first, first));
        assertFalse(RtRetainedGeometryPlan.canRefitBlas(first, changedTraversal));
        assertFalse(RtRetainedGeometryPlan.canRefitBlas(first,
                build(new MeshBuild.IndexRevision(10), 0x3000,
                        new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(10),
                                new MeshBuild.CoveragePolicy.Opaque()),
                        new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(20),
                                new MeshBuild.CoveragePolicy.Cutout(0.75f)))));
    }

    @Test
    void staticBlasCanBeSharedButCannotBeRefittedOrReusedAcrossPolicies() {
        var slot = new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(1),
                new MeshBuild.CoveragePolicy.Opaque());
        var refittable = build(new MeshBuild.IndexRevision(9), 0x1000, slot, slot);
        var moved = build(new MeshBuild.IndexRevision(9), 0x3000, slot, slot);
        var fixed = withPolicy(refittable, MeshBuild.BuildPolicy.STATIC);
        var fixedMoved = withPolicy(moved, MeshBuild.BuildPolicy.STATIC);

        assertTrue(RtRetainedGeometryPlan.canReuseBlas(fixed, fixed));
        assertFalse(RtRetainedGeometryPlan.canReuseBlas(fixed, refittable));
        assertFalse(RtRetainedGeometryPlan.canReuseBlas(refittable, fixed));
        assertFalse(RtRetainedGeometryPlan.canRefitBlas(fixed, fixedMoved));
        assertFalse(RtRetainedGeometryPlan.canRefitBlas(fixed, moved));
        assertFalse(RtRetainedGeometryPlan.canRefitBlas(refittable, fixedMoved));
        assertTrue(RtRetainedGeometryPlan.canRefitBlas(refittable, moved));
    }

    private static MeshBuild<Instance> withPolicy(MeshBuild<Instance> build, MeshBuild.BuildPolicy policy) {
        return new MeshBuild<>(build.positions(), build.indices(), build.vertexCount(),
                build.indexRevision(), policy, build.geometries());
    }

    @Test
    void geometryAbiPacksProgramsRootsAndCurrentInstanceIndices() {
        var first = new RtRetainedGeometryPlan.GeometryRecord(3, 3, 5,
                RtRetainedGeometryPlan.HAS_SURFACE | RtRetainedGeometryPlan.HAS_VOLUME
                        | RtRetainedGeometryPlan.CUTOUT,
                0x1111, 0x2222, 0.45f, 0x3333, new VulkanDeviceAddress(0x6660), 3,
                new VulkanDeviceAddress(0x4444), 6);
        var second = new RtRetainedGeometryPlan.GeometryRecord(7, 0, 0,
                RtRetainedGeometryPlan.HAS_SURFACE, 0x4444, 0, 0, 0x5555,
                new VulkanDeviceAddress(0x8880), 12, null, 0);

        ByteBuffer packed = RtRetainedGeometryPlan.pack(List.of(first, second));

        assertEquals(64, RtRetainedGeometryPlan.RECORD_BYTES);
        assertEquals(2 * RtRetainedGeometryPlan.RECORD_BYTES, packed.remaining());
        assertEquals(3, packed.getInt(RtRetainedGeometryPlan.SURFACE_IMPLEMENTATION_OFFSET));
        assertEquals(3, packed.getInt(RtRetainedGeometryPlan.COVERAGE_IMPLEMENTATION_OFFSET));
        assertEquals(5, packed.getInt(RtRetainedGeometryPlan.VOLUME_IMPLEMENTATION_OFFSET));
        assertEquals(0x1111, packed.getLong(RtRetainedGeometryPlan.SURFACE_BINDING_OFFSET));
        assertEquals(0x2222, packed.getLong(RtRetainedGeometryPlan.VOLUME_BINDING_OFFSET));
        assertEquals(0x3333, packed.getInt(RtRetainedGeometryPlan.INSTANCE_INDEX_OFFSET));
        assertEquals(0.45f, packed.getFloat(RtRetainedGeometryPlan.ALPHA_CUTOFF_OFFSET));
        assertEquals(0x4444, packed.getLong(RtRetainedGeometryPlan.EMITTER_INDEX_ADDRESS_OFFSET));
        assertEquals(6, packed.getInt(RtRetainedGeometryPlan.EMITTER_PRIMITIVE_BASE_OFFSET));
        assertEquals(0x6660, packed.getLong(RtRetainedGeometryPlan.INDEX_ADDRESS_OFFSET));
        assertEquals(3, packed.getInt(RtRetainedGeometryPlan.FIRST_INDEX_OFFSET));
        assertEquals(0x5555, packed.getInt(64 + RtRetainedGeometryPlan.INSTANCE_INDEX_OFFSET));
        assertEquals(0, packed.getLong(64 + RtRetainedGeometryPlan.EMITTER_INDEX_ADDRESS_OFFSET));
        assertEquals(List.of(RtRetainedGeometryPlan.HitGroup.RADIANCE_OPAQUE,
                        RtRetainedGeometryPlan.HitGroup.RADIANCE_OPAQUE),
                RtRetainedGeometryPlan.hitGroups(List.of(first, second)));
    }

    @Test
    void revisionPackingBindsInstanceIndicesWithoutMutatingSharedTemplates() {
        var template = new RtRetainedGeometryPlan.GeometryRecord(1, 0, 0,
                RtRetainedGeometryPlan.HAS_SURFACE, 23, 0, 0, 7,
                new VulkanDeviceAddress(0x2000), 3, null, 0);
        ByteBuffer first = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer second = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);

        RtRetainedGeometryPlan.packInto(first, List.of(template), 41);
        RtRetainedGeometryPlan.packInto(second, List.of(template), 83);

        assertEquals(41, first.getInt(RtRetainedGeometryPlan.INSTANCE_INDEX_OFFSET));
        assertEquals(83, second.getInt(RtRetainedGeometryPlan.INSTANCE_INDEX_OFFSET));
        assertEquals(7, template.instanceIndex());
        assertEquals(64, first.position());
        assertEquals(64, second.position());
        assertEquals(23, second.getLong(RtRetainedGeometryPlan.SURFACE_BINDING_OFFSET));
    }

    @Test
    void volumeBoundaryUsesOpaqueRadianceRouting() {
        MeshBuild.Stream positions = stream(0x1000, 256, 12);
        MeshBuild.Stream indices = stream(0x2000, 64, 4);
        MeshBuild<Instance> build = new MeshBuild<>(positions, indices, 8,
                new MeshBuild.IndexRevision(1), MeshBuild.BuildPolicy.REFITTABLE, List.of(new MeshBuild.Geometry<>(
                new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(1),
                        new MeshBuild.CoveragePolicy.Cutout(0.5f)),
                new MeshBuild.VolumeSlot<>(VOLUME, BINDING.data(2)), 0, 6)));
        var range = RtRetainedGeometryPlan.blasRanges(build).getFirst();
        var record = new RtRetainedGeometryPlan.GeometryRecord(1, 1, 2,
                RtRetainedGeometryPlan.HAS_SURFACE | RtRetainedGeometryPlan.HAS_VOLUME
                        | RtRetainedGeometryPlan.CUTOUT,
                1, 2, 0.5f, 3, indices.bytes().address(), 0, null, 0);

        assertTrue(range.opaque());
        assertEquals(List.of(RtRetainedGeometryPlan.HitGroup.RADIANCE_OPAQUE),
                RtRetainedGeometryPlan.hitGroups(List.of(record)));
        assertTrue((record.flags() & RtRetainedGeometryPlan.CUTOUT) != 0);
    }

    @Test
    void onlyCoverageTestedSurfacesWithoutVolumeAreNonOpaque() {
        var opaque = new MeshBuild.CoveragePolicy.Opaque();
        var cutout = new MeshBuild.CoveragePolicy.Cutout(0.5f);
        var volume = new MeshBuild.VolumeSlot<>(VOLUME, BINDING.data(9));
        var micromap = new dev.comfyfluffy.caustica.api.geometry.OpacityMicromap(0, 1, new byte[]{1});
        List<MeshBuild.Geometry<Instance>> geometries = List.of(
                new MeshBuild.Geometry<>(new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(1), opaque), null, 0, 3),
                new MeshBuild.Geometry<>(new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(2), opaque,
                        MeshBuild.ShadowPolicy.GUARANTEED_BLOCKER), null, 3, 3),
                new MeshBuild.Geometry<>(new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(3), opaque), volume, 6, 3),
                new MeshBuild.Geometry<>(new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(4), cutout), volume, 9, 3),
                new MeshBuild.Geometry<>(null, volume, 12, 3),
                new MeshBuild.Geometry<>(new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(5), cutout),
                        null, 15, 3, micromap),
                new MeshBuild.Geometry<>(new MeshBuild.SurfaceSlot<>(SURFACE, BINDING.data(6),
                        new MeshBuild.CoveragePolicy.Stochastic(0.5f)), null, 18, 3));
        MeshBuild<Instance> build = new MeshBuild<>(stream(0x1000, 96, 12), stream(0x2000, 84, 4), 8,
                new MeshBuild.IndexRevision(1), MeshBuild.BuildPolicy.STATIC, geometries);

        var ranges = RtRetainedGeometryPlan.blasRanges(build);
        var records = RtRetainedGeometryPlan.records(RtRetainedGeometryPlan.resolve(build, PROGRAMS), 0);

        // Visibility queries test coverage only on non-opaque candidates and see blockers only as committed hits.
        assertEquals(List.of(true, true, true, true, true, false, false),
                ranges.stream().map(RtAccel.GeometryRange::opaque).toList());
        for (int geometry = 0; geometry < records.size(); geometry++) {
            int flags = records.get(geometry).flags();
            assertEquals(!ranges.get(geometry).opaque(),
                    (flags & (RtRetainedGeometryPlan.CUTOUT | RtRetainedGeometryPlan.STOCHASTIC)) != 0
                            && (flags & RtRetainedGeometryPlan.HAS_VOLUME) == 0);
        }
        assertEquals(RtRetainedGeometryPlan.GUARANTEED_SHADOW_BLOCKER,
                records.get(1).flags() & RtRetainedGeometryPlan.GUARANTEED_SHADOW_BLOCKER);
    }

    @Test
    void preparedTraceWritesOnlyItsOwnedWorldRoots() {
        int prefix = 9;
        ByteBuffer storage = ByteBuffer.allocate(prefix + RtBindings.WORLD_PUSH_CONSTANT_SIZE + 7)
                .order(ByteOrder.nativeOrder());
        for (int index = 0; index < storage.capacity(); index++) storage.put(index, (byte) 0x5a);
        ByteBuffer roots = storage.duplicate().order(ByteOrder.nativeOrder());
        roots.position(prefix).limit(prefix + RtBindings.WORLD_PUSH_CONSTANT_SIZE);
        ByteBuffer window = roots.slice().order(ByteOrder.nativeOrder());
        window.putLong(RtBindings.WORLD_COMPOSITION_DATA_ADDRESS_OFFSET, 0x7777L);
        var trace = new RtRetainedSceneBackend.PreparedTrace(
                new VulkanDeviceAddress(0x1234L), new VulkanDeviceAddress(0x5678L), 19,
                new RtPipeline.HitTable(new VulkanDeviceAddressRange(
                        new VulkanDeviceAddress(0x8000L), 256), 64));

        trace.writeWorldRoots(roots);

        assertEquals(0x1234L, window.getLong(RtBindings.WORLD_GEOMETRY_TABLE_ADDRESS_OFFSET));
        assertEquals(19, window.getInt(RtBindings.WORLD_TOP_LEVEL_AS_INDEX_OFFSET));
        assertEquals(0x5678L, window.getLong(RtBindings.WORLD_NEE_AT_STATE_ADDRESS_OFFSET));
        assertEquals(0x7777L, window.getLong(RtBindings.WORLD_COMPOSITION_DATA_ADDRESS_OFFSET));
        assertEquals(prefix, roots.position());
        assertEquals(prefix + RtBindings.WORLD_PUSH_CONSTANT_SIZE, roots.limit());
        for (int index = 0; index < prefix; index++) assertEquals(0x5a, storage.get(index));
        for (int index = roots.limit(); index < storage.capacity(); index++) assertEquals(0x5a, storage.get(index));
    }

    private static MeshBuild<Instance> build(MeshBuild.IndexRevision revision, long positionAddress,
                                             MeshBuild.SurfaceSlot<Binding, Instance> first,
                                             MeshBuild.SurfaceSlot<Binding, Instance> second) {
        return new MeshBuild<>(stream(positionAddress, 256, 12),
                stream(0x2000, 128, 4), 8, revision, MeshBuild.BuildPolicy.REFITTABLE, List.of(
                new MeshBuild.Geometry<>(first, null, 3, 6),
                new MeshBuild.Geometry<>(second, null, 12, 9)));
    }

    private static MeshBuild.Stream stream(long address, long bytes, int stride) {
        return new MeshBuild.Stream(new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), bytes), stride,
                ResourceOwner.none());
    }
}
