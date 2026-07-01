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
#include "nvsdk_ngx_defs_dlssg.h"
#include "nvsdk_ngx_params_dlssg.h"
#include "nvsdk_ngx_helpers_dlssg.h"
#include "nvsdk_ngx_helpers_dlssg_vk.h"

#include <cstring>
#include <cstdlib>
#include <cstdio>

// Lightweight diagnostic logging. The shim is loaded into the JVM via FFM and a
// crash here surfaces only as a bare SIGSEGV on the Java side, so we can trace every
// exported entry/exit to stderr (unbuffered + flushed) to localize the fault. This is
// off by default (it logs per-frame in evaluate); set NGXSHIM_VERBOSE=1 to enable it
// and the NGX core's own verbose logging sink.
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

// Logging sink wired into NVSDK_NGX_FeatureCommonInfo so the (closed) NGX core/SDK pipes its own
// init diagnostics back to us. NGX init crashes deep inside the driver core on this setup; the core's
// last log line before the fault is the best clue to *why*. Flush every line so nothing is lost.
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
    NGX_LOG("required_extensions: enter wantDevice=%d outBuf=%p bufLen=%d", wantDevice, (void*) outBuf, bufLen);
    unsigned int instanceCount = 0, deviceCount = 0;
    const char** instanceExts = nullptr;
    const char** deviceExts = nullptr;

    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_RequiredExtensions(&instanceCount, &instanceExts, &deviceCount, &deviceExts);
    g_lastResult = (int) r;
    NGX_LOG("required_extensions: RequiredExtensions r=0x%08x instanceCount=%u deviceCount=%u", (unsigned) r, instanceCount, deviceCount);
    if (NVSDK_NGX_FAILED(r)) {
        NGX_LOG("required_extensions: FAILED, returning -1");
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
    NGX_LOG("required_extensions: exit count=%u bytesWritten=%d", count, pos);
    return (int) count;
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

    NVSDK_NGX_FeatureCommonInfo info;
    std::memset(&info, 0, sizeof(info));
    const wchar_t* paths[1] = { featureDllPath };
    info.PathListInfo.Path = paths;
    info.PathListInfo.Length = featureDllPath ? 1u : 0u;

    // Route the NGX core/SDK's own logging to our callback at the most verbose level so we capture
    // exactly what it does (and the last thing it does before crashing) during init.
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

// Creates a DLSS Ray Reconstruction (DLSSD) feature. Configured for our path tracer: DL-unified
// denoise, roughness packed into normals.w, and HW (non-linear, reversed-Z) depth — the rgen writes
// ndc z/w, shared with Frame Generation. renderPreset is an NVSDK_NGX_RayReconstruction_Hint_Render_Preset
// value (0 = DLL default).
NGX_SHIM_EXPORT void* ngxshim_create_dlssd(VkCommandBuffer cmd,
                                           unsigned int renderWidth, unsigned int renderHeight,
                                           unsigned int displayWidth, unsigned int displayHeight,
                                           int quality, int featureFlags, int renderPreset) {
    NGX_LOG("create_dlssd: enter cmd=%p render=%ux%u display=%ux%u quality=%d featureFlags=0x%x renderPreset=%d g_device=%p",
            (void*) cmd, renderWidth, renderHeight, displayWidth, displayHeight, quality, featureFlags, renderPreset, (void*) g_device);
    // DLSS Ray Reconstruction must be created with the capability parameter block (it carries the
    // snippet/preset callbacks the feature needs); a fresh AllocateParameters block fails with
    // FAIL_InvalidParameter. The block is shared (freed at shutdown), so the feature does not own it.
    NVSDK_NGX_Parameter* params = g_capabilityParams;
    if (!params) {
        NGX_LOG("create_dlssd: no capability params, returning null");
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
    createParams.InUseHWDepth = NVSDK_NGX_DLSS_Depth_Type_HW;            // rgen writes ndc reversed-Z depth
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
    NGX_LOG("create_dlssd: calling NGX_VULKAN_CREATE_DLSSD_EXT1 perfQuality=%d", (int) createParams.InPerfQualityValue);
    r = NGX_VULKAN_CREATE_DLSSD_EXT1(g_device, cmd, 1, 1, &handle, params, &createParams);
    g_lastResult = (int) r;
    NGX_LOG("create_dlssd: CREATE_DLSSD_EXT1 r=0x%08x handle=%p", (unsigned) r, (void*) handle);
    if (NVSDK_NGX_FAILED(r)) {
        return nullptr; // params is the shared capability block; do not destroy it
    }

    DlssFeature* feature = (DlssFeature*) std::malloc(sizeof(DlssFeature));
    feature->handle = handle;
    feature->params = params;
    feature->ownsParams = false; // shared capability block, freed at shutdown
    NGX_LOG("create_dlssd: exit feature=%p", (void*) feature);
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
    NGX_LOG("evaluate_dlssd: enter cmd=%p feature=%p render=%ux%u display=%ux%u jitter=(%.3f,%.3f) mvScale=(%.3f,%.3f) reset=%d frameTimeMs=%.3f",
            (void*) cmd, feature, renderWidth, renderHeight, displayWidth, displayHeight,
            jitterX, jitterY, mvScaleX, mvScaleY, reset, frameTimeMs);
    DlssFeature* f = (DlssFeature*) feature;
    if (!f) {
        NGX_LOG("evaluate_dlssd: null feature, returning -1");
        return -1;
    }
    NGX_LOG("evaluate_dlssd: handle=%p params=%p color=%p depth=%p mv=%p diffuse=%p specular=%p normals=%p specMotion=%p output=%p",
            (void*) f->handle, (void*) f->params, (void*) colorView, (void*) depthView, (void*) mvView,
            (void*) diffuseAlbedoView, (void*) specularAlbedoView, (void*) normalsView, (void*) specularMotionView, (void*) outputView);
    (void) specularHitDistanceView;
    (void) specularHitDistanceImage;
    (void) specularHitDistanceFormat;

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
    // HW depth needs the projection so DLSS can linearize it (jitter-free; NGX left-multiply layout).
    eval.pInWorldToViewMatrix = worldToViewMatrix;
    eval.pInViewToClipMatrix = viewToClipMatrix;
    // pInRoughness left null: InRoughnessMode_Packed reads roughness from normals.w.
    eval.InJitterOffsetX = jitterX;
    eval.InJitterOffsetY = jitterY;
    eval.InMVScaleX = mvScaleX;
    eval.InMVScaleY = mvScaleY;
    eval.InReset = reset;
    eval.InRenderSubrectDimensions.Width = renderWidth;
    eval.InRenderSubrectDimensions.Height = renderHeight;
    eval.InFrameTimeDeltaInMsec = frameTimeMs;

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

// Maximum multi-frame-generation count the driver/DLL supports (1 = 2x only). 0 if unknown.
NGX_SHIM_EXPORT unsigned int ngxshim_dlssg_multi_frame_count_max() {
    if (!g_capabilityParams) {
        return 0;
    }
    unsigned int maxCount = 0;
    NVSDK_NGX_Parameter_GetUI(g_capabilityParams, NVSDK_NGX_DLSSG_Parameter_MultiFrameCountMax, &maxCount);
    return maxCount;
}

// Creates a DLSS Frame Generation (DLSSG) feature. Width/Height are the backbuffer (present) size;
// render size is the upscaled-but-pre-FG size (== backbuffer when no dynamic-res). nativeBackbufferFormat
// is the VkFormat of the presented swapchain image. Like DLSSD, FG must be created with the shared
// capability parameter block (it carries the snippet callbacks), so the feature does not own it.
NGX_SHIM_EXPORT void* ngxshim_create_dlssg(VkCommandBuffer cmd,
                                           unsigned int width, unsigned int height,
                                           unsigned int renderWidth, unsigned int renderHeight,
                                           int nativeBackbufferFormat) {
    NVSDK_NGX_Parameter* params = g_capabilityParams;
    if (!params) {
        g_lastResult = (int) NVSDK_NGX_Result_FAIL_NotInitialized;
        return nullptr;
    }

    NVSDK_NGX_DLSSG_Create_Params createParams;
    std::memset(&createParams, 0, sizeof(createParams));
    createParams.Width = width;
    createParams.Height = height;
    createParams.NativeBackbufferFormat = (unsigned int) nativeBackbufferFormat;
    createParams.RenderWidth = renderWidth;
    createParams.RenderHeight = renderHeight;
    createParams.DynamicResolutionScaling = false;

    NVSDK_NGX_Handle* handle = nullptr;
    NVSDK_NGX_Result r = NGX_VK_CREATE_DLSSG(cmd, 1, 1, &handle, params, &createParams);
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

// Records a DLSS Frame Generation evaluation: generate one interpolated frame (index multiFrameIndex of
// multiFrameCount) into outputInterp from the final backbuffer + hardware depth + motion vectors. Optional
// resources (hudless, ui, outputReal) are skipped when their view handle is 0. Matrices are 16-float
// row-major (NGX left-multiply convention) and MUST be jitter-free; pass null to leave a matrix zeroed.
// depthInverted/colorBuffersHDR/cameraMotionIncluded/reset are 0/1 flags. Each call generates ONE frame;
// the caller presents generated frame(s) then the real frame with its own pacing.
NGX_SHIM_EXPORT int ngxshim_evaluate_dlssg(VkCommandBuffer cmd, void* feature,
                                           VkImageView backbufferView, VkImage backbufferImage, int backbufferFormat,
                                           VkImageView depthView, VkImage depthImage, int depthFormat,
                                           VkImageView mvecView, VkImage mvecImage, int mvecFormat,
                                           VkImageView hudlessView, VkImage hudlessImage, int hudlessFormat,
                                           VkImageView uiView, VkImage uiImage, int uiFormat,
                                           VkImageView outputInterpView, VkImage outputInterpImage, int outputInterpFormat,
                                           VkImageView outputRealView, VkImage outputRealImage, int outputRealFormat,
                                           unsigned int width, unsigned int height,
                                           unsigned int mvecDepthWidth, unsigned int mvecDepthHeight,
                                           unsigned int multiFrameCount, unsigned int multiFrameIndex,
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

    NVSDK_NGX_VK_DLSSG_Eval_Params eval;
    std::memset(&eval, 0, sizeof(eval));
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

    // Default-construct so the SDK's in-class defaults apply (multiFrameCount=1, etc.), then override.
    NVSDK_NGX_DLSSG_Opt_Eval_Params opt{};
    opt.multiFrameCount = multiFrameCount;
    opt.multiFrameIndex = multiFrameIndex;
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
