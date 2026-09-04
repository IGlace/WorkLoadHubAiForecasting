import type React from 'react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import type { DepartmentOverview, OverviewTeam } from '../../../shared/types'
import { getDepartmentOverview } from '../api'
import { DemandCapacityChart } from '../components/DemandCapacityChart'
import { StatusMessage } from '../components/StatusMessage'
import { useApp } from '../context'
import { hours, weekLabel } from '../format'
import { t } from '../i18n'

function TeamCard({ team }: { team: OverviewTeam }): React.JSX.Element {
  return (
    <section className="panel">
      <h2>{team.team_name} {team.due && <span className="badge high">{t('dashboard.due')}</span>}</h2>
      <p className="muted">{team.run_id ? t('dashboard.lastRun', { date: team.finished_at?.slice(0, 10) ?? team.as_of ?? '' }) : t('dashboard.noRun')}</p>
      {team.weeks.length > 0 && (
        <>
          <DemandCapacityChart data={team.weeks} />
          <table>
            <thead><tr><th>{t('member.week')}</th><th className="num">{t('dashboard.demand')}</th><th className="num">{t('dashboard.capacity')}</th><th className="num">{t('dashboard.overload')}</th></tr></thead>
            <tbody>
              {team.weeks.map((w) => (
                <tr key={w.week}><td>{weekLabel(w.week)}</td><td className="num">{hours(w.demand)}</td><td className="num">{hours(w.capacity)}</td><td className="num">{hours(w.overload)}</td></tr>
              ))}
            </tbody>
          </table>
        </>
      )}
      {team.overloaded.length > 0 && (
        <p>{t('dashboard.overloaded')}: {team.overloaded.map((m) => <span key={m.member_id} className="badge high" style={{ marginRight: 6 }}><span>{m.name}</span> +{m.overload_hours.toFixed(1)} h</span>)}</p>
      )}
      <p>
        {team.run_id && <Link to={`/runs/${team.run_id}`}>Open result</Link>}{' '}
        <Link to={`/run?team=${team.team_id}`}>{t('nav.run')}</Link>
      </p>
    </section>
  )
}

export function Dashboard(): React.JSX.Element {
  const { me, visibleTeams } = useApp()
  const [overview, setOverview] = useState<DepartmentOverview | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    if (!me) return
    getDepartmentOverview(me.department_id).then(setOverview).catch((e: Error) => setError(e.message))
  }, [me])
  if (!me) return <h1>{t('dashboard.title')}</h1>
  const visible = new Set(visibleTeams.map((tm) => tm.id))
  return (
    <div>
      <h1>{t('dashboard.title')}</h1>
      {error && <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>}
      {!overview && !error && <p>{t('common.loading')}</p>}
      {overview?.teams.filter((tm) => visible.has(tm.team_id)).map((tm) => <TeamCard key={tm.team_id} team={tm} />)}
    </div>
  )
}
