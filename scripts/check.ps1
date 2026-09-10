# The local gate, the same steps as CI (.github/workflows/ci.yml) and scripts/check.sh: the Java module's
# `mvn verify` and the parity gate's test. A step whose tool is missing is skipped with a message.
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

$steps = [System.Collections.Generic.List[object]]::new()
function Add-Step([string]$Name, [string]$Dir, [string]$Exe, [string[]]$Arguments) {
    $steps.Add([pscustomobject]@{ Name = $Name; Dir = $Dir; Exe = $Exe; Arguments = $Arguments })
}

if (Get-Command mvn -ErrorAction SilentlyContinue) {
    Add-Step "server (mvn verify)" $server "mvn" @("-B", "-q", "verify")
} else {
    Write-Host "SKIP server (mvn verify): mvn not found on PATH" -ForegroundColor Yellow
}
if (Get-Command uv -ErrorAction SilentlyContinue) {
    Add-Step "tools (parity gate test)" $root "uv" @("run", "--python", "3.11", "--with", "pytest", "pytest", "server/tools/tests", "-q")
} else {
    Write-Host "SKIP tools (parity gate test): uv not found on PATH" -ForegroundColor Yellow
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
