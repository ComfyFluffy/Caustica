package dev.comfyfluffy.caustica.rt.entity;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.RenderSetupAccessor;
import dev.comfyfluffy.caustica.mixin.RenderTypeAccessor;
import dev.comfyfluffy.caustica.rt.material.RtEntityMaterials;
import dev.comfyfluffy.caustica.rt.material.RtParallelAtlas;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Resolves the texture backing an entity {@link RenderType} to a Vulkan image-view handle, for the
 * bindless entity-texture array. Entities use per-type texture files (zombie.png, …), not the block
 * atlas, so each distinct texture maps to its own bindless slot. Slots are keyed by the resolved image
 * view rather than the {@link RenderType}: some render types are rebuilt every frame with a fresh
 * identity (the charged-creeper energy swirl scrolls its texture transform, so {@code energySwirl()}
 * allocates a new RenderType per frame), and keying by RenderType there would leak a slot per frame
 * until the array exhausts and everything falls back to slot 0.
 *
 * <p>The view is obtained through the <b>public</b> {@code RenderType.prepare()} → {@link
 * PreparedRenderType#textures()} API (a list of {@code Texture(name, GpuTextureView, sampler)}): the
 * primary sampler is {@code "Sampler0"} ({@code "Sampler1"}/{@code "Sampler2"} are the overlay/lightmap
 * the prepared list prepends). Resolution is cached per {@code RenderType} (they are stable singletons),
 * so the prepare() cost is paid once per distinct texture.
 */
public final class RtEntityTextures {
    /** Bindless array capacity (slot 0 reserved as a fallback texture). {@code -Dcaustica.rt.maxEntityTextures}. */
    public static int maxTextures() {
        return CausticaConfig.Rt.EntityTextures.MAX_TEXTURES.value();
    }

    /** Resolve + bind per-type LabPBR {@code _n}/{@code _s} for entities. Toggle off to skip entity material
     *  branches (prim flags stay 0). {@code -Dcaustica.rt.entityPbr}. */
    public static boolean entityPbr() {
        return CausticaConfig.Rt.EntityTextures.PBR.value();
    }

    public static final RtEntityTextures INSTANCE = new RtEntityTextures();

    // RenderType identity → resolved primary image-view handle. WEAK: some render types are rebuilt
    // every frame with a fresh identity (e.g. the charged-creeper energy-swirl layer, whose scrolling
    // texture transform makes RenderTypes.energySwirl() allocate a new RenderType each frame). A weak map
    // lets those dead identities be collected instead of accumulating; stable singletons (zombie.png, …)
    // stay cached and skip the costly RenderType.prepare().
    private final Map<RenderType, Long> viewCache = new WeakHashMap<>();
    // Resolved image-view handle → bindless slot. The slot identifies a *texture*, not a RenderType, so
    // many render types that differ only by a texture transform we don't replicate (the swirl's scroll)
    // collapse to ONE slot — instead of leaking a slot per frame until the array exhausts and everything
    // falls back to slot 0 (the block atlas). Append-only: a handle's slot never changes once assigned,
    // so update-after-bind writes for new slots never disturb in-flight frames.
    private final Map<Long, Integer> viewSlotCache = new HashMap<>();
    // Atlas-location → bindless slot, for items/blocks (which texture from an atlas, not a per-type
    // file). Seeded with the block atlas = slot 0 (also the fallback). Items use a separate item atlas.
    private final Map<Identifier, Integer> atlasSlotCache = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>(); // slots resolved this frame, awaiting upload
    // Atlas slots whose parallel LabPBR _s/_n (RtEntityMaterials, for block entities) have been bound into
    // bindless bindings 1/2 — bind once per atlas slot. Cleared on reset (the bindless set is recreated).
    private final java.util.Set<Integer> atlasMaterialBound = new java.util.HashSet<>();
    // Descriptor array capacity of the currently alive world pipeline. A higher config value applies after
    // reset/recreate; a lower value stops allocating new slots immediately without invalidating old ones.
    private int capacity = maxTextures();
    private int reservedTailSlots;
    private int nextSlot = 1;
    private boolean loggedFailure;
    private boolean loggedMaterialFailure;

    // Entity LabPBR: per-type _n/_s textures cached by resource Identifier (null = known-missing), closed
    // on reset(). Per-slot presence feeds one compact capture feature mask; the guard resolves _n/_s once
    // (slots can be re-seen via the same shared texture handle every frame).
    private final Map<Identifier, DynamicTexture> materialCache = new HashMap<>();
    private boolean[] slotHasN = new boolean[capacity];
    private boolean[] slotHasS = new boolean[capacity];
    private boolean[] materialResolved = new boolean[capacity];
    // Cached RenderSetup.TextureBinding#location() (the class is package-private, the method public).
    private Method locationMethod;

    private record Pending(int binding, int slot, long view) {
    }

    private RtEntityTextures() {
    }

    /**
     * The bindless slot for {@code renderType}'s texture. Keyed by the resolved image view, not the
     * RenderType, so per-frame-allocated render types sharing one texture reuse a single slot. Returns 0
     * (the fallback slot = block atlas) if the texture can't be resolved or the array is full.
     */
    public int slotFor(RenderType renderType) {
        if (renderType == null) {
            return 0;
        }
        int slot = slotForView(resolveView(renderType));
        // Resolve this per-type texture's LabPBR _n/_s siblings into the parallel bindless arrays the
        // first time the slot is seen (slots can recur every frame via the same shared handle). Marked
        // resolved up front so a one-off resolution miss isn't retried every frame.
        if (entityPbr() && slot > 0 && slot < materialResolved.length && !materialResolved[slot]) {
            materialResolved[slot] = true;
            resolveEntityMaterials(slot, renderType);
        }
        return slot;
    }

    /** Whether the slot has a LabPBR {@code _n} (normal) map bound. */
    public boolean slotHasNormal(int slot) {
        return slot > 0 && slot < slotHasN.length && slotHasN[slot];
    }

    /** Whether the slot has a LabPBR {@code _s} (specular) map bound. */
    public boolean slotHasSpec(int slot) {
        return slot > 0 && slot < slotHasS.length && slotHasS[slot];
    }

    /** Load the per-type {@code _n}/{@code _s} siblings of {@code renderType}'s texture into bindless
     *  bindings 1/2 at {@code slot}, recording presence for the prim flags. Render-thread (GPU upload). */
    private void resolveEntityMaterials(int slot, RenderType renderType) {
        Identifier loc = textureLocation(renderType);
        if (loc == null) {
            return;
        }
        long nView = loadMaterialView(loc, "_n");
        if (nView != 0L) {
            pending.add(new Pending(1, slot, nView));
            slotHasN[slot] = true;
        }
        long sView = loadMaterialView(loc, "_s");
        if (sView != 0L) {
            pending.add(new Pending(2, slot, sView));
            slotHasS[slot] = true;
        }
    }

    /**
     * The bindless slot for a texture atlas (block/item atlas used by item + block-model quads), cached
     * per atlas location. The block atlas is pre-seeded to slot 0; other atlases (the item atlas) get
     * their own slot. Returns 0 (fallback) if unresolvable or the array is full.
     */
    public int slotForAtlas(Identifier atlasLocation) {
        if (atlasLocation == null) {
            return 0;
        }
        Integer cached = atlasSlotCache.get(atlasLocation);
        if (cached != null) {
            return cached;
        }
        long view = 0L;
        try {
            GpuTextureView v = Minecraft.getInstance().getTextureManager().getTexture(atlasLocation).getTextureView();
            view = vkImageView(v);
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                CausticaMod.LOGGER.warn("RT atlas texture resolution failed for {}", atlasLocation, t);
            }
        }
        int slot = slotForView(view);
        atlasSlotCache.put(atlasLocation, slot);
        return slot;
    }

    /**
     * Like {@link #slotForAtlas}, but for a <b>block-entity</b> sprite atlas (chest/sign/bed/…): also binds
     * that atlas's parallel LabPBR {@code _s}/{@code _n} ({@link RtEntityMaterials}) into bindless bindings
     * 1/2 at the same slot, so the hit shader's per-type material path samples them at the captured atlas
     * UV. Bound once per slot (the parallel-atlas view handle is stable; later sprite blits flush into the
     * same texture). Retried each frame until the source atlas is ready. Block-atlas geometry uses the
     * fixed terrain atlases instead, so callers route the block atlas elsewhere — never here.
     */
    public int slotForBlockEntityAtlas(Identifier atlasLocation) {
        int slot = slotForAtlas(atlasLocation);
        if (entityPbr() && slot > 0 && !atlasMaterialBound.contains(slot)) {
            RtParallelAtlas pa = RtEntityMaterials.INSTANCE.atlasFor(atlasLocation);
            if (pa != null) {
                atlasMaterialBound.add(slot);
                long nView = pa.viewN();
                long sView = pa.viewS();
                if (nView != 0L) {
                    pending.add(new Pending(1, slot, nView));
                }
                if (sView != 0L) {
                    pending.add(new Pending(2, slot, sView));
                }
            }
        }
        return slot;
    }

    /**
     * Map a resolved image-view handle to a stable bindless slot, allocating one on first sight (queued
     * for upload via {@link #uploadPending}). Returns 0 (fallback) when the view is unresolved or the
     * array is full. Deduping by handle is what bounds slot use: distinct render types backed by the same
     * texture share a slot instead of each consuming one.
     */
    private int slotForView(long view) {
        if (view == 0L) {
            return 0;
        }
        Integer cached = viewSlotCache.get(view);
        if (cached != null) {
            return cached;
        }
        if (nextSlot >= slotLimit()) {
            return 0;
        }
        int slot = nextSlot++;
        viewSlotCache.put(view, slot);
        pending.add(new Pending(0, slot, view)); // binding 0 = albedo
        return slot;
    }

    /** Write any newly-registered entity textures into the pipeline's bindless set (before the trace). */
    public void uploadPending(RtPipeline pipeline, long sampler) {
        if (pending.isEmpty()) {
            return;
        }
        for (Pending p : pending) {
            pipeline.setBindlessTexture(p.binding(), p.slot(), p.view(), sampler);
        }
        pending.clear();
    }

    /** Drop the registry (call when the world pipeline / bindless set is recreated, or textures reload). */
    public void reset() {
        reset(maxTextures());
    }

    /** Drop the registry for a pipeline whose bindless descriptor arrays have this capacity. */
    public void reset(int descriptorCapacity) {
        reset(descriptorCapacity, 0);
    }

    /** Reset while reserving the highest slots for canonical block-material page bundles. */
    public void reset(int descriptorCapacity, int reservedTailSlots) {
        capacity = Math.max(1, descriptorCapacity);
        this.reservedTailSlots = Math.max(0, Math.min(capacity - 1, reservedTailSlots));
        viewCache.clear();
        viewSlotCache.clear();
        atlasSlotCache.clear();
        atlasSlotCache.put(TextureAtlas.LOCATION_BLOCKS, 0); // block atlas = the slot-0 fallback
        atlasMaterialBound.clear();
        RtEntityMaterials.INSTANCE.reset(); // block-entity parallel _s/_n atlases are slot-bound → rebuild in lockstep
        pending.clear();
        nextSlot = 1;
        for (DynamicTexture dt : materialCache.values()) {
            if (dt != null) {
                dt.close();
            }
        }
        materialCache.clear();
        slotHasN = new boolean[capacity];
        slotHasS = new boolean[capacity];
        materialResolved = new boolean[capacity];
    }

    private int slotLimit() {
        return Math.min(capacity - reservedTailSlots, maxTextures());
    }

    /**
     * The {@code _n}/{@code _s} sibling of an entity texture, loaded as a {@link DynamicTexture} and cached
     * by Identifier (a null cache entry marks a known-missing file). Returns its vk image-view handle, or 0
     * if the resource doesn't exist / can't load.
     */
    private long loadMaterialView(Identifier albedoLoc, String suffix) {
        String path = albedoLoc.getPath();
        String base = path.endsWith(".png") ? path.substring(0, path.length() - 4) : path;
        Identifier loc = Identifier.fromNamespaceAndPath(albedoLoc.getNamespace(), base + suffix + ".png");
        if (materialCache.containsKey(loc)) {
            DynamicTexture cached = materialCache.get(loc);
            return cached != null ? vkImageView(cached.getTextureView()) : 0L;
        }
        DynamicTexture dt = null;
        try {
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(loc);
            if (res.isPresent()) {
                try (InputStream in = res.get().open()) {
                    // DynamicTexture takes ownership of the NativeImage (closes it) + uploads immediately.
                    dt = new DynamicTexture(loc::toString, NativeImage.read(in));
                }
            }
        } catch (Throwable t) {
            warnMaterialOnce("RT entity material load failed for " + loc, t);
        }
        materialCache.put(loc, dt); // null = known-missing
        return dt != null ? vkImageView(dt.getTextureView()) : 0L;
    }

    /** Recover the resource Identifier of {@code renderType}'s primary texture (Sampler0), or null. The
     *  {@code RenderSetup.TextureBinding} class is package-private, so {@code location()} is reflective. */
    private Identifier textureLocation(RenderType renderType) {
        try {
            // RenderSetup is final, so the accessor cast must go through Object (the interface is only
            // mixed in at runtime); RenderType is non-final so its cast is fine directly.
            Object setup = ((RenderTypeAccessor) renderType).caustica$state();
            Map<String, ?> textures = ((RenderSetupAccessor) setup).caustica$textures();
            Object binding = textures.get("Sampler0");
            if (binding == null) {
                return null;
            }
            if (locationMethod == null) {
                locationMethod = binding.getClass().getMethod("location");
                locationMethod.setAccessible(true);
            }
            return (Identifier) locationMethod.invoke(binding);
        } catch (Throwable t) {
            warnMaterialOnce("RT entity texture Identifier resolution failed for " + renderType, t);
            return null;
        }
    }

    private void warnMaterialOnce(String msg, Throwable t) {
        if (!loggedMaterialFailure) {
            loggedMaterialFailure = true;
            CausticaMod.LOGGER.warn(msg, t);
        }
    }

    /** The Vulkan image-view handle of {@code renderType}'s primary texture, or 0 if it can't be resolved. */
    public long resolveView(RenderType renderType) {
        if (renderType == null) {
            return 0L;
        }
        Long cached = viewCache.get(renderType);
        if (cached != null) {
            return cached;
        }
        long handle = 0L;
        try {
            PreparedRenderType prepared = renderType.prepare();
            GpuTextureView chosen = null;
            GpuTextureView firstNonAux = null;
            for (PreparedRenderType.Texture t : prepared.textures()) {
                String name = t.name();
                if ("Sampler0".equals(name)) {
                    chosen = t.textureView();
                    break;
                }
                if (firstNonAux == null && !"Sampler1".equals(name) && !"Sampler2".equals(name)) {
                    firstNonAux = t.textureView();
                }
            }
            if (chosen == null) {
                chosen = firstNonAux;
            }
            if (chosen != null) {
                handle = vkImageView(chosen);
            }
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                CausticaMod.LOGGER.warn("RT entity texture resolution failed for {}", renderType, t);
            }
        }
        viewCache.put(renderType, handle);
        return handle;
    }

    private static long vkImageView(GpuTextureView view) {
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        return 0L;
    }
}
