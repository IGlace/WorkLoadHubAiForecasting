import type React from 'react'
import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import type { RunDetail } from '../../../shared/types'
import { createNarrative, getRun } from '../api'
import { RiskBadge } from '../components/RiskBadge'
import { StatusMessage } from '../components/StatusMessage'
import { WeekTable, type WeekTableRow } from '../components/WeekTable'
import { useApp } from '../context'
import { t } from '../i18n'

export function TeamResult(): React.JSX.Element {
  const { runId } = useParams()
  const { settings } = useApp()
  const [detail, setDetail] = useState<RunDetail | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const id = Number(runId)

  const load = useCallback((): void => { getRun(id).then(setDetail).catch((e: Error) => setError(e.message)) }, [id])
  useEffect(() => { getRun(id).then(setDetail).catch((e: Error) => setError(e.message)) }, [id])

  async function narrate(): Promise<void> {
    setBusy(true); setError(null)
    try {
      const outcome = await createNarrative(id, settings.model)
      if (outcome.status === 'failed') setError(outcome.error ?? outcome.ai_status)
      load()
    } catch (err) { setError(err instanceof Error ? err.message : String(err)) } finally { setBusy(false) }
  }

  if (error && !detail) return <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>
  if (!detail) return <p>{t('common.loading')}</p>
  const facts = detail.facts
  const weeks = facts?.run.weeks ?? [...new Set(detail.forecasts.map((f) => f.week_start))].sort()
  const narrative = detail.narrative
  const riskOf = new Map(narrative?.members.map((m) => [m.member_id, m.risk_level]) ?? [])
  const rows: WeekTableRow[] = (facts?.members ?? []).map((m) => ({
    member_id: m.id, name: m.name, risk: riskOf.get(m.id), href: `/runs/${id}/members/${m.id}`,
    cells: Object.fromEntries(weeks.map((w) => [w, detail.forecasts.find((f) => f.member_id === m.id && f.week_start === w)])),
  }))
  return (
    <div>
      <h1>{t('team.title')}: <span>{facts?.team.name ?? `team ${detail.run.team_id}`}</span></h1>
      {error && <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>}
      <p className="muted">{t('runs.asof')} {detail.run.as_of} · {t('team.champion')}: <strong>{detail.run.champion_model}</strong> · {t('team.mase')}: <strong>{detail.run.backtest_mase?.toFixed(2)}</strong></p>
      <section className="panel"><WeekTable weeks={weeks} rows={rows} /></section>
      <section className="panel">
        <h2>{t('team.summary')}</h2>
        <p className="muted">{t('team.narrativeStatus', { status: detail.run.ai_status })}</p>
        {detail.run.ai_status === 'unverified' && <StatusMessage kind="info">{t('team.unverified')}</StatusMessage>}
        {!narrative && <button className="primary" disabled={busy} onClick={() => { void narrate() }}>{t('team.narrate')}</button>}
        {narrative && (
          <>
            <p>{narrative.run_summary}</p>
            {narrative.members.some((m) => m.warnings.length) && (
              <>
                <h3>{t('team.warnings')}</h3>
                <ul>{narrative.members.flatMap((m) => m.warnings.map((w, i) => <li key={`${m.member_id}-${i}`}><strong>{m.name}</strong>: <span>{w}</span></li>))}</ul>
              </>
            )}
            {narrative.team_risks.length > 0 && (
              <>
                <h3>{t('team.risks')}</h3>
                <ul>{narrative.team_risks.map((r) => <li key={r.title}><RiskBadge level={r.severity} /> <strong>{r.title}</strong> — {r.detail}</li>)}</ul>
              </>
            )}
            {narrative.model_notes && <p className="muted">{t('team.notes')}: {narrative.model_notes}</p>}
          </>
        )}
      </section>
    </div>
  )
}
