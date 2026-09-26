// SHaRC resolve: one invocation per cache entry merges the frame's accumulation into the resolved
// radiance, ages and evicts stale entries, and clears the accumulation for the next update.
module sharc_resolve;
import sharc_bridge;

struct SharcResolvePush {
    uint64_t frameAddress;
};

[[vk::push_constant]] SharcResolvePush sharcResolvePush;

[shader("compute")]
[numthreads(256, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    resolveSharc(id.x, sharcResolvePush.frameAddress);
}