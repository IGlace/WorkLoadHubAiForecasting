package com.workloadhub.forecast.store;

import com.workloadhub.forecast.ai.NarrationOutcome;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.NarrativeStatus;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** One row per narration in forecast_narratives, whatever its status, on SQLite or PostgreSQL. */
public final class JdbcNarrativeStore {

    private static final String COLUMNS = "id, run_id, language, status, model, narrative_json, raw_text, verification_json, usage_json, error,"
            + " attempts, tool_calls, created_at";

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public JdbcNarrativeStore(DataSource dataSource, Dialect dialect) {
        this.jdbc = JdbcClient.create(dataSource);
        this.dialect = dialect;
    }

    private String ph(String type) {
        return dialect.placeholder(type);
    }

    private static String ts(LocalDateTime t) {
        return t == null ? null : t.truncatedTo(ChronoUnit.MICROS).toString();
    }

    public NarrativeResult save(UUID runId, String language, NarrationOutcome outcome, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO forecast_narratives (" + COLUMNS + ") VALUES (" + ph("uuid") + ", " + ph("uuid") + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + ph("timestamp") + ")")
                .param(id.toString()).param(runId.toString()).param(language).param(outcome.status().name()).param(outcome.model())
                .param(outcome.narrativeJson()).param(outcome.rawText()).param(outcome.verificationJson()).param(outcome.usageJson())
                .param(outcome.error()).param(outcome.attempts()).param(outcome.toolCalls().size()).param(ts(createdAt))
                .update();
        return find(id).orElseThrow();
    }

    public Optional<NarrativeResult> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM forecast_narratives WHERE id = " + ph("uuid")).param(id.toString())
                .query().listOfRows().stream().findFirst().map(JdbcNarrativeStore::row);
    }

    /** The newest narration of that language for the run, whatever its status. */
    public Optional<NarrativeResult> latest(UUID runId, String language) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM forecast_narratives WHERE run_id = " + ph("uuid")
                + " AND language = ? ORDER BY created_at DESC, id DESC LIMIT 1")
                .param(runId.toString()).param(language).query().listOfRows().stream().findFirst().map(JdbcNarrativeStore::row);
    }

    private static NarrativeResult row(Map<String, Object> r) {
        return new NarrativeResult(UUID.fromString(str(r, "id")), UUID.fromString(str(r, "run_id")), str(r, "language"),
                NarrativeStatus.valueOf(str(r, "status")), str(r, "model"), str(r, "narrative_json"), str(r, "raw_text"), str(r, "verification_json"),
                str(r, "usage_json"), str(r, "error"), ((Number) r.get("attempts")).intValue(), ((Number) r.get("tool_calls")).intValue(),
                dateTime(r.get("created_at")));
    }

    private static String str(Map<String, Object> r, String col) {
        Object v = r.get(col);
        return v == null ? null : v.toString();
    }

    private static LocalDateTime dateTime(Object v) {
        if (v instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime();
        }
        return LocalDateTime.parse(v.toString().replace(' ', 'T'));
    }
}
