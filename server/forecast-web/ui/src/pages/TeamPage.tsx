import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import { Behind } from '../components/Behind'
import { ErrorNotice, Notice } from '../components/Notice'
import { ProgressBar } from '../components/ProgressBar'
import { loadStep } from '../lib/charts'
import { addDays, dateTime, dayLabel, hours, isWeekend, num, shortId } from '../lib/format'
import { useAsync } from '../lib/useAsync'
import { useActing } from '../state/acting'
import type { RunProgress } from '../types'

export function TeamPage() {
  const { id = '' } = useParams()
  const { userId, me, system, refresh } = useActing()
  const perm = me?.teams.find((t) => t.teamId === id)
  const today = system?.today ?? ''
  const from = today
  const to = today ? addDays(today, 20) : ''
  const team = useAsync(() => api.team(id), [id, userId])
  const runs = useAsync(() => api.runs(id, 20), [id, userId])
  const forecast = useAsync(() => (today ? api.forecast(id, from, to) : Promise.resolve([])), [id, userId, today])
  const [running, setRunning] = useState<string | null>(null)
  const [progress, setProgress] = useState<RunProgress | null>(null)
  const [startError, setStartError] = useState<unknown>(null)
  const timer = useRef<number | null>(null)

  // The page polls progress once a second until the run ends, then reloads what the run changed.
  useEffect(() => {
    if (!running) return
    let stopped = false
    const tick = async () => {
      try {
        const p = await api.progress(running)
        if (stopped) return
        setProgress(p)
        if (p.phase === 'DONE' || p.phase === 'FAILED') {
          setRunning(null)
          void runs.reload()
          void forecast.reload()
          return
        }
      } catch (e) {
        if (!stopped) { setStartError(e); setRunning(null); return }
      }
      timer.current = window.setTimeout(() => void tick(), 1000)
    }
    void tick()
    return () => { stopped = true; if (timer.current) window.clearTimeout(timer.current) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [running])

  async function start() {
    setStartError(null)
    setProgress(null)
    try {
      const { id: runId } = await api.startRun(id)
      setRunning(runId)
    } catch (e) {
      setStartError(e)
      if (e instanceof ApiError && e.status === 401) void refresh()
    }
  }

  const days = useMemo(() => Array.from(new Set((forecast.data ?? []).map((d) => d.day))).sort(), [forecast.data])
  const cells = useMemo(() => new Map((forecast.data ?? []).map((d) => [d.userId + '|' + d.day, d])), [forecast.data])
  const members = team.data?.members ?? []
  const totals = useMemo(() => {
    const m = new Map<string, { demand: number; capacity: number; overload: number }>()
    for (const d of forecast.data ?? []) {
      const t = m.get(d.userId) ?? { demand: 0, capacity: 0, overload: 0 }
      t.demand += d.demandHrs; t.capacity += d.capacityHrs; t.overload += d.overloadHrs
      m.set(d.userId, t)
    }
    return m
  }, [forecast.data])
  const latestDone = runs.data?.find((r) => r.status === 'DONE')
  const startDisabled = !userId || !perm?.canRun || running !== null
  const startReason = !userId ? 'pick a user to act as' : !perm ? 'permissions not loaded' : !perm.canRun ? perm.runReason : running ? 'a run is in progress' : perm.runReason

  return (
    <div>
      <div className="row spread">
        <div>
          <h1>{team.data?.name ?? 'Team'}</h1>
          <p className="lead">
            {team.data?.managerName ? <>led by <b>{team.data.managerName}</b></> : 'no manager'}
            {team.data?.parentTeamName ? <> · under {team.data.parentTeamName}</> : null} · {members.length} members
          </p>
        </div>
        <div className="row">
          <Link className="btn" to={'/teams/' + id + '/accuracy'}>Accuracy of past forecasts</Link>
          <button className="primary" disabled={startDisabled} title={startReason} onClick={() => void start()}>Start a forecast</button>
        </div>
      </div>
      <p className="small muted">Start: <code>POST /api/teams/{'{teamId}'}/forecast-runs</code> → {startReason}.</p>
      <ErrorNotice error={team.error ?? startError} />
      {perm && !perm.canView && <Notice kind="warn">{perm.viewReason}</Notice>}
      {(running || progress) && (
        <div className="card">
          <h3>Run {shortId(running ?? progress?.runId)}</h3>
          <ProgressBar progress={progress} />
          {!running && progress && (progress.phase === 'DONE'
            ? <p><Link to={'/runs/' + progress.runId}>Open the run</Link></p>
            : <p className="muted">{progress.message}</p>)}
          <p className="small muted">The page polls <code>GET /api/forecast-runs/{'{id}'}/progress</code> once a second; the label is what a person reads, the phase is what code branches on.</p>
        </div>
      )}

      <h2>Current forecast, {from} to {to}</h2>
      <p className="lead">The latest value per member and day, whichever run produced it (<code>GET /api/teams/{'{teamId}'}/forecast</code>). Demand is the predicted logged hours; overload is demand beyond capacity, measured day by day and never capped.</p>
      <ErrorNotice error={forecast.error} />
      {forecast.data && forecast.data.length === 0 && <Notice>No forecast covers these days yet. Start a run{latestDone ? '' : ''}.</Notice>}
      {days.length > 0 && (
        <div className="card scroll">
          <table className="grid">
            <thead>
              <tr><th>Member</th>{days.map((d) => <th key={d} className={isWeekend(d) ? 'muted' : ''}>{dayLabel(d)}</th>)}<th className="num">Demand</th><th className="num">Capacity</th><th className="num">Overload</th></tr>
            </thead>
            <tbody>
              {members.map((m) => {
                const t = totals.get(m.id)
                return (
                  <tr key={m.id}>
                    <td className="name">{m.fullName}<small className="muted">{m.jobTitle ?? m.role}</small></td>
                    {days.map((d) => {
                      const c = cells.get(m.id + '|' + d)
                      if (!c) return <td key={d} className="we">·</td>
                      const cls = 'l' + loadStep(c.demandHrs, c.capacityHrs) + (c.overloadHrs > 0 ? ' over' : '')
                      return (
                        <td key={d} className={cls} title={`${dayLabel(d)}: demand ${hours(c.demandHrs)}, capacity ${hours(c.capacityHrs)}, overload ${hours(c.overloadHrs)} (run ${shortId(c.runId)})`}>
                          {num(c.demandHrs, 1)}{c.overloadHrs > 0 ? ' ▲' : ''}<small>of {num(c.capacityHrs, 1)}</small>
                        </td>
                      )
                    })}
                    <td className="num">{hours(t?.demand)}</td><td className="num">{hours(t?.capacity)}</td>
                    <td className="num">{t && t.overload > 0 ? <b>{hours(t.overload)}</b> : hours(t?.overload)}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          <div className="legend">
            <span>load = demand / capacity:</span>
            {[1, 2, 3, 4, 5].map((s) => <span key={s}><span className="sw" style={{ background: `var(--seq-${s})` }} />{['< 25 %', '< 50 %', '< 75 %', '< 100 %', '≥ 100 %'][s - 1]}</span>)}
            <span><span className="sw" style={{ outline: '2px solid var(--critical)', outlineOffset: -2 }} />▲ overloaded day</span>
            <span><span className="sw" style={{ background: 'var(--surface-2)' }} />no capacity (holiday, leave)</span>
          </div>
          <p className="small muted">Summing the days is the page's own arithmetic; the overload column does not follow from the other two, because eight idle hours on Monday never pay for four extra on Tuesday.</p>
        </div>
      )}

      <h2>Runs</h2>
      <p className="lead">The run history, newest first (<code>GET /api/teams/{'{teamId}'}/forecast-runs</code>). A DONE run opens to its windows, facts and narrative.</p>
      <ErrorNotice error={runs.error} />
      {runs.data && (
        <div className="card scroll">
          {runs.data.length === 0 && <p className="muted">No run yet.</p>}
          {runs.data.length > 0 && (
            <table>
              <thead><tr><th>Run</th><th>As of</th><th>Status</th><th className="num">MAE (h)</th><th>Requested by</th><th>Started</th><th>Finished</th></tr></thead>
              <tbody>
                {runs.data.map((r) => (
                  <tr key={r.id}>
                    <td>{r.status === 'DONE' ? <Link to={'/runs/' + r.id}>{shortId(r.id)}</Link> : <span className="mono">{shortId(r.id)}</span>}</td>
                    <td>{r.asOf}</td>
                    <td><span className={'badge ' + (r.status === 'DONE' ? 'ok' : r.status === 'FAILED' ? 'bad' : 'warn')}>{r.status}</span>{r.error ? <span className="muted small"> {r.error}</span> : null}</td>
                    <td className="num">{r.mae === null ? <span className="muted">not scored</span> : num(r.mae)}</td>
                    <td className="mono small">{shortId(r.requestedBy)}</td>
                    <td className="small">{dateTime(r.createdAt)}</td>
                    <td className="small">{dateTime(r.finishedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
      <Behind />
    </div>
  )
}
