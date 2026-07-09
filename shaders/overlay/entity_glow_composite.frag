#version 460

// Composites the entity-glow outline onto the main render target's colour attachment via fixed-function
// blending (SRC_ALPHA, ONE_MINUS_SRC_ALPHA) — NOT a compute imageStore. Vanilla's Blaze3D texture (the
// main render target) is never created with VK_IMAGE_USAGE_STORAGE_BIT (confirmed via validation:
// VUID-VkWriteDescriptorSet-descriptorType-00339), so it can only be written as a render-pass/dynamic-
// rendering colour attachment, exactly like RtUiOverlay's own GUI-composite blit. The mask stays a mod-
// owned storage image (entity_glow's raster pass writes it), read here via plain imageLoad — no sampler.
layout(binding = 0, set = 0, rgba8) uniform readonly image2D maskImage;

layout(location = 0) out vec4 outColor;

const float EDGE_THRESHOLD = 0.02;

float coverage(ivec2 p, ivec2 size) {
    return imageLoad(maskImage, clamp(p, ivec2(0), size - ivec2(1))).a;
}

void main() {
    ivec2 pix = ivec2(gl_FragCoord.xy);
    ivec2 size = imageSize(maskImage);

    // Interior silhouette pixels aren't part of the outline (vanilla's Glowing effect draws only the
    // ~2px edge, never a flat fill) — only background pixels can become outline.
    if (coverage(pix, size) > 0.5) {
        outColor = vec4(0.0);
        return;
    }

    // Sobel gradient of the binary silhouette coverage: nonzero only within a ~1px band just outside
    // the mask, which is exactly the outline band we want.
    float tl = coverage(pix + ivec2(-1, -1), size);
    float tc = coverage(pix + ivec2(0, -1), size);
    float tr = coverage(pix + ivec2(1, -1), size);
    float ml = coverage(pix + ivec2(-1, 0), size);
    float mr = coverage(pix + ivec2(1, 0), size);
    float bl = coverage(pix + ivec2(-1, 1), size);
    float bc = coverage(pix + ivec2(0, 1), size);
    float br = coverage(pix + ivec2(1, 1), size);

    float gx = -tl - 2.0 * ml - bl + tr + 2.0 * mr + br;
    float gy = -tl - 2.0 * tc - tr + bl + 2.0 * bc + br;
    float edge = clamp(length(vec2(gx, gy)), 0.0, 1.0);
    if (edge <= EDGE_THRESHOLD) {
        outColor = vec4(0.0);
        return;
    }

    // Colour the outline from the average of the covered neighbours (this pixel itself has none) — where
    // two differently-coloured glowing entities are adjacent, the shared edge blends between them.
    vec3 col = vec3(0.0);
    float count = 0.0;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            ivec2 p = clamp(pix + ivec2(dx, dy), ivec2(0), size - ivec2(1));
            vec4 s = imageLoad(maskImage, p);
            if (s.a > 0.5) {
                col += s.rgb;
                count += 1.0;
            }
        }
    }
    if (count <= 0.0) {
        outColor = vec4(0.0);
        return;
    }
    col /= count;
    outColor = vec4(col, edge);
}
