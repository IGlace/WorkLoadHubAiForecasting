# WorkloadHub forecast: the Java module

One Maven module, `forecast-core`: the library the WorkloadHub Spring Boot application adds as a
dependency. Experiments — building a SQLite database and scoring models on it — are driven by
`tools/experiment.sh`, which is not a module but a single Java file the launcher compiles on the spot.
Design:
`docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`.

## Prerequisites

A JDK 21 and Maven on the machine. On Debian or Ubuntu:

```bash
sudo apt update && sudo apt install -y openjdk-21-jdk maven
java -version   # 21
mvn -version    # 3.8 or newer
```

Or, on a machine that has neither and cannot easily be given them, the development container:
`bash scripts/devbox.sh shell` opens a shell in an Ubuntu box that already has the whole toolchain,
with this repository mounted. See "The development container" below.

A container engine is optional for the build itself. When the tests can reach one the PostgreSQL
tests run against a real database; otherwise they skip themselves with a message.

## Build and test

```bash
cd server
mvn -B verify                 # compiles, runs every test, builds forecast-core/target/workloadhub-forecast-core-0.1.0-SNAPSHOT.jar
mvn -B verify -Dseed.full=true   # also times the 264-user, 52-week seed
```

## The development container

`scripts/devbox.sh` keeps an Ubuntu container running with Java 21, Maven and uv already in it, so
work that needs Linux can be done by hand inside it. It is how this project is built on a Windows
machine with no JDK and a WSL distro whose DNS does not work, so nothing can be installed there
either.

```bash
bash scripts/devbox.sh up        # build the image if needed and start the box; it stays up
bash scripts/devbox.sh shell     # a shell inside it, starting it first if it is down
bash scripts/devbox.sh exec mvn -v        # one command, without opening a shell
bash scripts/devbox.sh status    # what is mounted, and whether Testcontainers has an engine
bash scripts/devbox.sh stop      # `up` brings it back with everything intact
bash scripts/devbox.sh restart   # stop and start again
bash scripts/devbox.sh rebuild   # after editing scripts/container/Containerfile
bash scripts/devbox.sh --help    # the script's header, which is the reference for the variables
```

Inside the box, `/work` is this repository **bind-mounted, not copied**: an edit made in the box is
an edit on Windows and the other way round, so an editor on the host and a shell in the box work on
the same files. `/data` is `~/whf` on the host, for the databases, seeds and exports that must stay
out of the repository. Five named volumes hold what is expensive to fetch again — `~/.m2`,
`~/.cache`, `~/.local/share/uv` (where uv keeps its downloaded Pythons, not in `~/.cache`),
`~/.copilot` (where the SDK unpacks its runtime) and `~/.config/gh` (the token `gh auth login`
obtained, so a rebuild does not cost another browser sign-in) — so all of it survives a `rm` or a
rebuild. Worth keeping: a full Maven re-resolve took 9:45 against 5:47 warm. The script's header
documents every variable that overrides a default, and which of them are read only when the box is
created.

The image is `maven:3.9-eclipse-temurin-21` — Ubuntu 24.04 with Java 21 and Maven 3.9 — plus
`libgomp1`, git, less, ps, psql, sqlite3, uv, gh (for a Copilot token, see "Narrating with Copilot")
and `vi` (vim-tiny, so `vim` is not a command).
`libgomp1` is not optional: XGBoost4J loads a native
library that needs the OpenMP runtime, and without it sixteen tests fail, four of them as two-minute
`JavaHostIntegrationTest` timeouts that blame a run for not finishing rather than the library for not
loading.

The box mounts the engine's own socket, so Testcontainers starts PostgreSQL as a sibling container
and the database tests **run** rather than skip. `devbox.sh` checks that mount from inside and warns
if it is not a socket, because a wrong path is created as an empty directory and the only symptom
would be tests quietly skipping. Testcontainers' reaper is switched off rather than given the
privileges it asks for (`TESTCONTAINERS_RYUK_PRIVILEGED`), which keeps one setting fewer per engine
at the cost of a run killed part way leaving a `postgres` container behind: `podman ps`.

That socket is also the box's blast radius, and it is wider than the two mounts suggest. Anything
that executes in there — a Maven plugin, a transitive dependency, a wheel uv fetched — can drive the
engine, and so can start a sibling container that mounts anything the engine can reach, `/mnt/c`
included. The box is root inside and stays up indefinitely, so a foothold persists. This is the
ordinary bargain for running Testcontainers from inside a container; it is worth knowing that it is
being made.

Sharing one worktree with Windows puts one requirement on the repository: every text file keeps LF
endings, which `.gitattributes` pins. Linux bash stops on the first CRLF line of a script with `set:
pipefail: invalid option name`, a message that names nothing it is about — and `core.autocrlf` is a
Windows-side setting that the container's git never reads, so before the pin the box saw 413 files as
modified against their LF blobs, which made `git add -A` in there a trap and stopped
`scripts/release.sh`, whose first check is a clean tree. With the pin, git reads the same in both
places, and `release.sh` runs in the box — the only place on this machine where the gate can run.

The container's clock is UTC, and that decides what "today" means to a forecast run. `WHF_TZ` changes
it, but only for a box being created: set it, then `rm` and `up`, if you run without `--as-of`. Only
podman on Windows is exercised; the script prefers podman and falls back to docker.

On a machine with no JDK, run the gate **inside** the box. `bash scripts/check.sh` on the Windows
host skips the Maven step and still exits 0, reporting success having compiled nothing.

## Running experiments

```bash
X="bash tools/experiment.sh"

# 1. a database with the WorkloadHub schema and the module's tables
$X init-db --db ~/whf/workloadhub.db

# 2. a year of history for the real directory (the export holds personal data: keep it and the output outside git)
$X seed --export ~/whf/workloadhub_export.json --weeks 52 --end 2026-09-06 --seed 42 --out ~/whf/seeded.json
$X seed --export ~/whf/workloadhub_export.json --weeks 52 --end 2026-09-06 --seed 42 --format sql --out ~/whf/seeded.sql

# 3. load it
$X import --db ~/whf/workloadhub.db ~/whf/seeded.json

# 4. or a synthetic population with no personal data, for tests and demos
$X seed --synthetic --users 40 --weeks 26 --seed 7 --end 2026-09-06 --out /tmp/synthetic.json

# 5. dump a database back to JSON
$X export --db ~/whf/workloadhub.db /tmp/dump.json

# 6. score every model on it
$X eval --db ~/whf/workloadhub.db --as-of 2026-09-06 --models xgboost,seasonal_naive --out ~/whf/eval
```

| command | options | what it does |
|---|---|---|
| `init-db` | `[--db] [--force]` | Creates the 24 WorkloadHub tables and the module's tables in a new SQLite file; refuses an existing file without `--force`. |
| `import` | `[--db] <file>` | Loads a WorkloadHub JSON export (real or seeded) into the database, replacing existing rows. |
| `export` | `[--db] <file>` | Writes the database's WorkloadHub tables as a JSON export. |
| `seed` | `--out <file> [--export f] [--synthetic] [--users n] [--weeks 52] [--end] [--seed 42] [--format json\|sql] [--force]` | Generates an export with weeks of realistic history, from a real export (`--export`) or a synthetic directory (`--synthetic`). Real-mode output refuses to land inside a git repository without `--force`. |
| `eval` | `[--as-of] [--db] [--origins 6] [--models a,b] [--teams a,b] [--out dir]` | Scores every model at every origin (arrival level) and replays whole runs per team (demand level); writes `scores.csv`, `demand.csv` and `summary.md` in `--out` (default `./eval/<as-of>`). `--as-of` defaults to the latest task creation date; `--origins` are two weeks apart; `--models`/`--teams` default to all. |

Those five are the whole of it: they build an experiment database and score models on it, which is what the
parity check needs (`tools/parity.sh` calls `init-db`, `import` and `eval`). Everything a *host* does —
starting a run and polling its progress, reading the run, the current forecast, the run list, accuracy,
`copilotStatus` and a narration — is in `examples/HostExample.java`, run through
`examples/run-host-example.sh` ("Integrating from the server's own code" below).

`tools/experiment.sh` runs `tools/Experiment.java` the same way: Java 21's single-file source launcher
(JEP 330) compiles it against `forecast-core`'s own classes and its runtime dependencies, resolved by
`tools/core-classpath.sh`, which compiles the module first if the sources are newer. There is no second
Maven module and no jar — until 2026-09-12 there was one, `forecast-cli`, wrapping picocli around core
classes that are all public anyway. File arguments are resolved against your working directory. `eval`
boots the module's auto-configuration over a SQLite `DataSource` and calls `ForecastService.evaluate`, so
it scores the engine a host gets rather than a copy assembled for the occasion; the other four verbs call
`forecast-core` classes directly and need no Spring context. `ExperimentFlowTest` in `forecast-core` drives
the whole file as a subprocess, so it is covered by `mvn verify` like anything else.

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

The run day is today by the `java.time.Clock` bean; the auto-configuration registers
`Clock.systemDefaultZone()` unless the host provides one (a fixed clock in tests, a zoned clock in
production). A forecast covers the ten weekdays after the run day in two windows of five
(`docs/superpowers/specs/2026-09-10-rolling-forecast-windows-design.md`).

### Integrating from the server's own code

The design is `docs/superpowers/specs/2026-09-11-host-integration-design.md`; the reference implementation is the
sample host in the tests (`forecast-core/src/test/java/com/workloadhub/forecast/samplehost/`: `ForecastAccess`,
`HostForecastFacade`, `JavaHostIntegrationTest`).

- **Wiring**: add the dependency, keep `whf.web.enabled` false, set `whf.token-key` from your secret store,
  `whf.work-dir` to a directory the service account can write, and declare a `java.time.Clock` bean in your
  time zone. The module's Flyway creates its tables in your schema under `forecast_schema_history`.
- **Who may do what** (the host enforces it; the module trusts `requestedBy`):

  | role | may start a run for | may view |
  |---|---|---|
  | `ADMIN` | any team | any team |
  | `SKILL_TEAM_LEADER` | a team whose parent team they manage, one at a time | those teams and their own memberships |
  | `TEAM_LEADER` | the teams they manage | those teams and their own memberships |
  | `MEMBER` | none | the teams they belong to |
  | `VIEWER`, `CENTER_MANAGER` | none | any team |

- **A run**: check the role, `startRun(new RunRequest(teamId, userId, null, null))`, let the page poll
  `progress(runId)` and show `label` in the user's language until `DONE` or `FAILED`, then read `getRun(runId)`
  and `currentForecast(teamId, from, to)` (per member and day, two windows of five weekdays). Persist
  `(runId, teamId, requestedBy)` in your own table when you start a run: `getRun` answers only for `DONE` runs
  and `listRuns` needs the team, so after a restart that row is what lets you authorize a poll of the
  interrupted run (the sample facade's in-memory map is a test convenience).
- **A narration**: check the role, `GitHubTokenStore.has(userId)` (one query) and that the run is `DONE`, then
  submit `narrate(new NarrativeRequest(runId, userId, language, null))` to your own bounded executor and
  return; the page polls `progress(runId)` (`NARRATING` with a label that rotates through "collecting data",
  "consulting Copilot", "thinking"; then `NARRATED` or `NARRATION_FAILED`) and reads `narrative(runId,
  language)`. Refuse a second narration of the same run and language while one is in flight. The sample
  facade's one-at-a-time and in-flight guards are check-then-act on in-memory maps, enough for one instance
  and sequential requests; a host serving concurrent requests for the same user should synchronise them.
- **Accuracy**: `accuracy(teamId, from, to)` returns the current-forecast rows compared with the logged hours
  and the scores by team, member and lead; nothing is stored, every call recomputes. Show it to the roles that
  can view the team.
- **Tokens**: your settings page calls `GitHubTokenStore.save(userId, token)` and `clear`, and shows
  `copilotStatus(userId)`, which opens a Copilot session and reports authentication and quota — that is the
  settings page's job, not a pre-check. The module reads a token in one place, at narration, and never returns
  it.
- **Errors**: `ForecastException.code()`: `*_NOT_FOUND` → 404, `INVALID_REQUEST` → 400, everything else → 409; a
  refused role check is your 403.
- **Try the calls first**: `bash server/examples/run-host-example.sh --team <uuid>` (from the root of the
  repository, inside the development container) runs `examples/HostExample.java`, a standalone Spring Boot
  application on the seeded SQLite file that makes every one of those calls and prints what comes back — a run
  with its progress labels, the windows and the overload, the current forecast, the run list, accuracy and
  `copilotStatus`. Without `--team` it lists the teams, marking which of them have a `TEAM_LEADER`: the example
  acts as that user, and refuses a team that has none, since it has no session to take a user from. With
  `--narrate` it also calls the model, which is the live Copilot check ("Narrating with Copilot" below). It is
  compiled by Java's single-file source launcher against `forecast-core`'s classes, so it adds no module to the
  build and can be edited and re-run in a few seconds. What it deliberately leaves out is the host's own work:
  the role check, the one-run-at-a-time rule and the narration executor, which are in the sample facade above.
- **One instance**: progress and the run executor live in the JVM. Once every bean is up (after your own
  Flyway, whichever owns the module's tables) the module marks runs left `QUEUED` or `RUNNING` by the previous
  process as `FAILED` (`interrupted by a restart`); a database that cannot answer is logged, never fatal.

### The REST surface (`whf.web.enabled=true`)

Paths are relative to `whf.web.base-path`. Authorisation is the host's: `requestedBy` is trusted as given.

| route | body in | out |
|---|---|---|
| `POST /runs` | `RunRequestBody` (`teamId`, `requestedBy`, `forcedModel`, `plannedWork`; an `asOf` field is refused with 400) | 202, `{"id": "<uuid>"}` |
| `GET /runs/{id}` | | 200, `RunResult` (the run, its member windows and days, scores and facts) |
| `GET /teams/{teamId}/runs?limit=20` | | 200, `RunSummary[]`, newest first |
| `GET /teams/{teamId}/current?from=YYYY-MM-DD&to=YYYY-MM-DD` | | 200, `CurrentDayForecast[]` per member and day (defaults: today and today + 20 days) |
| `GET /runs/{id}/progress` | | 200, `RunProgress` (`phase`, `percent`, `message` for logs, `label` `{en, fr}` for people; the label rotates through a few phrases every four seconds while Copilot works) |
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
(with a rotating label such as "collecting data", "consulting Copilot", "thinking"), then `NARRATED` or
`NARRATION_FAILED`; Copilot's streamed text is not exposed, the stored narrative holds the answer.

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
`whf.copilot.cli-path`.

Tokens are stored encrypted on `users.github_token` with the key in `whf.token-key`: a base64 AES-256 key,
`openssl rand -base64 32`. Accepted tokens: `gho_`, `ghu_`, `github_pat_`; classic `ghp_` tokens are refused.

The experiment driver does not narrate. The live path is exercised by the sample host,
`examples/HostExample.java`, which reads the key from `WHF_TOKEN_KEY` (into `whf.token-key`) and the user's
token from `WHF_EXAMPLE_GH_TOKEN`, saving it through `GitHubTokenStore.save` as a settings page would.

It needs a seeded database, and its default is `/data/workloadhub.db`. `/data` is the box's data mount, the
host's `~/whf`; it is **not** the `~/whf` that "Running experiments" above writes to, which inside the box is
`/root/whf`. So either run that recipe with `--db /data/workloadhub.db` in place of `~/whf/workloadhub.db`
(`init-db`, then `seed`, then `import`), or leave it where it is and point the example at it with its own
`--db`. Then, from the root of the repository inside the development container:

```bash
export WHF_TOKEN_KEY="$(openssl rand -base64 32)"   # keep it: the stored tokens are unreadable without it
export WHF_EXAMPLE_GH_TOKEN="gho_..."               # the user's own token
bash server/examples/run-host-example.sh                                   # lists the teams of /data/workloadhub.db
bash server/examples/run-host-example.sh --team <uuid> --narrate --lang fr
```

Pick a team the listing marks `TEAM_LEADER`. The seed leaves many without one — a department team's head is a
`SKILL_TEAM_LEADER`, and a manager who is also an `ADMIN` keeps that role — and the example stops with a
message rather than forecasting for a team it has no user to act as.

The application has no sign-in of its own yet, so the token is pasted; the intended path is a GitHub OAuth
App in the server, where the user signs in, accepts the connection and the server stores what comes back.
Until then `gh`, which the development box carries, produces one without leaving the terminal:

```bash
gh auth login           # GitHub.com, HTTPS, "Login with a web browser": it prints a one-time code to
                        # type at https://github.com/login/device in a browser on the host
export WHF_EXAMPLE_GH_TOKEN="$(gh auth token)"      # a gho_ token for your own seat
export WHF_TOKEN_KEY="$(cat /data/.whf-token-key)"  # generated once, outside the repository
```

The login lives in the `whf-gh` volume and survives a rebuild. Keep the key in a file of your own the same
way: a new key makes every token stored under the old one unreadable. If a seat check refuses the token,
`gh auth refresh -h github.com -s copilot` is the thing to try.

The example acts as the `TEAM_LEADER` the chosen team has (a token can only be stored for a user who exists,
which is why a team without one is refused), forecasts the team first — narration needs a `DONE` run — and then prints `copilotStatus` (token, runtime and version,
authenticated, login, message), whether a narrative of that language is already stored, and, with `--narrate`,
the narration: status (`OK`, `UNVERIFIED` with the numbers it could not find in the facts, `FAILED` with the
reason and the last answer), attempts, tool calls, the narrative JSON and the verification JSON. Without
`--narrate` it stops after `copilotStatus` and calls no model. Every narration is a row in
`forecast_narratives`, whatever its status, so a failed one keeps its cost.

No automated test talks to Copilot. The live check is manual: run the command above on a seeded database with a
real token, and read `copilotStatus` first (it starts the runtime with the token and reports the login and the
quota). Then confirm from the printed tool-call count that the model used the tools: the nine are
`get_run_overview`, the five member tools (called for every member), `get_project_timelines`,
`get_planned_work` and `get_rebalancing_candidates`, so a full narration is roughly `4 + 5 x members` calls.
The names themselves are in `progress(runId).message` (`tool <name>`) for a
host that polls while narration runs, which this example does not, since it calls `narrate` on the main thread.
Confirm too that no permission prompt was needed: the tools are
marked `skipPermission(true)`, and the handler behind them approves only a request whose `kind` is
`custom-tool` and whose `toolName` is one of the nine. A prompt of another `kind` would still let the
narration through — the tools skip permission — but it means the handler's assumption about the runtime is
wrong: report it.
Sessions never resume; each narration is one client and one session, closed at the end.

## Parity check

`server/tools/parity.sh EXPORT_JSON OUT_DIR ARCHIVE_DIR [AS_OF]` runs the Java and Python harnesses on the same
WorkloadHub export and compares them, where `ARCHIVE_DIR` is a checkout of the tag
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
bash tools/experiment.sh seed --synthetic --users 36 --weeks 52 --seed 11 --end 2026-09-06 --out <file>
tools/parity.sh <file> <out> ../../whf-archive 2026-09-06
```

(On the day, the first command was `java -jar forecast-cli/target/workloadhub-forecast-cli-*.jar seed …`;
the module is gone and the seed is the same code, so the result still reproduces. Its `summary.md` records
`forecast-cli: 0.1.0` in its versions for the same reason.)

Never run the procedure on the real export inside the repository: point `OUT_DIR` outside git and keep the
real-mode result in the owner's own folder.

Since 2026-09-10 the Java harness's `demand.csv` is per member-window while the archived Python harness's is
per member-week; the gate compares `scores.csv` only.

The gate's own test, `server/tools/tests/test_parity_compare.py`, runs `parity_compare.py` as a subprocess against
hand-built `scores.csv` fixtures (pass, tolerance-exceeded, champions-differ, no-booster-rows and
exactly-at-the-tolerance-boundary). It is the one Python test left in the repository and runs from the root with
`uv run --python 3.11 --with pytest pytest server/tools/tests`, and in `scripts/check.ps1` and `scripts/check.sh`.
`parity_compare.py` and `translate-schema.py` stay standalone, standard-library scripts.
