import { describe, expect, it, vi } from 'vitest'
import type { Meta, Profile } from '../../shared/types'
import { DueChecker, overloadedMembers, teamsToCheck } from '../due-check'

const meta: Meta = {
  departments: [{ id: 1, name: 'D1', skill_team_leader_id: 10 }, { id: 2, name: 'D2', skill_team_leader_id: 20 }],
  teams: [{ id: 1, department_id: 1, name: 'T1', team_leader_id: 11 }, { id: 2, department_id: 1, name: 'T2', team_leader_id: 12 }, { id: 3, department_id: 2, name: 'T3', team_leader_id: 21 }],
  members: [
    { id: 10, name: 'Sara', team_id: null, department_id: 1, role: 'skill_team_leader', counted_in_workload: 0 },
    { id: 11, name: 'Ali', team_id: 1, department_id: 1, role: 'team_leader', counted_in_workload: 1 },
    { id: 12, name: 'Nour', team_id: 2, department_id: 1, role: 'team_leader', counted_in_workload: 1 },
  ],
  capacity_default: 40,
}

describe('teamsToCheck', () => {
  it('is the own team for a team leader', () => {
    expect(teamsToCheck(meta, { member_id: 11, role: 'team_leader' }).map((t) => t.id)).toEqual([1])
  })
  it('is every team of the department for a skill team leader', () => {
    expect(teamsToCheck(meta, { member_id: 10, role: 'skill_team_leader' }).map((t) => t.id)).toEqual([1, 2])
  })
  it('is empty without a profile', () => {
    expect(teamsToCheck(meta, { member_id: null, role: null })).toEqual([])
  })
})

describe('overloadedMembers', () => {
  it('sums overload over the weeks and keeps only positive totals, largest first', () => {
    const rows = [
      { member_id: 11, week_start: '2026-09-07', overload_hours: 2 }, { member_id: 11, week_start: '2026-09-14', overload_hours: 3.5 },
      { member_id: 12, week_start: '2026-09-07', overload_hours: 0 }, { member_id: 12, week_start: '2026-09-14', overload_hours: 0 },
      { member_id: 13, week_start: '2026-09-07', overload_hours: 8 },
    ].map((r) => ({ run_id: 1, demand_hours: 0, demand_low: 0, demand_high: 0, capacity_hours: 40, open_task_hours: 0, new_task_hours: 0, ...r }))
    const members = [...meta.members, { id: 13, name: 'Yara', team_id: 1, department_id: 1, role: 'member' as const, counted_in_workload: 1 }]
    expect(overloadedMembers(rows, members)).toEqual([{ name: 'Yara', hours: 8 }, { name: 'Ali', hours: 5.5 }])
  })
})

describe('DueChecker', () => {
  it('asks the service per team and notifies once for the due ones', async () => {
    const request = vi.fn(async (req: { path: string }) => {
      if (req.path === '/meta') return { ok: true as const, status: 200, data: meta }
      if (req.path === '/profile') return { ok: true as const, status: 200, data: { member_id: 10, role: 'skill_team_leader' } satisfies Profile }
      if (req.path === '/teams/1/due') return { ok: true as const, status: 200, data: { team_id: 1, due: true, last_run_id: null, last_finished_at: null } }
      if (req.path === '/teams/2/due') return { ok: true as const, status: 200, data: { team_id: 2, due: false, last_run_id: 4, last_finished_at: '2026-09-01T10:00:00' } }
      return { ok: false as const, status: 404, error: 'nope' }
    })
    const notify = vi.fn()
    const checker = new DueChecker({ request: request as never, notify })
    const result = await checker.checkNow()
    expect(result.due.map((t) => t.name)).toEqual(['T1'])
    expect(notify).toHaveBeenCalledTimes(1)
    expect(notify.mock.calls[0]![1]).toContain('T1')
  })
  it('stays silent when the service is not ready', async () => {
    const notify = vi.fn()
    const checker = new DueChecker({ request: (async () => ({ ok: false as const, status: 0, error: 'service not ready' })) as never, notify })
    expect(await checker.checkNow()).toEqual({ due: [] })
    expect(notify).not.toHaveBeenCalled()
  })
})
