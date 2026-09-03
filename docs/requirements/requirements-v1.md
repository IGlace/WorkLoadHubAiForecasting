# Requirements: WorkloadHub AI Forecasting, version 1

Derived from `2026-09-03-discovery-qa.md`. Items marked **[decision]** are choices
made by the assistant to fill a gap; the owner can overturn them.

## 1. Purpose

A locally installed application that lets a team leader forecast the **estimated
work hours of each team member for the next two weeks**, using the team's task
history, the member's capacity, planned holidays and vacations, and known upcoming
events, with the user's own **GitHub Copilot Enterprise** license providing the AI
reasoning. Version 1 works on **dummy data** only.

## 2. Users and roles

| Role | Counted in workload | Can run forecast | Can view |
|------|--------------------|------------------|----------|
| Team member | yes | no | not a user of the app in v1 |
| Team leader | **yes** (does technical work) | own team | own team |
| Skill team leader | **no** (manages only) | any single team under them, one at a time, on behalf of the team leader | all teams in their department |

## 3. Functional requirements

### 3.1 Forecast
- F1. Forecast horizon is exactly **two calendar weeks** ahead of the run date, reported per week.
- F2. Unit is **estimated hours per person per week**. Team and department numbers are sums of members, computed in the UI.
- F3. The forecast **must respect capacity**: for each person and week, available capacity = configured weekly capacity, minus public holidays, minus planned vacations, minus per-week overrides. Predicted demand above capacity is reported as **overload**, never silently truncated. **[decision]** The report shows three figures per person per week: predicted demand, available capacity, and overload (demand minus capacity, floored at zero).
- F4. Predicted demand combines **[decision]**: (a) hours from already-assigned open tasks that fall in the window, and (b) hours from tasks expected to arrive, estimated from each person's historical arrival and assignment pattern.
- F5. The AI must **discover per-person assignment patterns** (manual assignment, self-picked, project-driven) from the history and use them to explain and adjust the forecast.
- F6. The **numeric forecast is computed by deterministic code**. Copilot never invents numbers; it reads computed facts and writes explanations, risks and recommendations, and may propose bounded adjustments that are shown as such. (Research basis: `docs/research/2026-09-03-research-notes.md`.)
- F7. Runs are **manual**, expected every two weeks. No scheduled forecasting in v1.

### 3.2 Configuration
- C1. Default weekly capacity per person, default **44 hours**.
- C2. Per-person capacity override, and per-person **per-week** override (for example internal project time).
- C3. Public holiday calendar; v1 ships the **Morocco** calendar.
- C4. Planned vacations per person (date ranges).
- C5. **Upcoming events** entered by the team leader: project start or end, affected members or team, expected effect. **[decision]** Entered through a form in the local UI before a run; stored locally; passed to the forecast as context.

### 3.3 Local application (primary entry point)
- U1. Installable on Windows from a **single shared installer file**; no terminal needed to install or run.
- U2. Opens a UI on the user's device with: run forecast for a team, view results per person, per team, per department; filter by department, team, person, week; show demand vs capacity vs overload; show the AI narrative, risks and recommendations.
- U3. Shows a **rebalancing** view: overloaded and under-loaded members side by side, with the AI's suggested moves.
- U4. **Notifications** on the device when a forecast is due (a daily check), and warnings for members predicted to be overloaded.
- U5. Skill team leaders see all teams of their department and may run one team at a time.
- U6. Keeps a **history of runs** so a later version can compare forecast against actuals.

### 3.4 CLI (secondary entry point)
- L1. The same workflow runnable from **PowerShell** for advanced users: run, list runs, export, configure.
- L2. Everything the CLI does, the UI can do; the CLI is not required for any v1 user journey.

### 3.5 Dummy data
- D1. One year of task history.
- D2. Several departments, each with a skill team leader; several teams per department, each with a team leader who also holds tasks.
- D3. Every task has: id, assignee, team, project, type, created date, due date, completed date, estimated hours, actual hours, status, priority.
- D4. Generated with **deliberate, discoverable patterns**: per-person arrival rates, project-driven bursts, manual vs self-picked assignment styles, weekly seasonality, holiday dips. Patterns are documented in a hidden "answer key" so the AI's discoveries can be checked by the owner.
- D5. Includes Morocco public holidays and planned vacations for some employees in the weeks after the dataset's end date.
- D6. Regenerable from a seed so results are reproducible.

## 4. Non-functional requirements
- N1. **Windows, PowerShell only.** No WSL dependency at install or run time.
- N2. Uses the user's **GitHub Copilot Enterprise** seat through supported GitHub tooling (Copilot CLI or Copilot SDK). No separate API keys.
- N3. Works without Copilot for the numeric part: if Copilot is unavailable the app still shows the deterministic forecast and says the narrative is missing.
- N4. Real names are used; no pseudonymization required by the company.
- N5. All data stays on the user's device except what is sent to Copilot for the narrative.
- N6. One run should finish in well under a few minutes on a company laptop.

## 5. Out of scope for version 1
- Integration with the WorkloadHub application (API, database, exports).
- Scheduled forecasting runs.
- Programmatic accuracy evaluation and feedback loops (the data model must not prevent it; see U6).
- Sprint or release cycles as model inputs.
- Distribution channel beyond a shared installer file.

## 6. Open decisions for the design spec
- Working week definition for capacity: **[decision]** Monday to Friday, 44 h / 5 = 8.8 h per working day, unless the owner specifies Saturday work.
- How to attribute a task's estimated hours to weeks: **[decision]** spread evenly over the working days between its start (assignment) date and its due date, clipped to the window.
- Language and packaging: see the approaches in the design spec.
