package com.workloadhub.forecast.store;

import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.MemberDayForecast;
import com.workloadhub.forecast.api.MemberWindowForecast;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunStatus;
import com.workloadhub.forecast.api.RunSummary;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The module's own tables: one row per run, the window and day tables, the current forecast and the facts, on SQLite or PostgreSQL. */
public final class JdbcRunStore {

    public static final UUID NIL = new UUID(0L, 0L);
    static final int BATCH = 200;
    static final int ERROR_MAX = 500;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final Dialect dialect;
    private final TransactionTemplate tx;

    public JdbcRunStore(DataSource dataSource, Dialect dialect) {
        this.jdbc = JdbcClient.create(dataSource);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.dialect = dialect;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private String ph(String type) {
        return dialect.placeholder(type);
    }

    private static String ts(LocalDateTime t) {
        return t == null ? null : t.truncatedTo(ChronoUnit.MICROS).toString();
    }

    public UUID create(RunRequest request, LocalDate asOf, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO forecast_runs (id, team_id, requested_by, as_of, status, forced_model, created_at) VALUES ("
                + ph("uuid") + ", " + ph("uuid") + ", " + ph("uuid") + ", " + ph("date") + ", ?, ?, " + ph("timestamp") + ")")
                .param(id.toString()).param(request.teamId().toString())
                .param((request.requestedBy() == null ? NIL : request.requestedBy()).toString())
                .param(asOf.toString()).param(RunStatus.QUEUED.name()).param(request.forcedModel()).param(ts(createdAt))
                .update();
        return id;
    }

    public void markRunning(UUID runId) {
        jdbc.sql("UPDATE forecast_runs SET status = ? WHERE id = " + ph("uuid")).param(RunStatus.RUNNING.name()).param(runId.toString()).update();
    }

    public void fail(UUID runId, String error, LocalDateTime finishedAt) {
        String line = error == null ? "" : error.strip().lines().findFirst().orElse("");
        if (line.length() > ERROR_MAX) {
            line = line.substring(0, ERROR_MAX);
        }
        jdbc.sql("UPDATE forecast_runs SET status = ?, error = ?, finished_at = " + ph("timestamp") + " WHERE id = " + ph("uuid"))
                .param(RunStatus.FAILED.name()).param(line).param(ts(finishedAt)).param(runId.toString()).update();
    }

    public void finish(UUID runId, String champion, double championMase, String backtestJson, List<MemberWindowForecast> windows,
            List<MemberDayForecast> days, String factsJson, LocalDateTime finishedAt) {
        tx.executeWithoutResult(status -> {
            jdbc.sql("UPDATE forecast_runs SET status = ?, champion_model = ?, champion_mase = ?, backtest_json = ?, finished_at = " + ph("timestamp")
                    + " WHERE id = " + ph("uuid"))
                    .param(RunStatus.DONE.name()).param(champion).param(Double.isNaN(championMase) ? null : championMase).param(backtestJson)
                    .param(ts(finishedAt)).param(runId.toString()).update();
            insertWindows(runId, windows);
            insertDays(runId, days);
            String teamId = jdbc.sql("SELECT team_id FROM forecast_runs WHERE id = " + ph("uuid")).param(runId.toString())
                    .query().listOfRows().get(0).get("team_id").toString();
            upsertCurrentDays(teamId, runId, days, finishedAt);
            jdbc.sql("INSERT INTO forecast_facts (run_id, facts_json, created_at) VALUES (" + ph("uuid") + ", ?, " + ph("timestamp") + ")")
                    .param(runId.toString()).param(factsJson).param(ts(finishedAt)).update();
        });
    }

    /** Batches of {@link #BATCH} rows, same typed placeholders and binding order as a single-row insert. */
    private void insertWindows(UUID runId, List<MemberWindowForecast> rows) {
        String insert = "INSERT INTO forecast_member_windows (run_id, user_id, window_index, window_start, window_end, open_hrs, new_hrs, planned_hrs,"
                + " demand_hrs, low_hrs, high_hrs, capacity_hrs, overload_hrs, working_days, absence_hrs) VALUES (" + ph("uuid") + ", " + ph("uuid")
                + ", ?, " + ph("date") + ", " + ph("date") + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        for (int start = 0; start < rows.size(); start += BATCH) {
            List<MemberWindowForecast> chunk = rows.subList(start, Math.min(start + BATCH, rows.size()));
            List<Object[]> args = new ArrayList<>(chunk.size());
            for (MemberWindowForecast r : chunk) {
                args.add(new Object[] {runId.toString(), r.userId().toString(), r.windowIndex(), r.windowStart().toString(), r.windowEnd().toString(),
                        r.openHrs(), r.newHrs(), r.plannedHrs(), r.demandHrs(), r.lowHrs(), r.highHrs(), r.capacityHrs(), r.overloadHrs(),
                        r.workingDays(), r.absenceHrs()});
            }
            jdbcTemplate.batchUpdate(insert, args);
        }
    }

    private void insertDays(UUID runId, List<MemberDayForecast> rows) {
        String insert = "INSERT INTO forecast_member_days (run_id, user_id, day, window_index, open_hrs, new_hrs, planned_hrs, demand_hrs, capacity_hrs,"
                + " overload_hrs, working_day) VALUES (" + ph("uuid") + ", " + ph("uuid") + ", " + ph("date") + ", ?, ?, ?, ?, ?, ?, ?, ?)";
        for (int start = 0; start < rows.size(); start += BATCH) {
            List<MemberDayForecast> chunk = rows.subList(start, Math.min(start + BATCH, rows.size()));
            List<Object[]> args = new ArrayList<>(chunk.size());
            for (MemberDayForecast r : chunk) {
                args.add(new Object[] {runId.toString(), r.userId().toString(), r.day().toString(), r.windowIndex(), r.openHrs(), r.newHrs(),
                        r.plannedHrs(), r.demandHrs(), r.capacityHrs(), r.overloadHrs(), dialect.bool(r.workingDay())});
            }
            jdbcTemplate.batchUpdate(insert, args);
        }
    }

    /** Every day of the run overwrites the team's current forecast for that member and day (all of them lie after the run day). */
    private void upsertCurrentDays(String teamId, UUID runId, List<MemberDayForecast> rows, LocalDateTime forecastAt) {
        String upsert = "INSERT INTO forecast_current_days (team_id, user_id, day, run_id, open_hrs, new_hrs, planned_hrs, demand_hrs, capacity_hrs,"
                + " overload_hrs, forecast_at) VALUES (" + ph("uuid") + ", " + ph("uuid") + ", " + ph("date") + ", " + ph("uuid") + ", ?, ?, ?, ?, ?, ?, "
                + ph("timestamp") + ") ON CONFLICT (team_id, user_id, day) DO UPDATE SET run_id = excluded.run_id, open_hrs = excluded.open_hrs,"
                + " new_hrs = excluded.new_hrs, planned_hrs = excluded.planned_hrs, demand_hrs = excluded.demand_hrs, capacity_hrs = excluded.capacity_hrs,"
                + " overload_hrs = excluded.overload_hrs, forecast_at = excluded.forecast_at";
        for (int start = 0; start < rows.size(); start += BATCH) {
            List<MemberDayForecast> chunk = rows.subList(start, Math.min(start + BATCH, rows.size()));
            List<Object[]> args = new ArrayList<>(chunk.size());
            for (MemberDayForecast r : chunk) {
                args.add(new Object[] {teamId, r.userId().toString(), r.day().toString(), runId.toString(), r.openHrs(), r.newHrs(), r.plannedHrs(),
                        r.demandHrs(), r.capacityHrs(), r.overloadHrs(), ts(forecastAt)});
            }
            jdbcTemplate.batchUpdate(upsert, args);
        }
    }

    public Optional<RunSummary> find(UUID runId) {
        return jdbc.sql("SELECT id, team_id, requested_by, as_of, status, forced_model, champion_model, champion_mase, error, created_at, finished_at"
                + " FROM forecast_runs WHERE id = " + ph("uuid")).param(runId.toString()).query().listOfRows().stream().findFirst().map(JdbcRunStore::summary);
    }

    public List<RunSummary> list(UUID teamId, int limit) {
        return jdbc.sql("SELECT id, team_id, requested_by, as_of, status, forced_model, champion_model, champion_mase, error, created_at, finished_at"
                + " FROM forecast_runs WHERE team_id = " + ph("uuid") + " ORDER BY created_at DESC, id DESC LIMIT " + Math.max(1, limit))
                .param(teamId.toString()).query().listOfRows().stream().map(JdbcRunStore::summary).toList();
    }

    public List<MemberWindowForecast> memberWindows(UUID runId) {
        List<MemberWindowForecast> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("SELECT user_id, window_index, window_start, window_end, open_hrs, new_hrs, planned_hrs, demand_hrs, low_hrs,"
                + " high_hrs, capacity_hrs, overload_hrs, working_days, absence_hrs FROM forecast_member_windows WHERE run_id = " + ph("uuid")
                + " ORDER BY user_id, window_index").param(runId.toString()).query().listOfRows()) {
            out.add(new MemberWindowForecast(UUID.fromString(str(r, "user_id")), (int) num(r, "window_index"), date(r, "window_start"), date(r, "window_end"),
                    num(r, "open_hrs"), num(r, "new_hrs"), num(r, "planned_hrs"), num(r, "demand_hrs"), num(r, "low_hrs"), num(r, "high_hrs"),
                    num(r, "capacity_hrs"), num(r, "overload_hrs"), (int) num(r, "working_days"), num(r, "absence_hrs")));
        }
        out.sort((a, b) -> a.userId().toString().equals(b.userId().toString()) ? Integer.compare(a.windowIndex(), b.windowIndex())
                : a.userId().toString().compareTo(b.userId().toString()));
        return out;
    }

    public List<MemberDayForecast> memberDays(UUID runId) {
        List<MemberDayForecast> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("SELECT user_id, day, window_index, open_hrs, new_hrs, planned_hrs, demand_hrs, capacity_hrs, overload_hrs,"
                + " working_day FROM forecast_member_days WHERE run_id = " + ph("uuid") + " ORDER BY user_id, day").param(runId.toString()).query().listOfRows()) {
            out.add(new MemberDayForecast(UUID.fromString(str(r, "user_id")), date(r, "day"), (int) num(r, "window_index"), num(r, "open_hrs"),
                    num(r, "new_hrs"), num(r, "planned_hrs"), num(r, "demand_hrs"), num(r, "capacity_hrs"), num(r, "overload_hrs"),
                    dialect.asBoolean(r.get("working_day"))));
        }
        out.sort((a, b) -> a.userId().toString().equals(b.userId().toString()) ? a.day().compareTo(b.day())
                : a.userId().toString().compareTo(b.userId().toString()));
        return out;
    }

    /** The team's current forecast between two days inclusive, by member then day. */
    public List<CurrentDayForecast> currentDays(UUID teamId, LocalDate from, LocalDate to) {
        List<CurrentDayForecast> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("SELECT team_id, user_id, day, run_id, open_hrs, new_hrs, planned_hrs, demand_hrs, capacity_hrs, overload_hrs,"
                + " forecast_at FROM forecast_current_days WHERE team_id = " + ph("uuid") + " AND day >= " + ph("date") + " AND day <= " + ph("date")
                + " ORDER BY user_id, day").param(teamId.toString()).param(from.toString()).param(to.toString()).query().listOfRows()) {
            out.add(new CurrentDayForecast(UUID.fromString(str(r, "team_id")), UUID.fromString(str(r, "user_id")), date(r, "day"),
                    UUID.fromString(str(r, "run_id")), num(r, "open_hrs"), num(r, "new_hrs"), num(r, "planned_hrs"), num(r, "demand_hrs"),
                    num(r, "capacity_hrs"), num(r, "overload_hrs"), dateTime(r, "forecast_at")));
        }
        out.sort((a, b) -> a.userId().toString().equals(b.userId().toString()) ? a.day().compareTo(b.day())
                : a.userId().toString().compareTo(b.userId().toString()));
        return out;
    }

    public Optional<String> facts(UUID runId) {
        return jdbc.sql("SELECT facts_json FROM forecast_facts WHERE run_id = " + ph("uuid")).param(runId.toString())
                .query().listOfRows().stream().findFirst().map(r -> str(r, "facts_json"));
    }

    public Optional<String> backtestJson(UUID runId) {
        return jdbc.sql("SELECT backtest_json FROM forecast_runs WHERE id = " + ph("uuid")).param(runId.toString())
                .query().listOfRows().stream().findFirst().map(r -> str(r, "backtest_json"));
    }

    private static RunSummary summary(Map<String, Object> r) {
        Object mase = r.get("champion_mase");
        return new RunSummary(UUID.fromString(str(r, "id")), UUID.fromString(str(r, "team_id")), UUID.fromString(str(r, "requested_by")),
                date(r, "as_of"), RunStatus.valueOf(str(r, "status")), str(r, "forced_model"), str(r, "champion_model"),
                mase == null ? null : ((Number) mase).doubleValue(), str(r, "error"), dateTime(r, "created_at"), dateTime(r, "finished_at"));
    }

    private static String str(Map<String, Object> r, String col) {
        Object v = r.get(col);
        return v == null ? null : v.toString();
    }

    private static double num(Map<String, Object> r, String col) {
        return ((Number) r.get(col)).doubleValue();
    }

    private static LocalDate date(Map<String, Object> r, String col) {
        Object v = r.get(col);
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        String s = v.toString();
        return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }

    private static LocalDateTime dateTime(Map<String, Object> r, String col) {
        Object v = r.get(col);
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime();
        }
        return LocalDateTime.parse(v.toString().replace(' ', 'T'));
    }
}
