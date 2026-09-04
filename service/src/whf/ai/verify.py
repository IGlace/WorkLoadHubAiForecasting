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
# A thousands-separated token (groups of exactly three digits split by a space or comma, optional
# decimal part) is tried first so "1,200" and "1 200,5" are not mistaken for a decimal comma; a plain
# number falls through to the second alternative, which keeps today's "," -> "." behaviour.
_NUMBER = re.compile(
    r"(?<![\d.])-?\d{1,3}(?:[ ,]\d{3})+(?:[.,]\d+)?(?![\w.]*\d)|(?<![\d.])-?\d+(?:[.,]\d+)?(?![\w.]*\d)"
)
_THOUSANDS = re.compile(r"^(-?)(\d{1,3}(?:[ ,]\d{3})+)(?:([.,])(\d+))?$")
# An hours unit right after a number. Longest spellings first so "hours" is not consumed as "hour";
# the trailing word boundary keeps "8 high-priority tasks" a count rather than 8 hours.
_HOURS_UNIT = re.compile(r"\s*(?:hours|hour|heures|heure|hrs|hr|h)\b", re.IGNORECASE)
# Facts under these keys belong to one member each, so they are not part of every field's scope.
_MEMBER_SCOPED_KEYS = ("members", "rebalancing_candidates")


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


def _parse_number(token: str) -> float:
    m = _THOUSANDS.match(token)
    if not m:
        return float(token.replace(",", "."))
    sign, int_part, _dec_sep, dec_digits = m.groups()
    digits = re.sub(r"[ ,]", "", int_part)
    return float(f"{sign}{digits}.{dec_digits}" if dec_digits else f"{sign}{digits}")


def numbers_with_units(text: str) -> list[tuple[float, bool]]:
    """Every number in `text`, each paired with whether it is written as a number of hours."""
    cleaned = _PERCENT.sub(" ", _TIME.sub(" ", _DATE.sub(" ", text)))
    return [
        (_parse_number(m.group(0)), _HOURS_UNIT.match(cleaned, m.end()) is not None) for m in _NUMBER.finditer(cleaned)
    ]


def numbers_in_text(text: str) -> list[float]:
    return [value for value, _as_hours in numbers_with_units(text)]


@dataclass
class VerificationReport:
    checked: int = 0
    unverified: list[str] = field(default_factory=list)
    fields: dict[str, list[float]] = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return not self.unverified


def shared_numbers(facts: dict) -> set[float]:
    """The numeric facts that belong to the run as a whole: every field may cite these."""
    return fact_numbers({k: v for k, v in facts.items() if k not in _MEMBER_SCOPED_KEYS})


def member_numbers(facts: dict) -> dict[int, set[float]]:
    """The numeric facts of each member, keyed by member id: their own row plus their rebalancing candidacy."""
    scopes: dict[int, set[float]] = {}
    for entry in facts.get("members") or []:
        if isinstance(entry, dict) and isinstance(entry.get("id"), int):
            scopes.setdefault(entry["id"], set()).update(fact_numbers(entry))
    candidates = facts.get("rebalancing_candidates") or {}
    if isinstance(candidates, dict):
        for group in candidates.values():
            for entry in group or []:
                if isinstance(entry, dict) and isinstance(entry.get("member_id"), int):
                    scopes.setdefault(entry["member_id"], set()).update(fact_numbers(entry))
    return scopes


def _rounded(values: Any) -> set[float]:
    out: set[float] = set()
    _walk(values, out)
    return out


def _text_fields(narrative: Narrative, facts: dict) -> list[tuple[str, str, set[float]]]:
    """Each text field of the narrative with the set of numbers that field is allowed to cite."""
    shared = shared_numbers(facts)
    per_member = member_numbers(facts)
    everything = shared.union(*per_member.values()) if per_member else shared

    def member_scope(member_id: int) -> set[float]:
        return shared | per_member.get(member_id, set())

    fields: list[tuple[str, str, set[float]]] = [
        ("run_summary", narrative.run_summary, everything),
        ("model_notes", narrative.model_notes, everything),
    ]
    for i, m in enumerate(narrative.members):
        allowed = member_scope(m.member_id)
        fields.append((f"members[{i}].summary", m.summary, allowed))
        fields += [(f"members[{i}].warnings[{j}]", w, allowed) for j, w in enumerate(m.warnings)]
        for j, p in enumerate(m.patterns):
            fields.append((f"members[{i}].patterns[{j}].statement", p.statement, allowed))
            fields.append((f"members[{i}].patterns[{j}].evidence", p.evidence, allowed))
    for i, r in enumerate(narrative.team_risks):
        fields.append((f"team_risks[{i}].detail", r.detail, everything))
    for i, mv in enumerate(narrative.rebalancing):
        # The move's own size is the model's proposal, bounded by the schema rather than drawn from the facts.
        allowed = member_scope(mv.from_member_id) | member_scope(mv.to_member_id) | _rounded(mv.hours)
        fields.append((f"rebalancing[{i}].reason", mv.reason, allowed))
    for i, adj in enumerate(narrative.suggested_adjustments):
        allowed = member_scope(adj.member_id) | _rounded([adj.delta_hours, abs(adj.delta_hours)])
        fields.append((f"suggested_adjustments[{i}].reason", adj.reason, allowed))
    return fields


def verify_narrative(narrative: Narrative, facts: dict) -> VerificationReport:
    """Check every number in the narrative's text fields against the facts that field is entitled to cite.

    A member's own fields are scoped: their numbers must come from their own forecast row or their
    rebalancing candidacy, plus the run-wide facts (run, team totals, projects, model quality) that any
    field may cite. Team-level fields (`run_summary`, `model_notes`, `team_risks`) may cite anyone's
    numbers, because comparing members is their purpose. So `ok` means "every number is a fact this field
    could legitimately state", which catches a figure attributed to the wrong member as well as an
    invented one.

    A number matches when it equals a fact rounded to one decimal or to the nearest integer, since the
    narrative is asked to write facts as given but may drop a trailing decimal. The narrative's own value
    is never re-rounded to find a match: that would let any half-hour figure land on an unrelated integer.

    Integers up to SMALL_INTEGER_ALLOWANCE are skipped unless written as a number of hours, because they
    are almost always counts ("3 overdue tasks", "2 weeks") rather than quantities taken from the facts.
    """
    report = VerificationReport()
    known = fact_numbers(facts)
    for path, text, allowed in _text_fields(narrative, facts):
        found = numbers_with_units(text)
        if not found:
            continue
        report.fields[path] = [value for value, _ in found]
        for value, as_hours in found:
            report.checked += 1
            if not as_hours and float(value).is_integer() and abs(value) <= SMALL_INTEGER_ALLOWANCE:
                continue
            if round(value, 1) in allowed:
                continue
            elsewhere = " (it is a fact of this run, but not of this field)" if round(value, 1) in known else ""
            report.unverified.append(f"{path}: {value:g} is not in the facts{elsewhere}")
    return report
