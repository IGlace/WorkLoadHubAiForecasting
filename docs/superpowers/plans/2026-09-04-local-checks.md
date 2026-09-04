# Local Checks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the GitHub Actions workflow, which can never run now that the repository has no remote, with local PowerShell checks that gate every commit and every release.

**Architecture:** One script, `scripts/check.ps1`, owns the list of checks and runs them cheapest-first so failures surface fast; a `-Full` switch adds the slow pytest suite and the app build, and `-Package` adds the installer. Versioned hooks in `scripts/hooks/` call it: `pre-commit` runs the fast gate, `post-merge` reports on `main`. Because git has no pre-merge hook that fires on a fast-forward, the only thing that can genuinely block a release is `scripts/release.ps1`, which runs the full gate and merges only if it passes.

**Tech Stack:** PowerShell 7.6 (`pwsh`), POSIX `sh` for the hook shims (git runs hooks through Git Bash on Windows), `uv`, `npm`.

**Spec:** `docs/backlog.md`, the "Local checks in place of CI" item under "Approved, not yet built", which records the owner's decisions of 2026-09-04: fast gate on every commit, full gate plus installer build on merges into `main`, hooks versioned in the repository and activated with `core.hooksPath`.

## Global Constraints

- Everything runs on Windows in PowerShell. No WSL at install or run time.
- The repository is local only; the remote was removed on 2026-09-04. Do not add one back.
- Test-driven development for every change; property tests for arithmetic invariants.
- Commit messages: imperative subject, short body explaining why.
- Branches: `dev` is the development branch, `main` the release branch, fast-forward merges only.
- The checks the workflow ran, which this must reproduce: `uv run ruff check .`, `uv run ruff format --check .`, `uv run pytest -q -m "not slow"`, `uv run pytest -q -m slow` in `service/`; `npm run lint`, `npm run typecheck`, `npm test`, `npm run build` in `app/`; `pwsh scripts/build-installer.ps1` for the installer.
- Measured costs on this machine, which is why the gate is split: service pytest about 8.5 minutes in total, app tests about 40 seconds, lint and typecheck seconds.
- A hook must always be escapable: `git commit --no-verify`, and an explicit `WHF_SKIP_HOOKS=1`.

---

### Task 1: Make npm work without corporate credentials

`npm config get registry` on this machine is `https://arti.avl.com/artifactory/api/npm/npm-release/`, whose stored credentials are expired, so `npm ci` fails with `npm error code E401`. Every one of the 625 `resolved` entries in `app/package-lock.json` already points at `https://registry.npmjs.org`, so pinning that registry for this project changes where metadata is fetched from, not which artefacts are installed. Without this, both the app half of the gate and `scripts/build-installer.ps1` (which runs `npm ci` at line 8) fail on a fresh checkout.

**Files:**
- Create: `app/.npmrc`

**Interfaces:**
- Consumes: nothing.
- Produces: a working `npm ci` in `app/`, relied on by Task 2's app steps and by `scripts/build-installer.ps1`.

- [ ] **Step 1: Write the failing check**

Run, and record the output as the "before" state:

```sh
cd app && npm config get registry
```

Expected before the change: `https://arti.avl.com/artifactory/api/npm/npm-release/`

- [ ] **Step 2: Create the file**

`app/.npmrc`:

```ini
# This project resolves from the public npm registry. Every "resolved" URL in package-lock.json
# already points here, and the machine-wide registry (a corporate Artifactory) has expired
# credentials, so a plain "npm ci" fails with E401 on a fresh checkout. Delete this file if the
# Artifactory credentials are ever refreshed and you want installs to go through it again.
registry=https://registry.npmjs.org/
```

- [ ] **Step 3: Verify the check now passes**

```sh
cd app && npm config get registry
```

Expected: `https://registry.npmjs.org/`

Then prove a real install works from a clean state:

```sh
cd app && rm -rf node_modules && npm ci --no-audit --no-fund && git status --short package-lock.json
```

Expected: `added 550 packages`, and `git status` prints nothing, meaning the lockfile was not rewritten.

- [ ] **Step 4: Commit**

```bash
git add app/.npmrc
git commit -m "build(app): resolve npm from the public registry

The machine-wide registry is a corporate Artifactory with expired credentials, so
npm ci failed with E401 and took the installer build down with it. Every resolved
URL in package-lock.json already points at registry.npmjs.org, so pinning it
changes where metadata is fetched, not what is installed."
```

---

### Task 2: The check script

**Files:**
- Create: `scripts/check.ps1`

**Interfaces:**
- Consumes: `app/.npmrc` from Task 1.
- Produces: `scripts/check.ps1` with switches `-Full`, `-Package`, `-DryRun`; exit code 0 when every step passed, 1 otherwise. Tasks 3 and 4 invoke it as `pwsh -NoProfile -File <root>/scripts/check.ps1 [-Full] [-Package]`.

- [ ] **Step 1: Write the failing test**

The script's own contract is "runs these commands, in this order, and fails if one fails". `-DryRun` makes that contract assertable without a ten-minute run. Write `service/tests/test_check_script.py`:

```python
"""The local gate stands in for CI, so the list of checks it runs is worth pinning down."""

import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "check.ps1"
pytestmark = pytest.mark.skipif(shutil.which("pwsh") is None, reason="pwsh is not on PATH")


def _dry_run(*args: str) -> list[str]:
    out = subprocess.run(
        ["pwsh", "-NoProfile", "-File", str(SCRIPT), "-DryRun", *args],
        capture_output=True,
        text=True,
        check=True,
    )
    return [line.strip() for line in out.stdout.splitlines() if line.strip().startswith("==>")]


def test_the_fast_gate_runs_the_cheap_checks_first_and_skips_the_slow_suite() -> None:
    steps = " | ".join(_dry_run())
    assert "ruff check" in steps and "app lint" in steps and "pytest (fast)" in steps
    assert "pytest (slow)" not in steps and "installer" not in steps
    # cheapest first, so a lint error does not cost eight minutes of pytest
    assert steps.index("ruff check") < steps.index("pytest (fast)")
    assert steps.index("app lint") < steps.index("pytest (fast)")


def test_full_adds_the_slow_suite_and_the_app_build() -> None:
    steps = " | ".join(_dry_run("-Full"))
    assert "pytest (slow)" in steps and "app build" in steps
    assert "installer" not in steps


def test_package_adds_the_installer_build_last() -> None:
    steps = _dry_run("-Full", "-Package")
    assert "installer" in steps[-1]


def test_a_dry_run_changes_nothing_and_succeeds() -> None:
    done = subprocess.run(
        ["pwsh", "-NoProfile", "-File", str(SCRIPT), "-DryRun"], capture_output=True, text=True
    )
    assert done.returncode == 0
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd service && uv run pytest tests/test_check_script.py -v`
Expected: FAIL — every test errors, because `scripts/check.ps1` does not exist and pwsh exits non-zero.

- [ ] **Step 3: Write the script**

`scripts/check.ps1`:

```powershell
# Run locally the checks that GitHub Actions used to run. This repository has no remote, so this
# script and the hooks in scripts/hooks are the only gate there is.
#
# Usage: pwsh scripts/check.ps1                     fast gate: ruff, non-slow pytest, app lint/typecheck/test
#        pwsh scripts/check.ps1 -Full               plus the slow pytest suite and the app build
#        pwsh scripts/check.ps1 -Full -Package      plus the installer build
#        pwsh scripts/check.ps1 -DryRun             print the steps without running them
[CmdletBinding()]
param([switch]$Full, [switch]$Package, [switch]$DryRun)

$ErrorActionPreference = "Stop"
# Decide on failure from $LASTEXITCODE rather than letting a native command throw, so that every
# step reports its own name and duration.
$PSNativeCommandUseErrorActionPreference = $false

$root = Split-Path -Parent $PSScriptRoot
$service = Join-Path $root "service"
$app = Join-Path $root "app"

$steps = [System.Collections.Generic.List[object]]::new()
function Add-Step([string]$Name, [string]$Dir, [string]$Exe, [string[]]$Arguments) {
    $steps.Add([pscustomobject]@{ Name = $Name; Dir = $Dir; Exe = $Exe; Arguments = $Arguments })
}

# Cheapest first: a lint error should not cost eight minutes of pytest.
Add-Step "ruff check" $service "uv" @("run", "ruff", "check", ".")
Add-Step "ruff format" $service "uv" @("run", "ruff", "format", "--check", ".")
if (-not (Test-Path (Join-Path $app "node_modules"))) {
    Add-Step "npm ci" $app "npm" @("ci", "--no-audit", "--no-fund")
}
Add-Step "app lint" $app "npm" @("run", "lint")
Add-Step "app typecheck" $app "npm" @("run", "typecheck")
Add-Step "app tests" $app "npm" @("test")
Add-Step "pytest (fast)" $service "uv" @("run", "pytest", "-q", "-m", "not slow")
if ($Full) {
    Add-Step "pytest (slow)" $service "uv" @("run", "pytest", "-q", "-m", "slow")
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd service && uv run pytest tests/test_check_script.py -v`
Expected: 4 passed.

- [ ] **Step 5: Run the real fast gate once**

Run: `pwsh scripts/check.ps1`
Expected: every step prints `OK`, then `fast gate passed`. If a step fails, that is a real defect to fix before continuing, not a problem with the script.

- [ ] **Step 6: Commit**

```bash
git add scripts/check.ps1 service/tests/test_check_script.py
git commit -m "ci: run the former GitHub Actions checks locally

The remote is gone, so the workflow can never fire. check.ps1 owns the list of
checks, cheapest first so a lint error does not cost eight minutes of pytest,
with -Full for the slow suite and -Package for the installer. -DryRun makes the
list assertable in a test without a ten-minute run."
```

---

### Task 3: The pre-commit hook

**Files:**
- Create: `scripts/hooks/pre-commit`

**Interfaces:**
- Consumes: `scripts/check.ps1` from Task 2.
- Produces: a hook directory that `git config core.hooksPath scripts/hooks` activates. Task 4 adds a second hook to the same directory.

- [ ] **Step 1: Write the hook**

Git runs hooks through `sh` even on Windows, so the hook is a POSIX shim that calls pwsh. `scripts/hooks/pre-commit`:

```sh
#!/bin/sh
# Fast local gate, standing in for the CI that cannot run without a remote.
# Escape hatches: git commit --no-verify, or WHF_SKIP_HOOKS=1 git commit ...
#
# Note this checks the working tree, not the staged snapshot, so a partially staged commit is
# verified as it looks on disk. That is the useful behaviour for a single-developer repository.
if [ -n "$WHF_SKIP_HOOKS" ]; then
    echo "pre-commit: skipped (WHF_SKIP_HOOKS is set)"
    exit 0
fi
root=$(git rev-parse --show-toplevel) || exit 1
exec pwsh -NoProfile -File "$root/scripts/check.ps1"
```

- [ ] **Step 2: Activate it**

```bash
git config core.hooksPath scripts/hooks
git config --get core.hooksPath
```

Expected: `scripts/hooks`

- [ ] **Step 3: Verify it blocks a bad commit**

Break something cheap on purpose, then try to commit:

```bash
printf 'x=1\n' >> service/src/whf/capacity.py   # ruff format will reject the file
git add service/src/whf/capacity.py
git commit -m "should not land"
```

Expected: the commit is refused, `FAIL ruff format` is printed, and `git log --oneline -1` still shows the previous commit.

Then undo the damage and confirm the escape hatch works:

```bash
git restore --staged --worktree service/src/whf/capacity.py
git status --short
```

Expected: nothing.

- [ ] **Step 4: Commit the hook**

The gate now runs on this very commit, which is the real proof it works.

```bash
git add scripts/hooks/pre-commit
git commit -m "ci: gate every commit with the fast local checks

Hooks live in the repository rather than .git/hooks so they are reviewable in a
diff and survive a reclone; one core.hooksPath setting activates them. Escapable
with --no-verify or WHF_SKIP_HOOKS for the times that matters."
```

---

### Task 4: Release gating and the post-merge reporter

A `post-merge` hook runs after the merge has already been recorded, and git has no pre-merge hook that fires for a fast-forward, so a hook cannot refuse a release. `scripts/release.ps1` therefore runs the full gate first and merges only if it passed; the hook stays as a reporter for a merge done by hand.

**Files:**
- Create: `scripts/release.ps1`
- Create: `scripts/hooks/post-merge`

**Interfaces:**
- Consumes: `scripts/check.ps1` from Task 2, the hook directory from Task 3.
- Produces: `pwsh scripts/release.ps1 [-From dev] [-To main] [-SkipPackage]`.

- [ ] **Step 1: Write the release script**

`scripts/release.ps1`:

```powershell
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
```

- [ ] **Step 2: Write the post-merge reporter**

`scripts/hooks/post-merge`:

```sh
#!/bin/sh
# A merge landed. On main, say whether the result is green.
#
# This cannot block anything: git runs post-merge after the merge is already recorded, and there is
# no pre-merge hook for a fast-forward. Use scripts/release.ps1 to gate before merging; this hook is
# the safety net for a merge done by hand. Its exit code is ignored by git, so the message matters.
if [ -n "$WHF_SKIP_HOOKS" ]; then
    exit 0
fi
if [ "$(git rev-parse --abbrev-ref HEAD)" != "main" ]; then
    exit 0
fi
root=$(git rev-parse --show-toplevel) || exit 1
echo "post-merge: main changed, running the full gate (about ten minutes)"
if ! pwsh -NoProfile -File "$root/scripts/check.ps1" -Full; then
    echo ""
    echo "post-merge: main is NOT green. Fix it on dev and release again, or move main back with:"
    echo "    git branch -f main <last good commit>"
    exit 1
fi
```

- [ ] **Step 3: Verify the guards without a real release**

The cheap, safe checks are the refusals, which need no merge at all:

```bash
pwsh scripts/release.ps1 -SkipPackage   # while on dev with a dirty tree
```

Expected: it stops immediately with `the working tree is dirty`, and `git rev-parse main` is unchanged.

```bash
git checkout main && pwsh scripts/release.ps1 -SkipPackage ; git checkout dev
```

Expected: it stops with `expected to be on 'dev' but the current branch is 'main'`.

- [ ] **Step 4: Commit**

```bash
git add scripts/release.ps1 scripts/hooks/post-merge
git commit -m "ci: gate releases before the merge, not after

A post-merge hook runs once the merge is already recorded and git has no
pre-merge hook for a fast-forward, so a hook cannot refuse a bad release.
release.ps1 runs the full gate and the installer build first and moves main only
if they pass; the hook remains as a reporter for a merge done by hand."
```

---

### Task 5: Say all this where a reader will look

**Files:**
- Modify: `CLAUDE.md`, the Toolchain section
- Modify: `.github/workflows/ci.yml:1`
- Modify: `docs/backlog.md`, the "Local checks in place of CI" item

**Interfaces:**
- Consumes: everything above.
- Produces: nothing code depends on.

- [ ] **Step 1: Document the commands in CLAUDE.md**

In the Toolchain section, after the `Commands:` line, add:

```markdown
- Local gate (there is no CI; the remote was removed): `pwsh scripts/check.ps1` runs the fast
  checks, `-Full` adds the slow pytest suite and the app build, `-Package` adds the installer.
  `pwsh scripts/release.ps1` runs the full gate and then fast-forwards `main` to `dev`; it is the
  only thing that can refuse a bad release, because git has no pre-merge hook for a fast-forward.
  Activate the hooks once per clone with `git config core.hooksPath scripts/hooks`; escape them
  with `git commit --no-verify` or `WHF_SKIP_HOOKS=1`.
```

- [ ] **Step 2: Mark the workflow inert**

Insert at the top of `.github/workflows/ci.yml`, above `name: CI`:

```yaml
# INERT. The repository has no remote as of 2026-09-04, so nothing here can ever trigger. It is kept
# as the written definition of "green", which scripts/check.ps1 reproduces locally. If a remote is
# ever added back, check that the two have not drifted apart.
```

- [ ] **Step 3: Move the backlog item**

In `docs/backlog.md`, delete the "Local checks in place of CI" bullet from "Approved, not yet built" and add to a new "Landed" section at the top of the file:

```markdown
## Landed

- **Local checks in place of CI** (2026-09-04): `scripts/check.ps1`, `scripts/release.ps1` and the
  hooks in `scripts/hooks/`. See the Toolchain section of `CLAUDE.md`.
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md .github/workflows/ci.yml docs/backlog.md
git commit -m "docs: record the local gate that replaced CI

Anyone opening the workflow file needs to know it can no longer fire, and anyone
cloning needs the one command that activates the hooks."
```

---

## Self-Review

**Spec coverage.** The backlog item asks for four things. `scripts/check.ps1` with a fast and a full mode: Task 2. A fast gate on every commit covering ruff, non-slow pytest and the app checks: Task 2's step list plus Task 3's hook. A full gate on merges into `main`, including the installer build the owner asked for: Task 4, moved before the merge because a hook after it cannot refuse anything. Versioned hooks activated by `core.hooksPath`: Task 3. Task 1 is not in the backlog item but the plan cannot be executed without it, since `npm ci` fails with E401 on this machine and `build-installer.ps1` calls it.

**Deliberately not covered.** The workflow's `freeze-linux` job has no local equivalent: it exists to prove the PyInstaller build works on Linux, which is irrelevant to a Windows-only product. `npm run build` is folded into the full gate rather than run separately.

**Placeholder scan.** No TBDs; every code step carries the file's full content, and every verification step names the command and the expected output.

**Type consistency.** `check.ps1` is invoked identically in three places — `pwsh -NoProfile -File <root>/scripts/check.ps1` with `-Full`/`-Package` — in Task 3's hook, Task 4's hook, Task 4's release script and Task 2's test. The `WHF_SKIP_HOOKS` variable is read by both hooks and set by `release.ps1`. The step names asserted in `test_check_script.py` (`ruff check`, `app lint`, `pytest (fast)`, `pytest (slow)`, `app build`, `installer`) match the `Add-Step` calls exactly.
