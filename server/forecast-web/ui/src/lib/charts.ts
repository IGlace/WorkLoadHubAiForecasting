/** The arithmetic behind the SVG charts: a nice axis maximum, a linear scale and the tick values. */
export function niceMax(values: number[]): number {
  const max = Math.max(0, ...values.filter((v) => Number.isFinite(v)))
  if (max === 0) return 10
  const magnitude = Math.pow(10, Math.floor(Math.log10(max)))
  for (const s of [1, 2, 2.5, 5, 10]) {
    const candidate = s * magnitude
    if (candidate >= max) return candidate
  }
  return 10 * magnitude
}

export function scale(domainMax: number, rangeMax: number): (v: number) => number {
  return (v: number) => (domainMax <= 0 ? 0 : Math.max(0, Math.min(rangeMax, (v / domainMax) * rangeMax)))
}

export function ticks(max: number, count = 4): number[] {
  const out: number[] = []
  for (let i = 0; i <= count; i++) out.push(Math.round(((max * i) / count) * 100) / 100)
  return out
}

/**
 * The cell shade of the team grid: the load ratio demand / capacity on a one-hue sequential ramp, six steps.
 * Overload (ratio above one) is not shaded darker: it gets a glyph and a label, because a state never rides on
 * colour alone. A day without capacity (holiday, leave) is step 0.
 */
export function loadStep(demand: number, capacity: number): number {
  if (capacity <= 0 || demand <= 0) return 0
  const ratio = demand / capacity
  if (ratio < 0.25) return 1
  if (ratio < 0.5) return 2
  if (ratio < 0.75) return 3
  if (ratio < 1) return 4
  return 5
}
