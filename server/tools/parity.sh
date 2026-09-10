#!/usr/bin/env bash
# Run the parity procedure on one WorkloadHub export: Java eval and Python eval on the same data, then compare.
# The Python harness lives on the archive branch: ARCHIVE_DIR is a checkout of archive/python-desktop-v1
# (for example: git worktree add ../whf-archive archive/python-desktop-v1).
# Usage: server/tools/parity.sh EXPORT_JSON OUT_DIR ARCHIVE_DIR [AS_OF]
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
export_json="$1"; out="$2"; archive="$3"; as_of="${4:-}"
if [ ! -d "$archive/service" ]; then
  echo "error: $archive is not a checkout of archive/python-desktop-v1 (no service/ directory)" >&2
  exit 2
fi
mkdir -p "$out/java" "$out/python"
jar="$(ls "$here"/forecast-cli/target/workloadhub-forecast-cli-*.jar 2>/dev/null | head -n 1 || true)"
if [ -z "$jar" ]; then (cd "$here" && mvn -B -q -DskipTests package); jar="$(ls "$here"/forecast-cli/target/workloadhub-forecast-cli-*.jar | head -n 1)"; fi
db="$out/java/parity.db"
rm -f "$db"
java -jar "$jar" init-db --db "$db"
java -jar "$jar" import --db "$db" "$export_json"
if [ -n "$as_of" ]; then java -jar "$jar" eval --db "$db" --as-of "$as_of" --models xgboost,seasonal_naive --out "$out/java";
else java -jar "$jar" eval --db "$db" --models xgboost,seasonal_naive --out "$out/java"; fi
as_of="$(sed -n '1s/.*as of //p' "$out/java/summary.md")"
pydb="$out/python/parity.db"
rm -f "$pydb"
(cd "$archive/service" && uv run whf import-workloadhub "$export_json" --db "$pydb" \
  && uv run whf eval --db "$pydb" --as-of "$as_of" --models gbm,seasonal_naive --out "$out/python")
python3 "$here/tools/parity_compare.py" "$out/python/scores.csv" "$out/java/scores.csv" --out "$out/parity.md"
