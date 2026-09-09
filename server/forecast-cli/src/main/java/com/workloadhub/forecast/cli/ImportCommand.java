package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.data.ExportImporter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

@Command(name = "import", description = "Load a WorkloadHub JSON export (real or seeded) into the database, replacing existing rows.")
public class ImportCommand implements Callable<Integer> {

    @Mixin DbOptions db;

    @Parameters(index = "0", description = "The export file")
    Path file;

    @Override
    public Integer call() throws Exception {
        var env = ExportFiles.read(file);
        var counts = new ExportImporter(db.dataSource()).importAll(env, true);
        counts.forEach((table, n) -> {
            if (n > 0) {
                System.out.printf("%-26s %7d%n", table, n);
            }
        });
        System.out.println("Imported " + counts.values().stream().mapToInt(Integer::intValue).sum() + " rows from " + file);
        return 0;
    }
}
