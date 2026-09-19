package com.workloadhub.forecast.store;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Creates the module's own tables, with a history table the host's migrations never see. */
public final class ForecastMigrations {

    public static final String HISTORY_TABLE = "forecast_schema_history";
    static final String LOCATION = "classpath:db/forecast/postgresql";

    private ForecastMigrations() {
    }

    public static void run(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(LOCATION)
                .table(HISTORY_TABLE)
                // The module's tables land in task_service, which already holds the WorkloadHub tables
                // before this ever runs; baseline that pre-existing state at version 0 so V1 (the module's
                // one migration) still applies on top of it. The schema is named below rather than taken
                // from the connection, so the tables always land beside the ones the module reads -- which
                // makes the host owe the matching search path, since every statement the module issues is
                // unqualified. A connection that resolves elsewhere migrates here and reads there.
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .validateOnMigrate(true)
                .schemas("task_service")
                .defaultSchema("task_service")
                .load()
                .migrate();
    }
}
