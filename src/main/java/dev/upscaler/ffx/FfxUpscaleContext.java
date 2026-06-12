package dev.upscaler.ffx;

import dev.upscaler.UpscalerMod;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * An FSR 3.1 upscale context on the Vulkan backend.
 *
 * <p>Struct layouts are hand-laid-out against the SDK 1.1.4 headers
 * ({@code ffx_api.h}, {@code ffx_upscale.h}, {@code vk/ffx_api_vk.h}); every
 * offset below is annotated with the C member it corresponds to. All structs
 * start with {@code ffxApiHeader { uint64_t type; void* pNext; }} = 16 bytes.
 */
public final class FfxUpscaleContext implements AutoCloseable {
	// ffx_upscale.h / ffx_api_vk.h struct type ids
	private static final long TYPE_CREATE_CONTEXT_UPSCALE = 0x00010000L;
	private static final long TYPE_CREATE_BACKEND_VK = 0x0000003L;
	private static final long TYPE_QUERY_GET_VERSIONS = 4L;
	private static final long TYPE_QUERY_UPSCALE_RATIO = 0x00010002L;
	private static final long TYPE_QUERY_RENDER_RESOLUTION = 0x00010003L;
	private static final long TYPE_QUERY_JITTER_PHASE_COUNT = 0x00010004L;
	private static final long TYPE_QUERY_JITTER_OFFSET = 0x00010005L;
	private static final long TYPE_QUERY_GPU_MEMORY_USAGE = 0x00010008L;

	// FfxApiCreateContextUpscaleFlags
	public static final int FLAG_HIGH_DYNAMIC_RANGE = 1 << 0;
	public static final int FLAG_DISPLAY_RES_MOTION_VECTORS = 1 << 1;
	public static final int FLAG_MV_JITTER_CANCELLATION = 1 << 2;
	public static final int FLAG_DEPTH_INVERTED = 1 << 3;
	public static final int FLAG_DEPTH_INFINITE = 1 << 4;
	public static final int FLAG_AUTO_EXPOSURE = 1 << 5;
	public static final int FLAG_DYNAMIC_RESOLUTION = 1 << 6;
	public static final int FLAG_DEBUG_CHECKING = 1 << 7;
	public static final int FLAG_NON_LINEAR_COLORSPACE = 1 << 8;

	// FfxApiUpscaleQualityMode
	public static final int QUALITY_NATIVE_AA = 0;
	public static final int QUALITY_QUALITY = 1;
	public static final int QUALITY_BALANCED = 2;
	public static final int QUALITY_PERFORMANCE = 3;
	public static final int QUALITY_ULTRA_PERFORMANCE = 4;

	private final FfxLibrary lib;
	private final Arena arena;
	private final MemorySegment ctxSlot;
	private boolean destroyed;

	private FfxUpscaleContext(FfxLibrary lib, Arena arena, MemorySegment ctxSlot) {
		this.lib = lib;
		this.arena = arena;
		this.ctxSlot = ctxSlot;
	}

	public record ProviderVersion(long id, String name) { }

	/**
	 * Context-less ABI sanity probe: enumerates available upscale provider versions
	 * (struct ffxQueryDescGetVersions, 56 bytes). Touches no device — if this works,
	 * the FFM plumbing and header chaining are correct.
	 */
	public static java.util.List<ProviderVersion> queryAvailableVersions(FfxLibrary lib) {
		try (Arena local = Arena.ofConfined()) {
			//   0 header, 16 u64 createDescType, 24 void* device,
			//   32 u64* outputCount, 40 u64* versionIds, 48 const char** versionNames
			MemorySegment count = local.allocate(ValueLayout.JAVA_LONG);
			MemorySegment desc = local.allocate(56, 8);
			desc.set(ValueLayout.JAVA_LONG, 0, TYPE_QUERY_GET_VERSIONS);
			desc.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
			desc.set(ValueLayout.JAVA_LONG, 16, TYPE_CREATE_CONTEXT_UPSCALE);
			desc.set(ValueLayout.ADDRESS, 24, MemorySegment.NULL);
			desc.set(ValueLayout.ADDRESS, 32, count);
			desc.set(ValueLayout.ADDRESS, 40, MemorySegment.NULL);
			desc.set(ValueLayout.ADDRESS, 48, MemorySegment.NULL);

			int rc = lib.queryGlobal(desc);
			if (rc != FfxLibrary.RETURN_OK) {
				throw new FfxException("ffxQuery(GetVersions, count)", rc);
			}
			int n = (int) count.get(ValueLayout.JAVA_LONG, 0);
			if (n <= 0) {
				return java.util.List.of();
			}

			MemorySegment ids = local.allocate(ValueLayout.JAVA_LONG, n);
			MemorySegment names = local.allocate(ValueLayout.ADDRESS, n);
			desc.set(ValueLayout.ADDRESS, 40, ids);
			desc.set(ValueLayout.ADDRESS, 48, names);
			rc = lib.queryGlobal(desc);
			if (rc != FfxLibrary.RETURN_OK) {
				throw new FfxException("ffxQuery(GetVersions, list)", rc);
			}

			var result = new java.util.ArrayList<ProviderVersion>(n);
			for (int i = 0; i < n; i++) {
				MemorySegment namePtr = names.getAtIndex(ValueLayout.ADDRESS, i);
				String name = namePtr.equals(MemorySegment.NULL)
						? "<unnamed>"
						: namePtr.reinterpret(256).getString(0);
				result.add(new ProviderVersion(ids.getAtIndex(ValueLayout.JAVA_LONG, i), name));
			}
			return result;
		}
	}

	/**
	 * Creates the upscale context against an existing Vulkan device.
	 *
	 * @param vkGetDeviceProcAddr raw {@code PFN_vkGetDeviceProcAddr} function pointer
	 * @param useMessageCallback  install the wchar_t log callback (skip while bisecting native crashes)
	 */
	public static FfxUpscaleContext create(FfxLibrary lib,
	                                       long vkDevice, long vkPhysicalDevice, long vkGetDeviceProcAddr,
	                                       int maxRenderWidth, int maxRenderHeight,
	                                       int maxUpscaleWidth, int maxUpscaleHeight,
	                                       int flags, boolean useMessageCallback) {
		Arena arena = Arena.ofShared();
		try {
			// struct ffxCreateContextDescUpscale (48 bytes):
			//   0  ffxApiHeader header        { u64 type; void* pNext; }
			//   16 uint32_t flags
			//   20 FfxApiDimensions2D maxRenderSize   { u32 width; u32 height; }
			//   28 FfxApiDimensions2D maxUpscaleSize  { u32 width; u32 height; }
			//   40 ffxApiMessage fpMessage   (8-aligned, 4 bytes padding before)
			MemorySegment createDesc = arena.allocate(48, 8);
			createDesc.set(ValueLayout.JAVA_LONG, 0, TYPE_CREATE_CONTEXT_UPSCALE);
			createDesc.set(ValueLayout.JAVA_INT, 16, flags);
			createDesc.set(ValueLayout.JAVA_INT, 20, maxRenderWidth);
			createDesc.set(ValueLayout.JAVA_INT, 24, maxRenderHeight);
			createDesc.set(ValueLayout.JAVA_INT, 28, maxUpscaleWidth);
			createDesc.set(ValueLayout.JAVA_INT, 32, maxUpscaleHeight);
			createDesc.set(ValueLayout.ADDRESS, 40, useMessageCallback ? messageCallback(arena) : MemorySegment.NULL);

			// struct ffxCreateBackendVKDesc (40 bytes):
			//   0  ffxApiHeader header
			//   16 VkDevice vkDevice
			//   24 VkPhysicalDevice vkPhysicalDevice
			//   32 PFN_vkGetDeviceProcAddr vkDeviceProcAddr
			MemorySegment backendDesc = arena.allocate(40, 8);
			backendDesc.set(ValueLayout.JAVA_LONG, 0, TYPE_CREATE_BACKEND_VK);
			backendDesc.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
			backendDesc.set(ValueLayout.JAVA_LONG, 16, vkDevice);
			backendDesc.set(ValueLayout.JAVA_LONG, 24, vkPhysicalDevice);
			backendDesc.set(ValueLayout.JAVA_LONG, 32, vkGetDeviceProcAddr);

			// chain: upscale desc -> backend desc
			createDesc.set(ValueLayout.ADDRESS, 8, backendDesc);

			MemorySegment ctxSlot = arena.allocate(ValueLayout.ADDRESS);
			int rc = lib.createContext(ctxSlot, createDesc);
			if (rc != FfxLibrary.RETURN_OK) {
				arena.close();
				throw new FfxException("ffxCreateContext", rc);
			}
			return new FfxUpscaleContext(lib, arena, ctxSlot);
		} catch (RuntimeException | Error e) {
			if (e instanceof FfxException) {
				throw e;
			}
			arena.close();
			throw e;
		}
	}

	/** {@code wchar_t*} on Windows is UTF-16; read until NUL (bounded). */
	private static MemorySegment messageCallback(Arena arena) {
		try {
			var handle = MethodHandles.lookup().findStatic(FfxUpscaleContext.class, "onFfxMessage",
					MethodType.methodType(void.class, int.class, MemorySegment.class));
			return Linker.nativeLinker().upcallStub(handle,
					FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.ADDRESS), arena);
		} catch (ReflectiveOperationException e) {
			UpscalerMod.LOGGER.warn("Could not create FFX message callback; runtime messages will be lost", e);
			return MemorySegment.NULL;
		}
	}

	private static final java.util.Set<String> SEEN_MESSAGES = java.util.concurrent.ConcurrentHashMap.newKeySet();

	@SuppressWarnings("unused") // upcall target
	private static void onFfxMessage(int type, MemorySegment message) {
		String text = readWideString(message);
		if (!SEEN_MESSAGES.add(text)) {
			return; // repeated every frame otherwise
		}
		if (type == 0) {
			UpscalerMod.LOGGER.error("[FFX] {}", text);
		} else {
			UpscalerMod.LOGGER.warn("[FFX] {} (further occurrences suppressed)", text);
		}
	}

	private static String readWideString(MemorySegment ptr) {
		if (ptr.equals(MemorySegment.NULL)) {
			return "<null>";
		}
		MemorySegment data = ptr.reinterpret(8192);
		StringBuilder sb = new StringBuilder();
		for (long i = 0; i < 4096; i++) {
			char c = data.getAtIndex(ValueLayout.JAVA_CHAR, i);
			if (c == 0) {
				break;
			}
			sb.append(c);
		}
		return sb.toString();
	}

	public record RenderResolution(int width, int height) { }

	/** struct ffxQueryDescUpscaleGetRenderResolutionFromQualityMode (48 bytes). */
	public RenderResolution queryRenderResolution(int displayWidth, int displayHeight, int qualityMode) {
		try (Arena local = Arena.ofConfined()) {
			MemorySegment outW = local.allocate(ValueLayout.JAVA_INT);
			MemorySegment outH = local.allocate(ValueLayout.JAVA_INT);
			//   0  header, 16 displayWidth, 20 displayHeight, 24 qualityMode,
			//   28 pad, 32 pOutRenderWidth, 40 pOutRenderHeight
			MemorySegment desc = local.allocate(48, 8);
			desc.set(ValueLayout.JAVA_LONG, 0, TYPE_QUERY_RENDER_RESOLUTION);
			desc.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
			desc.set(ValueLayout.JAVA_INT, 16, displayWidth);
			desc.set(ValueLayout.JAVA_INT, 20, displayHeight);
			desc.set(ValueLayout.JAVA_INT, 24, qualityMode);
			desc.set(ValueLayout.ADDRESS, 32, outW);
			desc.set(ValueLayout.ADDRESS, 40, outH);

			int rc = this.lib.query(this.ctxSlot, desc);
			if (rc != FfxLibrary.RETURN_OK) {
				throw new FfxException("ffxQuery(GetRenderResolutionFromQualityMode)", rc);
			}
			return new RenderResolution(outW.get(ValueLayout.JAVA_INT, 0), outH.get(ValueLayout.JAVA_INT, 0));
		}
	}

	/** struct ffxQueryDescUpscaleGetUpscaleRatioFromQualityMode (32 bytes). */
	public float queryUpscaleRatio(int qualityMode) {
		try (Arena local = Arena.ofConfined()) {
			MemorySegment out = local.allocate(ValueLayout.JAVA_FLOAT);
			//   0 header, 16 qualityMode, 20 pad, 24 pOutUpscaleRatio
			MemorySegment desc = local.allocate(32, 8);
			desc.set(ValueLayout.JAVA_LONG, 0, TYPE_QUERY_UPSCALE_RATIO);
			desc.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
			desc.set(ValueLayout.JAVA_INT, 16, qualityMode);
			desc.set(ValueLayout.ADDRESS, 24, out);

			int rc = this.lib.query(this.ctxSlot, desc);
			if (rc != FfxLibrary.RETURN_OK) {
				throw new FfxException("ffxQuery(GetUpscaleRatioFromQualityMode)", rc);
			}
			return out.get(ValueLayout.JAVA_FLOAT, 0);
		}
	}

	/** struct ffxQueryDescUpscaleGetJitterPhaseCount (32 bytes). */
	public int queryJitterPhaseCount(int renderWidth, int displayWidth) {
		try (Arena local = Arena.ofConfined()) {
			MemorySegment out = local.allocate(ValueLayout.JAVA_INT);
			//   0 header, 16 renderWidth, 20 displayWidth, 24 pOutPhaseCount
			MemorySegment desc = local.allocate(32, 8);
			desc.set(ValueLayout.JAVA_LONG, 0, TYPE_QUERY_JITTER_PHASE_COUNT);
			desc.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
			desc.set(ValueLayout.JAVA_INT, 16, renderWidth);
			desc.set(ValueLayout.JAVA_INT, 20, displayWidth);
			desc.set(ValueLayout.ADDRESS, 24, out);

			int rc = this.lib.query(this.ctxSlot, desc);
			if (rc != FfxLibrary.RETURN_OK) {
				throw new FfxException("ffxQuery(GetJitterPhaseCount)", rc);
			}
			return out.get(ValueLayout.JAVA_INT, 0);
		}
	}

	public record JitterOffset(float x, float y) { }

	/** struct ffxQueryDescUpscaleGetJitterOffset (40 bytes). */
	public JitterOffset queryJitterOffset(int index, int phaseCount) {
		try (Arena local = Arena.ofConfined()) {
			MemorySegment outX = local.allocate(ValueLayout.JAVA_FLOAT);
			MemorySegment outY = local.allocate(ValueLayout.JAVA_FLOAT);
			//   0 header, 16 index, 20 phaseCount, 24 pOutX, 32 pOutY
			MemorySegment desc = local.allocate(40, 8);
			desc.set(ValueLayout.JAVA_LONG, 0, TYPE_QUERY_JITTER_OFFSET);
			desc.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
			desc.set(ValueLayout.JAVA_INT, 16, index);
			desc.set(ValueLayout.JAVA_INT, 20, phaseCount);
			desc.set(ValueLayout.ADDRESS, 24, outX);
			desc.set(ValueLayout.ADDRESS, 32, outY);

			int rc = this.lib.query(this.ctxSlot, desc);
			if (rc != FfxLibrary.RETURN_OK) {
				throw new FfxException("ffxQuery(GetJitterOffset)", rc);
			}
			return new JitterOffset(outX.get(ValueLayout.JAVA_FLOAT, 0), outY.get(ValueLayout.JAVA_FLOAT, 0));
		}
	}

	public record GpuMemoryUsage(long totalBytes, long aliasableBytes) { }

	/** struct ffxQueryDescUpscaleGetGPUMemoryUsage (24 bytes) -> FfxApiEffectMemoryUsage { u64; u64; }. */
	public GpuMemoryUsage queryGpuMemoryUsage() {
		try (Arena local = Arena.ofConfined()) {
			MemorySegment usage = local.allocate(16, 8);
			MemorySegment desc = local.allocate(24, 8);
			desc.set(ValueLayout.JAVA_LONG, 0, TYPE_QUERY_GPU_MEMORY_USAGE);
			desc.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
			desc.set(ValueLayout.ADDRESS, 16, usage);

			int rc = this.lib.query(this.ctxSlot, desc);
			if (rc != FfxLibrary.RETURN_OK) {
				throw new FfxException("ffxQuery(GetGPUMemoryUsage)", rc);
			}
			return new GpuMemoryUsage(usage.get(ValueLayout.JAVA_LONG, 0), usage.get(ValueLayout.JAVA_LONG, 8));
		}
	}

	// FfxApiSurfaceFormat (ffx_api_types.h, by enum position)
	public static final int FORMAT_R8G8B8A8_UNORM = 10;
	public static final int FORMAT_R16G16_FLOAT = 18;
	public static final int FORMAT_R32_FLOAT = 28;

	// FfxApiResourceState
	public static final int STATE_COMMON = 1 << 0;
	public static final int STATE_UNORDERED_ACCESS = 1 << 1;

	// FfxApiResourceUsage
	public static final int USAGE_READ_ONLY = 0;
	public static final int USAGE_UAV = 1 << 1;
	public static final int USAGE_DEPTHTARGET = 1 << 2;

	// FfxApiDispatchFsrUpscaleFlags
	public static final int DISPATCH_FLAG_NON_LINEAR_COLOR_SRGB = 1 << 1;

	private static final long TYPE_DISPATCH_UPSCALE = 0x00010001L;
	private static final int RESOURCE_TYPE_TEXTURE2D = 2; // FfxApiResourceType

	/** One input/output image for {@link #dispatchUpscale}. */
	public record Resource(long vkImage, int format, int width, int height, int usage, int state) { }

	public record DispatchParams(
			long vkCommandBuffer,
			Resource color, Resource depth, Resource motionVectors, Resource output,
			float jitterX, float jitterY,
			float motionVectorScaleX, float motionVectorScaleY,
			int renderWidth, int renderHeight,
			int upscaleWidth, int upscaleHeight,
			float frameTimeDeltaMs, boolean reset,
			float cameraNear, float cameraFar, float cameraFovAngleVertical,
			int flags) { }

	/**
	 * Records the FSR upscale dispatch into the given command buffer.
	 * struct ffxDispatchDescUpscale (432 bytes, offsets per SDK 1.1.4 headers).
	 */
	public void dispatchUpscale(DispatchParams p) {
		try (Arena local = Arena.ofConfined()) {
			MemorySegment desc = local.allocate(432, 8);
			desc.set(ValueLayout.JAVA_LONG, 0, TYPE_DISPATCH_UPSCALE);
			desc.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
			desc.set(ValueLayout.JAVA_LONG, 16, p.vkCommandBuffer());          // commandList
			putResource(desc, 24, p.color());                                  // color
			putResource(desc, 72, p.depth());                                  // depth
			putResource(desc, 120, p.motionVectors());                         // motionVectors
			putEmptyResource(desc, 168);                                       // exposure
			putEmptyResource(desc, 216);                                       // reactive
			putEmptyResource(desc, 264);                                       // transparencyAndComposition
			putResource(desc, 312, p.output());                                // output
			desc.set(ValueLayout.JAVA_FLOAT, 360, p.jitterX());
			desc.set(ValueLayout.JAVA_FLOAT, 364, p.jitterY());
			desc.set(ValueLayout.JAVA_FLOAT, 368, p.motionVectorScaleX());
			desc.set(ValueLayout.JAVA_FLOAT, 372, p.motionVectorScaleY());
			desc.set(ValueLayout.JAVA_INT, 376, p.renderWidth());
			desc.set(ValueLayout.JAVA_INT, 380, p.renderHeight());
			desc.set(ValueLayout.JAVA_INT, 384, p.upscaleWidth());
			desc.set(ValueLayout.JAVA_INT, 388, p.upscaleHeight());
			desc.set(ValueLayout.JAVA_BYTE, 392, (byte) 0);                    // enableSharpening
			desc.set(ValueLayout.JAVA_FLOAT, 396, 0.0f);                       // sharpness
			desc.set(ValueLayout.JAVA_FLOAT, 400, p.frameTimeDeltaMs());
			desc.set(ValueLayout.JAVA_FLOAT, 404, 1.0f);                       // preExposure
			desc.set(ValueLayout.JAVA_BYTE, 408, (byte) (p.reset() ? 1 : 0));
			desc.set(ValueLayout.JAVA_FLOAT, 412, p.cameraNear());
			desc.set(ValueLayout.JAVA_FLOAT, 416, p.cameraFar());
			desc.set(ValueLayout.JAVA_FLOAT, 420, p.cameraFovAngleVertical());
			desc.set(ValueLayout.JAVA_FLOAT, 424, 1.0f);                       // viewSpaceToMetersFactor
			desc.set(ValueLayout.JAVA_INT, 428, p.flags());

			int rc = this.lib.dispatch(this.ctxSlot, desc);
			if (rc != FfxLibrary.RETURN_OK) {
				throw new FfxException("ffxDispatch(Upscale)", rc);
			}
		}
	}

	/** struct FfxApiResource (48 bytes): resource(8), description(32: type/format/w/h/depth/mips/flags/usage), state(4), pad(4). */
	private static void putResource(MemorySegment desc, long offset, Resource r) {
		desc.set(ValueLayout.JAVA_LONG, offset, r.vkImage());
		desc.set(ValueLayout.JAVA_INT, offset + 8, RESOURCE_TYPE_TEXTURE2D);
		desc.set(ValueLayout.JAVA_INT, offset + 12, r.format());
		desc.set(ValueLayout.JAVA_INT, offset + 16, r.width());
		desc.set(ValueLayout.JAVA_INT, offset + 20, r.height());
		desc.set(ValueLayout.JAVA_INT, offset + 24, 1);  // depth
		desc.set(ValueLayout.JAVA_INT, offset + 28, 1);  // mipCount
		desc.set(ValueLayout.JAVA_INT, offset + 32, 0);  // flags
		desc.set(ValueLayout.JAVA_INT, offset + 36, r.usage());
		desc.set(ValueLayout.JAVA_INT, offset + 40, r.state());
	}

	private static void putEmptyResource(MemorySegment desc, long offset) {
		desc.asSlice(offset, 48).fill((byte) 0);
	}

	@Override
	public void close() {
		if (this.destroyed) {
			return;
		}
		this.destroyed = true;
		int rc = this.lib.destroyContext(this.ctxSlot);
		this.arena.close();
		if (rc != FfxLibrary.RETURN_OK) {
			throw new FfxException("ffxDestroyContext", rc);
		}
	}

	public static final class FfxException extends RuntimeException {
		public final int returnCode;

		public FfxException(String call, int returnCode) {
			super(call + " failed: " + FfxLibrary.describeReturnCode(returnCode));
			this.returnCode = returnCode;
		}
	}
}
