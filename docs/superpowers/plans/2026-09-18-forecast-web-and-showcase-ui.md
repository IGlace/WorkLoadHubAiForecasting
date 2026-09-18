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
