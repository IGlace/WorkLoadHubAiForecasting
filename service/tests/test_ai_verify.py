from whf.ai.schema import Narrative
from whf.ai.verify import SMALL_INTEGER_ALLOWANCE, fact_numbers, numbers_in_text, verify_narrative

FACTS = {
    "run": {"id": 3, "weeks": ["2026-09-07", "2026-09-14"], "generated_at": "2026-09-03T10:00:00"},
    "members": [
        {
            "id": 4,
            "name": "A",
            "forecast": [{"week": "2026-09-07", "demand": 52.04, "capacity": 40.0, "overload": 12.04}],
        },
        {"id": 5, "name": "B", "forecast": [{"week": "2026-09-07", "demand": 20.5, "capacity": 40.0, "overload": 0.0}]},
    ],
    "model": {"champion": "gbm", "champion_mase": 0.913},
}


def _narrative(summary: str, warnings: list[str] | None = None) -> Narrative:
    return Narrative.model_validate(
        {
            "run_summary": "ok",
            "members": [
                {
                    "member_id": 4,
                    "name": "A",
                    "risk_level": "high",
                    "summary": summary,
                    "patterns": [],
                    "warnings": warnings or [],
                },
                {"member_id": 5, "name": "B", "risk_level": "low", "summary": "fine", "patterns": [], "warnings": []},
            ],
        }
    )


def test_fact_numbers_round_to_one_decimal_and_integers() -> None:
    nums = fact_numbers(FACTS)
    assert {52.0, 12.0, 40.0, 20.5, 0.9, 1.0, 3.0} <= nums


def test_numbers_in_text_skips_dates_times_and_percentages() -> None:
    text = "In the week of 2026-09-07 at 10:30, demand is 52.0 h (30% above 40 h), MASE 0.91."
    assert numbers_in_text(text) == [52.0, 40.0, 0.91]


def test_verified_when_every_number_matches_facts() -> None:
    report = verify_narrative(_narrative("Demand 52.0 h against 40 h, overload 12.0 h in week 2026-09-07."), FACTS)
    assert report.ok and report.checked == 3 and report.unverified == []


def test_unverified_number_is_reported_with_its_field() -> None:
    report = verify_narrative(_narrative("Demand will reach 63.5 h.", ["Expect 12 h overload."]), FACTS)
    assert not report.ok
    assert any("63.5" in u and "members[0].summary" in u for u in report.unverified)
    assert report.checked == 2  # 12 is a small integer, counted but never flagged


def test_small_integers_are_never_flagged() -> None:
    report = verify_narrative(
        _narrative(f"Over {SMALL_INTEGER_ALLOWANCE} tasks in 2 weeks, 13 weeks of history."), FACTS
    )
    assert report.ok
