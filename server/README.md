# WorkloadHub forecast: the Java module

Three Maven modules: `forecast-core`, the library the WorkloadHub Spring Boot application adds as a
dependency; `forecast-tools`, never shipped, which holds the seed, the import and export of a WorkloadHub
database, the experiment driver and the sample host; and `forecast-web`, also never shipped, a showcase
Spring Boot application with a React front end that depends on `forecast-core` alone ("Running the showcase"
below).
Design:
`docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`,
`docs/superpowers/specs/2026-09-13-weekly-hours-forecast-design.md`.

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

A container engine (Docker or podman) is required: the database tests run PostgreSQL 18 through
Testcontainers and the gate fails without one. The same engine runs the local database of
`scripts/postgres.sh`.

## Build and test

```bash
cd server
mvn -B verify                 # both modules: compiles, runs every test, builds forecast-core/target/workloadhub-forecast-core-0.1.0-SNAPSHOT.jar
mvn -B verify -Dseed.full=true   # also times the 264-user, 52-week seed
```

```bash
bash scripts/check.sh            # the gate: checks for the engine, then mvn verify
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
the same files. `/data` is `~/whf` on the host, for the seeds and exports that must stay
out of the repository. The database is not a file: `scripts/postgres.sh` runs it as a sibling
container, reachable from the box as `localhost:5432`. Five named volumes hold what is
expensive to fetch again — `~/.m2`,
`~/.cache`, `~/.local/share/uv` (where uv keeps its downloaded Pythons, not in `~/.cache`),
`~/.copilot` (where the SDK unpacks its runtime) and `~/.config/gh` (the token `gh auth login`
obtained, so a rebuild does not cost another browser sign-in) — so all of it survives a `rm` or a
rebuild. Worth keeping: a full Maven re-resolve took 9:45 against 5:47 warm. The script's header
documents every variable that overrides a default, and which of them are read only when the box is
created.

The image is `maven:3.9-eclipse-temurin-21` — Ubuntu 24.04 with Java 21 and Maven 3.9 — plus
`libgomp1`, git, less, ps, psql, uv, gh (for a Copilot token, see "Narrating with Copilot")
and `vi` (vim-tiny, so `vim` is not a command).
`libgomp1` is not optional: XGBoost4J loads a native
library that needs the OpenMP runtime, and without it sixteen tests fail, four of them as two-minute
`JavaHostIntegrationTest` timeouts that blame a run for not finishing rather than the library for not
loading.

The box mounts the engine's own socket, so Testcontainers starts PostgreSQL as a sibling container
and the database tests run; without that mount the gate fails, since nothing skips any more.
`devbox.sh` checks the mount from inside and warns if it is not a socket, because a wrong path is
created as an empty directory. Testcontainers' reaper is switched off rather than given the
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

From the root of the repository, inside the development container:

```bash
X="bash server/tools/experiment.sh"

# 0. the local database, once
bash scripts/postgres.sh up

# 1. the schema task_service with the 24 WorkloadHub tables and the module's tables
$X init-db                                    # --force drops and recreates the schema

# 2. a year of history for the real directory: the seed writes projects, tasks, task_history, time_logs and personal_leaves; the application's own tables are read from the export and left alone (the export holds personal data: keep it and the output outside git)
# only the local database is ever seeded; nothing of this ships to the WorkloadHub developer
$X seed --export ~/whf/workloadhub_export.json --weeks 52 --end 2026-09-06 --seed 42 --out ~/whf/seeded.json

# 3. load it
$X import ~/whf/seeded.json

# 4. or a synthetic population with no personal data, for tests and demos
# team size is emergent (ReferenceData.syntheticUsers: perDept = n / 9, capped at 10 members per team), so a
# run needs roughly eighty users before any team reaches eight members -- a team of three can never show
# rebalancing. Several teams tie for the largest size; not every one of them has a forecast-overloaded
# member, so try more than one before concluding rebalancing isn't showing up. The population's mean
# member-week should land in the high twenties to low thirties against the 44 h capacity, with some weeks
# well above it; a seed averaging in the low twenties predates the 2026-09-15 weekly-supply correction and
# is stale.
$X seed --synthetic --users 120 --weeks 52 --seed 7 --end 2026-09-06 --out /tmp/synthetic.json
$X import /tmp/synthetic.json

# 5. dump the database back to JSON
$X export /tmp/dump.json

# 6. regenerate forecast-core's committed test fixture after a change to the seed
$X fixture
```

| command | options | what it does |
|---|---|---|
| `init-db` | `[--force]` | Creates schema `task_service` with the 24 WorkloadHub tables and the module's tables; refuses an existing schema without `--force`. |
| `import` | `<file>` | Loads a WorkloadHub JSON export (real or seeded) into the database, replacing existing rows. |
| `export` | `<file>` | Writes the database's WorkloadHub tables as a JSON export. |
| `seed` | `--out <file> [--export f] [--synthetic] [--users n] [--weeks 52] [--end] [--seed 42] [--force]` | Generates an export with weeks of realistic history, from a real export (`--export`) or a synthetic directory (`--synthetic`). Real-mode output refuses to land inside a git repository without `--force`. Real mode writes five tables; synthetic mode writes them all. |
| `fixture` | `[--out <dir>]` | Regenerates `forecast-core`'s committed test fixture, `workloadhub-schema.sql` and `seeded-rows.sql`. Commit both. |
| any of them | `[--url] [--user] [--password]` | Where to connect: the options, else `WHF_DB_URL`, `WHF_DB_USER`, `WHF_DB_PASSWORD`, else the local database of `scripts/postgres.sh` (`jdbc:postgresql://localhost:5432/workloadhub`, user and password `workloadhub`). |

Those five are the whole of it: they build and move an experiment database, and freeze the seeded test
fixture. Everything a
*host* does — starting a run and polling its progress, reading the run, the current forecast, the run list,
accuracy, `copilotStatus` and a narration — is in `HostExample`, a class of `forecast-tools`, run through
`server/examples/run-host-example.sh` ("Integrating from the server's own code" below).

`server/tools/experiment.sh` runs `com.workloadhub.forecast.tools.Experiment`, a class of `forecast-tools`,
against the classpath `server/tools/tools-classpath.sh` resolves (it compiles both modules first when the sources
are newer). `ExperimentFlowTest` in `forecast-tools` drives every verb in process, so the driver is covered
by `mvn verify` like anything else. File arguments are resolved against your working directory. Every verb
calls the two modules' classes directly and needs no Spring context: the one that booted the module was
`eval`, removed on 2026-09-14 with the evaluation harness
(`docs/superpowers/specs/2026-09-14-evaluation-removal-design.md`).

`--seed` fixes the output byte for byte; `--end` is the as-of date, and the history covers `--weeks`
Monday weeks ending in the week of that date. `import` with an existing database replaces only the tables
the file carries, children first, plus the three application tables that reference them —
`project_history`, `task_comments` and `task_attachments` — because the seed owns everything under
`projects` on the one database it ever targets, the local one (design 2026-09-18). Two things to know:
`DELETE FROM personal_leaves` removes the leaves of **every** employee in the database, not only those of
the members the forecast counts, and a comment or attachment on a replaced task is gone with the task. There
is no SQL output any more: the real database is never seeded.

## What the seed writes

Teams come from `users.manager_id`, one per manager under a department team per department code;
job titles decide the kind of work; each member gets a weekly rhythm with seasonal dips, project
ramps, team events and absences; tasks are created into the backlog or assigned directly, worked
three at a time, logged day by day, reviewed, blocked or reopened at the design's rates, and a few
finish without logs. Leaves are written as `personal_leaves` (paid leave blocks, one in five ending on a
half day, sick days, and for one member in ten a pending request after the as-of date); no capacity rows
are written, the module computes capacity itself. Loading it clears the application's own project
history, comments and attachments of the rows it replaces (see above). The invariants the tests hold
are listed in the design, section 4.8.

## Running the showcase

`forecast-web`, a third Maven module, is a Spring Boot application with a React front end that calls
`forecast-core` the way the WorkloadHub server will and shows every feature of the forecast with the route
behind it — a page per role rule, per run, per narration, for someone to click through rather than read
about. It depends on `forecast-core` only, the same one dependency a real host adds, so it demonstrates
exactly what shipping the module gets a host and nothing that `forecast-tools` adds for development.
It targets the same local PostgreSQL as "Running experiments" above and creates and seeds nothing itself, so
run `init-db`, `seed` and `import` first:

```bash
bash scripts/postgres.sh up
bash server/tools/experiment.sh init-db
bash server/tools/experiment.sh seed --synthetic --users 120 --weeks 52 --seed 7 --out /tmp/seed.json
bash server/tools/experiment.sh import /tmp/seed.json
bash server/forecast-web/run.sh          # builds the front end and the jar when stale, then http://localhost:8080
```

Details, the page inventory, the REST surface and the no-authentication warning: `server/forecast-web/README.md`.

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
| `whf.default-weekly-hours` | `44.0` | Weekly capacity of a member with no override, spread over the working days. |
| `whf.run-threads` | `2` | Size of the pool that runs forecasts; it does not bound narrations. |
| `whf.forecast.windows` | `2` | The rolling horizon's window count, 1 to 6, each window five weekdays; refused outside that range at start-up. |
| `whf.copilot.model` | `""` (blank) | The model each narration asks for; blank means the account default. A request may override it. |
| `whf.copilot.cli-path` | `""` (blank) | Path to an installed Copilot CLI; blank means the in-process runtime. |
| `whf.copilot.timeout-seconds` | `300` | How long one `ask` may take before the narration ends as `FAILED` with reason `timeout`. |
| `whf.web.enabled` | `false` | Registers the REST controller. Off by default: a host that calls `ForecastService` directly exposes nothing. |
| `whf.web.base-path` | `/api/forecast` | Where the controller is mounted. |
| `whf.flyway.enabled` | `true` | Runs the module's migrations at start-up; `false` when the host migrates the module's tables itself. |

The run day is today by the `java.time.Clock` bean; the auto-configuration registers
`Clock.systemDefaultZone()` unless the host provides one (a fixed clock in tests, a zoned clock in
production). A forecast covers a rolling horizon of `whf.forecast.windows` five-weekday windows after the
run day, two by default
(`docs/superpowers/specs/2026-09-10-rolling-forecast-windows-design.md`,
`docs/superpowers/specs/2026-09-13-weekly-hours-forecast-design.md` section 4).

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

- **A run**: check the role, `startRun(new RunRequest(teamId, userId))`, let the page poll
  `progress(runId)` and show `label` in the user's language until `DONE` or `FAILED`, then read `getRun(runId)`
  and `currentForecast(teamId, from, to)` (per member and day, over the rolling horizon). Persist
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
  repository, inside the development container) runs `HostExample`, a standalone Spring Boot
  application on the local PostgreSQL that makes every one of those calls and prints what comes back — a run
  with its progress labels, the windows and the overload, the current forecast, the run list, accuracy and
  `copilotStatus`. Without `--team` it lists the teams, marking which of them have a `TEAM_LEADER`: the example
  acts as that user, and refuses a team that has none, since it has no session to take a user from. With
  `--narrate` it also calls the model, which is the live Copilot check ("Narrating with Copilot" below). It is
  a class of `forecast-tools`, so the gate compiles it; its `DataSource` is the one Spring Boot builds from
  `spring.datasource.*`, exactly as in the server. What it deliberately leaves out is the host's own work:
  the role check, the one-run-at-a-time rule and the narration executor, which are in the sample facade above.
- **One instance**: progress and the run executor live in the JVM. Once every bean is up (after your own
  Flyway, whichever owns the module's tables) the module marks runs left `QUEUED` or `RUNNING` by the previous
  process as `FAILED` (`interrupted by a restart`); a database that cannot answer is logged, never fatal.

### The REST surface (`whf.web.enabled=true`)

Paths are relative to `whf.web.base-path`. Authorisation is the host's: `requestedBy` is trusted as given.

| route | body in | out |
|---|---|---|
| `POST /runs` | `RunRequestBody` (`teamId`, `requestedBy`; an `asOf` field is refused with 400) | 202, `{"id": "<uuid>"}` |
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
- Nothing of the seed, the schema scripts or the driver: they live in `forecast-tools`, which the host never
  adds.

## The tools module

`forecast-tools` depends on `forecast-core` and is never shipped. It holds the seed (`tools.seed`), the
import and export of a WorkloadHub database and the schema script (`tools.export`), the driver
(`tools.Experiment`) and the sample host (`examples.HostExample`). Its tests are the seed's, the
export code's, the driver's and `FixtureFreshnessTest`, which fails the gate when
`forecast-core/src/test/resources/fixtures/` no longer matches what the generator produces: run
`bash server/tools/experiment.sh fixture` and commit the two files.

## Narrating with Copilot

Narration uses the requesting user's own GitHub Copilot seat through `copilot-sdk-java`. The SDK runs an
**in-process runtime** (`runtime.node`, from the `copilot-sdk-java-runtime` artifact with classifier
`linux-x64`, 44 MB on the classpath); on first use it is unpacked into `~/.copilot/runtime-cache/<version>/`
(91 MB, once per SDK version, nothing downloaded). To use an installed Copilot CLI as a subprocess instead, set
`whf.copilot.cli-path`.

Tokens are stored encrypted on `users.github_token` with the key in `whf.token-key`: a base64 AES-256 key,
`openssl rand -base64 32`. Accepted tokens: `gho_`, `ghu_`, `github_pat_`; classic `ghp_` tokens are refused.

The experiment driver does not narrate. The live path is exercised by the sample host, `HostExample` in
`forecast-tools`, which reads the key from `WHF_TOKEN_KEY` (into `whf.token-key`) and the user's
token from `WHF_EXAMPLE_GH_TOKEN`, saving it through `GitHubTokenStore.save` as a settings page would.

It needs a seeded database, and its default is the local PostgreSQL of `scripts/postgres.sh`, the same one
the driver writes to: run "Running experiments" above (`init-db`, then `seed`, then `import`) and the
example finds the result with no argument. It reads `--url`, `--user` and `--password`, then `WHF_DB_URL`,
`WHF_DB_USER` and `WHF_DB_PASSWORD`, exactly as the driver does, so another database is one option away.
Then, from the root of the repository inside the development container:

```bash
export WHF_TOKEN_KEY="$(openssl rand -base64 32)"   # keep it: the stored tokens are unreadable without it
export WHF_EXAMPLE_GH_TOKEN="gho_..."               # the user's own token
bash server/examples/run-host-example.sh                                   # lists the teams in the local database
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
`get_likely_work` and `get_rebalancing_candidates`, so a full narration is roughly `4 + 5 x members` calls.
The names themselves are in `progress(runId).message` (`tool <name>`) for a
host that polls while narration runs, which this example does not, since it calls `narrate` on the main thread.
Confirm too that no permission prompt was needed: the tools are
marked `skipPermission(true)`, and the handler behind them approves only a request whose `kind` is
`custom-tool` and whose `toolName` is one of the nine. A prompt of another `kind` would still let the
narration through — the tools skip permission — but it means the handler's assumption about the runtime is
wrong: report it.
Sessions never resume; each narration is one client and one session, closed at the end.
