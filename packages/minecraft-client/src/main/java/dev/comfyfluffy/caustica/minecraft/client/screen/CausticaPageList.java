package dev.comfyfluffy.caustica.minecraft.client.screen;

import dev.comfyfluffy.caustica.minecraft.client.screen.widget.SettingWidgets;
import dev.comfyfluffy.caustica.minecraft.client.settings.SettingControl;
import dev.comfyfluffy.caustica.minecraft.client.settings.SettingGroup;
import dev.comfyfluffy.caustica.minecraft.client.settings.SettingsPage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

/**
 * The rows of an options page, laid out like vanilla's options list: section headings, then two controls per
 * row. Each control keeps a slot on its right for a reset button, which appears only while the control holds
 * a non-default value; that slot is why this is not vanilla's list, whose rows hold exactly two widgets.
 *
 * <p>A section's whole-row controls come first and the rest follow in pairs, so rows never alternate between
 * widths. A control is whole-row when the page asks for it or when its longest caption would not fit half a
 * row, measured with the active font and language and again whenever the window resizes. Advanced sections
 * start folded behind one button; which ones the player unfolded is kept for the rest of the session.
 */
final class CausticaPageList extends ContainerObjectSelectionList<CausticaPageList.Row> {
    private static final int ROW_HEIGHT = 25;
    private static final int MAX_ROW_WIDTH = 400;
    /** Leaves room for the scrollbar beside the rows on the narrowest GUI-scaled window. */
    private static final int SIDE_MARGIN = 40;
    private static final int COLUMN_GAP = 10;
    private static final int RESET_GAP = 2;
    private static final Set<String> UNFOLDED = new HashSet<>();

    private boolean foldChanged;

    CausticaPageList(Minecraft minecraft, int width, HeaderAndFooterLayout layout) {
        super(minecraft, width, layout.getContentHeight(), layout.getHeaderHeight(), ROW_HEIGHT);
        centerListVertically = false;
    }

    static int rowWidth(int listWidth) {
        return Math.min(MAX_ROW_WIDTH, listWidth - SIDE_MARGIN);
    }

    /** Width of the control itself, beside its reset slot, in a half or a whole row. */
    static int controlWidth(int rowWidth, boolean wide) {
        int cell = wide ? rowWidth : (rowWidth - COLUMN_GAP) / 2;
        return cell - RESET_GAP - SettingWidgets.RESET_WIDTH;
    }

    /** The control width at which none of {@code control}'s captions is clipped. */
    static int captionWidth(SettingControl control, ToIntFunction<FormattedText> width) {
        return SettingWidgets.captions(control).stream().mapToInt(width::applyAsInt).max().orElseThrow()
                + 2 * SettingWidgets.CAPTION_MARGIN;
    }

    @Override
    public int getRowWidth() {
        return rowWidth(width);
    }

    /** Whether the player folded or unfolded a section since the rows were last shown. */
    boolean foldChanged() {
        return foldChanged;
    }

    /** Replaces the rows with {@code page}'s, keeping the scroll position so a rebuild does not jump. */
    void show(SettingsPage page) {
        foldChanged = false;
        double scroll = scrollAmount();
        setFocused(null);
        clearEntries();
        int half = controlWidth(getRowWidth(), false);
        Predicate<SettingControl> whole = control -> page.wide().contains(control.id())
                || captionWidth(control, minecraft.font::width) > half;
        for (SettingGroup section : page.sections()) {
            if (section.advanced()) {
                String key = page.id() + "/" + section.id();
                addFold(key, section.rows().size());
                if (!UNFOLDED.contains(key)) continue;
            } else {
                addText(section.title());
            }
            List<Cell> pending = new ArrayList<>();
            List<SettingControl> rows = Stream.concat(section.rows().stream().filter(whole),
                    section.rows().stream().filter(whole.negate())).toList();
            for (SettingControl control : rows) {
                Cell cell = new Cell(control, () -> section.editable(control));
                if (whole.test(control)) {
                    addEntry(new Controls(List.of(cell), true));
                } else {
                    pending.add(cell);
                    if (pending.size() == 2) flush(pending);
                }
            }
            flush(pending);
        }
        setScrollAmount(scroll);
    }

    /** Matches vanilla's header spacing: flush with the list top, then a blank line above each later one. */
    private void addText(Component text) {
        int paddingTop = children().isEmpty() ? 0 : minecraft.font.lineHeight * 2;
        addEntry(new Text(text, paddingTop), paddingTop + minecraft.font.lineHeight + 4);
    }

    private void addFold(String key, int rows) {
        boolean unfolded = UNFOLDED.contains(key);
        Component label = unfolded ? Component.translatable("caustica.page.section.hide_advanced")
                : Component.translatable("caustica.page.section.show_advanced", rows);
        addEntry(new Fold(Button.builder(label, button -> {
            if (!UNFOLDED.remove(key)) UNFOLDED.add(key);
            foldChanged = true;
        }).build()), ROW_HEIGHT + minecraft.font.lineHeight);
    }

    private void flush(List<Cell> pending) {
        if (!pending.isEmpty()) {
            addEntry(new Controls(List.copyOf(pending), false));
            pending.clear();
        }
    }

    abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
    }

    private final class Text extends Row {
        private final StringWidget widget;
        private final int paddingTop;

        Text(Component text, int paddingTop) {
            this.widget = new StringWidget(text, minecraft.font);
            this.paddingTop = paddingTop;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered,
                                   float partialTick) {
            widget.setMaxWidth(getRowWidth());
            widget.setPosition(getRowLeft(), getContentY() + paddingTop);
            widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(widget);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(widget);
        }
    }

    /** The whole-row button that folds or unfolds an advanced section, a blank line below the rows above. */
    private final class Fold extends Row {
        private final Button button;

        Fold(Button button) {
            this.button = button;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered,
                                   float partialTick) {
            button.setWidth(controlWidth(getRowWidth(), true));
            button.setPosition(getRowLeft(), getContentY() + minecraft.font.lineHeight);
            button.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(button);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(button);
        }
    }

    /** A control and the reset button in the slot beside it. */
    private record Cell(AbstractWidget control, SettingWidgets.Reset reset) {
        Cell(SettingControl control, BooleanSupplier editable) {
            this(SettingWidgets.control(control, editable, 0), SettingWidgets.reset(control, editable));
        }
    }

    private final class Controls extends Row {
        private final List<Cell> cells;
        private final boolean wide;
        private final List<AbstractWidget> widgets;

        Controls(List<Cell> cells, boolean wide) {
            this.cells = cells;
            this.wide = wide;
            this.widgets = cells.stream().flatMap(cell -> Stream.<AbstractWidget>of(cell.control(), cell.reset()))
                    .toList();
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered,
                                   float partialTick) {
            int controlWidth = controlWidth(getRowWidth(), wide);
            int x = getRowLeft();
            for (Cell cell : cells) {
                cell.control().setWidth(controlWidth);
                cell.control().setPosition(x, getContentY());
                cell.reset().setPosition(x + controlWidth + RESET_GAP, getContentY());
                cell.reset().sync();
                cell.control().extractRenderState(graphics, mouseX, mouseY, partialTick);
                cell.reset().extractRenderState(graphics, mouseX, mouseY, partialTick);
                x += controlWidth + RESET_GAP + SettingWidgets.RESET_WIDTH + COLUMN_GAP;
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return widgets;
        }
    }
}
