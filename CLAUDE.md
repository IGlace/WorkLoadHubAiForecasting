# WorkloadHub AI Forecasting

A Windows desktop application plus PowerShell CLI that forecasts each team member's
estimated work hours for the next two weeks from the team's task history, compares
them with capacity (40 h/week default, holidays, vacations, overrides), and uses the
user's own GitHub Copilot Enterprise seat to explain patterns, warn about overload
and suggest rebalancing. Version 1 runs on generated dummy data.

## Read these first

- `docs/superpowers/specs/2026-09-03-workload-forecast-design.md`: the approved design.
- `docs/requirements/requirements-v1.md`: scope, roles, functional and non-functional requirements.
- `docs/requirements/2026-09-03-discovery-qa.md`: the owner's answers, source of truth for scope questions.
- `docs/research/2026-09-03-research-notes.md`: sourced facts about Copilot CLI/SDK, forecasting methods, prior art.
- `docs/superpowers/plans/`: implementation plans, when present.

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

## Layout

```
service/   Python 3.12 service (package `whf`): FastAPI, Typer CLI, SQLite, models, generator, Copilot session
app/       Electron + React + TypeScript desktop app
installer/ PyInstaller and electron-builder configuration
scripts/   PowerShell and shell helpers
docs/      research, requirements, specs, plans
.claude/   skills, agents, hooks, settings
```

## Toolchain

- Python: `uv`, `ruff`, `ty`, `pytest`, `hypothesis`; see the `modern-python` skill.
- Node: Node 22, `npm`, `vite`, `vitest`, `electron-builder`, `eslint`, `tsc`.
- Commands (once scaffolded): `uv run pytest` in `service/`; `npm test` in `app/`.

## Skills and agents

Superpowers skills are installed as project skills in `.claude/skills/` and are
bootstrapped at session start by `.claude/hooks/superpowers-session-start.sh`.
Follow their workflow: `brainstorming` before new features, `writing-plans` before
multi-step work, `test-driven-development` while coding,
`verification-before-completion` before claiming done, `finishing-a-development-branch`
at the end. Upstream names `superpowers:<name>` map to plain `<name>` here.

External skills: `copilot-sdk` (Copilot SDK usage), `pydantic-models-py`,
`modern-python`, `property-based-testing`. Index and licenses in `.claude/skills/README.md`.

Product skills `whf-*` are shipped to the Copilot agent inside the product and are
also loaded by Claude Code; keep them factual, short and specific to this domain.

Subagents in `.claude/agents/` (VoltAgent selection): python-pro, typescript-pro,
electron-pro, react-specialist, cli-developer, data-scientist, data-engineer,
ai-engineer, prompt-engineer, test-automator, code-reviewer, architect-reviewer,
security-auditor, technical-writer. Index in `.claude/agents/README.md`.

## Conventions

- Branch work goes to `claude/skills-installation-approach-su979o` until told otherwise.
- Commit messages: imperative subject, short body explaining why.
- Domain vocabulary: department (skill team leader), team (team leader), member;
  demand, capacity, overload; arrival model, effort model, champion model, backtest.
- Dates are ISO 8601; weeks start on Monday; working days are Monday to Friday.
