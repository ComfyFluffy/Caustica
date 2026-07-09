#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
#extension GL_EXT_nonuniform_qualifier : require
#extension GL_EXT_ray_tracing_position_fetch : require // gl_HitTriangleVertexPositionsEXT for TBN

// Closest-hit shader. Geometry is per-section: gl_InstanceCustomIndexEXT indexes a section table (BDA
// array reached from the push constant) holding this section's {prim, index, uv} buffer addresses.
// Per-primitive {normal, tint} plus barycentric atlas UVs produce the payload guides; lighting is deferred
// to raygen.
struct Prim {
    vec4 normal;
    vec4 tint;
    // {roughness, metalness, hasS, hasN} heuristic PBR material (RtMaterials). Unread by the
    // TRANSLUCENT (stained glass / ice) early return below, whose prim records repurpose this lane for a
    // precomputed avg sprite color instead — see world.rahit's shadow-ray BUCKET_TRANSLUCENT path.
    vec4 mat;
};
struct Section {
    uint64_t primAddr;
    uint64_t uvAddr;
    uint triBase[4]; // fixed bucket triangle offsets: solid/cutout/translucent/water
};
// Entity geometry record: the entity's {prim, index, uv} buffer addresses (same per-prim
// {normal, tint} + UV layout as a terrain section) plus the address of a per-vertex world-space
// displacement buffer (cur - prev frame; vec4 per vertex, xyz used) for the motion vector. The
// displacement is per-vertex (barycentric-interpolated below) so rotating mobs and animating
// block entities reproject correctly. dispAddr == 0 ⇒ rigidDisp.xyz (zero for static/new objects).
// std430 packs dispAddr at offset 24 and keeps the 48-byte stride; matches RtEntities.
struct EntityGeom {
    uint64_t primAddr;
    uint64_t idxAddr;
    uint64_t uvAddr;
    uint64_t dispAddr;
    vec4 rigidDisp;
};

layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Prims { Prim p[]; };
layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer Indices { uint i[]; };
layout(buffer_reference, std430, buffer_reference_align = 8) readonly buffer UVs { vec2 uv[]; };
layout(buffer_reference, std430, buffer_reference_align = 8) readonly buffer SectionTable { Section s[]; };
// Per-entity geometry records, indexed by the entity instance index (the low bits of
// gl_InstanceCustomIndexEXT). See RtEntities.
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer EntityTable { EntityGeom e[]; };
// Per-vertex world-space displacement (cur - prev frame), parallel to the vertex/UV buffers
// (vec4 per vertex, xyz used). Barycentric-interpolated at the hit for a per-vertex motion vector.
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Disps { vec4 d[]; };

layout(binding = 2, set = 0) uniform sampler2D blockAtlas;
// Parallel LabPBR _s (specular) and _n (normal) atlases, stitched to mirror the block atlas
// sprite layout (RtBlockMaterials), sampled at the SAME uv as blockAtlas. Read only when the prim is
// flagged (pr.mat.z for _s, pr.mat.w for _n).
layout(binding = 9, set = 0) uniform sampler2D blockSpecAtlas;
layout(binding = 10, set = 0) uniform sampler2D blockNormalAtlas;
// Bindless entity textures — a runtime-sized array indexed per-prim (tint.w) by the entity
// hit path. Slot 0 is a fallback. Entities use per-type texture files, so each RenderType gets a slot.
layout(binding = 0, set = 1) uniform sampler2D entityTex[];
// Parallel per-type LabPBR _n / _s for entities, sampled at the SAME bindless slot as the albedo.
layout(binding = 1, set = 1) uniform sampler2D entityNormalTex[];
layout(binding = 2, set = 1) uniform sampler2D entitySpecTex[];

// Per-frame push data lives in a host-visible BDA buffer; only its 8-byte address is pushed. `pc` lazily
// loads the needed field, avoiding a whole-struct copy per hit. The std430 layout matches RtComposite and
// raygen's WorldPush.
//
// tableAddr/entityTableAddr are ALSO duplicated as real inline push constants (pcAddr.tableAddr /
// pcAddr.entityTableAddr) below: they're read on every single hit (Section/EntityGeom fetch, the
// hottest chain in the frame), so going through pcAddr.worldPushAddr first would cost an extra
// global-memory load just to find the address of the load we actually want. Read them via pcAddr
// directly instead of pc; pc/WorldPush still carries them (harmless duplication) for the fields
// below (breaking overlay) that do need the cold BDA struct.
//
// Block-breaking overlay: xyz = the breaking block's position (rebased, matches gl_WorldRay*EXT
// space), w = the bindless entityTex[] slot holding that block's destroy-stage texture (RtEntityTextures;
// destroy_stage_N.png is a standalone Sampler0 texture, not a block-atlas sprite).
struct BreakEntry { ivec4 posSlot; };
struct WorldPush {
    mat4 invViewProj;
    vec3 camOffset;
    uint64_t tableAddr;        // 80  section table
    uint debugView; uint frameIndex;
    mat4 prevViewProj;
    vec3 camDelta; uint spp;
    vec2 jitter;
    uint64_t entityTableAddr;  // 184 entity geometry table
    uint flags;
    vec4 sunDir; vec4 lightDir; vec4 lightRadiance; // 208/224/240
    // Unused by this shader below — declared only to keep std430 offsets aligned with the CPU layout
    // (a std430 buffer_reference struct can be a truncated PREFIX of the real layout, but can't skip a
    // field in the middle of the prefix it does read).
    vec4 moonDir; vec4 celestial; vec4 sunUv; vec4 moonUv; vec4 waterParams; vec4 waterAnchor; // 256..352
    mat4 curViewProj; // 352
    uint breakCount; uint breakPad0, breakPad1, breakPad2; // 416
    BreakEntry breaking[8]; // 432
};
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer WorldPushRef { WorldPush v; };
layout(push_constant) uniform PushAddr {
    uint64_t worldPushAddr;
    uint64_t tableAddr;
    uint64_t entityTableAddr;
    uint frameIndex;
} pcAddr;
#define pc WorldPushRef(pcAddr.worldPushAddr).v

struct Payload {
    vec3 albedo;
    vec3 normal;
    float hitT;
    vec3 motionPrev; // world displacement since last frame (entity per-object MV); 0 for static terrain
    vec3 f0;         // specular reflectance at normal incidence (dielectric 0.04 / LabPBR / metal)
    uint flags;       // bits 0..1 = material, bit 2 = show celestial disc on miss
    uint roughMetal;  // packHalf2x16(vec2(roughness, metalness))
    uint emissionSss; // packHalf2x16(vec2(emission, sss))
    uint rayCone;     // packHalf2x16(vec2(width, spread))
};
layout(location = 0) rayPayloadInEXT Payload payload;
hitAttributeEXT vec2 attribs;

const uint MATERIAL_OPAQUE = 0u;
const uint MATERIAL_WATER = 1u;
const uint MATERIAL_PARTICLE = 2u;
const uint MATERIAL_GLASS = 3u;

void payloadSetPacked(uint material, float roughness, float metalness, float emission, float sss) {
    payload.flags = material;
    payload.roughMetal = packHalf2x16(vec2(roughness, metalness));
    payload.emissionSss = packHalf2x16(vec2(emission, sss));
}

// Dynamic entities: instances with this custom-index flag bit carry real captured ModelPart
// geometry. Their gl_InstanceCustomIndexEXT (low bits) indexes the entity geometry table, not the
// section table; shading reads per-prim normal + vertex-colour tint (no atlas — entity textures are
// bindless) and the per-object MV displacement.
const int ENTITY_BIT = 0x800000;
// Particles: a single combined billboard mesh sharing the entity geometry table + bindless atlas path,
// but shaded as an unlit cutout billboard (see the particle branch below). 0x400000 (bit 22); the low 22
// bits index the geom table (IDX_MASK). Particle instances also carry TLAS mask 0x02 (camera-ray-only).
const int PARTICLE_BIT = 0x400000;
const int IDX_MASK = 0x3FFFFF;

// LabPBR predefined metals (green channel 230..237): complex refractive indices N (eta) and K
// (kappa) per RGB. F0 = ((n-1)^2 + k^2) / ((n+1)^2 + k^2). Values per the LabPBR 1.3 standard.
const vec3 METAL_N[8] = vec3[8](
    vec3(2.9114, 2.9497, 2.5845),   // 230 iron
    vec3(0.18299, 0.42108, 1.3734), // 231 gold
    vec3(1.3456, 0.96521, 0.61722), // 232 aluminum
    vec3(3.1071, 3.1812, 2.3230),   // 233 chrome
    vec3(0.27105, 0.67693, 1.3164), // 234 copper
    vec3(1.9100, 1.8300, 1.4400),   // 235 lead
    vec3(2.3757, 2.0847, 1.8453),   // 236 platinum
    vec3(0.15943, 0.14512, 0.13547) // 237 silver
);
const vec3 METAL_K[8] = vec3[8](
    vec3(3.0893, 2.9318, 2.7670),
    vec3(3.4242, 2.3459, 1.7704),
    vec3(7.4746, 6.3995, 5.3031),
    vec3(3.3314, 3.3291, 3.1350),
    vec3(3.6092, 2.6248, 2.2921),
    vec3(3.5100, 3.4000, 3.1800),
    vec3(4.2655, 3.7153, 3.1365),
    vec3(3.9291, 3.1900, 2.3808)
);
vec3 metalF0(int idx) {
    vec3 n = METAL_N[idx];
    vec3 k = METAL_K[idx];
    return ((n - 1.0) * (n - 1.0) + k * k) / ((n + 1.0) * (n + 1.0) + k * k);
}

// Strength of the LabPBR _n ambient-occlusion (blue) applied to albedo. Kept mild because the
// path tracer already computes its own AO from sky-visibility rays (avoid double-darkening).
const float NORMAL_AO_STRENGTH = 0.5;

const float RAY_CONE_MIN_WIDTH = 1.0e-5;
const float RAY_CONE_MIN_TEXEL_FOOTPRINT = 1.0e-4;
const float RAY_CONE_MAX_LOD = 12.0;

float rayConeHitWidth() {
    vec2 cone = unpackHalf2x16(payload.rayCone);
    return max(cone.x + cone.y * max(gl_HitTEXT, 0.0), RAY_CONE_MIN_WIDTH);
}

float edgeTexelsPerWorld(vec3 p0, vec3 p1, vec2 uv0, vec2 uv1, vec2 textureSizePx) {
    float worldLen = length(p1 - p0);
    if (worldLen <= 1.0e-7) {
        return 0.0;
    }
    return length((uv1 - uv0) * textureSizePx) / worldLen;
}

float rayConeTextureLod(vec2 textureSizePx, vec3 p0, vec3 p1, vec3 p2, vec2 uv0, vec2 uv1, vec2 uv2) {
    float texelsPerWorld = max(edgeTexelsPerWorld(p0, p1, uv0, uv1, textureSizePx),
            max(edgeTexelsPerWorld(p1, p2, uv1, uv2, textureSizePx),
                edgeTexelsPerWorld(p2, p0, uv2, uv0, textureSizePx)));
    float footprint = max(rayConeHitWidth() * texelsPerWorld, RAY_CONE_MIN_TEXEL_FOOTPRINT);
    return clamp(log2(footprint), 0.0, RAY_CONE_MAX_LOD);
}

float rayConeUnitUvLod(vec2 textureSizePx) {
    float footprint = max(rayConeHitWidth() * max(textureSizePx.x, textureSizePx.y), RAY_CONE_MIN_TEXEL_FOOTPRINT);
    return clamp(log2(footprint), 0.0, RAY_CONE_MAX_LOD);
}

// LabPBR _s decode (shared by terrain + entities): red = perceptual smoothness -> roughness; green =
// reflectance (dielectric F0 0..229 / predefined metal 230..237 / generic metal albedo); alpha = emission
// (255 = ignored). `albedo` feeds the generic-metal F0.
void decodeSpec(vec4 s, vec3 albedo, out float rough, out float metal, out vec3 f0, out float emission, out float sss) {
    rough = (1.0 - s.r) * (1.0 - s.r);
    float g = s.g * 255.0;
    if (g < 229.5) {
        metal = 0.0;
        f0 = vec3(s.g);
    } else if (g < 237.5) {
        metal = 1.0;
        f0 = metalF0(int(g + 0.5) - 230);
    } else {
        metal = 1.0;
        f0 = albedo;
    }
    float a = s.a * 255.0;
    emission = a < 254.5 ? a / 254.0 : 0.0;
    float b = s.b * 255.0;
    sss = b > 64.5 ? (b - 65.0) / 190.0 : 0.0; // porosity 0-64 ignored; SSS 65-255 -> 0..1
}

// LabPBR _n decode (shared): rotate the tangent-space normal into world space via a TBN built from the hit
// triangle's vertex positions (VK_KHR_ray_tracing_position_fetch) + UVs. `n` must already be oriented
// toward the viewer (`vdir`). Clamps the result above the horizon so grazing perturbations don't invert it
// through the surface (the black-spot fix). Returns AO (blue) via `ao`; falls back to `n` on a degenerate
// UV triangle. Instance transforms are translation-only, so object edges equal world edges.
vec3 perturbNormal(vec3 n, vec3 p0, vec3 p1, vec3 p2, vec2 t0, vec2 t1, vec2 t2, vec3 vdir, vec4 ntex, out float ao) {
    ao = 1.0;
    vec2 g1 = t1 - t0;
    vec2 g2 = t2 - t0;
    float det = g1.x * g2.y - g1.y * g2.x;
    if (abs(det) <= 1.0e-12) {
        return n;
    }
    float r = 1.0 / det;
    vec3 traw = ((p1 - p0) * g2.y - (p2 - p0) * g1.y) * r;
    vec3 T = normalize(traw - n * dot(n, traw));       // Gram-Schmidt against the viewer-oriented normal
    vec3 B = cross(n, T) * (det < 0.0 ? -1.0 : 1.0);   // handedness from the UV winding
    vec2 nxy = ntex.xy * 2.0 - 1.0;                    // R,G = tangent-space X,Y
    float nz = sqrt(max(0.0, 1.0 - dot(nxy, nxy)));    // reconstruct Z
    vec3 nm = normalize(nxy.x * T + nxy.y * B + nz * n);
    float NoV = dot(nm, vdir);
    if (NoV < 0.02) {
        nm = normalize(nm + vdir * (0.02 - NoV));      // keep above the horizon (no flip)
    }
    ao = mix(1.0, ntex.b, NORMAL_AO_STRENGTH);
    return nm;
}

// Block-breaking overlay: shared by both opaque terrain AND stained-glass/ice (tint.w == 2 —
// translucent blocks are breakable too), so it's a function rather than inlined twice — each call
// site passes in the albedo it already computed (never re-fetches blockAtlas itself). Reconstructs the
// hit position in the same rebased space the breaking list was pushed in, nudges it into the solid
// block along -normal to get the block's integer coordinate, then decal-projects the local [0,1]
// position onto whichever axis pair the normal is most aligned with (same idea as vanilla's
// SheetedDecalTextureGenerator, which is how the real crumbling overlay tiles regardless of the
// block's own UV) and multiply-blends the matching destroy-stage crack texture in, mirroring vanilla's
// crumbling blend (DST_COLOR*SRC_COLOR, doubled — the destroy textures are mid-gray where uncracked).
// Approximate orientation is sufficient for the crack overlay.
vec3 applyBreaking(vec3 albedo, vec3 rayOrigin, vec3 rayDir, float hitT, vec3 n) {
    if (pc.breakCount == 0u) {
        return albedo;
    }
    vec3 hitPos = rayOrigin + rayDir * hitT;
    ivec3 blockPos = ivec3(floor(hitPos - n * 0.01));
    for (uint bi = 0u; bi < pc.breakCount; ++bi) {
        ivec4 ps = pc.breaking[bi].posSlot;
        if (all(equal(ps.xyz, blockPos))) {
            vec3 local = hitPos - vec3(blockPos);
            vec3 an = abs(n);
            vec2 decalUv = (an.x >= an.y && an.x >= an.z) ? local.zy
                         : (an.y >= an.z) ? local.xz
                         : local.xy;
            float decalLod = rayConeUnitUvLod(vec2(textureSize(entityTex[nonuniformEXT(ps.w)], 0)));
            vec3 crack = textureLod(entityTex[nonuniformEXT(ps.w)], decalUv, decalLod).rgb;
            return clamp(crack * albedo, 0.0, 1.0);
        }
    }
    return albedo;
}

void main() {
    // Particle billboard: same geom-table/UV/bindless-atlas path as entities. Albedo is atlas texel *
    // per-particle colour; raygen treats material 2 as a primary-visible diffuse receiver with no emission
    // or specular response. Reached only by the primary ray (instance mask 0x02). Cutout: rahit.
    if ((gl_InstanceCustomIndexEXT & PARTICLE_BIT) != 0) {
        int idx = gl_InstanceCustomIndexEXT & IDX_MASK;
        EntityGeom g = EntityTable(pcAddr.entityTableAddr).e[idx];
        Prim pr = Prims(g.primAddr).p[gl_PrimitiveID];
        Indices ib = Indices(g.idxAddr);
        UVs uvb = UVs(g.uvAddr);
        uint p0 = ib.i[3u * gl_PrimitiveID + 0u];
        uint p1 = ib.i[3u * gl_PrimitiveID + 1u];
        uint p2 = ib.i[3u * gl_PrimitiveID + 2u];
        vec3 pbary = vec3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        vec2 puv = pbary.x * uvb.uv[p0] + pbary.y * uvb.uv[p1] + pbary.z * uvb.uv[p2];
        int pslot = int(pr.tint.w + 0.5);
        vec3 pp0 = mat3(gl_ObjectToWorldEXT) * gl_HitTriangleVertexPositionsEXT[0];
        vec3 pp1 = mat3(gl_ObjectToWorldEXT) * gl_HitTriangleVertexPositionsEXT[1];
        vec3 pp2 = mat3(gl_ObjectToWorldEXT) * gl_HitTriangleVertexPositionsEXT[2];
        float particleLod = rayConeTextureLod(vec2(textureSize(entityTex[nonuniformEXT(pslot)], 0)),
                pp0, pp1, pp2, uvb.uv[p0], uvb.uv[p1], uvb.uv[p2]);
        vec3 pn = normalize(pr.normal.xyz);
        if (dot(pn, gl_WorldRayDirectionEXT) > 0.0) {
            pn = -pn;
        }
        payload.albedo = textureLod(entityTex[nonuniformEXT(pslot)], puv, particleLod).rgb * pr.tint.rgb;
        payload.normal = pn;
        payload.hitT = gl_HitTEXT;
        // Per-particle motion vector: interpolate the captured per-vertex displacement (uniform across the
        // billboard's verts) with the same indices/barycentrics as the UV. dispAddr == 0 falls back to
        // rigidDisp, which particles write as zero.
        if (g.dispAddr != 0ul) {
            Disps pd = Disps(g.dispAddr);
            payload.motionPrev = pbary.x * pd.d[p0].xyz + pbary.y * pd.d[p1].xyz + pbary.z * pd.d[p2].xyz;
        } else {
            payload.motionPrev = g.rigidDisp.xyz;
        }
        payload.f0 = vec3(0.0);
        payloadSetPacked(MATERIAL_PARTICLE, 1.0, 0.0, 0.0, 0.0);
        return;
    }
    if ((gl_InstanceCustomIndexEXT & ENTITY_BIT) != 0) {
        int eidx = gl_InstanceCustomIndexEXT & ~ENTITY_BIT;
        EntityGeom g = EntityTable(pcAddr.entityTableAddr).e[eidx];
        Prim pr = Prims(g.primAddr).p[gl_PrimitiveID];
        // Rigid-reuse instances (still / spinning entities re-referencing a cached BLAS) carry a real
        // rotation in the instance transform; per-frame-built entities are identity. The cached prim
        // normal (and the TBN source positions below) are object-space — rotate them into world space.
        // Identity/translation instances make this a no-op.
        mat3 entityO2w = mat3(gl_ObjectToWorldEXT);
        // Orient the geometric normal toward the viewer first (so the _n TBN is built in the right
        // hemisphere — same fix as terrain).
        vec3 vdir = -gl_WorldRayDirectionEXT;
        vec3 n = normalize(entityO2w * pr.normal.xyz);
        if (dot(n, vdir) < 0.0) {
            n = -n;
        }
        // Interpolate the captured entity-texture UV (same scheme as terrain) and sample the bindless
        // per-type texture; tint.rgb is the model colour multiplier (white for most, tinted for sheep/etc.).
        Indices eidxBuf = Indices(g.idxAddr);
        UVs euv = UVs(g.uvAddr);
        uint e0 = eidxBuf.i[3u * gl_PrimitiveID + 0u];
        uint e1 = eidxBuf.i[3u * gl_PrimitiveID + 1u];
        uint e2 = eidxBuf.i[3u * gl_PrimitiveID + 2u];
        vec3 ebary = vec3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        vec2 euvCoord = ebary.x * euv.uv[e0] + ebary.y * euv.uv[e1] + ebary.z * euv.uv[e2];
        int texSlot = int(pr.tint.w + 0.5);
        vec3 ep0 = entityO2w * gl_HitTriangleVertexPositionsEXT[0];
        vec3 ep1 = entityO2w * gl_HitTriangleVertexPositionsEXT[1];
        vec3 ep2 = entityO2w * gl_HitTriangleVertexPositionsEXT[2];
        float entityLod = rayConeTextureLod(vec2(textureSize(entityTex[nonuniformEXT(texSlot)], 0)),
                ep0, ep1, ep2, euv.uv[e0], euv.uv[e1], euv.uv[e2]);
        float blockEntityLod = rayConeTextureLod(vec2(textureSize(blockAtlas, 0)),
                ep0, ep1, ep2, euv.uv[e0], euv.uv[e1], euv.uv[e2]);

        vec3 albedo = textureLod(entityTex[nonuniformEXT(texSlot)], euvCoord, entityLod).rgb * pr.tint.rgb;
        float rough = pr.mat.x;          // heuristic material default
        float metal = pr.mat.y;
        vec3 f0 = mix(vec3(0.04), albedo, metal);
        float emission = 0.0;
        float ao = 1.0;
        float sss = 0.0;
        // LabPBR _s / _n for entities. mat.z/mat.w encode the source: 2 = block atlas (block-like entities —
        // block items / falling / contained blocks; sampled from the terrain parallel atlases at the same UV,
        // since their geometry textures come from the block atlas), 1 = per-type bindless entity arrays.
        if (pr.mat.z > 1.5) {
            decodeSpec(textureLod(blockSpecAtlas, euvCoord, blockEntityLod), albedo, rough, metal, f0, emission, sss);
        } else if (pr.mat.z > 0.5) {
            decodeSpec(textureLod(entitySpecTex[nonuniformEXT(texSlot)], euvCoord, entityLod), albedo, rough, metal, f0, emission, sss);
        }
        if (pr.mat.w > 1.5) {
            n = perturbNormal(n, ep0, ep1, ep2, euv.uv[e0], euv.uv[e1], euv.uv[e2], vdir,
                    textureLod(blockNormalAtlas, euvCoord, blockEntityLod), ao);
        } else if (pr.mat.w > 0.5) {
            n = perturbNormal(n, ep0, ep1, ep2, euv.uv[e0], euv.uv[e1], euv.uv[e2], vdir,
                    textureLod(entityNormalTex[nonuniformEXT(texSlot)], euvCoord, entityLod), ao);
        }

        payload.albedo = albedo * ao;
        payload.normal = n;
        payload.hitT = gl_HitTEXT;
        // Per-vertex motion vector: interpolate the captured per-vertex displacement with the
        // same indices/barycentrics used for the UV above. Rotation and skeletal/lid animation use a
        // per-vertex buffer; pure whole-object translation is packed into rigidDisp with no buffer.
        if (g.dispAddr != 0ul) {
            Disps dd = Disps(g.dispAddr);
            payload.motionPrev = ebary.x * dd.d[e0].xyz + ebary.y * dd.d[e1].xyz + ebary.z * dd.d[e2].xyz;
        } else {
            payload.motionPrev = g.rigidDisp.xyz;
        }
        payload.f0 = f0;
        payloadSetPacked(MATERIAL_OPAQUE, rough, metal, emission, sss);
        return;
    }

    Section sec = SectionTable(pcAddr.tableAddr).s[gl_InstanceCustomIndexEXT];
    // Any-hit/SBT opt: the section BLAS has one fixed geometry per material bucket
    // (solid / cutout / translucent / water).
    // gl_PrimitiveID restarts at 0 per geometry, so re-add this geometry's triangle base to land in the
    // section's packed prim/index/uv arrays (concatenated in BUCKET order). Water prims keep tint.w == 1,
    // which is converted to the packed material enum below.
    uint pid = gl_PrimitiveID + sec.triBase[gl_GeometryIndexEXT];
    Prim pr = Prims(sec.primAddr).p[pid];
    vec3 n = normalize(pr.normal.xyz);
    vec3 tint = pr.tint.rgb;

    // Lever B: per-triangle corner UVs in primitive order. uvs.uv[3*pid + k] is a contiguous,
    // directly-addressed load (no index buffer, no scattered vertex-UV gather), so it issues as soon as
    // pid is known and its latency overlaps the prim fetch instead of serialising behind an index load.
    // The index buffer still exists for the BLAS build; the shading path just no longer reads it.
    UVs uvs = UVs(sec.uvAddr);
    vec2 uv0 = uvs.uv[3u * pid + 0u];
    vec2 uv1 = uvs.uv[3u * pid + 1u];
    vec2 uv2 = uvs.uv[3u * pid + 2u];
    vec3 bary = vec3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
    vec2 uv = bary.x * uv0 + bary.y * uv1 + bary.z * uv2;
    vec3 tp0 = gl_HitTriangleVertexPositionsEXT[0];
    vec3 tp1 = gl_HitTriangleVertexPositionsEXT[1];
    vec3 tp2 = gl_HitTriangleVertexPositionsEXT[2];
    float blockLod = rayConeTextureLod(vec2(textureSize(blockAtlas, 0)), tp0, tp1, tp2, uv0, uv1, uv2);

    // Orient the GEOMETRIC normal toward the viewer FIRST (double-sided geometry), so the normal-map TBN
    // is built in the correct hemisphere. Doing this before the map — rather than flipping the perturbed
    // normal afterward — is what avoids the grazing-angle black spots: a post-flip could invert a tipped
    // mapped normal through the surface and light the back face.
    vec3 vdir = -gl_WorldRayDirectionEXT; // toward the viewer
    if (dot(n, vdir) < 0.0) {
        n = -n;
    }

    // LabPBR normal map (_n), gated by mat.w — shared decode (TBN from position-fetch + UVs).
    float ao = 1.0;
    if (pr.mat.w > 0.5) {
        n = perturbNormal(n, tp0, tp1, tp2, uv0, uv1, uv2, vdir,
                textureLod(blockNormalAtlas, uv, blockLod), ao);
    }

    // Stained glass / ice (tint.w == 2, flagged at extraction): a thin colored filter resolved in raygen
    // (material 3). albedo carries the transmission tint = texel rgb mixed toward white by (1 − opacity), so
    // a more opaque texel tints transmitted light more strongly.
    if (pr.tint.w > 1.5) {
        vec4 gtex = textureLod(blockAtlas, uv, blockLod);
        // Translucent blocks (glass, ice, …) are breakable too — apply the same overlay here, reusing
        // gtex (already sampled above) rather than re-fetching blockAtlas.
        payload.albedo = applyBreaking(mix(vec3(1.0), gtex.rgb * tint * ao, gtex.a),
                gl_WorldRayOriginEXT, gl_WorldRayDirectionEXT, gl_HitTEXT, n);
        payload.normal = n;
        payload.hitT = gl_HitTEXT;
        payload.motionPrev = vec3(0.0);
        payload.f0 = vec3(0.04);
        payloadSetPacked(MATERIAL_GLASS, 0.05, 0.0, 0.0, 0.0);
        return;
    }

    // Water (tint.w == 1) carries the pure biome water tint (no grey water-texture multiply): raygen
    // shades the surface as a clear dielectric and only needs the tint to derive the per-channel
    // Beer–Lambert absorption. Opaque terrain uses textured albedo.
    payload.albedo = (pr.tint.w > 0.5) ? tint : textureLod(blockAtlas, uv, blockLod).rgb * tint * ao;
    payload.normal = n;
    payload.hitT = gl_HitTEXT;
    payload.motionPrev = vec3(0.0); // static terrain: camera-only motion vector
    uint material = pr.tint.w > 0.5 ? MATERIAL_WATER : MATERIAL_OPAQUE;

    // Block-breaking overlay: opaque/cutout terrain (water skips — fluids aren't breakable, so
    // breakCount==0 short-circuits it anyway; translucent glass/ice is handled at its own early-return
    // above, reusing the gtex it already sampled instead of re-fetching blockAtlas here).
    if (pr.tint.w < 0.5) {
        payload.albedo = applyBreaking(payload.albedo, gl_WorldRayOriginEXT, gl_WorldRayDirectionEXT, gl_HitTEXT, n);
    }

    // Heuristic defaults, overridden per-texel by LabPBR _s when present (flagged in mat.z).
    float rough = pr.mat.x;
    float metal = pr.mat.y;
    vec3 f0 = mix(vec3(0.04), payload.albedo, metal);
    // normal.w packs the 0..1 block-light level plus a +2 offset flag for non-SOLID (cutout / translucent)
    // render layers, set at extraction so the hit can opt SOLID terrain out of SSS below.
    float ew = pr.normal.w;
    bool nonSolid = ew >= 1.5;
    float emission = nonSolid ? ew - 2.0 : ew; // heuristic emission source (block light level)
    // LabPBR _s, gated by mat.z — shared decode. When an _s map is authored we trust ITS emission and
    // REPLACE the block-light heuristic, so emissive texels come from the pack, not the light level.
    // Blue channel: porosity 0-64 is ignored; SSS 65-255 decodes into sss (0 if no _s map or porosity range).
    float sss = 0.0;
    if (pr.mat.z > 0.5) {
        decodeSpec(textureLod(blockSpecAtlas, uv, blockLod), payload.albedo, rough, metal, f0, emission, sss);
        if (!nonSolid) sss = 0.0; // SSS only on non-SOLID terrain (leaves/foliage); SOLID blocks opt out
    }
    payload.f0 = f0;
    payloadSetPacked(material, rough, metal, emission, sss);
}
