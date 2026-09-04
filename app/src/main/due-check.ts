import type { ApiRequest, ApiResponse } from '../shared/ipc'
import type { ForecastRow, Member, Meta, Profile, Team, TeamDue } from '../shared/types'
import { notifyDue } from './notifications'

export function teamsToCheck(meta: Meta, profile: Profile): Team[] {
  if (profile.member_id === null) return []
  const me = meta.members.find((m) => m.id === profile.member_id)
  if (!me) return []
  if (me.role === 'skill_team_leader') return meta.teams.filter((t) => t.department_id === me.department_id)
  return meta.teams.filter((t) => t.id === me.team_id)
}

export function overloadedMembers(forecasts: ForecastRow[], members: Member[]): { name: string; hours: number }[] {
  const totals = new Map<number, number>()
  for (const row of forecasts) totals.set(row.member_id, (totals.get(row.member_id) ?? 0) + row.overload_hours)
  const nameOf = new Map(members.map((m) => [m.id, m.name]))
  return [...totals.entries()]
    .filter(([, hours]) => hours > 0)
    .sort((a, b) => b[1] - a[1])
    .map(([id, hours]) => ({ name: nameOf.get(id) ?? String(id), hours: Math.round(hours * 100) / 100 }))
}

export const DAY_MS = 24 * 60 * 60 * 1000

export class DueChecker {
  private timer: ReturnType<typeof setInterval> | null = null

  constructor(private readonly deps: { request: (req: ApiRequest) => Promise<ApiResponse>; notify: (title: string, body: string) => void; intervalMs?: number }) {}

  async checkNow(): Promise<{ due: Team[] }> {
    const [meta, profile] = await Promise.all([this.get<Meta>('/meta'), this.get<Profile>('/profile')])
    if (!meta || !profile) return { due: [] }
    const due: Team[] = []
    for (const team of teamsToCheck(meta, profile)) {
      const status = await this.get<TeamDue>(`/teams/${team.id}/due`)
      if (status?.due) due.push(team)
    }
    if (due.length) notifyDue(this.deps.notify, due)
    return { due }
  }

  start(): void {
    this.stop()
    this.timer = setInterval(() => { void this.checkNow() }, this.deps.intervalMs ?? DAY_MS)
  }

  stop(): void { if (this.timer) { clearInterval(this.timer); this.timer = null } }

  private async get<T>(path: string): Promise<T | null> {
    const res = await this.deps.request({ method: 'GET', path })
    return res.ok ? (res.data as T) : null
  }
}
