export type Role = 'member' | 'team_leader' | 'skill_team_leader'

export interface Department { id: number; name: string; skill_team_leader_id: number | null }
export interface Team { id: number; department_id: number; name: string; team_leader_id: number | null }
export interface Member {
  id: number; name: string; team_id: number | null; department_id: number; role: Role; counted_in_workload: number
}
export interface Meta { departments: Department[]; teams: Team[]; members: Member[]; capacity_default: number }
export interface Profile { member_id: number | null; role: Role | null }

export interface RunSummary {
  id: number; team_id: number; as_of: string; requested_by: number | null; status: string
  champion_model: string | null; backtest_mase: number | null; started_at: string; finished_at: string | null
  ai_status: string
}
export interface ForecastRow {
  run_id: number; member_id: number; week_start: string; demand_hours: number; demand_low: number; demand_high: number
  capacity_hours: number; overload_hours: number; open_task_hours: number; new_task_hours: number
}
export interface RunCreated {
  run_id: number; team_id: number; as_of: string; weeks: string[]; champion: string; backtest_mase: number
  forecasts: ForecastRow[]
}

export interface HistoryPoint { week: string; hours: number; tasks: number }
export interface MemberForecastFact {
  week: string; demand: number; low: number; high: number; capacity: number; overload: number
  open_hours: number; new_hours: number
}
export interface OpenTaskFact {
  id: number; title: string; type: string; priority: string; estimated_hours: number; due_date: string | null
  overdue: boolean; project_id: number | null
}
export interface PatternStats {
  member_id: number; trend_hours_per_week: number | null; top_weekday: string | null
  weekday_shares: Record<string, number>; estimate_ratio_median: number | null; cycle_days_median: number | null
  cycle_days_by_type: Record<string, number>; lateness_days_median: number | null; share_late: number | null
  deadline_proximity_corr: number | null; share_with_project: number | null; hours_by_project: Record<string, number>
  open_tasks: number; open_est_hours: number; overdue_open: number; cluster: number
  [extra: string]: unknown
}
export interface MemberFacts {
  id: number; name: string; role: Role; history_13w: HistoryPoint[]; forecast: MemberForecastFact[]
  patterns: PatternStats; open_tasks: OpenTaskFact[]
}
export interface ProjectFact {
  id: number; name: string; start_date: string; deadline: string; status: string; type: string
  active_in_window: boolean; starting_in_window: boolean; ending_in_window: boolean
}
export interface RunFacts {
  run: { id: number | null; as_of: string; weeks: string[]; generated_at: string }
  team: { id: number; name: string; department_id: number; team_leader_id: number | null
    totals: { week: string; demand: number; capacity: number }[] }
  members: MemberFacts[]
  projects: ProjectFact[]
  model: { champion: string; champion_mase: number; mase_by_model: Record<string, number>; backtest_origins: string[]
    horizons: number[]; limitations: string; interval: { basis: string; horizons: Record<string, { low: number; high: number }> } }
  rebalancing_candidates: { overloaded: { member_id: number; name: string; overload_hours: number }[]
    underloaded: { member_id: number; name: string; spare_hours: number }[] }
}

export type RiskLevel = 'low' | 'medium' | 'high'
export interface PatternFinding { kind: string; statement: string; evidence: string }
export interface MemberNarrative {
  member_id: number; name: string; risk_level: RiskLevel; summary: string; patterns: PatternFinding[]; warnings: string[]
}
export interface TeamRisk { title: string; detail: string; severity: RiskLevel; member_ids: number[] }
export interface RebalancingMove {
  from_member_id: number; to_member_id: number; week: string; hours: number; reason: string; confidence: RiskLevel
}
export interface SuggestedAdjustment { member_id: number; week: string; delta_hours: number; reason: string }
export interface Narrative {
  run_summary: string; members: MemberNarrative[]; team_risks: TeamRisk[]; rebalancing: RebalancingMove[]
  suggested_adjustments: SuggestedAdjustment[]; model_notes: string
}
export interface RunDetail { run: RunSummary; forecasts: ForecastRow[]; facts: RunFacts | null; narrative: Narrative | null }
export interface NarrativeOutcome {
  run_id: number; status: 'ok' | 'unverified' | 'failed'; ai_status: string; narrative: Narrative | null
  error: string | null; reason: string | null; attempts: number; tool_calls: string[]
}

export interface CopilotStatus {
  cli_path: string | null; cli_source: string; authenticated: boolean | null; login: string | null; message: string
  ready: boolean
}

export interface Project {
  id: number; name: string; department_id: number; start_date: string; deadline: string; type: string; status: string
  created_by: number | null; team_ids: number[]
}
export interface ProjectInput {
  name: string; department_id: number; start_date: string; deadline: string; team_ids: number[]; type: string
}
export interface ProjectUpdate {
  name: string; start_date: string; deadline: string; team_ids: number[]; type: string
  status: 'planned' | 'active' | 'done'
}
export interface CapacityOverride {
  id: number; member_id: number; week_start: string | null; weekly_hours: number; reason: string | null
}
export interface Capacity { default_weekly_hours: number; overrides: CapacityOverride[] }
export interface Holiday { date: string; name: string; country: string }
export interface Vacation { id: number; member_id: number; start_date: string; end_date: string; type: string }
export interface TeamDue { team_id: number; due: boolean; last_run_id: number | null; last_finished_at: string | null }
export interface OverviewTeam {
  team_id: number; team_name: string; run_id: number | null; as_of: string | null; finished_at: string | null
  due: boolean; weeks: { week: string; demand: number; capacity: number; overload: number }[]
  overloaded: { member_id: number; name: string; overload_hours: number }[]
}
export interface DepartmentOverview { department_id: number; teams: OverviewTeam[] }
