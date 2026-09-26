package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.minecraft.client.config.CausticaConfig;
import dev.comfyfluffy.caustica.minecraft.client.config.CausticaOptions;
import dev.comfyfluffy.caustica.renderer.runtime.RendererOptions;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionValues;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Rebindable key (default F6) that flips the SDR and HDR tone mappers between the two quick-toggle
 * preselects. The flip is a saved preference, so the next rendered frame uses it like an edit in the
 * settings screen.
 */
public final class TonemapperQuickToggle {
    public static final KeyMapping KEY = new KeyMapping(
            "key.caustica.tonemapper_toggle", GLFW.GLFW_KEY_F6, CausticaKeyMappings.CATEGORY);

    private TonemapperQuickToggle() {
    }

    public static void flip() {
        OptionValues values = CausticaConfig.snapshot();
        String a = values.get(RendererOptions.Rt.Tonemap.QUICK_TOGGLE_A);
        String b = values.get(RendererOptions.Rt.Tonemap.QUICK_TOGGLE_B);
        CausticaOptions store = CausticaConfig.store();
        for (Option<String> mapper : List.of(RendererOptions.Rt.Tonemap.SDR_MAPPER, RendererOptions.Rt.Tonemap.HDR_MAPPER)) {
            store.apply(CausticaConfig.FEATURE, mapper, next(values.get(mapper), a, b, mapper.choices()));
        }
        store.save();
    }

    /** A goes to B and anything else to A; an output that does not offer the target keeps its mapper. */
    static String next(String current, String a, String b, List<String> offered) {
        String target = current.equals(a) ? b : a;
        return offered.contains(target) ? target : current;
    }
}
