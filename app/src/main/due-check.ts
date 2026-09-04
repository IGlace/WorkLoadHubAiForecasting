import type { ApiRequest, ApiResponse, Language } from '../shared/ipc'
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
    .map(([id, hours]) => ({ name: nameOf.get(id) ?? String(id), hours }))
}

export const DAY_MS = 24 * 60 * 60 * 1000

export class DueChecker {
  private timer: ReturnType<typeof setInterval> | null = null

  constructor(private readonly deps: { request: (req: ApiRequest) => Promise<ApiResponse>; notify: (title: string, body: string) => void; intervalMs?: number; getLang?: () => Language }) {}

  async checkNow(): Promise<{ due: Team[] }> {
    const [meta, profile] = await Promise.all([this.get<Meta>('/meta'), this.get<Profile>('/profile')])
    if (!meta || !profile) return { due: [] }
    const teams = teamsToCheck(meta, profile)
    const statuses = await Promise.all(teams.map((team) => this.get<TeamDue>(`/teams/${team.id}/due`)))
    const due = teams.filter((_, i) => statuses[i]?.due)
    if (due.length) notifyDue(this.deps.notify, due, this.deps.getLang?.())
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
