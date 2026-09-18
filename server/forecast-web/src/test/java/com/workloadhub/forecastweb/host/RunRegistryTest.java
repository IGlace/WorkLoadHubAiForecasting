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

    @Test
    void theLatestRunIsTheLastOneRegistered() {
        // The caller writes started_at from the real clock, never from the module's Clock bean: this host's
        // demo clock is pinned and an admin moves it backwards, and the one-at-a-time rule must still look at
        // the run that is actually computing.
        RunRegistry registry = new RunRegistry(JdbcClient.create(DatabaseTestSupport.sqliteInMemory()));
        UUID user = UUID.randomUUID();
        UUID team = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        registry.register(first, team, user, LocalDateTime.of(2026, 9, 18, 10, 0));
        registry.register(second, team, user, LocalDateTime.of(2026, 9, 18, 10, 5));
        assertEquals(Optional.of(second), registry.latestRunOf(user));
    }
}
