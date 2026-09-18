package com.workloadhub.forecast;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.SqlExportWriter;
import com.workloadhub.forecast.seed.SeedGenerator;
import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** The committed fixture is what the generator produces today; a stale one fails the gate and says how to refresh it. */
class FixtureFreshnessTest {

    static final String HOW = "stale fixture: run `bash server/tools/experiment.sh fixture` and commit the result";

    @Test
    void theCommittedRowsMatchTheGenerator() throws Exception {
        StringWriter expected = new StringWriter();
        SqlExportWriter.write(SeedGenerator.generate(null, SeedGenerator.FIXTURE), expected);
        String actual = Files.readString(DatabaseTestSupport.FIXTURES.resolve("seeded-rows.sql"), StandardCharsets.UTF_8);
        assertTrue(expected.toString().equals(actual), HOW);
    }

    @Test
    void theCommittedSchemaMatchesTheResource() throws Exception {
        String expected = WorkloadHubSchema.readResource("/schema/workloadhub-postgresql.sql");
        String actual = Files.readString(DatabaseTestSupport.FIXTURES.resolve("workloadhub-schema.sql"), StandardCharsets.UTF_8);
        assertTrue(expected.equals(actual), HOW);
    }
}
