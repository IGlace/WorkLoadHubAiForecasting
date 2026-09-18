package com.workloadhub.forecast.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.tools.export.ExportEnvelope;
import com.workloadhub.forecast.tools.export.SqlExportWriter;
import com.workloadhub.forecast.tools.export.WorkloadHubSchema;
import com.workloadhub.forecast.tools.seed.AbsencePlanner;
import com.workloadhub.forecast.tools.seed.SeedGenerator;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The committed fixture is what the generator produces today; a stale one fails the gate and says how to refresh it. */
class FixtureFreshnessTest {

    /** forecast-core's committed fixture directory, from this module's own working directory under surefire. */
    static final Path FIXTURES = Path.of("../forecast-core/src/test/resources/fixtures");

    static final String HOW = "stale fixture: run `bash server/tools/experiment.sh fixture` and commit the result";

    private static ExportEnvelope generated;

    static synchronized ExportEnvelope generated() {
        if (generated == null) {
            generated = SeedGenerator.generate(null, SeedGenerator.FIXTURE);
        }
        return generated;
    }

    @Test
    void theCommittedRowsMatchTheGenerator() throws Exception {
        StringWriter expected = new StringWriter();
        SqlExportWriter.write(generated(), expected);
        String actual = Files.readString(FIXTURES.resolve("seeded-rows.sql"), StandardCharsets.UTF_8);
        assertTrue(expected.toString().equals(actual), HOW);
    }

    /** Overload must be reachable on the fixture: at least one member logs more than a present day's 8.8 h somewhere. */
    @Test
    void someMemberLogsMoreThanADay() {
        Map<String, Double> byMemberDay = new HashMap<>();
        for (LinkedHashMap<String, Object> row : generated().rows("time_logs")) {
            byMemberDay.merge(row.get("user_id") + "|" + row.get("log_date"), (Double) row.get("hours"), Double::sum);
        }
        assertTrue(byMemberDay.values().stream().anyMatch(h -> h > AbsencePlanner.HOURS_PER_DAY),
                "no seeded member ever logs more than a day's worth in one day, so overload can never fire");
    }

    @Test
    void theCommittedSchemaMatchesTheResource() throws Exception {
        String expected = WorkloadHubSchema.readResource("/schema/workloadhub-postgresql.sql");
        String actual = Files.readString(FIXTURES.resolve("workloadhub-schema.sql"), StandardCharsets.UTF_8);
        assertTrue(expected.equals(actual), HOW);
    }
}
