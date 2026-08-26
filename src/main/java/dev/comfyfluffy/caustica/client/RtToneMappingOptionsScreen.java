package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.RtVideoOptions.ResettableControl;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Focused exposure and display-mapping page. The complete exposure surface is always available,
 * while the curve section is rebuilt whenever the active output path's tone mapper changes so
 * inactive algorithms never clutter the page.
 */
public final class RtToneMappingOptionsScreen extends OptionsSubScreen {
    private static final long VISIBLE_STATE_DEBOUNCE_NANOS = 100_000_000L;

    private final List<ResettableControl> resettableControls = new ArrayList<>();
    private String visibleState;
    private String pendingVisibleState;
    private long pendingVisibleStateSince;
    private ResettableControl toneMapperControl;
    private boolean toneMapperDragging;

    public RtToneMappingOptionsScreen(Screen lastScreen, Options options) {
        super(
                lastScreen,
                options,
                Component.translatable("caustica.options.rt.toneMapping.title"));
    }

    @Override
    protected void addOptions() {
        resettableControls.clear();
        toneMapperControl = null;
        list.addHeader(Component.translatable(
                        "caustica.options.rt.toneMapping.resetHint")
                .withStyle(ChatFormatting.GRAY));

        list.addHeader(Component.translatable(
                "caustica.options.rt.toneMapping.section.exposure"));
        addSmall(RtVideoOptions.exposureOptions());

        boolean hdr = requestedHdr();
        list.addHeader(Component.translatable(
                hdr
                        ? "caustica.options.rt.toneMapping.section.hdrOutput"
                        : "caustica.options.rt.toneMapping.section.sdrOutput"));
        toneMapperControl = hdr
                ? RtVideoOptions.hdrToneMapper()
                : RtVideoOptions.sdrToneMapper();
        addBig(toneMapperControl);
        if (hdr) {
            addSmall(RtVideoOptions.hdrDisplayOptions());
        }

        ResettableControl[] mapperOptions =
                RtVideoOptions.activeToneMapperOptions(hdr);
        if (mapperOptions.length > 0) {
            list.addHeader(Component.translatable(
                    "caustica.options.rt.toneMapping.section.activeMapper",
                    RtVideoOptions.activeToneMapperName(hdr)));
            addSmall(mapperOptions);
        }
        visibleState = currentVisibleState();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && toneMapperControl != null) {
            AbstractWidget widget = list.findOption(toneMapperControl.option());
            toneMapperDragging = widget != null
                    && widget.visible
                    && widget.active
                    && widget.isMouseOver(event.x(), event.y());
        }
        if (event.button() == 0 && event.hasControlDown() && event.hasShiftDown()) {
            for (ResettableControl control : resettableControls) {
                AbstractWidget widget = list.findOption(control.option());
                if (widget != null
                        && widget.visible
                        && widget.active
                        && widget.isMouseOver(event.x(), event.y())) {
                    control.resetToDefault();
                    list.resetOption(control.option());
                    CausticaConfig.save();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean handled = super.mouseReleased(event);
        if (event.button() == 0) {
            toneMapperDragging = false;
            if (pendingVisibleState != null) {
                pendingVisibleStateSince = System.nanoTime();
            }
        }
        return handled;
    }

    @Override
    public void tick() {
        super.tick();
        String nextState = currentVisibleState();
        if (!nextState.equals(visibleState)) {
            long now = System.nanoTime();
            if (!nextState.equals(pendingVisibleState)) {
                pendingVisibleState = nextState;
                pendingVisibleStateSince = now;
            } else if (!toneMapperDragging
                    && now - pendingVisibleStateSince >= VISIBLE_STATE_DEBOUNCE_NANOS) {
                list.applyUnsavedChanges();
                CausticaConfig.save();
                // OptionsSubScreen keeps this layout instance across rebuildWidgets().
                // Clear its frames as well as the screen widget list, otherwise every
                // mapper change leaves another full options list stacked underneath.
                layout.removeChildren();
                rebuildWidgets();
                pendingVisibleState = null;
            }
        } else {
            pendingVisibleState = null;
        }
    }

    @Override
    public void removed() {
        super.removed();
        CausticaConfig.save();
    }

    private static boolean requestedHdr() {
        return CausticaConfig.Rt.Hdr.ENABLED.value();
    }

    private static String currentVisibleState() {
        boolean hdr = requestedHdr();
        return (hdr ? "hdr:" : "sdr:")
                + (hdr
                        ? CausticaConfig.Rt.Hdr.TONE_MAPPER.get()
                        : CausticaConfig.Rt.Sdr.TONE_MAPPER.get());
    }

    private void addBig(ResettableControl control) {
        resettableControls.add(control);
        list.addBig(control.option());
    }

    private void addSmall(ResettableControl[] controls) {
        for (ResettableControl control : controls) {
            resettableControls.add(control);
        }
        list.addSmall(RtVideoOptions.optionInstances(controls));
    }
}
