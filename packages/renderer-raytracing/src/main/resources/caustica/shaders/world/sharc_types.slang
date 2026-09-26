// SHaRC cache state shared by the host (generated SharcFrameData) and the SHaRC fill variants and
// resolve. Plain data only, so the ordinary program and the ABI probe build without the SDK.
module sharc_types;

// One frame's cache state. The host writes a fresh copy per submitted frame; the fill variants read
// it through WorldBindingRoots.sharcFrameAddress and the resolve through its push data.
public struct SharcFrame {
    public uint64_t hashEntriesAddress;
    public uint64_t accumulationAddress;
    public uint64_t resolvedAddress;
    // Scene-space camera of this frame and of the previous frame that updated the cache.
    public float3 cameraPosition;
    public uint capacity;
    public float3 previousCameraPosition;
    public uint frameIndex;
    public float sceneScale;
    public float gridLogarithmBase;
    public float gridLevelBias;
    // Accumulator quantization steps per unit of pre-exposed radiance.
    public float radianceScale;
    // This frame's WorldPush.preExposure. Transport and the resolved table stay in absolute radiance;
    // only the int32 frame accumulation quantizes in pre-exposed units.
    public float preExposure;
    public uint accumulationFrames;
    public uint staleFrames;
    // The update traces one pixel of every updateTileSize x updateTileSize tile of renderExtent.
    public uint updateTileSize;
    public uint2 renderExtent;
    // Perceptual roughness a surface must exceed to own cache entries.
    public float roughnessThreshold;
    public uint flags;
};

public static const uint SHARC_FLAG_ANTI_FIREFLY = 1u;
// Primary surfaces query the cache too, showing its content directly on screen.
public static const uint SHARC_FLAG_PRIMARY_QUERY = 2u;
