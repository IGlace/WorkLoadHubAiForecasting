import { describe, expect, it, vi } from 'vitest'
import { notifyDue, notifyOverload } from '../notifications'

describe('notification texts', () => {
  it('lists due teams', () => {
    const notify = vi.fn()
    notifyDue(notify, [{ id: 1, name: 'T1', department_id: 1, team_leader_id: null }, { id: 2, name: 'T2', department_id: 1, team_leader_id: null }])
    expect(notify).toHaveBeenCalledWith('Forecast due', 'No forecast in the last 14 days for T1, T2. Open WorkloadHub Forecast to run one.')
  })
  it('lists overloaded members with one decimal', () => {
    const notify = vi.fn()
    notifyOverload(notify, 'T1', [{ name: 'Yara', hours: 8 }, { name: 'Ali', hours: 5.55 }])
    expect(notify).toHaveBeenCalledWith('Overload predicted for T1', 'Yara (+8.0 h), Ali (+5.6 h) exceed capacity in the next two weeks.')
  })
  it('does nothing when nobody is overloaded', () => {
    const notify = vi.fn()
    notifyOverload(notify, 'T1', [])
    expect(notify).not.toHaveBeenCalled()
  })
  it('lists due teams in French', () => {
    const notify = vi.fn()
    notifyDue(notify, [{ id: 1, name: 'T1', department_id: 1, team_leader_id: null }, { id: 2, name: 'T2', department_id: 1, team_leader_id: null }], 'fr')
    expect(notify).toHaveBeenCalledWith('Prévision à faire', 'Aucune prévision au cours des 14 derniers jours pour T1, T2. Ouvrez WorkloadHub Forecast pour en lancer une.')
  })
  it('lists overloaded members in French, still with one decimal', () => {
    const notify = vi.fn()
    notifyOverload(notify, 'T1', [{ name: 'Yara', hours: 8 }, { name: 'Ali', hours: 5.55 }], 'fr')
    expect(notify).toHaveBeenCalledWith('Surcharge prévue pour T1', 'Yara (+8.0 h), Ali (+5.6 h) dépassent la capacité au cours des deux prochaines semaines.')
  })
})
