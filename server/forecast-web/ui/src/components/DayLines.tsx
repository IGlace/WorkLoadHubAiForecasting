import { useState } from 'react'
import { niceMax, scale, ticks } from '../lib/charts'
import { dayLabel, hours } from '../lib/format'

export interface DayPoint { day: string; forecast: number; logged: number }

/** Forecast against logged hours per day, summed over the members: two lines, a crosshair that snaps to the day. */
export function DayLines({ points }: { points: DayPoint[] }) {
  const [hover, setHover] = useState<number | null>(null)
  const width = 720
  const height = 240
  const left = 48
  const right = 16
  const top = 14
  const bottom = 34
  const plotW = width - left - right
  const plotH = height - top - bottom
  const max = niceMax(points.flatMap((p) => [p.forecast, p.logged]))
  const y = scale(max, plotH)
  const xs = (i: number) => (points.length <= 1 ? left + plotW / 2 : left + (i / (points.length - 1)) * plotW)
  const path = (key: 'forecast' | 'logged') => points.map((p, i) => `${i === 0 ? 'M' : 'L'}${xs(i).toFixed(1)},${(top + plotH - y(p[key])).toFixed(1)}`).join(' ')
  if (points.length === 0) return <p className="muted">nothing to draw</p>
  return (
    <div className="chart-wrap">
      <svg className="chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="forecast against logged hours per day"
        onPointerLeave={() => setHover(null)}
        onPointerMove={(e) => {
          const rect = (e.currentTarget as SVGSVGElement).getBoundingClientRect()
          const px = ((e.clientX - rect.left) / rect.width) * width
          let best = 0
          for (let i = 1; i < points.length; i++) if (Math.abs(xs(i) - px) < Math.abs(xs(best) - px)) best = i
          setHover(best)
        }}>
        {ticks(max, 4).map((t) => (
          <g key={t}>
            <line className="axis" x1={left} x2={width - right} y1={top + plotH - y(t)} y2={top + plotH - y(t)} />
            <text className="tick" x={left - 6} y={top + plotH - y(t) + 4} textAnchor="end">{t}</text>
          </g>
        ))}
        {points.map((p, i) => (i % Math.max(1, Math.ceil(points.length / 8)) === 0 || i === points.length - 1) && (
          <text key={p.day} className="tick" x={xs(i)} y={height - 12} textAnchor="middle">{p.day.slice(5)}</text>
        ))}
        <path className="line-1" d={path('forecast')} />
        <path className="line-2" d={path('logged')} />
        {hover !== null && (
          <g>
            <line className="cross" x1={xs(hover)} x2={xs(hover)} y1={top} y2={top + plotH} />
            <circle className="dot-1" cx={xs(hover)} cy={top + plotH - y(points[hover].forecast)} r={4} />
            <circle className="dot-2" cx={xs(hover)} cy={top + plotH - y(points[hover].logged)} r={4} />
          </g>
        )}
      </svg>
      {hover !== null && (
        <div className="tooltip" style={{ left: Math.min(xs(hover) / width * 100, 70) + '%', top: 0 }}>
          <div className="muted">{dayLabel(points[hover].day)}</div>
          <div><span className="ln" style={{ background: 'var(--series-1)', display: 'inline-block', width: 14, height: 2, marginRight: 6, verticalAlign: 3 }} /><b>{hours(points[hover].forecast)}</b> forecast</div>
          <div><span className="ln" style={{ background: 'var(--series-2)', display: 'inline-block', width: 14, height: 2, marginRight: 6, verticalAlign: 3 }} /><b>{hours(points[hover].logged)}</b> logged</div>
        </div>
      )}
      <div className="legend">
        <span><span className="ln" style={{ background: 'var(--series-1)' }} />forecast, summed over the members</span>
        <span><span className="ln" style={{ background: 'var(--series-2)' }} />logged</span>
      </div>
    </div>
  )
}
