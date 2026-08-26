package dev.comfyfluffy.caustica.rt;

import net.minecraft.world.level.dimension.DimensionType;

/** Shared CPU-side mapping for Minecraft's dimension sky modes and fog color conversion. */
public final class RtSkyMath {
    public static final int SKYBOX_NONE = 0;
    public static final int SKYBOX_OVERWORLD = 1;
    public static final int SKYBOX_END = 2;
    public static final int SKY_FLAG_END_FLASH = 1;

    private RtSkyMath() {
    }

    public static int skyboxMode(DimensionType.Skybox skybox) {
        if (skybox == DimensionType.Skybox.NONE) {
            return SKYBOX_NONE;
        }
        if (skybox == DimensionType.Skybox.END) {
            return SKYBOX_END;
        }
        return SKYBOX_OVERWORLD;
    }

    /** Minecraft fog colors are authored as sRGB values; the RT sky payload is linear BT.709. */
    public static float srgbToLinear(float value) {
        value = Math.clamp(value, 0.0f, 1.0f);
        return value <= 0.04045f
                ? value / 12.92f
                : (float) Math.pow((value + 0.055f) / 1.055f, 2.4f);
    }
}
