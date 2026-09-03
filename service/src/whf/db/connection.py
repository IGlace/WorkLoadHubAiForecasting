"""SQLite connection with the schema applied."""

from __future__ import annotations

import sqlite3
from importlib import resources
from pathlib import Path

from whf.config import db_path


def _schema_sql() -> str:
    return resources.files("whf.db").joinpath("schema.sql").read_text(encoding="utf-8")


def connect(path: str | Path | None = None) -> sqlite3.Connection:
    """Open (and initialise) the database. Pass ':memory:' for tests."""
    target = ":memory:" if path == ":memory:" else str(path or db_path())
    conn = sqlite3.connect(target)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.executescript(_schema_sql())
    conn.commit()
    return conn
