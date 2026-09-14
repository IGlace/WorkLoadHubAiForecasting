# Backlog

Open items after version 1 (plans 1 to 4 and the deferred-items hardening pass). Nothing here blocks using
version 1. Dated 2026-09-04, last updated 2026-09-13; update this file when an item lands.

Backlog items that name the desktop app, the installer, the Python service or Windows verification apply to
the archived first version only — including the whole "Verification on Windows" section below. Their
commands (`pwsh scripts/build-installer.ps1`, `uv run pytest tests/test_ai_live.py` in `service/`,
`whf eval`) exist only in a checkout of the tag `archive/python-desktop-v1`:
`git worktree add ../whf-archive archive/python-desktop-v1`. Nothing there is on `dev`, and none of it is
waiting on anyone; the frozen version receives no new features.

The owner walked the whole list on 2026-09-04 and decided each item. The decision is recorded next to the
item, so a later reader knows whether something is waiting, accepted as it is, or deliberately dropped.

State on 2026-09-14: the `origin` remote is back and `dev` is pushed to it, but CI is paused at the owner's
request until the work has progressed much further: `.github/workflows/ci.yml` keeps only
`workflow_dispatch`, so no push starts a run and the gate remains `bash scripts/check.sh` run by hand in
the development container.

State on 2026-09-12: the owner deleted the `origin` remote, so this clone was the only copy. Nothing was
pushed and `.github/workflows/ci.yml` could not fire. The CLI was trimmed the same day to the five commands
that build and score an experiment database.

State on 2026-09-06: the owner's local work (39 commits) was verified and pushed back to the GitHub remote, `dev` and `main` level again (the remote is gone as of 2026-09-12, above).

State on 2026-09-05: released — `scripts/release.ps1` fast-forwarded `main` to `dev` at `0f3eb03` on
2026-09-04, so both branches carry the team-page live progress, the "Ask Copilot" button scoping and the
narrative-envelope fix. "Upcoming events" was brainstormed on 2026-09-04 and closed as already covered, and
the Playwright smoke path was deferred the same day. The review of the button-scoping change turned up a
serious pre-existing defect — the app could not render a narrative the service returns — which has since
been fixed; see "Landed" below. The owner began the Windows verification on 2026-09-05 and hit the empty
first install straight away; the installer now seeds the data itself, see "Landed" below.

## Landed

- **`forecast-cli` removed; evaluation is a module feature** (2026-09-12): the trim earlier that day left five
  commands that build and score an experiment database — 408 lines of flag parsing over `forecast-core`
  classes that are all public, carried by a Spring Boot module whose repackaged jar could not even be put on a
  classpath. They are now one file, `server/tools/Experiment.java`, run by `server/tools/experiment.sh`
  through Java 21's single-file source launcher, the way `server/examples/HostExample.java` already runs;
  `server/tools/core-classpath.sh` is the compile-and-resolve step both share. `ForecastService` gained
  `evaluate(EvalConfig)`, so `eval` measures the engine a host gets rather than a copy assembled for the
  occasion, and `EvalResult` now carries the config as resolved and a fingerprint of the data. `CliSmokeTest`
  became `ExperimentFlowTest` in `forecast-core`, which runs the driver as a subprocess, so the file is
  covered by the gate instead of by nothing. Design section 12, third amendment.

- **The Python service and the desktop app archived** (2026-09-10): `archive/python-desktop-v1` held
  `service/`, `app/`, `installer/`, the notebook, the desktop scripts and hooks, and the
  Python and desktop skills and agents; `dev` and `main` carry the Java module and the documentation.
  The branch of that name lived only on the remote deleted on 2026-09-12, so `archive/python-desktop-v1` is
  now a **tag** at `3c6f836`, the last commit on `dev` that still holds those trees (`d985a56` removed them).
  It differs from the old branch tip `5c69bf6` only in that `service/tests/test_parity_compare.py` had by then
  moved to `server/tools/tests/` to stay in the live repository; the `whf` CLI the parity procedure runs is
  identical. The parity
  procedure runs the Python side from a checkout of the archive (`server/tools/parity.sh ... ARCHIVE_DIR`), and
  the parity gate's test is the one Python test left. Spec
  `docs/superpowers/specs/2026-09-10-python-desktop-archival-design.md`.

- **Planned work and likely work, designed and locked, waiting for the real export** (2026-09-07): the owner
  asked whether Copilot could predict the tasks a member will be assigned. Decided: no language model will
  ever produce a forecast number (closed for good); instead, tasks that exist before they are assigned are
  allocated to members by a deterministic, backtestable share rule and become a third demand component,
  and the narrative gains a qualitative "what is likely to land" section written from new facts. Spec
  `docs/superpowers/specs/2026-09-07-planned-work-and-likely-work-design.md`, plan
  `docs/superpowers/plans/2026-09-07-planned-work-and-likely-work.md`. Implementation starts once the export is
  in the database and `whf data profile` (plan task 1) has shown whether the organisation keeps a backlog;
  the plan marks which tasks run in each case. The harness then decides, on the real data, whether the
  planned component stays on.
- **The live Copilot view, what a run cost, and the "run again" wording** (2026-09-07): a narration used
  to be a several-minute wait behind one progress line. The session now runs with `streaming=True` and
  forwards what it receives to the Run and team pages as it arrives: the model's intent and reasoning under
  "What Copilot is thinking", the answer under "The answer as it is written", and the facts it read beside
  them. Every narration also records what it cost, read from `session.rpc.usage.get_metrics()` when the
  session can answer and from the streamed `assistant.usage` events when it cannot; the stored keys are
  `input_tokens, output_tokens, cache_read_tokens, reasoning_tokens, requests, premium_requests, ai_credits,
  usd, api_seconds, models, source`, and a value nobody reported stays null rather than becoming a
  plausible-looking zero. One AI credit is one US cent per GitHub's pricing page, so the money shown is the
  credits divided by a hundred and nothing more. Settings shows the account's remaining monthly quota from
  `client.rpc.account.get_quota(...)`, and the Run page button now says "Running…" while a forecast runs and
  "Run another forecast" afterwards, with a line saying the previous run is kept. Caveat to carry into the
  Windows verification: `session.usage.getMetrics` and `account.getQuota` are experimental SDK calls, proven
  here only against fakes, so the first live run by the owner is what confirms they answer at all — both
  failure paths are already handled (the cost falls back to the events, the quota is simply not shown).
  Plan: `docs/superpowers/plans/2026-09-07-copilot-live-and-cost.md`.
  - Deferred: the tool-step window. A tool call now costs two of the 50 steps the store keeps (start and
    completion), so the live list holds half the history it did; and once a start has rolled out of the
    window, a later completion of a same-named tool can be matched to the wrong start and mis-labelled.
  - Deferred: the partial-token quiet paths. The cost line shows tokens only when both `input_tokens` and
    `output_tokens` are known, so a source that reported one of the two says nothing about tokens at all.
  - Deferred: the `run.live.*` strings are asserted in English only; the French wording exists and is
    checked for existence by the parity test, but no test reads the live panel in French.
  - Accepted as it is: the live panels auto-scroll to the newest text, so a reader cannot scroll back
    through what has already gone by while the answer is still being written.
  - Deferred: `keep_chars=0` disables the bound on the stored live text instead of storing nothing, which is
    the reading a caller would expect from a zero.
  - Deferred: a turn that ends without a final `assistant.message` (read from the deltas instead) records no
    model name in the audit row, because the name only arrives on the final message; and a retried
    narration whose first attempt ended with a message but whose second sent deltas only would keep the
    first attempt's text. The SDK produces neither shape as far as its documentation and the fakes show.

- **Local checks in place of CI** (2026-09-04): `scripts/check.ps1` (fast, `-Full`, `-Package`),
  `scripts/release.ps1` (gate then fast-forward `main`) and the hooks in `scripts/hooks/`, activated by
  `git config core.hooksPath scripts/hooks`. A committed `app/.npmrc` was needed alongside it: the
  machine-wide registry is a corporate Artifactory with expired credentials, so `npm ci` failed with E401
  and took the installer build with it. Plan: `docs/superpowers/plans/2026-09-04-local-checks.md`.
  See the Toolchain section of `CLAUDE.md`.
- **Full French parity** (2026-09-04): every one of the roughly 140 renderer keys is translated, and
  `untranslatedKeys` in `app/src/renderer/src/i18n.ts` is asserted empty so a new English key cannot land
  alone. Three places had been shipping English regardless of the setting and were fixed with it: the tray
  menu, the Copilot sign-in messages and the service-failure banner. Those come from the main process or the
  Python service, so they now travel as language-independent codes (`CopilotStatus.code`, `LoginResult.code`)
  and the window supplies the wording; raw English detail from the CLI stays beside it as technical detail.
- **The five small polish items** (2026-09-04): the settings store now deletes its temp file when the final
  rename fails, so a locked file on Windows leaves no orphans; the IPC settings validator and
  `SettingsStore.sanitize` read one shared table of per-key rules instead of two copies that could drift;
  the narrative route no longer shadows its `body` parameter; the frozen-service smoke test's two parsers
  (`_last_json_object`, `_valid_handshake`) have unit tests, which showed one branch to be unreachable and
  it was removed; and `window-all-closed` is an extracted `onWindowAllClosed` so the quit path is tested.
- **Live progress for the Copilot narrative** (2026-09-04): the Run page used to show "Asking Copilot…" for
  the whole narration, which is exactly where a user assumes the app has hung. The narrator now emits a coded
  event per step (`starting`, `session`, `asking` with the attempt number, `tool` with the tool name,
  `checking`), a bounded in-memory store keeps the last steps of the last few runs, `GET
  /runs/{run_id}/narrative/progress` serves them, and the Run page polls that once a second and shows the
  step with an elapsed-seconds counter. The service sends codes only; the window supplies the English and
  French wording, like `CopilotStatus.code` before it. Polling rather than streaming because both routes are
  synchronous `def`s that FastAPI hands to its threadpool, so the progress GET runs on a different worker
  while the narration holds one — concurrent with no new channel to build. Plan:
  `docs/superpowers/plans/2026-09-04-live-copilot-progress.md`.
- **Live progress on the team page too** (2026-09-04): `TeamResult.tsx` starts the same multi-minute
  narration as the Run page and used to show nothing while it ran. The Run page's polling effect is now a
  `useNarrativeProgress(runId)` hook in `app/src/renderer/src/narrative-progress.ts` that both pages use, so
  the two non-obvious behaviours live in one place: an empty poll keeps the last known step, and a failed
  poll does not fail the run. The team page passes the id `narrate()` was called for rather than a bare busy
  flag — with a bare flag, navigating to another run mid-narration fed the new id to the hook and showed the
  first run's progress under the second run's identity. The hook resets its step and elapsed counter whenever
  its `runId` changes, which is why no separate reset call is needed at either call site.
- **The fast gate made fast enough to sit in front of every commit** (2026-09-04): it started at 8.4
  minutes, which nobody would have kept. The AI tests each recomputed a ten-second forecast, so that now
  runs once per session and is handed out as a copy, and pytest runs on six xdist workers. 8.4 minutes
  down to about 2.5.
- **The team page's "Ask Copilot" button scoped to the run on screen** (2026-09-04): it was disabled from a
  single narrating-run state, and React Router reuses the page across `/runs/:runId` navigations, so
  narrating run A disabled run B's button and showed A's progress under B's heading. The owner chose to scope
  it to the displayed run, accepting concurrent narrations — the service was already ready for them, since
  each gets its own Copilot client, session and connection and `ProgressStore` is keyed by run id. The page
  now tracks a *set* of in-flight run ids rather than one: with a single id, enabling B's button meant
  clicking it overwrote A's id, and A's completion then cleared the state out from under B. One narration
  finishing no longer drops tracking of another, which is the test that fails against any single-id version.
  Accepted with it: navigating away from a narrating run and back restarts its elapsed counter at zero,
  because the progress hook resets whenever its run id changes; the step label recovers on the next poll.
- **The app could not render a narrative that came back from the service** (found by the review of the
  "Ask Copilot" scoping work, 2026-09-04; fixed the same day). `narrate_run` stores the whole outcome
  envelope in `run_narratives` — `{**asdict(NarrativeOutcome), generated_at}`, so top-level `status`,
  `narrative`, `error`, `reason`, `raw_text`, `verification`, `model`, `usage`, `attempts`, `tool_calls` — on
  purpose, because the exact reply Copilot gave and how it was verified are the audit trail (the exact facts
  sent live separately, in `run_facts`). But `load_run`
  returned that envelope verbatim as `RunDetail.narrative`, while the app declares that field as the bare
  `Narrative` (`app/src/shared/types.ts`): `run_summary`, `members`, `team_risks`, `rebalancing`,
  `suggested_adjustments`, `model_notes`. Nothing transformed it in between, so on real service data
  `detail.narrative` was truthy with no `members`, and `TeamResult.tsx`'s `narrative?.members.map(...)`
  threw — the optional chain guards the object, not the field. `MemberDetail.tsx` had the same line. Worse
  for the button: a *failed* narration also wrote a `run_narratives` row, so the envelope was truthy and
  `{!narrative && <button>}` hid "Ask Copilot" for precisely the runs that needed a retry. Every renderer
  test used a hand-written fixture in the bare shape and every service test asserted the envelope shape, so
  the suites agreed with themselves and nothing caught the seam. Fixed by unwrapping in `load_run` only: the
  stored `run_narratives` row is untouched (still the full envelope, for audit), and the payload's
  `narrative` key becomes the inner document, or `None` for all three of no row, a row whose narrative is
  `null` (a failed narration — this is what restores the retry button), and a row that predates the change
  or is otherwise missing the key. No app file changed; the app's types were already correct. One visible
  side effect: `whf runs show --json` and `whf export --format json` dump the whole `load_run` payload, so
  they now also drop the envelope (including the model's raw unparsed reply) from their output — intended,
  since that raw reply was never meant to leave the database over these paths either. A contract test now
  asserts that `load_run(...)["narrative"]` has exactly those six keys, and it builds its narrative through
  the real `CopilotNarrator` path rather than a hand-written fixture — deliberately, because a fixture would
  only have proved its own shape. Binding it to the real producer is what makes it guard both halves of the
  seam: an envelope leaking through `load_run` again, and a seventh field added to `whf.ai.schema.Narrative`
  that the app's interface does not declare. Both were confirmed to fail the test before it was accepted.

- **A fresh install has no data, and the first-run checklist did not say so** (found by the owner on
  2026-09-05, on the first real walkthrough of the packaged app; documented the same day). Version 1
  forecasts from generated dummy data, but nothing seeds it: the installer ships only the empty schema, the
  app creates the database on first launch and leaves it empty, there is no API route for generation and no
  action for it in the interface. `whf data generate` is the only path, and `installer/README.md` never
  mentioned it — so the walkthrough died at "choose your profile", with every picker empty and no team to
  forecast. The bundled `whf.exe` under `resources/service/whf` runs it and defaults to the same
  `%LOCALAPPDATA%\WorkloadHubForecast\whf.db` the app uses, so no `--db` argument is needed; it produced 3
  departments, 8 teams, 48 members, 42 projects and 6,522 tasks on the owner's machine, which incidentally
  is the first evidence that the frozen PyInstaller build runs the generator correctly. Two traps the new
  step calls out: the app must be restarted afterwards, because it reads the database at startup and
  otherwise keeps showing empty lists, and `data generate` *replaces* everything, so running it after a
  forecast destroys the stored runs and their narratives. Fixed as documentation first (a new step 2 in the
  first-run checklist), then properly on 2026-09-06 at the owner's request: the setup file now loads the sample
  data itself. `whf data generate --if-empty` generates only when the database holds no departments, and a
  custom NSIS step (`installer/nsis/installer.nsh`, run by electron-builder's `customInstall` hook after the
  files are copied and before the finish page launches the app) calls it through the bundled `whf.exe`. So a
  fresh install starts populated, an upgrade or a reinstall keeps its data, runs and narratives, and a seeding
  failure shows a message box with the manual command rather than failing the install. The
  frozen-service smoke test exercises both paths of `--if-empty`. Still open, lower priority now: an in-app
  "Load sample data" action in Settings (guarded by a confirmation because it replaces everything) for someone
  who wants to reset to fresh data without reinstalling.

- **Evaluation harness and the Chronos-2 candidate** (2026-09-06): `whf eval` backtests the arrival models —
  seasonal_naive, tsb, gbm and the new chronos2, a pretrained time-series foundation model run zero-shot with
  the holiday, vacation and project covariates — across origins and horizons, and writes `scores.csv`,
  `demand.csv` and `summary.md`. The installer bundles CPU-only PyTorch and the pinned Chronos-2 weights
  (`installer/pyinstaller/download_weights.py` prefetches them at build time; the app never downloads weights
  or resolves a CUDA build at run time). Pending: which candidates become the default champion set is a
  decision for the real data, not this harness's dummy-data run. Spec:
  `docs/superpowers/specs/2026-09-06-forecast-evaluation-and-chronos2-design.md`.
  - First harness run on generated data (seed 42, twelve months): `docs/eval/2026-09-07-generated/summary.md`.
    Winner on generated data: gbm, MASE 0.821 at horizon 1; demand MAE 8.778 h per member-week; overload
    precision 0.000 and recall undefined, because the generated truth never exceeds capacity in any
    replayed week (maximum 40.0 h against 44 h), so every predicted overload week (292 across the four
    models) is a false positive and recall has no positives to count — the overload metrics are
    uninformative on generated data and only the real-data run can judge them.
    Took 31.6 minutes end to end, over the fifteen-minute budget in the brief; kept because it completed
    with exit 0 and all four models scored. Not a decision: the generator wrote the truth. The real-data
    run next week decides.
  - Deferred: gap weeks between the training cut-off and the forecast origin carry forward-filled covariates
    in the Chronos-2 context (up to three weeks stale for the deadline-proximity covariate).
  - Deferred: the fine-tuned harness candidate loads the weights twice (shared availability check, then a
    private copy).
  - Deferred: the app does not surface `model.unavailable` in the interface. The narrative says when a
    candidate (typically chronos2, with no weights on the machine) was skipped and why, but the run page
    shows only the champion, so a user reading the screen alone cannot tell that a model was missing.

## Approved, not yet built

Ordered roughly by value. Each of these has an owner decision behind it.

- **A narration that fails for a run you have navigated away from says nothing** (raised by the same review).
  `narrate()`'s error path is guarded on the displayed run, so if run A's `POST` throws while run B is on
  screen, the error is dropped and A's button simply re-enables as though nothing happened. The guard is
  still right — without it, A's failure was displayed under B's heading, which is worse. The correct shape
  is a run-keyed error map consulted alongside the fetched detail. Note the narrower blast radius: a
  narration that *completes* as failed returns 200 and persists `ai_status`, so only a thrown request is
  silent.
- **Accuracy evaluation.** Built (2026-09-11, `docs/superpowers/specs/2026-09-11-accuracy-evaluation-design.md`):
  `ForecastService.accuracy` compares `forecast_current_days` and each run's day rows with the logged hours. Left open: a history of evaluations (a table and a chart) if the owner wants to
  see accuracy evolve; results are recomputed on every call today.
- **WorkloadHub integration.** The module's side is designed and demonstrated
  (`docs/superpowers/specs/2026-09-11-host-integration-design.md`, the sample host); what remains is the
  server's own code (its endpoints, pages and settings), written in the WorkloadHub repository against
  `server/README.md`, "Integrating from the server's own code".

## Verification on Windows (owner)

To be run once the approved work above has landed, so the owner verifies the final state rather than an
intermediate one. **In progress**, started 2026-09-05 against the 0.1.0 installer built from `0f3eb03`.

- Build the installer with `pwsh scripts/build-installer.ps1` and walk the first-run checklist in
  `installer/README.md`: per-user install, dummy-data seeding, Copilot sign-in, one run with the AI
  narrative, toast, tray, quit without an orphaned `whf.exe`, uninstall.
- Confirm the team page renders a real narrative and that "Ask Copilot" comes back after a failed narration.
  Both shipped in `0f3eb03` reasoned out against tests only; neither has ever run against a signed-in
  Copilot seat, and the envelope defect they fixed survived precisely because the app and service suites
  each asserted their own side of the seam.
- Run the live Copilot test (`WHF_COPILOT_LIVE=1 uv run pytest tests/test_ai_live.py -v -s` in `service/`) and
  confirm a `whf-*` skill is loaded in the session. This is also how skill loading in the frozen build gets
  proved: the owner chose the manual check over a frozen-environment substitute test.
- Confirm the "Start with Windows" login item passes `--hidden` through the NSIS build.

## Accepted as they are

Decided 2026-09-04; no work planned. Recorded so they are not re-litigated.

- **Upcoming events — closed as already covered** (brainstormed and decided 2026-09-04). Requirement C5 in
  `docs/requirements/requirements-v1.md` asked for upcoming events entered before a run. The owner decided
  events are limited to vacations and official country holidays, and that the upstream WorkloadHub
  application guarantees those never overlap. Both are already fully modelled: the `holidays` and
  `vacations` tables, `GET/POST/DELETE /vacations` in `service/src/whf/api.py`, the form on the Time off
  page, and the generator, which produces exactly these two. Capacity subtracts them as a set union of
  off-days (`pipeline.py`), so an overlap is absorbed rather than double-counted even if one existed. No
  work planned. Four things the model still cannot express, deliberately out of scope: a team-wide dated
  absence as one entry rather than one vacation row per member; demand-adding events, for work a leader
  knows is coming but that has no task yet; free-text narrative-only context, though project starts and
  ends inside the forecast window already reach the narrative as facts; and partial-day reductions, which
  need a weekly-hours override on the Capacity page instead. Only the demand-adding gap is real
  arithmetic, and whether a leader's advance knowledge is accurate enough to feed it is a question for
  real WorkloadHub data, not dummy data.
- **Installer size, about 375 MB** (Copilot CLI about 170 MB, scikit-learn plus scipy about 120 MB). Accepted:
  both large pieces are load-bearing, and the size is unremarkable for a desktop app with a bundled runtime.
- **Skill loading in the frozen build cannot be proved automatically**, because it sits behind Copilot sign-in.
  Accepted; proved by hand in the live Copilot test above, with no substitute unit test.
- **Distribution stays a shared installer file.** No auto-update feed (there is no remote to host one) and no
  code signing for now, which would be a certificate procurement question rather than a code change.

## Waiting on something specific

- Run `whf eval` on the real export and commit the result under `docs/eval/`; decide the default candidates
  from it.
- `@vitejs/plugin-react` 6 requires Vite 8, which electron-vite does not support. Rechecked 2026-09-04:
  electron-vite is still 5.0.0 with a Vite peer of `^5 || ^6 || ^7`, and plugin-react 6.1.1 still requires
  `^8`, so the pin at plugin-react 5 stands. Revisit when electron-vite supports Vite 8.
- Project-phase features in the effort model (cycle time versus deadline proximity), deferred from plan 1.
  Waiting on real data: on generated data the backtest would measure the generator's own assumptions rather
  than the team's behaviour.
- **Playwright, all of it, including the single smoke path** (deferred by the owner on 2026-09-04; it had been
  approved earlier the same day). The smoke path would launch the packaged app and confirm the window opens
  and the service handshake succeeds. It is deferred rather than dropped because the same ground is covered by
  hand for now: the first-run checklist in `installer/README.md` walks the packaged app, and the frozen-service
  smoke test already checks the handshake below the UI. Revisit when the app is installed on more than one
  machine, or when the manual checklist has to be run often enough to be worth automating. Everything beyond
  the smoke path stays deferred as it was (spec section 10: "later").

## Java migration

- **The evaluation harness applies a shorter history gate than a run does (2026-09-14). Important; go back to
  this.** `eval/Harness` sets `maxHorizon = windows` and passes it to `Backtest.origins`, while a real forecast
  uses `Horizon.maxHorizon(origin, windows)`, which is `windows + 1`. With `minHistoryWeeks(maxHorizon) =
  10 + maxHorizon`, the harness admits a team on twelve weeks of history where a run demands thirteen. Each
  context is internally coherent — the harness scores horizons `1..windows` and a run scores `1..windows + 1`,
  so each gate matches the hold-out it actually uses — which is why this is a design question and not a bug to
  patch blindly. The question is whether `evaluate()` should score the same population of teams that a run will
  forecast. As it stands it can report an accuracy figure for a team production then refuses to score, and the
  two numbers a leader sees come from different admission rules. Decide deliberately: either the harness takes
  the run's gate (one population, at the cost of discarding a team the harness could legitimately have scored),
  or the divergence is documented as intended and `evaluate()` says which gate it used. Landed unresolved with
  task 8 of `docs/superpowers/plans/2026-09-14-weekly-hours-forecast.md`.

- **Weekly hours forecast (2026-09-13, reviewed 2026-09-14).** Spec
  `docs/superpowers/specs/2026-09-13-weekly-hours-forecast-design.md`, **reviewed and ready for a plan, not
  implemented**. The review's seven rulings are its section 18: the history gate scales as `10 + maxHorizon`;
  `backlog_excess_hrs` is cumulative over the windows so far rather than per window; the per-horizon feature
  columns are sized from the window count, so `Features.HORIZONS` stops being the constant `{1,2,3}` and a
  matrix is tied to the count it was built with; no hour is ever logged on a weekend, holiday or absence day,
  so the target needs no day filter; a second pressure fact `due_excess_hrs` and a `deadline_pressed` list are
  added, because work that must finish inside a window is what makes a leader rebalance and Copilot can only
  state the gap if the gap is itself a fact; overtime is recordable, so overload survives the retarget and the
  spec's largest stated cost was wrong; the experiment driver gets `--windows N`; the seed generator changes
  with it, because a seeded member cannot log past 8 hours a day against a 40-hour capacity row, so overload
  would be unreachable on seeded data; the backlog figure is measured against predicted demand rather than
  capacity, since the forecast is an analysis a leader reads and the narrative catches what it misses; work
  already late becomes `overdue_hrs`, per member; and it stays one plan, with the task order in section 23.

  It supersedes the single-model simplification below and is the only document to implement from. The
  2026-09-13 rulings behind it: the booster's target becomes logged hours per member-week, so training and
  measurement are the same quantity; `EffortModel` and everything serving it are deleted, `HourPlacement` and the
  open/new/planned split with them; `PlannedWork` becomes a feature rather than an addend and
  `PlannedWork.allocate` goes; the window count becomes the property `whf.forecast.windows`, default 2, one to
  six, with `RunRequest` unchanged at `(teamId, requestedBy)`; a week's hours land on its weekdays by the
  member's logged-hours weekday shares, a new series computed from `time_logs`; `XgboostArrival` is renamed
  `XgboostHours`; the default weekly capacity becomes 44 h, as requirement C1 has always said. Accepted costs:
  the forecast inherits logging discipline; the demand breakdown and part of "likely work" are lost. The cost
  this list used to carry first — that overload would go quiet because logged hours are censored by what a
  person can work — was withdrawn at the review: logging is not capped, so overtime is in the data and
  overload still fires. Both points the spec left open are settled.
- **Single-model simplification (2026-09-12).** Spec
  `docs/superpowers/specs/2026-09-12-single-model-simplification-design.md`, **superseded on 2026-09-13 by the
  entry above**; approved but never implemented, so read it as history. Its rulings are carried forward there,
  with two changed: `TeamOutcome.plannedWorkEnabled` and the `planned_basis` fact go with `PlannedWork`, and
  `mean_actual_hours` becomes the mean of logged hours rather than arrival hours. Rulings: `SeasonalNaive` is deleted
  outright, not demoted to a MASE yardstick, so the backtest score becomes MAE in hours reported next to
  `mean_actual_hours` for scale; the parity procedure is retired entirely rather than retargeted, which empties
  `server/tools/tests/` and makes the gate `mvn verify` alone with no `uv` precondition; a team with too little
  history still forecasts, marked `confidence: thin_history` with no interval band, and only a booster that
  cannot train at all fails the run. Accepted costs: no scale-free score, no independent cross-check against the
  archived Python service, a weaker booster regression test, and no graceful degradation when the XGBoost native
  library is missing. The two-model question is closed; do not reopen it.
- **Over-engineering survey (2026-09-12).** Found while scoping the single-model change; each item needs its own
  brainstorm before any work. Ranked by size:
  1. **`seed/` ships inside the library.** 2,300 lines over 18 files, about a fifth of `forecast-core`'s main
     source, whose only consumers are `src/test` and `server/tools/Experiment.java`. `forecast-core/pom.xml` has
     no jar exclusions, so every host that adds the dependency also ships the synthetic-data generator
     (`WorkQueue` alone is 509 lines). Candidate fixes: a second Maven module, or `src/test` plus a test-jar on
     the driver's classpath. Structural, no behaviour changes. The largest single win.
  2. **Two ways to reach Copilot.** `whf.copilot.cli-path` switches `SdkCopilotGateway` from the in-process SDK
     runtime to a CLI subprocess (`SdkCopilotGateway:254-257`). This was a deliberate ruling of the narration
     plan (2026-09-10, section 15, recorded below), not an accident — but it is a second path that the hard rule
     "no `copilot login` on the server" points away from, and it is worth asking whether it still earns its keep.
  3. **`facts/Clustering`.** K-means, 10 restarts, 100 iterations, silhouette-based selection, 210 lines, to
     produce one integer per member used in exactly one place (`FactsBuilder:67`) so the narrative can say a
     member behaves like others (`whf-pattern-discovery/SKILL.md:19`). Steep machinery-to-output ratio; check
     whether a simpler rule reads the same to the narrator.
  4. **Speculative configuration.** `ForecastProperties` exposes ten settings; `web.base-path`,
     `flyway.enabled`, `copilot.model` (empty default) and `run-threads` configure variation nobody has
     requested.
  - Two further findings are **not** left here: they touch the same files as the single-model change, so the
    owner folded them into it (section 13 of its spec). The duplicated arithmetic (`round2` written three times,
    `mae` twice) collapses into a new neutral `com.workloadhub.forecast.Numbers` — not `eval/Metrics`, which
    would close a package cycle, since `eval` already depends on `backtest`. And planned work loses its
    per-request nullable `Boolean`, leaving `whf.planned-work.enabled` as the only control.
  - Checked and **not** a finding: the `web/` REST surface. It defaults to `whf.web.enabled=false` and the
    requirements never mention REST, but the host integration design records that the sample host drives the
    REST controller only (`2026-09-11-host-integration-design.md:16-17`), so it is the demonstrated integration
    path, not dead weight. Removing it is a product decision, not a cleanup.
- **Accuracy evaluation (2026-09-11).** Rulings: MASE over the rows with a prior-week log (count `maseN`); public holidays not scored, counted; the summary carries the log-lag and lead-pooling caveats. Follow-ups: report the number of distinct runs behind the lead rows (a weekly cadence confounds lead with weekday); a narrower truth load than `loadAll()` if profiling asks. no REST endpoint (the server calls the Java method); nothing
  stored; a weekday without a log counts as zero hours; MASE against the same weekday one week earlier; lead
  counts weekdays after the run day.
- **Host integration through the Java interface** (2026-09-11): the host enforces the v1 roles, narration runs
  on a host executor and the page polls a rotating bilingual label (Copilot's streamed text is no longer
  exposed), one server instance, runs interrupted by a restart are failed at start-up; the sample host in the
  tests is the reference. Spec `docs/superpowers/specs/2026-09-11-host-integration-design.md`. Rulings:
  `VIEWER` and `CENTER_MANAGER` view any team (read only); the one-at-a-time rule applies to skill team leaders
  only; a member of another team sees nothing.
- **Rolling forecast windows** (2026-09-10): a forecast starts the first weekday after the run day and covers
  ten weekdays in two windows of five, computed per day; each run upserts a per-day current forecast
  (`forecast_current_days`) that the accuracy evaluation will read; the caller no longer chooses the run day
  (REST refuses `asOf`; `--as-of` survives on the CLI's `eval` alone, for seeded experiments); the evaluation's demand level is per
  member-window while its arrival level, the parity gate, is unchanged. Spec
  `docs/superpowers/specs/2026-09-10-rolling-forecast-windows-design.md`.
- **Rolling windows, recorded follow-ups** (2026-09-10 final review): a `new_hours_after_window` fact for
  predicted arrival hours that spill past window 2 (planned work already reports `hours_after_window`); a
  guard so a run given a back-dated run day cannot overwrite arrived days in `forecast_current_days` on a live
  database (raised against the CLI's `run --as-of`, which the 2026-09-12 trim of the command line removed; the
  hazard moved rather than closed, to the sample host's fixed `Clock` and its `--as-of`, and is still scoped to
  seeded databases by convention only); `GET .../current` with only
  `from` given far ahead defaults `to` to today + 20 and answers `INVALID_REQUEST` (the 20-day default was
  written twice, in the controller and the CLI's `current`, until the 2026-09-12 trim removed the second);
  `Truth.realisedHours` (weekly) has no production caller;
  `JdbcRunStore.finish` re-reads the team id and re-sorts what `ORDER BY` already ordered; a window row's
  `absence_hours` comes from the day rows while its capacity may come from the application's week row;
  `CapacityRule`'s identity-keyed index is mutated from run threads (predates this branch) and two concurrent
  `finish` calls on SQLite contend for the write lock (a `busy_timeout` when SQLite is used for real).
- Seed realism to revisit after the first forecast on seeded data: per-department rhythms, the share of
  reopened and unlogged tasks, and whether department teams (people without a manager) should run their own
  forecast.
- Java module residuals parked at the close of the foundation-and-seed plan (2026-09-09): synthetic mode does
  not scrub `projects.key` (keys are department codes by construction); CI's Maven call lacks `-q`;
  `check.ps1` prints its skip line during step collection; the seed's log rows use quarter-hour slices (spec
  says 1 to 8 h); `Reference.covers` replaces rather than completes reference rows; the importer leaves
  autocommit off before close; CLI errors fall to picocli's default handler (moot since 2026-09-12: the
  driver that replaced the CLI handles them itself). The CLAUDE.md hard rule "no WSL"
  predated the server direction and was dropped on 2026-09-10 with the archival.
- Java module residuals parked at the close of the pipeline-core final review (2026-09-09):
  - The Python effort model's cycle-time regressor was not ported; `EffortModel.familyCycleDays` uses the
    hierarchy fallback only (member, then team × family, then team, then a global median), with no learned
    regression on top of it.
  - Three approximations the review accepted rather than fixed: `Truncation.at` carries a task's present-day
    `reopened_from_done` flag and its project's current `status` forward into every replayed origin week
    instead of rewinding them (alongside the due date and original estimate already read as of today; see
    `docs/design/2026-09-08-workloadhub-schema-and-feature-matrix.md` section 5's closing paragraph);
    `Truncation` also carries `team_capacity` forward untruncated, so a replayed origin sees today's realised
    allocations rather than what was known as of that origin — this reaches only the facts, never the backtest
    harness itself.
  - Planned-work's open- and new-hour placement falls back to the start week when a member has no present
    working day in the placement span, matching the Python `place_hours`. Whether that fallback (rather than,
    say, dropping the hours or pushing them past the absence) is the right behaviour is a design decision for
    the owner, not something the port should have silently inherited.
  - **Resolved** (fix wave, 2026-09-10): the Python parity check no longer compares apples to oranges. The
    WorkloadHub importer (`service/src/whf/data/workloadhub.py`) keeps fresh arrivals only by default
    (assignment lag under two days), so both harnesses forecast the same series; see `server/README.md`'s
    "Parity check" section.
- The parity gate is measured at the arrival level over all counted members (the harness's global backtest); a
  per-team gate needs per-team champions in `demand.csv`, not produced by either harness.
- Plan 3's remaining rulings (`docs/superpowers/plans/2026-09-10-java-run-eval-and-parity.md`), alongside the
  two recorded just above (the parity gate is global; the Python side matches Java by importing fresh arrivals
  only):
  3. Patterns drop `deadline_proximity_corr` (no project dates in the WorkloadHub schema) and key
     `cycle_days_by_type` by family; `similar_projects` is not built and `project_roles.phase` is always
     `active` — superseding `docs/superpowers/specs/2026-09-07-planned-work-and-likely-work-design.md`'s
     phases (`starting`|`active`|`ending`) and its `similar_projects` block.
  4. `forecast_member_weeks` has no `demand_hrs` column; demand is recomputed as `open + new + planned` on
     read.
  5. Progress lives in the JVM that runs the run: a run seen as `RUNNING` from another instance reports 50 %.
  6. A team without counted members fails the run on the row (`TEAM_NOT_FOUND`) rather than being refused at
     submission.
- Narration (`narrate`, `narrative`, `copilotStatus`) throws `COPILOT_UNAVAILABLE` until the Copilot plan lands.
- `whf import-workloadhub` sets no per-member capacity overrides, but it does set `capacity_defaults.weekly_hours`
  to 40 h (`DEFAULT_WEEKLY_HOURS` in the converter) to match the Java module's `whf.default-weekly-hours`
  default; the Python service's own stock default, outside this import path, is 44 h/week. Java also reads
  `user_capacity` for per-member overrides that the importer does not set; demand-level numbers between the two
  harnesses still differ for that reason and are not part of the gate.
- Rulings of the Copilot narration plan (2026-09-10), design
  `docs/superpowers/specs/2026-09-10-java-copilot-narration-design.md` section 15: the SDK runtime is in process
  (JNA, `~/.copilot/runtime-cache`), `whf.copilot.cli-path` switches to a subprocess; the six product skills are
  embedded in the system message although the SDK supports skill directories; tools use `ToolDefinition.from`,
  not `@CopilotTool`; `forecast_narratives` was recreated by V2 with `status`, `raw_text`, `error`, `attempts`,
  `tool_calls`; `narrate` returns a FAILED result (with its cost) instead of throwing `NARRATIVE_INVALID`;
  `DELETE /users/{id}/github-token` added; the rebalancing fit table of the 2026-09-07 design is not built
  (`task_keys` are checked against the source's open tasks only); new property `whf.work-dir`; the controller
  answers `NARRATIVE_NOT_FOUND` (404) for a missing narrative row; `narrate` without `WHF_TOKEN_KEY` exits 2
  (a usage error, spec section 13) where spec section 14's test list said 1 — section 14 has been corrected.
- Copilot narration residuals: the narration runs on the caller's thread (the host schedules it); the quota
  snapshot keys are whatever the account reports; no live SDK test in CI (manual procedure in `server/README.md`).
