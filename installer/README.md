# WorkloadHub Forecast installer

Builds the single `.exe` an owner double-clicks to install the desktop app. No
administrator rights are needed anywhere in the build or the install.

## What it contains

- The Electron app (`app/`), packaged with electron-builder into an `asar`.
- The frozen forecast service, one-folder PyInstaller build, under
  `resources/service/whf` (`whf.exe` plus its Python runtime and dependencies:
  scikit-learn, pandas, holidays, and the CPU-only build of PyTorch that
  Chronos-2 runs on).
- The GitHub Copilot CLI, pre-downloaded at build time into
  `resources/service/whf/copilot-cli/`. The app points the service at it via
  `COPILOT_CLI_PATH`, so the CLI is never downloaded at install or run time.
- The Chronos-2 forecasting model weights
  (`resources/service/whf/models/chronos-2`, about 480 MB, Apache 2.0),
  downloaded at build time at a pinned revision; the service loads them from
  there and never contacts the network for them.
- A custom NSIS step (`installer/nsis/installer.nsh`, wired in through `nsis.include`)
  that runs the bundled `whf.exe data generate --if-empty` at the end of the
  installation, so a fresh install starts with the sample data. `--if-empty`
  makes it a no-op when the database already holds data, so an upgrade or a
  reinstall keeps existing data, stored runs and narratives.

`installer/electron-builder.yml` configures the packaging (`extraResources`,
icons, NSIS options); `installer/pyinstaller/whf.spec` configures the freeze.

## Building the installer

On Windows, with `uv`, Node 22 and `npm` on `PATH`:

```powershell
pwsh scripts/build-installer.ps1
```

This runs `scripts/build-service.ps1` (freeze the service, download the
Copilot CLI and the Chronos-2 weights, run the frozen-service smoke test),
then `npm ci`, `npm run build` and `npm run build:win` in `app/`. The finished
installer lands at
`dist/installer/WorkloadHub-Forecast-Setup-<version>.exe`.

The first build downloads about 1.2 GB: the CPU-only PyTorch wheel that `uv
sync` installs (the CPU wheel index is pinned in `service/pyproject.toml`, so
no CUDA build is ever resolved) and the Chronos-2 weights. Both are cached
afterwards. Passing `-SkipModelDownload` to `scripts/build-service.ps1` leaves
the weights out; the installer still builds and works, but its forecasts use
the three classical models only and the app reports Chronos-2 as unavailable.

Expect the result to be much larger than before Chronos-2: the frozen service
folder grows from about 150 MB to about 1.5 GB with the weights bundled (about
982 MB with `-SkipModelDownload`, which is the CPU PyTorch runtime), so the
installer grows by roughly a gigabyte.

The `package-windows` job in `.github/workflows/ci.yml` runs the same script
on `windows-latest` and uploads the `.exe` as a build artifact on every push,
pull request and manual dispatch.

## First-run checklist (for the owner)

1. Copy `WorkloadHub-Forecast-Setup-<version>.exe` to the target machine and
   run it. By default it installs per user (no administrator prompt) into
   the user's local app data and adds Desktop and Start Menu shortcuts. Near
   the end, the progress text shows "Loading the sample data" while the
   installer runs the bundled service once to fill the database (3
   departments, 8 teams, 48 members, 12 months of task history, plus
   `answer_key.json` beside the database, the ground truth the backtest
   compares against). When the installer finishes it launches the app itself
   (`runAfterFinish: true` in `installer/electron-builder.yml`). Choosing
   "for all users" on the install-mode page, or running the setup itself as
   administrator, installs without loading the sample data: the app data
   folder is per user, so seeding there would fill the administrator's
   profile instead of yours. An information box then shows the manual
   command to run afterwards, as yourself.
2. If the installer showed a message box saying the sample data could not
   be loaded, run the bundled service by hand and restart the app
   afterwards (it reads the database at startup):

   ```powershell
   & "$env:LOCALAPPDATA\Programs\WorkloadHub Forecast\resources\service\whf\whf.exe" data generate
   ```

   Adjust the path if the install directory was changed
   (`allowToChangeInstallationDirectory: true`). Without `--if-empty`,
   `data generate` **replaces** all existing data, including stored runs
   and their narratives, so never run it after a forecast you want to keep.
3. Start the app from the Start Menu if the installer did not launch it.
4. Settings → choose your profile (department, team, member).
5. Settings → "Sign in to GitHub Copilot". A PowerShell window opens showing
   a device code; this first sign-in must use the company GitHub account
   with the Copilot Enterprise seat. The Copilot CLI itself is bundled with
   the installer, so nothing downloads at this step — only the device-flow
   sign-in talks to GitHub.
6. Click "Check again" in Settings until it reports signed in.
7. Run → pick the team → "Run forecast" with the AI box ticked.
8. Expect an overload notification if any team member is over capacity.
9. Close the window and confirm the app is still running from its tray icon
   (closing the window hides it to the tray by default; Quit from the tray
   menu exits it).

## Data, logs and uninstall

- Database: `%LOCALAPPDATA%\WorkloadHubForecast\whf.db`
- Answer key for the generated data:
  `%LOCALAPPDATA%\WorkloadHubForecast\answer_key.json`, written by the
  installer's seeding step on a fresh install and rewritten by every
  `data generate` so it always describes the data currently in the database
- App settings: `%LOCALAPPDATA%\WorkloadHubForecast\app\settings.json`
- Logs: `%LOCALAPPDATA%\WorkloadHubForecast\logs\app.log` (rotates at 1 MB,
  keeps 5 files)

Enable "start with Windows" in Settings if the owner wants the app to launch
automatically at login; it is off by default.

Uninstalling the app (Windows "Add or remove programs") removes the
installed program files but, by design (`deleteAppDataOnUninstall: false` in
`installer/electron-builder.yml`), leaves the
`%LOCALAPPDATA%\WorkloadHubForecast` folder — database, settings and logs —
in place. Delete that folder by hand for a fully clean removal, or to keep
history across a reinstall, leave it. Because the folder survives,
reinstalling over it keeps the data: the installer's seeding step sees a
populated database and does nothing.
