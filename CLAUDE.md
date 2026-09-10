# WorkloadHub AI Forecasting

A Windows desktop application plus PowerShell CLI that forecasts each team member's
estimated work hours for the next two weeks from the team's task history, compares
them with capacity (44 h/week default over 5 working days, holidays, vacations, overrides), and uses the
user's own GitHub Copilot Enterprise seat to explain patterns, warn about overload
and suggest rebalancing. Version 1 runs on generated dummy data.

## Read these first

- `docs/superpowers/specs/2026-09-03-workload-forecast-design.md`: the approved design.
- `docs/requirements/requirements-v1.md`: scope, roles, functional and non-functional requirements.
- `docs/requirements/2026-09-03-discovery-qa.md`: the owner's answers, source of truth for scope questions.
- `docs/research/2026-09-03-research-notes.md`: sourced facts about Copilot CLI/SDK, forecasting methods, prior art.
- `docs/superpowers/specs/2026-09-06-forecast-evaluation-and-chronos2-design.md`: the evaluation harness and Chronos-2 candidate design.
- `docs/superpowers/specs/2026-09-07-planned-work-and-likely-work-design.md`: planned-work allocation and the
  "likely work" narrative section, locked on 2026-09-07 and implemented once the real export has landed
  (plan: `docs/superpowers/plans/2026-09-07-planned-work-and-likely-work.md`).
- `docs/design/2026-09-07-forecasting-internals.md`: the feature matrix, the four arrival models' algorithms,
  and the backtest tournament end to end, as the code does it (update it when that code changes).
- `docs/design/2026-09-08-workloadhub-schema-and-feature-matrix.md`: the real WorkloadHub schema mapped to
  the forecast, and the feature matrix redesigned for it; the basis of the server-side Java implementation.
  The export it was written from holds credentials and personal data and is never committed.
- `docs/superpowers/plans/`: implementation plans, when present.
- `docs/backlog.md`: open items after version 1 (polish, Windows verification, design decisions, future topics).
- `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`: the current direction, a Java 21 module
  for the WorkloadHub Spring Boot server that replaces the Python service and the desktop app. Section 14 is
  the migration and archival procedure, section 16 the order of work.

## Where the Java migration stands (2026-09-10)

Done, each as a reviewed plan landed on `dev` and fast-forwarded to `main`:
`docs/superpowers/plans/2026-09-09-java-foundation-and-seed.md` (skeleton, database, import, token store,
seed generator), `2026-09-09-java-pipeline-core.md` (lifecycle, calendar, capacity, feature matrix, models,
backtest, effort, planned work), `2026-09-10-java-run-eval-and-parity.md` (run pipeline, facts JSON, run
store, service, CLI `run`/`runs`/`teams`/`eval`, evaluation harness, Python import for parity, parity gate;
synthetic result in `docs/eval/2026-09-10-java-parity-synthetic/`) and
`docs/superpowers/plans/2026-09-10-java-copilot-narration.md` (Copilot narration through the SDK, tools,
contract, verification, usage, REST controller, sample host, CLI `narrate` and `copilot status`). Rulings
taken on the way are in each plan's closing notes and under "Java migration" in `docs/backlog.md`.

Next, with the standing workflow (`brainstorming`, `writing-plans`, subagent-driven execution with a review
per task, a final whole-branch review, one fix wave, CI green on `dev`, then fast-forward `main`): the
migration plan, spec section 14 steps 1, 4 and 5 (branch `archive/python-desktop-v1` from `main`, remove
`service/`, `app/`, `installer/` and the desktop-only scripts and skills, rewrite this file, the README,
`scripts/` and CI for Java only, including dropping the "no WSL" hard rule below). Until then the Python
service stays as the parity oracle and the desktop app is untouched.

## Hard rules

- The language model never produces a forecast number. Deterministic code computes
  demand, capacity and overload; Copilot reads facts through tools and writes narrative.
  This is permanent: on 2026-09-07 the owner closed the idea of an LLM forecast candidate for good
  (`docs/superpowers/specs/2026-09-07-planned-work-and-likely-work-design.md`, section 2). Do not reopen it.
- Demand is never capped by capacity; overload is reported.
- Everything must run on Windows in PowerShell. No WSL at install or run time.
- Copilot access goes through the GitHub Copilot SDK or CLI with the user's login.
  Never add an API key of another provider.
- Real names are allowed in prompts (owner decision); still keep all data local except
  what a run sends to Copilot, and store the exact facts sent for audit.
- Test-driven development for every change; property tests for arithmetic invariants.
- The installer bundles CPU-only PyTorch and the pinned Chronos-2 weights; never resolve a CUDA build and
  never download weights at run time.
- The GitHub remote (`IGlace/WorkLoadHubAiForecasting`) is the shared copy again since 2026-09-06:
  `dev` and `main` are pushed there and CI runs on both. Work offline if you must, but push `dev`
  when a batch is reviewed.
- English and French are both fully supported, everywhere in the interface and in the
  narrative; a user may switch freely between them. No third language.

## Layout

```text
service/   Python 3.11+ service (package `whf`): FastAPI, Typer CLI, SQLite, models, generator, Copilot session,
           evaluation harness (`src/whf/eval/`)
app/       Electron + React + TypeScript desktop app (`src/main`, `src/preload`, `src/renderer`, `src/shared`)
installer/ PyInstaller and electron-builder configuration, installer README, weight prefetch
           (`installer/pyinstaller/download_weights.py`)
scripts/   PowerShell and shell helpers (`dev-app.ps1`, `build-service.{ps1,sh}`, `build-installer.ps1`)
server/    Java 21 module for the WorkloadHub Spring Boot server: `forecast-core` (library) and `forecast-cli`
           (WSL command line: init-db, import, export, seed, run, runs, teams, eval, narrate, copilot status);
           see docs/superpowers/specs/2026-09-09-java-forecast-module-design.md
docs/      research, requirements, specs, plans
.claude/   skills, agents, hooks, settings
```

## Toolchain

- Python: `uv`, `ruff`, `ty`, `pytest`, `hypothesis`; see the `modern-python` skill.
- Node: Node 22, `npm`, `electron-vite` (Vite 7), `vitest`, `eslint` 10, `tsc`, `electron-builder`.
- Commands: `uv run pytest` in `service/`; `npm test`, `npm run lint`, `npm run typecheck` in `app/`;
  `uv run whf eval` in `service/` runs the evaluation harness, writing `scores.csv`, `demand.csv` and
  `summary.md`.
- Notebooks: `docs/notebooks/gbm-forecast-walkthrough.ipynb` walks one team through load, feature matrix,
  backtest, forecast with a forced model and the Copilot narrative, using the service's own functions. Run from
  `service/` with `uv run --with jupyter --with matplotlib jupyter lab <path>`; Jupyter and matplotlib are not
  project dependencies. Copilot calls go through a worker thread there because they own their asyncio loop.
- Local gate (mirrors CI): `pwsh scripts/check.ps1` runs the fast
  checks in about two and a half minutes, `-Full` adds the slow pytest suite and the app build,
  `-Package` adds the installer. `pwsh scripts/release.ps1` runs the full gate and then
  fast-forwards `main` to `dev`; it is the only thing that can refuse a bad release, because git
  has no pre-merge hook for a fast-forward. Activate the hooks once per clone with
  `git config core.hooksPath scripts/hooks`; escape them with `git commit --no-verify` or
  `WHF_SKIP_HOOKS=1`.
- Java: Maven 3.9, Spring Boot 4.1, JUnit 6, jqwik; `scripts/check.ps1` and `scripts/check.sh` run
  `mvn -B -q verify` in `server/` (under three minutes without Docker; PostgreSQL tests run when Docker is
  present). The real export and any real-mode seed output stay outside the repository.
- Packaging: PyInstaller (`installer/pyinstaller/whf.spec`), electron-builder
  (`installer/electron-builder.yml`); `pwsh scripts/build-installer.ps1` builds the
  installer. `.github/workflows/ci.yml` runs the same gate on GitHub for pushes to `dev` and
  `main`; keep it and `scripts/check.ps1` in step.

## Skills and agents

Superpowers skills are installed as project skills in `.claude/skills/` and are
bootstrapped at session start by `.claude/hooks/superpowers-session-start.sh`.
Follow their workflow: `brainstorming` before new features, `writing-plans` before
multi-step work, `test-driven-development` while coding,
`verification-before-completion` before claiming done, `finishing-a-development-branch`
at the end. Upstream names `superpowers:<name>` map to plain `<name>` here.

External skills: `copilot-sdk` (Copilot SDK usage), `pydantic-models-py`,
`modern-python`, `property-based-testing`. Index and licenses in `.claude/skills/README.md`.

Product skills `whf-*` live in `service/src/whf/ai/skills/` and are shipped inside the service; the Copilot session loads them through `skill_directories`. Edit them there; keep them factual, short and specific to this domain.

Subagents in `.claude/agents/` (VoltAgent selection): python-pro, typescript-pro,
electron-pro, react-specialist, cli-developer, data-scientist, data-engineer,
ai-engineer, prompt-engineer, test-automator, code-reviewer, architect-reviewer,
security-auditor, technical-writer. Index in `.claude/agents/README.md`.

## Conventions

- Branches: `dev` is the development branch; all work lands there first. `main` is the release branch and only
  receives fast-forward merges from `dev` once a plan or fix batch is reviewed and every suite is green.
- Commit messages: imperative subject, short body explaining why.
- Domain vocabulary: department (skill team leader), team (team leader), member;
  demand, capacity, overload; arrival model, effort model, champion model, backtest.
- Dates are ISO 8601; weeks start on Monday; working days are Monday to Friday.
