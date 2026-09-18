# forecast-web: the showcase host

A Spring Boot application that calls `forecast-core` the way the WorkloadHub server will in production, and
serves a React front end that shows every feature of the forecast with the route behind it. Design:
`docs/superpowers/specs/2026-09-18-forecast-web-on-dev-design.md`.

```bash
bash scripts/devbox.sh shell                 # the toolchain: Java 21, Maven, Node 22
bash server/forecast-web/run.sh              # builds the front end and the jar when stale, then http://localhost:8080
```

**It has no authentication.** Whoever reaches the port picks the user to act as, and `GET /api/system` names
the seeded admin so the front end can start somewhere; the role rules are then enforced for that user, which
is the point of the permissions pages, but nothing decides which user a caller may be — that is the session
the server brings. The tokens its settings page stores are real GitHub credentials, so keep it on a machine
you trust (`--server.address=127.0.0.1` binds it to the loopback interface; a container needs `0.0.0.0` to be
reachable from its host, which is why that is the default).

**This application creates and seeds nothing.** It connects to the local PostgreSQL of `scripts/postgres.sh`
through Spring Boot's own `spring.datasource.*`, exactly as the WorkloadHub server will, and expects the work
tables to already be filled. Prepare it once, from the root of the repository inside the development
container:

```bash
bash scripts/postgres.sh up
bash server/tools/experiment.sh init-db
bash server/tools/experiment.sh seed --synthetic --users 120 --weeks 52 --seed 7 --out /tmp/seed.json
bash server/tools/experiment.sh import /tmp/seed.json
```

`--end` is not given above, so the seed's history ends on the day it is generated: there is no fixed demo
date, and the application's own clock follows the system date until the Demo clock page's admin pins it (see
"Properties" below). `server/README.md`, "Running experiments" covers the driver's other verbs, including
seeding from a real export. Run `bash server/forecast-web/run.sh` again once the database is loaded; without
it the script refuses to start, with the four commands above printed to stderr.

To reseed — a fresh population, or the same one after `--end` has moved on — drop the schema and repeat the
last three commands:

```bash
bash scripts/postgres.sh psql -c 'DROP SCHEMA task_service CASCADE'
bash server/tools/experiment.sh init-db
bash server/tools/experiment.sh seed --synthetic --users 120 --weeks 52 --seed 7 --out /tmp/seed.json
bash server/tools/experiment.sh import /tmp/seed.json
```

## What is what

| package | what | production |
|---|---|---|
| `host` | `ActingUser` (the `X-Acting-User` header, resolved against `users`), `ForecastAccess` (the role rules with reasons), `HostForecastFacade` (role check, one run at a time, narration executor, tokens), `ApiExceptionHandler` | **copies it**, replacing the header with its session |
| `api` | the controllers under `/api` and the response views (names joined, JSON strings parsed) | copies the routes it wants |
| `demo` | the demo clock, the token key file, the served front end | deletes it |
| `ui` | Vite + React + TypeScript, no component library, SVG charts | picks what to build in its own pages |

The module's own optional controller (`whf.web.enabled`) stays off: it trusts `requestedBy` as given, and
deciding it is the host's job. The application's package is `com.workloadhub.forecastweb`, deliberately
outside `com.workloadhub.forecast`, so component scanning never picks that controller up.

## The pages

| page | what it shows | routes |
|---|---|---|
| Acting as (top bar) | pick any user; the role badge; whether a token is stored | `GET /api/directory/users`, `GET /api/me` |
| Teams | every team with the acting user's `canRun` / `canView` and the reasons | `GET /api/directory/teams`, `GET /api/me` |
| Team | the current forecast as a members × days grid, the run list, the start button (disabled with its reason), the progress label while a run computes | `GET /api/teams/{id}/forecast`, `.../forecast-runs`, `POST .../forecast-runs`, `GET /api/forecast-runs/{id}/progress` |
| Run | the windows per member as bars with their band and capacity, the day rows, the backtest, the pressure lists, the facts tree, the narration panel | `GET /api/forecast-runs/{id}`, `POST .../narratives`, `GET .../narratives/{lang}` |
| Accuracy | forecast against logged per day, the scores by team, member and lead, the rows | `GET /api/teams/{id}/accuracy` |
| Copilot & token | store or clear the acting user's token; the seat check | `GET/PUT/DELETE /api/me/github-token`, `GET /api/me/copilot` |
| Permissions | the role matrix; any user on any team with the reasons; act as that user | `GET /api/permissions` |
| Demo clock | today; move it as an admin; the recipe that fills the accuracy page | `GET /api/system`, `POST /api/system/clock` |
| Integration guide & API | module versus host, the flows of a run and a narration, the properties, the errors, every route with a "try it", the hard rules | generated from `ui/src/lib/endpoints.ts` |

Every page ends with "Behind this page": the calls it made, their status and time, and the response on demand.

## Properties

`whf.*` are the module's (`server/README.md`, "Properties"); `application.yml` sets `whf.web.enabled=false`
and reads `whf.token-key` from `WHF_TOKEN_KEY`, blank by default, in which case `forecast-web.token-key-file`
supplies one. The demo wiring, from `ForecastWebProperties`:

| property | default | what it does |
|---|---|---|
| `forecast-web.clock.today` | blank (`FORECAST_WEB_TODAY`) | The demo clock's pinned date; blank means it follows the system date. Set from the Demo clock page as an `ADMIN`, or here at start-up. |
| `forecast-web.clock.adjustable` | `true` | Whether `POST /api/system/clock` is accepted (an `ADMIN` only). |
| `forecast-web.token-key-file` | `~/.workloadhub-forecast/forecast-web/token.key` | Where a key is generated when `WHF_TOKEN_KEY` is blank. |
| `forecast-web.ui-dir` | `ui/dist` (`FORECAST_WEB_UI`) | The built front end, served at `/` when present. |

`spring.datasource.url` / `.username` / `.password` (`FORECAST_WEB_DB_URL`, `FORECAST_WEB_DB_USER`,
`FORECAST_WEB_DB_PASSWORD`) point at the database — `jdbc:postgresql://localhost:5432/workloadhub` by
default, the same local PostgreSQL `server/tools/experiment.sh` writes to. There is no database-file property
and no built-in seeding: fill the database first, as "Setup" above describes.

## Checks

- The gate (`bash scripts/check.sh`) runs the module's tests with `mvn verify` (the role rules with their
  reasons, the facade's one-at-a-time rule, the demo pieces, and one ordered integration test over the whole
  REST surface on the seeded database with the scripted Copilot gateway) and the front end's `npm run check`
  (`tsc`, vitest on the pure functions, `vite build`).
- A manual end-to-end check drives the built pages in a headless browser: `ui/e2e/smoke.mjs` (its header
  says how to run it). No automated test talks to Copilot; the live narration is checked by hand with a real
  token on the Copilot & token page.

## Front-end development

```bash
cd server/forecast-web/ui
npm ci
npm run dev        # Vite on http://localhost:5173, /api proxied to :8080 (FORECAST_WEB_API overrides)
npm run check      # tsc, vitest, vite build
```
