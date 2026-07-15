package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Opt-in render-frame timing and hitch detection. Gated by {@code -Dcaustica.rt.frameStats}; every method
 * is a cheap branch when disabled. The profile begins when RT client-tick work starts (or at
 * {@code GameRenderer.render} HEAD when there was no RT tick work) and ends at render TAIL, so the hitch
 * decision uses one frame envelope rather than one action/pass at a time. Every completed frame is appended
 * as one row to {@code <gameDir>/rt-frame-stats/frame.csv} (fresh file per session), and a log line is
 * emitted only when the frame exceeds {@value #HITCH_MULTIPLIER}x its rolling median. That hitch line
 * includes all detailed stage timings and counters recorded during the frame.
 */
public final class RtFrameStats {
    private static final int MEDIAN_WINDOW = 64;
    private static final double HITCH_MULTIPLIER = 1.5;

    // trackGc: per-frame GC deltas (collection count + reported pause ms) help distinguish JVM pauses from
    // uninstrumented render work when a hitch's unaccounted time is large.
    public static final Profile FRAME = new Profile("frame",
            new String[] {
                    "terrain.windowSync",
                    "terrain.dirtyDrain",
                    "terrain.drainCompletion",
                    "terrain.snapshotDispatch",
                    "terrain.publish",
                    "entity.capture",
                    "entity.capture.extract",
                    "entity.capture.submit",
                    "entity.capture.submit.material",
                    "entity.capture.submit.setupAnim",
                    "entity.capture.submit.modelDraw",
                    "entity.capture.submit.modelDraw.direct",
                    "entity.capture.submit.modelDraw.fallback",
                    "entity.capture.submit.bakedQuads",
                    "entity.capture.submit.metrics",
                    "entity.capture.submit.parity",
                    "entity.capture.motion",
                    "entity.capture.rigidReuse",
                    "entity.capture.rigidReuse.equal",
                    "entity.capture.rigidReuse.yaw",
                    "entity.capture.rigidReuse.shade",
                    "entity.capture.append.alloc",
                    "entity.capture.append.copy",
                    "entity.capture.append.blas",
                    "entity.blockEntities",
                    "entity.particles",
                    "entity.blasRecord",
                    "frame.prepareTlas",
                    "frame.recordTlas",
                    "frame.trace",
                    "frame.exposure",
                    "frame.dlssRr",
                    "frame.upscale",
                    "frame.displayMap",
                    "frame.copyOutput"
            },
            new String[] {"sectionsSnapshotted", "sectionCopies", "terrainBuildsCompleted",
                    "entitiesCaptured", "blockEntitiesCaptured", "particlesCaptured", "refits",
                    "entityReuse", "entityRigidFitSuccesses", "entityRigidFitFailures",
                    "vmaBufferCreates",
                    "entityModelSubmissions", "entityCuboids",
                    "entityModelQuads", "entityModelVertices", "entityBakedQuads", "entityBakedVertices",
                    "entityDirectSubmissions", "entityDirectFallbacks", "entityDirectQuads", "entityDirectVertices",
                    "entitySpecializedCuboids", "entityGenericCuboids",
                    "entityParityChecks", "entityVmaBufferCreates", "entityGeometryBufferReuses",
                    "entityScratchBufferReuses", "entityUploadBytes", "entityMotionUploadBytes",
                    "entityPackedBytes", "entityPackedPaddingBytes", "entityRetainedGeometryBytes"},
            true);

    private static final List<GarbageCollectorMXBean> GC_BEANS = ManagementFactory.getGarbageCollectorMXBeans();

    private static long gcCollections() {
        long total = 0;
        for (GarbageCollectorMXBean bean : GC_BEANS) {
            long count = bean.getCollectionCount();
            if (count > 0) {
                total += count;
            }
        }
        return total;
    }

    private static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean bean : GC_BEANS) {
            long time = bean.getCollectionTime();
            if (time > 0) {
                total += time;
            }
        }
        return total;
    }

    private RtFrameStats() {
    }

    public static boolean enabled() {
        return CausticaConfig.Rt.FrameStats.ENABLED.value();
    }

    public interface Scope extends AutoCloseable {
        Scope NOOP = () -> {};

        @Override
        void close();
    }

    /** A timed render frame: per-stage nanos + named counters, plus a rolling-median hitch log. */
    public static final class Profile {
        private final String name;
        private final String[] stageNames;
        private final String[] counterNames;
        private final Map<String, Integer> stageIndices;
        private final Map<String, Integer> counterIndices;
        private final boolean trackGc;
        private final long[] stageNanos;
        private final long[] counters;
        private final long[] history = new long[MEDIAN_WINDOW];
        private int historyCount;
        private int historyPos;
        private long frameStart;
        private long frameIndex;
        private long gcCountStart;
        private long gcMsStart;
        private PrintWriter csv;
        private boolean csvOpenAttempted;
        private boolean active;

        private Profile(String name, String[] stageNames, String[] counterNames) {
            this(name, stageNames, counterNames, false);
        }

        private Profile(String name, String[] stageNames, String[] counterNames, boolean trackGc) {
            this.name = name;
            this.stageNames = stageNames;
            this.counterNames = counterNames;
            this.trackGc = trackGc;
            this.stageNanos = new long[stageNames.length];
            this.counters = new long[counterNames.length];
            this.stageIndices = index(stageNames);
            this.counterIndices = index(counterNames);
        }

        /** Start timing a new frame; clears this frame's stage/counter accumulators. */
        public void begin() {
            active = false;
            if (!enabled()) {
                return;
            }
            active = true;
            frameStart = System.nanoTime();
            Arrays.fill(stageNanos, 0L);
            Arrays.fill(counters, 0L);
            if (trackGc) {
                gcCountStart = gcCollections();
                gcMsStart = gcMillis();
            }
        }

        /** Start a frame only when one is not already collecting RT tick details for this render. */
        public void beginIfInactive() {
            if (!active) {
                begin();
            }
        }

        /** Time one named stage of the current frame; close the returned scope when the stage completes. */
        public Scope stage(String stageName) {
            if (!enabled() || !active) {
                return Scope.NOOP;
            }
            int idx = indexOf(stageIndices, stageName);
            long start = System.nanoTime();
            return () -> stageNanos[idx] += System.nanoTime() - start;
        }

        /**
         * Start a hot-path stage without allocating a {@link Scope}. Returns zero while profiling is inactive;
         * pass the value unchanged to {@link #endStage(String, long)}.
         */
        public long startStage() {
            return enabled() && active ? System.nanoTime() : 0L;
        }

        /** Finish a stage started by {@link #startStage()}. */
        public void endStage(String stageName, long startNanos) {
            if (startNanos == 0L) {
                return;
            }
            stageNanos[indexOf(stageIndices, stageName)] += System.nanoTime() - startNanos;
        }

        /** Add to a named counter for the current frame. */
        public void count(String counterName, long delta) {
            if (!enabled() || !active || delta == 0) {
                return;
            }
            counters[indexOf(counterIndices, counterName)] += delta;
        }

        /** Finish the current frame: record it into the rolling median and log a hitch line if it's slow. */
        public void end() {
            if (!active) {
                return;
            }
            active = false;
            if (!enabled()) {
                return;
            }
            long total = System.nanoTime() - frameStart;
            long gcCount = trackGc ? gcCollections() - gcCountStart : 0;
            long gcMs = trackGc ? gcMillis() - gcMsStart : 0;
            long median = median();
            history[historyPos] = total;
            historyPos = (historyPos + 1) % MEDIAN_WINDOW;
            if (historyCount < MEDIAN_WINDOW) {
                historyCount++;
            }
            boolean hitch = median > 0 && total > (long) (median * HITCH_MULTIPLIER);
            writeCsvRow(total, median, hitch, gcCount, gcMs);
            if (hitch) {
                logHitch(total, median, gcCount, gcMs);
            }
        }

        private long median() {
            if (historyCount == 0) {
                return 0L;
            }
            long[] sorted = Arrays.copyOf(history, historyCount);
            Arrays.sort(sorted);
            return sorted[historyCount / 2];
        }

        private void writeCsvRow(long total, long median, boolean hitch, long gcCount, long gcMs) {
            ensureCsv();
            if (csv == null) {
                return;
            }
            StringBuilder row = new StringBuilder();
            row.append(frameIndex++).append(',').append(ms(total)).append(',').append(ms(median)).append(',').append(hitch ? 1 : 0);
            for (long stageNano : stageNanos) {
                row.append(',').append(ms(stageNano));
            }
            for (long counter : counters) {
                row.append(',').append(counter);
            }
            if (trackGc) {
                row.append(',').append(gcCount).append(',').append(gcMs);
            }
            csv.println(row);
            csv.flush();
        }

        /** Opens (or gives up on) this profile's CSV file at most once per session. */
        private void ensureCsv() {
            if (csvOpenAttempted) {
                return;
            }
            csvOpenAttempted = true;
            Path dir = FabricLoader.getInstance().getGameDir().resolve("rt-frame-stats");
            Path file = dir.resolve(name + ".csv");
            try {
                Files.createDirectories(dir);
                csv = new PrintWriter(new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8)));
                StringBuilder header = new StringBuilder("frame,totalMs,medianMs,hitch");
                for (String stageName : stageNames) {
                    header.append(',').append(stageName).append("Ms");
                }
                for (String counterName : counterNames) {
                    header.append(',').append(counterName);
                }
                if (trackGc) {
                    header.append(",gcCount,gcPauseMs");
                }
                csv.println(header);
                csv.flush();
            } catch (IOException e) {
                CausticaMod.LOGGER.warn("RtFrameStats: failed to open CSV {} for profile {}: {}", file, name, e.toString());
                csv = null;
            }
        }

        private void logHitch(long total, long median, long gcCount, long gcMs) {
            StringBuilder sb = new StringBuilder("RT hitch [").append(name).append("] ")
                    .append(ms(total)).append("ms (median ").append(ms(median)).append("ms):");
            long staged = 0;
            for (int i = 0; i < stageNames.length; i++) {
                // entity.capture.* is a detailed subdivision nested inside the top-level entity.capture
                // envelope. Report it, but do not double-count it when deriving unaccounted frame time.
                if (!stageNames[i].startsWith("entity.capture.")) {
                    staged += stageNanos[i];
                }
                sb.append(' ').append(stageNames[i]).append('=').append(ms(stageNanos[i])).append("ms");
            }
            // Time inside this frame not covered by any stage timer — a big value here with gcPause>0 means
            // a GC pause landed mid-frame; with gcPause=0 it points at an uninstrumented stage.
            sb.append(" unaccounted=").append(ms(Math.max(0, total - staged))).append("ms");
            for (int i = 0; i < counterNames.length; i++) {
                sb.append(' ').append(counterNames[i]).append('=').append(counters[i]);
            }
            if (trackGc) {
                sb.append(" gcCount=").append(gcCount).append(" gcPauseMs=").append(gcMs);
            }
            CausticaMod.LOGGER.info(sb.toString());
        }

        private static double ms(long nanos) {
            return Math.round(nanos / 1000.0) / 1000.0;
        }

        private static Map<String, Integer> index(String[] names) {
            Map<String, Integer> result = new HashMap<>(names.length * 2);
            for (int i = 0; i < names.length; i++) {
                if (result.put(names[i], i) != null) {
                    throw new IllegalArgumentException("Duplicate RtFrameStats name: " + names[i]);
                }
            }
            return result;
        }

        private static int indexOf(Map<String, Integer> indices, String name) {
            Integer index = indices.get(name);
            if (index == null) {
                throw new IllegalArgumentException("Unknown RtFrameStats name: " + name);
            }
            return index;
        }
    }
}
