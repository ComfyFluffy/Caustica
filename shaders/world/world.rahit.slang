// Any-hit alpha/tint handling. Radiance rays only invoke this for true alpha cutout. Shadow rays invoke it
// for cutout, translucent, and water buckets so those materials can pass/tint sunlight without closest-hit.
//  * terrain (per-section): InstanceID() indexes the section table → uv buffers, interpolate the atlas
//    UV, discard below the cutoff (leaves/grass/glass see-through).
//  * entity (ENTITY_BIT): InstanceID() (low bits) indexes the entity geometry table → interpolate the
//    entity UV, sample the per-prim bindless entity texture, discard transparent texels (so entity
//    cutout — hat/jacket overlays, eyes, capes, … — is see-through). Accepted hits fall through to the
//    closest-hit. Opaque entity geometry bypasses this shader; every alpha/transmissive material shares
//    one non-opaque geometry and routes here through its SBT record.
//
// Radiance and shadow rays share the exact Payload ABI, as Vulkan requires for every stage reachable by
// a trace. Shadow traversal uses albedo.rgb as accumulated transmittance and hitT as the nearest-water
// crossing; radiance cutout paths do not touch either field.
import world_common;

[[vk::push_constant]] WorldPushConstants pc;

[[vk::binding(2, 0)]] Sampler2D blockAlbedoAtlas;
[[vk::binding(0, 1)]] Sampler2D entityAlbedoTex[];

static const float ALPHA_CUTOFF = 0.5;        // terrain (matches the cutout block models)
static const float ENTITY_ALPHA_CUTOFF = 0.1; // entities: only discard near-fully-transparent texels
// Faint baseline extinction added to every translucent hit regardless of its color, so perfectly clear
// glass/ice still dims a shadow ray a little instead of being fully invisible to it (real glass isn't a
// perfect transmitter either). Additive with the color-derived extinction below, not a floor/clamp — a
// tinted pane still absorbs its own color on top of this.
static const float TRANSLUCENT_NEUTRAL_EXTINCTION = 0.15;
static const float WATER_SHADOW_TINT = 0.5;

// Progressive 8x8 blue-noise-style alpha threshold pattern, with a golden-ratio temporal rotation. This keeps
// translucent entity hits stochastic, but trades per-pixel white noise for a stable spatial distribution
// that temporal upscaling can integrate with less sparkle.
static const float ALPHA_DITHER_8X8[64] = {
    0.281250, 0.968750, 0.375000, 0.859375, 0.250000, 0.937500, 0.406250, 0.812500,
    0.593750, 0.187500, 0.734375, 0.046875, 0.578125, 0.218750, 0.703125, 0.015625,
    0.453125, 0.765625, 0.328125, 0.921875, 0.484375, 0.796875, 0.343750, 0.890625,
    0.640625, 0.093750, 0.546875, 0.156250, 0.671875, 0.078125, 0.515625, 0.125000,
    0.265625, 0.953125, 0.421875, 0.828125, 0.296875, 0.984375, 0.390625, 0.843750,
    0.562500, 0.234375, 0.687500, 0.000000, 0.609375, 0.203125, 0.718750, 0.031250,
    0.468750, 0.781250, 0.359375, 0.875000, 0.437500, 0.750000, 0.312500, 0.906250,
    0.656250, 0.062500, 0.500000, 0.140625, 0.625000, 0.109375, 0.531250, 0.171875
};

float alphaDitherThreshold(uint salt) {
    uint2 p = (DispatchRaysIndex().xy + uint2(salt * 3u, salt * 5u)) & uint2(7u, 7u);
    float base = ALPHA_DITHER_8X8[int(p.y * 8u + p.x)];
    return frac(base + float((pc.frameIndex + salt) & 255u) * 0.61803398875);
}

[shader("anyhit")]
void main(inout Payload payload, in BuiltInTriangleIntersectionAttributes attr) {
    uint pid = PrimitiveIndex();
    float2 attribs = attr.barycentrics;
    float3 bary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);

    uint instanceKind = InstanceID() & (ENTITY_BIT | PARTICLE_BIT);
    if (instanceKind != 0u) {
        EntityGeom g = ConstPtr<EntityGeom>(pc.entityTableAddr)[InstanceID() & IDX_MASK];
        pid += g.triBase[GeometryIndex()];
        ConstPtr<uint> indices = ConstPtr<uint>(g.idxAddr);
        ConstPtr<float2> uvs = ConstPtr<float2>(g.uvAddr);
        uint i0 = indices[3u * pid + 0u];
        uint i1 = indices[3u * pid + 1u];
        uint i2 = indices[3u * pid + 2u];
        float2 uv = bary.x * uvs[i0] + bary.y * uvs[i1] + bary.z * uvs[i2];
        Prim epr = ConstPtr<Prim>(g.primAddr)[pid];
        int texSlot = int(epr.tint.w + 0.5);
        float4 texel = entityAlbedoTex[NonUniformResourceIndex(texSlot)].SampleLevel(uv, 0.0);
        bool stochasticAlpha = false;
        MaterialHeader materialHeader;
        if (instanceKind == ENTITY_BIT) {
            materialHeader = ConstPtr<MaterialHeader>(pc.materialTableAddr)[epr.materialId];
            stochasticAlpha = (materialHeader.features & MATERIAL_FEATURE_STOCHASTIC_ALPHA) != 0u;
        }
        if (stochasticAlpha) {
            uint salt = pid * 1664525u
                    ^ (InstanceID() & IDX_MASK) * 1013904223u
                    ^ asuint(RayTCurrent());
            if (alphaDitherThreshold(salt) >= clamp(texel.a, 0.0, 1.0)) {
                IgnoreHit();
            }
        } else if (texel.a < ENTITY_ALPHA_CUTOFF) {
            IgnoreHit();
        }

        // Radiance rays accept the surface and continue to closest-hit. Shadow rays use the same material
        // model here so entity dielectrics transmit instead of becoming opaque shadow blockers.
        bool shadowRay = (RayFlags() & RAY_FLAG_SKIP_CLOSEST_HIT_SHADER) != 0u;
        if (instanceKind == ENTITY_BIT && shadowRay && materialHeader.model == MATERIAL_GLASS) {
            float3 tint = lerp(float3(1.0, 1.0, 1.0),
                    srgbToLinear(texel.rgb) * epr.tint.rgb, texel.a);
            payload.albedo *= tint * clamp(materialHeader.params.w, 0.0, 1.0);
            IgnoreHit();
        }
        if (instanceKind == ENTITY_BIT && shadowRay && materialHeader.model == MATERIAL_WATER) {
            float3 tint = srgbToLinear(texel.rgb) * epr.tint.rgb;
            payload.albedo *= lerp(float3(1.0, 1.0, 1.0), tint, WATER_SHADOW_TINT)
                    * clamp(materialHeader.params.w, 0.0, 1.0);
            payload.hitT = payload.hitT < 0.0 ? RayTCurrent() : min(payload.hitT, RayTCurrent());
            IgnoreHit();
        }
        return;
    }

    Section sec = ConstPtr<Section>(pc.tableAddr)[InstanceID()];
    uint bucket = GeometryIndex();
    uint tri = pid + sec.triBase[bucket];

    // Water radiance rays use a closest-hit-only SBT record. Shadow rays run this record: tint sunlight by
    // the biome water color, then keep walking so submerged terrain is lit by colored transmission.
    if (bucket == BUCKET_WATER) {
        TerrainPrim pr = ConstPtr<TerrainPrim>(sec.primAddr)[tri];
        payload.albedo *= lerp(float3(1.0, 1.0, 1.0), pr.tint.rgb, WATER_SHADOW_TINT);
        // Record the NEAREST water crossing (any-hit order is arbitrary) in the shadow payload's hitT
        // lane. For an underwater shading point this is the exit point of its sun shadow ray, where
        // world.rgen evaluates the wave-refraction caustic. visibility() seeds the -1 sentinel.
        payload.hitT = payload.hitT < 0.0 ? RayTCurrent() : min(payload.hitT, RayTCurrent());
        IgnoreHit();
    }

    // Translucent radiance rays use a closest-hit-only SBT record; shadow rays tint and pass through here.
    // This doesn't touch the atlas at all: a shadow ray only needs the pane's overall tint/opacity, so
    // MaterialHeader carries a precomputed whole-sprite average {r,g,b,a}; per-primitive biome tint is
    // applied here. The average is compiled once per resource epoch rather than copied per triangle.
    //
    // Modeled as Beer-Lambert absorption (matching the water medium in world.rgen): a per-channel
    // extinction derived from how dark the average is, scaled by the average alpha (how much of the
    // sprite is glass-colorant vs. see-through frame), so saturated panes darken transmitted light
    // non-linearly. Multiplying into payload.albedo compounds correctly across stacked panes.
    if (bucket == BUCKET_TRANSLUCENT) {
        TerrainPrim pr = ConstPtr<TerrainPrim>(sec.primAddr)[tri];
        MaterialHeader materialHeader = ConstPtr<MaterialHeader>(pc.materialTableAddr)[pr.materialId];
        float3 avgColor = max(materialHeader.average.rgb * pr.tint.rgb,
                float3(1.0e-3, 1.0e-3, 1.0e-3));
        float3 colorExtinction = max(-log(avgColor), float3(0.0, 0.0, 0.0));
        // The neutral floor is a flat per-hit dimming, NOT scaled by alpha: vanilla clear glass has a low
        // natural alpha, so folding it into the alpha-scaled term crushed it to near-zero for exactly the
        // white/clear-glass case it's meant to cover.
        payload.albedo *= exp(-colorExtinction * materialHeader.average.a - TRANSLUCENT_NEUTRAL_EXTINCTION);
        IgnoreHit();
    }

    // Cutout geometry: alpha from the atlas. PrimitiveIndex() restarts at 0 per geometry, so re-add this
    // bucket's triangle base to index the section's packed corner-UV array (3 UVs per triangle).
    ConstPtr<float2> uvs = ConstPtr<float2>(sec.uvAddr);
    float2 uv = bary.x * uvs[3u * tri + 0u] + bary.y * uvs[3u * tri + 1u] + bary.z * uvs[3u * tri + 2u];
    if (blockAlbedoAtlas.SampleLevel(uv, 0.0).a < ALPHA_CUTOFF) {
        IgnoreHit();
    }
}
