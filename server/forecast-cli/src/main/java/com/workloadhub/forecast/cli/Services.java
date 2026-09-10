package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.service.DefaultForecastService;
import com.workloadhub.forecast.service.RunProgressTracker;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.ForecastMigrations;
import com.workloadhub.forecast.store.JdbcRunStore;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The module's services on a CLI-owned SQLite file: one thread, planned work on, default capacity 40. */
record Services(DefaultForecastService service, Dialect dialect, JdbcClient jdbc) implements AutoCloseable {

    static Services open(DataSource ds) {
        ForecastMigrations.run(ds);
        Dialect dialect = Dialect.of(ds);
        ForecastRunner runner = new ForecastRunner(new CapacityRule(40), true);
        DefaultForecastService service = new DefaultForecastService(ds, dialect, runner, new JdbcRunStore(ds, dialect), new RunProgressTracker(), 1, true);
        return new Services(service, dialect, JdbcClient.create(ds));
    }

    @Override
    public void close() throws Exception {
        service.close();
    }
}
