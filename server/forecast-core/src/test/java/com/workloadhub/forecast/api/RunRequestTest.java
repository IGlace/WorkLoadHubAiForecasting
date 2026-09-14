package com.workloadhub.forecast.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunRequestTest {

    static final UUID TEAM = UUID.randomUUID();

    @Test
    void aTeamIsRequired() {
        ForecastException e = assertThrows(ForecastException.class, () -> new RunRequest(null, UUID.randomUUID()));
        assertEquals("INVALID_REQUEST", e.code());
    }
}
