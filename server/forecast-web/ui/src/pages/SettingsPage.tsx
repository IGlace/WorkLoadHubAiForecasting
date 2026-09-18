import { useState } from 'react'
import { api } from '../api'
import { Behind } from '../components/Behind'
import { JsonView } from '../components/JsonView'
import { ErrorNotice, Notice } from '../components/Notice'
import { useAsync } from '../lib/useAsync'
import { useActing } from '../state/acting'
import type { CopilotStatus } from '../types'

/** The settings page of the acting user: their GitHub token and their Copilot seat. */
export function SettingsPage() {
  const { userId, me, refresh } = useActing()
  const state = useAsync(() => (userId ? api.tokenState() : Promise.resolve({ hasToken: false })), [userId])
  const [token, setToken] = useState('')
  const [saveError, setSaveError] = useState<unknown>(null)
  const [saved, setSaved] = useState<string | null>(null)
  const [status, setStatus] = useState<CopilotStatus | null>(null)
  const [statusError, setStatusError] = useState<unknown>(null)
  const [checking, setChecking] = useState(false)

  async function save() {
    setSaveError(null); setSaved(null)
    try {
      await api.putToken(token.trim())
      setToken('')
      setSaved('Token stored, encrypted with whf.token-key on this user\'s row. It is never returned by any route.')
      await state.reload(); await refresh()
    } catch (e) { setSaveError(e) }
  }
  async function clear() {
    setSaveError(null); setSaved(null)
    try { await api.deleteToken(); setSaved('Token cleared.'); setStatus(null); await state.reload(); await refresh() } catch (e) { setSaveError(e) }
  }
  async function check() {
    setChecking(true); setStatusError(null)
    try { setStatus(await api.copilot()) } catch (e) { setStatusError(e) } finally { setChecking(false) }
  }
  const quota = status?.quotaJson ? safeJson(status.quotaJson) : null

  return (
    <div>
      <h1>Copilot &amp; token</h1>
      <p className="lead">Narration uses the acting user's <em>own</em> GitHub Copilot seat. The host stores the token encrypted and the module reads it in one place, at narration time. Acting as <b>{me?.user.fullName ?? '—'}</b>.</p>
      {!userId && <Notice>Pick a user to act as first.</Notice>}
      <div className="grid2">
        <div className="card">
          <h2 style={{ marginTop: 0 }}>GitHub token</h2>
          <p><span className={'badge ' + (state.data?.hasToken ? 'ok' : 'no')}>{state.data?.hasToken ? 'a token is stored' : 'no token stored'}</span> <span className="muted small">GET /api/me/github-token</span></p>
          <p className="small muted">Accepted: <code>gho_</code>, <code>ghu_</code> and <code>github_pat_</code> tokens with the Copilot scope; classic <code>ghp_</code> tokens are refused. From a terminal with the GitHub CLI: <span className="kbd">gh auth login</span> then <span className="kbd">gh auth token</span>; if the seat check refuses it, <span className="kbd">gh auth refresh -h github.com -s copilot</span>.</p>
          <div className="row">
            <input type="password" autoComplete="off" placeholder="gho_…" value={token} onChange={(e) => setToken(e.target.value)} style={{ minWidth: 320 }} disabled={!userId} />
            <button className="primary" disabled={!userId || !token.trim()} onClick={() => void save()}>Save</button>
            <button disabled={!userId || !state.data?.hasToken} onClick={() => void clear()}>Clear</button>
          </div>
          <p className="small muted">PUT /api/me/github-token · DELETE /api/me/github-token. A user stores their own token only; the production server does the same from its session.</p>
          {saved && <Notice kind="good">{saved}</Notice>}
          <ErrorNotice error={saveError ?? state.error} />
        </div>
        <div className="card">
          <h2 style={{ marginTop: 0 }}>Copilot seat</h2>
          <p className="small muted"><code>GET /api/me/copilot</code> opens a Copilot session with the stored token and reports what GitHub says: this is the settings page's check, not something to call before every narration (the host checks <code>hasToken</code> with one query instead).</p>
          <button disabled={!userId || checking} onClick={() => void check()}>{checking ? 'checking…' : 'Check the seat'}</button>
          <ErrorNotice error={statusError} />
          {status && (
            <table className="mt">
              <tbody>
                <tr><th>token stored</th><td>{String(status.hasToken)}</td></tr>
                <tr><th>runtime available</th><td>{String(status.runtimeAvailable)} <span className="muted small">{status.runtimeVersion ?? ''} {status.runtimePath ?? ''}</span></td></tr>
                <tr><th>authenticated</th><td>{status.authenticated === null ? <span className="muted">not checked</span> : String(status.authenticated)}</td></tr>
                <tr><th>login</th><td>{status.login ?? <span className="muted">–</span>}</td></tr>
                <tr><th>message</th><td>{status.message ?? <span className="muted">–</span>}</td></tr>
                <tr><th>quota</th><td>{quota ? <JsonView value={quota} depth={2} /> : <span className="muted">{status.quotaJson ?? 'unavailable'}</span>}</td></tr>
              </tbody>
            </table>
          )}
        </div>
      </div>
      <Behind />
    </div>
  )
}

function safeJson(s: string): unknown {
  try { return JSON.parse(s) } catch { return null }
}
