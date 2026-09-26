// SHaRC hooks of the stable-plane fill. This module is SDK-free: the ordinary fill uses SharcDisabled,
// whose hooks are constants, and the optional sharc_bridge policies implement the SHaRC update and
// query passes with the NVIDIA headers.
module sharc_policy;

import surface_bsdf;

// A path vertex as the cache sees it.
public struct SharcVertex {
    public float3 position;
    // Cache entries key on the face normal; the shading normal would split one surface per texel.
    public float3 faceNormal;
    // Unit direction toward the previous path vertex.
    public float3 outgoing;
    public SurfaceClosure surface;
    // False when the stable planes delegated one of the vertex's lobes to another plane, so its
    // sampled transport is only part of the light it reflects.
    public bool complete;
};

public interface ISharcPolicy {
    /** Maps a dispatch thread to its path pixel and the render extent; false ends the invocation. */
    [mutating] public bool begin(uint64_t frameAddress, uint2 dispatchIndex, uint2 dispatchExtent,
            out uint2 pixel, out uint2 extent);
    /** True for the update pass: paths start at unit throughput and write no stable-plane signal. */
    public bool updatesOnly();
    /**
     * Cached light that the vertex reflects toward the previous vertex, excluding its own emission.
     * segmentLength is the traced segment reaching the vertex and lobeRoughness the summed
     * perceptual roughness of the lobes sampled before it, with a diffuse lobe counting as one.
     */
    [mutating] public bool query(SharcVertex vertex, bool primary, float segmentLength,
            float lobeRoughness, out float3 radiance);
    /**
     * Update pass: records the vertex's direct lighting and hands it and the vertex's MIS-weighted
     * emission to the earlier recorded vertices. scatterEvent is the lobe the path continues along,
     * or EVENT_NONE. False ends the path because the cache stands in for the rest of it.
     */
    [mutating] public bool record(SharcVertex vertex, float3 directLighting, float3 emission,
            uint scatterEvent, float random);
    /** Update pass: radiance reaching the recorded vertices through their current path weights. */
    [mutating] public void propagate(float3 radiance);
    /** Update pass: multiplies a segment weight into the path weights of the recorded vertices. */
    [mutating] public void carry(float3 segmentWeight);
};

public struct SharcDisabled : ISharcPolicy {
    [mutating] public bool begin(uint64_t frameAddress, uint2 dispatchIndex, uint2 dispatchExtent,
            out uint2 pixel, out uint2 extent) {
        pixel = dispatchIndex;
        extent = dispatchExtent;
        return true;
    }
    public bool updatesOnly() { return false; }
    [mutating] public bool query(SharcVertex vertex, bool primary, float segmentLength,
            float lobeRoughness, out float3 radiance) {
        radiance = float3(0.0);
        return false;
    }
    [mutating] public bool record(SharcVertex vertex, float3 directLighting, float3 emission,
            uint scatterEvent, float random) {
        return true;
    }
    [mutating] public void propagate(float3 radiance) { }
    [mutating] public void carry(float3 segmentWeight) { }
};
