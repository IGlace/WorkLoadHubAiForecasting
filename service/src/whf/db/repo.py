"""Small helpers between Python values, SQLite rows and pandas frames."""

from __future__ import annotations

import datetime as dt
import sqlite3
from typing import Any

import pandas as pd


def _to_sql(value: Any) -> Any:
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, dt.datetime):
        return value.isoformat(timespec="seconds")
    if isinstance(value, dt.date):
        return value.isoformat()
    return value


def insert_rows(conn: sqlite3.Connection, table: str, rows: list[dict[str, Any]], commit: bool = True) -> int:
    if not rows:
        return 0
    columns = list(rows[0].keys())
    placeholders = ", ".join("?" for _ in columns)
    sql = f"INSERT INTO {table} ({', '.join(columns)}) VALUES ({placeholders})"
    conn.executemany(sql, [[_to_sql(row.get(c)) for c in columns] for row in rows])
    if commit:
        conn.commit()
    return len(rows)


def read_df(conn: sqlite3.Connection, sql: str, params: tuple = ()) -> pd.DataFrame:
    cur = conn.execute(sql, params)
    columns = [c[0] for c in cur.description]
    return pd.DataFrame([tuple(r) for r in cur.fetchall()], columns=columns)


def _parse_date(value: Any) -> dt.date | None:
    if value is None or value == "" or (isinstance(value, float) and pd.isna(value)):
        return None
    if isinstance(value, dt.date):
        return value
    return dt.date.fromisoformat(str(value)[:10])


def with_dates(df: pd.DataFrame, columns: list[str]) -> pd.DataFrame:
    out = df.copy()
    for c in columns:
        if c in out.columns:
            out[c] = [_parse_date(v) for v in out[c]]
            out[c] = out[c].astype(object)
    return out


def table_names(conn: sqlite3.Connection) -> list[str]:
    rows = conn.execute("SELECT name FROM sqlite_master WHERE type = 'table'").fetchall()
    return [r[0] for r in rows]
