package com.workloadhub.forecast.store;

import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.MemberDayForecast;
import com.workloadhub.forecast.api.MemberWindowForecast;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunStatus;
import com.workloadhub.forecast.api.RunSummary;
import com.workloadhub.forecast.eval.RunDayForecast;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The module's own tables: one row per run, the window and day tables, the current forecast and the facts. */
public final class JdbcRunStore {

    public static final UUID NIL = new UUID(0L, 0L);
    /** The error a run left behind by a restart carries (design 2026-09-11, section 4.2). */
    public static final String INTERRUPTED = "interrupted by a restart";
    static final int ERROR_MAX = 500;

    private static final String RUN_COLUMNS = "id, team_id, requested_by, as_of, status, mae, error, created_at, finished_at";
    private static final String WINDOW_COLUMNS = "user_id, window_index, window_start, window_end, demand_hrs, low_hrs, high_hrs,"
            + " capacity_hrs, overload_hrs, working_days, absence_hrs, backlog_excess_hrs, due_excess_hrs";
    private static final String DAY_COLUMNS = "user_id, day, window_index, demand_hrs, capacity_hrs, overload_hrs, working_day";

    private static final RowMapper<RunSummary> SUMMARY = (rs, i) -> new RunSummary(rs.getObject("id", UUID.class),
            rs.getObject("team_id", UUID.class), rs.getObject("requested_by", UUID.class), rs.getObject("as_of", LocalDate.class),
            RunStatus.valueOf(rs.getString("status")), rs.getObject("mae", Double.class), rs.getString("error"),
            rs.getObject("created_at", LocalDateTime.class), rs.getObject("finished_at", LocalDateTime.class));
    private static final RowMapper<MemberWindowForecast> WINDOW = (rs, i) -> new MemberWindowForecast(rs.getObject("user_id", UUID.class),
            rs.getInt("window_index"), rs.getObject("window_start", LocalDate.class), rs.getObject("window_end", LocalDate.class),
            rs.getDouble("demand_hrs"), rs.getDouble("low_hrs"), rs.getDouble("high_hrs"), rs.getDouble("capacity_hrs"),
            rs.getDouble("overload_hrs"), rs.getInt("working_days"), rs.getDouble("absence_hrs"), rs.getDouble("backlog_excess_hrs"),
            rs.getDouble("due_excess_hrs"));
    private static final RowMapper<MemberDayForecast> DAY = (rs, i) -> new MemberDayForecast(rs.getObject("user_id", UUID.class),
            rs.getObject("day", LocalDate.class), rs.getInt("window_index"), rs.getDouble("demand_hrs"), rs.getDouble("capacity_hrs"),
            rs.getDouble("overload_hrs"), rs.getBoolean("working_day"));

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate tx;

    public JdbcRunStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public UUID create(RunRequest request, LocalDate asOf, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO forecast_runs (id, team_id, requested_by, as_of, status, created_at) VALUES (?, ?, ?, ?, ?, ?)")
                .param(id).param(request.teamId()).param(request.requestedBy() == null ? NIL : request.requestedBy())
                .param(asOf).param(RunStatus.QUEUED.name()).param(JdbcValues.micros(createdAt))
                .update();
        return id;
    }

    public void markRunning(UUID runId) {
        jdbc.sql("UPDATE forecast_runs SET status = ? WHERE id = ?").param(RunStatus.RUNNING.name()).param(runId).update();
    }

    public void fail(UUID runId, String error, LocalDateTime finishedAt) {
        String line = error == null ? "" : error.strip().lines().findFirst().orElse("");
        if (line.length() > ERROR_MAX) {
            line = line.substring(0, ERROR_MAX);
        }
        jdbc.sql("UPDATE forecast_runs SET status = ?, error = ?, finished_at = ? WHERE id = ?")
                .param(RunStatus.FAILED.name()).param(line).param(JdbcValues.micros(finishedAt)).param(runId).update();
    }

    /** After a restart nothing can still be running a QUEUED or RUNNING row (design 2026-09-11, section 4.2): fail them all, return how many. */
    public int failInterrupted(LocalDateTime now) {
        return jdbc.sql("UPDATE forecast_runs SET status = ?, error = ?, finished_at = ? WHERE status IN (?, ?)")
                .param(RunStatus.FAILED.name()).param(INTERRUPTED).param(JdbcValues.micros(now))
                .param(RunStatus.QUEUED.name()).param(RunStatus.RUNNING.name()).update();
    }

    /** {@code mae} is {@code null}, not NaN, when the run had no scored backtest origin. */
    public void finish(UUID runId, Double mae, String backtestJson, List<MemberWindowForecast> windows, List<MemberDayForecast> days, String factsJson,
            LocalDateTime finishedAt) {
        LocalDateTime at = JdbcValues.micros(finishedAt);
        tx.executeWithoutResult(status -> {
            jdbc.sql("UPDATE forecast_runs SET status = ?, mae = ?, backtest_json = ?, finished_at = ? WHERE id = ?")
                    .param(RunStatus.DONE.name()).param(mae == null || mae.isNaN() ? null : mae).param(backtestJson)
                    .param(at).param(runId).update();
            jdbcTemplate.batchUpdate("INSERT INTO forecast_member_windows (run_id, " + WINDOW_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    windows.stream().map(r -> new Object[] {runId, r.userId(), r.windowIndex(), r.windowStart(), r.windowEnd(), r.demandHrs(),
                            r.lowHrs(), r.highHrs(), r.capacityHrs(), r.overloadHrs(), r.workingDays(), r.absenceHrs(), r.backlogExcessHrs(),
                            r.dueExcessHrs()}).toList());
            jdbcTemplate.batchUpdate("INSERT INTO forecast_member_days (run_id, " + DAY_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    days.stream().map(r -> new Object[] {runId, r.userId(), r.day(), r.windowIndex(), r.demandHrs(), r.capacityHrs(),
                            r.overloadHrs(), r.workingDay()}).toList());
            UUID teamId = jdbc.sql("SELECT team_id FROM forecast_runs WHERE id = ?").param(runId).query(UUID.class).single();
            // Every day of the run overwrites the team's current forecast for that member and day (all of them lie after the run day).
            jdbcTemplate.batchUpdate("INSERT INTO forecast_current_days (team_id, user_id, day, run_id, demand_hrs, capacity_hrs,"
                    + " overload_hrs, forecast_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (team_id, user_id, day) DO UPDATE SET"
                    + " run_id = excluded.run_id, demand_hrs = excluded.demand_hrs, capacity_hrs = excluded.capacity_hrs,"
                    + " overload_hrs = excluded.overload_hrs, forecast_at = excluded.forecast_at",
                    days.stream().map(r -> new Object[] {teamId, r.userId(), r.day(), runId, r.demandHrs(), r.capacityHrs(), r.overloadHrs(), at})
                            .toList());
            jdbc.sql("INSERT INTO forecast_facts (run_id, facts_json, created_at) VALUES (?, ?, ?)")
                    .param(runId).param(factsJson).param(at).update();
        });
    }

    public Optional<RunSummary> find(UUID runId) {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM forecast_runs WHERE id = ?").param(runId).query(SUMMARY).optional();
    }

    public List<RunSummary> list(UUID teamId, int limit) {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM forecast_runs WHERE team_id = ? ORDER BY created_at DESC, id DESC LIMIT " + Math.max(1, limit))
                .param(teamId).query(SUMMARY).list();
    }

    /** PostgreSQL orders uuid by its bytes, which is the order of its canonical text and of {@code Ids.UUID_ORDER}: no re-sort here. */
    public List<MemberWindowForecast> memberWindows(UUID runId) {
        return jdbc.sql("SELECT " + WINDOW_COLUMNS + " FROM forecast_member_windows WHERE run_id = ? ORDER BY user_id, window_index")
                .param(runId).query(WINDOW).list();
    }

    public List<MemberDayForecast> memberDays(UUID runId) {
        return jdbc.sql("SELECT " + DAY_COLUMNS + " FROM forecast_member_days WHERE run_id = ? ORDER BY user_id, day")
                .param(runId).query(DAY).list();
    }

    /** The team's current forecast between two days inclusive, by member then day. */
    public List<CurrentDayForecast> currentDays(UUID teamId, LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT team_id, user_id, day, run_id, demand_hrs, capacity_hrs, overload_hrs, forecast_at FROM forecast_current_days"
                + " WHERE team_id = ? AND day >= ? AND day <= ? ORDER BY user_id, day")
                .param(teamId).param(from).param(to)
                .query((rs, i) -> new CurrentDayForecast(rs.getObject("team_id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("day", LocalDate.class), rs.getObject("run_id", UUID.class), rs.getDouble("demand_hrs"),
                        rs.getDouble("capacity_hrs"), rs.getDouble("overload_hrs"), rs.getObject("forecast_at", LocalDateTime.class)))
                .list();
    }

    /** Every day row of the team's DONE runs between two days inclusive, with the run day it was made on; by run, member, day. */
    public List<RunDayForecast> runDays(UUID teamId, LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT d.run_id, r.as_of, d.user_id, d.day, d.window_index, d.demand_hrs, d.capacity_hrs, d.overload_hrs, d.working_day"
                + " FROM forecast_member_days d JOIN forecast_runs r ON r.id = d.run_id"
                + " WHERE r.team_id = ? AND r.status = ? AND d.day >= ? AND d.day <= ? ORDER BY d.run_id, d.user_id, d.day")
                .param(teamId).param(RunStatus.DONE.name()).param(from).param(to)
                .query((rs, i) -> new RunDayForecast(rs.getObject("run_id", UUID.class), rs.getObject("as_of", LocalDate.class), DAY.mapRow(rs, i)))
                .list();
    }

    public Optional<String> facts(UUID runId) {
        return jdbc.sql("SELECT facts_json FROM forecast_facts WHERE run_id = ?").param(runId).query(String.class).optional();
    }

    public Optional<String> backtestJson(UUID runId) {
        return jdbc.sql("SELECT backtest_json FROM forecast_runs WHERE id = ?").param(runId).query(String.class).optional()
                .filter(s -> s != null);
    }
}
