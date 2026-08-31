// Closest-hit shader. Geometry is per-section: InstanceID() indexes a section table (BDA array reached
// from the push constant) holding this section's {prim, uv} buffer addresses. Per-primitive
// {normal, tint} plus barycentric atlas UVs produce the payload guides; lighting is deferred to raygen.
//
// tableAddr/entityTableAddr are read from the inline push-constant lanes (pc), not through the
// WorldPush BDA struct: they're read on every single hit (the hottest chain in the frame), and going
// through worldPushAddr first would cost an extra global-memory load just to find the address of the
// load we actually want. The cold WorldPush struct is dereferenced only for the breaking overlay.
import world_common;
import math;
import bindings;

void payloadSetPacked(inout Payload payload, uint material, float roughness, float metalness,
        float emission, float sss, float ior, float transmission, uint emissionSource) {
    payload.flags = material | (emissionSource << PAYLOAD_EMISSION_SOURCE_SHIFT);
    payload.roughMetal = packHalf2(float2(roughness, metalness));
    payload.emissionSss = packHalf2(float2(emission, sss));
    payload.iorTransmission = packHalf2(float2(ior, transmission));
}

// Which side of a dielectric face this hit is on, shared by every hit path. Comes from the face
// orientation, so it is re-derived at each crossing instead of toggled (see PAYLOAD_DIELECTRIC_ENTERING).
void payloadSetDielectric(inout Payload payload, uint material, bool entering) {
    if (material != MATERIAL_WATER && material != MATERIAL_DIELECTRIC) return;
    if (entering) payload.flags |= PAYLOAD_DIELECTRIC_ENTERING;
}

uint materialEmissionSource(MaterialHeader header, float emission) {
    if (emission <= 0.0) return EMISSION_SOURCE_NONE;
    if ((header.features & MATERIAL_FEATURE_SPEC) != 0u) return EMISSION_SOURCE_LAB_PBR;
    if ((header.features & MATERIAL_FEATURE_HEURISTIC_EMISSION) != 0u) return EMISSION_SOURCE_HEURISTIC;
    return EMISSION_SOURCE_UNIFORM;
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

// ---- Parallax occlusion mapping -------------------------------------------------------------------
//
// Packs such as Patrix author most of their apparent depth into the LabPBR height channel (_n alpha)
// rather than into geometry. Caustica already ingests that channel -- RtBlockMaterials writes it to
// normalAo.a and RtMaterialTextureData carries it correctly down the mip chain -- but nothing has ever
// read it. This does.
//
// WHAT THIS IS AND IS NOT. This shifts the texture coordinate along the view ray through the height
// field, so surfaces gain real depth parallax: mortar lines sink, stones stand proud, and the whole
// thing shifts correctly as you move. It does NOT displace geometry, so SILHOUETTES STAY FLAT -- a
// block edge viewed side-on is still a straight line. Actual displacement in a path tracer needs the
// height field to exist as geometry the rays can hit, either tessellated into the BLAS or handled by a
// custom intersection shader, which is a different and much larger feature. Every real-time POM
// implementation makes this same trade.
//
// The offset is applied ONCE, before the albedo fetch, so albedo, roughness, F0 and the normal map all
// land on the same shifted coordinate. Offsetting only the material channels would slide them out of
// register with the colour and look worse than no POM at all.
//
// LabPBR height: 1.0 is the surface, 0.0 the deepest point, so depth = 1 - height.

static const int POM_MAX_STEPS = 64;
static const int POM_REFINE_STEPS = 5;

// Tangent frame for the hit triangle. Duplicated from perturbNormal rather than shared, because that
// function consumes it immediately and threading it out would change a signature every shading path
// depends on. Same maths; if one changes, change both.
bool hitTangentFrame(float3 n, float3 p0, float3 p1, float3 p2, float2 t0, float2 t1, float2 t2,
                     out float3 T, out float3 B) {
    T = float3(1.0, 0.0, 0.0);
    B = float3(0.0, 1.0, 0.0);
    float2 g1 = t1 - t0;
    float2 g2 = t2 - t0;
    float det = g1.x * g2.y - g1.y * g2.x;
    if (abs(det) <= 1.0e-12) {
        return false;
    }
    float r = 1.0 / det;
    float3 traw = ((p1 - p0) * g2.y - (p2 - p0) * g1.y) * r;
    T = normalize(traw - n * dot(n, traw));
    B = cross(n, T) * (det < 0.0 ? -1.0 : 1.0);
    return true;
}

float pomHeightAt(MaterialHeader header, float2 localUv, float lod) {
    float2 pageUv = header.materialUv.xy + clamp(localUv, float2(0.0), float2(1.0)) * header.materialUv.zw;
    return samplePageNormalAo(header.texturePage, pageUv, lod).a;
}

// The march itself, in the sprite's own [0,1] space. Terrain reaches this through an atlas-rect
// conversion; entities pass their UV straight in, because an entity texture is its own bindless image
// rather than a region of a shared atlas — there is no rect to map through, and running terrain's
// conversion on an identity rect would be a no-op at best and a silent scale error at worst.
float2 parallaxLocalUv(MaterialHeader header, float2 localUv, float lod, float3 n,
                       float3 p0, float3 p1, float3 p2, float2 t0, float2 t1, float2 t2,
                       float3 vdir, float3 pomParams) {
    float depthScale = pomParams.x;
    if (depthScale <= 0.0 || (header.features & MATERIAL_FEATURE_NORMAL) == 0u) {
        return localUv;
    }
    // Distance fade. POM's cost is per-hit and its benefit vanishes once a sprite covers a few pixels,
    // so it is faded out by ray-cone LOD rather than switched off at a hard distance -- a hard cutoff
    // would pop a visible seam across the ground as you walk.
    float fade = 1.0 - smoothstep(pomParams.z - 1.0, pomParams.z, lod);
    if (fade <= 0.0) {
        return localUv;
    }
    float3 T;
    float3 B;
    if (!hitTangentFrame(n, p0, p1, p2, t0, t1, t2, T, B)) {
        return localUv;
    }
    // View direction in tangent space. At grazing angles vz goes to zero and the offset would explode,
    // so it is floored -- the classic POM stretching artifact is worse than the lost parallax.
    float3 viewT = float3(dot(vdir, T), dot(vdir, B), dot(vdir, n));
    float vz = max(abs(viewT.z), 0.35);

    localUv = clamp(localUv, float2(0.0), float2(1.0));
    // Total UV travel for a ray descending the full height range.
    float2 maxOffset = (viewT.xy / vz) * depthScale * fade;

    // Steps scale with view angle: a steep view crosses little of the height field and needs few
    // samples, a grazing one crosses a lot and needs many.
    int steps = int(clamp(lerp(float(POM_MAX_STEPS), 8.0, abs(viewT.z)) * max(pomParams.y, 0.1),
            4.0, float(POM_MAX_STEPS)));
    float stepDepth = 1.0 / float(steps);
    float2 stepOffset = maxOffset * stepDepth;

    // Linear search for the first step where the ray has sunk below the height field.
    float rayDepth = 0.0;
    float2 current = localUv;
    float height = pomHeightAt(header, current, lod);
    float surfaceDepth = 1.0 - height;
    for (int i = 0; i < steps; ++i) {
        if (rayDepth >= surfaceDepth) {
            break;
        }
        current -= stepOffset;
        rayDepth += stepDepth;
        surfaceDepth = 1.0 - pomHeightAt(header, current, lod);
    }

    // Binary refinement of the crossing. Cheaper and more stable than the usual linear interpolation
    // between the last two samples when the height field has hard steps, which block textures are
    // almost entirely made of.
    float2 lo = current;
    float2 hi = current + stepOffset;
    for (int i = 0; i < POM_REFINE_STEPS; ++i) {
        float2 mid = (lo + hi) * 0.5;
        float midRayDepth = rayDepth - stepDepth * 0.5;
        if (1.0 - pomHeightAt(header, mid, lod) > midRayDepth) {
            hi = mid;
        } else {
            lo = mid;
            rayDepth = midRayDepth;
        }
        stepDepth *= 0.5;
    }
    return clamp((lo + hi) * 0.5, float2(0.0), float2(1.0));
}

// Terrain: convert into the sprite's local space, march, convert back. Clamping happens inside the
// march, in local space, so the result can never bleed into a neighbouring sprite -- the single most
// common way POM goes wrong on a texture atlas.
float2 parallaxOffsetUv(MaterialHeader header, float2 atlasUv, float lod, float3 n,
                        float3 p0, float3 p1, float3 p2, float2 t0, float2 t1, float2 t2,
                        float3 vdir, float3 pomParams) {
    if (pomParams.x <= 0.0 || (header.features & MATERIAL_FEATURE_NORMAL) == 0u) {
        return atlasUv;
    }
    float2 localUv = (atlasUv - header.albedoUv.xy) * header.albedoUv.zw;
    float2 shifted = parallaxLocalUv(header, localUv, lod, n, p0, p1, p2, t0, t1, t2, vdir, pomParams);
    return header.albedoUv.xy + shifted / max(header.albedoUv.zw, float2(1.0e-8));
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

// Canonical material pages use a compact page index shared by their three dedicated arrays.
float4 samplePageSurface0(uint page, float2 uv, float lod) {
    return materialSurface0Tex[NonUniformResourceIndex(page)].SampleLevel(uv, lod);
}
float4 samplePageSurface1(uint page, float2 uv, float lod) {
    return materialSurface1Tex[NonUniformResourceIndex(page)].SampleLevel(uv, lod);
}
float4 samplePageNormalAo(uint page, float2 uv, float lod) {
    return materialNormalAoTex[NonUniformResourceIndex(page)].SampleLevel(uv, lod);
}

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
    if ((header.features & (MATERIAL_FEATURE_SPEC | MATERIAL_FEATURE_HEURISTIC_EMISSION)) != 0u) {
        float4 surface0 = samplePageSurface0(page, pageUv, materialLod);
        if ((header.features & MATERIAL_FEATURE_SPEC) != 0u) {
            float4 surface1 = samplePageSurface1(page, pageUv, materialLod); // LabPBR F0
            surface.roughness = surface0.r;
            surface.metalness = surface0.g;
            surface.emission = surface0.b;
            surface.sss = allowSss ? surface0.a : 0.0;
            // Canonical LabPBR F0 is authored in the asset's BT.709 RGB basis. Fresnel is evaluated in
            // the transport basis, so coloured metal/custom F0 crosses the same boundary as albedo.
            surface.f0 = clamp(bt709ToAcesCg(surface1.rgb), float3(0.0), float3(1.0));
        } else {
            // Eligibility is compiled from block-state semantics; the primitive's exact light level
            // supplies strength, while the page supplies only the normalized spatial mask.
            surface.emission = surface0.b * fallbackEmission;
        }
    }
    // Final HDR emitting-surface luminance (look-package baseline or absolute JSON cd/m² override), baked in Java at
    // material-compile time and packed unconditionally — see MATERIAL_EMISSION_STRENGTH_SHIFT. 0 for
    // non-emissive materials, so this multiply is always safe (and a no-op) when nothing above ran.
    float strength = float((header.features >> MATERIAL_EMISSION_STRENGTH_SHIFT)
            & MATERIAL_EMISSION_STRENGTH_MASK)
            * (MATERIAL_MAX_EMISSION_STRENGTH / float(MATERIAL_EMISSION_STRENGTH_MASK));
    surface.emission *= strength;
    if ((header.features & MATERIAL_FEATURE_NORMAL) != 0u) {
        float4 normalAo = samplePageNormalAo(page, pageUv, materialLod);
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
float3 applyBreaking(float3 albedo709, uint rayCone, float3 rayOrigin, float3 rayDir, float hitT, float3 n) {
    ConstPtr<WorldPush> pcRef = ConstPtr<WorldPush>(pc.worldPushAddr);
    uint breakCount = pcRef[0].breakCount;
    if (breakCount == 0u) {
        return albedo709;
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
            float decalLod = rayConeUnitUvLod(rayCone, texSizePx(entityAlbedoTex[NonUniformResourceIndex(ps.w)]));
            float3 crack709 = srgbToLinear(
                    entityAlbedoTex[NonUniformResourceIndex(ps.w)].SampleLevel(decalUv, decalLod).rgb);
            return clamp(crack709 * albedo709, 0.0, 1.0);
        }
    }
    return albedo709;
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
        EntityGeom g = ConstPtr<EntityGeom>(pc.entityTableAddr)[idx];
        uint pid = PrimitiveIndex() + g.triBase[GeometryIndex()];
        Prim pr = ConstPtr<Prim>(g.primAddr)[pid];
        ConstPtr<uint> ib = ConstPtr<uint>(g.idxAddr);
        ConstPtr<float2> uvb = ConstPtr<float2>(g.uvAddr);
        uint p0 = ib[3u * pid + 0u];
        uint p1 = ib[3u * pid + 1u];
        uint p2 = ib[3u * pid + 2u];
        float3 pbary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        float2 puv = pbary.x * uvb[p0] + pbary.y * uvb[p1] + pbary.z * uvb[p2];
        int pslot = int(pr.tint.w + 0.5);
        float3x3 o2w = (float3x3)ObjectToWorld3x4();
        float3 pp0 = mul(o2w, HitTriangleVertexPosition(0));
        float3 pp1 = mul(o2w, HitTriangleVertexPosition(1));
        float3 pp2 = mul(o2w, HitTriangleVertexPosition(2));
        float particleLod = rayConeTextureLod(rayCone, texSizePx(entityAlbedoTex[NonUniformResourceIndex(pslot)]),
                pp0, pp1, pp2, uvb[p0], uvb[p1], uvb[p2]);
        float3 pn = normalize(pr.normal.xyz);
        if (dot(pn, WorldRayDirection()) > 0.0) {
            pn = -pn;
        }
        float3 particleAlbedo709 = srgbToLinear(
                entityAlbedoTex[NonUniformResourceIndex(pslot)].SampleLevel(puv, particleLod).rgb)
                * srgbToLinear(pr.tint.rgb);
        packAlbedo(payload, bt709ToAcesCg(particleAlbedo709));
        packNormal(payload, pn);
        payload.hitT = RayTCurrent();
        // Per-particle motion vector: interpolate the captured per-vertex displacement (uniform across
        // the billboard's verts) with the same indices/barycentrics as the UV. dispAddr == 0 falls back
        // to rigidDisp, which particles write as zero.
        if (g.dispAddr != 0) {
            ConstPtr<float4> pd = ConstPtr<float4>(g.dispAddr);
            payload.motionPrev = half3(pbary.x * pd[p0].xyz + pbary.y * pd[p1].xyz + pbary.z * pd[p2].xyz);
        } else {
            payload.motionPrev = half3(g.rigidDisp.xyz);
        }
        payload.f0 = half3(0.0h, 0.0h, 0.0h);
        payloadSetPacked(payload, MATERIAL_PARTICLE, 1.0, 0.0, 0.0, 0.0,
                1.0, 0.0, EMISSION_SOURCE_NONE);
        return;
    }
    // Dynamic entities: instances with this custom-index flag bit carry real captured ModelPart
    // geometry; the low bits index the entity geometry table, not the section table.
    if ((InstanceID() & ENTITY_BIT) != 0u) {
        uint eidx = InstanceID() & ~ENTITY_BIT;
        EntityGeom g = ConstPtr<EntityGeom>(pc.entityTableAddr)[eidx];
        uint pid = PrimitiveIndex() + g.triBase[GeometryIndex()];
        Prim pr = ConstPtr<Prim>(g.primAddr)[pid];
        // Rigid-reuse instances (still / spinning entities re-referencing a cached BLAS) carry a real
        // rotation in the instance transform; per-frame-built entities are identity. The cached prim
        // normal (and the TBN source positions below) are object-space — rotate them into world space.
        // Identity/translation instances make this a no-op.
        float3x3 entityO2w = (float3x3)ObjectToWorld3x4();
        // Orient the geometric normal toward the viewer first (so the _n TBN is built in the right
        // hemisphere — same fix as terrain).
        float3 vdir = -WorldRayDirection();
        float3 n = normalize(mul(entityO2w, pr.normal.xyz));
        bool entering = dot(WorldRayDirection(), n) < 0.0;
        if (dot(n, vdir) < 0.0) {
            n = -n;
        }
        // Interpolate the captured entity-texture UV (same scheme as terrain) and sample the bindless
        // per-type texture; tint.rgb is the model colour multiplier (white for most, tinted for sheep/…).
        ConstPtr<uint> eidxBuf = ConstPtr<uint>(g.idxAddr);
        ConstPtr<float2> euv = ConstPtr<float2>(g.uvAddr);
        uint e0 = eidxBuf[3u * pid + 0u];
        uint e1 = eidxBuf[3u * pid + 1u];
        uint e2 = eidxBuf[3u * pid + 2u];
        float3 ebary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        float2 euvCoord = ebary.x * euv[e0] + ebary.y * euv[e1] + ebary.z * euv[e2];
        int texSlot = int(pr.tint.w + 0.5);
        float3 ep0 = mul(entityO2w, HitTriangleVertexPosition(0));
        float3 ep1 = mul(entityO2w, HitTriangleVertexPosition(1));
        float3 ep2 = mul(entityO2w, HitTriangleVertexPosition(2));
        float entityLod = rayConeTextureLod(rayCone, texSizePx(entityAlbedoTex[NonUniformResourceIndex(texSlot)]),
                ep0, ep1, ep2, euv[e0], euv[e1], euv[e2]);
        MaterialHeader header = ConstPtr<MaterialHeader>(pc.materialTableAddr)[pr.materialId];
        // Parallax on entity geometry, before the albedo fetch so colour and material channels stay in
        // register — same rule as terrain. Entity UVs are already in the texture's own space, so the
        // march runs directly rather than through an atlas rect. Mob armour, shulker shells and chest
        // trim carry LabPBR height in packs like Patrix, and without this they stay flat while the
        // blocks around them do not.
        euvCoord = parallaxLocalUv(header, euvCoord, entityLod, n, ep0, ep1, ep2,
                                   euv[e0], euv[e1], euv[e2], vdir,
                                   ConstPtr<WorldPush>(pc.worldPushAddr)[0].pom.xyz);
        float4 entityTexel = entityAlbedoTex[NonUniformResourceIndex(texSlot)].SampleLevel(euvCoord, entityLod);
        uint material = header.model;
        float3 baseAlbedo709 = srgbToLinear(entityTexel.rgb) * srgbToLinear(pr.tint.rgb);
        float3 opticalColor709 = material == MATERIAL_DIELECTRIC
                ? lerp(float3(1.0), baseAlbedo709, entityTexel.a) : baseAlbedo709;
        float3 albedo = bt709ToAcesCg(opticalColor709);
        Surface surface = evaluateMaterial(header, euvCoord, entityLod, albedo, n,
                ep0, ep1, ep2, euv[e0], euv[e1], euv[e2], vdir,
                header.params.x, header.params.y, pr.normal.w, material == MATERIAL_OPAQUE);
        n = surface.normal;

        packAlbedo(payload, albedo * surface.ao);
        packNormal(payload, n);
        payload.hitT = RayTCurrent();
        // Per-vertex motion vector: interpolate the captured per-vertex displacement with the same
        // indices/barycentrics used for the UV above. Rotation and skeletal/lid animation use a
        // per-vertex buffer; pure whole-object translation is packed into rigidDisp with no buffer.
        if (g.dispAddr != 0) {
            ConstPtr<float4> dd = ConstPtr<float4>(g.dispAddr);
            payload.motionPrev = half3(ebary.x * dd[e0].xyz + ebary.y * dd[e1].xyz + ebary.z * dd[e2].xyz);
        } else {
            payload.motionPrev = half3(g.rigidDisp.xyz);
        }
        payload.f0 = half3(surface.f0);
        float emission = material == MATERIAL_OPAQUE ? surface.emission : 0.0;
        float sss = material == MATERIAL_OPAQUE ? surface.sss : 0.0;
        payloadSetPacked(payload, material, surface.roughness, surface.metalness,
                emission, sss, header.params.z, header.params.w,
                materialEmissionSource(header, emission));
        payloadSetDielectric(payload, material, entering);
        return;
    }

    Section sec = ConstPtr<Section>(pc.tableAddr)[InstanceID()];
    // Any-hit/SBT opt: the section BLAS has one fixed geometry per material bucket (solid / cutout /
    // translucent / water). PrimitiveIndex() restarts at 0 per geometry, so re-add this geometry's
    // triangle base to land in the section's packed prim/uv arrays (concatenated in BUCKET order).
    // Water prims keep tint.w == 1, converted to the packed material enum below.
    uint pid = PrimitiveIndex() + sec.triBase[GeometryIndex()];
    TerrainPrim pr = ConstPtr<TerrainPrim>(sec.primAddr)[pid];
    MaterialHeader materialHeader = ConstPtr<MaterialHeader>(pc.materialTableAddr)[pr.materialId];
    float3 n = normalize(pr.normal.xyz);
    float3 tint = pr.tint.rgb;

    // Water prims are single-sided, wound outward from the fluid volume (RtFluidMesher), so the
    // UNFLIPPED geometric normal is a reliable "which side of the dielectric" signal: a ray travelling
    // against it is entering the volume, one travelling with it is exiting. Captured before the
    // toward-viewer flip below and carried in the payload so raygen derives the medium from hit
    // orientation instead of toggling parity per crossing, so one malformed face cannot desync the path.
    bool entering = dot(WorldRayDirection(), n) < 0.0;

    // Lever B: per-triangle corner UVs in primitive order. uvs[3*pid + k] is a contiguous,
    // directly-addressed load (no index buffer, no scattered vertex-UV gather), so it issues as soon as
    // pid is known and its latency overlaps the prim fetch instead of serialising behind an index load.
    // The index buffer remains BLAS-only; shading reads the expanded UV buffer directly.
    ConstPtr<float2> uvs = ConstPtr<float2>(sec.uvAddr);
    float2 uv0 = uvs[3u * pid + 0u];
    float2 uv1 = uvs[3u * pid + 1u];
    float2 uv2 = uvs[3u * pid + 2u];
    float3 bary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
    float2 uv = bary.x * uv0 + bary.y * uv1 + bary.z * uv2;
    float3 tp0 = HitTriangleVertexPosition(0);
    float3 tp1 = HitTriangleVertexPosition(1);
    float3 tp2 = HitTriangleVertexPosition(2);
    float blockLod = rayConeTextureLod(rayCone, texSizePx(blockAlbedoAtlas), tp0, tp1, tp2, uv0, uv1, uv2);

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
    if (materialHeader.model == MATERIAL_DIELECTRIC) {
        float4 gtex = blockAlbedoAtlas.SampleLevel(uv, blockLod);
        float3 gtex709 = srgbToLinear(gtex.rgb);
        float3 tint709 = srgbToLinear(tint);
        float3 opticalColor709 = lerp(float3(1.0), gtex709 * tint709, gtex.a);
        float3 glassAlbedo = bt709ToAcesCg(opticalColor709);
        Surface glassSurface = evaluateMaterial(materialHeader, uv, blockLod, glassAlbedo, n,
                tp0, tp1, tp2, uv0, uv1, uv2, vdir,
                materialHeader.params.x, materialHeader.params.y, 0.0, false);
        n = glassSurface.normal;
        // Translucent blocks (glass, ice, …) are breakable too — apply the same overlay here, reusing
        // gtex (already sampled above) rather than re-fetching blockAlbedoAtlas.
        packAlbedo(payload, bt709ToAcesCg(applyBreaking(
                opticalColor709 * glassSurface.ao, rayCone, WorldRayOrigin(),
                WorldRayDirection(), RayTCurrent(), n)));
        packNormal(payload, n);
        payload.hitT = RayTCurrent();
        payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
        payload.f0 = half3(glassSurface.f0);
        payloadSetPacked(payload, MATERIAL_DIELECTRIC, glassSurface.roughness,
                glassSurface.metalness, 0.0, 0.0, materialHeader.params.z,
                materialHeader.params.w, EMISSION_SOURCE_NONE);
        payloadSetDielectric(payload, MATERIAL_DIELECTRIC, entering);
        return;
    }

    // Water (tint.w == 1) carries the pure biome water tint (no grey water-texture multiply): raygen
    // shades the surface as a clear dielectric and only needs the tint to derive the per-channel
    // Beer–Lambert absorption. Opaque terrain uses textured albedo.
    float3 tint709 = srgbToLinear(tint);
    // Parallax BEFORE the albedo fetch, so every channel sampled below shares one shifted coordinate.
    // One indirect load for the POM parameters, taken only on the terrain path that uses them rather
    // than at shader entry, matching how the entity path above reaches WorldPush.
    uv = parallaxOffsetUv(materialHeader, uv, blockLod, n, tp0, tp1, tp2, uv0, uv1, uv2, vdir,
                          ConstPtr<WorldPush>(pc.worldPushAddr)[0].pom.xyz);
    float3 albedo709 = srgbToLinear(blockAlbedoAtlas.SampleLevel(uv, blockLod).rgb) * tint709;
    packAlbedo(payload, bt709ToAcesCg(
            materialHeader.model == MATERIAL_WATER ? tint709 : albedo709));
    payload.hitT = RayTCurrent();
    payload.motionPrev = half3(0.0h, 0.0h, 0.0h); // static terrain: camera-only motion vector
    uint material = materialHeader.model == MATERIAL_WATER ? MATERIAL_WATER : MATERIAL_OPAQUE;

    // normal.w packs the 0..1 block-light level plus a +2 offset flag for non-SOLID (cutout /
    // translucent) render layers, set at extraction so the hit can opt SOLID terrain out of SSS below.
    float ew = pr.normal.w;
    bool nonSolid = ew >= 1.5;
    float emission = nonSolid ? ew - 2.0 : ew; // heuristic emission source (block light level)
    Surface surface = evaluateMaterial(materialHeader, uv, blockLod, unpackAlbedo(payload), n,
            tp0, tp1, tp2, uv0, uv1, uv2, vdir,
            materialHeader.params.x, materialHeader.params.y, emission, nonSolid);
    n = surface.normal;
    packAlbedo(payload, unpackAlbedo(payload) * surface.ao);
    // Block-breaking overlay: opaque/cutout terrain (water skips — fluids aren't breakable). Evaluate
    // it after normal mapping so decal projection uses the final shading orientation.
    if (material != MATERIAL_WATER) {
        packAlbedo(payload, bt709ToAcesCg(applyBreaking(
                albedo709 * surface.ao, rayCone, WorldRayOrigin(),
                WorldRayDirection(), RayTCurrent(), n)));
    }
    packNormal(payload, n);
    payload.f0 = half3(surface.f0);
    payloadSetPacked(payload, material, surface.roughness, surface.metalness,
            surface.emission, surface.sss, materialHeader.params.z, materialHeader.params.w,
            materialEmissionSource(materialHeader, surface.emission));
    payloadSetDielectric(payload, material, entering);
    // RIS emitter-NEE membership: raygen gates this emitter's direct-hit emission term (RIS covers it).
    if ((pr.flags & TERRAIN_PRIM_IN_LIGHT_BUFFER) != 0u) {
        payload.flags |= PAYLOAD_EMITTER_IN_LIST;
    }
}
