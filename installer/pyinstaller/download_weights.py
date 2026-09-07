"""Download the pinned Chronos-2 weights into the frozen folder. Build-time only.

The frozen service loads the model from `<dist>/whf/models/chronos-2` and never contacts the
network for it, so the weights have to be fetched here, at the pinned revision the adapter
declares, while the build machine still has a network.

Usage: python installer/pyinstaller/download_weights.py <dist-dir>/whf
"""

from __future__ import annotations

import sys
from pathlib import Path

from huggingface_hub import snapshot_download

from whf.models.chronos2 import WEIGHTS_REPO, WEIGHTS_REVISION


def main(dist: str) -> int:
    target = Path(dist) / "models" / "chronos-2"
    target.mkdir(parents=True, exist_ok=True)
    snapshot_download(
        WEIGHTS_REPO,
        revision=WEIGHTS_REVISION,
        local_dir=str(target),
        allow_patterns=["*.json", "*.safetensors", "LICENSE*", "README.md"],
    )
    files = sorted(p.name for p in target.iterdir())
    if "model.safetensors" not in files or "config.json" not in files:
        raise SystemExit(f"weights incomplete in {target}: {files}")
    print(f"chronos-2 weights at {target}: {files}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    raise SystemExit(main(sys.argv[1]))
