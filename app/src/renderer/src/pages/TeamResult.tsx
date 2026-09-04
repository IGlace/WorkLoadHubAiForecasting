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

interface Fetched { id: number; detail: RunDetail | null; error: string | null }

export function TeamResult(): React.JSX.Element {
  const { runId } = useParams()
  const { settings } = useApp()
  // `fetched.id` tags which run the payload belongs to; a superseded id (runId changed
  // since this was written) is treated as empty below, so stale data from a previous
  // run never renders while the next run's fetch is in flight.
  const [fetched, setFetched] = useState<Fetched>({ id: NaN, detail: null, error: null })
  const [busy, setBusy] = useState(false)
  const id = Number(runId)

  const load = useCallback((): Promise<RunDetail> => getRun(id), [id])

  useEffect(() => {
    let cancelled = false
    load()
      .then((d) => { if (!cancelled) setFetched({ id, detail: d, error: null }) })
      .catch((e: Error) => { if (!cancelled) setFetched({ id, detail: null, error: e.message }) })
    return () => { cancelled = true }
  }, [id, load])

  async function narrate(): Promise<void> {
    setBusy(true)
    try {
      const outcome = await createNarrative(id, settings.model)
      const d = await load()
      setFetched({ id, detail: d, error: outcome.status === 'failed' ? (outcome.error ?? outcome.ai_status) : null })
    } catch (err) { setFetched((prev) => ({ ...prev, error: err instanceof Error ? err.message : String(err) })) } finally { setBusy(false) }
  }

  const detail = fetched.id === id ? fetched.detail : null
  const error = fetched.id === id ? fetched.error : null

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
