# WorkloadHub AI Forecasting

A Java 21 module for the WorkloadHub Spring Boot server (Linux; development on Windows, in the container
`scripts/devbox.sh` keeps running) that
forecasts each team member's work hours for the next two weeks from the team's task history in the
application's own PostgreSQL database, compares them with capacity (40 h/week default over the working days,
holidays, absences and the team's capacity plan), and uses each user's own GitHub Copilot seat to explain
patterns, warn about overload and suggest rebalancing. The host calls a Java interface or an optional REST
surface; a command line runs the same code on SQLite for experiments. The first version (a Windows desktop
app with a Python service) is archived at the tag `archive/python-desktop-v1`.

## Read these first

- `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`: the design of the module (database,
  seed generator, feature matrix, models, backtest, run pipeline, public API, CLI, testing). Its section 12
  describes the CLI as first designed; the 2026-09-12 trim cut it to five commands, so read that section as
  history and `server/README.md` for what exists.
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

## Where the project stands (2026-09-12)

Plans 1 to 4 landed on `dev` and `main` (foundation and seed; pipeline core; run, eval and parity; Copilot
narration), then the archival plan (`docs/superpowers/plans/2026-09-10-python-desktop-archival.md`). Then the
rolling forecast windows (`docs/superpowers/plans/2026-09-10-rolling-forecast-windows.md`): a run starts the
first weekday after the run day, covers ten weekdays in two windows, is computed per day and keeps a per-day
current forecast. Then the host integration design
(`docs/superpowers/specs/2026-09-11-host-integration-design.md`): progress labels, start-up reconciliation and
a Java-interface sample host; the server's own code is written in the WorkloadHub repository. Then the
accuracy evaluation (`docs/superpowers/specs/2026-09-11-accuracy-evaluation-design.md`): `accuracy(teamId,
from, to)` compares the forecasts made before each past weekday with the logged hours. Then, on 2026-09-12,
the CLI trim: `forecast-cli` keeps only the five commands that build and score an experiment database, and
everything a host does is shown by `server/examples/HostExample.java` instead.
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
- There is no remote. The owner deleted `origin` on 2026-09-12, so this clone is the only copy and nothing
  is pushed. Never propose a push, a pull request, or "CI will catch it"; the gate is run by hand in the
  development container. Do not re-add a remote unless the owner asks.
- English and French are both fully supported in the narrative; a user may switch freely. No third language.
  The CLI's own messages and help are English only, and it does not narrate; the sample host asks for one
  language or the other (`run-host-example.sh --lang en|fr`).
- The real export and any real-mode seed output stay outside the repository.

## Layout

```text
server/    Java 21 module: `forecast-core` (the library the host adds) and `forecast-cli` (command line for
           experiments: init-db, import, export, seed, eval — everything a host does goes through
           `ForecastService`, shown by `server/examples/HostExample.java`);
           `server/tools/` holds the parity scripts and their one Python test
docs/      requirements, research, design documents, specs, plans, evaluation results, reports, backlog
scripts/   `check.ps1` and `check.sh` (the gate), `release.sh` and `release.ps1` (gate, then fast-forward main to
           dev), `test-release.sh` (the release script's self-test), `devbox.sh` (the development
           container, built from `scripts/container/Containerfile`)
.claude/   skills, agents, hooks, settings
```

## Toolchain

- Java 21, Maven 3.9, Spring Boot 4.1, JUnit 6, jqwik, picocli, Flyway, XGBoost4J, copilot-sdk-java.
- The gate: `cd server && mvn -B -q verify` (about six minutes without Docker; PostgreSQL tests run through
  Testcontainers when Docker is present, else skip with a message) plus the parity tool's test,
  `uv run --python 3.11 --with pytest pytest server/tools/tests`. `bash scripts/check.sh` and
  `pwsh scripts/check.ps1` run both, and running one of them by hand is the only gate there is.
  `.github/workflows/ci.yml` describes the same steps but cannot fire with no remote; keep the three in
  step anyway, so it works again if a remote ever returns.
- With no JDK on the machine, work inside the development container: `bash scripts/devbox.sh shell`. It
  keeps an Ubuntu box running with the toolchain and this repository bind-mounted at `/work`, so the gate
  and the CLI run there exactly as on Linux, with the engine's socket mounted so the PostgreSQL tests
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
