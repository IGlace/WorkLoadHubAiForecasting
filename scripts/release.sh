#!/usr/bin/env bash
# Run the gate, then fast-forward main to dev: the bash twin of scripts/release.ps1. Git has no pre-merge
# hook for a fast-forward, so this script is what refuses a bad release; CI on GitHub runs the same gate
# on both branches afterwards. Nothing is pushed.
#
# Usage: bash scripts/release.sh [FROM] [TO]      (defaults: dev main)
# WHF_GATE names the gate script to run instead of scripts/check.sh; scripts/test-release.sh uses it.
set -euo pipefail

from="${1:-dev}"
to="${2:-main}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
gate="${WHF_GATE:-$root/scripts/check.sh}"
cd "$root"

fail() {
    echo "release: $*" >&2
    exit 1
}

[ -z "$(git status --porcelain)" ] || fail "the working tree is dirty; commit or stash before releasing"
branch="$(git rev-parse --abbrev-ref HEAD)"
[ "$branch" = "$from" ] || fail "expected to be on '$from' but the current branch is '$branch'"
if ! command -v mvn >/dev/null 2>&1 || ! command -v uv >/dev/null 2>&1; then
    fail "release needs mvn and uv on PATH so that the whole gate runs"
fi

bash "$gate" || fail "the gate failed; '$to' was not moved"

git checkout -q "$to" || fail "could not check out '$to'"
if ! git merge --ff-only "$from"; then
    git checkout -q "$from"
    fail "'$to' is not a fast-forward of '$from'; reconcile them by hand"
fi
git checkout -q "$from"
echo "released: $to is now at $(git rev-parse --short "$to")"
