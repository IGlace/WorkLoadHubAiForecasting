# Run locally the checks that GitHub Actions used to run. This repository has no remote, so this
# script and the hooks in scripts/hooks are the only gate there is.
#
# Usage: pwsh scripts/check.ps1                     fast gate: ruff, non-slow pytest, app lint/typecheck/test, mvn verify
#        pwsh scripts/check.ps1 -Full               plus the slow pytest suite and the app build
#        pwsh scripts/check.ps1 -Full -Package      plus the installer build
#        pwsh scripts/check.ps1 -DryRun             print the steps without running them
[CmdletBinding()]
param([switch]$Full, [switch]$Package, [switch]$DryRun)

$ErrorActionPreference = "Stop"
# Decide on failure from $LASTEXITCODE rather than letting a native command throw, so that every
# step reports its own name and duration.
$PSNativeCommandUseErrorActionPreference = $false

$env:HF_HUB_OFFLINE = "1"  # as CI does: nothing in a test run may fetch model weights from the network

$root = Split-Path -Parent $PSScriptRoot
$service = Join-Path $root "service"
$app = Join-Path $root "app"
$server = Join-Path $root "server"

$steps = [System.Collections.Generic.List[object]]::new()
function Add-Step([string]$Name, [string]$Dir, [string]$Exe, [string[]]$Arguments) {
    $steps.Add([pscustomobject]@{ Name = $Name; Dir = $Dir; Exe = $Exe; Arguments = $Arguments })
}

# Six xdist workers, not one per core. The suite fits candidate models, and each worker already runs
# scikit-learn with OMP_NUM_THREADS=2 (see service/tests/conftest.py), so twelve workers on twelve
# cores oversubscribe and come out slower: measured 254 s serial, 127 s at -n 6, 159 s at -n 12.
$xdist = @("-n", "6")

# Cheapest first: a lint error should not cost two minutes of pytest. server/tools/ holds
# standalone Python (the schema translator, the parity gate); its own tests moved under
# service/tests, but ruff still has to see it as it is not part of the service package.
Add-Step "ruff check" $service "uv" @("run", "ruff", "check", ".", "../server/tools")
Add-Step "ruff format" $service "uv" @("run", "ruff", "format", "--check", ".", "../server/tools")
if (-not (Test-Path (Join-Path $app "node_modules"))) {
    Add-Step "npm ci" $app "npm" @("ci", "--no-audit", "--no-fund")
}
Add-Step "app lint" $app "npm" @("run", "lint")
Add-Step "app typecheck" $app "npm" @("run", "typecheck")
Add-Step "app tests" $app "npm" @("test")
Add-Step "pytest (fast)" $service "uv" (@("run", "pytest", "-q", "-m", "not slow") + $xdist)
if (Get-Command mvn -ErrorAction SilentlyContinue) {
    Add-Step "server (mvn verify)" $server "mvn" @("-B", "-q", "verify")
} else {
    Write-Host "SKIP server (mvn verify): mvn not found on PATH" -ForegroundColor Yellow
}
if ($Full) {
    Add-Step "pytest (slow)" $service "uv" (@("run", "pytest", "-q", "-m", "slow") + $xdist)
    Add-Step "app build" $app "npm" @("run", "build")
}
if ($Package) {
    Add-Step "installer" $root "pwsh" @("-NoProfile", "-File", (Join-Path $PSScriptRoot "build-installer.ps1"))
}

$mode = if ($Package) { "full gate plus installer" } elseif ($Full) { "full gate" } else { "fast gate" }
Write-Host "$mode : $($steps.Count) steps" -ForegroundColor Cyan

$failed = $null
foreach ($step in $steps) {
    Write-Host "==> $($step.Name)" -ForegroundColor Cyan
    if ($DryRun) {
        Write-Host "    $($step.Exe) $($step.Arguments -join ' ')   [in $($step.Dir)]"
        continue
    }
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    Push-Location $step.Dir
    try { & $step.Exe @($step.Arguments); $code = $LASTEXITCODE } finally { Pop-Location }
    $watch.Stop()
    $seconds = [int]$watch.Elapsed.TotalSeconds
    if ($code -ne 0) {
        Write-Host "FAIL $($step.Name) after ${seconds}s (exit $code)" -ForegroundColor Red
        $failed = $step.Name
        break
    }
    Write-Host "OK   $($step.Name) in ${seconds}s" -ForegroundColor Green
}

if ($DryRun) { exit 0 }
if ($failed) {
    Write-Host "$mode failed at: $failed" -ForegroundColor Red
    exit 1
}
Write-Host "$mode passed" -ForegroundColor Green
