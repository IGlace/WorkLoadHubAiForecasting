import type React from 'react'
import { useEffect, useState } from 'react'
import type { RunDetail } from '../../../shared/types'
import { getRun, getRuns } from '../api'
import { Field } from '../components/Field'
import { RiskBadge } from '../components/RiskBadge'
import { StatusMessage } from '../components/StatusMessage'
import { useApp } from '../context'
import { hours, weekLabel } from '../format'
import { t } from '../i18n'

interface Fetched { team: string; detail: RunDetail | 'none' | null; error: string | null }

export function Rebalancing(): React.JSX.Element {
  const { visibleTeams } = useApp()
  const [team, setTeam] = useState('')
  const selectedTeam = team || (visibleTeams[0] ? String(visibleTeams[0].id) : '')
  // `fetched.team` tags which team the payload belongs to; a superseded team (the select
  // changed since this was written) is treated as empty below, so a stale error or a stale
  // run from the previous team never renders while the next team's fetch is in flight.
  const [fetched, setFetched] = useState<Fetched>({ team: '', detail: null, error: null })
  useEffect(() => {
    if (!selectedTeam) return
    let cancelled = false
    getRuns(Number(selectedTeam))
      .then<RunDetail | 'none'>((runs) => { const ok = runs.filter((r) => r.status === 'done').sort((a, b) => b.id - a.id)[0]; return ok ? getRun(ok.id) : 'none' })
      .then((d) => { if (!cancelled) setFetched({ team: selectedTeam, detail: d, error: null }) })
      .catch((e: Error) => { if (!cancelled) setFetched({ team: selectedTeam, detail: null, error: e.message }) })
    return () => { cancelled = true }
  }, [selectedTeam])
  const detail = fetched.team === selectedTeam ? fetched.detail : null
  const error = fetched.team === selectedTeam ? fetched.error : null
  const facts = detail && detail !== 'none' ? detail.facts : null
  const names = new Map(facts?.members.map((m) => [m.id, m.name]) ?? [])
  const narrative = detail && detail !== 'none' ? detail.narrative : null
  return (
    <div>
      <h1>{t('rebalancing.title')}</h1>
      {error && <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>}
      <Field label={t('run.team')}>
        {(id) => <select id={id} value={selectedTeam} onChange={(e) => setTeam(e.target.value)}>{visibleTeams.map((tm) => <option key={tm.id} value={tm.id}>{tm.name}</option>)}</select>}
      </Field>
      {detail === 'none' && <p className="muted">{t('dashboard.noRun')}</p>}
      {facts && (
        <>
          <div className="grid-2">
            <section className="panel">
              <h2>{t('rebalancing.overloaded')}</h2>
              <ul>{facts.rebalancing_candidates.overloaded.map((m) => <li key={m.member_id}>{m.name} — <span className="badge high">{t('rebalancing.over', { hours: hours(m.overload_hours) })}</span></li>)}</ul>
            </section>
            <section className="panel">
              <h2>{t('rebalancing.underloaded')}</h2>
              <ul>{facts.rebalancing_candidates.underloaded.map((m) => <li key={m.member_id}>{m.name} — <span className="badge low">{t('rebalancing.spare', { hours: hours(m.spare_hours) })}</span></li>)}</ul>
            </section>
          </div>
          <section className="panel">
            <h2>{t('rebalancing.moves')}</h2>
            {!narrative || narrative.rebalancing.length === 0 ? <p className="muted">{t('rebalancing.none')}</p> : (
              <table>
                <thead><tr><th>{t('rebalancing.from')}</th><th>{t('rebalancing.to')}</th><th>{t('member.week')}</th><th className="num">{t('rebalancing.hours')}</th><th>{t('rebalancing.reason')}</th><th>{t('rebalancing.confidence')}</th></tr></thead>
                <tbody>
                  {narrative.rebalancing.map((mv, i) => (
                    <tr key={i} aria-label={`${names.get(mv.from_member_id)} to ${names.get(mv.to_member_id)}`}>
                      <td>{names.get(mv.from_member_id) ?? mv.from_member_id}</td><td>{names.get(mv.to_member_id) ?? mv.to_member_id}</td>
                      <td>{weekLabel(mv.week)}</td><td className="num">{hours(mv.hours)}</td><td>{mv.reason}</td><td><RiskBadge level={mv.confidence} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {narrative && narrative.suggested_adjustments.length > 0 && (
              <>
                <h3>{t('rebalancing.adjustments')}</h3>
                <ul>{narrative.suggested_adjustments.map((a, i) => <li key={i}>{names.get(a.member_id) ?? a.member_id}, {weekLabel(a.week)}: {a.delta_hours >= 0 ? '+' : ''}{a.delta_hours.toFixed(1)} h — {a.reason}</li>)}</ul>
              </>
            )}
          </section>
        </>
      )}
    </div>
  )
}
