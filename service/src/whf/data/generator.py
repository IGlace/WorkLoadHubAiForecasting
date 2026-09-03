"""Seeded dummy data with hidden, discoverable patterns.

Part A (this task): organisation, member profiles, projects with phase curves, vacations.
Part B (next task): day-by-day task arrival and effort simulation, answer key.
"""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass, field

import numpy as np

from whf.calendar import ONE_DAY, ONE_WEEK, week_start

TASK_TYPES = ("feature", "bug", "support", "analysis", "maintenance")
TYPE_PROBS = (0.30, 0.25, 0.20, 0.15, 0.10)
TYPE_BASE_HOURS = {"feature": 12.0, "bug": 5.0, "support": 3.0, "analysis": 8.0, "maintenance": 6.0}
TYPE_BASE_CYCLE_DAYS = {"feature": 9.0, "bug": 3.0, "support": 1.5, "analysis": 6.0, "maintenance": 4.0}
ASSIGNMENT_MODES = ("manual", "self_picked", "project")
PRIORITIES = ("low", "medium", "high")
PRIORITY_PROBS = (0.3, 0.5, 0.2)

DEPARTMENTS: dict[str, list[str]] = {
    "Software Engineering": ["Web Platform", "Mobile Apps", "Integration"],
    "Data & Analytics": ["Data Engineering", "BI & Reporting"],
    "Infrastructure & Support": ["Cloud Operations", "Service Desk", "Security"],
}

FIRST_NAMES = [
    "Youssef",
    "Amina",
    "Omar",
    "Salma",
    "Mehdi",
    "Khadija",
    "Hamza",
    "Nadia",
    "Anas",
    "Sara",
    "Bilal",
    "Imane",
    "Yassine",
    "Meryem",
    "Zakaria",
    "Hajar",
    "Reda",
    "Fatima",
    "Ayoub",
    "Soukaina",
    "Ilyas",
    "Rim",
    "Adam",
    "Laila",
    "Karim",
    "Ghita",
    "Othmane",
    "Nour",
    "Amine",
    "Houda",
    "Walid",
    "Zineb",
    "Taha",
    "Hind",
    "Ismail",
    "Aya",
    "Rachid",
    "Samira",
    "Nabil",
    "Ikram",
    "Mohammed",
    "Chaimae",
    "Hicham",
    "Kenza",
    "Driss",
    "Loubna",
    "Tarik",
    "Wafae",
    "Jalil",
    "Asmae",
    "Khalil",
    "Siham",
    "Mounir",
    "Rania",
    "Saad",
    "Malak",
    "Badr",
    "Yasmine",
    "Fouad",
    "Dounia",
]
LAST_NAMES = [
    "El Idrissi",
    "Benali",
    "Alaoui",
    "Bennani",
    "Chraibi",
    "El Fassi",
    "Tazi",
    "Berrada",
    "Lahlou",
    "Sefrioui",
    "Kettani",
    "Bouazza",
    "Amrani",
    "Cherkaoui",
    "Mansouri",
    "Zniber",
    "Filali",
    "Haddad",
    "Ouazzani",
    "Skalli",
    "Belkadi",
    "Naciri",
    "Lamrani",
    "Rhazi",
    "Benjelloun",
    "Tahiri",
    "Ziani",
    "El Ghazi",
    "Boukhari",
    "Saidi",
]


@dataclass(frozen=True)
class GeneratorConfig:
    seed: int = 42
    months: int = 12
    as_of: dt.date = dt.date(2026, 9, 3)
    horizon_days: int = 21


@dataclass
class MemberProfile:
    member_id: int
    base_rate: float  # expected tasks per week
    dispersion: float  # negative-binomial over-dispersion (0 = Poisson)
    style: dict[str, float]  # assignment mode probabilities, sums to 1
    est_bias: float  # actual hours / estimated hours
    cycle_factor: float  # multiplies the type's base cycle time for due dates
    weekday_weights: list[float]  # Mon..Fri, average 1.0


@dataclass
class ProjectCurve:
    project_id: int
    ramp_weeks: int
    crunch_weeks: int
    crunch_factor: float


@dataclass
class Org:
    departments: list[dict] = field(default_factory=list)
    teams: list[dict] = field(default_factory=list)
    members: list[dict] = field(default_factory=list)
    profiles: dict[int, MemberProfile] = field(default_factory=dict)


def history_start(config: GeneratorConfig) -> dt.date:
    return week_start(config.as_of - dt.timedelta(days=int(config.months * 30.44)))


def seasonal_factor(d: dt.date) -> float:
    week = d.isocalendar()[1]
    if 31 <= week <= 34:
        return 0.6  # summer dip
    if week >= 49:
        return 1.3  # year-end crunch
    if week <= 1:
        return 0.7
    return 1.0


def phase_intensity(d: dt.date, start: dt.date, deadline: dt.date, curve: ProjectCurve) -> float:
    if d < start or d > deadline:
        return 0.0
    weeks_since_start = (d - start).days / 7
    weeks_to_deadline = (deadline - d).days / 7
    if weeks_since_start < curve.ramp_weeks:
        return 0.6
    if weeks_to_deadline <= curve.crunch_weeks:
        return curve.crunch_factor
    return 1.0


def _make_profile(rng: np.random.Generator, member_id: int) -> MemberProfile:
    dominant = str(rng.choice(ASSIGNMENT_MODES, p=[0.35, 0.30, 0.35]))
    style = {mode: 0.2 for mode in ASSIGNMENT_MODES}
    style[dominant] = 0.6
    weights = rng.dirichlet([3.0, 2.0, 2.0, 2.0, 1.5]) * 5.0
    return MemberProfile(
        member_id=member_id,
        base_rate=float(rng.gamma(9.0, 0.4)),
        dispersion=float(rng.uniform(0.3, 1.0)),
        style=style,
        est_bias=float(np.exp(rng.normal(0.05, 0.25))),
        cycle_factor=float(np.exp(rng.normal(0.0, 0.3))),
        weekday_weights=[round(float(w), 3) for w in weights],
    )


def build_org(rng: np.random.Generator) -> Org:
    org = Org()
    first = list(rng.permutation(FIRST_NAMES))
    last = list(rng.permutation(LAST_NAMES))
    names = [f"{first[i]} {last[i % len(last)]}" for i in range(len(first))]
    next_name = 0
    member_id = 0
    for dept_id, (dept_name, team_names) in enumerate(DEPARTMENTS.items(), start=1):
        member_id += 1
        leader_id = member_id
        org.members.append(
            {
                "id": leader_id,
                "name": names[next_name],
                "team_id": None,
                "department_id": dept_id,
                "role": "skill_team_leader",
                "counted_in_workload": 0,
                "active_from": None,
                "active_to": None,
            }
        )
        next_name += 1
        org.departments.append({"id": dept_id, "name": dept_name, "skill_team_leader_id": leader_id})
        for team_name in team_names:
            team_id = len(org.teams) + 1
            size = int(rng.integers(4, 8))
            team_leader_id = None
            for k in range(size):
                member_id += 1
                role = "team_leader" if k == 0 else "member"
                if k == 0:
                    team_leader_id = member_id
                org.members.append(
                    {
                        "id": member_id,
                        "name": names[next_name % len(names)],
                        "team_id": team_id,
                        "department_id": dept_id,
                        "role": role,
                        "counted_in_workload": 1,
                        "active_from": None,
                        "active_to": None,
                    }
                )
                next_name += 1
                org.profiles[member_id] = _make_profile(rng, member_id)
            org.teams.append(
                {"id": team_id, "department_id": dept_id, "name": team_name, "team_leader_id": team_leader_id}
            )
    return org


def build_projects(
    rng: np.random.Generator, org: Org, config: GeneratorConfig
) -> tuple[list[dict], list[dict], dict[int, ProjectCurve]]:
    projects: list[dict] = []
    project_teams: list[dict] = []
    curves: dict[int, ProjectCurve] = {}
    start = history_start(config)
    project_id = 0
    for team in org.teams:
        cursor = start
        for _ in range(int(rng.integers(3, 6))):
            project_id += 1
            duration_weeks = int(rng.integers(6, 20))
            p_start = min(cursor + dt.timedelta(days=int(rng.integers(0, 28))), config.as_of - 2 * ONE_WEEK)
            deadline = p_start + duration_weeks * ONE_WEEK
            projects.append(
                {
                    "id": project_id,
                    "name": f"{team['name']} project {project_id}",
                    "department_id": team["department_id"],
                    "start_date": p_start,
                    "deadline": deadline,
                    "type": str(rng.choice(["delivery", "internal", "support"])),
                    "status": "closed" if deadline < config.as_of else "active",
                    "created_by": team["team_leader_id"],
                }
            )
            project_teams.append({"project_id": project_id, "team_id": team["id"]})
            curves[project_id] = ProjectCurve(
                project_id, int(rng.integers(1, 3)), int(rng.integers(1, 3)), float(rng.uniform(1.3, 1.8))
            )
            cursor = p_start + max(2, duration_weeks // 2) * ONE_WEEK
        project_id += 1
        p_start = config.as_of + dt.timedelta(days=int(rng.integers(1, min(12, config.horizon_days))))
        deadline = p_start + int(rng.integers(6, 14)) * ONE_WEEK
        projects.append(
            {
                "id": project_id,
                "name": f"{team['name']} project {project_id}",
                "department_id": team["department_id"],
                "start_date": p_start,
                "deadline": deadline,
                "type": "delivery",
                "status": "planned",
                "created_by": team["team_leader_id"],
            }
        )
        project_teams.append({"project_id": project_id, "team_id": team["id"]})
        curves[project_id] = ProjectCurve(project_id, 1, 2, 1.5)
    return projects, project_teams, curves


def build_vacations(rng: np.random.Generator, org: Org, config: GeneratorConfig) -> list[dict]:
    vacations: list[dict] = []
    start = history_start(config)
    span_days = (config.as_of - start).days
    for m in org.members:
        if not m["counted_in_workload"]:
            continue
        for _ in range(int(rng.integers(1, 3))):
            v_start = start + dt.timedelta(days=int(rng.integers(0, span_days)))
            v_end = v_start + dt.timedelta(days=int(rng.integers(4, 11)))
            vacations.append({"member_id": m["id"], "start_date": v_start, "end_date": v_end, "type": "vacation"})
        if rng.random() < 0.25:
            v_start = config.as_of + dt.timedelta(days=int(rng.integers(1, 15)))
            v_end = v_start + dt.timedelta(days=int(rng.integers(2, 8)))
            vacations.append({"member_id": m["id"], "start_date": v_start, "end_date": v_end, "type": "vacation"})
    return vacations


def _unused() -> None:  # keeps ONE_DAY imported for part B; removed in Task 6
    _ = ONE_DAY
