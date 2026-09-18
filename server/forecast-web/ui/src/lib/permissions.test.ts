import { describe, expect, it } from 'vitest'
import { describe as verdict, ROLE_MATRIX, summarise } from './permissions'

describe('permissions', () => {
  const teams = [
    { teamId: 'a', name: 'A', canRun: true, canView: true, runReason: '', viewReason: '' },
    { teamId: 'b', name: 'B', canRun: false, canView: true, runReason: '', viewReason: '' },
    { teamId: 'c', name: 'C', canRun: false, canView: false, runReason: '', viewReason: '' },
  ]
  it('counts what the acting user may do', () => {
    expect(summarise(teams)).toEqual({ total: 3, runnable: 1, viewable: 2 })
    expect(verdict('TEAM_LEADER', summarise(teams))).toBe('TEAM_LEADER: views 2 of 3 teams, runs 1')
    expect(verdict('ADMIN', { total: 3, runnable: 3, viewable: 3 })).toBe('ADMIN: views every team, runs every team')
    expect(verdict('MEMBER', { total: 0, runnable: 0, viewable: 0 })).toBe('no teams')
  })
  it('lists the six roles of the design once each', () => {
    expect(ROLE_MATRIX.map((r) => r.role)).toEqual(['ADMIN', 'SKILL_TEAM_LEADER', 'TEAM_LEADER', 'MEMBER', 'VIEWER', 'CENTER_MANAGER'])
  })
})
