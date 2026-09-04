#!/usr/bin/env bash
# Freeze the forecast service with PyInstaller, bundle the pinned Copilot CLI, smoke-test the result.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/service/dist"
cd "$ROOT/service"
uv sync --group build
rm -rf "$DIST/whf"
# Freeze: package the service and its Python dependencies into a one-folder executable at $DIST/whf.
uv run pyinstaller --noconfirm --clean --distpath "$DIST" --workpath "$ROOT/installer/pyinstaller/build" "$ROOT/installer/pyinstaller/whf.spec"
if [ "${WHF_SKIP_CLI_DOWNLOAD:-0}" != "1" ]; then
  # CLI download: fetch the pinned GitHub Copilot CLI runtime into the frozen folder so the app never
  # needs the CLI to be separately installed on the target machine (skip with WHF_SKIP_CLI_DOWNLOAD=1).
  export COPILOT_CLI_EXTRACT_DIR="$DIST/whf/copilot-cli"
  uv run python -m copilot download-runtime
  ls -l "$COPILOT_CLI_EXTRACT_DIR"
fi
# Smoke: launch the frozen exe and run a real forecast through it, to catch packaging regressions early.
uv run python "$ROOT/installer/pyinstaller/smoke_frozen.py" "$DIST/whf"
echo "service frozen at $DIST/whf"
