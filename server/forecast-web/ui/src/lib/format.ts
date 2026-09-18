/**
 * Numbers and dates as the pages print them. Hours get one decimal and a unit; a missing value is a dash.
 * A score the module could not compute is NaN, which Jackson writes as the string "NaN": the formatters take
 * either and print the dash.
 */
export type Numeric = number | string | null | undefined

/** The value as a number, or null when it is absent or not finite (a NaN score arrives as the string "NaN"). */
export function finite(v: Numeric): number | null {
  if (v === null || v === undefined) return null
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : null
}

export function hours(v: Numeric, decimals = 1): string {
  const n = finite(v)
  return n === null ? '–' : n.toFixed(decimals) + ' h'
}

export function num(v: Numeric, decimals = 2): string {
  const n = finite(v)
  return n === null ? '–' : n.toFixed(decimals)
}

export function signed(v: Numeric, decimals = 2): string {
  const n = finite(v)
  return n === null ? '–' : (n > 0 ? '+' : '') + n.toFixed(decimals)
}

export function pct(v: Numeric): string {
  const n = finite(v)
  return n === null ? '–' : Math.round(n * 100) + ' %'
}

const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/** "Mon 07 Sep" for an ISO date, computed without time zones (the date is a calendar day). */
export function dayLabel(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number)
  const dow = new Date(Date.UTC(y, m - 1, d)).getUTCDay()
  return `${DAYS[dow]} ${String(d).padStart(2, '0')} ${MONTHS[m - 1]}`
}

export function isWeekend(iso: string): boolean {
  const [y, m, d] = iso.split('-').map(Number)
  const dow = new Date(Date.UTC(y, m - 1, d)).getUTCDay()
  return dow === 0 || dow === 6
}

export function addDays(iso: string, days: number): string {
  const [y, m, d] = iso.split('-').map(Number)
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10)
}

export function shortId(id: string | null | undefined): string {
  return id ? id.slice(0, 8) : '–'
}

export function dateTime(iso: string | null | undefined): string {
  if (!iso) return '–'
  return iso.replace('T', ' ').slice(0, 16)
}
