package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.MemberDayForecast;
import com.workloadhub.forecast.api.MemberWindowForecast;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunStatus;
import com.workloadhub.forecast.api.RunSummary;
import com.workloadhub.forecast.calendar.ForecastWindow;
import com.workloadhub.forecast.calendar.Horizon;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    static List<MemberWindowForecast> windows() {
        return List.of(
                new MemberWindowForecast(USER, 1, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 11), 10, 5.5, 2, 17.5, 15, 20, 40, 0, 5, 0),
                new MemberWindowForecast(USER, 2, LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 18), 4, 6, 0, 10, 10, 12, 32, 0, 4, 8));
    }

    /** Ten days of one member for a run made on {@code asOf}, every day carrying {@code demand} so a later run's values are recognisable. */
    static List<MemberDayForecast> days(LocalDate asOf, double demand) {
        List<MemberDayForecast> out = new ArrayList<>();
        for (ForecastWindow w : Horizon.windows(asOf)) {
            for (LocalDate d : w.weekdays()) {
                out.add(new MemberDayForecast(USER, d, w.index(), demand, 0, 0, demand, 8, Math.max(0, demand - 8), true));
            }
        }
        return out;
    }

    void lifecycle(DataSource ds) {
        JdbcRunStore store = new JdbcRunStore(ds, Dialect.of(ds));
        UUID id = store.create(new RunRequest(TEAM, USER, null, null), LocalDate.of(2026, 9, 6), T0);
        RunSummary queued = store.find(id).orElseThrow();
        assertEquals(RunStatus.QUEUED, queued.status());
        assertEquals(TEAM, queued.teamId());
        assertEquals(LocalDate.of(2026, 9, 6), queued.asOf());
        assertEquals(T0, queued.createdAt());
        store.markRunning(id);
        assertEquals(RunStatus.RUNNING, store.find(id).orElseThrow().status());
        store.finish(id, "xgboost", 0.83, "{\"scores\":[]}", windows(), days(LocalDate.of(2026, 9, 6), 3), "{\"run\":{}}", T0.plusMinutes(1));
        RunSummary done = store.find(id).orElseThrow();
        assertEquals(RunStatus.DONE, done.status());
        assertEquals("xgboost", done.championModel());
        assertEquals(0.83, done.championMase(), 1e-9);
        assertEquals(T0.plusMinutes(1), done.finishedAt());
        assertEquals(windows(), store.memberWindows(id));
        assertEquals(days(LocalDate.of(2026, 9, 6), 3), store.memberDays(id));
        assertEquals(10, store.currentDays(TEAM, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).size());
        assertEquals("{\"run\":{}}", store.facts(id).orElseThrow());
        assertEquals("{\"scores\":[]}", store.backtestJson(id).orElseThrow());

        UUID failed = store.create(new RunRequest(TEAM, null, "xgboost", false), LocalDate.of(2026, 9, 6), T0.plusMinutes(2));
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
        UUID nanRun = store.create(new RunRequest(TEAM, USER, null, null), LocalDate.of(2026, 9, 6), T0.plusMinutes(4));
        store.finish(nanRun, "seasonal_naive", Double.NaN, "{}", List.of(), List.of(), "{}", T0.plusMinutes(5));
        assertNull(store.find(nanRun).orElseThrow().championMase(), "NaN is stored as null");

        UUID bigRun = store.create(new RunRequest(TEAM, USER, null, null), LocalDate.of(2026, 9, 6), T0.plusMinutes(6));
        List<MemberWindowForecast> manyRows = manyRows(450);
        store.finish(bigRun, "xgboost", 0.5, "{}", manyRows, List.of(), "{}", T0.plusMinutes(7));
        assertEquals(manyRows, store.memberWindows(bigRun), "450 rows survive a batched insert, in order");
    }

    /** One window of each of {@code count} members, spanning more than one batch of {@link JdbcRunStore#BATCH}, in the store's own order. */
    static List<MemberWindowForecast> manyRows(int count) {
        List<MemberWindowForecast> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID user = UUID.nameUUIDFromBytes(("user-" + i).getBytes(StandardCharsets.UTF_8));
            out.add(new MemberWindowForecast(user, 1, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 11), 1, 2, 0, 3, 2, 5, 40, 0, 5, 0));
        }
        out.sort((a, b) -> a.userId().toString().compareTo(b.userId().toString()));
        return out;
    }

    /** Spec 2026-09-10, section 7: a later run overwrites the days ahead of it and leaves the days only the earlier run covered. */
    void aLaterRunOverwritesOnlyTheDaysItCovers(DataSource ds) {
        JdbcRunStore store = new JdbcRunStore(ds, Dialect.of(ds));
        LocalDate wednesday = LocalDate.of(2026, 9, 2);
        LocalDate monday = LocalDate.of(2026, 9, 7);
        UUID first = store.create(new RunRequest(TEAM, USER, null, null), wednesday, T0);
        store.finish(first, "xgboost", 0.8, "{}", List.of(), days(wednesday, 1), "{}", T0.plusMinutes(1));
        UUID second = store.create(new RunRequest(TEAM, USER, null, null), monday, T0.plusDays(5));
        store.finish(second, "xgboost", 0.8, "{}", List.of(), days(monday, 2), "{}", T0.plusDays(5).plusMinutes(1));
        List<CurrentDayForecast> current = store.currentDays(TEAM, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertEquals(13, current.size(), "three days only the first run covered, ten the second overwrote");
        for (CurrentDayForecast c : current) {
            boolean beforeSecond = c.day().isBefore(LocalDate.of(2026, 9, 8));
            assertEquals(beforeSecond ? first : second, c.runId(), c.day().toString());
            assertEquals(beforeSecond ? 1.0 : 2.0, c.demandHrs(), 1e-9, c.day().toString());
            assertEquals(beforeSecond ? T0.plusMinutes(1) : T0.plusDays(5).plusMinutes(1), c.forecastAt());
        }
        assertEquals(LocalDate.of(2026, 9, 3), current.get(0).day());
        assertEquals(LocalDate.of(2026, 9, 21), current.get(current.size() - 1).day());
        assertTrue(store.currentDays(UUID.randomUUID(), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).isEmpty());
        assertEquals(2, store.currentDays(TEAM, LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 4)).size(), "the range is inclusive");
    }

    @Test
    void sqliteOverwrite() {
        aLaterRunOverwritesOnlyTheDaysItCovers(sqlite());
    }

    @Test
    void postgresOverwrite() {
        DataSource ds = DatabaseTestSupport.postgresOrSkip();
        WorkloadHubSchema.createPostgresql(ds);
        ForecastMigrations.run(ds);
        aLaterRunOverwritesOnlyTheDaysItCovers(ds);
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
        UUID a = store.create(new RunRequest(TEAM, USER, null, null), LocalDate.of(2026, 9, 6), T0);
        UUID b = store.create(new RunRequest(TEAM, USER, null, null), LocalDate.of(2026, 9, 6), T0);
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
