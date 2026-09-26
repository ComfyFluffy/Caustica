module retained_trace_ordinary;

import retained_trace_policy;
import bindings;
import world_minimal;

public struct RetainedOrdinaryTrace : IRetainedRadianceTrace {
    public void trace(inout WorldPayload payload, RayDesc ray, uint pathPhaseHint) {
        TraceRay(worldAccelerationStructure(), RAY_FLAG_NONE, WORLD_RAY_MASK_SECONDARY,
                0u, 1u, 0u, ray, payload);
    }
};
