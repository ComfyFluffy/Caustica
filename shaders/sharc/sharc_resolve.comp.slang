// SHaRC 1.8 directional-SH resolve. The full cache is dispatched; invalid slots exit in the shader.
#define SHARC_UPDATE 0
#define SHARC_QUERY 0

import world_common;
import sharc_types;
#include "sharc_bridge.slang"

struct SharcResolvePush {
    uint64_t sharcFrameAddr;
};

[[vk::push_constant]] SharcResolvePush resolvePush;

[shader("compute")]
[numthreads(256, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    SharcFrame frame = ConstPtr<SharcFrame>(resolvePush.sharcFrameAddr)[0];
    uint entryIndex = id.x;
    if (entryIndex >= frame.capacity) return;

    HashGridKey hashKey = SharcRWPtr<HashGridKey>(frame.hashEntriesAddr)[entryIndex];
    if (hashKey == HASH_GRID_INVALID_HASH_KEY) return;

    SharcParameters parameters = causticaSharcParameters(
            frame.hashEntriesAddr, frame.accumulationAddr, frame.resolvedAddr, frame.capacity,
            frame.cameraPosition, frame.sceneScale, frame.radianceScale,
            frame.gridLogarithmBase, frame.gridLevelBias);
    SharcResolveParameters resolve;
    resolve.cameraPositionPrev = causticaSharcResolvePreviousCamera(parameters, frame.cameraPositionPrev);
    resolve.accumulationFrameNum = frame.accumulationFrameNum;
    resolve.responsiveFrameNum = 1u;
    resolve.staleFrameNumMax = frame.staleFrameNumMax;
    resolve.frameIndex = frame.frameIndex;
    SharcResolveEntry(entryIndex, parameters, resolve);
}
