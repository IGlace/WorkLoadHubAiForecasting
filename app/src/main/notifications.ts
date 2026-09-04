import type { Language } from '../shared/ipc'
import type { Team } from '../shared/types'

export type Notify = (title: string, body: string) => void

const STRINGS: Record<Language, { due: (teams: string) => { title: string; body: string }; overload: (team: string, list: string) => { title: string; body: string } }> = {
  en: {
    due: (teams) => ({ title: 'Forecast due', body: `No forecast in the last 14 days for ${teams}. Open WorkloadHub Forecast to run one.` }),
    overload: (team, list) => ({ title: `Overload predicted for ${team}`, body: `${list} exceed capacity in the next two weeks.` }),
  },
  fr: {
    due: (teams) => ({ title: 'Prévision à faire', body: `Aucune prévision au cours des 14 derniers jours pour ${teams}. Ouvrez WorkloadHub Forecast pour en lancer une.` }),
    overload: (team, list) => ({ title: `Surcharge prévue pour ${team}`, body: `${list} dépassent la capacité au cours des deux prochaines semaines.` }),
  },
}

export function notifyDue(notify: Notify, teams: Team[], lang: Language = 'en'): void {
  if (!teams.length) return
  const { title, body } = STRINGS[lang].due(teams.map((t) => t.name).join(', '))
  notify(title, body)
}

export function notifyOverload(notify: Notify, teamName: string, members: { name: string; hours: number }[], lang: Language = 'en'): void {
  if (!members.length) return
  const list = members.map((m) => `${m.name} (+${(Math.round(m.hours * 10) / 10).toFixed(1)} h)`).join(', ')
  const { title, body } = STRINGS[lang].overload(teamName, list)
  notify(title, body)
}
