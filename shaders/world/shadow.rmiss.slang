// Shadow / sky-visibility miss (SBT miss index 1). Under the SER (EXT) raygen variant this shader
// does not actually run: visibility() uses a hit object purely as the traversal result and never
// invokes the miss shader — it just fills the SBT slot. The NO_SER raygen fallback (plain TraceRay,
// no HitObject) does invoke it: shadowVis.a == 1.0 is that variant's "ray escaped" signal.
[shader("miss")]
void main(inout float4 shadowVis) {
    shadowVis.a = 1.0;
}
