# forecast-web and the showcase UI: design

Date: 2026-09-18. Status: implemented on the branch `claude/forecast-web-showcase`, **not on `dev`**, which
has since been restructured — section 8 says what that costs and what re-integration needs, and it overtakes
decision 3. Written from the owner's request of 2026-09-18, decided by the author where the request left a
choice (section 2 lists each decision so the owner can overturn it). Builds on
`docs/superpowers/specs/2026-09-11-host-integration-design.md` (what a host does) and changes nothing in
`forecast-core`'s behaviour.

## 1. Goal

Two new pieces next to `forecast-core` and the experiment driver:

1. **`forecast-web`**, a Spring Boot application that calls `forecast-core` exactly the way the WorkloadHub
   server will in production — through `ForecastService` and `GitHubTokenStore`, behind the role rules of
   design 2026-09-11, with the run registry, the one-run-at-a-time rule and the narration executor that the
   sample host in `forecast-core`'s tests only sketches — and exposes every feature of the forecast system
   through REST.
2. **A showcase front end**, a React single-page application served by `forecast-web`, where the owner
   chooses a user to act as (there is no sign-in yet), stores that user's GitHub token, checks what each role
   may do, runs forecasts, reads the windows, the days, the facts and the narrative, and evaluates past
   forecasts against the hours actually logged. Every screen names the endpoint behind it, and a reference
   page documents the whole surface, so the application's developer can pick what to implement.

Everything the module already offers is shown; nothing new is computed. In particular the language model
still never produces a forecast number, demand is never capped, and Copilot is reached only through the SDK
with the acting user's own token (CLAUDE.md, "Hard rules").

## 2. Decisions taken here

The request settled the shape ("a Spring Boot application that calls the library like the host", "a front
end with whatever fast framework", "an input to fill the GitHub token", "choose a user to act as", "test
permissions", "test all prediction features, see the evaluation of previous predictions", "full
documentation the developer can pick from"). These choices were left to the author:

1. **The acting user is an HTTP header, `X-Acting-User: <user uuid>`.** The production server has a session;
   this application has none, so the front end sends the chosen user's id with every call and the server
   resolves it against `users` before anything else. A missing or unknown header is 401. The header is the
   only difference between this host and the real one: every check after it is the same code.
2. **`forecast-web` does not enable `whf.web.enabled`.** The module's own controller trusts `requestedBy`
   as given; the host's job is to decide it. `forecast-web` has its own controllers under `/api`, with the
   host routes of design 2026-09-11 section 3, and calls `ForecastService` from them.
3. **The database is a SQLite file, seeded at first start.** `forecast-web.database` names the file; when it
   does not exist the application creates the WorkloadHub schema, generates a synthetic population
   (`forecast-web.seed.*`, 120 users and 52 weeks by default, as the README recommends so that a team
   reaches eight members) and imports it. No personal data: the synthetic directory only. A PostgreSQL
   database is reached by leaving `forecast-web.database` blank and setting `spring.datasource.*`, which is
   what the production server does; the application then seeds nothing.
4. **The clock is a demo clock.** A run starts from "today" by the module's `Clock` bean, and a seeded
   history ends on 2026-09-06, so `forecast-web` pins today to `forecast-web.clock.today` (default
   2026-06-28: the synthetic history's logged hours peak from March to June and taper over its last eight
   weeks, so a run on the last day forecasts near zero, while a run in late June has two months of real
   logs after it for the accuracy page) and lets an `ADMIN` move it from the UI. Moving it is how accuracy gets something to score:
   run at an earlier date, move the clock forward, run again, and every weekday in between has a forecast
   made before it arrived. The production server deletes this bean and keeps the module's default, exactly
   as `HostExample` says of its fixed clock.
5. **The token key is generated once when none is given.** `whf.token-key` comes from `WHF_TOKEN_KEY`; when
   that is blank `forecast-web` reads or creates a base64 32-byte key in `forecast-web.token-key-file` so
   that a stored token survives a restart. Production reads the key from its secret store and never uses
   the file.
6. **React with Vite and TypeScript, no component library, no chart library.** Fast to build, nothing to
   learn for the developer who reads it, and the charts are a few SVG elements. The front end is built by
   npm and is not part of the Maven build: `forecast-web` serves `ui/dist` when it exists, and the gate
   gains an npm step that is skipped with a message when npm is absent, like the Maven step is today.
7. **Both tokens and the permission explorer act on the acting user only.** A user stores and clears their
   own token; nobody can read one. The permission explorer answers "what may user X do on team Y" for any
   pair, because it is a test tool for the owner, and the page says so.

## 3. `forecast-web`: the module

`server/forecast-web`, Maven artifact `workloadhub-forecast-web`, a child of the existing parent, packaged
as a Spring Boot jar. It depends on `forecast-core`, `spring-boot-starter-webmvc` and `sqlite-jdbc`; its
tests depend on `forecast-core`'s test jar (new: `forecast-core` publishes one) for `SeededData`,
`DatabaseTestSupport` and `FakeGateway`.

### 3.1 Wiring (what production copies)

- `ForecastWebApplication`, a `@SpringBootApplication`; the module's auto-configuration builds
  `ForecastService` and everything under it on top of the application's `DataSource`.
- `whf.*` properties in `application.yml`: `token-key` from the environment, `work-dir`, `run-threads`,
  `forecast.windows`, `flyway.enabled=true`, `web.enabled=false`.
- `ForecastAccess`: the role rules of design 2026-09-11 section 3.2, read from `users`, `teams` and
  `team_members`, the sample host's class moved into a main source set, plus a `reason` for every refusal so
  that the UI can say why a button is disabled.
- `HostForecastFacade`: the sample host's facade with the two things a real host must add: the run registry
  is a table (`forecast_web_runs`: `run_id`, `team_id`, `requested_by`, `started_at`, created with
  `CREATE TABLE IF NOT EXISTS` at start-up) so a restart can still authorise a poll, and the one-at-a-time
  rule for a `SKILL_TEAM_LEADER` reads the registry's latest run of that user. Narration runs on a bounded
  executor of two daemon threads named `forecast-narration`, one narration per run and language in flight.
- `ActingUser`: resolves `X-Acting-User` on every `/api` request (a `HandlerInterceptor`), exposes the
  user's id, role and name to the controllers, and answers 401 with `ACTING_USER_MISSING` or
  `ACTING_USER_UNKNOWN`. Production replaces this class with its session principal and changes nothing
  else.
- `ApiExceptionHandler`: `ForecastException` maps as the module's own handler does (`*_NOT_FOUND` 404,
  `INVALID_REQUEST` 400, else 409); `HostForbidden` is 403 `FORBIDDEN`; the acting-user failures are 401;
  a malformed body, a non-UUID path variable or a missing parameter is 400 `INVALID_REQUEST`. Every error
  body is `{code, message}`.

### 3.2 Wiring (demo only, deleted by production)

- `SqliteDatabase`: the `DataSource` from `forecast-web.database`, and `DemoSeeder`, which creates and
  seeds the file when it is missing (schema, `SeedGenerator.generate(null, SeedConfig)`, `ExportImporter`).
- `DemoClock`: a `Clock` whose date is `forecast-web.clock.today` and whose time of day is the system's, so
  the progress labels still rotate; `POST /api/system/clock` moves the date when
  `forecast-web.clock.adjustable` is true (default true) and the acting user is an `ADMIN`.
- `TokenKeyFile`: an `EnvironmentPostProcessor` that fills `whf.token-key` from the key file when the
  property is blank, creating the file (0600) on first use.
- `UiResources`: serves `forecast-web.ui-dir` (default `ui/dist`) at `/` with a fallback to `index.html`
  for any path that is neither `/api/**` nor a file, when the directory exists.

### 3.3 The REST surface

All paths under `/api`; every request except `GET /api/system` carries `X-Acting-User`. Dates are ISO 8601.

| route | who | in | out |
|---|---|---|---|
| `GET /system` | anyone | | `{today, clockPinned, clockAdjustable, windows, defaultWeeklyHours, runThreads, dialect, database, seededAtStart, actingUserHeader, bootstrapUserId}`; `bootstrapUserId` is the seed's `ADMIN` on the demo database, so the front end can read the directory before anyone is chosen, and null elsewhere |
| `POST /system/clock` | `ADMIN`, and only when adjustable | `{today}` | 200, the same body as `GET /system` |
| `GET /directory/users` | any acting user | | `[{id, fullName, role, jobTitle, department, active, teams: [{teamId, name, relation}]}]` where `relation` is `manages`, `member` or `manages-parent` |
| `GET /directory/teams` | any acting user | | `[{id, name, managerId, managerName, parentTeamId, parentTeamName, memberCount}]` |
| `GET /directory/teams/{id}` | any acting user | | the team with `members: [{id, fullName, role, jobTitle}]` |
| `GET /me` | any acting user | | `{user: {id, fullName, role, jobTitle}, hasToken, teams: [{teamId, name, canRun, canView, runReason, viewReason}]}` |
| `GET /permissions?userId&teamId` | any acting user | | `{userId, role, teamId, canRun, canView, runReason, viewReason}` |
| `POST /teams/{teamId}/forecast-runs` | `canRun` | `{}` (empty body accepted) | 202 `{id}`; 403 `FORBIDDEN` (role, or one at a time) |
| `GET /teams/{teamId}/forecast-runs?limit=20` | `canView` | | `RunSummary[]`, newest first |
| `GET /teams/{teamId}/forecast?from&to` | `canView` | | `CurrentDayForecast[]` (defaults: today and today + 20 days) |
| `GET /teams/{teamId}/accuracy?from&to` | `canView` | | `AccuracyResult` (defaults: today − 28 days and today − 1) |
| `GET /forecast-runs/{id}` | `canView` on the run's team | | `RunResult` with `facts` as a JSON object instead of the `factsJson` string, plus `members: [{id, fullName, role, jobTitle}]` |
| `GET /forecast-runs/{id}/progress` | `canView` on the run's team | | `RunProgress` |
| `POST /forecast-runs/{id}/narratives` | `canView`, a stored token, the run `DONE` | `{language, model}` | 202 `{runId, language}`; 409 `TOKEN_MISSING`, `RUN_NOT_DONE`, `NARRATION_IN_PROGRESS` |
| `GET /forecast-runs/{id}/narratives/{lang}` | `canView` on the run's team | | `NarrativeResult` with `narrative`, `verification` and `usage` as JSON objects; 404 `NARRATIVE_NOT_FOUND` |
| `GET /me/copilot` | any acting user | | `CopilotStatus` (opens a Copilot session: the settings page's call, not a pre-check) |
| `GET /me/github-token` | any acting user | | `{hasToken}` |
| `PUT /me/github-token` | any acting user | `{token}` | 204; 400 `INVALID_REQUEST` for a classic `ghp_` token or an empty one, 409 `TOKEN_KEY_MISSING` |
| `DELETE /me/github-token` | any acting user | | 204 |

A run not in the registry is 404 `RUN_NOT_FOUND` ("not started by this host"), as the sample facade does.
`POST /forecast-runs/{id}/narratives` returns at once; the page polls progress (`NARRATING`, then `NARRATED`
or `NARRATION_FAILED`) and reads the stored narrative. `GET /forecast-runs/{id}` answers only for `DONE` runs
(`RUN_NOT_DONE` 409 otherwise), as `ForecastService.getRun` does.

### 3.4 Properties

| property | default | what it does |
|---|---|---|
| `forecast-web.database` | `${user.home}/.workloadhub-forecast/forecast-web/workloadhub.db` | The SQLite file; blank means the application's own `spring.datasource.*` |
| `forecast-web.seed.users` | `120` | Synthetic population size when the file is created |
| `forecast-web.seed.weeks` | `52` | Weeks of history |
| `forecast-web.seed.seed` | `7` | The generator's seed |
| `forecast-web.seed.end` | `2026-09-06` | The history's last day |
| `forecast-web.clock.today` | `2026-06-28` | The demo clock's date; blank means the system date (the demo clock bean stays, unpinned) |
| `forecast-web.clock.adjustable` | `true` | Whether `POST /api/system/clock` is accepted |
| `forecast-web.token-key-file` | `${user.home}/.workloadhub-forecast/forecast-web/token.key` | Where a generated key lives when `WHF_TOKEN_KEY` is blank |
| `forecast-web.ui-dir` | `ui/dist` | The built front end, served when present |
| `server.port` | `8080` | |

`whf.*` keep the module's defaults; `application.yml` sets `whf.web.enabled=false` explicitly and reads
`whf.token-key` from `WHF_TOKEN_KEY`.

## 4. The front end

`server/forecast-web/ui`: Vite, React 19, TypeScript, react-router. One `api.ts` client that adds the
acting-user header and turns `{code, message}` errors into an `ApiError`; one `theme.css` with colour
tokens for light and dark; pages under `pages/`, shared pieces under `components/`. The acting user is kept
in `localStorage`. Every page shows the routes it called in a footer strip ("behind this page"), with the
last response inspectable as JSON, so the developer sees the contract while using the feature.

| page | route | what it shows and calls |
|---|---|---|
| Act as | top bar, every page | A searchable user picker grouped by role; the role badge; `GET /directory/users`, `GET /me` |
| Teams | `/teams` | Every team with manager, parent, member count and the acting user's `canRun` / `canView` with reasons; `GET /directory/teams`, `GET /me` |
| Team | `/teams/:id` | Members; the current forecast as a members × days grid with demand, capacity and overload per cell and per-member totals; the run list; the start button, disabled with the reason when not allowed; progress bar with the bilingual label while a run computes; `GET /directory/teams/{id}`, `GET /teams/{id}/forecast`, `GET /teams/{id}/forecast-runs`, `POST /teams/{id}/forecast-runs`, `GET /forecast-runs/{id}/progress` |
| Run | `/runs/:id` | The run summary (as-of, MAE, confidence); per member the windows (demand, low/high band, capacity, overload, working days, absence, backlog excess, due excess) as a bar chart and a table; the day rows; the backtest scores; the facts as a collapsible tree with the pressure lists (overloaded, underloaded, backlog pressed, deadline pressed) surfaced; the narration panel (section 4.1); `GET /forecast-runs/{id}`, and the narrative routes |
| Accuracy | `/teams/:id/accuracy` | From/to pickers; the scores by team, member and lead (n, MAE, bias, MASE, overload precision and recall); the rows as a forecast-versus-logged chart per day and a table; the explanation of what is scored; `GET /teams/{id}/accuracy` |
| Copilot & token | `/settings` | A password input for the token with the accepted prefixes, save and clear; `hasToken`; the `copilotStatus` card (runtime, version, authenticated, login, quota, message); `GET /me/github-token`, `PUT`, `DELETE`, `GET /me/copilot` |
| Permissions | `/permissions` | The role matrix of design 2026-09-11 as a static table; a live check for any user and team with the reasons; a button to act as that user; `GET /permissions` |
| Demo clock | `/clock` | Today; a date input to move it (admin only); the recipe for filling the accuracy page; `GET /system`, `POST /system/clock` |
| Docs | `/docs` | The integration guide (module versus host, the flow of a run, the narration flow, the properties, the hard rules) and the API reference generated from one TypeScript table of endpoints, each with method, path, who may call it, body, response and errors, and a "try it" for the GET routes |

### 4.1 The narration panel

A language switch (en, fr), a start button (disabled with the reason: no token, run not done, one in
flight), the rotating label while `NARRATING`, then the stored result: status (`OK`, `UNVERIFIED`, `FAILED`),
attempts, tool calls, model; the narrative rendered by section (run summary, members with risk level,
summary, patterns with evidence, warnings, likely work with confidence; team risks; rebalancing moves;
suggested adjustments; model notes); the verification with the unverified numbers listed and highlighted in
the text; on failure the error and the raw text. The panel says in one line that every number was checked
against the facts and that an unverified one is reported, never promoted.

### 4.2 Charts

Inline SVG: a grouped bar per member and window (demand with its band, capacity) on the run page; a
two-line chart of forecast against logged hours per day on the accuracy page; the members × days grid on
the team page uses a colour scale on overload. Colours come from the theme tokens; every chart has a text
table next to it, so nothing is only in a picture.

## 5. Testing

- **`forecast-web` unit tests**: `ForecastAccess` reasons per role; `DemoClock` date and ticking time;
  `TokenKeyFile` creates then reuses; `HostForecastFacade` registry survives a new instance on the same
  database; `runInProgress` phases; UI fallback resolution.
- **`forecast-web` integration tests** (`@SpringBootTest`, MockMvc, in-memory seeded SQLite from the core
  test jar, `FakeGateway` as the `CopilotGateway`): 401 without the header; the role matrix through
  `GET /me` and `POST .../forecast-runs` (a `MEMBER` gets 403, an `ADMIN` 202, a `VIEWER` reads and cannot
  run); a run through progress to `DONE`, the run with `facts` as an object and `members` named, the
  current forecast, the run list; a narration through 202, polling and the stored result with
  `narrative` and `verification` as objects, a second one refused while in flight; token put, get and delete
  and the `TOKEN_REJECTED` case; the demo clock moved by an admin, refused for a member, and accuracy with
  rows after a run at an earlier date and one at a later; `GET /system` without a header.
- **Front end**: vitest on the pure functions (the API client's error mapping, the permission summary, the
  chart scales, the unverified-number highlighter, the endpoint table's completeness against the routes);
  `tsc --noEmit`; `vite build`. `npm run check` runs the three.
- **The gate**: `scripts/check.sh`, `scripts/check.ps1` and `.github/workflows/ci.yml` gain the npm step
  (`npm ci && npm run check` in `server/forecast-web/ui`), skipped with a message when npm is missing.
- No test talks to Copilot. The live path is checked by hand: start the application, act as a team leader,
  store a real token on the settings page, run and narrate.

## 6. What this does not do

- No sign-in, no OAuth: the acting user header stands in for the session, and the page says so.
- No new forecast feature: every figure comes from `ForecastService` unchanged.
- No PostgreSQL test in this module: the module's own PostgreSQL tests cover the stores; `forecast-web`
  against PostgreSQL is a configuration, checked by hand.
- No front end in the Maven build: the jar serves a directory, and a release of the front end is
  `npm run build`.

## 8. Where this sits against `dev` (2026-09-18)

The branch was built on `c9bdc1c`. `dev` has since become PostgreSQL-only (`Dialect` gone, real JDBC types
bound, SQLite out of the module, the migrations collapsed into one `V1`), moved the seed, the export code,
`WorkloadHubSchema`, the driver and the sample host into a new `forecast-tools` module, replaced
`ExportFiles.mapper()` with `com.workloadhub.forecast.Json`, made the seeded test data a committed fixture
loaded into a Testcontainers PostgreSQL, and made the gate fail rather than skip without a container engine.

**Decision 3 of section 2 is therefore overtaken**: a SQLite file seeded at first start is no longer
available to this module, because the module it calls no longer speaks SQLite. The demo database has to be
PostgreSQL — `scripts/postgres.sh` starts one locally and `experiment.sh init-db` creates the schema in it —
which means `forecast-web` depends on `forecast-tools` for the seeding path, and the
`forecast-web.database` and `forecast-web.seed.*` properties give way to `spring.datasource.*`. Everything
else in this document stands: the acting user, the role rules, the run registry, the narration executor, the
routes of section 3.3, the pages of section 4 and the testing of section 5 are all independent of the engine,
apart from four `Dialect` call sites in `host/Directory.java` and `host/ForecastAccess.java`.

The plan's closing notes hold the ordered list of what re-integration takes.
