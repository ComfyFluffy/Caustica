// Build-time reflection probe only. This file is not packaged as a runtime shader.
// Exposing WorldPush as a structured-buffer element makes Slang's JSON reflection include the
// complete Std430DataLayout: field types, byte offsets, total size, matrix mode, and array counts.
import world_common;

struct WorldPushLayoutProbe {
    WorldPush values[2]; // array stride is the complete, tail-padded WorldPush byte size
};

struct MaterialHeaderLayoutProbe {
    MaterialHeader values[2]; // array stride is the complete, tail-padded MaterialHeader byte size
};

[[vk::binding(0, 0)]] StructuredBuffer<WorldPushLayoutProbe, Std430DataLayout> worldPushLayoutProbe;
[[vk::binding(1, 0)]] StructuredBuffer<MaterialHeaderLayoutProbe, Std430DataLayout> materialHeaderLayoutProbe;
[[vk::push_constant]] PushAddr pushAddrLayoutProbe;

[shader("compute")]
[numthreads(1, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    // Keep the parameter reachable without requiring a writable output resource.
    if (id.x == 0xFFFFFFFFu) {
        float sink = worldPushLayoutProbe[0].values[0].invViewProj[0][0]
                + materialHeaderLayoutProbe[0].values[0].params.x
                + float(pushAddrLayoutProbe.frameIndex);
    }
}
