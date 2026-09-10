package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.ai.NarrationOutcome;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.NarrativeStatus;
import com.workloadhub.forecast.api.RunRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcNarrativeStoreTest {

    static final UUID TEAM = UUID.fromString("40000000-0000-0000-0000-000000000001");
    static final UUID USER = UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 10, 9, 0, 0, 123456000);

    static DataSource sqlite() {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        ForecastMigrations.run(ds);
        return ds;
    }

    static NarrationOutcome ok() {
        return new NarrationOutcome(NarrativeStatus.OK, null, "{\"run_summary\":\"fine\"}", null, "{\"checked\":2,\"unverified\":[]}",
                "{\"source\":\"metrics\"}", "gpt-5", 1, List.of("get_run_overview", "get_member_forecast"), null);
    }

    static NarrationOutcome failed() {
        return new NarrationOutcome(NarrativeStatus.FAILED, "invalid_output", null, "still nope", "{}", "{\"source\":\"events\"}", "gpt-5", 2,
                List.of("get_run_overview"), "invalid_output: the answer is not valid JSON");
    }

    void lifecycle(DataSource ds) {
        Dialect dialect = Dialect.of(ds);
        JdbcRunStore runs = new JdbcRunStore(ds, dialect);
        UUID run = runs.create(new RunRequest(TEAM, USER, null, null), LocalDate.of(2026, 9, 6), T0);
        JdbcNarrativeStore store = new JdbcNarrativeStore(ds, dialect);

        NarrativeResult first = store.save(run, "en", ok(), T0.plusMinutes(1));
        assertEquals(NarrativeStatus.OK, first.status());
        assertEquals(run, first.runId());
        assertEquals("en", first.language());
        assertEquals("gpt-5", first.model());
        assertEquals("{\"run_summary\":\"fine\"}", first.narrativeJson());
        assertNull(first.rawText());
        assertEquals(1, first.attempts());
        assertEquals(2, first.toolCalls());
        assertEquals(T0.plusMinutes(1), first.createdAt());
        assertEquals(first, store.find(first.id()).orElseThrow());
        assertEquals(first, store.latest(run, "en").orElseThrow());
        assertTrue(store.latest(run, "fr").isEmpty());

        NarrativeResult second = store.save(run, "en", failed(), T0.plusMinutes(2));
        assertEquals(NarrativeStatus.FAILED, second.status());
        assertNull(second.narrativeJson());
        assertEquals("still nope", second.rawText());
        assertEquals("invalid_output: the answer is not valid JSON", second.error());
        assertEquals(second, store.latest(run, "en").orElseThrow(), "the newest row wins whatever its status");

        NarrativeResult french = store.save(run, "fr", ok(), T0.plusMinutes(3));
        assertEquals(french, store.latest(run, "fr").orElseThrow());
        assertEquals(second, store.latest(run, "en").orElseThrow());
        assertTrue(store.find(UUID.randomUUID()).isEmpty());
        assertTrue(store.latest(UUID.randomUUID(), "en").isEmpty());
    }

    @Test
    void sqliteLifecycle() {
        lifecycle(sqlite());
    }

    @Test
    void postgresLifecycle() {
        DataSource ds = DatabaseTestSupport.postgresOrSkip();
        WorkloadHubSchema.createPostgresql(ds);
        ForecastMigrations.run(ds);
        lifecycle(ds);
    }
}
