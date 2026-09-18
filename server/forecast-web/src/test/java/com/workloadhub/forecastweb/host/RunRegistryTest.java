package com.workloadhub.forecastweb.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.store.DatabaseTestSupport;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class RunRegistryTest {

    @Test
    void theRegistrySurvivesANewInstanceOnTheSameDatabase() {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        JdbcClient jdbc = JdbcClient.create(ds);
        RunRegistry first = new RunRegistry(jdbc);
        UUID run = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        UUID team = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        first.register(older, team, user, LocalDateTime.of(2026, 9, 6, 9, 0));
        first.register(run, team, user, LocalDateTime.of(2026, 9, 6, 10, 0));

        RunRegistry second = new RunRegistry(jdbc); // a restart: CREATE TABLE IF NOT EXISTS keeps the rows
        assertEquals(Optional.of(team), second.teamOf(run));
        assertEquals(Optional.of(run), second.latestRunOf(user), "the latest by start time");
        assertTrue(second.teamOf(UUID.randomUUID()).isEmpty());
        assertTrue(second.latestRunOf(UUID.randomUUID()).isEmpty());
    }
}
