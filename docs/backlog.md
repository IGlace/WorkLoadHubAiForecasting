# Backlog

Open items after version 1 (plans 1 to 4 and the deferred-items hardening pass). Nothing here blocks using
version 1. Dated 2026-09-04; update this file when an item lands.

The owner walked the whole list on 2026-09-04 and decided each item. The decision is recorded next to the
item, so a later reader knows whether something is waiting, accepted as it is, or deliberately dropped.

## Approved, not yet built

Ordered roughly by value. Each of these has an owner decision behind it.

- **Upcoming events** (new, agreed 2026-09-04). An event has a title, a date range, a scope (the whole team or
  named members), an effect type (reduces capacity or adds demand) and optional hours. With hours,
  deterministic code applies the effect and shows it as a named line in the forecast; without hours it is
  context for the narrative only. This closes the gap where a known future event could not be expressed.
- **Full French parity.** The app must support English and French completely, so the user can choose freely
  between them. Today the `fr` dictionary in `app/src/renderer/src/i18n.ts` covers navigation, common labels
  and notifications only, and pages fall back to English silently. The narrative language already works.
  No third language: `en` and `fr` only.
- **Local checks in place of CI.** The remote was removed on 2026-09-04, so GitHub Actions can never run.
  Replace it with `scripts/check.ps1` plus versioned hooks in `scripts/hooks/`, activated by
  `git config core.hooksPath scripts/hooks`:
  - fast gate on every commit: ruff, `pytest -m "not slow"`, app lint, typecheck and tests;
  - full gate on every merge into `main` (`post-merge`, because a fast-forward merge creates no commit and
    would never fire `post-commit`): both pytest suites, the whole app suite, and the installer build.
- **Live progress for the Copilot narrative** on the Run page; today the step shows only "Asking Copilot…",
  which is where a user assumes the app has hung.
- **Small polish**, all approved:
  - Settings store (`app/src/main/settings-store.ts`): delete the temp file when the final rename fails, so a
    locked file on Windows does not leave orphans.
  - The IPC settings validator (`app/src/main/ipc.ts`) duplicates `SettingsStore.sanitize`; merge into one check.
  - Narrative route (`service/src/whf/api.py`, `create_narrative`): rename the shadowed `body` local.
  - Frozen-service smoke test (`installer/pyinstaller/smoke_frozen.py`): unit tests for the "last JSON line" parser.
  - Add a test for the quit path when the last window closes with "keep running in the tray" off
    (`app/src/main/index.ts`, `window-all-closed`).
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
