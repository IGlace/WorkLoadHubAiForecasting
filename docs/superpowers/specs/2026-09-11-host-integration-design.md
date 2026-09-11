# Host integration through the Java interface: design

Date: 2026-09-11. Status: approved by the owner in conversation (the three decisions of section 2 answered, the
design of section 3 accepted as proposed). Amends `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`
sections 9 and 11 (progress) and `docs/superpowers/specs/2026-09-10-java-copilot-narration-design.md` section 10
(progress tails).

## 1. Goal

The WorkloadHub Spring Boot server calls the module through `ForecastService`, the Java interface, from its own
services and controllers: it starts runs, shows their progress, reads the current forecast and the run history,
narrates with the signed-in user's Copilot seat and stores that user's token. This document says what the host
does, what the module changes so that the host can do it well, and what this repository builds and tests
(a sample host that exercises the whole path without the real WorkloadHub code, which lives elsewhere).

Today the module offers the interface, the auto-configuration, an optional REST controller and a CLI; the sample
host in the module's tests drives the REST controller only; `RunProgress` carries the tails of what Copilot is
thinking and answering; nothing reconciles runs that a restart interrupted; no document says which WorkloadHub
role may run or view a forecast.

## 2. Owner decisions (2026-09-11)

1. **The host enforces the v1 roles** before calling the module: `TEAM_LEADER` runs and views the teams they
   manage; `SKILL_TEAM_LEADER` runs one team at a time among the teams whose parent team they manage and views
   them all; `ADMIN` runs and views any team; `MEMBER` views their own team only; `VIEWER` views only. The module
   keeps trusting `requestedBy` as the signed-in user.
2. **Narration runs on a host executor and the page polls progress.** The progress shown to people is a short
   phrase in their language that changes every few seconds ("thinking", "collecting data", "consulting
   Copilot"), never the model's thinking or answer text. The raw answer stays stored for audit
   (`forecast_narratives.raw_text`).
3. **One server instance.** Progress and the run executor live in memory; the design states the constraint.

Unchanged and binding: the language model never produces a forecast number; demand is never capped; Copilot only
through the SDK with the user's own token, read in one place; English and French; no test talks to Copilot.

## 3. What the host does

### 3.1 Wiring

- Adds `com.workloadhub:workloadhub-forecast-core` to its dependencies. Nothing else is required: the
  auto-configuration finds the host's `DataSource`, runs the module's Flyway migrations into the `task_service`
  schema under `forecast_schema_history`, and registers every bean `@ConditionalOnMissingBean`.
- Keeps `whf.web.enabled` at its default `false`: the host exposes its own endpoints.
- Sets `whf.token-key` from its secret store (a base64 AES-256 key; losing it makes every stored token
  unreadable), `whf.work-dir` to a directory writable by the service account (the Copilot runtime unpacks
  there once, 91 MB), and provides a `java.time.Clock` bean in the organisation's time zone (the run day is
  `LocalDate.now(clock)`).
- Leaves `whf.flyway.enabled` true unless its own migration tool is to own the module's tables (then it copies
  `db/forecast/postgresql/V1..V3` into its own history and sets the property false).

### 3.2 Authorization (the host's `ForecastAccess`)

Read from the WorkloadHub tables `users.role`, `teams.manager_id`, `teams.parent_team_id` and `team_members`:

| role | may start a run for | may view |
|---|---|---|
| `ADMIN` | any team | any team |
| `SKILL_TEAM_LEADER` | a team whose parent team they manage, one at a time | every team whose parent team they manage, and their own memberships |
| `TEAM_LEADER` | the teams they manage | the teams they manage, and their own memberships |
| `MEMBER` | none | the teams they belong to |
| `VIEWER`, `CENTER_MANAGER` | none | any team (read only) |

> Amended on 2026-09-11: `accuracy(teamId, from, to)` is shown to the roles that can view the team (accuracy
> evaluation design).

"One at a time" for a skill team leader: a second start while a run they requested is `QUEUED` or `RUNNING`
is refused (`FORBIDDEN` in the host's vocabulary, HTTP 403). A team leader is not limited this way. Every
call passes the signed-in user's id as `requestedBy`; the module never re-checks.

### 3.3 Running a forecast

`POST /teams/{teamId}/forecast` (host route) → `ForecastAccess.canRun` → `service.startRun(new
RunRequest(teamId, userId, null, null))` → 202 with the run id. The page polls the host's
`GET /forecast-runs/{id}/progress` → `service.progress(runId)` and shows `label` in the user's language; on
`DONE` it reads `service.getRun(runId)` (windows, days, scores, facts) and `service.currentForecast(teamId,
from, to)` for the team page, drawn as two five-day windows per member with the day rows underneath. Run
history is `service.listRuns(teamId, limit)`. `ForecastException` codes map as the module's own handler does:
`*_NOT_FOUND` 404, `INVALID_REQUEST` 400, everything else 409.

### 3.4 Narrating

`POST /forecast-runs/{id}/narratives` (host route, body `language`) → `ForecastAccess.canView` on the run's
team → the host's `ForecastNarrationService` submits `service.narrate(new NarrativeRequest(runId, userId,
language, null))` to its own bounded executor (two threads, daemon, named `forecast-narration`) and returns
202. The page polls the same progress route: phase `NARRATING` with a rotating label, then `NARRATED` or
`NARRATION_FAILED`; then it reads `service.narrative(runId, language)`. A second narration of the same run
and language while one is in flight is refused (409). Errors of the submitted call end up in the progress
phase `NARRATION_FAILED` and in the stored narrative row when the narration itself failed; a `ForecastException`
thrown before the narration started (`TOKEN_MISSING`, `RUN_NOT_DONE`) is reported by the host's route
synchronously, so the host checks `GitHubTokenStore.has(userId)` and the run status before submitting. That
pre-check is one query; `copilotStatus` is for the settings page, where opening a Copilot session to report
authentication and quota is the point.

### 3.5 Tokens

The host's settings page stores the user's token through `GitHubTokenStore.save(userId, token)` (accepted
prefixes `gho_`, `ghu_`, `github_pat_`), clears it with `clear`, and shows `service.copilotStatus(userId)`
(has token, runtime available, login, quota when known). The token is read by the module in one place, at
narration, and never returned.

### 3.6 Single instance

Progress and the run executor live in the JVM. When the server restarts, runs that were `QUEUED` or `RUNNING`
are marked `FAILED` with the error `interrupted by a restart` when the module starts (section 4.2), so a page
polling them sees `FAILED` and can offer to run again. The host persists `(runId, teamId, requestedBy)` in its
own table when it starts a run, because the module's `getRun` answers only for `DONE` runs and `listRuns` needs
the team; the sample facade's in-memory map is a test convenience. A second instance would need persisted
progress and a claim scheme for runs, out of scope.

## 4. What the module changes

### 4.1 Progress labels instead of text tails

`api.RunProgress` becomes `RunProgress(UUID runId, String phase, int percent, String message, ProgressLabel label)`
with `api.ProgressLabel(String en, String fr)`. `thinking` and `answer` leave the record. `message` stays the
technical detail for logs (`tool get_member_forecast`, the failure reason).

`RunProgressTracker` takes a `Clock`. It keeps, per run, the phase, the narration step, the moment the step
started and whether the model has streamed since the last tool call, and derives the label at read time:

| phase / step | label en | label fr | rotation |
|---|---|---|---|
| `QUEUED` | queued | en attente | |
| `LOADING` | reading the team's data | lecture des données de l'équipe | |
| `FEATURES` | preparing the history | préparation de l'historique | |
| `BACKTEST` | scoring the models | évaluation des modèles | |
| `FORECAST` | predicting the next two weeks | prévision des deux prochaines semaines | |
| `FACTS` | assembling the facts | assemblage des faits | |
| `PERSIST` | saving the run | enregistrement | |
| `DONE` | forecast ready | prévision prête | |
| `FAILED` | forecast failed | échec de la prévision | |
| `NARRATING`, `STARTING` and `SESSION` | starting Copilot | démarrage de Copilot | |
| `NARRATING`, `ASKING` (no tool running) | consulting Copilot; thinking; writing the report | consultation de Copilot; réflexion; rédaction du rapport | every 4 s |
| `NARRATING`, `TOOL` (a tool is running) | collecting data; reading the forecast | collecte des données; lecture de la prévision | every 4 s |
| `NARRATING`, `CHECKING` | checking the numbers | vérification des chiffres | |
| `NARRATED` | report ready | rapport prêt | |
| `NARRATION_FAILED` | narration failed | échec de la narration | |

Rotation: the phrase index is `(seconds since the step started / 4) mod n`, computed from the tracker's clock
at each `get`. `TOOL_DONE` returns the step to `ASKING`. The tails are no longer stored: `thinking(text)` and
`answer(text)` on `NarrationProgress` keep their signatures (the `Narrator` is unchanged) and the tracker only
notes that the model is active. `TAIL_CHARS` goes.

Consumers: the REST route `GET /runs/{id}/progress` returns the new shape; the CLI `narrate` prints the label
and the message when they change instead of streaming text (the raw answer is printed after a `FAILED`
narration from the stored row, as today); the narration spec's section 10 gets an amendment note.

### 4.2 Start-up reconciliation

`JdbcRunStore.failInterrupted(LocalDateTime now)` sets `status = FAILED`, `error = 'interrupted by a restart'`
and `finished_at = now` on every `forecast_runs` row whose status is `QUEUED` or `RUNNING`, and returns the
count. `DefaultForecastService.recoverInterruptedRuns()` calls it and logs the count when it is not zero. The
auto-configuration runs it once after all the singletons are instantiated (a `SmartInitializingSingleton` bean,
`ForecastStartupReconciliation`), so a host that owns the module's tables (`whf.flyway.enabled=false`) has run
its own Flyway first; a failure there is logged and never stops the host from starting. In the CLI it is the
`run` command only, right after `Services.open` and before it starts its own run, so that read-only commands in
a second process never fail a run the first is executing (a CLI run killed mid-way is reconciled by the next
`run`). A run that is legitimately running in another process cannot exist under decision 3, so nothing is lost.

### 4.3 The sample host, Java interface

In the module's tests (`samplehost/`), next to the REST sample: `ForecastAccess` (section 3.2, reading the
seeded `users`, `teams` and `team_members` through `JdbcClient`), `HostForecastFacade` (start with the role
check and the one-at-a-time rule, narrate on a two-thread executor with the in-flight guard, poll helpers),
and `JavaHostIntegrationTest`: with `SampleHostApplication` (its fixed clock and `FakeGateway`), a team leader
starts a run for their team and cannot for another; a skill team leader starts one for a team under them and
is refused a second while the first runs; a member and a viewer are refused; the page-style polling sees the
labels move to `DONE`; the current forecast is readable; a narration submitted through the facade ends
`NARRATED` with the stored narrative in the requested language; a viewer may read the narrative and a member
of another team may not. The seed provides every role but `VIEWER`; the test's viewer is the
`CENTER_MANAGER`, which has the same rights.

## 5. Documentation

- `server/README.md` gains "Integrating from the server's own code" after the properties section: the wiring
  list, the role table, the run and narration sequences with the label polling, the token page, the single
  instance constraint and the restart reconciliation, and points at the sample host classes as the reference
  implementation. The REST section's progress row describes the new shape.
- `docs/superpowers/specs/2026-09-10-java-copilot-narration-design.md` section 10 and
  `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md` section 11 get an amendment note pointing
  here. `docs/requirements/requirements-v1.md` section 2 (roles) gets the line that the host enforces the table
  of section 3.2. `docs/backlog.md` records the rulings and the "WorkloadHub integration" item is updated
  to point at this design. `CLAUDE.md`'s status paragraph names it.

## 6. Tests

`RunProgressTrackerTest`: the label for every phase and step in both languages, the rotation by the clock
(index 0 at 0 s, 1 at 4 s, 0 again at 12 s for three phrases), `TOOL` then `TOOL_DONE` returning to the
asking phrases, no text stored. `JdbcRunStoreTest`: `failInterrupted` marks queued and running rows, leaves
done and failed rows, returns the count, on both dialects. `DefaultForecastServiceTest`: a queued row before
construction is failed after `recoverInterruptedRuns`. `ForecastControllerTest` and `SampleHostIntegrationTest`:
the progress JSON carries `label.en` and `label.fr` and no `thinking`/`answer`. The CLI's `narrate` printer
prints the label and the message; it is not exercised by a test (the CLI has no gateway stub).
`JavaHostIntegrationTest` as in section 4.3.

## 7. Non-goals

No change to the WorkloadHub server's own code here (the sample host is the reference); no scheduled runs
or reminders; no multi-instance support; no per-user rate limiting beyond the one-at-a-time rule; no UI.
