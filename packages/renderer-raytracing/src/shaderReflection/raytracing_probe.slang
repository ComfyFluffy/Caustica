import nrd_compose_types;
import trace_diagnostics_types;
import world_common;
import path_queue_types;
import stable_plane_types;
import sharc_types;

struct WorldPushLayoutProbe { WorldPush values[2]; };
struct RetainedGeometryRecordLayoutProbe { RetainedGeometryRecord values[2]; };
struct RetainedInstanceRecordLayoutProbe { RetainedInstanceRecord values[2]; };
struct RetainedLightRecordLayoutProbe { RetainedLightRecord values[2]; };
struct NeeAtStateLayoutProbe { NeeAtState values[2]; };
struct PackedPathSegmentLayoutProbe { PackedPathSegment values[2]; };
struct PackedStablePlaneLayoutProbe { PackedStablePlane values[2]; };

[[vk::binding(0, 0)]] StructuredBuffer<WorldPushLayoutProbe, Std430DataLayout> worldPushLayoutProbe;
[[vk::binding(1, 0)]] StructuredBuffer<RetainedGeometryRecordLayoutProbe, Std430DataLayout> retainedGeometryRecordLayoutProbe;
[[vk::binding(2, 0)]] StructuredBuffer<RetainedLightRecordLayoutProbe, Std430DataLayout> retainedLightRecordLayoutProbe;
[[vk::binding(3, 0)]] StructuredBuffer<NeeAtStateLayoutProbe, Std430DataLayout> neeAtStateLayoutProbe;
[[vk::binding(4, 0)]] StructuredBuffer<PackedPathSegmentLayoutProbe, Std430DataLayout> packedPathSegmentLayoutProbe;
[[vk::binding(5, 0)]] StructuredBuffer<PackedStablePlaneLayoutProbe, Std430DataLayout> packedStablePlaneLayoutProbe;
[[vk::binding(6, 0)]] StructuredBuffer<RetainedInstanceRecordLayoutProbe, Std430DataLayout> retainedInstanceRecordLayoutProbe;

[shader("compute")]
[numthreads(1, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = worldPushLayoutProbe[0].values[0].invViewProj[0][0]
                + float(retainedGeometryRecordLayoutProbe[0].values[0].surfaceImplementation)
                + float(retainedInstanceRecordLayoutProbe[0].values[0].identity)
                + float(retainedLightRecordLayoutProbe[0].values[0].type)
                + float(neeAtStateLayoutProbe[0].values[0].lightCount)
                + float(packedPathSegmentLayoutProbe[0].values[0].flags)
                + float(packedStablePlaneLayoutProbe[0].values[0].flags);
    }
}

[[vk::push_constant]] NrdComposePush nrdComposePushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeNrdComposePush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = float(nrdComposePushProbe.denoisedDiffuseImageIndex)
                + float(nrdComposePushProbe.mode);
    }
}

struct NrdPlaneFrameLayoutProbe { NrdPlaneFrame values[2]; };
[[vk::binding(7, 0)]] StructuredBuffer<NrdPlaneFrameLayoutProbe, Std430DataLayout> nrdPlaneFrameLayoutProbe;

[shader("compute")]
[numthreads(1, 1, 1)]
void probeNrdPlaneFrame(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = nrdPlaneFrameLayoutProbe[0].values[0].currentViewProjection[0][0]
                + nrdPlaneFrameLayoutProbe[0].values[1].radianceScale;
    }
}

[[vk::push_constant]] NeeAtBakePush neeAtBakePushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeNeeAtBakePush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        uint sink = neeAtBakePushProbe.phase + neeAtBakePushProbe.currentIdentityLookupMask;
    }
}

struct ShadowDiagnosticsLayoutProbe { ShadowDiagnostics values[2]; };
[[vk::binding(8, 0)]] StructuredBuffer<ShadowDiagnosticsLayoutProbe, Std430DataLayout> shadowDiagnosticsLayoutProbe;

[shader("compute")]
[numthreads(1, 1, 1)]
void probeShadowDiagnostics(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        uint sink = shadowDiagnosticsLayoutProbe[0].values[0].maxShadowRestartsAbove32
                + shadowDiagnosticsLayoutProbe[0].values[1].firstAnomalyFlags;
    }
}

struct SharcFrameLayoutProbe { SharcFrame values[2]; };
[[vk::binding(9, 0)]] StructuredBuffer<SharcFrameLayoutProbe, Std430DataLayout> sharcFrameLayoutProbe;

[shader("compute")]
[numthreads(1, 1, 1)]
void probeSharcFrame(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = sharcFrameLayoutProbe[0].values[0].roughnessThreshold
                + float(sharcFrameLayoutProbe[0].values[1].flags);
    }
}
