package dev.comfyfluffy.caustica.rt.material;

import com.mojang.blaze3d.platform.NativeImage;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.SpriteContentsAccessor;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.gen.MaterialHeaderData;
import dev.comfyfluffy.caustica.rt.gen.MaterialHeaderData.Float4;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resource-epoch material registry shared by terrain, entities, block entities and item geometry. Terrain
 * workers read an immutable {@link Snapshot}; entity atlas headers may append into pre-reserved table slots
 * because their stitched UV rectangles only become available during capture. Published records never mutate.
 */
public final class RtMaterialRegistry {
    public static final RtMaterialRegistry INSTANCE = new RtMaterialRegistry();

    // Canonical MaterialHeader model/feature bits, mirrored by world_common.slang's MATERIAL_* constants.
    // RtBlockMaterials.Entry.features uses the same bit values (FEATURE_OVERRIDE_EMISSION there means the
    // override mask was baked into surface1.a), so entry features flow into headers with a plain mask.
    public static final int MODEL_OPAQUE = 0;
    public static final int MODEL_WATER = 1;
    public static final int MODEL_GLASS = 3;
    public static final int FEATURE_SPEC = 1;
    public static final int FEATURE_NORMAL = 2;
    public static final int FEATURE_HEURISTIC_EMISSION = 4;
    public static final int FEATURE_OVERRIDE_EMISSION = 8;
    public static final int FEATURE_STOCHASTIC_ALPHA = 16;
    private static final int EMISSION_STRENGTH_SHIFT = 8;
    private static final int EMISSION_STRENGTH_MASK = 255;
    private static final float MAX_OVERRIDE_EMISSION_STRENGTH = 4.0f;
    private static final int MAX_LOD_SHIFT = 24;

    private static final int MODEL_VARIANTS = 2; // ordinary opaque/cutout and thin glass
    private static final int EMISSION_VARIANTS = 2; // state-gated emission disabled/enabled
    private static final int VARIANT_OPAQUE = 0;
    private static final int VARIANT_GLASS = 1;
    // Profiles a sprite variant can actually be resolved with: RtMaterials.profile() never returns
    // WATER/LAVA (fluids use the dedicated singleton headers), so compiling those variants per sprite
    // would only bloat the table. The variant index math assumes these are the first enum ordinals.
    private static final RtMaterials.Profile[] SPRITE_PROFILES = {
            RtMaterials.Profile.DEFAULT, RtMaterials.Profile.METAL,
            RtMaterials.Profile.GLASS, RtMaterials.Profile.SMOOTH};

    static {
        for (int i = 0; i < SPRITE_PROFILES.length; i++) {
            if (SPRITE_PROFILES[i].ordinal() != i) {
                throw new IllegalStateException("Sprite profile ordinals must be contiguous from 0");
            }
        }
    }

    private volatile Snapshot snapshot;
    private RtBuffer table;
    private long nextEpoch;
    private Map<Identifier, Integer> entityTextureIds = Map.of();
    private Map<Identifier, EntityTemplate> entityTemplates = Map.of();
    private final Map<EntitySpriteKey, Integer> entitySpriteIds = new HashMap<>();
    private final Map<Integer, Integer> stochasticAlphaIds = new HashMap<>();
    private int entityFallbackId;
    private int nextDynamicId;
    private int tableCapacity;

    private record EntityTemplate(RtMaterialDesc desc, RtBlockMaterials.Entry entry) {
    }

    /** Stable within one atlas epoch even if capture presents a different sprite wrapper instance. */
    private record EntitySpriteKey(Identifier name, Identifier atlas) {
        static EntitySpriteKey of(TextureAtlasSprite sprite) {
            return new EntitySpriteKey(sprite.contents().name(), sprite.atlasLocation());
        }
    }

    private RtMaterialRegistry() {
    }

    /** Build and atomically publish the block and entity registry for the current resource epoch. */
    public void rebuild(RtContext ctx, RtBlockMaterials blockMaterials, RtMaterialOverrides overrides) {
        Map<TextureAtlasSprite, RtBlockMaterials.Entry> entriesBySprite = blockMaterials.preparedEntries();
        List<TextureAtlasSprite> sprites = new ArrayList<>(entriesBySprite.keySet());
        sprites.sort(Comparator.comparing(sprite -> sprite.contents().name().toString()));
        Map<Identifier, RtBlockMaterials.Entry> entriesByResource = blockMaterials.preparedResourceEntries();
        List<Identifier> entityResources = new ArrayList<>(entriesByResource.keySet());
        entityResources.sort(Comparator.comparing(Identifier::toString));
        RtBlockMaterials.Entry fallbackEntry = blockMaterials.entry(null);

        // One pass per sprite computes both the raw average (translucent shadow filtering) and the
        // premultiplied-linear uniform emission summary; sprites are independent, so scan in parallel.
        Map<TextureAtlasSprite, SpriteStats> spriteStats = new ConcurrentHashMap<>();
        sprites.parallelStream().forEach(sprite -> spriteStats.put(sprite, computeSpriteStats(sprite)));

        int profileVariants = SPRITE_PROFILES.length * MODEL_VARIANTS * EMISSION_VARIANTS;
        List<MaterialHeaderData> headers = new ArrayList<>(3 + profileVariants
                + sprites.size() * profileVariants);
        List<RtMaterialDesc> descriptions = new ArrayList<>(headers.size());
        List<RtEmissionGrid> grids = new ArrayList<>(headers.size());
        add(headers, descriptions, grids, compileDesc(MODEL_OPAQUE, 0, RtMaterials.Profile.DEFAULT,
                false, true, RtMaterialDesc.EmissionSummary.NONE), transparentWhiteAverage(), fallbackEntry, null);
        int[] fallbackVariants = new int[profileVariants];
        for (RtMaterials.Profile profile : SPRITE_PROFILES) {
            for (boolean glass : new boolean[]{false, true}) {
                for (boolean emitting : new boolean[]{false, true}) {
                    int variant = index(profile, glass, emitting);
                    if (profile == RtMaterials.Profile.DEFAULT && !glass && !emitting) {
                        fallbackVariants[variant] = 0;
                        continue;
                    }
                    fallbackVariants[variant] = headers.size();
                    add(headers, descriptions, grids, compileDesc(glass ? MODEL_GLASS : MODEL_OPAQUE, 0,
                                    profile, emitting, true, RtMaterialDesc.EmissionSummary.NONE),
                            transparentWhiteAverage(), fallbackEntry, null);
                }
            }
        }
        int waterId = headers.size();
        add(headers, descriptions, grids, compileDesc(MODEL_WATER, 0, RtMaterials.Profile.WATER,
                false, true, RtMaterialDesc.EmissionSummary.NONE), whiteAverage(), fallbackEntry, null);
        int lavaId = headers.size();
        // Lava's fluid mesher assigns this singleton id (no sprite resolve), so its light color comes from
        // the lava_still albedo grid — a mean-color area light instead of the old branch's white lava.
        add(headers, descriptions, grids, compileDesc(MODEL_OPAQUE, 0, RtMaterials.Profile.LAVA,
                true, true, uniformWhiteSummary()), whiteAverage(), fallbackEntry,
                albedoGridFor(sprites, spriteStats, "block/lava_still"));
        int nextEntityFallbackId = headers.size();
        add(headers, descriptions, grids, compileEntityDesc(0, true, RtMaterialDesc.EmissionSummary.NONE),
                transparentWhiteAverage(), fallbackEntry, null);

        IdentityHashMap<TextureAtlasSprite, int[]> ids = new IdentityHashMap<>();
        List<MutableCompiledOverride> compiledOverrides = new ArrayList<>();
        for (RtMaterialOverrides.Rule rule : overrides.rules()) {
            compiledOverrides.add(new MutableCompiledOverride(rule));
        }
        for (TextureAtlasSprite sprite : sprites) {
            RtBlockMaterials.Entry entry = entriesBySprite.get(sprite);
            int baseFeatures = entry.features()
                    & (FEATURE_SPEC | FEATURE_NORMAL | FEATURE_HEURISTIC_EMISSION);
            SpriteStats stats = spriteStats.getOrDefault(sprite, SpriteStats.NEUTRAL);

            // The first sprite-wide (block == null) rule owns this sprite for every state, so its variants
            // are compiled straight into the primary map and the base variants are never emitted. Only
            // block-conditional rules stay in the per-quad runtime scan.
            MutableCompiledOverride spriteWide = null;
            for (MutableCompiledOverride compiled : compiledOverrides) {
                if (compiled.rule.block() == null && compiled.rule.matchesSprite(sprite)) {
                    spriteWide = compiled;
                    compiled.matchedSprite = true;
                    break;
                }
            }
            int[] variants = new int[profileVariants];
            for (RtMaterials.Profile profile : SPRITE_PROFILES) {
                for (boolean glass : new boolean[]{false, true}) {
                    for (boolean emitting : new boolean[]{false, true}) {
                        int features = emitting ? baseFeatures : baseFeatures & ~FEATURE_HEURISTIC_EMISSION;
                        RtMaterialDesc desc = compileDesc(glass ? MODEL_GLASS : MODEL_OPAQUE, features,
                                profile, emitting, false,
                                variantSummary(features, emitting, entry, stats.uniformSummary()));
                        if (spriteWide != null) {
                            desc = spriteWide.rule.apply(desc, entry.overrideEmissionSummary());
                        }
                        variants[index(profile, glass, emitting)] = headers.size();
                        add(headers, descriptions, grids, desc, stats.average(), entry, stats.albedoGrid());
                    }
                }
            }
            ids.put(sprite, variants);

            for (MutableCompiledOverride compiled : compiledOverrides) {
                if (compiled.rule.block() == null || !compiled.rule.matchesSprite(sprite)) continue;
                int[] overrideVariants = new int[profileVariants];
                for (RtMaterials.Profile profile : SPRITE_PROFILES) {
                    for (boolean glass : new boolean[]{false, true}) {
                        for (boolean emitting : new boolean[]{false, true}) {
                            int features = emitting ? baseFeatures : baseFeatures & ~FEATURE_HEURISTIC_EMISSION;
                            RtMaterialDesc base = compileDesc(glass ? MODEL_GLASS : MODEL_OPAQUE,
                                    features, profile, emitting, false,
                                    variantSummary(features, emitting, entry, stats.uniformSummary()));
                            RtMaterialDesc desc = compiled.rule.apply(base, entry.overrideEmissionSummary());
                            overrideVariants[index(profile, glass, emitting)] = headers.size();
                            add(headers, descriptions, grids, desc, stats.average(), entry, stats.albedoGrid());
                        }
                    }
                }
                compiled.ids.put(sprite, overrideVariants);
            }
        }

        Map<Identifier, Integer> nextEntityTextureIds = new HashMap<>();
        Map<Identifier, EntityTemplate> nextEntityTemplates = new HashMap<>();
        Set<RtMaterialOverrides.Rule> entityMatchedOverrides = new HashSet<>();
        for (Identifier name : entityResources) {
            RtBlockMaterials.Entry entry = entriesByResource.get(name);
            int features = entry.features() & (FEATURE_SPEC | FEATURE_NORMAL);
            RtMaterialDesc desc = compileEntityDesc(features, false, entry.emissionSummary());
            for (RtMaterialOverrides.Rule rule : overrides.rules()) {
                if (!rule.matchesEntity(name)) continue;
                desc = rule.apply(desc, entry.overrideEmissionSummary());
                entityMatchedOverrides.add(rule);
                break;
            }
            int id = headers.size();
            add(headers, descriptions, grids, desc, transparentWhiteAverage(), entry, null);
            nextEntityTextureIds.put(name, id);
            nextEntityTemplates.put(name, new EntityTemplate(desc, entry));
        }

        // Full entity textures have fixed [0,1] UVs and receive IDs above. Atlas sprites need a second,
        // append-only header with the actual stitched atlas rectangle, which is only known when the sprite
        // first appears in capture. Reserve room for atlas and alpha-mode variants.
        int dynamicReserve = Math.max(64, Math.addExact(sprites.size(),
                Math.multiplyExact(entityResources.size(), 3)));
        int recordCapacity = Math.addExact(headers.size(), dynamicReserve);
        long byteSize = Math.multiplyExact((long) recordCapacity, MaterialHeaderData.BYTE_SIZE);
        if (byteSize > Integer.MAX_VALUE) {
            throw new IllegalStateException("RT material table exceeds mapped-buffer limit: " + byteSize);
        }
        RtBuffer nextTable = ctx.createBuffer(byteSize, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                true, "material table");
        try {
            ByteBuffer mapped = MemoryUtil.memByteBuffer(nextTable.mapped, (int) byteSize)
                    .order(ByteOrder.nativeOrder());
            for (int i = 0; i < headers.size(); i++) {
                ByteBuffer entry = mapped.slice(i * MaterialHeaderData.BYTE_SIZE,
                        MaterialHeaderData.BYTE_SIZE).order(ByteOrder.nativeOrder());
                headers.get(i).write(entry);
            }
            nextTable.flush();
        } catch (Throwable t) {
            nextTable.destroy();
            throw t;
        }

        RtBuffer oldTable = table;
        long epoch = ++nextEpoch;
        List<CompiledOverride> frozenOverrides = compiledOverrides.stream()
                .filter(value -> !value.ids.isEmpty())
                .map(MutableCompiledOverride::freeze).toList();
        long matchedOverrideRules = 0;
        for (MutableCompiledOverride compiled : compiledOverrides) {
            if (!compiled.ids.isEmpty() || compiled.matchedSprite
                    || entityMatchedOverrides.contains(compiled.rule)) {
                matchedOverrideRules++;
            } else {
                CausticaMod.LOGGER.warn("RT material override {} matched no compiled texture ({})",
                        compiled.rule.source(), compiled.rule.sprite());
            }
        }
        Snapshot next = new Snapshot(epoch, Collections.unmodifiableMap(ids), fallbackVariants, waterId, lavaId,
                List.copyOf(descriptions), Collections.unmodifiableList(new ArrayList<>(grids)), frozenOverrides);
        entityTextureIds = Collections.unmodifiableMap(nextEntityTextureIds);
        entityTemplates = Collections.unmodifiableMap(nextEntityTemplates);
        entitySpriteIds.clear();
        stochasticAlphaIds.clear();
        entityFallbackId = nextEntityFallbackId;
        nextDynamicId = headers.size();
        tableCapacity = recordCapacity;
        table = nextTable;
        snapshot = next; // volatile publication: map and arrays are never mutated afterward
        if (oldTable != null) oldTable.destroy();
        long emissive = descriptions.stream().filter(desc -> desc.emissionSource() != RtMaterialDesc.EmissionSource.NONE)
                .count();
        long inferred = descriptions.stream().filter(desc -> desc.emissionSource()
                == RtMaterialDesc.EmissionSource.HEURISTIC_MASK).count();
        long authoredEmission = descriptions.stream().filter(desc -> desc.emissionSource()
                == RtMaterialDesc.EmissionSource.LAB_PBR).count();
        long uniformEmission = descriptions.stream().filter(desc -> desc.emissionSource()
                == RtMaterialDesc.EmissionSource.STATE_UNIFORM).count();
        long overrideEmission = descriptions.stream().filter(desc -> desc.emissionSource()
                == RtMaterialDesc.EmissionSource.OVERRIDE).count();
        double averageCoverage = descriptions.stream().filter(desc -> desc.emissionSummary().emissive())
                .mapToDouble(desc -> desc.emissionSummary().coverage()).average().orElse(0.0);
        CausticaMod.LOGGER.info("RT materials: epoch={}, records={}, capacity={}, blockSprites={}, entityResources={}, overrideRules={}, matchedOverrides={}, emissive={}, labPbrEmission={}, heuristicMasks={}, uniformEmission={}, overrideEmission={}, avgEmissionCoverage={}, tableKiB={}",
                epoch, headers.size(), recordCapacity, sprites.size(), entityResources.size(), overrides.rules().size(),
                matchedOverrideRules, emissive,
                authoredEmission, inferred, uniformEmission, overrideEmission,
                String.format(java.util.Locale.ROOT, "%.3f", averageCoverage), byteSize / 1024);
    }

    public Snapshot requireSnapshot() {
        Snapshot current = snapshot;
        if (current == null) throw new IllegalStateException("RT terrain materials are not prepared");
        return current;
    }

    public long epoch() {
        Snapshot current = snapshot;
        return current != null ? current.epoch() : 0L;
    }

    public boolean isReady() {
        return snapshot != null && table != null;
    }

    public long tableAddress() {
        RtBuffer current = table;
        if (current == null) throw new IllegalStateException("RT material table is not uploaded");
        return current.deviceAddress;
    }

    /** Neutral canonical material used by runtime-only textures (player skins, generated glyph atlases, etc.). */
    public int entityFallbackId() {
        return entityFallbackId;
    }

    public int entityFallbackId(boolean stochasticAlpha) {
        return stochasticAlpha ? withStochasticAlpha(entityFallbackId) : entityFallbackId;
    }

    /** Return an append-only material-instance variant with stochastic coverage enabled. */
    public synchronized int withStochasticAlpha(int materialId) {
        if (table == null || materialId < 0 || materialId >= nextDynamicId) {
            throw new IllegalStateException("RT material is not available for alpha specialization: " + materialId);
        }
        long sourceOffset = Math.multiplyExact((long) materialId, MaterialHeaderData.BYTE_SIZE);
        ByteBuffer source = MemoryUtil.memByteBuffer(table.mapped + sourceOffset, MaterialHeaderData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        if ((source.getInt(4) & FEATURE_STOCHASTIC_ALPHA) != 0) return materialId;
        Integer current = stochasticAlphaIds.get(materialId);
        if (current != null) return current;
        if (nextDynamicId >= tableCapacity) {
            throw new IllegalStateException("RT material header reserve exhausted");
        }
        int id = nextDynamicId++;
        long targetOffset = Math.multiplyExact((long) id, MaterialHeaderData.BYTE_SIZE);
        MemoryUtil.memCopy(table.mapped + sourceOffset, table.mapped + targetOffset,
                MaterialHeaderData.BYTE_SIZE);
        ByteBuffer target = MemoryUtil.memByteBuffer(table.mapped + targetOffset, MaterialHeaderData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        target.putInt(4, target.getInt(4) | FEATURE_STOCHASTIC_ALPHA);
        table.flush(targetOffset, MaterialHeaderData.BYTE_SIZE);
        stochasticAlphaIds.put(materialId, id);
        return id;
    }

    /** Resolve a full entity texture resource to its pack-compiled material ID. */
    public int resolveEntityTexture(Identifier textureLocation, boolean stochasticAlpha) {
        int id = textureLocation != null
                ? entityTextureIds.getOrDefault(RtBlockMaterials.logicalTextureName(textureLocation), entityFallbackId)
                : entityFallbackId;
        return stochasticAlpha ? withStochasticAlpha(id) : id;
    }

    /**
     * Resolve a block-entity atlas sprite. Canonical texels were compiled at pack load; only this header's
     * atlas-to-local UV transform is appended now. Existing IDs and page contents are never modified.
     */
    public synchronized int resolveEntitySprite(TextureAtlasSprite sprite, boolean stochasticAlpha) {
        if (sprite == null) return entityFallbackId(stochasticAlpha);
        EntitySpriteKey key = EntitySpriteKey.of(sprite);
        Integer current = entitySpriteIds.get(key);
        if (current != null) return stochasticAlpha ? withStochasticAlpha(current) : current;
        EntityTemplate template = entityTemplates.get(key.name());
        if (template == null) return entityFallbackId(stochasticAlpha);
        if (nextDynamicId >= tableCapacity || table == null) {
            throw new IllegalStateException("RT entity material header reserve exhausted");
        }
        int id = nextDynamicId++;
        MaterialHeaderData header = header(template.desc(), transparentWhiteAverage(), template.entry(),
                sprite.getU0(), sprite.getV0(),
                RtBlockMaterials.inverseExtent(sprite.getU1() - sprite.getU0()),
                RtBlockMaterials.inverseExtent(sprite.getV1() - sprite.getV0()));
        long offset = Math.multiplyExact((long) id, MaterialHeaderData.BYTE_SIZE);
        ByteBuffer target = MemoryUtil.memByteBuffer(table.mapped + offset, MaterialHeaderData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        header.write(target);
        table.flush(offset, MaterialHeaderData.BYTE_SIZE);
        entitySpriteIds.put(key, id);
        return stochasticAlpha ? withStochasticAlpha(id) : id;
    }

    /** Caller must ensure no in-flight trace references the current table. */
    public void destroy() {
        snapshot = null;
        entityTextureIds = Map.of();
        entityTemplates = Map.of();
        entitySpriteIds.clear();
        stochasticAlphaIds.clear();
        entityFallbackId = 0;
        nextDynamicId = 0;
        tableCapacity = 0;
        if (table != null) {
            table.destroy();
            table = null;
        }
    }

    private static int index(RtMaterials.Profile profile, boolean glass, boolean emitting) {
        // WATER/LAVA cannot classify a sprite; map them defensively to DEFAULT instead of overrunning.
        int p = profile.ordinal() < SPRITE_PROFILES.length ? profile.ordinal() : 0;
        return (p * MODEL_VARIANTS + (glass ? VARIANT_GLASS : VARIANT_OPAQUE))
                * EMISSION_VARIANTS + (emitting ? 1 : 0);
    }

    /** LabPBR/heuristic masks own the summary; otherwise an emitting state falls back to whole-sprite. */
    private static RtMaterialDesc.EmissionSummary variantSummary(int features, boolean emitting,
                                                                 RtBlockMaterials.Entry entry,
                                                                 RtMaterialDesc.EmissionSummary uniformSummary) {
        if ((features & (FEATURE_SPEC | FEATURE_HEURISTIC_EMISSION)) != 0) return entry.emissionSummary();
        return emitting ? uniformSummary : RtMaterialDesc.EmissionSummary.NONE;
    }

    private static RtMaterialDesc compileDesc(int model, int features, RtMaterials.Profile profile,
                                              boolean emitting, boolean neutral,
                                              RtMaterialDesc.EmissionSummary emissionSummary) {
        float roughness = model == MODEL_GLASS ? 0.05f : profile.roughness();
        float metalness = model == MODEL_GLASS ? 0.0f : profile.metalness();
        float ior = model == MODEL_WATER ? 1.333f : (model == MODEL_GLASS ? 1.52f : 1.0f);
        float transmission = model == MODEL_WATER || model == MODEL_GLASS ? 1.0f : 0.0f;
        boolean labPbr = (features & (FEATURE_SPEC | FEATURE_NORMAL)) != 0;
        RtMaterialDesc.Source source = neutral ? RtMaterialDesc.Source.NEUTRAL
                : (labPbr ? RtMaterialDesc.Source.LAB_PBR : RtMaterialDesc.Source.HEURISTIC);
        RtMaterialDesc.EmissionSource emissionSource;
        if ((features & FEATURE_SPEC) != 0) {
            emissionSource = RtMaterialDesc.EmissionSource.LAB_PBR;
        } else if ((features & FEATURE_HEURISTIC_EMISSION) != 0) {
            emissionSource = RtMaterialDesc.EmissionSource.HEURISTIC_MASK;
        } else if (emitting) {
            emissionSource = RtMaterialDesc.EmissionSource.STATE_UNIFORM;
        } else {
            emissionSource = RtMaterialDesc.EmissionSource.NONE;
        }
        float emissionStrength = emissionSource == RtMaterialDesc.EmissionSource.NONE ? 0.0f : 1.0f;
        return new RtMaterialDesc(model, source, features, roughness, metalness, ior, transmission,
                emissionSource, emissionStrength, emissionSummary);
    }

    private static RtMaterialDesc compileEntityDesc(int features, boolean neutral,
                                                    RtMaterialDesc.EmissionSummary emissionSummary) {
        boolean authored = (features & (FEATURE_SPEC | FEATURE_NORMAL)) != 0;
        RtMaterialDesc.Source source = neutral ? RtMaterialDesc.Source.NEUTRAL
                : (authored ? RtMaterialDesc.Source.LAB_PBR : RtMaterialDesc.Source.HEURISTIC);
        RtMaterialDesc.EmissionSource emissionSource = (features & FEATURE_SPEC) != 0
                ? RtMaterialDesc.EmissionSource.LAB_PBR : RtMaterialDesc.EmissionSource.NONE;
        return new RtMaterialDesc(MODEL_OPAQUE, source, features, RtMaterials.ENTITY_ROUGH, 0.0f,
                1.0f, 0.0f, emissionSource, emissionSource == RtMaterialDesc.EmissionSource.NONE ? 0.0f : 1.0f,
                emissionSummary);
    }

    private static void add(List<MaterialHeaderData> headers, List<RtMaterialDesc> descriptions,
                            List<RtEmissionGrid> grids, RtMaterialDesc desc, float[] average,
                            RtBlockMaterials.Entry entry, RtEmissionGrid uniformGrid) {
        headers.add(header(desc, average, entry, entry.albedoU(), entry.albedoV(),
                entry.albedoInvDu(), entry.albedoInvDv()));
        descriptions.add(desc);
        grids.add(gridFor(desc, entry, uniformGrid));
    }

    /**
     * The emission grid whose per-texel source matches what {@code world.rchit} shades for this
     * description — the same selection {@link #variantSummary}/override application made for the summary.
     */
    private static RtEmissionGrid gridFor(RtMaterialDesc desc, RtBlockMaterials.Entry entry,
                                          RtEmissionGrid uniformGrid) {
        return switch (desc.emissionSource()) {
            case LAB_PBR, HEURISTIC_MASK -> entry.emissionGrid();
            case OVERRIDE -> entry.overrideEmissionGrid();
            case STATE_UNIFORM -> uniformGrid;
            case NONE -> null;
        };
    }

    /** The whole-sprite albedo grid of a named block sprite (uniform-emitter light color), or null. */
    private static RtEmissionGrid albedoGridFor(List<TextureAtlasSprite> sprites,
                                                Map<TextureAtlasSprite, SpriteStats> spriteStats,
                                                String name) {
        Identifier id = Identifier.withDefaultNamespace(name);
        for (TextureAtlasSprite sprite : sprites) {
            if (sprite.contents().name().equals(id)) {
                SpriteStats stats = spriteStats.get(sprite);
                return stats != null ? stats.albedoGrid() : null;
            }
        }
        return null;
    }

    private static MaterialHeaderData header(RtMaterialDesc desc, float[] average,
                                             RtBlockMaterials.Entry entry, float albedoU, float albedoV,
                                             float albedoInvDu, float albedoInvDv) {
        int packedFeatures = desc.features() | (entry.maxLod() << MAX_LOD_SHIFT);
        if ((desc.features() & FEATURE_OVERRIDE_EMISSION) != 0) {
            int strength = Math.round(Math.min(MAX_OVERRIDE_EMISSION_STRENGTH, desc.emissionStrength())
                    * (EMISSION_STRENGTH_MASK / MAX_OVERRIDE_EMISSION_STRENGTH));
            packedFeatures |= strength << EMISSION_STRENGTH_SHIFT;
        }
        return new MaterialHeaderData(desc.model(), packedFeatures, entry.pageIndex(), 0,
                new Float4(entry.materialU(), entry.materialV(), entry.materialDu(), entry.materialDv()),
                new Float4(albedoU, albedoV, albedoInvDu, albedoInvDv),
                new Float4(desc.roughness(), desc.metalness(), desc.ior(), desc.transmission()),
                new Float4(average[0], average[1], average[2], average[3]));
    }

    private static float[] whiteAverage() {
        return new float[]{1.0f, 1.0f, 1.0f, 1.0f};
    }

    private static float[] transparentWhiteAverage() {
        return new float[]{1.0f, 1.0f, 1.0f, 0.0f};
    }

    private static RtMaterialDesc.EmissionSummary uniformWhiteSummary() {
        return new RtMaterialDesc.EmissionSummary(1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
    }

    /**
     * Per-sprite compile inputs gathered in a single pixel pass: the raw average RGBA (matching the
     * previous translucent shadow-filter input) and the premultiplied-linear uniform emission summary
     * used when a state emits light but no per-texel mask was compiled.
     */
    private record SpriteStats(float[] average, RtMaterialDesc.EmissionSummary uniformSummary,
                               RtEmissionGrid albedoGrid) {
        static final SpriteStats NEUTRAL = new SpriteStats(transparentWhiteAverage(),
                RtMaterialDesc.EmissionSummary.NONE, null);
    }

    private static SpriteStats computeSpriteStats(TextureAtlasSprite sprite) {
        var contents = sprite.contents();
        int width = contents.width();
        int height = contents.height();
        NativeImage image = ((SpriteContentsAccessor) contents).caustica$originalImage();
        if (image == null || width <= 0 || height <= 0) return SpriteStats.NEUTRAL;
        long sr = 0L, sg = 0L, sb = 0L, sa = 0L;
        double lr = 0.0, lg = 0.0, lb = 0.0;
        int covered = 0;
        // A uniform (state-gated) emitter radiates alpha-weighted albedo per texel; its grid mirrors that
        // so the light collector's footprint math is one code path across all emission sources.
        RtEmissionGrid.Builder gridBuilder = new RtEmissionGrid.Builder(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getPixel(x, y); // frame 0 always occupies the image's top-left tile
                int a = ARGB.alpha(pixel);
                sr += ARGB.red(pixel);
                sg += ARGB.green(pixel);
                sb += ARGB.blue(pixel);
                sa += a;
                float alpha = a / 255.0f;
                float plr = RtMaterialTextureData.srgbToLinear(ARGB.red(pixel)) * alpha;
                float plg = RtMaterialTextureData.srgbToLinear(ARGB.green(pixel)) * alpha;
                float plb = RtMaterialTextureData.srgbToLinear(ARGB.blue(pixel)) * alpha;
                lr += plr;
                lg += plg;
                lb += plb;
                gridBuilder.add(x, y, plr, plg, plb, alpha);
                if (a > 1) covered++;
            }
        }
        float inv = 1.0f / (width * (float) height);
        float scale = inv / 255.0f;
        float[] average = {sr * scale, sg * scale, sb * scale, sa * scale};
        double luminance = 0.2126 * lr + 0.7152 * lg + 0.0722 * lb;
        RtMaterialDesc.EmissionSummary uniform = luminance <= 0.0
                ? RtMaterialDesc.EmissionSummary.NONE
                : new RtMaterialDesc.EmissionSummary((float) (lr * inv), (float) (lg * inv),
                        (float) (lb * inv), (float) (luminance * inv), covered * inv);
        return new SpriteStats(average, uniform, gridBuilder.build());
    }

    private static final class MutableCompiledOverride {
        final RtMaterialOverrides.Rule rule;
        final IdentityHashMap<TextureAtlasSprite, int[]> ids = new IdentityHashMap<>();
        // Sprite-wide rules compile into the primary ids map instead of `ids`; this marks them matched.
        boolean matchedSprite;

        MutableCompiledOverride(RtMaterialOverrides.Rule rule) {
            this.rule = rule;
        }

        CompiledOverride freeze() {
            return new CompiledOverride(rule, Collections.unmodifiableMap(new IdentityHashMap<>(ids)));
        }
    }

    private record CompiledOverride(RtMaterialOverrides.Rule rule, Map<TextureAtlasSprite, int[]> ids) {
    }

    /** Read-only lookup captured once by a terrain task. */
    public static final class Snapshot {
        private final long epoch;
        private final Map<TextureAtlasSprite, int[]> ids;
        private final int[] fallbackVariants;
        private final int waterId;
        private final int lavaId;
        private final List<RtMaterialDesc> descriptions;
        private final List<RtEmissionGrid> grids;
        private final List<CompiledOverride> overrides;

        private Snapshot(long epoch, Map<TextureAtlasSprite, int[]> ids, int[] fallbackVariants,
                         int waterId, int lavaId, List<RtMaterialDesc> descriptions,
                         List<RtEmissionGrid> grids, List<CompiledOverride> overrides) {
            this.epoch = epoch;
            this.ids = ids;
            this.fallbackVariants = fallbackVariants;
            this.waterId = waterId;
            this.lavaId = lavaId;
            this.descriptions = descriptions;
            this.grids = grids;
            this.overrides = overrides;
        }

        public long epoch() {
            return epoch;
        }

        public int waterId() {
            return waterId;
        }

        public int lavaId() {
            return lavaId;
        }

        public int materialCount() {
            return descriptions.size();
        }

        public RtMaterialDesc material(int materialId) {
            return descriptions.get(materialId);
        }

        /** Emission summary grid matching this material's shaded emission source, or null when none. */
        public RtEmissionGrid emissionGrid(int materialId) {
            return grids.get(materialId);
        }

        public int resolve(TextureAtlasSprite sprite, BlockState state, boolean glass) {
            RtMaterials.Profile profile = RtMaterials.profile(state);
            boolean emitting = state != null && state.getLightEmission() > 0;
            int variant = index(profile, glass, emitting);
            // Only block-conditional rules remain here; sprite-wide overrides were compiled into `ids`.
            for (CompiledOverride override : overrides) {
                if (!override.rule.matches(sprite, state)) continue;
                int[] variants = override.ids.get(sprite);
                if (variants != null) return variants[variant];
            }
            int[] variants = ids.get(sprite);
            return variants != null ? variants[variant] : fallbackVariants[variant];
        }

        /** Stateless resolve (entity/block-atlas geometry). Sprite-wide overrides are already folded in. */
        public int resolve(TextureAtlasSprite sprite, RtMaterials.Profile profile, boolean glass, boolean emitting) {
            int[] variants = ids.get(sprite);
            int variant = index(profile, glass, emitting);
            return variants != null ? variants[variant] : fallbackVariants[variant];
        }
    }
}
