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
      <p className="lead">Every team of the directory: a team leader and the people who report to them directly, named by its leader. The company structure is <code>users.manager_id</code>; the application’s own <code>teams</code> table groups people around a project and is not read here. A run needs <code>canRun</code>; every read needs <code>canView</code>.</p>
      {!userId && <Notice>Pick a user to act as first: every route but <code>GET /api/system</code> needs the <code>X-Acting-User</code> header.</Notice>}
      {me && <Notice>{describe(me.user.role, summarise(me.teams))}.</Notice>}
      <ErrorNotice error={teams.error} />
      {teams.data && (
        <div className="card scroll">
          <table>
            <thead><tr><th>Team (its leader)</th><th>Leader reports to</th><th className="num">Members</th><th>View</th><th>Run</th></tr></thead>
            <tbody>
              {teams.data.map((t) => {
                const p = perms.get(t.id)
                return (
                  <tr key={t.id}>
                    <td><Link to={'/teams/' + t.id}>{t.name}</Link></td>
                    <td>{t.parentTeamName ?? <span className="muted">nobody</span>}</td>
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
