import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api'
import { Behind } from '../components/Behind'
import { JsonView } from '../components/JsonView'
import { NarrationPanel } from '../components/NarrationPanel'
import { ErrorNotice } from '../components/Notice'
import { WindowBars } from '../components/WindowBars'
import { dateTime, dayLabel, hours, num, shortId } from '../lib/format'
import { useAsync } from '../lib/useAsync'
import { useActing } from '../state/acting'
import type { PressedMember } from '../types'

export function RunPage() {
  const { id = '' } = useParams()
  const { userId } = useActing()
  const run = useAsync(() => api.run(id), [id, userId])
  const [showDays, setShowDays] = useState(false)
  const names = useMemo(() => new Map((run.data?.members ?? []).map((m) => [m.id, m.fullName])), [run.data])
  const r = run.data
  const windows = r?.facts.run.windows ?? []
  const days = useMemo(() => Array.from(new Set((r?.memberDays ?? []).map((d) => d.day))).sort(), [r])
  const dayCells = useMemo(() => new Map((r?.memberDays ?? []).map((d) => [d.userId + '|' + d.day, d])), [r])
  const model = r?.facts.model
  const pressure = r?.facts.rebalancing_candidates

  return (
    <div>
      <div className="row spread">
        <div>
          <h1>Run {shortId(id)}</h1>
          {r && <p className="lead">Team <Link to={'/teams/' + r.run.teamId}>{r.facts.team.name ?? shortId(r.run.teamId)}</Link> · as of <b>{r.run.asOf}</b> · {r.members.length} members · {windows.length} windows · finished {dateTime(r.run.finishedAt)}</p>}
        </div>
      </div>
      <ErrorNotice error={run.error} />
      {r && (
        <>
          <div className="stats">
            <div className="stat"><div className="label">Status</div><div className="value small">{r.run.status}</div></div>
            <div className="stat"><div className="label">Backtest MAE</div><div className="value">{r.run.mae === null ? '–' : num(r.run.mae)} <span className="small muted">h</span></div></div>
            <div className="stat"><div className="label">Confidence</div><div className="value small">{model?.confidence}</div></div>
            <div className="stat"><div className="label">Mean actual hours / member-week</div><div className="value">{model?.mean_actual_hours === null || model?.mean_actual_hours === undefined ? '–' : num(model.mean_actual_hours, 1)}</div></div>
            <div className="stat"><div className="label">History</div><div className="value">{r.facts.data_quality.history_weeks} <span className="small muted">weeks</span></div></div>
            <div className="stat"><div className="label">Model</div><div className="value small">{model?.name} → {model?.target}</div></div>
          </div>
          <p className="small muted">The model is trained and scored once over the whole history, then applied per team, so two teams forecast on the same day report the same MAE; only the windows are the team's own. <code>GET /api/forecast-runs/{'{id}'}</code>.</p>

          {windows.map((w) => {
            const rows = r.memberWindows.filter((m) => m.windowIndex === w.index).sort((a, b) => b.demandHrs - a.demandHrs)
            const total = r.facts.team.totals.find((t) => t.window === w.index)
            return (
              <div className="card" key={w.index}>
                <div className="row spread">
                  <h2 style={{ margin: 0 }}>Window {w.index}: {dayLabel(w.start)} to {dayLabel(w.end)}</h2>
                  <span className="muted small">{w.working_days} working days · team demand {hours(total?.demand)} of {hours(total?.capacity)} capacity</span>
                </div>
                <WindowBars rows={rows} names={names} />
                <div className="scroll">
                  <table>
                    <thead><tr><th>Member</th><th className="num">Demand</th><th className="num">Low</th><th className="num">High</th><th className="num">Capacity</th><th className="num">Overload</th><th className="num">Days</th><th className="num">Absence</th><th className="num">Backlog excess</th><th className="num">Due excess</th></tr></thead>
                    <tbody>
                      {rows.map((m) => (
                        <tr key={m.userId}>
                          <td>{names.get(m.userId) ?? m.userId}</td>
                          <td className="num">{hours(m.demandHrs)}</td><td className="num">{hours(m.lowHrs)}</td><td className="num">{hours(m.highHrs)}</td>
                          <td className="num">{hours(m.capacityHrs)}</td>
                          <td className="num">{m.overloadHrs > 0 ? <b>{hours(m.overloadHrs)}</b> : hours(m.overloadHrs)}</td>
                          <td className="num">{m.workingDays}</td><td className="num">{hours(m.absenceHrs)}</td>
                          <td className="num">{hours(m.backlogExcessHrs)}</td><td className="num">{hours(m.dueExcessHrs)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <p className="small muted">Backlog excess: open work still left once the windows so far are forecast, cumulative and non-increasing across windows. Due excess: hours due inside this window beyond what it holds, against this window's own capacity. Both are pressures a forecast of logged hours cannot show on its own.</p>
              </div>
            )
          })}

          {pressure && (
            <div className="card">
              <h2 style={{ marginTop: 0 }}>Pressure, as the narrator reads it</h2>
              <p className="lead">The four lists of <code>facts.rebalancing_candidates</code>: what Copilot is told before it advises rebalancing. Every figure here is computed by the module; the model never writes a number.</p>
              <div className="grid2">
                <PressList title="Overloaded" items={pressure.overloaded} field="overload_hours" unit="h over capacity" />
                <PressList title="Underloaded" items={pressure.underloaded} field="spare_hours" unit="h spare" />
                <PressList title="Backlog pressed" items={pressure.backlog_pressed} field="backlog_excess_hrs" unit="h of backlog excess" />
                <PressList title="Deadline pressed" items={pressure.deadline_pressed} field="due_excess_hrs" unit="h due beyond the window" />
              </div>
            </div>
          )}

          <NarrationPanel runId={id} />

          <div className="card">
            <div className="row spread">
              <h2 style={{ margin: 0 }}>Days</h2>
              <button className="small" onClick={() => setShowDays(!showDays)}>{showDays ? 'hide' : 'show'} the {r.memberDays.length} day rows</button>
            </div>
            {showDays && (
              <div className="scroll mt">
                <table className="grid">
                  <thead><tr><th>Member</th>{days.map((d) => <th key={d}>{dayLabel(d)}</th>)}</tr></thead>
                  <tbody>
                    {r.members.map((m) => (
                      <tr key={m.id}>
                        <td className="name">{m.fullName}</td>
                        {days.map((d) => {
                          const c = dayCells.get(m.id + '|' + d)
                          if (!c) return <td key={d} className="we">·</td>
                          return <td key={d} className={(c.workingDay ? 'l' + (c.overloadHrs > 0 ? 5 : c.demandHrs > 0 ? 3 : 1) : 'l0') + (c.overloadHrs > 0 ? ' over' : '')}>
                            {num(c.demandHrs, 1)}<small>of {num(c.capacityHrs, 1)}{c.overloadHrs > 0 ? ` ▲ ${num(c.overloadHrs, 1)}` : ''}</small>
                          </td>
                        })}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div className="card">
            <h2 style={{ marginTop: 0 }}>Backtest</h2>
            <p className="lead">The per-run backtest behind the intervals and the MAE: one score per origin and horizon, MAE in hours; a null MAE is an origin that could not be scored.</p>
            {r.scores.length === 0 ? <p className="muted">No scored origin (thin history).</p> : (
              <div className="scroll">
                <table>
                  <thead><tr><th>Origin</th><th className="num">Horizon</th><th className="num">MAE (h)</th></tr></thead>
                  <tbody>{r.scores.map((s, i) => <tr key={i}><td>{s.origin}</td><td className="num">{s.horizon}</td><td className="num">{s.mae === null ? <span className="muted">not scored</span> : num(s.mae)}</td></tr>)}</tbody>
                </table>
              </div>
            )}
            {model?.limitations && model.limitations.length > 0 && (
              <>
                <h3>Limitations the narrator is told</h3>
                <ul>{(Array.isArray(model.limitations) ? model.limitations : model.limitations.split('; ')).map((l, i) => <li key={i} className="small">{l}</li>)}</ul>
              </>
            )}
          </div>

          <div className="card">
            <h2 style={{ marginTop: 0 }}>Facts sent to Copilot</h2>
            <p className="lead">The exact facts the narrator is allowed to see, stored for audit in <code>forecast_facts</code>. Every number a narrative cites is verified against these.</p>
            <JsonView value={r.facts} depth={1} />
          </div>
        </>
      )}
      <Behind />
    </div>
  )
}

function PressList({ title, items, field, unit }: { title: string; items: PressedMember[]; field: keyof PressedMember; unit: string }) {
  return (
    <div>
      <h3>{title} <span className="badge">{items.length}</span></h3>
      {items.length === 0 ? <p className="muted small">none</p> : (
        <ul>{items.map((m) => <li key={m.member_id}><b>{m.name}</b> · {num(m[field] as number, 1)} {unit}</li>)}</ul>
      )}
    </div>
  )
}
