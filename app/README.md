# WorkloadHub Forecast desktop app

Electron + React front end for the forecast service in `../service`.

## Develop (PowerShell)

```powershell
cd app
npm install
npm run dev          # starts Vite for the renderer and Electron; the main process starts `uv run whf serve` from ../service
npm test             # vitest: main-process logic (node) and renderer components (jsdom)
npm run lint; npm run typecheck
npm run build        # electron-vite build into out/
```

Set `WHF_SERVICE_COMMAND` to override how the service is started, as a JSON array, for example
`$env:WHF_SERVICE_COMMAND = '["C:\\path\\whf.exe","serve"]'`.

## Package

```powershell
npm run pack:dir            # unpacked electron-builder output, any OS, for a quick check of the packaged app
pwsh ../scripts/build-installer.ps1   # freeze the service, then build the Windows NSIS installer (Windows only)
```

`pack:dir` and `npm run build:win` both read `../installer/electron-builder.yml`
and expect the frozen service at `../service/dist/whf` (run `pwsh
../scripts/build-service.ps1` first, or let `build-installer.ps1` do it).
See `../installer/README.md` for what the installer contains and a first-run
checklist.

## Layout

- `src/main`: Electron main process (service supervisor, IPC, tray, notifications, settings)
- `src/preload`: the `window.whf` bridge
- `src/renderer`: React app (pages, components, typed API client)
- `src/shared`: types shared by all three

## Screens

- Dashboard `/`
- Run `/run` (accepts `?team=<id>`)
- Team result `/runs/:runId`
- Member detail `/runs/:runId/members/:memberId`
- Rebalancing `/rebalancing`
- Projects `/projects`
- Capacity `/capacity`
- Time off `/timeoff`
- Runs `/runs`
- Settings `/settings`

## Notifications

The app checks whether a forecast is due at startup and every 24 hours. If the user's team has no run in the last 14 days, a Windows notification prompts them to run a forecast. After each run completes, a notification lists any team members with overload. Windows toast notifications require the app user model ID, which is set in `src/main/index.ts`.
