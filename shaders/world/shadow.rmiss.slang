// Shadow / sky-visibility miss (SBT miss index 1). Under the SER (EXT) raygen variant this shader
// does not actually run: visibility() uses a hit object purely as the traversal result and never
// invokes the miss shader — it just fills the SBT slot. The NO_SER raygen fallback (plain TraceRay,
// no HitObject) does invoke it: payload.hitT == 1.0 is that variant's "ray escaped" signal.
// Radiance and shadow rays share the exact Payload ABI, as Vulkan requires for every stage reachable by
// a trace — see world.rgen.slang's shadowPayload.
import world_common;

[shader("miss")]
void main(inout Payload payload) {
    payload.hitT = 1.0;
}
