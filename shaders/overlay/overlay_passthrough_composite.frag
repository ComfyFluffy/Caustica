#version 460

// Composites a mod-owned RGBA8 storage image onto the current colour attachment via fixed-function
// blending — a plain "over" operator, no filtering; the shader itself is blend-mode-agnostic (just an
// imageLoad passthrough), the FIXED-FUNCTION blend state is what actually does the compositing math, and
// it differs per caller (same reasoning as entity_glow_composite.frag/overlay_fullscreen_triangle.vert: the
// destination is always a real dynamic-rendering colour ATTACHMENT, never a storage image, so this can't be
// a compute imageStore):
//   - RtBlockOutlineFeature's own mask composite: srcImage is its MSAA-resolved outline mask (the resolve
//     already turned per-sample rasterizer coverage into a fractional STRAIGHT alpha), composited onto
//     RtWorldOverlay's shared overlay buffer with RtOverlayPipelines.Blend.ALPHA (SRC_ALPHA/
//     ONE_MINUS_SRC_ALPHA — correct for a straight-alpha source).
//   - RtWorldOverlay's own final SDR composite: srcImage is the shared overlay buffer itself, which ends up
//     PREMULTIPLIED once more than one feature has drawn into it (see Blend.ALPHA's own doc comment) —
//     composited onto vanilla's main render target with Blend.PREMULTIPLIED_ALPHA (ONE/
//     ONE_MINUS_SRC_ALPHA — using ALPHA here would double-multiply by alpha).
layout(binding = 0, set = 0, rgba8) uniform readonly image2D srcImage;

layout(location = 0) out vec4 outColor;

void main() {
    outColor = imageLoad(srcImage, ivec2(gl_FragCoord.xy));
}
