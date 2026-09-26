package dev.comfyfluffy.caustica.nvidia.ngx;

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
 * {@code packages/nvidia-ngx/native/ngx_shim/ngx_shim.cpp}). All Caustica-specific structs and
 * NGX parameter blocks live in the shim; Java only passes primitives and raw
 * Vulkan handles (as {@code long} addresses).
 */
final class NgxLibrary {
	private static final Linker LINKER = Linker.nativeLinker();

	private final MethodHandle init;
	private final MethodHandle dlssAvailable;
	private final MethodHandle queryOptimal;
	private final MethodHandle createDlss;
	private final MethodHandle evaluate;
	private final MethodHandle dlssdAvailable;
	private final MethodHandle queryOptimalDlssd;
	private final MethodHandle createDlssd;
	private final MethodHandle evaluateDlssd;
	private final MethodHandle dlssgAvailable;
	private final MethodHandle createDlssg;
	private final MethodHandle evaluateDlssg;
	private final MethodHandle release;
	private final MethodHandle shutdown;
	private final MethodHandle lastResult;

	private NgxLibrary(SymbolLookup lookup) {
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
		// int ngxshim_evaluate(cmd, feature, [color/depth/mv/out: view,img,fmt]*4, rw,rh,dw,dh, jx,jy,mvsx,mvsy, reset, frameMs, preExposure)
		this.evaluate = handle(lookup, "ngxshim_evaluate",
				FunctionDescriptor.of(ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
						ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));
		// DLSS Ray Reconstruction (DLSSD) — same create ABI as DLSS-SR; evaluate adds the diffuse
		// albedo / specular albedo / normals guide buffers (roughness is packed in normals.w).
		this.dlssdAvailable = handle(lookup, "ngxshim_dlssd_available",
				FunctionDescriptor.of(ValueLayout.JAVA_INT));
		// int ngxshim_query_optimal_dlssd(u32 dispW, u32 dispH, int quality, u32* outRW, u32* outRH, float* outSharp)
		this.queryOptimalDlssd = handle(lookup, "ngxshim_query_optimal_dlssd",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		this.createDlssd = handle(lookup, "ngxshim_create_dlssd",
				FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
		// int ngxshim_evaluate_dlssd(cmd, feature, [color/depth/mv/diffAlbedo/specAlbedo/normals/specMotion/responsivity/out: view,img,fmt]*9, rw,rh,dw,dh, jx,jy,mvsx,mvsy, reset, frameMs, preExposure)
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
						ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));
		// DLSS Frame Generation (DLSSG).
		this.dlssgAvailable = handle(lookup, "ngxshim_dlssg_available",
				FunctionDescriptor.of(ValueLayout.JAVA_INT));
		// void* ngxshim_create_dlssg(cmd, u32 w, u32 h, int nativeBackbufferFormat)
		this.createDlssg = handle(lookup, "ngxshim_create_dlssg",
				FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
						ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
		// int ngxshim_evaluate_dlssg_2x(cmd, feature, [backbuffer/depth/mvec/hudless/ui/outInterp/outReal: view,img,fmt]*7,
		//   w,h, mvecDepthW, mvecDepthH, mvScaleX, mvScaleY, depthInv, hdr, camMotion, reset, 4 matrices)
		this.evaluateDlssg = handle(lookup, "ngxshim_evaluate_dlssg_2x",
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

	public int[] queryOptimal(int displayWidth, int displayHeight, int quality) {
		return queryRenderSize(this.queryOptimal, "ngxshim_query_optimal", displayWidth, displayHeight, quality);
	}

	private static int[] queryRenderSize(MethodHandle query, String symbol,
	                                    int displayWidth, int displayHeight, int quality) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment outWidth = arena.allocate(ValueLayout.JAVA_INT);
			MemorySegment outHeight = arena.allocate(ValueLayout.JAVA_INT);
			MemorySegment outSharpness = arena.allocate(ValueLayout.JAVA_FLOAT);
			int result;
			try {
				result = (int) query.invokeExact(displayWidth, displayHeight, quality,
						outWidth, outHeight, outSharpness);
			} catch (Throwable failure) {
				throw new RuntimeException(symbol + " failed", failure);
			}
			if (NgxRuntime.ngxFailed(result)) {
				throw new IllegalStateException(symbol + " failed: 0x" + Integer.toHexString(result));
			}
			int width = outWidth.get(ValueLayout.JAVA_INT, 0);
			int height = outHeight.get(ValueLayout.JAVA_INT, 0);
			if (width <= 0 || height <= 0) {
				throw new IllegalStateException(symbol + " returned invalid render size " + width + "x" + height);
			}
			return new int[]{width, height};
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
	                    int reset, float frameTimeMs, float preExposure) {
		try {
			return (int) this.evaluate.invokeExact(cmd, feature,
					colorView, colorImage, colorFormat,
					depthView, depthImage, depthFormat,
					mvView, mvImage, mvFormat,
					outputView, outputImage, outputFormat,
					renderWidth, renderHeight, displayWidth, displayHeight,
					jitterX, jitterY, mvScaleX, mvScaleY, reset, frameTimeMs, preExposure);
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

	public int[] queryOptimalDlssd(int displayWidth, int displayHeight, int quality) {
		return queryRenderSize(this.queryOptimalDlssd, "ngxshim_query_optimal_dlssd", displayWidth, displayHeight, quality);
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
	                         long responsivityView, long responsivityImage, int responsivityFormat,
	                         long outputView, long outputImage, int outputFormat,
	                         int renderWidth, int renderHeight, int displayWidth, int displayHeight,
	                         float jitterX, float jitterY, float mvScaleX, float mvScaleY,
	                         int reset, float frameTimeMs, float preExposure) {
		try {
			return (int) this.evaluateDlssd.invokeExact(cmd, feature,
					colorView, colorImage, colorFormat,
					depthView, depthImage, depthFormat,
					mvView, mvImage, mvFormat,
					diffuseAlbedoView, diffuseAlbedoImage, diffuseAlbedoFormat,
					specularAlbedoView, specularAlbedoImage, specularAlbedoFormat,
					normalsView, normalsImage, normalsFormat,
					specularMotionView, specularMotionImage, specularMotionFormat,
					responsivityView, responsivityImage, responsivityFormat,
					outputView, outputImage, outputFormat,
					renderWidth, renderHeight, displayWidth, displayHeight,
					jitterX, jitterY, mvScaleX, mvScaleY, reset, frameTimeMs, preExposure);
		} catch (Throwable t) {
			throw new RuntimeException("ngxshim_evaluate_dlssd failed", t);
		}
	}

	public boolean dlssgAvailable() {
		try {
			return ((int) this.dlssgAvailable.invokeExact()) != 0;
		} catch (Throwable t) {
			throw new RuntimeException("ngxshim_dlssg_available failed", t);
		}
	}

	public MemorySegment createDlssg(long cmd, int width, int height, int nativeBackbufferFormat) {
		try {
			return (MemorySegment) this.createDlssg.invokeExact(cmd, width, height, nativeBackbufferFormat);
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
					mvecScaleX, mvecScaleY,
					depthInverted, colorBuffersHDR, cameraMotionIncluded, reset,
					cameraViewToClip, clipToCameraView, clipToPrevClip, prevClipToClip);
		} catch (Throwable t) {
			throw new RuntimeException("ngxshim_evaluate_dlssg_2x failed", t);
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
