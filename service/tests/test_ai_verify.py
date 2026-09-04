from whf.ai.schema import Narrative
from whf.ai.verify import SMALL_INTEGER_ALLOWANCE, fact_numbers, numbers_in_text, verify_narrative

FACTS = {
    "run": {"id": 3, "weeks": ["2026-09-07", "2026-09-14"], "generated_at": "2026-09-03T10:00:00"},
    "team": {"id": 1, "totals": [{"week": "2026-09-07", "demand": 72.5, "capacity": 88.0}]},
    "members": [
        {
            "id": 4,
            "name": "A",
            "forecast": [{"week": "2026-09-07", "demand": 52.04, "capacity": 40.0, "overload": 12.04}],
        },
        {"id": 5, "name": "B", "forecast": [{"week": "2026-09-07", "demand": 20.5, "capacity": 40.0, "overload": 0.0}]},
    ],
    "model": {"champion": "gbm", "champion_mase": 0.913},
    "rebalancing_candidates": {
        "overloaded": [{"member_id": 4, "name": "A", "overload_hours": 12.0}],
        "underloaded": [{"member_id": 5, "name": "B", "spare_hours": 19.5}],
    },
}


def _narrative(
    summary: str,
    warnings: list[str] | None = None,
    *,
    run_summary: str = "ok",
    extra: dict | None = None,
) -> Narrative:
    return Narrative.model_validate(
        {
            "run_summary": run_summary,
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
            **(extra or {}),
        }
    )


def test_fact_numbers_round_to_one_decimal_and_integers() -> None:
    nums = fact_numbers(FACTS)
    assert {52.0, 12.0, 40.0, 20.5, 0.9, 1.0, 3.0} <= nums


def test_numbers_in_text_skips_dates_times_and_percentages() -> None:
    text = "In the week of 2026-09-07 at 10:30, demand is 52.0 h (30% above 40 h), MASE 0.91."
    assert numbers_in_text(text) == [52.0, 40.0, 0.91]


def test_numbers_in_text_extracts_numbers_glued_to_a_preceding_word() -> None:
    assert numbers_in_text("MASE0.91") == [0.91]
    assert numbers_in_text("demand52.5h") == [52.5]


def test_numbers_in_text_handles_thousands_separators_without_breaking_decimal_commas() -> None:
    assert numbers_in_text("1,200 h") == [1200.0]
    assert numbers_in_text("1 200,5 h") == [1200.5]
    assert numbers_in_text("52,5 h") == [52.5]
    assert numbers_in_text("12,34") == [12.34]


def test_verified_when_every_number_matches_facts() -> None:
    report = verify_narrative(_narrative("Demand 52.0 h against 40 h, overload 12.0 h in week 2026-09-07."), FACTS)
    assert report.ok and report.checked == 3 and report.unverified == []


def test_unverified_number_is_reported_with_its_field() -> None:
    report = verify_narrative(_narrative("Demand will reach 63.5 h.", ["Expect 12 h overload."]), FACTS)
    assert not report.ok
    assert any("63.5" in u and "members[0].summary" in u for u in report.unverified)
    assert report.checked == 2  # 12 h matches member 4's own overload of 12.04, rounded


def test_small_integers_without_an_hours_unit_are_never_flagged() -> None:
    report = verify_narrative(
        _narrative(f"Over {SMALL_INTEGER_ALLOWANCE} tasks in 2 weeks, 13 weeks of history."), FACTS
    )
    assert report.ok


def test_a_small_number_written_as_hours_is_checked() -> None:
    """The small-integer allowance exists for counts, not for hours: 8 h is nowhere in the facts."""
    report = verify_narrative(_narrative("Overload of 8 h in week 2026-09-07."), FACTS)
    assert not report.ok
    assert any("8" in u and "members[0].summary" in u for u in report.unverified)


def test_hours_unit_is_recognised_in_its_common_spellings() -> None:
    for written in ("8 h", "8h", "8 hrs", "8 hours", "8 hour", "8 heures"):
        assert not verify_narrative(_narrative(f"Overload of {written}."), FACTS).ok, written
    # a word merely starting with "h" is not an hours unit, so the count allowance still applies
    assert verify_narrative(_narrative("8 high-priority tasks arrive."), FACTS).ok


def test_a_members_text_may_not_cite_another_members_number() -> None:
    """20.5 h is member 5's demand; in member 4's summary it is a misattribution, not a fact about A."""
    report = verify_narrative(_narrative("Demand is 20.5 h."), FACTS)
    assert not report.ok
    assert any("20.5" in u and "members[0].summary" in u for u in report.unverified)


def test_a_members_text_may_cite_run_team_and_model_numbers() -> None:
    report = verify_narrative(_narrative("Demand 52.0 h of the team's 72.5 h against 88.0 h, MASE 0.91."), FACTS)
    assert report.ok


def test_a_members_text_may_cite_their_own_rebalancing_candidacy() -> None:
    report = verify_narrative(_narrative("Overload of 12.0 h; B has 19.5 h spare."), FACTS)
    assert any("19.5" in u for u in report.unverified)  # B's spare hours belong in B's section
    report_b = verify_narrative(_narrative("Overload of 12.0 h."), FACTS)
    assert report_b.ok


def test_team_level_text_may_cite_any_members_number() -> None:
    report = verify_narrative(_narrative("fine", run_summary="A is at 52.0 h, B at 20.5 h against 40.0 h."), FACTS)
    assert report.ok


def test_rebalancing_reason_may_cite_the_moves_own_hours() -> None:
    """A partial move of 3.5 h is a number the model chose, bounded by the schema, not a fact of the run."""
    move = {
        "from_member_id": 4,
        "to_member_id": 5,
        "week": "2026-09-07",
        "hours": 6.5,
        "reason": "Move 6.5 h of A's 12.0 h overload to B, who has 19.5 h spare.",
        "confidence": "high",
    }
    report = verify_narrative(_narrative("fine", extra={"rebalancing": [move]}), FACTS)
    assert report.ok


def test_a_value_that_only_rounds_onto_an_unrelated_fact_is_flagged() -> None:
    """3.5 h is no fact of this run; that round(3.5) equals member 4's id is a collision, not evidence."""
    report = verify_narrative(_narrative("Overload of 3.5 h."), FACTS)
    assert not report.ok
    assert any("3.5" in u for u in report.unverified)


def test_adjustment_reason_may_cite_its_own_delta_hours() -> None:
    adjustment = {
        "member_id": 4,
        "week": "2026-09-07",
        "delta_hours": -2.5,
        "reason": "Trim 2.5 h: the audit day is already counted in capacity.",
    }
    report = verify_narrative(_narrative("fine", extra={"suggested_adjustments": [adjustment]}), FACTS)
    assert report.ok
