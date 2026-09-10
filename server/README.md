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

# 6. list the teams, run a forecast, list its runs, and score every model
$CLI teams --db ~/whf/workloadhub.db
$CLI run --team "Platform" --db ~/whf/workloadhub.db --as-of 2026-09-06
$CLI runs --team "Platform" --db ~/whf/workloadhub.db
$CLI eval --db ~/whf/workloadhub.db --as-of 2026-09-06 --models xgboost,seasonal_naive --out ~/whf/eval
```

| command | options | what it does |
|---|---|---|
| `teams` | `[--db]` | Lists the teams with their counted member count. |
| `run` | `--team <name or id> [--as-of] [--db] [--model xgboost\|seasonal_naive] [--user] [--no-planned] [--json]` | Runs a forecast for one team as of a date (default: today) and prints the champion, the scores and the member-week table; `--model` forces a model, `--no-planned` switches off planned-work allocation, `--json` prints the result as JSON. Exits 2 on a usage error, 1 on a failed run. |
| `runs` | `--team <name or id> [--db] [--limit 20]` | Lists the runs of a team, newest first. |
| `eval` | `[--as-of] [--db] [--origins 6] [--models a,b] [--teams a,b] [--out dir]` | Scores every model at every origin (arrival level) and replays whole runs per team (demand level); writes `scores.csv`, `demand.csv` and `summary.md` in `--out` (default `./eval/<as-of>`). `--as-of` defaults to the latest task creation date; `--origins` are two weeks apart; `--models`/`--teams` default to all. |
| `narrate` | `--run <id> --user <name or id> [--lang en\|fr] [--model m] [--token-env GITHUB_TOKEN] [--db] [--json]` | Stores the user's GitHub token, narrates a finished run through their Copilot seat, streams progress to stderr, and prints the stored result. Exits 0 OK, 3 UNVERIFIED, 1 FAILED or error, 2 usage. |
| `copilot status` | `--user <name or id> [--db]` | Reports whether this user can narrate: token presence, runtime availability, sign-in and quota. |

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

## Narrating with Copilot

Narration uses the requesting user's own GitHub Copilot seat through `copilot-sdk-java`. The SDK runs an
**in-process runtime** (`runtime.node`, from the `copilot-sdk-java-runtime` artifact with classifier
`linux-x64`, 44 MB on the classpath); on first use it is unpacked into `~/.copilot/runtime-cache/<version>/`
(91 MB, once per SDK version, nothing downloaded). To use an installed Copilot CLI as a subprocess instead, set
`whf.copilot.cli-path` (server) or `WHF_COPILOT_CLI_PATH` (CLI).

Tokens are stored encrypted on `users.github_token` with the key in `whf.token-key` (server) or
`WHF_TOKEN_KEY` (CLI): a base64 AES-256 key, `openssl rand -base64 32`. Accepted tokens: `gho_`, `ghu_`,
`github_pat_`; classic `ghp_` tokens are refused.

```bash
export WHF_TOKEN_KEY="$(openssl rand -base64 32)"   # keep it: the stored tokens are unreadable without it
export GITHUB_TOKEN="gho_..."                      # the user's own token
$CLI copilot status --db ~/whf/workloadhub.db --user "Sara Tazi"
$CLI narrate --db ~/whf/workloadhub.db --run <run id> --user "Sara Tazi" --lang fr
```

`narrate` stores the token for the user, narrates, streams the steps, the thinking and the answer to stderr, and
prints the stored result on stdout: status (`OK`, `UNVERIFIED` with the numbers it could not find in the facts,
`FAILED` with the reason and the last answer), model, attempts, cost, then the narrative JSON. Exit codes: 0 OK,
3 UNVERIFIED, 1 FAILED or error, 2 usage. Every narration is a row in `forecast_narratives`, whatever its
status, so a failed one keeps its cost.

No automated test talks to Copilot. The live check is manual: run the two commands above on a seeded database
with a real token, and read `copilot status` first (it starts the runtime with the token and reports the login
and the quota). Sessions never resume; each narration is one client and one session, closed at the end.

## Parity check

`server/tools/parity.sh EXPORT_JSON OUT_DIR [AS_OF]` runs the Java and Python harnesses on the same
WorkloadHub export and compares them: `forecast init-db`, `import` and `eval --models xgboost,seasonal_naive`
into `OUT_DIR/java`, then, from `service/`, `uv run whf import-workloadhub` and
`uv run whf eval --models gbm,seasonal_naive` into `OUT_DIR/python`, then
`server/tools/parity_compare.py` on the two `scores.csv` files. When `AS_OF` is omitted, it is read back from
the Java summary's first line (`forecast eval` computes it as the latest task date), so both harnesses score
the same origins.

The Python import keeps fresh arrivals only (`--arrivals fresh`, the default): only tasks assigned within two
days of creation count as arrivals, which is the series the Java pipeline forecasts, so the two harnesses are
scoring the same thing rather than the Python harness's usual `est_hours` series.

The gate itself (design section 13): the booster's mean MASE over horizons 1 and 2, every origin, agrees within
0.10 between the two harnesses, and both sides pick the same champion (booster when its mean MASE is below
1.0, else the seasonal-naive floor). It is measured at the arrival level (the global backtest over every
counted member), not per team. `parity_compare.py` exits 0 on a pass, 1 on a fail, 2 on a usage error, and
`--out` writes the Markdown report.

The first synthetic result (36 users, 52 weeks, seed 11, as of 2026-09-06) is in
[`docs/eval/2026-09-10-java-parity-synthetic/parity.md`](../docs/eval/2026-09-10-java-parity-synthetic/parity.md),
alongside both harnesses' `summary.md`. It was produced with:

```bash
java -jar forecast-cli/target/workloadhub-forecast-cli-0.1.0-SNAPSHOT.jar \
  seed --synthetic --users 36 --weeks 52 --seed 11 --end 2026-09-06 --out <file>
tools/parity.sh <file> <out> 2026-09-06
```

Never run the procedure on the real export inside the repository: point `OUT_DIR` outside git and keep the
real-mode result in the owner's own folder.

The gate's own test, `service/tests/test_parity_compare.py`, runs `parity_compare.py` as a subprocess against
hand-built `scores.csv` fixtures (pass, tolerance-exceeded, champions-differ, no-booster-rows and
exactly-at-the-tolerance-boundary); it moved under `service/tests` so `uv run pytest` picks it up with the rest
of the Python suite. `parity_compare.py` and `translate-schema.py` stay standalone scripts under `server/tools/`
(no Python package there to import), so `ruff check`/`ruff format` run on them explicitly from `service/`:
`uv run ruff check . ../server/tools` and `uv run ruff format --check . ../server/tools`.
