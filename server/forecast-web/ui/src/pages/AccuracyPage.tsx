import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api'
import { Behind } from '../components/Behind'
import { DayLines } from '../components/DayLines'
import { ErrorNotice, Notice } from '../components/Notice'
import { addDays, dayLabel, finite, hours, num, pct, shortId, signed } from '../lib/format'
import { useAsync } from '../lib/useAsync'
import { useActing } from '../state/acting'
import type { AccuracyScore } from '../types'

export function AccuracyPage() {
  const { id = '' } = useParams()
  const { userId, system } = useActing()
  const today = system?.today ?? ''
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const f = from || (today ? addDays(today, -28) : '')
  const t = to || (today ? addDays(today, -1) : '')
  const team = useAsync(() => api.team(id), [id, userId])
  // Null until GET /api/system has supplied today: the page shows its loading state rather than an error.
  const acc = useAsync(() => (f && t ? api.accuracy(id, f, t) : Promise.resolve(null)), [id, userId, f, t])
  const names = useMemo(() => new Map((team.data?.members ?? []).map((m) => [m.id, m.fullName])), [team.data])
  const points = useMemo(() => {
    const byDay = new Map<string, { forecast: number; logged: number }>()
    for (const r of acc.data?.current ?? []) {
      const p = byDay.get(r.day) ?? { forecast: 0, logged: 0 }
      // finite(): a value the module could not compute arrives as the string "NaN", and adding that to a
      // number would concatenate it into a string that silently drops the day from the chart.
      p.forecast += finite(r.forecastHrs) ?? 0
      p.logged += finite(r.loggedHrs) ?? 0
      byDay.set(r.day, p)
    }
    return Array.from(byDay.entries()).sort(([a], [b]) => a.localeCompare(b)).map(([day, p]) => ({ day, ...p }))
  }, [acc.data])
  const scores = acc.data?.scores ?? []
  const byScope = (s: string) => scores.filter((x) => x.scope === s)

  return (
    <div>
      <h1>Accuracy of past forecasts</h1>
      <p className="lead">Team <Link to={'/teams/' + id}>{team.data?.name ?? shortId(id)}</Link>. For every weekday between the two dates, what was forecast for it <em>before it arrived</em> is compared with the hours actually logged on it (<code>GET /api/teams/{'{teamId}'}/accuracy</code>). Nothing is stored; every call recomputes.</p>
      <div className="row card tight">
        <label className="field">from<input type="date" value={f} onChange={(e) => setFrom(e.target.value)} /></label>
        <label className="field">to<input type="date" value={t} onChange={(e) => setTo(e.target.value)} /></label>
        <button className="small" onClick={() => { setFrom(''); setTo('') }}>default: last 28 days</button>
        {acc.data && <span className="muted small">evaluated at {acc.data.evaluatedAt.replace('T', ' ').slice(0, 16)} · {acc.data.nonWorkingDays} public holiday{acc.data.nonWorkingDays === 1 ? '' : 's'} not scored</span>}
      </div>
      <ErrorNotice error={acc.error} />
      {acc.data && acc.data.current.length === 0 && (
        <Notice>No scored day: nothing in this range was forecast before it arrived. On the demo database, an <b>ADMIN</b> can move the <Link to="/clock">demo clock</Link> back (say four weeks), run a forecast, move it forward again, and this page fills in.</Notice>
      )}
      {acc.data && acc.data.current.length > 0 && (
        <>
          <div className="card">
            <h2 style={{ marginTop: 0 }}>Forecast against logged, per day</h2>
            <DayLines points={points} />
          </div>
          <div className="card">
            <h2 style={{ marginTop: 0 }}>Scores</h2>
            <p className="lead">MAE and bias in hours per member-day; MASE against the lag-7 naive baseline on the rows that have one (below 1 beats "same as last week"); overload precision and recall on the days flagged over capacity.</p>
            <ScoreTable title="Team" rows={byScope('team')} label={() => team.data?.name ?? 'team'} />
            <ScoreTable title="By member" rows={byScope('member')} label={(k) => names.get(k) ?? k} />
            <ScoreTable title="By lead (days ahead the forecast was made)" rows={byScope('lead')} label={(k) => k + ' day' + (k === '1' ? '' : 's') + ' ahead'} />
          </div>
          <div className="card scroll">
            <h2 style={{ marginTop: 0 }}>Rows ({acc.data.current.length})</h2>
            <table>
              <thead><tr><th>Member</th><th>Day</th><th>Run</th><th className="num">Lead</th><th className="num">Forecast</th><th className="num">Logged</th><th className="num">Error</th><th className="num">Capacity</th><th>Overload</th></tr></thead>
              <tbody>
                {acc.data.current.slice(0, 400).map((r, i) => (
                  <tr key={i}>
                    <td>{names.get(r.userId) ?? shortId(r.userId)}</td><td>{dayLabel(r.day)}</td><td className="mono small">{shortId(r.runId)}</td>
                    <td className="num">{r.lead}</td><td className="num">{hours(r.forecastHrs)}</td><td className="num">{hours(r.loggedHrs)}</td>
                    <td className="num">{signed(error(r.forecastHrs, r.loggedHrs), 1)}</td><td className="num">{hours(r.capacityHrs)}</td>
                    <td className="small">{r.forecastOverload ? 'forecast' : ''}{r.forecastOverload && r.actualOverload ? ' + ' : ''}{r.actualOverload ? 'actual' : ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {acc.data.current.length > 400 && <p className="muted small">first 400 rows shown</p>}
          </div>
        </>
      )}
      <Behind />
    </div>
  )
}

/** The day's error, or null when either side could not be computed. */
function error(forecast: number | string, logged: number | string): number | null {
  const f = finite(forecast)
  const l = finite(logged)
  return f === null || l === null ? null : f - l
}

function ScoreTable({ title, rows, label }: { title: string; rows: AccuracyScore[]; label: (key: string) => string }) {
  if (rows.length === 0) return <p className="muted small">{title}: no score.</p>
  return (
    <div className="scroll mt">
      <h3>{title}</h3>
      <table>
        <thead><tr><th>Scope</th><th className="num">n</th><th className="num">MAE (h)</th><th className="num">Bias (h)</th><th className="num">MASE</th><th className="num">on rows</th><th className="num">Overload precision</th><th className="num">Overload recall</th></tr></thead>
        <tbody>
          {rows.map((s) => (
            <tr key={s.key}>
              <td>{label(s.key)}</td><td className="num">{s.n}</td><td className="num">{num(s.mae)}</td><td className="num">{signed(s.bias)}</td>
              <td className="num">{num(s.mase, 3)}</td><td className="num">{s.maseN}</td>
              <td className="num">{pct(s.overloadPrecision)}</td><td className="num">{pct(s.overloadRecall)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
