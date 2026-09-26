package dev.comfyfluffy.caustica.minecraft.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TonemapperQuickToggleTest {
    private static final List<String> SDR = List.of("aces2.0", "agx", "psychov31", "psychov30");
    private static final List<String> HDR = List.of("aces2.0", "caustica", "psychov31");

    @Test
    void aGoesToBAndAnythingElseToA() {
        assertEquals("psychov31", TonemapperQuickToggle.next("aces2.0", "aces2.0", "psychov31", SDR));
        assertEquals("aces2.0", TonemapperQuickToggle.next("psychov31", "aces2.0", "psychov31", SDR));
        assertEquals("aces2.0", TonemapperQuickToggle.next("agx", "aces2.0", "psychov31", SDR));
    }

    @Test
    void anOutputWithoutTheTargetKeepsItsMapper() {
        assertEquals("psychov31", TonemapperQuickToggle.next("psychov31", "psychov31", "psychov30", HDR));
        assertEquals("aces2.0", TonemapperQuickToggle.next("aces2.0", "caustica", "psychov31", SDR));
        assertEquals("caustica", TonemapperQuickToggle.next("aces2.0", "caustica", "psychov31", HDR));
    }
}
