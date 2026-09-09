#!/usr/bin/env bash
# The Java module's slice of the local gate, for Linux/WSL where pwsh (and therefore
# scripts/check.ps1) may not be available. Mirrors scripts/check.ps1's "server (mvn verify)"
# step: same command, same skip behaviour when mvn is absent, same timing output. Keep the two in
# step when either changes.
#
# Usage: bash scripts/check.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
server="$root/server"

if ! command -v mvn >/dev/null 2>&1; then
    echo "SKIP server (mvn verify): mvn not found on PATH"
    exit 0
fi

echo "==> server (mvn verify)"
start=$(date +%s)
if (cd "$server" && mvn -B -q verify); then
    elapsed=$(( $(date +%s) - start ))
    echo "OK   server (mvn verify) in ${elapsed}s"
else
    code=$?
    elapsed=$(( $(date +%s) - start ))
    echo "FAIL server (mvn verify) after ${elapsed}s (exit $code)"
    exit "$code"
fi
