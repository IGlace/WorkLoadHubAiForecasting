# WorkloadHub Forecast installer

Builds the single `.exe` an owner double-clicks to install the desktop app. No
administrator rights are needed anywhere in the build or the install.

## What it contains

- The Electron app (`app/`), packaged with electron-builder into an `asar`.
- The frozen forecast service, one-folder PyInstaller build, under
  `resources/service/whf` (`whf.exe` plus its Python runtime and dependencies:
  scikit-learn, pandas, holidays).
- The GitHub Copilot CLI, pre-downloaded at build time into
  `resources/service/whf/copilot-cli/`. The app points the service at it via
  `COPILOT_CLI_PATH`, so the CLI is never downloaded at install or run time.

`installer/electron-builder.yml` configures the packaging (`extraResources`,
icons, NSIS options); `installer/pyinstaller/whf.spec` configures the freeze.

## Building the installer

On Windows, with `uv`, Node 22 and `npm` on `PATH`:

```powershell
pwsh scripts/build-installer.ps1
```

This runs `scripts/build-service.ps1` (freeze the service, download the
Copilot CLI, run the frozen-service smoke test), then `npm ci`, `npm run
build` and `npm run build:win` in `app/`. The finished installer lands at
`dist/installer/WorkloadHub-Forecast-Setup-<version>.exe`.

The `package-windows` job in `.github/workflows/ci.yml` runs the same script
on `windows-latest` and uploads the `.exe` as a build artifact on every push,
pull request and manual dispatch.

## First-run checklist (for the owner)

1. Copy `WorkloadHub-Forecast-Setup-<version>.exe` to the target machine and
   run it. It installs per user (no administrator prompt) into the user's
   local app data and adds Desktop and Start Menu shortcuts.
2. Start the app from the Start Menu.
3. Settings → choose your profile (department, team, member).
4. Settings → "Sign in to GitHub Copilot". A PowerShell window opens showing
   a device code; this first sign-in must use the company GitHub account
   with the Copilot Enterprise seat. The Copilot CLI itself is bundled with
   the installer, so nothing downloads at this step — only the device-flow
   sign-in talks to GitHub.
5. Click "Check again" in Settings until it reports signed in.
6. Run → pick the team → "Run forecast" with the AI box ticked.
7. Expect an overload notification if any team member is over capacity.
8. Close the window and confirm the app is still running from its tray icon
   (closing the window hides it to the tray by default; Quit from the tray
   menu exits it).

## Data, logs and uninstall

- Database: `%LOCALAPPDATA%\WorkloadHubForecast\whf.db`
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
history across a reinstall, leave it.
