package dev.comfyfluffy.caustica.renderer.raytracing;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.PackedPathSegmentData;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.PackedStablePlaneData;
import org.lwjgl.vulkan.VK10;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;

import java.util.ArrayList;
import java.util.List;

/** Owns extent-keyed trace images and stable-plane scratch. */
public final class TraceResources {
    private static final int PATH_RECORDS_PER_PIXEL = 3;

    private TraceExtent extent;
    private TraceImages images;
    private GpuBuffer stablePlaneBuffer;
    private GpuBuffer pathScratchBuffer;
    private final List<Runnable> releases = new ArrayList<>();

    public boolean hasDisplayExtent(int width, int height) {
        return extent != null && extent.displayWidth() == width && extent.displayHeight() == height;
    }

    public TraceExtent extent() {
        if (extent == null) {
            throw new IllegalStateException("Trace resources are not sized");
        }
        return extent;
    }

    public TraceImages images() {
        if (images == null) {
            throw new IllegalStateException("Trace resources are not sized");
        }
        return images;
    }

    public GpuBuffer stablePlaneBuffer() {
        if (stablePlaneBuffer == null) {
            throw new IllegalStateException("Trace resources are not sized");
        }
        return stablePlaneBuffer;
    }

    /** Replaces the allocation after the caller has drained prior GPU use. */
    public void resize(VulkanDeviceContext context, TraceExtent wanted) {
        destroy();
        try {
            allocate(context, wanted);
        } catch (RuntimeException | Error failure) {
            try { destroy(); }
            catch (RuntimeException | Error cleanup) {
                if (failure != cleanup) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void allocate(VulkanDeviceContext context, TraceExtent wanted) {
        int renderWidth = wanted.renderWidth();
        int renderHeight = wanted.renderHeight();
        int displayWidth = wanted.displayWidth();
        int displayHeight = wanted.displayHeight();

        GpuImage traceColor = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "trace color " + renderWidth + "x" + renderHeight);
        GpuImage stablePlaneMetadata = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "stable plane metadata " + renderWidth + "x" + renderHeight);
        GpuImage normalRoughness = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide normal roughness " + renderWidth + "x" + renderHeight);
        GpuImage diffuseAlbedo = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide diffuse albedo " + renderWidth + "x" + renderHeight);
        GpuImage depth = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R32_SFLOAT, "guide reverse depth " + renderWidth + "x" + renderHeight);
        GpuImage primaryDepth = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R32_SFLOAT, "primary reverse depth " + renderWidth + "x" + renderHeight);
        GpuImage motion = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16_SFLOAT, "guide motion " + renderWidth + "x" + renderHeight);
        GpuImage specularAlbedo = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide specular albedo " + renderWidth + "x" + renderHeight);
        GpuImage specularMotion = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16_SFLOAT, "guide specular motion " + renderWidth + "x" + renderHeight);
        GpuImage responsivity = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R16_SFLOAT, "guide responsivity " + renderWidth + "x" + renderHeight);
        GpuImage diffuseRadianceHitDistance = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "NRD diffuse radiance hit distance " + renderWidth + "x" + renderHeight);
        GpuImage specularRadianceHitDistance = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "NRD specular radiance hit distance " + renderWidth + "x" + renderHeight);
        GpuImage nrdViewZ = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R32_SFLOAT, "NRD view Z " + renderWidth + "x" + renderHeight);
        GpuImage nrdMotion = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "NRD 2.5D motion " + renderWidth + "x" + renderHeight);
        GpuImage nrdDisocclusionThresholdMix = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R16_SFLOAT,
                "NRD disocclusion threshold mix " + renderWidth + "x" + renderHeight);
        GpuImage denoisedDiffuseRadianceHitDistance = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "NRD denoised diffuse radiance hit distance " + renderWidth + "x" + renderHeight);
        GpuImage denoisedSpecularRadianceHitDistance = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "NRD denoised specular radiance hit distance " + renderWidth + "x" + renderHeight);
        GpuImage nrdStableRadiance = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "NRD stable radiance " + renderWidth + "x" + renderHeight);
        GpuImage reconstructedColor = createImage(context, displayWidth, displayHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "reconstruction output " + displayWidth + "x" + displayHeight);
        GpuImage sceneEffectColorA = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "scene effect color A");
        GpuImage sceneEffectColorB = createImage(context, renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "scene effect color B");
        stablePlaneBuffer = context.createBuffer(stablePlaneBytes(renderWidth, renderHeight),
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                false, "stable planes " + renderWidth + "x" + renderHeight + "x3");
        releases.add(stablePlaneBuffer::destroy);
        pathScratchBuffer = context.createBuffer(pathScratchBytes(renderWidth, renderHeight),
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                false, "stable plane path scratch " + renderWidth + "x" + renderHeight
                        + "x" + PATH_RECORDS_PER_PIXEL);
        releases.add(pathScratchBuffer::destroy);
        images = new TraceImages(traceColor, stablePlaneMetadata,
                normalRoughness, diffuseAlbedo, depth, primaryDepth, motion,
                specularAlbedo, specularMotion, responsivity, diffuseRadianceHitDistance,
                specularRadianceHitDistance, nrdViewZ, nrdMotion, nrdDisocclusionThresholdMix,
                denoisedDiffuseRadianceHitDistance,
                denoisedSpecularRadianceHitDistance, nrdStableRadiance, reconstructedColor,
                sceneEffectColorA, sceneEffectColorB);
        extent = wanted;
    }

    /** Reused by the ordered graphics queue; resizing requires drained GPU use. */
    public GpuBuffer pathScratchBuffer() {
        return pathScratchBuffer;
    }

    public void destroy() {
        var lifetime = new ResourceLifetime(releases.toArray(Runnable[]::new));
        releases.clear();
        images = null;
        stablePlaneBuffer = null;
        pathScratchBuffer = null;
        extent = null;
        lifetime.close();
    }

    private GpuImage createImage(VulkanDeviceContext context, int width, int height, int format, String label) {
        GpuImage image = context.createStorageImage(width, height, format, label);
        releases.add(image::destroy);
        return image;
    }

    static long pathScratchBytes(int width, int height) {
        long pixels = Math.multiplyExact((long) width, (long) height);
        return Math.multiplyExact(Math.multiplyExact(pixels, PATH_RECORDS_PER_PIXEL),
                PackedPathSegmentData.BYTE_SIZE);
    }

    static long stablePlaneBytes(int width, int height) {
        long pixels = Math.multiplyExact((long) width, (long) height);
        return Math.multiplyExact(Math.multiplyExact(pixels, 3L), PackedStablePlaneData.BYTE_SIZE);
    }
}
