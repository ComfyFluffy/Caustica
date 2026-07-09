#version 460

// Samples one page of the vanilla font atlas (bound as a real combined-image-sampler — this is a plain
// forward-rendered quad, not bindless like the in-RT entity texture arrays). Font pages are almost always
// RGBA8_UNORM ("colored", per BitmapProvider.Glyph.isColored() = image.format().components() > 1 — a
// standard PNG glyph sheet decodes to 4 components even for a "grayscale-looking" font): RGB is the
// glyph's baked colour (constant white for the stock font), and coverage lives in ALPHA — .rgb is NOT
// coverage. The glyph's actual tint (and the name-tag background quad's colour+alpha) travels per-vertex
// instead, so only the alpha channel is sampled here.
layout(set = 0, binding = 0) uniform sampler2D fontAtlas;

layout(location = 0) in vec2 inUv;
layout(location = 1) in vec4 inColor;

layout(location = 0) out vec4 outColor;

void main() {
    float a = texture(fontAtlas, inUv).a;
    outColor = vec4(inColor.rgb, inColor.a * a);
}
