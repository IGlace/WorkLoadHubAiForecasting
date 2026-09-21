import type { Role, TeamPermission } from '../types'

/**
 * The role matrix of design 2026-09-11, section 3.2, as the permissions page prints it, rebound to the
 * hierarchy: a team is a team leader and the people who report to them directly (design 2026-09-21).
 */
export const ROLE_MATRIX: { role: Role; run: string; view: string }[] = [
  { role: 'ADMIN', run: 'any team', view: 'any team' },
  { role: 'SKILL_TEAM_LEADER', run: 'the team of a leader who reports to them, one at a time', view: 'those teams and their own' },
  { role: 'TEAM_LEADER', run: 'their own team', view: 'their own team and the one they belong to' },
  { role: 'MEMBER', run: 'none', view: 'the team they belong to' },
  { role: 'VIEWER', run: 'none', view: 'any team' },
  { role: 'CENTER_MANAGER', run: 'none', view: 'any team' },
]

export const ROLES: Role[] = ROLE_MATRIX.map((r) => r.role)

export interface PermissionSummary { total: number; runnable: number; viewable: number }

export function summarise(teams: TeamPermission[]): PermissionSummary {
  return { total: teams.length, runnable: teams.filter((t) => t.canRun).length, viewable: teams.filter((t) => t.canView).length }
}

/** The one-line verdict the teams page prints next to a role badge. */
export function describe(role: Role, s: PermissionSummary): string {
  if (s.total === 0) return 'no teams'
  const view = s.viewable === s.total ? 'every team' : `${s.viewable} of ${s.total} teams`
  const run = s.runnable === 0 ? 'runs none' : s.runnable === s.total ? 'runs every team' : `runs ${s.runnable}`
  return `${role}: views ${view}, ${run}`
}
