# The gate, the same steps as scripts/check.sh and .github/workflows/ci.yml (which has no remote to fire
# on): the Java modules' `mvn verify` (forecast-core and forecast-web), then the showcase front end's
# `npm run check` (type check, unit tests, build) in server/forecast-web/ui. Running this by hand is the
# only gate there is. A step whose tool is missing is skipped with a message.
# Unlike check.sh, which runs every step and reports each failure, this script stops at the first
# failing step; both exit non-zero on any failure.
#
# Usage: pwsh scripts/check.ps1            run the gate
#        pwsh scripts/check.ps1 -DryRun    print the steps without running them
[CmdletBinding()]
param([switch]$DryRun)

$ErrorActionPreference = "Stop"
# Decide on failure from $LASTEXITCODE rather than letting a native command throw, so that every
# step reports its own name and duration.
$PSNativeCommandUseErrorActionPreference = $false

$root = Split-Path -Parent $PSScriptRoot
$server = Join-Path $root "server"
$ui = Join-Path $server "forecast-web/ui"

$steps = [System.Collections.Generic.List[object]]::new()
function Add-Step([string]$Name, [string]$Dir, [string]$Exe, [string[]]$Arguments) {
    $steps.Add([pscustomobject]@{ Name = $Name; Dir = $Dir; Exe = $Exe; Arguments = $Arguments })
}

if (Get-Command mvn -ErrorAction SilentlyContinue) {
    Add-Step "server (mvn verify)" $server "mvn" @("-B", "-q", "verify")
} else {
    Write-Host "SKIP server (mvn verify): mvn not found on PATH" -ForegroundColor Yellow
}
if (Get-Command npm -ErrorAction SilentlyContinue) {
    Add-Step "ui (npm ci)" $ui "npm" @("ci", "--no-audit", "--no-fund", "--silent")
    Add-Step "ui (npm run check)" $ui "npm" @("run", "check", "--silent")
} else {
    Write-Host "SKIP ui (npm run check): npm not found on PATH" -ForegroundColor Yellow
}
if ($steps.Count -eq 0) {
    Write-Host "gate ran nothing: install mvn and npm" -ForegroundColor Red
    exit 1
}

Write-Host "gate: $($steps.Count) steps" -ForegroundColor Cyan

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
    Write-Host "gate failed at: $failed" -ForegroundColor Red
    exit 1
}
Write-Host "gate passed" -ForegroundColor Green
