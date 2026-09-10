package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.MemberWeekForecast;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunStatus;
import com.workloadhub.forecast.api.RunSummary;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcRunStoreTest {

    static final UUID TEAM = UUID.fromString("40000000-0000-0000-0000-000000000001");
    static final UUID USER = UUID.fromString("30000000-0000-0000-0000-000000000001");
    /** Sub-second precision on purpose: {@code ts()} must keep microseconds, not truncate to the second. */
    static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 6, 10, 0, 0, 123456000);

    static DataSource sqlite() {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        ForecastMigrations.run(ds);
        return ds;
    }

    static List<MemberWeekForecast> rows() {
        return List.of(
                new MemberWeekForecast(USER, LocalDate.of(2026, 9, 7), 10, 5.5, 2, 17.5, 15, 20, 40, 0, 5, 0),
                new MemberWeekForecast(USER, LocalDate.of(2026, 9, 14), 4, 6, 0, 10, 10, 12, 32, 0, 4, 8));
    }

    void lifecycle(DataSource ds) {
        JdbcRunStore store = new JdbcRunStore(ds, Dialect.of(ds));
        UUID id = store.create(new RunRequest(TEAM, USER, LocalDate.of(2026, 9, 6), null, null), T0);
        RunSummary queued = store.find(id).orElseThrow();
        assertEquals(RunStatus.QUEUED, queued.status());
        assertEquals(TEAM, queued.teamId());
        assertEquals(LocalDate.of(2026, 9, 6), queued.asOf());
        assertEquals(T0, queued.createdAt());
        store.markRunning(id);
        assertEquals(RunStatus.RUNNING, store.find(id).orElseThrow().status());
        store.finish(id, "xgboost", 0.83, "{\"scores\":[]}", rows(), "{\"run\":{}}", T0.plusMinutes(1));
        RunSummary done = store.find(id).orElseThrow();
        assertEquals(RunStatus.DONE, done.status());
        assertEquals("xgboost", done.championModel());
        assertEquals(0.83, done.championMase(), 1e-9);
        assertEquals(T0.plusMinutes(1), done.finishedAt());
        assertEquals(rows(), store.memberWeeks(id));
        assertEquals("{\"run\":{}}", store.facts(id).orElseThrow());
        assertEquals("{\"scores\":[]}", store.backtestJson(id).orElseThrow());

        UUID failed = store.create(new RunRequest(TEAM, null, LocalDate.of(2026, 9, 6), "xgboost", false), T0.plusMinutes(2));
        store.fail(failed, "boom\nstack line 2", T0.plusMinutes(3));
        RunSummary f = store.find(failed).orElseThrow();
        assertEquals(RunStatus.FAILED, f.status());
        assertEquals("boom", f.error());
        assertEquals("xgboost", f.forcedModel());
        assertNull(f.championModel());
        assertTrue(store.facts(failed).isEmpty());

        List<RunSummary> list = store.list(TEAM, 10);
        assertEquals(List.of(failed, id), list.stream().map(RunSummary::id).toList(), "newest first");
        assertEquals(1, store.list(TEAM, 1).size());
        assertTrue(store.list(UUID.randomUUID(), 10).isEmpty());
        assertFalse(store.find(UUID.randomUUID()).isPresent());
        UUID nanRun = store.create(new RunRequest(TEAM, USER, LocalDate.of(2026, 9, 6), null, null), T0.plusMinutes(4));
        store.finish(nanRun, "seasonal_naive", Double.NaN, "{}", List.of(), "{}", T0.plusMinutes(5));
        assertNull(store.find(nanRun).orElseThrow().championMase(), "NaN is stored as null");

        UUID bigRun = store.create(new RunRequest(TEAM, USER, LocalDate.of(2026, 9, 6), null, null), T0.plusMinutes(6));
        List<MemberWeekForecast> manyRows = manyRows(450);
        store.finish(bigRun, "xgboost", 0.5, "{}", manyRows, "{}", T0.plusMinutes(7));
        assertEquals(manyRows, store.memberWeeks(bigRun), "450 rows survive a batched insert, in order");
    }

    /** {@code count} consecutive Monday weeks for one user, spanning more than one batch of {@link JdbcRunStore#BATCH}. */
    static List<MemberWeekForecast> manyRows(int count) {
        LocalDate week = LocalDate.of(2027, 1, 4);
        List<MemberWeekForecast> out = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(new MemberWeekForecast(USER, week.plusWeeks(i), 1, 2, 0, 3, 2, 5, 40, 0, 5, 0));
        }
        return out;
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

    void tiedCreatedAtOrdersByIdDescending(DataSource ds) {
        JdbcRunStore store = new JdbcRunStore(ds, Dialect.of(ds));
        UUID a = store.create(new RunRequest(TEAM, USER, LocalDate.of(2026, 9, 6), null, null), T0);
        UUID b = store.create(new RunRequest(TEAM, USER, LocalDate.of(2026, 9, 6), null, null), T0);
        List<UUID> ordered = store.list(TEAM, 10).stream().map(RunSummary::id).toList();
        List<UUID> expected = a.toString().compareTo(b.toString()) > 0 ? List.of(a, b) : List.of(b, a);
        assertEquals(expected, ordered, "same created_at: newest (highest) id first");
    }

    @Test
    void sqliteTiedCreatedAtOrdersByIdDescending() {
        tiedCreatedAtOrdersByIdDescending(sqlite());
    }

    @Test
    void postgresTiedCreatedAtOrdersByIdDescending() {
        DataSource ds = DatabaseTestSupport.postgresOrSkip();
        WorkloadHubSchema.createPostgresql(ds);
        ForecastMigrations.run(ds);
        tiedCreatedAtOrdersByIdDescending(ds);
    }
}
