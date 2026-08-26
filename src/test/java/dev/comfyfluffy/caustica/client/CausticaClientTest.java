package dev.comfyfluffy.caustica.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CausticaClientTest {
    @Test
    void nativeOwnershipMustFullyReleaseBeforeDeviceDestruction() {
        assertFalse(CausticaClient.teardownRequiresRestart(true, true));
        assertTrue(CausticaClient.teardownRequiresRestart(false, true));
        assertTrue(CausticaClient.teardownRequiresRestart(true, false));
        assertTrue(CausticaClient.teardownRequiresRestart(false, false));
    }
}
