import type React from 'react'
import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import type { CopilotStatus, RunCreated } from '../../../shared/types'
import { createNarrative, createRun, getCopilotStatus } from '../api'
import { Field } from '../components/Field'
import { StatusMessage } from '../components/StatusMessage'
import { useApp } from '../context'
import { hours, today } from '../format'
import { t } from '../i18n'

type Phase = 'idle' | 'forecasting' | 'narrating' | 'done'

export function Run(): React.JSX.Element {
  const { meta, me, visibleTeams, settings } = useApp()
  const [params] = useSearchParams()
  const [team, setTeam] = useState(params.get('team') ?? '')
  const [asOf, setAsOf] = useState(today())
  const [withAi, setWithAi] = useState(true)
  const [copilot, setCopilot] = useState<CopilotStatus | null>(null)
  const [phase, setPhase] = useState<Phase>('idle')
  const [result, setResult] = useState<RunCreated | null>(null)
  const [aiError, setAiError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => { getCopilotStatus().then(setCopilot).catch(() => setCopilot(null)) }, [])

  const effectiveTeam = team || (visibleTeams.length === 1 ? String(visibleTeams[0]!.id) : '')
  const selected = visibleTeams.find((tm) => tm.id === Number(effectiveTeam))
  const leader = selected && meta?.members.find((m) => m.id === selected.team_leader_id)
  const onBehalf = me?.role === 'skill_team_leader' && leader && leader.id !== me.id
  const aiPossible = copilot?.ready === true

  async function start(): Promise<void> {
    if (!selected || !me) return
    setError(null); setAiError(null); setResult(null); setPhase('forecasting')
    let run: RunCreated
    try {
      run = await createRun(selected.id, asOf, me.id)
    } catch (err) { setError(err instanceof Error ? err.message : String(err)); setPhase('idle'); return }
    setResult(run)
    if (withAi && aiPossible) {
      setPhase('narrating')
      try {
        const outcome = await createNarrative(run.run_id, settings.model)
        if (outcome.status === 'failed') setAiError(outcome.error ?? outcome.ai_status)
      } catch (err) { setAiError(err instanceof Error ? err.message : String(err)) }
    }
    setPhase('done')
  }

  const busy = phase === 'forecasting' || phase === 'narrating'
  return (
    <div>
      <h1>{t('run.title')}</h1>
      {error && <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>}
      <section className="panel">
        <Field label={t('run.team')}>
          {(id) => (
            <select id={id} value={effectiveTeam} onChange={(e) => setTeam(e.target.value)} disabled={busy}>
              {visibleTeams.length !== 1 && <option value="">–</option>}
              {visibleTeams.map((tm) => <option key={tm.id} value={tm.id}>{tm.name}</option>)}
            </select>
          )}
        </Field>
        {onBehalf && <StatusMessage kind="info">{t('run.onBehalf', { leader: leader.name })}</StatusMessage>}
        <Field label={t('run.asof')}>{(id) => <input id={id} type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} disabled={busy} />}</Field>
        <div className="field">
          <label><input type="checkbox" checked={withAi && aiPossible} disabled={!aiPossible || busy} onChange={(e) => setWithAi(e.target.checked)} /> {t('run.withai')}</label>
          {copilot && !copilot.ready && <span className="muted"> {copilot.message}</span>}
        </div>
        <button className="primary" disabled={!selected || busy} onClick={() => { void start() }}>{t('run.start')}</button>
      </section>
      {phase === 'forecasting' && <StatusMessage kind="info">{t('run.progress.forecasting', { team: selected?.name ?? '' })}</StatusMessage>}
      {phase === 'narrating' && <StatusMessage kind="info">{t('run.progress.narrating')}</StatusMessage>}
      {aiError && <StatusMessage kind="error">{t('run.aiFailed', { reason: aiError })}</StatusMessage>}
      {phase === 'done' && result && (
        <section className="panel">
          <StatusMessage kind="success">{t('run.done')}</StatusMessage>
          <p>{t('team.champion')}: {result.champion} · {t('team.mase')}: {result.backtest_mase.toFixed(2)}</p>
          <ul>
            {result.weeks.map((w) => {
              const rows = result.forecasts.filter((f) => f.week_start === w)
              return <li key={w}>{t('common.week', { date: w })}: {hours(rows.reduce((s, f) => s + f.demand_hours, 0))} / {hours(rows.reduce((s, f) => s + f.capacity_hours, 0))}</li>
            })}
          </ul>
          <Link to={`/runs/${result.run_id}`}>{t('run.open')}</Link>
        </section>
      )}
    </div>
  )
}
