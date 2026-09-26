package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.RtAccel;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.RetainedGeometryRecordData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Pure planning and CPU ABI packing for native retained geometry. */
public final class RtRetainedGeometryPlan {
    // Only radiance rays use TraceRay, with SBT stride 1; visibility uses inline ray queries.
    public static final int HIT_RECORDS_PER_GEOMETRY = 1;
    public static final int RECORD_BYTES = RetainedGeometryRecordData.BYTE_SIZE;

    public static final int SURFACE_IMPLEMENTATION_OFFSET = 0;
    public static final int COVERAGE_IMPLEMENTATION_OFFSET = 4;
    public static final int VOLUME_IMPLEMENTATION_OFFSET = 8;
    public static final int FLAGS_OFFSET = 12;
    public static final int SURFACE_BINDING_OFFSET = 16;
    public static final int VOLUME_BINDING_OFFSET = 24;
    public static final int ALPHA_CUTOFF_OFFSET = 32;
    public static final int INSTANCE_INDEX_OFFSET = 36;
    public static final int EMITTER_INDEX_ADDRESS_OFFSET = 40;
    public static final int EMITTER_PRIMITIVE_BASE_OFFSET = 48;
    public static final int FIRST_INDEX_OFFSET = 52;
    public static final int INDEX_ADDRESS_OFFSET = 56;

    public static final int HAS_SURFACE = 1;
    public static final int HAS_VOLUME = 2;
    public static final int CUTOUT = 4;
    public static final int STOCHASTIC = 8;
    public static final int GUARANTEED_SHADOW_BLOCKER = 16;

    private RtRetainedGeometryPlan() { }

    public static List<RtAccel.GeometryRange> blasRanges(MeshBuild<?> build) {
        return build.geometries().stream().map(geometry -> new RtAccel.GeometryRange(
                geometry.firstIndex(), geometry.indexCount(), isOpaque(geometry), geometry.opacityMicromap())).toList();
    }

    /** A BLAS can be shared only when every acceleration-structure-visible input is identical. */
    public static boolean canReuseBlas(MeshBuild<?> previous, MeshBuild<?> next) {
        return previous.buildPolicy() == next.buildPolicy()
                && previous.positions().equals(next.positions())
                && previous.indices().equals(next.indices())
                && previous.vertexCount() == next.vertexCount()
                && previous.indexRevision() != null
                && previous.indexRevision().equals(next.indexRevision())
                && blasRanges(previous).equals(blasRanges(next));
    }

    /** Refit requires both revisions to permit updates and preserve the UPDATE layout. */
    public static boolean canRefitBlas(MeshBuild<?> previous, MeshBuild<?> next) {
        return previous.buildPolicy() == MeshBuild.BuildPolicy.REFITTABLE
                && previous.geometries().stream().noneMatch(geometry -> geometry.opacityMicromap() != null)
                && next.geometries().stream().noneMatch(geometry -> geometry.opacityMicromap() != null)
                && next.buildPolicy() == MeshBuild.BuildPolicy.REFITTABLE
                && !previous.positions().equals(next.positions())
                && previous.positions().byteStride() == next.positions().byteStride()
                && RetainedSceneSnapshot.vertexTopologyCompatible(previous, next)
                && blasRanges(previous).equals(blasRanges(next));
    }

    /** Resolves producer shader identities against the exact composition retained by the renderer revision. */
    public static ResolvedMesh resolve(MeshBuild<?> build, ProgramComposition composition) {
        List<ResolvedGeometry> geometries = new ArrayList<>(build.geometries().size());
        for (MeshBuild.Geometry<?> geometry : build.geometries()) {
            geometries.add(new ResolvedGeometry(geometry,
                    geometry.surface() == null ? 0 : composition.resolve(geometry.surface().surface()),
                    geometry.volume() == null ? 0 : composition.resolve(geometry.volume().volume()),
                    geometry.surface() == null ? 0L
                    : geometry.surface().bindingData().bits(), geometry.volume() == null ? 0L
                    : geometry.volume().bindingData().bits()));
        }
        return new ResolvedMesh(build, geometries);
    }

    /** Builds current geometry records against the exact instance table retained by the revision. */
    public static List<GeometryRecord> records(ResolvedMesh mesh, int instanceIndex) {
        List<GeometryRecord> records = new ArrayList<>(mesh.geometries().size());
        for (ResolvedGeometry resolved : mesh.geometries()) {
            MeshBuild.Geometry<?> geometry = resolved.geometry();
            int surface = resolved.surfaceImplementation();
            MeshBuild.CoveragePolicy coveragePolicy = geometry.surface() == null
                    ? null : geometry.surface().coverage();
            boolean cutout = coveragePolicy instanceof MeshBuild.CoveragePolicy.Cutout;
            boolean stochastic = coveragePolicy instanceof MeshBuild.CoveragePolicy.Stochastic;
            int coverage = cutout || stochastic ? surface : 0;
            int volume = resolved.volumeImplementation();
            int flags = (geometry.surface() == null ? 0 : HAS_SURFACE)
                    | (geometry.volume() == null ? 0 : HAS_VOLUME)
                    | (cutout ? CUTOUT : 0)
                    | (stochastic ? STOCHASTIC : 0)
                    | (geometry.surface() != null
                    && geometry.surface().shadow() == MeshBuild.ShadowPolicy.GUARANTEED_BLOCKER
                    ? GUARANTEED_SHADOW_BLOCKER : 0);
            float alphaCutoff = switch (coveragePolicy) {
                case MeshBuild.CoveragePolicy.Cutout policy -> policy.alphaCutoff();
                case MeshBuild.CoveragePolicy.Stochastic policy -> policy.guideAlphaCutoff();
                case MeshBuild.CoveragePolicy.Opaque ignored -> 0.0f;
                case null -> 0.0f;
            };
            records.add(new GeometryRecord(surface, coverage, volume, flags,
                    resolved.surfaceBinding(), resolved.volumeBinding(), alphaCutoff, instanceIndex,
                    mesh.build().indices().bytes().address(), geometry.firstIndex(),
                    null, 0));
        }
        return List.copyOf(records);
    }

    public record ResolvedMesh(MeshBuild<?> build, List<ResolvedGeometry> geometries) {
        public ResolvedMesh {
            java.util.Objects.requireNonNull(build, "build");
            geometries = List.copyOf(geometries);
            if (geometries.size() != build.geometries().size()) {
                throw new IllegalArgumentException("each geometry needs one resolved frame entry");
            }
        }
    }

    public record ResolvedGeometry(MeshBuild.Geometry<?> geometry,
                                   int surfaceImplementation, int volumeImplementation,
                                   long surfaceBinding, long volumeBinding) {
        public ResolvedGeometry {
            java.util.Objects.requireNonNull(geometry, "geometry");
            if (surfaceImplementation < 0 || volumeImplementation < 0) {
                throw new IllegalArgumentException("implementation indices must be non-negative");
            }
        }
    }

    public static ByteBuffer pack(List<GeometryRecord> records) {
        ByteBuffer packed = ByteBuffer.allocate(Math.multiplyExact(records.size(), RECORD_BYTES))
                .order(ByteOrder.LITTLE_ENDIAN);
        packInto(packed, records);
        return packed.flip();
    }

    /** Writes directly to exclusively owned revision upload storage. */
    static void packInto(ByteBuffer packed, List<GeometryRecord> records) {
        for (GeometryRecord record : records) {
            packRecord(packed, record, record.instanceIndex());
        }
    }

    /** Binds shared geometry templates to one entry in this revision's instance table. */
    static void packInto(ByteBuffer packed, List<GeometryRecord> records, int instanceIndex) {
        for (GeometryRecord record : records) packRecord(packed, record, instanceIndex);
    }

    private static void packRecord(ByteBuffer packed, GeometryRecord record, int instanceIndex) {
        int base = packed.position();
        new RetainedGeometryRecordData(record.surfaceImplementation(), record.coverageImplementation(),
                record.volumeImplementation(), record.flags(), record.surfaceBinding(),
                record.volumeBinding(), record.alphaCutoff(), instanceIndex,
                record.emitterIndexAddress() == null ? 0L : record.emitterIndexAddress().value(),
                record.emitterPrimitiveBase(), record.firstIndex(), record.indexAddress().value())
                .write(packed.slice(base, RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN));
        packed.position(base + RECORD_BYTES);
    }

    public static List<HitGroup> hitGroups(List<GeometryRecord> records) {
        List<HitGroup> groups = new ArrayList<>(Math.multiplyExact(records.size(), HIT_RECORDS_PER_GEOMETRY));
        for (GeometryRecord record : records) {
            boolean volume = (record.flags() & HAS_VOLUME) != 0;
            // Volume boundaries must reach closest-hit for ordered medium and transmission updates.
            boolean anyHit = !volume && (record.flags() & (CUTOUT | STOCHASTIC)) != 0;
            groups.add(anyHit ? HitGroup.RADIANCE_CUTOUT : HitGroup.RADIANCE_OPAQUE);
        }
        return List.copyOf(groups);
    }

    /**
     * Opaque geometry needs no coverage test, so traversal commits it without shader involvement. Opacity does
     * not imply zero transmission: volume boundaries and uncertified surfaces are opaque and still transmit.
     * Every guaranteed shadow blocker is opaque, so visibility queries see a blocker only as a committed hit.
     */
    private static boolean isOpaque(MeshBuild.Geometry<?> geometry) {
        return geometry.volume() != null || geometry.surface() == null
                || geometry.surface().coverage() instanceof MeshBuild.CoveragePolicy.Opaque;
    }

    public record GeometryRecord(int surfaceImplementation, int coverageImplementation,
                                 int volumeImplementation, int flags, long surfaceBinding,
                                 long volumeBinding, float alphaCutoff, int instanceIndex,
                                 VulkanDeviceAddress indexAddress, int firstIndex,
                                 VulkanDeviceAddress emitterIndexAddress, int emitterPrimitiveBase) {
        GeometryRecord withEmitterIndex(VulkanDeviceAddress address, int primitiveBase) {
            return new GeometryRecord(surfaceImplementation, coverageImplementation, volumeImplementation,
                    flags, surfaceBinding, volumeBinding, alphaCutoff, instanceIndex, indexAddress,
                    firstIndex, java.util.Objects.requireNonNull(address, "address"), primitiveBase);
        }
    }

    public enum HitGroup {
        RADIANCE_OPAQUE,
        RADIANCE_CUTOUT
    }
}
