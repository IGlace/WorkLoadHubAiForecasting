package com.workloadhub.forecast.store;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Creates and upgrades the module's own tables, with a history table the host's migrations never see. */
public final class ForecastMigrations {

    public static final String HISTORY_TABLE = "forecast_schema_history";

    private ForecastMigrations() {
    }

    public static void run(DataSource dataSource) {
        Dialect dialect = Dialect.of(dataSource);
        var config = Flyway.configure()
                .dataSource(dataSource)
                .locations(dialect.flywayLocation())
                .table(HISTORY_TABLE)
                // The module's tables land in the host's own schema, which already holds the
                // WorkloadHub tables before this ever runs; baseline that pre-existing state at
                // version 0 so V1 (the module's own first migration) still applies on top of it.
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .validateOnMigrate(true);
        if (dialect == Dialect.POSTGRESQL) {
            config = config.schemas("task_service").defaultSchema("task_service");
        }
        config.load().migrate();
    }
}
