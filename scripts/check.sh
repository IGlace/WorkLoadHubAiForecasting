#!/usr/bin/env bash
# The local gate, the same steps as CI (.github/workflows/ci.yml) and scripts/check.ps1: the Java module's
# `mvn verify` and the parity gate's test. A step whose tool is missing is skipped with a message.
# Unlike check.ps1, which stops at the first failing step, this script runs every step and reports each
# failure; both exit non-zero on any failure.
#
# Usage: bash scripts/check.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
failed=0
ran=0

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

if command -v uv >/dev/null 2>&1; then
    ran=$((ran + 1))
    run_step "tools (parity gate test)" bash -c "cd '$root' && uv run --python 3.11 --with pytest pytest server/tools/tests -q"
else
    echo "SKIP tools (parity gate test): uv not found on PATH"
fi

if [ "$ran" -eq 0 ]; then
    echo "gate ran nothing: install mvn and uv"
    exit 1
fi

exit $failed
