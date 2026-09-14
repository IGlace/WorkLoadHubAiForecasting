# WorkloadHub AI Forecasting

A Java 21 module for the WorkloadHub Spring Boot server (Linux; development on Windows, in the container
`scripts/devbox.sh` keeps running) that
forecasts each team member's work hours for the next two weeks from the team's task history in the
application's own PostgreSQL database, compares them with capacity (40 h/week default over the working days,
holidays, absences and the team's capacity plan), and uses each user's own GitHub Copilot seat to explain
patterns, warn about overload and suggest rebalancing. The host calls a Java interface or an optional REST
surface; a single-file Java driver runs the same code on SQLite for experiments. The first version (a Windows desktop
app with a Python service) is archived at the tag `archive/python-desktop-v1`.

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
- `docs/superpowers/specs/2026-09-13-weekly-hours-forecast-design.md`: **reviewed by the owner on 2026-09-14
  and ready for an implementation plan; nothing of it is implemented.** It retargets the forecast at logged
  hours, deletes `EffortModel`, `PlannedWork`, `SeasonalNaive` and the champion machinery, makes the window
  count configurable, corrects the capacity default to 44 h, adds the pressure facts a forecast cannot show,
  and changes the seed generator, which today cannot produce overtime at all. Its section 18 holds the twelve
  rulings of that review and is the first thing to read; each one cites the section it changes. Section 23
  gives the task order. Read the document before changing anything in `model/`, `planned/`, `backtest/`,
  `features/` or the facts contract; it supersedes `2026-09-12-single-model-simplification-design.md`, which
  is history.
- `docs/superpowers/plans/`: the reviewed plans, each with closing notes and rulings; `docs/backlog.md`: open
  items and the rulings under "Java migration".
- `server/README.md`: build, running experiments, the seed, the parity check, using the module from the
  server, narrating with Copilot.
- Documents dated before 2026-09-09 describe the archived version; each carries a note saying so.

## Where the project stands (2026-09-13)

Plans 1 to 4 landed on `dev` and `main` (foundation and seed; pipeline core; run, eval and parity; Copilot
narration), then the archival plan (`docs/superpowers/plans/2026-09-10-python-desktop-archival.md`). Then the
rolling forecast windows (`docs/superpowers/plans/2026-09-10-rolling-forecast-windows.md`): a run starts the
first weekday after the run day, covers ten weekdays in two windows, is computed per day and keeps a per-day
current forecast. Then the host integration design
(`docs/superpowers/specs/2026-09-11-host-integration-design.md`): progress labels, start-up reconciliation and
a Java-interface sample host; the server's own code is written in the WorkloadHub repository. Then the
accuracy evaluation (`docs/superpowers/specs/2026-09-11-accuracy-evaluation-design.md`): `accuracy(teamId,
from, to)` compares the forecasts made before each past weekday with the logged hours. Then, on 2026-09-12,
the CLI trim: `forecast-cli` kept only the five commands that build and score an experiment database, and
everything a host does is shown by `server/examples/HostExample.java` instead. The same day the module went
too: those five commands were 408 lines wrapping public `forecast-core` classes, so they are now one
single-file Java program, `server/tools/Experiment.java`, run by `server/tools/experiment.sh` the way the
sample host is run; and evaluation became a module feature, `ForecastService.evaluate(EvalConfig)`, which is
the path the driver's `eval` takes.
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
changes with it (section 22): a seeded member can never log past 8 hours in a day against a 40-hour capacity
row, so under the retarget overload would be arithmetically unreachable on seeded data. It stays one plan
(section 23). **Reviewed and ready for a plan; nothing of it is implemented.** Until it lands, the code still
forecasts arrival hours and the parity procedure it retires is still in the tree.
Next: the live Copilot check on a seeded database (`server/README.md`, "Narrating with Copilot"), then the
real export through the seed and the parity procedure, then the server's own integration code, against the
sample host.
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
  development container before anything is pushed. The tag `archive/python-desktop-v1` is not on the remote
  yet; only the owner can push it.
- English and French are both fully supported in the narrative; a user may switch freely. No third language.
  The experiment driver's own messages and help are English only, and it does not narrate; the sample host
  asks for one language or the other (`run-host-example.sh --lang en|fr`).
- The real export and any real-mode seed output stay outside the repository.

## Layout

```text
server/    Java 21 module: `forecast-core`, the library the host adds and the only artifact. Two single-file
           programs are run by the launcher, not built: `server/examples/HostExample.java` (what a host does
           through `ForecastService`) and `server/tools/Experiment.java` (experiments on SQLite: init-db,
           import, export, seed, eval). `server/tools/` also holds the parity scripts and their one Python test
docs/      requirements, research, design documents, specs, plans, evaluation results, reports, backlog
scripts/   `check.ps1` and `check.sh` (the gate), `release.sh` and `release.ps1` (gate, then fast-forward main to
           dev), `test-release.sh` (the release script's self-test), `devbox.sh` (the development
           container, built from `scripts/container/Containerfile`)
.claude/   skills, agents, hooks, settings
```

## Toolchain

- Java 21, Maven 3.9, Spring Boot 4.1, JUnit 6, jqwik, Flyway, XGBoost4J, copilot-sdk-java.
- The gate: `cd server && mvn -B -q verify` (about six minutes without Docker; PostgreSQL tests run through
  Testcontainers when Docker is present, else skip with a message) plus the parity tool's test,
  `uv run --python 3.11 --with pytest pytest server/tools/tests`. `bash scripts/check.sh` and
  `pwsh scripts/check.ps1` run both, and running one of them by hand is the only gate there is.
  `.github/workflows/ci.yml` describes the same steps but is paused: on 2026-09-14 the owner asked for no
  CI until the work has progressed much further, so only `workflow_dispatch` is left and no push starts a
  run. Keep the three in step anyway, and restore the `push` and `pull_request` triggers, which the file
  carries as a comment, when the owner asks for CI back.
- With no JDK on the machine, work inside the development container: `bash scripts/devbox.sh shell`. It
  keeps an Ubuntu box running with the toolchain and this repository bind-mounted at `/work`, so the gate
  and the experiment driver run there exactly as on Linux, with the engine's socket mounted so the PostgreSQL tests
  still run. `bash scripts/check.sh` on the Windows host instead would skip the Maven step and still exit
  0, reporting success having compiled nothing. Details in `server/README.md`, "The development container".
  Every text file stays LF (`.gitattributes`): Linux bash rejects a CRLF script with a message that names
  nothing it is about, and `core.autocrlf` is Windows-side, so CRLF copies make git inside the box see the
  whole tree as modified.
- `bash scripts/release.sh` or `pwsh scripts/release.ps1` runs the gate and fast-forwards `main` to `dev`;
  either is the only thing that can refuse a bad release, because git has no pre-merge hook for a
  fast-forward. Both refuse to run unless mvn and uv are on PATH, so the whole gate runs; neither pushes.
  `bash scripts/test-release.sh` checks the bash one on a throwaway repository. On this
  machine the bash one runs inside the development container, not in WSL: that is where mvn and uv are,
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
  `archive/python-desktop-v1` is a **tag**, not a branch: the branch lived only on the deleted remote, so the
  tag is now the only name for the frozen Python desktop version. `git worktree add ../whf-archive
  archive/python-desktop-v1` checks it out, which is what `server/tools/parity.sh` wants.
- Commit messages: imperative subject, short body explaining why.
- Domain vocabulary: department (a team without a manager), team (team leader), member; demand (open, new and
  planned hours), capacity, overload; arrival model, effort model, champion model, backtest; narrative, facts,
  contract, verification; window (five weekdays; a run covers two, starting the first weekday after the run
  day), current forecast (the latest run's value per member and day).
- Dates are ISO 8601; weeks start on Monday; working days are Monday to Friday.
