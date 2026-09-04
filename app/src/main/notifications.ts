import type { Team } from '../shared/types'

export type Notify = (title: string, body: string) => void

export function notifyDue(notify: Notify, teams: Team[]): void {
  if (!teams.length) return
  notify('Forecast due', `No forecast in the last 14 days for ${teams.map((t) => t.name).join(', ')}. Open WorkloadHub Forecast to run one.`)
}

export function notifyOverload(notify: Notify, teamName: string, members: { name: string; hours: number }[]): void {
  if (!members.length) return
  const list = members.map((m) => `${m.name} (+${(Math.round(m.hours * 10) / 10).toFixed(1)} h)`).join(', ')
  notify(`Overload predicted for ${teamName}`, `${list} exceed capacity in the next two weeks.`)
}
