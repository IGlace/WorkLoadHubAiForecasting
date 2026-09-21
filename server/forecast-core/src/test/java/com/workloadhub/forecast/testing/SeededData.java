package com.workloadhub.forecast.testing;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.ForecastRepository;
import com.workloadhub.forecast.data.Ids;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.ForecastMigrations;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The seeded dataset every core test reads: the synthetic seed of 36 users over 30 weeks (seed 11, last day
 * 2026-09-06), committed as fixtures/seeded-rows.sql by `experiment.sh fixture` and kept fresh by
 * FixtureFreshnessTest. One migrated database per JVM, loaded through the real repository.
 */
public final class SeededData {

    /** The fixture's last day (SeedGenerator.FIXTURE). */
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 6);
    private static DataSource dataSource;
    private static ForecastData data;

    private SeededData() {
    }

    /** The JVM's shared copy: read it, or write only what the test clears again. */
    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            dataSource = freshDataSource();
        }
        return dataSource;
    }

    /** A new database with the WorkloadHub tables, the seeded rows and the module's tables, for a test that changes rows. */
    public static DataSource freshDataSource() {
        DataSource ds = DatabaseTestSupport.postgresWithSchema();
        DatabaseTestSupport.runScript(ds, DatabaseTestSupport.SEEDED_ROWS_SQL);
        ForecastMigrations.run(ds);
        return ds;
    }

    public static synchronized ForecastData data() {
        if (data == null) {
            data = new ForecastRepository(JdbcClient.create(dataSource())).loadAll();
        }
        return data;
    }

    public static LocalDate asOf() {
        return AS_OF;
    }

    /**
     * The teams of a dataset, keyed by their leader: every user who has at least one counted direct report
     * (design 2026-09-21, section 4). Sorted, so a test that takes the first one takes the same one every run.
     */
    public static List<UUID> teams(ForecastData data) {
        return data.members().stream()
                .map(MemberRow::managerId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Ids.UUID_ORDER)
                .toList();
    }

    /** The first team of a dataset that has members, which is every team by construction. */
    public static UUID anyTeam(ForecastData data) {
        return teams(data).stream().filter(t -> !data.membersOfTeam(t).isEmpty()).findFirst().orElseThrow();
    }
}
