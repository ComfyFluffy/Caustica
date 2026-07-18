package dev.comfyfluffy.caustica.rt.material;

/**
 * CPU-side per-material emission summary grid, compiled once at the resource epoch next to
 * {@link RtMaterialDesc.EmissionSummary}. Each cell holds the mean premultiplied emissive radiance
 * {@code linearAlbedo * emissionMask} and the mean mask weight of the texels it covers, so the terrain
 * light collector can resolve a quad's emissive footprint (centroid, bounding rectangle, undiluted mean
 * radiance) with pure array reads — no {@code NativeImage} access anywhere near the meshing workers.
 *
 * <p>The grid is resolution-independent: a high-res pack texture still reduces to {@link #SIZE} cells.
 * 16 matches MC's base texel grid, so vanilla sprites map 1:1.
 */
public final class RtEmissionGrid {
    public static final int SIZE = 16;
    private static final int CELL_FLOATS = 4;

    /** {@code SIZE*SIZE} cells of {@code {r, g, b, weight}} in row-major (v-major) order. */
    private final float[] cells;

    private RtEmissionGrid(float[] cells) {
        this.cells = cells;
    }

    private static int cell(int cx, int cy) {
        return (cy * SIZE + cx) * CELL_FLOATS;
    }

    /** Premultiplied mean emissive radiance (linear albedo x mask) of the cell containing (u, v). */
    public float r(int cx, int cy) {
        return cells[cell(cx, cy)];
    }

    public float g(int cx, int cy) {
        return cells[cell(cx, cy) + 1];
    }

    public float b(int cx, int cy) {
        return cells[cell(cx, cy) + 2];
    }

    /** Mean emission-mask weight (0..1) of the cell — the cell's emissive fraction. */
    public float weight(int cx, int cy) {
        return cells[cell(cx, cy) + 3];
    }

    /** Grid cell index for a sprite-local coordinate in [0,1); clamps out-of-range inputs. */
    public static int cellIndex(float local) {
        int index = (int) (local * SIZE);
        return Math.max(0, Math.min(SIZE - 1, index));
    }

    /** Accumulates texels into cells; used by the canonical decode and the sprite-stats pass. */
    static final class Builder {
        private final float[] sums = new float[SIZE * SIZE * CELL_FLOATS];
        private final int[] counts = new int[SIZE * SIZE];
        private final int width;
        private final int height;

        Builder(int width, int height) {
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
        }

        /** Add one texel's premultiplied emissive radiance and mask weight. */
        void add(int x, int y, float r, float g, float b, float weight) {
            int cx = Math.min(SIZE - 1, x * SIZE / width);
            int cy = Math.min(SIZE - 1, y * SIZE / height);
            int index = (cy * SIZE + cx) * CELL_FLOATS;
            sums[index] += r;
            sums[index + 1] += g;
            sums[index + 2] += b;
            sums[index + 3] += weight;
            counts[cy * SIZE + cx]++;
        }

        /** Returns null when no texel carried any weight (a non-emissive input). */
        RtEmissionGrid build() {
            float total = 0.0f;
            float[] cells = new float[sums.length];
            for (int cell = 0; cell < counts.length; cell++) {
                int count = counts[cell];
                if (count == 0) {
                    continue;
                }
                float inv = 1.0f / count;
                int index = cell * CELL_FLOATS;
                cells[index] = sums[index] * inv;
                cells[index + 1] = sums[index + 1] * inv;
                cells[index + 2] = sums[index + 2] * inv;
                cells[index + 3] = sums[index + 3] * inv;
                total += cells[index + 3];
            }
            return total > 0.0f ? new RtEmissionGrid(cells) : null;
        }
    }
}
