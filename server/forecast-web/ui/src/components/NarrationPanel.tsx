import { useEffect, useRef, useState } from 'react'
import { api, ApiError } from '../api'
import { segments } from '../lib/highlight'
import { dateTime, num } from '../lib/format'
import { useActing } from '../state/acting'
import type { NarrativeView, RunProgress } from '../types'
import { ErrorNotice, Notice } from './Notice'
import { ProgressBar } from './ProgressBar'
import { JsonView } from './JsonView'

/**
 * The narration of one run in one language: start it (the host answers 202 and the page polls progress), then
 * the stored result, section by section, with every unverified number flagged in the text.
 */
export function NarrationPanel({ runId }: { runId: string }) {
  const { me, userId } = useActing()
  const [lang, setLang] = useState<'en' | 'fr'>('en')
  const [model, setModel] = useState('')
  const [narrative, setNarrative] = useState<NarrativeView | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [progress, setProgress] = useState<RunProgress | null>(null)
  const [busy, setBusy] = useState(false)
  const [showUsage, setShowUsage] = useState(false)
  const timer = useRef<number | null>(null)

  async function loadStored() {
    try {
      setNarrative(await api.narrative(runId, lang))
      setError(null)
    } catch (e) {
      setNarrative(null)
      if (!(e instanceof ApiError && e.code === 'NARRATIVE_NOT_FOUND')) setError(e)
      else setError(null)
    }
  }
  useEffect(() => { void loadStored() /* eslint-disable-line react-hooks/exhaustive-deps */ }, [runId, lang, userId])

  useEffect(() => {
    if (!busy) return
    let stopped = false
    const tick = async () => {
      try {
        const p = await api.progress(runId)
        if (stopped) return
        setProgress(p)
        if (p.phase === 'NARRATED' || p.phase === 'NARRATION_FAILED' || p.phase === 'DONE' || p.phase === 'FAILED') {
          setBusy(false)
          await loadStored()
          return
        }
      } catch (e) {
        if (!stopped) { setError(e); setBusy(false); return }
      }
      timer.current = window.setTimeout(() => void tick(), 1000)
    }
    void tick()
    return () => { stopped = true; if (timer.current) window.clearTimeout(timer.current) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [busy])

  async function start() {
    setError(null)
    setProgress(null)
    try {
      await api.narrate(runId, lang, model.trim() || undefined)
      setBusy(true)
    } catch (e) {
      setError(e)
    }
  }

  const reason = !userId ? 'pick a user to act as' : !me?.hasToken ? 'no GitHub token stored for the acting user (Copilot & token page)' : busy ? 'a narration is in flight' : 'uses the acting user\'s own Copilot seat'
  const unverified = narrative?.verification?.unverified ?? []
  const T = ({ text }: { text: string }) => <>{segments(text, unverified).map((s, i) => s.flagged ? <mark key={i} className="flag" title="not found in the facts">{s.text}</mark> : <span key={i}>{s.text}</span>)}</>
  const n = narrative?.narrative

  return (
    <div className="card">
      <div className="row spread">
        <h2 style={{ margin: 0 }}>Narrative</h2>
        <div className="row">
          <label className="field">language
            <select value={lang} onChange={(e) => setLang(e.target.value as 'en' | 'fr')}><option value="en">English</option><option value="fr">Français</option></select>
          </label>
          <label className="field">model (blank: account default)
            <input value={model} onChange={(e) => setModel(e.target.value)} placeholder="" style={{ width: 160 }} />
          </label>
          <button className="primary" disabled={!userId || !me?.hasToken || busy} title={reason} onClick={() => void start()}>
            {narrative ? 'Narrate again' : 'Narrate with Copilot'}
          </button>
        </div>
      </div>
      <p className="small muted">
        <code>POST /api/forecast-runs/{'{id}'}/narratives</code> → {reason}. Copilot reads the facts through tools and writes the narrative; deterministic code computed every figure. Every number it writes is verified against the facts, and an unverified one is reported, never promoted.
      </p>
      <ErrorNotice error={error} />
      {busy && <ProgressBar progress={progress} />}
      {!busy && !narrative && !error && <p className="muted">No {lang} narrative stored for this run.</p>}
      {narrative && !busy && (
        <div className="narr">
          <div className="row" style={{ margin: '8px 0' }}>
            <span className={'badge ' + (narrative.status === 'OK' ? 'ok' : narrative.status === 'UNVERIFIED' ? 'warn' : 'bad')}>{narrative.status}</span>
            <span className="small muted">model {narrative.model ?? '–'} · {narrative.attempts} attempt{narrative.attempts === 1 ? '' : 's'} · {narrative.toolCalls} tool calls · {dateTime(narrative.createdAt)}</span>
            {narrative.verification && <span className="small muted">· {narrative.verification.checked} numbers checked, {unverified.length} unverified</span>}
            <button className="small" onClick={() => setShowUsage(!showUsage)}>{showUsage ? 'hide' : 'usage & verification JSON'}</button>
          </div>
          {narrative.status === 'UNVERIFIED' && (
            <Notice kind="warn">Numbers the verifier could not find in the facts, flagged below: {unverified.map((u, i) => <code key={i}>{u}{i < unverified.length - 1 ? ', ' : ''}</code>)}. They are reported as written and never corrected by the host.</Notice>
          )}
          {narrative.status === 'FAILED' && (
            <Notice kind="error"><b>{narrative.error}</b>{narrative.rawText ? <pre>{narrative.rawText}</pre> : null}</Notice>
          )}
          {showUsage && <div className="card tight"><JsonView value={{ verification: narrative.verification, usage: narrative.usage }} depth={2} /></div>}
          {n && (
            <>
              <h3>Run summary</h3>
              <p><T text={n.run_summary} /></p>
              <h3>Members</h3>
              {n.members.map((m) => (
                <div key={m.member_id} style={{ margin: '6px 0 12px' }}>
                  <div className="row"><b>{m.name}</b> <span className={'badge level-' + m.risk_level}>{m.risk_level} risk</span></div>
                  <p><T text={m.summary} /></p>
                  {m.patterns && m.patterns.length > 0 && <ul>{m.patterns.map((p, i) => <li key={i}><span className="badge">{p.kind}</span> <T text={p.statement} /> <span className="muted">— <T text={p.evidence} /></span></li>)}</ul>}
                  {m.warnings && m.warnings.length > 0 && <ul>{m.warnings.map((w, i) => <li key={i}>⚠ <T text={w} /></li>)}</ul>}
                  {m.likely_work && m.likely_work.length > 0 && <ul>{m.likely_work.map((w, i) => <li key={i}><span className={'badge level-' + w.confidence}>{w.confidence}</span> <T text={w.statement} /> <span className="muted">— <T text={w.evidence} /></span></li>)}</ul>}
                </div>
              ))}
              {n.team_risks && n.team_risks.length > 0 && (<><h3>Team risks</h3><ul>{n.team_risks.map((t, i) => <li key={i}><span className={'badge level-' + t.severity}>{t.severity}</span> <b><T text={t.title} /></b>: <T text={t.detail} /></li>)}</ul></>)}
              {n.rebalancing && n.rebalancing.length > 0 && (
                <><h3>Rebalancing</h3>
                  <table><thead><tr><th>From</th><th>To</th><th>Window</th><th className="num">Hours</th><th>Reason</th><th>Confidence</th><th>Tasks</th></tr></thead>
                    <tbody>{n.rebalancing.map((mv, i) => <tr key={i}><td>{nameOf(n, mv.from_member_id)}</td><td>{nameOf(n, mv.to_member_id)}</td><td>{mv.window}</td><td className="num">{num(mv.hours, 1)}</td><td><T text={mv.reason} /></td><td><span className={'badge level-' + mv.confidence}>{mv.confidence}</span></td><td className="mono small">{(mv.task_keys ?? []).join(', ')}</td></tr>)}</tbody>
                  </table></>
              )}
              {n.suggested_adjustments && n.suggested_adjustments.length > 0 && (
                <><h3>Suggested adjustments</h3>
                  <table><thead><tr><th>Member</th><th>Window</th><th className="num">Δ hours</th><th>Reason</th></tr></thead>
                    <tbody>{n.suggested_adjustments.map((a, i) => <tr key={i}><td>{nameOf(n, a.member_id)}</td><td>{a.window}</td><td className="num">{num(a.delta_hours, 1)}</td><td><T text={a.reason} /></td></tr>)}</tbody>
                  </table></>
              )}
              {n.model_notes && <><h3>Model notes</h3><p className="muted"><T text={n.model_notes} /></p></>}
            </>
          )}
        </div>
      )}
    </div>
  )
}

function nameOf(n: { members: { member_id: string; name: string }[] }, id: string): string {
  return n.members.find((m) => m.member_id === id)?.name ?? id
}
