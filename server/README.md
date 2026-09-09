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
