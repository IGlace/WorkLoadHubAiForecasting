import type React from 'react'
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import type { PatternStats, RunDetail } from '../../../shared/types'
import { getRun } from '../api'
import { HistoryChart } from '../components/HistoryChart'
import { RiskBadge } from '../components/RiskBadge'
import { StatusMessage } from '../components/StatusMessage'
import { hours, pct, weekLabel } from '../format'
import { t } from '../i18n'

function patternLines(p: PatternStats): string[] {
  const lines: string[] = []
  if (p.trend_hours_per_week !== null) lines.push(`Trend: ${p.trend_hours_per_week >= 0 ? '+' : ''}${p.trend_hours_per_week.toFixed(2)} h per week`)
  if (p.top_weekday) lines.push(`Busiest arrival day: ${p.top_weekday}${p.weekday_shares[p.top_weekday] !== undefined ? ` (${pct(p.weekday_shares[p.top_weekday]!)})` : ''}`)
  if (p.estimate_ratio_median !== null) lines.push(`Median estimate ratio (actual / estimate): ${p.estimate_ratio_median.toFixed(2)}`)
  if (p.cycle_days_median !== null) lines.push(`Median cycle time: ${p.cycle_days_median.toFixed(1)} days`)
  if (p.share_late !== null) lines.push(`Share of late tasks: ${pct(p.share_late)}`)
  if (p.share_with_project !== null) lines.push(`Share of work on projects: ${pct(p.share_with_project)}`)
  lines.push(`Open tasks: ${p.open_tasks} (${hours(p.open_est_hours)}), overdue: ${p.overdue_open}`)
  return lines
}

export function MemberDetail(): React.JSX.Element {
  const { runId, memberId } = useParams()
  const [detail, setDetail] = useState<RunDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { getRun(Number(runId)).then(setDetail).catch((e: Error) => setError(e.message)) }, [runId])
  if (error) return <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>
  if (!detail) return <p>{t('common.loading')}</p>
  const member = detail.facts?.members.find((m) => m.id === Number(memberId))
  if (!member) return <StatusMessage kind="error">{t('common.error', { message: `member ${memberId} is not in run ${runId}` })}</StatusMessage>
  const story = detail.narrative?.members.find((m) => m.member_id === member.id)
  return (
    <div>
      <p><Link to={`/runs/${runId}`}>← {t('team.title')}</Link></p>
      <h1>{member.name} {story && <RiskBadge level={story.risk_level} />}</h1>
      <section className="panel">
        <h2>{t('member.history')}</h2>
        {member.history_13w.length ? <HistoryChart points={member.history_13w} /> : <p className="muted">–</p>}
      </section>
      <section className="panel">
        <h2>{t('member.forecast')}</h2>
        <table>
          <thead><tr><th>{t('member.week')}</th><th className="num">{t('member.demand')}</th><th>{t('member.range')}</th><th className="num">{t('member.capacity')}</th><th className="num">{t('member.overload')}</th><th className="num">{t('member.openHours')}</th><th className="num">{t('member.newHours')}</th></tr></thead>
          <tbody>
            {member.forecast.map((f) => (
              <tr key={f.week} aria-label={weekLabel(f.week)}>
                <td>{weekLabel(f.week)}</td><td className="num">{hours(f.demand)}</td><td>{f.low.toFixed(1)} – {f.high.toFixed(1)} h</td>
                <td className="num">{hours(f.capacity)}</td><td className="num">{f.overload > 0 ? <span className="badge high">+{f.overload.toFixed(1)} h</span> : '–'}</td>
                <td className="num">{hours(f.open_hours)}</td><td className="num">{hours(f.new_hours)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
      <div className="grid-2">
        <section className="panel">
          <h2>{t('member.patterns')}</h2>
          <ul>{patternLines(member.patterns).map((l) => <li key={l}>{l}</li>)}</ul>
          {story && story.patterns.length > 0 && <ul>{story.patterns.map((p, i) => <li key={i}><strong>{p.kind.replace(/_/g, ' ')}</strong>: {p.statement} <span className="muted">({p.evidence})</span></li>)}</ul>}
        </section>
        <section className="panel">
          <h2>{t('member.open')}</h2>
          {member.open_tasks.length === 0 ? <p className="muted">–</p> : (
            <table>
              <tbody>
                {member.open_tasks.map((task) => (
                  <tr key={task.id}><td>{task.title}</td><td>{task.type} · {task.priority}</td><td className="num">{hours(task.estimated_hours)}</td><td>{task.due_date ?? ''} {task.overdue && <span className="badge high">overdue</span>}</td></tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </div>
      {story && (
        <section className="panel">
          <h2>{t('member.narrative')}</h2>
          <p>{story.summary}</p>
          {story.warnings.length > 0 && <ul>{story.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>}
        </section>
      )}
    </div>
  )
}
