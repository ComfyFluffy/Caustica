package dev.comfyfluffy.caustica.minecraft.client.settings;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One options page: titled sections of rows.
 *
 * <p>A page is a snapshot of what the preferences currently make relevant; the tone-mapping page, for
 * instance, lists only the selected mapper's rows. The screen re-derives the page as preferences change and
 * rebuilds its rows in place when {@link #shape()} differs.
 *
 * @param wide ids of rows that span both columns, such as a selector whose choices decide the rest of the page
 */
public record SettingsPage(String id, Component title, List<SettingGroup> sections, Set<String> wide) {
    public SettingsPage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        sections = List.copyOf(sections);
        wide = Set.copyOf(wide);
    }

    public SettingsPage(String id, Component title, List<SettingGroup> sections) {
        this(id, title, sections, Set.of());
    }

    public List<SettingControl> allControls() {
        return sections.stream().flatMap(section -> section.rows().stream()).toList();
    }

    /** Restores every editable row; rows held by a process override keep their value. */
    public void reset() {
        allControls().stream().filter(SettingControl::enabled).forEach(SettingControl::reset);
    }

    /** Section and row identities: everything that decides which widgets exist. */
    public List<String> shape() {
        List<String> shape = new ArrayList<>();
        for (SettingGroup section : sections) {
            shape.add(section.id());
            section.rows().forEach(control -> shape.add(control.id()));
        }
        return shape;
    }
}
