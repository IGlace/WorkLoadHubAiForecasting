package com.workloadhub.forecastweb.demo;

import com.workloadhub.forecast.data.ExportEnvelope;
import com.workloadhub.forecast.data.ExportImporter;
import com.workloadhub.forecast.seed.SeedConfig;
import com.workloadhub.forecast.seed.SeedGenerator;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteDataSource;

/**
 * Demo only: the SQLite file this host runs on, created and seeded with a synthetic population the first time
 * it is missing (the schema, {@link SeedGenerator}, {@link ExportImporter}). No personal data: the synthetic
 * directory only. Delete the file to seed again. Production has its PostgreSQL pool and none of this.
 */
public final class DemoDatabase {

    private static final Logger LOG = LoggerFactory.getLogger(DemoDatabase.class);

    private final Path file;
    private final DataSource dataSource;
    private final boolean seededAtStart;

    private DemoDatabase(Path file, DataSource dataSource, boolean seededAtStart) {
        this.file = file;
        this.dataSource = dataSource;
        this.seededAtStart = seededAtStart;
    }

    /** Opens the file, seeding it first when it does not exist. */
    public static DemoDatabase open(Path file, SeedConfig seed) {
        boolean create = !Files.exists(file);
        if (create) {
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create " + file.getParent(), e);
            }
        }
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        ds.setBusyTimeout(30_000);
        // A run computes on the module's pool while the browser polls progress: in the default rollback
        // journal every reader blocks the writer, so a poll can sit out the whole busy timeout.
        ds.setJournalMode("WAL");
        ds.setSynchronous("NORMAL");
        if (create) {
            LOG.info("seeding {}: {} synthetic users, {} weeks ending {}", file, seed.users(), seed.weeks(), seed.end());
            try {
                WorkloadHubSchema.createSqlite(ds);
                ExportEnvelope envelope = SeedGenerator.generate(null, seed);
                new ExportImporter(ds).importAll(envelope, true);
            } catch (RuntimeException e) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // the failure below is the one to report
                }
                throw e;
            }
        }
        return new DemoDatabase(file, ds, create);
    }

    public Path file() {
        return file;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /** Whether this start created and seeded the file. */
    public boolean seededAtStart() {
        return seededAtStart;
    }
}
