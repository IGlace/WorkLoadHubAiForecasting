#!/usr/bin/env bash
# Self-test of scripts/release.sh on a throwaway repository: every refusal path and the fast-forward.
# The gate is replaced through WHF_GATE (a script that exits with FAKE_GATE_EXIT) and PATH is reduced
# to a directory of symlinks, so a missing mvn or uv can be simulated. Usage: bash scripts/test-release.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
failures=0

check() {  # check NAME EXPECTED_EXIT ACTUAL_EXIT OUTPUT EXPECTED_SUBSTRING
    if [ "$2" = "$3" ] && [[ "$4" == *"$5"* ]]; then
        echo "ok   $1"
    else
        echo "FAIL $1: exit $3 (expected $2), output: $4"
        failures=1
    fi
}

# A bin directory with only what release.sh needs, plus fake mvn and uv when asked for.
bin="$tmp/bin"; mkdir -p "$bin"
for tool in bash git dirname mktemp rm mkdir cp cat; do ln -s "$(command -v $tool)" "$bin/$tool"; done
with_tools() { printf '#!/usr/bin/env bash\nexit 0\n' > "$bin/mvn"; cp "$bin/mvn" "$bin/uv"; chmod +x "$bin/mvn" "$bin/uv"; }
without_tools() { rm -f "$bin/mvn" "$bin/uv"; }

# A repository with dev and main at the same commit and release.sh in scripts/.
repo="$tmp/repo"; mkdir -p "$repo/scripts"; cp "$here/release.sh" "$repo/scripts/release.sh"
printf '#!/usr/bin/env bash\nexit "${FAKE_GATE_EXIT:-0}"\n' > "$repo/scripts/fake-gate.sh"
git -C "$repo" init -q -b dev
git -C "$repo" -c user.name=t -c user.email=t@t config commit.gpgsign false
git -C "$repo" add . && git -C "$repo" -c user.name=t -c user.email=t@t commit -q -m "init"
git -C "$repo" branch main

run() { (cd "$repo" && PATH="$bin" WHF_GATE="$repo/scripts/fake-gate.sh" bash scripts/release.sh 2>&1); }

with_tools
echo "dirty" > "$repo/dirty.txt"
out="$(run || true)"; code=$?; out="$(run)" && code=0 || code=$?
check "dirty tree refused" 1 "$code" "$out" "dirty"
rm "$repo/dirty.txt"

git -C "$repo" checkout -q main
out="$(run)" && code=0 || code=$?
check "wrong branch refused" 1 "$code" "$out" "expected to be on 'dev'"
git -C "$repo" checkout -q dev

without_tools
out="$(run)" && code=0 || code=$?
check "missing tools refused" 1 "$code" "$out" "needs mvn and uv"
with_tools

git -C "$repo" -c user.name=t -c user.email=t@t commit -q --allow-empty -m "work on dev"
out="$(FAKE_GATE_EXIT=1 run)" && code=0 || code=$?
check "failing gate refused" 1 "$code" "$out" "gate failed"
[ "$(git -C "$repo" rev-parse main)" != "$(git -C "$repo" rev-parse dev)" ] && echo "ok   main untouched after a failed gate" || { echo "FAIL main moved after a failed gate"; failures=1; }

out="$(run)" && code=0 || code=$?
check "release fast-forwards main" 0 "$code" "$out" "released: main is now at"
[ "$(git -C "$repo" rev-parse main)" = "$(git -C "$repo" rev-parse dev)" ] && echo "ok   main equals dev" || { echo "FAIL main does not equal dev"; failures=1; }
[ "$(git -C "$repo" rev-parse --abbrev-ref HEAD)" = "dev" ] && echo "ok   back on dev" || { echo "FAIL not back on dev"; failures=1; }

git -C "$repo" checkout -q main && git -C "$repo" -c user.name=t -c user.email=t@t commit -q --allow-empty -m "stray commit on main" && git -C "$repo" checkout -q dev
git -C "$repo" -c user.name=t -c user.email=t@t commit -q --allow-empty -m "more work on dev"
out="$(run)" && code=0 || code=$?
check "non fast-forward refused" 1 "$code" "$out" "not a fast-forward"
[ "$(git -C "$repo" rev-parse --abbrev-ref HEAD)" = "dev" ] && echo "ok   back on dev after the refusal" || { echo "FAIL not back on dev after the refusal"; failures=1; }

exit $failures
