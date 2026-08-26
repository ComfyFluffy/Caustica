// Build-time reflection probe only. This file is not packaged as a runtime shader.
// Exposing WorldPush as a structured-buffer element makes Slang's JSON reflection include the
// complete Std430DataLayout: field types, byte offsets, total size, matrix mode, and array counts.
import world_common;
import display_common;
import sharc_types;

struct WorldPushLayoutProbe {
    WorldPush values[2]; // array stride is the complete, tail-padded WorldPush byte size
};

struct MaterialHeaderLayoutProbe {
    MaterialHeader values[2]; // array stride is the complete, tail-padded MaterialHeader byte size
};

struct ExposureStateLayoutProbe {
    ExposureState values[2]; // array stride is the complete, tail-padded ExposureState byte size
};

struct SharcPushLayoutProbe {
    SharcPushConstants values[2];
};

struct SharcFrameLayoutProbe {
    SharcFrame values[2];
};

[[vk::binding(0, 0)]] StructuredBuffer<WorldPushLayoutProbe, Std430DataLayout> worldPushLayoutProbe;
[[vk::binding(1, 0)]] StructuredBuffer<MaterialHeaderLayoutProbe, Std430DataLayout> materialHeaderLayoutProbe;
[[vk::binding(2, 0)]] StructuredBuffer<ExposureStateLayoutProbe, Std430DataLayout> exposureStateLayoutProbe;
[[vk::binding(3, 0)]] StructuredBuffer<SharcPushLayoutProbe, Std430DataLayout> sharcPushLayoutProbe;
[[vk::binding(4, 0)]] StructuredBuffer<SharcFrameLayoutProbe, Std430DataLayout> sharcFrameLayoutProbe;
[[vk::push_constant]] WorldPushConstants pushConstantsLayoutProbe;

[shader("compute")]
[numthreads(1, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    // Keep the parameter reachable without requiring a writable output resource.
    if (id.x == 0xFFFFFFFFu) {
        float sink = worldPushLayoutProbe[0].values[0].invViewProj[0][0]
                + materialHeaderLayoutProbe[0].values[0].params.x
                + exposureStateLayoutProbe[0].values[0].previous
                + float(sharcPushLayoutProbe[0].values[0].sharcUpdateTileSize)
                + float(sharcFrameLayoutProbe[0].values[0].capacity)
                + worldPushLayoutProbe[0].values[0].mipMapBias
                + float(pushConstantsLayoutProbe.frameIndex);
    }
}

// Each display-chain push-constant struct (shaders/common/display_common.slang) gets its own tiny
// entry point below: SPIR-V permits only one push-constant block reachable per entry point, so
// probing four distinct push-constant structs in one module needs four distinct entry points, each
// touching exactly one. All four are still reported in the same reflection-json pass because
// reflection parameters are enumerated module-wide, not per-entry-point -- see the main() probe
// above for the equivalent structured-buffer case.

[[vk::push_constant]] ExposureHistPush exposureHistPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeExposureHistPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = float(exposureHistPushProbe.stride);
    }
}

[[vk::push_constant]] ExposureResolvePush exposureResolvePushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeExposureResolvePush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = exposureResolvePushProbe.key + exposureResolvePushProbe.preExposure;
    }
}

[[vk::push_constant]] DisplayPush displayPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeDisplayPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = float(displayPushProbe.hdrEnabled) + displayPushProbe.hdrPeakNits;
    }
}

[[vk::push_constant]] DebugPresentPush debugPresentPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeDebugPresentPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = float(debugPresentPushProbe.debugView) + debugPresentPushProbe.centerWeightFloor;
    }
}

// The sky LUT compute passes push only the frame's WorldPush device address (see world_common.PushAddr).
[[vk::push_constant]] PushAddr pushAddrLayoutProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probePushAddr(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = float(pushAddrLayoutProbe.worldPushAddr);
    }
}

[[vk::push_constant]] BloomPush bloomPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeBloomPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = float(bloomPushProbe.mode) + bloomPushProbe.radius;
    }
}
