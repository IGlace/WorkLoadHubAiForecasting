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

## Layout

- `src/main`: Electron main process (service supervisor, IPC, tray, notifications, settings)
- `src/preload`: the `window.whf` bridge
- `src/renderer`: React app (pages, components, typed API client)
- `src/shared`: types shared by all three
