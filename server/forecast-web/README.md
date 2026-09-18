# forecast-web: the showcase host

A Spring Boot application that calls `forecast-core` the way the WorkloadHub server will in production, and
serves a React front end that shows every feature of the forecast with the route behind it. Design:
`docs/superpowers/specs/2026-09-18-forecast-web-and-showcase-ui-design.md`.

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

The first start creates and seeds a synthetic SQLite database (120 users, 52 weeks ending 2026-09-06, no
personal data) under `~/.workloadhub-forecast/forecast-web/`, generates a token key file next to it, and
pins the demo clock to **2026-06-28**. Delete the database file to seed again.

## What is what

| package | what | production |
|---|---|---|
| `host` | `ActingUser` (the `X-Acting-User` header, resolved against `users`), `ForecastAccess` (the role rules with reasons), `RunRegistry` (`forecast_web_runs`), `HostForecastFacade` (role check, one run at a time, narration executor, tokens), `ApiExceptionHandler` | **copies it**, replacing the header with its session |
| `api` | the controllers under `/api` and the response views (names joined, JSON strings parsed) | copies the routes it wants |
| `demo` | the seeded SQLite file, the demo clock, the token key file, the served front end | deletes it |
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
and reads `whf.token-key` from `WHF_TOKEN_KEY`. The demo wiring:

| property | default | what it does |
|---|---|---|
| `forecast-web.database` | `~/.workloadhub-forecast/forecast-web/workloadhub.db` (`FORECAST_WEB_DB`) | The SQLite file, seeded when missing; blank means the application's own `spring.datasource.*` (PostgreSQL), and no seeding |
| `forecast-web.seed.users` / `weeks` / `seed` / `end` | 120 / 52 / 7 / 2026-09-06 | The synthetic population written when the file is created |
| `forecast-web.clock.today` | `2026-06-28` (`FORECAST_WEB_TODAY`) | The demo clock's date; blank follows the system date |
| `forecast-web.clock.adjustable` | `true` | Whether `POST /api/system/clock` is accepted (an `ADMIN` only) |
| `forecast-web.token-key-file` | `~/.workloadhub-forecast/forecast-web/token.key` | Where a key is generated when `WHF_TOKEN_KEY` is blank |
| `forecast-web.ui-dir` | `ui/dist` (`FORECAST_WEB_UI`) | The built front end, served at `/` when present |

Why June and not the seed's last day: the synthetic history's logged hours peak from March to June and
taper over its last eight weeks (fewer tasks are created near the end), so a run on 2026-09-06 forecasts
near zero for everyone, while a run on 2026-06-28 has two months of real logs after it, which the accuracy
page can score once the clock is moved forward (`docs/backlog.md`, "Java migration").

## Checks

- The gate (`bash scripts/check.sh`) runs the module's tests with `mvn verify` (21 tests: the role rules
  with their reasons, the registry, the facade's one-at-a-time rule, the demo pieces, and one ordered
  integration test over the whole REST surface on the seeded in-memory database with the scripted Copilot
  gateway) and the front end's `npm run check` (`tsc`, 16 vitest tests on the pure functions, `vite build`).
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
