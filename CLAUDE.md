# WorkloadHub AI Forecasting

A Java 21 module for the WorkloadHub Spring Boot server (Linux; development on Windows, in the container
`scripts/devbox.sh` keeps running) that
forecasts each team member's logged work hours for a rolling horizon (one to six windows of five weekdays,
two by default) from the team's task history in the
application's own PostgreSQL database, compares them with capacity (44 h/week default over the working days,
minus public holidays and approved personal leaves), and uses each user's own GitHub Copilot seat to explain
patterns, warn about overload and suggest rebalancing. The host calls a Java interface or an optional REST
surface; a second Maven module, `forecast-tools`, holds the seed and the experiment driver, run by hand
against a local PostgreSQL; a third, `forecast-web`, is a showcase Spring Boot application with a React front
end that calls the module the same way a real host will, against the same local PostgreSQL. The first version
(a Windows desktop app with a Python service) is archived on the remote branch `archive/python-desktop-v1`.

## Read these first

- `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`: the design of the module (database,
  seed generator, feature matrix, models, backtest, run pipeline, public API, CLI, testing). Its section 12
  describes the CLI as first designed; on 2026-09-12 it was trimmed to five commands and then removed
  altogether, so read that section as history and `server/README.md` for what exists.
- `docs/superpowers/specs/2026-09-10-java-copilot-narration-design.md`: Copilot narration from Java (the SDK
  as found, tools, contract, verification, persistence, REST, CLI) and its recorded deviations.
- `docs/design/2026-09-08-workloadhub-schema-and-feature-matrix.md`: the real WorkloadHub schema mapped to the
  forecast. The export it was written from holds credentials and personal data and is never committed.
- `docs/requirements/requirements-v1.md` and `docs/requirements/2026-09-03-discovery-qa.md`: scope, roles and
  the owner's answers, still the source of truth for scope questions.
- `docs/superpowers/specs/2026-09-13-weekly-hours-forecast-design.md`: **implemented, landed on 2026-09-14.**
  It retargets the forecast at logged hours, deleted `EffortModel`, `PlannedWork`, `SeasonalNaive` and the
  champion machinery, made the window count configurable, corrected the capacity default to 44 h, added the
  pressure facts a forecast cannot show, and changed the seed generator, which used to be unable to produce
  overtime at all. Its section 18 holds the twelve rulings of the owner's review and is the first thing to
  read; each one cites the section it changes. Section 23 gave the task order. Read the document before
  changing anything in `model/`, `backtest/`, `features/` or the facts contract; it supersedes
  `2026-09-12-single-model-simplification-design.md`, which is history.
- `docs/superpowers/specs/2026-09-14-evaluation-removal-design.md`: **implemented, landed on 2026-09-14.**
  Records the removal of the offline evaluation harness — `ForecastService.evaluate(EvalConfig)`, `Harness`,
  `Report` and the driver's `eval` command — once the features were settled and the model locked, leaving
  `accuracy(teamId, from, to)` as the module's only evaluation surface. The per-run backtest inside
  `ForecastRunner.prepare` stayed: it still produces every run's prediction intervals and `mae`.
- `docs/superpowers/specs/2026-09-21-hierarchy-teams-design.md`: **implemented, landed on `dev` on
  2026-09-21.** Read it before touching anything that says "team" or "role". WorkloadHub's `teams` and
  `team_members` are project teams and are never read; a team is a team leader and their direct reports, keyed
  by the leader's own user id, from `users.manager_id`. Its section 2 holds the ten rulings and is the first
  thing to read; section 3 explains why `team_id` keeps its name and why there is no migration. **Read section
  16 with section 2**: the second revision of the same day, which took the role out of `users.role` — the
  owner's directory leaves it at MEMBER for 260 of 264 people — and put it in `users.job_title`, through
  `EffectiveRole`. It amends rulings 1, 2, 3 and 9. The document withdraws sections 5 and 6 of
  `2026-09-19-real-export-preparation-design.md`, which are history.
- `docs/superpowers/specs/2026-09-17-personal-leaves-capacity-and-seed-scope-design.md`: **implemented,
  landing on dev on 2026-09-17.** `personal_leaves` replaces `absences`, capacity is the calendar and the
  approved leaves over the 44 h default, `user_capacity` and `team_capacity` are not read, the seed writes
  five tables in real mode, and the owner's feature-matrix rulings of 2026-09-16 (section 1 of the spec,
  `docs/design/2026-09-16-feature-matrix-review.html`) are applied. Two pages of 2026-09-17 describe the
  result for the application's developer, current state only: `docs/design/2026-09-17-workloadhub-schema-diagram.html`
  (every table of the database with the module installed, what the module adds to the schema and to the
  running application, what it reads and what the data must contain, and the full integration guide: the host
  layer, the calls, the results, the routes, the errors, running and testing it, modelled on `forecast-web`)
  and `docs/design/2026-09-17-feature-matrix-now.html` (who is forecast and what a team is, the features, the
  logic and the rules; the 2026-09-16 page is kept as the reviewed record). Both were brought up to the
  hierarchy model of 2026-09-21 on 2026-09-22 and keep their file names.
- `docs/superpowers/specs/2026-09-17-postgresql-only-and-tools-module-design.md`: **implemented, landed on
  dev on 2026-09-18.** PostgreSQL is the only database, one final `V1` migration, the seed and the driver in
  `forecast-tools`, core tests on a committed seeded fixture, a local PostgreSQL in `scripts/postgres.sh`.
- `docs/superpowers/plans/`: the reviewed plans, each with closing notes and rulings; `docs/backlog.md`: open
  items and the rulings under "Java migration".
- `server/README.md`: build, running experiments, the seed, using the module from the server, narrating with
  Copilot.
- Documents dated before 2026-09-09 describe the archived version; each carries a note saying so.

## Where the project stands (2026-09-19)

Plans 1 to 4 landed on `dev` and `main` (foundation and seed; pipeline core; run, eval and parity; Copilot
narration), then the archival plan (`docs/superpowers/plans/2026-09-10-python-desktop-archival.md`). Then the
rolling forecast windows (`docs/superpowers/plans/2026-09-10-rolling-forecast-windows.md`): a run starts the
first weekday after the run day, covers a rolling horizon of five-weekday windows, is computed per day and
keeps a per-day current forecast. Then the host integration design
(`docs/superpowers/specs/2026-09-11-host-integration-design.md`): progress labels, start-up reconciliation and
a Java-interface sample host; the server's own code is written in the WorkloadHub repository. Then the
accuracy evaluation (`docs/superpowers/specs/2026-09-11-accuracy-evaluation-design.md`): `accuracy(teamId,
from, to)` compares the forecasts made before each past weekday with the logged hours. Then, on 2026-09-12,
the CLI trim: `forecast-cli` kept only the five commands that build and score an experiment database, and
everything a host does is shown by `server/examples/HostExample.java` instead. The same day the module went
too: those five commands were 408 lines wrapping public `forecast-core` classes, so they are now one
single-file Java program, `server/tools/Experiment.java`, run by `server/tools/experiment.sh` the way the
sample host is run; and evaluation became a module feature, `ForecastService.evaluate(EvalConfig)`, which is
the path the driver's `eval` takes (removed on 2026-09-14; see below).
Then, on 2026-09-13, the weekly hours forecast design
(`docs/superpowers/specs/2026-09-13-weekly-hours-forecast-design.md`): the booster trains on fresh estimated
arrival hours while `accuracy()` scores logged hours, so it is retargeted at logged hours per member-week and
everything that existed to bridge the two — `EffortModel`, `PlannedWork`, `HourPlacement`, the open/new/planned
split — goes, along with `SeasonalNaive` and the champion machinery of the superseded single-model design; the
window count becomes `whf.forecast.windows` (default 2, one to six) and the capacity default is corrected to
44 h. The owner reviewed it on 2026-09-14 (its section 18): the two points it left open were settled, a
contradiction was found and fixed — the per-horizon feature columns must be sized from the window count, so
`Features.HORIZONS` stops being a constant — and its worst-stated cost was overturned, because WorkloadHub
puts no cap on logging, so overtime is recorded and overload survives the retarget. That review also added a
second pressure fact: work due inside a window beyond what the window holds, with the members it presses, so
Copilot can state the gap in hours and advise rebalancing, plus `overdue_hrs` for work already late. The
backlog figure is measured against predicted demand rather than capacity, because the forecast is an analysis
a leader reads rather than a fact, and the narrative is there to catch what it misses. The seed generator
changed with it (section 22): a seeded member could never log past 8 hours in a day against a 40-hour capacity
row, so under the retarget overload would have been arithmetically unreachable on seeded data. It stayed one
plan (section 23), executed as eleven tasks and landed on `dev` on 2026-09-14: the model now forecasts logged
hours per member-week, `EffortModel`, `PlannedWork`, `SeasonalNaive` and the champion machinery are gone, the
window count is `whf.forecast.windows` (1 to 6, default 2), capacity defaults to 44 h, the pressure facts
(`backlog_excess_hrs`, `due_excess_hrs`, `overdue_hrs`) are in the facts contract, and the old parity procedure
(`server/tools/parity.sh`, `parity_compare.py`, `server/tools/tests/`) is retired —
`docs/eval/2026-09-10-java-parity-synthetic/` stays as a record of the run that produced it, with a note that
the procedure is gone.
Then, on 2026-09-14, the evaluation removal
(`docs/superpowers/specs/2026-09-14-evaluation-removal-design.md`): with the features settled and the model
locked, the offline harness had nothing left to measure, so `ForecastService.evaluate(EvalConfig)`,
`Harness`, `Report`, their row types, the orphaned `AccuracyReport` and the driver's `eval` command are
gone, and the driver is four verbs that build and move an experiment database. `accuracy(teamId, from, to)`
is the module's only evaluation surface; the per-run backtest still gives each window its interval and each
run its `mae`.
The last three tasks ran back to back at the owner's request, then one combined review over all three,
then one fix wave (`1a93de2`): it corrected a half-day absence that deleted a whole day from the forecast,
opened the two pressure lists to the tool Copilot actually calls, put 44 h and 8.8 h in the product skills
where a test had been pinning 40 h, broke a `run`/`facts` cycle, and renamed two jqwik files that surefire
had never been collecting. The gate stands at 409 tests, 0 failures, 13 skipped. `main` was fast-forwarded to
`dev` afterward -- `git ls-remote --heads origin` shows both at `765aa9f`. The plan's closing notes record the
rest; `docs/backlog.md` holds what was left open.
Then, on 2026-09-15, the post-retarget defect sweep
(`docs/superpowers/specs/2026-09-15-post-retarget-defect-sweep-design.md`,
`docs/superpowers/plans/2026-09-15-post-retarget-defect-sweep.md`): a live run on 2026-09-14 had reported
`overloaded members: none` and six false `UNVERIFIED` numbers, and the sweep fixed both together with three
smaller cosmetic bugs. The seed's weekly work supply (`WorkFamily.weeklyHours`) was raised to match the 44 h
capacity correction of 2026-09-13 — it had been sized for the pre-retarget world, and a stale database plus
a still-unraised supply made overload arithmetically unreachable even after the seed was rebuilt; a jqwik
property test now guards the population statistic that made this observable (mean member-week hours and the
over-capacity rate, not bare existence, since a rare event-week tail hit could already clear capacity by
chance under the old constants). `NumberVerifier` had three independent causes of false `UNVERIFIED`: a
hyphen preceded by a letter read as a false minus sign, a task key's own digits read as facts, and a fact's
one-decimal rounding missing its own two-decimal rendering — all fixed as a pure widening, proven by
negative tests. `backlog_pressed` and `deadline_pressed` now carry `{member_id, name}` plus their own
pressure figure instead of bare names, closing a standing backlog item. The horizon-hardcoded progress
label, a token-status print-order bug in the sample host, and an all-NaN accuracy row over zero scored rows
were also fixed. Four false documentation statements were corrected and two backlog entries naming
already-deleted code were closed.
The plan's own end-to-end acceptance check (a real Copilot narration, run with the owner's explicit go-ahead
since it uses a live GitHub credential) found two more true `UNVERIFIED` numbers `NumberVerifier` still
missed — a `HALF_EVEN`/`HALF_UP` rounding-tie disagreement and a member's own synthetic name's trailing
number misread as a citation — fixed as a follow-up ("task 2b") with the same rigor. A second re-run then
surfaced a third, architecturally different gap: Copilot correctly computing derived arithmetic (a
difference or sum of two real facts) that is never itself a stored fact node, so the verifier has nothing to
match it against. This is a design question, not a quick fix, and was deliberately left open rather than
patched under time pressure — recorded in `docs/backlog.md`, "Java migration".
The gate stands at 428 tests, 0 failures, 1 skipped with Docker (13 skipped without it; the skip-count drop
from 409/0/13 is Postgres tests newly running under this session's Docker-available devbox, not anything this
sweep touched).
Then, on 2026-09-17, personal leaves, capacity from the calendar and the seed's scope
(`docs/superpowers/specs/2026-09-17-personal-leaves-capacity-and-seed-scope-design.md`): the main developer
confirmed `absences` is legacy and `user_capacity`/`team_capacity` will not be used, so the module reads
`personal_leaves` instead and computes capacity itself from `whf.default-weekly-hours` (44), the working
days and the member's own APPROVED leave hours; the seed's real mode now writes only the five work tables
(`projects`, `tasks`, `task_history`, `time_logs`, `personal_leaves`), with the application's own tables read
from the export and left alone, `projects` upserted by id and the other four replaced child-first (both
retired the next day, below); and the owner walked the feature matrix on 2026-09-16 and ruled on every
invented rule: `proj_first_due_weeks` is
gone, `share_manual_13w`/`share_project_13w` are replaced by `share_assigned_13w` from the assignment's own
`task_history.user_id`, `planned_hrs_h` returns as a per-horizon column, and every ratio or "weeks since"
with nothing to measure is left blank instead of an invented sentinel, for 40 shared and 5 per-horizon
feature columns. The gate stands at 450 tests, 0 failures, 14 skipped without Docker (the plan's closing notes).
Then, on 2026-09-18, PostgreSQL only, one final migration and the tools module
(`docs/superpowers/specs/2026-09-17-postgresql-only-and-tools-module-design.md`,
`docs/superpowers/plans/2026-09-17-postgresql-only-and-tools-module.md`): SQLite is gone from the module,
the five paired migrations became one `V1` for PostgreSQL alone, and `Dialect` and the row helpers written
three times went with them; everything the host never runs — the seed, the import and export code, the
WorkloadHub schema script, the experiment driver and the sample host — moved to a second Maven module,
`forecast-tools`, which is never shipped, so the library jar fell from 438,713 B over 243 entries to
317,615 B over 196, none of them seed, schema or SQLite. Core's tests read their seeded rows from two
committed files under `forecast-core/src/test/resources/fixtures/` (`workloadhub-schema.sql`, 34,564 B, and
`seeded-rows.sql`, 3,431,937 B), written by `bash server/tools/experiment.sh fixture` and held fresh by
`FixtureFreshnessTest`; `scripts/postgres.sh` runs the local PostgreSQL 18 that the driver and the sample
host connect to, and `--db FILE` is gone from both; the gate now needs a container engine, failing without
Docker or podman where it used to skip. Two tasks were added during execution (2b and 2c) because
PostgreSQL enforces the foreign keys SQLite never did: real mode builds teams from the export's own teams
and invents none, and refuses an export missing a task status or type. That plan's gate was 438 tests (core
312, tools 126, the last one from the review's fix wave), 0 failures, 0 errors, 1 skipped, in 11m44s — the
same wall time as before the test pruning
of the last task, because 94% of the test time is seven full-pipeline classes and the caches removed only
repeated work; the speed-up the spec hoped for did not happen.
Then, the same day, the seed's landing path
(`docs/superpowers/specs/2026-09-18-seed-owns-the-work-tables-design.md`): the owner ruled that only the
local database is ever seeded, so the importer clears `project_history`, `task_comments` and
`task_attachments` with the work tables it replaces, and `seed --format sql`, its user-content guard and
the projects upsert are gone; the whole-branch review of the PostgreSQL plan had found the importer
failing on those foreign keys against any database that holds history or comments. The gate stands at
437 tests (core 312, tools 125), 0 failures, 0 errors, 1 skipped, in 11m45s.
Then, also on 2026-09-18, the forecast-web showcase
(`docs/superpowers/specs/2026-09-18-forecast-web-on-dev-design.md`,
`docs/superpowers/plans/2026-09-18-forecast-web-on-dev.md`): a third Maven module, ported from a stale
showcase branch that predated the two plans above, so its 90 files arrived verbatim and did not compile —
they named `Dialect`, `SeedConfig`, the `forecast.seed` package and the export classes, all of which had
since moved to `forecast-tools` or been deleted outright. Those internal dependencies were removed rather
than restored, because a host — this showcase included — depends on `forecast-core` alone; `forecast-web`
now adds only that (plus its test-jar, for the seeded fixture and the sample-host classes its own tests
reuse), connects to the same local PostgreSQL `server/tools/experiment.sh` fills, and creates and seeds
nothing itself. The `RunRegistry`/`DemoDatabase` pair that used to seed and pin its own SQLite file is gone:
`ShowcaseConfiguration` replaces the deleted `DemoConfiguration`, and `HostForecastFacade` finds a run
through `ForecastService.findRun`/`latestRunOf` rather than a registry table. The demo clock no longer pins
a date by default — it follows the system date until an `ADMIN` sets one from the Demo clock page — and the
seed itself carries no fixed end any more: `seed`'s `--end` defaults to today, so a fresh seed already has a
full history up to the day it was generated rather than to a date baked into an old fixture. The port also
surfaced a real seed defect: `ProjectPlanner` had dealt every ACTIVE project's start from the first 60% of
the window while `WorkQueue.planArrivals` gives a member no work in a week where none of their projects is
active, so projects begun early expired through the tail with nothing to replace them and a run on the
seed's own last day forecast near zero for everybody — not an intended wind-down. Fixed (commits `05e2f61`,
`779958c`): ACTIVE starts are now dealt across the whole window and every department's last project runs
past the as-of date, raising the last-eight-weeks-to-middle ratio a property test now holds from 0.53 to
0.76 (the remaining gap to 1.0 on this fixture is seasonal leave in weeks 30-34, not a code defect).
Documentation was corrected to match throughout — `server/forecast-web/README.md` rewritten, `server/README.md`
and this file updated, and `docs/backlog.md`'s "Java migration" section revised, closing the two
registry-shaped items the stale branch would otherwise have reintroduced and opening the ones specific to
this port (the host layer's duplication of `forecast-core`'s sample host, the pre-existing duplicate
`org.postgresql:postgresql` declaration in `forecast-core/pom.xml`, the test-jar's size, and the unchecked
Java/TypeScript boundary). Task 12 of that plan — the whole-branch review, one fix wave (`5028638`) and the
closing notes (`22b66ef`) — landed; its gate is the owner's to run, and the notes give the order to run it in.
Then, on 2026-09-19, the real-export preparation
(`docs/superpowers/specs/2026-09-19-real-export-preparation-design.md`,
`docs/superpowers/plans/2026-09-19-real-export-preparation.md`), a sixth driver verb, `prepare`, that rewrites
a real export in front of the seed so its people can be counted. **Its sections 5 and 6 were withdrawn two
days later**; what survives is described below.
Then, on 2026-09-21, **teams come from the hierarchy, not the `teams` table**
(`docs/superpowers/specs/2026-09-21-hierarchy-teams-design.md`): the owner corrected a misreading the whole
forecast had been built on. `teams` and `team_members` hold **project teams** — an ad-hoc group working on
one project — and say nothing about who reports to whom. The company structure is `users.manager_id`, and a
**team is a team leader and the people who report to them directly**, keyed by the leader's own user id. The
leader is counted like any member, because they do technical work; a **skill team leader** manages team
leaders, does none, is never counted, and runs a forecast only by choosing one leader beneath them, which
they do rarely and to stand in for an absent leader. The name `team_id` is kept everywhere — API parameters,
REST paths, `forecast_runs.team_id`, `forecast_current_days.team_id`, the facts — so **no migration**: only
what the uuid points at changed. `ForecastRepository` stops reading both tables and drops the
"must be in a team" condition, so every active user of a counted role is forecastable, where the owner's real
export previously produced a forecast over nobody. `TeamRow` is gone, `ProjectRow` lost `team_id`, `UserRef`
gained `department` and `manager_id`. The joined date came from `team_members.joined_at`, which is no longer
read, and becomes the member's **first activity** — their earliest logged hour or the earliest `task_history`
row they are the actor of — with a blank tenure rather than an invented date for a member who has none. A
team's projects become the projects of its members' tasks, bounded in the feature path by the week being
built, because a project the team only picks up later must not reach a row built before it did
(`FeatureLeakageTest` caught exactly that). The seed's `Team` record split: a `Department` is where work comes
from, and a team is a manager's id (`Directory.teamKey`, the same rule as `FeatureBuilder.teamOf`);
`ProjectPlanner` now mints the `teams` rows, one per project, from the people who actually took its work,
which is what the application means by them, and mints none in real mode, where a team id would be a dangling
foreign key. `ExportPreparer` fell from 319 lines to 100: it keeps the activation and gives each manager the
leader role their place implies, a manager of managers becoming `SKILL_TEAM_LEADER`, and `--joined` went with
the rows it stamped. `forecast-web` reads `users` alone for its teams and its three permission predicates.
One pre-existing defect surfaced on the way: `ExportPreparerPropertyTest` could never run, because its
`DEPARTMENTS` list holds a null and `List.of` rejects nulls, so the class failed in its static initialiser
every time.
Then, later the same day, the **second revision** of that design (its section 16), after the owner's real
export was measured for the first time: `users.role` reads MEMBER for 260 of its 264 people, the only two
accounts marked `TEAM_LEADER` have no title, no department and no reports, and nobody at all carries
`SKILL_TEAM_LEADER` — so keying teams on that column would have forecast nobody in production, the same
failure one level in. `users.job_title`, which the owner's organisation system writes automatically, does
carry the structure, so `EffectiveRole` derives the role from it in two stages: the title classifies (`skill
team leader` and `center manager` make actors, `team lead` or `lead engineer` a leader, anything else a
member; `ADMIN`, `CENTER_MANAGER` and `VIEWER` are still read from the column, because no title implies
them), and then a leader with no counted, active direct report is demoted to a member — the owner's ruling,
so a lead engineer who leads nobody is forecast as one. Actors are never demoted, because that would make
them forecast subjects. The two stages cannot contradict each other: demotion turns `TEAM_LEADER` into
`MEMBER` and both are counted, so who is counted is fixed by the title alone. A `CENTER_MANAGER` now runs any
team, like an `ADMIN`; a skill team leader still runs one leader beneath them at a time. `ExportPreparer`
decides nothing any more — it activates and writes the module's own answer back — the seed's `Directory`
calls the same rule instead of its own, and the synthetic directory gained one `VIEWER` so all six roles have
a subject. Running `prepare` over the owner's own export confirms it end to end: 258 users activated, **179 members in
12 teams**, 3 skill team leaders, and 79 people who carry a counted role but sit in no team, so no run
reaches them — 41 reporting to nobody, 38 to an actor. That gap is the owner's ruling that people in no team
stay out, and section 16.5 records it. `prepare` prints both figures: a counted role is permission to be
forecast, not a guarantee, since a run is per team.
Next: the real
export through `prepare`, the seed and the import into the local PostgreSQL, then the derived-arithmetic
backlog item's own design pass, then the server's own integration code, against the sample host.
The standing workflow for a plan:
`brainstorming`, `writing-plans`, subagent-driven execution with a review per task, a whole-branch review, one
fix wave, the gate green by hand in the development container, then fast-forward `main`.

## Hard rules

- The language model never produces a forecast number. Deterministic code computes demand, capacity and
  overload; Copilot reads facts through tools and writes narrative, and every number it writes is verified
  against the facts (`UNVERIFIED` is stored and reported, never promoted). This is permanent: on 2026-09-07 the
  owner closed the idea of an LLM forecast candidate for good. Do not reopen it.
- Demand is never capped by capacity; overload is reported.
- Copilot access goes through the GitHub Copilot SDK with the user's own token, stored encrypted and read in
  one place. Never add an API key of another provider. No `copilot login` on the server.
- Real names are allowed in prompts (owner decision); still keep all data local except what a run sends to
  Copilot, and store the exact facts sent for audit (`forecast_facts`).
- Test-driven development for every change; jqwik property tests for arithmetic invariants. No test talks to
  Copilot or extracts the SDK's runtime.
- The `origin` remote is back since 2026-09-14 (`IGlace/WorkLoadHubAiForecasting`); the owner deleted it on
  2026-09-12 and restored it. `dev` is pushed there. CI is paused on it by the owner's decision of
  2026-09-14, so never say "CI will catch it": the gate is `bash scripts/check.sh` run by hand in the
  development container before anything is pushed. `archive/python-desktop-v1` **is** on the remote, as a
  branch (`origin/archive/python-desktop-v1`): 68 commits ending 2026-09-10, carrying the Electron app, the
  Python service and the first Java migration history. Corrected on 2026-09-14, when the earlier claim that
  it was an unpushed tag was checked against `git ls-remote` and found false; no such tag exists anywhere.
- English and French are both fully supported in the narrative; a user may switch freely. No third language.
  The experiment driver's own messages and help are English only, and it does not narrate; the sample host
  asks for one language or the other (`run-host-example.sh --lang en|fr`).
- The real export and any real-mode seed output stay outside the repository, and the seed only ever targets
  the local database of `scripts/postgres.sh`: the real WorkloadHub database is never seeded (owner,
  2026-09-18), and nothing of `forecast-tools` or the scripts ships to the application's developer.

## Layout

```text
server/    Java 21 modules: `forecast-core`, the library the host adds and the only artifact; `forecast-tools`,
           never shipped: the seed, the import and export of a WorkloadHub database, `Experiment` (the
           driver: init-db, import, export, seed, prepare, fixture) and `HostExample` (what a host does through
           `ForecastService`), run through `server/tools/experiment.sh` and `server/examples/run-host-example.sh`;
           `forecast-web`, never shipped: a showcase Spring Boot application and React front end that depends
           on `forecast-core` alone and calls it the way a real host will, run through
           `server/forecast-web/run.sh` against a database filled beforehand by `server/tools/experiment.sh`
docs/      requirements, research, design documents, specs, plans, evaluation results, reports, backlog
scripts/   `check.ps1` and `check.sh` (the gate), `release.sh` and `release.ps1` (gate, then fast-forward main to
           dev), `test-release.sh` (the release script's self-test), `devbox.sh` (the development
           container, built from `scripts/container/Containerfile`), `postgres.sh` (the local PostgreSQL the
           driver connects to)
.claude/   skills, agents, hooks, settings
```

## Toolchain

- Java 21, Maven 3.9, Spring Boot 4.1, JUnit 6, jqwik, Flyway, XGBoost4J, copilot-sdk-java; Node 22 and
  npm for `forecast-web`'s front end, resolving through AVL's Artifactory
  (`https://arti.avl.com/artifactory/api/npm/npm-release/`, set in the development container's global npmrc).
- The gate is one step: `cd server && mvn -B -q verify` (both modules; the database tests run PostgreSQL
  through Testcontainers and need Docker or podman: without an engine the gate fails, it never skips). It
  takes about twelve minutes as of 2026-09-18, and grows with the suite. `bash scripts/check.sh` and
  `pwsh scripts/check.ps1` run it, and running one of them by hand is the only gate there is. **Read the
  result from `forecast-core/target/surefire-reports/TEST-*.xml` and
  `forecast-tools/target/surefire-reports/TEST-*.xml`, never by summing the `*.txt` files**: a class that
  mixes JUnit `@Test` with jqwik `@Property` has both engines write the same `.txt` and the second
  overwrites the first, so the text total is short by about thirty. Wipe both report directories before a
  run you intend to trust.
  `.github/workflows/ci.yml` describes the same step but is paused: on 2026-09-14 the owner asked for no
  CI until the work has progressed much further, so only `workflow_dispatch` is left and no push starts a
  run. Keep the three in step anyway, and restore the `push` and `pull_request` triggers, which the file
  carries as a comment, when the owner asks for CI back.
- With no JDK on the machine, work inside the development container: `bash scripts/devbox.sh shell`. It
  keeps an Ubuntu box running with the toolchain — Java, Maven and, since 2026-09-19, Node 22 — and this
  repository bind-mounted at `/work`, so the gate
  and the experiment driver run there exactly as on Linux, with the engine's socket mounted, which the gate
  needs. `bash scripts/check.sh` on the Windows host instead would skip the Maven step and still exit
  0, reporting success having compiled nothing. Details in `server/README.md`, "The development container".
  Every text file stays LF (`.gitattributes`): Linux bash rejects a CRLF script with a message that names
  nothing it is about, and `core.autocrlf` is Windows-side, so CRLF copies make git inside the box see the
  whole tree as modified.
- `bash scripts/postgres.sh up` starts the local PostgreSQL 18 (database, user and password `workloadhub`,
  port 5432); `bash server/tools/experiment.sh init-db` creates the schema in it.
- `bash scripts/release.sh` or `pwsh scripts/release.ps1` runs the gate and fast-forwards `main` to `dev`;
  either is the only thing that can refuse a bad release, because git has no pre-merge hook for a
  fast-forward. Both refuse to run unless mvn is on PATH, so the whole gate runs; neither pushes.
  `bash scripts/test-release.sh` checks the bash one on a throwaway repository. On this
  machine the bash one runs inside the development container, not in WSL: that is where mvn is,
  and `test-release.sh` passes there. A real release from there has not been done yet.
- Copilot: the SDK runs an in-process runtime, unpacked once to `~/.copilot/runtime-cache`; tokens need
  `whf.token-key` (the sample host reads it from `WHF_TOKEN_KEY`). The live path is checked by hand, through
  `bash server/examples/run-host-example.sh --narrate` (`server/README.md`).

## Skills and agents

Superpowers skills are installed as project skills in `.claude/skills/` and are bootstrapped at session start
by `.claude/hooks/superpowers-session-start.sh`. Follow their workflow: `brainstorming` before new features,
`writing-plans` before multi-step work, `test-driven-development` while coding,
`verification-before-completion` before claiming done, `finishing-a-development-branch` at the end. Upstream
names `superpowers:<name>` map to plain `<name>` here.

External skills: `copilot-sdk` (Copilot SDK usage; note that the Java SDK's runtime and session options are
documented in the narration spec, section 2) and `property-based-testing`. Index and licenses in
`.claude/skills/README.md`.

Product skills `whf-*` live in `server/forecast-core/src/main/resources/skills/` and are embedded in the
narrator's system message. Edit them there; keep them factual, short and specific to this domain; the test
`SkillTextsTest` pins their vocabulary to the Java facts.

Subagents in `.claude/agents/` (VoltAgent selection): cli-developer, data-scientist, data-engineer,
ai-engineer, prompt-engineer, test-automator, code-reviewer, architect-reviewer, security-auditor,
technical-writer. Index in `.claude/agents/README.md`.

## Conventions

- Branches: `dev` is the development branch; all work lands there first. `main` is the release branch and only
  receives fast-forward merges from `dev` once a plan or fix batch is reviewed and every suite is green.
  `archive/python-desktop-v1` is a **branch on the remote**, not a tag: it survived the 2026-09-12 remote
  deletion and is the only name for the frozen Python desktop version. `git fetch origin
  archive/python-desktop-v1 && git worktree add ../whf-archive origin/archive/python-desktop-v1` checks it
  out. Its history is unrelated to `dev`'s: the restored remote started `dev` afresh on 2026-09-11, so the
  two share no ancestor and must never be merged into each other — a merge would resurrect every file the
  Java module has since deleted.
- Commit messages: imperative subject, short body explaining why.
- Domain vocabulary: team (a team leader and the people who report to them directly, keyed by the leader's
  own user id), effective role (the role the forecast acts on: the job title decides, and a leader nobody
  counted reports to is a member — `EffectiveRole`, never `users.role` except for ADMIN, CENTER_MANAGER and
  VIEWER), skill team leader (the manager of a team leader; not counted, runs one leader's team at a
  time), department (`users.department`, where work comes from), member; demand, capacity,
  overload; the model and its target (logged hours per member-week), backtest; narrative, facts, contract,
  verification; window (five weekdays; a run covers one to six, two by default, starting the first weekday
  after the run day), current forecast (the latest run's value per member and day).
- Dates are ISO 8601; weeks start on Monday; working days are Monday to Friday.
