"""Start-time hygiene: importing the CLI must not pull in the Copilot SDK eagerly."""

from __future__ import annotations

import subprocess
import sys


def test_importing_cli_does_not_import_copilot() -> None:
    subprocess.run(
        [sys.executable, "-c", "import sys, whf.cli; assert 'copilot' not in sys.modules"],
        check=True,
    )
