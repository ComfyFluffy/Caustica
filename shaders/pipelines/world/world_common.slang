// Shared definitions for all ray-tracing stages. The generateShaderRecords task generates typed Java
// records and serializers from Slang reflection.
module world_common;

// Read-only device pointer with explicit std430 layout. The layout parameter is LOAD-BEARING: a bare
// Ptr<T> gets Slang's "natural" layout (no vec4 16-byte alignment → e.g. WorldPush.sunDir lands at 200
// instead of the Java writer's 208, silently reading garbage) and additionally requires the
// scalarBlockLayout device feature. Keep std430 unless RtDeviceBringup explicitly enables that feature;
// this alias is the single layout switch and generated Java serialization stays in lock-step.
public typealias ConstPtr<T> = Ptr<T, Access.Read, AddressSpace.Device, Std430DataLayout>;
public typealias DevicePtr<T> = Ptr<T, Access.ReadWrite, AddressSpace.Device, Std430DataLayout>;

// Push constant block. The hot inline lanes avoid dereferencing WorldPush for values that are read in
// hit shaders or used for raygen control flow — every 64-bit device address lives here for exactly that
// reason, rather than behind the worldPushAddr indirection. tableAddr/entityTableAddr/materialTableAddr
// are read in world.rahit/world.rchit, which never load WorldPush at all; the light-buffer addresses are
// only read in world.rgen, which loads WorldPush anyway, but keeping every Addr in one place means there
// is exactly one struct to check when adding a new device buffer, instead of two.
public struct WorldPushConstants {
    public uint64_t worldPushAddr;
    public uint64_t tableAddr;
    public uint64_t entityTableAddr;
    public uint64_t materialTableAddr;
    public uint64_t lightBufAddr;        // RIS emitter-NEE global light buffer (0 = none published)
    public uint64_t lightAliasAddr;      // power-weighted O(1) alias table (0 = uniform fallback)
    public uint64_t lightLocalAliasAddr; // power aliases relative to each section's range
    public uint64_t lightGridCellAddr;   // dense light grid cell headers (0 = global proposals only)
    public uint64_t lightGridSpanAddr;   // packed weighted section spans referenced by cell headers
    public uint64_t pathQueueAddr;       // packed primary -> indirect continuation records
    public uint frameIndex;
};

// Minimal push block for the compute passes that only need to dereference the frame's WorldPush: the sky
// LUT bakes read sun/moon/look state from the same BDA slot the trace does, so there is exactly one
// per-frame source of sky state and no way for the LUT and the frame it shades to disagree.
public struct PushAddr {
    public uint64_t worldPushAddr;
};

// Block-breaking overlay entry: xyz = breaking block position (rebased, same space as gl_WorldRay*),
// w = bindless entityAlbedoTex slot of the destroy-stage texture.
public struct BreakEntry {
    public int4 posSlot;
};

// Per-frame world data, read through a 64-bit device address. WorldPushData.java is generated from this
// reflected type, including its serializer, byte size, vector/matrix types, and fixed-array capacities.
public struct WorldPush {
    public float4x4 invViewProj;
    public float3   camOffset;
    public uint     frameIndex;
    public float4x4 prevViewProj;
    public float3   camDelta;
    public uint     spp;
    public float2   jitter;
    public uint     flags;            // bit0 submerged, bit4 waves
    public uint     maxBounces;
    // ---- Sky state. Only what the CPU alone can know: Minecraft's four eased celestial angles (the
    // 26.2 timeline drives them through a cubic-bezier ease and a datapack may replace the track, so
    // they are read from the attribute probe rather than re-derived from the tick), its star brightness,
    // the moon phase, and the look package's immutable constants. Every direction, colour, level and
    // transmittance is derived in sky.slang from these — no atmosphere maths runs on the CPU any more.
    public float4   celestial;        // x sun angle (rad), y moon angle, z star angle, w MC star brightness 0..1
    // x sun illuminance (lux, top of atmosphere), y full-moon illuminance (lux), z night airglow
    // (cd/m²), w star luminance anchor (cd/m²).
    public float4   skyLook0;
    // x noon south tilt (rad), y sun NEE half-angle (rad), z moon NEE half-angle (rad),
    // w moon-phase fixed light fraction.
    public float4   skyLook1;
    // x drawn sun half-angle (rad), y drawn moon half-angle (rad), z viewer altitude (km),
    // w moon phase index (0 full .. 4 new).
    public float4   skyLook2;
    public float4   skyLook3;         // x ground albedo, yzw reserved
    public float4   sunUv;            // vanilla sun sprite atlas rect (u0,v0,u1,v1)
    public float4   moonUv;           // vanilla current-moon-phase sprite atlas rect
    public float4   waterParams;      // xyz linear ACEScg camera-biome water tint, w wave time (s)
    public float4   waterAnchor;      // xy world-stable wave-domain anchor, z previous wave time
    public float4x4 curViewProj;      // forward camera-relative view-projection
    public uint     breakCount;
    public BreakEntry breaking[8];    // Java capacity and breakCount serialization are generated from this
    public float4   lightRebase;      // xyz hierarchy-to-current rebase, w inverse total light power
    public float4   lightGridOrigin;     // xyz minimum cell origin in rebased blocks, w cell size
    public int4     lightGridDims;       // xyz dense grid dimensions, w reserved
    public uint     lightCount;       // packed Light records in pc.lightBufAddr
    public uint     risCandidates;    // RIS candidate count M per diffuse vertex (0 = emitter NEE off)
    // Pre-exposure scalar applied to radiance at the outImage write
    // ONLY, so all light transport above stays in absolute scene units. Keeps stored fp16 values
    // near mid-grey at any scene brightness; the display pass divides it back out, so the two cancel
    // exactly and this cannot change the image. 1.0 disables it.
    public float    preExposure;
    // ---- Height fog. x extinction per block at the reference height (0 disables), y 1/scale-height,
    // z reference world Y in REBASED coordinates (Java subtracts the terrain origin, so this stays a
    // small number as the player moves), w single-scattering albedo. Integrated analytically in
    // fog.slang; the in-scatter colour comes from the sky view LUT, so nothing about the fog's colour
    // lives here.
    public float4   fog;
    // ---- Cloud deck. cloud0: x coverage 0..1 (0 disables), y extinction per block at full density,
    // z deck base as REBASED world Y, w deck thickness in blocks. cloud1: xy wind domain offset in
    // blocks (advanced on the CPU so it is frame-rate independent and world-stable across a rebase),
    // z detail erosion 0..1, w ambient multiple-scattering fraction.
    public float4   cloud0;
    public float4   cloud1;
    // cloud2: x ground-shadow strength (0 disables shadowing without disabling the deck), y the
    // multiple-scattering floor the shadow cannot darken past, z rain wetness 0..1,
    // w cloud feature size in blocks (one noise period).
    public float4   cloud2;
    // ---- Parallax occlusion mapping. x depth in sprite-widths (0 disables it entirely), y step-count
    // scale, z ray-cone LOD at which it has faded out, w unused.
    public float4   pom;
    // ---- Dimension / biome environment colours, read from 26.2's EnvironmentAttributes system.
    // dimSky: rgb = SKY_COLOR (linear BT.709), w = skybox mode (0 overworld, 1 end, 2 none/nether).
    // dimFog: rgb = FOG_COLOR (linear BT.709), w = how strongly it tints scattering, 0 = not at all.
    public float4   dimSky;
    public float4   dimFog;
    // dimCloud: rgb = vanilla CLOUD_COLOR (linear BT.709), w = tint strength (0 = leave the deck lit
    // purely by the atmosphere).
    public float4   dimCloud;
    // cloud3: x cloud march quality scale (1 = reference step counts), yzw unused.
    public float4   cloud3;
    // fog2: x = debug mode (1 = show raw fog in-scatter as the final colour), yzw unused.
    public float4   fog2;
    // ---- ReSTIR. x temporal reuse strength (0 disables it and both image accesses), y frame parity
    // selecting which reservoir image is history this frame, z spatial neighbour taps per pixel
    // (0 = temporal only), w spatial tap radius in render pixels.
    public float4   restir;
};

// Skybox modes, mirroring vanilla's DimensionType.Skybox enum.
public static const uint SKYBOX_OVERWORLD = 0u;
public static const uint SKYBOX_END = 1u;
public static const uint SKYBOX_NONE = 2u;

// 32-byte hot area-light record. Linear ACEScg radiance uses packed R11G11B10; the
// grid-relative owner section uses three unsigned 10-bit coordinates; the geometric normal is
// reconstructed from the half axes.
//
// 32 divides the 64-byte cache line, so a record never straddles one. RIS fetches random records M times
// per shading vertex, making this alignment important. float3 forces 16-byte struct alignment and 32 is
// a multiple of it, so this lands with no tail padding.
//
// The rectangle spans 2U x 2V, so its area is derived as 4*|U x V|. lightGeometricNormal already
// needs that cross product, allowing the compiler to share the length with normalization.
public struct Light {
    public float3 pos;      // rebased world centre, f32 (the RIS target divides by distance to it)
    public uint   le;       // bitcast R11G11B10 radiance
    public uint   halfUxy;  // half2(U.x, U.y)
    public uint   halfUzVx; // half2(U.z, V.x)
    public uint   halfVyz;  // half2(V.y, V.z)
    public uint   section;  // 10:10:10 grid-relative section coord + bit 30 normal-flip
};

// Eight-byte Vose alias-table column. PDFs are reconstructed from the selected Light.
public struct LightAlias {
    public uint  aliasIndex;
    public float accept;
};

// Dense light grid cell entry. Empty coordinates have spanCount=0; populated section cells point into
// an immutable list of weighted section light ranges built and published alongside this grid.
public struct LightGridCell {
    public uint spanOffset;
    public uint spanCount;
    public float invWeightSum;
};

// One 16-byte Vose alias column for a cell's section distribution. The two light counts are uint16s.
public struct LightGridSpan {
    public uint firstLight;
    public uint aliasFirstLight;
    public uint packedLightCounts; // low16 self, high16 alias
    public float accept;
};

// TerrainPrim.flags bit 0: this emissive quad is in the light buffer (RtLightCollector membership).
public static const uint TERRAIN_PRIM_IN_LIGHT_BUFFER = 1u;

// Largest finite half. Producers of fp16 HDR storage clamp to this instead of letting the conversion
// round up to +inf and propagate as NaN — world.rgen's rgba16f outImage store most of all, which is the
// one place the whole path integral's absolute radiance meets a half.
public static const float HALF_MAX = 65504.0;

// Radiance-ray payload (location 0). Member order and types are a cross-stage ABI.
//
// Three-component quantities are packed into half pairs rather than declared as float3: they cost 6 bytes
// instead of 12, and payload storage is reserved per trace call site, so every byte here is paid twice per
// radiance trace (once for TraceRay, once for Invoke) and is preserved across the SER reorder. hitT stays
// f32 — it reaches 10000 blocks and the hit position is reconstructed from it, where half's ~4-block
// spacing at that magnitude would be visible.
public struct Payload {
    // surface0..2 are a hand-packed UNION of the two things a radiance ray can come back with. Reach them
    // only through unpackAlbedo/packAlbedo, unpackNormal/packNormal, unpackSky/packSky — the raw words
    // have no meaning without knowing which case you are in.
    //
    //   hit  — half3 albedo + half3 normal
    //   miss — float3 sky radiance, full fp32
    //
    // The miss case is the entire reason these are hand-packed. Sky is in absolute photometric units and
    // the sun disc alone is ~3.6e5 cd/m², well past half's 65504 ceiling — a
    // half3 could not carry it without first normalising by the frame's exposure, which made a storage
    // format's limitation into a dependency on the exposure controller. A hit has no sky and a miss has
    // neither albedo nor normal, so reinterpreting the same twelve bytes buys the range for free and keeps
    // the payload exactly the size it was.
    //
    // The albedo lane doubles as the shadow ray's accumulated transmittance (see makeShadowPayload).
    public uint   surface0;    // hit: half2(albedo.r, albedo.g) | miss: asuint(sky.r)
    public uint   surface1;    // hit: half2(albedo.b, normal.x) | miss: asuint(sky.g)
    public uint   surface2;    // hit: half2(normal.y, normal.z) | miss: asuint(sky.b)
    public float  hitT;        // >= 0 on hit, < 0 on miss.
    public half3  motionPrev;  // per-vertex world displacement since last frame.
    public half3  f0;          // specular F0.
    public uint   flags;       // bits 0..1 material, bit 2 celestial, bit 3 water-entering, bits 4..6 emission source.
    public uint   roughMetal;  // packHalf2x16(roughness, metalness)
    public uint   emissionSss; // packHalf2x16(emission, sss)
    public uint   iorTransmission; // packHalf2x16(IOR, transmission factor)
    public uint   rayCone;     // packHalf2x16(width, spread)
};

public static const uint PAYLOAD_SHOW_CELESTIAL = 4u;
// Set by world.rchit on a terrain hit whose prim carries TERRAIN_PRIM_IN_LIGHT_BUFFER: this emitter is
// RIS-sampled, so raygen gates its direct-hit emission on diffuse continuation rays (no double count).
public static const uint PAYLOAD_EMITTER_IN_LIST = 128u;
// Set by world.rchit on any dielectric hit (water or glass/ice): true when the incoming ray travels
// against the prim's outward face normal (entering the volume), false when it exits. Derived from face
// orientation rather than toggled, so a stray or missing face cannot corrupt the medium for the rest of
// the path — each crossing re-derives which side it is on. Bit 2 (PAYLOAD_SHOW_CELESTIAL) is only
// meaningful on a miss and this bit only on a hit, so they never need to be read together, but they get
// distinct bits anyway to keep the two concerns unambiguous.
public static const uint PAYLOAD_DIELECTRIC_ENTERING = 8u;
public static const uint PAYLOAD_EMISSION_SOURCE_SHIFT = 4u;
public static const uint PAYLOAD_EMISSION_SOURCE_MASK = 7u;
public static const uint EMISSION_SOURCE_NONE = 0u;
public static const uint EMISSION_SOURCE_UNIFORM = 1u;
public static const uint EMISSION_SOURCE_HEURISTIC = 2u;
public static const uint EMISSION_SOURCE_LAB_PBR = 3u;

public static const uint PAYLOAD_MATERIAL_MASK = 3u;
public static const uint MATERIAL_OPAQUE = 0u;
// Both dielectrics are volumes: they refract and push a participating medium. They stay distinct ids
// because water owns behaviour nothing else has — an animated wave surface, the caustic term, biome-tint
// absorption calibrated per block of depth, and a chit path fed by the fluid mesher rather than the
// (inset) translucent terrain layer. MATERIAL_DIELECTRIC is everything else transparent: glass, ice, and
// any solid transparent block a pack defines.
public static const uint MATERIAL_WATER = 1u;
public static const uint MATERIAL_PARTICLE = 2u;
public static const uint MATERIAL_DIELECTRIC = 3u;

// Entity per-triangle record (48 B). The final lane mirrors TerrainPrim's integer material metadata.
public struct Prim {
    public float4 normal; // xyz = geometric normal, w = per-primitive emission strength
    public float4 tint;
    public uint materialId;
    public uint flags;
    public uint aux0;
    public uint aux1;
};

// Terrain per-triangle record (48 B). The final 16-byte lane contains integer geometry-local metadata;
// shared surface parameters live once in MaterialHeader instead of being copied into every triangle.
public struct TerrainPrim {
    public float4 normal;
    public float4 tint;
    public uint materialId;
    public uint flags;
    public uint aux0;
    public uint aux1;
};

// Compiled material record (80 B). Source-format data has already been decoded into canonical pages;
// texturePage indexes the dedicated canonical page arrays. materialUv maps sprite-local UV into the packed
// page, while albedoUv maps the vanilla atlas rectangle back to sprite-local UV.
public struct MaterialHeader {
    public uint model;
    public uint features;        // low feature bits; bits 24..31 = maximum valid material-page LOD
    public uint texturePage;
    public uint extensionOffset; // zero when no optional extension record is present
    public float4 materialUv; // packed-page uvMin.xy + uvExtent.xy
    public float4 albedoUv;   // vanilla atlas uvMin.xy + inverse uvExtent.xy
    public float4 params;  // roughness, metalness, IOR, transmission
    public float4 average; // whole-sprite linear-input average RGBA for translucent shadow filtering
};

public static const uint MATERIAL_FEATURE_SPEC = 1u;
public static const uint MATERIAL_FEATURE_NORMAL = 2u;
public static const uint MATERIAL_FEATURE_HEURISTIC_EMISSION = 4u;
public static const uint MATERIAL_FEATURE_STOCHASTIC_ALPHA = 16u;
// Final HDR emitting-surface luminance (look-package baseline or absolute JSON cd/m² override), baked in Java
// at material-compile time (RtMaterialRegistry) and packed here as a 16-bit fraction of the max — every
// emissive material carries a value (0 for non-emissive), not just resource-pack-overridden ones, so the
// full features word's spare 16 bits (8..23) hold the encoded value.
public static const uint MATERIAL_EMISSION_STRENGTH_SHIFT = 8u;
public static const uint MATERIAL_EMISSION_STRENGTH_MASK = 65535u;
// Mirrors RtMaterialRegistry.MAX_EMISSION_STRENGTH — HALF_MAX, the ceiling emission can survive through
// Payload.emissionSss (half2) and Light.le (R11G11B10). Emission is measured in cd/m².
public static const float MATERIAL_MAX_EMISSION_STRENGTH = 65504.0;
public static const uint MATERIAL_MAX_LOD_SHIFT = 24u;
public static const uint MATERIAL_MAX_LOD_MASK = 255u;

// Per-section geometry record: packed buffer addresses + per-bucket triangle offsets
// (gl_PrimitiveID restarts at 0 per geometry, so hit shaders re-add triBase[geometryIndex]).
public struct Section {
    public uint64_t primAddr;
    public uint64_t uvAddr;
    public uint triBase[4]; // fixed bucket order: solid/cutout/translucent/water
};

// Entity geometry record: packed buffer addresses, motion, and per-geometry triangle bases. Entity BLAS
// geometry order is opaque/any-hit; PrimitiveIndex restarts at zero for each geometry.
public struct EntityGeom {
    public uint64_t primAddr;
    public uint64_t idxAddr;
    public uint64_t uvAddr;
    public uint64_t dispAddr;
    public float4 rigidDisp;
    public uint triBase[2];
    public uint2 reserved;
};

// TLAS instance-custom-index encoding + terrain geometry buckets (shared by rahit/rchit).
public static const uint ENTITY_BIT = 0x800000u;
public static const uint PARTICLE_BIT = 0x400000u; // particles share the entity cutout path
public static const uint IDX_MASK = 0x3FFFFFu;     // low 22 bits = geom-table index
public static const uint BUCKET_CUTOUT = 1u;
public static const uint BUCKET_TRANSLUCENT = 2u;
public static const uint BUCKET_WATER = 3u;

// packHalf2x16 equivalent (Slang core module has no GLSL pack builtin; f32tof16 returns the half
// bit-pattern in the low 16 bits).
public uint packHalf2(float2 v) {
    return (f32tof16(v.y) << 16) | (f32tof16(v.x) & 0xFFFFu);
}

public float2 unpackHalf2(uint p) {
    return float2(f16tof32(p & 0xFFFFu), f16tof32(p >> 16));
}

// ---- Payload.surface0..2 accessors. Two views of the same twelve bytes; see the struct for why.
//
// The hit view straddles surface1 (albedo.b in its low half, normal.x in its high half), so the two
// setters are read-modify-write on that word. That is a couple of ALU ops on a value already in
// registers, and it buys a payload that did not have to grow. Each setter touches only its own halves,
// so writing one before the other is initialised is safe — nothing here does arithmetic on the half it
// is preserving.
public float3 unpackAlbedo(Payload p) {
    return float3(unpackHalf2(p.surface0), unpackHalf2(p.surface1).x);
}

public void packAlbedo(inout Payload p, float3 albedo) {
    p.surface0 = packHalf2(albedo.xy);
    p.surface1 = (p.surface1 & 0xFFFF0000u) | (f32tof16(albedo.z) & 0xFFFFu);
}

public float3 unpackNormal(Payload p) {
    return float3(unpackHalf2(p.surface1).y, unpackHalf2(p.surface2));
}

public void packNormal(inout Payload p, float3 normal) {
    p.surface1 = (p.surface1 & 0x0000FFFFu) | (f32tof16(normal.x) << 16);
    p.surface2 = packHalf2(normal.yz);
}

// Miss view: full fp32, so absolute photometric sky needs no pre-exposure round trip through the payload
// and no clamp to keep it finite. The fp16 ceiling still applies at the outImage store in world.rgen,
// where the finite-half clamp guards every radiance source.
public float3 unpackSky(Payload p) {
    return float3(asfloat(p.surface0), asfloat(p.surface1), asfloat(p.surface2));
}

public void packSky(inout Payload p, float3 sky) {
    p.surface0 = asuint(sky.x);
    p.surface1 = asuint(sky.y);
    p.surface2 = asuint(sky.z);
}

// The ray tracer's transport contract is scene-linear ACEScg (AP1/D60). Minecraft assets and
// captured vertex colours are authored as sRGB/BT.709 (D65), so colour inputs cross this OCIO-derived
// chromatic-adaptation + primary-conversion seam exactly once before they enter any BRDF, throughput,
// medium, light, guide, or radiance calculation.
public static const float3 ACESCG_LUMA = float3(0.27222872, 0.67408177, 0.05368952);
public static const float3 BT709_TO_ACESCG_R = float3(0.61309743, 0.33952314, 0.04737945);
public static const float3 BT709_TO_ACESCG_G = float3(0.07019372, 0.91635388, 0.01345240);
public static const float3 BT709_TO_ACESCG_B = float3(0.02061559, 0.10956977, 0.86981463);

public float3 bt709ToAcesCg(float3 color) {
    return float3(
            dot(color, BT709_TO_ACESCG_R),
            dot(color, BT709_TO_ACESCG_G),
            dot(color, BT709_TO_ACESCG_B));
}

public float3 srgbToLinear(float3 color) {
    float3 lo = color / 12.92;
    float3 hi = pow((color + 0.055) / 1.055, float3(2.4));
    return lerp(lo, hi, step(float3(0.04045), color));
}
