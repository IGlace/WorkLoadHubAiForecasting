CREATE TABLE IF NOT EXISTS departments (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    skill_team_leader_id INTEGER
);
CREATE TABLE IF NOT EXISTS teams (
    id INTEGER PRIMARY KEY,
    department_id INTEGER NOT NULL REFERENCES departments(id),
    name TEXT NOT NULL,
    team_leader_id INTEGER
);
CREATE TABLE IF NOT EXISTS members (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    team_id INTEGER REFERENCES teams(id),
    department_id INTEGER NOT NULL REFERENCES departments(id),
    role TEXT NOT NULL CHECK (role IN ('member', 'team_leader', 'skill_team_leader')),
    counted_in_workload INTEGER NOT NULL DEFAULT 1,
    active_from TEXT,
    active_to TEXT
);
CREATE TABLE IF NOT EXISTS projects (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    department_id INTEGER NOT NULL REFERENCES departments(id),
    start_date TEXT NOT NULL,
    deadline TEXT NOT NULL,
    type TEXT NOT NULL,
    status TEXT NOT NULL,
    created_by INTEGER
);
CREATE TABLE IF NOT EXISTS project_teams (
    project_id INTEGER NOT NULL REFERENCES projects(id),
    team_id INTEGER NOT NULL REFERENCES teams(id),
    PRIMARY KEY (project_id, team_id)
);
CREATE TABLE IF NOT EXISTS tasks (
    id INTEGER PRIMARY KEY,
    title TEXT NOT NULL,
    project_id INTEGER REFERENCES projects(id),
    assignee_id INTEGER NOT NULL REFERENCES members(id),
    team_id INTEGER NOT NULL REFERENCES teams(id),
    type TEXT NOT NULL,
    priority TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    assigned_at TEXT NOT NULL,
    due_date TEXT,
    completed_at TEXT,
    estimated_hours REAL NOT NULL,
    actual_hours REAL,
    created_by INTEGER,
    assignment_mode TEXT CHECK (assignment_mode IN ('manual', 'self_picked', 'project'))
);
CREATE INDEX IF NOT EXISTS idx_tasks_assignee ON tasks(assignee_id);
CREATE INDEX IF NOT EXISTS idx_tasks_team ON tasks(team_id);
CREATE TABLE IF NOT EXISTS capacity_defaults (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    weekly_hours REAL NOT NULL
);
INSERT OR IGNORE INTO capacity_defaults (id, weekly_hours) VALUES (1, 40.0);
CREATE TABLE IF NOT EXISTS capacity_overrides (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL REFERENCES members(id),
    week_start TEXT,
    weekly_hours REAL NOT NULL,
    reason TEXT,
    UNIQUE (member_id, week_start)
);
CREATE TABLE IF NOT EXISTS holidays (
    date TEXT NOT NULL,
    name TEXT NOT NULL,
    country TEXT NOT NULL DEFAULT 'MA',
    PRIMARY KEY (date, country)
);
CREATE TABLE IF NOT EXISTS vacations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL REFERENCES members(id),
    start_date TEXT NOT NULL,
    end_date TEXT NOT NULL,
    type TEXT NOT NULL DEFAULT 'vacation'
);
CREATE TABLE IF NOT EXISTS profiles (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    member_id INTEGER REFERENCES members(id),
    role TEXT
);
CREATE TABLE IF NOT EXISTS runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    team_id INTEGER NOT NULL REFERENCES teams(id),
    as_of TEXT NOT NULL,
    requested_by INTEGER,
    status TEXT NOT NULL,
    champion_model TEXT,
    backtest_mase REAL,
    started_at TEXT NOT NULL,
    finished_at TEXT,
    ai_status TEXT NOT NULL DEFAULT 'not_requested'
);
CREATE TABLE IF NOT EXISTS forecasts (
    run_id INTEGER NOT NULL REFERENCES runs(id),
    member_id INTEGER NOT NULL REFERENCES members(id),
    week_start TEXT NOT NULL,
    demand_hours REAL NOT NULL,
    demand_low REAL NOT NULL,
    demand_high REAL NOT NULL,
    capacity_hours REAL NOT NULL,
    overload_hours REAL NOT NULL,
    open_task_hours REAL NOT NULL,
    new_task_hours REAL NOT NULL,
    PRIMARY KEY (run_id, member_id, week_start)
);
CREATE TABLE IF NOT EXISTS run_narratives (
    run_id INTEGER PRIMARY KEY REFERENCES runs(id),
    json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS run_facts (
    run_id INTEGER PRIMARY KEY REFERENCES runs(id),
    json TEXT NOT NULL
);
