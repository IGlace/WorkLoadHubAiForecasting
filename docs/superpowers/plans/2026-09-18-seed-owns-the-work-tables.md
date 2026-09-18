# The Seed Owns the Work Tables Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The JSON importer, under `replace`, clears the three application tables that hang off the seed's work tables so a real-mode seed lands on a local database that holds project history and comments; the SQL export format, its guard and its projects upsert go.

**Architecture:** One constant in `WorkloadHubSchema` names the dependents; `ExportImporter.importAll` deletes them with the carried tables, children first. `SqlExportWriter` shrinks to the plain script the fixture needs. `Experiment.seed` writes JSON only. Documents record the owner's ruling that only the local database is ever seeded.

**Tech Stack:** Java 21, Maven 3.9, JUnit 6, PostgreSQL 18 through Testcontainers (Docker is running here), the `forecast-tools` module.

**Spec:** `docs/superpowers/specs/2026-09-18-seed-owns-the-work-tables-design.md`

## Global Constraints

- Every text file stays LF. No model identifier in any file or commit subject/body; every commit message ends, after a blank line, with exactly:
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_018PXJBiDynLHNhzrxdnE9Z1`.
- Test first: write the test, run it, see it fail for the right reason, then the code.
- Read test results from `server/forecast-*/target/surefire-reports/TEST-*.xml` only. Tasks 1 and 2 run their test classes and `cd server && mvn -B -q -DskipTests test-compile`; Task 3 runs the whole gate once, `rm -rf server/forecast-*/target/surefire-reports && bash scripts/check.sh`, expected 438 tests in `forecast-core` + `forecast-tools` combined minus the three deleted writer/driver tests plus the two added, so 437, 0 failures, 0 errors, 1 skipped.
- Single test classes run with `cd server && mvn -B -q -pl forecast-tools -am -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false test`; `-am` builds core first, which the tools module needs.
- The driver's messages are English only. Nothing of the real export ever enters the repository.
- Commit on `dev` after each task; push with `git push -u origin dev`.

---

### Task 1: The importer clears the application tables under the work tables

**Files:**
- Modify: `server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/export/WorkloadHubSchema.java` (after `TABLE_ORDER`, line 28)
- Modify: `server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/export/ExportImporter.java:60-72` (the `replace` block of `importAll`)
- Test: `server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/export/ExportImporterTest.java`

**Interfaces:**
- Produces: `WorkloadHubSchema.DEPENDENTS_OF_WORK`, `public static final List<String>` of `"project_history"`, `"task_comments"`, `"task_attachments"`. Task 3's README text names the same three tables.

- [ ] **Step 1: Write the failing test**

Replace the whole of `ExportImporterTest.java` with:

```java
package com.workloadhub.forecast.tools.export;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.workloadhub.forecast.tools.seed.SeedConfig;
import com.workloadhub.forecast.tools.seed.SeedGenerator;
import com.workloadhub.forecast.tools.testing.DatabaseTestSupport;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ExportImporterTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/mini-export.json");

    @Test
    void replaceDeletesOnlyTheTablesTheEnvelopeCarries() throws Exception {
        DataSource ds = DatabaseTestSupport.postgresWithSchema();
        ExportEnvelope input = ExportFiles.read(FIXTURE);
        new ExportImporter(ds).importAll(input, true);
        ExportEnvelope seeded = SeedGenerator.generate(input, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0));
        new ExportImporter(ds).importAll(seeded, true);
        ExportEnvelope back = new ExportExporter(ds).exportAll();
        assertEquals(input.rows("users").size(), back.rows("users").size(), "the directory survives a five-table replace");
        assertEquals(input.rows("holidays").size(), back.rows("holidays").size());
        assertEquals(seeded.rows("tasks").size(), back.rows("tasks").size(), "the work tables are replaced, not appended");
        assertEquals(seeded.rows("projects").size(), back.rows("projects").size());
        assertEquals(seeded.rows("personal_leaves").size(), back.rows("personal_leaves").size());
    }

    /**
     * The seed only ever targets the local database (design 2026-09-18): it owns everything under projects, so a
     * replace clears the application's own history and comments of the rows it replaces instead of failing on
     * their foreign keys.
     */
    @Test
    void replaceClearsTheApplicationTablesUnderTheWorkTables() throws Exception {
        DataSource ds = DatabaseTestSupport.postgresWithSchema();
        ExportEnvelope input = ExportFiles.read(FIXTURE);
        new ExportImporter(ds).importAll(input, true);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO project_history (id, project_id, changed_by, field_name, changed_at, created_at, updated_at)"
                    + " VALUES ('a0000000-0000-0000-0000-000000000001', '80000000-0000-0000-0000-000000000001',"
                    + " '30000000-0000-0000-0000-000000000001', 'name', now(), now(), now())");
            st.execute("INSERT INTO task_comments (id, task_id, user_id, content, created_at, updated_at)"
                    + " VALUES ('a0000000-0000-0000-0000-000000000002', '90000000-0000-0000-0000-000000000001',"
                    + " '30000000-0000-0000-0000-000000000001', 'a comment on a task the seed replaces', now(), now())");
        }
        ExportEnvelope seeded = SeedGenerator.generate(input, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0));
        new ExportImporter(ds).importAll(seeded, true);
        ExportEnvelope back = new ExportExporter(ds).exportAll();
        assertEquals(0, back.rows("project_history").size(), "the history of the replaced projects goes with them");
        assertEquals(0, back.rows("task_comments").size(), "the comments on the replaced tasks go with them");
        assertEquals(0, back.rows("task_attachments").size());
        assertEquals(ids(seeded, "projects"), ids(back, "projects"), "projects are the seed's, no more, no less");
        assertEquals(input.rows("users").size(), back.rows("users").size(), "the directory is untouched");
    }

    private static Set<String> ids(ExportEnvelope env, String table) {
        return env.rows(table).stream().map(r -> String.valueOf(r.get("id"))).collect(Collectors.toSet());
    }
}
```

- [ ] **Step 2: Run it and see it fail on the foreign key**

`cd server && mvn -B -q -pl forecast-tools -am -Dtest=ExportImporterTest -Dsurefire.failIfNoSpecifiedTests=false test`. Expected: `replaceClearsTheApplicationTablesUnderTheWorkTables` fails with `IllegalStateException: Import failed: ... violates foreign key constraint ... task_comments` (the delete of `tasks` is refused while a comment references one). The other test passes.

- [ ] **Step 3: The constant**

In `WorkloadHubSchema.java`, directly after the `TABLE_ORDER` declaration (line 28, the line ending `"sync_metadata", "refresh_tokens");`), add:

```java

    /**
     * Application tables that reference the seed's work tables: a replace of the work tables clears them first,
     * children before parents, because the seed only ever targets the local database (design 2026-09-18).
     */
    public static final List<String> DEPENDENTS_OF_WORK = List.of("project_history", "task_comments", "task_attachments");
```

- [ ] **Step 4: The importer**

In `ExportImporter.importAll`, replace

```java
            if (replace) {
                // Only the tables the envelope carries, children first: a five-table seed lands into a database
                // that already holds the directory (design 2026-09-17, section 7.2).
                List<String> reverse = new ArrayList<>(WorkloadHubSchema.TABLE_ORDER);
                java.util.Collections.reverse(reverse);
                try (Statement st = c.createStatement()) {
                    for (String table : reverse) {
                        if (envelope.data().containsKey(table)) {
                            st.execute("DELETE FROM " + table);
                        }
                    }
                }
            }
```

with

```java
            if (replace) {
                // Only the tables the envelope carries, children first, so a five-table seed lands into a database
                // that already holds the directory (design 2026-09-17, section 7.2); plus the application tables
                // that reference the work tables, since the seed owns everything under projects (design 2026-09-18).
                boolean work = envelope.data().containsKey("projects") || envelope.data().containsKey("tasks");
                List<String> reverse = new ArrayList<>(WorkloadHubSchema.TABLE_ORDER);
                java.util.Collections.reverse(reverse);
                try (Statement st = c.createStatement()) {
                    for (String table : reverse) {
                        if (envelope.data().containsKey(table) || (work && WorkloadHubSchema.DEPENDENTS_OF_WORK.contains(table))) {
                            st.execute("DELETE FROM " + table);
                        }
                    }
                }
            }
```

- [ ] **Step 5: Run the class and the round trip, both green**

`cd server && mvn -B -q -pl forecast-tools -am -Dtest='ExportImporterTest,RoundTripTest,SeedGeneratorTest' -Dsurefire.failIfNoSpecifiedTests=false test`; read `forecast-tools/target/surefire-reports/TEST-*ExportImporterTest.xml`: `tests="2" failures="0" errors="0"`. Then `mvn -B -q -DskipTests test-compile` from `server/`, exit 0.

- [ ] **Step 6: Commit and push**

```bash
git add server/forecast-tools
git commit -m "Clear the application tables under the work tables on a replace

The seed only ever targets the local database, so its replace owns
project_history, task_comments and task_attachments too, instead of
failing on their foreign keys against a database that holds them
(design 2026-09-18, section 2)."
git push -u origin dev
```

---

### Task 2: The writer is plain inserts and the driver writes JSON only

**Files:**
- Modify: `server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/export/SqlExportWriter.java` (whole file)
- Modify: `server/forecast-tools/src/main/java/com/workloadhub/forecast/tools/Experiment.java:62` (usage), `:102` (the `seed` option set), `:170-215` (`seed`)
- Test: `server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/export/SqlExportWriterTest.java`, `server/forecast-tools/src/test/java/com/workloadhub/forecast/tools/ExperimentFlowTest.java:48-61`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `SqlExportWriter.write(ExportEnvelope, Writer)` unchanged in signature; `USER_CONTENT_GUARD`, `UPSERTED` and `onConflict` no longer exist. `experiment.sh seed --format ...` is an unknown option (exit 2).

- [ ] **Step 1: Write the failing tests**

In `SqlExportWriterTest.java`, delete the three tests `aFiveTableEnvelopeIsLandedWithDeletesAndAProjectsUpsert`, `aSyntheticEnvelopeHasNoDeletes` and `aFiveTableScriptLandsTwiceOnPostgresql` (from the `@Test` line before each through its closing brace), and add in their place:

```java
    /**
     * The writer only ever inserts: a seed lands through the importer, and the script is the fixture's (design
     * 2026-09-18, section 3). A real-mode envelope gets no deletes, no upsert and no guard either.
     */
    @Test
    void aRealModeEnvelopeIsPlainInsertsToo() throws Exception {
        ExportEnvelope input = ExportFiles.read(Path.of("src/test/resources/fixtures/mini-export.json"));
        ExportEnvelope env = SeedGenerator.generate(input, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0));
        StringWriter out = new StringWriter();
        SqlExportWriter.write(env, out);
        String sql = out.toString();
        assertTrue(!sql.contains("DELETE FROM") && !sql.contains("ON CONFLICT") && !sql.contains("RAISE EXCEPTION"), sql.substring(0, 200));
        assertTrue(sql.contains("INSERT INTO projects") && sql.contains("INSERT INTO tasks") && !sql.contains("INSERT INTO users"),
                "the five work tables and nothing of the directory");
    }
```

In `ExperimentFlowTest.java`, replace the test `seedWritesJsonAndSqlThatImports` (lines 48 to 61) with:

```java
    @Test
    void seedWritesJsonThatImports(@TempDir Path dir) throws Exception {
        String[] db = connection(DatabaseTestSupport.postgres());
        Path seeded = dir.resolve("seeded.json");
        assertOk(experiment("seed", "--synthetic", "--users", "12", "--weeks", "8", "--seed", "1", "--end", "2026-09-06",
                "--out", seeded.toString()), "synthetic identities");
        assertOk(experiment(concat(db, "init-db")), "Created");
        assertOk(experiment(concat(db, "import", seeded.toString())), "Imported");
        assertEquals(2, experiment("seed", "--synthetic", "--users", "12", "--weeks", "8", "--format", "sql",
                "--out", dir.resolve("seeded.sql").toString()).exit(), "--format went with the SQL export (design 2026-09-18)");
        assertFalse(Files.exists(dir.resolve("seeded.sql")));
    }
```

(`assertFalse` and `Files` are already imported there.)

- [ ] **Step 2: Run both classes and see the two new tests fail**

`cd server && mvn -B -q -pl forecast-tools -am -Dtest='SqlExportWriterTest,ExperimentFlowTest' -Dsurefire.failIfNoSpecifiedTests=false test`. Expected: `aRealModeEnvelopeIsPlainInsertsToo` fails (the script still opens with the guard and the deletes) and `seedWritesJsonThatImports` fails (`--format sql` still exits 0 and writes the file).

- [ ] **Step 3: The writer**

Replace the whole of `SqlExportWriter.java` with:

```java
package com.workloadhub.forecast.tools.export;

import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Writes an envelope as one PostgreSQL transaction of INSERT statements. Its one reader is the committed test
 * fixture of {@code forecast-core} ({@code experiment.sh fixture}), which core's tests run without any importer;
 * a seed lands on the local database through {@link ExportImporter}, never through a script, since the real
 * database is never seeded (design 2026-09-18).
 */
public final class SqlExportWriter {

    private static final int ROWS_PER_STATEMENT = 200;

    private SqlExportWriter() {
    }

    public static void write(ExportEnvelope env, Writer out) throws IOException {
        out.write("BEGIN;\nSET search_path TO task_service;\n");
        for (String table : WorkloadHubSchema.TABLE_ORDER) {
            List<LinkedHashMap<String, Object>> rows = WorkloadHubSchema.parentsFirst(table, env.rows(table));
            if (rows.isEmpty()) {
                continue;
            }
            List<String> columns = WorkloadHubSchema.columnsOf(rows);
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

- [ ] **Step 4: The driver**

In `Experiment.java`:

1. Usage (line 62): `[--end ISO_DATE] [--seed N] [--format json|sql] [--force]` becomes `[--end ISO_DATE] [--seed N] [--force]`.
2. The `seed` option set (line 102): remove `"format"` from `Set.of("url", "user", "password", "out", "export", "users", "weeks", "end", "seed", "format")`.
3. In `seed(Args)`: delete the line `String format = args.string("format", "json");`, delete the block

```java
        if (!format.equals("json") && !format.equals("sql")) {
            System.err.println("--format must be json or sql");
            return 2;
        }
```

and replace

```java
        if (format.equals("sql")) {
            try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                SqlExportWriter.write(result, w);
            }
        } else {
            ExportFiles.write(out, result);
        }
```

with

```java
        ExportFiles.write(out, result);
```

`Writer`, `Files`, `StandardCharsets` and `SqlExportWriter` stay imported: `fixture(Args)` still uses all four. Confirm with `grep -n 'Writer\b\|SqlExportWriter' Experiment.java` that each has a remaining use; remove any import the compiler reports unused.

- [ ] **Step 5: Run the classes, the fixture freshness and the compile**

`cd server && mvn -B -q -pl forecast-tools -am -Dtest='SqlExportWriterTest,ExperimentFlowTest,FixtureFreshnessTest' -Dsurefire.failIfNoSpecifiedTests=false test`. Read the three XML files: `SqlExportWriterTest` `tests="4"`, `ExperimentFlowTest` `tests="6"`, `FixtureFreshnessTest` all passing (the committed fixture is byte-identical: a synthetic envelope never took the deleted branch; `git status --short server/forecast-core/src/test/resources/fixtures` prints nothing). Then `mvn -B -q -DskipTests test-compile` from `server/`, exit 0.

- [ ] **Step 6: Commit and push**

```bash
git add server/forecast-tools
git commit -m "Drop the SQL export format, its guard and the projects upsert

The script existed to feed the host's own database with psql, which the
owner ruled out on 2026-09-18: only the local database is ever seeded, and
it is fed through the importer. The writer stays for the fixture, as plain
inserts (design 2026-09-18, sections 3 and 4)."
git push -u origin dev
```

---

### Task 3: Documents, and the gate

**Files:**
- Modify: `docs/superpowers/specs/2026-09-17-personal-leaves-capacity-and-seed-scope-design.md:299` (section 7.2 heading)
- Modify: `server/README.md:120-124`, `:151`, `:169-180`, the "What the seed writes" section (line 182 on)
- Modify: `CLAUDE.md:151-170` ("Where the project stands", the 2026-09-18 paragraph), `:199` (the hard rule)
- Modify: `docs/backlog.md` (the 2026-09-18 importer entry under "Java migration", and "Landed")
- Modify: `docs/superpowers/plans/2026-09-18-seed-owns-the-work-tables.md` (closing notes)

**Interfaces:** none; documentation.

- [ ] **Step 1: The 2026-09-17 spec**

Directly under the heading `### 7.2 Projects are upserted, the rest replaced` insert:

```markdown

> **Superseded on 2026-09-18** by `2026-09-18-seed-owns-the-work-tables-design.md`: the owner ruled that only the
> local database is ever seeded, so the upsert and the user-content guard below are gone; the importer clears
> `project_history`, `task_comments` and `task_attachments` with the work tables, and `--format sql` no longer
> exists. The rest of this section is history.
```

- [ ] **Step 2: The README**

1. In the recipe, delete the line `$X seed --export ~/whf/workloadhub_export.json --weeks 52 --end 2026-09-06 --seed 42 --format sql --out ~/whf/seeded.sql`, and change the comment above it to `# 2. a year of history for the real directory: the seed writes projects, tasks, task_history, time_logs and personal_leaves; the application's own tables are read from the export and left alone (the export holds personal data: keep it and the output outside git)` (unchanged) followed by a new comment line `# only the local database is ever seeded; nothing of this ships to the WorkloadHub developer`.
2. In the command table, the `seed` row's options lose ` [--format json\|sql]`.
3. Replace the paragraph that starts `` `--seed` fixes the output byte for byte; `` and ends `it replaces only the tables the file carries.` with:

```markdown
`--seed` fixes the output byte for byte; `--end` is the as-of date, and the history covers `--weeks`
Monday weeks ending in the week of that date. `import` with an existing database replaces only the tables
the file carries, children first, plus the three application tables that reference them —
`project_history`, `task_comments` and `task_attachments` — because the seed owns everything under
`projects` on the one database it ever targets, the local one (design 2026-09-18). Two things to know:
`DELETE FROM personal_leaves` removes the leaves of **every** employee in the database, not only those of
the members the forecast counts, and a comment or attachment on a replaced task is gone with the task. There
is no SQL output any more: the real database is never seeded.
```

4. In "What the seed writes", after `no capacity rows are written, the module computes capacity itself.` add the sentence `Loading it clears the application's own project history, comments and attachments of the rows it replaces (see above).`

- [ ] **Step 3: CLAUDE.md**

1. Hard rule (line 199) becomes: `- The real export and any real-mode seed output stay outside the repository, and the seed only ever targets the local database of `scripts/postgres.sh`: the real WorkloadHub database is never seeded (owner, 2026-09-18), and nothing of `forecast-tools` or the scripts ships to the application's developer.`
2. In the "Where the project stands" 2026-09-18 paragraph, after the sentence ending `the speed-up the spec hoped for did not happen.` and before `Next:`, add: `Then, the same day, the seed's landing path (`docs/superpowers/specs/2026-09-18-seed-owns-the-work-tables-design.md`): the owner ruled that only the local database is ever seeded, so the importer clears `project_history`, `task_comments` and `task_attachments` with the work tables it replaces, and `seed --format sql`, its user-content guard and the projects upsert are gone; the whole-branch review of the PostgreSQL plan had found the importer failing on those foreign keys against any database that holds history or comments.` Update the gate figure in that paragraph to the one Step 5 measures.

- [ ] **Step 4: The backlog**

Move the "Java migration" entry `**The JSON importer deletes `projects` under `replace`, which a real database refuses (2026-09-18).**` to the top of "Landed", rewritten as: `- **The seed owns the work tables, local database only** (2026-09-18): the importer's replace clears `project_history`, `task_comments` and `task_attachments` with the work tables, the SQL export format with its guard and projects upsert is gone, by the owner's ruling that the real database is never seeded. Found by the PostgreSQL plan's whole-branch review as a foreign-key failure the next step would have hit. Spec `docs/superpowers/specs/2026-09-18-seed-owns-the-work-tables-design.md`, plan `docs/superpowers/plans/2026-09-18-seed-owns-the-work-tables.md`.` Update the header line `last updated 2026-09-18` if it is not already that date.

- [ ] **Step 5: The gate**

`rm -rf server/forecast-*/target/surefire-reports && bash scripts/check.sh` from the repository root; exit 0. Sum the `testsuite` attributes of both modules' `TEST-*.xml`: expected 437 tests (core 312, tools 125: three tests deleted, two added), 0 failures, 0 errors, 1 skipped. Record the wall time.

- [ ] **Step 6: Closing notes and commit**

Append to this plan:

```markdown
---

## Closing notes

Landed on `dev` on 2026-09-18, three tasks, each reviewed. Gate: <tests> tests (core <n>, tools <n>), 0 failures,
0 errors, 1 skipped, in <time>. The committed fixture did not change. Deviations from the spec: <none, or list>.
```

with the real figures, then:

```bash
git add CLAUDE.md server/README.md docs
git commit -m "Document the seed as owner of the work tables, local database only

The 2026-09-17 seed-scope spec's section 7.2 is superseded, the README's
recipe loses the SQL script and says what a replace clears, CLAUDE.md's
hard rule records the owner's ruling, and the backlog entry moves to Landed."
git push -u origin dev
```

---

## After the tasks

A whole-branch review over the three commits, one fix wave if it finds anything, then the release (`bash scripts/release.sh`, fast-forwarding `main` to `dev`) when the owner asks for it.

---

## Closing notes

Landed on `dev` on 2026-09-18, three tasks, each reviewed. Gate: 437 tests (core 312, tools 125), 0 failures,
0 errors, 1 skipped, in 11m45s (705 s, `bash scripts/check.sh`). The committed fixture did not change.
Deviations from the spec: none.
