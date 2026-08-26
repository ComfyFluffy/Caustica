// EXT Shader Execution Reordering radiance dispatch. Kept in a separate module so the ordinary
// indirect shader never imports hit-object operations or their SPIR-V capability.

import world_common;
import world_core;
import bindings;
import trace;

// Trace into a hit object, reorder threads by hit coherence, THEN invoke the hit/miss shader. This is
// where the divergent Section/Prim shading work (world.rchit/world.rahit) actually happens, so
// reordering before it is what pays for the reorder's own cost.
//
// The payload is built TWICE from the trace state rather than carried across ReorderThread. Traversal
// runs only world.rahit, which never writes a RADIANCE payload: its terrain tint paths live in the
// translucent/water buckets, which carry no any-hit record for radiance rays, and its entity paths are
// gated on RAY_FLAG_SKIP_CLOSEST_HIT_SHADER. So whatever TraceRay leaves in the payload holds no
// information. Reading it back and carrying it over would pin all ten members — 72 bytes — in registers
// across the one point where SER must spill every live value. Rebuilding costs a few constant moves and
// leaves only these two words spanning the reorder.
public void traceRadianceReordered(uint cullMask, float3 ro, float tmin, float3 rd, float tmax,
                            bool showCelestial, bool primarySky, float rayConeWidth, float rayConeSpread,
                            uint pathPhaseHint) {
    uint flags = (showCelestial ? PAYLOAD_SHOW_CELESTIAL : 0u)
            | (primarySky ? PAYLOAD_PRIMARY_SKY : 0u);
    uint rayCone = packHalf2(float2(rayConeWidth, rayConeSpread));
    Payload tracePayload = makeRadiancePayload(flags, rayCone);
    HitObject hObj = HitObject::TraceRay(topLevelAS, RAY_FLAG_NONE, cullMask,
            SBT_RADIANCE, SBT_STRIDE_BUCKET, MISS_RADIANCE,
            makeRay(ro, tmin, rd, tmax), tracePayload);
    // The hit object groups shader IDs first. Its SBT index then supplies the two-bit geometry/material
    // bucket ({solid, cutout, translucent, water}); terrain and entity record ranges are both multiples
    // of TERRAIN_BUCKETS, so the low bits have the same meaning. The high two bits describe path lifetime:
    // pre-roulette, roulette-active, or guaranteed terminal after this hit.
    uint materialHint = hObj.GetShaderTableIndex() & (TERRAIN_BUCKETS - 1u);
    uint coherenceHint = (pathPhaseHint << 2u) | materialHint;
    ReorderThread(hObj, coherenceHint, 4u);
    payload = makeRadiancePayload(flags, rayCone);
    HitObject::Invoke(topLevelAS, hObj, payload);
}
