package dev.comfyfluffy.caustica.rt.terrain;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads Distant Horizons' persistent terrain database and turns it into axis-aligned boxes that can be
 * meshed and put in the ray-tracing acceleration structure.
 *
 * <h2>Why this exists</h2>
 * Caustica builds every section from {@code level.getChunk(...)}, the client chunk cache. Beyond the
 * server's view distance there is no block data in the process at all — so a Caustica-native LOD can
 * reduce detail on loaded terrain, but it can never show terrain that was never sent. DH already solves
 * that problem: it maintains a durable, generated, streamed world database far past the chunk cache.
 *
 * <h2>What this deliberately does NOT do</h2>
 * It does not touch DH's renderer, intercept its draw calls, or read its GPU buffers. DH's rendering
 * should be switched OFF when this is used. DH is a data source here and Caustica does all drawing, as
 * rays. That avoids the whole class of problems that come from two world renderers coexisting, and it
 * means this survives DH changing its rendering backend — which it has done twice recently.
 *
 * <h2>Why reflection</h2>
 * DH is optional. A hard compile-time dependency would make Caustica refuse to load without it, and
 * would pin a DH version. Everything here resolves lazily and degrades to "no LOD" if DH is absent, is
 * a version whose API moved, or has not finished loading a world. The API surface used is small and
 * documented — {@code DhApi.Delayed.terrainRepo}, {@code DhApi.Delayed.worldProxy}, and
 * {@code IDhApiTerrainDataRepo.getAllTerrainDataAtDetailLevelAndPos} — and it is a versioned public
 * API rather than internals, so it is a reasonable thing to bind to loosely.
 *
 * <h2>Data shape</h2>
 * DH returns {@code DhApiTerrainDataPoint[][][]} indexed [x][z][column entry]. Each entry is already a
 * vertical RUN — a {@code bottomYBlockPos}..{@code topYBlockPos} span of one block state, with baked
 * block and sky light. That is run-length encoding done for us: a 200-block stone column is one entry,
 * not 200. Emitting one box per entry is therefore already a large win before any greedy merging, and
 * it is why this does not need a general voxel mesher.
 *
 * <p>Crucially the entry carries a {@code blockStateWrapper} whose {@code getWrappedMcObject()} is a real
 * Minecraft {@link BlockState}. That means distant terrain can resolve to the SAME material table entries
 * as near terrain — roughness, metalness, emission, LabPBR maps — rather than the baked vertex colour a
 * renderer-interception approach would give you. Distant lava glowing correctly is downstream of this
 * one method call.
 */
public final class RtDhLodSource {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica/DhLod");
    private static final String DH_MOD_ID = "distanthorizons";

    /** Resolution states. Resolved once; a failure is remembered so a broken API is not retried per frame. */
    private enum State { UNRESOLVED, READY, UNAVAILABLE }

    private static State state = State.UNRESOLVED;
    private static Object terrainRepo;
    private static Object worldProxy;
    private static Method getAllTerrainDataAtDetailLevelAndPos;
    private static Method createSoftCache;
    private static Method getSinglePlayerLevel;
    private static Method getAllLoadedLevelWrappers;
    /**
     * A soft cache DH's own API creates and owns. Passing null for this parameter is not "no cache" —
     * DH treats it as a hard failure and every query returns unsuccessful with the message "Missing
     * [IDhApiTerrainDataCache]". That single null was the actual cause of every LOD query failing;
     * the footprint-vs-area fix and the diagnostics were correct but never got to matter.
     */
    private static Object softCache;
    private static Field resultPayload;
    private static Field resultSuccess;
    private static Field resultMessage;
    private static Field pointBottomY;
    private static Field pointTopY;
    private static Field pointBlockLight;
    private static Field pointSkyLight;
    private static Field pointBlockState;
    private static Method wrapperGetMcObject;
    private static final java.util.concurrent.atomic.AtomicBoolean loggedQueryFailure =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean loggedQuerySuccess =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean loggedNoLevel =
            new java.util.concurrent.atomic.AtomicBoolean();

    private RtDhLodSource() {
    }

    /**
     * One vertical run of a single block state, in world coordinates. {@code topY} is exclusive, matching
     * DH's convention, so an empty run is representable and callers do not need an off-by-one guard.
     */
    public record LodBox(int blockX, int bottomY, int topY, int blockZ, int sizeXZ,
                         BlockState blockState, int blockLight, int skyLight) {
        public int heightBlocks() {
            return topY - bottomY;
        }
    }

    public static synchronized boolean available() {
        resolve();
        return state == State.READY;
    }

    private static void resolve() {
        if (state != State.UNRESOLVED) {
            return;
        }
        if (!FabricLoader.getInstance().isModLoaded(DH_MOD_ID)) {
            state = State.UNAVAILABLE;
            return;
        }
        try {
            Class<?> dhApi = Class.forName("com.seibel.distanthorizons.api.DhApi");
            Class<?> delayed = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
            terrainRepo = delayed.getField("terrainRepo").get(null);
            worldProxy = delayed.getField("worldProxy").get(null);
            if (terrainRepo == null || worldProxy == null) {
                // DH is present but has not finished initialising. Stay UNRESOLVED so this retries:
                // the fields are populated after world load, not at mod init.
                terrainRepo = null;
                worldProxy = null;
                return;
            }

            Class<?> repoInterface = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo");
            Class<?> levelWrapper = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper");
            Class<?> cacheInterface = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache");
            getAllTerrainDataAtDetailLevelAndPos = repoInterface.getMethod(
                    "getAllTerrainDataAtDetailLevelAndPos",
                    levelWrapper, byte.class, int.class, int.class, cacheInterface);
            createSoftCache = repoInterface.getMethod("createSoftCache");

            // Two accessors, because they cover different worlds. getSinglePlayerLevel throws on a
            // multiplayer server, which would have ruled out exactly the case that matters here:
            // Wynncraft with WynnLODGrabber, where DH's database is populated for a SERVER world.
            // getAllLoadedLevelWrappers covers that, at the cost of having to pick — see levelWrapper().
            Class<?> worldProxyInterface = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy");
            getSinglePlayerLevel = worldProxyInterface.getMethod("getSinglePlayerLevel");
            getAllLoadedLevelWrappers = worldProxyInterface.getMethod("getAllLoadedLevelWrappers");

            // DhApiResult exposes success/message/payload as public FIELDS, not getters.
            Class<?> resultClass = Class.forName("com.seibel.distanthorizons.api.objects.DhApiResult");
            resultSuccess = resultClass.getField("success");
            resultMessage = resultClass.getField("message");
            resultPayload = resultClass.getField("payload");

            Class<?> pointClass = Class.forName(
                    "com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint");
            pointBottomY = pointClass.getField("bottomYBlockPos");
            pointTopY = pointClass.getField("topYBlockPos");
            pointBlockLight = pointClass.getField("blockLightLevel");
            pointSkyLight = pointClass.getField("skyLightLevel");
            pointBlockState = pointClass.getField("blockStateWrapper");

            Class<?> unsafeWrapper = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.IDhApiUnsafeWrapper");
            wrapperGetMcObject = unsafeWrapper.getMethod("getWrappedMcObject");

            softCache = createSoftCache.invoke(terrainRepo);
            state = State.READY;
            LOGGER.info("Distant Horizons LOD source resolved ({})", dhApi.getName());
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A moved API is expected across DH majors and is not an error worth spamming: log once and
            // run without distant terrain, exactly as if DH were not installed.
            state = State.UNAVAILABLE;
            terrainRepo = null;
            worldProxy = null;
            LOGGER.warn("Distant Horizons is installed but its API did not resolve; "
                    + "distant LOD terrain disabled. {}", e.toString());
        }
    }

    /**
     * Fetches one square area of terrain and flattens it into boxes.
     *
     * <p><b>DH's detailLevel is the size of the QUERIED AREA, not the resolution of the data.</b> This
     * is the single most misreadable thing in the API and getting it wrong produces silence rather
     * than an error. From DH's own javadoc: 0 = block, 2 = 4x4 blocks, 4 = chunk, 9 = region. So
     * {@code detailLevel} 4 asks for one chunk's worth of columns at position (posX, posZ) measured in
     * chunks — it does NOT ask for chunk-resolution data.
     *
     * <p>Because of that, the caller passes the FOOTPRINT it wants covered and this derives everything
     * else. The returned grid's own dimensions then tell us the resolution DH actually had, which is
     * read from the array rather than assumed: DH may return a coarser grid than the footprint implies
     * if that is all its database holds, and silently treating a 4x4 grid as 16x16 would scatter
     * terrain across the section with holes between.
     *
     * <p><b>Call this off the render thread.</b> DH may hit disk. It is a database query, not a memory
     * read.
     *
     * @param footprintBlocks width of the square area to cover, in blocks; must be a power of two
     * @param originBlockX    world X of the area's corner, a multiple of footprintBlocks
     * @param originBlockZ    world Z of the area's corner
     * @return the boxes, or an empty list if DH is unavailable or has nothing here
     */
    public static List<LodBox> fetchArea(int footprintBlocks, int originBlockX, int originBlockZ) {
        byte detailLevel = (byte) Integer.numberOfTrailingZeros(Math.max(footprintBlocks, 1));
        int posX = Math.floorDiv(originBlockX, Math.max(footprintBlocks, 1));
        int posZ = Math.floorDiv(originBlockZ, Math.max(footprintBlocks, 1));
        return fetchRegion(detailLevel, posX, posZ, footprintBlocks, originBlockX, originBlockZ);
    }

    private static List<LodBox> fetchRegion(byte detailLevel, int posX, int posZ,
                                            int footprintBlocks, int originBlockX, int originBlockZ) {
        synchronized (RtDhLodSource.class) {
            resolve();
            if (state != State.READY) {
                return List.of();
            }
        }
        try {
            Object levelWrapper = levelWrapper();
            if (levelWrapper == null) {
                // The most likely single cause of "DH returns nothing": DH has no level loaded at all,
                // which is not the same as a level with no terrain in it.
                if (loggedNoLevel.compareAndSet(false, true)) {
                    LOGGER.info("DH has no loaded level — it may be disabled for this world, or its "
                            + "level lifecycle may require its renderer to be enabled");
                }
                return List.of();
            }
            Object result = getAllTerrainDataAtDetailLevelAndPos.invoke(
                    terrainRepo, levelWrapper, detailLevel, posX, posZ, softCache);
            if (result == null || !resultSuccess.getBoolean(result)) {
                // DH's own explanation, surfaced ONCE at info. It was debug-only, which meant that when
                // every query failed the log showed a count of zeroes and no reason — the one piece of
                // information that would have identified the cause was being swallowed. Once, because
                // a failing setup fails on every query and would otherwise flood the log.
                if (loggedQueryFailure.compareAndSet(false, true)) {
                    LOGGER.info("DH query failed at detail {} pos {},{}: {}", detailLevel, posX, posZ,
                            result == null ? "null result" : resultMessage.get(result));
                }
                return List.of();
            }
            // A successful but empty answer is a different diagnosis from a failed one, so say so.
            if (loggedQuerySuccess.compareAndSet(false, true)) {
                LOGGER.info("DH query succeeded at detail {} pos {},{}", detailLevel, posX, posZ);
            }
            Object grid = resultPayload.get(result);
            if (grid == null) {
                return List.of();
            }
            return flatten(grid, footprintBlocks, originBlockX, originBlockZ);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.debug("DH region fetch failed at detail {} ({}, {}): {}",
                    detailLevel, posX, posZ, e.toString());
            return List.of();
        }
    }

    /**
     * Walks the [x][z][column] array. Reflection on the array rather than a cast because the element
     * type lives in DH's classloader-visible API and casting it here would reintroduce the hard
     * dependency this class exists to avoid.
     */
    private static List<LodBox> flatten(Object grid, int footprintBlocks, int originX, int originZ)
            throws ReflectiveOperationException {
        List<LodBox> boxes = new ArrayList<>();
        int lenX = Array.getLength(grid);
        if (lenX <= 0) {
            return boxes;
        }
        // Resolution derived from what DH actually returned, not from what was asked for. A 128-block
        // footprint answered with a 16x16 grid means each cell stands for 8 blocks.
        int sizeXZ = Math.max(footprintBlocks / lenX, 1);
        for (int ix = 0; ix < lenX; ix++) {
            Object column = Array.get(grid, ix);
            if (column == null) {
                continue;
            }
            int lenZ = Array.getLength(column);
            for (int iz = 0; iz < lenZ; iz++) {
                Object entries = Array.get(column, iz);
                if (entries == null) {
                    continue;
                }
                int lenY = Array.getLength(entries);
                for (int iy = 0; iy < lenY; iy++) {
                    Object point = Array.get(entries, iy);
                    if (point == null) {
                        continue;
                    }
                    int bottomY = pointBottomY.getInt(point);
                    int topY = pointTopY.getInt(point);
                    if (topY <= bottomY) {
                        continue;
                    }
                    Object wrapper = pointBlockState.get(point);
                    if (wrapper == null) {
                        continue;
                    }
                    Object mcObject = wrapperGetMcObject.invoke(wrapper);
                    if (!(mcObject instanceof BlockState blockState) || blockState.isAir()) {
                        // Air runs are the majority of every column and carry no geometry. Skipping
                        // them here rather than in the mesher keeps the returned list proportional to
                        // the terrain rather than to the world height.
                        continue;
                    }
                    boxes.add(new LodBox(
                            originX + ix * sizeXZ,
                            bottomY,
                            topY,
                            originZ + iz * sizeXZ,
                            sizeXZ,
                            blockState,
                            pointBlockLight.getInt(point),
                            pointSkyLight.getInt(point)));
                }
            }
        }
        return boxes;
    }

    /**
     * The level to query. Single-player has exactly one and DH says so directly. On a server —
     * Wynncraft being the case this exists for — that call throws, so this falls back to the loaded
     * set.
     *
     * <p>Taking the first loaded wrapper is a real limitation, not a tidy default: with more than one
     * level loaded it can return the wrong dimension's terrain. It is acceptable here because the
     * situation this serves is a server world where DH has one level populated by WynnLODGrabber. If
     * distant terrain ever appears from the wrong dimension, this is the line to fix, by matching the
     * wrapper's dimension against the client's.
     */
    private static Object levelWrapper() throws ReflectiveOperationException {
        try {
            Object single = getSinglePlayerLevel.invoke(worldProxy);
            if (single != null) {
                return single;
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            // IllegalStateException on a server. Expected; fall through.
        }
        // Iterable, NOT Collection. Verified against DistantHorizons-3.2.0-b-26.2: the signature is
        // getAllLoadedLevelWrappers()Ljava/lang/Iterable;. A Collection check here compiles and runs
        // fine, silently matches nothing, and disables distant terrain on every server — which is
        // exactly the case this fallback exists for.
        Object loaded = getAllLoadedLevelWrappers.invoke(worldProxy);
        if (loaded instanceof Iterable<?> iterable) {
            for (Object wrapper : iterable) {
                if (wrapper != null) {
                    return wrapper;
                }
            }
        }
        return null;
    }

    /** Forgets the resolved API. Call on world unload so a DH reload is picked up. */
    public static synchronized void invalidate() {
        if (state == State.READY) {
            state = State.UNRESOLVED;
            terrainRepo = null;
            worldProxy = null;
            // The cache holds soft references into DH's data sources for a world that is going away;
            // clear() is the documented way to release them rather than just dropping the reference.
            if (softCache instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // AutoCloseable#close is declared checked; DH's own override is not, so this is
                    // unreachable in practice and kept only to satisfy the compiler.
                }
            }
            softCache = null;
        }
    }
}
