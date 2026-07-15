package dev.comfyfluffy.caustica.rt.material;

import com.mojang.blaze3d.platform.NativeImage;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.SpriteContentsAccessor;
import dev.comfyfluffy.caustica.mixin.TextureAtlasAccessor;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.ARGB;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Compiles block LabPBR inputs into compact canonical pages with explicit semantic mip chains. */
public final class RtBlockMaterials {
    public static final RtBlockMaterials INSTANCE = new RtBlockMaterials();

    public static final int HAS_S = 1;
    public static final int HAS_N = 2;

    private static final int DEFAULT_PAGE_SIZE = 2048;
    private static final int MAX_PAGE_SIZE = 8192;
    private static final int GUTTER = 8;
    private static final int MAX_VALID_LOD = 3;
    private static final int PACK_ALIGNMENT = 1 << MAX_VALID_LOD;

    private final Map<TextureAtlasSprite, Entry> entries = new IdentityHashMap<>();
    private final List<Page> pages = new ArrayList<>();
    private Entry fallback;
    private boolean loggedFailure;

    private RtBlockMaterials() {
    }

    /** Immutable per-sprite page mapping consumed by the material registry. */
    public record Entry(int features, int textureSlot, int maxLod,
                        float materialU, float materialV, float materialDu, float materialDv,
                        float albedoU, float albedoV, float albedoInvDu, float albedoInvDv) {
    }

    private record Page(RtMaterialPageTexture surface0, RtMaterialPageTexture normalAo,
                        RtMaterialPageTexture surface1, int textureSlot) {
        void destroy() {
            surface0.destroy();
            normalAo.destroy();
            surface1.destroy();
        }
    }

    private static final class Candidate {
        final TextureAtlasSprite sprite;
        final int features;
        final Identifier specLocation;
        final Identifier normalLocation;
        final int width;
        final int height;
        int page;
        int x;
        int y;

        Candidate(TextureAtlasSprite sprite, int features, Identifier specLocation,
                  Identifier normalLocation, int width, int height) {
            this.sprite = sprite;
            this.features = features;
            this.specLocation = specLocation;
            this.normalLocation = normalLocation;
            this.width = width;
            this.height = height;
        }
    }

    private static final class LayoutPage {
        final int size;
        int x;
        int y;
        int rowHeight;

        LayoutPage(int size, boolean reserveFallback) {
            this.size = size;
            if (reserveFallback) y = align(1 + 2 * GUTTER, PACK_ALIGNMENT);
        }

        boolean place(Candidate candidate) {
            int cellWidth = align(candidate.width + 2 * GUTTER, PACK_ALIGNMENT);
            int cellHeight = align(candidate.height + 2 * GUTTER, PACK_ALIGNMENT);
            if (cellWidth > size || cellHeight > size) return false;
            if (x + cellWidth > size) {
                x = 0;
                y += rowHeight;
                rowHeight = 0;
            }
            if (y + cellHeight > size) return false;
            candidate.x = x + GUTTER;
            candidate.y = y + GUTTER;
            x += cellWidth;
            rowHeight = Math.max(rowHeight, cellHeight);
            return true;
        }
    }

    /** Drop the previous epoch's CPU mappings and GPU pages. Caller owns the idle/reload boundary. */
    public void reset() {
        entries.clear();
        fallback = null;
        for (Page page : pages) page.destroy();
        pages.clear();
    }

    /** Compile, pack, mip, upload, and publish every block-atlas material page. */
    public void prepareAll(RtContext ctx, int descriptorCapacity) {
        List<TextureAtlasSprite> sprites = blockSprites();
        List<Candidate> authored = new ArrayList<>();
        int specCount = 0;
        int normalCount = 0;
        int largest = 1 + 2 * GUTTER;
        for (TextureAtlasSprite sprite : sprites) {
            if (sprite == null) continue;
            Identifier name = sprite.contents().name();
            Identifier spec = sibling(name, "_s.png");
            Identifier normal = sibling(name, "_n.png");
            int features = 0;
            if (resourceExists(spec)) {
                features |= HAS_S;
                specCount++;
            }
            if (resourceExists(normal)) {
                features |= HAS_N;
                normalCount++;
            }
            if (features != 0) {
                int width = sprite.contents().width();
                int height = sprite.contents().height();
                largest = Math.max(largest, Math.max(width, height) + 2 * GUTTER);
                authored.add(new Candidate(sprite, features, spec, normal, width, height));
            }
        }

        int pageSize = authored.isEmpty() ? 32 : Math.max(DEFAULT_PAGE_SIZE, nextPowerOfTwo(largest));
        if (pageSize > MAX_PAGE_SIZE) {
            CausticaMod.LOGGER.warn("RT material sprite exceeds canonical page limit ({} > {}); oversized maps use neutral fallback",
                    pageSize, MAX_PAGE_SIZE);
            pageSize = MAX_PAGE_SIZE;
            authored.removeIf(candidate -> candidate.width + 2 * GUTTER > MAX_PAGE_SIZE
                    || candidate.height + 2 * GUTTER > MAX_PAGE_SIZE);
            if (authored.isEmpty()) pageSize = 32;
        }
        authored.sort(Comparator.<Candidate>comparingInt(candidate -> candidate.height).reversed()
                .thenComparing(Comparator.comparingInt((Candidate candidate) -> candidate.width).reversed())
                .thenComparing(candidate -> candidate.sprite.contents().name().toString()));

        List<LayoutPage> layouts = new ArrayList<>();
        layouts.add(new LayoutPage(pageSize, true));
        for (Candidate candidate : authored) {
            boolean placed = false;
            for (int i = 0; i < layouts.size(); i++) {
                if (layouts.get(i).place(candidate)) {
                    candidate.page = i;
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                LayoutPage page = new LayoutPage(pageSize, false);
                if (!page.place(candidate)) continue;
                candidate.page = layouts.size();
                layouts.add(page);
            }
        }
        if (layouts.size() >= descriptorCapacity) {
            throw new IllegalStateException("RT material pages require " + layouts.size()
                    + " bindless slots but capacity is " + descriptorCapacity);
        }

        int mipCount = Integer.numberOfTrailingZeros(pageSize) + 1;
        for (int pageIndex = 0; pageIndex < layouts.size(); pageIndex++) {
            int textureSlot = descriptorCapacity - 1 - pageIndex;
            PagePixels pixels = new PagePixels(pageSize, mipCount);
            if (pageIndex == 0) pixels.writeFallback();
            for (Candidate candidate : authored) {
                if (candidate.page != pageIndex) continue;
                try {
                    List<RtMaterialTextureData.Level> levels = decode(candidate);
                    pixels.write(candidate, levels);
                } catch (Throwable t) {
                    warnOnce("RT canonical material decode failed for " + candidate.sprite.contents().name(), t);
                    candidate.page = -1;
                }
            }
            pages.add(new Page(
                    new RtMaterialPageTexture(ctx, pageSize, pageSize, pixels.surface0,
                            "material surface0 page " + pageIndex),
                    new RtMaterialPageTexture(ctx, pageSize, pageSize, pixels.normalAo,
                            "material normalAo page " + pageIndex),
                    new RtMaterialPageTexture(ctx, pageSize, pageSize, pixels.surface1,
                            "material surface1 page " + pageIndex),
                    textureSlot));
        }

        int fallbackSlot = pages.get(0).textureSlot;
        float fallbackUv = GUTTER / (float) pageSize;
        fallback = new Entry(0, fallbackSlot, 0, fallbackUv, fallbackUv,
                1.0f / pageSize, 1.0f / pageSize, 0, 0, 1, 1);
        for (TextureAtlasSprite sprite : sprites) {
            if (sprite != null) entries.put(sprite, fallbackFor(sprite));
        }
        for (Candidate candidate : authored) {
            if (candidate.page < 0) continue;
            int maxLod = Math.min(MAX_VALID_LOD,
                    31 - Integer.numberOfLeadingZeros(Math.max(candidate.width, candidate.height)));
            int slot = pages.get(candidate.page).textureSlot;
            entries.put(candidate.sprite, new Entry(candidate.features, slot, maxLod,
                    candidate.x / (float) pageSize, candidate.y / (float) pageSize,
                    candidate.width / (float) pageSize, candidate.height / (float) pageSize,
                    candidate.sprite.getU0(), candidate.sprite.getV0(),
                    inverseExtent(candidate.sprite.getU1() - candidate.sprite.getU0()),
                    inverseExtent(candidate.sprite.getV1() - candidate.sprite.getV0())));
        }

        long bytesPerBundle = 0L;
        int w = pageSize;
        for (int mip = 0; mip < mipCount; mip++) {
            bytesPerBundle += (long) w * w * 4L;
            w = Math.max(1, w / 2);
        }
        CausticaMod.LOGGER.info("RT canonical material pages: sprites={}, spec={}, normal={}, pages={}x{}², validLod<={}, gpuMiB={}",
                sprites.size(), specCount, normalCount, pages.size(), pageSize, MAX_VALID_LOD,
                String.format(java.util.Locale.ROOT, "%.2f", bytesPerBundle * pages.size() * 3.0 / (1024.0 * 1024.0)));
    }

    public void bindPages(RtPipeline pipeline, long sampler) {
        for (Page page : pages) {
            pipeline.setBindlessTexture(0, page.textureSlot, page.surface1.view(), sampler);
            pipeline.setBindlessTexture(1, page.textureSlot, page.normalAo.view(), sampler);
            pipeline.setBindlessTexture(2, page.textureSlot, page.surface0.view(), sampler);
        }
    }

    public int pageCount() {
        return pages.size();
    }

    public Entry entry(TextureAtlasSprite sprite) {
        return entries.getOrDefault(sprite, fallback);
    }

    public Map<TextureAtlasSprite, Entry> preparedEntries() {
        return Collections.unmodifiableMap(new IdentityHashMap<>(entries));
    }

    public void destroy() {
        reset();
    }

    private Entry fallbackFor(TextureAtlasSprite sprite) {
        return new Entry(0, fallback.textureSlot, 0,
                fallback.materialU, fallback.materialV, fallback.materialDu, fallback.materialDv,
                sprite.getU0(), sprite.getV0(), inverseExtent(sprite.getU1() - sprite.getU0()),
                inverseExtent(sprite.getV1() - sprite.getV0()));
    }

    private static List<RtMaterialTextureData.Level> decode(Candidate candidate) throws Exception {
        NativeImage spec = (candidate.features & HAS_S) != 0 ? load(candidate.specLocation) : null;
        NativeImage normal = (candidate.features & HAS_N) != 0 ? load(candidate.normalLocation) : null;
        try {
            int width = candidate.width;
            int height = candidate.height;
            float[] surface0 = new float[width * height * 4];
            float[] normalAo = new float[surface0.length];
            float[] surface1 = new float[surface0.length];
            NativeImage albedo = ((SpriteContentsAccessor) candidate.sprite.contents()).caustica$originalImage();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = (y * width + x) * 4;
                    int albedoPixel = albedo != null ? albedo.getPixel(x, y) : -1;
                    float ar = srgbToLinear(ARGB.red(albedoPixel) / 255.0f);
                    float ag = srgbToLinear(ARGB.green(albedoPixel) / 255.0f);
                    float ab = srgbToLinear(ARGB.blue(albedoPixel) / 255.0f);
                    if (spec != null) {
                        int pixel = sample(spec, x, y, width, height);
                        RtLabPbr.Specular decoded = RtLabPbr.decodeSpec(
                                ARGB.red(pixel) / 255.0f, ARGB.green(pixel) / 255.0f,
                                ARGB.blue(pixel) / 255.0f, ARGB.alpha(pixel) / 255.0f, ar, ag, ab);
                        surface0[i] = decoded.roughness();
                        surface0[i + 1] = decoded.metalness();
                        surface0[i + 2] = decoded.emission();
                        surface0[i + 3] = decoded.sss();
                        surface1[i] = decoded.f0r();
                        surface1[i + 1] = decoded.f0g();
                        surface1[i + 2] = decoded.f0b();
                    } else {
                        surface0[i] = 1.0f;
                        surface1[i] = surface1[i + 1] = surface1[i + 2] = 0.04f;
                    }
                    if (normal != null) {
                        int pixel = sample(normal, x, y, width, height);
                        float nx = ARGB.red(pixel) / 127.5f - 1.0f;
                        float ny = ARGB.green(pixel) / 127.5f - 1.0f;
                        float lengthSq = nx * nx + ny * ny;
                        if (lengthSq > 1.0f) {
                            float invLength = 1.0f / (float) Math.sqrt(lengthSq);
                            nx *= invLength;
                            ny *= invLength;
                        }
                        normalAo[i] = nx * 0.5f + 0.5f;
                        normalAo[i + 1] = ny * 0.5f + 0.5f;
                        normalAo[i + 2] = ARGB.blue(pixel) / 255.0f;
                        normalAo[i + 3] = ARGB.alpha(pixel) / 255.0f;
                    } else {
                        normalAo[i] = normalAo[i + 1] = 0.5f;
                        normalAo[i + 2] = 1.0f;
                    }
                }
            }
            int maxLod = Math.min(MAX_VALID_LOD,
                    31 - Integer.numberOfLeadingZeros(Math.max(width, height)));
            return RtMaterialTextureData.mipChain(new RtMaterialTextureData.Level(width, height,
                    surface0, normalAo, surface1), maxLod);
        } finally {
            if (spec != null) spec.close();
            if (normal != null) normal.close();
        }
    }

    private static final class PagePixels {
        final List<byte[]> surface0;
        final List<byte[]> normalAo;
        final List<byte[]> surface1;
        final int pageSize;

        PagePixels(int pageSize, int mipCount) {
            this.pageSize = pageSize;
            surface0 = allocate(pageSize, mipCount, 255, 0, 0, 0);
            normalAo = allocate(pageSize, mipCount, 128, 128, 255, 0);
            surface1 = allocate(pageSize, mipCount, 10, 10, 10, 0);
        }

        void writeFallback() {
            // Neutral arrays already contain the fallback texel and its replicated surroundings.
        }

        void write(Candidate candidate, List<RtMaterialTextureData.Level> levels) {
            for (int mip = 0; mip < levels.size(); mip++) {
                RtMaterialTextureData.Level level = levels.get(mip);
                int width = Math.max(1, pageSize >> mip);
                int cx = candidate.x >> mip;
                int cy = candidate.y >> mip;
                int gutter = Math.max(1, GUTTER >> mip);
                blit(surface0.get(mip), width, cx, cy, gutter, level.width(), level.height(), level.surface0());
                blit(normalAo.get(mip), width, cx, cy, gutter, level.width(), level.height(), level.normalAo());
                blit(surface1.get(mip), width, cx, cy, gutter, level.width(), level.height(), level.surface1());
            }
        }

        private static List<byte[]> allocate(int size, int mipCount, int r, int g, int b, int a) {
            List<byte[]> result = new ArrayList<>(mipCount);
            int width = size;
            for (int mip = 0; mip < mipCount; mip++) {
                byte[] values = new byte[width * width * 4];
                for (int i = 0; i < values.length; i += 4) {
                    values[i] = (byte) r;
                    values[i + 1] = (byte) g;
                    values[i + 2] = (byte) b;
                    values[i + 3] = (byte) a;
                }
                result.add(values);
                width = Math.max(1, width / 2);
            }
            return result;
        }

        private static void blit(byte[] dst, int dstWidth, int cx, int cy, int gutter,
                                 int srcWidth, int srcHeight, float[] src) {
            for (int dy = -gutter; dy < srcHeight + gutter; dy++) {
                int sy = Math.max(0, Math.min(srcHeight - 1, dy));
                int ty = cy + dy;
                if (ty < 0 || ty >= dstWidth) continue;
                for (int dx = -gutter; dx < srcWidth + gutter; dx++) {
                    int sx = Math.max(0, Math.min(srcWidth - 1, dx));
                    int tx = cx + dx;
                    if (tx < 0 || tx >= dstWidth) continue;
                    int si = (sy * srcWidth + sx) * 4;
                    int di = (ty * dstWidth + tx) * 4;
                    dst[di] = (byte) RtMaterialTextureData.unorm8(src[si]);
                    dst[di + 1] = (byte) RtMaterialTextureData.unorm8(src[si + 1]);
                    dst[di + 2] = (byte) RtMaterialTextureData.unorm8(src[si + 2]);
                    dst[di + 3] = (byte) RtMaterialTextureData.unorm8(src[si + 3]);
                }
            }
        }
    }

    private static List<TextureAtlasSprite> blockSprites() {
        TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS);
        List<TextureAtlasSprite> sprites = ((TextureAtlasAccessor) atlas).caustica$sprites();
        return sprites != null ? sprites : List.of();
    }

    private static Identifier sibling(Identifier name, String suffix) {
        return Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + suffix);
    }

    private static boolean resourceExists(Identifier location) {
        return Minecraft.getInstance().getResourceManager().getResource(location).isPresent();
    }

    private static NativeImage load(Identifier location) throws Exception {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) return null;
        try (InputStream input = resource.get().open()) {
            return NativeImage.read(input);
        }
    }

    private static int sample(NativeImage image, int x, int y, int width, int height) {
        int frameHeight = Math.min(image.getHeight(), image.getWidth());
        int sx = Math.min(image.getWidth() - 1, x * image.getWidth() / width);
        int sy = Math.min(frameHeight - 1, y * frameHeight / height);
        return image.getPixel(sx, sy);
    }

    private static float srgbToLinear(float value) {
        return value <= 0.04045f ? value / 12.92f
                : (float) Math.pow((value + 0.055f) / 1.055f, 2.4f);
    }

    private static float inverseExtent(float extent) {
        return Math.abs(extent) > 1.0e-12f ? 1.0f / extent : 0.0f;
    }

    private static int nextPowerOfTwo(int value) {
        if (value <= 1) return 1;
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) & -alignment;
    }

    private void warnOnce(String message, Throwable throwable) {
        if (!loggedFailure) {
            loggedFailure = true;
            CausticaMod.LOGGER.warn(message, throwable);
        }
    }
}
