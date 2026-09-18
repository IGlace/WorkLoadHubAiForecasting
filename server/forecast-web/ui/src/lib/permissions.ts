import type { Role, TeamPermission } from '../types'

/** The role matrix of design 2026-09-11, section 3.2, as the permissions page prints it. */
export const ROLE_MATRIX: { role: Role; run: string; view: string }[] = [
  { role: 'ADMIN', run: 'any team', view: 'any team' },
  { role: 'SKILL_TEAM_LEADER', run: 'a team whose parent team they manage, one at a time', view: 'those teams and their own memberships' },
  { role: 'TEAM_LEADER', run: 'the teams they manage', view: 'those teams and their own memberships' },
  { role: 'MEMBER', run: 'none', view: 'the teams they belong to' },
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
