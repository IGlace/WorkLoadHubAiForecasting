import type React from 'react'
import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import type { RunDetail } from '../../../shared/types'
import { createNarrative, getRun } from '../api'
import { RiskBadge } from '../components/RiskBadge'
import { StatusMessage } from '../components/StatusMessage'
import { WeekTable, type WeekTableRow } from '../components/WeekTable'
import { useApp } from '../context'
import { modelName, useT } from '../i18n'
import { progressLabel, useNarrativeProgress } from '../narrative-progress'

interface Fetched { id: number; detail: RunDetail | null; error: string | null }

export function TeamResult(): React.JSX.Element {
  const { runId } = useParams()
  const { settings } = useApp()
  const t = useT()
  // `fetched.id` tags which run the payload belongs to; a superseded id (runId changed
  // since this was written) is treated as empty below, so stale data from a previous
  // run never renders while the next run's fetch is in flight.
  const [fetched, setFetched] = useState<Fetched>({ id: NaN, detail: null, error: null })
  // The ids `narrate()` is running for, not the id of the run currently on screen: the route is
  // reused across `/runs/:runId` navigations, so a user can move to another run's page while a
  // narration is still in flight. Tracking the narrating runs' own ids (rather than a plain busy
  // flag paired with the current `id`) keeps each narration attributed to the run it belongs to,
  // not whichever one happens to be showing. A single id is not enough: once the button for a run
  // other than the one narrating is (correctly) enabled, a second narration can start for it while
  // the first is still in flight, and a single `narratingId` would let the first narration's
  // `finally` overwrite or wipe out the second's tracking when it finishes. The owner accepted that
  // two narrations may run concurrently, so this is a set of every run currently narrating, and
  // every update goes through the functional `setX((prev) => ...)` form because two `narrate()`
  // calls can overlap in time and a stale closure would drop one of them.
  const [narratingIds, setNarratingIds] = useState<Set<number>>(new Set())
  const id = Number(runId)
  const busy = narratingIds.has(id)
  // Progress is polled for the run on screen only when a narration is in flight for that same run;
  // another run's narration (if any) keeps running in the background but is not shown here. Known and
  // accepted: navigating away from a narrating run and back restarts its elapsed counter at 0, because
  // `useNarrativeProgress` resets whenever its `runId` argument changes (including a change to or from
  // `null`); the step label recovers on the next poll, one second later. This is not worth start-time
  // bookkeeping to avoid.
  const { step, elapsed } = useNarrativeProgress(busy ? id : null)

  const load = useCallback((): Promise<RunDetail> => getRun(id), [id])

  useEffect(() => {
    let cancelled = false
    load()
      .then((d) => { if (!cancelled) setFetched({ id, detail: d, error: null }) })
      .catch((e: Error) => { if (!cancelled) setFetched({ id, detail: null, error: e.message }) })
    return () => { cancelled = true }
  }, [id, load])

  async function narrate(): Promise<void> {
    setNarratingIds((prev) => new Set(prev).add(id))
    try {
      const outcome = await createNarrative(id, settings.model)
      const d = await load()
      // Only replace the displayed payload if this narration's run is still the one on screen:
      // navigating away and starting another narration there must not have this one's completion
      // clobber the other run's data with a stale `Loading…` once it lands.
      setFetched((prev) => (prev.id === id
        ? { id, detail: d, error: outcome.status === 'failed' ? (outcome.error ?? outcome.ai_status) : null }
        : prev))
    } catch (err) {
      setFetched((prev) => (prev.id === id
        ? { ...prev, error: err instanceof Error ? err.message : String(err) }
        : prev))
    } finally {
      // Remove only this narration's own id: another call to `narrate()` for a different run may
      // still be in flight, and must keep being tracked when this one finishes.
      setNarratingIds((prev) => {
        if (!prev.has(id)) return prev
        const next = new Set(prev)
        next.delete(id)
        return next
      })
    }
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
      <p className="muted">{t('runs.asof')} {detail.run.as_of} · {t('team.champion')}: <strong>{modelName(t, detail.run.champion_model)}</strong> · {t('team.mase')}: <strong>{detail.run.backtest_mase?.toFixed(2)}</strong></p>
      <section className="panel"><WeekTable weeks={weeks} rows={rows} /></section>
      <section className="panel">
        <h2>{t('team.summary')}</h2>
        <p className="muted">{t('team.narrativeStatus', { status: detail.run.ai_status })}</p>
        {detail.run.ai_status === 'unverified' && <StatusMessage kind="info">{t('team.unverified')}</StatusMessage>}
        {!narrative && <button className="primary" disabled={busy} onClick={() => { void narrate() }}>{t('team.narrate')}</button>}
        {busy && (
          <StatusMessage kind="info">
            {progressLabel(step)} <span className="muted">{t('run.progress.elapsed', { seconds: String(elapsed) })}</span>
          </StatusMessage>
        )}
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
