"""The parity gate: same data, Python harness against Java harness, mean MASE within tolerance and the same champion."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "server" / "tools" / "parity_compare.py"
HEADER = "model,horizon,origin,metric,value\n"


def scores(path: Path, booster: str, values: list[float]) -> Path:
    lines = [HEADER]
    for i, v in enumerate(values):
        origin = f"2026-0{1 + i % 8}-0{1 + i % 7}"
        lines.append(f"{booster},1,{origin},mase,{v}\n")
        lines.append(f"{booster},2,{origin},mase,{v + 0.02}\n")
        lines.append(f"seasonal_naive,1,{origin},mase,1.0\n")
        lines.append(f"{booster},1,{origin},mae,3.0\n")
    path.write_text("".join(lines), encoding="utf-8")
    return path


def floor_only(path: Path) -> Path:
    """A side with no booster rows at all: only the floor (seasonal_naive) was scored."""
    lines = [HEADER]
    for i in range(3):
        origin = f"2026-0{1 + i % 8}-0{1 + i % 7}"
        lines.append(f"seasonal_naive,1,{origin},mase,1.0\n")
        lines.append(f"seasonal_naive,2,{origin},mase,1.0\n")
    path.write_text("".join(lines), encoding="utf-8")
    return path


def run(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True, check=False)


def test_passes_within_tolerance_and_same_champion(tmp_path: Path) -> None:
    py = scores(tmp_path / "py.csv", "gbm", [0.80, 0.85, 0.90])
    java = scores(tmp_path / "java.csv", "xgboost", [0.86, 0.90, 0.93])
    result = run(str(py), str(java))
    assert result.returncode == 0, result.stdout + result.stderr
    assert "PASS" in result.stdout and "| python | gbm |" in result.stdout and "| java | xgboost |" in result.stdout


def test_fails_when_the_gap_exceeds_the_tolerance(tmp_path: Path) -> None:
    py = scores(tmp_path / "py.csv", "gbm", [0.80, 0.85])
    far = scores(tmp_path / "far.csv", "xgboost", [0.95, 0.99])
    assert run(str(py), str(far)).returncode == 1
    assert run(str(py), str(far), "--tolerance", "0.2").returncode == 0


def test_fails_when_the_champions_differ_even_within_tolerance(tmp_path: Path) -> None:
    # mean MASE: python 0.97 (booster wins), java 1.02 (floor wins) - a 0.05 gap, well inside the
    # default 0.10 tolerance, so only the champion mismatch can fail this.
    py = scores(tmp_path / "py.csv", "gbm", [0.96, 0.96])
    java = scores(tmp_path / "java.csv", "xgboost", [1.01, 1.01])
    result = run(str(py), str(java))
    assert result.returncode == 1
    assert "FAIL: champions differ" in result.stdout, result.stdout


def test_fails_when_one_side_has_no_booster_rows(tmp_path: Path) -> None:
    py = scores(tmp_path / "py.csv", "gbm", [0.80, 0.85])
    java = floor_only(tmp_path / "java.csv")
    result = run(str(py), str(java))
    assert result.returncode == 1
    assert "no booster rows" in result.stdout, result.stdout


def test_passes_exactly_on_the_tolerance_boundary(tmp_path: Path) -> None:
    # mean MASE: python 0.81, java 0.91 - a 0.10 gap, exactly the default tolerance.
    py = scores(tmp_path / "py.csv", "gbm", [0.80])
    java = scores(tmp_path / "java.csv", "xgboost", [0.90])
    result = run(str(py), str(java))
    assert result.returncode == 0, result.stdout + result.stderr
    assert "PASS" in result.stdout


def test_usage_errors(tmp_path: Path) -> None:
    assert run(str(tmp_path / "missing.csv"), str(tmp_path / "missing2.csv")).returncode == 2
    out = tmp_path / "parity.md"
    py = scores(tmp_path / "py.csv", "gbm", [0.8])
    java = scores(tmp_path / "java.csv", "xgboost", [0.8])
    assert run(str(py), str(java), "--out", str(out)).returncode == 0
    assert out.read_text(encoding="utf-8").startswith("# Parity")
