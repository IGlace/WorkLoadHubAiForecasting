#!/usr/bin/env bash
# Freeze the forecast service with PyInstaller, bundle the pinned Copilot CLI and the pinned
# Chronos-2 weights, smoke-test the result.
# Usage: bash scripts/build-service.sh   (WHF_SKIP_CLI_DOWNLOAD=1 and/or WHF_SKIP_MODEL_DOWNLOAD=1 to skip a download)
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
if [ "${WHF_SKIP_MODEL_DOWNLOAD:-0}" != "1" ]; then
  # Chronos-2 weights: pinned revision, bundled next to the CLI so the service never downloads at run time
  # (skip with WHF_SKIP_MODEL_DOWNLOAD=1; the frozen service then reports chronos2 as unavailable).
  uv run python "$ROOT/installer/pyinstaller/download_weights.py" "$DIST/whf"
fi
# Smoke: launch the frozen exe and run a real forecast through it, to catch packaging regressions early.
uv run python "$ROOT/installer/pyinstaller/smoke_frozen.py" "$DIST/whf"
echo "service frozen at $DIST/whf"
