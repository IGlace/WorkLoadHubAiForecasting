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
