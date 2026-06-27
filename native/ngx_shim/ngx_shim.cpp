// Flat C ABI shim over the NVIDIA NGX DLSS Vulkan API.
//
// The NGX SDK ships only as a static library (nvsdk_ngx_d.lib) plus a C++/macro
// helper layer that fiddles with parameter blocks and resource structs. Java's
// FFM can only bind a clean flat-C ABI, so this tiny DLL links the static lib,
// uses the helpers internally, and exposes ~10 primitive-argument functions.
//
// Built as a SHARED library; every exported symbol is undecorated extern "C".

#include <vulkan/vulkan.h>

#include "nvsdk_ngx.h"
#include "nvsdk_ngx_vk.h"
#include "nvsdk_ngx_helpers.h"
#include "nvsdk_ngx_helpers_vk.h"
#include "nvsdk_ngx_helpers_dlssd.h"
#include "nvsdk_ngx_helpers_dlssd_vk.h"

#include <cstring>
#include <cstdlib>

// A fixed GUID-like project id (NGX requires GUID-like ids for CUSTOM engine).
static const char* kProjectId = "b6f1e9c2-7a44-4d1e-9b3a-1f2c3d4e5a6b";

static NVSDK_NGX_Parameter* g_capabilityParams = nullptr;
static VkDevice g_device = VK_NULL_HANDLE;
static int g_lastResult = 0;

struct DlssFeature {
    NVSDK_NGX_Handle* handle;
    NVSDK_NGX_Parameter* params;
    bool ownsParams; // false when params is the shared capability block (DLSS-RR), so release leaves it
};

static NVSDK_NGX_Resource_VK makeImageResource(VkImageView view, VkImage image, int format,
                                               unsigned int width, unsigned int height,
                                               VkImageAspectFlags aspect, bool readWrite) {
    VkImageSubresourceRange range;
    std::memset(&range, 0, sizeof(range));
    range.aspectMask = aspect;
    range.baseMipLevel = 0;
    range.levelCount = 1;
    range.baseArrayLayer = 0;
    range.layerCount = 1;
    return NVSDK_NGX_Create_ImageView_Resource_VK(view, image, range, (VkFormat) format, width, height, readWrite);
}

extern "C" {

#if defined(_WIN32)
#define NGX_SHIM_EXPORT __declspec(dllexport)
#else
#define NGX_SHIM_EXPORT __attribute__((visibility("default")))
#endif

// Last NVSDK_NGX_Result observed, for diagnostics from the Java side.
NGX_SHIM_EXPORT int ngxshim_last_result() {
    return g_lastResult;
}

// Required Vulkan extensions for NGX (deprecated-but-simple API; needs no device
// or prior init, so it is callable at mod-init before device creation). Writes
// the names newline-joined into outBuf. wantDevice != 0 -> device extensions,
// else instance extensions. Returns the count, or -1 on failure.
NGX_SHIM_EXPORT int ngxshim_required_extensions(int wantDevice, char* outBuf, int bufLen) {
    unsigned int instanceCount = 0, deviceCount = 0;
    const char** instanceExts = nullptr;
    const char** deviceExts = nullptr;

    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_RequiredExtensions(&instanceCount, &instanceExts, &deviceCount, &deviceExts);
    g_lastResult = (int) r;
    if (NVSDK_NGX_FAILED(r)) {
        return -1;
    }

    unsigned int count = wantDevice ? deviceCount : instanceCount;
    const char** exts = wantDevice ? deviceExts : instanceExts;

    int pos = 0;
    for (unsigned int i = 0; i < count && exts; i++) {
        int n = (int) std::strlen(exts[i]);
        if (pos + n + 1 >= bufLen) {
            break;
        }
        std::memcpy(outBuf + pos, exts[i], n);
        pos += n;
        outBuf[pos++] = '\n';
    }
    if (pos < bufLen) {
        outBuf[pos] = 0;
    }
    return (int) count;
}

// Initializes NGX against the live device. featureDllPath is the directory that
// contains nvngx_dlss.dll (added to the NGX feature DLL search paths).
NGX_SHIM_EXPORT int ngxshim_init(unsigned long long appId, const wchar_t* dataPath,
                                 VkInstance instance, VkPhysicalDevice physicalDevice, VkDevice device,
                                 void* getInstanceProcAddr, void* getDeviceProcAddr,
                                 const wchar_t* featureDllPath) {
    g_device = device;

    NVSDK_NGX_FeatureCommonInfo info;
    std::memset(&info, 0, sizeof(info));
    const wchar_t* paths[1] = { featureDllPath };
    info.PathListInfo.Path = paths;
    info.PathListInfo.Length = featureDllPath ? 1u : 0u;

    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_Init_with_ProjectID(
            kProjectId, NVSDK_NGX_ENGINE_TYPE_CUSTOM, "1.0", dataPath,
            instance, physicalDevice, device,
            (PFN_vkGetInstanceProcAddr) getInstanceProcAddr,
            (PFN_vkGetDeviceProcAddr) getDeviceProcAddr,
            &info, NVSDK_NGX_Version_API);
    g_lastResult = (int) r;
    if (NVSDK_NGX_FAILED(r)) {
        return (int) r;
    }

    r = NVSDK_NGX_VULKAN_GetCapabilityParameters(&g_capabilityParams);
    g_lastResult = (int) r;
    return (int) r;
}

// 1 if DLSS Super Resolution is available on this system, else 0.
NGX_SHIM_EXPORT int ngxshim_dlss_available() {
    if (!g_capabilityParams) {
        return 0;
    }
    int available = 0;
    NVSDK_NGX_Parameter_GetI(g_capabilityParams, NVSDK_NGX_Parameter_SuperSampling_Available, &available);
    return available;
}

// Optimal render resolution + recommended sharpness for a display size and
// quality mode (NVSDK_NGX_PerfQuality_Value). Returns NVSDK_NGX_Result.
NGX_SHIM_EXPORT int ngxshim_query_optimal(unsigned int displayWidth, unsigned int displayHeight, int quality,
                                          unsigned int* outRenderWidth, unsigned int* outRenderHeight,
                                          float* outSharpness) {
    if (!g_capabilityParams) {
        return -1;
    }
    unsigned int maxW = 0, maxH = 0, minW = 0, minH = 0;
    NVSDK_NGX_Result r = NGX_DLSS_GET_OPTIMAL_SETTINGS(
            g_capabilityParams, displayWidth, displayHeight, (NVSDK_NGX_PerfQuality_Value) quality,
            outRenderWidth, outRenderHeight, &maxW, &maxH, &minW, &minH, outSharpness);
    g_lastResult = (int) r;
    return (int) r;
}

// Creates a DLSS feature. cmd must be an open, recording command buffer.
// renderPreset is an NVSDK_NGX_DLSS_Hint_Render_Preset value (0 = let the DLL
// choose its per-mode default; 11 = Preset K, the transformer model). Returns an
// opaque feature pointer, or NULL on failure (see ngxshim_last_result).
NGX_SHIM_EXPORT void* ngxshim_create_dlss(VkCommandBuffer cmd,
                                          unsigned int renderWidth, unsigned int renderHeight,
                                          unsigned int displayWidth, unsigned int displayHeight,
                                          int quality, int featureFlags, int renderPreset) {
    NVSDK_NGX_Parameter* params = nullptr;
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_AllocateParameters(&params);
    g_lastResult = (int) r;
    if (NVSDK_NGX_FAILED(r)) {
        return nullptr;
    }

    if (renderPreset != 0) {
        unsigned int preset = (unsigned int) renderPreset;
        NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA, preset);
        NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality, preset);
        NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced, preset);
        NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance, preset);
        NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance, preset);
        NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraQuality, preset);
    }

    NVSDK_NGX_DLSS_Create_Params createParams;
    std::memset(&createParams, 0, sizeof(createParams));
    createParams.Feature.InWidth = renderWidth;
    createParams.Feature.InHeight = renderHeight;
    createParams.Feature.InTargetWidth = displayWidth;
    createParams.Feature.InTargetHeight = displayHeight;
    createParams.Feature.InPerfQualityValue = (NVSDK_NGX_PerfQuality_Value) quality;
    createParams.InFeatureCreateFlags = featureFlags;

    NVSDK_NGX_Handle* handle = nullptr;
    r = NGX_VULKAN_CREATE_DLSS_EXT(cmd, 1, 1, &handle, params, &createParams);
    g_lastResult = (int) r;
    if (NVSDK_NGX_FAILED(r)) {
        NVSDK_NGX_VULKAN_DestroyParameters(params);
        return nullptr;
    }

    DlssFeature* feature = (DlssFeature*) std::malloc(sizeof(DlssFeature));
    feature->handle = handle;
    feature->params = params;
    feature->ownsParams = true; // allocated above; release destroys it
    return feature;
}

// Records a DLSS evaluation into cmd. All images are VkImageView+VkImage+VkFormat
// triples; depth uses the depth aspect, the rest use the color aspect; output is
// the only read-write (storage) resource.
NGX_SHIM_EXPORT int ngxshim_evaluate(VkCommandBuffer cmd, void* feature,
                                     VkImageView colorView, VkImage colorImage, int colorFormat,
                                     VkImageView depthView, VkImage depthImage, int depthFormat,
                                     VkImageView mvView, VkImage mvImage, int mvFormat,
                                     VkImageView outputView, VkImage outputImage, int outputFormat,
                                     unsigned int renderWidth, unsigned int renderHeight,
                                     unsigned int displayWidth, unsigned int displayHeight,
                                     float jitterX, float jitterY, float mvScaleX, float mvScaleY,
                                     int reset, float frameTimeMs) {
    DlssFeature* f = (DlssFeature*) feature;
    if (!f) {
        return -1;
    }

    NVSDK_NGX_Resource_VK color = makeImageResource(colorView, colorImage, colorFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, false);
    NVSDK_NGX_Resource_VK depth = makeImageResource(depthView, depthImage, depthFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_DEPTH_BIT, false);
    NVSDK_NGX_Resource_VK mv = makeImageResource(mvView, mvImage, mvFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, false);
    NVSDK_NGX_Resource_VK output = makeImageResource(outputView, outputImage, outputFormat, displayWidth, displayHeight, VK_IMAGE_ASPECT_COLOR_BIT, true);

    NVSDK_NGX_VK_DLSS_Eval_Params eval;
    std::memset(&eval, 0, sizeof(eval));
    eval.Feature.pInColor = &color;
    eval.Feature.pInOutput = &output;
    eval.pInDepth = &depth;
    eval.pInMotionVectors = &mv;
    eval.InJitterOffsetX = jitterX;
    eval.InJitterOffsetY = jitterY;
    eval.InMVScaleX = mvScaleX;
    eval.InMVScaleY = mvScaleY;
    eval.InReset = reset;
    eval.InRenderSubrectDimensions.Width = renderWidth;
    eval.InRenderSubrectDimensions.Height = renderHeight;
    eval.InFrameTimeDeltaInMsec = frameTimeMs;

    NVSDK_NGX_Result r = NGX_VULKAN_EVALUATE_DLSS_EXT(cmd, f->handle, f->params, &eval);
    g_lastResult = (int) r;
    return (int) r;
}

// 1 if DLSS Ray Reconstruction (denoising) is available on this system, else 0.
NGX_SHIM_EXPORT int ngxshim_dlssd_available() {
    if (!g_capabilityParams) {
        return 0;
    }
    int available = 0;
    NVSDK_NGX_Parameter_GetI(g_capabilityParams, NVSDK_NGX_Parameter_SuperSamplingDenoising_Available, &available);
    return available;
}

// Creates a DLSS Ray Reconstruction (DLSSD) feature. Configured for our path tracer: DL-unified
// denoise, roughness packed into normals.w, and linear depth (we feed view-distance, not HW depth).
// renderPreset is an NVSDK_NGX_RayReconstruction_Hint_Render_Preset value (0 = DLL default).
NGX_SHIM_EXPORT void* ngxshim_create_dlssd(VkCommandBuffer cmd,
                                           unsigned int renderWidth, unsigned int renderHeight,
                                           unsigned int displayWidth, unsigned int displayHeight,
                                           int quality, int featureFlags, int renderPreset) {
    // DLSS Ray Reconstruction must be created with the capability parameter block (it carries the
    // snippet/preset callbacks the feature needs); a fresh AllocateParameters block fails with
    // FAIL_InvalidParameter. The block is shared (freed at shutdown), so the feature does not own it.
    NVSDK_NGX_Parameter* params = g_capabilityParams;
    if (!params) {
        g_lastResult = (int) NVSDK_NGX_Result_FAIL_NotInitialized;
        return nullptr;
    }
    NVSDK_NGX_Result r = NVSDK_NGX_Result_Success;

    if (renderPreset != 0) {
        unsigned int preset = (unsigned int) renderPreset;
        NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_DLAA, preset);
        NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Quality, preset);
        NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Balanced, preset);
        NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Performance, preset);
        NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_UltraPerformance, preset);
        NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_UltraQuality, preset);
    }

    NVSDK_NGX_DLSSD_Create_Params createParams;
    std::memset(&createParams, 0, sizeof(createParams));
    createParams.InDenoiseMode = NVSDK_NGX_DLSS_Denoise_Mode_DLUnified;
    createParams.InRoughnessMode = NVSDK_NGX_DLSS_Roughness_Mode_Packed; // roughness from normals.w
    createParams.InUseHWDepth = NVSDK_NGX_DLSS_Depth_Type_Linear;        // we feed linear view depth
    createParams.InWidth = renderWidth;
    createParams.InHeight = renderHeight;
    createParams.InTargetWidth = displayWidth;
    createParams.InTargetHeight = displayHeight;
    // Native resolution (render == display) is the DLAA mode; the upscaling quality values (MaxQuality
    // etc.) imply a fixed render:display ratio and are rejected with FAIL_InvalidParameter at 1:1.
    createParams.InPerfQualityValue = (renderWidth == displayWidth && renderHeight == displayHeight)
            ? NVSDK_NGX_PerfQuality_Value_DLAA
            : (NVSDK_NGX_PerfQuality_Value) quality;
    createParams.InFeatureCreateFlags = featureFlags;

    NVSDK_NGX_Handle* handle = nullptr;
    r = NGX_VULKAN_CREATE_DLSSD_EXT1(g_device, cmd, 1, 1, &handle, params, &createParams);
    g_lastResult = (int) r;
    if (NVSDK_NGX_FAILED(r)) {
        return nullptr; // params is the shared capability block; do not destroy it
    }

    DlssFeature* feature = (DlssFeature*) std::malloc(sizeof(DlssFeature));
    feature->handle = handle;
    feature->params = params;
    feature->ownsParams = false; // shared capability block, freed at shutdown
    return feature;
}

// Records a DLSS Ray Reconstruction evaluation. Guide buffers: HDR color, linear depth, motion
// vectors, diffuse albedo, specular albedo, world-space normals (roughness packed in normals.w),
// and reflection motion vectors. Specular hit distance remains in the ABI/resources for debug and
// easy A/B, but is disabled by leaving pInSpecularHitDistance null.
// Output is the only read-write (storage) resource. All non-output images use the color aspect;
// depth is a linear value carried in a color image, not a depth-aspect attachment.
NGX_SHIM_EXPORT int ngxshim_evaluate_dlssd(VkCommandBuffer cmd, void* feature,
                                           VkImageView colorView, VkImage colorImage, int colorFormat,
                                           VkImageView depthView, VkImage depthImage, int depthFormat,
                                           VkImageView mvView, VkImage mvImage, int mvFormat,
                                           VkImageView diffuseAlbedoView, VkImage diffuseAlbedoImage, int diffuseAlbedoFormat,
                                            VkImageView specularAlbedoView, VkImage specularAlbedoImage, int specularAlbedoFormat,
                                            VkImageView normalsView, VkImage normalsImage, int normalsFormat,
                                            VkImageView specularMotionView, VkImage specularMotionImage, int specularMotionFormat,
                                            VkImageView specularHitDistanceView, VkImage specularHitDistanceImage, int specularHitDistanceFormat,
                                            VkImageView outputView, VkImage outputImage, int outputFormat,
                                           unsigned int renderWidth, unsigned int renderHeight,
                                           unsigned int displayWidth, unsigned int displayHeight,
                                           float jitterX, float jitterY, float mvScaleX, float mvScaleY,
                                           int reset, float frameTimeMs,
                                           float* worldToViewMatrix, float* viewToClipMatrix) {
    DlssFeature* f = (DlssFeature*) feature;
    if (!f) {
        return -1;
    }
    (void) specularHitDistanceView;
    (void) specularHitDistanceImage;
    (void) specularHitDistanceFormat;
    (void) worldToViewMatrix;
    (void) viewToClipMatrix;

    NVSDK_NGX_Resource_VK color = makeImageResource(colorView, colorImage, colorFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, false);
    NVSDK_NGX_Resource_VK depth = makeImageResource(depthView, depthImage, depthFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, false);
    NVSDK_NGX_Resource_VK mv = makeImageResource(mvView, mvImage, mvFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, false);
    NVSDK_NGX_Resource_VK diffuseAlbedo = makeImageResource(diffuseAlbedoView, diffuseAlbedoImage, diffuseAlbedoFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, false);
    NVSDK_NGX_Resource_VK specularAlbedo = makeImageResource(specularAlbedoView, specularAlbedoImage, specularAlbedoFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, false);
    NVSDK_NGX_Resource_VK normals = makeImageResource(normalsView, normalsImage, normalsFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, false);
    NVSDK_NGX_Resource_VK specularMotion = makeImageResource(specularMotionView, specularMotionImage, specularMotionFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, false);
    NVSDK_NGX_Resource_VK output = makeImageResource(outputView, outputImage, outputFormat, displayWidth, displayHeight, VK_IMAGE_ASPECT_COLOR_BIT, true);

    NVSDK_NGX_VK_DLSSD_Eval_Params eval;
    std::memset(&eval, 0, sizeof(eval));
    eval.pInColor = &color;
    eval.pInOutput = &output;
    eval.pInDepth = &depth;
    eval.pInMotionVectors = &mv;
    eval.pInDiffuseAlbedo = &diffuseAlbedo;
    eval.pInSpecularAlbedo = &specularAlbedo;
    eval.pInNormals = &normals;
    eval.pInMotionVectorsReflections = &specularMotion;
    eval.pInSpecularHitDistance = nullptr;
    eval.pInWorldToViewMatrix = nullptr;
    eval.pInViewToClipMatrix = nullptr;
    // pInRoughness left null: InRoughnessMode_Packed reads roughness from normals.w.
    eval.InJitterOffsetX = jitterX;
    eval.InJitterOffsetY = jitterY;
    eval.InMVScaleX = mvScaleX;
    eval.InMVScaleY = mvScaleY;
    eval.InReset = reset;
    eval.InRenderSubrectDimensions.Width = renderWidth;
    eval.InRenderSubrectDimensions.Height = renderHeight;
    eval.InFrameTimeDeltaInMsec = frameTimeMs;

    NVSDK_NGX_Result r = NGX_VULKAN_EVALUATE_DLSSD_EXT(cmd, f->handle, f->params, &eval);
    g_lastResult = (int) r;
    return (int) r;
}

NGX_SHIM_EXPORT void ngxshim_release(void* feature) {
    DlssFeature* f = (DlssFeature*) feature;
    if (!f) {
        return;
    }
    if (f->handle) {
        NVSDK_NGX_VULKAN_ReleaseFeature(f->handle);
    }
    if (f->params && f->ownsParams) {
        NVSDK_NGX_VULKAN_DestroyParameters(f->params);
    }
    std::free(f);
}

NGX_SHIM_EXPORT void ngxshim_shutdown(VkDevice device) {
    if (g_capabilityParams) {
        NVSDK_NGX_VULKAN_DestroyParameters(g_capabilityParams);
        g_capabilityParams = nullptr;
    }
    NVSDK_NGX_VULKAN_Shutdown1(device ? device : g_device);
    g_device = VK_NULL_HANDLE;
}

} // extern "C"
