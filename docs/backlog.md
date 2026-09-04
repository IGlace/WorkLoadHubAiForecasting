# Backlog

Open items after version 1 (plans 1 to 4 and the deferred-items hardening pass). Nothing here blocks using
version 1. Dated 2026-09-04; update this file when an item lands.

## Small polish

- Settings store (`app/src/main/settings-store.ts`): delete the temp file when the final rename fails, so a
  locked file on Windows does not leave orphans.
- French copy: the `fr` dictionary in `app/src/renderer/src/i18n.ts` covers navigation, common labels and
  notifications only; add the page keys (Capacity, Projects, Time off, Runs, Run, Team result, Member detail,
  Rebalancing, Settings) and do a full French pass.
- The IPC settings validator (`app/src/main/ipc.ts`) duplicates `SettingsStore.sanitize`; merge into one check.
- Narrative route (`service/src/whf/api.py`, `create_narrative`): rename the shadowed `body` local.
- Frozen-service smoke test (`installer/pyinstaller/smoke_frozen.py`): unit tests for the "last JSON line" parser.
- Add a test for the quit path when the last window closes with "keep running in the tray" off
  (`app/src/main/index.ts`, `window-all-closed`).

## Verification on Windows (owner)

- Build the installer with `pwsh scripts/build-installer.ps1` and walk the first-run checklist in
  `installer/README.md`: per-user install, Copilot sign-in, one run with the AI narrative, toast, tray, quit
  without an orphaned `whf.exe`, uninstall.
- Run the live Copilot test (`WHF_COPILOT_LIVE=1 uv run pytest tests/test_ai_live.py -v -s` in `service/`) and
  confirm a `whf-*` skill is loaded in the session.
- Confirm the "Start with Windows" login item passes `--hidden` through the NSIS build.

## Design and product decisions

- Installer size (about 375 MB): the Copilot CLI is about 170 MB and scikit-learn plus scipy about 120 MB.
  Options: a smaller CLI distribution, dropping scipy-dependent paths, or accepting the size.
- The frozen build cannot prove skill loading because it sits behind Copilot sign-in; the closest substitute is
  a frozen-environment unit test of `skill_directories()`.
- `@vitejs/plugin-react` 6 requires Vite 8, which electron-vite 5 does not support; revisit on the next
  electron-vite release.
- Project-phase features in the effort model (cycle time versus deadline proximity), deferred from plan 1.
- Live progress for the Copilot narrative on the Run page (today the step shows only "Asking Copilot…").
- Playwright end-to-end tests (spec section 10: "later").

## Future topics from the discovery answers

- Accuracy evaluation after the two forecast weeks pass, feeding back into the next forecast.
- WorkloadHub integration to replace the dummy data.
- Distribution beyond a shared installer file.
