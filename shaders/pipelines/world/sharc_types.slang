// SHaRC-only ABI types. Keeping this module out of world_common keeps the ordinary
// path shader's interface independent of the optional SDK build.
module sharc_types;

import world_common;

public struct SharcPushConstants {
    public uint64_t worldPushAddr;
    public uint64_t tableAddr;
    public uint64_t entityTableAddr;
    public uint64_t materialTableAddr;
    public uint64_t lightBufAddr;
    public uint64_t lightAliasAddr;
    public uint64_t lightLocalAliasAddr;
    public uint64_t lightGridCellAddr;
    public uint64_t lightGridSpanAddr;
    public uint64_t pathQueueAddr;
    public uint frameIndex;
    public uint64_t sharcFrameAddr;
    public uint sharcUpdateTileSize;
    public uint sharcRenderWidth;
    public uint sharcRenderHeight;
    // Additional minimum linear roughness for diffuse cache ownership.
    public float sharcRoughnessThreshold;
    // bit 0: developer comparison mode that permits primary-terminal cache insertion/query.
    public uint sharcDebugFlags;
};

public struct SharcFrame {
    public uint64_t hashEntriesAddr;
    public uint64_t accumulationAddr;
    public uint64_t resolvedAddr;
    public float3 cameraPosition;
    public uint capacity;
    public float3 cameraPositionPrev;
    public uint frameIndex;
    public float sceneScale;
    public float radianceScale;
    public uint accumulationFrameNum;
    public uint staleFrameNumMax;
    public float gridLogarithmBase;
    public float gridLevelBias;
    // bit 0: enable the confidence-based anti-firefly adjustment during updates
    public uint sharcFlags;
};
