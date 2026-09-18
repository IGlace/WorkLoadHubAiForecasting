package com.workloadhub.forecastweb.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.seed.SeedConfig;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

class DemoDatabaseTest {

    @TempDir
    Path dir;

    @Test
    void seedsAMissingFileOnceAndOpensAnExistingOneAsIs() {
        Path file = dir.resolve("data/workloadhub.db");
        SeedConfig small = new SeedConfig(6, LocalDate.of(2026, 9, 6), 3, true, 12);
        DemoDatabase first = DemoDatabase.open(file, small);
        assertTrue(first.seededAtStart());
        int users = JdbcClient.create(first.dataSource()).sql("SELECT COUNT(*) FROM users").query(Integer.class).single();
        assertEquals(12, users);
        JdbcClient.create(first.dataSource()).sql("UPDATE users SET full_name = 'Kept' WHERE id = (SELECT id FROM users LIMIT 1)").update();

        DemoDatabase second = DemoDatabase.open(file, small);
        assertFalse(second.seededAtStart());
        assertEquals(1, JdbcClient.create(second.dataSource()).sql("SELECT COUNT(*) FROM users WHERE full_name = 'Kept'").query(Integer.class).single(),
                "an existing file is opened, never reseeded");
    }
}
