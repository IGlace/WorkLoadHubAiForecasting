from typer.testing import CliRunner

from whf import __version__
from whf.cli import app


def test_version_command_prints_version() -> None:
    result = CliRunner().invoke(app, ["version"])
    assert result.exit_code == 0
    assert result.output.strip() == f"whf {__version__}"
