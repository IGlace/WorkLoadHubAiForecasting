package com.workloadhub.forecast.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.tools.export.SqlExportWriter;
import com.workloadhub.forecast.tools.export.WorkloadHubSchema;
import com.workloadhub.forecast.tools.seed.SeedGenerator;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The committed fixture is what the generator produces today; a stale one fails the gate and says how to refresh it. */
class FixtureFreshnessTest {

    /** forecast-core's committed fixture directory, from this module's own working directory under surefire. */
    static final Path FIXTURES = Path.of("../forecast-core/src/test/resources/fixtures");

    static final String HOW = "stale fixture: run `bash server/tools/experiment.sh fixture` and commit the result";

    @Test
    void theCommittedRowsMatchTheGenerator() throws Exception {
        StringWriter expected = new StringWriter();
        SqlExportWriter.write(SeedGenerator.generate(null, SeedGenerator.FIXTURE), expected);
        String actual = Files.readString(FIXTURES.resolve("seeded-rows.sql"), StandardCharsets.UTF_8);
        assertTrue(expected.toString().equals(actual), HOW);
    }

    @Test
    void theCommittedSchemaMatchesTheResource() throws Exception {
        String expected = WorkloadHubSchema.readResource("/schema/workloadhub-postgresql.sql");
        String actual = Files.readString(FIXTURES.resolve("workloadhub-schema.sql"), StandardCharsets.UTF_8);
        assertTrue(expected.equals(actual), HOW);
    }
}
