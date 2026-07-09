#version 460

// Full-res, post-upscale name-tag billboards. Every visible tag's glyph quads (already billboarded to
// face the camera and positioned in the entity's rebased world space by RtNameTagFeature, on the CPU —
// see that class for why: unlike glow, this can't be baked into the entity's own rigid mesh, since the
// billboard rotates with the camera every frame) are merged into one vertex buffer per font-atlas page;
// this shader just finishes the camera-relative transform, mirroring world.rgen's WorldPush fields
// byte-for-byte in meaning (curViewProj/camOffset).
layout(push_constant) uniform Push {
    mat4 curViewProj;
    vec3 camOffset;
} pc;

layout(location = 0) in vec3 inPos;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec4 inColor;

layout(location = 0) out vec2 outUv;
layout(location = 1) out vec4 outColor;

void main() {
    gl_Position = pc.curViewProj * vec4(inPos - pc.camOffset, 1.0);
    outUv = inUv;
    outColor = inColor;
}
