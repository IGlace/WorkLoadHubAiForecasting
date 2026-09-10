package com.workloadhub.forecast.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunRequestTest {

    static final UUID TEAM = UUID.randomUUID();
    static final LocalDate AS_OF = LocalDate.of(2026, 9, 6);

    @Test
    void normalisesBlankModelAndDefaultsPlannedWork() {
        RunRequest r = new RunRequest(TEAM, null, AS_OF, " ", null);
        assertNull(r.forcedModel());
        assertTrue(r.plannedWorkOr(true));
        assertEquals(false, new RunRequest(TEAM, null, AS_OF, "xgboost", false).plannedWorkOr(true));
    }

    @Test
    void rejectsMissingTeamOrDateOrUnknownModel() {
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> new RunRequest(null, null, AS_OF, null, null)).code());
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> new RunRequest(TEAM, null, null, null, null)).code());
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> new RunRequest(TEAM, null, AS_OF, "chronos", null)).code());
    }
}
