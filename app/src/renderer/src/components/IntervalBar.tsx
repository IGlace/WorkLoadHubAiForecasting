import type React from 'react'

export function IntervalBar({ low, high, value, max }: { low: number; high: number; value: number; max: number }): React.JSX.Element {
  // Forecast facts should always have high >= low and low <= value <= high, but never
  // trust that at the rendering boundary: an inverted interval or an out-of-range value
  // would otherwise produce a negative-width bar or a marker drawn outside it.
  const clampedHigh = Math.max(high, low)
  const clampedValue = Math.min(Math.max(value, low), clampedHigh)
  const scale = max > 0 ? 100 / max : 0
  const clamp = (n: number): number => Math.max(0, Math.min(100, n * scale))
  return (
    <div className="bar" title={`${low.toFixed(1)} – ${clampedHigh.toFixed(1)} h`} aria-label={`interval ${low.toFixed(1)} to ${clampedHigh.toFixed(1)} hours`}>
      <span style={{ left: `${clamp(low)}%`, width: `${clamp(clampedHigh) - clamp(low)}%` }} />
      <i style={{ left: `${clamp(clampedValue)}%` }} />
    </div>
  )
}
