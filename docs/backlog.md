# Backlog

Open items after version 1 (plans 1 to 4 and the deferred-items hardening pass). Nothing here blocks using
version 1. Dated 2026-09-04, last updated 2026-09-05; update this file when an item lands.

The owner walked the whole list on 2026-09-04 and decided each item. The decision is recorded next to the
item, so a later reader knows whether something is waiting, accepted as it is, or deliberately dropped.

State on 2026-09-06: the owner's local work (39 commits) was verified and pushed back to the GitHub remote, `dev` and `main` level again.

State on 2026-09-05: released — `scripts/release.ps1` fast-forwarded `main` to `dev` at `0f3eb03` on
2026-09-04, so both branches carry the team-page live progress, the "Ask Copilot" button scoping and the
narrative-envelope fix. "Upcoming events" was brainstormed on 2026-09-04 and closed as already covered, and
the Playwright smoke path was deferred the same day. The review of the button-scoping change turned up a
serious pre-existing defect — the app could not render a narrative the service returns — which has since
been fixed; see "Landed" below. The owner began the Windows verification on 2026-09-05 and hit the empty
first install straight away; the installer now seeds the data itself, see "Landed" below.

## Landed

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
  - Deferred: gap weeks between the training cut-off and the forecast origin carry forward-filled covariates
    in the Chronos-2 context (up to three weeks stale for the deadline-proximity covariate).
  - Deferred: the fine-tuned harness candidate loads the weights twice (shared availability check, then a
    private copy).
  - Deferred: a single-origin evaluation reports NaN interval coverage without saying why.
  - Deferred: `whf eval` level A `seconds` is per origin including skipped origins and repeated on every
    horizon row.

## Approved, not yet built

Ordered roughly by value. Each of these has an owner decision behind it.

- **A narration that fails for a run you have navigated away from says nothing** (raised by the same review).
  `narrate()`'s error path is guarded on the displayed run, so if run A's `POST` throws while run B is on
  screen, the error is dropped and A's button simply re-enables as though nothing happened. The guard is
  still right — without it, A's failure was displayed under B's heading, which is worse. The correct shape
  is a run-keyed error map consulted alongside the fetched detail. Note the narrower blast radius: a
  narration that *completes* as failed returns 200 and persists `ai_status`, so only a thrown request is
  silent.
- **Accuracy evaluation, design first.** Write down what has to be stored and compared (forecast versus actual
  per member per week) and make sure version 1 already records it, because it cannot be recovered
  retroactively. Build the comparison once real weeks have passed.
- **WorkloadHub integration, requirements first.** Document exactly what an importer needs from WorkloadHub
  (fields per task, per member, per project) so the owner can check whether its API or export supplies them.
  No importer code until that is known.

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
