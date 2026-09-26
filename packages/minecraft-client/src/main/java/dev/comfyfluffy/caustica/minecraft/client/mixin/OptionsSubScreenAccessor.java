package dev.comfyfluffy.caustica.minecraft.client.mixin;

import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the option list built by {@link OptionsSubScreen} so subscreen mixins can append entries, and the
 * screen it returns to so one can be reopened with the same parent.
 */
@Mixin(OptionsSubScreen.class)
public interface OptionsSubScreenAccessor {
    @Accessor("list")
    OptionsList getList();

    @Accessor("lastScreen")
    Screen getLastScreen();
}
