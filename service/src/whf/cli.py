"""Command-line interface. Every command calls the same functions the API uses."""

from __future__ import annotations

import typer

from whf import __version__

app = typer.Typer(help="WorkloadHub AI Forecasting", no_args_is_help=True)


@app.callback()
def main() -> None:
    """WorkloadHub AI Forecasting service commands."""


@app.command()
def version() -> None:
    """Print the service version."""
    typer.echo(f"whf {__version__}")
