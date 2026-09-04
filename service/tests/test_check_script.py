"""The local gate stands in for CI, so the list of checks it runs is worth pinning down."""

import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "check.ps1"
pytestmark = pytest.mark.skipif(shutil.which("pwsh") is None, reason="pwsh is not on PATH")


def _output(*args: str) -> str:
    done = subprocess.run(
        ["pwsh", "-NoProfile", "-File", str(SCRIPT), "-DryRun", *args],
        capture_output=True,
        text=True,
        check=True,
    )
    return done.stdout


def _dry_run(*args: str) -> list[str]:
    return [line.strip() for line in _output(*args).splitlines() if line.strip().startswith("==>")]


def test_the_fast_gate_runs_the_cheap_checks_first_and_skips_the_slow_suite() -> None:
    steps = " | ".join(_dry_run())
    assert "ruff check" in steps and "app lint" in steps and "pytest (fast)" in steps
    assert "pytest (slow)" not in steps and "installer" not in steps
    # cheapest first, so a lint error does not cost two minutes of pytest
    assert steps.index("ruff check") < steps.index("pytest (fast)")
    assert steps.index("app lint") < steps.index("pytest (fast)")


def test_both_pytest_steps_run_in_parallel() -> None:
    """Serial the suite is 254 s, which is too long to sit in front of every commit; -n 6 halves it."""
    lines = [line for line in _output("-Full").splitlines() if "pytest" in line and "-n" in line]
    assert len(lines) == 2, f"expected the fast and slow pytest steps to be parallel, got: {lines}"


def test_full_adds_the_slow_suite_and_the_app_build() -> None:
    steps = " | ".join(_dry_run("-Full"))
    assert "pytest (slow)" in steps and "app build" in steps
    assert "installer" not in steps


def test_package_adds_the_installer_build_last() -> None:
    steps = _dry_run("-Full", "-Package")
    assert "installer" in steps[-1]


def test_a_dry_run_changes_nothing_and_succeeds() -> None:
    done = subprocess.run(["pwsh", "-NoProfile", "-File", str(SCRIPT), "-DryRun"], capture_output=True, text=True)
    assert done.returncode == 0
