const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

export function hours(n: number | null | undefined): string {
  return n === null || n === undefined || Number.isNaN(n) ? '–' : `${n.toFixed(1)} h`
}

export function weekLabel(iso: string): string {
  const d = new Date(`${iso.slice(0, 10)}T00:00:00`)
  return `${DAYS[d.getDay()]} ${String(d.getDate()).padStart(2, '0')} ${MONTHS[d.getMonth()]}`
}

export function pct(n: number): string { return `${Math.round(n * 100)}%` }

export function today(): string { return new Date().toISOString().slice(0, 10) }
