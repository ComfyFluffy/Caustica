#version 460

// Native LINE_LIST draw (see RtBlockOutlineFeature) — real width comes from the raster pipeline's
// dynamic line width (VK_DYNAMIC_STATE_LINE_WIDTH, vkCmdSetLineWidth), gated on the device's wideLines
// feature (RtDeviceBringup.wideLinesEnabled/maxLineWidth); without it Vulkan mandates lineWidth == 1.0.
// inPos is in the terrain's REBASE space (blockPos - terrain.blockX/Y/Z + local edge fraction), the same
// convention entity_glow.vert's captured vertices use — camOffset (the camera's position in that same
// rebased space) is subtracted here to get the camera-relative delta curViewProj expects.

layout(push_constant) uniform Push {
    mat4 curViewProj; // 0, 64B
    vec3 camOffset;   // 64, padded to 16B
    vec4 color;       // 80, 16B
} pc;

layout(location = 0) in vec3 inPos;

layout(location = 0) out vec3 vCamRel;

void main() {
    vec3 camRel = inPos - pc.camOffset;
    vCamRel = camRel;
    gl_Position = pc.curViewProj * vec4(camRel, 1.0);
}
