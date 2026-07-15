package dev.comfyfluffy.caustica.rt.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.comfyfluffy.caustica.mixin.ModelPartAccessor;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Exact direct traversal for vanilla {@link ModelPart.Cube} geometry. */
final class RtCuboidEmitter {
    private static final int STANDARD_CORNERS = 8;
    private static final ClassValue<Boolean> VANILLA_MODEL_CLASS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.getName().startsWith("net.minecraft.");
        }
    };

    private final IdentityHashMap<Model<?>, ModelTemplate> templates = new IdentityHashMap<>();
    private final Vector3f scratch = new Vector3f();
    private final float[] quadX = new float[4];
    private final float[] quadY = new float[4];
    private final float[] quadZ = new float[4];
    private final float[] quadU = new float[4];
    private final float[] quadV = new float[4];
    private final float[] cornerX = new float[STANDARD_CORNERS];
    private final float[] cornerY = new float[STANDARD_CORNERS];
    private final float[] cornerZ = new float[STANDARD_CORNERS];

    /**
     * Return a template only after validating the complete ordered tree. Nothing is written on failure,
     * so the caller can safely use vanilla's final render method as the fallback.
     */
    ModelTemplate prepare(Model<?> model) {
        if (!VANILLA_MODEL_CLASS.get(model.getClass())) {
            return null;
        }
        ModelTemplate template = templates.get(model);
        if (template != null && template.matches(model.root())) {
            return template;
        }
        template = ModelTemplate.create(model.root());
        if (template == null) {
            templates.remove(model);
            return null;
        }
        templates.put(model, template);
        return template;
    }

    void clear() {
        templates.clear();
    }

    /** Return packed actual cube counts: specialized in the high 32 bits, generic in the low 32 bits. */
    long emit(ModelTemplate template, PoseStack poseStack, RtEntityCapture capture, int color) {
        capture.ensureAdditionalVertexCapacity(template.maxVertices);
        return emitPart(template.root, poseStack, capture, color);
    }

    private long emitPart(PartTemplate template, PoseStack poseStack, RtEntityCapture capture, int color) {
        ModelPart part = template.part;
        if (!part.visible || template.empty()) {
            return 0L;
        }
        poseStack.pushPose();
        part.translateAndRotate(poseStack);
        long counts = 0L;
        if (!part.skipDraw) {
            PoseStack.Pose pose = poseStack.last();
            for (CubeTemplate cube : template.cubes) {
                if (cube instanceof EightCornerCube eight) {
                    emitEightCornerCube(eight, pose, capture, color);
                    counts += 1L << 32;
                } else {
                    emitGenericCube(cube.cube, pose, capture, color);
                    counts++;
                }
            }
        }
        for (PartTemplate child : template.children) {
            counts += emitPart(child, poseStack, capture, color);
        }
        poseStack.popPose();
        return counts;
    }

    private void emitEightCornerCube(EightCornerCube cube, PoseStack.Pose pose,
                                     RtEntityCapture capture, int color) {
        Matrix4f matrix = pose.pose();
        for (int i = 0; i < STANDARD_CORNERS; i++) {
            matrix.transformPosition(cube.x[i], cube.y[i], cube.z[i], scratch);
            cornerX[i] = scratch.x();
            cornerY[i] = scratch.y();
            cornerZ[i] = scratch.z();
        }
        for (FaceTemplate face : cube.faces) {
            pose.transformNormal(face.nx, face.ny, face.nz, scratch);
            capture.addIndexedDirectQuad(cornerX, cornerY, cornerZ, face.corners, face.u, face.v,
                    scratch.x(), scratch.y(), scratch.z(), color);
        }
    }

    /** A2's verified direct-polygon path retained for nonstandard but valid cube topology. */
    private void emitGenericCube(ModelPart.Cube cube, PoseStack.Pose pose,
                                 RtEntityCapture capture, int color) {
        Matrix4f matrix = pose.pose();
        for (ModelPart.Polygon polygon : cube.polygons) {
            Vector3f normal = pose.transformNormal(polygon.normal(), scratch);
            float nx = normal.x();
            float ny = normal.y();
            float nz = normal.z();
            ModelPart.Vertex[] vertices = polygon.vertices();
            for (int i = 0; i < 4; i++) {
                ModelPart.Vertex vertex = vertices[i];
                matrix.transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), scratch);
                quadX[i] = scratch.x();
                quadY[i] = scratch.y();
                quadZ[i] = scratch.z();
                quadU[i] = vertex.u();
                quadV[i] = vertex.v();
            }
            capture.addDirectQuad(quadX, quadY, quadZ, quadU, quadV, nx, ny, nz, color);
        }
    }

    static final class ModelTemplate {
        final PartTemplate root;
        final int maxVertices;

        private ModelTemplate(PartTemplate root) {
            this.root = root;
            this.maxVertices = root.maxVertices;
        }

        static ModelTemplate create(ModelPart root) {
            PartTemplate part = PartTemplate.create(root);
            return part != null ? new ModelTemplate(part) : null;
        }

        boolean matches(ModelPart root) {
            return this.root.matches(root);
        }
    }

    private static final class PartTemplate {
        final ModelPart part;
        final CubeTemplate[] cubes;
        final PartTemplate[] children;
        final int maxVertices;

        private PartTemplate(ModelPart part, CubeTemplate[] cubes, PartTemplate[] children) {
            this.part = part;
            this.cubes = cubes;
            this.children = children;
            int vertices = 0;
            for (CubeTemplate cube : cubes) {
                vertices = Math.addExact(vertices, Math.multiplyExact(cube.faceCount, 4));
            }
            for (PartTemplate child : children) {
                vertices = Math.addExact(vertices, child.maxVertices);
            }
            this.maxVertices = vertices;
        }

        static PartTemplate create(ModelPart part) {
            ModelPartAccessor access = (ModelPartAccessor) (Object) part;
            List<ModelPart.Cube> sourceCubes = access.caustica$cubes();
            CubeTemplate[] cubes = new CubeTemplate[sourceCubes.size()];
            for (int i = 0; i < cubes.length; i++) {
                CubeTemplate cube = CubeTemplate.create(sourceCubes.get(i));
                if (cube == null) {
                    return null;
                }
                cubes[i] = cube;
            }
            Map<String, ModelPart> sourceChildren = access.caustica$children();
            PartTemplate[] children = new PartTemplate[sourceChildren.size()];
            int i = 0;
            for (ModelPart child : sourceChildren.values()) {
                PartTemplate childTemplate = create(child);
                if (childTemplate == null) {
                    return null;
                }
                children[i++] = childTemplate;
            }
            return new PartTemplate(part, cubes, children);
        }

        boolean empty() {
            return cubes.length == 0 && children.length == 0;
        }

        boolean matches(ModelPart candidate) {
            if (part != candidate) {
                return false;
            }
            ModelPartAccessor access = (ModelPartAccessor) (Object) candidate;
            List<ModelPart.Cube> currentCubes = access.caustica$cubes();
            if (currentCubes.size() != cubes.length) {
                return false;
            }
            for (int i = 0; i < cubes.length; i++) {
                if (currentCubes.get(i) != cubes[i].cube) {
                    return false;
                }
            }
            Map<String, ModelPart> currentChildren = access.caustica$children();
            if (currentChildren.size() != children.length) {
                return false;
            }
            int i = 0;
            for (ModelPart child : currentChildren.values()) {
                if (!children[i++].matches(child)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static class CubeTemplate {
        final ModelPart.Cube cube;
        final int faceCount;

        CubeTemplate(ModelPart.Cube cube) {
            this.cube = cube;
            this.faceCount = cube.polygons.length;
        }

        static CubeTemplate create(ModelPart.Cube cube) {
            if (cube.getClass() != ModelPart.Cube.class) {
                return null;
            }
            ModelPart.Polygon[] polygons = cube.polygons;
            float[] sourceX = new float[STANDARD_CORNERS];
            float[] sourceY = new float[STANDARD_CORNERS];
            float[] sourceZ = new float[STANDARD_CORNERS];
            FaceTemplate[] faces = new FaceTemplate[polygons.length];
            int cornerCount = 0;
            boolean overflow = false;
            for (int faceIndex = 0; faceIndex < polygons.length; faceIndex++) {
                ModelPart.Polygon polygon = polygons[faceIndex];
                if (polygon == null || polygon.normal() == null || polygon.vertices().length != 4) {
                    return null;
                }
                int[] corners = new int[4];
                float[] u = new float[4];
                float[] v = new float[4];
                for (int i = 0; i < 4; i++) {
                    ModelPart.Vertex vertex = polygon.vertices()[i];
                    if (vertex == null) {
                        return null;
                    }
                    int corner = findCorner(sourceX, sourceY, sourceZ, cornerCount,
                            vertex.x(), vertex.y(), vertex.z());
                    if (corner < 0) {
                        if (cornerCount == STANDARD_CORNERS) {
                            overflow = true;
                        } else {
                            corner = cornerCount++;
                            sourceX[corner] = vertex.x();
                            sourceY[corner] = vertex.y();
                            sourceZ[corner] = vertex.z();
                        }
                    }
                    corners[i] = corner;
                    u[i] = vertex.u();
                    v[i] = vertex.v();
                }
                Vector3fc normal = polygon.normal();
                faces[faceIndex] = new FaceTemplate(corners, u, v, normal.x(), normal.y(), normal.z());
            }
            if (overflow || cornerCount != STANDARD_CORNERS) {
                return new CubeTemplate(cube);
            }
            for (int i = 0; i < STANDARD_CORNERS; i++) {
                sourceX[i] /= ModelPart.Vertex.SCALE_FACTOR;
                sourceY[i] /= ModelPart.Vertex.SCALE_FACTOR;
                sourceZ[i] /= ModelPart.Vertex.SCALE_FACTOR;
            }
            return new EightCornerCube(cube, sourceX, sourceY, sourceZ, faces);
        }

        private static int findCorner(float[] x, float[] y, float[] z, int count,
                                      float px, float py, float pz) {
            int xb = Float.floatToRawIntBits(px);
            int yb = Float.floatToRawIntBits(py);
            int zb = Float.floatToRawIntBits(pz);
            for (int i = 0; i < count; i++) {
                if (Float.floatToRawIntBits(x[i]) == xb
                        && Float.floatToRawIntBits(y[i]) == yb
                        && Float.floatToRawIntBits(z[i]) == zb) {
                    return i;
                }
            }
            return -1;
        }
    }

    private static final class EightCornerCube extends CubeTemplate {
        final float[] x;
        final float[] y;
        final float[] z;
        final FaceTemplate[] faces;

        EightCornerCube(ModelPart.Cube cube, float[] x, float[] y, float[] z, FaceTemplate[] faces) {
            super(cube);
            this.x = Arrays.copyOf(x, STANDARD_CORNERS);
            this.y = Arrays.copyOf(y, STANDARD_CORNERS);
            this.z = Arrays.copyOf(z, STANDARD_CORNERS);
            this.faces = faces;
        }
    }

    private record FaceTemplate(int[] corners, float[] u, float[] v, float nx, float ny, float nz) {
    }
}
