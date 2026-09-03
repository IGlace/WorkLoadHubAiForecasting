import json

from typer.testing import CliRunner

from whf.cli import app

runner = CliRunner()


def _generate(db_path) -> None:
    result = runner.invoke(app, ["data", "generate", "--db", str(db_path), "--seed", "3", "--months", "6"])
    assert result.exit_code == 0, result.output
    assert "tasks" in result.output


def test_generate_run_list_show_export(tmp_path) -> None:
    db = tmp_path / "t.db"
    _generate(db)
    run = runner.invoke(app, ["run", "--db", str(db), "--team", "1", "--as-of", "2026-09-03", "--json"])
    assert run.exit_code == 0, run.output
    payload = json.loads(run.output)
    assert payload["run_id"] == 1 and len(payload["forecasts"]) >= 8
    listed = runner.invoke(app, ["runs", "list", "--db", str(db)])
    assert listed.exit_code == 0 and "team   1" in listed.output
    shown = runner.invoke(app, ["runs", "show", "1", "--db", str(db), "--json"])
    assert shown.exit_code == 0
    assert json.loads(shown.output)["run"]["id"] == 1
    out = tmp_path / "f.csv"
    exported = runner.invoke(app, ["export", "1", "--db", str(db), "--format", "csv", "--out", str(out)])
    assert exported.exit_code == 0 and out.exists()
    assert out.read_text().splitlines()[0].startswith("run_id,member_id,week_start")


def test_capacity_vacations_projects_commands(tmp_path) -> None:
    db = tmp_path / "t.db"
    _generate(db)
    assert runner.invoke(app, ["capacity", "default", "--db", str(db), "--hours", "38"]).exit_code == 0
    assert (
        runner.invoke(
            app,
            [
                "capacity",
                "set",
                "--db",
                str(db),
                "--member",
                "2",
                "--hours",
                "20",
                "--week",
                "2026-09-14",
                "--reason",
                "internal",
            ],
        ).exit_code
        == 0
    )
    assert (
        runner.invoke(
            app, ["vacations", "add", "--db", str(db), "--member", "2", "--start", "2026-09-21", "--end", "2026-09-25"]
        ).exit_code
        == 0
    )
    added = runner.invoke(
        app,
        [
            "projects",
            "add",
            "--db",
            str(db),
            "--name",
            "New CRM",
            "--department",
            "1",
            "--start",
            "2026-09-10",
            "--deadline",
            "2026-11-30",
            "--team",
            "1",
            "--team",
            "2",
        ],
    )
    assert added.exit_code == 0, added.output
    from whf.db.connection import connect
    from whf.db.repo import read_df

    conn = connect(db)
    assert read_df(conn, "SELECT weekly_hours FROM capacity_defaults")["weekly_hours"][0] == 38.0
    assert len(read_df(conn, "SELECT * FROM capacity_overrides WHERE member_id = 2")) == 1
    assert len(read_df(conn, "SELECT * FROM vacations WHERE member_id = 2 AND start_date = '2026-09-21'")) == 1
    assert len(read_df(conn, "SELECT * FROM project_teams WHERE project_id = (SELECT MAX(id) FROM projects)")) == 2


def test_run_with_unknown_team_fails_cleanly(tmp_path) -> None:
    db = tmp_path / "t.db"
    _generate(db)
    result = runner.invoke(app, ["run", "--db", str(db), "--team", "999", "--as-of", "2026-09-03"])
    assert result.exit_code == 1
    assert "no counted members" in result.output
