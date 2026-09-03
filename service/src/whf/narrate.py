"""Attach a Copilot narrative to a stored run."""

from __future__ import annotations

import datetime as dt
import json
import sqlite3
from collections.abc import Callable
from dataclasses import asdict

from whf.ai.session import CopilotNarrator, NarrativeOutcome, Narrator
from whf.db.repo import read_df


def default_narrator() -> CopilotNarrator:
    return CopilotNarrator()


def narrate_run(
    conn: sqlite3.Connection,
    run_id: int,
    narrator: Narrator | None = None,
    progress: Callable[[str], None] | None = None,
) -> NarrativeOutcome:
    facts_rows = read_df(conn, "SELECT json FROM run_facts WHERE run_id = ?", (run_id,))
    if facts_rows.empty:
        raise KeyError(f"run {run_id} not found or has no facts")
    facts = json.loads(facts_rows["json"][0])
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
