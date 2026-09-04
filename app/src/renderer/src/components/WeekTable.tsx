import type React from 'react'
import { Link } from 'react-router-dom'
import type { ForecastRow, RiskLevel } from '../../../shared/types'
import { hours, weekLabel } from '../format'
import { t } from '../i18n'
import { IntervalBar } from './IntervalBar'
import { RiskBadge } from './RiskBadge'

export interface WeekTableRow { member_id: number; name: string; cells: Record<string, ForecastRow | undefined>; risk?: RiskLevel; href?: string }

export function WeekTable({ weeks, rows }: { weeks: string[]; rows: WeekTableRow[] }): React.JSX.Element {
  const max = Math.max(1, ...rows.flatMap((r) => weeks.map((w) => Math.max(r.cells[w]?.demand_high ?? 0, r.cells[w]?.capacity_hours ?? 0))))
  return (
    <table>
      <thead>
        <tr>
          <th>{t('team.member')}</th>
          {weeks.map((w) => <th key={w} colSpan={3}>{weekLabel(w)}</th>)}
        </tr>
        <tr>
          <th></th>
          {weeks.map((w) => [<th key={`${w}d`} className="num">{t('member.demand')}</th>, <th key={`${w}i`}>{t('team.interval')}</th>, <th key={`${w}c`} className="num">{t('member.capacity')}</th>])}
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.member_id} aria-label={r.name}>
            <td>{r.href ? <Link to={r.href}>{r.name}</Link> : r.name} {r.risk && <RiskBadge level={r.risk} />}</td>
            {weeks.map((w) => {
              const c = r.cells[w]
              if (!c) return [<td key={`${w}d`} className="num">–</td>, <td key={`${w}i`}></td>, <td key={`${w}c`} className="num">–</td>]
              return [
                <td key={`${w}d`} className="num">{hours(c.demand_hours)}</td>,
                <td key={`${w}i`}><IntervalBar low={c.demand_low} high={c.demand_high} value={c.demand_hours} max={max} /></td>,
                <td key={`${w}c`} className="num">{hours(c.capacity_hours)} {c.overload_hours > 0 && <span className="badge high">+{c.overload_hours.toFixed(1)} h</span>}</td>,
              ]
            })}
          </tr>
        ))}
      </tbody>
    </table>
  )
}
