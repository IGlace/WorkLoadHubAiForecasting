# Starts the desktop app in development mode. Requires uv (service) and Node 22 (app).
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $root "service")
try { uv sync --quiet } finally { Pop-Location }
Push-Location (Join-Path $root "app")
try {
    if (-not (Test-Path "node_modules")) { npm install }
    npm run dev
} finally { Pop-Location }
