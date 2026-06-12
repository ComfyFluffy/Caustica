package dev.upscaler.ffx;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * FFM bindings for the AMD FidelityFX API (amd_fidelityfx_vk.dll, SDK 1.1.4 / FSR 3.1.4).
 *
 * <p>The ffx-api surface is four C functions operating on descriptor chains
 * (see {@code ffx_api.h}):
 * <pre>
 *   ffxReturnCode_t ffxCreateContext(ffxContext* context, ffxCreateContextDescHeader* desc, const ffxAllocationCallbacks* memCb);
 *   ffxReturnCode_t ffxDestroyContext(ffxContext* context, const ffxAllocationCallbacks* memCb);
 *   ffxReturnCode_t ffxConfigure(ffxContext* context, const ffxConfigureDescHeader* desc);
 *   ffxReturnCode_t ffxQuery(ffxContext* context, ffxQueryDescHeader* desc);
 *   ffxReturnCode_t ffxDispatch(ffxContext* context, const ffxDispatchDescHeader* desc);
 * </pre>
 */
public final class FfxLibrary {
	public static final int RETURN_OK = 0;

	private static final Linker LINKER = Linker.nativeLinker();
	private static final FunctionDescriptor DESC_3ARG =
			FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
	private static final FunctionDescriptor DESC_2ARG =
			FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

	private final Path source;
	private final MethodHandle createContext;
	private final MethodHandle destroyContext;
	private final MethodHandle configure;
	private final MethodHandle query;
	private final MethodHandle dispatch;

	private FfxLibrary(Path source, SymbolLookup lookup) {
		this.source = source;
		this.createContext = LINKER.downcallHandle(find(lookup, "ffxCreateContext"), DESC_3ARG);
		this.destroyContext = LINKER.downcallHandle(find(lookup, "ffxDestroyContext"), DESC_2ARG);
		this.configure = LINKER.downcallHandle(find(lookup, "ffxConfigure"), DESC_2ARG);
		this.query = LINKER.downcallHandle(find(lookup, "ffxQuery"), DESC_2ARG);
		this.dispatch = LINKER.downcallHandle(find(lookup, "ffxDispatch"), DESC_2ARG);
	}

	/**
	 * Loads the FFX runtime from the given DLL path. The library stays loaded for the
	 * lifetime of the JVM (global arena) — contexts created from it must remain valid
	 * until explicitly destroyed.
	 */
	public static FfxLibrary load(Path dll) {
		SymbolLookup lookup = SymbolLookup.libraryLookup(dll, Arena.global());
		return new FfxLibrary(dll, lookup);
	}

	private static MemorySegment find(SymbolLookup lookup, String name) {
		return lookup.find(name)
				.orElseThrow(() -> new IllegalStateException("FFX library does not export " + name));
	}

	public Path source() {
		return this.source;
	}

	/** @param ctxSlot pointer-sized slot receiving the ffxContext handle */
	public int createContext(MemorySegment ctxSlot, MemorySegment descChain) {
		try {
			return (int) this.createContext.invokeExact(ctxSlot, descChain, MemorySegment.NULL);
		} catch (Throwable t) {
			throw new RuntimeException("ffxCreateContext invocation failed", t);
		}
	}

	public int destroyContext(MemorySegment ctxSlot) {
		try {
			return (int) this.destroyContext.invokeExact(ctxSlot, MemorySegment.NULL);
		} catch (Throwable t) {
			throw new RuntimeException("ffxDestroyContext invocation failed", t);
		}
	}

	public int configure(MemorySegment ctxSlot, MemorySegment desc) {
		try {
			return (int) this.configure.invokeExact(ctxSlot, desc);
		} catch (Throwable t) {
			throw new RuntimeException("ffxConfigure invocation failed", t);
		}
	}

	public int query(MemorySegment ctxSlot, MemorySegment desc) {
		try {
			return (int) this.query.invokeExact(ctxSlot, desc);
		} catch (Throwable t) {
			throw new RuntimeException("ffxQuery invocation failed", t);
		}
	}

	/** Context-less query ("operates on any global state" per ffx_api.h). */
	public int queryGlobal(MemorySegment desc) {
		return query(MemorySegment.NULL, desc);
	}

	public int dispatch(MemorySegment ctxSlot, MemorySegment desc) {
		try {
			return (int) this.dispatch.invokeExact(ctxSlot, desc);
		} catch (Throwable t) {
			throw new RuntimeException("ffxDispatch invocation failed", t);
		}
	}

	public static String describeReturnCode(int code) {
		return switch (code) {
			case 0 -> "FFX_API_RETURN_OK";
			case 1 -> "FFX_API_RETURN_ERROR";
			case 2 -> "FFX_API_RETURN_ERROR_UNKNOWN_DESCTYPE";
			case 3 -> "FFX_API_RETURN_ERROR_RUNTIME_ERROR";
			case 4 -> "FFX_API_RETURN_NO_PROVIDER";
			case 5 -> "FFX_API_RETURN_ERROR_MEMORY";
			case 6 -> "FFX_API_RETURN_ERROR_PARAMETER";
			default -> "unknown (" + code + ")";
		};
	}
}
