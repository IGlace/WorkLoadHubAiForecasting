#!/usr/bin/env bash
# Freeze the forecast service with PyInstaller, bundle the pinned Copilot CLI, smoke-test the result.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/service/dist"
cd "$ROOT/service"
uv sync --group build
rm -rf "$DIST/whf"
uv run pyinstaller --noconfirm --clean --distpath "$DIST" --workpath "$ROOT/installer/pyinstaller/build" "$ROOT/installer/pyinstaller/whf.spec"
if [ "${WHF_SKIP_CLI_DOWNLOAD:-0}" != "1" ]; then
  export COPILOT_CLI_EXTRACT_DIR="$DIST/whf/copilot-cli"
  uv run python -m copilot download-runtime
  ls -l "$COPILOT_CLI_EXTRACT_DIR"
fi
uv run python "$ROOT/installer/pyinstaller/smoke_frozen.py" "$DIST/whf"
echo "service frozen at $DIST/whf"
