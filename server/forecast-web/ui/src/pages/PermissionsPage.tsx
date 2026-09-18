import { useState } from 'react'
import { api } from '../api'
import { Behind } from '../components/Behind'
import { ErrorNotice, Notice } from '../components/Notice'
import { ROLE_MATRIX } from '../lib/permissions'
import { useAsync } from '../lib/useAsync'
import { useActing } from '../state/acting'
import type { PermissionView } from '../types'

/** The role matrix as designed, and a live check of any user on any team, with the reasons the host gives. */
export function PermissionsPage() {
  const { userId, users, setUserId } = useActing()
  const teams = useAsync(() => (userId ? api.teams() : Promise.resolve([])), [userId])
  const [subject, setSubject] = useState('')
  const [team, setTeam] = useState('')
  const [result, setResult] = useState<PermissionView | null>(null)
  const [error, setError] = useState<unknown>(null)

  async function check() {
    setError(null)
    try { setResult(await api.permissions(subject, team)) } catch (e) { setResult(null); setError(e) }
  }

  return (
    <div>
      <h1>Permissions</h1>
      <p className="lead">The host decides who may run and view a forecast before calling the module; the module trusts <code>requestedBy</code> as given. These are the v1 role rules (design 2026-09-11, section 3.2), and a refused check is the host's own 403.</p>
      <div className="card scroll">
        <table>
          <thead><tr><th>Role</th><th>May start a run for</th><th>May view</th></tr></thead>
          <tbody>{ROLE_MATRIX.map((r) => <tr key={r.role}><td><span className="badge accent">{r.role}</span></td><td>{r.run}</td><td>{r.view}</td></tr>)}</tbody>
        </table>
        <p className="small muted">"One at a time" for a skill team leader: a second start while a run they requested is still computing is refused with 403. Team leaders are not limited this way. <code>accuracy</code> is shown to the roles that can view the team.</p>
      </div>
      <div className="card">
        <h2 style={{ marginTop: 0 }}>Check any user on any team</h2>
        <p className="small muted"><code>GET /api/permissions?userId&amp;teamId</code>, a test tool of this showcase (not a production route): the same two decisions the host makes, with their reasons.</p>
        {!userId && <Notice>Pick a user to act as first: the directory is read as that user.</Notice>}
        <div className="row">
          <select value={subject} onChange={(e) => setSubject(e.target.value)} style={{ minWidth: 280 }}>
            <option value="">— user —</option>
            {users.map((u) => <option key={u.id} value={u.id}>{u.fullName} · {u.role}</option>)}
          </select>
          <select value={team} onChange={(e) => setTeam(e.target.value)} style={{ minWidth: 280 }}>
            <option value="">— team —</option>
            {(teams.data ?? []).map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </select>
          <button className="primary" disabled={!subject || !team} onClick={() => void check()}>Check</button>
        </div>
        <ErrorNotice error={error ?? teams.error} />
        {result && (
          <div className="mt">
            <table>
              <tbody>
                <tr><th>role</th><td><span className="badge accent">{result.role}</span></td></tr>
                <tr><th>may run</th><td><span className={'badge ' + (result.canRun ? 'ok' : 'no')}>{result.canRun ? 'yes' : 'no'}</span> {result.runReason}</td></tr>
                <tr><th>may view</th><td><span className={'badge ' + (result.canView ? 'ok' : 'no')}>{result.canView ? 'yes' : 'no'}</span> {result.viewReason}</td></tr>
              </tbody>
            </table>
            <p className="mt"><button onClick={() => setUserId(result.userId)}>Act as this user</button> <span className="muted small">then open the team and try the button yourself</span></p>
          </div>
        )}
      </div>
      <Behind />
    </div>
  )
}
