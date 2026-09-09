package com.workloadhub.forecast.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CliSmokeTest {

    @Test
    void initImportExport(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("t.db");
        Path fixture = Path.of("../forecast-core/src/test/resources/fixtures/mini-export.json");
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        assertEquals(0, cli.execute("init-db", "--db", db.toString()));
        assertEquals(2, cli.execute("init-db", "--db", db.toString()), "refuses to overwrite without --force");
        assertEquals(0, cli.execute("import", "--db", db.toString(), fixture.toString()));
        Path out = dir.resolve("out.json");
        assertEquals(0, cli.execute("export", "--db", db.toString(), out.toString()));
        assertTrue(Files.readString(out).contains("\"CT2-CAL-1\""));
    }
}
