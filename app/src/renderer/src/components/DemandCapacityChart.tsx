import type React from 'react'
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { weekLabel } from '../format'

export function DemandCapacityChart({ data }: { data: { week: string; demand: number; capacity: number }[] }): React.JSX.Element {
  const rows = data.map((d) => ({ ...d, label: weekLabel(d.week) }))
  return (
    <div style={{ width: '100%', height: 220 }}>
      <ResponsiveContainer>
        <BarChart data={rows}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis dataKey="label" />
          <YAxis unit=" h" />
          <Tooltip />
          <Legend />
          <Bar dataKey="demand" name="Demand" fill="#2457c5" />
          <Bar dataKey="capacity" name="Capacity" fill="#9ca3af" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
