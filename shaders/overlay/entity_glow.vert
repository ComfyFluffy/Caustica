#version 460

// Entity-glow mask: re-rasterizes glowing entities' body meshes (RtEntities already keeps this frame's
// posed CPU-side vertex data around for BLAS refit) into a full-res, depth-less mask image at the exact
// same camera projection the RT world trace used (curViewProj/camOffset mirror world.rgen's WorldPush
// fields byte-for-byte in meaning), so the silhouette lands pixel-exact on the ray-traced entity. No
// depth test/attachment at all — like vanilla's Glowing outline, the mask (and therefore the outline
// RtGlowOutline derives from it) is meant to show through walls.
layout(push_constant) uniform Push {
    mat4 curViewProj; // forward camera-relative view-projection (= RtComposite's frameProjection*frameViewRotation)
    vec3 camOffset;   // camera position in the same rebased space inPos is captured in
    vec4 color;       // this entity's vanilla outline colour (opaque team colour, or white)
} pc;

layout(location = 0) in vec3 inPos;

void main() {
    gl_Position = pc.curViewProj * vec4(inPos - pc.camOffset, 1.0);
}
