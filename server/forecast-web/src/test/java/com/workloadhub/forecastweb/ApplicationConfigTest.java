package com.workloadhub.forecastweb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The shipped application.yml, read as the application reads it.
 *
 * <p>Nothing else covers this file: {@link TestBeans} replaces the DataSource bean with a Testcontainers one
 * that sets its own schema, so the integration test never touches the connection settings a real run uses.
 * That is how a started application could reach Flyway -- which names its schema itself -- and then fail
 * every query with {@code relation "users" does not exist}.
 */
class ApplicationConfigTest {

    private final StandardEnvironment environment = environment();

    /**
     * The WorkloadHub tables and the module's own live in task_service; PostgreSQL resolves an unqualified
     * name against the search path, which for the workloadhub role is "$user", public. Without this property
     * every query misses, so it is as much a part of pointing at the database as the URL is.
     */
    @Test
    void theDatasourceAsksForTheSchemaTheTablesAreIn() {
        assertEquals("task_service,public", environment.getProperty(
                "spring.datasource.hikari.data-source-properties.currentSchema"));
    }

    /** A database elsewhere may hold the tables in a schema of its own, so the default is overridable. */
    @Test
    void theSchemaIsOverridableLikeTheRestOfTheConnection() {
        assertTrue(raw("spring.datasource.hikari.data-source-properties.currentSchema")
                .contains("${FORECAST_WEB_DB_SCHEMA:"), "the schema needs an environment variable of its own");
        assertNotNull(environment.getProperty("spring.datasource.url"));
    }

    private String raw(String key) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            Object value = source.getProperty(key);
            if (value != null) {
                return value.toString();
            }
        }
        throw new AssertionError(key + " is not in application.yml");
    }

    private static StandardEnvironment environment() {
        StandardEnvironment environment = new StandardEnvironment();
        try {
            List<PropertySource<?>> sources =
                    new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
            sources.forEach(source -> environment.getPropertySources().addLast(source));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read application.yml", e);
        }
        return environment;
    }
}
