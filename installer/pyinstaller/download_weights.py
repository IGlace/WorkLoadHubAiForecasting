"""Download the pinned Chronos-2 weights into the frozen folder. Build-time only.

The frozen service loads the model from `<dist>/whf/models/chronos-2` and never contacts the
network for it, so the weights have to be fetched here, at the pinned revision the adapter
declares, while the build machine still has a network.

Usage: python installer/pyinstaller/download_weights.py <dist-dir>/whf
"""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

from huggingface_hub import snapshot_download

from whf.models.chronos2 import WEIGHTS_REPO, WEIGHTS_REVISION

LICENCE_SOURCE = Path(__file__).resolve().parent / "LICENSE-Apache-2.0.txt"
REQUIRED = ("model.safetensors", "config.json", "LICENSE")


def main(dist: str) -> int:
    target = Path(dist) / "models" / "chronos-2"
    target.mkdir(parents=True, exist_ok=True)
    snapshot_download(
        WEIGHTS_REPO,
        revision=WEIGHTS_REVISION,
        local_dir=str(target),
        allow_patterns=["*.json", "*.safetensors", "LICENSE*", "README.md"],
    )
    # The model repository carries no licence file of its own at this revision, so the weights we
    # redistribute inside the installer would ship without their licence: put the Apache-2.0 text
    # (the licence Chronos-2 is published under) beside them ourselves.
    shutil.copyfile(LICENCE_SOURCE, target / "LICENSE")
    # `local_dir` leaves a `.cache/huggingface` bookkeeping tree inside the folder we bundle; it is
    # a second copy of nothing useful and confuses the completeness listing below.
    shutil.rmtree(target / ".cache", ignore_errors=True)
    files = sorted(p.name for p in target.iterdir())
    missing = [name for name in REQUIRED if name not in files]
    if missing:
        raise SystemExit(f"weights incomplete in {target}: missing {missing}, found {files}")
    print(f"chronos-2 weights at {target}: {files}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    raise SystemExit(main(sys.argv[1]))
