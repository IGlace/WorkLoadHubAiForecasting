import type React from 'react'

export function IntervalBar({ low, high, value, max }: { low: number; high: number; value: number; max: number }): React.JSX.Element {
  const scale = max > 0 ? 100 / max : 0
  const clamp = (n: number): number => Math.max(0, Math.min(100, n * scale))
  return (
    <div className="bar" title={`${low.toFixed(1)} – ${high.toFixed(1)} h`} aria-label={`interval ${low.toFixed(1)} to ${high.toFixed(1)} hours`}>
      <span style={{ left: `${clamp(low)}%`, width: `${clamp(high) - clamp(low)}%` }} />
      <i style={{ left: `${clamp(value)}%` }} />
    </div>
  )
}
