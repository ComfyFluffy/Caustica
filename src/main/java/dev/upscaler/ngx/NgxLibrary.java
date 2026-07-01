package dev.upscaler.ngx;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * FFM bindings for the NGX DLSS shim ({@code ngxshim.dll}).
 *
 * <p>The shim wraps the static-only NGX SDK behind a flat C ABI (see
 * {@code native/ngx_shim/ngx_shim.cpp}). All upscaler-specific structs and
 * NGX parameter blocks live in the shim; Java only passes primitives and raw
 * Vulkan handles (as {@code long} addresses).
 */
public final class NgxLibrary {
	private static final Linker LINKER = Linker.nativeLinker();

	private final MethodHandle requiredExtensions;
	private final MethodHandle init;
	private final MethodHandle dlssAvailable;
	private final MethodHandle queryOptimal;
	private final MethodHandle createDlss;
	private final MethodHandle evaluate;
	private final MethodHandle dlssdAvailable;
	private final MethodHandle createDlssd;
	private final MethodHandle evaluateDlssd;
	private final MethodHandle dlssgAvailable;
	private final MethodHandle dlssgMultiFrameCountMax;
	private final MethodHandle createDlssg;
	private final MethodHandle evaluateDlssg;
	private final MethodHandle release;
	private final MethodHandle shutdown;
	private final MethodHandle lastResult;

	private NgxLibrary(SymbolLookup lookup) {
		// int ngxshim_required_extensions(int wantDevice, char* outBuf, int bufLen)
		this.requiredExtensions = handle(lookup, "ngxshim_required_extensions",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
		// int ngxshim_init(u64 appId, wchar* dataPath, VkInstance, VkPhysicalDevice, VkDevice, void* gipa, void* gdpa, wchar* dllPath)
		this.init = handle(lookup, "ngxshim_init",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
		this.dlssAvailable = handle(lookup, "ngxshim_dlss_available",
				FunctionDescriptor.of(ValueLayout.JAVA_INT));
		// int ngxshim_query_optimal(u32 dispW, u32 dispH, int quality, u32* outRW, u32* outRH, float* outSharp)
		this.queryOptimal = handle(lookup, "ngxshim_query_optimal",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		// void* ngxshim_create_dlss(VkCommandBuffer, u32 rw, u32 rh, u32 dw, u32 dh, int quality, int flags, int preset)
		this.createDlss = handle(lookup, "ngxshim_create_dlss",
				FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
		// int ngxshim_evaluate(cmd, feature, [color/depth/mv/out: view,img,fmt]*4, rw,rh,dw,dh, jx,jy,mvsx,mvsy, reset, frameMs)
		this.evaluate = handle(lookup, "ngxshim_evaluate",
				FunctionDescriptor.of(ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
						ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT));
		// DLSS Ray Reconstruction (DLSSD) — same create ABI as DLSS-SR; evaluate adds the diffuse
		// albedo / specular albedo / normals guide buffers (roughness is packed in normals.w).
		this.dlssdAvailable = handle(lookup, "ngxshim_dlssd_available",
				FunctionDescriptor.of(ValueLayout.JAVA_INT));
		this.createDlssd = handle(lookup, "ngxshim_create_dlssd",
				FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
		// int ngxshim_evaluate_dlssd(cmd, feature, [color/depth/mv/diffAlbedo/specAlbedo/normals/specMotion/specHit/out: view,img,fmt]*9, rw,rh,dw,dh, jx,jy,mvsx,mvsy, reset, frameMs, matrices)
		this.evaluateDlssd = handle(lookup, "ngxshim_evaluate_dlssd",
				FunctionDescriptor.of(ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
						ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		// DLSS Frame Generation (DLSSG). Optional: a stale shim without these exports still loads (FG off).
		this.dlssgAvailable = optionalHandle(lookup, "ngxshim_dlssg_available",
				FunctionDescriptor.of(ValueLayout.JAVA_INT));
		this.dlssgMultiFrameCountMax = optionalHandle(lookup, "ngxshim_dlssg_multi_frame_count_max",
				FunctionDescriptor.of(ValueLayout.JAVA_INT));
		// void* ngxshim_create_dlssg(cmd, u32 w, u32 h, u32 rw, u32 rh, int nativeBackbufferFormat)
		this.createDlssg = optionalHandle(lookup, "ngxshim_create_dlssg",
				FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
						ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
		// int ngxshim_evaluate_dlssg(cmd, feature, [backbuffer/depth/mvec/hudless/ui/outInterp/outReal: view,img,fmt]*7,
		//   w,h, mvecDepthW, mvecDepthH, mfCount, mfIndex, mvScaleX, mvScaleY, depthInv, hdr, camMotion, reset, 4 matrices)
		this.evaluateDlssg = optionalHandle(lookup, "ngxshim_evaluate_dlssg",
				FunctionDescriptor.of(ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
						ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		this.release = handle(lookup, "ngxshim_release",
				FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
		this.shutdown = handle(lookup, "ngxshim_shutdown",
				FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG));
		this.lastResult = handle(lookup, "ngxshim_last_result",
				FunctionDescriptor.of(ValueLayout.JAVA_INT));
	}

	public static NgxLibrary load(Path dll) {
		return new NgxLibrary(SymbolLookup.libraryLookup(dll, Arena.global()));
	}

	private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor desc) {
		return LINKER.downcallHandle(
				lookup.find(name).orElseThrow(() -> new IllegalStateException("ngxshim missing export " + name)),
				desc);
	}

	// For exports added later than the core ABI (e.g. DLSSG): a stale locally-built ngxshim.dll (the DLL is
	// not rebuilt by gradle, only copied) must still load so DLSS-RR keeps working — the newer feature just
	// reports unavailable. Returns null when the symbol is absent.
	private static MethodHandle optionalHandle(SymbolLookup lookup, String name, FunctionDescriptor desc) {
		return lookup.find(name).map(sym -> LINKER.downcallHandle(sym, desc)).orElse(null);
	}

	public int requiredExtensions(boolean wantDevice, MemorySegment outBuf, int bufLen) {
		try {
			return (int) this.requiredExtensions.invokeExact(wantDevice ? 1 : 0, outBuf, bufLen);
		} catch (Throwable t) {
			throw new RuntimeException("ngxshim_required_extensions failed", t);
		}
	}

	public int init(long appId, MemorySegment dataPath, long vkInstance, long vkPhysicalDevice, long vkDevice,
	                long getInstanceProcAddr, long getDeviceProcAddr, MemorySegment dllPath) {
		try {
			return (int) this.init.invokeExact(appId, dataPath, vkInstance, vkPhysicalDevice, vkDevice,
					getInstanceProcAddr, getDeviceProcAddr, dllPath);
		} catch (Throwable t) {
			throw new RuntimeException("ngxshim_init failed", t);
		}
	}

	public boolean dlssAvailable() {
		try {
			return ((int) this.dlssAvailable.invokeExact()) != 0;
		} catch (Throwable t) {
			throw new RuntimeException("ngxshim_dlss_available failed", t);
		}
	}

	public int queryOptimal(int displayWidth, int displayHeight, int quality,
	                        MemorySegment outRenderWidth, MemorySegment outRenderHeight, MemorySegment outSharpness) {
		try {
			return (int) this.queryOptimal.invokeExact(displayWidth, displayHeight, quality,
					outRenderWidth, outRenderHeight, outSharpness);
		} catch (Throwable t) {
			throw new RuntimeException("ngxshim_query_optimal failed", t);
		}
	}

	public MemorySegment createDlss(long cmd, int renderWidth, int renderHeight, int displayWidth, int displayHeight,
	                                int quality, int featureFlags, int renderPreset) {
		try {
			return (MemorySegment) this.createDlss.invokeExact(cmd, renderWidth, renderHeight, displayWidth, displayHeight,
					quality, featureFlags, renderPreset);
		} catch (Throwable t) {
			throw new RuntimeException("ngxshim_create_dlss failed", t);
		}
	}

	public int evaluate(long cmd, MemorySegment feature,
	                    long colorView, long colorImage, int colorFormat,
	                    long depthView, long depthImage, int depthFormat,
	                    long mvView, long mvImage, int mvFormat,
	                    long outputView, long outputImage, int outputFormat,
	                    int renderWidth, int renderHeight, int displayWidth, int displayHeight,
	                    float jitterX, float jitterY, float mvScaleX, float mvScaleY,
	                    int reset, float frameTimeMs) {
		try {
			return (int) this.evaluate.invokeExact(cmd, feature,
					colorView, colorImage, colorFormat,
					depthView, depthImage, depthFormat,
					mvView, mvImage, mvFormat,
					outputView, outputImage, outputFormat,
					renderWidth, renderHeight, displayWidth, displayHeight,
					jitterX, jitterY, mvScaleX, mvScaleY, reset, frameTimeMs);
		} catch (Throwable t) {
			throw new RuntimeException("ngxshim_evaluate failed", t);
		}
	}

	public boolean dlssdAvailable() {
		try {
			return ((int) this.dlssdAvailable.invokeExact()) != 0;
		} catch (Throwable t) {
			throw new RuntimeException("ngxshim_dlssd_available failed", t);
		}
	}

	public MemorySegment createDlssd(long cmd, int renderWidth, int renderHeight, int displayWidth, int displayHeight,
	                                 int quality, int featureFlags, int renderPreset) {
		try {
			return (MemorySegment) this.createDlssd.invokeExact(cmd, renderWidth, renderHeight, displayWidth, displayHeight,
					quality, featureFlags, renderPreset);
		} catch (Throwable t) {
			throw new RuntimeException("ngxshim_create_dlssd failed", t);
		}
	}

	public int evaluateDlssd(long cmd, MemorySegment feature,
	                         long colorView, long colorImage, int colorFormat,
	                         long depthView, long depthImage, int depthFormat,
	                         long mvView, long mvImage, int mvFormat,
	                         long diffuseAlbedoView, long diffuseAlbedoImage, int diffuseAlbedoFormat,
	                         long specularAlbedoView, long specularAlbedoImage, int specularAlbedoFormat,
	                         long normalsView, long normalsImage, int normalsFormat,
	                         long specularMotionView, long specularMotionImage, int specularMotionFormat,
	                         long specularHitDistanceView, long specularHitDistanceImage, int specularHitDistanceFormat,
	                         long outputView, long outputImage, int outputFormat,
	                         int renderWidth, int renderHeight, int displayWidth, int displayHeight,
	                         float jitterX, float jitterY, float mvScaleX, float mvScaleY,
	                         int reset, float frameTimeMs,
	                         MemorySegment worldToViewMatrix, MemorySegment viewToClipMatrix) {
		try {
			return (int) this.evaluateDlssd.invokeExact(cmd, feature,
					colorView, colorImage, colorFormat,
					depthView, depthImage, depthFormat,
					mvView, mvImage, mvFormat,
					diffuseAlbedoView, diffuseAlbedoImage, diffuseAlbedoFormat,
					specularAlbedoView, specularAlbedoImage, specularAlbedoFormat,
					normalsView, normalsImage, normalsFormat,
					specularMotionView, specularMotionImage, specularMotionFormat,
					specularHitDistanceView, specularHitDistanceImage, specularHitDistanceFormat,
					outputView, outputImage, outputFormat,
					renderWidth, renderHeight, displayWidth, displayHeight,
					jitterX, jitterY, mvScaleX, mvScaleY, reset, frameTimeMs,
					worldToViewMatrix, viewToClipMatrix);
		} catch (Throwable t) {
			throw new RuntimeException("ngxshim_evaluate_dlssd failed", t);
		}
	}

	/** Whether the loaded shim exposes the DLSSG ABI at all (false for a stale shim built before FG). */
	public boolean hasDlssg() {
		return dlssgAvailable != null && createDlssg != null && evaluateDlssg != null;
	}

	public boolean dlssgAvailable() {
		if (dlssgAvailable == null) {
			return false;
		}
		try {
			return ((int) this.dlssgAvailable.invokeExact()) != 0;
		} catch (Throwable t) {
			throw new RuntimeException("ngxshim_dlssg_available failed", t);
		}
	}

	public int dlssgMultiFrameCountMax() {
		if (dlssgMultiFrameCountMax == null) {
			return 0;
		}
		try {
			return (int) this.dlssgMultiFrameCountMax.invokeExact();
		} catch (Throwable t) {
			throw new RuntimeException("ngxshim_dlssg_multi_frame_count_max failed", t);
		}
	}

	public MemorySegment createDlssg(long cmd, int width, int height, int renderWidth, int renderHeight,
	                                 int nativeBackbufferFormat) {
		try {
			return (MemorySegment) this.createDlssg.invokeExact(cmd, width, height, renderWidth, renderHeight,
					nativeBackbufferFormat);
		} catch (Throwable t) {
			throw new RuntimeException("ngxshim_create_dlssg failed", t);
		}
	}

	public int evaluateDlssg(long cmd, MemorySegment feature,
	                         long backbufferView, long backbufferImage, int backbufferFormat,
	                         long depthView, long depthImage, int depthFormat,
	                         long mvecView, long mvecImage, int mvecFormat,
	                         long hudlessView, long hudlessImage, int hudlessFormat,
	                         long uiView, long uiImage, int uiFormat,
	                         long outputInterpView, long outputInterpImage, int outputInterpFormat,
	                         long outputRealView, long outputRealImage, int outputRealFormat,
	                         int width, int height, int mvecDepthWidth, int mvecDepthHeight,
	                         int multiFrameCount, int multiFrameIndex,
	                         float mvecScaleX, float mvecScaleY,
	                         int depthInverted, int colorBuffersHDR, int cameraMotionIncluded, int reset,
	                         MemorySegment cameraViewToClip, MemorySegment clipToCameraView,
	                         MemorySegment clipToPrevClip, MemorySegment prevClipToClip) {
		try {
			return (int) this.evaluateDlssg.invokeExact(cmd, feature,
					backbufferView, backbufferImage, backbufferFormat,
					depthView, depthImage, depthFormat,
					mvecView, mvecImage, mvecFormat,
					hudlessView, hudlessImage, hudlessFormat,
					uiView, uiImage, uiFormat,
					outputInterpView, outputInterpImage, outputInterpFormat,
					outputRealView, outputRealImage, outputRealFormat,
					width, height, mvecDepthWidth, mvecDepthHeight,
					multiFrameCount, multiFrameIndex, mvecScaleX, mvecScaleY,
					depthInverted, colorBuffersHDR, cameraMotionIncluded, reset,
					cameraViewToClip, clipToCameraView, clipToPrevClip, prevClipToClip);
		} catch (Throwable t) {
			throw new RuntimeException("ngxshim_evaluate_dlssg failed", t);
		}
	}

	public void release(MemorySegment feature) {
		try {
			this.release.invokeExact(feature);
		} catch (Throwable t) {
			throw new RuntimeException("ngxshim_release failed", t);
		}
	}

	public void shutdown(long vkDevice) {
		try {
			this.shutdown.invokeExact(vkDevice);
		} catch (Throwable t) {
			throw new RuntimeException("ngxshim_shutdown failed", t);
		}
	}

	public int lastResult() {
		try {
			return (int) this.lastResult.invokeExact();
		} catch (Throwable t) {
			throw new RuntimeException("ngxshim_last_result failed", t);
		}
	}
}
