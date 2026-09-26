package dev.comfyfluffy.caustica.minecraft.client.settings;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Objects;

/**
 * A titled run of rows on an options page.
 *
 * <p>{@code gate} is the bool whose value decides whether the other rows mean anything, such as bloom's on/off
 * switch: while it is off they stay visible but disabled. It is the section's first row.
 *
 * <p>An {@code advanced} section holds internals most players never change, so a page shows it folded behind
 * one button instead of under its title until the player unfolds it.
 */
public record SettingGroup(String id, Component title, SettingControl.BoolControl gate,
                           List<SettingControl> rows, boolean advanced) {
    public SettingGroup {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        rows = List.copyOf(rows);
    }

    public SettingGroup(String id, Component title, List<SettingControl> rows) {
        this(id, title, null, rows, false);
    }

    /** Whether {@code row} can be edited given the gate; the gate itself always can. */
    public boolean editable(SettingControl row) {
        return gate == null || row == gate || gate.get();
    }
}
