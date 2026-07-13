package dev.comfyfluffy.caustica.mixin;

import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access used by entity cuboid profiling and the direct traversal that follows it. */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {
    @Accessor("cubes")
    List<ModelPart.Cube> caustica$cubes();

    @Accessor("children")
    Map<String, ModelPart> caustica$children();
}
