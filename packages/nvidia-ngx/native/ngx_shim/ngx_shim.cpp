// Flat C ABI shim over the NVIDIA NGX DLSS Vulkan API used by the Java package.
//
// Java FFM calls a primitive-argument C ABI; NGX parameter blocks and resource
// structures stay here with the statically linked SDK and its C++ helpers.
//
// Built as a SHARED library; every exported symbol is undecorated extern "C".

#include <vulkan/vulkan.h>

#include "nvsdk_ngx.h"
#include "nvsdk_ngx_vk.h"
#include "nvsdk_ngx_helpers.h"
#include "nvsdk_ngx_helpers_vk.h"
#include "nvsdk_ngx_helpers_dlssd.h"
#include "nvsdk_ngx_helpers_dlssd_vk.h"
#include "nvsdk_ngx_defs_dlssg.h"
#include "nvsdk_ngx_params_dlssg.h"
#include "nvsdk_ngx_helpers_dlssg.h"
#include "nvsdk_ngx_helpers_dlssg_vk.h"

#include <cstring>
#include <cstdlib>
#include <cstdio>

// NGXSHIM_VERBOSE enables unbuffered shim and NGX-core diagnostics for native failures.
static const bool g_verbose = std::getenv("NGXSHIM_VERBOSE") != nullptr;
#define NGX_LOG(...)                                       \
    do {                                                   \
        if (g_verbose) {                                   \
            std::fprintf(stderr, "[ngx_shim] " __VA_ARGS__); \
            std::fprintf(stderr, "\n");                    \
            std::fflush(stderr);                           \
        }                                                  \
    } while (0)

// A fixed GUID-like project id (NGX requires GUID-like ids for CUSTOM engine).
static const char* kProjectId = "b6f1e9c2-7a44-4d1e-9b3a-1f2c3d4e5a6b";

static NVSDK_NGX_Parameter* g_capabilityParams = nullptr;
static VkDevice g_device = VK_NULL_HANDLE;
static int g_lastResult = 0;

// The NGX callback is flushed immediately because failures may terminate the hosting JVM.
static void NVSDK_CONV ngxshim_log_callback(const char* message,
                                            NVSDK_NGX_Logging_Level level,
                                            NVSDK_NGX_Feature component) {
    std::fprintf(stderr, "[ngx_core L%d C%d] %s", (int) level, (int) component, message ? message : "(null)");
    size_t n = message ? std::strlen(message) : 0;
    if (n == 0 || message[n - 1] != '\n') {
        std::fprintf(stderr, "\n");
    }
    std::fflush(stderr);
}

struct DlssFeature {
    NVSDK_NGX_Handle* handle;
    NVSDK_NGX_Parameter* params;
    bool ownsParams;
};

static NVSDK_NGX_Resource_VK makeImageResource(VkImageView view, VkImage image, int format,
                                               unsigned int width, unsigned int height,
                                               VkImageAspectFlags aspect, bool readWrite) {
    VkImageSubresourceRange range{};
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

// Initializes NGX against the live device. featureDllPath is the directory that
// contains nvngx_dlss.dll (added to the NGX feature DLL search paths).
NGX_SHIM_EXPORT int ngxshim_init(unsigned long long appId, const wchar_t* dataPath,
                                 VkInstance instance, VkPhysicalDevice physicalDevice, VkDevice device,
                                 void* getInstanceProcAddr, void* getDeviceProcAddr,
                                 const wchar_t* featureDllPath) {
    NGX_LOG("init: enter appId=%llu dataPath=%p instance=%p physicalDevice=%p device=%p getInstanceProcAddr=%p getDeviceProcAddr=%p featureDllPath=%p",
            appId, (void*) dataPath, (void*) instance, (void*) physicalDevice, (void*) device,
            getInstanceProcAddr, getDeviceProcAddr, (void*) featureDllPath);
    g_device = device;

    NVSDK_NGX_FeatureCommonInfo info{};
    const wchar_t* paths[1] = { featureDllPath };
    info.PathListInfo.Path = paths;
    info.PathListInfo.Length = featureDllPath ? 1u : 0u;

    // Verbose mode routes NGX diagnostics through the shim's unbuffered callback.
    if (g_verbose) {
        info.LoggingInfo.LoggingCallback = &ngxshim_log_callback;
        info.LoggingInfo.MinimumLoggingLevel = NVSDK_NGX_LOGGING_LEVEL_VERBOSE;
        info.LoggingInfo.DisableOtherLoggingSinks = false;
    }

    NGX_LOG("init: calling NVSDK_NGX_VULKAN_Init_with_ProjectID");
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_Init_with_ProjectID(
            kProjectId, NVSDK_NGX_ENGINE_TYPE_CUSTOM, "1.0", dataPath,
            instance, physicalDevice, device,
            (PFN_vkGetInstanceProcAddr) getInstanceProcAddr,
            (PFN_vkGetDeviceProcAddr) getDeviceProcAddr,
            &info, NVSDK_NGX_Version_API);
    g_lastResult = (int) r;
    NGX_LOG("init: Init_with_ProjectID r=0x%08x", (unsigned) r);
    if (NVSDK_NGX_FAILED(r)) {
        NGX_LOG("init: FAILED init, returning 0x%08x", (unsigned) r);
        return (int) r;
    }

    NGX_LOG("init: calling NVSDK_NGX_VULKAN_GetCapabilityParameters");
    r = NVSDK_NGX_VULKAN_GetCapabilityParameters(&g_capabilityParams);
    g_lastResult = (int) r;
    NGX_LOG("init: GetCapabilityParameters r=0x%08x g_capabilityParams=%p", (unsigned) r, (void*) g_capabilityParams);
    if (NVSDK_NGX_FAILED(r)) {
        // Java retains the runtime only after this entire initialization succeeds.
        NVSDK_NGX_VULKAN_Shutdown1(device);
        g_capabilityParams = nullptr;
        g_device = VK_NULL_HANDLE;
    }
    return (int) r;
}

// 1 if DLSS Super Resolution is available on this system, else 0.
NGX_SHIM_EXPORT int ngxshim_dlss_available() {
    NGX_LOG("dlss_available: enter g_capabilityParams=%p", (void*) g_capabilityParams);
    if (!g_capabilityParams) {
        return 0;
    }
    int available = 0;
    NVSDK_NGX_Parameter_GetI(g_capabilityParams, NVSDK_NGX_Parameter_SuperSampling_Available, &available);
    NGX_LOG("dlss_available: exit available=%d", available);
    return available;
}

// Optimal render resolution + recommended sharpness for a display size and
// quality mode (NVSDK_NGX_PerfQuality_Value). Returns NVSDK_NGX_Result.
NGX_SHIM_EXPORT int ngxshim_query_optimal(unsigned int displayWidth, unsigned int displayHeight, int quality,
                                          unsigned int* outRenderWidth, unsigned int* outRenderHeight,
                                          float* outSharpness) {
    NGX_LOG("query_optimal: enter display=%ux%u quality=%d g_capabilityParams=%p", displayWidth, displayHeight, quality, (void*) g_capabilityParams);
    if (!g_capabilityParams) {
        return -1;
    }
    unsigned int maxW = 0, maxH = 0, minW = 0, minH = 0;
    NVSDK_NGX_Result r = NGX_DLSS_GET_OPTIMAL_SETTINGS(
            g_capabilityParams, displayWidth, displayHeight, (NVSDK_NGX_PerfQuality_Value) quality,
            outRenderWidth, outRenderHeight, &maxW, &maxH, &minW, &minH, outSharpness);
    g_lastResult = (int) r;
    NGX_LOG("query_optimal: exit r=0x%08x render=%ux%u", (unsigned) r,
            outRenderWidth ? *outRenderWidth : 0u, outRenderHeight ? *outRenderHeight : 0u);
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
    NGX_LOG("create_dlss: enter cmd=%p render=%ux%u display=%ux%u quality=%d featureFlags=0x%x renderPreset=%d",
            (void*) cmd, renderWidth, renderHeight, displayWidth, displayHeight, quality, featureFlags, renderPreset);
    NVSDK_NGX_Parameter* params = nullptr;
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_AllocateParameters(&params);
    g_lastResult = (int) r;
    NGX_LOG("create_dlss: AllocateParameters r=0x%08x params=%p", (unsigned) r, (void*) params);
    if (NVSDK_NGX_FAILED(r)) {
        return nullptr;
    }

    unsigned int preset = renderPreset != 0
            ? (unsigned int) renderPreset
            : (unsigned int) NVSDK_NGX_DLSS_Hint_Render_Preset_Default;
    NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA, preset);
    NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality, preset);
    NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced, preset);
    NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance, preset);
    NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance, preset);
    NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraQuality, preset);

    NVSDK_NGX_DLSS_Create_Params createParams{};
    createParams.Feature.InWidth = renderWidth;
    createParams.Feature.InHeight = renderHeight;
    createParams.Feature.InTargetWidth = displayWidth;
    createParams.Feature.InTargetHeight = displayHeight;
    createParams.Feature.InPerfQualityValue = (NVSDK_NGX_PerfQuality_Value) quality;
    createParams.InFeatureCreateFlags = featureFlags;

    NVSDK_NGX_Handle* handle = nullptr;
    NGX_LOG("create_dlss: calling NGX_VULKAN_CREATE_DLSS_EXT");
    r = NGX_VULKAN_CREATE_DLSS_EXT(cmd, 1, 1, &handle, params, &createParams);
    g_lastResult = (int) r;
    NGX_LOG("create_dlss: CREATE_DLSS_EXT r=0x%08x handle=%p", (unsigned) r, (void*) handle);
    if (NVSDK_NGX_FAILED(r)) {
        NVSDK_NGX_VULKAN_DestroyParameters(params);
        return nullptr;
    }

    DlssFeature* feature = (DlssFeature*) std::malloc(sizeof(DlssFeature));
    feature->handle = handle;
    feature->params = params;
    feature->ownsParams = true; // allocated above; release destroys it
    NGX_LOG("create_dlss: exit feature=%p", (void*) feature);
    return feature;
}

// Records a DLSS evaluation into cmd. All images are VkImageView+VkImage+VkFormat
// triples backed by storage-capable images, so NGX requires ReadWrite resources even for inputs.
// The reverse hardware-depth guide is an R32 color-aspect image, not a depth attachment.
NGX_SHIM_EXPORT int ngxshim_evaluate(VkCommandBuffer cmd, void* feature,
                                     VkImageView colorView, VkImage colorImage, int colorFormat,
                                     VkImageView depthView, VkImage depthImage, int depthFormat,
                                     VkImageView mvView, VkImage mvImage, int mvFormat,
                                     VkImageView outputView, VkImage outputImage, int outputFormat,
                                     unsigned int renderWidth, unsigned int renderHeight,
                                     unsigned int displayWidth, unsigned int displayHeight,
                                     float jitterX, float jitterY, float mvScaleX, float mvScaleY,
                                     int reset, float frameTimeMs, float preExposure) {
    NGX_LOG("evaluate: enter cmd=%p feature=%p render=%ux%u display=%ux%u jitter=(%.3f,%.3f) mvScale=(%.3f,%.3f) reset=%d frameTimeMs=%.3f",
            (void*) cmd, feature, renderWidth, renderHeight, displayWidth, displayHeight,
            jitterX, jitterY, mvScaleX, mvScaleY, reset, frameTimeMs);
    DlssFeature* f = (DlssFeature*) feature;
    if (!f) {
        NGX_LOG("evaluate: null feature, returning -1");
        return -1;
    }
    NGX_LOG("evaluate: handle=%p params=%p colorView=%p depthView=%p mvView=%p outputView=%p",
            (void*) f->handle, (void*) f->params, (void*) colorView, (void*) depthView, (void*) mvView, (void*) outputView);

    NVSDK_NGX_Resource_VK color = makeImageResource(colorView, colorImage, colorFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, true);
    NVSDK_NGX_Resource_VK depth = makeImageResource(depthView, depthImage, depthFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, true);
    NVSDK_NGX_Resource_VK mv = makeImageResource(mvView, mvImage, mvFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, true);
    NVSDK_NGX_Resource_VK output = makeImageResource(outputView, outputImage, outputFormat, displayWidth, displayHeight, VK_IMAGE_ASPECT_COLOR_BIT, true);

    NVSDK_NGX_VK_DLSS_Eval_Params eval{};
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
    eval.InPreExposure = preExposure;

    // Presentation flips bottom-up renderer images; orient the native diagnostic text independently.
    eval.InIndicatorInvertYAxis = 1;
    NGX_LOG("evaluate: calling NGX_VULKAN_EVALUATE_DLSS_EXT");
    NVSDK_NGX_Result r = NGX_VULKAN_EVALUATE_DLSS_EXT(cmd, f->handle, f->params, &eval);
    g_lastResult = (int) r;
    NGX_LOG("evaluate: EVALUATE_DLSS_EXT r=0x%08x", (unsigned) r);
    return (int) r;
}

// 1 if DLSS Ray Reconstruction (denoising) is available on this system, else 0.
NGX_SHIM_EXPORT int ngxshim_dlssd_available() {
    NGX_LOG("dlssd_available: enter g_capabilityParams=%p", (void*) g_capabilityParams);
    if (!g_capabilityParams) {
        return 0;
    }
    int available = 0;
    NVSDK_NGX_Parameter_GetI(g_capabilityParams, NVSDK_NGX_Parameter_SuperSamplingDenoising_Available, &available);
    NGX_LOG("dlssd_available: exit available=%d", available);
    return available;
}

// Optimal render resolution + recommended sharpness for a display size and quality mode
// (NVSDK_NGX_PerfQuality_Value), for the Ray Reconstruction (DLSSD) feature specifically — its
// render:display ratios are queried from the DLSSD callback rather than assumed to match DLSS-SR's
// (see NGX_DLSSD_GET_OPTIMAL_SETTINGS; create_dlssd below rejects a mismatched ratio at non-1:1
// quality values with FAIL_InvalidParameter). Returns NVSDK_NGX_Result.
NGX_SHIM_EXPORT int ngxshim_query_optimal_dlssd(unsigned int displayWidth, unsigned int displayHeight, int quality,
                                                unsigned int* outRenderWidth, unsigned int* outRenderHeight,
                                                float* outSharpness) {
    NGX_LOG("query_optimal_dlssd: enter display=%ux%u quality=%d g_capabilityParams=%p", displayWidth, displayHeight, quality, (void*) g_capabilityParams);
    if (!g_capabilityParams) {
        return -1;
    }
    unsigned int maxW = 0, maxH = 0, minW = 0, minH = 0;
    NVSDK_NGX_Result r = NGX_DLSSD_GET_OPTIMAL_SETTINGS(
            g_capabilityParams, displayWidth, displayHeight, (NVSDK_NGX_PerfQuality_Value) quality,
            outRenderWidth, outRenderHeight, &maxW, &maxH, &minW, &minH, outSharpness);
    g_lastResult = (int) r;
    NGX_LOG("query_optimal_dlssd: exit r=0x%08x render=%ux%u", (unsigned) r,
            outRenderWidth ? *outRenderWidth : 0u, outRenderHeight ? *outRenderHeight : 0u);
    return (int) r;
}

// Creates a DLSS Ray Reconstruction feature for DL-unified denoising, packed roughness, and reverse
// hardware depth. Each feature owns its mutable parameter block; the capability block remains query-only.
NGX_SHIM_EXPORT void* ngxshim_create_dlssd(VkCommandBuffer cmd,
                                           unsigned int renderWidth, unsigned int renderHeight,
                                           unsigned int displayWidth, unsigned int displayHeight,
                                           int quality, int featureFlags, int renderPreset) {
    NGX_LOG("create_dlssd: enter cmd=%p render=%ux%u display=%ux%u quality=%d featureFlags=0x%x renderPreset=%d g_device=%p",
            (void*) cmd, renderWidth, renderHeight, displayWidth, displayHeight, quality, featureFlags, renderPreset, (void*) g_device);
    NVSDK_NGX_Parameter* params = nullptr;
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_AllocateParameters(&params);
    g_lastResult = (int) r;
    NGX_LOG("create_dlssd: AllocateParameters r=0x%08x params=%p", (unsigned) r, (void*) params);
    if (NVSDK_NGX_FAILED(r) || !params) {
        return nullptr;
    }

    unsigned int preset = renderPreset != 0
            ? (unsigned int) renderPreset
            : (unsigned int) NVSDK_NGX_RayReconstruction_Hint_Render_Preset_Default;
    NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_DLAA, preset);
    NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Quality, preset);
    NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Balanced, preset);
    NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Performance, preset);
    NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_UltraPerformance, preset);
    NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_UltraQuality, preset);

    NVSDK_NGX_DLSSD_Create_Params createParams{};
    createParams.InDenoiseMode = NVSDK_NGX_DLSS_Denoise_Mode_DLUnified;
    createParams.InRoughnessMode = NVSDK_NGX_DLSS_Roughness_Mode_Packed; // roughness from normals.w
    createParams.InUseHWDepth = NVSDK_NGX_DLSS_Depth_Type_HW;
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
    createParams.InEnableOutputSubrects = false;

    NVSDK_NGX_Handle* handle = nullptr;
    NGX_LOG("create_dlssd: calling NGX_VULKAN_CREATE_DLSSD_EXT1 perfQuality=%d", (int) createParams.InPerfQualityValue);
    r = NGX_VULKAN_CREATE_DLSSD_EXT1(g_device, cmd, 1, 1, &handle, params, &createParams);
    g_lastResult = (int) r;
    NGX_LOG("create_dlssd: CREATE_DLSSD_EXT1 r=0x%08x handle=%p", (unsigned) r, (void*) handle);
    if (NVSDK_NGX_FAILED(r)) {
        NVSDK_NGX_VULKAN_DestroyParameters(params);
        return nullptr;
    }

    DlssFeature* feature = (DlssFeature*) std::malloc(sizeof(DlssFeature));
    if (!feature) {
        NVSDK_NGX_VULKAN_ReleaseFeature(handle);
        NVSDK_NGX_VULKAN_DestroyParameters(params);
        g_lastResult = (int) NVSDK_NGX_Result_FAIL_OutOfGPUMemory;
        return nullptr;
    }
    feature->handle = handle;
    feature->params = params;
    feature->ownsParams = true;
    NGX_LOG("create_dlssd: exit feature=%p", (void*) feature);
    return feature;
}

// Records a DLSS Ray Reconstruction evaluation. Guide buffers are HDR color, reverse hardware depth, motion
// vectors, diffuse albedo, specular albedo, world-space normals with roughness packed in normals.w,
// reflection motion vectors, and the one-channel render-resolution responsivity mask, where larger
// values make DLSSD favor the current frame over its history. Specular hit distance remains optional
// and is omitted. Every supplied image is storage-capable and must be declared ReadWrite to NGX. All
// guides use the color aspect; hardware depth is carried in a color image, not a depth-aspect attachment.
NGX_SHIM_EXPORT int ngxshim_evaluate_dlssd(VkCommandBuffer cmd, void* feature,
                                           VkImageView colorView, VkImage colorImage, int colorFormat,
                                           VkImageView depthView, VkImage depthImage, int depthFormat,
                                           VkImageView mvView, VkImage mvImage, int mvFormat,
                                           VkImageView diffuseAlbedoView, VkImage diffuseAlbedoImage, int diffuseAlbedoFormat,
                                            VkImageView specularAlbedoView, VkImage specularAlbedoImage, int specularAlbedoFormat,
                                            VkImageView normalsView, VkImage normalsImage, int normalsFormat,
                                            VkImageView specularMotionView, VkImage specularMotionImage, int specularMotionFormat,
                                            VkImageView responsivityView, VkImage responsivityImage, int responsivityFormat,
                                            VkImageView outputView, VkImage outputImage, int outputFormat,
                                           unsigned int renderWidth, unsigned int renderHeight,
                                           unsigned int displayWidth, unsigned int displayHeight,
                                           float jitterX, float jitterY, float mvScaleX, float mvScaleY,
                                           int reset, float frameTimeMs, float preExposure) {
    NGX_LOG("evaluate_dlssd: enter cmd=%p feature=%p render=%ux%u display=%ux%u jitter=(%.3f,%.3f) mvScale=(%.3f,%.3f) reset=%d frameTimeMs=%.3f preExposure=%.6f",
            (void*) cmd, feature, renderWidth, renderHeight, displayWidth, displayHeight,
            jitterX, jitterY, mvScaleX, mvScaleY, reset, frameTimeMs, preExposure);
    DlssFeature* f = (DlssFeature*) feature;
    if (!f) {
        NGX_LOG("evaluate_dlssd: null feature, returning -1");
        return -1;
    }
    NGX_LOG("evaluate_dlssd: handle=%p params=%p color=%p depth=%p mv=%p diffuse=%p specular=%p normals=%p specMotion=%p responsivity=%p output=%p",
            (void*) f->handle, (void*) f->params, (void*) colorView, (void*) depthView, (void*) mvView,
            (void*) diffuseAlbedoView, (void*) specularAlbedoView, (void*) normalsView, (void*) specularMotionView,
            (void*) responsivityView, (void*) outputView);

    NVSDK_NGX_Resource_VK color = makeImageResource(colorView, colorImage, colorFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, true);
    NVSDK_NGX_Resource_VK depth = makeImageResource(depthView, depthImage, depthFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, true);
    NVSDK_NGX_Resource_VK mv = makeImageResource(mvView, mvImage, mvFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, true);
    NVSDK_NGX_Resource_VK diffuseAlbedo = makeImageResource(diffuseAlbedoView, diffuseAlbedoImage, diffuseAlbedoFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, true);
    NVSDK_NGX_Resource_VK specularAlbedo = makeImageResource(specularAlbedoView, specularAlbedoImage, specularAlbedoFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, true);
    NVSDK_NGX_Resource_VK normals = makeImageResource(normalsView, normalsImage, normalsFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, true);
    NVSDK_NGX_Resource_VK specularMotion = makeImageResource(specularMotionView, specularMotionImage, specularMotionFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, true);
    NVSDK_NGX_Resource_VK responsivity = makeImageResource(responsivityView, responsivityImage, responsivityFormat, renderWidth, renderHeight, VK_IMAGE_ASPECT_COLOR_BIT, true);
    NVSDK_NGX_Resource_VK output = makeImageResource(outputView, outputImage, outputFormat, displayWidth, displayHeight, VK_IMAGE_ASPECT_COLOR_BIT, true);

    NVSDK_NGX_VK_DLSSD_Eval_Params eval{};
    eval.pInColor = &color;
    eval.pInOutput = &output;
    eval.pInDepth = &depth;
    eval.pInMotionVectors = &mv;
    eval.pInDiffuseAlbedo = &diffuseAlbedo;
    eval.pInSpecularAlbedo = &specularAlbedo;
    eval.pInNormals = &normals;
    eval.pInRoughness = nullptr;
    eval.pInMotionVectorsReflections = &specularMotion;
    eval.pInResponsivityMask = &responsivity;
    eval.pInSpecularHitDistance = nullptr;
    eval.pInWorldToViewMatrix = nullptr;
    eval.pInViewToClipMatrix = nullptr;
    eval.InJitterOffsetX = jitterX;
    eval.InJitterOffsetY = jitterY;
    eval.InMVScaleX = mvScaleX;
    eval.InMVScaleY = mvScaleY;
    eval.InReset = reset;
    eval.InRenderSubrectDimensions.Width = renderWidth;
    eval.InRenderSubrectDimensions.Height = renderHeight;
    eval.InFrameTimeDeltaInMsec = frameTimeMs;
    eval.InPreExposure = preExposure;
    // Renderer images are bottom-up; keep the NGX diagnostic indicator upright after presentation.
    eval.InIndicatorInvertYAxis = 1;
    NGX_LOG("evaluate_dlssd: calling NGX_VULKAN_EVALUATE_DLSSD_EXT");
    NVSDK_NGX_Result r = NGX_VULKAN_EVALUATE_DLSSD_EXT(cmd, f->handle, f->params, &eval);
    g_lastResult = (int) r;
    NGX_LOG("evaluate_dlssd: EVALUATE_DLSSD_EXT r=0x%08x", (unsigned) r);
    return (int) r;
}

// 1 if DLSS Frame Generation is available on this system, else 0.
NGX_SHIM_EXPORT int ngxshim_dlssg_available() {
    if (!g_capabilityParams) {
        return 0;
    }
    int available = 0;
    NVSDK_NGX_Parameter_GetI(g_capabilityParams, NVSDK_NGX_Parameter_FrameGeneration_Available, &available);
    return available;
}

// Creates a DLSS Frame Generation (DLSSG) feature. Width/Height are the backbuffer (present) size;
// render size is the upscaled-but-pre-FG size (== backbuffer when no dynamic-res). nativeBackbufferFormat
// is the VkFormat of the presented swapchain image. FG uses the shared
// capability parameter block (it carries the snippet callbacks), so the feature does not own it.
NGX_SHIM_EXPORT void* ngxshim_create_dlssg(VkCommandBuffer cmd,
                                           unsigned int width, unsigned int height,
                                           int nativeBackbufferFormat) {
    NVSDK_NGX_Parameter* params = g_capabilityParams;
    if (!params) {
        g_lastResult = (int) NVSDK_NGX_Result_FAIL_NotInitialized;
        return nullptr;
    }

    NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_CreationNodeMask, 1);
    NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_Parameter_VisibilityNodeMask, 1);
    NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_DLSSG_Parameter_Width, width);
    NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_DLSSG_Parameter_Height, height);
    NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_DLSSG_Parameter_BackbufferFormat,
                            (unsigned int) nativeBackbufferFormat);

    // UI recomposition is a create-time feature. Evaluation supplies HUDless/UI resources when available.
    NVSDK_NGX_Parameter_SetUI(params, NVSDK_NGX_DLSSG_Parameter_UserInterfaceRecompositionEnabled, 1);

    NVSDK_NGX_Handle* handle = nullptr;
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_CreateFeature(
            cmd, NVSDK_NGX_Feature_FrameGeneration, params, &handle);
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

// Records a DLSS Frame Generation evaluation: generate one frame halfway between rendered frames into
// outputInterp from the final backbuffer + hardware depth + motion vectors. Optional
// resources (hudless, ui, outputReal) are skipped when their view handle is 0. Matrices are 16-float
// row-major (NGX left-multiply convention) and MUST be jitter-free; pass null to leave a matrix zeroed.
// depthInverted/colorBuffersHDR/cameraMotionIncluded/reset are 0/1 flags. The caller presents the generated
// frame then the real frame with its own pacing.
NGX_SHIM_EXPORT int ngxshim_evaluate_dlssg_2x(VkCommandBuffer cmd, void* feature,
                                           VkImageView backbufferView, VkImage backbufferImage, int backbufferFormat,
                                           VkImageView depthView, VkImage depthImage, int depthFormat,
                                           VkImageView mvecView, VkImage mvecImage, int mvecFormat,
                                           VkImageView hudlessView, VkImage hudlessImage, int hudlessFormat,
                                           VkImageView uiView, VkImage uiImage, int uiFormat,
                                           VkImageView outputInterpView, VkImage outputInterpImage, int outputInterpFormat,
                                           VkImageView outputRealView, VkImage outputRealImage, int outputRealFormat,
                                           unsigned int width, unsigned int height,
                                           unsigned int mvecDepthWidth, unsigned int mvecDepthHeight,
                                           float mvecScaleX, float mvecScaleY,
                                           int depthInverted, int colorBuffersHDR, int cameraMotionIncluded, int reset,
                                           float* cameraViewToClip, float* clipToCameraView,
                                           float* clipToPrevClip, float* prevClipToClip) {
    DlssFeature* f = (DlssFeature*) feature;
    if (!f) {
        return -1;
    }

    NVSDK_NGX_Resource_VK backbuffer = makeImageResource(backbufferView, backbufferImage, backbufferFormat, width, height, VK_IMAGE_ASPECT_COLOR_BIT, false);
    NVSDK_NGX_Resource_VK depth = makeImageResource(depthView, depthImage, depthFormat, mvecDepthWidth, mvecDepthHeight, VK_IMAGE_ASPECT_COLOR_BIT, false);
    NVSDK_NGX_Resource_VK mvec = makeImageResource(mvecView, mvecImage, mvecFormat, mvecDepthWidth, mvecDepthHeight, VK_IMAGE_ASPECT_COLOR_BIT, false);
    NVSDK_NGX_Resource_VK hudless = makeImageResource(hudlessView, hudlessImage, hudlessFormat, width, height, VK_IMAGE_ASPECT_COLOR_BIT, false);
    NVSDK_NGX_Resource_VK ui = makeImageResource(uiView, uiImage, uiFormat, width, height, VK_IMAGE_ASPECT_COLOR_BIT, false);
    NVSDK_NGX_Resource_VK outputInterp = makeImageResource(outputInterpView, outputInterpImage, outputInterpFormat, width, height, VK_IMAGE_ASPECT_COLOR_BIT, true);
    NVSDK_NGX_Resource_VK outputReal = makeImageResource(outputRealView, outputRealImage, outputRealFormat, width, height, VK_IMAGE_ASPECT_COLOR_BIT, true);

    NVSDK_NGX_VK_DLSSG_Eval_Params eval{};
    eval.pBackbuffer = &backbuffer;
    eval.pDepth = &depth;
    eval.pMVecs = &mvec;
    eval.pHudless = hudlessView ? &hudless : nullptr;
    eval.pUI = uiView ? &ui : nullptr;
    eval.pUIAlpha = nullptr;
    eval.pBidirectionalDistortionField = nullptr;
    eval.pOutputInterpFrame = &outputInterp;
    eval.pOutputRealFrame = outputRealView ? &outputReal : nullptr;
    eval.pOutputDisableInterpolation = nullptr;

    NVSDK_NGX_DLSSG_Opt_Eval_Params opt{};
    opt.multiFrameCount = 1;
    opt.multiFrameIndex = 1;
    opt.mvecScale[0] = mvecScaleX;
    opt.mvecScale[1] = mvecScaleY;
    opt.depthInverted = depthInverted != 0;
    opt.colorBuffersHDR = colorBuffersHDR != 0;
    opt.cameraMotionIncluded = cameraMotionIncluded != 0;
    opt.reset = reset != 0;
    if (cameraViewToClip) std::memcpy(opt.cameraViewToClip, cameraViewToClip, sizeof(opt.cameraViewToClip));
    if (clipToCameraView) std::memcpy(opt.clipToCameraView, clipToCameraView, sizeof(opt.clipToCameraView));
    if (clipToPrevClip) std::memcpy(opt.clipToPrevClip, clipToPrevClip, sizeof(opt.clipToPrevClip));
    if (prevClipToClip) std::memcpy(opt.prevClipToClip, prevClipToClip, sizeof(opt.prevClipToClip));

    NVSDK_NGX_Result r = NGX_VK_EVALUATE_DLSSG(cmd, f->handle, f->params, &eval, &opt);
    g_lastResult = (int) r;
    return (int) r;
}

NGX_SHIM_EXPORT void ngxshim_release(void* feature) {
    NGX_LOG("release: enter feature=%p", feature);
    DlssFeature* f = (DlssFeature*) feature;
    if (!f) {
        return;
    }
    if (f->handle) {
        NGX_LOG("release: releasing handle=%p", (void*) f->handle);
        NVSDK_NGX_VULKAN_ReleaseFeature(f->handle);
    }
    if (f->params && f->ownsParams) {
        NGX_LOG("release: destroying owned params=%p", (void*) f->params);
        NVSDK_NGX_VULKAN_DestroyParameters(f->params);
    }
    std::free(f);
    NGX_LOG("release: exit");
}

NGX_SHIM_EXPORT void ngxshim_shutdown(VkDevice device) {
    NGX_LOG("shutdown: enter device=%p g_device=%p g_capabilityParams=%p", (void*) device, (void*) g_device, (void*) g_capabilityParams);
    if (g_capabilityParams) {
        NVSDK_NGX_VULKAN_DestroyParameters(g_capabilityParams);
        g_capabilityParams = nullptr;
    }
    NVSDK_NGX_VULKAN_Shutdown1(device ? device : g_device);
    g_device = VK_NULL_HANDLE;
    NGX_LOG("shutdown: exit");
}

} // extern "C"
