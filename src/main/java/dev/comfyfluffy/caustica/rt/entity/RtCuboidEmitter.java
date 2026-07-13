package dev.comfyfluffy.caustica.rt.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.comfyfluffy.caustica.mixin.ModelPartAccessor;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Exact direct traversal for vanilla {@link ModelPart.Cube} geometry. */
final class RtCuboidEmitter {
    private static final ClassValue<Boolean> VANILLA_MODEL_CLASS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.getName().startsWith("net.minecraft.");
        }
    };

    private final IdentityHashMap<Model<?>, ModelTemplate> templates = new IdentityHashMap<>();
    private final Vector3f scratch = new Vector3f();
    private final float[] x = new float[4];
    private final float[] y = new float[4];
    private final float[] z = new float[4];
    private final float[] u = new float[4];
    private final float[] v = new float[4];

    /**
     * Return an emitter only after validating the complete ordered tree. Nothing is written on failure,
     * so the caller can safely use vanilla's final render method as the fallback.
     */
    ModelTemplate prepare(Model<?> model) {
        if (!isVanillaModel(model)) {
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

    void emit(ModelTemplate template, PoseStack poseStack, RtEntityCapture capture, int color) {
        emitPart(template.root, poseStack, capture, color);
    }

    private void emitPart(PartTemplate template, PoseStack poseStack, RtEntityCapture capture, int color) {
        ModelPart part = template.part;
        if (!part.visible || template.empty()) {
            return;
        }
        poseStack.pushPose();
        part.translateAndRotate(poseStack);
        if (!part.skipDraw) {
            PoseStack.Pose pose = poseStack.last();
            Matrix4f matrix = pose.pose();
            for (ModelPart.Cube cube : template.cubes) {
                for (ModelPart.Polygon polygon : cube.polygons) {
                    Vector3f normal = pose.transformNormal(polygon.normal(), scratch);
                    float nx = normal.x();
                    float ny = normal.y();
                    float nz = normal.z();
                    ModelPart.Vertex[] vertices = polygon.vertices();
                    for (int i = 0; i < 4; i++) {
                        ModelPart.Vertex vertex = vertices[i];
                        matrix.transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), scratch);
                        x[i] = scratch.x();
                        y[i] = scratch.y();
                        z[i] = scratch.z();
                        u[i] = vertex.u();
                        v[i] = vertex.v();
                    }
                    capture.addDirectQuad(x, y, z, u, v, nx, ny, nz, color);
                }
            }
        }
        for (PartTemplate child : template.children) {
            emitPart(child, poseStack, capture, color);
        }
        poseStack.popPose();
    }

    private static boolean isVanillaModel(Model<?> model) {
        return VANILLA_MODEL_CLASS.get(model.getClass());
    }

    static final class ModelTemplate {
        final PartTemplate root;

        private ModelTemplate(PartTemplate root) {
            this.root = root;
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
        final ModelPart.Cube[] cubes;
        final PartTemplate[] children;

        private PartTemplate(ModelPart part, ModelPart.Cube[] cubes, PartTemplate[] children) {
            this.part = part;
            this.cubes = cubes;
            this.children = children;
        }

        static PartTemplate create(ModelPart part) {
            ModelPartAccessor access = (ModelPartAccessor) (Object) part;
            List<ModelPart.Cube> sourceCubes = access.caustica$cubes();
            ModelPart.Cube[] cubes = sourceCubes.toArray(ModelPart.Cube[]::new);
            for (ModelPart.Cube cube : cubes) {
                if (cube.getClass() != ModelPart.Cube.class) {
                    return null;
                }
                for (ModelPart.Polygon polygon : cube.polygons) {
                    if (polygon == null || polygon.vertices().length != 4) {
                        return null;
                    }
                }
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
                if (currentCubes.get(i) != cubes[i]) {
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
}
