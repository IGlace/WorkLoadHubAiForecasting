"""Cross-check every number Copilot wrote against the facts it was given."""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from whf.ai.schema import Narrative

SMALL_INTEGER_ALLOWANCE = 20
_DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
_TIME = re.compile(r"\b\d{1,2}:\d{2}(?::\d{2})?\b")
_PERCENT = re.compile(r"-?\d+(?:[.,]\d+)?\s*%")
_NUMBER = re.compile(r"(?<![\w.])-?\d+(?:[.,]\d+)?(?![\w.]*\d)")


def _walk(value: Any, out: set[float]) -> None:
    if isinstance(value, bool):
        return
    if isinstance(value, (int, float)):
        out.add(round(float(value), 1))
        out.add(float(round(float(value))))
    elif isinstance(value, dict):
        for v in value.values():
            _walk(v, out)
    elif isinstance(value, (list, tuple)):
        for v in value:
            _walk(v, out)


def fact_numbers(facts: dict) -> set[float]:
    out: set[float] = set()
    _walk(facts, out)
    return out


def numbers_in_text(text: str) -> list[float]:
    cleaned = _PERCENT.sub(" ", _TIME.sub(" ", _DATE.sub(" ", text)))
    return [float(m.group(0).replace(",", ".")) for m in _NUMBER.finditer(cleaned)]


@dataclass
class VerificationReport:
    checked: int = 0
    unverified: list[str] = field(default_factory=list)
    fields: dict[str, list[float]] = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return not self.unverified


def _text_fields(narrative: Narrative) -> list[tuple[str, str]]:
    fields: list[tuple[str, str]] = [("run_summary", narrative.run_summary), ("model_notes", narrative.model_notes)]
    for i, m in enumerate(narrative.members):
        fields.append((f"members[{i}].summary", m.summary))
        fields += [(f"members[{i}].warnings[{j}]", w) for j, w in enumerate(m.warnings)]
        for j, p in enumerate(m.patterns):
            fields.append((f"members[{i}].patterns[{j}].statement", p.statement))
            fields.append((f"members[{i}].patterns[{j}].evidence", p.evidence))
    for i, r in enumerate(narrative.team_risks):
        fields.append((f"team_risks[{i}].detail", r.detail))
    for i, mv in enumerate(narrative.rebalancing):
        fields.append((f"rebalancing[{i}].reason", mv.reason))
    for i, adj in enumerate(narrative.suggested_adjustments):
        fields.append((f"suggested_adjustments[{i}].reason", adj.reason))
    return fields


def verify_narrative(narrative: Narrative, facts: dict) -> VerificationReport:
    known = fact_numbers(facts)
    report = VerificationReport()
    for path, text in _text_fields(narrative):
        found = numbers_in_text(text)
        if not found:
            continue
        report.fields[path] = found
        for value in found:
            report.checked += 1
            if float(value).is_integer() and abs(value) <= SMALL_INTEGER_ALLOWANCE:
                continue
            if round(value, 1) in known or float(round(value)) in known:
                continue
            report.unverified.append(f"{path}: {value:g} is not in the facts")
    return report
