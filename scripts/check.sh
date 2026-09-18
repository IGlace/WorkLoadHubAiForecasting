#!/usr/bin/env bash
# The gate, the same step as scripts/check.ps1 and .github/workflows/ci.yml (which has no remote to fire
# on): the Java module's `mvn verify`. Running this by hand is the only gate there is. A step whose tool
# is missing is skipped with a message; a missing container engine fails the gate.
# Unlike check.ps1, which stops at the first failing step, this script runs every step and reports each
# failure; both exit non-zero on any failure.
#
# Usage: bash scripts/check.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
failed=0
ran=0

# The PostgreSQL tests are the database tests: without an engine they fail, they do not skip, so say so at
# the door instead of seventeen minutes in. Inside the development box the engine is the mounted socket.
engine_reachable() {
    local sock="${DOCKER_HOST:-}"; sock="${sock#unix://}"
    if [ -n "${DOCKER_HOST:-}" ] && [ -S "$sock" ]; then return 0; fi
    if [ -S /var/run/docker.sock ]; then return 0; fi
    if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then return 0; fi
    if command -v podman >/dev/null 2>&1 && podman info >/dev/null 2>&1; then return 0; fi
    return 1
}
if ! engine_reachable; then
    echo "no container engine is reachable: the gate needs Docker or podman (server/README.md, The development container)" >&2
    exit 1
fi

run_step() {
    local name="$1"; shift
    echo "==> $name"
    local start; start=$(date +%s)
    if "$@"; then
        echo "OK   $name in $(( $(date +%s) - start ))s"
    else
        local code=$?
        echo "FAIL $name after $(( $(date +%s) - start ))s (exit $code)"
        failed=1
    fi
}

if command -v mvn >/dev/null 2>&1; then
    ran=$((ran + 1))
    run_step "server (mvn verify)" bash -c "cd '$root/server' && mvn -B -q verify"
else
    echo "SKIP server (mvn verify): mvn not found on PATH"
fi

if [ "$ran" -eq 0 ]; then
    echo "gate ran nothing: install mvn"
    exit 1
fi

exit $failed
