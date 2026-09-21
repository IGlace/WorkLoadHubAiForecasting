// The response bodies of forecast-web (design 2026-09-18, section 3.3). The module's own records keep their
// Java field names; the host's views add names and parse the stored JSON strings into objects.

export type Role = 'ADMIN' | 'CENTER_MANAGER' | 'SKILL_TEAM_LEADER' | 'TEAM_LEADER' | 'MEMBER' | 'VIEWER'

export interface SystemView {
  today: string
  clockPinned: boolean
  clockAdjustable: boolean
  windows: number
  defaultWeeklyHours: number
  runThreads: number
  actingUserHeader: string
  bootstrapUserId: string | null
}

export interface TeamRelation { teamId: string; name: string; relation: 'manages' | 'member' | 'manages-parent' }
export interface UserView { id: string; fullName: string; role: Role; jobTitle: string | null; department: string | null; active: boolean; teams: TeamRelation[] }
export interface TeamView { id: string; name: string; managerId: string | null; managerName: string | null; parentTeamId: string | null; parentTeamName: string | null; memberCount: number }
export interface MemberView { id: string; fullName: string; role: Role; jobTitle: string | null }
export interface TeamDetailView { id: string; name: string; managerId: string | null; managerName: string | null; parentTeamId: string | null; parentTeamName: string | null; members: MemberView[] }
export interface TeamPermission { teamId: string; name: string; canRun: boolean; canView: boolean; runReason: string; viewReason: string }
export interface MeView { user: MemberView; hasToken: boolean; teams: TeamPermission[] }
export interface PermissionView { userId: string; role: Role; teamId: string; canRun: boolean; canView: boolean; runReason: string; viewReason: string }

export type RunStatus = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED'
export interface RunSummary { id: string; teamId: string; requestedBy: string | null; asOf: string; status: RunStatus; mae: number | null; error: string | null; createdAt: string; finishedAt: string | null }
export interface BacktestScore { origin: string; horizon: number; mae: number | null }
export interface MemberWindowForecast {
  userId: string; windowIndex: number; windowStart: string; windowEnd: string; demandHrs: number; lowHrs: number; highHrs: number
  capacityHrs: number; overloadHrs: number; workingDays: number; absenceHrs: number; backlogExcessHrs: number; dueExcessHrs: number
}
export interface MemberDayForecast { userId: string; day: string; windowIndex: number; demandHrs: number; capacityHrs: number; overloadHrs: number; workingDay: boolean }
export interface RunView { run: RunSummary; scores: BacktestScore[]; memberWindows: MemberWindowForecast[]; memberDays: MemberDayForecast[]; facts: Facts; members: MemberView[] }
export interface CurrentDayForecast { teamId: string; userId: string; day: string; runId: string; demandHrs: number; capacityHrs: number; overloadHrs: number; forecastAt: string }
export interface ProgressLabel { en: string; fr: string }
export interface RunProgress { runId: string; phase: string; percent: number; message: string; label: ProgressLabel }

/** A number the module could not compute is NaN, which Jackson writes as the string "NaN". */
export type Score = number | 'NaN'
export interface AccuracyRow { userId: string; day: string; runId: string; lead: number; forecastHrs: Score; loggedHrs: Score; capacityHrs: Score; forecastOverload: boolean; actualOverload: boolean }
export interface AccuracyScore { scope: 'team' | 'member' | 'lead'; key: string; n: number; mae: Score; bias: Score; mase: Score | null; maseN: number; overloadPrecision: Score; overloadRecall: Score }
export interface AccuracyResult { teamId: string; from: string; to: string; evaluatedAt: string; current: AccuracyRow[]; scores: AccuracyScore[]; nonWorkingDays: number }

export type NarrativeStatus = 'OK' | 'UNVERIFIED' | 'FAILED'
export type Level = 'low' | 'medium' | 'high'
export interface NarrativePattern { kind: string; statement: string; evidence: string }
export interface LikelyWork { statement: string; evidence: string; confidence: Level }
export interface NarrativeMember { member_id: string; name: string; risk_level: Level; summary: string; patterns?: NarrativePattern[]; warnings?: string[]; likely_work?: LikelyWork[] }
export interface TeamRisk { title: string; detail: string; severity: Level; member_ids?: string[] }
export interface Move { from_member_id: string; to_member_id: string; window: string; hours: number; reason: string; confidence: Level; task_keys?: string[] }
export interface Adjustment { member_id: string; window: string; delta_hours: number; reason: string }
export interface Narrative { run_summary: string; members: NarrativeMember[]; team_risks?: TeamRisk[]; rebalancing?: Move[]; suggested_adjustments?: Adjustment[]; model_notes?: string }
export interface Verification { checked: number; unverified: string[]; fields?: Record<string, number[]> }
export interface NarrativeView {
  id: string; runId: string; language: string; status: NarrativeStatus; model: string | null; narrative: Narrative | null; rawText: string | null
  verification: Verification | null; usage: unknown; error: string | null; attempts: number; toolCalls: number; createdAt: string
}
export interface NarrationStarted { runId: string; language: string; phase: string }

export interface CopilotStatus {
  userId: string; hasToken: boolean; runtimeAvailable: boolean; runtimePath: string | null; runtimeVersion: string | null
  authenticated: boolean | null; login: string | null; quotaJson: string | null; message: string | null
}
export interface TokenState { hasToken: boolean }

// The facts sent to Copilot, stored for audit (only the parts the pages surface are typed).
export interface FactsWindow { index: number; start: string; end: string; working_days: number }
export interface PressedMember { member_id: string; name: string; overload_hours?: number; spare_hours?: number; backlog_excess_hrs?: number; due_excess_hrs?: number }
export interface Facts {
  run: { id: string; as_of: string; windows: FactsWindow[]; generated_at: string; origin: string; horizons: number[] }
  team: { id: string; name: string | null; totals: { window: number; start: string; end: string; demand: number; capacity: number }[] }
  members: unknown[]
  projects: unknown[]
  model: { name: string; target: string; mae: number | null; mean_actual_hours: number | null; confidence: string; backtest_origins: string[]; horizons: number[]; windows: number; limitations?: string | string[] }
  rebalancing_candidates: { overloaded: PressedMember[]; underloaded: PressedMember[]; backlog_pressed: PressedMember[]; deadline_pressed: PressedMember[] }
  pending_holidays: { title: string; start: string; end: string }[]
  data_quality: { unresolved_assignments: string[]; unlogged_tasks: string[]; history_weeks: number }
  [key: string]: unknown
}
