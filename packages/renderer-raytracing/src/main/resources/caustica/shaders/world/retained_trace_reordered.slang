module retained_trace_reordered;

import retained_trace_policy;
import bindings;
import world_common;
import world_minimal;

public struct RetainedReorderedTrace : IRetainedRadianceTrace {
    public void trace(inout WorldPayload payload, RayDesc ray, uint pathPhaseHint) {
        HitObject hit = HitObject::TraceRay(worldAccelerationStructure(), RAY_FLAG_NONE,
                WORLD_RAY_MASK_SECONDARY, 0u, 1u, 0u, ray, payload);
        // Reordering already groups by the hit object's shader: miss, opaque, or cutout. The hint adds
        // what raygen branches on after reordering, most significant first: whether this segment ends
        // the path, whether the hit is a volume boundary, and the surface dispatch case modulo four.
        uint coherenceHint = 0u;
        if (hit.IsHit()) {
            ConstPtr<RetainedGeometryRecord> records =
                    (ConstPtr<RetainedGeometryRecord>)worldBindings.geometryTableAddress;
            uint record = hit.GetInstanceID() + hit.GetGeometryIndex();
            coherenceHint = (pathPhaseHint << 3u)
                    | ((records[record].flags & RETAINED_GEOMETRY_HAS_VOLUME) != 0u ? 4u : 0u)
                    | (records[record].surfaceImplementation & 3u);
        }
        ReorderThread(hit, coherenceHint, 4u);
        if (hit.IsMiss()) {
            payload.hitDistance = -1.0;
            payload.pathFlags |= WORLD_PATH_MISS;
        } else {
            payload.geometryRecordIndex = hit.GetInstanceID() + hit.GetGeometryIndex();
            payload.primitiveIndex = hit.GetPrimitiveIndex();
            payload.barycentrics = hit.GetAttributes<BuiltInTriangleIntersectionAttributes>().barycentrics;
            // A hit object's ray limit is the accepted hit parameter.
            payload.hitDistance = hit.GetRayDesc().TMax;
            payload.pathFlags &= ~WORLD_PATH_MISS;
        }
    }
};
