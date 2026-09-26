module caustica_fog_types;

public struct FogVolumeData {
    public uint64_t fieldAddress;
    // Physical-pointer and reflected buffer layouts both place the float4 fields on 16-byte boundaries.
    public uint64_t alignmentPadding;
    public float4 fieldOrigin;
    public float4 fieldSize;
    public float4 noiseOrigin;
    public float4 profile;
    public float4 lightDirection;
    public float4 lightRadiance;
    public float4 ambientRadiance;
};

public struct FogPush {
    public uint64_t volumeAddress;
    public uint64_t visibilityRaysAddress;
    public uint64_t visibilityResultsAddress;
    public uint64_t prefixAddress;
    public uint destinationIndex;
    public uint sceneIndex;
    public uint depthIndex;
    public uint fogIndex;
    public uint mode;
    public uint steps;
    // Depth steps in the batch that begins at step traceData.w.
    public uint batchSteps;
    public uint debug;
    public float4 clipX;
    public float4 clipY;
    public float4 clipZ;
    public float4 clipW;
    public float4 tlasCamera;
    public float4 mediumCamera;
    public float4 traceData;
    public uint64_t historyAddress;
    public uint64_t previousHistoryAddress;
    public float4 previousClipX;
    public float4 previousClipY;
    public float4 previousClipZ;
    public float4 previousClipW;
};
