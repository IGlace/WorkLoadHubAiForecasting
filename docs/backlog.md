# Backlog

Open items after version 1 (plans 1 to 4 and the deferred-items hardening pass). Nothing here blocks using
version 1. Dated 2026-09-04; update this file when an item lands.

The owner walked the whole list on 2026-09-04 and decided each item. The decision is recorded next to the
item, so a later reader knows whether something is waiting, accepted as it is, or deliberately dropped.

State on 2026-09-04: `main` is at `4fba18a`; `dev` carries the team-page live progress on top of it, so that
one item is landed but not yet released. "Upcoming events" was brainstormed on 2026-09-04 and closed as
already covered, so the next item to start is the Playwright smoke path, unless the owner first decides the
"Ask Copilot" button question below.

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

## Approved, not yet built

Ordered roughly by value. Each of these has an owner decision behind it.

- **The team page's "Ask Copilot" button is disabled for every run at once** (raised by the review of the
  team-page live-progress work, 2026-09-04, owner has not yet decided). The button is disabled from the
  single narrating-run state, so while run A's narration is in flight, run B's button is disabled too even
  though nothing is running for B. It predates the live-progress work and was left alone by it. Scoping the
  disable to the displayed run is a one-line change plus a test; the question for the owner is whether two
  narrations should be allowed to run at once at all, which is a service question, not a button question.
- **One Playwright smoke path**: launch the packaged app, confirm the window opens and the service handshake
  succeeds. Not a full end-to-end suite; that stays deferred.
- **Accuracy evaluation, design first.** Write down what has to be stored and compared (forecast versus actual
  per member per week) and make sure version 1 already records it, because it cannot be recovered
  retroactively. Build the comparison once real weeks have passed.
- **WorkloadHub integration, requirements first.** Document exactly what an importer needs from WorkloadHub
  (fields per task, per member, per project) so the owner can check whether its API or export supplies them.
  No importer code until that is known.

## Verification on Windows (owner)

To be run once the approved work above has landed, so the owner verifies the final state rather than an
intermediate one.

- Build the installer with `pwsh scripts/build-installer.ps1` and walk the first-run checklist in
  `installer/README.md`: per-user install, Copilot sign-in, one run with the AI narrative, toast, tray, quit
  without an orphaned `whf.exe`, uninstall.
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

- `@vitejs/plugin-react` 6 requires Vite 8, which electron-vite does not support. Rechecked 2026-09-04:
  electron-vite is still 5.0.0 with a Vite peer of `^5 || ^6 || ^7`, and plugin-react 6.1.1 still requires
  `^8`, so the pin at plugin-react 5 stands. Revisit when electron-vite supports Vite 8.
- Project-phase features in the effort model (cycle time versus deadline proximity), deferred from plan 1.
  Waiting on real data: on generated data the backtest would measure the generator's own assumptions rather
  than the team's behaviour.
- Playwright end-to-end tests beyond the single smoke path (spec section 10: "later").
