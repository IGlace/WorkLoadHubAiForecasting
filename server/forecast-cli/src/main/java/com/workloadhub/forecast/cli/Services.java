package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.ForecastMigrations;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What the CLI needs from the module on a CLI-owned SQLite file: the module's tables migrated, the dialect read
 * off the file, a {@link JdbcClient} to query it and a {@link ForecastRunner} with planned work on and the
 * default capacity of 40. The CLI builds and evaluates an experiment database and nothing more — it owns no run
 * and never narrates — so there is no {@code ForecastService} here and nothing that has to be closed. The host's
 * calls, and the live Copilot check with them, are in {@code server/examples/HostExample.java}.
 */
record Services(Dialect dialect, JdbcClient jdbc, ForecastRunner runner) {

    static Services open(DataSource ds) {
        ForecastMigrations.run(ds);
        return new Services(Dialect.of(ds), JdbcClient.create(ds), new ForecastRunner(new CapacityRule(40), true));
    }
}
