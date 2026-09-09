package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.data.ExportExporter;
import com.workloadhub.forecast.data.ExportFiles;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

@Command(name = "export", description = "Write the database's WorkloadHub tables as a JSON export.")
public class ExportCommand implements Callable<Integer> {

    @Mixin DbOptions db;

    @Parameters(index = "0", description = "Output file")
    Path file;

    @Override
    public Integer call() throws Exception {
        var env = new ExportExporter(db.dataSource()).exportAll();
        ExportFiles.write(file, env);
        System.out.println("Wrote " + file + " (" + env.data().values().stream().mapToInt(java.util.List::size).sum() + " rows)");
        return 0;
    }
}
