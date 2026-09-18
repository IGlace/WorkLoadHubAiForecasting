import { Link } from 'react-router-dom'
import { api } from '../api'
import { Behind } from '../components/Behind'
import { ErrorNotice, Notice } from '../components/Notice'
import { useAsync } from '../lib/useAsync'
import { describe, summarise } from '../lib/permissions'
import { useActing } from '../state/acting'

export function TeamsPage() {
  const { userId, me } = useActing()
  const teams = useAsync(() => (userId ? api.teams() : Promise.resolve([])), [userId])
  const perms = new Map(me?.teams.map((t) => [t.teamId, t]) ?? [])
  return (
    <div>
      <h1>Teams</h1>
      <p className="lead">Every team of the directory, with what the acting user may do on it. A run needs <code>canRun</code>; every read needs <code>canView</code>.</p>
      {!userId && <Notice>Pick a user to act as first: every route but <code>GET /api/system</code> needs the <code>X-Acting-User</code> header.</Notice>}
      {me && <Notice>{describe(me.user.role, summarise(me.teams))}.</Notice>}
      <ErrorNotice error={teams.error} />
      {teams.data && (
        <div className="card scroll">
          <table>
            <thead><tr><th>Team</th><th>Manager</th><th>Parent team</th><th className="num">Members</th><th>View</th><th>Run</th></tr></thead>
            <tbody>
              {teams.data.map((t) => {
                const p = perms.get(t.id)
                return (
                  <tr key={t.id}>
                    <td><Link to={'/teams/' + t.id}>{t.name}</Link></td>
                    <td>{t.managerName ?? <span className="muted">none</span>}</td>
                    <td>{t.parentTeamName ?? <span className="muted">—</span>}</td>
                    <td className="num">{t.memberCount}</td>
                    <td><span className={'badge ' + (p?.canView ? 'ok' : 'no')} title={p?.viewReason}>{p?.canView ? 'view' : 'no'}</span> <span className="muted small">{p?.viewReason}</span></td>
                    <td><span className={'badge ' + (p?.canRun ? 'ok' : 'no')} title={p?.runReason}>{p?.canRun ? 'run' : 'no'}</span> <span className="muted small">{p?.runReason}</span></td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
      <Behind />
    </div>
  )
}
