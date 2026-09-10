#!/usr/bin/env python3
"""Compare the Python harness's scores.csv with the Java harness's: the parity gate of the Java module design, section 13.

Usage: parity_compare.py PYTHON_SCORES JAVA_SCORES [--tolerance 0.10] [--python-booster gbm] [--java-booster xgboost] [--out parity.md]
Exit 0 when the booster's mean MASE (horizons 1 and 2, every origin) agrees within the tolerance and both sides pick the same
champion; 1 otherwise; 2 on a usage error. Standard library only.
"""

from __future__ import annotations

import argparse
import csv
import math
import sys
from pathlib import Path

FLOOR = "seasonal_naive"
HORIZONS = {"1", "2"}


def side(path: Path, booster: str) -> dict:
    rows = [
        r
        for r in csv.DictReader(path.open(encoding="utf-8"))
        if r["metric"] == "mase" and r["horizon"] in HORIZONS and r["value"] != ""
    ]
    mine = [float(r["value"]) for r in rows if r["model"] == booster and not math.isnan(float(r["value"]))]
    floor = [float(r["value"]) for r in rows if r["model"] == FLOOR and not math.isnan(float(r["value"]))]
    origins = {r["origin"] for r in rows if r["model"] == booster}
    mean = sum(mine) / len(mine) if mine else float("nan")
    return {
        "booster": booster,
        "mean": mean,
        "floor_mean": sum(floor) / len(floor) if floor else float("nan"),
        "origins": len(origins),
        "champion": "floor" if not mine or mean >= 1.0 else "booster",
    }


def report(py: dict, java: dict, tolerance: float) -> tuple[str, bool]:
    gap = abs(py["mean"] - java["mean"]) if not (math.isnan(py["mean"]) or math.isnan(java["mean"])) else float("nan")
    ok = not math.isnan(gap) and gap <= tolerance and py["champion"] == java["champion"]
    lines = [
        "# Parity: Python harness against Java harness",
        "",
        "| side | booster | mean MASE (h1, h2) | floor mean MASE | origins | champion |",
        "|---|---|---|---|---|---|",
        f"| python | {py['booster']} | {py['mean']:.3f} | {py['floor_mean']:.3f} | {py['origins']} | {py['champion']} |",
        f"| java | {java['booster']} | {java['mean']:.3f} | {java['floor_mean']:.3f} | {java['origins']} | {java['champion']} |",
        "",
        f"Absolute difference of the booster's mean MASE: {gap:.3f} (tolerance {tolerance:.2f}).",
        f"Champion decision: python {py['champion']}, java {java['champion']}.",
        "",
        "PASS"
        if ok
        else "FAIL: "
        + (
            "no booster rows on one side"
            if math.isnan(gap)
            else "gap above tolerance"
            if gap > tolerance
            else "champions differ"
        ),
        "",
    ]
    return "\n".join(lines), ok


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("python_scores", type=Path)
    parser.add_argument("java_scores", type=Path)
    parser.add_argument("--tolerance", type=float, default=0.10)
    parser.add_argument("--python-booster", default="gbm")
    parser.add_argument("--java-booster", default="xgboost")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args(argv)
    for path in (args.python_scores, args.java_scores):
        if not path.is_file():
            print(f"error: {path} is not a file", file=sys.stderr)
            return 2
    text, ok = report(
        side(args.python_scores, args.python_booster), side(args.java_scores, args.java_booster), args.tolerance
    )
    print(text)
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
