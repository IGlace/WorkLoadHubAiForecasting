// One client for the whole surface: adds the acting-user header, records every call for the "behind this
// page" strip, and turns a {code, message} body into an ApiError.
import type {
  AccuracyResult, CopilotStatus, CurrentDayForecast, MeView, NarrationStarted, NarrativeView, PermissionView, RunProgress, RunSummary, RunView,
  SystemView, TeamDetailView, TeamView, TokenState, UserView,
} from './types'

export const ACTING_USER_HEADER = 'X-Acting-User'
const STORAGE_KEY = 'whf.actingUser'

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

/** What an error response becomes: the body's code and message when it is one of ours, else the HTTP status. */
export function toApiError(status: number, body: unknown): ApiError {
  if (body && typeof body === 'object' && typeof (body as { code?: unknown }).code === 'string') {
    const b = body as { code: string; message?: string }
    return new ApiError(status, b.code, b.message ?? b.code)
  }
  return new ApiError(status, 'HTTP_' + status, typeof body === 'string' && body ? body : 'HTTP ' + status)
}

export interface CallRecord {
  seq: number
  method: string
  path: string
  status: number | null
  ms: number
  at: string
  response: unknown
  error: string | null
}

let seq = 0
let calls: CallRecord[] = []
const listeners = new Set<() => void>()
const LIMIT = 40

function record(c: CallRecord) {
  calls = [...calls.slice(-(LIMIT - 1)), c]
  listeners.forEach((l) => l())
}

export const callLog = {
  subscribe(listener: () => void) {
    listeners.add(listener)
    return () => { listeners.delete(listener) }
  },
  snapshot(): CallRecord[] { return calls },
  seq(): number { return seq },
  clear() { calls = []; listeners.forEach((l) => l()) },
}

export function readActingUser(): string | null {
  try { return localStorage.getItem(STORAGE_KEY) } catch { return null }
}

export function writeActingUser(id: string | null) {
  try { if (id) localStorage.setItem(STORAGE_KEY, id); else localStorage.removeItem(STORAGE_KEY) } catch { /* private window */ }
}

async function request<T>(method: string, path: string, body?: unknown, opts: { withUser?: boolean } = {}): Promise<T> {
  const headers: Record<string, string> = {}
  const user = readActingUser()
  if (opts.withUser !== false && user) headers[ACTING_USER_HEADER] = user
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const started = performance.now()
  const rec: CallRecord = { seq: ++seq, method, path, status: null, ms: 0, at: new Date().toISOString(), response: null, error: null }
  try {
    const res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
    const text = await res.text()
    let parsed: unknown = null
    if (text) { try { parsed = JSON.parse(text) } catch { parsed = text } }
    rec.status = res.status
    rec.ms = Math.round(performance.now() - started)
    rec.response = parsed
    if (!res.ok) {
      const err = toApiError(res.status, parsed)
      rec.error = err.code + ': ' + err.message
      record(rec)
      throw err
    }
    record(rec)
    return parsed as T
  } catch (e) {
    if (e instanceof ApiError) throw e
    rec.ms = Math.round(performance.now() - started)
    rec.error = e instanceof Error ? e.message : String(e)
    record(rec)
    throw new ApiError(0, 'NETWORK', rec.error)
  }
}

const q = (params: Record<string, string | number | undefined>) => {
  const s = Object.entries(params).filter(([, v]) => v !== undefined && v !== '').map(([k, v]) => k + '=' + encodeURIComponent(String(v))).join('&')
  return s ? '?' + s : ''
}

export const api = {
  system: () => request<SystemView>('GET', '/api/system', undefined, { withUser: false }),
  setClock: (today: string | null) => request<SystemView>('POST', '/api/system/clock', { today }),
  users: () => request<UserView[]>('GET', '/api/directory/users'),
  teams: () => request<TeamView[]>('GET', '/api/directory/teams'),
  team: (id: string) => request<TeamDetailView>('GET', '/api/directory/teams/' + id),
  me: () => request<MeView>('GET', '/api/me'),
  permissions: (userId: string, teamId: string) => request<PermissionView>('GET', '/api/permissions' + q({ userId, teamId })),
  startRun: (teamId: string) => request<{ id: string }>('POST', '/api/teams/' + teamId + '/forecast-runs'),
  runs: (teamId: string, limit = 20) => request<RunSummary[]>('GET', '/api/teams/' + teamId + '/forecast-runs' + q({ limit })),
  forecast: (teamId: string, from?: string, to?: string) => request<CurrentDayForecast[]>('GET', '/api/teams/' + teamId + '/forecast' + q({ from, to })),
  accuracy: (teamId: string, from?: string, to?: string) => request<AccuracyResult>('GET', '/api/teams/' + teamId + '/accuracy' + q({ from, to })),
  run: (id: string) => request<RunView>('GET', '/api/forecast-runs/' + id),
  progress: (id: string) => request<RunProgress>('GET', '/api/forecast-runs/' + id + '/progress'),
  narrate: (id: string, language: string, model?: string) => request<NarrationStarted>('POST', '/api/forecast-runs/' + id + '/narratives', { language, model: model || null }),
  narrative: (id: string, lang: string) => request<NarrativeView>('GET', '/api/forecast-runs/' + id + '/narratives/' + lang),
  copilot: () => request<CopilotStatus>('GET', '/api/me/copilot'),
  tokenState: () => request<TokenState>('GET', '/api/me/github-token'),
  putToken: (token: string) => request<void>('PUT', '/api/me/github-token', { token }),
  deleteToken: () => request<void>('DELETE', '/api/me/github-token'),
  /** For the reference page's "try it": any GET path as written. */
  getRaw: (path: string) => request<unknown>('GET', path),
}
