# Material system refactor

## Decision

Replace the current per-primitive `roughness/metalness/hasS/hasN` representation with a compiled,
versioned material registry built when resources reload.

The registry should:

- ingest LabPBR when present;
- synthesize a complete material when it is absent;
- decode source formats into one internal, physical representation before upload;
- build semantic mip chains (normal, roughness, emission, and alpha need different filters);
- expose one immutable CPU snapshot to terrain/entity extraction;
- expose one GPU material table and a small bindless array of texture **pages** to shaders; and
- assign every primitive a `materialId`, removing resource lookup and `hasS`/`hasN` patching from terrain
  publication.

Do not make every block sprite independently bindless. Use bindless descriptors for material atlas pages and
standalone entity textures. A page index plus a rectangle in the material table scales better, preserves
coherent sampling, and permits gutters and semantic mip generation.

Implementation status: terrain, full entity textures, block-entity atlases, and item-atlas geometry now share
the compiled `MaterialHeader` table and canonical semantic-mip pages. Entity albedo remains bindless, while its
physical channels are pack-compiled. Atlas sprites append only a previously unused header containing their
stitched UV rectangle; runtime-only textures such as downloaded player skins use the neutral entity material.

## Problems in the current path

The existing implementation works, but its data ownership is inverted:

1. `RtTerrainMesher` writes heuristic roughness/metalness, two zero placeholders, and one
   `TextureAtlasSprite` reference per triangle.
2. `RtSectionBuilder.resolveMaterials` runs after asynchronous buffer preparation and patches two mapped
   floats per triangle on the render thread.
3. `world.rchit.slang` interprets the two floats as LabPBR presence/source flags and decodes raw LabPBR at
   every hit.
4. The same `Prim.mat` lane means four different things for ordinary terrain, translucent terrain, entities,
   and fluids. `tint.w` and `normal.w` are similarly overloaded.

Consequences:

- Material ingestion remains coupled to terrain publication even though terrain is otherwise asynchronous.
- `hasS`/`hasN` state is repeated in the registry, entity capture, terrain sidecars, primitive records, and
  shader branches.
- The 48-byte primitive record stores material properties per triangle even when thousands of triangles share
  them.
- Resource reload requires all terrain to be re-extracted partly to update material-presence flags.
- The parallel `DynamicTexture` atlases do not explicitly own a semantic mip chain. Sampling them with the
  albedo LOD therefore cannot guarantee a useful normal/specular LOD.
- Directly filtering raw LabPBR is invalid in important channels: the green channel contains categorical
  encodings, normals must be renormalized, emission must preserve energy, and cutout alpha must preserve
  coverage.
- Transmission is a two-bit hit category followed by hard-coded water/glass IOR in raygen, rather than a
  property of a material.
- Future light sampling has no stable material-level emission metadata to consume.

## Architecture

### 1. Resource source format versus internal format

Keep LabPBR as an input adapter, not the engine's runtime material format. Add an optional, versioned Caustica
JSON material description for properties LabPBR cannot express cleanly. Resource-pack authors should not have
to produce a custom binary format.

Format 1 uses resources under `assets/<namespace>/caustica/materials/*.json`. An exact `match.sprite` is
required; `match.block` optionally narrows a rule when sprites are shared. Block+sprite rules take precedence
over sprite-wide rules, and resource identifiers break ties deterministically. Unknown fields are ignored so
new extension data can be added without breaking older clients. Shape:

```json
{
  "format": 1,
  "match": {
    "block": "minecraft:blue_stained_glass",
    "sprite": "minecraft:block/blue_stained_glass"
  },
  "model": "thin_dielectric",
  "base": {
    "roughness": 0.06,
    "metalness": 0.0
  },
  "emission": {
    "strength": 0.0,
    "color_source": "albedo"
  },
  "transmission": {
    "factor": 1.0,
    "ior": 1.52,
    "attenuation_color": [0.35, 0.55, 1.0],
    "attenuation_distance": 8.0,
    "thin_walled": true
  }
}
```

Resolution precedence should be deterministic:

1. user/config override;
2. explicit Caustica material JSON;
3. LabPBR maps;
4. engine heuristic material;
5. neutral fallback.

All five sources compile to the same `MaterialDesc`; shaders never know which source won.

### 2. Immutable material snapshot

Build a `MaterialSnapshot` during resource reload, after baked sprites/models are available and before RT terrain
is allowed to publish against the new resource epoch.

Suggested CPU objects:

```text
MaterialCompiler
  inputs: resource manager, baked atlases/models, block registry, overrides
  output: MaterialSnapshot

MaterialSnapshot
  epoch
  sprite identity/name -> textureTemplateId
  (textureTemplateId, surfaceProfileId) -> materialId
  materialId -> MaterialDesc
  GPU page images + mip data
  emission summaries
```

The snapshot is fully constructed and then atomically published. Worker code performs read-only lookups; no
`ensure`, lazy PNG decode, descriptor update, or registry mutation is permitted from terrain extraction.

A material key should separate texture-derived information from block/state-derived information. One sprite
can be used by emitting and non-emitting blocks, and block states can change emission. A useful key is:

```text
MaterialKey(textureTemplateId, surfaceProfileId, renderClass, emissionClass)
```

The compiler can pre-enumerate combinations from baked block models. Rare unknown combinations use material
ID 0 (the complete neutral fallback) and are counted in diagnostics rather than mutating the snapshot.

### 3. GPU material table

Use a fixed-size header plus an optional extension buffer. This keeps the hot hit path compact while allowing
new lobes without changing every primitive.

Initial 64-byte header (exact packing can be tuned after profiling):

```slang
struct MaterialHeader
{
    uint model;             // opaque, cutout, thin dielectric, volume dielectric, ...
    uint features;          // normal detail, specular color, textured emission, SSS, ...
    uint texturePage;       // descriptor-array page; 0 is the complete fallback page
    uint extensionOffset;   // byte/record offset, 0 when absent

    float4 materialUv;      // material-page uvMin.xy + uvExtent.xy
    float4 albedoUv;        // source-atlas uvMin.xy + inverse extent.xy

    uint packedRoughMetal;  // half2
    uint packedIorTransmit; // half2
    uint packedEmitSss;     // half2
    uint packedAlphaFlags;  // alpha cutoff + compact flags/version
};
```

Do not expose this binary layout as the resource-pack format. It is an engine ABI generated/validated alongside
Slang reflection, like the existing world push records.

Optional extensions can hold absorption RGB/distance, clearcoat, anisotropy, alternate emission color, or a
future BSDF parameter block. Unknown extensions can be skipped by older shader variants.

### 4. Primitive record

Stop storing shared material values in each triangle and stop using floats as enums/booleans. A conservative
first migration keeps the existing 48-byte stride while making it explicit:

```slang
struct Prim
{
    float4 geometricNormal; // xyz; w is a per-instance emission multiplier if needed
    float4 tint;            // rgb; w unused/reserved after entity texture ownership moves to material
    uint materialId;
    uint flags;             // geometry-local facts only: front-face policy, tint mode, etc.
    uint packedAux0;        // e.g. state emission and alpha/cutout data
    uint packedAux1;
};
```

This is intentionally a semantic cleanup before a compression exercise. After validation, normal/tint can be
packed and the record can likely fall to 32 bytes. Keeping 48 bytes initially makes behavior comparison and
rollback much easier.

Geometry buckets should continue to express intersection behavior (`opaque`, `cutout`, `transmissive`,
`water/volume`) because that is useful to the BLAS/SBT. They should not define the BSDF or carry material
parameters.

### 5. Texture pages, not per-sprite bindless textures

Create engine-owned material pages with independent packing from the vanilla albedo atlas. Each material header
contains both the vanilla albedo rectangle and the material-page rectangle. Closest-hit converts atlas UV to
sprite-local UV, then to material-page UV:

```text
localUv    = (atlasUv - albedoUvMin) * albedoUvInvExtent
materialUv = materialUvMin + localUv * materialUvExtent
```

This permits:

- gutters at every mip level;
- LOD clamping to the sprite's valid mip count;
- arbitrary resource-pack resolutions;
- multiple fixed-size pages instead of one maximum-size atlas; and
- stable descriptors when a page is updated.

Recommended initial bundles:

```text
surface0 RGBA8_UNORM : roughness, metalness, emission mask, SSS
normalAo RGBA8_UNORM : encoded normal X/Y, AO, height/reserved
surface1 RGBA8_UNORM : optional F0 RGB, transmission mask
```

`surface1` is only needed when F0 cannot be derived from `(0.04, albedo, metalness)`. The page arrays are
bindless; `texturePage` indexes all bundles at the same slot. Missing optional bundles bind a valid neutral page.
This uses the descriptor-indexing support already required for entity textures without allocating descriptors
per block sprite.

Standalone entity textures can use the same material table and page API later. Initially, retain their existing
bindless albedo slots but put the slot and all PBR presence in the entity's `MaterialHeader`, not each triangle.

### 6. Central shader evaluation

Move source decoding out of closest-hit and provide one entry point:

```slang
Surface evaluateMaterial(uint materialId, float2 atlasUv, float lod,
                         float3 geometricNormal, TbnInput tbn, float3 tint);
```

`Surface` should contain the resolved values consumed by raygen and future light sampling:

```text
baseColor, geometricNormal, shadingNormal, roughness, metalness, F0,
emissionRadiance, transmission, IOR, absorption, SSS, model, frontFace
```

Feature tests remain, but they occur once inside this function and read `MaterialHeader.features`. This removes
`hasS`/`hasN` from geometry and prevents terrain/entity paths from implementing subtly different decoding.

## Heuristic material compilation

Emission should be the first improved heuristic because it affects visible shading and the future light table.

### Emission

Do not mark arbitrary bright textures emissive. Gate heuristic emission on a trustworthy semantic signal:

- the block/state light-emission value;
- an explicit material override/tag; or
- a small, reviewable engine rule for known emissive renderers.

For a gated texture, build an emission mask from the linear albedo image at reload time:

1. Convert albedo to linear RGB and compute luminance.
2. Estimate dark/background and bright/foreground percentiles per sprite.
3. Use a smooth percentile-relative threshold rather than a global luminance threshold.
4. Increase confidence for saturated pixels or pixels far from the sprite's median color.
5. For uniformly luminous sprites (glowstone, sea lantern), retain a nonzero full-surface floor instead of
   selecting only a few highlights.
6. Multiply the mask by the state light level and a calibrated radiance scale. Use albedo as emission color by
   default.

LabPBR-authored emission replaces this inferred mask. The compiler should also produce, per material:

```text
averageEmissionRgb
integratedEmissionLuminance
emissiveCoverage
```

Those values make emissive-triangle classification and importance weighting possible without sampling a
texture during light-table construction.

### Roughness and metalness

Retain `SoundType`/known-block rules as semantic priors, but make them inputs to compilation rather than values
copied into every primitive. Texture statistics can refine roughness conservatively (contrast/edge density can
raise micro-roughness; it should not classify a material as metal). Metalness should remain rule-, tag-, or
authoring-driven; image brightness alone is not enough.

### Transmission and IOR

Add data-driven profiles for at least:

| Profile | Model | IOR | Notes |
|---|---:|---:|---|
| air | volume | 1.0003 | implicit exterior |
| water | volume dielectric | 1.333 | Beer-Lambert absorption, optional wave normal |
| glass | thin/solid dielectric | 1.52 | tint and thickness policy separate |
| ice | solid dielectric | 1.31 | rougher, colored absorption |
| honey/slime | solid dielectric | configurable | higher roughness/absorption |

Use the unperturbed geometric normal for front/back classification, medium transitions, ray offsets, and
hemisphere validity. Use the mapped shading normal for the microfacet reflection/transmission lobe, clamped so
it cannot cross the geometric-normal hemisphere. Refracting solely with an unconstrained normal map causes
leaks and incorrect medium entry/exit.

Replace the raygen `inWater` boolean with a small medium state. A single current-medium ID/IOR is enough for the
first milestone; a depth-4 stack is the eventual robust solution for water inside glass or nested modded
materials. Thin-walled materials do not push the stack.

The hit payload should carry either `materialId` plus resolved surface data, or packed IOR/transmission/model
values. It must no longer reduce all transmission to `MATERIAL_WATER`/`MATERIAL_GLASS` before raygen.

## LOD and mip generation

The ray-cone LOD computation can remain shared. The fix belongs in material asset preparation and semantic
sampling:

- Generate every material page with an explicit full mip chain.
- Decode LabPBR categorical values to continuous physical values **before** filtering.
- Average normal vectors then renormalize. Feed lost normal length/variance into roughness (Toksvig-style is a
  reasonable first implementation).
- Filter roughness in a variance-aware domain rather than averaging raw LabPBR smoothness bytes.
- Average emission as radiance/energy, not as a categorical alpha code and not with `max`.
- Preserve cutout alpha coverage at the configured threshold.
- Average metalness, F0, AO, SSS, and transmission as their decoded physical values.
- Replicate sprite borders into per-mip gutters and clamp sampling to the valid sprite mip count.
- Use linear mip interpolation for material sampling once the mip contents are valid. Pixel-art albedo may keep
  nearest within a level as a separate artistic choice.

Albedo, material, normal, breaking-overlay, and entity paths should all consume the same ray-cone footprint,
but each texture uses its own dimensions and maximum valid LOD.

## Async terrain and reload lifecycle

With the snapshot in place, the terrain path becomes:

```text
resource reload
  -> compile immutable MaterialSnapshot + GPU pages/table
  -> publish snapshot epoch N

terrain worker
  -> snapshot.resolve(blockState, sprite, layer)
  -> write materialId directly into Prim
  -> upload + prepare BLAS on async path
  -> publish without render-thread material patching
```

Delete from the terrain result once migrated:

- `PackedSection.materialSprites`;
- `Geom.materialSprites`;
- `PreparedSection.materialSprites`;
- `RtSectionBuilder.resolveMaterials`; and
- the mapped-buffer rewrite/flush at completion.

Every terrain task/result must carry the material epoch it used. A result from an older epoch is discarded and
requeued rather than published with IDs from a different table.

Resource reload also changes atlas UVs, not only materials. Do not incrementally show old geometry with a new
atlas/material table. The safest first policy is:

1. stop RT presentation (vanilla fallback remains available);
2. invalidate/cancel old-epoch worker results;
3. compile and upload the new snapshot;
4. rebuild required resident sections for the new atlas epoch; and
5. resume RT after a coherent minimum residency set is ready.

A later optimization can retain two complete resource epochs and atomically switch after background rebuild,
but that requires retaining the old Minecraft atlas image and is not a good first milestone.

## ReSTIR-ready light table

Treat emission metadata as a compiler output, not something rediscovered by ReSTIR.

On section publication, a compute pass (or CPU builder initially) emits one light candidate for each triangle
whose material/state emission summary is nonzero:

```text
LightRecord
  instance/section ID
  primitive ID
  materialId
  area
  average radiance RGB
  sampling weight = area * luminance(average radiance)
```

Keep section-local light ranges so terrain eviction/rebuild updates only that section. A second-level table over
section totals can feed an alias table or hierarchical sampler. ReSTIR reservoirs can later reference stable
light handles `(section generation, local light index)` rather than raw pointers.

The exact emissive texel can still be evaluated at the sampled barycentric UV. The precomputed average is only
for candidate existence and importance, so the estimator remains consistent.

## Migration sequence

### M0: diagnostics and invariants

- Count materials, pages, source type, unknown/fallback lookups, emissive coverage, and page memory.
- Add CPU tests for LabPBR decode and every semantic mip reducer.
- Add a shader/Java layout test for the material header and new primitive record.
- Capture reference images/guides for vanilla, LabPBR, cutout foliage, emissive blocks, water, glass, and ice.

### M1: registry and material IDs, behavior-preserving

- Build an immutable registry at resource reload.
- Keep the existing block/spec/normal textures temporarily.
- Change terrain primitives to carry `materialId`; move old rough/metal/has flags into a compatibility
  `MaterialHeader`.
- Remove terrain render-thread patching and add material epochs.

This is the highest-value structural milestone and should land separately.

### M2: canonical pages and semantic mips

- CPU-decode LabPBR and synthesize neutral missing channels.
- Add page packing, gutters, mip chains, and bindless page bindings.
- Route closest-hit through `evaluateMaterial`.
- Delete raw LabPBR decode and parallel atlas sampling from shaders.

### M3: heuristic full materials and emission summaries

- Add the gated albedo-based emission mask.
- Move current sound/block heuristics into compiler profiles.
- Add versioned JSON overrides and diagnostics.
- Produce emission summaries and a debug view for emission mask/source.

### M4: generalized transmission

- Add material IOR/transmission/absorption to the payload/path state.
- Separate geometric and shading normals.
- Introduce current-medium state, then a small stack.
- Convert water, glass, ice, honey, and slime from hard-coded raygen branches to profiles.

### M5: light table foundation

- Build/update section-local emissive triangle records during publication.
- Add hierarchical totals and stable handles.
- Validate sampled power against brute-force material integration before adding ReSTIR reservoirs.

## Acceptance criteria

- Terrain completion performs no resource lookup, PNG decode, material registry mutation, or primitive-buffer
  patch.
- No `hasS`/`hasN` field exists in geometry or capture code.
- A missing LabPBR map produces a complete neutral/heuristic material, not an absent channel.
- Material textures visibly choose coarser mips under ray-cone debug, without sprite bleed or invalid categorical
  blending.
- LabPBR level-0 output remains within the agreed image tolerance of the old decoder.
- Applying a resource pack cannot publish a terrain result from the previous material epoch.
- Emission seen by shading and emission summarized for the light table come from the same compiled material.
- IOR and transmission are data-driven, and normal mapping cannot flip medium entry/exit.

## Recommended first change

Implement M1 before improving the heuristic. It removes the async/render-thread coupling and gives heuristic
emission, canonical mips, transmission, and the light table one stable place to live. Adding better emission to
`RtMaterials` or adding more lanes to `Prim.mat` first would make the eventual migration harder and would still
duplicate material state per triangle.
