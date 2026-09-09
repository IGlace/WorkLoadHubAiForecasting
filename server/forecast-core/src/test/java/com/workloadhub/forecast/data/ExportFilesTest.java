package com.workloadhub.forecast.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportFilesTest {

    static Path fixture() {
        return Path.of("src/test/resources/fixtures/mini-export.json");
    }

    @Test
    void readsEnvelopeAndKeepsJsonTypes() throws IOException {
        ExportEnvelope env = ExportFiles.read(fixture());
        assertEquals("task_service", env.schema());
        assertEquals(List.of("refresh_tokens"), env.excludedTables());
        assertEquals(13, env.data().size());
        var task = env.rows("tasks").get(0);
        assertEquals("CT2-CAL-1", task.get("key"));
        assertEquals(16L, task.get("original_estimate_hrs"));
        assertEquals(12.5, task.get("remaining_estimate_hrs"));
        assertEquals(Boolean.FALSE, task.get("archived"));
        assertNull(task.get("finished_date"));
        assertTrue(env.rows("no_such_table").isEmpty());
    }

    @Test
    void keepsColumnOrderOfTheFirstRow() throws IOException {
        ExportEnvelope env = ExportFiles.read(fixture());
        var keys = List.copyOf(env.rows("users").get(0).keySet());
        assertEquals(List.of("id", "role", "email", "active"), keys.subList(0, 4));
        assertEquals(List.of("user_roles", "job_titles", "users"), List.copyOf(env.data().keySet()).subList(0, 3));
    }

    @Test
    void fallsBackToWindows1252(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cp1252.json");
        String json = Files.readString(fixture(), StandardCharsets.UTF_8);
        Files.write(file, json.getBytes(Charset.forName("windows-1252")));
        ExportEnvelope env = ExportFiles.read(file);
        assertEquals("New Year’s Day", env.rows("holidays").get(0).get("title"));
    }

    @Test
    void writesAndReadsBackIdentically(@TempDir Path dir) throws IOException {
        ExportEnvelope env = ExportFiles.read(fixture());
        Path out = dir.resolve("out.json");
        ExportFiles.write(out, env);
        assertEquals(env, ExportFiles.read(out));
        String written = Files.readString(out);
        assertTrue(written.startsWith("{\n"), "pretty printed");
        assertTrue(written.indexOf('\r') < 0, "no CR: line endings must be \\n on every platform");
    }
}
