# The seed owns the work tables, and only the local database is ever seeded

**Date:** 2026-09-18
**Status:** approved by the owner in conversation on 2026-09-18; plan
`docs/superpowers/plans/2026-09-18-seed-owns-the-work-tables.md`
**Amends:** `2026-09-17-personal-leaves-capacity-and-seed-scope-design.md`, section 7.2 (the projects upsert
and the user-content guard), and the README's recipe. Everything else in that spec stands.

## 1. The ruling

The owner's words, 2026-09-18: the seed is only ever for the local database used for testing; the real
WorkloadHub database is never seeded, and the code handed to the application's developer carries no
seeding, importing or exporting at all. The last part is already true: `forecast-core` is the only
artifact, and `forecast-tools`, the driver and the scripts never ship (spec 2026-09-17, PostgreSQL only).

Two consequences for the seed's landing path:

1. **The seed owns everything under `projects`.** Real mode still writes its five tables (`projects`,
   `tasks`, `task_history`, `time_logs`, `personal_leaves`); when that output replaces what a local
   database holds, the three application tables that hang off those five are wiped with them:
   `project_history` (references `projects`), `task_comments` and `task_attachments` (reference `tasks`).
   No upsert of `projects`, no guard refusing to run while comments or attachments exist: both existed to
   protect a real database that will never see this output. The directory (users, teams, memberships,
   roles, job titles, statuses, types, holidays) stays untouched, as before.
2. **The SQL export format goes.** `seed --format sql` existed for one purpose, feeding the host's own
   database with `psql`; the README said so. With that purpose gone the option, its validation, the
   user-content guard and the `ON CONFLICT` upsert are dead code and are deleted. `SqlExportWriter`
   itself stays, smaller, because `experiment.sh fixture` writes `seeded-rows.sql` with it and core's tests
   load that file without any importer (spec 2026-09-17, PostgreSQL only, section 5.3).

## 2. The importer

`ExportImporter.importAll(envelope, replace = true)` deletes, children first (the reverse of
`WorkloadHubSchema.TABLE_ORDER`), every table the envelope carries **and** every table of
`WorkloadHubSchema.DEPENDENTS_OF_WORK` when the envelope carries `projects` or `tasks`:

```java
/** Application tables that reference the seed's work tables: a replace of the work tables clears them first. */
public static final List<String> DEPENDENTS_OF_WORK = List.of("project_history", "task_comments", "task_attachments");
```

then inserts the envelope's rows in `TABLE_ORDER`, as today. For a full synthetic envelope (every table
present) nothing changes: the three are carried and were already deleted. For a real-mode envelope the
delete order is `task_attachments`, `task_comments`, `time_logs`, `task_history`, `project_history`,
`tasks`, `projects`, `personal_leaves` — `TABLE_ORDER` reversed, filtered — so no foreign key is ever
crossed, and the transaction is one, as today. `replace = false` is unchanged: insert only.

## 3. The writer

`SqlExportWriter.write` becomes `BEGIN`, `SET search_path TO task_service`, one `INSERT` per 200 rows per
table in `TABLE_ORDER` with self-references parents first, `COMMIT`. The `partial` branch, the guard
constant, `UPSERTED` and `onConflict` go. The committed fixture is byte-identical before and after: a
synthetic envelope never took that branch, which `FixtureFreshnessTest` proves on the next gate.

## 4. The driver

`seed` loses `--format`: the option name in `Args.parse`, the validation, the SQL branch and the usage line.
`seed` writes JSON only. Exit codes and every other option are unchanged.

## 5. Tests (written first)

- `ExportImporterTest.replaceClearsTheApplicationTablesUnderTheWorkTables` on PostgreSQL: land the
  mini-export fixture in full, add one `project_history` row for an existing project and one
  `task_comments` row for an existing task, generate a real-mode seed from the fixture, import it with
  `replace`; assert the three dependent tables are empty, `projects` holds exactly the seed's ids, and the
  directory tables' counts are unchanged. This test fails on today's code with a foreign-key error, which
  is the defect the 2026-09-18 whole-branch review recorded.
- `ExportImporterTest.replaceDeletesOnlyTheTablesTheEnvelopeCarries` keeps its assertion for the directory
  tables and gains the dependents.
- `SqlExportWriterTest`: `aFiveTableEnvelopeIsLandedWithDeletesAndAProjectsUpsert` and
  `aFiveTableScriptLandsTwiceOnPostgresql` are deleted with the behaviour; `aSyntheticEnvelopeHasNoDeletes`
  becomes `aScriptHasNoDeletes` over a real-mode envelope too.
- `ExperimentFlowTest.seedWritesJsonAndSqlThatImports` loses its SQL half and is renamed
  `seedWritesJsonThatImports`; a new assertion checks `--format` is now an unknown option (exit 2).

## 6. Documents

- Spec 2026-09-17, section 7.2: a dated note at its top pointing here; the section is history.
- `server/README.md`: the recipe loses the `--format sql` line and the `psql -d avl_workloadhub` paragraph
  with the guard; the command table's `seed` row loses `--format`; "What the seed writes" states that a
  replace clears `project_history`, `task_comments` and `task_attachments`; a sentence says the seed
  targets the local database only and nothing of it ships.
- `CLAUDE.md`: the hard rule "The real export and any real-mode seed output stay outside the repository"
  gains "and the seed only ever targets the local database of `scripts/postgres.sh`"; the "Where the
  project stands" paragraph gets one sentence; "Next" is unchanged.
- `docs/backlog.md`: the importer entry of 2026-09-18 moves under "Landed" with this spec's name.

## 7. Out of scope

`ExportExporter` writing `refresh_tokens` when present, and the other leftovers of the 2026-09-18 plan,
stay in the backlog. The seed's generation logic is untouched.
