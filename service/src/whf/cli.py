"""Command-line interface. Every command calls the same functions the API uses."""

from __future__ import annotations

import datetime as dt
import json
from pathlib import Path
from typing import Annotated

import typer

from whf import __version__
from whf.admin import add_project, add_vacation, set_capacity_default, set_capacity_override
from whf.ai.session import CopilotNarrator, NarratorConfig
from whf.ai.status import copilot_status_sync, resolve_cli_path, run_login
from whf.config import data_dir, db_path
from whf.data.generator import GeneratorConfig, generate
from whf.data.loader import load_generated, write_answer_key
from whf.db.connection import connect
from whf.narrate import narrate_run
from whf.pipeline import jsonable, list_runs, load_run, run_forecast

app = typer.Typer(help="WorkloadHub AI Forecasting", no_args_is_help=True)
data_app = typer.Typer(help="Dummy data commands", no_args_is_help=True)
runs_app = typer.Typer(help="Inspect stored runs", no_args_is_help=True)
capacity_app = typer.Typer(help="Capacity configuration", no_args_is_help=True)
vacations_app = typer.Typer(help="Planned time off", no_args_is_help=True)
projects_app = typer.Typer(help="Projects with start date and deadline", no_args_is_help=True)
for name, sub in [
    ("data", data_app),
    ("runs", runs_app),
    ("capacity", capacity_app),
    ("vacations", vacations_app),
    ("projects", projects_app),
]:
    app.add_typer(sub, name=name)

copilot_app = typer.Typer(help="GitHub Copilot sign-in and status", no_args_is_help=True)
app.add_typer(copilot_app, name="copilot")

DbOption = Annotated[Path | None, typer.Option("--db", help="SQLite database path (default: the app data folder)")]


def default_narrator(model: str | None = None) -> CopilotNarrator:
    return CopilotNarrator(NarratorConfig(model=model))


def _conn(db: Path | None):
    return connect(db or db_path())


def _date(value: str | None) -> dt.date | None:
    if not value:
        return None
    try:
        return dt.date.fromisoformat(value)
    except ValueError:
        typer.echo(f"error: invalid date {value!r}, expected YYYY-MM-DD")
        raise typer.Exit(code=2) from None


@app.callback()
def main() -> None:
    """WorkloadHub AI Forecasting service commands."""


@app.command()
def version() -> None:
    """Print the service version."""
    typer.echo(f"whf {__version__}")


@data_app.command("generate")
def data_generate(
    db: DbOption = None,
    seed: int = 42,
    months: int = 12,
    as_of: Annotated[str | None, typer.Option("--as-of")] = None,
    answer_key: Annotated[Path | None, typer.Option("--answer-key")] = None,
) -> None:
    """Generate dummy data (replaces existing data in the database)."""
    config = GeneratorConfig(seed=seed, months=months, as_of=_date(as_of) or GeneratorConfig().as_of)
    data = generate(config)
    conn = _conn(db)
    load_generated(conn, data)
    key_path = answer_key or (data_dir() / "answer_key.json")
    write_answer_key(key_path, data)
    typer.echo(
        f"generated {len(data.members)} members, {len(data.teams)} teams, {len(data.projects)} projects, "
        f"{len(data.tasks)} tasks; answer key at {key_path}"
    )


@app.command()
def run(
    team: Annotated[int, typer.Option("--team", help="Team id to forecast")],
    db: DbOption = None,
    as_of: Annotated[str | None, typer.Option("--as-of")] = None,
    requested_by: Annotated[int | None, typer.Option("--requested-by")] = None,
    as_json: Annotated[bool, typer.Option("--json")] = False,
    ai: Annotated[bool, typer.Option("--ai", help="Also ask Copilot for the narrative")] = False,
) -> None:
    """Run the two-week forecast for one team."""
    conn = _conn(db)
    try:
        result = run_forecast(conn, team_id=team, as_of=_date(as_of), requested_by=requested_by)
    except ValueError as exc:
        typer.echo(f"error: {exc}")
        raise typer.Exit(code=1) from exc
    if as_json:
        typer.echo(
            json.dumps(
                jsonable(
                    {
                        "run_id": result.run_id,
                        "team_id": result.team_id,
                        "as_of": result.as_of,
                        "weeks": list(result.weeks),
                        "champion": result.champion,
                        "backtest_mase": result.backtest_mase,
                        "forecasts": result.forecasts.to_dict(orient="records"),
                    }
                )
            )
        )
        if ai:
            outcome = narrate_run(
                conn,
                result.run_id,
                narrator=default_narrator(),
                progress=None if as_json else lambda m: typer.echo(f"  {m}"),
            )
            typer.echo(json.dumps(jsonable({"run_id": result.run_id, "ai_status": outcome.ai_status})))
        return
    typer.echo(
        f"run {result.run_id}: team {team}, weeks {result.weeks[0]} and {result.weeks[1]}, champion {result.champion} (MASE {result.backtest_mase:.2f})"
    )
    for row in result.forecasts.itertuples():
        flag = " OVERLOAD" if row.overload_hours > 0 else ""
        typer.echo(
            f"  member {row.member_id:>4} {row.week_start}: demand {row.demand_hours:6.1f}h  capacity {row.capacity_hours:5.1f}h{flag}"
        )
    if ai:
        outcome = narrate_run(
            conn,
            result.run_id,
            narrator=default_narrator(),
            progress=None if as_json else lambda m: typer.echo(f"  {m}"),
        )
        typer.echo(f"narrative: {outcome.ai_status}" + (f" ({outcome.error})" if outcome.error else ""))


@copilot_app.command("status")
def copilot_status_cmd(as_json: Annotated[bool, typer.Option("--json")] = False) -> None:
    """Show where the Copilot CLI is and whether the user is signed in."""
    status = copilot_status_sync()
    if as_json:
        typer.echo(json.dumps({**status.__dict__, "ready": status.ready}))
    else:
        typer.echo(f"cli: {status.cli_path or 'not found'} ({status.cli_source})")
        typer.echo(f"sign-in: {status.message}")
    raise typer.Exit(code=0 if status.ready else 3)


@copilot_app.command("login")
def copilot_login_cmd() -> None:
    """Sign in to GitHub Copilot with the CLI's device flow (interactive)."""
    cli_path, _source = resolve_cli_path()
    if cli_path is None:
        typer.echo("error: Copilot CLI not found; run `whf copilot status` after installing it or set COPILOT_CLI_PATH")
        raise typer.Exit(code=3)
    raise typer.Exit(code=run_login(cli_path))


@app.command()
def narrate(
    run_id: int,
    db: DbOption = None,
    model: Annotated[
        str | None, typer.Option("--model", help="Copilot model id; default is the account's default")
    ] = None,
    as_json: Annotated[bool, typer.Option("--json")] = False,
) -> None:
    """Ask Copilot for the narrative of a stored run and save it with the run."""
    conn = _conn(db)
    try:
        outcome = narrate_run(
            conn, run_id, narrator=default_narrator(model), progress=None if as_json else lambda m: typer.echo(f"  {m}")
        )
    except KeyError as exc:
        typer.echo(f"error: {exc}")
        raise typer.Exit(code=1) from exc
    if as_json:
        typer.echo(json.dumps(jsonable({**outcome.__dict__, "ai_status": outcome.ai_status, "run_id": run_id})))
    else:
        typer.echo(f"narrative: {outcome.ai_status}" + (f" ({outcome.error})" if outcome.error else ""))
    raise typer.Exit(code=0 if outcome.status != "failed" else 4)


@runs_app.command("list")
def runs_list(db: DbOption = None, team: Annotated[int | None, typer.Option("--team")] = None) -> None:
    """List stored runs."""
    df = list_runs(_conn(db), team)
    if df.empty:
        typer.echo("no runs")
        return
    for row in df.itertuples():
        typer.echo(
            f"{row.id:>4}  team {row.team_id:>3}  as_of {row.as_of}  {row.status:<6} {row.champion_model or '-':<15} ai={row.ai_status}"
        )


@runs_app.command("show")
def runs_show(run_id: int, db: DbOption = None, as_json: Annotated[bool, typer.Option("--json")] = False) -> None:
    """Show one run with its forecasts; facts are printed only with --json."""
    try:
        payload = load_run(_conn(db), run_id)
    except KeyError as exc:
        typer.echo(f"error: {exc}")
        raise typer.Exit(code=1) from exc
    if as_json:
        typer.echo(json.dumps(jsonable(payload)))
        return
    typer.echo(
        f"run {run_id}: team {payload['run']['team_id']} as_of {payload['run']['as_of']} champion {payload['run']['champion_model']}"
    )
    for row in payload["forecasts"]:
        typer.echo(
            f"  member {row['member_id']:>4} {row['week_start']}: demand {row['demand_hours']:6.1f}h capacity {row['capacity_hours']:5.1f}h overload {row['overload_hours']:5.1f}h"
        )


@app.command()
def export(
    run_id: int,
    out: Annotated[Path, typer.Option("--out")],
    db: DbOption = None,
    fmt: Annotated[str, typer.Option("--format")] = "csv",
) -> None:
    """Export a run's forecasts to CSV or JSON."""
    try:
        payload = load_run(_conn(db), run_id)
    except KeyError as exc:
        typer.echo(f"error: {exc}")
        raise typer.Exit(code=1) from exc
    out.parent.mkdir(parents=True, exist_ok=True)
    if fmt == "json":
        out.write_text(json.dumps(jsonable(payload), indent=1), encoding="utf-8")
    elif fmt == "csv":
        import pandas as pd

        pd.DataFrame(payload["forecasts"]).to_csv(out, index=False)
    else:
        typer.echo("error: --format must be csv or json")
        raise typer.Exit(code=2)
    typer.echo(f"wrote {out}")


@capacity_app.command("default")
def capacity_default(hours: Annotated[float, typer.Option("--hours")], db: DbOption = None) -> None:
    """Set the default weekly capacity for everyone."""
    set_capacity_default(_conn(db), hours)
    typer.echo(f"default weekly capacity set to {hours}h")


@capacity_app.command("set")
def capacity_set(
    member: Annotated[int, typer.Option("--member")],
    hours: Annotated[float, typer.Option("--hours")],
    db: DbOption = None,
    week: Annotated[
        str | None, typer.Option("--week", help="Monday of the week; omit for a permanent override")
    ] = None,
    reason: Annotated[str | None, typer.Option("--reason")] = None,
) -> None:
    """Override a member's weekly capacity, permanently or for one week."""
    week_date = _date(week)
    set_capacity_override(_conn(db), member, hours, week_date, reason)
    week_iso = week_date.isoformat() if week_date else None
    typer.echo(f"member {member}: {hours}h" + (f" for week {week_iso}" if week_iso else " permanently"))


@vacations_app.command("add")
def vacations_add(
    member: Annotated[int, typer.Option("--member")],
    start: Annotated[str, typer.Option("--start")],
    end: Annotated[str, typer.Option("--end")],
    db: DbOption = None,
    kind: Annotated[str, typer.Option("--type")] = "vacation",
) -> None:
    """Add planned time off for a member."""
    start_date = _date(start)
    end_date = _date(end)
    try:
        add_vacation(_conn(db), member, start_date, end_date, kind)
    except ValueError as exc:
        typer.echo(f"error: {exc}")
        raise typer.Exit(code=2) from exc
    typer.echo(f"member {member}: {kind} {start_date.isoformat()} to {end_date.isoformat()}")


@projects_app.command("add")
def projects_add(
    name: Annotated[str, typer.Option("--name")],
    department: Annotated[int, typer.Option("--department")],
    start: Annotated[str, typer.Option("--start")],
    deadline: Annotated[str, typer.Option("--deadline")],
    team: Annotated[list[int], typer.Option("--team", help="Team id; repeat for several teams")],
    db: DbOption = None,
    kind: Annotated[str, typer.Option("--type")] = "delivery",
    created_by: Annotated[int | None, typer.Option("--created-by")] = None,
) -> None:
    """Create a project with a start date, a deadline and its teams."""
    try:
        project_id = add_project(_conn(db), name, department, _date(start), _date(deadline), team, kind, created_by)
    except ValueError as exc:
        typer.echo(f"error: {exc}")
        raise typer.Exit(code=2) from exc
    typer.echo(f"project {project_id} '{name}' {start} to {deadline} for teams {team}")


@app.command()
def serve(
    db: DbOption = None,
    port: int = 0,
    token: Annotated[
        str | None, typer.Option("--token", help="Token the client must send; generated when omitted")
    ] = None,
) -> None:
    """Start the local API on 127.0.0.1 and print the port and token as one JSON line."""
    import socket

    import uvicorn

    from whf.api import create_app, new_token

    token = token or new_token()
    if port == 0:
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
    typer.echo(json.dumps({"port": port, "token": token}), nl=True)
    uvicorn.run(create_app(db or db_path(), token), host="127.0.0.1", port=port, log_level="warning")
