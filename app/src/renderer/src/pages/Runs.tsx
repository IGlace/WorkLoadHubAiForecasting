import type React from 'react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import type { RunSummary } from '../../../shared/types'
import { getRuns } from '../api'
import { Field } from '../components/Field'
import { StatusMessage } from '../components/StatusMessage'
import { useApp } from '../context'
import { t } from '../i18n'

export function Runs(): React.JSX.Element {
  const { visibleTeams } = useApp()
  const [runs, setRuns] = useState<RunSummary[]>([])
  const [team, setTeam] = useState<string>('')
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { getRuns().then(setRuns).catch((e: Error) => setError(e.message)) }, [])
  const nameOf = new Map(visibleTeams.map((tm) => [tm.id, tm.name]))
  const rows = runs.filter((r) => nameOf.has(r.team_id) && (team === '' || r.team_id === Number(team))).sort((a, b) => b.id - a.id)
  return (
    <div>
      <h1>{t('runs.title')}</h1>
      {error && <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>}
      <Field label={t('runs.team')}>
        {(id) => (
          <select id={id} value={team} onChange={(e) => setTeam(e.target.value)}>
            <option value="">{t('common.all')}</option>
            {visibleTeams.map((tm) => <option key={tm.id} value={tm.id}>{tm.name}</option>)}
          </select>
        )}
      </Field>
      {!error && (rows.length === 0 ? <p className="muted">{t('runs.empty')}</p> : (
        <table>
          <thead><tr><th>{t('runs.id')}</th><th>{t('runs.team')}</th><th>{t('runs.asof')}</th><th>{t('runs.status')}</th><th>{t('runs.champion')}</th><th>{t('runs.ai')}</th><th></th></tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id}>
                <td>{r.id}</td><td>{nameOf.get(r.team_id)}</td><td>{r.as_of}</td><td>{r.status}</td>
                <td>{r.champion_model ?? '–'}{r.backtest_mase !== null && <span className="muted"> (MASE {r.backtest_mase.toFixed(2)})</span>}</td>
                <td>{r.ai_status}</td><td><Link to={`/runs/${r.id}`}>{t('runs.open')}</Link></td>
              </tr>
            ))}
          </tbody>
        </table>
      ))}
    </div>
  )
}
