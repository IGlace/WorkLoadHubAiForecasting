# Discovery Q&A (2026-09-03)

Answers given by the project owner during the initial brainstorming session.
This is the source of truth for version 1 scope. The derived requirements are in
`requirements-v1.md`; the design rationale lives in `docs/superpowers/specs/`.

## Platform and licensing

| # | Question | Answer |
|---|----------|--------|
| 1 | Which Copilot do team leaders have? | **GitHub Copilot Enterprise.** Copilot CLI is allowed by the organization policy. |
| 2 | Operating system? | **Windows only.** Company laptops with the company Microsoft account, which is the account linked to the Copilot license. WSL can be installed, but **everything must run in PowerShell**: Copilot may not be used through WSL in the company. |
| 3 | Are team leaders comfortable with a terminal? | Yes, but the **primary workflow must not require a terminal** so that anyone can run it. The CLI is a secondary, advanced-user entry point. |
| 4 | Language? | Owner asked whether Python for forecasting plus TypeScript for the local visualization app is the right split. See the approaches section of the design spec for the recommendation. |

## Data and forecasting target

| # | Question | Answer |
|---|----------|--------|
| 5 | Task record fields? | All of these exist: task id, assignee, team, project, type/category, created date, due date, completed date, estimated hours, actual hours, status, priority. |
| 6 | Unit of workload? | **Estimated hours per person only.** Team and department views are aggregations chosen in the UI. The application must expose **configurable parameters**: a default weekly capacity per person, overridable per person and per week (for example when someone is busy with other internal projects). Capacity must also account for **planned public holidays and planned vacations**, which arrive as input data. |
| 7 | What does "workload next period" mean? | **One goal: forecast the predicted work hours of each team member for the next two weeks.** The forecast is run **every two weeks** by the team leader on their own device. The application **notifies them on the device** if they forget. The forecast must **not bypass the weekly capacity setup** and must take off days into account. |
| 8 | History available? | Years of real data exist in the database. For version 1, build **one year of dummy data** covering **multiple departments**, each managed by a **skill team leader**, each containing **multiple teams** managed by **team leaders**. Skill team leaders are **not** counted in the workload (they manage but do not do team work). Team leaders **do** technical work and **are** counted. All task fields are populated; the patterns are for the AI to discover. |
| 9 | Recurring planning cycles (sprints, releases, quarter-end)? | Maybe, but **out of scope for version 1**. |
| 10 | How are tasks assigned? | Mixed: some assigned manually, some self-picked, and it depends on the project each team or person is working on. **Discovering each person's assignment pattern is a core job of the AI** and a major input to how forecasting is applied. |

## Context the history cannot provide

| # | Question | Answer |
|---|----------|--------|
| 11 | Source of leave, holidays, deadlines? | Normally the WorkloadHub application. For version 1: **dummy data**. Use **Morocco public holidays**, a **default capacity of 44 hours per week**, and **dummy planned vacations for some employees in the coming weeks**. |
| 12 | Do team leaders know upcoming project starts and ends a week ahead? | **Yes.** The owner does not yet know how they should provide this to the application. (Design decision recorded in `requirements-v1.md`: an "upcoming events" form in the local UI.) |

## Output and operation

| # | Question | Answer |
|---|----------|--------|
| 13 | Output format and destination? | A **local application opened on the user's device** with a proper UI: better visualization, filtering controls, and, later, a way to **verify prediction accuracy after the two weeks pass**. That evaluation should later feed back into the next forecast so the agent can correct its mistakes. |
| 14 | Decisions the report should support? | **Rebalancing tasks between members**, and **warning or notifying team leaders or skill team leaders** about employees with a high predicted workload. |
| 15 | Scheduled runs? | **Manual runs only** in version 1, once every two weeks. Scheduled forecasting is a future topic. The background service may only do a **daily check** of whether a run is due and **notify the user on the device**. |
| 16 | Skill team leaders? | They use the application mainly to **visualize the forecasts of all teams under them**. They **can run** the forecast **on behalf of a team leader**, but only **for one team at a time**, never all teams at once. This is for when the team leader is unavailable. |

## Governance and rollout

| # | Question | Answer |
|---|----------|--------|
| 17 | Pseudonymize names before sending to Copilot? | **No. Use real personal names.** The company does not require hiding anything. |
| 18 | How is version 1 validated? | **Not now.** The owner will evaluate manually by watching the system. Programmatic evaluation is a future topic. |
| 19 | Distribution? | **A shared installer file** for now. Distribution channel to be discussed later. |
| 20 | Integration with WorkloadHub (API, database, exports)? | **Later.** Not needed for version 1. |

## Additional instructions from the owner

- Record all answers in project documentation (this file).
- Produce a proper `CLAUDE.md` that manages the project and its skills once the design is settled.
- Consider authoring **Copilot skills** (SKILL.md) that the Copilot agent inside the product can use to perform better on this project.
