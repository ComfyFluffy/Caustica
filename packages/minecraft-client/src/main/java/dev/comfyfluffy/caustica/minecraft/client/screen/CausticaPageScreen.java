package dev.comfyfluffy.caustica.minecraft.client.screen;

import dev.comfyfluffy.caustica.minecraft.client.config.CausticaOptions;
import dev.comfyfluffy.caustica.minecraft.client.settings.SettingsPage;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * One options page in the vanilla options-screen frame: title, scrolling rows, and a footer with
 * "Reset Page" and "Done".
 *
 * <p>Rows write the shared preference store as they change, so the renderer follows on its next frame. The
 * page is re-derived every tick and its rows are rebuilt in place when a change alters which rows apply, such
 * as picking another tone mapper, or when the player folds a section; the rebuild waits until no control is
 * held, so a slider being dragged across mappers is not replaced under the pointer. Opening, leaving and
 * rebuilding a page never render a frame outside the game's own frame loop. The store reaches disk when the
 * page closes.
 */
public final class CausticaPageScreen extends Screen {
    private final Screen parent;
    private final Supplier<SettingsPage> source;
    private final CausticaOptions store;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private SettingsPage page;
    private CausticaPageList rows;

    public CausticaPageScreen(Screen parent, Supplier<SettingsPage> source, CausticaOptions store) {
        this(parent, source, source.get(), store);
    }

    private CausticaPageScreen(Screen parent, Supplier<SettingsPage> source, SettingsPage page,
                               CausticaOptions store) {
        super(page.title());
        this.parent = parent;
        this.source = source;
        this.page = page;
        this.store = store;
    }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);
        rows = layout.addToContents(new CausticaPageList(minecraft, width, layout));
        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(Component.translatable("caustica.page.reset_page"), button -> page.reset())
                .tooltip(Tooltip.create(Component.translatable("caustica.page.reset_page.tooltip")))
                .build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    /** Rows are laid out again at the new width, since it decides which controls need a whole row. */
    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        rows.updateSize(width, layout);
        rows.show(page);
    }

    @Override
    public void tick() {
        SettingsPage next = source.get();
        if (!isDragging() && (rows.foldChanged() || !next.shape().equals(page.shape()))) {
            page = next;
            rows.show(page);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void removed() {
        store.save();
    }
}
