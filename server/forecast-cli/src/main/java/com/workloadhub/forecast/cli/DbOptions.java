package com.workloadhub.forecast.cli;

import java.nio.file.Path;
import javax.sql.DataSource;
import org.sqlite.SQLiteDataSource;
import picocli.CommandLine.Option;

/** The --db option every command takes; builds a SQLite DataSource on the file. */
public class DbOptions {

    @Option(names = "--db", description = "SQLite database file (default: ${DEFAULT-VALUE})", defaultValue = "./workloadhub.db")
    Path db;

    public DataSource dataSource() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath());
        return ds;
    }
}
