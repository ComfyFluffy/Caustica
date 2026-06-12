#version 330

// Camera-only motion vectors from depth reprojection. Exact for all static
// geometry (terrain, sky, cutout); dynamic objects (entities/particles) get
// camera motion only and may ghost slightly.

uniform sampler2D InDepth;

layout(std140) uniform MvParams {
    mat4 Reproject; // prevViewProj * translate(camDelta) * inverse(curViewProj), unjittered
};

in vec2 texCoord;

out vec2 fragColor;

void main() {
    float depth = texture(InDepth, texCoord).r;
    vec2 ndc = texCoord * 2.0 - 1.0;

    vec4 clip = vec4(ndc, depth, 1.0);
    vec4 prevClip = Reproject * clip;
    vec2 prevNdc = prevClip.xy / prevClip.w;

    // NDC-space delta, current -> previous; converted to pixels via the
    // motionVectorScale passed to the FSR dispatch.
    fragColor = prevNdc - ndc;
}
