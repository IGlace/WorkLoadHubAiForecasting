package com.workloadhub.forecast.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunRequestTest {

    static final UUID TEAM = UUID.randomUUID();

    @Test
    void normalisesBlankModelAndDefaultsPlannedWork() {
        RunRequest r = new RunRequest(TEAM, null, " ", null);
        assertNull(r.forcedModel());
        assertTrue(r.plannedWorkOr(true));
        assertEquals(false, new RunRequest(TEAM, null, "xgboost", false).plannedWorkOr(true));
    }

    @Test
    void rejectsMissingTeamOrUnknownModel() {
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> new RunRequest(null, null, null, null)).code());
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> new RunRequest(TEAM, null, "chronos", null)).code());
    }
}
