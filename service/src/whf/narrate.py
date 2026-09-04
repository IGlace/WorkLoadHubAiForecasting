"""Attach a Copilot narrative to a stored run."""

from __future__ import annotations

import datetime as dt
import json
import sqlite3
from collections.abc import Callable
from dataclasses import asdict

from whf.ai.progress import ProgressEvent
from whf.ai.session import NarrativeOutcome, Narrator, default_narrator
from whf.db.repo import read_df


class RunNotFoundError(KeyError):
    """No run with this id exists."""


class RunHasNoFactsError(LookupError):
    """The run exists but has no stored facts to narrate (the forecast did not save them)."""


def narrate_run(
    conn: sqlite3.Connection,
    run_id: int,
    narrator: Narrator | None = None,
    progress: Callable[[ProgressEvent], None] | None = None,
    on_valid: Callable[[], None] | None = None,
) -> NarrativeOutcome:
    """Attach a narrative to `run_id`.

    `on_valid`, if given, runs once the run is known to exist and have facts, before Copilot is
    asked anything: the caller uses it to start tracking progress only for requests worth tracking.
    """
    run_rows = read_df(conn, "SELECT id FROM runs WHERE id = ?", (run_id,))
    if run_rows.empty:
        raise RunNotFoundError(f"run {run_id} not found")
    facts_rows = read_df(conn, "SELECT json FROM run_facts WHERE run_id = ?", (run_id,))
    if facts_rows.empty:
        raise RunHasNoFactsError(f"run {run_id} exists but has no stored facts to narrate")
    facts = json.loads(facts_rows["json"][0])
    if on_valid is not None:
        on_valid()
    outcome = (narrator or default_narrator()).narrate_sync(facts, progress)
    document = {**asdict(outcome), "generated_at": dt.datetime.now().isoformat(timespec="seconds")}
    try:
        conn.execute(
            "INSERT OR REPLACE INTO run_narratives (run_id, json) VALUES (?, ?)", (run_id, json.dumps(document))
        )
        conn.execute("UPDATE runs SET ai_status = ? WHERE id = ?", (outcome.ai_status, run_id))
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    return outcome
