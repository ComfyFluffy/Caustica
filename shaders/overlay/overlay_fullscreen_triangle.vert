#version 460

// Fullscreen triangle, no vertex buffer (the classic gl_VertexIndex trick: 3 vertices covering the whole
// clip-space square, the excess clipped by the viewport). Shared by every overlay composite pass that runs
// its filtering/passthrough logic once per pixel over a colour attachment: entity_glow_composite.frag,
// overlay_passthrough_composite.frag (block outline's own bridge + RtWorldOverlay's final SDR composite).
void main() {
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
}
