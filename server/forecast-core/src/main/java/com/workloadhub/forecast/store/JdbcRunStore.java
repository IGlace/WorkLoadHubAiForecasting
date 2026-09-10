package com.workloadhub.forecast.store;

import com.workloadhub.forecast.api.MemberWeekForecast;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunStatus;
import com.workloadhub.forecast.api.RunSummary;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The module's own tables: one row per run, the member-week table and the facts, on SQLite or PostgreSQL. */
public final class JdbcRunStore {

    public static final UUID NIL = new UUID(0L, 0L);
    static final int BATCH = 200;
    static final int ERROR_MAX = 500;

    private final JdbcClient jdbc;
    private final Dialect dialect;
    private final TransactionTemplate tx;

    public JdbcRunStore(DataSource dataSource, Dialect dialect) {
        this.jdbc = JdbcClient.create(dataSource);
        this.dialect = dialect;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private String ph(String type) {
        return dialect.placeholder(type);
    }

    private static String ts(LocalDateTime t) {
        return t == null ? null : t.withNano(0).toString();
    }

    public UUID create(RunRequest request, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO forecast_runs (id, team_id, requested_by, as_of, status, forced_model, created_at) VALUES ("
                + ph("uuid") + ", " + ph("uuid") + ", " + ph("uuid") + ", " + ph("date") + ", ?, ?, " + ph("timestamp") + ")")
                .param(id.toString()).param(request.teamId().toString())
                .param((request.requestedBy() == null ? NIL : request.requestedBy()).toString())
                .param(request.asOf().toString()).param(RunStatus.QUEUED.name()).param(request.forcedModel()).param(ts(createdAt))
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

    public void finish(UUID runId, String champion, double championMase, String backtestJson, List<MemberWeekForecast> rows, String factsJson,
            LocalDateTime finishedAt) {
        tx.executeWithoutResult(status -> {
            jdbc.sql("UPDATE forecast_runs SET status = ?, champion_model = ?, champion_mase = ?, backtest_json = ?, finished_at = " + ph("timestamp")
                    + " WHERE id = " + ph("uuid"))
                    .param(RunStatus.DONE.name()).param(champion).param(Double.isNaN(championMase) ? null : championMase).param(backtestJson)
                    .param(ts(finishedAt)).param(runId.toString()).update();
            String insert = "INSERT INTO forecast_member_weeks (run_id, user_id, week_start, open_hrs, new_hrs, planned_hrs, low_hrs, high_hrs,"
                    + " capacity_hrs, overload_hrs, working_days, absence_hrs) VALUES (" + ph("uuid") + ", " + ph("uuid") + ", " + ph("date")
                    + ", ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            for (MemberWeekForecast r : rows) {
                jdbc.sql(insert).param(runId.toString()).param(r.userId().toString()).param(r.weekStart().toString())
                        .param(r.openHrs()).param(r.newHrs()).param(r.plannedHrs()).param(r.lowHrs()).param(r.highHrs())
                        .param(r.capacityHrs()).param(r.overloadHrs()).param(r.workingDays()).param(r.absenceHrs()).update();
            }
            jdbc.sql("INSERT INTO forecast_facts (run_id, facts_json, created_at) VALUES (" + ph("uuid") + ", ?, " + ph("timestamp") + ")")
                    .param(runId.toString()).param(factsJson).param(ts(finishedAt)).update();
        });
    }

    public Optional<RunSummary> find(UUID runId) {
        return jdbc.sql("SELECT id, team_id, requested_by, as_of, status, forced_model, champion_model, champion_mase, error, created_at, finished_at"
                + " FROM forecast_runs WHERE id = " + ph("uuid")).param(runId.toString()).query().listOfRows().stream().findFirst().map(JdbcRunStore::summary);
    }

    public List<RunSummary> list(UUID teamId, int limit) {
        return jdbc.sql("SELECT id, team_id, requested_by, as_of, status, forced_model, champion_model, champion_mase, error, created_at, finished_at"
                + " FROM forecast_runs WHERE team_id = " + ph("uuid") + " ORDER BY created_at DESC, id LIMIT " + Math.max(1, limit))
                .param(teamId.toString()).query().listOfRows().stream().map(JdbcRunStore::summary).toList();
    }

    public List<MemberWeekForecast> memberWeeks(UUID runId) {
        List<MemberWeekForecast> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("SELECT user_id, week_start, open_hrs, new_hrs, planned_hrs, low_hrs, high_hrs, capacity_hrs, overload_hrs,"
                + " working_days, absence_hrs FROM forecast_member_weeks WHERE run_id = " + ph("uuid") + " ORDER BY user_id, week_start")
                .param(runId.toString()).query().listOfRows()) {
            double open = num(r, "open_hrs");
            double fresh = num(r, "new_hrs");
            double planned = num(r, "planned_hrs");
            out.add(new MemberWeekForecast(UUID.fromString(str(r, "user_id")), date(r, "week_start"), open, fresh, planned,
                    Math.round((open + fresh + planned) * 100.0) / 100.0, num(r, "low_hrs"), num(r, "high_hrs"), num(r, "capacity_hrs"),
                    num(r, "overload_hrs"), (int) num(r, "working_days"), num(r, "absence_hrs")));
        }
        out.sort((a, b) -> a.userId().toString().equals(b.userId().toString()) ? a.weekStart().compareTo(b.weekStart())
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
