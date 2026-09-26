import display_common;
import presentation_common;
import caustica_fog_types;
[[vk::push_constant]] FogPush fogPushProbe;
[shader("compute")]
[numthreads(1,1,1)]
void probeFogPush(uint3 id : SV_DispatchThreadID) {
    if(id.x == 0xFFFFFFFFu) { float sink = fogPushProbe.traceData.x; }
}

struct FogVolumeLayoutProbe { FogVolumeData values[2]; };
[[vk::binding(1, 0)]] StructuredBuffer<FogVolumeLayoutProbe, Std430DataLayout> fogVolumeLayoutProbe;

struct ExposureStateLayoutProbe { ExposureState values[2]; };
[[vk::binding(0, 0)]] StructuredBuffer<ExposureStateLayoutProbe, Std430DataLayout> exposureStateLayoutProbe;

[shader("compute")]
[numthreads(1, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = exposureStateLayoutProbe[0].values[0].previous;
    }
}

[[vk::push_constant]] ExposureHistPush exposureHistPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeExposureHistPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) { float sink = float(exposureHistPushProbe.stride); }
}

[[vk::push_constant]] ExposureResolvePush exposureResolvePushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeExposureResolvePush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) { float sink = exposureResolvePushProbe.key + exposureResolvePushProbe.preExposure; }
}

[[vk::push_constant]] DisplayPush displayPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeDisplayPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) { float sink = displayPushProbe.gamma + displayPushProbe.hdrPeakNits; }
}

[[vk::push_constant]] DebugPresentPush debugPresentPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probeDebugPresentPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) { float sink = float(debugPresentPushProbe.debugView) + debugPresentPushProbe.centerWeightFloor; }
}

[[vk::push_constant]] PresentPush presentPushProbe;
[shader("compute")]
[numthreads(1, 1, 1)]
void probePresentPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) { float sink = float(presentPushProbe.outputImageIndex) + presentPushProbe.uiNits; }
}

import caustica_bloom_types;

[[vk::push_constant]] BloomPush bloomPushProbe;

[shader("compute")]
[numthreads(1, 1, 1)]
void probeBloomPush(uint3 id : SV_DispatchThreadID) {
    if (id.x == 0xFFFFFFFFu) {
        float sink = float(bloomPushProbe.destinationImageIndex)
                + float(bloomPushProbe.samplerIndex)
                + float(bloomPushProbe.mode)
                + bloomPushProbe.radius;
    }
}
