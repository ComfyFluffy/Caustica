#version 460

// Trivial unlit fill — the mask only needs coverage + the entity's flat outline colour; edge extraction
// happens later, in entity_glow_composite.comp.
layout(push_constant) uniform Push {
    mat4 curViewProj;
    vec3 camOffset;
    vec4 color;
} pc;

layout(location = 0) out vec4 outColor;

void main() {
    outColor = pc.color;
}
