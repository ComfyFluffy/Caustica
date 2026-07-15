// Closest-hit shader. Geometry is per-section: InstanceID() indexes a section table (BDA array reached
// from the push constant) holding this section's {prim, uv} buffer addresses. Per-primitive
// {normal, tint} plus barycentric atlas UVs produce the payload guides; lighting is deferred to raygen.
//
// tableAddr/entityTableAddr are read from the inline push-constant lanes (pcAddr), not through the
// WorldPush BDA struct: they're read on every single hit (the hottest chain in the frame), and going
// through worldPushAddr first would cost an extra global-memory load just to find the address of the
// load we actually want. The cold WorldPush struct is dereferenced only for the breaking overlay.
import world_common;

[[vk::push_constant]] PushAddr pcAddr;

[[vk::binding(2, 0)]]  Sampler2D blockAtlas;
// Shared bindless bundle arrays. Entity slots contain albedo/raw LabPBR maps. Reserved high slots contain
// canonical material surface1/normalAo/surface0 pages, selected by MaterialHeader.texturePage.
[[vk::binding(0, 1)]] Sampler2D entityTex[];
[[vk::binding(1, 1)]] Sampler2D entityNormalTex[];
[[vk::binding(2, 1)]] Sampler2D entitySpecTex[];

static const uint MATERIAL_OPAQUE = 0u;
static const uint MATERIAL_WATER = 1u;
static const uint MATERIAL_PARTICLE = 2u;
static const uint MATERIAL_GLASS = 3u;

void payloadSetPacked(inout Payload payload, uint material, float roughness, float metalness,
        float emission, float sss, uint emissionSource) {
    payload.flags = material | (emissionSource << PAYLOAD_EMISSION_SOURCE_SHIFT);
    payload.roughMetal = packHalf2(float2(roughness, metalness));
    payload.emissionSss = packHalf2(float2(emission, sss));
}

uint materialEmissionSource(MaterialHeader header, float emission) {
    if (emission <= 0.0) return EMISSION_SOURCE_NONE;
    if ((header.features & MATERIAL_FEATURE_OVERRIDE_EMISSION) != 0u) return EMISSION_SOURCE_OVERRIDE;
    if ((header.features & MATERIAL_FEATURE_SPEC) != 0u) return EMISSION_SOURCE_LAB_PBR;
    if ((header.features & MATERIAL_FEATURE_HEURISTIC_EMISSION) != 0u) return EMISSION_SOURCE_HEURISTIC;
    return EMISSION_SOURCE_UNIFORM;
}

// LabPBR predefined metals (green channel 230..237): complex refractive indices N (eta) and K (kappa)
// per RGB. F0 = ((n-1)^2 + k^2) / ((n+1)^2 + k^2). Values per the LabPBR 1.3 standard.
static const float3 METAL_N[8] = {
    float3(2.9114, 2.9497, 2.5845),   // 230 iron
    float3(0.18299, 0.42108, 1.3734), // 231 gold
    float3(1.3456, 0.96521, 0.61722), // 232 aluminum
    float3(3.1071, 3.1812, 2.3230),   // 233 chrome
    float3(0.27105, 0.67693, 1.3164), // 234 copper
    float3(1.9100, 1.8300, 1.4400),   // 235 lead
    float3(2.3757, 2.0847, 1.8453),   // 236 platinum
    float3(0.15943, 0.14512, 0.13547) // 237 silver
};
static const float3 METAL_K[8] = {
    float3(3.0893, 2.9318, 2.7670),
    float3(3.4242, 2.3459, 1.7704),
    float3(7.4746, 6.3995, 5.3031),
    float3(3.3314, 3.3291, 3.1350),
    float3(3.6092, 2.6248, 2.2921),
    float3(3.5100, 3.4000, 3.1800),
    float3(4.2655, 3.7153, 3.1365),
    float3(3.9291, 3.1900, 2.3808)
};
float3 metalF0(int idx) {
    float3 n = METAL_N[idx];
    float3 k = METAL_K[idx];
    return ((n - 1.0) * (n - 1.0) + k * k) / ((n + 1.0) * (n + 1.0) + k * k);
}

// Strength of the LabPBR _n ambient-occlusion (blue) applied to albedo. Kept mild because the path
// tracer already computes its own AO from sky-visibility rays (avoid double-darkening).
static const float NORMAL_AO_STRENGTH = 0.5;

static const float RAY_CONE_MIN_WIDTH = 1.0e-5;
static const float RAY_CONE_MIN_TEXEL_FOOTPRINT = 1.0e-4;
static const float RAY_CONE_MAX_LOD = 12.0;

float2 texSizePx(Sampler2D t) {
    uint w, h;
    t.GetDimensions(w, h);
    return float2(w, h);
}

float rayConeHitWidth(uint rayCone) {
    float2 cone = unpackHalf2(rayCone);
    return max(cone.x + cone.y * max(RayTCurrent(), 0.0), RAY_CONE_MIN_WIDTH);
}

float edgeTexelsPerWorld(float3 p0, float3 p1, float2 uv0, float2 uv1, float2 textureSizePx) {
    float worldLen = length(p1 - p0);
    if (worldLen <= 1.0e-7) {
        return 0.0;
    }
    return length((uv1 - uv0) * textureSizePx) / worldLen;
}

float rayConeTextureLod(uint rayCone, float2 textureSizePx, float3 p0, float3 p1, float3 p2, float2 uv0, float2 uv1, float2 uv2) {
    float texelsPerWorld = max(edgeTexelsPerWorld(p0, p1, uv0, uv1, textureSizePx),
            max(edgeTexelsPerWorld(p1, p2, uv1, uv2, textureSizePx),
                edgeTexelsPerWorld(p2, p0, uv2, uv0, textureSizePx)));
    float footprint = max(rayConeHitWidth(rayCone) * texelsPerWorld, RAY_CONE_MIN_TEXEL_FOOTPRINT);
    return clamp(log2(footprint), 0.0, RAY_CONE_MAX_LOD);
}

float rayConeUnitUvLod(uint rayCone, float2 textureSizePx) {
    float footprint = max(rayConeHitWidth(rayCone) * max(textureSizePx.x, textureSizePx.y), RAY_CONE_MIN_TEXEL_FOOTPRINT);
    return clamp(log2(footprint), 0.0, RAY_CONE_MAX_LOD);
}

// LabPBR _n decode (shared): rotate the tangent-space normal into world space via a TBN built from the
// hit triangle's vertex positions (VK_KHR_ray_tracing_position_fetch) + UVs. `n` must already be
// oriented toward the viewer (`vdir`). Clamps the result above the horizon so grazing perturbations
// don't invert it through the surface (the black-spot fix). Returns AO (blue) via `ao`; falls back to
// `n` on a degenerate UV triangle. Instance transforms are translation-only, so object edges equal
// world edges.
float3 perturbNormal(float3 n, float3 p0, float3 p1, float3 p2, float2 t0, float2 t1, float2 t2, float3 vdir, float4 ntex, out float ao) {
    ao = 1.0;
    float2 g1 = t1 - t0;
    float2 g2 = t2 - t0;
    float det = g1.x * g2.y - g1.y * g2.x;
    if (abs(det) <= 1.0e-12) {
        return n;
    }
    float r = 1.0 / det;
    float3 traw = ((p1 - p0) * g2.y - (p2 - p0) * g1.y) * r;
    float3 T = normalize(traw - n * dot(n, traw));       // Gram-Schmidt against the viewer-oriented normal
    float3 B = cross(n, T) * (det < 0.0 ? -1.0 : 1.0);   // handedness from the UV winding
    float2 nxy = ntex.xy * 2.0 - 1.0;                    // R,G = tangent-space X,Y
    float nz = sqrt(max(0.0, 1.0 - dot(nxy, nxy)));      // reconstruct Z
    float3 nm = normalize(nxy.x * T + nxy.y * B + nz * n);
    float NoV = dot(nm, vdir);
    if (NoV < 0.02) {
        nm = normalize(nm + vdir * (0.02 - NoV));        // keep above the horizon (no flip)
    }
    ao = lerp(1.0, ntex.b, NORMAL_AO_STRENGTH);
    return nm;
}

struct Surface {
    float3 normal;
    float3 f0;
    float roughness;
    float metalness;
    float emission;
    float sss;
    float ao;
};

// The sole terrain/entity material evaluation path. LabPBR categorical bytes have already been
// decoded on the CPU; every sample below is a continuously filterable physical channel with semantic mips.
Surface evaluateMaterial(MaterialHeader header, float2 atlasUv, float lod, float3 albedo,
        float3 geometricNormal, float3 p0, float3 p1, float3 p2,
        float2 uv0, float2 uv1, float2 uv2, float3 vdir,
        float fallbackRoughness, float fallbackMetalness, float fallbackEmission, bool allowSss) {
    Surface surface;
    surface.normal = geometricNormal;
    surface.roughness = fallbackRoughness;
    surface.metalness = fallbackMetalness;
    surface.f0 = lerp(float3(0.04, 0.04, 0.04), albedo, fallbackMetalness);
    surface.emission = fallbackEmission;
    surface.sss = 0.0;
    surface.ao = 1.0;

    float2 localUv = clamp((atlasUv - header.albedoUv.xy) * header.albedoUv.zw,
            float2(0.0, 0.0), float2(1.0, 1.0));
    float2 pageUv = header.materialUv.xy + localUv * header.materialUv.zw;
    float maxMaterialLod = float((header.features >> MATERIAL_MAX_LOD_SHIFT) & MATERIAL_MAX_LOD_MASK);
    float materialLod = min(lod, maxMaterialLod);
    uint page = header.texturePage;
    if ((header.features & (MATERIAL_FEATURE_SPEC | MATERIAL_FEATURE_HEURISTIC_EMISSION
            | MATERIAL_FEATURE_OVERRIDE_EMISSION)) != 0u) {
        float4 surface0 = entitySpecTex[NonUniformResourceIndex(page)].SampleLevel(pageUv, materialLod);
        if ((header.features & MATERIAL_FEATURE_SPEC) != 0u) {
            float4 surface1 = entityTex[NonUniformResourceIndex(page)].SampleLevel(pageUv, materialLod);
            surface.roughness = surface0.r;
            surface.metalness = surface0.g;
            surface.emission = surface0.b;
            surface.sss = allowSss ? surface0.a : 0.0;
            surface.f0 = surface1.rgb;
        } else {
            // Eligibility is compiled from block-state semantics; the primitive's exact light level
            // supplies strength, while the page supplies only the normalized spatial mask.
            surface.emission = surface0.b * fallbackEmission;
        }
        if ((header.features & MATERIAL_FEATURE_OVERRIDE_EMISSION) != 0u) {
            float strength = float((header.features >> MATERIAL_EMISSION_STRENGTH_SHIFT)
                    & MATERIAL_EMISSION_STRENGTH_MASK)
                    * (MATERIAL_MAX_OVERRIDE_EMISSION_STRENGTH / float(MATERIAL_EMISSION_STRENGTH_MASK));
            float overrideMask = entityTex[NonUniformResourceIndex(page)].SampleLevel(pageUv, materialLod).a;
            surface.emission = overrideMask * strength;
        }
    }
    if ((header.features & MATERIAL_FEATURE_NORMAL) != 0u) {
        float4 normalAo = entityNormalTex[NonUniformResourceIndex(page)].SampleLevel(pageUv, materialLod);
        surface.normal = perturbNormal(geometricNormal, p0, p1, p2, uv0, uv1, uv2,
                vdir, normalAo, surface.ao);
    }
    return surface;
}

// Block-breaking overlay: shared by opaque terrain AND stained-glass/ice. Reconstructs the hit position
// in the same rebased space the breaking list was pushed in, nudges it into the solid block along
// -normal to get the block's integer coordinate, then decal-projects the local [0,1] position onto
// whichever axis pair the normal is most aligned with (same idea as vanilla's
// SheetedDecalTextureGenerator) and multiply-blends the matching destroy-stage crack texture in,
// mirroring vanilla's crumbling blend (DST_COLOR*SRC_COLOR, doubled).
float3 applyBreaking(float3 albedo, uint rayCone, float3 rayOrigin, float3 rayDir, float hitT, float3 n) {
    ConstPtr<WorldPush> pcRef = ConstPtr<WorldPush>(pcAddr.worldPushAddr);
    uint breakCount = pcRef[0].breakCount;
    if (breakCount == 0u) {
        return albedo;
    }
    float3 hitPos = rayOrigin + rayDir * hitT;
    int3 blockPos = int3(floor(hitPos - n * 0.01));
    for (uint bi = 0u; bi < breakCount; ++bi) {
        int4 ps = pcRef[0].breaking[bi].posSlot;
        if (all(ps.xyz == blockPos)) {
            float3 local = hitPos - float3(blockPos);
            float3 an = abs(n);
            float2 decalUv = (an.x >= an.y && an.x >= an.z) ? local.zy
                           : (an.y >= an.z) ? local.xz
                           : local.xy;
            float decalLod = rayConeUnitUvLod(rayCone, texSizePx(entityTex[NonUniformResourceIndex(ps.w)]));
            float3 crack = srgbToLinear(entityTex[NonUniformResourceIndex(ps.w)].SampleLevel(decalUv, decalLod).rgb);
            return clamp(crack * albedo, 0.0, 1.0);
        }
    }
    return albedo;
}

[shader("closesthit")]
void main(inout Payload payload, in BuiltInTriangleIntersectionAttributes attr) {
    float2 attribs = attr.barycentrics;
    uint rayCone = payload.rayCone;

    // Particle billboard: same geom-table/UV/bindless-atlas path as entities. Albedo is atlas texel *
    // per-particle colour; raygen treats material 2 as a primary-visible diffuse receiver with no
    // emission or specular response. Reached only by the primary ray (instance mask 0x02). Cutout: rahit.
    if ((InstanceID() & PARTICLE_BIT) != 0u) {
        uint idx = InstanceID() & IDX_MASK;
        EntityGeom g = ConstPtr<EntityGeom>(pcAddr.entityTableAddr)[idx];
        Prim pr = ConstPtr<Prim>(g.primAddr)[PrimitiveIndex()];
        ConstPtr<uint> ib = ConstPtr<uint>(g.idxAddr);
        ConstPtr<float2> uvb = ConstPtr<float2>(g.uvAddr);
        uint p0 = ib[3u * PrimitiveIndex() + 0u];
        uint p1 = ib[3u * PrimitiveIndex() + 1u];
        uint p2 = ib[3u * PrimitiveIndex() + 2u];
        float3 pbary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        float2 puv = pbary.x * uvb[p0] + pbary.y * uvb[p1] + pbary.z * uvb[p2];
        int pslot = int(pr.tint.w + 0.5);
        float3x3 o2w = (float3x3)ObjectToWorld3x4();
        float3 pp0 = mul(o2w, HitTriangleVertexPosition(0));
        float3 pp1 = mul(o2w, HitTriangleVertexPosition(1));
        float3 pp2 = mul(o2w, HitTriangleVertexPosition(2));
        float particleLod = rayConeTextureLod(rayCone, texSizePx(entityTex[NonUniformResourceIndex(pslot)]),
                pp0, pp1, pp2, uvb[p0], uvb[p1], uvb[p2]);
        float3 pn = normalize(pr.normal.xyz);
        if (dot(pn, WorldRayDirection()) > 0.0) {
            pn = -pn;
        }
        payload.albedo = srgbToLinear(entityTex[NonUniformResourceIndex(pslot)].SampleLevel(puv, particleLod).rgb) * pr.tint.rgb;
        payload.normal = pn;
        payload.hitT = RayTCurrent();
        // Per-particle motion vector: interpolate the captured per-vertex displacement (uniform across
        // the billboard's verts) with the same indices/barycentrics as the UV. dispAddr == 0 falls back
        // to rigidDisp, which particles write as zero.
        if (g.dispAddr != 0) {
            ConstPtr<float4> pd = ConstPtr<float4>(g.dispAddr);
            payload.motionPrev = pbary.x * pd[p0].xyz + pbary.y * pd[p1].xyz + pbary.z * pd[p2].xyz;
        } else {
            payload.motionPrev = g.rigidDisp.xyz;
        }
        payload.f0 = float3(0.0, 0.0, 0.0);
        payloadSetPacked(payload, MATERIAL_PARTICLE, 1.0, 0.0, 0.0, 0.0, EMISSION_SOURCE_NONE);
        return;
    }
    // Dynamic entities: instances with this custom-index flag bit carry real captured ModelPart
    // geometry; the low bits index the entity geometry table, not the section table.
    if ((InstanceID() & ENTITY_BIT) != 0u) {
        uint eidx = InstanceID() & ~ENTITY_BIT;
        EntityGeom g = ConstPtr<EntityGeom>(pcAddr.entityTableAddr)[eidx];
        Prim pr = ConstPtr<Prim>(g.primAddr)[PrimitiveIndex()];
        // Rigid-reuse instances (still / spinning entities re-referencing a cached BLAS) carry a real
        // rotation in the instance transform; per-frame-built entities are identity. The cached prim
        // normal (and the TBN source positions below) are object-space — rotate them into world space.
        // Identity/translation instances make this a no-op.
        float3x3 entityO2w = (float3x3)ObjectToWorld3x4();
        // Orient the geometric normal toward the viewer first (so the _n TBN is built in the right
        // hemisphere — same fix as terrain).
        float3 vdir = -WorldRayDirection();
        float3 n = normalize(mul(entityO2w, pr.normal.xyz));
        if (dot(n, vdir) < 0.0) {
            n = -n;
        }
        // Interpolate the captured entity-texture UV (same scheme as terrain) and sample the bindless
        // per-type texture; tint.rgb is the model colour multiplier (white for most, tinted for sheep/…).
        ConstPtr<uint> eidxBuf = ConstPtr<uint>(g.idxAddr);
        ConstPtr<float2> euv = ConstPtr<float2>(g.uvAddr);
        uint e0 = eidxBuf[3u * PrimitiveIndex() + 0u];
        uint e1 = eidxBuf[3u * PrimitiveIndex() + 1u];
        uint e2 = eidxBuf[3u * PrimitiveIndex() + 2u];
        float3 ebary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        float2 euvCoord = ebary.x * euv[e0] + ebary.y * euv[e1] + ebary.z * euv[e2];
        int texSlot = int(pr.tint.w + 0.5);
        float3 ep0 = mul(entityO2w, HitTriangleVertexPosition(0));
        float3 ep1 = mul(entityO2w, HitTriangleVertexPosition(1));
        float3 ep2 = mul(entityO2w, HitTriangleVertexPosition(2));
        float entityLod = rayConeTextureLod(rayCone, texSizePx(entityTex[NonUniformResourceIndex(texSlot)]),
                ep0, ep1, ep2, euv[e0], euv[e1], euv[e2]);
        float3 albedo = srgbToLinear(entityTex[NonUniformResourceIndex(texSlot)].SampleLevel(euvCoord, entityLod).rgb) * pr.tint.rgb;
        uint materialId = uint(pr.mat.z + 0.5);
        MaterialHeader header = ConstPtr<MaterialHeader>(pcAddr.materialTableAddr)[materialId];
        Surface surface = evaluateMaterial(header, euvCoord, entityLod, albedo, n,
                ep0, ep1, ep2, euv[e0], euv[e1], euv[e2], vdir,
                header.params.x, header.params.y, 0.0, true);
        n = surface.normal;

        payload.albedo = albedo * surface.ao;
        payload.normal = n;
        payload.hitT = RayTCurrent();
        // Per-vertex motion vector: interpolate the captured per-vertex displacement with the same
        // indices/barycentrics used for the UV above. Rotation and skeletal/lid animation use a
        // per-vertex buffer; pure whole-object translation is packed into rigidDisp with no buffer.
        if (g.dispAddr != 0) {
            ConstPtr<float4> dd = ConstPtr<float4>(g.dispAddr);
            payload.motionPrev = ebary.x * dd[e0].xyz + ebary.y * dd[e1].xyz + ebary.z * dd[e2].xyz;
        } else {
            payload.motionPrev = g.rigidDisp.xyz;
        }
        payload.f0 = surface.f0;
        payloadSetPacked(payload, MATERIAL_OPAQUE, surface.roughness, surface.metalness,
                surface.emission, surface.sss, materialEmissionSource(header, surface.emission));
        return;
    }

    Section sec = ConstPtr<Section>(pcAddr.tableAddr)[InstanceID()];
    // Any-hit/SBT opt: the section BLAS has one fixed geometry per material bucket (solid / cutout /
    // translucent / water). PrimitiveIndex() restarts at 0 per geometry, so re-add this geometry's
    // triangle base to land in the section's packed prim/uv arrays (concatenated in BUCKET order).
    // Water prims keep tint.w == 1, converted to the packed material enum below.
    uint pid = PrimitiveIndex() + sec.triBase[GeometryIndex()];
    TerrainPrim pr = ConstPtr<TerrainPrim>(sec.primAddr)[pid];
    MaterialHeader materialHeader = ConstPtr<MaterialHeader>(pcAddr.materialTableAddr)[pr.materialId];
    float3 n = normalize(pr.normal.xyz);
    float3 tint = pr.tint.rgb;

    // Water prims are single-sided, wound outward from the fluid volume (RtFluidMesher), so the
    // UNFLIPPED geometric normal is a reliable "which side is water" signal: a ray travelling against
    // it is entering the volume, one travelling with it is exiting. Captured before the toward-viewer
    // flip below and carried in the payload so raygen can set inWater from hit orientation instead of
    // toggling parity per crossing (a single missing/extra face no longer desyncs the rest of the path).
    bool entering = dot(WorldRayDirection(), n) < 0.0;

    // Lever B: per-triangle corner UVs in primitive order. uvs[3*pid + k] is a contiguous,
    // directly-addressed load (no index buffer, no scattered vertex-UV gather), so it issues as soon as
    // pid is known and its latency overlaps the prim fetch instead of serialising behind an index load.
    // The index buffer still exists for the BLAS build; the shading path just no longer reads it.
    ConstPtr<float2> uvs = ConstPtr<float2>(sec.uvAddr);
    float2 uv0 = uvs[3u * pid + 0u];
    float2 uv1 = uvs[3u * pid + 1u];
    float2 uv2 = uvs[3u * pid + 2u];
    float3 bary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
    float2 uv = bary.x * uv0 + bary.y * uv1 + bary.z * uv2;
    float3 tp0 = HitTriangleVertexPosition(0);
    float3 tp1 = HitTriangleVertexPosition(1);
    float3 tp2 = HitTriangleVertexPosition(2);
    float blockLod = rayConeTextureLod(rayCone, texSizePx(blockAtlas), tp0, tp1, tp2, uv0, uv1, uv2);

    // Orient the GEOMETRIC normal toward the viewer FIRST (double-sided geometry), so the normal-map TBN
    // is built in the correct hemisphere. Doing this before the map — rather than flipping the perturbed
    // normal afterward — is what avoids the grazing-angle black spots: a post-flip could invert a tipped
    // mapped normal through the surface and light the back face.
    float3 vdir = -WorldRayDirection(); // toward the viewer
    if (dot(n, vdir) < 0.0) {
        n = -n;
    }

    // Stained glass / ice: a thin colored filter resolved in raygen
    // (material 3). albedo carries the transmission tint = texel rgb mixed toward white by (1 − opacity),
    // so a more opaque texel tints transmitted light more strongly.
    if (materialHeader.model == MATERIAL_GLASS) {
        float4 gtex = blockAtlas.SampleLevel(uv, blockLod);
        float3 gtexRgb = srgbToLinear(gtex.rgb);
        float3 glassAlbedo = lerp(float3(1.0, 1.0, 1.0), gtexRgb * tint, gtex.a);
        Surface glassSurface = evaluateMaterial(materialHeader, uv, blockLod, glassAlbedo, n,
                tp0, tp1, tp2, uv0, uv1, uv2, vdir,
                materialHeader.params.x, materialHeader.params.y, 0.0, false);
        n = glassSurface.normal;
        // Translucent blocks (glass, ice, …) are breakable too — apply the same overlay here, reusing
        // gtex (already sampled above) rather than re-fetching blockAtlas.
        payload.albedo = applyBreaking(glassAlbedo * glassSurface.ao,
                rayCone, WorldRayOrigin(), WorldRayDirection(), RayTCurrent(), n);
        payload.normal = n;
        payload.hitT = RayTCurrent();
        payload.motionPrev = float3(0.0, 0.0, 0.0);
        payload.f0 = glassSurface.f0;
        payloadSetPacked(payload, MATERIAL_GLASS, glassSurface.roughness,
                glassSurface.metalness, 0.0, 0.0, EMISSION_SOURCE_NONE);
        return;
    }

    // Water (tint.w == 1) carries the pure biome water tint (no grey water-texture multiply): raygen
    // shades the surface as a clear dielectric and only needs the tint to derive the per-channel
    // Beer–Lambert absorption. Opaque terrain uses textured albedo.
    payload.albedo = materialHeader.model == MATERIAL_WATER
            ? tint : srgbToLinear(blockAtlas.SampleLevel(uv, blockLod).rgb) * tint;
    payload.hitT = RayTCurrent();
    payload.motionPrev = float3(0.0, 0.0, 0.0); // static terrain: camera-only motion vector
    uint material = materialHeader.model == MATERIAL_WATER ? MATERIAL_WATER : MATERIAL_OPAQUE;

    // normal.w packs the 0..1 block-light level plus a +2 offset flag for non-SOLID (cutout /
    // translucent) render layers, set at extraction so the hit can opt SOLID terrain out of SSS below.
    float ew = pr.normal.w;
    bool nonSolid = ew >= 1.5;
    float emission = nonSolid ? ew - 2.0 : ew; // heuristic emission source (block light level)
    Surface surface = evaluateMaterial(materialHeader, uv, blockLod, payload.albedo, n,
            tp0, tp1, tp2, uv0, uv1, uv2, vdir,
            materialHeader.params.x, materialHeader.params.y, emission, nonSolid);
    n = surface.normal;
    payload.albedo *= surface.ao;
    // Block-breaking overlay: opaque/cutout terrain (water skips — fluids aren't breakable). Evaluate
    // it after normal mapping so decal projection uses the same shading orientation as the old path.
    if (material != MATERIAL_WATER) {
        payload.albedo = applyBreaking(payload.albedo, rayCone, WorldRayOrigin(), WorldRayDirection(), RayTCurrent(), n);
    }
    payload.normal = n;
    payload.f0 = surface.f0;
    payloadSetPacked(payload, material, surface.roughness, surface.metalness,
            surface.emission, surface.sss, materialEmissionSource(materialHeader, surface.emission));
    if (material == MATERIAL_WATER && entering) {
        payload.flags |= PAYLOAD_WATER_ENTERING;
    }
}
