package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.renderer.presentation.gen.DisplayPushData;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps the display-res scene-linear ACEScg image to sRGB SDR and optional PQ/BT.2020 HDR.
 *
 * <p>The HDR switch and the selected SDR and HDR tone mappers are specialization constants, so each
 * combination is its own shader object carrying only its operators. Variants are created on first
 * use and live until {@link #destroy()}, because a recorded frame may still reference a variant the
 * settings have since moved away from.
 */
public final class RtDisplayPipeline {
    private static final String SHADER = "/caustica/shaders/pipelines/display/main.comp.spv";
    private final VulkanDeviceContext context;
    private final Map<Variant, ShaderObjectCompute> variants = new HashMap<>();

    /**
     * Specialization of the display shader; component order is the shader's SpecId order. The HDR
     * mapper is irrelevant while HDR is off, so those variants all take ACES 2.0.
     */
    record Variant(boolean hdrEnabled, int sdrMode, int hdrMode) {
        static Variant of(boolean hdrEnabled, RtToneMapping.Settings toneMapping) {
            RtToneMapping.HdrMode hdrMode = hdrEnabled ? toneMapping.hdrMode() : RtToneMapping.HdrMode.ACES_2_0;
            return new Variant(hdrEnabled, toneMapping.sdrMode().id(), hdrMode.id());
        }

        int[] specializationConstants() {
            return new int[] {hdrEnabled ? 1 : 0, sdrMode, hdrMode};
        }
    }

    private RtDisplayPipeline(VulkanDeviceContext context) {
        this.context = context;
    }

    public static RtDisplayPipeline create(VulkanDeviceContext context) {
        return new RtDisplayPipeline(context);
    }

    public void dispatch(VkCommandBuffer command, GpuImage output, GpuImage scene, GpuImage exposure,
                         GpuImage hdrOutput, RtToneLut toneLut, RtToneLut hdrToneLut,
                         RtToneLut lookLut, boolean hdrEnabled, float gamma, float hdrPeakNits,
                         boolean lookEnabled, RtToneMapping.Settings toneMapping) {
        ShaderObjectCompute shader = variants.computeIfAbsent(Variant.of(hdrEnabled, toneMapping), variant ->
                ShaderObjectCompute.load(context, RtDisplayPipeline.class, SHADER,
                        variant.specializationConstants()));
        try (MemoryStack stack = MemoryStack.stackPush();
             var ignored = RtDebugLabels.scope(context, command, "display compute")) {
            RtToneMapping.Parameters sdr = toneMapping.sdrParameters();
            RtToneMapping.Parameters hdr = toneMapping.hdrParameters();
            ByteBuffer push = stack.malloc(DisplayPushData.BYTE_SIZE);
            new DisplayPushData(storage(output), storage(scene), storage(exposure), storage(hdrOutput),
                    toneLut.sampledIndex().value(), toneLut.samplerIndex().value(),
                    hdrToneLut.sampledIndex().value(), hdrToneLut.samplerIndex().value(),
                    lookLut.sampledIndex().value(), lookLut.samplerIndex().value(),
                    toneLut.size, gamma, hdrPeakNits,
                    lookEnabled ? 1 : 0, lookLut.size,
                    toneMapping.paperWhiteNits(), toneMapping.headroom(),
                    sdr.param0(), sdr.param1(), sdr.param2(), sdr.param3(),
                    sdr.param4(), sdr.param5(), sdr.param6(), sdr.param7(),
                    hdr.param0(), hdr.param1(), hdr.param2(), hdr.param3(),
                    hdr.param4(), hdr.param5(), hdr.param6(), hdr.param7()).write(push);
            shader.dispatch(command, push, (output.width() + 15) / 16, (output.height() + 15) / 16, 1);
        }
    }

    private static int storage(GpuImage image) {
        return image.descriptor(GpuImageDescriptorKind.STORAGE).index().value();
    }

    public void destroy() {
        new ResourceLifetime(variants.values().stream()
                .map(shader -> (Runnable) shader::close).toArray(Runnable[]::new)).close();
        variants.clear();
    }
}
