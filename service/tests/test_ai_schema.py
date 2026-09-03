import datetime as dt
import json

import pytest

from whf.ai.schema import Narrative, parse_narrative

FACTS = {
    "run": {"id": 7, "as_of": "2026-09-03", "weeks": ["2026-09-07", "2026-09-14"]},
    "team": {"id": 1, "name": "Web Platform"},
    "members": [{"id": 4, "name": "Sara Tazi"}, {"id": 5, "name": "Omar Benali"}],
}


def _good() -> dict:
    return {
        "run_summary": "Two members, one overloaded in week one.",
        "members": [
            {
                "member_id": 4,
                "name": "Sara Tazi",
                "risk_level": "high",
                "summary": "Sara has 52.0 h of demand against 40.0 h of capacity in the week of 2026-09-07.",
                "patterns": [
                    {
                        "kind": "assignment_style",
                        "statement": "Mostly project-driven work.",
                        "evidence": "share_project 0.6",
                    }
                ],
                "warnings": ["Overload of 12.0 h in the week of 2026-09-07."],
            },
            {
                "member_id": 5,
                "name": "Omar Benali",
                "risk_level": "low",
                "summary": "Spare capacity.",
                "patterns": [],
                "warnings": [],
            },
        ],
        "team_risks": [
            {
                "title": "Week one overload",
                "detail": "One member above capacity.",
                "severity": "medium",
                "member_ids": [4],
            }
        ],
        "rebalancing": [
            {
                "from_member_id": 4,
                "to_member_id": 5,
                "week": "2026-09-07",
                "hours": 8.0,
                "reason": "Omar has 20.0 h spare.",
                "confidence": "medium",
            }
        ],
        "suggested_adjustments": [],
        "model_notes": "Champion gbm, MASE 0.9.",
    }


def test_parse_accepts_plain_json_and_fenced_json() -> None:
    n = parse_narrative(json.dumps(_good()))
    assert n.members[0].risk_level == "high"
    fenced = "```json\n" + json.dumps(_good()) + "\n```"
    assert parse_narrative(fenced).rebalancing[0].week == dt.date(2026, 9, 7)


def test_parse_rejects_invalid_json_and_unknown_fields() -> None:
    with pytest.raises(ValueError, match="not valid JSON"):
        parse_narrative("Here is my analysis: {")
    bad = _good()
    bad["extra_field"] = 1
    with pytest.raises(ValueError, match="extra_field"):
        parse_narrative(json.dumps(bad))


def test_validate_against_facts_flags_unknown_members_and_weeks() -> None:
    n = parse_narrative(json.dumps(_good()))
    assert n.validate_against_facts(FACTS) == []
    bad = _good()
    bad["members"][1]["member_id"] = 99
    bad["rebalancing"][0]["week"] = "2026-09-28"
    problems = Narrative.model_validate(bad).validate_against_facts(FACTS)
    assert any("99" in p for p in problems) and any("2026-09-28" in p for p in problems)


def test_validate_requires_every_team_member_once() -> None:
    only_one = _good()
    only_one["members"] = only_one["members"][:1]
    problems = Narrative.model_validate(only_one).validate_against_facts(FACTS)
    assert any("missing" in p and "5" in p for p in problems)


def test_rebalancing_hours_must_be_positive_and_members_distinct() -> None:
    bad = _good()
    bad["rebalancing"][0]["hours"] = 0
    with pytest.raises(ValueError):
        Narrative.model_validate(bad)
    same = _good()
    same["rebalancing"][0]["to_member_id"] = 4
    assert any("same member" in p for p in Narrative.model_validate(same).validate_against_facts(FACTS))


def test_validate_flags_duplicate_members() -> None:
    dup = _good()
    dup["members"].append(dup["members"][0])
    problems = Narrative.model_validate(dup).validate_against_facts(FACTS)
    assert any("appears more than once" in p and "4" in p for p in problems)
