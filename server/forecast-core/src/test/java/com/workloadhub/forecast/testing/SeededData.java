package com.workloadhub.forecast.testing;

import com.workloadhub.forecast.data.ExportEnvelope;
import com.workloadhub.forecast.data.ExportImporter;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.ForecastRepository;
import com.workloadhub.forecast.seed.SeedConfig;
import com.workloadhub.forecast.seed.SeedGenerator;
import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** One synthetic dataset per JVM: 36 users, 30 weeks, seed 11, loaded through the real repository. */
public final class SeededData {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 6);
    private static ExportEnvelope envelope;
    private static ForecastData data;
    private static DataSource dataSource;

    private SeededData() {
    }

    public static synchronized ExportEnvelope envelope() {
        if (envelope == null) {
            envelope = SeedGenerator.generate(null, new SeedConfig(30, AS_OF, 11, true, 36));
        }
        return envelope;
    }

    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            dataSource = DatabaseTestSupport.sqliteInMemory();
            WorkloadHubSchema.createSqlite(dataSource);
            new ExportImporter(dataSource).importAll(envelope(), true);
        }
        return dataSource;
    }

    public static synchronized ForecastData data() {
        if (data == null) {
            DataSource ds = dataSource();
            data = new ForecastRepository(JdbcClient.create(ds), Dialect.of(ds)).loadAll();
        }
        return data;
    }

    public static LocalDate asOf() {
        return AS_OF;
    }
}
