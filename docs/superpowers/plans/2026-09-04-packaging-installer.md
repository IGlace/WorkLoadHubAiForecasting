# Packaging and Installer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce one shareable Windows installer that contains the Electron app, the frozen Python service and the pinned Copilot CLI, installs per user without administrator rights, keeps data and logs under `%LOCALAPPDATA%\WorkloadHubForecast`, and is built and smoke-tested by CI.

**Architecture:** PyInstaller freezes the service in one-folder mode (`service/dist/whf/whf.exe` plus `_internal/`), with the Copilot CLI pre-downloaded by the SDK's own downloader into `service/dist/whf/copilot-cli/`. electron-builder packages the app with the frozen service as an extra resource and emits a per-user NSIS installer. The main process, when packaged, points the service at the bundled CLI through `COPILOT_CLI_PATH`, keeps settings and rotating logs next to the service database, and resolves the icon from the resources directory. A GitHub Actions workflow runs tests and lint on Linux, freezes and smoke-tests the service on Linux (same PyInstaller configuration), and builds the installer on Windows.

**Tech Stack:** PyInstaller 6.22 + pyinstaller-hooks-contrib 2026.7 (via a `build` dependency group), electron-builder 26.15 (NSIS target), GitHub Actions (`ubuntu-latest`, `windows-latest`), PowerShell and bash build scripts, no new runtime dependencies.

**Spec:** `docs/superpowers/specs/2026-09-03-workload-forecast-design.md` (sections 2, 9, 10, 12). Plan 3's final review notes for packaging are summarised in Global Constraints.

## Global Constraints

- Everything runs on Windows in PowerShell and on Linux CI; no WSL; build scripts exist as `.ps1` for Windows and `.sh` for Linux; paths through `pathlib`/`path`.
- The installer is **per user** (no administrator rights): NSIS `oneClick: false`, `perMachine: false`, `allowToChangeInstallationDirectory: true`; artifact name `WorkloadHub-Forecast-Setup-<version>.exe`; `appId` is `com.workloadhub.forecast` (must equal the `setAppUserModelId` value in `app/src/main/index.ts` or Windows toasts do nothing) and a Start-menu shortcut is created.
- The service is frozen in **one-folder** mode; the app spawns `<resourcesPath>/service/whf/whf.exe serve` with `cwd = resourcesPath` (already implemented in `app/src/main/service-launcher.ts:serviceCommand`); electron-builder `extraResources` maps `../service/dist/whf` to `service/whf`.
- The Copilot CLI is pinned by the SDK (`copilot._cli_version.CLI_VERSION`, currently `1.0.79`); the build pre-downloads it with `python -m copilot download-runtime` and `COPILOT_CLI_EXTRACT_DIR=<dist>/whf/copilot-cli`, so the binary lands at `service/dist/whf/copilot-cli/copilot.exe` (Windows) or `copilot` (Linux). The packaged app injects `COPILOT_CLI_PATH` into the service environment (the `env: {}` argument at `app/src/main/index.ts` is the injection point); the service resolves `COPILOT_CLI_PATH` first (`whf/ai/status.py`, SDK client). The SDK's stdio transport is the default, so `runtime.node` is not needed.
- Data and logs live under `%LOCALAPPDATA%\WorkloadHubForecast\` on Windows (`whf.db` from the service's `whf.config.data_dir()`; the app's settings and logs in `app\` and `logs\` under the same root); on Linux the app uses Electron's default `userData`. Logs rotate at 1 MB, keeping 5 files.
- Auto-start is optional and off by default (already: `DEFAULT_SETTINGS.launchAtLogin = false`, `--hidden` argument); the installer must not enable it.
- Frozen-service smoke test (`installer/pyinstaller/smoke_frozen.py`, cross-platform, standard library only): `whf version` prints the version; `whf data generate --db <tmp>`; `whf serve --db <tmp>` prints the handshake line; `GET /health` answers `{"status":"ok"}`; `GET /meta` with the token answers 200; the process is terminated. This runs on Linux CI against the Linux freeze and inside `scripts/build-service.ps1` on Windows.
- Quality gates before each commit: `service/` `uv run ruff check --fix . && uv run ruff format .` then `uv run ruff check . && uv run ruff format --check .` and `uv run pytest -q -m "not slow"`; `app/` `npm run lint`, `npm run typecheck`, `npm test`, `npm run build`. TDD for every behaviour change in app code (pure helpers tested with vitest); build configuration is verified by running the build.
- Versions: `pyinstaller>=6.22,<7`, `pyinstaller-hooks-contrib>=2026.7`, `electron-builder ^26.15.3`. No other new dependencies.
- Never commit build outputs: `service/dist/`, `service/build/`, `app/out/`, `app/dist/`, `dist/` are ignored.

---

## File structure

```
service/pyproject.toml                 add [dependency-groups] build = pyinstaller, pyinstaller-hooks-contrib
installer/pyinstaller/entry.py         frozen entry: freeze_support() then whf.cli app()
installer/pyinstaller/whf.spec         PyInstaller spec (one-folder, console, data files, copilot package)
installer/pyinstaller/smoke_frozen.py  smoke test of a frozen service folder (stdlib only)
installer/electron-builder.yml         electron-builder configuration (NSIS per-user, extraResources)
installer/README.md                    how to build, what the installer contains, first-run checklist
scripts/build-service.ps1 / .sh        uv sync --group build; pyinstaller; download CLI; smoke
scripts/build-installer.ps1            build-service.ps1 + npm ci + npm run build + electron-builder
app/scripts/make-icon.mjs              also writes resources/icon.ico (PNG-in-ICO, 256x256)
app/resources/icon.ico                 generated, committed
app/package.json                       electron-builder devDependency; scripts pack:dir, build:win
app/src/main/paths.ts                  dataRoot(), iconPath(), bundledCliPath(), serviceEnv()  (pure)
app/src/main/logger.ts                 RotatingLog (pure), installConsoleLogging()
app/src/main/index.ts                  wire paths, env injection, logging
app/src/main/__tests__/paths.test.ts, logger.test.ts
.github/workflows/ci.yml               service, app, freeze-linux, package-windows jobs
.gitignore                             dist/ entries
CLAUDE.md, docs spec section 9         commands and implementation notes
```

---

### Task 1: Freeze the service with PyInstaller and smoke-test the frozen folder

**Files:**
- Modify: `service/pyproject.toml`, `.gitignore`
- Create: `installer/pyinstaller/entry.py`, `installer/pyinstaller/whf.spec`, `installer/pyinstaller/smoke_frozen.py`

**Interfaces:**
- Consumes: `whf.cli:app` (Typer application object), `whf` package data (`whf/db/schema.sql`, `whf/ai/skills/*/SKILL.md`), the `copilot` package.
- Produces: `service/dist/whf/whf` (`whf.exe` on Windows) + `_internal/`; `python installer/pyinstaller/smoke_frozen.py <dist-dir>` exits 0 on success and prints one line per check.

- [ ] **Step 1: Build dependency group and ignores**

In `service/pyproject.toml` add under `[dependency-groups]`:

```toml
build = [
    "pyinstaller>=6.22,<7",
    "pyinstaller-hooks-contrib>=2026.7",
]
```

Append to `.gitignore`:

```
# Build outputs
dist/
installer/pyinstaller/build/
```

(`service/dist/` and `service/build/` are already ignored.)

- [ ] **Step 2: Entry point and spec**

`installer/pyinstaller/entry.py`:

```python
"""Frozen entry point: `whf.exe <command>` behaves exactly like `uv run whf <command>`."""

import multiprocessing

from whf.cli import app

if __name__ == "__main__":
    multiprocessing.freeze_support()  # scikit-learn/joblib may spawn helpers in a frozen app
    app()
```

`installer/pyinstaller/whf.spec`:

```python
# PyInstaller spec for the WorkloadHub AI Forecasting service (one-folder mode).
# Build from the repository root: uv run --directory service pyinstaller ../installer/pyinstaller/whf.spec
from pathlib import Path

from PyInstaller.utils.hooks import collect_all, collect_data_files

HERE = Path(SPECPATH)  # noqa: F821 (SPECPATH is injected by PyInstaller)
ROOT = HERE.parents[1]

whf_datas = collect_data_files("whf", includes=["db/schema.sql", "ai/skills/**/SKILL.md"])
copilot_datas, copilot_binaries, copilot_hidden = collect_all("copilot")

a = Analysis(  # noqa: F821
    [str(HERE / "entry.py")],
    pathex=[str(ROOT / "service" / "src")],
    binaries=copilot_binaries,
    datas=whf_datas + copilot_datas,
    hiddenimports=[
        "uvicorn.logging",
        "uvicorn.loops.auto",
        "uvicorn.protocols.http.auto",
        "uvicorn.protocols.websockets.auto",
        "uvicorn.lifespan.on",
        "sklearn.ensemble._hist_gradient_boosting",
        *copilot_hidden,
    ],
    excludes=["tkinter", "matplotlib", "IPython", "pytest", "hypothesis", "notebook"],
    noarchive=False,
)
pyz = PYZ(a.pure)  # noqa: F821
exe = EXE(  # noqa: F821
    pyz,
    a.scripts,
    exclude_binaries=True,
    name="whf",
    console=True,  # the app spawns it with windowsHide, so no console window appears
    disable_windowed_traceback=False,
)
coll = COLLECT(exe, a.binaries, a.datas, name="whf")  # noqa: F821
```

- [ ] **Step 3: The smoke test**

`installer/pyinstaller/smoke_frozen.py`:

```python
"""Smoke-test a frozen service folder: version, data generation, serve handshake, health and one guarded route.

Usage: python installer/pyinstaller/smoke_frozen.py <dist-dir>   (dist-dir contains whf or whf.exe)
Exit code 0 on success. Standard library only, so it runs on the Windows build machine and on Linux CI.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path


def _exe(dist: Path) -> Path:
    exe = dist / ("whf.exe" if os.name == "nt" else "whf")
    if not exe.exists():
        raise SystemExit(f"missing frozen executable: {exe}")
    return exe


def _run(exe: Path, *args: str, timeout: int = 300) -> str:
    out = subprocess.run([str(exe), *args], capture_output=True, text=True, timeout=timeout)
    if out.returncode != 0:
        raise SystemExit(f"{exe.name} {' '.join(args)} failed ({out.returncode}):\n{out.stdout}\n{out.stderr}")
    return out.stdout


def _get(url: str, token: str | None = None) -> tuple[int, dict]:
    req = urllib.request.Request(url, headers={"X-WHF-Token": token} if token else {})
    with urllib.request.urlopen(req, timeout=10) as res:
        return res.status, json.loads(res.read().decode("utf-8"))


def main(dist_dir: str) -> int:
    dist = Path(dist_dir).resolve()
    exe = _exe(dist)
    version = _run(exe, "version").strip()
    print(f"ok version: {version}")
    with tempfile.TemporaryDirectory() as tmp:
        db = Path(tmp) / "smoke.db"
        _run(exe, "data", "generate", "--db", str(db), "--months", "3")
        print("ok data generate")
        proc = subprocess.Popen(
            [str(exe), "serve", "--db", str(db)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
        )
        try:
            line = proc.stdout.readline() if proc.stdout else ""
            handshake = json.loads(line)
            port, token = int(handshake["port"]), str(handshake["token"])
            print(f"ok handshake on port {port}")
            deadline = time.time() + 60
            while True:
                try:
                    status, body = _get(f"http://127.0.0.1:{port}/health")
                    if status == 200 and body.get("status") == "ok":
                        break
                except OSError:
                    pass
                if time.time() > deadline:
                    raise SystemExit("health check timed out")
                time.sleep(0.25)
            print("ok health")
            status, meta = _get(f"http://127.0.0.1:{port}/meta", token)
            if status != 200 or not meta.get("teams"):
                raise SystemExit(f"/meta failed: {status} {meta}")
            print(f"ok meta ({len(meta['teams'])} teams)")
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    raise SystemExit(main(sys.argv[1]))
```

Check `whf data generate` accepts `--months` (see `service/src/whf/cli.py`, `data_generate`); if the option is named differently, use the real name and note it.

- [ ] **Step 4: Freeze on this machine and run the smoke test**

Run from the repository root:

```bash
cd service && uv sync --group build && cd ..
uv run --directory service pyinstaller --noconfirm --clean --distpath "$PWD/service/dist" --workpath "$PWD/installer/pyinstaller/build" "$PWD/installer/pyinstaller/whf.spec"
python3 installer/pyinstaller/smoke_frozen.py service/dist/whf
```

Expected: the build finishes without "module not found" warnings for `whf`, `sklearn`, `pandas`, `holidays`, `copilot`; the result is `service/dist/whf/whf`; the smoke test prints four `ok` lines and exits 0. Fix the spec (hidden imports, data files) until it does; record every addition in the report. The freeze takes a few minutes; run it in the foreground with a long timeout.

- [ ] **Step 5: Lint and commit**

Run: `cd service && uv run ruff check . && uv run ruff format --check .` (the spec file is outside `service/`; the smoke script must pass `python3 -m py_compile installer/pyinstaller/smoke_frozen.py`).

```bash
git add service/pyproject.toml service/uv.lock .gitignore installer/pyinstaller/entry.py installer/pyinstaller/whf.spec installer/pyinstaller/smoke_frozen.py
git commit -m "build(service): PyInstaller one-folder freeze with a frozen-service smoke test"
```

---

### Task 2: Build scripts with the bundled Copilot CLI

**Files:**
- Create: `scripts/build-service.ps1`, `scripts/build-service.sh`

**Interfaces:**
- Consumes: Task 1's spec and smoke test; `python -m copilot download-runtime` with `COPILOT_CLI_EXTRACT_DIR`.
- Produces: `service/dist/whf/` complete with `copilot-cli/copilot(.exe)`; both scripts exit non-zero on any failure.

- [ ] **Step 1: The bash script (used by Linux CI and verifiable here)**

`scripts/build-service.sh`:

```bash
#!/usr/bin/env bash
# Freeze the forecast service with PyInstaller, bundle the pinned Copilot CLI, smoke-test the result.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/service/dist"
cd "$ROOT/service"
uv sync --group build
rm -rf "$DIST/whf"
uv run pyinstaller --noconfirm --clean --distpath "$DIST" --workpath "$ROOT/installer/pyinstaller/build" "$ROOT/installer/pyinstaller/whf.spec"
if [ "${WHF_SKIP_CLI_DOWNLOAD:-0}" != "1" ]; then
  export COPILOT_CLI_EXTRACT_DIR="$DIST/whf/copilot-cli"
  uv run python -m copilot download-runtime
  ls -l "$COPILOT_CLI_EXTRACT_DIR"
fi
uv run python "$ROOT/installer/pyinstaller/smoke_frozen.py" "$DIST/whf"
echo "service frozen at $DIST/whf"
```

`chmod +x scripts/build-service.sh`.

- [ ] **Step 2: The PowerShell script**

`scripts/build-service.ps1`:

```powershell
# Freeze the forecast service with PyInstaller, bundle the pinned Copilot CLI, smoke-test the result.
# Usage: pwsh scripts/build-service.ps1 [-SkipCliDownload]
param([switch]$SkipCliDownload)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root "service\dist"
Push-Location (Join-Path $root "service")
try {
    uv sync --group build
    if (Test-Path (Join-Path $dist "whf")) { Remove-Item -Recurse -Force (Join-Path $dist "whf") }
    uv run pyinstaller --noconfirm --clean --distpath $dist --workpath (Join-Path $root "installer\pyinstaller\build") (Join-Path $root "installer\pyinstaller\whf.spec")
    if ($LASTEXITCODE -ne 0) { throw "pyinstaller failed" }
    if (-not $SkipCliDownload) {
        $env:COPILOT_CLI_EXTRACT_DIR = Join-Path $dist "whf\copilot-cli"
        uv run python -m copilot download-runtime
        if ($LASTEXITCODE -ne 0) { throw "Copilot CLI download failed" }
        Get-ChildItem $env:COPILOT_CLI_EXTRACT_DIR
    }
    uv run python (Join-Path $root "installer\pyinstaller\smoke_frozen.py") (Join-Path $dist "whf")
    if ($LASTEXITCODE -ne 0) { throw "frozen service smoke test failed" }
    Write-Host "service frozen at $dist\whf"
} finally { Pop-Location }
```

- [ ] **Step 3: Run the bash script here**

Run: `bash scripts/build-service.sh` (long; foreground with a long timeout). Expected: PyInstaller rebuilds, the CLI download prints the cached path under `service/dist/whf/copilot-cli/` (the file `copilot` exists and is executable; if the download is blocked by the proxy, rerun with `WHF_SKIP_CLI_DOWNLOAD=1` and record the exact error), the smoke test prints its four `ok` lines. `pwsh` is not available here: validate the PowerShell script by reading it against the bash one and note that it runs on Windows CI in Task 5.

- [ ] **Step 4: Commit**

```bash
git add scripts/build-service.sh scripts/build-service.ps1
git commit -m "build(service): build scripts that freeze the service and bundle the Copilot CLI"
```

---

### Task 3: electron-builder configuration and icon

**Files:**
- Create: `installer/electron-builder.yml`
- Modify: `app/package.json`, `app/package-lock.json`, `app/scripts/make-icon.mjs`, `app/resources/icon.ico` (generated)

**Interfaces:**
- Consumes: `app/out/**` from `npm run build`; `service/dist/whf` from Task 2.
- Produces: `npm run pack:dir` (unpacked app in `dist/installer/<platform>-unpacked`, usable on Linux to validate the configuration) and `npm run build:win` (NSIS installer `dist/installer/WorkloadHub-Forecast-Setup-0.1.0.exe`, Windows only).

- [ ] **Step 1: Icon in ICO format**

Extend `app/scripts/make-icon.mjs`: after writing `icon.png`, also write `resources/icon.ico` containing the same 256×256 PNG (ICO allows PNG-compressed entries):

```js
const ico = Buffer.alloc(6 + 16)
ico.writeUInt16LE(0, 0); ico.writeUInt16LE(1, 2); ico.writeUInt16LE(1, 4)
ico[6] = 0; ico[7] = 0            // 256 px is encoded as 0
ico[8] = 0; ico[9] = 0            // colour count, reserved
ico.writeUInt16LE(1, 10); ico.writeUInt16LE(32, 12)
ico.writeUInt32LE(png.length, 14); ico.writeUInt32LE(22, 18)
const icoOut = resolve(dirname(fileURLToPath(import.meta.url)), '../resources/icon.ico')
writeFileSync(icoOut, Buffer.concat([ico, png]))
console.log(`wrote ${icoOut} (${ico.length + png.length} bytes)`)
```

Run `npm run icon` and commit both files.

- [ ] **Step 2: electron-builder configuration**

`installer/electron-builder.yml`:

```yaml
# electron-builder configuration. Run from app/: npm run build:win (Windows) or npm run pack:dir (any OS, unpacked).
appId: com.workloadhub.forecast
productName: WorkloadHub Forecast
copyright: Copyright © 2026
directories:
  output: ../dist/installer
  buildResources: resources
files:
  - out/**
  - resources/icon.png
  - resources/icon.ico
  - package.json
extraResources:
  - from: ../service/dist/whf
    to: service/whf
asar: true
win:
  target:
    - target: nsis
      arch: [x64]
  icon: resources/icon.ico
  artifactName: WorkloadHub-Forecast-Setup-${version}.${ext}
nsis:
  oneClick: false
  perMachine: false
  allowToChangeInstallationDirectory: true
  createDesktopShortcut: true
  createStartMenuShortcut: true
  shortcutName: WorkloadHub Forecast
  runAfterFinish: true
  deleteAppDataOnUninstall: false
linux:
  target: [dir]
  icon: resources/icon.png
publish: null
```

- [ ] **Step 3: Package scripts**

In `app/package.json` add `"electron-builder": "^26.15.3"` to `devDependencies` and the scripts:

```json
"pack:dir": "electron-builder --dir --config ../installer/electron-builder.yml",
"build:win": "electron-builder --win nsis --x64 --config ../installer/electron-builder.yml"
```

Run `npm install` (commit the lock file).

- [ ] **Step 4: Validate on Linux with an unpacked build**

Run from `app/`: `npm run build && npm run pack:dir`. Expected: electron-builder writes `dist/installer/linux-unpacked/` containing `resources/app.asar` and `resources/service/whf/whf` (the frozen service copied as an extra resource) and `resources/icon.png` is inside the asar. Verify with `ls dist/installer/linux-unpacked/resources/service/whf | head` and `npx asar list dist/installer/linux-unpacked/resources/app.asar | grep -E "out/main/index.js|out/preload/index.cjs|resources/icon.png"`. If electron-builder needs the Electron binary and it is missing, run `node node_modules/electron/install.js` first. The whole `dist/` directory is ignored by git.

- [ ] **Step 5: Gates and commit**

Run: `cd app && npm run lint && npm run typecheck && npm test` (must stay green).

```bash
git add installer/electron-builder.yml app/package.json app/package-lock.json app/scripts/make-icon.mjs app/resources/icon.ico
git commit -m "build(app): electron-builder per-user NSIS configuration and ICO icon"
```

---

### Task 4: Main-process packaging glue: data root, bundled CLI, icon path, rotating logs

**Files:**
- Create: `app/src/main/paths.ts`, `app/src/main/logger.ts`, `app/src/main/__tests__/paths.test.ts`, `app/src/main/__tests__/logger.test.ts`
- Modify: `app/src/main/index.ts`

**Interfaces:**
- Produces (pure, no Electron imports):
  - `dataRoot(opts: { platform: NodeJS.Platform; env: NodeJS.ProcessEnv; fallback: string }): string` → on `win32` with `LOCALAPPDATA` set: `join(LOCALAPPDATA, 'WorkloadHubForecast')`; otherwise `fallback`.
  - `iconPath(opts: { isPackaged: boolean; resourcesPath: string; appPath: string; platform: NodeJS.Platform }): string` → packaged: `join(resourcesPath, 'app.asar', 'resources', platform === 'win32' ? 'icon.ico' : 'icon.png')`; development: `join(appPath, 'resources', 'icon.png')`.
  - `bundledCliPath(opts: { isPackaged: boolean; resourcesPath: string; platform: NodeJS.Platform; exists: (p: string) => boolean }): string | null` → `join(resourcesPath, 'service', 'whf', 'copilot-cli', platform === 'win32' ? 'copilot.exe' : 'copilot')` when packaged and the file exists, else `null`.
  - `serviceEnv(opts: { cliPath: string | null; env: NodeJS.ProcessEnv }): NodeJS.ProcessEnv` → `{ COPILOT_CLI_PATH: cliPath }` when `cliPath` is set and `env.COPILOT_CLI_PATH` is not already set, else `{}`.
  - `class RotatingLog { constructor(opts: { dir: string; name?: string; maxBytes?: number; keep?: number; fs?: typeof import('node:fs') }); write(line: string): void; path: string }` — appends `<ISO timestamp> <line>\n` to `<dir>/<name>.log` (default `app.log`, 1 MB, keep 5); when the file exceeds `maxBytes`, renames `app.log` → `app.1.log` (shifting older ones up to `keep - 1`, dropping the oldest) and starts a new file; creates `dir` on demand; never throws (errors are swallowed after one `console.error`).
  - `installConsoleLogging(log: RotatingLog): void` — wraps `console.log/warn/error` so each call also writes to the log with a level prefix.

- [ ] **Step 1: Failing tests**

`app/src/main/__tests__/paths.test.ts`:

```ts
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { bundledCliPath, dataRoot, iconPath, serviceEnv } from '../paths'

describe('dataRoot', () => {
  it('uses LOCALAPPDATA\\WorkloadHubForecast on Windows', () => {
    expect(dataRoot({ platform: 'win32', env: { LOCALAPPDATA: 'C:\\Users\\a\\AppData\\Local' }, fallback: '/x' })).toBe(join('C:\\Users\\a\\AppData\\Local', 'WorkloadHubForecast'))
  })
  it('falls back elsewhere or when LOCALAPPDATA is missing', () => {
    expect(dataRoot({ platform: 'linux', env: {}, fallback: '/home/a/.config/app' })).toBe('/home/a/.config/app')
    expect(dataRoot({ platform: 'win32', env: {}, fallback: 'C:\\fallback' })).toBe('C:\\fallback')
  })
})

describe('iconPath', () => {
  it('reads from the asar when packaged and from the app dir in development', () => {
    expect(iconPath({ isPackaged: true, resourcesPath: '/r', appPath: '/a', platform: 'win32' })).toBe(join('/r', 'app.asar', 'resources', 'icon.ico'))
    expect(iconPath({ isPackaged: true, resourcesPath: '/r', appPath: '/a', platform: 'linux' })).toBe(join('/r', 'app.asar', 'resources', 'icon.png'))
    expect(iconPath({ isPackaged: false, resourcesPath: '/r', appPath: '/a', platform: 'win32' })).toBe(join('/a', 'resources', 'icon.png'))
  })
})

describe('bundledCliPath and serviceEnv', () => {
  it('finds the bundled CLI only when packaged and present', () => {
    const win = join('/r', 'service', 'whf', 'copilot-cli', 'copilot.exe')
    expect(bundledCliPath({ isPackaged: true, resourcesPath: '/r', platform: 'win32', exists: (p) => p === win })).toBe(win)
    expect(bundledCliPath({ isPackaged: true, resourcesPath: '/r', platform: 'win32', exists: () => false })).toBeNull()
    expect(bundledCliPath({ isPackaged: false, resourcesPath: '/r', platform: 'win32', exists: () => true })).toBeNull()
  })
  it('injects COPILOT_CLI_PATH unless the user already set one', () => {
    expect(serviceEnv({ cliPath: 'C:\\x\\copilot.exe', env: {} })).toEqual({ COPILOT_CLI_PATH: 'C:\\x\\copilot.exe' })
    expect(serviceEnv({ cliPath: 'C:\\x\\copilot.exe', env: { COPILOT_CLI_PATH: 'D:\\mine.exe' } })).toEqual({})
    expect(serviceEnv({ cliPath: null, env: {} })).toEqual({})
  })
})
```

`app/src/main/__tests__/logger.test.ts`:

```ts
import { existsSync, mkdtempSync, readFileSync, readdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { RotatingLog, installConsoleLogging } from '../logger'

describe('RotatingLog', () => {
  it('creates the directory, timestamps lines and rotates past maxBytes keeping N files', () => {
    const dir = join(mkdtempSync(join(tmpdir(), 'whf-log-')), 'logs')
    const log = new RotatingLog({ dir, maxBytes: 120, keep: 3 })
    for (let i = 0; i < 12; i++) log.write(`line ${i} ${'x'.repeat(20)}`)
    const files = readdirSync(dir).sort()
    expect(files).toEqual(['app.1.log', 'app.2.log', 'app.log'])
    expect(readFileSync(join(dir, 'app.log'), 'utf8')).toMatch(/^\d{4}-\d{2}-\d{2}T[^ ]+ line \d+/)
    expect(existsSync(join(dir, 'app.3.log'))).toBe(false)
  })
  it('never throws when the directory cannot be written', () => {
    const log = new RotatingLog({ dir: '/proc/definitely/not/writable' })
    expect(() => log.write('x')).not.toThrow()
  })
  it('mirrors console output with a level prefix', () => {
    const dir = mkdtempSync(join(tmpdir(), 'whf-log-'))
    const log = new RotatingLog({ dir })
    const original = { log: console.log, warn: console.warn, error: console.error }
    const spy = vi.spyOn(process.stdout, 'write').mockImplementation(() => true)
    try {
      installConsoleLogging(log)
      console.warn('careful', 42)
      console.error(new Error('boom'))
    } finally {
      console.log = original.log; console.warn = original.warn; console.error = original.error; spy.mockRestore()
    }
    const text = readFileSync(log.path, 'utf8')
    expect(text).toContain('WARN careful 42')
    expect(text).toContain('ERROR Error: boom')
  })
})
```

- [ ] **Step 2: Run to verify failure**

Run: `cd app && npx vitest run src/main/__tests__/paths.test.ts src/main/__tests__/logger.test.ts`
Expected: FAIL, modules not found

- [ ] **Step 3: Implement**

`app/src/main/paths.ts`:

```ts
import { join } from 'node:path'

export const DATA_DIR_NAME = 'WorkloadHubForecast'

export function dataRoot(opts: { platform: NodeJS.Platform; env: NodeJS.ProcessEnv; fallback: string }): string {
  const local = opts.env['LOCALAPPDATA']
  if (opts.platform === 'win32' && local) return join(local, DATA_DIR_NAME)
  return opts.fallback
}

export function iconPath(opts: { isPackaged: boolean; resourcesPath: string; appPath: string; platform: NodeJS.Platform }): string {
  if (opts.isPackaged) return join(opts.resourcesPath, 'app.asar', 'resources', opts.platform === 'win32' ? 'icon.ico' : 'icon.png')
  return join(opts.appPath, 'resources', 'icon.png')
}

export function bundledCliPath(opts: { isPackaged: boolean; resourcesPath: string; platform: NodeJS.Platform; exists: (p: string) => boolean }): string | null {
  if (!opts.isPackaged) return null
  const candidate = join(opts.resourcesPath, 'service', 'whf', 'copilot-cli', opts.platform === 'win32' ? 'copilot.exe' : 'copilot')
  return opts.exists(candidate) ? candidate : null
}

export function serviceEnv(opts: { cliPath: string | null; env: NodeJS.ProcessEnv }): NodeJS.ProcessEnv {
  if (opts.cliPath && !opts.env['COPILOT_CLI_PATH']) return { COPILOT_CLI_PATH: opts.cliPath }
  return {}
}
```

`app/src/main/logger.ts`:

```ts
import nodeFs from 'node:fs'
import { join } from 'node:path'
import { format } from 'node:util'

export class RotatingLog {
  readonly path: string
  private readonly fs: typeof nodeFs
  private readonly maxBytes: number
  private readonly keep: number
  private failed = false

  constructor(opts: { dir: string; name?: string; maxBytes?: number; keep?: number; fs?: typeof nodeFs }) {
    this.fs = opts.fs ?? nodeFs
    this.maxBytes = opts.maxBytes ?? 1_000_000
    this.keep = opts.keep ?? 5
    this.path = join(opts.dir, `${opts.name ?? 'app'}.log`)
    try { this.fs.mkdirSync(opts.dir, { recursive: true }) } catch { this.failed = true }
  }

  write(line: string): void {
    if (this.failed) return
    try {
      this.rotateIfNeeded()
      this.fs.appendFileSync(this.path, `${new Date().toISOString()} ${line}\n`)
    } catch (err) {
      this.failed = true
      console.error('log file disabled:', err)
    }
  }

  private rotateIfNeeded(): void {
    let size = 0
    try { size = this.fs.statSync(this.path).size } catch { return }
    if (size < this.maxBytes) return
    const base = this.path.slice(0, -'.log'.length)
    for (let i = this.keep - 1; i >= 1; i--) {
      const from = i === 1 ? this.path : `${base}.${i - 1}.log`
      const to = `${base}.${i}.log`
      if (this.fs.existsSync(from)) this.fs.renameSync(from, to)
    }
  }
}

export function installConsoleLogging(log: RotatingLog): void {
  const wrap = (level: string, original: (...args: unknown[]) => void) => (...args: unknown[]): void => {
    original(...args)
    log.write(`${level} ${format(...args)}`)
  }
  console.log = wrap('INFO', console.log.bind(console))
  console.warn = wrap('WARN', console.warn.bind(console))
  console.error = wrap('ERROR', console.error.bind(console))
}
```

(Check the rotation test's expectation against this loop: with `keep: 3`, files are `app.log`, `app.1.log`, `app.2.log`; adjust the loop bounds if the test shows an `app.3.log`; the test is the contract.)

- [ ] **Step 4: Wire into `index.ts`**

In `app/src/main/index.ts`, before `app.whenReady()` (and before the `SettingsStore` is constructed):

```ts
const root = dataRoot({ platform: process.platform, env: process.env, fallback: app.getPath('userData') })
if (root !== app.getPath('userData')) app.setPath('userData', join(root, 'app'))
const log = new RotatingLog({ dir: join(root, 'logs') })
installConsoleLogging(log)
console.log(`WorkloadHub Forecast ${app.getVersion()} starting; packaged=${app.isPackaged}; data=${root}`)
```

(`SettingsStore` must be created after `setPath`; move its construction into the controller constructor or a lazy getter accordingly.) In `startService()` compute `const cli = bundledCliPath({ isPackaged: app.isPackaged, resourcesPath: process.resourcesPath, platform: process.platform, exists: existsSync })` and pass `env: serviceEnv({ cliPath: cli, env: process.env })` to `ServiceProcess`; log which CLI path was injected. Replace both `join(__dirname, '../../resources/icon.png')` uses with `iconPath({ isPackaged: app.isPackaged, resourcesPath: process.resourcesPath, appPath: app.getAppPath(), platform: process.platform })`. The service's stderr lines already go through `console.log` (`log: (l) => console.log(l)`), so they reach the file.

- [ ] **Step 5: Gates, smoke, commit**

Run: `cd app && npm test && npm run lint && npm run typecheck && npm run build`, then the headless smoke as in plan 3 (`xvfb-run -a npm run smoke` with `WHF_SERVICE_COMMAND` pointing at `uv run --directory <abs>/service whf serve`); expected `SMOKE window.whf=object`, `SMOKE service=ready`, exit 0, and a `logs/app.log` under the Linux userData directory containing the start line. Paste both in the report.

```bash
git add app/src/main
git commit -m "feat(app): data root under LOCALAPPDATA, bundled Copilot CLI path, icon from resources, rotating logs"
```

---

### Task 5: CI workflow, installer script and documentation

**Files:**
- Create: `.github/workflows/ci.yml`, `scripts/build-installer.ps1`, `installer/README.md`
- Modify: `CLAUDE.md`, `service/README.md`, `app/README.md`, `docs/superpowers/specs/2026-09-03-workload-forecast-design.md` (section 9)

- [ ] **Step 1: The installer script**

`scripts/build-installer.ps1`:

```powershell
# Build the shareable Windows installer: frozen service + Copilot CLI + Electron app -> dist/installer/WorkloadHub-Forecast-Setup-<version>.exe
# Usage: pwsh scripts/build-installer.ps1   (requires uv, Node 22, npm; no administrator rights)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot "build-service.ps1")
Push-Location (Join-Path $root "app")
try {
    npm ci
    if ($LASTEXITCODE -ne 0) { throw "npm ci failed" }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "app build failed" }
    npm run build:win
    if ($LASTEXITCODE -ne 0) { throw "electron-builder failed" }
    Get-ChildItem (Join-Path $root "dist\installer") -Filter *.exe | ForEach-Object { Write-Host "installer: $($_.FullName)" }
} finally { Pop-Location }
```

- [ ] **Step 2: The workflow**

`.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
  workflow_dispatch:

jobs:
  service:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: service } }
    steps:
      - uses: actions/checkout@v4
      - uses: astral-sh/setup-uv@v5
        with: { enable-cache: true }
      - run: uv python install 3.11
      - run: uv sync
      - run: uv run ruff check . && uv run ruff format --check .
      - run: uv run pytest -q -m "not slow"
      - run: uv run pytest -q -m slow

  app:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: app } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 22, cache: npm, cache-dependency-path: app/package-lock.json }
      - run: npm ci
      - run: npm run lint
      - run: npm run typecheck
      - run: npm test
      - run: npm run build

  freeze-linux:
    runs-on: ubuntu-latest
    needs: [service]
    steps:
      - uses: actions/checkout@v4
      - uses: astral-sh/setup-uv@v5
      - run: uv python install 3.11
      - run: bash scripts/build-service.sh
        env: { WHF_SKIP_CLI_DOWNLOAD: "1" }

  package-windows:
    runs-on: windows-latest
    needs: [service, app]
    steps:
      - uses: actions/checkout@v4
      - uses: astral-sh/setup-uv@v5
      - run: uv python install 3.11
      - uses: actions/setup-node@v4
        with: { node-version: 22, cache: npm, cache-dependency-path: app/package-lock.json }
      - run: pwsh scripts/build-installer.ps1
      - uses: actions/upload-artifact@v4
        with:
          name: WorkloadHub-Forecast-Setup
          path: dist/installer/*.exe
          if-no-files-found: error
```

Validate the YAML locally with `uv run --directory service --with pyyaml python -c "import yaml; yaml.safe_load(open('../.github/workflows/ci.yml'))"`.

- [ ] **Step 3: Documentation**

`installer/README.md`: what the installer contains (app, frozen service under `resources/service/whf`, Copilot CLI under `copilot-cli/`), how to build on Windows (`pwsh scripts/build-installer.ps1`; prerequisites: uv, Node 22, no admin), where the artifact lands, the CI job that builds it, and a **first-run checklist** for the owner: install per user; start from the Start menu; Settings → choose profile; Settings → "Sign in to GitHub Copilot" (a PowerShell window opens with the device code); "Check again" until signed in; Run → pick the team → "Run forecast" with the AI box ticked; expect the overload notification; close the window and confirm the tray icon; where the data and logs are (`%LOCALAPPDATA%\WorkloadHubForecast\whf.db`, `logs\app.log`); how to enable start with Windows in Settings.

`service/README.md`: add a "## Build" section with `pwsh scripts/build-service.ps1` / `bash scripts/build-service.sh` and the smoke test. `app/README.md`: add "## Package" with `npm run pack:dir` and `pwsh ../scripts/build-installer.ps1`. `CLAUDE.md`: in "Toolchain", add "- Packaging: PyInstaller (`installer/pyinstaller/whf.spec`), electron-builder (`installer/electron-builder.yml`); `pwsh scripts/build-installer.ps1` builds the installer; CI in `.github/workflows/ci.yml`." and update the `installer/` and `scripts/` layout lines.

Spec section 9: append "(Implementation note, 2026-09-04: the Copilot CLI is pre-downloaded at build time with the SDK's `python -m copilot download-runtime` into `service/dist/whf/copilot-cli/` and the app sets `COPILOT_CLI_PATH` for the service; the app's settings and logs live under `%LOCALAPPDATA%\WorkloadHubForecast\app` and `\logs` next to the database; the installer is a per-user NSIS setup built by `scripts/build-installer.ps1` and by the `package-windows` CI job.)"

- [ ] **Step 4: Verification and commit**

Run: `cd service && uv run ruff check . && uv run ruff format --check .`; `cd app && npm run lint && npm run typecheck && npm test`; the YAML check above.

```bash
git add .github/workflows/ci.yml scripts/build-installer.ps1 installer/README.md CLAUDE.md service/README.md app/README.md docs/superpowers/specs/2026-09-03-workload-forecast-design.md
git commit -m "ci: test, freeze and package workflows; installer build script and docs"
```

---

## Self-review against the spec (sections 9, 10, 12)

- **Section 9**: PyInstaller one-folder with the Copilot CLI, scikit-learn, pandas, holidays (Task 1, 2); electron-builder per-user NSIS embedding the service folder, one file to share (Task 3, 5); data and logs under `%LOCALAPPDATA%\WorkloadHubForecast\`, rotating logs (Task 4); auto-start off by default (plan 3, unchanged); Windows development plus Linux CI (Task 5).
- **Section 10**: CI on Linux for tests and lint, on Windows for packaging (Task 5); the frozen-service smoke test doubles as an integration test of the freeze (Task 1, run by both freeze jobs).
- **Section 12 risk** "The Copilot SDK downloads the CLI on first use": mitigated by the pre-download and `COPILOT_CLI_PATH` (Task 2, 4); "Frozen service size and start time": one-folder mode, `excludes` in the spec, the app's health polling with a banner (plan 3).
- **Plan 3 packaging notes**: CJS preload (done in plan 3), `extraResources` path (Task 3), `COPILOT_CLI_PATH` injection (Task 4), icon path in the asar (Task 4), `appId` + shortcut (Task 3), `--hidden` in NSIS (verify on the owner's machine: listed in `installer/README.md`'s checklist), CSP/packaged-window smoke (the first-run checklist), ESM main kept (`"type": "module"` stays).
- **Type consistency**: `serviceCommand` (plan 3) expects `<resourcesPath>/service/whf/whf.exe` and `cwd = resourcesPath` — matches `extraResources.to: service/whf` (Task 3) and `bundledCliPath` (Task 4); `ServiceProcess` takes `env` (plan 3) — Task 4 passes `serviceEnv(...)`; `RotatingLog.path` used by the logger test; `iconPath` replaces both icon usages.
