"""Locations of local data. Override everything with the WHF_HOME environment variable."""

from __future__ import annotations

import os
import sys
from pathlib import Path

APP_DIR_NAME = "WorkloadHubForecast"


def data_dir() -> Path:
    """Directory holding the database, logs and exports. Created on demand."""
    env = os.environ.get("WHF_HOME")
    if env:
        base = Path(env)
    elif sys.platform == "win32":
        base = Path(os.environ.get("LOCALAPPDATA", Path.home())) / APP_DIR_NAME
    else:
        base = Path.home() / ".local" / "share" / APP_DIR_NAME
    base.mkdir(parents=True, exist_ok=True)
    return base


def db_path() -> Path:
    return data_dir() / "whf.db"
