package dev.comfyfluffy.caustica.minecraft.client.screen.widget;

import dev.comfyfluffy.caustica.minecraft.client.settings.SettingControl;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Vanilla-skinned widgets bound to {@link SettingControl}s: a button for bools and two-value choices, and a
 * slider for numbers and longer choice lists, captioned {@code "Label: value"} like vanilla options.
 *
 * <p>Each widget re-reads its control whenever it draws, so a reset, an edit on another page, or a change
 * made outside the screen shows up without a refresh call. {@code gate} is false while the bool heading the
 * row's section is off, which greys the row out the same way an unavailable setting is.
 */
public final class SettingWidgets {
    public static final int RESET_WIDTH = Button.DEFAULT_HEIGHT;
    /** Horizontal inset of a vanilla button or slider caption on each side. */
    public static final int CAPTION_MARGIN = 2;
    private static final int MAX_ENUMERATED_STEPS = 64;
    private static final int SAMPLED_POSITIONS = 32;

    private SettingWidgets() {
    }

    public static AbstractWidget control(SettingControl control, BooleanSupplier gate, int width) {
        AbstractWidget widget = switch (control) {
            case SettingControl.BoolControl bool -> new Toggle(bool, gate, width);
            case SettingControl.RangeControl range -> new RangeSlider(range, gate, width);
            case SettingControl.ChoiceControl<?> choice -> choice.choices().size() > 2
                    ? new ChoiceSlider<>(choice, gate, width)
                    : new ChoiceCycle<>(choice, gate, width);
        };
        widget.setTooltip(Tooltip.create(control.tooltip()));
        return widget;
    }

    /** A square button that restores {@code control}'s default, shown only while the value differs from it. */
    public static Reset reset(SettingControl control, BooleanSupplier gate) {
        Reset reset = new Reset(control, gate);
        reset.setTooltip(Tooltip.create(Component.translatable("caustica.page.reset.tooltip",
                defaultText(control))));
        return reset;
    }

    /** How the control's default value reads, in the same words the control uses for its current value. */
    private static Component defaultText(SettingControl control) {
        return switch (control) {
            case SettingControl.BoolControl bool -> CommonComponents.optionStatus(bool.defaultValue());
            case SettingControl.RangeControl range -> range.format(range.defaultValue());
            case SettingControl.ChoiceControl<?> choice -> defaultChoice(choice);
        };
    }

    private static <T> Component defaultChoice(SettingControl.ChoiceControl<T> choice) {
        return choice.labelOf(choice.defaultValue());
    }

    /**
     * The captions the control can show, for sizing it so none is clipped: both states, every choice, or
     * every step of a slider, sampled evenly when there are more than {@link #MAX_ENUMERATED_STEPS}.
     */
    public static List<Component> captions(SettingControl control) {
        return switch (control) {
            case SettingControl.BoolControl bool ->
                    List.of(caption(bool, CommonComponents.optionStatus(true)),
                            caption(bool, CommonComponents.optionStatus(false)));
            case SettingControl.RangeControl range -> rangeCaptions(range);
            case SettingControl.ChoiceControl<?> choice -> choiceCaptions(choice);
        };
    }

    private static List<Component> rangeCaptions(SettingControl.RangeControl range) {
        double span = range.sliderMaximum() - range.sliderMinimum();
        int positions = range.step() > 0.0 && span / range.step() <= MAX_ENUMERATED_STEPS
                ? (int) Math.round(span / range.step()) : SAMPLED_POSITIONS;
        List<Component> captions = new ArrayList<>();
        captions.add(caption(range, range.format(range.defaultValue())));
        for (int position = 0; position <= positions; position++) {
            captions.add(caption(range, range.format(range.fromSlider(position / (double) positions))));
        }
        return captions;
    }

    private static <T> List<Component> choiceCaptions(SettingControl.ChoiceControl<T> choice) {
        return choice.choices().stream().map(value -> caption(choice, choice.labelOf(value))).toList();
    }

    private static Component caption(SettingControl control, Component value) {
        return Options.genericValueLabel(control.label(), value);
    }

    private static boolean editable(SettingControl control, BooleanSupplier gate) {
        return control.enabled() && gate.getAsBoolean();
    }

    private static final class Toggle extends AbstractButton {
        private final SettingControl.BoolControl control;
        private final BooleanSupplier gate;

        Toggle(SettingControl.BoolControl control, BooleanSupplier gate, int width) {
            super(0, 0, width, Button.DEFAULT_HEIGHT, Component.empty());
            this.control = control;
            this.gate = gate;
            refresh();
        }

        private void refresh() {
            active = editable(control, gate);
            setMessage(caption(control, CommonComponents.optionStatus(control.get())));
        }

        @Override
        public boolean isActive() {
            return visible && editable(control, gate);
        }

        @Override
        public void onPress(InputWithModifiers input) {
            if (!isActive()) return;
            control.set(!control.get());
            refresh();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            refresh();
            extractDefaultSprite(graphics);
            extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** Advances through a two-value choice, the vanilla shape for an on/off-like pair such as Auto/Manual. */
    private static final class ChoiceCycle<T> extends AbstractButton {
        private final SettingControl.ChoiceControl<T> control;
        private final BooleanSupplier gate;

        ChoiceCycle(SettingControl.ChoiceControl<T> control, BooleanSupplier gate, int width) {
            super(0, 0, width, Button.DEFAULT_HEIGHT, Component.empty());
            this.control = control;
            this.gate = gate;
            refresh();
        }

        private void refresh() {
            active = editable(control, gate);
            setMessage(caption(control, control.labelOf(control.get())));
        }

        @Override
        public boolean isActive() {
            return visible && editable(control, gate);
        }

        @Override
        public void onPress(InputWithModifiers input) {
            if (!isActive()) return;
            List<T> choices = control.choices();
            control.set(choices.get((choices.indexOf(control.get()) + 1) % choices.size()));
            refresh();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            refresh();
            extractDefaultSprite(graphics);
            extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /**
     * A numeric slider. Its span may be narrower than the stored range: a loaded value outside it pins the
     * knob at that end while the caption keeps reading the true number.
     */
    private static final class RangeSlider extends AbstractSliderButton {
        private final SettingControl.RangeControl control;
        private final BooleanSupplier gate;

        RangeSlider(SettingControl.RangeControl control, BooleanSupplier gate, int width) {
            super(0, 0, width, Button.DEFAULT_HEIGHT, Component.empty(), 0.0);
            this.control = control;
            this.gate = gate;
            refresh();
        }

        private void refresh() {
            active = editable(control, gate);
            value = control.toSlider(control.get());
            updateMessage();
        }

        @Override
        public boolean isActive() {
            return visible && editable(control, gate);
        }

        @Override
        protected void updateMessage() {
            setMessage(caption(control, control.format(control.get())));
        }

        @Override
        protected void applyValue() {
            if (!isActive()) return;
            control.set(control.fromSlider(value));
        }

        /** Stepped controls move one declared step per arrow press; continuous ones use vanilla increments. */
        @Override
        public boolean keyPressed(KeyEvent event) {
            refresh();
            if (canChangeValue && isActive() && control.step() > 0.0 && (event.isLeft() || event.isRight())) {
                double span = control.sliderMaximum() - control.sliderMinimum();
                setValue(value + control.step() / span * (event.isLeft() ? -1.0 : 1.0));
                return true;
            }
            return super.keyPressed(event);
        }

        /** Input starts from the stored value, which may have changed since the last frame drew the knob. */
        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            refresh();
            super.onClick(event, doubleClick);
        }

        @Override
        protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
            refresh();
            super.onDrag(event, dragX, dragY);
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                             float partialTick) {
            refresh();
            super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
        }
    }

    /**
     * A choice list longer than a pair, as a slider over the declared order. Dragging applies each value it
     * crosses, so the image follows the knob.
     */
    private static final class ChoiceSlider<T> extends AbstractSliderButton {
        private final SettingControl.ChoiceControl<T> control;
        private final BooleanSupplier gate;

        ChoiceSlider(SettingControl.ChoiceControl<T> control, BooleanSupplier gate, int width) {
            super(0, 0, width, Button.DEFAULT_HEIGHT, Component.empty(), 0.0);
            this.control = control;
            this.gate = gate;
            refresh();
        }

        private int last() {
            return control.choices().size() - 1;
        }

        private void refresh() {
            active = editable(control, gate);
            value = control.choices().indexOf(control.get()) / (double) last();
            updateMessage();
        }

        @Override
        public boolean isActive() {
            return visible && editable(control, gate);
        }

        @Override
        protected void updateMessage() {
            setMessage(caption(control, control.labelOf(control.get())));
        }

        @Override
        protected void applyValue() {
            if (!isActive()) return;
            control.set(control.choices().get((int) Math.round(value * last())));
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            refresh();
            if (canChangeValue && isActive() && (event.isLeft() || event.isRight())) {
                setValue(value + (event.isLeft() ? -1.0 : 1.0) / last());
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            refresh();
            super.onClick(event, doubleClick);
        }

        @Override
        protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
            refresh();
            super.onDrag(event, dragX, dragY);
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                             float partialTick) {
            refresh();
            super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
        }
    }

    /**
     * Hidden while the control holds its default, so an untouched page shows no reset buttons at all, and
     * greyed while the row cannot be edited.
     */
    public static final class Reset extends AbstractButton {
        private final SettingControl control;
        private final BooleanSupplier gate;

        private Reset(SettingControl control, BooleanSupplier gate) {
            super(0, 0, RESET_WIDTH, Button.DEFAULT_HEIGHT, Component.translatable("caustica.page.reset"));
            this.control = control;
            this.gate = gate;
            sync();
        }

        /** Follows the control; called before the button is drawn, since drawing skips a hidden widget. */
        public void sync() {
            visible = control.isModified();
            active = isActive();
        }

        @Override
        public boolean isActive() {
            return visible && editable(control, gate);
        }

        @Override
        public void onPress(InputWithModifiers input) {
            if (!isActive()) return;
            control.reset();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            active = isActive();
            extractDefaultSprite(graphics);
            extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
