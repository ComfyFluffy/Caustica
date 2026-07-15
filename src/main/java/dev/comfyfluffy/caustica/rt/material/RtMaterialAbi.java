package dev.comfyfluffy.caustica.rt.material;

/** Shared CPU invariants for the terrain material ABI. */
public final class RtMaterialAbi {
    private RtMaterialAbi() {
    }

    /** Existing terrain primitive record: three float4 lanes. */
    public static final int TERRAIN_PRIM_FLOATS = 12;
    public static final int TERRAIN_PRIM_BYTES = TERRAIN_PRIM_FLOATS * Float.BYTES;

    public static int checkedPrimitiveCount(int materialFloatCount) {
        if (materialFloatCount < 0 || materialFloatCount % TERRAIN_PRIM_FLOATS != 0) {
            throw new IllegalArgumentException("terrain material buffer has " + materialFloatCount
                    + " floats; expected a multiple of " + TERRAIN_PRIM_FLOATS);
        }
        return materialFloatCount / TERRAIN_PRIM_FLOATS;
    }

    public static void requireTriangleParity(int materialFloatCount, int indexCount) {
        if (indexCount < 0 || indexCount % 3 != 0) {
            throw new IllegalArgumentException("terrain index buffer has " + indexCount
                    + " entries; expected complete triangles");
        }
        int primitives = checkedPrimitiveCount(materialFloatCount);
        int triangles = indexCount / 3;
        if (primitives != triangles) {
            throw new IllegalArgumentException("terrain material/index mismatch: " + primitives
                    + " primitive records for " + triangles + " triangles");
        }
    }
}
