# Java Foundation and Seed Generator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Java module (`server/`) with the WorkloadHub schema on SQLite and PostgreSQL, import and export of the application's JSON export, the encrypted GitHub token store, the module's own tables, a command line for WSL, and the seed generator that writes 52 weeks of realistic history for the real directory of 264 users.

**Architecture:** Two Maven modules, `forecast-core` (library with a Spring Boot auto-configuration, JDBC data access through `JdbcClient`, Flyway for the module's tables) and `forecast-cli` (picocli commands on the same beans, SQLite). The seed generator is a deterministic, single-threaded simulation that emits an export envelope: directory-derived teams, job-title work families, a weekly rhythm per member, and a day-by-day work queue that writes tasks, transitions, time logs, absences and capacity rows the way the application would.

**Tech Stack:** Java 21, Maven 3.9, Spring Boot 4.1.1 (`spring-boot-starter-jdbc`, Jackson 3 `tools.jackson`), Flyway 12 (managed), `sqlite-jdbc` 3.53.4.0, PostgreSQL driver (managed), picocli 4.7.7 with `picocli-spring-boot-starter`, JUnit 6 (managed), jqwik 1.10.1, Testcontainers 1.21.4.

**Spec:** `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md` (sections 2, 3, 4, 11 properties, 12, 13). This plan is phases A and B of section 16; the forecast pipeline, Copilot and the migration follow in later plans.

## Global Constraints

- Java 21 is the floor (`<java.version>21</java.version>`); Spring Boot parent `4.1.1`; base package `com.workloadhub.forecast`; artifacts `workloadhub-forecast-core` and `workloadhub-forecast-cli`, group `com.workloadhub`.
- All SQL runs unchanged on PostgreSQL and SQLite: no vendor functions; UUIDs, dates and timestamps bound as ISO strings through `Dialect`; booleans through `Dialect.bool`.
- The module's own tables carry the `forecast_` prefix and are created by Flyway with `table = forecast_schema_history`.
- Tokens are AES-256-GCM encrypted under `whf.token-key`; never logged; classic `ghp_` tokens refused.
- The real-mode seed output holds password hashes and emails: never committed, and the CLI refuses to write it inside the repository without `--force`. Only `--synthetic` output is committed.
- Every generator step is deterministic given `--seed`; two runs with the same arguments produce identical bytes.
- Weeks start on Monday; working days are Monday to Friday minus confirmed, active holidays.
- Timestamps in exports are `LocalDateTime.toString()` without zone (`2026-09-03T13:59:58.548359`), dates `yyyy-MM-dd`, booleans JSON booleans.
- Tests: JUnit 6 for examples, jqwik for properties, SQLite always, PostgreSQL through Testcontainers when Docker is reachable (skipped with a message otherwise). `mvn -B verify` in `server/` must pass under three minutes without Docker.
- Commit messages: imperative subject, short body explaining why; no model identifiers in any file; the repository's commit trailer rules apply.
- Never `--no-verify`; stage files by path.

---

## File structure

```text
server/
  pom.xml                                                  parent (Task 1)
  tools/translate-schema.py                                regenerates the two schema files (committed with this plan)
  forecast-core/
    pom.xml                                                (Task 1)
    src/main/java/com/workloadhub/forecast/
      ForecastAutoConfiguration.java                       beans: properties, Dialect, migrations, token store (Task 6)
      ForecastProperties.java                              whf.* properties (Task 6)
      api/GitHubTokenStore.java                            interface (Task 5)
      api/ForecastException.java                           code + message (Task 5)
      store/Dialect.java                                   POSTGRESQL | SQLITE, value coercion, placeholders (Task 2)
      store/WorkloadHubSchema.java                         table order, boolean columns, createSqlite (Task 2)
      store/ForecastMigrations.java                        Flyway runner for db/forecast/<dialect> (Task 5)
      store/AesGcmCipher.java                              encrypt/decrypt (Task 5)
      store/JdbcGitHubTokenStore.java                      users.github_token (Task 5)
      data/ExportEnvelope.java                             record of the JSON export (Task 3)
      data/ExportFiles.java                                read (UTF-8, cp1252 fallback) and write JSON (Task 3)
      data/ExportImporter.java                             envelope -> tables (Task 4)
      data/ExportExporter.java                             tables -> envelope (Task 4)
      data/SqlExportWriter.java                            envelope -> PostgreSQL INSERT script (Task 11)
      seed/SeedConfig.java                                 parameters record (Task 7)
      seed/SeedRandom.java                                 seeded draws: uuid, lognormal, poisson, pick (Task 7)
      seed/WorkFamily.java                                 families and title classification (Task 7)
      seed/Directory.java                                  users -> teams, departments, roles, memberships (Task 7)
      seed/SeedCalendar.java                               holidays, working days, weeks (Task 8)
      seed/AbsencePlanner.java                             leaves, absences, per-day presence (Task 8)
      seed/CapacityWriter.java                             user_capacity and team_capacity rows (Task 8)
      seed/ProjectPlanner.java                             projects with windows, epics (Task 9)
      seed/Rhythm.java                                     weekly target hours and arrival plan (Task 9)
      seed/WorkQueue.java                                  day-by-day simulation, task lifecycle rows (Task 10)
      seed/Rows.java                                       row builders for each table (Task 10)
      seed/Anonymiser.java                                 synthetic identities (Task 11)
      seed/SeedGenerator.java                              orchestrator -> ExportEnvelope (Task 11)
    src/main/resources/
      schema/workloadhub-postgresql.sql                    the owner's DDL, cleaned (committed with this plan)
      schema/workloadhub-sqlite.sql                        the SQLite translation (committed with this plan)
      db/forecast/postgresql/V1__forecast_tables.sql       (Task 5)
      db/forecast/sqlite/V1__forecast_tables.sql           (Task 5)
      META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports (Task 6)
    src/test/java/com/workloadhub/forecast/...             tests per task
    src/test/resources/fixtures/mini-export.json           hand-written 2-user export (Task 3)
    src/test/resources/fixtures/synthetic-40.json          generated by Task 11
  forecast-cli/
    pom.xml                                                (Task 6)
    src/main/java/com/workloadhub/forecast/cli/
      ForecastCli.java                                     Spring Boot main + picocli root (Task 6)
      DbOptions.java                                       --db option shared by commands (Task 6)
      InitDbCommand.java, ImportCommand.java, ExportCommand.java   (Task 6)
      SeedCommand.java                                     (Task 11)
    src/main/resources/application.yml                     (Task 6)
    README.md                                              how to run in WSL (Task 12)
.github/workflows/ci.yml                                   add the `server` job (Task 1)
.gitignore                                                 real exports and *.db (Task 12)
```

---

### Task 1: Maven skeleton and CI job

**Files:**
- Create: `server/pom.xml`, `server/forecast-core/pom.xml`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/package-info.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/BuildSmokeTest.java`
- Modify: `.github/workflows/ci.yml` (add a job; leave the existing jobs untouched)

**Interfaces:**
- Produces: the parent POM every later task builds with; the `mvn -B verify` command executed from `server/`.

- [ ] **Step 1: Write the parent POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>
  </parent>
  <groupId>com.workloadhub</groupId>
  <artifactId>workloadhub-forecast-parent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>
  <name>WorkloadHub forecast (parent)</name>
  <modules>
    <module>forecast-core</module>
  </modules>
  <properties>
    <java.version>21</java.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <sqlite.version>3.53.4.0</sqlite.version>
    <picocli.version>4.7.7</picocli.version>
    <jqwik.version>1.10.1</jqwik.version>
    <testcontainers.version>1.21.4</testcontainers.version>
    <xgboost.version>3.4.0</xgboost.version>
    <copilot.version>1.0.13-preview.6</copilot.version>
  </properties>
  <dependencyManagement>
    <dependencies>
      <dependency><groupId>org.xerial</groupId><artifactId>sqlite-jdbc</artifactId><version>${sqlite.version}</version></dependency>
      <dependency><groupId>info.picocli</groupId><artifactId>picocli-spring-boot-starter</artifactId><version>${picocli.version}</version></dependency>
      <dependency><groupId>net.jqwik</groupId><artifactId>jqwik</artifactId><version>${jqwik.version}</version></dependency>
      <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><version>${testcontainers.version}</version></dependency>
      <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><version>${testcontainers.version}</version></dependency>
      <dependency><groupId>ml.dmlc</groupId><artifactId>xgboost4j_2.12</artifactId><version>${xgboost.version}</version></dependency>
      <dependency><groupId>com.github</groupId><artifactId>copilot-sdk-java</artifactId><version>${copilot.version}</version></dependency>
      <dependency><groupId>com.github</groupId><artifactId>copilot-sdk-java-runtime</artifactId><version>${copilot.version}</version><classifier>linux-x64</classifier></dependency>
    </dependencies>
  </dependencyManagement>
  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-surefire-plugin</artifactId>
          <configuration>
            <trimStackTrace>false</trimStackTrace>
          </configuration>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
```

- [ ] **Step 2: Write the core POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.workloadhub</groupId>
    <artifactId>workloadhub-forecast-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>workloadhub-forecast-core</artifactId>
  <name>WorkloadHub forecast core</name>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jdbc</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-autoconfigure</artifactId></dependency>
    <dependency><groupId>tools.jackson.core</groupId><artifactId>jackson-databind</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope><optional>true</optional></dependency>
    <dependency><groupId>org.xerial</groupId><artifactId>sqlite-jdbc</artifactId><scope>runtime</scope><optional>true</optional></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-configuration-processor</artifactId><optional>true</optional></dependency>

    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>net.jqwik</groupId><artifactId>jqwik</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.xerial</groupId><artifactId>sqlite-jdbc</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
  </dependencies>
</project>
```

The `sqlite-jdbc` dependency appears twice on purpose: optional at runtime for hosts that want it, and plain `test` scope so the core tests always have it. Maven keeps the nearest declaration; both resolve to the managed version.

- [ ] **Step 3: Write the package marker and the smoke test**

`server/forecast-core/src/main/java/com/workloadhub/forecast/package-info.java`:

```java
/** WorkloadHub forecast module: demand, capacity and overload per member and week, narrated by Copilot. */
package com.workloadhub.forecast;
```

`server/forecast-core/src/test/java/com/workloadhub/forecast/BuildSmokeTest.java`:

```java
package com.workloadhub.forecast;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class BuildSmokeTest {

    @Test
    void junitRuns() {
        assertTrue(Runtime.version().feature() >= 21, "Java 21 or newer");
    }

    @Property
    boolean jqwikRunsOnThisPlatform(@ForAll int x) {
        return x == Integer.MIN_VALUE || Math.abs(x) >= 0;
    }
}
```

- [ ] **Step 4: Build and run the tests**

Run: `cd server && mvn -B -q verify`
Expected: BUILD SUCCESS; surefire reports two tests (`junitRuns`, `jqwikRunsOnThisPlatform`) passing. If jqwik's engine is not discovered, surefire prints "Tests run: 1": add `<dependency><groupId>org.junit.platform</groupId><artifactId>junit-platform-launcher</artifactId><scope>test</scope></dependency>` to the core POM and rerun.

- [ ] **Step 5: Add the CI job**

Append to `.github/workflows/ci.yml` under `jobs:` (keep the existing jobs exactly as they are):

```yaml
  server:
    name: Java module
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: maven
      - name: Verify
        run: mvn -B verify
        working-directory: server
```

Docker is present on `ubuntu-latest`, so the PostgreSQL tests of later tasks run there.

- [ ] **Step 6: Commit**

```bash
git add server/pom.xml server/forecast-core/pom.xml server/forecast-core/src .github/workflows/ci.yml
git commit -m "build(server): Maven skeleton for the Java forecast module

Parent and core modules on Spring Boot 4.1.1 and Java 21 with the
versions the design verified together, a smoke test proving JUnit 6
and jqwik run, and a CI job that runs the Maven gate."
```

---

### Task 2: Dialect, the WorkloadHub schema on SQLite, and the database test support

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/store/Dialect.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/store/WorkloadHubSchema.java`
- Already present: `server/forecast-core/src/main/resources/schema/workloadhub-sqlite.sql`, `schema/workloadhub-postgresql.sql`, `server/tools/translate-schema.py` (committed with this plan)
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/store/DatabaseTestSupport.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/store/SchemaFilesTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/store/DialectTest.java`

**Interfaces:**
- Produces: `enum Dialect { POSTGRESQL, SQLITE }` with `static Dialect of(DataSource)`, `Object bool(boolean)`, `boolean asBoolean(Object)`, `String placeholder(String columnTypeName)`, `String flywayLocation()`; `WorkloadHubSchema.TABLE_ORDER` (List<String>), `WorkloadHubSchema.BOOLEAN_COLUMNS` (Map<String, Set<String>>), `static void createSqlite(DataSource)`, `static void createPostgresql(DataSource)`; test helper `DatabaseTestSupport.sqliteInMemory()` and `DatabaseTestSupport.postgresOrSkip()`.

- [ ] **Step 1: Write the failing tests**

`DialectTest.java`:

```java
package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DialectTest {

    @Test
    void sqliteStoresBooleansAsIntegers() {
        assertEquals(1, Dialect.SQLITE.bool(true));
        assertEquals(0, Dialect.SQLITE.bool(false));
        assertTrue(Dialect.SQLITE.asBoolean(1));
        assertFalse(Dialect.SQLITE.asBoolean(0L));
    }

    @Test
    void postgresqlKeepsBooleans() {
        assertEquals(Boolean.TRUE, Dialect.POSTGRESQL.bool(true));
        assertTrue(Dialect.POSTGRESQL.asBoolean(Boolean.TRUE));
    }

    @Test
    void postgresqlCastsTypedPlaceholders() {
        assertEquals("CAST(? AS uuid)", Dialect.POSTGRESQL.placeholder("uuid"));
        assertEquals("CAST(? AS timestamp)", Dialect.POSTGRESQL.placeholder("timestamp"));
        assertEquals("CAST(? AS date)", Dialect.POSTGRESQL.placeholder("date"));
        assertEquals("CAST(? AS time)", Dialect.POSTGRESQL.placeholder("time"));
        assertEquals("?", Dialect.POSTGRESQL.placeholder("varchar"));
        assertEquals("?", Dialect.SQLITE.placeholder("TEXT"));
    }

    @Test
    void detectsSqlite() {
        assertEquals(Dialect.SQLITE, Dialect.of(DatabaseTestSupport.sqliteInMemory()));
    }
}
```

`SchemaFilesTest.java`:

```java
package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class SchemaFilesTest {

    private static final Pattern TABLE = Pattern.compile("CREATE TABLE (?:task_service\\.)?(\\w+) \\((.*?)\\n\\);", Pattern.DOTALL);
    private static final Pattern COLUMN = Pattern.compile("^\\s{4}(\\w+) ", Pattern.MULTILINE);

    static Map<String, TreeSet<String>> columnsOf(String ddl) {
        Map<String, TreeSet<String>> out = new LinkedHashMap<>();
        Matcher m = TABLE.matcher(ddl);
        while (m.find()) {
            TreeSet<String> cols = new TreeSet<>();
            Matcher c = COLUMN.matcher(m.group(2));
            while (c.find()) {
                String name = c.group(1);
                if (!name.equals("CONSTRAINT") && !name.equals("PRIMARY") && !name.equals("UNIQUE")
                        && !name.equals("FOREIGN") && !name.equals("CHECK")) {
                    cols.add(name);
                }
            }
            out.put(m.group(1), cols);
        }
        return out;
    }

    static String resource(String name) throws IOException {
        try (var in = SchemaFilesTest.class.getResourceAsStream("/schema/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void sqliteTranslationHasTheSameTablesAndColumnsAsThePostgresqlDump() throws IOException {
        Map<String, TreeSet<String>> pg = columnsOf(resource("workloadhub-postgresql.sql"));
        Map<String, TreeSet<String>> lite = columnsOf(resource("workloadhub-sqlite.sql"));
        assertEquals(24, pg.size());
        assertEquals(pg, lite);
    }

    @Test
    void tableOrderCoversEveryTableOnce() throws IOException {
        Map<String, TreeSet<String>> pg = columnsOf(resource("workloadhub-postgresql.sql"));
        assertEquals(new TreeSet<>(pg.keySet()), new TreeSet<>(WorkloadHubSchema.TABLE_ORDER));
        assertEquals(pg.size(), WorkloadHubSchema.TABLE_ORDER.size());
    }

    @Test
    void booleanColumnsExistInTheDump() throws IOException {
        Map<String, TreeSet<String>> pg = columnsOf(resource("workloadhub-postgresql.sql"));
        WorkloadHubSchema.BOOLEAN_COLUMNS.forEach((table, cols) ->
                assertTrue(pg.get(table).containsAll(cols), table + " " + cols));
    }

    @Test
    void createsAllTablesOnSqlite() throws SQLException {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        assertEquals(24, countTables(ds));
    }

    @Test
    void createsAllTablesOnPostgresql() throws SQLException {
        DataSource ds = DatabaseTestSupport.postgresOrSkip();
        WorkloadHubSchema.createPostgresql(ds);
        assertEquals(24, countTables(ds));
    }

    static int countTables(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            int n = 0;
            try (ResultSet rs = md.getTables(null, null, "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    String schema = rs.getString("TABLE_SCHEM");
                    if (schema == null || schema.equals("task_service") || schema.equals("main")) {
                        n++;
                    }
                }
            }
            return n;
        }
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='DialectTest,SchemaFilesTest'`
Expected: compilation errors, `Dialect`, `WorkloadHubSchema` and `DatabaseTestSupport` do not exist.

- [ ] **Step 3: Write `Dialect`**

```java
package com.workloadhub.forecast.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;

/** The two database engines the module runs on, and the few places their SQL differs. */
public enum Dialect {
    POSTGRESQL,
    SQLITE;

    public static Dialect of(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            if (product.contains("sqlite")) {
                return SQLITE;
            }
            if (product.contains("postgres")) {
                return POSTGRESQL;
            }
            throw new IllegalStateException("Unsupported database: " + product);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read database metadata", e);
        }
    }

    /** The value to bind for a boolean column. */
    public Object bool(boolean value) {
        return this == SQLITE ? (value ? 1 : 0) : Boolean.valueOf(value);
    }

    /** Reads a boolean column value as returned by the driver. */
    public boolean asBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        String s = value.toString().trim().toLowerCase(Locale.ROOT);
        return s.equals("1") || s.equals("t") || s.equals("true");
    }

    /**
     * The placeholder for one column in an INSERT. PostgreSQL needs a cast for values bound as
     * strings into uuid, date, time and timestamp columns; SQLite stores them as text.
     */
    public String placeholder(String columnTypeName) {
        if (this == SQLITE) {
            return "?";
        }
        String t = columnTypeName.toLowerCase(Locale.ROOT);
        if (t.equals("uuid") || t.startsWith("timestamp") || t.equals("date") || t.startsWith("time")) {
            return "CAST(? AS " + (t.startsWith("timestamp") ? "timestamp" : t.startsWith("time") ? "time" : t) + ")";
        }
        return "?";
    }

    public String flywayLocation() {
        return "classpath:db/forecast/" + name().toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 4: Write `WorkloadHubSchema`**

```java
package com.workloadhub.forecast.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/** Facts about the WorkloadHub tables the module reads: load order, boolean columns, DDL. */
public final class WorkloadHubSchema {

    /** Parents before children, so an import can insert in this order. */
    public static final List<String> TABLE_ORDER = List.of(
            "user_roles", "job_titles", "users", "teams", "team_members", "job_title_role_mappings",
            "role_change_requests", "task_statuses", "task_types", "projects", "project_history",
            "tasks", "task_history", "task_comments", "task_attachments", "time_logs", "absences",
            "personal_leaves", "holidays", "user_capacity", "team_capacity", "notifications",
            "sync_metadata", "refresh_tokens");

    /** Columns that are boolean in PostgreSQL and integer 0/1 on SQLite. */
    public static final Map<String, Set<String>> BOOLEAN_COLUMNS = Map.of(
            "holidays", Set.of("active"),
            "notifications", Set.of("is_read"),
            "projects", Set.of("archived"),
            "sync_metadata", Set.of("sync_in_progress"),
            "task_statuses", Set.of("active"),
            "task_types", Set.of("active"),
            "tasks", Set.of("archived", "reopened_from_done"),
            "teams", Set.of("active"),
            "user_roles", Set.of("active"),
            "users", Set.of("active"));

    private WorkloadHubSchema() {
    }

    public static boolean isBoolean(String table, String column) {
        return BOOLEAN_COLUMNS.getOrDefault(table, Set.of()).contains(column);
    }

    /** Creates the 24 tables on an empty SQLite database. */
    public static void createSqlite(DataSource dataSource) {
        runScript(dataSource, "/schema/workloadhub-sqlite.sql");
    }

    /** Creates schema task_service and the 24 tables on an empty PostgreSQL database. */
    public static void createPostgresql(DataSource dataSource) {
        runScript(dataSource, "/schema/workloadhub-postgresql.sql");
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("SET search_path TO task_service, public");
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot set search_path", e);
        }
    }

    static void runScript(DataSource dataSource, String resource) {
        String sql = readResource(resource);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String statement : sql.split(";\\s*\\n")) {
                String trimmed = stripComments(statement).trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Schema script failed: " + resource, e);
        }
    }

    static String stripComments(String statement) {
        StringBuilder out = new StringBuilder();
        for (String line : statement.split("\n")) {
            if (!line.trim().startsWith("--")) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    static String readResource(String resource) {
        try (InputStream in = WorkloadHubSchema.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
    }
}
```

- [ ] **Step 5: Write `DatabaseTestSupport`**

```java
package com.workloadhub.forecast.store;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.postgresql.ds.PGSimpleDataSource;
import org.sqlite.SQLiteDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/** Data sources for tests: a fresh in-memory SQLite, and PostgreSQL in Docker when available. */
public final class DatabaseTestSupport {

    private static PostgreSQLContainer<?> postgres;

    private DatabaseTestSupport() {
    }

    /** A new, empty, private in-memory SQLite database (shared-cache URL so pooled connections see it). */
    public static DataSource sqliteInMemory() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:file:" + UUID.randomUUID() + "?mode=memory&cache=shared");
        return keepAlive(ds);
    }

    /** SQLite drops a memory database when its last connection closes; hold one open for the test's life. */
    private static DataSource keepAlive(SQLiteDataSource ds) {
        try {
            java.sql.Connection anchor = ds.getConnection();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    anchor.close();
                } catch (Exception ignored) {
                    // shutting down
                }
            }));
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return ds;
    }

    /** A PostgreSQL 16 container with a fresh database per call, or an assumption failure that skips the test. */
    public static synchronized DataSource postgresOrSkip() {
        boolean docker;
        try {
            docker = DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            docker = false;
        }
        Assumptions.assumeTrue(docker, "Docker is not reachable: PostgreSQL tests skipped");
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine");
            postgres.start();
        }
        String db = "t_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var st = c.createStatement()) {
            st.execute("CREATE DATABASE " + db);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1"));
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        ds.setCurrentSchema("task_service,public");
        return ds;
    }
}
```

- [ ] **Step 6: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='DialectTest,SchemaFilesTest'`
Expected: `DialectTest` 4 passed; `SchemaFilesTest` 4 passed and `createsAllTablesOnPostgresql` skipped when Docker is absent (passes on CI).

- [ ] **Step 7: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/store server/forecast-core/src/test/java/com/workloadhub/forecast/store server/forecast-core/src/main/resources/schema server/tools/translate-schema.py
git commit -m "feat(server): dialect helper and the WorkloadHub schema on SQLite and PostgreSQL

The owner's PostgreSQL dump, cleaned, and its SQLite translation ship as
resources with a test that keeps their tables and columns identical; the
Dialect enum holds the only engine differences (booleans, typed casts)."
```

---

### Task 3: The export envelope: read and write the WorkloadHub JSON export

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/ExportEnvelope.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/ExportFiles.java`
- Create: `server/forecast-core/src/test/resources/fixtures/mini-export.json`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/data/ExportFilesTest.java`

**Interfaces:**
- Produces: `record ExportEnvelope(String database, String schema, String exportedAt, List<String> excludedTables, LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data)` with `List<LinkedHashMap<String, Object>> rows(String table)` (empty list when absent) and `ExportEnvelope withData(LinkedHashMap<...>)`; `ExportFiles.read(Path)`, `ExportFiles.parse(String)`, `ExportFiles.write(Path, ExportEnvelope)`, `ExportFiles.toJson(ExportEnvelope)`, `ExportFiles.mapper()`.
- Row values are exactly the JSON types: `String`, `Long`, `Double`, `Boolean`, `null`.

- [ ] **Step 1: Write the fixture**

`mini-export.json` (two users, one team, one task with its history and one log; the `’` in the holiday title is deliberate, it is the character the real export encodes in cp1252):

```json
{
  "database": "avl_workloadhub",
  "schema": "task_service",
  "exported_at": "2026-09-08T16:45:02.63994+01:00",
  "excluded_tables": ["refresh_tokens"],
  "data": {
    "user_roles": [
      {"id": "10000000-0000-0000-0000-000000000001", "code": "MEMBER", "label": "Member", "active": true, "created_at": "2026-09-01T08:00:00", "updated_at": "2026-09-01T08:00:00"}
    ],
    "job_titles": [
      {"id": "20000000-0000-0000-0000-000000000001", "value": "Calibration Engineer", "created_at": "2026-09-01T08:00:00", "updated_at": "2026-09-01T08:00:00"}
    ],
    "users": [
      {"id": "30000000-0000-0000-0000-000000000001", "role": "TEAM_LEADER", "email": "lead@example.test", "active": true, "version": 0, "password": null, "username": "lead", "full_name": "Lead One", "job_title": "Team Leader Calibration", "object_id": null, "created_at": "2026-09-01T08:00:00", "department": "PTE / CT2 Calibration & Testing 2", "manager_id": null, "updated_at": "2026-09-01T08:00:00", "account_name": "lead", "deactivated_at": null, "manager_object_id": null},
      {"id": "30000000-0000-0000-0000-000000000002", "role": "MEMBER", "email": "eng@example.test", "active": true, "version": 0, "password": null, "username": "eng", "full_name": "Engineer Two", "job_title": "Calibration Engineer", "object_id": null, "created_at": "2026-09-01T08:00:00", "department": "PTE / CT2 Calibration & Testing 2", "manager_id": "30000000-0000-0000-0000-000000000001", "updated_at": "2026-09-01T08:00:00", "account_name": "eng", "deactivated_at": null, "manager_object_id": null}
    ],
    "teams": [
      {"id": "40000000-0000-0000-0000-000000000001", "name": "CT2 · Lead One", "active": true, "version": 0, "manager_id": "30000000-0000-0000-0000-000000000001", "parent_team_id": null, "created_at": "2026-09-01T08:00:00", "updated_at": "2026-09-01T08:00:00"}
    ],
    "team_members": [
      {"id": "50000000-0000-0000-0000-000000000001", "team_id": "40000000-0000-0000-0000-000000000001", "user_id": "30000000-0000-0000-0000-000000000002", "joined_at": "2026-09-01T08:00:00", "created_at": "2026-09-01T08:00:00", "updated_at": "2026-09-01T08:00:00"}
    ],
    "task_statuses": [
      {"id": "60000000-0000-0000-0000-000000000001", "name": "To Do", "active": true, "category": "TO_DO", "sort_order": 1, "description": null, "created_at": "2026-09-01T08:00:00", "updated_at": "2026-09-01T08:00:00"},
      {"id": "60000000-0000-0000-0000-000000000002", "name": "In Progress", "active": true, "category": "IN_PROGRESS", "sort_order": 2, "description": null, "created_at": "2026-09-01T08:00:00", "updated_at": "2026-09-01T08:00:00"}
    ],
    "task_types": [
      {"id": "70000000-0000-0000-0000-000000000001", "name": "Task", "active": true, "icon": null, "description": null, "subtask_type_id": null, "created_at": "2026-09-01T08:00:00", "updated_at": "2026-09-01T08:00:00"}
    ],
    "projects": [
      {"id": "80000000-0000-0000-0000-000000000001", "key": "CT2-CAL", "name": "CT2 calibration campaign Q1", "status": "ACTIVE", "previous_status": null, "archived": false, "archived_at": null, "archived_by": null, "owner_id": "30000000-0000-0000-0000-000000000001", "team_id": "40000000-0000-0000-0000-000000000001", "version": 0, "description": null, "next_task_number": 2, "created_at": "2026-09-01T08:00:00", "updated_at": "2026-09-01T08:00:00"}
    ],
    "tasks": [
      {"id": "90000000-0000-0000-0000-000000000001", "key": "CT2-CAL-1", "title": "Map base calibration", "description": null, "version": 0, "archived": false, "due_date": "2026-09-10", "priority": "HIGH", "project_id": "80000000-0000-0000-0000-000000000001", "assignee_id": "30000000-0000-0000-0000-000000000002", "reporter_id": "30000000-0000-0000-0000-000000000001", "task_number": 1, "created_date": "2026-09-01T09:00:00.54207", "planned_week": "2026-08-31", "started_date": "2026-09-02T09:00:00", "task_type_id": "70000000-0000-0000-0000-000000000001", "finished_date": null, "parent_task_id": null, "task_status_id": "60000000-0000-0000-0000-000000000002", "last_reopened_at": null, "reopened_from_done": false, "original_estimate_hrs": 16, "remaining_estimate_hrs": 12.5, "archived_at": null, "created_at": "2026-09-01T09:00:00.54207", "updated_at": "2026-09-02T09:00:00"}
    ],
    "task_history": [
      {"id": "a0000000-0000-0000-0000-000000000001", "task_id": "90000000-0000-0000-0000-000000000001", "user_id": "30000000-0000-0000-0000-000000000001", "field_name": "assignee", "old_value": null, "new_value": "Engineer Two", "changed_at": "2026-09-01T09:30:00", "created_at": "2026-09-01T09:30:00", "updated_at": "2026-09-01T09:30:00"}
    ],
    "time_logs": [
      {"id": "b0000000-0000-0000-0000-000000000001", "task_id": "90000000-0000-0000-0000-000000000001", "user_id": "30000000-0000-0000-0000-000000000002", "hours": 3.5, "log_date": "2026-09-02", "note": "Worked on map", "created_at": "2026-09-02T17:00:00", "updated_at": "2026-09-02T17:00:00"}
    ],
    "holidays": [
      {"id": "c0000000-0000-0000-0000-000000000001", "title": "New Year’s Day", "type": "NATIONAL", "status": "CONFIRMED", "active": true, "start_date": "2026-01-01", "end_date": "2026-01-01", "country_code": "MA", "created_at": "2026-09-01T08:00:00", "updated_at": "2026-09-01T08:00:00"}
    ],
    "user_capacity": [
      {"id": "d0000000-0000-0000-0000-000000000001", "user_id": "30000000-0000-0000-0000-000000000002", "week_start": "2026-08-31", "base_capacity_hrs": 40, "absence_hrs": 4, "available_hrs": 36, "created_at": "2026-09-01T08:00:00", "updated_at": "2026-09-01T08:00:00"}
    ]
  }
}
```

- [ ] **Step 2: Write the failing tests**

```java
package com.workloadhub.forecast.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportFilesTest {

    static Path fixture() {
        return Path.of("src/test/resources/fixtures/mini-export.json");
    }

    @Test
    void readsEnvelopeAndKeepsJsonTypes() throws IOException {
        ExportEnvelope env = ExportFiles.read(fixture());
        assertEquals("task_service", env.schema());
        assertEquals(List.of("refresh_tokens"), env.excludedTables());
        assertEquals(13, env.data().size());
        var task = env.rows("tasks").get(0);
        assertEquals("CT2-CAL-1", task.get("key"));
        assertEquals(16L, task.get("original_estimate_hrs"));
        assertEquals(12.5, task.get("remaining_estimate_hrs"));
        assertEquals(Boolean.FALSE, task.get("archived"));
        assertNull(task.get("finished_date"));
        assertTrue(env.rows("no_such_table").isEmpty());
    }

    @Test
    void keepsColumnOrderOfTheFirstRow() throws IOException {
        ExportEnvelope env = ExportFiles.read(fixture());
        var keys = List.copyOf(env.rows("users").get(0).keySet());
        assertEquals(List.of("id", "role", "email", "active"), keys.subList(0, 4));
        assertEquals(List.of("user_roles", "job_titles", "users"), List.copyOf(env.data().keySet()).subList(0, 3));
    }

    @Test
    void fallsBackToWindows1252(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cp1252.json");
        String json = Files.readString(fixture(), StandardCharsets.UTF_8);
        Files.write(file, json.getBytes(Charset.forName("windows-1252")));
        ExportEnvelope env = ExportFiles.read(file);
        assertEquals("New Year’s Day", env.rows("holidays").get(0).get("title"));
    }

    @Test
    void writesAndReadsBackIdentically(@TempDir Path dir) throws IOException {
        ExportEnvelope env = ExportFiles.read(fixture());
        Path out = dir.resolve("out.json");
        ExportFiles.write(out, env);
        assertEquals(env, ExportFiles.read(out));
        assertTrue(Files.readString(out).startsWith("{\n"), "pretty printed");
    }
}
```

- [ ] **Step 3: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest=ExportFilesTest`
Expected: compilation errors, `ExportEnvelope` and `ExportFiles` do not exist.

- [ ] **Step 4: Write `ExportEnvelope`**

```java
package com.workloadhub.forecast.data;

import java.util.LinkedHashMap;
import java.util.List;

/** The WorkloadHub JSON export: metadata plus every table's rows, in file order. */
public record ExportEnvelope(
        String database,
        String schema,
        String exportedAt,
        List<String> excludedTables,
        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data) {

    public ExportEnvelope {
        excludedTables = excludedTables == null ? List.of() : List.copyOf(excludedTables);
        data = data == null ? new LinkedHashMap<>() : data;
    }

    /** The rows of one table, or an empty list when the export has none. */
    public List<LinkedHashMap<String, Object>> rows(String table) {
        return data.getOrDefault(table, List.of());
    }

    public ExportEnvelope withData(LinkedHashMap<String, List<LinkedHashMap<String, Object>>> newData) {
        return new ExportEnvelope(database, schema, exportedAt, excludedTables, newData);
    }
}
```

- [ ] **Step 5: Write `ExportFiles`**

```java
package com.workloadhub.forecast.data;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Reads and writes the export envelope. Numbers become Long or Double, nothing else. */
public final class ExportFiles {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private ExportFiles() {
    }

    public static JsonMapper mapper() {
        return MAPPER;
    }

    /** Reads a file as UTF-8, or as Windows-1252 when the bytes are not valid UTF-8 (the real export is). */
    public static ExportEnvelope read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            text = new String(bytes, Charset.forName("windows-1252"));
        }
        return parse(text);
    }

    public static ExportEnvelope parse(String json) {
        JsonNode root = MAPPER.readTree(json);
        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data = new LinkedHashMap<>();
        JsonNode tables = root.path("data");
        for (Iterator<Map.Entry<String, JsonNode>> it = tables.properties().iterator(); it.hasNext();) {
            Map.Entry<String, JsonNode> entry = it.next();
            List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
            for (JsonNode row : entry.getValue()) {
                LinkedHashMap<String, Object> map = new LinkedHashMap<>();
                for (Iterator<Map.Entry<String, JsonNode>> f = row.properties().iterator(); f.hasNext();) {
                    Map.Entry<String, JsonNode> field = f.next();
                    map.put(field.getKey(), scalar(field.getValue()));
                }
                rows.add(map);
            }
            data.put(entry.getKey(), rows);
        }
        List<String> excluded = new ArrayList<>();
        for (JsonNode n : root.path("excluded_tables")) {
            excluded.add(n.asString());
        }
        return new ExportEnvelope(
                text(root, "database"), text(root, "schema"), text(root, "exported_at"), excluded, data);
    }

    static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return n == null || n.isNull() ? null : n.asString();
    }

    static Object scalar(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isIntegralNumber()) {
            return n.asLong();
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        return n.asString();
    }

    public static String toJson(ExportEnvelope envelope) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("database", envelope.database());
        root.put("schema", envelope.schema());
        root.put("exported_at", envelope.exportedAt());
        root.put("excluded_tables", envelope.excludedTables());
        root.put("data", envelope.data());
        return MAPPER.writeValueAsString(root);
    }

    public static void write(Path file, ExportEnvelope envelope) throws IOException {
        Files.writeString(file, toJson(envelope), StandardCharsets.UTF_8);
    }
}
```

If `JsonNode.properties()` or `asString()` do not exist in the Jackson 3 build on the classpath, use `n.fields()` and `n.asText()` (the Jackson 2 names) instead; both builds expose one of the pairs.

- [ ] **Step 6: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest=ExportFilesTest`
Expected: 4 passed.

- [ ] **Step 7: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/data server/forecast-core/src/test/java/com/workloadhub/forecast/data server/forecast-core/src/test/resources/fixtures/mini-export.json
git commit -m "feat(server): read and write the WorkloadHub export envelope

The application's JSON export becomes an in-memory envelope with JSON
types preserved and a Windows-1252 fallback, because the owner's real
export is not UTF-8."
```

---

### Task 4: Import an export into the database and export it back

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/ExportImporter.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/ExportExporter.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/data/RoundTripTest.java`

**Interfaces:**
- Consumes: `ExportEnvelope`, `Dialect`, `WorkloadHubSchema.TABLE_ORDER`, `WorkloadHubSchema.isBoolean`, `DatabaseTestSupport`.
- Produces: `new ExportImporter(DataSource).importAll(ExportEnvelope, boolean replace)` returning `Map<String, Integer>` rows inserted per table; `new ExportExporter(DataSource).exportAll()` returning an `ExportEnvelope` with `database`, `schema` and `exportedAt` filled and every table of `TABLE_ORDER` present (empty lists allowed).
- Values written by the exporter: timestamps as `LocalDateTime.toString()`, dates as `LocalDate.toString()`, times as `LocalTime.toString()`, UUIDs as strings, booleans as `Boolean` for the columns in `BOOLEAN_COLUMNS`, other numbers as `Long` when integral on an integer column and `Double` otherwise.

- [ ] **Step 1: Write the failing test**

```java
package com.workloadhub.forecast.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class RoundTripTest {

    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");

    /** Rows compared with numbers as doubles and timestamps parsed, so 16 and 16.0 or .54207 and .542070 are equal. */
    static Map<String, List<TreeMap<String, Object>>> canonical(ExportEnvelope env) {
        Map<String, List<TreeMap<String, Object>>> out = new TreeMap<>();
        env.data().forEach((table, rows) -> out.put(table, rows.stream().map(row -> {
            TreeMap<String, Object> m = new TreeMap<>();
            row.forEach((k, v) -> m.put(k, canonicalValue(v)));
            return m;
        }).sorted((a, b) -> String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id")))).toList()));
        return out;
    }

    static Object canonicalValue(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s && TIMESTAMP.matcher(s).matches()) {
            return LocalDateTime.parse(s);
        }
        return v;
    }

    static void roundTrip(DataSource ds) throws Exception {
        ExportEnvelope in = ExportFiles.read(Path.of("src/test/resources/fixtures/mini-export.json"));
        Map<String, Integer> counts = new ExportImporter(ds).importAll(in, true);
        assertEquals(2, counts.get("users"));
        assertEquals(1, counts.get("tasks"));
        ExportEnvelope out = new ExportExporter(ds).exportAll();
        assertEquals(WorkloadHubSchema.TABLE_ORDER.size(), out.data().size());
        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> onlyFixtureTables = new LinkedHashMap<>();
        in.data().keySet().forEach(t -> onlyFixtureTables.put(t, out.rows(t)));
        assertEquals(canonical(in), canonical(out.withData(onlyFixtureTables)));
        // importing again with replace=true leaves the same row counts
        assertEquals(counts, new ExportImporter(ds).importAll(in, true));
    }

    @Test
    void roundTripsOnSqlite() throws Exception {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        roundTrip(ds);
    }

    @Test
    void roundTripsOnPostgresql() throws Exception {
        DataSource ds = DatabaseTestSupport.postgresOrSkip();
        WorkloadHubSchema.createPostgresql(ds);
        roundTrip(ds);
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest=RoundTripTest`
Expected: compilation errors, `ExportImporter` and `ExportExporter` do not exist.

- [ ] **Step 3: Write `ExportImporter`**

```java
package com.workloadhub.forecast.data;

import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;

/** Inserts an export's rows table by table, parents first, through plain JDBC on either engine. */
public final class ExportImporter {

    private final DataSource dataSource;
    private final Dialect dialect;

    public ExportImporter(DataSource dataSource) {
        this.dataSource = dataSource;
        this.dialect = Dialect.of(dataSource);
    }

    /** Column name to database type name, in table order, from the driver's metadata. */
    static LinkedHashMap<String, String> columns(Connection c, String table) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        try (ResultSet rs = md.getColumns(null, null, table, "%")) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (schema != null && !schema.equals("task_service") && !schema.equals("main")) {
                    continue;
                }
                out.put(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), rs.getString("TYPE_NAME"));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Table not found: " + table);
        }
        return out;
    }

    /** Inserts every table of the envelope that exists in the schema; with replace, deletes children then parents first. */
    public Map<String, Integer> importAll(ExportEnvelope envelope, boolean replace) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            if (replace) {
                List<String> reverse = new ArrayList<>(WorkloadHubSchema.TABLE_ORDER);
                java.util.Collections.reverse(reverse);
                try (Statement st = c.createStatement()) {
                    for (String table : reverse) {
                        st.execute("DELETE FROM " + table);
                    }
                }
            }
            for (String table : WorkloadHubSchema.TABLE_ORDER) {
                List<LinkedHashMap<String, Object>> rows = envelope.rows(table);
                counts.put(table, rows.isEmpty() ? 0 : insert(c, table, rows));
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Import failed: " + e.getMessage(), e);
        }
        return counts;
    }

    private int insert(Connection c, String table, List<LinkedHashMap<String, Object>> rows) throws SQLException {
        LinkedHashMap<String, String> schemaColumns = columns(c, table);
        List<String> cols = new ArrayList<>();
        for (String col : schemaColumns.keySet()) {
            if (rows.stream().anyMatch(r -> r.containsKey(col))) {
                cols.add(col);
            }
        }
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (")
                .append(String.join(", ", cols)).append(") VALUES (");
        for (int i = 0; i < cols.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append(dialect.placeholder(schemaColumns.get(cols.get(i))));
        }
        sql.append(")");
        int n = 0;
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (LinkedHashMap<String, Object> row : rows) {
                for (int i = 0; i < cols.size(); i++) {
                    bind(ps, i + 1, table, cols.get(i), row.get(cols.get(i)));
                }
                ps.addBatch();
                n++;
                if (n % 500 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        return n;
    }

    private void bind(PreparedStatement ps, int index, String table, String column, Object value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NULL);
        } else if (value instanceof Boolean b) {
            ps.setObject(index, dialect.bool(b));
        } else if (WorkloadHubSchema.isBoolean(table, column) && value instanceof Number num) {
            ps.setObject(index, dialect.bool(num.intValue() != 0));
        } else if (value instanceof Long l) {
            ps.setLong(index, l);
        } else if (value instanceof Double d) {
            ps.setDouble(index, d);
        } else if (value instanceof Number num) {
            ps.setDouble(index, num.doubleValue());
        } else {
            ps.setString(index, value.toString());
        }
    }
}
```

On PostgreSQL `ps.setNull(index, Types.NULL)` inside a `CAST(? AS uuid)` is accepted; if the driver complains about an unknown type for a NULL, use `ps.setObject(index, null)` instead.

- [ ] **Step 4: Write `ExportExporter`**

```java
package com.workloadhub.forecast.data;

import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;

/** Reads every WorkloadHub table back into an envelope with JSON-shaped values. */
public final class ExportExporter {

    private final DataSource dataSource;
    private final Dialect dialect;

    public ExportExporter(DataSource dataSource) {
        this.dataSource = dataSource;
        this.dialect = Dialect.of(dataSource);
    }

    public ExportEnvelope exportAll() {
        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String table : WorkloadHubSchema.TABLE_ORDER) {
                data.put(table, readTable(st, table));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Export failed: " + e.getMessage(), e);
        }
        String db = dialect == Dialect.SQLITE ? "sqlite" : "postgresql";
        return new ExportEnvelope(db, "task_service", LocalDateTime.now().withNano(0).toString(),
                List.of(), data);
    }

    private List<LinkedHashMap<String, Object>> readTable(Statement st, String table) throws SQLException {
        List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT * FROM " + table + " ORDER BY 1")) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            while (rs.next()) {
                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) {
                    String col = md.getColumnLabel(i).toLowerCase(Locale.ROOT);
                    row.put(col, jsonValue(table, col, rs.getObject(i)));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    Object jsonValue(String table, String column, Object v) {
        if (v == null) {
            return null;
        }
        if (WorkloadHubSchema.isBoolean(table, column)) {
            return dialect.asBoolean(v);
        }
        if (v instanceof Timestamp ts) {
            return ts.toLocalDateTime().toString();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof java.sql.Time t) {
            return t.toLocalTime().toString();
        }
        if (v instanceof UUID u) {
            return u.toString();
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Short) {
            return ((Number) v).longValue();
        }
        if (v instanceof Number num) {
            double d = num.doubleValue();
            return d;
        }
        return v.toString();
    }
}
```

- [ ] **Step 5: Run the test**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest=RoundTripTest`
Expected: `roundTripsOnSqlite` passes; `roundTripsOnPostgresql` passes with Docker, skipped without. If the SQLite run fails on `remaining_estimate_hrs` (12.5 read back as `12.5` but `original_estimate_hrs` as `16.0`), the canonical comparison already maps both to doubles; a failure means a column was dropped, so print `counts` and the differing table.

- [ ] **Step 6: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/data server/forecast-core/src/test/java/com/workloadhub/forecast/data
git commit -m "feat(server): import an export into the database and export it back

Generic JDBC import in parent-first order with typed placeholders per
engine, and an exporter that yields the same JSON shapes; the round-trip
test runs on SQLite always and on PostgreSQL when Docker is present."
```

---

### Task 5: The module's tables, migrations and the encrypted GitHub token store

**Files:**
- Create: `server/forecast-core/src/main/resources/db/forecast/postgresql/V1__forecast_tables.sql`
- Create: `server/forecast-core/src/main/resources/db/forecast/sqlite/V1__forecast_tables.sql`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/store/ForecastMigrations.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/store/AesGcmCipher.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/store/JdbcGitHubTokenStore.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/api/GitHubTokenStore.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/api/ForecastException.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/store/ForecastMigrationsTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/store/AesGcmCipherTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/store/JdbcGitHubTokenStoreTest.java`

**Interfaces:**
- Produces: `ForecastMigrations.run(DataSource)` (idempotent; creates `forecast_runs`, `forecast_member_weeks`, `forecast_facts`, `forecast_narratives`, adds `users.github_token` and `users.github_token_updated_at`, records in `forecast_schema_history`); `AesGcmCipher.fromBase64Key(String)`, `new AesGcmCipher(byte[] key32)`, `String encrypt(String)`, `String decrypt(String)`; `interface GitHubTokenStore { void save(UUID userId, String token); Optional<String> load(UUID userId); boolean has(UUID userId); void clear(UUID userId); }`; `class ForecastException extends RuntimeException` with `String code()` and static factories `invalidRequest(String)`, `of(String code, String message)`; codes used here: `INVALID_REQUEST`, `TOKEN_KEY_MISSING`, `USER_NOT_FOUND`.

- [ ] **Step 1: Write the failing tests**

`AesGcmCipherTest.java`:

```java
package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class AesGcmCipherTest {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void ciphertextIsVersionedAndRandomised() {
        AesGcmCipher c = AesGcmCipher.fromBase64Key(KEY);
        String a = c.encrypt("gho_abc");
        String b = c.encrypt("gho_abc");
        assertTrue(a.startsWith("v1:"));
        assertNotEquals(a, b, "fresh nonce per value");
        assertEquals("gho_abc", c.decrypt(a));
        assertEquals("gho_abc", c.decrypt(b));
    }

    @Test
    void rejectsWrongKeyLength() {
        assertThrows(IllegalArgumentException.class, () -> new AesGcmCipher(new byte[16]));
    }

    @Test
    void tamperedValueFails() {
        AesGcmCipher c = AesGcmCipher.fromBase64Key(KEY);
        String enc = c.encrypt("gho_abc");
        String tampered = enc.substring(0, enc.length() - 2) + "AA";
        assertThrows(IllegalStateException.class, () -> c.decrypt(tampered));
    }

    @Property
    boolean roundTripsAnyString(@ForAll String s) {
        AesGcmCipher c = AesGcmCipher.fromBase64Key(KEY);
        return s.equals(c.decrypt(c.encrypt(s)));
    }
}
```

`ForecastMigrationsTest.java`:

```java
package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ForecastMigrationsTest {

    static TreeSet<String> tables(DataSource ds) throws Exception {
        TreeSet<String> out = new TreeSet<>();
        try (Connection c = ds.getConnection(); ResultSet rs = c.getMetaData().getTables(null, null, "forecast_%", null)) {
            while (rs.next()) {
                out.add(rs.getString("TABLE_NAME"));
            }
        }
        return out;
    }

    static boolean hasColumn(DataSource ds, String table, String column) throws Exception {
        try (Connection c = ds.getConnection(); ResultSet rs = c.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        }
    }

    static void check(DataSource ds) throws Exception {
        ForecastMigrations.run(ds);
        ForecastMigrations.run(ds); // idempotent
        assertEquals(new TreeSet<>(java.util.List.of("forecast_facts", "forecast_member_weeks", "forecast_narratives",
                "forecast_runs", "forecast_schema_history")), tables(ds));
        assertTrue(hasColumn(ds, "users", "github_token"));
        assertTrue(hasColumn(ds, "users", "github_token_updated_at"));
    }

    @Test
    void migratesSqlite() throws Exception {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        check(ds);
    }

    @Test
    void migratesPostgresql() throws Exception {
        DataSource ds = DatabaseTestSupport.postgresOrSkip();
        WorkloadHubSchema.createPostgresql(ds);
        check(ds);
    }
}
```

`JdbcGitHubTokenStoreTest.java`:

```java
package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.data.ExportImporter;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcGitHubTokenStoreTest {

    static final UUID ENG = UUID.fromString("30000000-0000-0000-0000-000000000002");

    static DataSource sqliteWithFixture() throws Exception {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        new ExportImporter(ds).importAll(ExportFiles.read(Path.of("src/test/resources/fixtures/mini-export.json")), true);
        ForecastMigrations.run(ds);
        return ds;
    }

    static JdbcGitHubTokenStore store(DataSource ds) {
        return new JdbcGitHubTokenStore(JdbcClient.create(ds), Dialect.of(ds),
                AesGcmCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])));
    }

    @Test
    void savesEncryptedAndLoadsForTheUser() throws Exception {
        DataSource ds = sqliteWithFixture();
        JdbcGitHubTokenStore s = store(ds);
        assertFalse(s.has(ENG));
        s.save(ENG, "gho_secret123");
        assertTrue(s.has(ENG));
        assertEquals("gho_secret123", s.load(ENG).orElseThrow());
        String stored = JdbcClient.create(ds).sql("SELECT github_token FROM users WHERE id = ?").param(ENG.toString()).query(String.class).single();
        assertTrue(stored.startsWith("v1:"));
        assertNotEquals("gho_secret123", stored);
        s.clear(ENG);
        assertFalse(s.has(ENG));
    }

    @Test
    void refusesClassicTokensAndUnknownUsers() throws Exception {
        JdbcGitHubTokenStore s = store(sqliteWithFixture());
        ForecastException classic = assertThrows(ForecastException.class, () -> s.save(ENG, "ghp_old"));
        assertEquals("INVALID_REQUEST", classic.code());
        ForecastException unknown = assertThrows(ForecastException.class, () -> s.save(UUID.randomUUID(), "gho_x"));
        assertEquals("USER_NOT_FOUND", unknown.code());
    }

    @Test
    void withoutKeyEveryCallFails() throws Exception {
        DataSource ds = sqliteWithFixture();
        JdbcGitHubTokenStore s = new JdbcGitHubTokenStore(JdbcClient.create(ds), Dialect.of(ds), null);
        assertEquals("TOKEN_KEY_MISSING", assertThrows(ForecastException.class, () -> s.save(ENG, "gho_x")).code());
        assertEquals("TOKEN_KEY_MISSING", assertThrows(ForecastException.class, () -> s.load(ENG)).code());
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='AesGcmCipherTest,ForecastMigrationsTest,JdbcGitHubTokenStoreTest'`
Expected: compilation errors for the missing classes.

- [ ] **Step 3: Write the two migrations**

`db/forecast/postgresql/V1__forecast_tables.sql`:

```sql
CREATE TABLE forecast_runs (
  id              uuid PRIMARY KEY,
  team_id         uuid NOT NULL,
  requested_by    uuid NOT NULL,
  as_of           date NOT NULL,
  status          varchar(16) NOT NULL,
  forced_model    varchar(32),
  champion_model  varchar(32),
  champion_mase   double precision,
  backtest_json   text,
  error           text,
  created_at      timestamp NOT NULL,
  finished_at     timestamp
);
CREATE INDEX forecast_runs_team_idx ON forecast_runs (team_id, created_at);

CREATE TABLE forecast_member_weeks (
  run_id          uuid NOT NULL REFERENCES forecast_runs(id),
  user_id         uuid NOT NULL,
  week_start      date NOT NULL,
  open_hrs        double precision NOT NULL,
  new_hrs         double precision NOT NULL,
  planned_hrs     double precision NOT NULL,
  low_hrs         double precision NOT NULL,
  high_hrs        double precision NOT NULL,
  capacity_hrs    double precision NOT NULL,
  overload_hrs    double precision NOT NULL,
  working_days    integer NOT NULL,
  absence_hrs     double precision NOT NULL,
  PRIMARY KEY (run_id, user_id, week_start)
);

CREATE TABLE forecast_facts (
  run_id          uuid PRIMARY KEY REFERENCES forecast_runs(id),
  facts_json      text NOT NULL,
  created_at      timestamp NOT NULL
);

CREATE TABLE forecast_narratives (
  id                uuid PRIMARY KEY,
  run_id            uuid NOT NULL REFERENCES forecast_runs(id),
  language          varchar(2) NOT NULL,
  model             varchar(64),
  narrative_json    text NOT NULL,
  verification_json text NOT NULL,
  usage_json        text NOT NULL,
  created_at        timestamp NOT NULL
);
CREATE INDEX forecast_narratives_run_idx ON forecast_narratives (run_id, language, created_at);

ALTER TABLE users ADD COLUMN github_token varchar(512);
ALTER TABLE users ADD COLUMN github_token_updated_at timestamp;
```

`db/forecast/sqlite/V1__forecast_tables.sql`:

```sql
CREATE TABLE forecast_runs (
  id              TEXT PRIMARY KEY,
  team_id         TEXT NOT NULL,
  requested_by    TEXT NOT NULL,
  as_of           TEXT NOT NULL,
  status          TEXT NOT NULL,
  forced_model    TEXT,
  champion_model  TEXT,
  champion_mase   REAL,
  backtest_json   TEXT,
  error           TEXT,
  created_at      TEXT NOT NULL,
  finished_at     TEXT
);
CREATE INDEX forecast_runs_team_idx ON forecast_runs (team_id, created_at);

CREATE TABLE forecast_member_weeks (
  run_id          TEXT NOT NULL REFERENCES forecast_runs(id),
  user_id         TEXT NOT NULL,
  week_start      TEXT NOT NULL,
  open_hrs        REAL NOT NULL,
  new_hrs         REAL NOT NULL,
  planned_hrs     REAL NOT NULL,
  low_hrs         REAL NOT NULL,
  high_hrs        REAL NOT NULL,
  capacity_hrs    REAL NOT NULL,
  overload_hrs    REAL NOT NULL,
  working_days    INTEGER NOT NULL,
  absence_hrs     REAL NOT NULL,
  PRIMARY KEY (run_id, user_id, week_start)
);

CREATE TABLE forecast_facts (
  run_id          TEXT PRIMARY KEY REFERENCES forecast_runs(id),
  facts_json      TEXT NOT NULL,
  created_at      TEXT NOT NULL
);

CREATE TABLE forecast_narratives (
  id                TEXT PRIMARY KEY,
  run_id            TEXT NOT NULL REFERENCES forecast_runs(id),
  language          TEXT NOT NULL,
  model             TEXT,
  narrative_json    TEXT NOT NULL,
  verification_json TEXT NOT NULL,
  usage_json        TEXT NOT NULL,
  created_at        TEXT NOT NULL
);
CREATE INDEX forecast_narratives_run_idx ON forecast_narratives (run_id, language, created_at);

ALTER TABLE users ADD COLUMN github_token TEXT;
ALTER TABLE users ADD COLUMN github_token_updated_at TEXT;
```

- [ ] **Step 4: Write `ForecastMigrations`**

```java
package com.workloadhub.forecast.store;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Creates and upgrades the module's own tables, with a history table the host's migrations never see. */
public final class ForecastMigrations {

    public static final String HISTORY_TABLE = "forecast_schema_history";

    private ForecastMigrations() {
    }

    public static void run(DataSource dataSource) {
        Dialect dialect = Dialect.of(dataSource);
        var config = Flyway.configure()
                .dataSource(dataSource)
                .locations(dialect.flywayLocation())
                .table(HISTORY_TABLE)
                .baselineOnMigrate(false)
                .validateOnMigrate(true);
        if (dialect == Dialect.POSTGRESQL) {
            config = config.schemas("task_service").defaultSchema("task_service");
        }
        config.load().migrate();
    }
}
```

If Flyway reports `Unsupported Database: SQLite` with the managed Flyway version, add the dependency `<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-sqlite</artifactId></dependency>` (no version, if Boot manages it) to the core POM; if that artifact does not exist for the managed version, replace the SQLite branch with a direct runner: create `forecast_schema_history(version TEXT PRIMARY KEY, description TEXT, installed_on TEXT)` if absent, and for each `V<n>__*.sql` resource under `db/forecast/sqlite/` not yet in the table, execute it through `WorkloadHubSchema.runScript` and insert its row, all inside one transaction. Keep Flyway for PostgreSQL either way.

- [ ] **Step 5: Write `ForecastException` and `GitHubTokenStore`**

```java
package com.workloadhub.forecast.api;

/** An error the host can act on: a stable code plus a message safe to show. */
public final class ForecastException extends RuntimeException {

    private final String code;

    public ForecastException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ForecastException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ForecastException of(String code, String message) {
        return new ForecastException(code, message);
    }

    public static ForecastException invalidRequest(String message) {
        return new ForecastException("INVALID_REQUEST", message);
    }
}
```

```java
package com.workloadhub.forecast.api;

import java.util.Optional;
import java.util.UUID;

/** Where each user's GitHub token lives: encrypted on the users table, read only for that user. */
public interface GitHubTokenStore {

    /** Stores a gho_, ghu_ or github_pat_ token; refuses classic ghp_ tokens and unknown users. */
    void save(UUID userId, String token);

    Optional<String> load(UUID userId);

    boolean has(UUID userId);

    void clear(UUID userId);
}
```

- [ ] **Step 6: Write `AesGcmCipher`**

```java
package com.workloadhub.forecast.store;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM with a random 96-bit nonce per value; output is "v1:" + base64(nonce || ciphertext). */
public final class AesGcmCipher {

    private static final String PREFIX = "v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public AesGcmCipher(byte[] key32) {
        if (key32 == null || key32.length != 32) {
            throw new IllegalArgumentException("whf.token-key must decode to 32 bytes");
        }
        this.key = new SecretKeySpec(key32, "AES");
    }

    public static AesGcmCipher fromBase64Key(String base64) {
        return new AesGcmCipher(Base64.getDecoder().decode(base64.trim()));
    }

    public String encrypt(String plain) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ct, 0, out, nonce.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Unknown token format");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] nonce = Arrays.copyOfRange(all, 0, NONCE_BYTES);
            byte[] ct = Arrays.copyOfRange(all, NONCE_BYTES, all.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
```

- [ ] **Step 7: Write `JdbcGitHubTokenStore`**

```java
package com.workloadhub.forecast.store;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.GitHubTokenStore;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** users.github_token, encrypted; the module reads it in exactly one place, for the requesting user. */
public final class JdbcGitHubTokenStore implements GitHubTokenStore {

    private final JdbcClient jdbc;
    private final Dialect dialect;
    private final AesGcmCipher cipher; // null when whf.token-key is not configured

    public JdbcGitHubTokenStore(JdbcClient jdbc, Dialect dialect, AesGcmCipher cipher) {
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.cipher = cipher;
    }

    private AesGcmCipher cipherOrFail() {
        if (cipher == null) {
            throw ForecastException.of("TOKEN_KEY_MISSING", "whf.token-key is not configured; tokens cannot be stored or read");
        }
        return cipher;
    }

    private String idPlaceholder() {
        return dialect.placeholder("uuid");
    }

    @Override
    public void save(UUID userId, String token) {
        AesGcmCipher c = cipherOrFail();
        if (token == null || token.isBlank()) {
            throw ForecastException.invalidRequest("token is empty");
        }
        String t = token.trim();
        if (!(t.startsWith("gho_") || t.startsWith("ghu_") || t.startsWith("github_pat_"))) {
            throw ForecastException.invalidRequest("token must be a gho_, ghu_ or github_pat_ token; classic ghp_ tokens are not accepted");
        }
        int updated = jdbc.sql("UPDATE users SET github_token = ?, github_token_updated_at = " + dialect.placeholder("timestamp")
                        + " WHERE id = " + idPlaceholder())
                .param(c.encrypt(t))
                .param(LocalDateTime.now().withNano(0).toString())
                .param(userId.toString())
                .update();
        if (updated == 0) {
            throw ForecastException.of("USER_NOT_FOUND", "No user " + userId);
        }
    }

    @Override
    public Optional<String> load(UUID userId) {
        AesGcmCipher c = cipherOrFail();
        return jdbc.sql("SELECT github_token FROM users WHERE id = " + idPlaceholder())
                .param(userId.toString())
                .query(String.class)
                .optional()
                .filter(v -> v != null && !v.isBlank())
                .map(c::decrypt);
    }

    @Override
    public boolean has(UUID userId) {
        return jdbc.sql("SELECT github_token FROM users WHERE id = " + idPlaceholder())
                .param(userId.toString())
                .query(String.class)
                .optional()
                .filter(v -> v != null && !v.isBlank())
                .isPresent();
    }

    @Override
    public void clear(UUID userId) {
        jdbc.sql("UPDATE users SET github_token = NULL, github_token_updated_at = NULL WHERE id = " + idPlaceholder())
                .param(userId.toString())
                .update();
    }
}
```

`JdbcClient.query(String.class).optional()` returns `Optional.empty()` for no row and an `Optional` of a null-valued column on some drivers; the `filter` handles both.

- [ ] **Step 8: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='AesGcmCipherTest,ForecastMigrationsTest,JdbcGitHubTokenStoreTest'`
Expected: all pass; `migratesPostgresql` skipped without Docker.

- [ ] **Step 9: Commit**

```bash
git add server/forecast-core/src/main/resources/db server/forecast-core/src/main/java/com/workloadhub/forecast/store server/forecast-core/src/main/java/com/workloadhub/forecast/api server/forecast-core/src/test/java/com/workloadhub/forecast/store
git commit -m "feat(server): module tables, migrations and the encrypted GitHub token store

Flyway creates the forecast_ tables and the two users columns under a
separate history table; tokens are AES-256-GCM encrypted under
whf.token-key, classic ghp_ tokens are refused, and only the requesting
user's token is ever read."
```

---

### Task 6: Auto-configuration, properties, and the command line with init-db, import and export

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/ForecastProperties.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/ForecastAutoConfiguration.java`
- Create: `server/forecast-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/ForecastAutoConfigurationTest.java`
- Create: `server/forecast-cli/pom.xml`
- Create: `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/ForecastCli.java`
- Create: `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/DbOptions.java`
- Create: `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/InitDbCommand.java`
- Create: `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/ImportCommand.java`
- Create: `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/ExportCommand.java`
- Create: `server/forecast-cli/src/main/resources/application.yml`
- Create: `server/forecast-cli/src/test/java/com/workloadhub/forecast/cli/CliSmokeTest.java`
- Modify: `server/pom.xml` (add `<module>forecast-cli</module>`)

**Interfaces:**
- Produces: `ForecastProperties` (`whf.token-key`, `whf.default-weekly-hours` 40, `whf.run-threads` 2, `whf.planned-work.enabled` true, `whf.copilot.model`, `whf.copilot.cli-path`, `whf.copilot.timeout-seconds` 300, `whf.web.enabled` false, `whf.web.base-path` `/api/forecast`, `whf.flyway.enabled` true); beans `Dialect`, `GitHubTokenStore`, and `ForecastMigrations` run at startup when `whf.flyway.enabled`; the CLI entry `java -jar forecast-cli/target/workloadhub-forecast-cli-0.1.0-SNAPSHOT.jar <command>` with `--db <path>` (default `./workloadhub.db`).

- [ ] **Step 1: Write the failing auto-configuration test**

```java
package com.workloadhub.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.util.Base64;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ForecastAutoConfigurationTest {

    static final DataSource DS = DatabaseTestSupport.sqliteInMemory();

    @Configuration
    static class HostConfig {
        @Bean
        DataSource dataSource() {
            return DS;
        }
    }

    @Test
    void registersBeansAndRunsMigrations() {
        WorkloadHubSchema.createSqlite(DS);
        new ApplicationContextRunner()
                .withUserConfiguration(HostConfig.class)
                .withConfiguration(AutoConfigurations.of(ForecastAutoConfiguration.class))
                .withPropertyValues("whf.token-key=" + Base64.getEncoder().encodeToString(new byte[32]))
                .run(ctx -> {
                    assertEquals(Dialect.SQLITE, ctx.getBean(Dialect.class));
                    assertNotNull(ctx.getBean(GitHubTokenStore.class));
                    ForecastProperties p = ctx.getBean(ForecastProperties.class);
                    assertEquals(40.0, p.getDefaultWeeklyHours());
                    assertTrue(p.getFlyway().isEnabled());
                    // migrations ran: the users table has the token column
                    var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(DS);
                    jdbc.sql("SELECT github_token FROM users WHERE 1 = 0").query().listOfRows();
                });
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest=ForecastAutoConfigurationTest`
Expected: compilation errors for `ForecastProperties` and `ForecastAutoConfiguration`.

- [ ] **Step 3: Write `ForecastProperties`**

```java
package com.workloadhub.forecast;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Every whf.* property, with the defaults of the design. */
@ConfigurationProperties(prefix = "whf")
public class ForecastProperties {

    private String tokenKey;
    private double defaultWeeklyHours = 40.0;
    private int runThreads = 2;
    private final PlannedWork plannedWork = new PlannedWork();
    private final Copilot copilot = new Copilot();
    private final Web web = new Web();
    private final Flyway flyway = new Flyway();

    public String getTokenKey() { return tokenKey; }
    public void setTokenKey(String tokenKey) { this.tokenKey = tokenKey; }
    public double getDefaultWeeklyHours() { return defaultWeeklyHours; }
    public void setDefaultWeeklyHours(double v) { this.defaultWeeklyHours = v; }
    public int getRunThreads() { return runThreads; }
    public void setRunThreads(int runThreads) { this.runThreads = runThreads; }
    public PlannedWork getPlannedWork() { return plannedWork; }
    public Copilot getCopilot() { return copilot; }
    public Web getWeb() { return web; }
    public Flyway getFlyway() { return flyway; }

    public static class PlannedWork {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Copilot {
        private String model = "";
        private String cliPath = "";
        private int timeoutSeconds = 300;
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getCliPath() { return cliPath; }
        public void setCliPath(String cliPath) { this.cliPath = cliPath; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public static class Web {
        private boolean enabled = false;
        private String basePath = "/api/forecast";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBasePath() { return basePath; }
        public void setBasePath(String basePath) { this.basePath = basePath; }
    }

    public static class Flyway {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
```

- [ ] **Step 4: Write `ForecastAutoConfiguration` and the imports file**

```java
package com.workloadhub.forecast;

import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.store.AesGcmCipher;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.ForecastMigrations;
import com.workloadhub.forecast.store.JdbcGitHubTokenStore;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Registers the module's beans on top of the host's DataSource; nothing else is required of the host. */
@AutoConfiguration
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(ForecastProperties.class)
public class ForecastAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Dialect forecastDialect(DataSource dataSource) {
        return Dialect.of(dataSource);
    }

    /** Runs the module's migrations before any other bean touches the tables. */
    @Bean
    ForecastMigrationsRunner forecastMigrationsRunner(DataSource dataSource, ForecastProperties properties) {
        if (properties.getFlyway().isEnabled()) {
            ForecastMigrations.run(dataSource);
        }
        return new ForecastMigrationsRunner();
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcClient forecastJdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    GitHubTokenStore gitHubTokenStore(JdbcClient jdbc, Dialect dialect, ForecastProperties properties,
            ForecastMigrationsRunner migrated) {
        String key = properties.getTokenKey();
        AesGcmCipher cipher = key == null || key.isBlank() ? null : AesGcmCipher.fromBase64Key(key);
        return new JdbcGitHubTokenStore(jdbc, dialect, cipher);
    }

    /** Marker bean so that beans needing the tables can depend on the migrations having run. */
    public static final class ForecastMigrationsRunner {
    }
}
```

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```text
com.workloadhub.forecast.ForecastAutoConfiguration
```

If Spring Boot 4.1 reports that `@ConditionalOnBean(DataSource.class)` on an auto-configuration is evaluated before the host's DataSource auto-configuration, add `@AutoConfiguration(after = org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class)`; the class lives in `org.springframework.boot.jdbc.autoconfigure` in Boot 4 (it moved from `org.springframework.boot.autoconfigure.jdbc`).

- [ ] **Step 5: Run the auto-configuration test**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest=ForecastAutoConfigurationTest`
Expected: PASS.

- [ ] **Step 6: Write the CLI module**

`server/forecast-cli/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.workloadhub</groupId>
    <artifactId>workloadhub-forecast-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>workloadhub-forecast-cli</artifactId>
  <name>WorkloadHub forecast CLI</name>
  <dependencies>
    <dependency><groupId>com.workloadhub</groupId><artifactId>workloadhub-forecast-core</artifactId><version>${project.version}</version></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter</artifactId></dependency>
    <dependency><groupId>info.picocli</groupId><artifactId>picocli-spring-boot-starter</artifactId></dependency>
    <dependency><groupId>org.xerial</groupId><artifactId>sqlite-jdbc</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <configuration>
          <mainClass>com.workloadhub.forecast.cli.ForecastCli</mainClass>
        </configuration>
        <executions>
          <execution><goals><goal>repackage</goal></goals></execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

Add `<module>forecast-cli</module>` after `forecast-core` in `server/pom.xml`.

`application.yml`:

```yaml
spring:
  main:
    web-application-type: none
    banner-mode: off
logging:
  level:
    root: warn
    com.workloadhub.forecast: info
whf:
  flyway:
    enabled: false   # init-db runs the migrations explicitly, after creating the WorkloadHub tables
```

`DbOptions.java`:

```java
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
```

`ForecastCli.java`:

```java
package com.workloadhub.forecast.cli;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IFactory;

/** Entry point: `java -jar workloadhub-forecast-cli.jar <command> [options]`. */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class ForecastCli {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(ForecastCli.class, args)));
    }

    @Command(name = "forecast", mixinStandardHelpOptions = true, version = "0.1.0",
            description = "WorkloadHub forecast: database, seed, runs and narration from the terminal.",
            subcommands = {InitDbCommand.class, ImportCommand.class, ExportCommand.class})
    @Component
    public static class Root {
    }

    @Component
    public static class Runner implements CommandLineRunner, ExitCodeGenerator {
        private final IFactory factory;
        private final Root root;
        private int exitCode;

        public Runner(IFactory factory, Root root) {
            this.factory = factory;
            this.root = root;
        }

        @Override
        public void run(String... args) {
            exitCode = new CommandLine(root, factory).execute(args);
        }

        @Override
        public int getExitCode() {
            return exitCode;
        }
    }
}
```

The DataSource auto-configuration is excluded because every command builds its own SQLite DataSource from `--db`; the core auto-configuration is therefore inactive in the CLI, and commands call `ForecastMigrations` and the stores directly.

`InitDbCommand.java`:

```java
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
```

`ImportCommand.java`:

```java
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
```

`ExportCommand.java`:

```java
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
```

`CliSmokeTest.java`:

```java
package com.workloadhub.forecast.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CliSmokeTest {

    @Test
    void initImportExport(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("t.db");
        Path fixture = Path.of("../forecast-core/src/test/resources/fixtures/mini-export.json");
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        assertEquals(0, cli.execute("init-db", "--db", db.toString()));
        assertEquals(2, cli.execute("init-db", "--db", db.toString()), "refuses to overwrite without --force");
        assertEquals(0, cli.execute("import", "--db", db.toString(), fixture.toString()));
        Path out = dir.resolve("out.json");
        assertEquals(0, cli.execute("export", "--db", db.toString(), out.toString()));
        assertTrue(Files.readString(out).contains("\"CT2-CAL-1\""));
    }
}
```

- [ ] **Step 7: Build everything and run the CLI by hand**

Run: `cd server && mvn -B -q verify`
Expected: BUILD SUCCESS, `CliSmokeTest` passes.

Run:
```bash
cd server && java -jar forecast-cli/target/workloadhub-forecast-cli-0.1.0-SNAPSHOT.jar init-db --db /tmp/whf.db && \
java -jar forecast-cli/target/workloadhub-forecast-cli-0.1.0-SNAPSHOT.jar import --db /tmp/whf.db forecast-core/src/test/resources/fixtures/mini-export.json
```
Expected: "Created /tmp/whf.db ..." then a table of counts ending with "Imported 15 rows".

- [ ] **Step 8: Commit**

```bash
git add server/pom.xml server/forecast-core/src/main/java/com/workloadhub/forecast/ForecastProperties.java server/forecast-core/src/main/java/com/workloadhub/forecast/ForecastAutoConfiguration.java server/forecast-core/src/main/resources/META-INF server/forecast-core/src/test/java/com/workloadhub/forecast/ForecastAutoConfigurationTest.java server/forecast-cli
git commit -m "feat(server): auto-configuration, properties and a CLI with init-db, import and export

The host gets the module's beans from its DataSource and whf.* properties;
the CLI builds its own SQLite database per --db so a WSL user can create,
load and dump a database without Spring configuration."
```

---

### Task 7: Seed foundations: configuration, seeded randomness, work families and the directory-derived teams

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/SeedConfig.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/SeedRandom.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/WorkFamily.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/Person.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/Team.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/Directory.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/SeedRandomTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/WorkFamilyTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/DirectoryTest.java`

**Interfaces:**
- Produces:
  - `record SeedConfig(int weeks, LocalDate end, long seed, boolean synthetic, int users)` with `LocalDate firstMonday()` (= Monday of the week of `end`, minus `weeks − 1` weeks), `LocalDate lastDay()` (= `end`), `List<LocalDate> mondays()`.
  - `SeedRandom(long seed)`: `UUID uuid()`, `double uniform(double a, double b)`, `int between(int a, int b)` inclusive, `boolean chance(double p)`, `double gaussian()`, `double lognormal(double median, double sigma)`, `int poisson(double lambda)`, `<T> T pick(List<T> items, double[] weights)`, `<T> T pick(List<T> items)`, `LocalDateTime at(LocalDate day, int fromHour, int toHour)`.
  - `enum WorkFamily { CALIBRATION, DATA, SUPPORT, SYSTEMS, ELECTRONICS, VALIDATION, DESIGN, COORDINATION, UNKNOWN }` with `double delivery, defect, support, container` (type mix), `double medianEstimate`, `double selfPicked`, `double weeklyHours`, `static WorkFamily classify(String jobTitle)` (whole-word, case-insensitive keyword match in enum order; `UNKNOWN` when nothing matches or the title is blank), `boolean isUnknown()`.
  - `record Person(UUID id, String fullName, String email, String jobTitle, String department, String deptCode, UUID managerId, String role, WorkFamily family, LocalDate joined, LocalDate left)` (`left` null while employed).
  - `record Team(UUID id, String name, UUID managerId, UUID parentId, List<UUID> memberIds, boolean department, String deptCode)`.
  - `Directory.derive(List<LinkedHashMap<String, Object>> userRows, List<LinkedHashMap<String, Object>> teamRows, List<LinkedHashMap<String, Object>> memberRows, SeedConfig cfg, SeedRandom rnd)` returning `Directory.Result(List<Person> people, List<Team> teams, List<LinkedHashMap<String, Object>> userRows, List<LinkedHashMap<String, Object>> teamRows, List<LinkedHashMap<String, Object>> teamMemberRows)`; `static String deptCode(String department)` (`"PTE / CT2 Calibration & Testing 2"` → `"CT2"`, `"PTE / CSC- CETIEV ..."` → `"CSC"`, null or blank → null).
  - Counted people: `Person.role` is `MEMBER` or `TEAM_LEADER`.

- [ ] **Step 1: Write the failing tests**

`SeedRandomTest.java`:

```java
package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class SeedRandomTest {

    @Property
    boolean sameSeedSameSequence(@ForAll @LongRange(min = 0, max = 1_000_000) long seed) {
        SeedRandom a = new SeedRandom(seed);
        SeedRandom b = new SeedRandom(seed);
        return a.uuid().equals(b.uuid()) && a.poisson(3.0) == b.poisson(3.0)
                && a.lognormal(10, 0.6) == b.lognormal(10, 0.6) && a.between(1, 9) == b.between(1, 9);
    }

    @Property
    boolean uuidsAreVersion4(@ForAll @LongRange(min = 0, max = 1_000_000) long seed) {
        var u = new SeedRandom(seed).uuid();
        return u.version() == 4 && u.variant() == 2;
    }

    @Property
    boolean poissonMeanIsLambda(@ForAll @DoubleRange(min = 0.2, max = 40) double lambda) {
        SeedRandom r = new SeedRandom(1);
        double sum = 0;
        for (int i = 0; i < 4000; i++) {
            sum += r.poisson(lambda);
        }
        double mean = sum / 4000;
        return Math.abs(mean - lambda) < 0.15 * lambda + 0.1;
    }

    @Test
    void lognormalMedianIsTheMedian() {
        SeedRandom r = new SeedRandom(2);
        int below = 0;
        for (int i = 0; i < 4000; i++) {
            if (r.lognormal(12, 0.6) < 12) {
                below++;
            }
        }
        assertTrue(below > 1800 && below < 2200, "below median: " + below);
    }

    @Test
    void pickFollowsWeights() {
        SeedRandom r = new SeedRandom(3);
        int a = 0;
        for (int i = 0; i < 2000; i++) {
            if (r.pick(List.of("a", "b"), new double[] {0.8, 0.2}).equals("a")) {
                a++;
            }
        }
        assertTrue(a > 1500 && a < 1700, "a picked " + a);
    }

    @Test
    void timestampsFallInsideTheWindow() {
        SeedRandom r = new SeedRandom(4);
        var t = r.at(LocalDate.of(2026, 3, 4), 9, 11);
        assertEquals(LocalDate.of(2026, 3, 4), t.toLocalDate());
        assertTrue(t.getHour() >= 9 && t.getHour() < 11);
    }
}
```

`WorkFamilyTest.java`:

```java
package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorkFamilyTest {

    @ParameterizedTest
    @CsvSource({
        "Calibration Engineer, CALIBRATION",
        "Team Leader Calibration, CALIBRATION",
        "Calibration Quality & Dataset Manager, CALIBRATION",
        "Data Analyst & SW Developer, DATA",
        "AI Engineer, DATA",
        "Lead Engineer DAI & AI, DATA",
        "IT System Administrator, SUPPORT",
        "HR Business Partner, SUPPORT",
        "Purchasing & Admin Officer, SUPPORT",
        "Facility Worker, SUPPORT",
        "System Development Engineer, SYSTEMS",
        "System Devlopment Engineer, SYSTEMS",
        "Lead Engineer System engineering, SYSTEMS",
        "Development Eng. Electric/ Electronics, ELECTRONICS",
        "Software & Functions Engineer, ELECTRONICS",
        "Development Engineer SW, ELECTRONICS",
        "Verification & Validation Engineer, VALIDATION",
        "Vehicule Fleet Validation Engineer, VALIDATION",
        "Attributes & Homologation Engineer, VALIDATION",
        "Design Engineer DMU, DESIGN",
        "Simulation Engineer CFD, DESIGN",
        "Project Manager PTE, COORDINATION",
        "Team Leader Customer Site Coordination, COORDINATION",
        "Skill Team Leader, COORDINATION",
        "Engineering Center Manager, COORDINATION",
        "Workshop Manager, COORDINATION",
        "Astronaut, UNKNOWN",
        "'', UNKNOWN"
    })
    void classifiesTitles(String title, WorkFamily expected) {
        assertEquals(expected, WorkFamily.classify(title));
    }

    @ParameterizedTest
    @CsvSource({"CALIBRATION, 12, 32", "COORDINATION, 6, 16", "SUPPORT, 4, 20"})
    void carriesTheDesignParameters(WorkFamily f, double median, double weekly) {
        assertEquals(median, f.medianEstimate);
        assertEquals(weekly, f.weeklyHours);
        assertEquals(1.0, f.delivery + f.defect + f.support + f.container, 1e-9);
    }
}
```

`DirectoryTest.java`:

```java
package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DirectoryTest {

    static final UUID HEAD = UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final UUID MGR = UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final UUID ENG1 = UUID.fromString("30000000-0000-0000-0000-000000000003");
    static final UUID ENG2 = UUID.fromString("30000000-0000-0000-0000-000000000004");
    static final UUID ORPHAN = UUID.fromString("30000000-0000-0000-0000-000000000005");
    static final UUID LOST = UUID.fromString("30000000-0000-0000-0000-000000000006");

    static LinkedHashMap<String, Object> user(UUID id, String name, String title, String dept, UUID manager, String role, boolean active) {
        LinkedHashMap<String, Object> u = new LinkedHashMap<>();
        u.put("id", id.toString());
        u.put("role", role);
        u.put("email", name.toLowerCase().replace(' ', '.') + "@example.test");
        u.put("active", active);
        u.put("version", 0L);
        u.put("password", null);
        u.put("username", name.toLowerCase().replace(' ', '.'));
        u.put("full_name", name);
        u.put("job_title", title);
        u.put("object_id", null);
        u.put("created_at", "2025-01-01T08:00:00");
        u.put("department", dept);
        u.put("manager_id", manager == null ? null : manager.toString());
        u.put("updated_at", "2025-01-01T08:00:00");
        u.put("account_name", null);
        u.put("deactivated_at", active ? null : "2026-01-01T08:00:00");
        u.put("manager_object_id", null);
        return u;
    }

    static List<LinkedHashMap<String, Object>> users() {
        return List.of(
                user(HEAD, "Head One", "Skill Team Leader", "PTE / CT2 Calibration & Testing 2", null, "MEMBER", false),
                user(MGR, "Manager Two", "Team Leader Calibration", "PTE / CT2 Calibration & Testing 2", HEAD, "MEMBER", false),
                user(ENG1, "Eng Three", "Calibration Engineer", "PTE / CT2", MGR, "MEMBER", false),
                user(ENG2, "Eng Four", "Calibration Engineer", "PTE / CT2 Calibration & Testing 2", MGR, "MEMBER", false),
                user(ORPHAN, "Orphan Five", "Calibration Engineer", "PTE / CT2 Calibration & Testing 2", null, "MEMBER", false),
                user(LOST, "Lost Six", null, null, null, "MEMBER", false));
    }

    static SeedConfig cfg() {
        return new SeedConfig(20, LocalDate.of(2026, 9, 6), 42L, false, 0);
    }

    @Test
    void parsesDepartmentCodes() {
        assertEquals("CT2", Directory.deptCode("PTE / CT2 Calibration & Testing 2"));
        assertEquals("CT2", Directory.deptCode("PTE / CT2"));
        assertEquals("CSC", Directory.deptCode("PTE / CSC- CETIEV Customer Site Coordination - Cetiev"));
        assertEquals("CSC", Directory.deptCode("PTE / CSC - OEZ Customer Site Coordination OEZ"));
        assertEquals("HR", Directory.deptCode("ZEN / HR & WKP DEP HR & Workplace Services Department"));
        assertEquals("CD&I", Directory.deptCode("PTE / CD&I Component Design & Industrialization"));
        assertNull(Directory.deptCode(null));
        assertNull(Directory.deptCode("  "));
    }

    @Test
    void derivesManagerTeamsDepartmentsAndRoles() {
        Directory.Result r = Directory.derive(users(), List.of(), List.of(), cfg(), new SeedRandom(1));
        // manager teams for MGR and for HEAD (whose report is MGR), the CT2 department team, the "Unassigned" department team
        assertEquals(4, r.teams().size());
        Team mgrTeam = r.teams().stream().filter(t -> MGR.equals(t.managerId()) && !t.department()).findFirst().orElseThrow();
        assertTrue(mgrTeam.memberIds().containsAll(List.of(MGR, ENG1, ENG2)), "leader counted with reports");
        Team dept = r.teams().stream().filter(t -> t.department() && "CT2".equals(t.deptCode())).findFirst().orElseThrow();
        assertEquals(dept.id(), mgrTeam.parentId());
        assertEquals(HEAD, dept.managerId());
        assertTrue(dept.memberIds().contains(ORPHAN), "user without manager joins the department team");
        Team unassigned = r.teams().stream().filter(t -> t.department() && t.deptCode() == null).findFirst().orElseThrow();
        assertTrue(unassigned.memberIds().contains(LOST));
        assertEquals("SKILL_TEAM_LEADER", role(r, HEAD));
        assertEquals("TEAM_LEADER", role(r, MGR));
        assertEquals("MEMBER", role(r, ENG1));
        assertEquals("CT2 · Manager Two", mgrTeam.name());
    }

    @Test
    void activatesEveryoneAndKeepsExistingTeams() {
        LinkedHashMap<String, Object> existingTeam = new LinkedHashMap<>();
        existingTeam.put("id", "40000000-0000-0000-0000-000000000001");
        existingTeam.put("name", "Backend Team");
        existingTeam.put("active", true);
        existingTeam.put("version", 0L);
        existingTeam.put("manager_id", MGR.toString());
        existingTeam.put("parent_team_id", null);
        existingTeam.put("created_at", "2026-09-03T13:59:58");
        existingTeam.put("updated_at", "2026-09-03T13:59:58");
        LinkedHashMap<String, Object> existingMember = new LinkedHashMap<>();
        existingMember.put("id", "50000000-0000-0000-0000-000000000001");
        existingMember.put("team_id", "40000000-0000-0000-0000-000000000001");
        existingMember.put("user_id", ENG1.toString());
        existingMember.put("joined_at", "2026-09-03T13:59:58");
        existingMember.put("created_at", "2026-09-03T13:59:58");
        existingMember.put("updated_at", "2026-09-03T13:59:58");
        Directory.Result r = Directory.derive(users(), List.of(existingTeam), List.of(existingMember), cfg(), new SeedRandom(1));
        assertEquals(5, r.teams().size());
        assertTrue(r.teamRows().stream().anyMatch(t -> "Backend Team".equals(t.get("name"))));
        long active = r.userRows().stream().filter(u -> Boolean.TRUE.equals(u.get("active"))).count();
        assertTrue(active >= 5, "3% leave at most; got " + active);
        assertTrue(r.people().stream().allMatch(p -> !p.joined().isAfter(cfg().lastDay())));
        assertTrue(r.teamMemberRows().stream().allMatch(m -> m.get("joined_at") != null));
    }

    @Test
    void isDeterministic() {
        Directory.Result a = Directory.derive(users(), List.of(), List.of(), cfg(), new SeedRandom(7));
        Directory.Result b = Directory.derive(users(), List.of(), List.of(), cfg(), new SeedRandom(7));
        assertEquals(a.teamRows(), b.teamRows());
        assertEquals(a.teamMemberRows(), b.teamMemberRows());
    }

    static String role(Directory.Result r, UUID id) {
        return r.people().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow().role();
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='SeedRandomTest,WorkFamilyTest,DirectoryTest'`
Expected: compilation errors for the seed package.

- [ ] **Step 3: Write `SeedConfig` and `SeedRandom`**

```java
package com.workloadhub.forecast.seed;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Parameters of one seed run. History spans `weeks` Monday weeks, the last one containing `end`. */
public record SeedConfig(int weeks, LocalDate end, long seed, boolean synthetic, int users) {

    public SeedConfig {
        if (weeks < 4) {
            throw new IllegalArgumentException("weeks must be at least 4");
        }
    }

    public static LocalDate mondayOf(LocalDate d) {
        return d.minusDays(d.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
    }

    public LocalDate firstMonday() {
        return mondayOf(end).minusWeeks(weeks - 1L);
    }

    public LocalDate lastDay() {
        return end;
    }

    public List<LocalDate> mondays() {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate m = firstMonday(); !m.isAfter(end); m = m.plusWeeks(1)) {
            out.add(m);
        }
        return out;
    }
}
```

```java
package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Every random draw of the generator goes through one seeded stream, so a seed fixes the output. */
public final class SeedRandom {

    private final Random random;

    public SeedRandom(long seed) {
        this.random = new Random(seed);
    }

    public UUID uuid() {
        long msb = random.nextLong();
        long lsb = random.nextLong();
        msb = (msb & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L; // version 4
        lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // IETF variant
        return new UUID(msb, lsb);
    }

    public double uniform(double a, double b) {
        return a + (b - a) * random.nextDouble();
    }

    /** Inclusive on both ends. */
    public int between(int a, int b) {
        return a + random.nextInt(b - a + 1);
    }

    public boolean chance(double p) {
        return random.nextDouble() < p;
    }

    public double gaussian() {
        return random.nextGaussian();
    }

    public double lognormal(double median, double sigma) {
        return median * Math.exp(sigma * random.nextGaussian());
    }

    /** Knuth's method below 30, a rounded normal above; never negative. */
    public int poisson(double lambda) {
        if (lambda <= 0) {
            return 0;
        }
        if (lambda < 30) {
            double l = Math.exp(-lambda);
            int k = 0;
            double p = 1.0;
            do {
                k++;
                p *= random.nextDouble();
            } while (p > l);
            return k - 1;
        }
        return (int) Math.max(0, Math.round(lambda + Math.sqrt(lambda) * random.nextGaussian()));
    }

    public <T> T pick(List<T> items, double[] weights) {
        double total = 0;
        for (double w : weights) {
            total += w;
        }
        double x = random.nextDouble() * total;
        for (int i = 0; i < items.size(); i++) {
            x -= weights[i];
            if (x < 0) {
                return items.get(i);
            }
        }
        return items.get(items.size() - 1);
    }

    public <T> T pick(List<T> items) {
        return items.get(random.nextInt(items.size()));
    }

    /** A timestamp on the day, between fromHour (inclusive) and toHour (exclusive), to the second. */
    public LocalDateTime at(LocalDate day, int fromHour, int toHour) {
        int seconds = between(fromHour * 3600, toHour * 3600 - 1);
        return day.atStartOfDay().plusSeconds(seconds);
    }
}
```

- [ ] **Step 4: Write `WorkFamily`**

```java
package com.workloadhub.forecast.seed;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The kinds of work in the directory, with the parameters of the design's section 4.2. */
public enum WorkFamily {
    // order = precedence: the first family with a matching whole word wins
    CALIBRATION(Set.of("calibration"), 0.55, 0.20, 0.20, 0.05, 12, 0.35, 32),
    DATA(Set.of("data", "ai", "dai"), 0.55, 0.20, 0.20, 0.05, 8, 0.55, 28),
    SUPPORT(Set.of("hr", "admin", "administration", "administrator", "finance", "purchasing", "it", "facility",
            "specialist", "generalist", "officer"), 0.50, 0.20, 0.30, 0.00, 4, 0.70, 20),
    SYSTEMS(Set.of("system", "systems"), 0.60, 0.15, 0.20, 0.05, 16, 0.30, 30),
    ELECTRONICS(Set.of("electric", "electronics", "ee", "software", "sw", "functions"), 0.50, 0.30, 0.15, 0.05, 10, 0.40, 30),
    VALIDATION(Set.of("validation", "verification", "homologation", "fleet", "test"), 0.45, 0.35, 0.15, 0.05, 12, 0.30, 30),
    DESIGN(Set.of("design", "simulation", "cfd", "dmu"), 0.60, 0.10, 0.25, 0.05, 20, 0.35, 30),
    COORDINATION(Set.of("project", "coordination", "leader", "manager", "workshop", "center"), 0.40, 0.10, 0.45, 0.05, 6, 0.60, 16),
    UNKNOWN(Set.of(), 0.60, 0.15, 0.20, 0.05, 16, 0.30, 30);

    public final Set<String> keywords;
    public final double delivery;
    public final double defect;
    public final double support;
    public final double container;
    public final double medianEstimate;
    public final double selfPicked;
    public final double weeklyHours;

    WorkFamily(Set<String> keywords, double delivery, double defect, double support, double container,
            double medianEstimate, double selfPicked, double weeklyHours) {
        this.keywords = keywords;
        this.delivery = delivery;
        this.defect = defect;
        this.support = support;
        this.container = container;
        this.medianEstimate = medianEstimate;
        this.selfPicked = selfPicked;
        this.weeklyHours = weeklyHours;
    }

    public boolean isUnknown() {
        return this == UNKNOWN;
    }

    public static WorkFamily classify(String jobTitle) {
        if (jobTitle == null || jobTitle.isBlank()) {
            return UNKNOWN;
        }
        List<String> words = List.of(jobTitle.toLowerCase(Locale.ROOT).split("[^a-z0-9&]+"));
        for (WorkFamily f : values()) {
            for (String w : words) {
                if (f.keywords.contains(w)) {
                    return f;
                }
            }
        }
        return UNKNOWN;
    }
}
```

- [ ] **Step 5: Write `Person`, `Team` and `Directory`**

```java
package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.util.UUID;

/** One user as the generator sees them. `left` is null while employed. */
public record Person(UUID id, String fullName, String email, String jobTitle, String department, String deptCode,
        UUID managerId, String role, WorkFamily family, LocalDate joined, LocalDate left) {

    public boolean counted() {
        return role.equals("MEMBER") || role.equals("TEAM_LEADER");
    }

    public boolean employedOn(LocalDate day) {
        return !day.isBefore(joined) && (left == null || day.isBefore(left));
    }

    public Person withRole(String newRole) {
        return new Person(id, fullName, email, jobTitle, department, deptCode, managerId, newRole, family, joined, left);
    }
}
```

```java
package com.workloadhub.forecast.seed;

import java.util.List;
import java.util.UUID;

/** A team row plus its membership; department teams have no parent and hold the users without a manager. */
public record Team(UUID id, String name, UUID managerId, UUID parentId, List<UUID> memberIds, boolean department, String deptCode) {
}
```

```java
package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Turns the directory columns of users (manager, department, job title) into teams, roles and memberships. */
public final class Directory {

    public record Result(List<Person> people, List<Team> teams, List<LinkedHashMap<String, Object>> userRows,
            List<LinkedHashMap<String, Object>> teamRows, List<LinkedHashMap<String, Object>> teamMemberRows) {
    }

    private static final double LEAVE_SHARE = 0.03;
    private static final double LATE_JOIN_SHARE = 0.10;

    private Directory() {
    }

    /** "PTE / CT2 Calibration & Testing 2" -> "CT2"; the first token after the slash, trailing punctuation dropped. */
    public static String deptCode(String department) {
        if (department == null || department.isBlank()) {
            return null;
        }
        String tail = department.contains("/") ? department.substring(department.indexOf('/') + 1) : department;
        String first = tail.trim().split("\\s+")[0];
        first = first.replaceAll("[^A-Za-z0-9&]+$", "");
        return first.isEmpty() ? null : first.toUpperCase(Locale.ROOT);
    }

    public static Result derive(List<LinkedHashMap<String, Object>> userRows, List<LinkedHashMap<String, Object>> teamRows,
            List<LinkedHashMap<String, Object>> memberRows, SeedConfig cfg, SeedRandom rnd) {
        List<LinkedHashMap<String, Object>> sortedUsers = new ArrayList<>(userRows);
        sortedUsers.sort(Comparator.comparing(u -> String.valueOf(u.get("id"))));

        // 1. people, joined/left dates, families
        Map<UUID, Person> people = new LinkedHashMap<>();
        LocalDate first = cfg.firstMonday();
        LocalDate last = cfg.lastDay();
        for (LinkedHashMap<String, Object> u : sortedUsers) {
            UUID id = UUID.fromString((String) u.get("id"));
            String dept = (String) u.get("department");
            UUID manager = u.get("manager_id") == null ? null : UUID.fromString((String) u.get("manager_id"));
            LocalDate joined = first;
            if (rnd.chance(LATE_JOIN_SHARE)) {
                joined = first.plusWeeks(rnd.between(1, Math.max(1, cfg.weeks() - 4)));
            }
            LocalDate left = null;
            if (rnd.chance(LEAVE_SHARE)) {
                int span = (int) (java.time.temporal.ChronoUnit.DAYS.between(joined, last));
                if (span > 60) {
                    left = joined.plusDays(rnd.between((int) (span * 0.6), span - 1));
                }
            }
            String role = String.valueOf(u.get("role"));
            String title = (String) u.get("job_title");
            people.put(id, new Person(id, (String) u.get("full_name"), (String) u.get("email"), title, dept,
                    deptCode(dept), manager, role, WorkFamily.classify(title), joined, left));
        }

        // 2. reports per manager, people per department code
        Map<UUID, List<UUID>> reports = new TreeMap<>();
        Map<String, List<UUID>> byDept = new TreeMap<>();
        Map<String, String> deptLabel = new HashMap<>();
        for (Person p : people.values()) {
            if (p.managerId() != null && people.containsKey(p.managerId())) {
                reports.computeIfAbsent(p.managerId(), k -> new ArrayList<>()).add(p.id());
            }
            String code = p.deptCode() == null ? "" : p.deptCode();
            byDept.computeIfAbsent(code, k -> new ArrayList<>()).add(p.id());
            if (p.department() != null && p.department().length() > deptLabel.getOrDefault(code, "").length()) {
                deptLabel.put(code, p.department());
            }
        }

        // 3. roles: managers lead, department heads are skill team leaders, admins and the center manager stay
        for (UUID managerId : reports.keySet()) {
            Person m = people.get(managerId);
            if (m.role().equals("MEMBER") || m.role().equals("TEAM_LEADER") || m.role().equals("VIEWER")) {
                people.put(managerId, m.withRole("TEAM_LEADER"));
            }
        }
        Map<String, UUID> heads = new TreeMap<>();
        for (Map.Entry<String, List<UUID>> e : byDept.entrySet()) {
            if (e.getKey().isEmpty()) {
                continue;
            }
            UUID head = e.getValue().stream()
                    .filter(id -> people.get(id).jobTitle() != null
                            && people.get(id).jobTitle().toLowerCase(Locale.ROOT).contains("skill team leader"))
                    .findFirst()
                    .orElseGet(() -> e.getValue().stream()
                            .filter(reports::containsKey)
                            .max(Comparator.comparingInt(id -> reports.get(id).size()))
                            .orElse(null));
            if (head != null) {
                heads.put(e.getKey(), head);
                Person h = people.get(head);
                if (!h.role().equals("ADMIN") && !h.role().equals("CENTER_MANAGER")) {
                    people.put(head, h.withRole("SKILL_TEAM_LEADER"));
                }
            }
        }

        // 4. department teams (one per code, plus "Unassigned" for people with neither manager nor department)
        List<Team> teams = new ArrayList<>();
        Map<String, UUID> deptTeamIds = new TreeMap<>();
        for (String code : byDept.keySet()) {
            UUID teamId = rnd.uuid();
            deptTeamIds.put(code, teamId);
            List<UUID> members = new ArrayList<>();
            for (UUID id : byDept.get(code)) {
                Person p = people.get(id);
                boolean hasManager = p.managerId() != null && people.containsKey(p.managerId());
                if (!hasManager || id.equals(heads.get(code))) {
                    members.add(id);
                }
            }
            String name = code.isEmpty() ? "Unassigned" : deptLabel.getOrDefault(code, code);
            teams.add(new Team(teamId, name, heads.get(code), null, members, true, code.isEmpty() ? null : code));
        }

        // 5. manager teams
        for (Map.Entry<UUID, List<UUID>> e : reports.entrySet()) {
            Person m = people.get(e.getKey());
            String code = m.deptCode();
            if (code == null) {
                Map<String, Integer> votes = new TreeMap<>();
                for (UUID r : e.getValue()) {
                    String c = people.get(r).deptCode();
                    if (c != null) {
                        votes.merge(c, 1, Integer::sum);
                    }
                }
                code = votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
            }
            List<UUID> members = new ArrayList<>();
            members.add(m.id());
            members.addAll(e.getValue());
            teams.add(new Team(rnd.uuid(), (code.isEmpty() ? "Team" : code) + " · " + m.fullName(), m.id(),
                    deptTeamIds.get(code), members, false, code.isEmpty() ? null : code));
        }

        // 6. rows: users updated, existing teams and memberships kept, new ones appended
        List<LinkedHashMap<String, Object>> newUserRows = new ArrayList<>();
        for (LinkedHashMap<String, Object> u : sortedUsers) {
            Person p = people.get(UUID.fromString((String) u.get("id")));
            LinkedHashMap<String, Object> row = new LinkedHashMap<>(u);
            row.put("role", p.role());
            row.put("active", p.left() == null);
            row.put("deactivated_at", p.left() == null ? null : p.left().atTime(18, 0).toString());
            newUserRows.add(row);
        }
        List<LinkedHashMap<String, Object>> newTeamRows = new ArrayList<>(teamRows);
        List<LinkedHashMap<String, Object>> newMemberRows = new ArrayList<>(memberRows);
        String now = first.atTime(8, 0).toString();
        for (Team t : teams) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.id().toString());
            row.put("name", t.name());
            row.put("active", true);
            row.put("version", 0L);
            row.put("manager_id", t.managerId() == null ? null : t.managerId().toString());
            row.put("parent_team_id", t.parentId() == null ? null : t.parentId().toString());
            row.put("created_at", now);
            row.put("updated_at", now);
            newTeamRows.add(row);
            for (UUID member : t.memberIds()) {
                LocalDateTime joined = people.get(member).joined().atTime(8, 0);
                LinkedHashMap<String, Object> m = new LinkedHashMap<>();
                m.put("id", rnd.uuid().toString());
                m.put("team_id", t.id().toString());
                m.put("user_id", member.toString());
                m.put("joined_at", joined.toString());
                m.put("created_at", joined.toString());
                m.put("updated_at", joined.toString());
                newMemberRows.add(m);
            }
        }
        for (Team existing : existingTeams(teamRows, memberRows)) {
            teams.add(existing);
        }
        return new Result(new ArrayList<>(people.values()), teams, newUserRows, newTeamRows, newMemberRows);
    }

    /** The export's own teams, as Team values, so the generator can give them tasks too. */
    static List<Team> existingTeams(List<LinkedHashMap<String, Object>> teamRows, List<LinkedHashMap<String, Object>> memberRows) {
        List<Team> out = new ArrayList<>();
        for (LinkedHashMap<String, Object> t : teamRows) {
            UUID id = UUID.fromString((String) t.get("id"));
            List<UUID> members = new ArrayList<>();
            for (LinkedHashMap<String, Object> m : memberRows) {
                if (id.toString().equals(m.get("team_id"))) {
                    members.add(UUID.fromString((String) m.get("user_id")));
                }
            }
            out.add(new Team(id, (String) t.get("name"),
                    t.get("manager_id") == null ? null : UUID.fromString((String) t.get("manager_id")),
                    t.get("parent_team_id") == null ? null : UUID.fromString((String) t.get("parent_team_id")),
                    members, false, null));
        }
        return out;
    }
}
```

- [ ] **Step 6: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='SeedRandomTest,WorkFamilyTest,DirectoryTest'`
Expected: all pass. If `activatesEveryoneAndKeepsExistingTeams` sees fewer than 5 active users, the 3 % leave draw hit several of six people: the test tolerates one leaver; change the seed passed to `new SeedRandom(1)` in that test to `new SeedRandom(2)` rather than weakening the assertion, and note the seed in the test.

- [ ] **Step 7: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/seed server/forecast-core/src/test/java/com/workloadhub/forecast/seed
git commit -m "feat(seed): configuration, seeded randomness, work families and directory-derived teams

The directory sync gives managers, departments and job titles; from
them the seed derives one team per manager under department teams,
sets leader roles, and classifies each person's work by job title."
```

---

### Task 8: Calendar, absences and capacity rows

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/SeedCalendar.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/AbsencePlanner.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/CapacityWriter.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/SeedCalendarTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/AbsencePlannerTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/CapacityWriterTest.java`

**Interfaces:**
- Consumes: `SeedConfig`, `SeedRandom`, `Person`, `Team`.
- Produces:
  - `SeedCalendar.fromHolidayRows(List<LinkedHashMap<String, Object>> holidayRows, SeedConfig cfg)` returning a calendar whose `holidayRows()` is the input plus one replicated CONFIRMED NATIONAL row per holiday for every year of the history that has no holiday rows at all; `boolean isWorkingDay(LocalDate)`, `int workingDays(LocalDate monday)`, `List<LocalDate> workingDaysOf(LocalDate monday)`, `Set<LocalDate> holidays()`.
  - `AbsencePlanner.plan(Person p, SeedCalendar cal, SeedConfig cfg, SeedRandom rnd)` returning `AbsencePlanner.Plan(Set<LocalDate> absentDays, List<LinkedHashMap<String, Object>> absenceRows, List<LinkedHashMap<String, Object>> leaveRows)`; `double hoursPresent(Person p, LocalDate day)` on the plan: 8 on a working day the person is employed and not absent, else 0; `double absenceHours(LocalDate monday)`: 8 × absent working days in that week.
  - `CapacityWriter.userCapacity(Person p, AbsencePlanner.Plan plan, SeedCalendar cal, SeedConfig cfg, SeedRandom rnd)` returning `user_capacity` rows for each week the person is employed; `CapacityWriter.teamCapacity(Team t, Map<UUID, List<LinkedHashMap<String, Object>>> userRowsByMember, ToDoubleBiFunction<UUID, LocalDate> allocated, SeedConfig cfg, SeedRandom rnd)` returning `team_capacity` rows.

- [ ] **Step 1: Write the failing tests**

`SeedCalendarTest.java`:

```java
package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeedCalendarTest {

    static LinkedHashMap<String, Object> holiday(String title, String start, String end, String status, String type) {
        LinkedHashMap<String, Object> h = new LinkedHashMap<>();
        h.put("id", java.util.UUID.nameUUIDFromBytes((title + start).getBytes()).toString());
        h.put("title", title);
        h.put("type", type);
        h.put("status", status);
        h.put("active", true);
        h.put("start_date", start);
        h.put("end_date", end);
        h.put("country_code", "MA");
        h.put("created_at", "2026-09-03T13:59:58");
        h.put("updated_at", "2026-09-03T13:59:58");
        return h;
    }

    static SeedConfig cfg() {
        return new SeedConfig(52, LocalDate.of(2026, 9, 6), 1, false, 0);
    }

    @Test
    void confirmedHolidaysAreNotWorkingDaysPendingOnesAre() {
        SeedCalendar cal = SeedCalendar.fromHolidayRows(List.of(
                holiday("Labour Day", "2026-05-01", "2026-05-01", "CONFIRMED", "NATIONAL"),
                holiday("Eid", "2026-03-20", "2026-03-21", "PENDING", "RELIGIOUS")), cfg());
        assertFalse(cal.isWorkingDay(LocalDate.of(2026, 5, 1)));
        assertTrue(cal.isWorkingDay(LocalDate.of(2026, 3, 20)));
        assertFalse(cal.isWorkingDay(LocalDate.of(2026, 5, 2)), "Saturday");
        assertEquals(4, cal.workingDays(LocalDate.of(2026, 4, 27)));
        assertEquals(5, cal.workingDays(LocalDate.of(2026, 3, 16)));
    }

    @Test
    void replicatesNationalHolidaysIntoYearsWithoutAny() {
        SeedCalendar cal = SeedCalendar.fromHolidayRows(List.of(
                holiday("Independence Day", "2026-11-18", "2026-11-18", "CONFIRMED", "NATIONAL"),
                holiday("Eid", "2026-03-20", "2026-03-21", "CONFIRMED", "RELIGIOUS")), cfg());
        assertFalse(cal.isWorkingDay(LocalDate.of(2025, 11, 18)), "national holiday replicated into 2025");
        assertTrue(cal.isWorkingDay(LocalDate.of(2025, 3, 20)), "religious holidays move; not replicated");
        assertEquals(3, cal.holidayRows().size());
        assertTrue(cal.holidayRows().stream().anyMatch(h -> "2025-11-18".equals(h.get("start_date"))));
    }
}
```

`AbsencePlannerTest.java`:

```java
package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class AbsencePlannerTest {

    static SeedConfig cfg() {
        return new SeedConfig(52, LocalDate.of(2026, 9, 6), 1, false, 0);
    }

    static Person person(LocalDate joined, LocalDate left) {
        return new Person(UUID.fromString("30000000-0000-0000-0000-000000000003"), "Eng Three", "e@example.test",
                "Calibration Engineer", "PTE / CT2", "CT2", null, "MEMBER", WorkFamily.CALIBRATION, joined, left);
    }

    static SeedCalendar cal() {
        return SeedCalendar.fromHolidayRows(List.of(SeedCalendarTest.holiday("Labour Day", "2026-05-01", "2026-05-01", "CONFIRMED", "NATIONAL")), cfg());
    }

    @Property(tries = 40)
    boolean absencesAreWorkingDaysWhileEmployedAndWithinBounds(@ForAll @LongRange(min = 0, max = 100_000) long seed) {
        SeedCalendar cal = cal();
        Person p = person(cfg().firstMonday(), null);
        AbsencePlanner.Plan plan = AbsencePlanner.plan(p, cal, cfg(), new SeedRandom(seed));
        long vacation = plan.absenceRows().stream().filter(a -> "VACATION".equals(a.get("type"))).count();
        long sick = plan.absenceRows().stream().filter(a -> "SICK_LEAVE".equals(a.get("type"))).count();
        boolean allWorking = plan.absentDays().stream().allMatch(d -> cal.isWorkingDay(d) && p.employedOn(d)
                && !d.isBefore(cfg().firstMonday()) && !d.isAfter(cfg().lastDay()));
        boolean distinct = plan.absentDays().size() == plan.absenceRows().size();
        return allWorking && distinct && vacation >= 10 && vacation <= 20 && sick <= 4
                && plan.leaveRows().stream().allMatch(l -> "APPROVED".equals(l.get("status")));
    }

    @Test
    void hoursPresentFollowsCalendarEmploymentAndAbsence() {
        SeedCalendar cal = cal();
        Person p = person(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 8, 3));
        AbsencePlanner.Plan plan = AbsencePlanner.plan(p, cal, cfg(), new SeedRandom(3));
        assertEquals(0.0, plan.hoursPresent(p, LocalDate.of(2026, 2, 27)), "before joining");
        assertEquals(0.0, plan.hoursPresent(p, LocalDate.of(2026, 8, 3)), "left");
        assertEquals(0.0, plan.hoursPresent(p, LocalDate.of(2026, 5, 1)), "holiday");
        assertEquals(0.0, plan.hoursPresent(p, LocalDate.of(2026, 5, 2)), "Saturday");
        LocalDate anyAbsent = plan.absentDays().iterator().next();
        assertEquals(0.0, plan.hoursPresent(p, anyAbsent));
        LocalDate monday = SeedConfig.mondayOf(anyAbsent);
        assertTrue(plan.absenceHours(monday) >= 8.0);
        long presentDays = cal.workingDaysOf(monday).stream().filter(d -> plan.hoursPresent(p, d) > 0).count();
        assertEquals(8.0 * (cal.workingDays(monday) - presentDays), plan.absenceHours(monday), 1e-9);
    }
}
```

`CapacityWriterTest.java`:

```java
package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapacityWriterTest {

    @Test
    void userCapacityRowsFollowTheFormulaForEveryEmployedWeek() {
        SeedConfig cfg = new SeedConfig(52, LocalDate.of(2026, 9, 6), 1, false, 0);
        SeedCalendar cal = AbsencePlannerTest.cal();
        Person p = AbsencePlannerTest.person(LocalDate.of(2026, 3, 2), null);
        AbsencePlanner.Plan plan = AbsencePlanner.plan(p, cal, cfg, new SeedRandom(5));
        List<LinkedHashMap<String, Object>> rows = CapacityWriter.userCapacity(p, plan, cal, cfg, new SeedRandom(6));
        long weeksEmployed = cfg.mondays().stream().filter(m -> !m.isBefore(LocalDate.of(2026, 3, 2))).count();
        assertEquals(weeksEmployed, rows.size());
        for (LinkedHashMap<String, Object> r : rows) {
            LocalDate monday = LocalDate.parse((String) r.get("week_start"));
            double base = (Double) r.get("base_capacity_hrs");
            double absence = (Double) r.get("absence_hrs");
            double available = (Double) r.get("available_hrs");
            assertEquals(40.0, base);
            assertEquals(plan.absenceHours(monday), absence, 1e-9);
            assertEquals(base * cal.workingDays(monday) / 5.0 - absence, available, 1e-9);
            assertTrue(available >= 0);
        }
    }

    @Test
    void teamCapacitySumsMembersAndTakesAllocatedFromTheCallback() {
        SeedConfig cfg = new SeedConfig(8, LocalDate.of(2026, 9, 6), 1, false, 0);
        SeedCalendar cal = AbsencePlannerTest.cal();
        Person a = AbsencePlannerTest.person(cfg.firstMonday(), null);
        Person b = new Person(UUID.fromString("30000000-0000-0000-0000-000000000004"), "Eng Four", "f@example.test",
                "Calibration Engineer", "PTE / CT2", "CT2", null, "MEMBER", WorkFamily.CALIBRATION, cfg.firstMonday(), null);
        var planA = AbsencePlanner.plan(a, cal, cfg, new SeedRandom(1));
        var planB = AbsencePlanner.plan(b, cal, cfg, new SeedRandom(2));
        var rowsA = CapacityWriter.userCapacity(a, planA, cal, cfg, new SeedRandom(3));
        var rowsB = CapacityWriter.userCapacity(b, planB, cal, cfg, new SeedRandom(4));
        Team team = new Team(UUID.randomUUID(), "CT2 · X", a.id(), null, List.of(a.id(), b.id()), false, "CT2");
        var rows = CapacityWriter.teamCapacity(team, Map.of(a.id(), rowsA, b.id(), rowsB), (id, monday) -> 12.0, cfg, new SeedRandom(5));
        assertEquals(8, rows.size());
        for (LinkedHashMap<String, Object> r : rows) {
            LocalDate monday = LocalDate.parse((String) r.get("week_start"));
            double expected = availableOf(rowsA, monday) + availableOf(rowsB, monday);
            assertEquals(expected, (Double) r.get("total_capacity_hrs"), 1e-9);
            assertEquals(24.0, (Double) r.get("allocated_hrs"), 1e-9);
            assertEquals(team.id().toString(), r.get("team_id"));
        }
    }

    static double availableOf(List<LinkedHashMap<String, Object>> rows, LocalDate monday) {
        return rows.stream().filter(r -> monday.toString().equals(r.get("week_start")))
                .mapToDouble(r -> (Double) r.get("available_hrs")).sum();
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='SeedCalendarTest,AbsencePlannerTest,CapacityWriterTest'`
Expected: compilation errors.

- [ ] **Step 3: Write `SeedCalendar`**

```java
package com.workloadhub.forecast.seed;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Working days of the history: weekdays minus confirmed, active holidays. */
public final class SeedCalendar {

    private final Set<LocalDate> holidays;
    private final List<LinkedHashMap<String, Object>> holidayRows;

    private SeedCalendar(Set<LocalDate> holidays, List<LinkedHashMap<String, Object>> holidayRows) {
        this.holidays = holidays;
        this.holidayRows = holidayRows;
    }

    public static SeedCalendar fromHolidayRows(List<LinkedHashMap<String, Object>> rows, SeedConfig cfg) {
        List<LinkedHashMap<String, Object>> all = new ArrayList<>(rows);
        Set<Integer> yearsWithRows = new HashSet<>();
        for (LinkedHashMap<String, Object> h : rows) {
            yearsWithRows.add(LocalDate.parse((String) h.get("start_date")).getYear());
        }
        for (int year = cfg.firstMonday().getYear(); year <= cfg.lastDay().getYear(); year++) {
            if (yearsWithRows.contains(year)) {
                continue;
            }
            for (LinkedHashMap<String, Object> h : rows) {
                if (!"NATIONAL".equals(h.get("type")) || !"CONFIRMED".equals(h.get("status"))) {
                    continue;
                }
                LocalDate start = LocalDate.parse((String) h.get("start_date")).withYear(year);
                LocalDate end = LocalDate.parse((String) h.get("end_date")).withYear(year);
                LinkedHashMap<String, Object> copy = new LinkedHashMap<>(h);
                copy.put("id", java.util.UUID.nameUUIDFromBytes((h.get("id") + ":" + year).getBytes()).toString());
                copy.put("start_date", start.toString());
                copy.put("end_date", end.toString());
                all.add(copy);
            }
        }
        Set<LocalDate> days = new TreeSet<>();
        for (LinkedHashMap<String, Object> h : all) {
            boolean active = h.get("active") == null || Boolean.TRUE.equals(h.get("active")) || Long.valueOf(1).equals(h.get("active"));
            if (!"CONFIRMED".equals(h.get("status")) || !active) {
                continue;
            }
            LocalDate d = LocalDate.parse((String) h.get("start_date"));
            LocalDate end = LocalDate.parse((String) h.get("end_date"));
            for (; !d.isAfter(end); d = d.plusDays(1)) {
                days.add(d);
            }
        }
        return new SeedCalendar(days, all);
    }

    public Set<LocalDate> holidays() {
        return holidays;
    }

    public List<LinkedHashMap<String, Object>> holidayRows() {
        return holidayRows;
    }

    public boolean isWorkingDay(LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(d);
    }

    public List<LocalDate> workingDaysOf(LocalDate monday) {
        List<LocalDate> out = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            LocalDate d = monday.plusDays(i);
            if (isWorkingDay(d)) {
                out.add(d);
            }
        }
        return out;
    }

    public int workingDays(LocalDate monday) {
        return workingDaysOf(monday).size();
    }
}
```

- [ ] **Step 4: Write `AbsencePlanner`**

```java
package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Vacation blocks, sick days and the resulting presence per day for one person. */
public final class AbsencePlanner {

    public record Plan(Set<LocalDate> absentDays, List<LinkedHashMap<String, Object>> absenceRows,
            List<LinkedHashMap<String, Object>> leaveRows, SeedCalendar calendar) {

        public double hoursPresent(Person p, LocalDate day) {
            return calendar.isWorkingDay(day) && p.employedOn(day) && !absentDays.contains(day) ? 8.0 : 0.0;
        }

        public double absenceHours(LocalDate monday) {
            int n = 0;
            for (LocalDate d : calendar.workingDaysOf(monday)) {
                if (absentDays.contains(d)) {
                    n++;
                }
            }
            return 8.0 * n;
        }
    }

    private AbsencePlanner() {
    }

    public static Plan plan(Person p, SeedCalendar cal, SeedConfig cfg, SeedRandom rnd) {
        Set<LocalDate> absent = new TreeSet<>();
        List<LinkedHashMap<String, Object>> absenceRows = new ArrayList<>();
        List<LinkedHashMap<String, Object>> leaveRows = new ArrayList<>();
        List<LocalDate> workingDays = new ArrayList<>();
        for (LocalDate d = cfg.firstMonday(); !d.isAfter(cfg.lastDay()); d = d.plusDays(1)) {
            if (cal.isWorkingDay(d) && p.employedOn(d)) {
                workingDays.add(d);
            }
        }
        if (workingDays.isEmpty()) {
            return new Plan(absent, absenceRows, leaveRows, cal);
        }
        // two vacation blocks per 52 weeks, scaled to the history length, at least one
        int blocks = Math.max(1, Math.round(2f * cfg.weeks() / 52f));
        for (int b = 0; b < blocks; b++) {
            int length = rnd.between(5, 10);
            int start = pickVacationStart(workingDays, rnd);
            List<LocalDate> block = new ArrayList<>();
            for (int i = start; i < workingDays.size() && block.size() < length; i++) {
                LocalDate d = workingDays.get(i);
                if (absent.contains(d)) {
                    break;
                }
                block.add(d);
            }
            if (block.isEmpty()) {
                continue;
            }
            addLeave(p, block, "VACATION", "PAID_LEAVE", "Annual leave", absent, absenceRows, leaveRows, rnd);
        }
        int sickDays = rnd.between(0, 4);
        for (int s = 0; s < sickDays; s++) {
            LocalDate d = workingDays.get(rnd.between(0, workingDays.size() - 1));
            if (!absent.contains(d)) {
                addLeave(p, List.of(d), "SICK_LEAVE", "SICK_LEAVE", "Sick", absent, absenceRows, leaveRows, rnd);
            }
        }
        return new Plan(absent, absenceRows, leaveRows, cal);
    }

    /** 45 % start in ISO weeks 30..34, 20 % in weeks 51..2, the rest anywhere. */
    static int pickVacationStart(List<LocalDate> workingDays, SeedRandom rnd) {
        double x = rnd.uniform(0, 1);
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < workingDays.size(); i++) {
            int week = workingDays.get(i).get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            boolean summer = week >= 30 && week <= 34;
            boolean winter = week >= 51 || week <= 2;
            if ((x < 0.45 && summer) || (x >= 0.45 && x < 0.65 && winter) || x >= 0.65) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            return rnd.between(0, workingDays.size() - 1);
        }
        return candidates.get(rnd.between(0, candidates.size() - 1));
    }

    static void addLeave(Person p, List<LocalDate> days, String absenceType, String leaveType, String note,
            Set<LocalDate> absent, List<LinkedHashMap<String, Object>> absenceRows,
            List<LinkedHashMap<String, Object>> leaveRows, SeedRandom rnd) {
        String created = days.get(0).minusDays(14).atTime(10, 0).toString();
        for (LocalDate d : days) {
            absent.add(d);
            LinkedHashMap<String, Object> a = new LinkedHashMap<>();
            a.put("id", rnd.uuid().toString());
            a.put("date", d.toString());
            a.put("note", note);
            a.put("type", absenceType);
            a.put("hours", 8.0);
            a.put("user_id", p.id().toString());
            a.put("created_at", created);
            a.put("updated_at", created);
            absenceRows.add(a);
        }
        LinkedHashMap<String, Object> l = new LinkedHashMap<>();
        l.put("absence_hours", 8.0 * days.size());
        l.put("begin_time", null);
        l.put("end_date", days.get(days.size() - 1).toString());
        l.put("end_time", null);
        l.put("start_date", days.get(0).toString());
        l.put("created_at", created);
        l.put("updated_at", created);
        l.put("employee_id", p.id().toString());
        l.put("id", rnd.uuid().toString());
        l.put("note", note);
        l.put("leave_type", leaveType);
        l.put("processor", null);
        l.put("status", "APPROVED");
        leaveRows.add(l);
    }
}
```

- [ ] **Step 5: Write `CapacityWriter`**

```java
package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToDoubleBiFunction;

/** user_capacity and team_capacity rows, one per week, as the application computes them. */
public final class CapacityWriter {

    public static final double BASE_HOURS = 40.0;

    private CapacityWriter() {
    }

    public static List<LinkedHashMap<String, Object>> userCapacity(Person p, AbsencePlanner.Plan plan, SeedCalendar cal,
            SeedConfig cfg, SeedRandom rnd) {
        List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
        for (LocalDate monday : cfg.mondays()) {
            boolean employed = false;
            for (int i = 0; i < 7; i++) {
                employed |= p.employedOn(monday.plusDays(i));
            }
            if (!employed) {
                continue;
            }
            double absence = plan.absenceHours(monday);
            double available = BASE_HOURS * cal.workingDays(monday) / 5.0 - absence;
            String stamp = monday.atTime(6, 0).toString();
            LinkedHashMap<String, Object> r = new LinkedHashMap<>();
            r.put("id", rnd.uuid().toString());
            r.put("user_id", p.id().toString());
            r.put("created_at", stamp);
            r.put("updated_at", stamp);
            r.put("week_start", monday.toString());
            r.put("absence_hrs", absence);
            r.put("available_hrs", Math.max(0.0, available));
            r.put("base_capacity_hrs", BASE_HOURS);
            rows.add(r);
        }
        return rows;
    }

    public static List<LinkedHashMap<String, Object>> teamCapacity(Team t, Map<UUID, List<LinkedHashMap<String, Object>>> userRowsByMember,
            ToDoubleBiFunction<UUID, LocalDate> allocated, SeedConfig cfg, SeedRandom rnd) {
        List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
        for (LocalDate monday : cfg.mondays()) {
            double total = 0;
            double alloc = 0;
            for (UUID member : t.memberIds()) {
                for (LinkedHashMap<String, Object> r : userRowsByMember.getOrDefault(member, List.of())) {
                    if (monday.toString().equals(r.get("week_start"))) {
                        total += (Double) r.get("available_hrs");
                    }
                }
                alloc += allocated.applyAsDouble(member, monday);
            }
            String stamp = monday.atTime(6, 0).toString();
            LinkedHashMap<String, Object> r = new LinkedHashMap<>();
            r.put("allocated_hrs", alloc);
            r.put("total_capacity_hrs", total);
            r.put("week_start", monday.toString());
            r.put("created_at", stamp);
            r.put("updated_at", stamp);
            r.put("id", rnd.uuid().toString());
            r.put("team_id", t.id().toString());
            rows.add(r);
        }
        return rows;
    }
}
```

- [ ] **Step 6: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='SeedCalendarTest,AbsencePlannerTest,CapacityWriterTest'`
Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/seed server/forecast-core/src/test/java/com/workloadhub/forecast/seed
git commit -m "feat(seed): calendar, absences and capacity rows

Confirmed holidays and weekends define working days, each person gets
vacation blocks and sick days written as leaves and absences, and the
weekly capacity rows follow the application's own formula."
```

---

### Task 9: Projects with windows, and the weekly rhythm

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/Project.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/ProjectPlanner.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/Rhythm.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/ProjectPlannerTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/RhythmTest.java`

**Interfaces:**
- Consumes: `Person`, `Team`, `WorkFamily`, `SeedCalendar`, `AbsencePlanner.Plan`, `SeedConfig`, `SeedRandom`.
- Produces:
  - `record Project(UUID id, String key, String name, UUID teamId, UUID ownerId, String status, LocalDate windowStart, LocalDate windowEnd, boolean existing, WorkFamily family)` with `boolean activeOn(LocalDate day)` (`ACTIVE` and `windowStart ≤ day < windowEnd`).
  - `ProjectPlanner.plan(List<Team> teams, Map<UUID, Person> people, List<LinkedHashMap<String, Object>> existingProjectRows, SeedConfig cfg, SeedRandom rnd)` returning `List<Project>`; `ProjectPlanner.row(Project p, LinkedHashMap<String, Object> existingRow, long nextTaskNumber, SeedConfig cfg)` returning the `projects` row (the existing row with `next_task_number` updated, or a new row); `ProjectPlanner.projectsFor(Team team, List<Team> teams, List<Project> projects)`: the projects of the team's department team (parent, or itself when it is a department team) plus, for the export's own teams, the projects whose `team_id` is that team.
  - `new Rhythm(SeedConfig cfg, SeedCalendar cal, Map<UUID, Person> people, Map<UUID, AbsencePlanner.Plan> plans, List<Team> teams, SeedRandom rnd)` with `double target(Person p, LocalDate monday)`, `static double season(LocalDate monday, SeedCalendar cal)`, `double ramp(Person p, LocalDate monday)`, `double event(UUID teamId, LocalDate monday)`, `double availability(Person p, LocalDate monday)`, `int arrivals(Person p, LocalDate monday)`, `double estimate(Person p)`, `Team teamOf(Person p)`. A `TEAM_LEADER`'s target is halved (leaders spend half their week leading).

- [ ] **Step 1: Write the failing tests**

`ProjectPlannerTest.java`:

```java
package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectPlannerTest {

    static final SeedConfig CFG = new SeedConfig(52, LocalDate.of(2026, 9, 6), 1, false, 0);

    static Map<UUID, Person> people(Team dept, Team mgr) {
        Map<UUID, Person> m = new HashMap<>();
        for (UUID id : dept.memberIds()) {
            m.put(id, new Person(id, "Head " + id.toString().substring(0, 4), "h@example.test", "Skill Team Leader", "PTE / CT2", "CT2", null, "SKILL_TEAM_LEADER", WorkFamily.COORDINATION, CFG.firstMonday(), null));
        }
        for (UUID id : mgr.memberIds()) {
            m.put(id, new Person(id, "Eng " + id.toString().substring(0, 4), "e@example.test", "Calibration Engineer", "PTE / CT2", "CT2", dept.managerId(), "MEMBER", WorkFamily.CALIBRATION, CFG.firstMonday(), null));
        }
        return m;
    }

    @Test
    void departmentTeamsGetTwoToFourProjectsWithWindowsAndMostlyActive() {
        UUID head = UUID.randomUUID();
        Team dept = new Team(UUID.randomUUID(), "PTE / CT2 Calibration & Testing 2", head, null, List.of(head), true, "CT2");
        Team mgr = new Team(UUID.randomUUID(), "CT2 · M", UUID.randomUUID(), dept.id(), List.of(UUID.randomUUID(), UUID.randomUUID()), false, "CT2");
        List<Project> projects = ProjectPlanner.plan(List.of(dept, mgr), people(dept, mgr), List.of(), CFG, new SeedRandom(11));
        assertTrue(projects.size() >= 2 && projects.size() <= 4, "projects " + projects.size());
        for (Project p : projects) {
            assertTrue(p.key().startsWith("CT2-"), p.key());
            assertEquals(dept.id(), p.teamId());
            assertEquals(head, p.ownerId());
            assertTrue(p.windowEnd().isAfter(p.windowStart()));
            if (p.status().equals("PLANNING")) {
                assertTrue(p.windowStart().isAfter(CFG.lastDay()));
            } else {
                assertEquals("ACTIVE", p.status());
                assertTrue(!p.windowStart().isBefore(CFG.firstMonday()));
            }
            assertEquals(WorkFamily.CALIBRATION, p.family());
        }
        assertEquals(projects.size(), projects.stream().map(Project::key).distinct().count(), "unique keys");
        assertEquals(projects, ProjectPlanner.projectsFor(mgr, List.of(dept, mgr), projects), "manager team sees its department's projects");
    }

    @Test
    void existingProjectsSpanTheWholeHistoryAndKeepTheirRows() {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        UUID team = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        row.put("id", UUID.randomUUID().toString());
        row.put("key", "WH");
        row.put("name", "WorkloadHub");
        row.put("status", "ACTIVE");
        row.put("previous_status", null);
        row.put("archived", false);
        row.put("archived_at", null);
        row.put("archived_by", null);
        row.put("owner_id", owner.toString());
        row.put("team_id", team.toString());
        row.put("version", 0L);
        row.put("description", "The app");
        row.put("next_task_number", 5L);
        row.put("created_at", "2026-09-03T13:59:58");
        row.put("updated_at", "2026-09-03T13:59:58");
        Team existing = new Team(team, "Backend Team", owner, null, List.of(owner), false, null);
        List<Project> projects = ProjectPlanner.plan(List.of(existing), Map.of(owner, new Person(owner, "O", "o@example.test", "Developer", null, null, null, "TEAM_LEADER", WorkFamily.UNKNOWN, CFG.firstMonday(), null)), List.of(row), CFG, new SeedRandom(1));
        Project wh = projects.stream().filter(Project::existing).findFirst().orElseThrow();
        assertEquals(CFG.firstMonday(), wh.windowStart());
        assertTrue(wh.windowEnd().isAfter(CFG.lastDay()));
        LinkedHashMap<String, Object> out = ProjectPlanner.row(wh, row, 42, CFG);
        assertEquals(42L, out.get("next_task_number"));
        assertEquals("The app", out.get("description"));
        assertEquals(List.of(wh), ProjectPlanner.projectsFor(existing, List.of(existing), projects));
    }
}
```

`RhythmTest.java`:

```java
package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RhythmTest {

    static final SeedConfig CFG = new SeedConfig(52, LocalDate.of(2026, 9, 6), 1, false, 0);

    static Rhythm rhythm(Person p, long seed) {
        SeedCalendar cal = AbsencePlannerTest.cal();
        Map<UUID, Person> people = new HashMap<>();
        people.put(p.id(), p);
        Map<UUID, AbsencePlanner.Plan> plans = new HashMap<>();
        plans.put(p.id(), AbsencePlanner.plan(p, cal, CFG, new SeedRandom(seed)));
        Team team = new Team(UUID.randomUUID(), "CT2 · X", p.id(), null, List.of(p.id()), false, "CT2");
        return new Rhythm(CFG, cal, people, plans, List.of(team), new SeedRandom(seed));
    }

    @Test
    void seasonFollowsTheDesign() {
        SeedCalendar cal = AbsencePlannerTest.cal();
        assertEquals(0.55, Rhythm.season(LocalDate.of(2026, 8, 3), cal), 1e-9);   // ISO week 32
        assertEquals(0.50, Rhythm.season(LocalDate.of(2025, 12, 29), cal), 1e-9); // ISO week 1 of 2026
        assertEquals(0.85, Rhythm.season(LocalDate.of(2026, 4, 27), cal), 1e-9);  // Labour Day in the week
        assertEquals(1.0, Rhythm.season(LocalDate.of(2026, 3, 16), cal), 1e-9);
    }

    @Test
    void targetIsBaseTimesFactorsAndLeadersAreHalved() {
        Person eng = AbsencePlannerTest.person(CFG.firstMonday(), null);
        Rhythm r = rhythm(eng, 9);
        LocalDate week = LocalDate.of(2026, 3, 16);
        double t = r.target(eng, week);
        double expected = r.base(eng) * Rhythm.season(week, r.calendar()) * r.ramp(eng, week) * r.event(r.teamOf(eng).id(), week) * r.availability(eng, week);
        assertEquals(expected, t, 1e-9);
        assertTrue(r.base(eng) >= 32 * 0.5 && r.base(eng) <= 32 * 1.3);
        Person lead = eng.withRole("TEAM_LEADER");
        assertEquals(r.base(eng) * 0.5, r.base(lead), 1e-9);
    }

    @Test
    void newcomersRampOverSixWeeks() {
        Person late = AbsencePlannerTest.person(LocalDate.of(2026, 3, 2), null);
        Rhythm r = rhythm(late, 2);
        assertEquals(0.0, r.ramp(late, LocalDate.of(2026, 2, 23)), 1e-9);
        assertEquals(1.0 / 6, r.ramp(late, LocalDate.of(2026, 3, 9)), 1e-9);
        assertEquals(1.0, r.ramp(late, LocalDate.of(2026, 5, 4)), 1e-9);
    }

    @Test
    void arrivalsMatchTheTargetOverManyWeeks() {
        Person eng = AbsencePlannerTest.person(CFG.firstMonday(), null);
        Rhythm r = rhythm(eng, 5);
        double targetSum = 0;
        double estimateSum = 0;
        for (LocalDate m : CFG.mondays()) {
            targetSum += r.target(eng, m);
            int n = r.arrivals(eng, m);
            for (int i = 0; i < n; i++) {
                estimateSum += r.estimate(eng);
            }
        }
        assertTrue(Math.abs(estimateSum - targetSum) < 0.25 * targetSum, "estimates " + estimateSum + " target " + targetSum);
        assertTrue(r.estimate(eng) >= 1.0 && r.estimate(eng) * 2 == Math.floor(r.estimate(eng) * 2), "half-hour steps");
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='ProjectPlannerTest,RhythmTest'`
Expected: compilation errors.

- [ ] **Step 3: Write `Project` and `ProjectPlanner`**

```java
package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.util.UUID;

/** A project and the window in which the generator creates its tasks. The window is not stored: the schema has no project dates. */
public record Project(UUID id, String key, String name, UUID teamId, UUID ownerId, String status,
        LocalDate windowStart, LocalDate windowEnd, boolean existing, WorkFamily family) {

    public boolean activeOn(LocalDate day) {
        return status.equals("ACTIVE") && !day.isBefore(windowStart) && day.isBefore(windowEnd);
    }
}
```

```java
package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Two to four projects per department, named for the department's kind of work, plus the export's own projects. */
public final class ProjectPlanner {

    private record Template(String suffix, String name) {
    }

    private static final Map<WorkFamily, List<Template>> TEMPLATES = new EnumMap<>(WorkFamily.class);

    static {
        TEMPLATES.put(WorkFamily.CALIBRATION, List.of(new Template("CAL", "%s calibration campaign %d"),
                new Template("DATASET", "%s dataset consolidation %d"), new Template("MAP", "%s engine mapping wave %d")));
        TEMPLATES.put(WorkFamily.DATA, List.of(new Template("DATA", "%s data platform %d"),
                new Template("AI", "%s AI assistant %d"), new Template("DASH", "%s analytics dashboard %d")));
        TEMPLATES.put(WorkFamily.SUPPORT, List.of(new Template("OPS", "%s operations %d"),
                new Template("ONB", "%s onboarding cycle %d")));
        TEMPLATES.put(WorkFamily.SYSTEMS, List.of(new Template("SYS", "%s system design %d"),
                new Template("REQ", "%s requirements baseline %d"), new Template("DIAG", "%s diagnostics %d")));
        TEMPLATES.put(WorkFamily.ELECTRONICS, List.of(new Template("EE", "%s EE integration %d"),
                new Template("SW", "%s software release %d"), new Template("HW", "%s hardware bring-up %d")));
        TEMPLATES.put(WorkFamily.VALIDATION, List.of(new Template("VAL", "%s validation wave %d"),
                new Template("HOMOL", "%s homologation %d"), new Template("FLEET", "%s fleet test %d")));
        TEMPLATES.put(WorkFamily.DESIGN, List.of(new Template("SIM", "%s simulation study %d"),
                new Template("DMU", "%s DMU release %d"), new Template("CFD", "%s CFD campaign %d")));
        TEMPLATES.put(WorkFamily.COORDINATION, List.of(new Template("PMO", "%s coordination %d")));
        TEMPLATES.put(WorkFamily.UNKNOWN, List.of(new Template("PRJ", "%s project %d")));
    }

    private ProjectPlanner() {
    }

    /** The dominant family of a team's members (department teams: everyone in that department code). */
    static WorkFamily dominantFamily(Team team, List<Team> teams, Map<UUID, Person> people) {
        Map<WorkFamily, Integer> votes = new EnumMap<>(WorkFamily.class);
        for (Team t : teams) {
            boolean inDept = t.id().equals(team.id()) || (team.department() && team.id().equals(t.parentId()));
            if (!inDept) {
                continue;
            }
            for (UUID id : t.memberIds()) {
                Person p = people.get(id);
                if (p != null && !p.family().isUnknown()) {
                    votes.merge(p.family(), 1, Integer::sum);
                }
            }
        }
        return votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(WorkFamily.SYSTEMS);
    }

    public static List<Project> plan(List<Team> teams, Map<UUID, Person> people,
            List<LinkedHashMap<String, Object>> existingProjectRows, SeedConfig cfg, SeedRandom rnd) {
        List<Project> out = new ArrayList<>();
        for (LinkedHashMap<String, Object> row : existingProjectRows) {
            UUID teamId = row.get("team_id") == null ? null : UUID.fromString((String) row.get("team_id"));
            out.add(new Project(UUID.fromString((String) row.get("id")), (String) row.get("key"), (String) row.get("name"),
                    teamId, UUID.fromString((String) row.get("owner_id")), String.valueOf(row.get("status")),
                    cfg.firstMonday(), cfg.lastDay().plusWeeks(1), true, WorkFamily.UNKNOWN));
        }
        List<LocalDate> mondays = cfg.mondays();
        for (Team team : teams) {
            if (!team.department()) {
                continue;
            }
            String code = team.deptCode() == null ? "GEN" : team.deptCode();
            WorkFamily family = dominantFamily(team, teams, people);
            List<Template> templates = TEMPLATES.get(family);
            int count = rnd.between(2, 4);
            Set<String> used = new HashSet<>();
            for (int i = 0; i < count; i++) {
                Template t = templates.get(i % templates.size());
                int n = 1;
                String key = code + "-" + t.suffix();
                while (used.contains(key)) {
                    n++;
                    key = code + "-" + t.suffix() + n;
                }
                used.add(key);
                boolean planning = rnd.chance(1.0 / 6);
                LocalDate start;
                LocalDate end;
                String status;
                if (planning) {
                    start = cfg.lastDay().plusWeeks(rnd.between(1, 8));
                    end = start.plusWeeks(rnd.between(12, 40));
                    status = "PLANNING";
                } else {
                    int startIndex = rnd.between(0, Math.max(0, (int) (mondays.size() * 0.6) - 1));
                    start = mondays.get(startIndex);
                    end = start.plusWeeks(rnd.between(12, 40));
                    if (end.isAfter(cfg.lastDay().plusWeeks(1))) {
                        end = cfg.lastDay().plusWeeks(1);
                    }
                    status = "ACTIVE";
                }
                out.add(new Project(rnd.uuid(), key, String.format(t.name(), code, n), team.id(), team.managerId(),
                        status, start, end, false, family));
            }
        }
        return out;
    }

    /** The projects a team's members work on. */
    public static List<Project> projectsFor(Team team, List<Team> teams, List<Project> projects) {
        UUID deptId = team.department() ? team.id() : team.parentId();
        List<Project> out = new ArrayList<>();
        for (Project p : projects) {
            if (p.teamId() != null && (p.teamId().equals(deptId) || p.teamId().equals(team.id()))) {
                out.add(p);
            }
        }
        return out;
    }

    public static LinkedHashMap<String, Object> row(Project p, LinkedHashMap<String, Object> existingRow, long nextTaskNumber, SeedConfig cfg) {
        LinkedHashMap<String, Object> row = existingRow == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existingRow);
        if (existingRow == null) {
            String created = cfg.firstMonday().atTime(9, 0).toString();
            row.put("archived", false);
            row.put("archived_at", null);
            row.put("created_at", created);
            row.put("next_task_number", nextTaskNumber);
            row.put("updated_at", created);
            row.put("version", 0L);
            row.put("archived_by", null);
            row.put("id", p.id().toString());
            row.put("owner_id", p.ownerId() == null ? null : p.ownerId().toString());
            row.put("team_id", p.teamId() == null ? null : p.teamId().toString());
            row.put("key", p.key());
            row.put("previous_status", null);
            row.put("status", p.status());
            row.put("name", p.name());
            row.put("description", null);
        } else {
            row.put("next_task_number", nextTaskNumber);
        }
        return row;
    }
}
```

Existing projects may have `owner_id` null in odd exports; `row.get("owner_id")` is then null and `UUID.fromString` fails: guard with the same null check as `team_id` if the real export shows one.

- [ ] **Step 4: Write `Rhythm`**

```java
package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The weekly target hours of each member and the arrivals that realise it (design section 4.4). */
public final class Rhythm {

    private static final int RAMP_WEEKS = 6;
    private static final double EVENT_FACTOR = 1.4;

    private final SeedConfig cfg;
    private final SeedCalendar cal;
    private final Map<UUID, Person> people;
    private final Map<UUID, AbsencePlanner.Plan> plans;
    private final List<Team> teams;
    private final SeedRandom rnd;
    private final Map<UUID, Double> baseFactor = new HashMap<>();
    private final Map<UUID, List<LocalDate>> eventWeeks = new HashMap<>();
    private final Map<UUID, Team> teamOf = new HashMap<>();

    public Rhythm(SeedConfig cfg, SeedCalendar cal, Map<UUID, Person> people, Map<UUID, AbsencePlanner.Plan> plans,
            List<Team> teams, SeedRandom rnd) {
        this.cfg = cfg;
        this.cal = cal;
        this.people = people;
        this.plans = plans;
        this.teams = teams;
        this.rnd = rnd;
        List<UUID> ids = new ArrayList<>(people.keySet());
        ids.sort(null);
        for (UUID id : ids) {
            baseFactor.put(id, Math.max(0.5, Math.min(1.3, 1.0 + 0.15 * rnd.gaussian())));
        }
        List<LocalDate> mondays = cfg.mondays();
        int events = Math.max(1, Math.round(3f * cfg.weeks() / 52f));
        for (Team t : teams) {
            List<LocalDate> weeks = new ArrayList<>();
            for (int e = 0; e < events; e++) {
                LocalDate start = mondays.get(rnd.between(0, mondays.size() - 1));
                weeks.add(start);
                weeks.add(start.plusWeeks(1));
            }
            eventWeeks.put(t.id(), weeks);
            for (UUID member : t.memberIds()) {
                // a manager team wins over a department team for people in both
                if (!teamOf.containsKey(member) || teamOf.get(member).department()) {
                    teamOf.put(member, t);
                }
            }
        }
    }

    public SeedCalendar calendar() {
        return cal;
    }

    public Team teamOf(Person p) {
        return teamOf.get(p.id());
    }

    /** family mean × personal factor, halved for team leaders. */
    public double base(Person p) {
        double b = p.family().weeklyHours * baseFactor.getOrDefault(p.id(), 1.0);
        return p.role().equals("TEAM_LEADER") ? b * 0.5 : b;
    }

    public static double season(LocalDate monday, SeedCalendar cal) {
        int week = monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        if (week >= 31 && week <= 34) {
            return 0.55;
        }
        if (week == 52 || week == 1) {
            return 0.50;
        }
        return cal.workingDays(monday) < 5 ? 0.85 : 1.0;
    }

    public double ramp(Person p, LocalDate monday) {
        if (!p.joined().isAfter(cfg.firstMonday())) {
            return 1.0;
        }
        long weeks = ChronoUnit.WEEKS.between(SeedConfig.mondayOf(p.joined()), monday);
        if (weeks < 0) {
            return 0.0;
        }
        return Math.min(1.0, weeks / (double) RAMP_WEEKS);
    }

    public double event(UUID teamId, LocalDate monday) {
        return eventWeeks.getOrDefault(teamId, List.of()).contains(monday) ? EVENT_FACTOR : 1.0;
    }

    public double availability(Person p, LocalDate monday) {
        AbsencePlanner.Plan plan = plans.get(p.id());
        int present = 0;
        for (int i = 0; i < 5; i++) {
            if (plan.hoursPresent(p, monday.plusDays(i)) > 0) {
                present++;
            }
        }
        return present / 5.0;
    }

    public double target(Person p, LocalDate monday) {
        Team t = teamOf(p);
        return base(p) * season(monday, cal) * ramp(p, monday) * (t == null ? 1.0 : event(t.id(), monday))
                * availability(p, monday);
    }

    public int arrivals(Person p, LocalDate monday) {
        return rnd.poisson(target(p, monday) / p.family().medianEstimate);
    }

    /** Log-normal around the family median, half-hour steps, at least one hour. */
    public double estimate(Person p) {
        double e = rnd.lognormal(p.family().medianEstimate, 0.6);
        return Math.max(1.0, Math.round(e * 2) / 2.0);
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='ProjectPlannerTest,RhythmTest'`
Expected: all pass. `arrivalsMatchTheTargetOverManyWeeks` compares a Poisson sum with its expectation over 52 weeks; the 25 % tolerance holds for seed 5. If it fails, print both sums; a gap beyond 25 % means the estimate distribution's mean, not median, is being used, or the arrival count uses hours instead of tasks.

- [ ] **Step 6: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/seed server/forecast-core/src/test/java/com/workloadhub/forecast/seed
git commit -m "feat(seed): department projects with windows and the weekly rhythm

Each department gets projects named for its kind of work and active in
a window; each member's weekly target follows base, season, ramp, team
events and availability, and arrivals are drawn to realise it."
```

---

### Task 10: The work queue: tasks, transitions and time logs written day by day

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/Reference.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/Rows.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/WorkQueue.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/WorkQueueTest.java`

**Interfaces:**
- Consumes: everything from Tasks 7 to 9.
- Produces:
  - `record Reference(Map<String, UUID> statusIds, Map<String, UUID> typeIds)` built by `Reference.from(statusRows, typeRows)` (keys are the `name` columns: `To Do`, `In Progress`, `In Review`, `Blocked`, `Done`; `Story`, `Task`, `New Feature`, `Improvement`, `Change Request`, `Bug`, `Incident`, `Spike`, `Test`, `Risk`, `Epic`, `Sub-task`), failing fast when one is missing.
  - `Rows`: `task(...)`, `history(...)`, `timeLog(...)` builders that return `LinkedHashMap<String, Object>` with every column of the export in its order.
  - `record WorkQueue.Rates(double backlog, double leaderAssigned, double subTask, double review, double blocked, double reopen, double unlogged)` with `Rates.DEFAULT = (0.60, 0.25, 0.15, 0.10, 0.03, 0.04, 0.05)`; `backlog` and `leaderAssigned` are the shares of the non-self-picked tasks (`selfPicked` comes from the family).
  - `WorkQueue.run(SeedConfig cfg, SeedCalendar cal, List<Person> people, Map<UUID, AbsencePlanner.Plan> plans, List<Team> teams, List<Project> projects, Rhythm rhythm, Reference ref, Rates rates, SeedRandom rnd)` returning `WorkQueue.Result(List<LinkedHashMap<String, Object>> taskRows, List<LinkedHashMap<String, Object>> historyRows, List<LinkedHashMap<String, Object>> timeLogRows, Map<UUID, Long> nextTaskNumber, Map<UUID, Map<LocalDate, Double>> assignedHours)`, where `assignedHours` is per member and Monday the sum of estimates assigned that week.

- [ ] **Step 1: Write the failing test**

```java
package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class WorkQueueTest {

    static final SeedConfig CFG = new SeedConfig(16, LocalDate.of(2026, 9, 6), 1, false, 0);

    record World(List<Person> people, Map<UUID, AbsencePlanner.Plan> plans, List<Team> teams, List<Project> projects,
            Rhythm rhythm, Reference ref, SeedCalendar cal) {
    }

    static World world(long seed) {
        SeedRandom rnd = new SeedRandom(seed);
        SeedCalendar cal = AbsencePlannerTest.cal();
        UUID lead = UUID.fromString("30000000-0000-0000-0000-000000000001");
        UUID e1 = UUID.fromString("30000000-0000-0000-0000-000000000002");
        UUID e2 = UUID.fromString("30000000-0000-0000-0000-000000000003");
        List<Person> people = List.of(
                new Person(lead, "Lead One", "l@example.test", "Team Leader Calibration", "PTE / CT2", "CT2", null, "TEAM_LEADER", WorkFamily.CALIBRATION, CFG.firstMonday(), null),
                new Person(e1, "Eng Two", "a@example.test", "Calibration Engineer", "PTE / CT2", "CT2", lead, "MEMBER", WorkFamily.CALIBRATION, CFG.firstMonday(), null),
                new Person(e2, "Eng Three", "b@example.test", "Data Analyst & SW Developer", "PTE / CT2", "CT2", lead, "MEMBER", WorkFamily.DATA, CFG.firstMonday().plusWeeks(3), null));
        Map<UUID, Person> byId = new HashMap<>();
        Map<UUID, AbsencePlanner.Plan> plans = new HashMap<>();
        for (Person p : people) {
            byId.put(p.id(), p);
            plans.put(p.id(), AbsencePlanner.plan(p, cal, CFG, rnd));
        }
        Team dept = new Team(UUID.fromString("40000000-0000-0000-0000-000000000001"), "PTE / CT2", lead, null, List.of(lead), true, "CT2");
        Team team = new Team(UUID.fromString("40000000-0000-0000-0000-000000000002"), "CT2 · Lead One", lead, dept.id(), List.of(lead, e1, e2), false, "CT2");
        List<Team> teams = List.of(dept, team);
        List<Project> projects = ProjectPlanner.plan(teams, byId, List.of(), CFG, rnd);
        Rhythm rhythm = new Rhythm(CFG, cal, byId, plans, teams, rnd);
        return new World(people, plans, teams, projects, rhythm, reference(), cal);
    }

    static Reference reference() {
        Map<String, UUID> statuses = new HashMap<>();
        for (String s : List.of("Open", "To Do", "In Progress", "In Review", "Testing", "Done", "Closed", "Blocked", "On Hold")) {
            statuses.put(s, UUID.nameUUIDFromBytes(("status:" + s).getBytes()));
        }
        Map<String, UUID> types = new HashMap<>();
        for (String t : List.of("Story", "Bug", "Task", "Epic", "Improvement", "New Feature", "Change Request", "Incident", "Risk", "Spike", "Test", "Sub-task")) {
            types.put(t, UUID.nameUUIDFromBytes(("type:" + t).getBytes()));
        }
        return new Reference(statuses, types);
    }

    static WorkQueue.Result run(long seed, WorkQueue.Rates rates) {
        World w = world(seed);
        return WorkQueue.run(CFG, w.cal(), w.people(), w.plans(), w.teams(), w.projects(), w.rhythm(), w.ref(), rates, new SeedRandom(seed + 1));
    }

    static LocalDateTime ts(Object v) {
        return v == null ? null : LocalDateTime.parse((String) v);
    }

    @Property(tries = 15)
    boolean lifecycleInvariantsHold(@ForAll @LongRange(min = 1, max = 5000) long seed) {
        World w = world(seed);
        WorkQueue.Result r = WorkQueue.run(CFG, w.cal(), w.people(), w.plans(), w.teams(), w.projects(), w.rhythm(), w.ref(), WorkQueue.Rates.DEFAULT, new SeedRandom(seed + 1));
        Map<UUID, Person> byId = new HashMap<>();
        w.people().forEach(p -> byId.put(p.id(), p));
        Map<String, List<LinkedHashMap<String, Object>>> logsByTask = new HashMap<>();
        for (var log : r.timeLogRows()) {
            logsByTask.computeIfAbsent((String) log.get("task_id"), k -> new ArrayList<>()).add(log);
        }
        Map<String, List<LinkedHashMap<String, Object>>> historyByTask = new HashMap<>();
        for (var h : r.historyRows()) {
            historyByTask.computeIfAbsent((String) h.get("task_id"), k -> new ArrayList<>()).add(h);
        }
        UUID done = w.ref().statusIds().get("Done");
        for (var t : r.taskRows()) {
            String id = (String) t.get("id");
            LocalDateTime created = ts(t.get("created_date"));
            LocalDateTime started = ts(t.get("started_date"));
            LocalDateTime finished = ts(t.get("finished_date"));
            List<LinkedHashMap<String, Object>> logs = logsByTask.getOrDefault(id, List.of());
            List<LinkedHashMap<String, Object>> history = historyByTask.getOrDefault(id, List.of());
            String assignee = (String) t.get("assignee_id");
            // 2. order of events
            LocalDateTime assigned = history.stream().filter(h -> "assignee".equals(h.get("field_name")))
                    .map(h -> ts(h.get("changed_at"))).max(LocalDateTime::compareTo).orElse(created);
            if (created.isAfter(assigned)) return false;
            if (started != null && assigned.isAfter(started)) return false;
            double logged = 0;
            for (var log : logs) {
                LocalDate day = LocalDate.parse((String) log.get("log_date"));
                if (started != null && day.isBefore(started.toLocalDate())) return false;
                if (finished != null && day.isAfter(finished.toLocalDate())) return false;
                // 3. no log on a day the assignee is absent, on a weekend or a holiday
                Person p = byId.get(UUID.fromString((String) log.get("user_id")));
                if (w.plans().get(p.id()).hoursPresent(p, day) == 0) return false;
                if (!log.get("user_id").equals(assignee)) return false;
                logged += (Double) log.get("hours");
            }
            // 4. remaining
            double estimate = t.get("original_estimate_hrs") == null ? 0 : (Double) t.get("original_estimate_hrs");
            Double remaining = (Double) t.get("remaining_estimate_hrs");
            boolean isDone = done.toString().equals(t.get("task_status_id"));
            if (isDone && (remaining == null || remaining != 0.0)) return false;
            if (!logs.isEmpty() && !isDone && Math.abs(remaining - Math.max(0, estimate - logged)) > 1e-6) return false;
            if (isDone && finished == null) return false;
            // 5. assignee transitions end at the current assignee, written as the display name
            for (var h : history) {
                if ("assignee".equals(h.get("field_name")) && h.get("new_value") != null) {
                    Person p = byId.get(UUID.fromString(assignee));
                    if (!p.fullName().equals(h.get("new_value"))) return false;
                }
                LocalDateTime changed = ts(h.get("changed_at"));
                if (changed.isBefore(created)) return false;
            }
            // status history is ordered
            List<LocalDateTime> statusTimes = history.stream().filter(h -> "status".equals(h.get("field_name"))).map(h -> ts(h.get("changed_at"))).toList();
            for (int i = 1; i < statusTimes.size(); i++) {
                if (statusTimes.get(i).isBefore(statusTimes.get(i - 1))) return false;
            }
        }
        // keys unique and numbered per project
        long keys = r.taskRows().stream().map(t -> t.get("key")).distinct().count();
        return keys == r.taskRows().size();
    }

    @Test
    void backlogTasksCarryOneAssigneeTransitionAndOthersNone() {
        WorkQueue.Result r = run(3, new WorkQueue.Rates(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
        Map<String, Long> assigneeRows = new HashMap<>();
        r.historyRows().stream().filter(h -> "assignee".equals(h.get("field_name")))
                .forEach(h -> assigneeRows.merge((String) h.get("task_id"), 1L, Long::sum));
        long epics = r.taskRows().stream().filter(t -> t.get("parent_task_id") == null && t.get("original_estimate_hrs") == null).count();
        long withRow = r.taskRows().stream().filter(t -> assigneeRows.containsKey(t.get("id"))).count();
        // every non-epic task of a member with selfPicked share is either self-picked (no row) or backlog (one row)
        assertTrue(withRow > 0);
        assertTrue(r.taskRows().stream().filter(t -> assigneeRows.containsKey(t.get("id")))
                .allMatch(t -> assigneeRows.get(t.get("id")) == 1L && ts(t.get("created_date")).isBefore(ts(historyOf(r, t).get("changed_at")))));
        assertTrue(epics >= 1, "epics created per project");
    }

    static LinkedHashMap<String, Object> historyOf(WorkQueue.Result r, LinkedHashMap<String, Object> task) {
        return r.historyRows().stream().filter(h -> "assignee".equals(h.get("field_name")) && task.get("id").equals(h.get("task_id"))).findFirst().orElseThrow();
    }

    @Test
    void unloggedTasksFinishWithoutLogsAndZeroRemaining() {
        WorkQueue.Result r = run(4, new WorkQueue.Rates(0.6, 0.25, 0.0, 0.0, 0.0, 0.0, 1.0));
        UUID done = reference().statusIds().get("Done");
        assertTrue(r.timeLogRows().isEmpty(), "no logs at all when every task is unlogged");
        long finished = r.taskRows().stream().filter(t -> done.toString().equals(t.get("task_status_id"))).count();
        assertTrue(finished > 0);
        r.taskRows().stream().filter(t -> done.toString().equals(t.get("task_status_id"))).forEach(t -> {
            assertEquals(0.0, t.get("remaining_estimate_hrs"));
            assertNotNull(t.get("finished_date"));
        });
    }

    @Test
    void reopenedTasksAreMarkedAndFinishTwice() {
        WorkQueue.Result r = run(5, new WorkQueue.Rates(0.6, 0.25, 0.0, 0.0, 0.0, 1.0, 0.0));
        List<LinkedHashMap<String, Object>> reopened = r.taskRows().stream().filter(t -> Boolean.TRUE.equals(t.get("reopened_from_done"))).toList();
        assertFalse(reopened.isEmpty());
        UUID done = reference().statusIds().get("Done");
        for (var t : reopened) {
            assertNotNull(t.get("last_reopened_at"));
            long doneTransitions = r.historyRows().stream().filter(h -> t.get("id").equals(h.get("task_id"))
                    && "status".equals(h.get("field_name")) && "Done".equals(h.get("new_value"))).count();
            assertTrue(doneTransitions >= 1);
            if (done.toString().equals(t.get("task_status_id"))) {
                assertEquals(2, doneTransitions, "finished twice");
            }
        }
    }

    @Test
    void openWorkRemainsAtTheEndAndAssignedHoursMatchEstimates() {
        WorkQueue.Result r = run(6, WorkQueue.Rates.DEFAULT);
        UUID done = reference().statusIds().get("Done");
        long open = r.taskRows().stream().filter(t -> !done.toString().equals(t.get("task_status_id")) && t.get("assignee_id") != null).count();
        assertTrue(open > 0, "tasks still open at the as-of date");
        assertTrue(r.taskRows().stream().anyMatch(t -> t.get("assignee_id") == null), "backlog left unassigned at the end");
        double fromRows = 0;
        for (var t : r.taskRows()) {
            if (t.get("assignee_id") != null && t.get("original_estimate_hrs") != null) {
                fromRows += (Double) t.get("original_estimate_hrs");
            }
        }
        double fromMap = r.assignedHours().values().stream().flatMap(m -> m.values().stream()).mapToDouble(Double::doubleValue).sum();
        assertEquals(fromRows, fromMap, 1e-6);
        assertNull(r.taskRows().stream().filter(t -> t.get("original_estimate_hrs") == null).findFirst().orElseThrow().get("remaining_estimate_hrs"), "epics carry no estimate");
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest=WorkQueueTest`
Expected: compilation errors.

- [ ] **Step 3: Write `Reference` and `Rows`**

```java
package com.workloadhub.forecast.seed;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ids of the reference rows the tasks point at, by name. */
public record Reference(Map<String, UUID> statusIds, Map<String, UUID> typeIds) {

    public static final List<String> STATUSES = List.of("To Do", "In Progress", "In Review", "Blocked", "Done");
    public static final List<String> TYPES = List.of("Story", "Task", "New Feature", "Improvement", "Change Request",
            "Bug", "Incident", "Spike", "Test", "Risk", "Epic", "Sub-task");

    public static Reference from(List<LinkedHashMap<String, Object>> statusRows, List<LinkedHashMap<String, Object>> typeRows) {
        Map<String, UUID> statuses = new HashMap<>();
        for (LinkedHashMap<String, Object> r : statusRows) {
            statuses.put((String) r.get("name"), UUID.fromString((String) r.get("id")));
        }
        Map<String, UUID> types = new HashMap<>();
        for (LinkedHashMap<String, Object> r : typeRows) {
            types.put((String) r.get("name"), UUID.fromString((String) r.get("id")));
        }
        for (String s : STATUSES) {
            if (!statuses.containsKey(s)) {
                throw new IllegalArgumentException("task_statuses lacks '" + s + "'");
            }
        }
        for (String t : TYPES) {
            if (!types.containsKey(t)) {
                throw new IllegalArgumentException("task_types lacks '" + t + "'");
            }
        }
        return new Reference(statuses, types);
    }

    /** True when the rows name every status and type the generator writes. */
    public static boolean covers(List<LinkedHashMap<String, Object>> statusRows, List<LinkedHashMap<String, Object>> typeRows) {
        try {
            from(statusRows, typeRows);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public UUID status(String name) {
        return statusIds.get(name);
    }

    public UUID type(String name) {
        return typeIds.get(name);
    }
}
```

```java
package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Row builders with every column of the export, in the export's column order. */
final class Rows {

    private Rows() {
    }

    static LinkedHashMap<String, Object> task(UUID id, String key, String title, String description, LocalDate dueDate,
            String priority, UUID projectId, UUID assigneeId, UUID reporterId, long taskNumber, LocalDateTime createdDate,
            LocalDate plannedWeek, UUID typeId, UUID parentId, UUID statusId, Double estimate) {
        LinkedHashMap<String, Object> t = new LinkedHashMap<>();
        t.put("id", id.toString());
        t.put("key", key);
        t.put("title", title);
        t.put("version", 0L);
        t.put("archived", false);
        t.put("due_date", dueDate == null ? null : dueDate.toString());
        t.put("priority", priority);
        t.put("created_at", createdDate.toString());
        t.put("project_id", projectId.toString());
        t.put("updated_at", createdDate.toString());
        t.put("archived_at", null);
        t.put("assignee_id", assigneeId == null ? null : assigneeId.toString());
        t.put("description", description);
        t.put("reporter_id", reporterId.toString());
        t.put("task_number", taskNumber);
        t.put("created_date", createdDate.toString());
        t.put("planned_week", plannedWeek == null ? null : plannedWeek.toString());
        t.put("started_date", null);
        t.put("task_type_id", typeId.toString());
        t.put("finished_date", null);
        t.put("parent_task_id", parentId == null ? null : parentId.toString());
        t.put("task_status_id", statusId.toString());
        t.put("last_reopened_at", null);
        t.put("reopened_from_done", false);
        t.put("original_estimate_hrs", estimate);
        t.put("remaining_estimate_hrs", estimate);
        return t;
    }

    static LinkedHashMap<String, Object> history(UUID id, UUID taskId, UUID userId, String field, String oldValue,
            String newValue, LocalDateTime changedAt) {
        LinkedHashMap<String, Object> h = new LinkedHashMap<>();
        h.put("id", id.toString());
        h.put("task_id", taskId.toString());
        h.put("user_id", userId.toString());
        h.put("new_value", newValue);
        h.put("old_value", oldValue);
        h.put("changed_at", changedAt.toString());
        h.put("created_at", changedAt.toString());
        h.put("field_name", field);
        h.put("updated_at", changedAt.toString());
        return h;
    }

    static LinkedHashMap<String, Object> timeLog(UUID id, UUID taskId, UUID userId, double hours, LocalDate day, String note) {
        LinkedHashMap<String, Object> l = new LinkedHashMap<>();
        LocalDateTime stamp = day.atTime(17, 30);
        l.put("id", id.toString());
        l.put("note", note);
        l.put("hours", hours);
        l.put("task_id", taskId.toString());
        l.put("user_id", userId.toString());
        l.put("log_date", day.toString());
        l.put("created_at", stamp.toString());
        l.put("updated_at", stamp.toString());
        return l;
    }
}
```

- [ ] **Step 4: Write `WorkQueue`**

```java
package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Simulates each counted member day by day: tasks arrive per the rhythm, at most three are worked at
 * once, hours are logged on present days, and the rows are written the way WorkloadHub writes them.
 */
public final class WorkQueue {

    public record Rates(double backlog, double leaderAssigned, double subTask, double review, double blocked,
            double reopen, double unlogged) {
        public static final Rates DEFAULT = new Rates(0.60, 0.25, 0.15, 0.10, 0.03, 0.04, 0.05);
    }

    public record Result(List<LinkedHashMap<String, Object>> taskRows, List<LinkedHashMap<String, Object>> historyRows,
            List<LinkedHashMap<String, Object>> timeLogRows, Map<UUID, Long> nextTaskNumber,
            Map<UUID, Map<LocalDate, Double>> assignedHours) {
    }

    private static final int MAX_ACTIVE = 3;
    private static final int FUTURE_WEEKS = 3;
    private static final double HOURS_PER_DAY_PER_TASK = 8.0 / 2.5;

    /** One task moving through the queue. */
    private static final class Work {
        final LinkedHashMap<String, Object> row;
        final UUID id;
        final double estimate;
        final LocalDateTime assignedAt;
        double actual;
        double logged;
        boolean started;
        boolean unlogged;
        int unloggedDaysLeft;
        int blockedLeft;
        boolean wasBlocked;
        int reviewLeft;
        boolean inReview;
        LocalDate reopenOn;
        boolean reopened;
        String status;

        Work(LinkedHashMap<String, Object> row, double estimate, double actual, LocalDateTime assignedAt) {
            this.row = row;
            this.id = UUID.fromString((String) row.get("id"));
            this.estimate = estimate;
            this.actual = actual;
            this.assignedAt = assignedAt;
            this.status = "To Do";
        }

        boolean finished() {
            return logged >= actual - 1e-9;
        }
    }

    /** A task decided in advance for one week. */
    private record Arrival(LocalDate assignDay, LocalDateTime assignAt, double estimate, String typeName, String family,
            String priority, String mode, Project project) {
    }

    private final SeedConfig cfg;
    private final SeedCalendar cal;
    private final Map<UUID, AbsencePlanner.Plan> plans;
    private final Rhythm rhythm;
    private final Reference ref;
    private final Rates rates;
    private final SeedRandom rnd;
    private final Map<UUID, Person> people = new HashMap<>();
    private final Map<UUID, Long> nextNumber = new HashMap<>();
    private final Map<UUID, List<UUID>> epicsByProject = new HashMap<>();
    private final List<LinkedHashMap<String, Object>> taskRows = new ArrayList<>();
    private final List<LinkedHashMap<String, Object>> historyRows = new ArrayList<>();
    private final List<LinkedHashMap<String, Object>> timeLogRows = new ArrayList<>();
    private final Map<UUID, Map<LocalDate, Double>> assignedHours = new HashMap<>();
    private final List<Team> teams;
    private final List<Project> projects;

    private WorkQueue(SeedConfig cfg, SeedCalendar cal, List<Person> personList, Map<UUID, AbsencePlanner.Plan> plans,
            List<Team> teams, List<Project> projects, Rhythm rhythm, Reference ref, Rates rates, SeedRandom rnd) {
        this.cfg = cfg;
        this.cal = cal;
        this.plans = plans;
        this.teams = teams;
        this.projects = projects;
        this.rhythm = rhythm;
        this.ref = ref;
        this.rates = rates;
        this.rnd = rnd;
        for (Person p : personList) {
            people.put(p.id(), p);
        }
        for (Project p : projects) {
            nextNumber.put(p.id(), 1L);
        }
    }

    public static Result run(SeedConfig cfg, SeedCalendar cal, List<Person> people, Map<UUID, AbsencePlanner.Plan> plans,
            List<Team> teams, List<Project> projects, Rhythm rhythm, Reference ref, Rates rates, SeedRandom rnd) {
        WorkQueue q = new WorkQueue(cfg, cal, people, plans, teams, projects, rhythm, ref, rates, rnd);
        q.createEpics();
        List<Person> counted = new ArrayList<>(people);
        counted.removeIf(p -> !p.counted());
        counted.sort(Comparator.comparing(p -> p.id().toString()));
        for (Person p : counted) {
            q.simulate(p);
        }
        return new Result(q.taskRows, q.historyRows, q.timeLogRows, q.nextNumber, q.assignedHours);
    }

    private void createEpics() {
        List<Project> sorted = new ArrayList<>(projects);
        sorted.sort(Comparator.comparing(Project::key));
        for (Project project : sorted) {
            if (!project.status().equals("ACTIVE") || project.ownerId() == null || !people.containsKey(project.ownerId())) {
                continue;
            }
            int n = rnd.between(1, 3);
            List<UUID> epics = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                LocalDate day = nextWorkingDay(project.windowStart());
                LocalDateTime at = rnd.at(day, 9, 11);
                long number = nextNumber.merge(project.id(), 1L, Long::sum) - 1;
                UUID id = rnd.uuid();
                LinkedHashMap<String, Object> row = Rows.task(id, project.key() + "-" + number, project.name() + " epic " + i,
                        "Container for the work of " + project.name(), null, "MEDIUM", project.id(), project.ownerId(),
                        project.ownerId(), number, at, SeedConfig.mondayOf(day), ref.type("Epic"), null, ref.status("In Progress"), null);
                row.put("started_date", at.toString());
                taskRows.add(row);
                epics.add(id);
            }
            epicsByProject.put(project.id(), epics);
        }
    }

    private LocalDate nextWorkingDay(LocalDate d) {
        LocalDate x = d;
        while (!cal.isWorkingDay(x)) {
            x = x.plusDays(1);
        }
        return x;
    }

    private List<Arrival> planArrivals(Person p, Team team) {
        List<Project> candidates = ProjectPlanner.projectsFor(team, teams, projects);
        List<Arrival> out = new ArrayList<>();
        AbsencePlanner.Plan plan = plans.get(p.id());
        List<LocalDate> mondays = new ArrayList<>(cfg.mondays());
        for (int i = 1; i <= FUTURE_WEEKS; i++) {
            mondays.add(SeedConfig.mondayOf(cfg.lastDay()).plusWeeks(i));
        }
        for (LocalDate monday : mondays) {
            boolean future = monday.isAfter(cfg.lastDay());
            int n = rhythm.arrivals(p, monday);
            if (n == 0) {
                continue;
            }
            List<LocalDate> present = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                LocalDate d = monday.plusDays(i);
                boolean ok = future ? cal.isWorkingDay(d) : plan.hoursPresent(p, d) > 0 && !d.isAfter(cfg.lastDay());
                if (ok) {
                    present.add(d);
                }
            }
            if (present.isEmpty()) {
                continue;
            }
            List<Project> active = candidates.stream().filter(pr -> pr.activeOn(monday)).toList();
            if (active.isEmpty()) {
                continue;
            }
            for (int i = 0; i < n; i++) {
                LocalDate day = rnd.pick(present);
                WorkFamily f = p.family();
                String family = rnd.pick(List.of("delivery", "defect", "support"), new double[] {f.delivery + f.container, f.defect, f.support});
                String type = switch (family) {
                    case "defect" -> rnd.pick(List.of("Bug", "Incident"), new double[] {0.8, 0.2});
                    case "support" -> rnd.pick(List.of("Spike", "Test", "Risk"), new double[] {0.4, 0.4, 0.2});
                    default -> rnd.pick(List.of("Story", "Task", "New Feature", "Improvement", "Change Request"), new double[] {0.35, 0.35, 0.1, 0.1, 0.1});
                };
                String priority = family.equals("defect")
                        ? rnd.pick(List.of("HIGHEST", "HIGH", "MEDIUM", "LOW"), new double[] {0.2, 0.4, 0.3, 0.1})
                        : rnd.pick(List.of("HIGHEST", "HIGH", "MEDIUM", "LOW", "LOWEST"), new double[] {0.05, 0.2, 0.5, 0.2, 0.05});
                double nonSelf = 1.0 - f.selfPicked;
                double share = rates.backlog + rates.leaderAssigned;
                double backlog = share == 0 ? 0 : nonSelf * rates.backlog / share;
                double leader = share == 0 ? 0 : nonSelf * rates.leaderAssigned / share;
                String mode = rnd.pick(List.of("backlog", "leader", "self"), new double[] {backlog, leader, f.selfPicked});
                if (future && !mode.equals("backlog")) {
                    continue; // only backlog tasks exist before their assignment week
                }
                out.add(new Arrival(day, rnd.at(day, 9, 11), rhythm.estimate(p), type, family, priority, mode, rnd.pick(active)));
            }
        }
        out.sort(Comparator.comparing(Arrival::assignAt));
        return out;
    }

    private void simulate(Person p) {
        Team team = rhythm.teamOf(p);
        if (team == null) {
            return;
        }
        UUID leader = team.managerId() != null && people.containsKey(team.managerId()) && !team.managerId().equals(p.id())
                ? team.managerId() : p.id();
        AbsencePlanner.Plan plan = plans.get(p.id());
        List<Arrival> arrivals = planArrivals(p, team);
        double ratio = rnd.lognormal(1.0, 0.25); // the member's estimation bias
        Deque<Work> queue = new ArrayDeque<>();
        List<Work> sleeping = new ArrayList<>(); // done, waiting for a possible reopen
        int next = 0;
        for (LocalDate day = cfg.firstMonday(); !day.isAfter(cfg.lastDay()); day = day.plusDays(1)) {
            while (next < arrivals.size() && arrivals.get(next).assignDay().equals(day)) {
                queue.addLast(assign(p, leader, arrivals.get(next), ratio));
                next++;
            }
            boolean working = cal.isWorkingDay(day);
            double hours = plan.hoursPresent(p, day);
            // reopen scheduled tasks
            for (Work w : new ArrayList<>(sleeping)) {
                if (w.reopenOn != null && !day.isBefore(w.reopenOn) && working) {
                    sleeping.remove(w);
                    reopen(p, w, day);
                    queue.addFirst(w);
                }
            }
            if (working) {
                for (Work w : queue) {
                    if (w.blockedLeft > 0) {
                        w.blockedLeft--;
                        if (w.blockedLeft == 0) {
                            transition(p, w, "In Progress", day.atTime(9, 0));
                        }
                    } else if (w.inReview) {
                        w.reviewLeft--;
                        if (w.reviewLeft <= 0) {
                            finish(p, w, day.atTime(16, 0));
                        }
                    } else if (w.unlogged && w.started) {
                        w.unloggedDaysLeft--;
                        if (w.unloggedDaysLeft <= 0) {
                            finish(p, w, day.atTime(16, 30));
                        }
                    }
                }
            }
            if (hours > 0) {
                // unlogged tasks start on the first present day after assignment
                for (Work w : queue) {
                    if (w.unlogged && !w.started) {
                        start(p, w, day.atTime(9, 15));
                    }
                }
                logDay(p, queue, day, hours);
            }
            // move finished tasks out of the queue
            for (Work w : new ArrayList<>(queue)) {
                if ("Done".equals(w.status)) {
                    queue.remove(w);
                    sleeping.add(w);
                }
            }
        }
        // tasks still queued keep their state; remaining reflects the logs
        for (Work w : queue) {
            if (w.row.get("original_estimate_hrs") != null) {
                w.row.put("remaining_estimate_hrs", Math.max(0.0, w.estimate - w.logged));
            }
        }
        // backlog tasks whose assignment week lies beyond the as-of date: created, not yet assigned
        for (; next < arrivals.size(); next++) {
            Arrival a = arrivals.get(next);
            LocalDate created = backlogCreationDay(a.assignDay());
            if (a.mode().equals("backlog") && !created.isAfter(cfg.lastDay())) {
                unassigned(leader, a, rnd.at(created, 9, 17));
            }
        }
    }

    private LocalDate backlogCreationDay(LocalDate assignDay) {
        LocalDate created = assignDay.minusDays(rnd.between(7, 21));
        while (!cal.isWorkingDay(created)) {
            created = created.minusDays(1);
        }
        return created;
    }

    /** A backlog row the leader created and nobody has picked up yet. */
    private void unassigned(UUID leader, Arrival a, LocalDateTime createdAt) {
        Project project = a.project();
        long number = nextNumber.merge(project.id(), 1L, Long::sum) - 1;
        LinkedHashMap<String, Object> row = Rows.task(rnd.uuid(), project.key() + "-" + number, title(a, project),
                "Generated for " + project.name(), null, a.priority(), project.id(), null, leader, number, createdAt,
                null, ref.type(a.typeName()), null, ref.status("To Do"), a.estimate());
        taskRows.add(row);
    }

    private Work assign(Person p, UUID leader, Arrival a, double ratio) {
        Project project = a.project();
        long number = nextNumber.merge(project.id(), 1L, Long::sum) - 1;
        UUID id = rnd.uuid();
        LocalDateTime createdAt = a.assignAt();
        UUID reporter = a.mode().equals("self") ? p.id() : leader;
        boolean backlog = a.mode().equals("backlog");
        if (backlog) {
            createdAt = rnd.at(backlogCreationDay(a.assignDay()), 9, 17);
        }
        String typeName = a.typeName();
        UUID parent = null;
        List<UUID> epics = epicsByProject.get(project.id());
        if (a.family().equals("delivery") && epics != null && !epics.isEmpty() && rnd.chance(rates.subTask())) {
            typeName = "Sub-task";
            parent = rnd.pick(epics);
        }
        double actual = Math.max(0.25, Math.round(a.estimate() * ratio * rnd.lognormal(1.0, 0.2) * 4) / 4.0);
        int cycleDays = (int) Math.ceil(actual / HOURS_PER_DAY_PER_TASK);
        LocalDate due = addWorkingDays(a.assignDay(), (int) Math.ceil(cycleDays * Math.max(0.6, 1.1 + 0.2 * rnd.gaussian())));
        String title = title(a, project);
        LinkedHashMap<String, Object> row = Rows.task(id, project.key() + "-" + number, title, "Generated for " + project.name(),
                due, a.priority(), project.id(), p.id(), reporter, number, createdAt, SeedConfig.mondayOf(a.assignDay()),
                ref.type(typeName), parent, ref.status("To Do"), a.estimate());
        row.put("updated_at", a.assignAt().toString());
        taskRows.add(row);
        if (backlog) {
            historyRows.add(Rows.history(rnd.uuid(), id, leader, "assignee", null, p.fullName(), a.assignAt()));
        }
        assignedHours.computeIfAbsent(p.id(), k -> new TreeMap<>()).merge(SeedConfig.mondayOf(a.assignDay()), a.estimate(), Double::sum);
        Work w = new Work(row, a.estimate(), actual, a.assignAt());
        w.unlogged = rnd.chance(rates.unlogged());
        w.unloggedDaysLeft = Math.max(1, cycleDays);
        if (rnd.chance(rates.reopen())) {
            w.reopened = false;
            w.reopenOn = LocalDate.MIN; // marker: reopen once after the first finish
        }
        return w;
    }

    private String title(Arrival a, Project project) {
        String verb = switch (a.family()) {
            case "defect" -> rnd.pick(List.of("Fix", "Investigate", "Resolve"));
            case "support" -> rnd.pick(List.of("Assess", "Review", "Prepare"));
            default -> rnd.pick(List.of("Implement", "Update", "Deliver", "Analyse"));
        };
        String object = rnd.pick(List.of("dataset", "test bench setup", "signal mapping", "report", "configuration",
                "model variant", "measurement plan", "release candidate", "checklist", "interface spec"));
        return verb + " " + object + " for " + project.name();
    }

    private LocalDate addWorkingDays(LocalDate from, int days) {
        LocalDate d = from;
        int n = 0;
        while (n < days) {
            d = d.plusDays(1);
            if (cal.isWorkingDay(d)) {
                n++;
            }
        }
        return d;
    }

    private void logDay(Person p, Deque<Work> queue, LocalDate day, double hours) {
        List<Work> active = new ArrayList<>();
        for (Work w : queue) {
            if (!w.unlogged && w.blockedLeft == 0 && !w.inReview && !w.finished()) {
                active.add(w);
                if (active.size() == MAX_ACTIVE) {
                    break;
                }
            }
        }
        double left = hours;
        int i = 0;
        while (left >= 0.25 && i < active.size()) {
            Work w = active.get(i);
            double share = Math.round(left / (active.size() - i) * 4) / 4.0;
            double give = Math.min(Math.max(0.25, share), Math.max(0.25, w.actual - w.logged));
            give = Math.min(give, left);
            if (give < 0.25) {
                break;
            }
            if (!w.started) {
                start(p, w, day.atTime(9, 0));
            }
            timeLogRows.add(Rows.timeLog(rnd.uuid(), w.id, p.id(), give, day, "Work on " + w.row.get("key")));
            w.logged += give;
            left -= give;
            w.row.put("remaining_estimate_hrs", Math.max(0.0, w.estimate - w.logged));
            w.row.put("updated_at", day.atTime(17, 30).toString());
            if (w.finished()) {
                if (rnd.chance(rates.review())) {
                    w.inReview = true;
                    w.reviewLeft = rnd.between(1, 2);
                    transition(p, w, "In Review", day.atTime(17, 0));
                } else {
                    finish(p, w, day.atTime(17, 0));
                }
            } else if (!w.wasBlocked && w.logged >= 0.3 * w.actual && rnd.chance(rates.blocked())) {
                w.wasBlocked = true;
                w.blockedLeft = rnd.between(2, 5);
                transition(p, w, "Blocked", day.atTime(17, 0));
            }
            i++;
        }
    }

    /** Starts a task; never before its assignment, which can be later the same morning. */
    private void start(Person p, Work w, LocalDateTime at) {
        LocalDateTime when = at.isBefore(w.assignedAt) ? w.assignedAt.plusMinutes(5) : at;
        w.started = true;
        w.row.put("started_date", when.toString());
        transition(p, w, "In Progress", when);
    }

    private void finish(Person p, Work w, LocalDateTime at) {
        w.inReview = false;
        w.row.put("finished_date", at.toString());
        if (w.row.get("original_estimate_hrs") != null) {
            w.row.put("remaining_estimate_hrs", 0.0);
        }
        transition(p, w, "Done", at);
        if (w.reopenOn != null && !w.reopened) {
            LocalDate on = at.toLocalDate().plusDays(rnd.between(7, 21));
            w.reopenOn = on.isAfter(cfg.lastDay()) ? null : on;
        } else {
            w.reopenOn = null;
        }
    }

    private void reopen(Person p, Work w, LocalDate day) {
        w.reopened = true;
        w.reopenOn = null;
        w.row.put("reopened_from_done", true);
        w.row.put("last_reopened_at", day.atTime(9, 0).toString());
        w.row.put("finished_date", null);
        w.actual += Math.max(0.25, Math.round(w.estimate * rnd.uniform(0.2, 0.4) * 4) / 4.0);
        w.unloggedDaysLeft = Math.max(1, (int) Math.ceil((w.actual - w.logged) / HOURS_PER_DAY_PER_TASK));
        transition(p, w, "In Progress", day.atTime(9, 0));
        w.row.put("remaining_estimate_hrs", Math.max(0.0, w.estimate - w.logged));
    }

    private void transition(Person p, Work w, String to, LocalDateTime at) {
        historyRows.add(Rows.history(rnd.uuid(), w.id, p.id(), "status", w.status, to, at));
        w.status = to;
        w.row.put("task_status_id", ref.status(to).toString());
        w.row.put("updated_at", at.toString());
    }
}
```

Two rules of the code worth knowing when a test fails: a reopened task's remaining estimate after reopening is `max(0, estimate − logged)`, which is 0 when the first pass already logged the estimate (the invariant test only checks the formula for tasks that are not `Done`, and the reopened extra work is real work beyond the estimate); and a task assigned on a day the member is absent never happens because arrivals are drawn from present days only.

- [ ] **Step 5: Run the test**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest=WorkQueueTest`
Expected: all pass. The property runs 15 seeds over a three-person, sixteen-week world in a few seconds. If `lifecycleInvariantsHold` fails, make the failing check print the task row before returning false (temporarily) to see which rule broke; the most likely culprits are a `started_date` set on a non-present day (fix: `start` only from `logDay` and from the unlogged branch, both inside `hours > 0`) or a log after `finished_date` (fix: finished tasks leave the queue at the end of the same day).

- [ ] **Step 6: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/seed server/forecast-core/src/test/java/com/workloadhub/forecast/seed
git commit -m "feat(seed): day-by-day work queue writing tasks, transitions and time logs

Each counted member works at most three tasks at once on present days;
tasks arrive from the backlog, the leader or themselves, pass through
review, blocked and reopen paths at the design's rates, and a share
finish without logs so the fallback rule has data."
```

---

### Task 11: The generator end to end: reference data, anonymiser, SQL writer and the `seed` command

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/ReferenceData.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/Anonymiser.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/SeedGenerator.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/SqlExportWriter.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/SeedGeneratorTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/data/SqlExportWriterTest.java`
- Create: `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/SeedCommand.java`
- Modify: `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/ForecastCli.java` (add `SeedCommand.class` to `subcommands`)
- Modify: `server/forecast-cli/src/test/java/com/workloadhub/forecast/cli/CliSmokeTest.java` (add the seed round trip)

**Interfaces:**
- Consumes: everything from Tasks 3 to 10.
- Produces:
  - `ReferenceData.statusRows()`, `typeRows()`, `roleRows()`, `holidayRows(int fromYear, int toYear)`, `jobTitleRows(Collection<String> titles)`, `syntheticUsers(int n, SeedRandom rnd)`: rows in the export's column shapes, ids from `UUID.nameUUIDFromBytes` so they are stable across runs.
  - `Anonymiser.anonymise(List<LinkedHashMap<String, Object>> users, SeedRandom rnd)` (new rows, identities replaced) and `Anonymiser.shrink(List<LinkedHashMap<String, Object>> users, int n)`.
  - `SeedGenerator.generate(ExportEnvelope input, SeedConfig cfg)` returning the seeded `ExportEnvelope`; `input` may be null in synthetic mode.
  - `SqlExportWriter.write(ExportEnvelope env, Writer out)`: PostgreSQL `INSERT` script inside one transaction with `SET search_path TO task_service`.
  - CLI: `seed [--export FILE] [--weeks 52] [--end DATE] [--seed 42] [--synthetic] [--users N] [--format json|sql] [--force] --out FILE`.

- [ ] **Step 1: Write the failing tests**

`SeedGeneratorTest.java`:

```java
package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.ExportEnvelope;
import com.workloadhub.forecast.data.ExportExporter;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.data.ExportImporter;
import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class SeedGeneratorTest {

    static final SeedConfig CFG = new SeedConfig(26, LocalDate.of(2026, 9, 6), 7, true, 40);

    static ExportEnvelope generated() {
        return SeedGenerator.generate(null, CFG);
    }

    static Set<String> ids(ExportEnvelope env, String table) {
        Set<String> out = new HashSet<>();
        for (var r : env.rows(table)) {
            out.add((String) r.get("id"));
        }
        return out;
    }

    static void assertResolves(ExportEnvelope env, String table, String column, Set<String> targets) {
        for (var r : env.rows(table)) {
            Object v = r.get(column);
            assertTrue(v == null || targets.contains(v), table + "." + column + " -> " + v);
        }
    }

    @Test
    void everyForeignKeyResolvesAndKeysAreUnique() {
        ExportEnvelope env = generated();
        Set<String> users = ids(env, "users");
        Set<String> teams = ids(env, "teams");
        Set<String> projects = ids(env, "projects");
        Set<String> tasks = ids(env, "tasks");
        Set<String> statuses = ids(env, "task_statuses");
        Set<String> types = ids(env, "task_types");
        assertResolves(env, "users", "manager_id", users);
        assertResolves(env, "teams", "manager_id", users);
        assertResolves(env, "teams", "parent_team_id", teams);
        assertResolves(env, "team_members", "team_id", teams);
        assertResolves(env, "team_members", "user_id", users);
        assertResolves(env, "projects", "owner_id", users);
        assertResolves(env, "projects", "team_id", teams);
        assertResolves(env, "tasks", "project_id", projects);
        assertResolves(env, "tasks", "assignee_id", users);
        assertResolves(env, "tasks", "reporter_id", users);
        assertResolves(env, "tasks", "parent_task_id", tasks);
        assertResolves(env, "tasks", "task_status_id", statuses);
        assertResolves(env, "tasks", "task_type_id", types);
        assertResolves(env, "task_history", "task_id", tasks);
        assertResolves(env, "task_history", "user_id", users);
        assertResolves(env, "time_logs", "task_id", tasks);
        assertResolves(env, "time_logs", "user_id", users);
        assertResolves(env, "absences", "user_id", users);
        assertResolves(env, "personal_leaves", "employee_id", users);
        assertResolves(env, "user_capacity", "user_id", users);
        assertResolves(env, "team_capacity", "team_id", teams);
        assertEquals(env.rows("tasks").size(), env.rows("tasks").stream().map(t -> t.get("key")).distinct().count());
        Map<String, Long> maxNumber = new HashMap<>();
        for (var t : env.rows("tasks")) {
            maxNumber.merge((String) t.get("project_id"), (Long) t.get("task_number"), Math::max);
        }
        for (var p : env.rows("projects")) {
            long expected = maxNumber.getOrDefault(p.get("id"), 0L) + 1;
            assertEquals(expected, p.get("next_task_number"), "next_task_number of " + p.get("key"));
        }
        assertEquals(WorkloadHubSchema.TABLE_ORDER.size(), env.data().size(), "every table present, empty ones included");
        assertEquals(List.of("refresh_tokens"), env.excludedTables());
    }

    @Test
    void historyCoversTheConfiguredWeeksWithRealisticVolume() {
        ExportEnvelope env = generated();
        assertEquals(40, env.rows("users").size());
        Set<String> weeks = new HashSet<>();
        for (var r : env.rows("user_capacity")) {
            weeks.add((String) r.get("week_start"));
        }
        assertEquals(CFG.mondays().size(), weeks.size());
        assertTrue(weeks.contains(CFG.firstMonday().toString()) && weeks.contains(SeedConfig.mondayOf(CFG.lastDay()).toString()));
        int tasks = env.rows("tasks").size();
        assertTrue(tasks > 40 * 26 * 0.8 && tasks < 40 * 26 * 6, "tasks " + tasks);
        assertTrue(env.rows("time_logs").size() > tasks, "several logs per task");
        long unassigned = env.rows("tasks").stream().filter(t -> t.get("assignee_id") == null).count();
        assertTrue(unassigned > 0, "backlog left at the as-of date");
        assertTrue(env.rows("holidays").size() >= 9);
        long counted = env.rows("users").stream().filter(u -> List.of("MEMBER", "TEAM_LEADER").contains(u.get("role"))).count();
        assertTrue(env.rows("personal_leaves").size() >= counted, "at least one vacation block per counted member");
    }

    @Test
    void loggedHoursTrackAssignedEstimatesAndNeverExceedPresence() {
        ExportEnvelope env = generated();
        Map<String, Double> logged = new HashMap<>();
        Map<String, Map<String, Double>> perDay = new HashMap<>();
        for (var l : env.rows("time_logs")) {
            String user = (String) l.get("user_id");
            logged.merge(user, (Double) l.get("hours"), Double::sum);
            perDay.computeIfAbsent(user, k -> new HashMap<>()).merge((String) l.get("log_date"), (Double) l.get("hours"), Double::sum);
        }
        Set<String> absentDays = new HashSet<>();
        for (var a : env.rows("absences")) {
            absentDays.add(a.get("user_id") + "|" + a.get("date"));
        }
        for (var e : perDay.entrySet()) {
            for (var d : e.getValue().entrySet()) {
                assertTrue(d.getValue() <= 8.0 + 1e-9, "more than 8 h on " + d.getKey());
                assertFalse(absentDays.contains(e.getKey() + "|" + d.getKey()), "log on an absence day");
            }
        }
        Map<String, Double> estimatesDone = new HashMap<>();
        Set<String> doneStatuses = new HashSet<>();
        for (var s : env.rows("task_statuses")) {
            if ("DONE".equals(s.get("category"))) {
                doneStatuses.add((String) s.get("id"));
            }
        }
        for (var t : env.rows("tasks")) {
            if (t.get("assignee_id") != null && t.get("original_estimate_hrs") != null && doneStatuses.contains(t.get("task_status_id"))) {
                estimatesDone.merge((String) t.get("assignee_id"), (Double) t.get("original_estimate_hrs"), Double::sum);
            }
        }
        double ratios = 0;
        int n = 0;
        for (var e : estimatesDone.entrySet()) {
            if (e.getValue() > 40) {
                double r = logged.getOrDefault(e.getKey(), 0.0) / e.getValue();
                assertTrue(r > 0.4 && r < 2.5, "logged/estimated for " + e.getKey() + " = " + r);
                ratios += r;
                n++;
            }
        }
        assertTrue(n >= 20, "members with finished work: " + n);
        double mean = ratios / n;
        assertTrue(mean > 0.75 && mean < 1.3, "mean logged/estimated " + mean);
    }

    @Test
    void capacityRowsAreConsistent() {
        ExportEnvelope env = generated();
        Map<String, Double> available = new HashMap<>();
        for (var r : env.rows("user_capacity")) {
            double base = (Double) r.get("base_capacity_hrs");
            double abs = (Double) r.get("absence_hrs");
            double avail = (Double) r.get("available_hrs");
            assertTrue(avail <= base - abs + 1e-9 && avail >= 0);
            available.put(r.get("user_id") + "|" + r.get("week_start"), avail);
        }
        Map<String, List<String>> members = new HashMap<>();
        for (var m : env.rows("team_members")) {
            members.computeIfAbsent((String) m.get("team_id"), k -> new java.util.ArrayList<>()).add((String) m.get("user_id"));
        }
        for (var r : env.rows("team_capacity")) {
            double sum = 0;
            for (String u : members.getOrDefault((String) r.get("team_id"), List.of())) {
                sum += available.getOrDefault(u + "|" + r.get("week_start"), 0.0);
            }
            assertEquals(sum, (Double) r.get("total_capacity_hrs"), 1e-6);
        }
    }

    @Test
    void roundTripsThroughSqlite() {
        ExportEnvelope env = generated();
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        new ExportImporter(ds).importAll(env, true);
        ExportEnvelope back = new ExportExporter(ds).exportAll();
        for (String table : WorkloadHubSchema.TABLE_ORDER) {
            assertEquals(env.rows(table).size(), back.rows(table).size(), table);
        }
        Function<LinkedHashMap<String, Object>, String> keyOf = t -> (String) t.get("key");
        assertEquals(env.rows("tasks").stream().map(keyOf).sorted().toList(), back.rows("tasks").stream().map(keyOf).sorted().toList());
    }

    @Test
    void isDeterministic() {
        assertEquals(ExportFiles.toJson(generated()), ExportFiles.toJson(SeedGenerator.generate(null, CFG)));
    }

    @Test
    void syntheticModeLeavesNoInputIdentityBehind() throws Exception {
        ExportEnvelope real = ExportFiles.read(java.nio.file.Path.of("src/test/resources/fixtures/mini-export.json"));
        SeedConfig cfg = new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, true, 0);
        String json = ExportFiles.toJson(SeedGenerator.generate(real, cfg));
        for (var u : real.rows("users")) {
            for (String col : List.of("full_name", "email")) {
                assertFalse(json.contains((String) u.get(col)), col + " leaked: " + u.get(col));
            }
        }
        ExportEnvelope out = ExportFiles.parse(json);
        assertTrue(out.rows("users").stream().allMatch(u -> ((String) u.get("username")).startsWith("user") && u.get("password") == null
                && u.get("object_id") == null && u.get("manager_object_id") == null));
    }

    @Test
    @EnabledIfSystemProperty(named = "seed.full", matches = "true")
    void fullPopulationRunsInUnderAMinute() {
        long start = System.nanoTime();
        ExportEnvelope env = SeedGenerator.generate(null, new SeedConfig(52, LocalDate.of(2026, 9, 6), 42, true, 264));
        long seconds = (System.nanoTime() - start) / 1_000_000_000L;
        assertTrue(seconds < 60, "took " + seconds + " s");
        assertTrue(env.rows("tasks").size() > 15_000, "tasks " + env.rows("tasks").size());
    }
}
```

`SqlExportWriterTest.java`:

```java
package com.workloadhub.forecast.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.io.StringWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class SqlExportWriterTest {

    @Test
    void writesQuotedInsertsInDependencyOrder() throws Exception {
        ExportEnvelope env = ExportFiles.read(Path.of("src/test/resources/fixtures/mini-export.json"));
        StringWriter out = new StringWriter();
        SqlExportWriter.write(env, out);
        String sql = out.toString();
        assertTrue(sql.startsWith("BEGIN;\nSET search_path TO task_service;\n"));
        assertTrue(sql.trim().endsWith("COMMIT;"));
        assertTrue(sql.indexOf("INSERT INTO users") < sql.indexOf("INSERT INTO tasks"));
        assertTrue(sql.contains("'New Year''s Day'") || sql.contains("'New Year’s Day'"), "quotes escaped");
        assertTrue(sql.contains("FALSE") && sql.contains("NULL"));
    }

    @Test
    void scriptLoadsIntoPostgresql() throws Exception {
        DataSource ds = DatabaseTestSupport.postgresOrSkip();
        WorkloadHubSchema.createPostgresql(ds);
        ExportEnvelope env = ExportFiles.read(Path.of("src/test/resources/fixtures/mini-export.json"));
        StringWriter out = new StringWriter();
        SqlExportWriter.write(env, out);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(out.toString());
        }
        assertEquals(2, new ExportExporter(ds).exportAll().rows("users").size());
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='SeedGeneratorTest,SqlExportWriterTest'`
Expected: compilation errors.

- [ ] **Step 3: Write `ReferenceData`**

```java
package com.workloadhub.forecast.seed;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/** Reference rows for synthetic mode, shaped like the export's, with stable ids. */
public final class ReferenceData {

    private static final String STAMP = "2025-09-01T08:00:00";

    private record Status(String name, String category, int order, String description) {
    }

    private record Dept(String code, String label, WorkFamily family) {
    }

    private static final List<Status> STATUSES = List.of(
            new Status("Open", "TO_DO", 1, "New task, not started"), new Status("To Do", "TO_DO", 2, "Ready to start"),
            new Status("In Progress", "IN_PROGRESS", 3, "Being worked on"), new Status("In Review", "IN_PROGRESS", 4, "Under review"),
            new Status("Testing", "IN_PROGRESS", 5, "Being tested"), new Status("Blocked", "IN_PROGRESS", 6, "Waiting on something"),
            new Status("On Hold", "TO_DO", 7, "Paused"), new Status("Done", "DONE", 8, "Finished"), new Status("Closed", "DONE", 9, "Closed"));

    private static final List<String> TYPES = List.of("Story", "Bug", "Task", "Epic", "Improvement", "New Feature",
            "Change Request", "Incident", "Risk", "Spike", "Test", "Sub-task");

    private static final List<String> ROLES = List.of("ADMIN", "CENTER_MANAGER", "SKILL_TEAM_LEADER", "TEAM_LEADER", "MEMBER", "VIEWER");

    /** Moroccan national holidays (the export's country code is MA). */
    private static final List<String[]> NATIONAL = List.of(
            new String[] {"New Year's Day", "01-01"}, new String[] {"Independence Manifesto Day", "01-11"},
            new String[] {"Labour Day", "05-01"}, new String[] {"Throne Day", "07-30"},
            new String[] {"Oued Ed-Dahab Day", "08-14"}, new String[] {"Revolution Day", "08-20"},
            new String[] {"Youth Day", "08-21"}, new String[] {"Green March Day", "11-06"},
            new String[] {"Independence Day", "11-18"});

    private static final List<Dept> DEPTS = List.of(
            new Dept("CT1", "PTE / CT1 Calibration & Testing 1", WorkFamily.CALIBRATION),
            new Dept("CT2", "PTE / CT2 Calibration & Testing 2", WorkFamily.CALIBRATION),
            new Dept("SD1", "PTE / SD1 Safety & Diagnostics 1", WorkFamily.SYSTEMS),
            new Dept("EE", "PTE / EE Electric & Electronics", WorkFamily.ELECTRONICS),
            new Dept("DAI", "PTE / DAI Data & Artificial Intelligence", WorkFamily.DATA),
            new Dept("SMBD", "PTE / SMBD Software Model Based Design", WorkFamily.ELECTRONICS),
            new Dept("VAH", "PTE / VAH Vehicle Attributes & Homologation", WorkFamily.VALIDATION),
            new Dept("MDS", "PTE / MDS Modeling, Design & Simulation", WorkFamily.DESIGN),
            new Dept("HR", "ZEN / HR & WKP DEP HR & Workplace Services Department", WorkFamily.SUPPORT));

    private static final List<String> TITLES_BY_FAMILY_CAL = List.of("Calibration Engineer", "Calibration Lead Engineer", "Calibration Quality & Dataset Manager");
    private static final List<String> FIRST = List.of("Amina", "Youssef", "Sara", "Omar", "Leila", "Karim", "Nadia", "Hamza", "Imane", "Rachid",
            "Salma", "Anas", "Hind", "Mehdi", "Kenza", "Ayoub", "Zineb", "Tarik", "Meryem", "Bilal");
    private static final List<String> LAST = List.of("Benali", "El Amrani", "Idrissi", "Bouzid", "Chraibi", "Haddad", "Kabbaj", "Lahlou",
            "Mansouri", "Naciri", "Ouazzani", "Rami", "Saadi", "Tazi", "Ziani", "Berrada", "Fassi", "Guessous", "Hajji", "Jabri");

    private ReferenceData() {
    }

    static UUID stableId(String key) {
        return UUID.nameUUIDFromBytes(("whf-seed:" + key).getBytes(StandardCharsets.UTF_8));
    }

    public static List<LinkedHashMap<String, Object>> statusRows() {
        List<LinkedHashMap<String, Object>> out = new ArrayList<>();
        for (Status s : STATUSES) {
            LinkedHashMap<String, Object> r = new LinkedHashMap<>();
            r.put("id", stableId("status:" + s.name()).toString());
            r.put("name", s.name());
            r.put("active", true);
            r.put("category", s.category());
            r.put("created_at", STAMP);
            r.put("sort_order", (long) s.order());
            r.put("updated_at", STAMP);
            r.put("description", s.description());
            out.add(r);
        }
        return out;
    }

    public static List<LinkedHashMap<String, Object>> typeRows() {
        List<LinkedHashMap<String, Object>> out = new ArrayList<>();
        for (String t : TYPES) {
            LinkedHashMap<String, Object> r = new LinkedHashMap<>();
            r.put("id", stableId("type:" + t).toString());
            r.put("icon", null);
            r.put("name", t);
            r.put("active", true);
            r.put("created_at", STAMP);
            r.put("updated_at", STAMP);
            r.put("description", t);
            r.put("subtask_type_id", null);
            out.add(r);
        }
        return out;
    }

    public static List<LinkedHashMap<String, Object>> roleRows() {
        List<LinkedHashMap<String, Object>> out = new ArrayList<>();
        for (String code : ROLES) {
            LinkedHashMap<String, Object> r = new LinkedHashMap<>();
            r.put("id", stableId("role:" + code).toString());
            r.put("code", code);
            r.put("label", code.charAt(0) + code.substring(1).toLowerCase().replace('_', ' '));
            r.put("active", true);
            r.put("created_at", STAMP);
            r.put("updated_at", STAMP);
            out.add(r);
        }
        return out;
    }

    public static List<LinkedHashMap<String, Object>> holidayRows(int fromYear, int toYear) {
        List<LinkedHashMap<String, Object>> out = new ArrayList<>();
        for (int year = fromYear; year <= toYear; year++) {
            for (String[] h : NATIONAL) {
                String date = year + "-" + h[1];
                LinkedHashMap<String, Object> r = new LinkedHashMap<>();
                r.put("id", stableId("holiday:" + h[0] + ":" + date).toString());
                r.put("type", "NATIONAL");
                r.put("title", h[0]);
                r.put("active", true);
                r.put("status", "CONFIRMED");
                r.put("end_date", date);
                r.put("created_at", STAMP);
                r.put("start_date", date);
                r.put("updated_at", STAMP);
                r.put("country_code", "MA");
                out.add(r);
            }
        }
        return out;
    }

    public static List<LinkedHashMap<String, Object>> jobTitleRows(Collection<String> titles) {
        List<LinkedHashMap<String, Object>> out = new ArrayList<>();
        for (String t : new TreeSet<>(titles)) {
            LinkedHashMap<String, Object> r = new LinkedHashMap<>();
            r.put("id", stableId("title:" + t).toString());
            r.put("value", t);
            r.put("created_at", STAMP);
            r.put("updated_at", STAMP);
            out.add(r);
        }
        return out;
    }

    static String titleFor(WorkFamily family, SeedRandom rnd) {
        return switch (family) {
            case CALIBRATION -> rnd.pick(TITLES_BY_FAMILY_CAL);
            case SYSTEMS -> rnd.pick(List.of("System Development Engineer", "Lead Engineer System engineering"));
            case ELECTRONICS -> rnd.pick(List.of("Development Eng. Electric/ Electronics", "Software & Functions Engineer", "Development Engineer SW"));
            case DATA -> rnd.pick(List.of("Data Analyst & SW Developer", "AI Engineer"));
            case VALIDATION -> rnd.pick(List.of("Verification & Validation Engineer", "Attributes & Homologation Engineer", "Vehicule Fleet Validation Engineer"));
            case DESIGN -> rnd.pick(List.of("Design Engineer", "Simulation Engineer", "Design Engineer DMU"));
            case SUPPORT -> rnd.pick(List.of("HR Specialist", "IT System Administrator", "Purchasing & Admin Officer"));
            default -> "Project Manager PTE";
        };
    }

    /** n users over the departments: one head per department, one manager per ten people, the rest members. */
    public static List<LinkedHashMap<String, Object>> syntheticUsers(int n, SeedRandom rnd) {
        List<LinkedHashMap<String, Object>> out = new ArrayList<>();
        int perDept = Math.max(3, n / DEPTS.size());
        int made = 0;
        int deptIndex = 0;
        while (made < n) {
            Dept d = DEPTS.get(deptIndex % DEPTS.size());
            deptIndex++;
            int size = Math.min(perDept, n - made);
            UUID head = stableId("user:" + made);
            out.add(user(head, name(made, rnd), "Skill Team Leader", d.label(), null, made == 0 ? "CENTER_MANAGER" : "MEMBER"));
            made++;
            UUID manager = null;
            for (int i = 1; i < size; i++) {
                UUID id = stableId("user:" + made);
                boolean isManager = (i - 1) % 10 == 0;
                if (isManager) {
                    manager = id;
                    out.add(user(id, name(made, rnd), "Team Leader " + d.code(), d.label(), head, "MEMBER"));
                } else {
                    out.add(user(id, name(made, rnd), titleFor(d.family(), rnd), d.label(), manager, "MEMBER"));
                }
                made++;
            }
        }
        if (out.size() > 1) {
            out.get(1).put("role", "ADMIN");
        }
        return out;
    }

    static String name(int i, SeedRandom rnd) {
        return FIRST.get(rnd.between(0, FIRST.size() - 1)) + " " + LAST.get(rnd.between(0, LAST.size() - 1)) + " " + (i + 1);
    }

    static LinkedHashMap<String, Object> user(UUID id, String fullName, String title, String department, UUID manager, String role) {
        String username = fullName.toLowerCase().replace(' ', '.');
        LinkedHashMap<String, Object> u = new LinkedHashMap<>();
        u.put("id", id.toString());
        u.put("role", role);
        u.put("email", username + "@example.test");
        u.put("active", true);
        u.put("version", 0L);
        u.put("password", null);
        u.put("username", username);
        u.put("full_name", fullName);
        u.put("job_title", title);
        u.put("object_id", null);
        u.put("created_at", STAMP);
        u.put("department", department);
        u.put("manager_id", manager == null ? null : manager.toString());
        u.put("updated_at", STAMP);
        u.put("account_name", username);
        u.put("deactivated_at", null);
        u.put("manager_object_id", null);
        return u;
    }
}
```

- [ ] **Step 4: Write `Anonymiser`**

```java
package com.workloadhub.forecast.seed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Replaces identities in user rows; ids, departments, titles and managers stay so the structure survives. */
public final class Anonymiser {

    private Anonymiser() {
    }

    public static List<LinkedHashMap<String, Object>> anonymise(List<LinkedHashMap<String, Object>> users, SeedRandom rnd) {
        List<LinkedHashMap<String, Object>> out = new ArrayList<>();
        int i = 0;
        for (LinkedHashMap<String, Object> u : users) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>(u);
            String name = ReferenceData.name(i, rnd);
            String username = "user" + (i + 1);
            row.put("full_name", name);
            row.put("username", username);
            row.put("email", username + "@example.test");
            row.put("account_name", username);
            row.put("password", null);
            row.put("object_id", null);
            row.put("manager_object_id", null);
            out.add(row);
            i++;
        }
        return out;
    }

    /** Keeps n users, department by department, and drops manager links that point outside the kept set. */
    public static List<LinkedHashMap<String, Object>> shrink(List<LinkedHashMap<String, Object>> users, int n) {
        if (n <= 0 || n >= users.size()) {
            return users;
        }
        List<LinkedHashMap<String, Object>> sorted = new ArrayList<>(users);
        sorted.sort(Comparator.comparing((LinkedHashMap<String, Object> u) -> String.valueOf(u.get("department")))
                .thenComparing(u -> u.get("manager_id") == null ? 0 : 1)
                .thenComparing(u -> String.valueOf(u.get("id"))));
        List<LinkedHashMap<String, Object>> kept = new ArrayList<>(sorted.subList(0, n));
        Set<Object> ids = new HashSet<>();
        for (LinkedHashMap<String, Object> u : kept) {
            ids.add(u.get("id"));
        }
        List<LinkedHashMap<String, Object>> out = new ArrayList<>();
        for (LinkedHashMap<String, Object> u : kept) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>(u);
            if (row.get("manager_id") != null && !ids.contains(row.get("manager_id"))) {
                row.put("manager_id", null);
            }
            out.add(row);
        }
        return out;
    }
}
```

- [ ] **Step 5: Write `SeedGenerator`**

```java
package com.workloadhub.forecast.seed;

import com.workloadhub.forecast.data.ExportEnvelope;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/** Orchestrates the seed: directory, calendar, absences, projects, rhythm, work queue, capacity, envelope. */
public final class SeedGenerator {

    private SeedGenerator() {
    }

    public static ExportEnvelope generate(ExportEnvelope input, SeedConfig cfg) {
        SeedRandom rnd = new SeedRandom(cfg.seed());
        boolean synthetic = cfg.synthetic();
        if (input == null && !synthetic) {
            throw new IllegalArgumentException("Real mode needs an input export");
        }

        // 1. source rows
        List<LinkedHashMap<String, Object>> users;
        List<LinkedHashMap<String, Object>> teams;
        List<LinkedHashMap<String, Object>> members;
        List<LinkedHashMap<String, Object>> projectRows;
        List<LinkedHashMap<String, Object>> statuses;
        List<LinkedHashMap<String, Object>> types;
        List<LinkedHashMap<String, Object>> roles;
        List<LinkedHashMap<String, Object>> holidays;
        List<LinkedHashMap<String, Object>> jobTitles;
        List<LinkedHashMap<String, Object>> syncMetadata;
        if (input != null) {
            users = input.rows("users");
            teams = input.rows("teams");
            members = input.rows("team_members");
            projectRows = input.rows("projects");
            statuses = input.rows("task_statuses");
            types = input.rows("task_types");
            roles = input.rows("user_roles");
            holidays = input.rows("holidays");
            jobTitles = input.rows("job_titles");
            syncMetadata = input.rows("sync_metadata");
            if (synthetic) {
                users = Anonymiser.anonymise(Anonymiser.shrink(users, cfg.users()), rnd);
            }
            if (!Reference.covers(statuses, types)) {
                // a partial export (tests, early installs): use the reference rows instead
                statuses = ReferenceData.statusRows();
                types = ReferenceData.typeRows();
            }
        } else {
            users = ReferenceData.syntheticUsers(cfg.users() > 0 ? cfg.users() : 40, rnd);
            teams = List.of();
            members = List.of();
            projectRows = List.of();
            statuses = ReferenceData.statusRows();
            types = ReferenceData.typeRows();
            roles = ReferenceData.roleRows();
            holidays = ReferenceData.holidayRows(cfg.firstMonday().getYear(), cfg.lastDay().getYear());
            TreeSet<String> titles = new TreeSet<>();
            for (LinkedHashMap<String, Object> u : users) {
                if (u.get("job_title") != null) {
                    titles.add((String) u.get("job_title"));
                }
            }
            jobTitles = ReferenceData.jobTitleRows(titles);
            syncMetadata = List.of();
        }
        if (synthetic) {
            // keep only the kept users' memberships
            java.util.Set<Object> ids = new java.util.HashSet<>();
            users.forEach(u -> ids.add(u.get("id")));
            members = members.stream().filter(m -> ids.contains(m.get("user_id"))).toList();
            teams = teams.stream().map(t -> {
                LinkedHashMap<String, Object> row = new LinkedHashMap<>(t);
                if (row.get("manager_id") != null && !ids.contains(row.get("manager_id"))) {
                    row.put("manager_id", null);
                }
                return row;
            }).toList();
            projectRows = projectRows.stream().filter(p -> p.get("owner_id") == null || ids.contains(p.get("owner_id"))).toList();
        }

        // 2. structure
        Directory.Result dir = Directory.derive(users, teams, members, cfg, rnd);
        Map<UUID, Person> people = new HashMap<>();
        for (Person p : dir.people()) {
            people.put(p.id(), p);
        }
        SeedCalendar cal = SeedCalendar.fromHolidayRows(holidays, cfg);

        // 3. absences, in id order for determinism
        List<Person> ordered = new ArrayList<>(dir.people());
        ordered.sort(Comparator.comparing(p -> p.id().toString()));
        Map<UUID, AbsencePlanner.Plan> plans = new HashMap<>();
        List<LinkedHashMap<String, Object>> absences = new ArrayList<>();
        List<LinkedHashMap<String, Object>> leaves = new ArrayList<>();
        for (Person p : ordered) {
            AbsencePlanner.Plan plan = AbsencePlanner.plan(p, cal, cfg, rnd);
            plans.put(p.id(), plan);
            if (p.counted()) {
                absences.addAll(plan.absenceRows());
                leaves.addAll(plan.leaveRows());
            }
        }

        // 4. projects, rhythm, work
        List<Project> projects = ProjectPlanner.plan(dir.teams(), people, projectRows, cfg, rnd);
        Rhythm rhythm = new Rhythm(cfg, cal, people, plans, dir.teams(), rnd);
        Reference ref = Reference.from(statuses, types);
        WorkQueue.Result work = WorkQueue.run(cfg, cal, dir.people(), plans, dir.teams(), projects, rhythm, ref,
                WorkQueue.Rates.DEFAULT, rnd);

        // 5. capacity
        Map<UUID, List<LinkedHashMap<String, Object>>> userCapacity = new HashMap<>();
        List<LinkedHashMap<String, Object>> userCapacityRows = new ArrayList<>();
        for (Person p : ordered) {
            if (!p.counted()) {
                continue;
            }
            List<LinkedHashMap<String, Object>> rows = CapacityWriter.userCapacity(p, plans.get(p.id()), cal, cfg, rnd);
            userCapacity.put(p.id(), rows);
            userCapacityRows.addAll(rows);
        }
        List<LinkedHashMap<String, Object>> teamCapacityRows = new ArrayList<>();
        List<Team> orderedTeams = new ArrayList<>(dir.teams());
        orderedTeams.sort(Comparator.comparing(t -> t.id().toString()));
        for (Team t : orderedTeams) {
            teamCapacityRows.addAll(CapacityWriter.teamCapacity(t, userCapacity,
                    (member, monday) -> work.assignedHours().getOrDefault(member, Map.of()).getOrDefault(monday, 0.0), cfg, rnd));
        }

        // 6. project rows with the next task number
        Map<String, LinkedHashMap<String, Object>> existingById = new HashMap<>();
        for (LinkedHashMap<String, Object> r : projectRows) {
            existingById.put((String) r.get("id"), r);
        }
        List<LinkedHashMap<String, Object>> outProjects = new ArrayList<>();
        List<Project> sortedProjects = new ArrayList<>(projects);
        sortedProjects.sort(Comparator.comparing(Project::key));
        for (Project p : sortedProjects) {
            outProjects.add(ProjectPlanner.row(p, existingById.get(p.id().toString()), work.nextTaskNumber().getOrDefault(p.id(), 1L), cfg));
        }

        // 7. envelope in table order
        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data = new LinkedHashMap<>();
        for (String table : WorkloadHubSchema.TABLE_ORDER) {
            data.put(table, new ArrayList<>());
        }
        data.put("user_roles", roles);
        data.put("job_titles", jobTitles);
        data.put("users", dir.userRows());
        data.put("teams", dir.teamRows());
        data.put("team_members", dir.teamMemberRows());
        data.put("task_statuses", statuses);
        data.put("task_types", types);
        data.put("projects", outProjects);
        data.put("tasks", work.taskRows());
        data.put("task_history", work.historyRows());
        data.put("time_logs", work.timeLogRows());
        data.put("absences", absences);
        data.put("personal_leaves", leaves);
        data.put("holidays", cal.holidayRows());
        data.put("user_capacity", userCapacityRows);
        data.put("team_capacity", teamCapacityRows);
        data.put("sync_metadata", syncMetadata);
        String database = input == null ? "synthetic" : input.database();
        return new ExportEnvelope(database, "task_service", cfg.lastDay().atTime(18, 0).toString(),
                List.of("refresh_tokens"), data);
    }
}
```

- [ ] **Step 6: Write `SqlExportWriter`**

```java
package com.workloadhub.forecast.data;

import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Writes an envelope as one PostgreSQL transaction of INSERT statements, for `psql -f`. */
public final class SqlExportWriter {

    private static final int ROWS_PER_STATEMENT = 200;

    private SqlExportWriter() {
    }

    public static void write(ExportEnvelope env, Writer out) throws IOException {
        out.write("BEGIN;\nSET search_path TO task_service;\n");
        for (String table : WorkloadHubSchema.TABLE_ORDER) {
            List<LinkedHashMap<String, Object>> rows = env.rows(table);
            if (rows.isEmpty()) {
                continue;
            }
            List<String> columns = new ArrayList<>(rows.get(0).keySet());
            for (int start = 0; start < rows.size(); start += ROWS_PER_STATEMENT) {
                out.write("INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES\n");
                int end = Math.min(rows.size(), start + ROWS_PER_STATEMENT);
                for (int i = start; i < end; i++) {
                    LinkedHashMap<String, Object> row = rows.get(i);
                    StringBuilder sb = new StringBuilder("(");
                    for (int c = 0; c < columns.size(); c++) {
                        sb.append(c == 0 ? "" : ", ").append(literal(row.get(columns.get(c))));
                    }
                    sb.append(i == end - 1 ? ");\n" : "),\n");
                    out.write(sb.toString());
                }
            }
        }
        out.write("COMMIT;\n");
    }

    static String literal(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        if (v instanceof Number n) {
            return n.toString();
        }
        return "'" + v.toString().replace("'", "''") + "'";
    }
}
```

- [ ] **Step 7: Write `SeedCommand` and register it**

```java
package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.data.ExportEnvelope;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.data.SqlExportWriter;
import com.workloadhub.forecast.seed.SeedConfig;
import com.workloadhub.forecast.seed.SeedGenerator;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "seed", description = "Generate a WorkloadHub export with weeks of realistic history, from a real export or a synthetic directory.")
public class SeedCommand implements Callable<Integer> {

    @Option(names = "--export", description = "A WorkloadHub JSON export to take users, teams, projects and reference rows from.")
    Path export;

    @Option(names = "--weeks", defaultValue = "52", description = "Weeks of history (default: ${DEFAULT-VALUE}).")
    int weeks;

    @Option(names = "--end", description = "The as-of date; history ends there (default: today).")
    LocalDate end;

    @Option(names = "--seed", defaultValue = "42", description = "Random seed; the same seed gives the same file.")
    long seed;

    @Option(names = "--synthetic", description = "Replace identities with generated ones (and generate a directory when no --export).")
    boolean synthetic;

    @Option(names = "--users", defaultValue = "0", description = "Synthetic mode: keep or generate this many users (0 = all, or 40 without --export).")
    int users;

    @Option(names = "--format", defaultValue = "json", description = "json or sql (PostgreSQL INSERT script).")
    String format;

    @Option(names = "--force", description = "Allow real-mode output inside a git repository.")
    boolean force;

    @Option(names = "--out", required = true, description = "Output file.")
    Path out;

    @Override
    public Integer call() throws Exception {
        if (!synthetic && export == null) {
            System.err.println("Real mode needs --export <file>; or pass --synthetic");
            return 2;
        }
        if (!synthetic && !force && insideGitRepository(out)) {
            System.err.println("Real-mode output holds personal data; write it outside the repository or pass --force");
            return 2;
        }
        if (!format.equals("json") && !format.equals("sql")) {
            System.err.println("--format must be json or sql");
            return 2;
        }
        ExportEnvelope input = export == null ? null : ExportFiles.read(export);
        SeedConfig cfg = new SeedConfig(weeks, end == null ? LocalDate.now() : end, seed, synthetic, users);
        long started = System.nanoTime();
        ExportEnvelope result = SeedGenerator.generate(input, cfg);
        if (format.equals("sql")) {
            try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                SqlExportWriter.write(result, w);
            }
        } else {
            ExportFiles.write(out, result);
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("Wrote %s in %d ms: %d weeks ending %s, seed %d, %s%n", out, ms, weeks, cfg.lastDay(), seed,
                synthetic ? "synthetic identities" : "real identities (do not commit)");
        for (String table : new String[] {"users", "teams", "team_members", "projects", "tasks", "task_history", "time_logs",
                "absences", "personal_leaves", "user_capacity", "team_capacity", "holidays"}) {
            System.out.printf("  %-18s %8d%n", table, result.rows(table).size());
        }
        return 0;
    }

    static boolean insideGitRepository(Path file) {
        Path dir = file.toAbsolutePath().getParent();
        while (dir != null) {
            if (Files.exists(dir.resolve(".git"))) {
                return true;
            }
            dir = dir.getParent();
        }
        return false;
    }
}
```

In `ForecastCli.Root`, change `subcommands = {InitDbCommand.class, ImportCommand.class, ExportCommand.class}` to `subcommands = {InitDbCommand.class, ImportCommand.class, ExportCommand.class, SeedCommand.class}`.

Add to `CliSmokeTest`:

```java
    @Test
    void seedSyntheticThenImport(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("s.db");
        Path seeded = dir.resolve("seeded.json");
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        assertEquals(0, cli.execute("seed", "--synthetic", "--users", "12", "--weeks", "8", "--seed", "1", "--end", "2026-09-06", "--out", seeded.toString()));
        assertEquals(0, cli.execute("init-db", "--db", db.toString()));
        assertEquals(0, cli.execute("import", "--db", db.toString(), seeded.toString()));
        Path sql = dir.resolve("seeded.sql");
        assertEquals(0, cli.execute("seed", "--synthetic", "--users", "12", "--weeks", "8", "--seed", "1", "--end", "2026-09-06", "--format", "sql", "--out", sql.toString()));
        assertTrue(Files.readString(sql).contains("INSERT INTO tasks"));
    }

    @Test
    void realModeRefusesToWriteInsideTheRepository() throws Exception {
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        Path fixture = Path.of("../forecast-core/src/test/resources/fixtures/mini-export.json");
        Path inRepo = Path.of("target/real-seeded.json");
        assertEquals(2, cli.execute("seed", "--export", fixture.toString(), "--weeks", "8", "--out", inRepo.toString()));
        assertFalse(Files.exists(inRepo));
    }
```

(add `import static org.junit.jupiter.api.Assertions.assertFalse;` to the test).

- [ ] **Step 8: Run the tests and the full gate**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='SeedGeneratorTest,SqlExportWriterTest'`
Expected: all pass (PostgreSQL test skipped without Docker).

Run: `cd server && mvn -B -q verify -Dseed.full=true`
Expected: BUILD SUCCESS; `fullPopulationRunsInUnderAMinute` reports the 264-user, 52-week run under 60 s. If it takes longer, the cost is in `logDay` building `active` for every day and member with `queue` iteration: keep a queue per member bounded (finished tasks leave it the same day, which the code does) and avoid `new ArrayList<>(queue)` copies when nothing finished.

Then time the whole gate: `cd server && time mvn -B -q verify`. It must finish under three minutes without Docker; if the generator tests dominate, reduce `SeedGeneratorTest.CFG` to 30 users and 20 weeks (keeping the assertions' bounds proportional) rather than dropping a test.

- [ ] **Step 9: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/seed server/forecast-core/src/main/java/com/workloadhub/forecast/data/SqlExportWriter.java server/forecast-core/src/test/java/com/workloadhub/forecast/seed server/forecast-core/src/test/java/com/workloadhub/forecast/data/SqlExportWriterTest.java server/forecast-cli/src/main/java/com/workloadhub/forecast/cli server/forecast-cli/src/test/java/com/workloadhub/forecast/cli
git commit -m "feat(seed): generator end to end with synthetic mode, SQL output and the seed command

Real mode reads the owner's export and fills a year of history for its
directory; synthetic mode invents a directory or anonymises one, so the
repository's tests need no personal data. Output is the export envelope
or a PostgreSQL script, and real-mode files are refused inside git."
```

---

### Task 12: Documentation, ignore rules and the real-mode run

**Files:**
- Create: `server/README.md`
- Modify: `.gitignore` (append)
- Modify: `CLAUDE.md` (add the server module to Layout and Toolchain; the full rewrite comes with the migration plan)
- Modify: `docs/backlog.md` (one entry: seed observations to revisit once the forecast runs on the seeded data)

**Interfaces:**
- Consumes: the CLI of Tasks 6 and 11.
- Produces: the instructions the owner follows in WSL, and the owner's seeded file generated once here to prove the real path works (never committed).

- [ ] **Step 1: Write `server/README.md`**

````markdown
# WorkloadHub forecast: the Java module

Two Maven modules: `forecast-core` (the library the WorkloadHub Spring Boot application adds as a
dependency) and `forecast-cli` (a runnable jar for experiments in WSL). Design:
`docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`.

## Prerequisites (WSL, Ubuntu)

```bash
sudo apt update && sudo apt install -y openjdk-21-jdk maven
java -version   # 21
mvn -version    # 3.8 or newer
```

Docker Desktop with WSL integration is optional; when it is present the PostgreSQL tests run in a
container, otherwise they are skipped with a message.

## Build and test

```bash
cd server
mvn -B verify                 # compiles, runs every test, builds forecast-cli/target/workloadhub-forecast-cli-0.1.0-SNAPSHOT.jar
mvn -B verify -Dseed.full=true   # also times the 264-user, 52-week seed
```

## The command line

```bash
CLI="java -jar forecast-cli/target/workloadhub-forecast-cli-0.1.0-SNAPSHOT.jar"

# 1. a database with the WorkloadHub schema and the module's tables
$CLI init-db --db ~/whf/workloadhub.db

# 2. a year of history for the real directory (the export holds personal data: keep it and the output outside git)
$CLI seed --export ~/whf/workloadhub_export.json --weeks 52 --end 2026-09-06 --seed 42 --out ~/whf/seeded.json
$CLI seed --export ~/whf/workloadhub_export.json --weeks 52 --end 2026-09-06 --seed 42 --format sql --out ~/whf/seeded.sql

# 3. load it
$CLI import --db ~/whf/workloadhub.db ~/whf/seeded.json

# 4. or a synthetic population with no personal data, for tests and demos
$CLI seed --synthetic --users 40 --weeks 26 --seed 7 --end 2026-09-06 --out /tmp/synthetic.json

# 5. dump a database back to JSON
$CLI export --db ~/whf/workloadhub.db /tmp/dump.json
```

`--seed` fixes the output byte for byte; `--end` is the as-of date, and the history covers `--weeks`
Monday weeks ending in the week of that date. Loading the SQL script into PostgreSQL:
`psql -d avl_workloadhub -f ~/whf/seeded.sql` (it runs inside one transaction and sets
`search_path` to `task_service`; the target tables must be empty).

## What the seed writes

Teams come from `users.manager_id`, one per manager under a department team per department code;
job titles decide the kind of work; each member gets a weekly rhythm with seasonal dips, project
ramps, team events and absences; tasks are created into the backlog or assigned directly, worked
three at a time, logged day by day, reviewed, blocked or reopened at the design's rates, and a few
finish without logs. Capacity rows follow the application's formula. The invariants the tests hold
are listed in the design, section 4.8.
````

- [ ] **Step 2: Append to `.gitignore`**

```gitignore

# Java module: build output, local databases, real-mode seed output (personal data)
server/**/target/
server/**/*.db
server/**/*.db-journal
*seeded*.json
*seeded*.sql
workloadhub_export*.json
```

- [ ] **Step 3: Update `CLAUDE.md` and the backlog**

In `CLAUDE.md`, add to the Layout block after the `scripts/` line:

```text
server/    Java 21 module for the WorkloadHub Spring Boot server: `forecast-core` (library) and `forecast-cli`
           (WSL command line: init-db, import, export, seed); see docs/superpowers/specs/2026-09-09-java-forecast-module-design.md
```

and to the Toolchain list:

```text
- Java: Maven 3.9, Spring Boot 4.1, JUnit 6, jqwik; `mvn -B verify` in `server/` is the gate (under three
  minutes without Docker; PostgreSQL tests run when Docker is present). The real export and any real-mode
  seed output stay outside the repository.
```

In `docs/backlog.md`, add under a heading `## Java migration` the line:
`- Seed realism to revisit after the first forecast on seeded data: per-department rhythms, the share of reopened and unlogged tasks, and whether department teams (people without a manager) should run their own forecast.`

- [ ] **Step 4: Run the real-mode seed once, outside the repository**

The owner's export is at `/root/.claude/uploads/b2a8eb27-4f3d-5e9c-b265-5c7e0182ff6a/77b3930e-workloadhub_export.json` in this environment (on the owner's machine, wherever they keep it). Run:

```bash
cd server && mvn -B -q -DskipTests package
CLI="java -jar forecast-cli/target/workloadhub-forecast-cli-0.1.0-SNAPSHOT.jar"
OUT=/tmp/claude-0/-home-user-WorkLoadHubAiForecasting/b2a8eb27-4f3d-5e9c-b265-5c7e0182ff6a/scratchpad/seed
mkdir -p $OUT
$CLI seed --export /root/.claude/uploads/b2a8eb27-4f3d-5e9c-b265-5c7e0182ff6a/77b3930e-workloadhub_export.json --weeks 52 --end 2026-09-06 --seed 42 --out $OUT/seeded.json
$CLI init-db --db $OUT/real.db && $CLI import --db $OUT/real.db $OUT/seeded.json
ls -la $OUT
```

Expected: the seed prints its table counts (users 264, teams about 40, tasks in the tens of thousands, time logs several times that) in well under a minute; the import prints the same counts. Record the counts and the file size in the task report for the owner; do not commit any of it.

- [ ] **Step 5: Run the whole gate one last time**

Run: `cd server && mvn -B -q verify && git status --short`
Expected: BUILD SUCCESS and no untracked seed output inside the repository.

- [ ] **Step 6: Commit**

```bash
git add server/README.md .gitignore CLAUDE.md docs/backlog.md
git commit -m "docs(server): README for the Java module and ignore rules for real-mode seed output

How to build and run the command line in WSL, what the seed writes, and
the ignore rules that keep the owner's export and any real-mode output
out of git."
```
