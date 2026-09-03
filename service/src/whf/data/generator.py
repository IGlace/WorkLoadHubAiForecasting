"""Seeded dummy data with hidden, discoverable patterns.

Part A (this task): organisation, member profiles, projects with phase curves, vacations.
Part B (next task): day-by-day task arrival and effort simulation, answer key.
"""

from __future__ import annotations

import datetime as dt
from dataclasses import asdict, dataclass, field

import numpy as np

from whf.calendar import ONE_DAY, ONE_WEEK, days_in_ranges, morocco_holidays, week_start

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


@dataclass
class GeneratedData:
    config: GeneratorConfig
    departments: list[dict]
    teams: list[dict]
    members: list[dict]
    projects: list[dict]
    project_teams: list[dict]
    vacations: list[dict]
    holidays: list[dict]
    tasks: list[dict]
    answer_key: dict


def _vacation_days(vacations: list[dict]) -> dict[int, set[dt.date]]:
    out: dict[int, set[dt.date]] = {}
    for v in vacations:
        out.setdefault(v["member_id"], set()).update(days_in_ranges([(v["start_date"], v["end_date"])]))
    return out


def _new_task(
    rng: np.random.Generator,
    task_id: int,
    day: dt.date,
    member: dict,
    profile: MemberProfile,
    mode: str,
    team_projects: list[dict],
    intensity: dict[int, float],
    team_leader_id: int | None,
) -> tuple[dict, float]:
    task_type = str(rng.choice(TASK_TYPES, p=TYPE_PROBS))
    estimated = float(np.clip(np.exp(rng.normal(np.log(TYPE_BASE_HOURS[task_type]), 0.4)), 1.0, 40.0))
    project = None
    if team_projects:
        weights = np.array([intensity[p["id"]] + 0.1 for p in team_projects])
        project = team_projects[int(rng.choice(len(team_projects), p=weights / weights.sum()))]
    cycle_days = TYPE_BASE_CYCLE_DAYS[task_type] * profile.cycle_factor * float(np.exp(rng.normal(0.0, 0.3)))
    actual_total = estimated * profile.est_bias * float(np.exp(rng.normal(0.0, 0.15)))
    task = {
        "id": task_id,
        "title": f"{task_type.title()} task {task_id}",
        "project_id": project["id"] if project else None,
        "assignee_id": member["id"],
        "team_id": member["team_id"],
        "type": task_type,
        "priority": str(rng.choice(PRIORITIES, p=PRIORITY_PROBS)),
        "status": "todo",
        "created_at": day,
        "assigned_at": day,
        "due_date": day + dt.timedelta(days=max(1, int(round(cycle_days)))),
        "completed_at": None,
        "estimated_hours": round(estimated, 1),
        "actual_hours": None,
        "created_by": team_leader_id if mode == "manual" else member["id"],
        "assignment_mode": mode,
    }
    return task, actual_total


def simulate_tasks(
    rng: np.random.Generator,
    org: Org,
    projects: list[dict],
    project_teams: list[dict],
    curves: dict[int, ProjectCurve],
    vacations: list[dict],
    off: set[dt.date],
    config: GeneratorConfig,
) -> tuple[list[dict], list[dict]]:
    """Day-by-day arrivals and effort. Returns (tasks, effort_log rows)."""
    start = history_start(config)
    projects_by_team: dict[int, list[dict]] = {t["id"]: [] for t in org.teams}
    for p, pt in zip(projects, project_teams, strict=True):
        projects_by_team[pt["team_id"]].append(p)
    leader_by_team = {t["id"]: t["team_leader_id"] for t in org.teams}
    vac_days = _vacation_days(vacations)
    workers = [m for m in org.members if m["counted_in_workload"]]
    tasks: list[dict] = []
    remaining: dict[int, float] = {}
    actual_total: dict[int, float] = {}
    open_by_member: dict[int, list[dict]] = {m["id"]: [] for m in workers}
    effort_log: list[dict] = []
    task_id = 0
    day = start
    while day <= config.as_of:
        is_working_day = day.weekday() < 5 and day not in off
        for member in workers:
            profile = org.profiles[member["id"]]
            if not is_working_day or day in vac_days.get(member["id"], set()):
                continue
            team_projects = [p for p in projects_by_team[member["team_id"]] if p["start_date"] <= day <= p["deadline"]]
            intensity = {
                p["id"]: phase_intensity(day, p["start_date"], p["deadline"], curves[p["id"]]) for p in team_projects
            }
            project_factor = (0.6 + 0.4 * float(np.mean(list(intensity.values())))) if intensity else 0.7
            rate = (
                profile.base_rate / 5.0 * profile.weekday_weights[day.weekday()] * seasonal_factor(day) * project_factor
            )
            lam = rng.gamma(1.0 / profile.dispersion, rate * profile.dispersion)
            for _ in range(int(rng.poisson(lam))):
                mode = str(rng.choice(ASSIGNMENT_MODES, p=[profile.style[m] for m in ASSIGNMENT_MODES]))
                if mode == "manual" and day.weekday() > 1 and rng.random() < 0.6:
                    continue  # manual assignments cluster on Monday and Tuesday
                task_id += 1
                task, total = _new_task(
                    rng,
                    task_id,
                    day,
                    member,
                    profile,
                    mode,
                    team_projects,
                    intensity,
                    leader_by_team[member["team_id"]],
                )
                tasks.append(task)
                remaining[task_id] = total
                actual_total[task_id] = total
                open_by_member[member["id"]].append(task)
            budget = 8.0
            queue = sorted(open_by_member[member["id"]], key=lambda t: (t["due_date"], t["priority"] != "high"))
            for task in queue:
                if budget <= 1e-9:
                    break
                spend = min(budget, remaining[task["id"]], 6.0)
                remaining[task["id"]] -= spend
                budget -= spend
                task["status"] = "in_progress"
                effort_log.append(
                    {"member_id": member["id"], "date": day, "task_id": task["id"], "hours": round(spend, 2)}
                )
                if remaining[task["id"]] <= 1e-9:
                    task["status"] = "done"
                    task["completed_at"] = day
                    task["actual_hours"] = round(actual_total[task["id"]], 1)
            open_by_member[member["id"]] = [t for t in open_by_member[member["id"]] if t["status"] != "done"]
        day += ONE_DAY
    return tasks, effort_log


def generate(config: GeneratorConfig = GeneratorConfig()) -> GeneratedData:
    rng = np.random.default_rng(config.seed)
    org = build_org(rng)
    projects, project_teams, curves = build_projects(rng, org, config)
    vacations = build_vacations(rng, org, config)
    start = history_start(config)
    horizon_end = config.as_of + dt.timedelta(days=config.horizon_days)
    holiday_map = morocco_holidays(range(start.year, horizon_end.year + 1))
    holidays_rows = [{"date": d, "name": n, "country": "MA"} for d, n in sorted(holiday_map.items())]
    tasks, effort_log = simulate_tasks(rng, org, projects, project_teams, curves, vacations, set(holiday_map), config)
    weekly: dict[tuple[int, dt.date], float] = {}
    for row in effort_log:
        key = (row["member_id"], week_start(row["date"]))
        weekly[key] = weekly.get(key, 0.0) + row["hours"]
    answer_key = {
        "profiles": {str(k): asdict(v) for k, v in org.profiles.items()},
        "curves": {str(k): asdict(v) for k, v in curves.items()},
        "effort_log": [{**r, "date": r["date"].isoformat()} for r in effort_log],
        "effort_by_member_week": [
            {"member_id": m, "week_start": w.isoformat(), "hours": round(h, 2)} for (m, w), h in sorted(weekly.items())
        ],
    }
    return GeneratedData(
        config=config,
        departments=org.departments,
        teams=org.teams,
        members=org.members,
        projects=projects,
        project_teams=project_teams,
        vacations=vacations,
        holidays=holidays_rows,
        tasks=tasks,
        answer_key=answer_key,
    )


def truncate_to(data: GeneratedData, as_of: dt.date) -> GeneratedData:
    """A copy of `data` as the database would have looked on `as_of`."""
    tasks: list[dict] = []
    for t in data.tasks:
        if t["assigned_at"] > as_of:
            continue
        t2 = dict(t)
        if t2["completed_at"] is not None and t2["completed_at"] > as_of:
            t2["completed_at"] = None
            t2["actual_hours"] = None
            t2["status"] = "in_progress"
        tasks.append(t2)
    config = GeneratorConfig(
        seed=data.config.seed, months=data.config.months, as_of=as_of, horizon_days=data.config.horizon_days
    )
    return GeneratedData(
        config=config,
        departments=data.departments,
        teams=data.teams,
        members=data.members,
        projects=data.projects,
        project_teams=data.project_teams,
        vacations=data.vacations,
        holidays=data.holidays,
        tasks=tasks,
        answer_key=data.answer_key,
    )
