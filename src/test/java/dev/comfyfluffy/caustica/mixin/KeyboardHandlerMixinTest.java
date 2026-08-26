package dev.comfyfluffy.caustica.mixin;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KeyboardHandlerMixinTest {
    @Test
    void captureAllowsReleasesAndOnlyTheInitialUltraTogglePress() {
        assertFalse(KeyboardHandlerMixin.shouldSuppressCaptureKey(false, GLFW.GLFW_PRESS, false));
        assertFalse(KeyboardHandlerMixin.shouldSuppressCaptureKey(true, GLFW.GLFW_RELEASE, false));
        assertFalse(KeyboardHandlerMixin.shouldSuppressCaptureKey(true, GLFW.GLFW_PRESS, true));

        assertTrue(KeyboardHandlerMixin.shouldSuppressCaptureKey(true, GLFW.GLFW_PRESS, false));
        assertTrue(KeyboardHandlerMixin.shouldSuppressCaptureKey(true, GLFW.GLFW_REPEAT, false));
        assertTrue(KeyboardHandlerMixin.shouldSuppressCaptureKey(true, GLFW.GLFW_REPEAT, true));
    }
}
