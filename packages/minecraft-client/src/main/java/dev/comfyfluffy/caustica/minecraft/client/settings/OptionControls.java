package dev.comfyfluffy.caustica.minecraft.client.settings;

import dev.comfyfluffy.caustica.minecraft.client.config.CausticaOptions;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.ResourceId;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Predicate;

/** Adapts declared options to native screen controls backed by the shared in-memory preference store. */
public final class OptionControls {
    private OptionControls() { }

    public static SettingControl of(CausticaOptions store, ResourceId featureId, Option<?> option) {
        return of(store, featureId, option, ignored -> true);
    }

    /** Null for options without a native row, including optional paths and unrestricted colors. */
    public static SettingControl of(CausticaOptions store, ResourceId featureId, Option<?> option,
                                    Predicate<Option<?>> available) {
        return switch (option.kind()) {
            case BOOL -> new BoolRow(store, featureId, cast(option), available);
            case RANGE, INTEGER -> new RangeRow(store, featureId, cast(option), available);
            case INT_CHOICE, STRING_CHOICE, ENUM -> new ChoiceRow<>(store, featureId, option, available);
            case OPTIONAL_STRING, COLOR -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> Option<T> cast(Option<?> option) {
        return (Option<T>) option;
    }

    private abstract static class Row<T> {
        final CausticaOptions store;
        final ResourceId featureId;
        final Option<T> option;
        final Predicate<Option<?>> available;

        Row(CausticaOptions store, ResourceId featureId, Option<T> option, Predicate<Option<?>> available) {
            this.store = store;
            this.featureId = featureId;
            this.option = option;
            this.available = available;
        }

        public String id() { return option.id(); }
        public Component label() { return LangKeys.optionLabel(featureId, option); }
        public Component tooltip() { return LangKeys.optionTooltip(featureId, option); }
        public boolean enabled() { return available.test(option) && !store.overridden(featureId, option); }
        final T value() { return store.options(featureId).get(option); }
        final void write(Object value) { store.apply(featureId, option, value); }
    }

    private static final class BoolRow extends Row<Boolean> implements SettingControl.BoolControl {
        BoolRow(CausticaOptions store, ResourceId featureId, Option<Boolean> option,
                Predicate<Option<?>> available) {
            super(store, featureId, option, available);
        }

        @Override public boolean get() { return value(); }
        @Override public void set(boolean value) { write(value); }
        @Override public boolean defaultValue() { return option.defaultValue(); }
    }

    private static final class RangeRow extends Row<Number> implements SettingControl.RangeControl {
        RangeRow(CausticaOptions store, ResourceId featureId, Option<Number> option,
                 Predicate<Option<?>> available) {
            super(store, featureId, option, available);
        }

        @Override public double get() { return value().doubleValue(); }
        @Override public void set(double value) { write(value); }
        @Override public double defaultValue() { return option.defaultValue().doubleValue(); }
        @Override public double sliderMinimum() { return option.sliderMinimum(); }
        @Override public double sliderMaximum() { return option.sliderMaximum(); }
        @Override public double step() { return option.kind() == Option.Kind.INTEGER ? 1 : option.step(); }
        @Override public Component format(double value) {
            String number = step() == 1.0
                    ? Integer.toString((int) Math.round(value)) : SettingsFormat.decimal(value);
            return LangKeys.optionValue(featureId, option, number, SettingsFormat.decimal(value * 100.0));
        }
    }

    private static final class ChoiceRow<T> extends Row<T> implements SettingControl.ChoiceControl<T> {
        ChoiceRow(CausticaOptions store, ResourceId featureId, Option<T> option,
                  Predicate<Option<?>> available) {
            super(store, featureId, option, available);
        }

        @Override public List<T> choices() { return option.choices(); }
        @Override public T get() { return value(); }
        @Override public void set(T value) { write(value); }
        @Override public T defaultValue() { return option.defaultValue(); }
        @Override public Component labelOf(T value) { return LangKeys.optionChoice(featureId, option, value); }
    }
}
