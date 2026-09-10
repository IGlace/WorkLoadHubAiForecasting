# Run the gate, then fast-forward main to dev. Git has no pre-merge hook for a fast-forward, so this
# script is what refuses a bad release; CI on GitHub runs the same gate on both branches afterwards.
#
# Usage: pwsh scripts/release.ps1
[CmdletBinding()]
param([string]$From = "dev", [string]$To = "main")

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if (git status --porcelain) { throw "the working tree is dirty; commit or stash before releasing" }
    $branch = (git rev-parse --abbrev-ref HEAD).Trim()
    if ($branch -ne $From) { throw "expected to be on '$From' but the current branch is '$branch'" }

    if (-not (Get-Command mvn -ErrorAction SilentlyContinue) -or -not (Get-Command uv -ErrorAction SilentlyContinue)) { throw "release needs mvn and uv on PATH so that the whole gate runs" }

    & pwsh -NoProfile -File (Join-Path $PSScriptRoot "check.ps1")
    if ($LASTEXITCODE -ne 0) { throw "the gate failed; '$To' was not moved" }

    git checkout $To
    if ($LASTEXITCODE -ne 0) { throw "could not check out '$To'" }
    git merge --ff-only $From
    if ($LASTEXITCODE -ne 0) {
        git checkout $From
        throw "'$To' is not a fast-forward of '$From'; reconcile them by hand"
    }
    git checkout $From

    Write-Host "released: $To is now at $((git rev-parse --short $To).Trim())" -ForegroundColor Green
} finally { Pop-Location }
