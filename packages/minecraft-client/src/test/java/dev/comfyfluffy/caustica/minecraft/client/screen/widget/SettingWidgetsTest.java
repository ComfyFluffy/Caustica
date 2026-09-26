package dev.comfyfluffy.caustica.minecraft.client.screen.widget;

import dev.comfyfluffy.caustica.minecraft.client.settings.SettingControl;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;

final class SettingWidgetsTest {
    @Test
    void theResetButtonAppearsOnlyWhileTheValueDiffersFromItsDefault() {
        var control = new Bool();
        var gate = new AtomicBoolean(true);
        var reset = SettingWidgets.reset(control, gate::get);
        assertFalse(reset.visible);
        assertFalse(reset.isActive());

        control.value = true;
        reset.sync();
        assertTrue(reset.visible);
        assertTrue(reset.isActive());
        gate.set(false);
        assertFalse(reset.isActive());
        reset.onPress(null);
        assertTrue(control.value);

        gate.set(true);
        reset.onPress(null);
        assertFalse(control.value);
        reset.sync();
        assertFalse(reset.visible);
        assertFalse(reset.isActive());
    }

    @Test
    void aClosedSectionGreysOutItsRows() {
        var control = new Bool();
        var gate = new AtomicBoolean(false);
        var toggle = (AbstractButton) SettingWidgets.control(control, gate::get, 150);
        toggle.onPress(null);
        assertFalse(control.value);
        gate.set(true);
        toggle.onPress(null);
        assertTrue(control.value);
    }

    @Test
    void availabilityCanChangeAfterConstruction() {
        var control = new Bool();
        control.enabled = false;
        var toggle = (AbstractButton) SettingWidgets.control(control, () -> true, 150);
        assertFalse(toggle.isActive());
        toggle.onPress(null);
        assertFalse(control.value);
        control.enabled = true;
        assertTrue(toggle.isActive());
        toggle.onPress(null);
        assertTrue(control.value);
    }

    @Test
    void aLongChoiceListIsASliderOverItsDeclaredOrder() {
        var control = new Choice(List.of("a", "b", "c", "d", "e"));
        AbstractWidget slider = SettingWidgets.control(control, () -> true, 108);
        slider.onClick(new MouseButtonEvent(104, 5, new MouseButtonInfo(0, 0)), false);
        assertEquals("e", control.value);

        slider.keyPressed(new KeyEvent(GLFW_KEY_ENTER, 0, 0));
        control.value = "b";
        slider.keyPressed(new KeyEvent(GLFW_KEY_RIGHT, 0, 0));
        assertEquals("c", control.value);
    }

    @Test
    void aPairOfChoicesCyclesOnPress() {
        var control = new Choice(List.of("auto", "manual"));
        var button = (AbstractButton) SettingWidgets.control(control, () -> true, 150);
        button.onPress(null);
        assertEquals("manual", control.value);
        button.onPress(null);
        assertEquals("auto", control.value);
    }

    @Test
    void sizingSeesEveryChoiceAndEveryStepOfAShortSlider() {
        assertEquals(List.of("a", "b", "c"), values(SettingWidgets.captions(new Choice(List.of("a", "b", "c")))));
        var steps = new SettingControl.RangeControl() {
            @Override public String id() { return "steps"; }
            @Override public Component label() { return Component.empty(); }
            @Override public Component tooltip() { return Component.empty(); }
            @Override public boolean enabled() { return true; }
            @Override public double get() { return 0; }
            @Override public void set(double value) { }
            @Override public double defaultValue() { return 2; }
            @Override public double sliderMinimum() { return 0; }
            @Override public double sliderMaximum() { return 4; }
            @Override public double step() { return 1; }
            @Override public Component format(double value) { return Component.literal(Double.toString(value)); }
        };
        assertEquals(List.of("2.0", "0.0", "1.0", "2.0", "3.0", "4.0"), values(SettingWidgets.captions(steps)));
    }

    /** The value half of each {@code "Label: value"} caption. */
    private static List<String> values(List<Component> captions) {
        return captions.stream()
                .map(caption -> ((Component) ((TranslatableContents) caption.getContents()).getArgs()[1]).getString())
                .toList();
    }

    private static final class Bool implements SettingControl.BoolControl {
        boolean value;
        boolean enabled = true;
        @Override public String id() { return "bool"; }
        @Override public Component label() { return Component.empty(); }
        @Override public Component tooltip() { return Component.empty(); }
        @Override public boolean enabled() { return enabled; }
        @Override public boolean get() { return value; }
        @Override public void set(boolean value) { this.value = value; }
        @Override public boolean defaultValue() { return false; }
    }

    private static final class Choice implements SettingControl.ChoiceControl<String> {
        private final List<String> choices;
        String value;

        Choice(List<String> choices) {
            this.choices = choices;
            this.value = choices.getFirst();
        }

        @Override public String id() { return "choice"; }
        @Override public Component label() { return Component.empty(); }
        @Override public Component tooltip() { return Component.empty(); }
        @Override public boolean enabled() { return true; }
        @Override public List<String> choices() { return choices; }
        @Override public String get() { return value; }
        @Override public void set(String value) { this.value = value; }
        @Override public String defaultValue() { return choices.getFirst(); }
        @Override public Component labelOf(String value) { return Component.literal(value); }
    }
}
