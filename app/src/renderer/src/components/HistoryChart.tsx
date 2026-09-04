import type React from 'react'
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { HistoryPoint } from '../../../shared/types'
import { weekLabel } from '../format'

export function HistoryChart({ points }: { points: HistoryPoint[] }): React.JSX.Element {
  const rows = points.map((p) => ({ ...p, label: weekLabel(p.week) }))
  return (
    <div style={{ width: '100%', height: 200 }}>
      <ResponsiveContainer>
        <LineChart data={rows}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis dataKey="label" />
          <YAxis unit=" h" />
          <Tooltip />
          <Line type="monotone" dataKey="hours" name="Estimated hours" stroke="#2457c5" dot={false} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
