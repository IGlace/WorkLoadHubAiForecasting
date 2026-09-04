# Run the full gate, then fast-forward main to dev. This is the only thing that can actually stop a
# bad release: git has no pre-merge hook for a fast-forward, so scripts/hooks/post-merge can only
# report after the fact.
#
# Usage: pwsh scripts/release.ps1                 gate (with the installer build), then merge dev into main
#        pwsh scripts/release.ps1 -SkipPackage    gate without building the installer
[CmdletBinding()]
param([string]$From = "dev", [string]$To = "main", [switch]$SkipPackage)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if (git status --porcelain) { throw "the working tree is dirty; commit or stash before releasing" }
    $branch = (git rev-parse --abbrev-ref HEAD).Trim()
    if ($branch -ne $From) { throw "expected to be on '$From' but the current branch is '$branch'" }

    $checkArgs = @("-NoProfile", "-File", (Join-Path $PSScriptRoot "check.ps1"), "-Full")
    if (-not $SkipPackage) { $checkArgs += "-Package" }
    & pwsh @checkArgs
    if ($LASTEXITCODE -ne 0) { throw "the full gate failed; '$To' was not moved" }

    # The gate has just run, so suppress the post-merge hook rather than run it all again.
    $env:WHF_SKIP_HOOKS = "1"
    try {
        git checkout $To
        if ($LASTEXITCODE -ne 0) { throw "could not check out '$To'" }
        git merge --ff-only $From
        if ($LASTEXITCODE -ne 0) {
            git checkout $From
            throw "'$To' is not a fast-forward of '$From'; reconcile them by hand"
        }
        git checkout $From
    } finally { Remove-Item Env:\WHF_SKIP_HOOKS -ErrorAction SilentlyContinue }

    Write-Host "released: $To is now at $((git rev-parse --short $To).Trim())" -ForegroundColor Green
} finally { Pop-Location }
