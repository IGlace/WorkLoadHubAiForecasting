package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * The fixtures are read from the classpath, not from a path relative to the module's working directory:
 * forecast-web's tests reuse this class through forecast-core's test-jar and run with their own module as
 * the working directory, where `src/test/resources/fixtures` does not exist.
 */
class DatabaseTestSupportTest {

    @Test
    void bothFixturesResolveOnTheClasspath() {
        try (InputStream schema = DatabaseTestSupport.class.getResourceAsStream(DatabaseTestSupport.SCHEMA_SQL);
                InputStream rows = DatabaseTestSupport.class.getResourceAsStream(DatabaseTestSupport.SEEDED_ROWS_SQL)) {
            assertNotNull(schema, DatabaseTestSupport.SCHEMA_SQL + " is not on the test classpath");
            assertNotNull(rows, DatabaseTestSupport.SEEDED_ROWS_SQL + " is not on the test classpath");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aMissingResourceSaysWhichOne() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DatabaseTestSupport.runScript(null, "/fixtures/does-not-exist.sql"));
        assertTrue(e.getMessage().contains("/fixtures/does-not-exist.sql"), "message must name the resource: " + e.getMessage());
    }
}
