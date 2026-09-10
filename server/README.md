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

## Using the module from the WorkloadHub server

The host adds `forecast-core` as a dependency; nothing else is required of it. Spring Boot's
auto-configuration (`ForecastAutoConfiguration`, registered through
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`) sees the host's own
`DataSource`, runs the module's Flyway migrations into the host's schema under its own history table
(`forecast_schema_history`, baselined at version 0 so the WorkloadHub tables are left alone), and registers
the run store, the token store, the narrator, the Copilot gateway and the `ForecastService` bean. Every bean
is `@ConditionalOnMissingBean`: a host that declares its own replaces it.

```xml
<dependency>
  <groupId>com.workloadhub</groupId>
  <artifactId>workloadhub-forecast-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Properties

| property | default | what it does |
|---|---|---|
| `whf.token-key` | unset | Base64 AES-256 key (32 bytes) for the tokens on `users.github_token`. Unset means no token can be stored or read: narration fails with `TOKEN_KEY_MISSING`. |
| `whf.work-dir` | `${user.home}/.workloadhub-forecast` | The module's own directory; the Copilot home is `<work-dir>/copilot`. **It must be writable by the user the server runs as** (a service account often has no home directory: set it explicitly). |
| `whf.default-weekly-hours` | `40.0` | Weekly capacity of a member with no override, spread over the working days. |
| `whf.run-threads` | `2` | Size of the pool that runs forecasts; it does not bound narrations. |
| `whf.planned-work.enabled` | `true` | Allocates backlog work to members; `false` leaves demand to open and predicted work only. |
| `whf.copilot.model` | `""` (blank) | The model each narration asks for; blank means the account default. A request may override it. |
| `whf.copilot.cli-path` | `""` (blank) | Path to an installed Copilot CLI; blank means the in-process runtime. |
| `whf.copilot.timeout-seconds` | `300` | How long one `ask` may take before the narration ends as `FAILED` with reason `timeout`. |
| `whf.web.enabled` | `false` | Registers the REST controller. Off by default: a host that calls `ForecastService` directly exposes nothing. |
| `whf.web.base-path` | `/api/forecast` | Where the controller is mounted. |
| `whf.flyway.enabled` | `true` | Runs the module's migrations at start-up; `false` when the host migrates the module's tables itself. |

### The REST surface (`whf.web.enabled=true`)

Paths are relative to `whf.web.base-path`. Authorisation is the host's: `requestedBy` is trusted as given.

| route | body in | out |
|---|---|---|
| `POST /runs` | `RunRequest` (`teamId`, `requestedBy`, `asOf`, `forcedModel`, `plannedWork`) | 202, `{"id": "<uuid>"}` |
| `GET /runs/{id}` | | 200, `RunResult` (the run, its member weeks, scores and facts) |
| `GET /teams/{teamId}/runs?limit=20` | | 200, `RunSummary[]`, newest first |
| `GET /runs/{id}/progress` | | 200, `RunProgress` (`phase`, `percent`, `message`, `thinking`, `answer`) |
| `POST /runs/{id}/narratives` | `{"requestedBy", "language", "model"}` | 200, `NarrativeResult` whatever its status |
| `GET /runs/{id}/narratives/{lang}` | | 200, the latest `NarrativeResult` of that language; 404 `NARRATIVE_NOT_FOUND` when there is none |
| `GET /copilot/status?userId=` | | 200, `CopilotStatus` |
| `PUT /users/{id}/github-token` | `{"token"}` | 204 |
| `DELETE /users/{id}/github-token` | | 204 |

Every error is `{"code", "message"}` (`ForecastExceptionHandler`): **404** for a code ending in `_NOT_FOUND`
(`TEAM_NOT_FOUND`, `RUN_NOT_FOUND`, `USER_NOT_FOUND`, `NARRATIVE_NOT_FOUND`), **400** for `INVALID_REQUEST`
(including a malformed body, a path variable that is not a UUID and a missing request parameter), **409** for
every other code (`RUN_NOT_DONE`, `TOKEN_MISSING`, `TOKEN_KEY_MISSING`, `TOKEN_REJECTED`,
`COPILOT_UNAVAILABLE`).

`POST /runs/{id}/narratives` **blocks for the whole narration** (a minute or more), as `ForecastService.narrate`
does: the module has no queue and no executor of its own. A host that wants it asynchronous runs the call on
its own executor and lets the browser poll `GET /runs/{id}/progress`, whose narration phases are `NARRATING`
(with the `thinking` and `answer` tails, the last 16 000 characters of each), then `NARRATED` or
`NARRATION_FAILED`.

A host can replace the `CopilotGateway` bean — to script it in tests, to route it through its own
credentials — by declaring one of its own; the sample host does exactly that
(`forecast-core/src/test/java/com/workloadhub/forecast/samplehost/`, a `@SpringBootApplication` with a
`DataSource` and a `FakeGateway` bean, driven through `MockMvc` in `SampleHostIntegrationTest`).

### What the module adds to the host's dependency tree

- `com.github:copilot-sdk-java` **1.0.13-preview.6** and its runtime artifact
  `copilot-sdk-java-runtime:linux-x64` (44 MB, `runtime` scope), unpacked to `~/.copilot/runtime-cache/<version>/`
  on first use (91 MB, nothing downloaded).
- `net.java.dev.jna:jna` **5.19.1**, optional in the SDK's own pom and required by the in-process runtime.
- **Jackson 2** (`com.fasterxml.jackson`), which the SDK uses, next to the host's Jackson 3 (`tools.jackson`).
  The two coexist under different package names. The SDK asks for **2.22.2**; a Spring Boot host's dependency
  management pins it instead (this build resolves 2.21.5, and the SDK works on it). A host that pins a much
  **older** Jackson 2 can break the SDK's deserialisation: check it after upgrading either side.
- `spring-webmvc` is optional and `jakarta.servlet-api` is `provided`: neither reaches a host that does not
  already have them, and the controller class is never loaded without Spring MVC and `whf.web.enabled`.

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
and the quota). In the streamed steps, confirm that the nine tools were called (`get_run_overview` first, then
the five member tools for every member, then `get_project_timelines`, `get_planned_work` and
`get_rebalancing_candidates`) and that no permission prompt was needed: the tools are marked
`skipPermission(true)`, and the handler behind them approves only a request whose `kind` is `custom-tool` and
whose `toolName` is one of the nine. A prompt of another `kind` would still let the narration through — the
tools skip permission — but it means the handler's assumption about the runtime is wrong: report it.
Sessions never resume; each narration is one client and one session, closed at the end.

## Parity check

`server/tools/parity.sh EXPORT_JSON OUT_DIR ARCHIVE_DIR [AS_OF]` runs the Java and Python harnesses on the same
WorkloadHub export and compares them, where `ARCHIVE_DIR` is a checkout of the branch
`archive/python-desktop-v1` (`git worktree add ../whf-archive archive/python-desktop-v1`), which holds the
Python service: `forecast init-db`, `import` and `eval --models xgboost,seasonal_naive`
into `OUT_DIR/java`, then, from `ARCHIVE_DIR/service`, `uv run whf import-workloadhub` and
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
tools/parity.sh <file> <out> ../whf-archive 2026-09-06
```

Never run the procedure on the real export inside the repository: point `OUT_DIR` outside git and keep the
real-mode result in the owner's own folder.

The gate's own test, `server/tools/tests/test_parity_compare.py`, runs `parity_compare.py` as a subprocess against
hand-built `scores.csv` fixtures (pass, tolerance-exceeded, champions-differ, no-booster-rows and
exactly-at-the-tolerance-boundary). It is the one Python test left in the repository and runs from the root with
`uv run --python 3.11 --with pytest pytest server/tools/tests`, in `scripts/check.ps1`, `scripts/check.sh` and CI.
`parity_compare.py` and `translate-schema.py` stay standalone, standard-library scripts.
