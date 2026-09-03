import datetime as dt

import numpy as np

from whf.data.generator import (
    ASSIGNMENT_MODES,
    GeneratorConfig,
    ProjectCurve,
    build_org,
    build_projects,
    build_vacations,
    history_start,
    phase_intensity,
    seasonal_factor,
)


def test_history_start_is_a_monday_about_a_year_back() -> None:
    cfg = GeneratorConfig(as_of=dt.date(2026, 9, 3), months=12)
    hs = history_start(cfg)
    assert hs.weekday() == 0
    assert 355 <= (cfg.as_of - hs).days <= 372


def test_org_has_three_departments_with_leaders_and_teams() -> None:
    org = build_org(np.random.default_rng(1))
    assert len(org.departments) == 3
    assert 7 <= len(org.teams) <= 9
    for d in org.departments:
        leader = next(m for m in org.members if m["id"] == d["skill_team_leader_id"])
        assert leader["role"] == "skill_team_leader"
        assert leader["counted_in_workload"] == 0
        assert leader["team_id"] is None
    for t in org.teams:
        leader = next(m for m in org.members if m["id"] == t["team_leader_id"])
        assert leader["role"] == "team_leader"
        assert leader["counted_in_workload"] == 1
        size = sum(1 for m in org.members if m["team_id"] == t["id"])
        assert 4 <= size <= 7


def test_every_counted_member_has_a_profile_with_a_style_mix_summing_to_one() -> None:
    org = build_org(np.random.default_rng(2))
    for m in org.members:
        if m["counted_in_workload"]:
            p = org.profiles[m["id"]]
            assert set(p.style) == set(ASSIGNMENT_MODES)
            assert abs(sum(p.style.values()) - 1.0) < 1e-9
            assert p.base_rate > 0 and len(p.weekday_weights) == 5
        else:
            assert m["id"] not in org.profiles


def test_org_is_reproducible() -> None:
    a = build_org(np.random.default_rng(5))
    b = build_org(np.random.default_rng(5))
    assert a.members == b.members and a.teams == b.teams


def test_projects_cover_history_and_include_one_starting_in_horizon_per_team() -> None:
    cfg = GeneratorConfig(seed=3)
    rng = np.random.default_rng(cfg.seed)
    org = build_org(rng)
    projects, project_teams, curves = build_projects(rng, org, cfg)
    assert {p["id"] for p in projects} == set(curves)
    assert len(project_teams) == len(projects)
    for t in org.teams:
        mine = [p for p, pt in zip(projects, project_teams, strict=True) if pt["team_id"] == t["id"]]
        assert len(mine) >= 4
        future = [p for p in mine if cfg.as_of < p["start_date"] <= cfg.as_of + dt.timedelta(days=cfg.horizon_days)]
        assert len(future) == 1 and future[0]["status"] == "planned"
        for p in mine:
            assert p["deadline"] > p["start_date"]


def test_phase_intensity_ramps_then_crunches() -> None:
    curve = ProjectCurve(1, ramp_weeks=2, crunch_weeks=2, crunch_factor=1.5)
    start, deadline = dt.date(2026, 1, 5), dt.date(2026, 3, 30)
    assert phase_intensity(dt.date(2026, 1, 1), start, deadline, curve) == 0.0
    assert phase_intensity(dt.date(2026, 1, 6), start, deadline, curve) == 0.6
    assert phase_intensity(dt.date(2026, 2, 10), start, deadline, curve) == 1.0
    assert phase_intensity(dt.date(2026, 3, 25), start, deadline, curve) == 1.5
    assert phase_intensity(dt.date(2026, 4, 1), start, deadline, curve) == 0.0


def test_seasonal_factor_has_summer_dip_and_year_end_peak() -> None:
    assert seasonal_factor(dt.date(2026, 8, 5)) < 1.0
    assert seasonal_factor(dt.date(2026, 12, 10)) > 1.0
    assert seasonal_factor(dt.date(2026, 5, 6)) == 1.0


def test_vacations_include_future_ones_for_some_members() -> None:
    cfg = GeneratorConfig(seed=4)
    rng = np.random.default_rng(cfg.seed)
    org = build_org(rng)
    vacations = build_vacations(rng, org, cfg)
    counted = {m["id"] for m in org.members if m["counted_in_workload"]}
    assert all(v["member_id"] in counted for v in vacations)
    future = [v for v in vacations if v["start_date"] > cfg.as_of]
    assert len(future) >= 3
    assert all(v["end_date"] >= v["start_date"] for v in vacations)
