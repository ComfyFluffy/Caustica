#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
#extension GL_EXT_nonuniform_qualifier : require
#extension GL_EXT_ray_tracing_position_fetch : require // P6.2b: gl_HitTriangleVertexPositionsEXT (TBN)

// P2 closest-hit. Geometry is per-section: gl_InstanceCustomIndexEXT indexes a section table (BDA
// array reached from the push constant) holding this section's {prim, index, uv} buffer addresses.
// From there it's the same as before: per-primitive {normal, tint} (gl_PrimitiveID) + per-vertex
// atlas UVs (index buffer -> UV buffer, barycentric-interpolated) -> atlas albedo. Lighting is done
// in raygen (deferred), so this writes albedo/normal/hitT into the payload and does no shading.
struct Prim {
    vec4 normal;
    vec4 tint;
    vec4 mat; // P6.1: {roughness, metalness, 0, 0} heuristic PBR material (RtMaterials)
};
struct Section {
    uint64_t primAddr;
    uint64_t idxAddr;
    uint64_t uvAddr;
    uint triBase[3]; // any-hit opt: per-geometry triangle offset (solid/cutout/water packed in BUCKET order)
    uint waterGeom;  // gl_GeometryIndexEXT of the water geometry (>= geom count if the section has no water)
};
// P5.1b-2 entity geometry record: the entity's {prim, index, uv} buffer addresses (same per-prim
// {normal, tint} + UV layout as a terrain section) plus the address of a per-vertex world-space
// displacement buffer (cur − prev frame; vec4 per vertex, xyz used) for the motion vector. P5.1c-2:
// the displacement is now per-vertex (barycentric-interpolated below) so rotating mobs and animating
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
// P5.1b-2: per-entity geometry records, indexed by the entity instance index (the low bits of
// gl_InstanceCustomIndexEXT). See RtEntities.
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer EntityTable { EntityGeom e[]; };
// P5.1c-2: per-vertex world-space displacement (cur − prev frame), parallel to the vertex/UV buffers
// (vec4 per vertex, xyz used). Barycentric-interpolated at the hit for a per-vertex motion vector.
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Disps { vec4 d[]; };

layout(binding = 2, set = 0) uniform sampler2D blockAtlas;
// P6.2a/b: parallel LabPBR _s (specular) and _n (normal) atlases, stitched to mirror the block atlas
// sprite layout (RtBlockMaterials), sampled at the SAME uv as blockAtlas. Read only when the prim is
// flagged (pr.mat.z for _s, pr.mat.w for _n).
layout(binding = 10, set = 0) uniform sampler2D blockSpecAtlas;
layout(binding = 11, set = 0) uniform sampler2D blockNormalAtlas;
// P5.1b-2b: bindless entity textures — a runtime-sized array indexed per-prim (tint.w) by the entity
// hit path. Slot 0 is a fallback. Entities use per-type texture files, so each RenderType gets a slot.
layout(binding = 0, set = 1) uniform sampler2D entityTex[];
// P6.2c: parallel per-type LabPBR _n / _s for entities, sampled at the SAME bindless slot as the albedo.
layout(binding = 1, set = 1) uniform sampler2D entityNormalTex[];
layout(binding = 2, set = 1) uniform sampler2D entitySpecTex[];

// P6.4: per-frame push data lives in a host-visible BDA buffer (the old inline block hit the 256-byte
// push-constant ceiling); only its 8-byte address is pushed. The hit shaders read just two table
// addresses, so `pc` is a macro that lazily loads the needed field — no whole-struct copy per hit. The
// std430 layout matches the CPU writer (RtComposite) and the raygen WorldPush exactly.
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
    vec4 sunDir; vec4 lightDir; vec4 lightRadiance;
};
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer WorldPushRef { WorldPush v; };
layout(push_constant) uniform PushAddr { uint64_t worldPushAddr; } pcAddr;
#define pc WorldPushRef(pcAddr.worldPushAddr).v

struct Payload {
    vec3 albedo;
    vec3 normal;
    float hitT;
    float emission; // block light level 0..1 (stashed in prim normal.w during extraction)
    vec3 motionPrev; // world displacement since last frame (entity per-object MV); 0 for static terrain
    float material;  // P5.2: 0 = opaque diffuse, 1 = water (smooth dielectric, handled in raygen)
    float roughness; // P6.1: perceptual roughness for the GGX BRDF
    float metalness; // P6.1: 0 = dielectric, 1 = conductor
    vec3 f0;         // P6.2a: specular reflectance at normal incidence (dielectric 0.04 / LabPBR / metal)
    float showCelestial; // sky-disc gate (raygen-set, read by world.rmiss); unused here, kept for layout match
};
layout(location = 0) rayPayloadInEXT Payload payload;
hitAttributeEXT vec2 attribs;

// P5.1b-2 dynamic entities: instances with this custom-index flag bit carry real captured ModelPart
// geometry. Their gl_InstanceCustomIndexEXT (low bits) indexes the entity geometry table, not the
// section table; shading reads per-prim normal + vertex-colour tint (no atlas — entity textures are
// P5.1b-2b) and the per-object MV displacement.
const int ENTITY_BIT = 0x800000;
// Particles: a single combined billboard mesh sharing the entity geometry table + bindless atlas path,
// but shaded as an unlit cutout billboard (see the particle branch below). 0x400000 (bit 22); the low 22
// bits index the geom table (IDX_MASK). Particle instances also carry TLAS mask 0x02 (camera-ray-only).
const int PARTICLE_BIT = 0x400000;
const int IDX_MASK = 0x3FFFFF;

// P6.2a LabPBR predefined metals (green channel 230..237): complex refractive indices N (eta) and K
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

// P6.2b: strength of the LabPBR _n ambient-occlusion (blue) applied to albedo. Kept mild because the
// path tracer already computes its own AO from sky-visibility rays (avoid double-darkening).
const float NORMAL_AO_STRENGTH = 0.5;

// LabPBR _s decode (shared by terrain + entities): red = perceptual smoothness -> roughness; green =
// reflectance (dielectric F0 0..229 / predefined metal 230..237 / generic metal albedo); alpha = emission
// (255 = ignored). `albedo` feeds the generic-metal F0.
void decodeSpec(vec4 s, vec3 albedo, out float rough, out float metal, out vec3 f0, out float emission) {
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

void main() {
    // Particle billboard: same geom-table/UV/bindless-atlas path as entities, but unlit — albedo =
    // atlas texel * per-particle colour (tint.rgb), tagged material 2 so the raygen shows it and stops
    // (no lighting/GI/emission). Reached only by the primary ray (instance mask 0x02). Cutout: rahit.
    if ((gl_InstanceCustomIndexEXT & PARTICLE_BIT) != 0) {
        int idx = gl_InstanceCustomIndexEXT & IDX_MASK;
        EntityGeom g = EntityTable(pc.entityTableAddr).e[idx];
        Prim pr = Prims(g.primAddr).p[gl_PrimitiveID];
        Indices ib = Indices(g.idxAddr);
        UVs uvb = UVs(g.uvAddr);
        uint p0 = ib.i[3u * gl_PrimitiveID + 0u];
        uint p1 = ib.i[3u * gl_PrimitiveID + 1u];
        uint p2 = ib.i[3u * gl_PrimitiveID + 2u];
        vec3 pbary = vec3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        vec2 puv = pbary.x * uvb.uv[p0] + pbary.y * uvb.uv[p1] + pbary.z * uvb.uv[p2];
        int pslot = int(pr.tint.w + 0.5);
        vec3 pn = normalize(pr.normal.xyz);
        if (dot(pn, gl_WorldRayDirectionEXT) > 0.0) {
            pn = -pn;
        }
        payload.albedo = texture(entityTex[nonuniformEXT(pslot)], puv).rgb * pr.tint.rgb;
        payload.normal = pn;
        payload.hitT = gl_HitTEXT;
        payload.emission = 0.0;
        // Per-particle motion vector: interpolate the captured per-vertex displacement (uniform across the
        // billboard's verts) with the same indices/barycentrics as the UV. dispAddr == 0 falls back to
        // rigidDisp, which particles write as zero unless a future path chooses otherwise.
        if (g.dispAddr != 0ul) {
            Disps pd = Disps(g.dispAddr);
            payload.motionPrev = pbary.x * pd.d[p0].xyz + pbary.y * pd.d[p1].xyz + pbary.z * pd.d[p2].xyz;
        } else {
            payload.motionPrev = g.rigidDisp.xyz;
        }
        payload.material = 2.0;          // particle marker → raygen shows-and-terminates
        payload.roughness = 1.0;
        payload.metalness = 0.0;
        payload.f0 = vec3(0.0);
        return;
    }
    if ((gl_InstanceCustomIndexEXT & ENTITY_BIT) != 0) {
        int eidx = gl_InstanceCustomIndexEXT & ~ENTITY_BIT;
        EntityGeom g = EntityTable(pc.entityTableAddr).e[eidx];
        Prim pr = Prims(g.primAddr).p[gl_PrimitiveID];
        // Orient the geometric normal toward the viewer first (so the _n TBN is built in the right
        // hemisphere — same fix as terrain).
        vec3 vdir = -gl_WorldRayDirectionEXT;
        vec3 n = normalize(pr.normal.xyz);
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

        vec3 albedo = texture(entityTex[nonuniformEXT(texSlot)], euvCoord).rgb * pr.tint.rgb;
        float rough = pr.mat.x;          // P6.1 heuristic defaults
        float metal = pr.mat.y;
        vec3 f0 = mix(vec3(0.04), albedo, metal);
        float emission = 0.0;
        float ao = 1.0;
        // LabPBR _s / _n for entities. mat.z/mat.w encode the source: 2 = block atlas (block-like entities —
        // block items / falling / contained blocks; sampled from the terrain parallel atlases at the same UV,
        // since their geometry textures from the block atlas), 1 = per-type bindless entity arrays (P6.2c mobs).
        if (pr.mat.z > 1.5) {
            decodeSpec(textureLod(blockSpecAtlas, euvCoord, 0.0), albedo, rough, metal, f0, emission);
        } else if (pr.mat.z > 0.5) {
            decodeSpec(texture(entitySpecTex[nonuniformEXT(texSlot)], euvCoord), albedo, rough, metal, f0, emission);
        }
        if (pr.mat.w > 1.5) {
            n = perturbNormal(n, gl_HitTriangleVertexPositionsEXT[0], gl_HitTriangleVertexPositionsEXT[1],
                    gl_HitTriangleVertexPositionsEXT[2], euv.uv[e0], euv.uv[e1], euv.uv[e2], vdir,
                    textureLod(blockNormalAtlas, euvCoord, 0.0), ao);
        } else if (pr.mat.w > 0.5) {
            n = perturbNormal(n, gl_HitTriangleVertexPositionsEXT[0], gl_HitTriangleVertexPositionsEXT[1],
                    gl_HitTriangleVertexPositionsEXT[2], euv.uv[e0], euv.uv[e1], euv.uv[e2], vdir,
                    texture(entityNormalTex[nonuniformEXT(texSlot)], euvCoord), ao);
        }

        payload.albedo = albedo * ao;
        payload.normal = n;
        payload.hitT = gl_HitTEXT;
        payload.emission = emission;
        // P5.1c-2: per-vertex motion vector — interpolate the captured per-vertex displacement with the
        // same indices/barycentrics used for the UV above. Rotation and skeletal/lid animation use a
        // per-vertex buffer; pure whole-object translation is packed into rigidDisp with no buffer.
        if (g.dispAddr != 0ul) {
            Disps dd = Disps(g.dispAddr);
            payload.motionPrev = ebary.x * dd.d[e0].xyz + ebary.y * dd.d[e1].xyz + ebary.z * dd.d[e2].xyz;
        } else {
            payload.motionPrev = g.rigidDisp.xyz;
        }
        payload.material = 0.0;          // entities are opaque
        payload.roughness = rough;
        payload.metalness = metal;
        payload.f0 = f0;
        return;
    }

    Section sec = SectionTable(pc.tableAddr).s[gl_InstanceCustomIndexEXT];
    // Any-hit opt: the section BLAS has one geometry per material bucket (solid / cutout / water).
    // gl_PrimitiveID restarts at 0 per geometry, so re-add this geometry's triangle base to land in the
    // section's packed prim/index/uv arrays (concatenated in BUCKET order). Water prims keep tint.w == 1,
    // so payload.material below is set from the prim exactly as before — no special-casing needed here.
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

    // Orient the GEOMETRIC normal toward the viewer FIRST (double-sided geometry), so the normal-map TBN
    // is built in the correct hemisphere. Doing this before the map — rather than flipping the perturbed
    // normal afterward — is what avoids the grazing-angle black spots: a post-flip could invert a tipped
    // mapped normal through the surface and light the back face.
    vec3 vdir = -gl_WorldRayDirectionEXT; // toward the viewer
    if (dot(n, vdir) < 0.0) {
        n = -n;
    }

    // P6.2b LabPBR normal map (_n), gated by mat.w — shared decode (TBN from position-fetch + UVs).
    float ao = 1.0;
    if (pr.mat.w > 0.5) {
        n = perturbNormal(n, gl_HitTriangleVertexPositionsEXT[0], gl_HitTriangleVertexPositionsEXT[1],
                gl_HitTriangleVertexPositionsEXT[2], uv0, uv1, uv2, vdir,
                textureLod(blockNormalAtlas, uv, 0.0), ao);
    }

    payload.albedo = textureLod(blockAtlas, uv, 0.0).rgb * tint * ao;
    payload.normal = n;
    payload.hitT = gl_HitTEXT;
    payload.motionPrev = vec3(0.0); // static terrain: camera-only motion vector
    payload.material = pr.tint.w;   // P5.2: 1 = water dielectric, 0 = opaque (set by extraction)

    // P6.1 heuristic defaults, overridden per-texel by LabPBR _s when present (P6.2a, flagged in mat.z).
    float rough = pr.mat.x;
    float metal = pr.mat.y;
    vec3 f0 = mix(vec3(0.04), payload.albedo, metal);
    float emission = pr.normal.w;   // 0..1 block light level (extraction): the heuristic emission source
    // P6.2a LabPBR _s, gated by mat.z — shared decode. When an _s map is authored we trust ITS emission and
    // REPLACE the block-light heuristic, so emissive texels come from the pack, not the light level.
    // (blue channel: porosity 0..64 / SSS 65..255 — deferred, not consumed yet.)
    if (pr.mat.z > 0.5) {
        decodeSpec(textureLod(blockSpecAtlas, uv, 0.0), payload.albedo, rough, metal, f0, emission);
    }
    payload.roughness = rough;
    payload.metalness = metal;
    payload.f0 = f0;
    payload.emission = emission;
}
