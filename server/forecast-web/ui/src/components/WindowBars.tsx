import { useState } from 'react'
import { niceMax, scale, ticks } from '../lib/charts'
import { hours } from '../lib/format'
import type { MemberWindowForecast } from '../types'

/**
 * One forecast window as horizontal bars: the demand of every member (series 1) with its low–high band as a
 * whisker, and the capacity as a tick (series 2). A legend and a table sit next to it; hover reads the row.
 */
export function WindowBars({ rows, names }: { rows: MemberWindowForecast[]; names: Map<string, string> }) {
  const [hover, setHover] = useState<number | null>(null)
  const width = 640
  const left = 150
  const right = 70
  const rowH = 26
  const height = 26 + rows.length * rowH + 22
  const plotW = width - left - right
  const max = niceMax(rows.flatMap((r) => [r.highHrs, r.capacityHrs, r.demandHrs]))
  const x = scale(max, plotW)
  return (
    <div className="chart-wrap">
      <svg className="chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="demand against capacity per member">
        {ticks(max, 4).map((t) => (
          <g key={t}>
            <line className="axis" x1={left + x(t)} x2={left + x(t)} y1={18} y2={height - 20} />
            <text className="tick" x={left + x(t)} y={height - 6} textAnchor="middle">{t} h</text>
          </g>
        ))}
        {rows.map((r, i) => {
          const y = 24 + i * rowH
          const over = r.overloadHrs > 0
          return (
            <g key={r.userId} onPointerEnter={() => setHover(i)} onPointerLeave={() => setHover(null)}>
              <rect className="hit" x={0} y={y - 2} width={width} height={rowH} />
              <text className="lbl" x={left - 8} y={y + 13} textAnchor="end">{(names.get(r.userId) ?? r.userId).slice(0, 22)}</text>
              <rect className="bar" x={left} y={y + 2} width={x(r.demandHrs)} height={16} rx={0} opacity={hover === null || hover === i ? 1 : 0.6} />
              <rect className="bar" x={left + Math.max(0, x(r.demandHrs) - 4)} y={y + 2} width={Math.min(4, x(r.demandHrs))} height={16} rx={4} />
              <line className="band" x1={left + x(r.lowHrs)} x2={left + x(r.highHrs)} y1={y + 10} y2={y + 10} />
              <line className="band" x1={left + x(r.lowHrs)} x2={left + x(r.lowHrs)} y1={y + 6} y2={y + 14} />
              <line className="band" x1={left + x(r.highHrs)} x2={left + x(r.highHrs)} y1={y + 6} y2={y + 14} />
              <line className="cap" x1={left + x(r.capacityHrs)} x2={left + x(r.capacityHrs)} y1={y} y2={y + 20} />
              <text className="val" x={left + Math.max(x(r.demandHrs), x(r.highHrs)) + 6} y={y + 14}>
                {hours(r.demandHrs)}{over ? ' ▲ +' + r.overloadHrs.toFixed(1) : ''}
              </text>
            </g>
          )
        })}
      </svg>
      {hover !== null && rows[hover] && (
        <div className="tooltip" style={{ left: left, top: 24 + hover * rowH - 34 }}>
          <b>{hours(rows[hover].demandHrs)}</b> demand · band {hours(rows[hover].lowHrs)}–{hours(rows[hover].highHrs)} · <b>{hours(rows[hover].capacityHrs)}</b> capacity
          {rows[hover].overloadHrs > 0 ? <> · overload <b>{hours(rows[hover].overloadHrs)}</b></> : null}
        </div>
      )}
      <div className="legend">
        <span><span className="sw" style={{ background: 'var(--series-1)' }} />demand (predicted logged hours)</span>
        <span><span className="ln" style={{ background: 'var(--text-2)' }} />low–high band</span>
        <span><span className="ln" style={{ background: 'var(--series-2)' }} />capacity</span>
        <span>▲ overload, hours beyond capacity</span>
      </div>
    </div>
  )
}
