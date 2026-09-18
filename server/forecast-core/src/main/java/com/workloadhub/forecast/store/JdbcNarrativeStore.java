package com.workloadhub.forecast.store;

import com.workloadhub.forecast.ai.NarrationOutcome;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.NarrativeStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** One row per narration in forecast_narratives, whatever its status. */
public final class JdbcNarrativeStore {

    private static final String COLUMNS = "id, run_id, language, status, model, narrative_json, raw_text, verification_json, usage_json, error,"
            + " attempts, tool_calls, created_at";
    private static final RowMapper<NarrativeResult> ROW = (rs, i) -> new NarrativeResult(rs.getObject("id", UUID.class),
            rs.getObject("run_id", UUID.class), rs.getString("language"), NarrativeStatus.valueOf(rs.getString("status")), rs.getString("model"),
            rs.getString("narrative_json"), rs.getString("raw_text"), rs.getString("verification_json"), rs.getString("usage_json"),
            rs.getString("error"), rs.getInt("attempts"), rs.getInt("tool_calls"), rs.getObject("created_at", LocalDateTime.class));

    private final JdbcClient jdbc;

    public JdbcNarrativeStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public NarrativeResult save(UUID runId, String language, NarrationOutcome outcome, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO forecast_narratives (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .param(id).param(runId).param(language).param(outcome.status().name()).param(outcome.model())
                .param(outcome.narrativeJson()).param(outcome.rawText()).param(outcome.verificationJson()).param(outcome.usageJson())
                .param(outcome.error()).param(outcome.attempts()).param(outcome.toolCalls().size()).param(JdbcValues.micros(createdAt))
                .update();
        return find(id).orElseThrow();
    }

    public Optional<NarrativeResult> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM forecast_narratives WHERE id = ?").param(id).query(ROW).optional();
    }

    /** The newest narration of that language for the run, whatever its status. */
    public Optional<NarrativeResult> latest(UUID runId, String language) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM forecast_narratives WHERE run_id = ? AND language = ? ORDER BY created_at DESC, id DESC LIMIT 1")
                .param(runId).param(language).query(ROW).optional();
    }
}
