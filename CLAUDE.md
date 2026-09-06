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
- `docs/superpowers/plans/`: implementation plans, when present.
- `docs/backlog.md`: open items after version 1 (polish, Windows verification, design decisions, future topics).

## Hard rules

- The language model never produces a forecast number. Deterministic code computes
  demand, capacity and overload; Copilot reads facts through tools and writes narrative.
- Demand is never capped by capacity; overload is reported.
- Everything must run on Windows in PowerShell. No WSL at install or run time.
- Copilot access goes through the GitHub Copilot SDK or CLI with the user's login.
  Never add an API key of another provider.
- Real names are allowed in prompts (owner decision); still keep all data local except
  what a run sends to Copilot, and store the exact facts sent for audit.
- Test-driven development for every change; property tests for arithmetic invariants.
- The GitHub remote (`IGlace/WorkLoadHubAiForecasting`) is the shared copy again since 2026-09-06:
  `dev` and `main` are pushed there and CI runs on both. Work offline if you must, but push `dev`
  when a batch is reviewed.
- English and French are both fully supported, everywhere in the interface and in the
  narrative; a user may switch freely between them. No third language.

## Layout

```text
service/   Python 3.11+ service (package `whf`): FastAPI, Typer CLI, SQLite, models, generator, Copilot session
app/       Electron + React + TypeScript desktop app (`src/main`, `src/preload`, `src/renderer`, `src/shared`)
installer/ PyInstaller and electron-builder configuration, installer README
scripts/   PowerShell and shell helpers (`dev-app.ps1`, `build-service.{ps1,sh}`, `build-installer.ps1`)
docs/      research, requirements, specs, plans
.claude/   skills, agents, hooks, settings
```

## Toolchain

- Python: `uv`, `ruff`, `ty`, `pytest`, `hypothesis`; see the `modern-python` skill.
- Node: Node 22, `npm`, `electron-vite` (Vite 7), `vitest`, `eslint` 10, `tsc`, `electron-builder`.
- Commands: `uv run pytest` in `service/`; `npm test`, `npm run lint`, `npm run typecheck` in `app/`.
- Local gate (mirrors CI): `pwsh scripts/check.ps1` runs the fast
  checks in about two and a half minutes, `-Full` adds the slow pytest suite and the app build,
  `-Package` adds the installer. `pwsh scripts/release.ps1` runs the full gate and then
  fast-forwards `main` to `dev`; it is the only thing that can refuse a bad release, because git
  has no pre-merge hook for a fast-forward. Activate the hooks once per clone with
  `git config core.hooksPath scripts/hooks`; escape them with `git commit --no-verify` or
  `WHF_SKIP_HOOKS=1`.
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
