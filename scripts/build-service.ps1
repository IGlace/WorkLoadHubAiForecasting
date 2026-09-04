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
