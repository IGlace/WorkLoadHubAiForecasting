import type React from 'react'
import { useEffect, useState } from 'react'
import type { Holiday, Vacation } from '../../../shared/types'
import { createVacation, deleteVacation, getHolidays, getVacations } from '../api'
import { Field } from '../components/Field'
import { StatusMessage } from '../components/StatusMessage'
import { useApp } from '../context'
import { t } from '../i18n'

export function TimeOff(): React.JSX.Element {
  const { meta, visibleTeams } = useApp()
  const [year, setYear] = useState(String(new Date().getFullYear()))
  const [holidays, setHolidays] = useState<Holiday[]>([])
  const [vacations, setVacations] = useState<Vacation[]>([])
  const [form, setForm] = useState({ member_id: '', start_date: '', end_date: '', type: 'vacation' })
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    let cancelled = false
    getHolidays(Number(year))
      .then((hs) => { if (!cancelled) { setHolidays(hs); setError(null) } })
      .catch((e: Error) => { if (!cancelled) setError(e.message) })
    return () => { cancelled = true }
  }, [year])
  const loadVacations = (): void => { getVacations().then((vs) => { setVacations(vs); setError(null) }).catch((e: Error) => setError(e.message)) }
  useEffect(() => { getVacations().then((vs) => { setVacations(vs); setError(null) }).catch((e: Error) => setError(e.message)) }, [])
  const teamIds = new Set(visibleTeams.map((tm) => tm.id))
  const members = (meta?.members ?? []).filter((m) => m.team_id !== null && teamIds.has(m.team_id))
  const nameOf = new Map(members.map((m) => [m.id, m.name]))
  return (
    <div>
      <h1>{t('timeoff.title')}</h1>
      {error && <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>}
      <div className="grid-2">
        <section className="panel">
          <h2>{t('timeoff.holidays')}</h2>
          <Field label={t('timeoff.year')}>{(id) => <input id={id} type="number" value={year} onChange={(e) => setYear(e.target.value)} />}</Field>
          <table><tbody>{holidays.map((h) => <tr key={h.date}><td>{h.date}</td><td>{h.name}</td></tr>)}</tbody></table>
        </section>
        <section className="panel">
          <h2>{t('timeoff.vacations')}</h2>
          <table>
            <thead><tr><th>{t('timeoff.member')}</th><th>{t('timeoff.from')}</th><th>{t('timeoff.to')}</th><th>{t('timeoff.type')}</th><th></th></tr></thead>
            <tbody>
              {vacations.filter((v) => nameOf.has(v.member_id)).map((v) => (
                <tr key={v.id}><td>{nameOf.get(v.member_id)}</td><td>{v.start_date}</td><td>{v.end_date}</td><td>{v.type}</td>
                  <td><button onClick={() => { deleteVacation(v.id).then(loadVacations).catch((e: Error) => setError(e.message)) }}>{t('timeoff.remove')}</button></td></tr>
              ))}
            </tbody>
          </table>
          <form onSubmit={(e) => {
            e.preventDefault()
            if (!form.member_id || !form.start_date || !form.end_date) return
            if (form.end_date < form.start_date) { setError(t('timeoff.rangeError')); return }
            setError(null)
            createVacation({ member_id: Number(form.member_id), start_date: form.start_date, end_date: form.end_date, type: form.type })
              .then(() => { setForm({ member_id: '', start_date: '', end_date: '', type: 'vacation' }); loadVacations() })
              .catch((err: Error) => setError(err.message))
          }}>
            <Field label={t('timeoff.member')}>{(id) => <select id={id} value={form.member_id} onChange={(e) => setForm({ ...form, member_id: e.target.value })}><option value="">–</option>{members.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}</select>}</Field>
            <Field label={t('timeoff.from')}>{(id) => <input id={id} type="date" value={form.start_date} onChange={(e) => setForm({ ...form, start_date: e.target.value })} />}</Field>
            <Field label={t('timeoff.to')}>{(id) => <input id={id} type="date" value={form.end_date} onChange={(e) => setForm({ ...form, end_date: e.target.value })} />}</Field>
            <Field label={t('timeoff.type')}>{(id) => <select id={id} value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value })}><option value="vacation">{t('timeoff.type.vacation')}</option><option value="sick">{t('timeoff.type.sick')}</option><option value="other">{t('timeoff.type.other')}</option></select>}</Field>
            <button className="primary" type="submit">{t('timeoff.add')}</button>
          </form>
        </section>
      </div>
    </div>
  )
}
