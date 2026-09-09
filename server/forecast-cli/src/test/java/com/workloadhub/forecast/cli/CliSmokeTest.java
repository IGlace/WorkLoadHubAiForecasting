package com.workloadhub.forecast.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void seedSyntheticThenImport(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("s.db");
        Path seeded = dir.resolve("seeded.json");
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        assertEquals(0, cli.execute("seed", "--synthetic", "--users", "12", "--weeks", "8", "--seed", "1", "--end", "2026-09-06", "--out", seeded.toString()));
        assertEquals(0, cli.execute("init-db", "--db", db.toString()));
        assertEquals(0, cli.execute("import", "--db", db.toString(), seeded.toString()));
        Path sql = dir.resolve("seeded.sql");
        assertEquals(0, cli.execute("seed", "--synthetic", "--users", "12", "--weeks", "8", "--seed", "1", "--end", "2026-09-06", "--format", "sql", "--out", sql.toString()));
        assertTrue(Files.readString(sql).contains("INSERT INTO tasks"));
    }

    @Test
    void realModeRefusesToWriteInsideTheRepository() throws Exception {
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        Path fixture = Path.of("../forecast-core/src/test/resources/fixtures/mini-export.json");
        Path inRepo = Path.of("target/real-seeded.json");
        assertEquals(2, cli.execute("seed", "--export", fixture.toString(), "--weeks", "8", "--out", inRepo.toString()));
        assertFalse(Files.exists(inRepo));
    }
}
