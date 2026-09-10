# Archiving the Python service and the desktop app: design

Date: 2026-09-10. Status: approved by the owner on 2026-09-10 (design presented in session). Implements
section 14, steps 1, 4 and 5, of `2026-09-09-java-forecast-module-design.md`. Steps 2 and 3 of that section
(the Java module, its parity check and the Copilot narration) landed with plans 1 to 4.

## 1. Goal

`dev` and `main` carry only the Java module and the documentation. The Python service, the Electron desktop
app, the installer and everything that served only them are frozen on the branch `archive/python-desktop-v1`,
which is the reference for wording and the parity oracle, and receives no new features.

## 2. Order of operations

1. `main` is fast-forwarded to `dev` at `5c69bf6` (plan 4, CI green: run 103), so the archive carries the
   whole history up to and including the Java narration. Done on 2026-09-10.
2. `archive/python-desktop-v1` is created from `main` and pushed. Done on 2026-09-10.
3. The removal and the rewrites below land as reviewed commits on the development branch, then on `dev`
   after the whole-branch review and a green CI run, then `main` is fast-forwarded.

## 3. What is removed from `dev`

| Path | Why |
|---|---|
| `service/` | the Python service (FastAPI, Typer CLI, models, generator, Copilot session, evaluation harness) |
| `app/` | the Electron and React desktop app |
| `installer/` | PyInstaller and electron-builder configuration, weight prefetch |
| `docs/notebooks/` | the Jupyter walkthrough over the Python service's functions |
| `scripts/build-installer.ps1`, `scripts/build-service.ps1`, `scripts/build-service.sh`, `scripts/dev-app.ps1` | desktop build and run scripts |
| `scripts/hooks/` (`pre-commit`, `post-merge`) | they run the PowerShell gate; the Java gate is `mvn verify` locally and CI on GitHub |
| `.claude/skills/modern-python/`, `.claude/skills/pydantic-models-py/` | Python-only skills |
| `.claude/agents/python-pro.md`, `typescript-pro.md`, `electron-pro.md`, `react-specialist.md` | desktop-only agents |
| `service/tests/test_parity_compare.py` | moves to `server/tools/tests/test_parity_compare.py` (section 5) |

Everything removed stays on the archive branch. `git rm -r` in one commit per group (code, scripts, skills and
agents), so `git log --follow` and the archive both remain readable.

## 4. What stays

`server/` (the two Maven modules), `server/tools/`, `docs/` except the notebooks, `.claude/` with the
superpowers skills, `copilot-sdk`, `property-based-testing`, the remaining ten agents, the session-start hook,
`.github/workflows/ci.yml` (rewritten), `scripts/check.ps1`, `scripts/check.sh`, `scripts/release.ps1`
(rewritten), `.gitignore` (trimmed), `CLAUDE.md` (rewritten), a new root `README.md`.

## 5. `server/tools` and the parity procedure

The parity oracle is now on another branch, so `server/tools/parity.sh` takes a fourth argument,
`ARCHIVE_DIR`, a checkout of `archive/python-desktop-v1` (`git worktree add ../whf-archive
archive/python-desktop-v1` is the documented way), and runs the Python side from `ARCHIVE_DIR/service` with
`uv run` there. Everything else in the script is unchanged. `parity_compare.py` and `translate-schema.py`
stay as they are.

The gate's own test moves to `server/tools/tests/test_parity_compare.py` unchanged except for the path it
resolves the script from. It runs with `uv run --python 3.11 --with pytest pytest server/tools/tests` from the
repository root, in `scripts/check.ps1`, `scripts/check.sh` and CI, and is skipped with a message when `uv`
is not on the path. `ruff` is no longer run on `server/tools` (the service's `pyproject.toml` carried its
configuration); the scripts keep their current style.

## 6. Gates and CI

- `.github/workflows/ci.yml`: the `server` job (Temurin 21, `mvn -B verify` in `server/`, Maven cache) and a
  `tools` job (astral-sh/setup-uv, `uv run --python 3.11 --with pytest pytest server/tools/tests`). The
  `service`, `app`, `freeze-linux` and `package-windows` jobs go. Triggers unchanged: pushes to `dev` and
  `main`, pull requests, manual dispatch.
- `scripts/check.ps1`: two steps, `mvn -B -q verify` in `server/` and the tools test; `-DryRun` kept; `-Full`
  and `-Package` removed. `scripts/check.sh` mirrors it (it already ran the Maven step; it gains the tools
  step). Both skip a step with a visible message when its tool is absent.
- `scripts/release.ps1`: runs `check.ps1` (no flags), then fast-forwards `main` to `dev`; the installer
  wording goes; `WHF_SKIP_HOOKS` handling goes with the hooks.
- `.gitignore`: the Python, Node, installer and report-PDF entries go; `.superpowers/`, `.worktrees/`, the
  local settings, `.DS_Store` and the `server/**` entries stay, the duplicated `server/**/target/` line
  collapsed to one.

## 7. Documentation

- `CLAUDE.md` is rewritten for the Java-only repository: what the project is now (a Java 21 module for the
  WorkloadHub Spring Boot server plus a CLI), the documents to read first (the two Java specs, the schema and
  internals design docs, the backlog), the state (plans 1 to 4 landed, the archive done, what comes next: the
  live Copilot check on a seeded database, then the real export), the hard rules (the language model never
  produces a forecast number; demand is never capped; Copilot only through the SDK with the user's own token,
  no other provider; real names allowed, data local except what a run sends to Copilot, the facts sent stored
  for audit; TDD with jqwik properties; English and French; `dev` then fast-forward `main`), the layout, the
  toolchain (Java 21, Maven 3.9, Spring Boot 4.1, JUnit 6, jqwik; `uv` only for the tools test), the skills
  and agents that remain, the conventions. The "no WSL" rule and the PyTorch and Chronos-2 rule go; a line
  says where the Python and desktop code lives.
- A root `README.md` (new, short): what the repository holds, how to build and test, a pointer to
  `server/README.md`, the archive branch, and `docs/`.
- `server/README.md`: the parity section names the archive checkout and the new argument.
- `.claude/skills/README.md`: the product skills now live in
  `server/forecast-core/src/main/resources/skills/`; the two removed skills leave the table.
  `.claude/agents/README.md`: the four removed agents leave the table; the "Used for" column names the Java
  work (`cli-developer` for the picocli commands, `test-automator` for JUnit, jqwik and CI).
- Every superseded document gets this note as its first lines, under the title:
  `> Superseded on 2026-09-10: the Python service and desktop app this document describes are archived on
  branch archive/python-desktop-v1. The current design is docs/superpowers/specs/2026-09-09-java-forecast-module-design.md.`
  The superseded documents are the specs `2026-09-03-workload-forecast-design.md`,
  `2026-09-06-forecast-evaluation-and-chronos2-design.md` and `2026-09-07-planned-work-and-likely-work-design.md`
  (whose allocation and narrative rules the Java module implements; the note says so), the design document
  `docs/design/2026-09-07-forecasting-internals.md`, and the ten plans dated 2026-09-03 to 2026-09-07. The
  requirements, research and discovery documents, `docs/eval/`, `docs/reports/` and the backlog stay as
  history without a note; the backlog gains a "Landed" entry for the archival and the Java spec's section 14
  gets a one-line "done on 2026-09-10" mark.

## 8. Verification

- `cd server && mvn -B -q verify` green on the stripped tree, under three minutes without Docker.
- The tools test green through `uv run --python 3.11 --with pytest pytest server/tools/tests`.
- A one-off check in the plan: `git grep -l` for `service/`, `app/src`, `installer/`, `uv run whf`,
  `npm run`, `pwsh scripts/check.ps1 -Full` outside `docs/` and `.claude/skills/` (the vendored skills mention
  these tools generically) returns nothing but the archive notes.
- CI green on `dev`, then `main` fast-forwarded.

## 9. Non-goals

No change to the Java module's code or tests beyond `server/tools`; no rewrite of the historical documents
beyond the note; no new tooling for Windows (WSL remains the Windows path, as the Java spec says); no deletion
of the archive branch, ever.
