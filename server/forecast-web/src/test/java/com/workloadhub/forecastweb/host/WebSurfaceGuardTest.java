package com.workloadhub.forecastweb.host;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WebSurfaceGuardTest {

    @Test
    void theModulesOwnControllerMayNotBeUpNextToThisHost() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> WebSurfaceGuard.check(true));
        assertTrue(e.getMessage().contains("whf.web.enabled must stay false"), e.getMessage());
        assertTrue(e.getMessage().contains("trusts requestedBy"), "the message says why");
        assertDoesNotThrow(() -> WebSurfaceGuard.check(false));
    }
}
