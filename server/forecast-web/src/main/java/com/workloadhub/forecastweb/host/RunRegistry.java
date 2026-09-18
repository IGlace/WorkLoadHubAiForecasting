package com.workloadhub.forecastweb.host;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The host's own record of the runs it started, {@code (runId, teamId, requestedBy)}, in its own table
 * (design 2026-09-11, section 3.3): {@code getRun} answers only for DONE runs and {@code listRuns} needs the
 * team, so after a restart this row is what lets the host authorise a poll of an interrupted run. The
 * sample facade in the module's tests keeps an in-memory map instead; a real host persists it. The columns
 * are text in both dialects, so the DDL and the placeholders are the same on SQLite and PostgreSQL.
 */
public final class RunRegistry {

    public static final String TABLE = "forecast_web_runs";

    private final JdbcClient jdbc;

    public RunRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
        jdbc.sql("CREATE TABLE IF NOT EXISTS " + TABLE + " (run_id TEXT PRIMARY KEY, team_id TEXT NOT NULL, requested_by TEXT NOT NULL,"
                + " started_at TEXT NOT NULL)").update();
    }

    public void register(UUID runId, UUID teamId, UUID requestedBy, LocalDateTime startedAt) {
        jdbc.sql("INSERT INTO " + TABLE + " (run_id, team_id, requested_by, started_at) VALUES (?, ?, ?, ?)")
                .param(runId.toString()).param(teamId.toString()).param(requestedBy.toString()).param(startedAt.toString()).update();
    }

    public Optional<UUID> teamOf(UUID runId) {
        return jdbc.sql("SELECT team_id FROM " + TABLE + " WHERE run_id = ?").param(runId.toString()).query().listOfRows().stream().findFirst()
                .map(r -> UUID.fromString(String.valueOf(r.get("team_id"))));
    }

    /**
     * The run this user started most recently, whatever its state: the one-at-a-time rule looks at it.
     * {@code started_at} is written by the caller from the real clock, not from the module's {@code Clock}
     * bean, so that a demo clock an admin moves backwards cannot reorder the rows.
     */
    public Optional<UUID> latestRunOf(UUID userId) {
        return jdbc.sql("SELECT run_id FROM " + TABLE + " WHERE requested_by = ? ORDER BY started_at DESC, run_id DESC")
                .param(userId.toString()).query().listOfRows().stream().findFirst().map(r -> UUID.fromString(String.valueOf(r.get("run_id"))));
    }
}
