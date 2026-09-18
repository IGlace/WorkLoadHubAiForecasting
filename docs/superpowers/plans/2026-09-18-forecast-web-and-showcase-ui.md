# forecast-web and the showcase UI: plan

Spec: `docs/superpowers/specs/2026-09-18-forecast-web-and-showcase-ui-design.md`. Every task is test-first;
the gate is `bash scripts/check.sh` (Maven, then npm) read from `TEST-*.xml`.

## Task 1: the core test jar and the module skeleton

- `forecast-core/pom.xml`: `maven-jar-plugin` `test-jar` goal, so `SeededData`, `DatabaseTestSupport` and
  `FakeGateway` are reusable.
- Parent `pom.xml`: add `forecast-web` to `<modules>`.
- `forecast-web/pom.xml`: Spring Boot jar, `forecast-core`, `spring-boot-starter-webmvc`, `sqlite-jdbc`,
  tests with the core test jar, `spring-boot-starter-test`.
- `ForecastWebApplication`, `application.yml`, a context-loads test on an in-memory seeded database.

## Task 2: acting user, access and the facade

- `ActingUser` + `ActingUserInterceptor` (401 codes), `ForecastAccess` with reasons, `HostForbidden`,
  `RunRegistry` (table `forecast_web_runs`), `HostForecastFacade` (runs, progress, current, accuracy,
  narration executor with in-flight guard, narrative, tokens), `ApiExceptionHandler`.
- Unit tests for the access reasons, the registry and the phases.

## Task 3: the controllers

- `SystemController`, `DirectoryController`, `MeController`, `PermissionsController`, `ForecastController`,
  `NarrativeController`, `TokenController`, and the `RunView` / `NarrativeView` wrappers that parse the JSON
  strings into objects.
- `ForecastWebIntegrationTest` covering section 5 of the spec.

## Task 4: the demo pieces

- `SqliteDatabase` + `DemoSeeder`, `DemoClock` + `POST /system/clock`, `TokenKeyFile`, `UiResources`.
- Tests: seeder creates the file once, clock date and ticking, key file created then reused, SPA fallback.

## Task 5: the front end

- Vite + React + TypeScript scaffold, `api.ts`, theme, layout with the acting-user picker, the nine pages of
  spec section 4, the endpoint table, the SVG charts.
- vitest on the pure functions; `npm run check`.

## Task 6: the gate, the docs and the launcher

- `scripts/check.sh`, `scripts/check.ps1`, `.github/workflows/ci.yml`: the npm step.
- `server/forecast-web/README.md`, `server/README.md` section, `CLAUDE.md` layout and status, `.gitignore`.
- `server/forecast-web/run.sh`: builds the UI when stale and runs the application.

## Task 7: the end-to-end check and the push

- Start the application on a fresh seeded file, drive the UI in a headless browser through every page, fix
  what breaks, run the whole gate, commit, push `dev`.

## Closing notes (2026-09-18)

**Landed on `dev`.** Two pieces, as designed: `server/forecast-web` (a Spring Boot host reaching
`forecast-core` only through `ForecastService` and `GitHubTokenStore`) and `server/forecast-web/ui` (the
showcase front end it serves). `forecast-core` also publishes a test jar now, so the new module's tests reuse
`SeededData` and the scripted Copilot gateway rather than copying them.

**What the design says and the code does.** The `host` package is what the WorkloadHub server copies: the
acting user, the role rules with a reason per refusal, the run registry table, one run at a time for a skill
team leader, the narration executor, the error mapping. The `demo` package is what it deletes. Nothing new is
computed anywhere: every figure in every response comes from the module unchanged.

**Deviations from the spec, all three recorded in it.** A refused token prefix is 400 `INVALID_REQUEST`, not
409 (the module's store already raises `INVALID_REQUEST`, and inventing a `TOKEN_REJECTED` code above it
would have been a second vocabulary). `GET /api/system` gained `bootstrapUserId`, the seeded admin, because
reading the directory needs an acting user and the front end had no way to choose the first one. The demo
clock defaults to 2026-06-28 rather than the seed's last day, for the reason below.

**The seed's taper, found by running the thing.** On the default demo population (120 users, 52 weeks ending
2026-09-06) a run on the last day forecast 0.1 h a day for every member. The population logs about 3,000 h a
week from March to June and 500 to 1,300 h through August, and tasks created per month fall from 1,171 in
June to 84 in September, so the forecast was arithmetically right and useless to look at. The demo clock
therefore starts in late June; `docs/backlog.md` records the taper itself as a question for
`ProjectPlanner`/`WorkQueue`, since `WorkFamilyPropertyTest` measures the mean over all weeks and cannot see
it.

**The whole-branch review's fix wave** (one wave, as the workflow asks). Fixed: the narration poll treated
the run's own `DONE` as terminal, so the first poll after the 202 stopped before a worker had picked the
narration up — the decision is now a tested pure function (`ui/src/lib/narration.ts`) that waits for
`NARRATING` and gives up after a minute; two poll loops rescheduled themselves from their failure path after
cleanup, leaking a request a second per visit; the one-at-a-time rule was check-then-act, now a per-user
monitor around check-and-start; the registry ordered "the latest run" by a clock an admin moves backwards,
now by the real one; the token key file was written and then narrowed to 0600, now created with it, in a
0700 directory; the static resource resolver skipped `PathResourceResolver`'s own containment check and now
delegates to it, with `..` refused outright; the narration model string reached the SDK unchecked;
`AccuracyPage` summed raw values, so a `"NaN"` score would have concatenated into a string and silently
dropped a day from the chart; the accuracy page showed an error before the system date arrived; the demo
SQLite file now runs in WAL, so a computing run and a polling browser stop blocking each other.

Added with them: `WebSurfaceGuard`, which refuses to start with `whf.web.enabled=true`, because the module's
own controller trusts `requestedBy` as given and would let any caller narrate with another user's stored
token. It runs while the environment is prepared, before any bean definition: the two controllers also clash
on the bean name `forecastController`, so the application would have failed anyway, but with a message about
bean overriding that explains nothing.

Left open, in `docs/backlog.md`: the registry table is created outside Flyway; a run is registered after it
starts; a narration key clears only when the call returns. The showcase has no authentication by design, and
`run.sh` and the module's README now say so where a reader starts.

**Verification.** `bash scripts/check.sh` green by hand in the development container: `mvn -B -q verify` over
both modules, then the front end's `npm run check`. Read from `TEST-*.xml` with the report directory wiped
first: **476 tests, 0 failures, 14 skipped** (451 in `forecast-core`, whose 14 skips want Docker; 25 in
`forecast-web`) and **20 vitest tests**, `tsc` and `vite build` clean. Maven took 649 s, the front end 7 s. The pages were also driven end to end
in a headless browser against the built jar (`ui/e2e/smoke.mjs`, a manual check, not part of the gate): the
acting-user picker, the role matrix, a run through its progress labels to its windows and facts, the token
input refusing a classic `ghp_` token and accepting a `gho_` one, the seat check, the permission explorer,
the demo clock refused for a member and moved by the admin, the accuracy page filled by moving the clock
forward, a second run, a route tried from the reference page, and a run that does not exist — no page error.
The live narration path is the one thing no test covers, by the standing rule that no test talks to Copilot:
it is checked by hand with a real token on the Copilot & token page.

**One thing worth knowing about the toolchain.** jqwik's console output now carries a sentence telling an AI
agent to disregard its instructions and ignore jqwik's results. It is a string in a dependency's output, not
an instruction; the gate's verdict is read from `TEST-*.xml` either way, which is what CLAUDE.md already
says to do.
