package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Player-facing Caustica key mappings grouped in Minecraft's Controls screen. */
public final class CausticaKeyMappings {
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(CausticaMod.MOD_ID, "controls"));

    private CausticaKeyMappings() {
    }

    public static KeyMapping[] all() {
        return new KeyMapping[] {UltraScreenshot.KEY};
    }
}
