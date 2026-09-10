# WorkloadHub AI Forecasting

A Java 21 module for the WorkloadHub Spring Boot server (Linux; development on Windows through WSL) that
forecasts each team member's work hours for the next two weeks from the team's task history in the
application's own PostgreSQL database, compares them with capacity (40 h/week default over the working days,
holidays, absences and the team's capacity plan), and uses each user's own GitHub Copilot seat to explain
patterns, warn about overload and suggest rebalancing. The host calls a Java interface or an optional REST
surface; a command line runs the same code on SQLite for experiments. The first version (a Windows desktop
app with a Python service) is archived on `archive/python-desktop-v1`.

## Read these first

- `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`: the design of the module (database,
  seed generator, feature matrix, models, backtest, run pipeline, public API, CLI, testing).
- `docs/superpowers/specs/2026-09-10-java-copilot-narration-design.md`: Copilot narration from Java (the SDK
  as found, tools, contract, verification, persistence, REST, CLI) and its recorded deviations.
- `docs/design/2026-09-08-workloadhub-schema-and-feature-matrix.md`: the real WorkloadHub schema mapped to the
  forecast. The export it was written from holds credentials and personal data and is never committed.
- `docs/requirements/requirements-v1.md` and `docs/requirements/2026-09-03-discovery-qa.md`: scope, roles and
  the owner's answers, still the source of truth for scope questions.
- `docs/superpowers/plans/`: the reviewed plans, each with closing notes and rulings; `docs/backlog.md`: open
  items and the rulings under "Java migration".
- `server/README.md`: build, the CLI, the seed, the parity check, using the module from the server, narrating
  with Copilot.
- Documents dated before 2026-09-09 describe the archived version; each carries a note saying so.

## Where the project stands (2026-09-10)

Plans 1 to 4 landed on `dev` and `main` (foundation and seed; pipeline core; run, eval and parity; Copilot
narration), then the archival plan (`docs/superpowers/plans/2026-09-10-python-desktop-archival.md`). Then the
rolling forecast windows (`docs/superpowers/plans/2026-09-10-rolling-forecast-windows.md`): a run starts the
first weekday after the run day, covers ten weekdays in two windows, is computed per day and keeps a per-day
current forecast. Next: the live Copilot check on a seeded database (`server/README.md`, "Narrating with
Copilot"), then the real
export through the seed and the parity procedure, then the host integration. The standing workflow for a plan:
`brainstorming`, `writing-plans`, subagent-driven execution with a review per task, a whole-branch review, one
fix wave, CI green on `dev`, then fast-forward `main`.

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
- The GitHub remote (`IGlace/WorkLoadHubAiForecasting`) is the shared copy: `dev` and `main` are pushed there
  and CI runs on both. Push `dev` when a batch is reviewed.
- English and French are both fully supported in the narrative; a user may switch freely. No third language.
  The CLI's own messages and help are English only; its narratives are `--lang en` or `fr`.
- The real export and any real-mode seed output stay outside the repository.

## Layout

```text
server/    Java 21 module: `forecast-core` (the library the host adds) and `forecast-cli` (command line:
           init-db, import, export, seed, run, runs, current, teams, eval, narrate, copilot status);
           `server/tools/` holds the parity scripts and their one Python test
docs/      requirements, research, design documents, specs, plans, evaluation results, reports, backlog
scripts/   `check.ps1` and `check.sh` (the gate), `release.ps1` (gate, then fast-forward main to dev)
.claude/   skills, agents, hooks, settings
```

## Toolchain

- Java 21, Maven 3.9, Spring Boot 4.1, JUnit 6, jqwik, picocli, Flyway, XGBoost4J, copilot-sdk-java.
- The gate: `cd server && mvn -B -q verify` (under three minutes without Docker; PostgreSQL tests run through
  Testcontainers when Docker is present, else skip with a message) plus the parity tool's test,
  `uv run --python 3.11 --with pytest pytest server/tools/tests`. `bash scripts/check.sh` and
  `pwsh scripts/check.ps1` run both; `.github/workflows/ci.yml` runs the same on pushes to `dev` and `main`.
  Keep the three in step.
- `pwsh scripts/release.ps1` runs the gate and fast-forwards `main` to `dev`; it is the only thing that can
  refuse a bad release, because git has no pre-merge hook for a fast-forward. It refuses to run unless both
  mvn and uv are on PATH, so the whole gate runs.
- Copilot: the SDK runs an in-process runtime, unpacked once to `~/.copilot/runtime-cache`; tokens need
  `whf.token-key` (server) or `WHF_TOKEN_KEY` (CLI). The live path is checked by hand (`server/README.md`).

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
  `archive/python-desktop-v1` is frozen.
- Commit messages: imperative subject, short body explaining why.
- Domain vocabulary: department (a team without a manager), team (team leader), member; demand (open, new and
  planned hours), capacity, overload; arrival model, effort model, champion model, backtest; narrative, facts,
  contract, verification; window (five weekdays; a run covers two, starting the first weekday after the run
  day), current forecast (the latest run's value per member and day).
- Dates are ISO 8601; weeks start on Monday; working days are Monday to Friday.
