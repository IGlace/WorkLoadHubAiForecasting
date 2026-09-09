package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.store.ForecastMigrations;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "init-db", description = "Create the 24 WorkloadHub tables and the module's tables in a new SQLite file.")
public class InitDbCommand implements Callable<Integer> {

    @Mixin DbOptions db;

    @Option(names = "--force", description = "Delete the file first if it exists.")
    boolean force;

    @Override
    public Integer call() throws Exception {
        if (Files.exists(db.db)) {
            if (!force) {
                System.err.println(db.db + " exists; use --force to recreate it");
                return 2;
            }
            Files.delete(db.db);
        }
        var ds = db.dataSource();
        WorkloadHubSchema.createSqlite(ds);
        ForecastMigrations.run(ds);
        System.out.println("Created " + db.db.toAbsolutePath() + " with the WorkloadHub schema and the forecast tables");
        return 0;
    }
}
