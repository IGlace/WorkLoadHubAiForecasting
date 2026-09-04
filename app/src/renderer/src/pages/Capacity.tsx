import type React from 'react'
import { useEffect, useState } from 'react'
import type { Capacity as CapacityData } from '../../../shared/types'
import { deleteCapacityOverride, getCapacity, setCapacityDefault, setCapacityOverride } from '../api'
import { Field } from '../components/Field'
import { StatusMessage } from '../components/StatusMessage'
import { useApp } from '../context'
import { t } from '../i18n'

export function Capacity(): React.JSX.Element {
  const { meta, visibleTeams } = useApp()
  const [data, setData] = useState<CapacityData | null>(null)
  const [def, setDef] = useState('')
  const [form, setForm] = useState({ member_id: '', week_start: '', weekly_hours: '', reason: '' })
  const [error, setError] = useState<string | null>(null)
  const load = (): void => { getCapacity().then((d) => { setData(d); setDef(String(d.default_weekly_hours)); setError(null) }).catch((e: Error) => setError(e.message)) }
  useEffect(() => { getCapacity().then((d) => { setData(d); setDef(String(d.default_weekly_hours)); setError(null) }).catch((e: Error) => setError(e.message)) }, [])
  const teamIds = new Set(visibleTeams.map((tm) => tm.id))
  const members = (meta?.members ?? []).filter((m) => m.team_id !== null && teamIds.has(m.team_id) && m.counted_in_workload)
  const nameOf = new Map(members.map((m) => [m.id, m.name]))
  const guard = (p: Promise<unknown>): void => { p.then(load).catch((e: Error) => setError(e.message)) }
  return (
    <div>
      <h1>{t('capacity.title')}</h1>
      {error && <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>}
      <section className="panel">
        <Field label={t('capacity.default')}>{(id) => <input id={id} type="number" min={1} max={80} step={0.5} value={def} onChange={(e) => setDef(e.target.value)} onBlur={() => { if (data && Number(def) !== data.default_weekly_hours && Number(def) > 0) guard(setCapacityDefault(Number(def))) }} />}</Field>
      </section>
      <section className="panel">
        <h2>{t('capacity.overrides')}</h2>
        <table>
          <thead><tr><th>{t('capacity.member')}</th><th>{t('capacity.week')}</th><th className="num">{t('capacity.hours')}</th><th>{t('capacity.reason')}</th><th></th></tr></thead>
          <tbody>
            {(data?.overrides ?? []).filter((o) => nameOf.has(o.member_id)).map((o) => (
              <tr key={o.id}><td>{nameOf.get(o.member_id)}</td><td>{o.week_start ?? t('capacity.permanent')}</td><td className="num">{o.weekly_hours.toFixed(1)}</td><td>{o.reason ?? ''}</td>
                <td><button onClick={() => guard(deleteCapacityOverride(o.id))}>{t('capacity.remove')}</button></td></tr>
            ))}
          </tbody>
        </table>
        <form onSubmit={(e) => {
          e.preventDefault()
          if (!form.member_id || !(Number(form.weekly_hours) >= 0)) return
          setCapacityOverride({ member_id: Number(form.member_id), week_start: form.week_start || null, weekly_hours: Number(form.weekly_hours), reason: form.reason || null })
            .then(() => { load(); setForm({ member_id: '', week_start: '', weekly_hours: '', reason: '' }) })
            .catch((err: Error) => setError(err.message))
        }}>
          <Field label={t('capacity.member')}>{(id) => <select id={id} value={form.member_id} onChange={(e) => setForm({ ...form, member_id: e.target.value })}><option value="">–</option>{members.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}</select>}</Field>
          <Field label={t('capacity.week')}>{(id) => <input id={id} type="date" value={form.week_start} onChange={(e) => setForm({ ...form, week_start: e.target.value })} />}</Field>
          <Field label={t('capacity.hours')}>{(id) => <input id={id} type="number" min={0} max={80} step={0.5} value={form.weekly_hours} onChange={(e) => setForm({ ...form, weekly_hours: e.target.value })} />}</Field>
          <Field label={t('capacity.reason')}>{(id) => <input id={id} value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} />}</Field>
          <button className="primary" type="submit">{t('capacity.add')}</button>
        </form>
      </section>
    </div>
  )
}
